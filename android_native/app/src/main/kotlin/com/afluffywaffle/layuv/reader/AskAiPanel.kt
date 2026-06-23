package com.afluffywaffle.layuv.reader

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import com.afluffywaffle.layuv.R
import com.afluffywaffle.layuv.ai.AiMessage
import com.afluffywaffle.layuv.ai.AiProviderFactory
import com.afluffywaffle.layuv.ai.AiResult
import com.afluffywaffle.layuv.ai.SecureKeyStore
import com.afluffywaffle.layuv.docx.DocxFromText
import com.afluffywaffle.layuv.docx.DocxStore
import com.afluffywaffle.layuv.docx.ManuscriptSerializer
import com.afluffywaffle.layuv.docx.model.AiTurn
import java.io.File

/**
 * The in-reader "Ask AI" conversation panel (top half of the screen). The reader
 * stays primary below it, so the chapter and its inline annotations remain visible
 * for reference. The conversation is seeded with the chapter text + annotations,
 * persisted in the chapter DOCX (`leamh/aichat.json`) so it suspends/resumes, and
 * an accepted reply can be saved as a clean, annotation-less new draft.
 *
 * E-ink: no animations; the transcript is the one scrollable surface, driven
 * primarily by discrete "screen flip" buttons (one pane-height per tap) with a
 * clean redraw, mirroring the reader's page-turn philosophy.
 */
class AskAiPanel(
    private val activity: Activity,
    private val onHide: () -> Unit,
    private val onOpenDraft: (File) -> Unit,
) : LinearLayout(activity) {

    private val main = Handler(Looper.getMainLooper())
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "ai-chat").apply { isDaemon = true }
    }

    private var book: OpenBook? = null
    private val messages = mutableListOf<AiTurn>()
    private var sending = false
    private var continuationCount = 0

    private val transcript: LinearLayout
    private val scroll: ScrollView
    private val input: EditText
    private val sendButton: TextView
    private val banner: LinearLayout

    val isOpen: Boolean get() = visibility == View.VISIBLE

    init {
        orientation = VERTICAL
        setBackgroundColor(ReaderTheme.PAPER)
        visibility = View.GONE

        addView(buildHeader(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(hairline(), LinearLayout.LayoutParams(MATCH_PARENT, dp(1f)))

        transcript = LinearLayout(activity).apply { orientation = VERTICAL }
        scroll = ScrollView(activity).apply {
            isFillViewport = true
            addView(transcript, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        banner = LinearLayout(activity).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
            visibility = View.GONE
        }
        addView(banner, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        addView(hairline(), LinearLayout.LayoutParams(MATCH_PARENT, dp(1f)))

        val inputRow = LinearLayout(activity).apply {
            orientation = HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
        }
        input = EditText(activity).apply {
            typeface = ReaderTheme.body(activity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ReaderTheme.BODY_TEXT_SP)
            setTextColor(ReaderTheme.INK_87)
            setHintTextColor(0xFF9E9A92.toInt())
            setHighlightColor(android.graphics.Color.argb(60, 0, 0, 0))
            hint = "Discuss, or just tap Send for a rewrite…"
            minLines = 1
            maxLines = 4
            gravity = Gravity.TOP or Gravity.START
            background = activity.popupBackground()
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            minimumHeight = dp(48f)
        }
        sendButton = TextView(activity).apply {
            typeface = ReaderTheme.chromeBold(activity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            text = "Send"
            gravity = Gravity.CENTER
            setPadding(dp(20f), dp(10f), dp(20f), dp(10f))
            minimumHeight = dp(48f)
            setOnTouchListener(PenTapListener(activity) { onSend() })
        }
        styleSendButton()
        inputRow.addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        inputRow.addView(sendButton, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).also { it.leftMargin = dp(8f) })
        addView(inputRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    // ---- Public API (host = ReaderActivity) ---------------------------------

    /** Bind to [b] (loading its persisted transcript) and show the panel. */
    fun open(b: OpenBook) {
        if (b.file == null) {
            toast("This document is read-only — open a writable copy to use Ask AI.")
            return
        }
        book = b
        continuationCount = 0
        visibility = View.VISIBLE
        loadTranscript(b)
    }

    fun close() {
        hideKeyboard()
        visibility = View.GONE
    }

    /** Called by the host when the open document changes while the panel is up. */
    fun onBookChanged(b: OpenBook) {
        if (!isOpen) { book = b; return }
        open(b)
    }

    fun destroy() {
        io.shutdownNow()
    }

    // ---- Transcript load / persist ------------------------------------------

    private fun loadTranscript(b: OpenBook) {
        val file = b.file ?: return
        renderLoading()
        io.execute {
            val loaded = try { DocxStore.readAiChat(file.readBytes()) } catch (e: Exception) { emptyList() }
            main.post {
                if (book !== b) return@post
                messages.clear()
                messages.addAll(loaded)
                render()
                scrollToBottom()
            }
        }
    }

    /** Persist the transcript into the chapter DOCX via the shared write queue. */
    private fun persist() {
        val file = book?.file ?: return
        val snapshot = messages.toList()
        DocxWriteQueue.submit(
            file = file,
            transform = { bytes -> DocxStore.writeAiChat(bytes, snapshot) },
        )
    }

    // ---- Send / conversation ------------------------------------------------

    private fun onSend() {
        if (sending) return
        val b = book ?: return
        val typed = input.text.toString().trim()
        if (messages.isEmpty()) {
            // Consent is captured upstream — Help & About → Ask AI must be accepted before a key can
            // be saved — so the panel sends directly.
            val seed = buildSeed(b, typed)
            input.setText("")
            appendUser(seed)
            callProvider(b)
        } else {
            if (typed.isEmpty()) return
            input.setText("")
            continuationCount = 0
            appendUser(typed)
            callProvider(b)
        }
    }

    private fun buildSeed(b: OpenBook, typed: String): String {
        val base = ManuscriptSerializer.buildPrompt(
            b.doc.plainText,
            b.doc.annotations.map { it.annotation },
        )
        return if (typed.isEmpty()) base else "$base\n\nAuthor's note for this revision: $typed"
    }

    private fun appendUser(text: String) {
        messages.add(AiTurn(AiTurn.ROLE_USER, text))
        persist()
        render()
        scrollToBottom()
    }

    private fun callProvider(b: OpenBook) {
        val key = SecureKeyStore.read(activity)
        if (key.isNullOrBlank()) {
            showBanner("Set up AI in Help & About → Ask AI.", actionLabel = "Open") {
                activity.startActivity(
                    android.content.Intent(activity, HelpActivity::class.java)
                        .putExtra(HelpActivity.EXTRA_PAGE, "Ask AI"),
                )
            }
            return
        }
        setSending(true)
        val history = messages.map { AiMessage(it.role, it.text) }
        val provider = AiProviderFactory.current(activity)
        io.execute {
            val res = provider.send(key, history)
            main.post {
                if (book !== b) return@post
                setSending(false)
                when (res) {
                    is AiResult.Ok -> {
                        messages.add(AiTurn(AiTurn.ROLE_ASSISTANT, res.text, res.truncated))
                        persist()
                        render()
                        scrollToBottom()
                    }
                    is AiResult.Error -> showBanner(res.userMessage, actionLabel = "Retry") { callProvider(b) }
                }
            }
        }
    }

    private fun continueTurn(b: OpenBook) {
        if (continuationCount >= MAX_CONTINUATIONS) {
            toast("Reached the continuation limit.")
            return
        }
        continuationCount++
        appendUser(CONTINUE_PROMPT)
        callProvider(b)
    }

    // ---- Save as draft ------------------------------------------------------

    private fun saveAsDraft(assistantIndex: Int) {
        val b = book ?: return
        val file = b.file ?: return
        val text = stitchedAnswer(assistantIndex)
        val base = file.nameWithoutExtension
        promptFilename("$base (AI draft).docx") { name ->
            val safe = if (name.endsWith(".docx", ignoreCase = true)) name else "$name.docx"
            val outFile = File(file.parentFile, safe)
            io.execute {
                try {
                    val src = file.readBytes() // freshest on-disk structure
                    val bytes = DocxFromText.build(src, text)
                    DocxWriteQueue.writeAtomicDurable(outFile, bytes)
                    main.post {
                        toast("Saved ${outFile.name}")
                        close()
                        onOpenDraft(outFile)
                    }
                } catch (e: Exception) {
                    main.post { toast("Couldn't save the draft.") }
                }
            }
        }
    }

    /** Stitch an assistant answer back together across any Continue fragments. */
    private fun stitchedAnswer(index: Int): String {
        var text = messages[index].text
        var j = index
        while (j - 2 >= 0 &&
            messages[j - 1].role == AiTurn.ROLE_USER && messages[j - 1].text == CONTINUE_PROMPT &&
            messages[j - 2].role == AiTurn.ROLE_ASSISTANT
        ) {
            text = messages[j - 2].text + text
            j -= 2
        }
        return text
    }

    // ---- Rendering ----------------------------------------------------------

    private fun renderLoading() {
        transcript.removeAllViews()
        transcript.addView(centeredHint("Loading…"))
        banner.visibility = View.GONE
    }

    private fun render() {
        transcript.removeAllViews()
        banner.visibility = View.GONE

        if (messages.isEmpty()) {
            transcript.addView(centeredHint(
                "Tap Send to ask Claude to rewrite this chapter addressing your annotations.\n" +
                    "Then discuss, and save any reply as a new draft.",
            ))
            return
        }

        messages.forEachIndexed { i, turn ->
            if (i > 0) transcript.addView(activity.rowDivider())
            when (turn.role) {
                AiTurn.ROLE_ASSISTANT -> transcript.addView(assistantTurn(turn, i))
                else -> transcript.addView(userTurn(turn, i))
            }
        }

        // Interrupted/awaiting state: last turn is a user message with no reply yet.
        if (!sending && messages.last().role == AiTurn.ROLE_USER) {
            showBanner("No reply yet.", actionLabel = "Get reply") { book?.let { callProvider(it) } }
        }
    }

    private fun userTurn(turn: AiTurn, index: Int): View {
        val col = turnColumn("You")
        val body = if (index == 0) {
            "Asked Claude to rewrite this chapter addressing your annotations."
        } else {
            turn.text
        }
        col.addView(TextView(activity).apply {
            text = body
            typeface = if (index == 0) ReaderTheme.bodyItalic(activity) else ReaderTheme.body(activity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ReaderTheme.BODY_TEXT_SP)
            setTextColor(if (index == 0) ReaderTheme.INK_54 else ReaderTheme.INK_87)
        })
        return col
    }

    private fun assistantTurn(turn: AiTurn, index: Int): View {
        val col = turnColumn("Claude")
        // Grey left bar marks assistant turns (greyscale, never colour).
        col.addView(TextView(activity).apply {
            text = turn.text
            typeface = ReaderTheme.body(activity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ReaderTheme.BODY_TEXT_SP)
            setTextColor(ReaderTheme.INK_87)
            setPadding(dp(12f), dp(2f), 0, dp(2f))
            background = leftBar()
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val actions = LinearLayout(activity).apply { orientation = HORIZONTAL }
        actions.addView(activity.textButton("Save as draft", bold = true) { saveAsDraft(index) })
        if (turn.truncated) {
            actions.addView(activity.textButton("Continue", bold = true) { book?.let { continueTurn(it) } })
        }
        col.addView(actions, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        return col
    }

    private fun turnColumn(role: String): LinearLayout {
        val col = LinearLayout(activity).apply {
            orientation = VERTICAL
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
        }
        col.addView(TextView(activity).apply {
            text = role
            typeface = ReaderTheme.chrome(activity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(ReaderTheme.INK_45)
            setPadding(0, 0, 0, dp(2f))
        })
        return col
    }

    private fun centeredHint(text: String) = TextView(activity).apply {
        this.text = text
        typeface = ReaderTheme.body(activity)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(ReaderTheme.INK_38)
        gravity = Gravity.CENTER
        setPadding(dp(24f), dp(28f), dp(24f), dp(28f))
    }

    private fun leftBar(): Drawable = object : Drawable() {
        private val paint = Paint().apply { color = ReaderTheme.INK_38; style = Paint.Style.FILL }
        private val w = dp(3f).toFloat()
        override fun draw(c: Canvas) { c.drawRect(0f, 0f, w, bounds.height().toFloat(), paint) }
        override fun setAlpha(a: Int) = Unit
        override fun setColorFilter(f: ColorFilter?) = Unit
        @Suppress("DEPRECATION") override fun getOpacity() = PixelFormat.TRANSPARENT
    }

    // ---- Banner (errors / retry / awaiting) ---------------------------------

    private fun showBanner(message: String, actionLabel: String?, action: (() -> Unit)?) {
        banner.removeAllViews()
        banner.addView(TextView(activity).apply {
            text = message
            typeface = ReaderTheme.body(activity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(ReaderTheme.INK_54)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (actionLabel != null && action != null) {
            banner.addView(activity.textButton(actionLabel, bold = true) {
                banner.visibility = View.GONE
                action()
            })
        }
        banner.visibility = View.VISIBLE
    }

    // ---- Header + screen-flip nav -------------------------------------------

    private fun buildHeader(): View {
        val header = LinearLayout(activity).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(6f), dp(8f), dp(6f))
        }
        header.addView(TextView(activity).apply {
            text = "Ask AI"
            typeface = ReaderTheme.chromeBold(activity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(ReaderTheme.INK_87)
        })
        header.addView(Space(activity), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        header.addView(flipButton("▲") { flip(-1) })
        header.addView(flipButton("▼") { flip(+1) })
        header.addView(activity.textButton("New", bold = true) { newConversation() })
        header.addView(activity.textButton("Hide", bold = true) { onHide() })
        return header
    }

    private fun flipButton(glyph: String, onTap: () -> Unit): View = TextView(activity).apply {
        text = glyph
        typeface = ReaderTheme.chromeBold(activity)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(ReaderTheme.INK_87)
        gravity = Gravity.CENTER
        minimumWidth = dp(48f)
        minimumHeight = dp(48f)
        setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
        setOnTouchListener(PenTapListener(activity, onTap = onTap))
    }

    /** Screen flip: jump one transcript-pane height (slight overlap), then clean redraw. */
    private fun flip(dir: Int) {
        val step = (scroll.height - dp(24f)).coerceAtLeast(dp(48f))
        scroll.scrollBy(0, dir * step)
        cleanRedraw()
    }

    private fun scrollToBottom() {
        scroll.post {
            scroll.fullScroll(View.FOCUS_DOWN)
            cleanRedraw()
        }
    }

    /** A single clean frame after a discrete move, mirroring the reader's page-turn refresh. */
    private fun cleanRedraw() {
        if (RattaEink.available(activity)) RattaEink.sendOneFullFrame(activity) else invalidate()
    }

    private fun newConversation() {
        if (messages.isEmpty()) return
        LeamhDialog.confirm(
            context = activity,
            message = "Start a new conversation? The current one will be cleared.",
            positiveLabel = "New conversation",
            negativeLabel = "Cancel",
            onConfirm = {
                messages.clear()
                continuationCount = 0
                persist()
                render()
            },
        )
    }

    // ---- Sending state ------------------------------------------------------

    private fun setSending(value: Boolean) {
        sending = value
        input.isEnabled = !value
        sendButton.isEnabled = !value
        sendButton.text = if (value) "Working…" else "Send"
        styleSendButton()
        // Static working notice (no spinner — e-ink rule).
        if (value) showBanner("Contacting Claude… this can take up to a minute.", null, null)
    }

    private fun styleSendButton() {
        val enabled = sendButton.isEnabled
        sendButton.setTextColor(if (enabled) ReaderTheme.PAPER else ReaderTheme.INK_54)
        sendButton.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = ReaderTheme.dp(activity, ReaderTheme.RADIUS_BTN)
            setColor(if (enabled) ReaderTheme.INK_87 else ReaderTheme.FILL_06)
            setStroke(dp(1f), ReaderTheme.INK_26)
        }
    }

    // ---- Filename prompt ----------------------------------------------------

    private fun promptFilename(default: String, onName: (String) -> Unit) {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setElevation(0f)
        val root = LinearLayout(activity).apply {
            orientation = VERTICAL
            setBackgroundResource(R.drawable.picker_bg)
            val p = dp(20f)
            setPadding(p, p, p, dp(12f))
        }
        root.addView(TextView(activity).apply {
            text = "Save draft as"
            typeface = ReaderTheme.body(activity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(ReaderTheme.INK_87)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                .also { it.bottomMargin = dp(12f) }
        })
        val field = EditText(activity).apply {
            typeface = ReaderTheme.body(activity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(ReaderTheme.INK_87)
            setHighlightColor(android.graphics.Color.argb(60, 0, 0, 0))
            inputType = InputType.TYPE_CLASS_TEXT
            setText(default)
            setSelection(0, (default.length - ".docx".length).coerceAtLeast(0))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                .also { it.bottomMargin = dp(16f) }
        }
        root.addView(field)
        val btnRow = LinearLayout(activity).apply { orientation = HORIZONTAL; gravity = Gravity.END }
        btnRow.addView(dialogButton("Cancel", ReaderTheme.INK_45) { dialog.dismiss() })
        btnRow.addView(Space(activity), LinearLayout.LayoutParams(dp(4f), 1))
        btnRow.addView(dialogButton("Save", ReaderTheme.INK_87) {
            val name = field.text.toString().trim()
            if (name.isEmpty()) { toast("Enter a filename."); return@dialogButton }
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(field.windowToken, 0)
            dialog.dismiss()
            onName(name)
        })
        root.addView(btnRow)
        dialog.setContentView(root)
        dialog.window?.setLayout(dp(320f), WindowManager.LayoutParams.WRAP_CONTENT)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        field.requestFocus()
    }

    private fun dialogButton(label: String, color: Int, onTap: () -> Unit): TextView =
        TextView(activity).apply {
            text = label
            typeface = ReaderTheme.chromeBold(activity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(color)
            setPadding(dp(12f), dp(14f), dp(12f), dp(14f))
            setOnTouchListener(PenTapListener(activity, onTap = onTap))
        }

    // ---- Small helpers ------------------------------------------------------

    /** dp→px. A member (not the Context extension) since this class is a View, not a Context. */
    private fun dp(v: Float): Int = ReaderTheme.dp(activity, v).toInt()

    private fun hairline(): View = View(activity).apply { setBackgroundColor(ReaderTheme.INK_12) }

    private fun hideKeyboard() {
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun toast(msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val MAX_CONTINUATIONS = 3
        private const val CONTINUE_PROMPT =
            "Continue the rewrite from exactly where the previous message was cut off. " +
                "Do not repeat any text; continue mid-sentence if needed."
    }
}

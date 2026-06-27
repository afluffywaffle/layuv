package com.afluffywaffle.layuv.reader

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
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
import android.widget.ImageView
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
import com.afluffywaffle.layuv.docx.RewriteProtocol
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
    /** Ids of ink annotations referenced by the seed, so their PNGs can ride the first turn as images. */
    private var seedInkIds: List<String> = emptyList()

    private val transcript: LinearLayout
    private val scroll: ScrollView
    private val input: EditText
    private val sendButton: TextView
    private val banner: LinearLayout
    private lateinit var leftFlipStrip: LinearLayout
    private lateinit var rightFlipStrip: LinearLayout

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
        // Transcript flanked by ▲/▼ flip strips; only the user's chosen side shows (handedness).
        leftFlipStrip = buildFlipStrip()
        rightFlipStrip = buildFlipStrip()
        val transcriptRow = LinearLayout(activity).apply { orientation = HORIZONTAL }
        // Strips fill the row height so their gravity=BOTTOM groups the buttons low, in reach.
        transcriptRow.addView(leftFlipStrip, LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT))
        transcriptRow.addView(scroll, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        transcriptRow.addView(rightFlipStrip, LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT))
        addView(transcriptRow, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        applyFlipSide()

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
            hint = "Add optional instructions, or just tap Send…"
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
                seedInkIds = emptyList() // a resumed conversation has no live ink to attach
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
            val send = {
                val note = input.text.toString().trim()
                input.setText("")
                appendUser(buildSeed(b, note))
                callProvider(b)
            }
            // Heads-up if this is manuscript-sized, not a chapter: the rewrite would truncate at the
            // per-call output cap and come back in pieces. Inline banner (not a popup) with a
            // "Send anyway" action — the input text is kept until they confirm.
            if (b.doc.plainText.length > LARGE_INPUT_CHARS) {
                showBanner(
                    "This looks like a large document, not a single chapter — the rewrite may come back " +
                        "in pieces and stop before the end. For a complete rewrite, send one chapter at a time.",
                    actionLabel = "Send anyway",
                    action = send,
                )
            } else {
                send()
            }
        } else {
            if (typed.isEmpty()) return
            input.setText("")
            continuationCount = 0
            appendUser(typed)
            callProvider(b)
        }
    }

    private fun buildSeed(b: OpenBook, typed: String): String {
        val prompt = ManuscriptSerializer.buildPrompt(
            b.doc.plainText,
            b.doc.annotations.map { it.annotation },
        )
        seedInkIds = prompt.inkAnnotationIds
        return if (typed.isEmpty()) prompt.text else "${prompt.text}\n\nAuthor's note for this revision: $typed"
    }

    private fun appendUser(text: String) {
        messages.add(AiTurn(AiTurn.ROLE_USER, text))
        persist()
        render()
        scrollToBottom()
    }

    // omitInk = true on the one-shot fallback after a text-only model rejects the ink
    // images: re-send without them, substituting a placeholder so the rest still rewrites.
    private fun callProvider(b: OpenBook, omitInk: Boolean = false) {
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
        val provider = AiProviderFactory.current(activity)
        val inkIds = seedInkIds                          // ink ids referenced by the seed turn
        val turns = messages.map { it.role to it.text }  // snapshot off the main thread
        io.execute {
            // Load the handwritten-note PNGs (read by vision models) and attach them to the seed turn.
            // On the text-only fallback (omitInk) we skip the images entirely.
            val imgs = if (omitInk || inkIds.isEmpty()) emptyList() else {
                val docBytes = try { b.file?.readBytes() } catch (e: Exception) { null } ?: b.bytes
                inkIds.mapNotNull { DocxStore.readInkPng(docBytes, it) }
            }
            val history = turns.mapIndexed { i, pair ->
                when {
                    i == 0 && imgs.isNotEmpty() -> AiMessage(pair.first, pair.second, imgs)
                    // Fallback: the seed referenced ink notes as images, but they were dropped —
                    // tell the model so it doesn't expect an attachment it can't see.
                    i == 0 && omitInk && inkIds.isNotEmpty() -> AiMessage(pair.first, pair.second + INK_OMITTED_NOTE)
                    else -> AiMessage(pair.first, pair.second)
                }
            }
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
                    // Text-only model rejected the ink images: re-send once without them.
                    // If we were ALREADY omitting (shouldn't reach a vision error), show it as an error.
                    is AiResult.NeedsTextOnlyRetry ->
                        if (!omitInk) {
                            showBanner(res.userMessage, actionLabel = null, action = null)
                            callProvider(b, omitInk = true)
                        } else {
                            showBanner(res.userMessage, actionLabel = "Retry") { callProvider(b, omitInk = true) }
                        }
                    is AiResult.Error -> showBanner(res.userMessage, actionLabel = "Retry") { callProvider(b, omitInk) }
                }
            }
        }
    }

    private fun continueTurn(b: OpenBook) {
        if (continuationCount >= MAX_CONTINUATIONS) {
            // Persistent banner, not a toast — the limit is too easy to miss otherwise.
            showBanner(
                "This is longer than a chapter — send one chapter at a time for a complete rewrite.",
                actionLabel = null,
                action = null,
            )
            return
        }
        continuationCount++
        appendUser(CONTINUE_PROMPT)
        callProvider(b)
    }

    // ---- Save as draft ------------------------------------------------------

    private fun saveRewrite(rewriteText: String, fileName: String) {
        val b = book ?: return
        val file = b.file ?: return
        val safe = if (fileName.endsWith(".docx", ignoreCase = true)) fileName else "$fileName.docx"
        io.execute {
            try {
                // A draft is always a NEW file — never overwrite an existing one.
                val outFile = uniqueFile(file.parentFile, safe)
                val src = file.readBytes() // freshest on-disk structure
                val bytes = DocxFromText.build(src, rewriteText)
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

    /** Resolve to a non-colliding file: if the name is taken, append " (2)", " (3)", … */
    private fun uniqueFile(dir: File?, fileName: String): File {
        val candidate = File(dir, fileName)
        if (!candidate.exists()) return candidate
        val stem = fileName.substringBeforeLast(".docx", fileName)
        var n = 2
        while (true) {
            val next = File(dir, "$stem ($n).docx")
            if (!next.exists()) return next
            n++
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

        val firstTurn = messages.isEmpty()
        // Make the send/receive model obvious: first turn auto-includes the whole chapter.
        input.hint = if (firstTurn) "Add optional instructions, or just tap Send…" else "Reply to ${providerName()}…"
        if (!sending) sendButton.text = if (firstTurn) "Send chapter" else "Send"

        if (firstTurn) {
            val name = book?.displayName ?: "this chapter"
            val n = book?.doc?.annotations?.size ?: 0
            val annPart = if (n > 0) " and your $n annotation" + (if (n == 1) "" else "s") else ""
            transcript.addView(centeredHint(
                "Tap \"Send chapter\" to send the full text of \"$name\"$annPart to ${providerName()} " +
                    "for a rewrite.\n\nYou don't attach anything — the whole chapter is included " +
                    "automatically. Add optional instructions below first if you like.",
            ))
            return
        }

        messages.forEachIndexed { i, turn ->
            if (turn.role == AiTurn.ROLE_USER && turn.text == CONTINUE_PROMPT) return@forEachIndexed
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
            "Sent the full chapter and your annotations to ${providerName()} for a rewrite."
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
        val col = turnColumn(providerName())

        val isContinuation = index >= 2 &&
            messages[index - 1].role == AiTurn.ROLE_USER && messages[index - 1].text == CONTINUE_PROMPT &&
            messages[index - 2].role == AiTurn.ROLE_ASSISTANT

        // Split the reply: discussion shows inline; a rewrite becomes a save-as-draft card
        // (the chapter is never dumped into the chat).
        val convo: String
        val rewrite: String?
        if (isContinuation) {
            convo = ""
            val stitched = stitchedAnswer(index)
            rewrite = RewriteProtocol.parse(stitched).rewrite ?: stitched
        } else {
            val p = RewriteProtocol.parse(turn.text)
            when {
                p.rewrite != null -> { convo = p.conversation; rewrite = p.rewrite }
                // Safety net: an unmarked but chapter-length reply is almost certainly a rewrite.
                p.conversation.length >= REWRITE_FALLBACK_CHARS -> { convo = ""; rewrite = p.conversation }
                else -> { convo = p.conversation; rewrite = null }
            }
        }

        if (convo.isNotEmpty()) col.addView(messageBody(convo))

        if (rewrite != null) {
            if (convo.isNotEmpty()) {
                col.addView(activity.rowDivider().also { (it.layoutParams as LinearLayout.LayoutParams).topMargin = dp(10f) })
            }
            if (turn.truncated) {
                col.addView(noteLine("The rewrite was cut off — tap Continue to get the rest."))
                col.addView(pillRow(activity.pillButton("Continue", filled = false) { book?.let { continueTurn(it) } }))
            } else {
                col.addView(rewriteCard(rewrite))
            }
        } else if (convo.isEmpty()) {
            col.addView(messageBody(turn.text)) // fallback: render the raw reply
        }
        return col
    }

    private fun messageBody(text: String): View = TextView(activity).apply {
        this.text = text
        typeface = ReaderTheme.body(activity)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ReaderTheme.BODY_TEXT_SP)
        setTextColor(ReaderTheme.INK_87)
        setPadding(dp(12f), dp(2f), 0, dp(2f))
        background = leftBar()
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }

    private fun noteLine(text: String): View = TextView(activity).apply {
        this.text = text
        typeface = ReaderTheme.body(activity)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(ReaderTheme.INK_54)
        setPadding(0, dp(2f), 0, dp(2f))
    }

    private fun pillRow(vararg pills: View): View = LinearLayout(activity).apply {
        orientation = HORIZONTAL
        setPadding(0, dp(8f), 0, 0)
        pills.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).also { if (i > 0) it.leftMargin = dp(8f) })
        }
    }

    /** A rewrite is offered as a draft (file name shown) — never rendered into the chat. */
    private fun rewriteCard(rewrite: String): View {
        val name = proposeDraftName()
        return LinearLayout(activity).apply {
            orientation = VERTICAL
            setPadding(0, dp(6f), 0, 0)
            addView(noteLine("Rewrite ready — save it as a new draft:"))
            addView(TextView(activity).apply {
                text = name
                typeface = ReaderTheme.chromeBold(activity)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(ReaderTheme.INK_87)
                setPadding(0, dp(2f), 0, dp(4f))
            })
            addView(pillRow(
                activity.pillButton("Save & Open", filled = true) { saveRewrite(rewrite, name) },
                activity.textButton("Change name…", bold = true) { promptFilename(name) { saveRewrite(rewrite, it) } },
            ))
        }
    }

    /** "<root>_draft_v<N>.docx" beside the source; the original counts as v1, so the first draft is v2. */
    private fun proposeDraftName(): String {
        val file = book?.file ?: return "draft_v2.docx"
        val root = file.nameWithoutExtension
            .replace(Regex("_draft_v\\d+$", RegexOption.IGNORE_CASE), "")
            .lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "chapter" }
        val existing = file.parentFile?.listFiles()?.mapNotNull { f ->
            Regex("^${Regex.escape(root)}_draft_v(\\d+)\\.docx$", RegexOption.IGNORE_CASE)
                .find(f.name)?.groupValues?.get(1)?.toIntOrNull()
        } ?: emptyList()
        val next = (existing.maxOrNull() ?: 1) + 1
        return "${root}_draft_v$next.docx"
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
        header.addView(activity.textButton("⇆", bold = true) { toggleFlipSide() }.also {
            it.contentDescription = "Move the scroll buttons to the other side"
        })
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

    /** Vertical ▲/▼ flip strip + an Expand button, grouped at the bottom of the chosen side. */
    private fun buildFlipStrip(): LinearLayout = LinearLayout(activity).apply {
        orientation = VERTICAL
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        setPadding(dp(2f), 0, dp(2f), dp(8f))
        addView(flipButton("▲") { flip(-1) })
        addView(flipButton("▼") { flip(+1) })
        addView(expandButton())
    }

    /** Expand: open the latest reply full screen (read-only) — sits below the flip controls.
     *  Uses the same outward-arrows icon the comments pane uses to expand. */
    private fun expandButton(): View = ImageView(activity).apply {
        setImageResource(R.drawable.ic_expand)
        setColorFilter(ReaderTheme.INK_87)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        minimumWidth = dp(48f)
        minimumHeight = dp(48f)
        setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
        contentDescription = "Open the latest reply full screen"
        setOnTouchListener(PenTapListener(activity, onTap = ::expandLatest))
    }

    private fun expandLatest() {
        val last = messages.lastOrNull { it.role == AiTurn.ROLE_ASSISTANT }
        if (last == null) {
            toast("No reply yet.")
            return
        }
        val p = RewriteProtocol.parse(last.text)
        val text = when {
            p.rewrite != null && p.conversation.isNotEmpty() -> p.conversation + "\n\n" + p.rewrite
            p.rewrite != null -> p.rewrite
            else -> p.conversation.ifEmpty { last.text }
        }
        activity.startActivity(
            Intent(activity, AiReplyActivity::class.java)
                .putExtra(AiReplyActivity.EXTRA_TITLE, "${providerName()} — latest reply")
                .putExtra(AiReplyActivity.EXTRA_TEXT, text),
        )
    }

    private fun flipPrefs() = activity.getSharedPreferences("leamh", Context.MODE_PRIVATE)

    private fun applyFlipSide() {
        val left = flipPrefs().getString(KEY_FLIP_SIDE, "right") == "left"
        leftFlipStrip.visibility = if (left) View.VISIBLE else View.GONE
        rightFlipStrip.visibility = if (left) View.GONE else View.VISIBLE
    }

    private fun toggleFlipSide() {
        val left = flipPrefs().getString(KEY_FLIP_SIDE, "right") == "left"
        flipPrefs().edit().putString(KEY_FLIP_SIDE, if (left) "right" else "left").apply()
        applyFlipSide()
    }

    private fun providerName() = AiProviderFactory.displayName(activity)

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
                seedInkIds = emptyList()
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
        if (value) showBanner("Contacting ${providerName()}… this can take up to a minute.", null, null)
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
        private const val KEY_FLIP_SIDE = "ai_flip_side"
        // An unmarked reply at least this long is treated as a rewrite (model-omitted markers).
        private const val REWRITE_FALLBACK_CHARS = 1200
        // Above this many chars the chapter is likely a whole manuscript — warn before sending.
        private const val LARGE_INPUT_CHARS = 45000
        private const val MAX_CONTINUATIONS = 3
        // Appended to the seed turn when a text-only model forced us to drop the ink images.
        private const val INK_OMITTED_NOTE =
            "\n\n[Note: this model can't read images, so the handwritten note(s) referenced above " +
                "were not included. Work from the text only.]"
        private const val CONTINUE_PROMPT =
            "Continue the rewrite from exactly where the previous message was cut off. " +
                "Do not repeat any text; continue mid-sentence if needed."
    }
}

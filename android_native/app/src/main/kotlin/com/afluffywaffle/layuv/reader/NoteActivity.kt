package com.afluffywaffle.layuv.reader

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.ActionMode
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.PopupWindow
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import com.afluffywaffle.layuv.R
import com.afluffywaffle.layuv.docx.model.AnnotationTag
import com.afluffywaffle.layuv.docx.model.AnnotationTool
import com.afluffywaffle.layuv.docx.model.ThreadEntry
import com.afluffywaffle.layuv.docx.model.newId
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full annotation editor — tool + tag selector, quote box, a compose field that
 * feeds a comment thread, and a two-tab bottom pane (Ink / Thread). Mirrors
 * Flutter's AnnotationPanel (lib/reader/annotation_panel.dart) plus the native
 * thread/ink pane.
 *
 * Extras in:
 *   EXTRA_NOTE          — existing note text (optional, edit flow / legacy)
 *   EXTRA_THREAD_JSON   — existing thread entries as JSON (optional, edit flow)
 *   EXTRA_TIMESTAMP     — annotation timestamp (millis), for legacy-note synthesis
 *   EXTRA_SELECTED_TEXT — the highlighted passage
 *   EXTRA_INITIAL_TOOL  — AnnotationTool.name to pre-select (comment → highlight)
 *   EXTRA_INITIAL_INK_ID— annotation id whose ink to preload (edit flow)
 *
 * Extras out (RESULT_OK):
 *   EXTRA_NOTE         — first thread entry text (kept for backward compatibility)
 *   EXTRA_THREAD_JSON  — full updated thread as JSON
 *   EXTRA_RESULT_TOOL  — AnnotationTool.name chosen at Save
 *   EXTRA_RESULT_TAG   — AnnotationTag.name (nullable)
 *   EXTRA_INK_ID       — String pre-allocated ID (only if ink was captured)
 */
class NoteActivity : Activity() {

    private enum class Tab { INK, THREAD }

    private lateinit var composeField: EditText
    private lateinit var composeButton: TextView
    private lateinit var paneContainer: LinearLayout
    private lateinit var inkTab: LinearLayout
    private lateinit var threadTab: LinearLayout
    private lateinit var bottomFrame: FrameLayout
    private var entryDetailOverlay: View? = null
    private var entryDetailLayoutListener: android.view.ViewTreeObserver.OnGlobalLayoutListener? = null

    // Toolbar paste affordance + its gating. Paste is greyed when the clipboard
    // has no pasteable text, and disabled right after a paste (no undo, so this
    // stops repeated dumping of the same chunk). It re-enables when the clipboard
    // changes — i.e. the next time the user copies something.
    private var pasteButton: ImageView? = null
    // The clipboard text last pasted (or seen at open). Paste stays disabled while
    // the clipboard still holds it, and re-enables when the clipboard changes (a
    // fresh copy). Seeded on first focus so the button starts quiet (faded) instead
    // of lit by whatever happened to be on the clipboard already.
    private var lastPastedClip: String? = null
    private var pasteSeeded = false

    // Full-screen compose overlay (P1d) — for writing a long reply while the
    // referenced passage/comment stays visible. Held so it can be synced + torn
    // down on pause. replyContextEntry is the comment a reply targets (else null,
    // in which case the highlighted passage is the reference).
    private var fullComposeOverlay: View? = null
    private var fullComposeField: EditText? = null
    private var replyContextEntry: ThreadEntry? = null

    private var selectedTool = AnnotationTool.highlight
    private var selectedTag: AnnotationTag? = null
    private var capturedInkBytes: ByteArray? = null
    private var capturedStrokeJson: String? = null
    private var inkId: String? = null
    private var selectedText = ""

    // Working comment thread. First entry's text mirrors the annotation's `note`.
    private val threadEntries = mutableListOf<ThreadEntry>()
    private var composeEditIndex = -1 // -1 = adding a new entry; else editing this index
    private var annotationTimestamp = 0L
    private var activeTab = Tab.THREAD
    private var threadPage = 0

    // Initial state, for unsaved-changes detection.
    private var initialThreadJson = "[]"
    private var inkDirty = false

    // Compose text to re-apply after the view is rebuilt (recreation restore).
    private var composePending = ""
    // Measured thread-pane height; the paginated row count derives from it.
    private var availablePaneHeight = 0

    // Derived from the user's font-size pref — set before buildUi() is called.
    private var bodySizeSp = ReaderTheme.BODY_TEXT_SP
    private var chromeSizeSp = 15f

    // Tool + tag controls live in the top toolbar (header) to maximise the
    // compose + comments space. The tool button shows the current tool; the tag
    // button shows '#' or the active tag's label. Both open a picker popup.
    private var toolButton: View? = null
    private var tagButton: FrameLayout? = null   // wrapper carries the corner-hint foreground
    private var tagLabel: TextView? = null       // the '#' / tag-label text inside the wrapper
    private var toolPickerPopup: PopupWindow? = null
    private var tagPickerPopup: PopupWindow? = null

    // Mirrors of the tag + paste controls inside the full-screen compose sheet, so
    // tag/paste are available while writing there too. Refreshed alongside the
    // toolbar copies; nulled when the sheet closes.
    private var fsTagButton: FrameLayout? = null
    private var fsTagLabel: TextView? = null
    private var fsPasteButton: ImageView? = null

    private val selectorTools = listOf(
        AnnotationTool.highlight,
        AnnotationTool.underline,
        AnnotationTool.doubleUnderline,
        AnnotationTool.strikethrough,
        AnnotationTool.bookmark,
    )

    private val tagLabels = mapOf(
        AnnotationTag.voice to "Voice",
        AnnotationTag.pacing to "Pacing",
        AnnotationTag.continuity to "Continuity",
        AnnotationTag.query to "Query",
    )
    private val tagPrompts = mapOf(
        AnnotationTag.voice to "This doesn't sound like [character] because…",
        AnnotationTag.pacing to "This section feels too [fast/slow] because…",
        AnnotationTag.continuity to "Possible inconsistency with…",
        AnnotationTag.query to "Question: …",
    )

    private val threadDateFormat by lazy { SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedText = intent.getStringExtra(EXTRA_SELECTED_TEXT) ?: ""

        // Sync font prefs so the panel matches the reader's current typography.
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        ReaderTheme.bodyFont = prefs.getString(KEY_BODY_FONT, "literata") ?: "literata"
        bodySizeSp = ReaderTheme.bodySizeSp(prefs.getString(KEY_FONT_SIZE, "medium") ?: "medium")
        chromeSizeSp = (bodySizeSp * 0.82f).coerceIn(14f, 18f)

        annotationTimestamp = intent.getLongExtra(EXTRA_TIMESTAMP, 0L)
            .let { if (it > 0L) it else System.currentTimeMillis() }

        if (savedInstanceState != null) restoreState(savedInstanceState) else seedFromIntent()

        setContentView(buildUi())
        if (composePending.isNotEmpty()) {
            composeField.setText(composePending)
            composeField.setSelection(composeField.text.length)
            composeButton.text = composeCommitText()
        }
        refreshToolSelection()
        refreshTagSelection()
        renderPane()
        refreshTabBar()
    }

    /** Fresh launch: seed working state from the launching Intent's extras. */
    private fun seedFromIntent() {
        val rawTool = AnnotationTool.fromName(intent.getStringExtra(EXTRA_INITIAL_TOOL))
        selectedTool = if (rawTool == AnnotationTool.comment) AnnotationTool.highlight else rawTool
        selectedTag = AnnotationTag.fromName(intent.getStringExtra(EXTRA_INITIAL_TAG))
        // Always restore the original annotation ID — whether we have PNG bytes or
        // stroke JSON, ink saves back under the same ID so the annotation finds it.
        inkId = intent.getStringExtra(EXTRA_INITIAL_INK_ID)
        // Existing ink arrives via cache files (survives process death; avoids the
        // Binder IPC size limit).
        capturedInkBytes = readTempBytes(FILE_LAUNCH_PNG)
        capturedStrokeJson = readTempText(FILE_LAUNCH_JSON)
        inkDirty = false

        // Seed the working thread: prefer the passed thread; otherwise synthesise a
        // single leamh entry from a legacy `note` so it shows (and can be edited).
        val existingNote = intent.getStringExtra(EXTRA_NOTE)
        threadEntries.clear()
        threadEntries.addAll(ThreadJson.decode(intent.getStringExtra(EXTRA_THREAD_JSON)))
        if (threadEntries.isEmpty() && !existingNote.isNullOrEmpty()) {
            threadEntries.add(ThreadEntry(existingNote.trim(), annotationTimestamp, ThreadEntry.SOURCE_LEAMH))
        }
        initialThreadJson = ThreadJson.encode(threadEntries)
        activeTab = when {
            threadEntries.isNotEmpty() -> Tab.THREAD
            capturedInkBytes != null -> Tab.INK
            else -> Tab.THREAD
        }
    }

    /** Activity recreation: restore the in-progress edit so committed work isn't lost. */
    private fun restoreState(s: Bundle) {
        selectedTool = AnnotationTool.fromName(s.getString(STATE_TOOL))
        selectedTag = AnnotationTag.fromName(s.getString(STATE_TAG))
        inkId = s.getString(STATE_INK_ID)
        capturedInkBytes = readTempBytes(FILE_STATE_PNG)
        capturedStrokeJson = readTempText(FILE_STATE_JSON)
        inkDirty = s.getBoolean(STATE_INK_DIRTY, false)
        threadEntries.clear()
        threadEntries.addAll(ThreadJson.decode(s.getString(STATE_THREAD)))
        initialThreadJson = s.getString(STATE_INITIAL_THREAD) ?: ThreadJson.encode(threadEntries)
        composeEditIndex = s.getInt(STATE_EDIT_INDEX, -1)
        threadPage = s.getInt(STATE_PAGE, 0)
        activeTab = Tab.entries.getOrElse(s.getInt(STATE_TAB, Tab.THREAD.ordinal)) { Tab.THREAD }
        composePending = s.getString(STATE_COMPOSE, "") ?: ""
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // If the full-screen compose is open, fold its text back into composeField
        // first so the in-progress edit is the value we persist below.
        syncFullComposeText()
        outState.putString(STATE_THREAD, ThreadJson.encode(threadEntries))
        outState.putString(STATE_INITIAL_THREAD, initialThreadJson)
        outState.putInt(STATE_EDIT_INDEX, composeEditIndex)
        outState.putInt(STATE_PAGE, threadPage)
        outState.putInt(STATE_TAB, activeTab.ordinal)
        outState.putString(STATE_TOOL, selectedTool.name)
        outState.putString(STATE_TAG, selectedTag?.name)
        outState.putString(STATE_INK_ID, inkId)
        outState.putBoolean(STATE_INK_DIRTY, inkDirty)
        outState.putString(
            STATE_COMPOSE,
            if (::composeField.isInitialized) composeField.text.toString() else composePending,
        )
        // Ink bytes are too large for the Bundle — stash in cache files.
        writeTempBytes(FILE_STATE_PNG, capturedInkBytes)
        writeTempText(FILE_STATE_JSON, capturedStrokeJson)
    }

    // -------------------------------------------------------------------------
    // Sub-activity result (InkNoteActivity launched from within NoteActivity)
    // -------------------------------------------------------------------------

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_PANEL_INK) {
            if (resultCode == RESULT_OK) {
                val bytes = readTempBytes(FILE_RESULT_PNG)
                val strokeJson = readTempText(FILE_RESULT_JSON)
                if (bytes != null && bytes.isNotEmpty()) {
                    capturedInkBytes = bytes
                    if (inkId == null) inkId = newId()
                    inkDirty = true
                }
                strokeJson?.let { capturedStrokeJson = it }
                activeTab = Tab.INK
                renderPane()
                refreshTabBar()
            }
        } else {
            @Suppress("DEPRECATION")
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    // -------------------------------------------------------------------------
    // Back / discard
    // -------------------------------------------------------------------------

    private fun hasUnsavedChanges(): Boolean {
        val threadChanged = ThreadJson.encode(threadEntries) != initialThreadJson
        val pendingCompose = composeField.text.toString().trim().isNotEmpty()
        return threadChanged || pendingCompose || inkDirty
    }

    private fun handleBack() {
        if (hasUnsavedChanges()) {
            LeamhDialog.confirm(
                context = this,
                message = "Your changes will be lost.",
                positiveLabel = "Discard",
                negativeLabel = "Keep editing",
                onConfirm = { setResult(RESULT_CANCELED); finish() },
            )
        } else {
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Back peels off transient layers first — full-screen compose (folding its
        // text back), then a read overlay — rather than leaving the editor.
        when {
            fullComposeOverlay != null -> dismissFullScreenCompose()
            entryDetailOverlay != null -> dismissEntryDetail()
            else -> handleBack()
        }
    }

    override fun onPause() {
        super.onPause()
        // Proactively tear down transient UI so nothing leaks a window/listener on
        // finish: the toolbar pickers and the entry-detail overlay.
        toolPickerPopup?.dismiss(); toolPickerPopup = null
        tagPickerPopup?.dismiss(); tagPickerPopup = null
        dismissEntryDetail()
        // Sync any full-screen compose text back into the field (so it survives
        // recreation via composeField) and remove the overlay.
        dismissFullScreenCompose()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // On the FIRST focus, treat whatever is already on the clipboard as
            // "already seen" so the paste button starts faded — it only lights up
            // once the user copies something new. (Clipboard reads need window focus
            // on Android 10+, so this can't be seeded earlier in onCreate.)
            if (!pasteSeeded) {
                lastPastedClip = clipboardText()
                pasteSeeded = true
            }
            // The clipboard may also have changed while we were away — re-evaluate.
            refreshPasteButton()
        }
    }

    // -------------------------------------------------------------------------
    // Save
    // -------------------------------------------------------------------------

    private fun onSave() {
        // Flush any unsubmitted compose text so it isn't silently lost.
        if (composeField.text.toString().trim().isNotEmpty()) commitCompose()

        val note = threadEntries.firstOrNull()?.text
        val ink = capturedInkBytes
        val id = inkId
        // Write large data to cache files to avoid Binder IPC size limit.
        writeTempBytes(FILE_RESULT_PNG, ink)
        writeTempText(FILE_RESULT_JSON, capturedStrokeJson)
        val result = Intent()
            .putExtra(EXTRA_NOTE, note ?: "")
            .putExtra(EXTRA_THREAD_JSON, ThreadJson.encode(threadEntries))
            .putExtra(EXTRA_RESULT_TOOL, selectedTool.name)
            .putExtra(EXTRA_RESULT_TAG, selectedTag?.name)
        if (id != null) result.putExtra(EXTRA_INK_ID, id) // small string, safe in extras
        setResult(RESULT_OK, result)
        finish()
    }

    // -------------------------------------------------------------------------
    // UI build
    // -------------------------------------------------------------------------

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ReaderTheme.PAPER)
            isFocusable = true
            isFocusableInTouchMode = true
        }

        root.addView(buildHeader(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(hDivider(), LinearLayout.LayoutParams(MATCH_PARENT, dp(1f)))

        // The tool + tag selectors now live in the header, and the annotated
        // passage is the pinned root of the Comments tab — so the top section is
        // just the compose field, freeing the rest for compose + comments.
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 12dp gutter matches the header, so the field's left edge and the Add
            // button's right edge line up with the toolbar's Dismiss + rightmost icon.
            setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
        }
        top.addView(buildComposeField(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(top, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // FrameLayout wraps the tab bar + pane so showEntryDetail can layer an
        // overlay over just this region without blocking the compose field above.
        bottomFrame = FrameLayout(this)
        val bottomStack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        bottomStack.addView(hDivider(), LinearLayout.LayoutParams(MATCH_PARENT, dp(1f)))
        bottomStack.addView(buildTabBar(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        bottomStack.addView(hDivider(), LinearLayout.LayoutParams(MATCH_PARENT, dp(1f)))

        paneContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // Capture the pane's real height once it's laid out (with the soft keyboard
        // up, since adjustResize shrinks it) so the paginated row count reflects the
        // space actually available, not a full-screen estimate.
        paneContainer.viewTreeObserver.addOnGlobalLayoutListener(
            object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    val h = paneContainer.height
                    if (h > 0 && availablePaneHeight == 0) {
                        availablePaneHeight = h
                        paneContainer.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        if (activeTab == Tab.THREAD) renderPane()
                    }
                }
            },
        )
        bottomStack.addView(paneContainer, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        bottomFrame.addView(bottomStack, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(bottomFrame, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        return root
    }

    /**
     * Top bar: Dismiss + Save paired on the left (so reaching to exit surfaces
     * Save), then a right cluster of tool · tag · paste · expand · Add Comment. The
     * compose helpers + commit all live up here so the compose row below is a clean
     * full-width field (no button beside it to mis-align against).
     */
    private fun buildHeader(): View {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(8f), dp(12f), dp(4f))
        }

        // Left: exit + commit pair. Dismiss keeps the unsaved-changes confirmation.
        header.addView(
            pillButton("Dismiss", filled = false) { handleBack() },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT),
        )
        header.addView(
            pillButton("Save", filled = true) { onSave() },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).also { it.leftMargin = dp(8f) },
        )

        // Space (not View) as the flex spacer: View.getDefaultSize returns the full
        // AT_MOST spec-size for WRAP_CONTENT, inflating the header to screen height.
        // Space.onMeasure returns 0 for AT_MOST, so the header stays wrap-content.
        header.addView(Space(this), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        // Right cluster: tool · tag · paste. Tool + tag carry the corner-hint
        // triangle (a picker drops down beneath them). Add lives beside the field.
        val toolBtn = buildToolButton()
        toolButton = toolBtn
        header.addView(toolBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        header.addView(
            buildTagButton(),
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).also { it.leftMargin = dp(8f) },
        )
        header.addView(
            buildPasteButton(),
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).also { it.leftMargin = dp(8f) },
        )
        // Expand → full-screen compose; grouped with Paste (a compose action).
        header.addView(
            iconButton(R.drawable.ic_expand, "Expand to full screen") { showFullScreenCompose() },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).also { it.leftMargin = dp(8f) },
        )
        // Add Comment — the compose commit, rightmost so the full-width field below
        // sits directly under it. "Update Comment" when editing an existing entry.
        composeButton = buildAddButton()
        header.addView(
            composeButton,
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).also { it.leftMargin = dp(8f) },
        )
        return header
    }

    /**
     * Clipboard paste icon button — wraps pasted text in quotes. Greyed (tap no-op)
     * when there is nothing pasteable or paste was already used this compose;
     * refreshPasteButton() keeps the dim state in sync. Shared by the toolbar and
     * the full-screen sheet.
     */
    private fun makePasteButton(): ImageView {
        val btn = ImageView(this).apply {
            setImageResource(R.drawable.ic_paste)
            contentDescription = "Paste with quotes"
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = ReaderTheme.dp(this@NoteActivity, ReaderTheme.RADIUS_BTN)
                setColor(ReaderTheme.FILL_04)
                setStroke(dp(1f), ReaderTheme.INK_26)
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(11f), dp(11f), dp(11f), dp(11f))
            minimumWidth = dp(48f)
            minimumHeight = dp(48f)
        }
        // The tap routes through pasteClipboardWithQuotes(), which no-ops when the
        // gate is closed — so a greyed button reliably does nothing.
        btn.setOnTouchListener(PenTapListener(this) { pasteClipboardWithQuotes() })
        return btn
    }

    private fun buildPasteButton(): View {
        val btn = makePasteButton()
        pasteButton = btn
        refreshPasteButton()
        return btn
    }

    /** Current primary-clip text, or null when the clipboard holds no text. */
    private fun clipboardText(): String? {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(this)?.toString()
    }

    /** Paste is allowed when the clipboard holds text we haven't already pasted. */
    private fun canPasteNow(): Boolean {
        val t = clipboardText()
        return !t.isNullOrBlank() && t != lastPastedClip
    }

    /**
     * Fade every paste icon when disabled. The icon is a black vector, so a
     * translucent-black colour filter is invisible (black over black) — fade via
     * imageAlpha instead, which composites the black over the paper to a clear grey.
     */
    private fun refreshPasteButton() {
        val alpha = if (canPasteNow()) 255 else 70
        pasteButton?.imageAlpha = alpha
        fsPasteButton?.imageAlpha = alpha
    }

    /** The compose field currently being written into (the full-screen sheet wins). */
    private fun activeComposeField(): EditText = fullComposeField ?: composeField


    /** Label for the compose commit button — reflects add-vs-edit. */
    private fun composeCommitText(): String =
        if (composeEditIndex in threadEntries.indices) "Update Comment" else "Add Comment"

    /** The compose commit button — "Add Comment", or "Update Comment" when editing. */
    private fun buildAddButton(): TextView {
        val btn = TextView(this).apply {
            text = composeCommitText()
            typeface = ReaderTheme.bodyBold(this@NoteActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(ReaderTheme.PAPER)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = ReaderTheme.dp(this@NoteActivity, ReaderTheme.RADIUS_BTN)
                setColor(ReaderTheme.INK_87)
            }
            setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
            minimumHeight = dp(48f)
        }
        btn.setOnTouchListener(PenTapListener(this) { commitCompose() })
        return btn
    }

    /** Toolbar tool selector — draws the current tool; corner hint signals a picker. */
    private fun buildToolButton(): View {
        val btn = object : View(this) {
            private val renderer = ToolIconRenderer(this@NoteActivity)
            private val iconPx = ReaderTheme.dp(this@NoteActivity, ReaderTheme.ICON_DP)
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                setMeasuredDimension(iconPx.toInt() + dp(14f) * 2, iconPx.toInt() + dp(10f) * 2)
            }
            override fun onDraw(canvas: Canvas) {
                renderer.draw(canvas, selectedTool, width / 2f, height / 2f, iconPx)
            }
        }
        btn.background = chipBackground(false)
        btn.foreground = cornerHintDrawable()
        btn.setOnTouchListener(PenTapListener(this) { showToolPicker(btn) })
        return btn
    }

    /**
     * Toolbar tag selector — a FrameLayout wrapper (carries the corner-hint
     * foreground reliably) around a '#'/tag-label TextView. Single-line + ellipsize
     * + a max width so a long label ("Continuity") can't crowd the Save button.
     */
    /** Build a tag button (corner-hint wrapper + '#'/label). Shared by toolbar + sheet. */
    private fun makeTagButton(): Pair<FrameLayout, TextView> {
        val label = TextView(this).apply {
            typeface = ReaderTheme.bodyBold(this@NoteActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, chromeSizeSp)
            setTextColor(ReaderTheme.INK_87)
            gravity = Gravity.CENTER
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(150f)
            // Extra right padding leaves room for the corner-hint triangle.
            setPadding(dp(16f), dp(9f), dp(20f), dp(9f))
        }
        val wrap = FrameLayout(this).apply {
            minimumHeight = dp(48f)
            foreground = cornerHintDrawable()
            addView(label, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        }
        wrap.setOnTouchListener(PenTapListener(this) { showTagPicker(wrap) })
        return wrap to label
    }

    private fun buildTagButton(): View {
        val (wrap, label) = makeTagButton()
        tagButton = wrap
        tagLabel = label
        return wrap
    }

    private fun refreshToolSelection() {
        toolButton?.invalidate()
    }

    /** Refresh every tag button (toolbar + full-screen sheet) to the current tag. */
    private fun refreshTagSelection() {
        val tag = selectedTag
        val txt = if (tag != null) tagLabels[tag] else "#"
        tagLabel?.text = txt
        tagButton?.background = chipBackground(tag != null)
        fsTagLabel?.text = txt
        fsTagButton?.background = chipBackground(tag != null)
    }

    /** Drop the tool picker below the toolbar's tool button. */
    private fun showToolPicker(anchor: View) {
        toolPickerPopup?.dismiss()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = popupBackground()
            setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
        }
        for (tool in selectorTools) {
            val cell = FrameLayout(this).apply { background = chipBackground(tool == selectedTool) }
            cell.addView(ToolIconView(this, tool), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            cell.setOnTouchListener(PenTapListener(this) {
                selectedTool = tool
                refreshToolSelection()
                // The pinned passage label reflects the tool — refresh it live.
                if (activeTab == Tab.THREAD) renderPane()
                toolPickerPopup?.dismiss()
            })
            row.addView(cell, LinearLayout.LayoutParams(dp(52f), dp(52f)).also { it.rightMargin = dp(8f) })
        }
        val popup = PopupWindow(row, WRAP_CONTENT, WRAP_CONTENT, true).apply {
            elevation = ReaderTheme.dp(this@NoteActivity, 4f)
            isOutsideTouchable = true
            setBackgroundDrawable(null)
        }
        toolPickerPopup = popup
        // Gravity.END right-aligns the popup under the button so a wide picker on a
        // right-side anchor extends leftward and stays on-screen.
        popup.showAsDropDown(anchor, 0, dp(4f), Gravity.END)
    }

    /** Drop the tag picker below the toolbar's tag button (incl. a clear option). */
    private fun showTagPicker(anchor: View) {
        tagPickerPopup?.dismiss()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = popupBackground()
            setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
        }
        fun row(label: String, tag: AnnotationTag?): View = TextView(this).apply {
            text = label
            typeface = ReaderTheme.bodyBold(this@NoteActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(if (tag == null) ReaderTheme.INK_54 else ReaderTheme.INK_87)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(11f), dp(28f), dp(11f))
            minimumHeight = dp(48f)
            if (selectedTag == tag) background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = ReaderTheme.dp(this@NoteActivity, ReaderTheme.RADIUS_BTN)
                setColor(ReaderTheme.FILL_06)
            }
            setOnTouchListener(PenTapListener(this@NoteActivity) {
                selectTag(tag)
                tagPickerPopup?.dismiss()
            })
        }
        col.addView(row("None", null), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        for (tag in AnnotationTag.entries) {
            col.addView(row(tagLabels[tag] ?: tag.name, tag), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        val popup = PopupWindow(col, dp(200f), WRAP_CONTENT, true).apply {
            elevation = ReaderTheme.dp(this@NoteActivity, 4f)
            isOutsideTouchable = true
            setBackgroundDrawable(null)
        }
        tagPickerPopup = popup
        // Gravity.END keeps the 200dp popup on-screen when the tag button sits near
        // the right edge of the toolbar.
        popup.showAsDropDown(anchor, 0, dp(4f), Gravity.END)
    }

    /** Set, toggle off, or clear the tag and refresh the toolbar button. */
    private fun selectTag(tag: AnnotationTag?) {
        val prev = selectedTag
        // Re-tapping the active tag in the picker clears it (restores toggle).
        val next = if (tag != null && tag == prev) null else tag
        selectedTag = next
        refreshTagSelection()
        // Seed the field currently being written into (full-screen sheet, if open).
        val field = activeComposeField()
        if (next != null && next != prev && field.text.isEmpty()) {
            field.setText(tagPrompts[next])
            field.setSelection(field.text.length)
        }
    }

    /**
     * The bottom-right "more underneath" triangle (matching the annotation
     * toolbar's lock hint) as a foreground Drawable — reliably painted on top of a
     * view's content + background, including TextViews where an onDraw/draw
     * override did not. Signals a tap reveals a picker.
     */
    private fun cornerHintDrawable(): Drawable = object : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ReaderTheme.INK_54
            style = Paint.Style.FILL
        }
        private val path = Path()
        override fun draw(canvas: Canvas) {
            val m = ReaderTheme.dp(this@NoteActivity, 4f)
            val s = ReaderTheme.dp(this@NoteActivity, 6f)
            val right = bounds.right - m
            val bottom = bounds.bottom - m
            val left = right - s
            val top = bottom - s
            path.reset()
            path.moveTo(right, top)
            path.lineTo(right, bottom)
            path.lineTo(left, bottom)
            path.close()
            canvas.drawPath(path, paint)
        }
        override fun setAlpha(a: Int) = Unit
        override fun setColorFilter(f: ColorFilter?) = Unit
        @Suppress("DEPRECATION") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** Label for the annotated passage, reflecting the current annotation tool. */
    private fun annotatedPassageLabel(): String = when (selectedTool) {
        AnnotationTool.highlight -> "Highlighted passage"
        AnnotationTool.underline -> "Underlined passage"
        AnnotationTool.doubleUnderline -> "Double-underlined passage"
        AnnotationTool.strikethrough -> "Struck-through passage"
        AnnotationTool.wavyUnderline -> "Wavy-underlined passage"
        AnnotationTool.bookmark -> "Bookmarked passage"
        else -> "Annotated passage" // comment / ink / anything else
    }

    /**
     * The annotated passage, pinned as the root of the Comments thread: grey left
     * margin bar + italic, 2-line truncation, tap to read it in full via the
     * detail overlay. No action buttons — it is source text, not a comment.
     */
    private fun buildPinnedQuoteRow(text: String): View {
        val barWidth = dp(3f).toFloat()
        val barColor = ReaderTheme.INK_38
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            minimumHeight = dp(56f)
        }
        row.setOnTouchListener(PenTapListener(this) {
            showEntryDetail(
                ThreadEntry(text, annotationTimestamp, ThreadEntry.SOURCE_LEAMH),
                metaOverride = annotatedPassageLabel(),
            )
        })
        row.addView(TextView(this).apply {
            this.text = text
            typeface = ReaderTheme.bodyItalic(this@NoteActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, (bodySizeSp - 1f).coerceAtLeast(14f))
            setTextColor(ReaderTheme.INK_87)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(14f), dp(2f), 0, dp(2f))
            background = object : Drawable() {
                private val paint = Paint().apply { color = barColor; style = Paint.Style.FILL }
                override fun draw(c: Canvas) { c.drawRect(0f, 0f, barWidth, bounds.height().toFloat(), paint) }
                override fun setAlpha(a: Int) = Unit
                override fun setColorFilter(f: ColorFilter?) = Unit
                @Suppress("DEPRECATION") override fun getOpacity() = PixelFormat.TRANSPARENT
            }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        row.addView(TextView(this).apply {
            this.text = annotatedPassageLabel()
            typeface = ReaderTheme.chrome(this@NoteActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(ReaderTheme.INK_38)
            setPadding(dp(14f), dp(4f), 0, 0)
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        return row
    }

    /**
     * The compose field — full width now that paste, expand and Add Comment all
     * live in the toolbar above (no button beside it to mis-align against).
     */
    private fun buildComposeField(): View {
        composeField = ComposeEditText(this).apply {
            typeface = ReaderTheme.body(this@NoteActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, bodySizeSp)
            setTextColor(ReaderTheme.INK_87)
            setHintTextColor(0xFF9E9A92.toInt())
            hint = "Write a comment…"
            minLines = 2
            maxLines = 4
            gravity = Gravity.TOP or Gravity.START
            isFocusable = true
            isFocusableInTouchMode = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = ReaderTheme.dp(this@NoteActivity, ReaderTheme.RADIUS_BTN)
                setColor(ReaderTheme.FILL_04)
            }
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
        }
        return composeField
    }

    /**
     * Pulls clipboard text, wraps it in quotes, and inserts at [target]'s cursor.
     * Gated: no-ops unless canPasteNow() (clipboard holds text we haven't already
     * pasted). Records the pasted clip and refreshes the buttons. All paste paths
     * (toolbar button, in-field popup, system menu) route through here so the gate
     * holds everywhere.
     */
    private fun pasteClipboardWithQuotes(target: EditText = activeComposeField()) {
        if (!canPasteNow()) return
        val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
            ?: return
        val start = target.selectionStart.coerceIn(0, target.text.length)
        val end   = target.selectionEnd.coerceIn(start, target.text.length)
        target.text.replace(start, end, "\"${clip.trim()}\" ")
        // Remember this clip so paste stays disabled until the clipboard changes.
        lastPastedClip = clip
        refreshPasteButton()
    }

    /** Add the compose field's text as a new entry, or save the entry being edited. */
    private fun commitCompose() {
        val text = composeField.text.toString().trim()
        if (text.isEmpty()) return
        val idx = composeEditIndex
        val isEdit = idx in threadEntries.indices
        if (isEdit) {
            val existing = threadEntries[idx]
            threadEntries[idx] = existing.copy(text = text)
        } else {
            threadEntries.add(ThreadEntry(text, System.currentTimeMillis(), ThreadEntry.SOURCE_LEAMH))
        }
        composeEditIndex = -1
        composeField.setText("")
        composeButton.text = composeCommitText()
        replyContextEntry = null
        activeTab = Tab.THREAD
        // Jump to the new entry on add; keep the reader's place when saving an edit.
        if (!isEdit) threadPage = lastThreadPage()
        renderPane()
        refreshTabBar()
    }

    // -------------------------------------------------------------------------
    // Tabs + pane
    // -------------------------------------------------------------------------

    private fun buildTabBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8f), dp(2f), dp(8f), dp(2f))
        }
        inkTab = tabButton("Ink") { selectTab(Tab.INK) }
        threadTab = tabButton("Comments") { selectTab(Tab.THREAD) }
        bar.addView(inkTab, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        bar.addView(threadTab, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        return bar
    }

    private fun tabButton(label: String, onTap: () -> Unit): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumHeight = dp(48f)
        }
        container.addView(TextView(this).apply {
            text = label
            typeface = ReaderTheme.bodyBold(this@NoteActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(ReaderTheme.INK_87)
            gravity = Gravity.CENTER
            setPadding(dp(8f), dp(10f), dp(8f), dp(6f))
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        // Active indicator bar.
        container.addView(View(this), LinearLayout.LayoutParams(dp(48f), dp(2f)))
        container.setOnTouchListener(PenTapListener(this, onTap = onTap))
        return container
    }

    private fun selectTab(tab: Tab) {
        if (activeTab == tab) return
        activeTab = tab
        renderPane()
        refreshTabBar()
    }

    private fun refreshTabBar() {
        styleTab(inkTab, active = activeTab == Tab.INK, hasData = capturedInkBytes != null)
        styleTab(threadTab, active = activeTab == Tab.THREAD, hasData = threadEntries.isNotEmpty())
    }

    /** Empty tabs are dimmed (greyed); the active tab carries a solid indicator bar. */
    private fun styleTab(tab: LinearLayout, active: Boolean, hasData: Boolean) {
        val label = tab.getChildAt(0) as TextView
        val indicator = tab.getChildAt(1)
        label.setTextColor(if (active) ReaderTheme.INK_87 else if (hasData) ReaderTheme.INK_54 else ReaderTheme.INK_26)
        indicator.setBackgroundColor(if (active) ReaderTheme.INK_87 else 0)
    }

    private fun renderPane() {
        paneContainer.removeAllViews()
        when (activeTab) {
            Tab.INK -> renderInkPane()
            Tab.THREAD -> renderThreadPane()
        }
    }

    // ---- Ink pane -----------------------------------------------------------

    private fun renderInkPane() {
        val pane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20f), dp(12f), dp(20f), dp(16f))
        }
        val bytes = capturedInkBytes
        val bmp = if (bytes != null) BitmapFactory.decodeByteArray(bytes, 0, bytes.size) else null
        if (bmp != null) {
            pane.addView(ImageView(this).apply {
                setImageBitmap(bmp)
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
                // Desaturate for e-ink display.
                colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
            }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        } else {
            pane.addView(TextView(this).apply {
                text = "No ink yet."
                typeface = ReaderTheme.body(this@NoteActivity)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(ReaderTheme.INK_38)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).also { it.topMargin = dp(24f) })
        }
        pane.addView(
            pillButton(if (bmp != null) "Edit ink" else "Add ink", filled = bmp != null) { launchInk() },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).also { it.topMargin = dp(12f) },
        )
        paneContainer.addView(pane, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    private fun launchInk() {
        if (inkId == null) inkId = newId()
        val sj = capturedStrokeJson
        // Write large data to cache files to avoid Binder IPC size limit.
        writeTempBytes(InkNoteActivity.FILE_LAUNCH_PNG, if (sj == null) capturedInkBytes else null)
        writeTempText(InkNoteActivity.FILE_LAUNCH_JSON, sj)
        startActivityForResult(
            Intent(this, InkNoteActivity::class.java)
                .putExtra(InkNoteActivity.EXTRA_SELECTED_TEXT, selectedText),
            REQ_PANEL_INK,
        )
    }

    // ---- Thread pane --------------------------------------------------------

    private fun renderThreadPane() {
        val pane = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Pin the annotated passage as the root of the thread — source first,
        // discussion below, both readable in one pane. Stays visible on every page.
        if (selectedText.isNotEmpty()) {
            pane.addView(buildPinnedQuoteRow(selectedText))
            pane.addView(rowDivider())
        }

        if (threadEntries.isEmpty()) {
            pane.addView(TextView(this).apply {
                text = "No comments yet.\nWrite one above and tap Add."
                typeface = ReaderTheme.body(this@NoteActivity)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(ReaderTheme.INK_38)
                gravity = Gravity.CENTER
                setPadding(dp(20f), dp(28f), dp(20f), dp(20f))
            }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            paneContainer.addView(pane, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            return
        }

        val pageSize = threadRowsPerPage()
        val totalPages = (threadEntries.size + pageSize - 1) / pageSize
        threadPage = threadPage.coerceIn(0, totalPages - 1)
        val start = threadPage * pageSize
        val end = minOf(start + pageSize, threadEntries.size)

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (i in start until end) {
            list.addView(rowDivider())
            list.addView(buildThreadRow(threadEntries[i], i))
        }
        pane.addView(list, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // Pagination — hidden when everything fits one page.
        val pager = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(4f), dp(12f), dp(4f))
            minimumHeight = dp(48f)
            visibility = if (totalPages > 1) View.VISIBLE else View.GONE
        }
        pager.addView(textButton("← Prev", bold = true) {
            if (threadPage > 0) { threadPage--; renderPane() }
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        pager.addView(TextView(this).apply {
            text = "${threadPage + 1} / $totalPages"
            typeface = ReaderTheme.body(this@NoteActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(ReaderTheme.INK_54)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        pager.addView(textButton("Next →", bold = true) {
            if (threadPage < totalPages - 1) { threadPage++; renderPane() }
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        pane.addView(pager, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        paneContainer.addView(pane, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    private fun buildThreadRow(entry: ThreadEntry, index: Int): View {
        val isWord = entry.source == ThreadEntry.SOURCE_WORD
        val beingEdited = index == composeEditIndex
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            minimumHeight = dp(64f)
            if (beingEdited) setBackgroundColor(ReaderTheme.FILL_06)
        }
        // Tap anywhere on the row body to read the full entry in a popup.
        row.setOnTouchListener(PenTapListener(this) { showEntryDetail(entry) })

        val textCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textCol.addView(TextView(this).apply {
            text = entry.text
            typeface = ReaderTheme.body(this@NoteActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(if (isWord) ReaderTheme.INK_54 else ReaderTheme.INK_87)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        val meta = buildString {
            append(formatTimestamp(entry.timestamp))
            if (isWord) append("  ·  Word")
        }
        textCol.addView(TextView(this).apply {
            text = meta
            typeface = ReaderTheme.body(this@NoteActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(ReaderTheme.INK_38)
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).also { it.topMargin = dp(2f) })
        row.addView(textCol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        row.addView(smallAction("Reply") { startReply(entry) },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        if (!isWord) {
            row.addView(smallAction("Edit") { startEditEntry(index) },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).also { it.leftMargin = dp(4f) })
            row.addView(smallAction("Delete") { confirmDeleteEntry(index) },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).also { it.leftMargin = dp(4f) })
        }
        return row
    }

    private fun startEditEntry(index: Int) {
        if (index !in threadEntries.indices) return
        composeEditIndex = index
        composeField.setText(threadEntries[index].text)
        composeField.setSelection(composeField.text.length)
        composeButton.text = composeCommitText()
        // Editing isn't replying — no reply-context reference.
        replyContextEntry = null
        renderPane() // re-highlight the edited row
    }

    private fun startReply(entry: ThreadEntry) {
        // Insert the first ~8 words of the entry as a quote; the user types after it.
        // The quote + response is stored as one flat new entry — no structural threading.
        val words = entry.text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val snippet = words.take(8).joinToString(" ")
        val quoted = "\"" + snippet + (if (words.size > 8) "…" else "") + "\" "
        composeEditIndex = -1
        composeButton.text = composeCommitText()
        composeField.setText(quoted)
        composeField.setSelection(composeField.text.length)
        composeField.requestFocus()
        // Replying: remember the target entry (the full-screen compose shows it as
        // the read-only reference).
        replyContextEntry = entry
        // Clear any stale "being edited" row highlight.
        renderPane()
    }

    private fun confirmDeleteEntry(index: Int) {
        LeamhDialog.confirm(
            context = this,
            message = "Delete this comment?",
            positiveLabel = "Delete",
            negativeLabel = "Cancel",
            onConfirm = {
                if (index in threadEntries.indices) {
                    threadEntries.removeAt(index)
                    when {
                        composeEditIndex == index -> {
                            composeEditIndex = -1
                            composeField.setText("")
                            composeButton.text = composeCommitText()
                        }
                        composeEditIndex > index -> composeEditIndex--
                    }
                    val pageSize = threadRowsPerPage()
                    val totalPages = maxOf(1, (threadEntries.size + pageSize - 1) / pageSize)
                    threadPage = threadPage.coerceIn(0, totalPages - 1)
                    renderPane()
                    refreshTabBar()
                }
            },
        )
    }

    /**
     * Visible thread rows per page, derived from screen height (Nomad and Manta
     * differ). Same approach as FileBrowserActivity.recentsCapFromHeight — never
     * a hardcoded count.
     */
    private fun threadRowsPerPage(): Int {
        val rowH = dp(THREAD_ROW_DP)
        // The pinned quote + its divider sit above the paginated rows (always
        // visible), so subtract their height. Use the MAX likely height (2 lines of
        // italic + label + paddings + divider, ~92dp) so we never squeeze in an
        // extra row that overflows — wasted space is safer than overflow on e-ink.
        val pinnedH = if (selectedText.isNotEmpty()) dp(96f) else 0
        val available = if (availablePaneHeight > 0) {
            // Measured pane height (post soft-keyboard inset) minus the pager row.
            availablePaneHeight - dp(56f) - pinnedH
        } else {
            // Pre-measure fallback: full screen minus the fixed chrome above/below
            // (header, compose, dividers, tab bar, pagination) and the pinned quote.
            resources.displayMetrics.heightPixels - dp(56f + 92f + 52f + 56f) - pinnedH
        }
        return (available / rowH).coerceIn(1, 8)
    }

    private fun lastThreadPage(): Int {
        if (threadEntries.isEmpty()) return 0
        val pageSize = threadRowsPerPage()
        return (threadEntries.size + pageSize - 1) / pageSize - 1
    }

    private fun formatTimestamp(millis: Long): String =
        if (millis <= 0L) "" else threadDateFormat.format(Date(millis))

    // -------------------------------------------------------------------------
    // Shared text-selection styling — the reader's dotted underline + a themed
    // popup, used by BOTH the read-only entry-detail overlay (SelectableBodyText)
    // and the editable compose fields (ComposeEditText) so selection looks the
    // same on every surface, instead of Android's blue fill + floating toolbar.
    // -------------------------------------------------------------------------

    private val selDottedPaint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = ReaderTheme.INK
            strokeWidth = ReaderTheme.dp(this@NoteActivity, ReaderTheme.UNDERLINE_STROKE_DP)
            pathEffect = DashPathEffect(
                floatArrayOf(
                    ReaderTheme.dp(this@NoteActivity, ReaderTheme.UNDERLINE_DASH_ON_DP),
                    ReaderTheme.dp(this@NoteActivity, ReaderTheme.UNDERLINE_DASH_OFF_DP),
                ),
                0f,
            )
        }
    }

    /**
     * Draws the reader's dotted underline beneath [tv]'s current selection range —
     * call from [tv]'s onDraw. Accounts for the view's scroll (the editable compose
     * field can scroll vertically), so the read-only overlay (scroll always 0) is
     * just the special case scrollX/scrollY == 0.
     */
    private fun drawSelectionUnderline(tv: TextView, canvas: Canvas, path: Path) {
        val l = tv.layout ?: return
        val lo = minOf(tv.selectionStart, tv.selectionEnd)
        val hi = maxOf(tv.selectionStart, tv.selectionEnd)
        if (lo < 0 || lo >= hi) return
        val underlineOffset = ReaderTheme.dp(this, ReaderTheme.UNDERLINE_OFFSET_DP)
        canvas.save()
        canvas.translate(
            (tv.totalPaddingLeft - tv.scrollX).toFloat(),
            (tv.totalPaddingTop - tv.scrollY).toFloat(),
        )
        val firstLine = l.getLineForOffset(lo)
        val lastLine = l.getLineForOffset((hi - 1).coerceAtLeast(lo))
        for (line in firstLine..lastLine) {
            val ls = maxOf(lo, l.getLineStart(line))
            val le = minOf(hi, l.getLineEnd(line))
            if (ls >= le) continue
            var x0 = l.getPrimaryHorizontal(ls)
            var x1 = l.getPrimaryHorizontal(le)
            if (x1 <= x0) x1 = l.getLineRight(line)
            if (x1 < x0) { val t = x0; x0 = x1; x1 = t }
            val y = l.getLineBaseline(line).toFloat() + underlineOffset
            path.rewind()
            path.moveTo(x0, y)
            path.lineTo(x1, y)
            canvas.drawPath(path, selDottedPaint)
        }
        canvas.restore()
    }

    /**
     * Builds + shows a themed selection popup (paper card, INK_26 border, divided
     * chrome-bold buttons) positioned ~60dp above [tv]'s selection start. [actions]
     * are (label, handler) pairs. Returns the PopupWindow so the caller stores +
     * dismisses it.
     */
    private fun showSelectionPopup(tv: TextView, actions: List<Pair<String, () -> Unit>>): PopupWindow {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = ReaderTheme.dp(this@NoteActivity, ReaderTheme.RADIUS_BTN)
                setColor(ReaderTheme.PAPER)
                setStroke(dp(1f), ReaderTheme.INK_26)
            }
        }
        actions.forEachIndexed { i, (label, action) ->
            if (i > 0) content.addView(View(this).apply {
                setBackgroundColor(ReaderTheme.INK_26)
                layoutParams = LinearLayout.LayoutParams(dp(1f), MATCH_PARENT).also {
                    it.topMargin = dp(10f); it.bottomMargin = dp(10f)
                }
            })
            content.addView(TextView(this).apply {
                text = label
                typeface = ReaderTheme.chromeBold(this@NoteActivity)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(ReaderTheme.INK_87)
                gravity = Gravity.CENTER
                setPadding(dp(20f), dp(12f), dp(20f), dp(12f))
                minimumHeight = dp(48f)
                setOnTouchListener(PenTapListener(this@NoteActivity) { action() })
            })
        }
        val popup = PopupWindow(content, WRAP_CONTENT, WRAP_CONTENT, true).apply {
            elevation = ReaderTheme.dp(this@NoteActivity, 4f)
            isOutsideTouchable = true
            setBackgroundDrawable(null)
        }
        // Position the popup just above the selected text's first line.
        val l = tv.layout
        val screenLoc = IntArray(2).also { tv.getLocationInWindow(it) }
        val xScreen: Int
        val yScreen: Int
        if (l != null) {
            val anchorOff = minOf(tv.selectionStart, tv.selectionEnd).coerceAtLeast(0)
            val line = l.getLineForOffset(anchorOff)
            val lineTop = tv.totalPaddingTop + l.getLineTop(line) - tv.scrollY
            xScreen = (screenLoc[0] + tv.totalPaddingLeft +
                l.getPrimaryHorizontal(anchorOff).toInt() - tv.scrollX)
                .coerceIn(screenLoc[0], screenLoc[0] + tv.width - dp(140f))
            yScreen = screenLoc[1] + lineTop - dp(60f)
        } else {
            xScreen = screenLoc[0] + dp(16f)
            yScreen = screenLoc[1] - dp(60f)
        }
        popup.showAtLocation(tv, Gravity.NO_GRAVITY, xScreen, yScreen.coerceAtLeast(0))
        return popup
    }

    /** Clears the system floating ActionMode (Copy/Share/…) so our themed popup is
     *  the only selection toolbar. Returning true from onCreateActionMode lets the
     *  selection itself proceed (handles stay live); the empty menu hides the bar. */
    private fun suppressingActionModeCallback() = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu) = true
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.clear(); return true
        }
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = false
        override fun onDestroyActionMode(mode: ActionMode) {}
    }

    /**
     * Read-only selectable TextView for the entry-detail overlay: suppresses the
     * system fill, draws the shared dotted underline, and shows a themed Copy /
     * Select-all popup via [showSelectionPopup].
     */
    private inner class SelectableBodyText(context: Context) : TextView(context) {
        private val selPath = Path()
        private var selectionPopup: PopupWindow? = null
        private val popupHandler = Handler(Looper.getMainLooper())
        private var pendingPopup: Runnable? = null

        init {
            setTextIsSelectable(true)
            highlightColor = 0 // suppress fill; dotted underline drawn in onDraw
            setCustomSelectionActionModeCallback(suppressingActionModeCallback())
        }

        override fun onSelectionChanged(selStart: Int, selEnd: Int) {
            super.onSelectionChanged(selStart, selEnd)
            pendingPopup?.let { popupHandler.removeCallbacks(it) }
            pendingPopup = null
            if (selStart < selEnd) {
                val r = Runnable { if (isAttachedToWindow) showPopup() }
                pendingPopup = r
                popupHandler.postDelayed(r, 150L)
            } else {
                dismissPopup()
            }
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            pendingPopup?.let { popupHandler.removeCallbacks(it) }
            dismissPopup()
        }

        private fun showPopup() {
            dismissPopup()
            selectionPopup = showSelectionPopup(this, listOf(
                "Copy" to {
                    val s = minOf(selectionStart, selectionEnd)
                    val e = maxOf(selectionStart, selectionEnd)
                    if (s < e) (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("", text.subSequence(s, e).toString()))
                    lastPastedClip = null // a fresh copy re-enables paste
                    refreshPasteButton()
                    dismissPopup()
                },
                // onTextContextMenuItem casts mText to Spannable (not Editable),
                // which works correctly when setTextIsSelectable buffers as SPANNABLE.
                "Select all" to { onTextContextMenuItem(android.R.id.selectAll) },
            ))
        }

        private fun dismissPopup() {
            selectionPopup?.let { if (it.isShowing) it.dismiss() }
            selectionPopup = null
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            drawSelectionUnderline(this, canvas, selPath)
        }
    }

    /**
     * Editable compose field that matches the reader's selection style: suppresses
     * the system blue fill, draws the shared dotted underline, and replaces the
     * floating toolbar with a themed Cut / Copy / Paste / Select-all popup.
     * Selection handles and the paste path still work — paste (toolbar, in-field
     * popup, or system insertion bubble) routes through [pasteClipboardWithQuotes].
     * Touching the field dismisses any open entry-detail overlay (read → write).
     */
    private inner class ComposeEditText(context: Context) : EditText(context) {
        private val selPath = Path()
        private var selectionPopup: PopupWindow? = null
        private val popupHandler = Handler(Looper.getMainLooper())
        private var pendingPopup: Runnable? = null

        init {
            highlightColor = 0 // suppress fill; dotted underline drawn in onDraw
            setCustomSelectionActionModeCallback(suppressingActionModeCallback())
        }

        override fun onTextContextMenuItem(id: Int): Boolean {
            if (id == android.R.id.paste || id == android.R.id.pasteAsPlainText) {
                pasteClipboardWithQuotes(this)
                return true
            }
            return super.onTextContextMenuItem(id)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            // Tapping into the field is the write intent — close any read overlay.
            if (event.actionMasked == MotionEvent.ACTION_DOWN) dismissEntryDetail()
            return super.onTouchEvent(event)
        }

        override fun onSelectionChanged(selStart: Int, selEnd: Int) {
            super.onSelectionChanged(selStart, selEnd)
            pendingPopup?.let { popupHandler.removeCallbacks(it) }
            pendingPopup = null
            if (selStart < selEnd) {
                val r = Runnable { if (isAttachedToWindow) showPopup() }
                pendingPopup = r
                popupHandler.postDelayed(r, 150L)
            } else {
                dismissPopup()
            }
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            pendingPopup?.let { popupHandler.removeCallbacks(it) }
            dismissPopup()
        }

        private fun showPopup() {
            dismissPopup()
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val actions = mutableListOf<Pair<String, () -> Unit>>()
            actions += "Cut" to {
                val s = minOf(selectionStart, selectionEnd)
                val e = maxOf(selectionStart, selectionEnd)
                if (s < e) {
                    cm.setPrimaryClip(ClipData.newPlainText("", text.subSequence(s, e).toString()))
                    text.replace(s, e, "")
                }
                lastPastedClip = null // a fresh copy/cut re-enables paste
                refreshPasteButton()
                dismissPopup()
            }
            actions += "Copy" to {
                val s = minOf(selectionStart, selectionEnd)
                val e = maxOf(selectionStart, selectionEnd)
                if (s < e) cm.setPrimaryClip(ClipData.newPlainText("", text.subSequence(s, e).toString()))
                lastPastedClip = null // a fresh copy/cut re-enables paste
                refreshPasteButton()
                dismissPopup()
            }
            // Paste only when the gate is open (clipboard has unpasted text) —
            // honours the same gate as the toolbar paste button.
            if (canPasteNow()) actions += "Paste" to {
                pasteClipboardWithQuotes(this)
                dismissPopup()
            }
            actions += "Select all" to { onTextContextMenuItem(android.R.id.selectAll) }
            selectionPopup = showSelectionPopup(this, actions)
        }

        private fun dismissPopup() {
            selectionPopup?.let { if (it.isShowing) it.dismiss() }
            selectionPopup = null
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            drawSelectionUnderline(this, canvas, selPath)
        }
    }

    /**
     * Overlays a full-entry reader on top of the bottom pane (tab bar + thread
     * list). The compose field above remains fully interactive so the user can
     * read a long comment and compose a reply at the same time.
     *
     * The overlay is only dismissed by the Dismiss button, by Save, or by the
     * user leaving NoteActivity (back). No touch event leaks through to the
     * underlying pane while it is shown.
     */
    private fun showEntryDetail(entry: ThreadEntry, metaOverride: String? = null) {
        dismissEntryDetail() // remove any existing overlay first

        val dm = resources.displayMetrics
        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ReaderTheme.PAPER)
            // Consume all touches so nothing bleeds through to the pane below.
            isClickable = true
            isFocusable = true
        }

        // Top edge accent — visually separates overlay from the compose area above.
        overlay.addView(View(this).apply { setBackgroundColor(ReaderTheme.INK_12) },
            LinearLayout.LayoutParams(MATCH_PARENT, dp(1f)))

        // Meta: timestamp · source, or an override label (e.g. the pinned quote).
        overlay.addView(TextView(this).apply {
            text = metaOverride ?: buildString {
                append(formatTimestamp(entry.timestamp))
                append("  ·  ")
                append(if (entry.source == ThreadEntry.SOURCE_WORD) "Word" else "Leamh")
            }
            typeface = ReaderTheme.chrome(this@NoteActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(ReaderTheme.INK_38)
            setPadding(dp(20f), dp(12f), dp(20f), dp(8f))
        })
        overlay.addView(hDivider(), LinearLayout.LayoutParams(MATCH_PARENT, dp(1f)))

        // Body text area — fills all remaining space; content replaced per page.
        // SelectableBodyText suppresses the system fill and draws a dotted underline
        // for the selection, matching the reader's active-selection style.
        val textView = SelectableBodyText(this).apply {
            typeface = ReaderTheme.body(this@NoteActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(ReaderTheme.INK_87)
            setPadding(dp(20f), dp(14f), dp(20f), dp(14f))
            setLineSpacing(0f, 1.4f)
            gravity = Gravity.TOP or Gravity.START
        }
        overlay.addView(textView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        overlay.addView(hDivider(), LinearLayout.LayoutParams(MATCH_PARENT, dp(1f)))

        // Pager row — hidden until pagination is needed.
        val pageLabel = TextView(this).apply {
            typeface = ReaderTheme.chrome(this@NoteActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(ReaderTheme.INK_54)
            gravity = Gravity.CENTER
        }
        val prevBtn = textButton("← Prev", bold = true) {}
        val nextBtn = textButton("Next →", bold = true) {}
        val pagerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(4f), dp(12f), dp(4f))
            minimumHeight = dp(48f)
            visibility = View.GONE
        }
        pagerRow.addView(prevBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        pagerRow.addView(pageLabel, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        pagerRow.addView(nextBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        overlay.addView(pagerRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // Dismiss button — centred, the only way to close the overlay.
        val dismissRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(20f), dp(8f), dp(20f), dp(16f))
        }
        dismissRow.addView(
            pillButton("Dismiss", filled = false) { dismissEntryDetail() },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT),
        )
        overlay.addView(dismissRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        entryDetailOverlay = overlay
        bottomFrame.addView(overlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // Mutable pagination state — shared between the layout listener and buttons.
        var pages = listOf<Pair<Int, Int>>()
        var currentPage = 0
        var lastTextH = 0
        var lastTextW = 0

        val paint = TextPaint().apply {
            typeface = ReaderTheme.body(this@NoteActivity)
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, dm)
        }

        fun buildPages(textW: Int, textH: Int): List<Pair<Int, Int>> {
            val sl = StaticLayout.Builder
                .obtain(entry.text, 0, entry.text.length, paint, textW.coerceAtLeast(1))
                .setLineSpacing(0f, 1.4f)
                .build()
            if (sl.lineCount == 0) return listOf(0 to entry.text.length)
            val result = mutableListOf<Pair<Int, Int>>()
            var startLine = 0
            while (startLine < sl.lineCount) {
                val topOfPage = sl.getLineTop(startLine)
                var endLine = startLine
                while (endLine < sl.lineCount &&
                    sl.getLineBottom(endLine) - topOfPage <= textH) {
                    endLine++
                }
                if (endLine == startLine) endLine = startLine + 1
                result.add(sl.getLineStart(startLine) to
                    if (endLine >= sl.lineCount) entry.text.length else sl.getLineStart(endLine))
                startLine = endLine
            }
            return result
        }

        fun renderPage(p: Int) {
            val (s, e) = pages[p]
            textView.text = entry.text.substring(s, e)
            pageLabel.text = "${p + 1} / ${pages.size}"
            pagerRow.visibility = if (pages.size > 1) View.VISIBLE else View.GONE
        }

        // Re-paginate whenever the overlay resizes (keyboard show/hide via adjustResize).
        // Guard on textH change so layout thrashing doesn't trigger unnecessary work.
        // On resize, seek to the page containing the same start-char as the current page.
        // Held in a field so dismissEntryDetail() can remove it (no listener leak).
        val layoutListener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
            val textW = (textView.width - textView.paddingLeft - textView.paddingRight)
            val textH = (textView.height - textView.paddingTop - textView.paddingBottom)
            if (textH > 0 && !(textH == lastTextH && textW == lastTextW)) {
                val anchorChar = pages.getOrNull(currentPage)?.first ?: 0
                lastTextH = textH
                lastTextW = textW
                pages = buildPages(textW, textH)
                // Seek to the page holding the anchor char to preserve reading position.
                currentPage = pages.indexOfLast { (s, _) -> s <= anchorChar }.coerceAtLeast(0)
                renderPage(currentPage)
            }
        }
        entryDetailLayoutListener = layoutListener
        overlay.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)

        prevBtn.setOnTouchListener(PenTapListener(this) {
            if (currentPage > 0) { currentPage--; renderPage(currentPage) }
        })
        nextBtn.setOnTouchListener(PenTapListener(this) {
            if (currentPage < pages.size - 1) { currentPage++; renderPage(currentPage) }
        })
    }

    private fun dismissEntryDetail() {
        val v = entryDetailOverlay ?: return
        entryDetailLayoutListener?.let { v.viewTreeObserver.removeOnGlobalLayoutListener(it) }
        entryDetailLayoutListener = null
        bottomFrame.removeView(v)
        entryDetailOverlay = null
    }

    // -------------------------------------------------------------------------
    // Full-screen compose (P1d)
    // -------------------------------------------------------------------------

    /** The reference shown in full-screen compose: the replied-to comment, else the passage. */
    private fun composeContextText(): String? =
        replyContextEntry?.text ?: selectedText.ifEmpty { null }

    /**
     * Full-screen compose: a paper sheet over everything with the referenced
     * passage/comment pinned (and SELECTABLE, so the user can copy a phrase to
     * quote) at the top, and a full-height editable field below, so a long reply
     * can be written while re-reading the source. Tag + Paste mirror the toolbar so
     * they're reachable while writing; the collapse icon folds the text back, and
     * Add Comment commits it. The small compose field stays fixed — this is the
     * escape hatch for overflow (no scroll, no char cap on it).
     */
    private fun showFullScreenCompose() {
        if (fullComposeOverlay != null) return
        dismissEntryDetail()

        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ReaderTheme.PAPER)
            isClickable = true   // consume touches; nothing leaks to the screen below
            isFocusable = true
            fitsSystemWindows = true
        }

        // Top bar: everything right-aligned (the left stays clear of where Dismiss/
        // Save sit on the toolbar beneath). The collapse icon sits just before Add
        // Comment — directly above the expand button it mirrors — so expand/collapse
        // toggle in place and an accidental double-tap re-opens the sheet instead of
        // reaching Dismiss/Save.
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
        }
        bar.addView(Space(this), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        // Tag mirror — set/clear a tag while writing (same picker + state).
        val (tagWrap, tagLbl) = makeTagButton()
        fsTagButton = tagWrap; fsTagLabel = tagLbl
        bar.addView(tagWrap, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        // Paste mirror — quote-wrapping, same clipboard gate + dim.
        val pasteBtn = makePasteButton()
        fsPasteButton = pasteBtn
        bar.addView(
            pasteBtn,
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).also { it.leftMargin = dp(8f) },
        )
        // Collapse — closes the sheet; sits where the expand button is underneath.
        bar.addView(
            iconButton(R.drawable.ic_collapse, "Collapse") { dismissFullScreenCompose() },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).also { it.leftMargin = dp(8f) },
        )
        // Add Comment — commit + close (folds text back, then commits to the thread).
        val fsAdd = buildAddButton()
        fsAdd.setOnTouchListener(PenTapListener(this) { dismissFullScreenCompose(); commitCompose() })
        bar.addView(
            fsAdd,
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).also { it.leftMargin = dp(8f) },
        )
        sheet.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        sheet.addView(hDivider(), LinearLayout.LayoutParams(MATCH_PARENT, dp(1f)))

        // Reference: the comment being replied to, else the annotated passage.
        // SelectableBodyText so a phrase can be selected → Copy → pasted as a quote.
        // Bounded height so it can't crowd out the writing area on e-ink.
        val ref = composeContextText()
        if (!ref.isNullOrEmpty()) {
            sheet.addView(TextView(this).apply {
                text = if (replyContextEntry != null) "Replying to" else annotatedPassageLabel()
                typeface = ReaderTheme.chrome(this@NoteActivity)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(ReaderTheme.INK_38)
                setPadding(dp(20f), dp(12f), dp(20f), dp(2f))
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            sheet.addView(SelectableBodyText(this).apply {
                this.text = ref
                typeface = ReaderTheme.bodyItalic(this@NoteActivity)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, (bodySizeSp - 1f).coerceAtLeast(14f))
                setTextColor(ReaderTheme.INK_87)
                maxLines = 8
                ellipsize = TextUtils.TruncateAt.END
                setLineSpacing(0f, 1.3f)
                setPadding(dp(20f), dp(2f), dp(20f), dp(12f))
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            sheet.addView(hDivider(), LinearLayout.LayoutParams(MATCH_PARENT, dp(1f)))
        }

        // Full-height editable field — same selection styling + quote-paste as the
        // small field; grows with the text (cursor-following scroll, no swipe).
        val field = ComposeEditText(this).apply {
            typeface = ReaderTheme.body(this@NoteActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, bodySizeSp)
            setTextColor(ReaderTheme.INK_87)
            setHintTextColor(0xFF9E9A92.toInt())
            hint = "Write a comment…"
            gravity = Gravity.TOP or Gravity.START
            isFocusable = true
            isFocusableInTouchMode = true
            setBackgroundColor(0)
            setPadding(dp(20f), dp(14f), dp(20f), dp(14f))
            setText(composeField.text)
            setSelection(text.length)
        }
        fullComposeField = field
        sheet.addView(field, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        fullComposeOverlay = sheet
        addContentView(sheet, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        // Sync the mirrored tag + paste controls to current state.
        refreshTagSelection()
        refreshPasteButton()
        field.requestFocus()
    }

    /** Copy the full-screen field's text into composeField (if the sheet is open). */
    private fun syncFullComposeText() {
        val field = fullComposeField ?: return
        composeField.setText(field.text)
        composeField.setSelection(composeField.text.length)
    }

    /** Fold the full-screen text back into composeField and remove the sheet. */
    private fun dismissFullScreenCompose() {
        val sheet = fullComposeOverlay ?: return
        syncFullComposeText()
        (sheet.parent as? ViewGroup)?.removeView(sheet)
        fullComposeOverlay = null
        fullComposeField = null
        fsTagButton = null
        fsTagLabel = null
        fsPasteButton = null
    }

    // -------------------------------------------------------------------------
    // Widget helpers
    // -------------------------------------------------------------------------

    private fun readTempBytes(name: String): ByteArray? = try {
        val f = File(cacheDir, name)
        if (!f.exists()) null else f.readBytes().also { f.delete() }
    } catch (_: Exception) { null }

    private fun readTempText(name: String): String? = try {
        val f = File(cacheDir, name)
        if (!f.exists()) null else f.readText().also { f.delete() }
    } catch (_: Exception) { null }

    private fun writeTempBytes(name: String, bytes: ByteArray?) = try {
        val f = File(cacheDir, name)
        if (bytes != null) f.writeBytes(bytes) else f.delete()
    } catch (_: Exception) {}

    private fun writeTempText(name: String, text: String?) = try {
        val f = File(cacheDir, name)
        if (text != null) f.writeText(text) else f.delete()
    } catch (_: Exception) {}

    companion object {
        const val EXTRA_NOTE            = "note"
        const val EXTRA_THREAD_JSON     = "thread_json"
        const val EXTRA_TIMESTAMP       = "timestamp_millis"
        const val EXTRA_SELECTED_TEXT   = "selected_text"
        const val EXTRA_INITIAL_TOOL    = "initial_tool"
        const val EXTRA_RESULT_TOOL     = "result_tool"
        const val EXTRA_RESULT_TAG      = "result_tag"
        const val EXTRA_INK_PNG         = "ink_png"
        const val EXTRA_INK_ID          = "ink_id"
        /** Optional: AnnotationTag.name to pre-select (edit flow). */
        const val EXTRA_INITIAL_TAG     = "initial_tag"
        /** Optional: annotation ID of existing ink to preload (edit flow). */
        const val EXTRA_INITIAL_INK_ID  = "initial_ink_id"

        const val FILE_LAUNCH_PNG  = "ink_launch.png"
        const val FILE_LAUNCH_JSON = "ink_launch_strokes.json"
        const val FILE_RESULT_PNG  = "ink_result.png"
        const val FILE_RESULT_JSON = "ink_result_strokes.json"

        private const val REQ_PANEL_INK  = 1008
        private const val PREFS          = "leamh"
        private const val KEY_FONT_SIZE  = "body_font_size"
        private const val KEY_BODY_FONT  = "body_font"
        private const val THREAD_ROW_DP  = 76f

        // Instance-state keys + ink cache files (survive activity recreation).
        private const val STATE_THREAD         = "state_thread"
        private const val STATE_INITIAL_THREAD = "state_initial_thread"
        private const val STATE_EDIT_INDEX     = "state_edit_index"
        private const val STATE_PAGE           = "state_page"
        private const val STATE_TAB            = "state_tab"
        private const val STATE_TOOL           = "state_tool"
        private const val STATE_TAG            = "state_tag"
        private const val STATE_INK_ID         = "state_ink_id"
        private const val STATE_INK_DIRTY      = "state_ink_dirty"
        private const val STATE_COMPOSE        = "state_compose"
        private const val FILE_STATE_PNG       = "ink_state.png"
        private const val FILE_STATE_JSON      = "ink_state_strokes.json"
    }
}

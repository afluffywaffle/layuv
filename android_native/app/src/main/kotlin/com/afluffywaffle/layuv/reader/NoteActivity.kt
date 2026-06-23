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
import android.view.View
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
    private lateinit var tagLabel: TextView      // the '#' / tag-label text inside the wrapper
    private var toolPickerPopup: PopupWindow? = null
    private var tagPickerPopup: PopupWindow? = null

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
            if (composeEditIndex in threadEntries.indices) composeButton.text = "Update"
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
    override fun onBackPressed() = handleBack()

    override fun onPause() {
        super.onPause()
        // Proactively tear down transient UI so nothing leaks a window/listener on
        // finish: the toolbar pickers and the entry-detail overlay.
        toolPickerPopup?.dismiss(); toolPickerPopup = null
        tagPickerPopup?.dismiss(); tagPickerPopup = null
        dismissEntryDetail()
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
            setPadding(dp(20f), dp(12f), dp(20f), dp(12f))
        }
        top.addView(buildComposeRow(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
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
     * Save), then a right cluster of tool · tag · paste · add. The compose field
     * below gets the full width because paste + add moved up here.
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
        return header
    }

    /** Clipboard paste icon button (toolbar) — wraps pasted text in quotes. */
    private fun buildPasteButton(): View {
        val btn = ImageView(this).apply {
            setImageResource(R.drawable.ic_paste)
            setColorFilter(ReaderTheme.INK_54)
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
        btn.setOnTouchListener(PenTapListener(this) { pasteClipboardWithQuotes() })
        return btn
    }

    /** The compose commit button (toolbar) — "Add" normally, "Update" when editing. */
    private fun buildAddButton(): TextView {
        val btn = TextView(this).apply {
            text = "Add"
            typeface = ReaderTheme.bodyBold(this@NoteActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(ReaderTheme.PAPER)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = ReaderTheme.dp(this@NoteActivity, ReaderTheme.RADIUS_BTN)
                setColor(ReaderTheme.INK_87)
            }
            setPadding(dp(18f), dp(10f), dp(18f), dp(10f))
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
    private fun buildTagButton(): View {
        tagLabel = TextView(this).apply {
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
            addView(tagLabel, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        }
        tagButton = wrap
        wrap.setOnTouchListener(PenTapListener(this) { showTagPicker(wrap) })
        return wrap
    }

    private fun refreshToolSelection() {
        toolButton?.invalidate()
    }

    private fun refreshTagSelection() {
        val wrap = tagButton ?: return
        val tag = selectedTag
        tagLabel.text = if (tag != null) tagLabels[tag] else "#"
        wrap.background = chipBackground(tag != null)
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
        if (next != null && next != prev && composeField.text.isEmpty()) {
            composeField.setText(tagPrompts[next])
            composeField.setSelection(composeField.text.length)
        }
    }

    private fun chipBackground(selected: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = ReaderTheme.dp(this@NoteActivity, ReaderTheme.RADIUS_BTN)
        setColor(if (selected) 0 else ReaderTheme.FILL_06)
        setStroke(dp(if (selected) 2f else 1f), if (selected) ReaderTheme.INK_87 else ReaderTheme.INK_26)
    }

    private fun popupBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = ReaderTheme.dp(this@NoteActivity, ReaderTheme.RADIUS_BTN)
        setColor(ReaderTheme.PAPER)
        setStroke(dp(1f), ReaderTheme.INK_26)
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
                metaOverride = "Highlighted passage",
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
            this.text = "Highlighted passage"
            typeface = ReaderTheme.chrome(this@NoteActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(ReaderTheme.INK_38)
            setPadding(dp(14f), dp(4f), 0, 0)
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        return row
    }

    /** Compose field with the Add/Update button beside it (paste lives in the toolbar). */
    private fun buildComposeRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        // Anonymous subclass so the system context-menu paste also goes through
        // pasteClipboardWithQuotes(), keeping both paths in sync.
        composeField = object : EditText(this) {
            override fun onTextContextMenuItem(id: Int): Boolean {
                if (id == android.R.id.paste || id == android.R.id.pasteAsPlainText) {
                    pasteClipboardWithQuotes()
                    return true
                }
                return super.onTextContextMenuItem(id)
            }
        }.apply {
            typeface = ReaderTheme.body(this@NoteActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, bodySizeSp)
            setTextColor(ReaderTheme.INK_87)
            setHintTextColor(0xFF9E9A92.toInt())
            setHighlightColor(android.graphics.Color.argb(60, 0, 0, 0))
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
        row.addView(composeField, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        // Add/Update sits to the right of the field — the commit action stays next
        // to where the user is typing.
        composeButton = buildAddButton()
        row.addView(
            composeButton,
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).also { it.leftMargin = dp(10f) },
        )
        return row
    }

    /** Pulls clipboard text, wraps it in quotes, and inserts at the compose cursor. */
    private fun pasteClipboardWithQuotes() {
        val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
            ?: return
        val start = composeField.selectionStart.coerceIn(0, composeField.text.length)
        val end   = composeField.selectionEnd.coerceIn(start, composeField.text.length)
        composeField.text.replace(start, end, "\"${clip.trim()}\" ")
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
        composeButton.text = "Add"
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
        pager.addView(textButton("← Prev") {
            if (threadPage > 0) { threadPage--; renderPane() }
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        pager.addView(TextView(this).apply {
            text = "${threadPage + 1} / $totalPages"
            typeface = ReaderTheme.body(this@NoteActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(ReaderTheme.INK_54)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        pager.addView(textButton("Next →") {
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
        // "Update" (not "Save") so it never collides with the toolbar's Save button.
        composeButton.text = "Update"
        renderPane() // re-highlight the edited row
    }

    private fun startReply(entry: ThreadEntry) {
        // Insert the first ~8 words of the entry as a quote; the user types after it.
        // The quote + response is stored as one flat new entry — no structural threading.
        val words = entry.text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val snippet = words.take(8).joinToString(" ")
        val quoted = "\"" + snippet + (if (words.size > 8) "…" else "") + "\" "
        composeEditIndex = -1
        composeButton.text = "Add"
        composeField.setText(quoted)
        composeField.setSelection(composeField.text.length)
        composeField.requestFocus()
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
                            composeButton.text = "Add"
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

    /**
     * Selectable TextView that draws a dotted underline for the selected range
     * instead of Android's default fill highlight. Matches the reader's active
     * selection style so the two surfaces feel consistent on e-ink.
     */
    private inner class SelectableBodyText(context: android.content.Context) : TextView(context) {
        private val dottedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = ReaderTheme.INK
            strokeWidth = ReaderTheme.dp(context, ReaderTheme.UNDERLINE_STROKE_DP)
            pathEffect = DashPathEffect(
                floatArrayOf(
                    ReaderTheme.dp(context, ReaderTheme.UNDERLINE_DASH_ON_DP),
                    ReaderTheme.dp(context, ReaderTheme.UNDERLINE_DASH_OFF_DP),
                ),
                0f,
            )
        }
        private val selPath = Path()

        private var selectionPopup: PopupWindow? = null
        private val popupHandler = Handler(Looper.getMainLooper())
        private var pendingPopup: Runnable? = null

        init {
            setTextIsSelectable(true)
            highlightColor = 0 // suppress fill; dotted underline drawn in onDraw
            // Replace the system floating ActionMode (Copy/Share/Select all) with our
            // own themed popup shown via onSelectionChanged. Returning true from
            // onCreateActionMode lets selection proceed; clearing the menu in
            // onPrepareActionMode suppresses the floating toolbar.
            setCustomSelectionActionModeCallback(object : ActionMode.Callback {
                override fun onCreateActionMode(mode: ActionMode, menu: Menu) = true
                override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                    menu.clear(); return true
                }
                override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = false
                override fun onDestroyActionMode(mode: ActionMode) {}
            })
        }

        override fun onSelectionChanged(selStart: Int, selEnd: Int) {
            super.onSelectionChanged(selStart, selEnd)
            pendingPopup?.let { popupHandler.removeCallbacks(it) }
            pendingPopup = null
            if (selStart < selEnd) {
                val r = Runnable { if (isAttachedToWindow) showSelectionPopup() }
                pendingPopup = r
                popupHandler.postDelayed(r, 150L)
            } else {
                dismissSelectionPopup()
            }
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            pendingPopup?.let { popupHandler.removeCallbacks(it) }
            dismissSelectionPopup()
        }

        private fun idp(v: Float) = ReaderTheme.dp(context, v).toInt()

        private fun showSelectionPopup() {
            dismissSelectionPopup()
            val ctx = context

            val content = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = ReaderTheme.dp(ctx, ReaderTheme.RADIUS_BTN)
                    setColor(ReaderTheme.PAPER)
                    setStroke(idp(1f), ReaderTheme.INK_26)
                }
            }

            fun popBtn(label: String, action: () -> Unit) = TextView(ctx).apply {
                text = label
                typeface = ReaderTheme.chromeBold(ctx)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(ReaderTheme.INK_87)
                gravity = Gravity.CENTER
                setPadding(idp(20f), idp(12f), idp(20f), idp(12f))
                minimumHeight = idp(48f)
                setOnTouchListener(PenTapListener(ctx) { action() })
            }

            content.addView(popBtn("Copy") {
                val s = selectionStart; val e = selectionEnd
                if (s < e) {
                    val copied = this.text.subSequence(s, e).toString()
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("", copied))
                }
                dismissSelectionPopup()
            })

            // Vertical divider between Copy and Select all.
            content.addView(View(ctx).apply {
                setBackgroundColor(ReaderTheme.INK_26)
                layoutParams = LinearLayout.LayoutParams(idp(1f), MATCH_PARENT).also { lp ->
                    lp.topMargin = idp(10f); lp.bottomMargin = idp(10f)
                }
            })

            content.addView(popBtn("Select all") {
                // onTextContextMenuItem casts mText to Spannable (not Editable),
                // which works correctly when setTextIsSelectable buffers as SPANNABLE.
                this@SelectableBodyText.onTextContextMenuItem(android.R.id.selectAll)
            })

            val popup = PopupWindow(content, WRAP_CONTENT, WRAP_CONTENT, true).apply {
                elevation = ReaderTheme.dp(ctx, 4f)
                isOutsideTouchable = true
                setBackgroundDrawable(null)
            }

            // Position the popup just above the selected text's first line.
            val l = layout
            val screenLoc = IntArray(2).also { getLocationInWindow(it) }
            val xScreen: Int
            val yScreen: Int
            if (l != null) {
                val anchorOff = minOf(selectionStart, selectionEnd).coerceAtLeast(0)
                val line = l.getLineForOffset(anchorOff)
                val lineTop = totalPaddingTop + l.getLineTop(line)
                xScreen = (screenLoc[0] + totalPaddingLeft +
                    l.getPrimaryHorizontal(anchorOff).toInt())
                    .coerceIn(screenLoc[0], screenLoc[0] + width - idp(140f))
                yScreen = screenLoc[1] + lineTop - idp(60f) // ~60dp above the line
            } else {
                xScreen = screenLoc[0] + idp(16f)
                yScreen = screenLoc[1] - idp(60f)
            }

            selectionPopup = popup
            popup.showAtLocation(this, Gravity.NO_GRAVITY, xScreen, yScreen.coerceAtLeast(0))
        }

        private fun dismissSelectionPopup() {
            selectionPopup?.let { if (it.isShowing) it.dismiss() }
            selectionPopup = null
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val start = selectionStart
            val end = selectionEnd
            val l = layout ?: return
            if (start >= end) return
            val underlineOffset = ReaderTheme.dp(context, ReaderTheme.UNDERLINE_OFFSET_DP)
            canvas.save()
            canvas.translate(totalPaddingLeft.toFloat(), totalPaddingTop.toFloat())
            val firstLine = l.getLineForOffset(start)
            val lastLine = l.getLineForOffset((end - 1).coerceAtLeast(start))
            for (line in firstLine..lastLine) {
                val ls = maxOf(start, l.getLineStart(line))
                val le = minOf(end, l.getLineEnd(line))
                if (ls >= le) continue
                var x0 = l.getPrimaryHorizontal(ls)
                var x1 = l.getPrimaryHorizontal(le)
                if (x1 <= x0) x1 = l.getLineRight(line)
                if (x1 < x0) { val t = x0; x0 = x1; x1 = t }
                val y = l.getLineBaseline(line).toFloat() + underlineOffset
                selPath.rewind()
                selPath.moveTo(x0, y)
                selPath.lineTo(x1, y)
                canvas.drawPath(selPath, dottedPaint)
            }
            canvas.restore()
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
        val prevBtn = textButton("← Prev") {}
        val nextBtn = textButton("Next →") {}
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
    // Widget helpers
    // -------------------------------------------------------------------------

    private fun pillButton(label: String, filled: Boolean, onTap: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            typeface = ReaderTheme.bodyBold(this@NoteActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(if (filled) ReaderTheme.PAPER else ReaderTheme.INK_87)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = ReaderTheme.dp(this@NoteActivity, ReaderTheme.RADIUS_BTN)
                setColor(if (filled) ReaderTheme.INK_87 else ReaderTheme.FILL_04)
                setStroke(dp(1f), ReaderTheme.INK_87)
            }
            setPadding(dp(20f), dp(10f), dp(20f), dp(10f))
            minimumHeight = dp(48f)
            setOnTouchListener(PenTapListener(this@NoteActivity, onTap = onTap))
        }

    private fun smallAction(label: String, onTap: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            typeface = ReaderTheme.bodyBold(this@NoteActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(ReaderTheme.INK_87)
            gravity = Gravity.CENTER
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            minimumHeight = dp(48f)
            setOnTouchListener(PenTapListener(this@NoteActivity, onTap = onTap))
        }

    private fun textButton(label: String, onTap: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            // Pager Prev/Next are primary navigation — bold for e-ink legibility.
            typeface = ReaderTheme.bodyBold(this@NoteActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(ReaderTheme.INK_87)
            gravity = Gravity.CENTER
            setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
            minimumHeight = dp(48f)
            setOnTouchListener(PenTapListener(this@NoteActivity, onTap = onTap))
        }

    private fun hDivider(): View = View(this).apply { setBackgroundColor(ReaderTheme.INK_12) }
    private fun rowDivider(): View = View(this).apply {
        setBackgroundColor(0x14000000)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(1f))
    }
    private fun dp(v: Float): Int = ReaderTheme.dp(this, v).toInt()

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

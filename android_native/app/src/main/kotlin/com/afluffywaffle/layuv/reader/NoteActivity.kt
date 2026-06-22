package com.afluffywaffle.layuv.reader

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
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

    private val toolContainers = mutableMapOf<AnnotationTool, FrameLayout>()
    private val tagViews = mutableMapOf<AnnotationTag, Pair<FrameLayout, TextView>>()

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
            if (composeEditIndex in threadEntries.indices) composeButton.text = "Save"
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

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(12f), dp(20f), dp(12f))
        }
        top.addView(buildSelectorRow(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        top.addView(space(10f), LinearLayout.LayoutParams(MATCH_PARENT, dp(10f)))
        if (selectedText.isNotEmpty()) {
            top.addView(buildQuoteBox(selectedText), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            top.addView(space(10f), LinearLayout.LayoutParams(MATCH_PARENT, dp(10f)))
        }
        top.addView(buildComposeRow(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(top, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        root.addView(hDivider(), LinearLayout.LayoutParams(MATCH_PARENT, dp(1f)))
        root.addView(buildTabBar(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(hDivider(), LinearLayout.LayoutParams(MATCH_PARENT, dp(1f)))

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
        root.addView(paneContainer, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        return root
    }

    /** Top bar: back arrow on the left, Save pill on the right (no title). */
    private fun buildHeader(): View {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4f), dp(8f), dp(12f), dp(4f))
        }
        header.addView(
            ChromeIconButton(this, R.drawable.ic_arrow_back) { handleBack() },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT),
        )
        // Invisible spacer pushes the Save pill to the right edge.
        header.addView(View(this), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        val saveBtn = TextView(this).apply {
            text = "Save"
            typeface = ReaderTheme.bodyBold(this@NoteActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(ReaderTheme.PAPER)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = ReaderTheme.dp(this@NoteActivity, ReaderTheme.RADIUS_BTN)
                setColor(ReaderTheme.INK_87)
            }
            setPadding(dp(20f), dp(10f), dp(20f), dp(10f))
            minimumHeight = dp(48f)
        }
        saveBtn.setOnTouchListener(PenTapListener(this) { onSave() })
        header.addView(saveBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        return header
    }

    /** Tool icon chips and tag pills share one scrollable row to save vertical space. */
    private fun buildSelectorRow(): View {
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        for (tool in selectorTools) {
            val frame = FrameLayout(this).apply { background = chipBackground(false) }
            frame.addView(ToolIconView(this, tool), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            frame.setOnTouchListener(PenTapListener(this) {
                selectedTool = tool
                refreshToolSelection()
            })
            toolContainers[tool] = frame
            inner.addView(frame, LinearLayout.LayoutParams(dp(48f), dp(48f)).also { it.rightMargin = dp(8f) })
        }
        refreshToolSelection()

        // Thin divider between tools and tags.
        inner.addView(
            View(this).apply { setBackgroundColor(ReaderTheme.INK_12) },
            LinearLayout.LayoutParams(dp(1f), dp(40f)).also { it.rightMargin = dp(8f); it.leftMargin = dp(4f) },
        )

        for (tag in AnnotationTag.entries) {
            val label = TextView(this).apply {
                text = tagLabels[tag]
                typeface = ReaderTheme.bodyBold(this@NoteActivity)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, chromeSizeSp)
                setPadding(dp(14f), dp(7f), dp(14f), dp(7f))
                setTextColor(ReaderTheme.INK_87)
            }
            val frame = FrameLayout(this).apply { background = tagBackground(false) }
            frame.addView(label, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            tagViews[tag] = Pair(frame, label)
            frame.setOnTouchListener(PenTapListener(this) { onTagTapped(tag) })
            inner.addView(frame, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).also { it.rightMargin = dp(8f) })
        }

        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        scroll.addView(inner, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        return scroll
    }

    private fun refreshToolSelection() {
        for ((tool, frame) in toolContainers) frame.background = chipBackground(tool == selectedTool)
    }

    private fun refreshTagSelection() {
        for ((tag, pair) in tagViews) pair.first.background = tagBackground(tag == selectedTag)
    }

    private fun chipBackground(selected: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = ReaderTheme.dp(this@NoteActivity, ReaderTheme.RADIUS_BTN)
        setColor(if (selected) 0 else ReaderTheme.FILL_06)
        setStroke(dp(if (selected) 2f else 1f), if (selected) ReaderTheme.INK_87 else ReaderTheme.INK_26)
    }

    private fun onTagTapped(tag: AnnotationTag) {
        val wasSelected = selectedTag == tag
        selectedTag = if (wasSelected) null else tag
        for ((t, pair) in tagViews) pair.first.background = tagBackground(selectedTag == t)
        if (!wasSelected && composeField.text.isEmpty()) {
            composeField.setText(tagPrompts[tag])
            composeField.setSelection(composeField.text.length)
        }
    }

    private fun tagBackground(selected: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = ReaderTheme.dp(this@NoteActivity, ReaderTheme.RADIUS_TAG)
        setColor(if (selected) 0 else ReaderTheme.FILL_08)
        if (selected) setStroke(dp(2f), ReaderTheme.INK_87)
    }

    private fun buildQuoteBox(text: String): View {
        val frame = FrameLayout(this)
        frame.addView(
            View(this).apply { setBackgroundColor(ReaderTheme.INK_38) },
            FrameLayout.LayoutParams(dp(3f), FrameLayout.LayoutParams.MATCH_PARENT),
        )
        frame.addView(TextView(this).apply {
            this.text = text.take(200)
            typeface = ReaderTheme.bodyItalic(this@NoteActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, (bodySizeSp - 1f).coerceAtLeast(14f))
            setTextColor(ReaderTheme.INK_87)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(14f), dp(4f), 0, dp(4f))
        }, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return frame
    }

    /** Compose field + Add/Save button — feeds the thread. */
    private fun buildComposeRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        composeField = EditText(this).apply {
            typeface = ReaderTheme.body(this@NoteActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, bodySizeSp)
            setTextColor(ReaderTheme.INK_87)
            setHintTextColor(0xFF9E9A92.toInt())
            setHighlightColor(android.graphics.Color.argb(60, 0, 0, 0)) // e-ink-safe light grey
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

        composeButton = TextView(this).apply {
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
        composeButton.setOnTouchListener(PenTapListener(this) { commitCompose() })
        row.addView(
            composeButton,
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).also { it.leftMargin = dp(10f) },
        )
        return row
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
        threadTab = tabButton("Thread") { selectTab(Tab.THREAD) }
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

        val textCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textCol.addView(TextView(this).apply {
            text = entry.text
            typeface = ReaderTheme.body(this@NoteActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            // Imported Word comments are read-only: render a touch lighter as a hint.
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
        if (!isWord) {
            // Leamh entries: tap the text to edit it in the compose field.
            textCol.isClickable = true
            textCol.setOnTouchListener(PenTapListener(this) { startEditEntry(index) })
        }
        row.addView(textCol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        row.addView(smallAction("Reply") { startReply(entry) },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        if (!isWord) {
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
        composeButton.text = "Save"
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
        val available = if (availablePaneHeight > 0) {
            // Measured pane height (post soft-keyboard inset) minus the pager row.
            availablePaneHeight - dp(56f)
        } else {
            // Pre-measure fallback: full screen minus the fixed chrome above/below
            // (header, tool+tag row, quote, compose, dividers, tab bar, pagination).
            resources.displayMetrics.heightPixels - dp(56f + 64f + 76f + 92f + 52f + 56f)
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
    private fun space(@Suppress("UNUSED_PARAMETER") dp: Float): View = View(this)
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

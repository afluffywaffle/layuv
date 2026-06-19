package com.afluffywaffle.layuv.reader

import android.app.Activity
import android.content.Intent
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.widget.ImageView
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.afluffywaffle.layuv.R
import com.afluffywaffle.layuv.docx.model.AnnotationTag
import com.afluffywaffle.layuv.docx.model.AnnotationTool
import com.afluffywaffle.layuv.docx.model.newId
import java.io.File

/**
 * Full annotation editor — tool selector, quote box, ink capture, note field,
 * tag chips, save. Mirrors Flutter's AnnotationPanel (lib/reader/annotation_panel.dart).
 *
 * Extras in:
 *   EXTRA_NOTE          — existing note text (optional, for edit flow)
 *   EXTRA_SELECTED_TEXT — the highlighted passage
 *   EXTRA_INITIAL_TOOL  — AnnotationTool.name to pre-select (comment → highlight)
 *
 * Extras out (RESULT_OK):
 *   EXTRA_NOTE         — trimmed note text
 *   EXTRA_RESULT_TOOL  — AnnotationTool.name chosen at Save
 *   EXTRA_RESULT_TAG   — AnnotationTag.name (nullable)
 *   EXTRA_INK_PNG      — ByteArray (only if ink was captured)
 *   EXTRA_INK_ID       — String pre-allocated ID (only if ink was captured)
 */
class NoteActivity : Activity() {

    private lateinit var editText: EditText
    private var selectedTool = AnnotationTool.highlight
    private var selectedTag: AnnotationTag? = null
    private var capturedInkBytes: ByteArray? = null
    private var capturedStrokeJson: String? = null
    private var inkId: String? = null
    private var initialNote = ""
    private var initialInkRef: ByteArray? = null

    // Derived from the user's font-size pref — set before buildUi() is called.
    private var bodySizeSp   = ReaderTheme.BODY_TEXT_SP
    private var chromeSizeSp = 15f

    private val toolContainers = mutableMapOf<AnnotationTool, FrameLayout>()
    private lateinit var inkButton: LinearLayout
    private lateinit var inkLabel: TextView
    private lateinit var inkIconView: ImageView
    private val tagViews = mutableMapOf<AnnotationTag, Pair<FrameLayout, TextView>>()

    private val selectorTools = listOf(
        AnnotationTool.highlight,
        AnnotationTool.underline,
        AnnotationTool.doubleUnderline,
        AnnotationTool.strikethrough,
        AnnotationTool.bookmark,
    )

    private val tagLabels = mapOf(
        AnnotationTag.voice       to "Voice",
        AnnotationTag.pacing      to "Pacing",
        AnnotationTag.continuity  to "Continuity",
        AnnotationTag.query       to "Query",
    )
    private val tagPrompts = mapOf(
        AnnotationTag.voice       to "This doesn't sound like [character] because…",
        AnnotationTag.pacing      to "This section feels too [fast/slow] because…",
        AnnotationTag.continuity  to "Possible inconsistency with…",
        AnnotationTag.query       to "Question: …",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val existingNote  = intent.getStringExtra(EXTRA_NOTE)
        val selectedText  = intent.getStringExtra(EXTRA_SELECTED_TEXT) ?: ""
        val rawTool       = AnnotationTool.fromName(intent.getStringExtra(EXTRA_INITIAL_TOOL))
        selectedTool      = if (rawTool == AnnotationTool.comment) AnnotationTool.highlight else rawTool

        // Sync font prefs so the panel matches the reader's current typography.
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        ReaderTheme.bodyFont = prefs.getString(KEY_BODY_FONT, "literata") ?: "literata"
        bodySizeSp   = ReaderTheme.bodySizeSp(prefs.getString(KEY_FONT_SIZE, "medium") ?: "medium")
        chromeSizeSp = (bodySizeSp * 0.82f).coerceIn(14f, 18f)

        // Pre-load existing ink from the annotation being edited.
        // Large data arrives via cache files (survives process death; avoids Binder IPC limit).
        val initialInk   = readTempBytes(FILE_LAUNCH_PNG)
        val initialInkId = intent.getStringExtra(EXTRA_INITIAL_INK_ID)
        // Always restore the original annotation ID — whether we have PNG bytes or stroke
        // JSON, the ink must be saved back under the same ID so the annotation finds it.
        if (initialInkId != null) inkId = initialInkId
        if (initialInk != null)   capturedInkBytes = initialInk
        capturedStrokeJson = readTempText(FILE_LAUNCH_JSON)
        initialNote   = existingNote?.trim() ?: ""
        initialInkRef = capturedInkBytes

        setContentView(buildUi(existingNote, selectedText))

        if (initialInk != null) updateInkButton(true)

        if (!existingNote.isNullOrEmpty()) {
            editText.setText(existingNote)
            editText.setSelection(existingNote.length)
        }
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
                    updateInkButton(true)
                }
                strokeJson?.let { capturedStrokeJson = it }
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
        val noteChanged = editText.text.toString().trim() != initialNote
        val inkChanged  = capturedInkBytes !== initialInkRef
        return noteChanged || inkChanged
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
        val note = editText.text.toString().trim()
        val ink = capturedInkBytes
        val id  = inkId
        // Write large data to cache files to avoid Binder IPC size limit.
        writeTempBytes(FILE_RESULT_PNG, ink)
        writeTempText(FILE_RESULT_JSON, capturedStrokeJson)
        val result = Intent()
            .putExtra(EXTRA_NOTE, note)
            .putExtra(EXTRA_RESULT_TOOL, selectedTool.name)
            .putExtra(EXTRA_RESULT_TAG, selectedTag?.name)
        if (id != null) result.putExtra(EXTRA_INK_ID, id) // small string, safe in extras
        setResult(RESULT_OK, result)
        finish()
    }

    // -------------------------------------------------------------------------
    // UI build
    // -------------------------------------------------------------------------

    private fun buildUi(existingNote: String?, selectedText: String): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ReaderTheme.PAPER)
            isFocusable = true
            isFocusableInTouchMode = true
        }

        root.addView(buildHeader(existingNote), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(hDivider(), LinearLayout.LayoutParams(MATCH_PARENT, dp(1f)))

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(16f), dp(20f), dp(16f))
        }

        body.addView(buildToolSelector(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        body.addView(vSpace(12f), LinearLayout.LayoutParams(MATCH_PARENT, dp(12f)))

        if (selectedText.isNotEmpty()) {
            body.addView(buildQuoteBox(selectedText), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            body.addView(vSpace(12f), LinearLayout.LayoutParams(MATCH_PARENT, dp(12f)))
        }

        body.addView(buildInkButton(selectedText), LinearLayout.LayoutParams(MATCH_PARENT, dp(48f)))
        body.addView(vSpace(12f), LinearLayout.LayoutParams(MATCH_PARENT, dp(12f)))

        editText = buildNoteField()
        body.addView(editText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        body.addView(vSpace(12f), LinearLayout.LayoutParams(MATCH_PARENT, dp(12f)))

        body.addView(buildTagRow(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val scroll = ScrollView(this)
        scroll.addView(body, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        return root
    }

    private fun buildHeader(existingNote: String?): View {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4f), dp(8f), dp(12f), dp(4f))
        }
        header.addView(
            ChromeIconButton(this, R.drawable.ic_arrow_back) { handleBack() },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT),
        )
        header.addView(TextView(this).apply {
            text = if (existingNote.isNullOrEmpty()) "Add note" else "Edit note"
            typeface = ReaderTheme.bodyBold(this@NoteActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(ReaderTheme.INK_87)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        // Save pill — inset from the right edge, can't be accidentally bumped.
        val saveBtn = TextView(this).apply {
            text = if (existingNote.isNullOrEmpty()) "Save" else "Update"
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
            minimumHeight = dp(44f)
        }
        saveBtn.setOnTouchListener(PenTapListener(this) { onSave() })
        header.addView(saveBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        return header
    }

    /** Horizontal scrollable row of 5 tool icon chips with border-swap selection. */
    private fun buildToolSelector(): View {
        val inner = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (tool in selectorTools) {
            val frame = FrameLayout(this).apply {
                background = chipBackground(false)
            }
            frame.addView(
                ToolIconView(this, tool),
                FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT),
            )
            frame.setOnTouchListener(PenTapListener(this) {
                selectedTool = tool
                refreshToolSelection()
            })
            toolContainers[tool] = frame
            inner.addView(frame, LinearLayout.LayoutParams(dp(48f), dp(48f)).also {
                it.rightMargin = dp(8f)
            })
        }
        refreshToolSelection()
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        scroll.addView(inner, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        return scroll
    }

    private fun refreshToolSelection() {
        for ((tool, frame) in toolContainers) {
            val selected = tool == selectedTool
            frame.background = chipBackground(selected)
        }
    }

    private fun chipBackground(selected: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = ReaderTheme.dp(this@NoteActivity, ReaderTheme.RADIUS_BTN)
        setColor(if (selected) 0 else ReaderTheme.FILL_06)
        setStroke(
            dp(if (selected) 2f else 1f),
            if (selected) ReaderTheme.INK_87 else ReaderTheme.INK_26,
        )
    }

    private fun buildQuoteBox(selectedText: String): View {
        val frame = FrameLayout(this)
        frame.addView(View(this).apply {
            setBackgroundColor(ReaderTheme.INK_38)
        }, FrameLayout.LayoutParams(dp(3f), FrameLayout.LayoutParams.MATCH_PARENT))
        frame.addView(TextView(this).apply {
            text = selectedText.take(200)
            typeface = ReaderTheme.bodyItalic(this@NoteActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, (bodySizeSp - 1f).coerceAtLeast(14f))
            setTextColor(ReaderTheme.INK_87)
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(14f), dp(4f), 0, dp(4f))
        }, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return frame
    }

    private fun buildInkButton(selectedText: String): LinearLayout {
        inkIconView = ImageView(this).apply {
            setImageResource(R.drawable.ic_edit_outline)
            setColorFilter(ReaderTheme.INK_87, PorterDuff.Mode.SRC_IN)
        }
        val iconSz = dp(20f)
        inkLabel = TextView(this).apply {
            text = "Add ink"
            typeface = ReaderTheme.bodyBold(this@NoteActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, chromeSizeSp)
            setTextColor(ReaderTheme.INK_87)
        }
        val btn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = inkBackground(false)
        }
        btn.addView(inkIconView, LinearLayout.LayoutParams(iconSz, iconSz).also { it.rightMargin = dp(8f) })
        btn.addView(inkLabel)
        btn.setOnTouchListener(PenTapListener(this) {
            if (inkId == null) inkId = newId()
            val sj = capturedStrokeJson
            // Write large data to cache files to avoid Binder IPC size limit.
            writeTempBytes(InkNoteActivity.FILE_LAUNCH_PNG, if (sj == null) capturedInkBytes else null)
            writeTempText(InkNoteActivity.FILE_LAUNCH_JSON, sj)
            val intent = Intent(this, InkNoteActivity::class.java)
                .putExtra(InkNoteActivity.EXTRA_SELECTED_TEXT, selectedText)
            startActivityForResult(intent, REQ_PANEL_INK)
        })
        inkButton = btn
        return btn
    }

    private fun updateInkButton(captured: Boolean) {
        val fg = if (captured) ReaderTheme.PAPER else ReaderTheme.INK_87
        inkLabel.text = if (captured) "Ink saved" else "Add ink"
        inkLabel.setTextColor(fg)
        inkIconView.setColorFilter(fg, PorterDuff.Mode.SRC_IN)
        inkButton.background = inkBackground(captured)
    }

    private fun inkBackground(captured: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = ReaderTheme.dp(this@NoteActivity, ReaderTheme.RADIUS_BTN)
        setColor(if (captured) ReaderTheme.INK_87 else ReaderTheme.FILL_04)
        setStroke(dp(1f), if (captured) ReaderTheme.INK_87 else ReaderTheme.INK_12)
    }

    private fun buildNoteField(): EditText = EditText(this).apply {
        typeface = ReaderTheme.body(this@NoteActivity)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, bodySizeSp)
        setTextColor(ReaderTheme.INK_87)
        setHintTextColor(0xFF9E9A92.toInt())
        hint = "Write your note… (optional)"
        minLines = 4
        gravity = Gravity.TOP or Gravity.START
        isFocusable = true
        isFocusableInTouchMode = true
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = ReaderTheme.dp(this@NoteActivity, ReaderTheme.RADIUS_BTN)
            setColor(ReaderTheme.FILL_04)
        }
        setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
    }

    /** Horizontal row of 4 tag pills with border-swap e-ink selection. */
    private fun buildTagRow(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
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
            row.addView(frame, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).also {
                it.rightMargin = dp(8f)
            })
        }
        return row
    }

    private fun onTagTapped(tag: AnnotationTag) {
        val wasSelected = selectedTag == tag
        selectedTag = if (wasSelected) null else tag
        for ((t, pair) in tagViews) {
            pair.first.background = tagBackground(selectedTag == t)
        }
        if (!wasSelected && editText.text.isEmpty()) {
            editText.setText(tagPrompts[tag])
            editText.setSelection(editText.text.length)
        }
    }

    private fun tagBackground(selected: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = ReaderTheme.dp(this@NoteActivity, ReaderTheme.RADIUS_TAG)
        setColor(if (selected) 0 else ReaderTheme.FILL_08)
        if (selected) setStroke(dp(2f), ReaderTheme.INK_87)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun hDivider(): View = View(this).apply { setBackgroundColor(ReaderTheme.INK_12) }
    private fun vSpace(@Suppress("UNUSED_PARAMETER") dp: Float): View = View(this)
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
        const val EXTRA_SELECTED_TEXT   = "selected_text"
        const val EXTRA_INITIAL_TOOL    = "initial_tool"
        const val EXTRA_RESULT_TOOL     = "result_tool"
        const val EXTRA_RESULT_TAG      = "result_tag"
        const val EXTRA_INK_PNG         = "ink_png"
        const val EXTRA_INK_ID          = "ink_id"
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
    }
}

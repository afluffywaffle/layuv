package com.afluffywaffle.layuv.reader

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.afluffywaffle.layuv.R
import com.afluffywaffle.layuv.docx.model.AnnotationTag
import com.afluffywaffle.layuv.docx.model.AnnotationTool
import com.afluffywaffle.layuv.docx.model.newId

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

    private val toolContainers = mutableMapOf<AnnotationTool, FrameLayout>()
    private lateinit var inkButton: LinearLayout
    private lateinit var inkLabel: TextView
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

        // Pre-load existing ink from the annotation being edited.
        // Large data comes via pendingLaunch (avoids Binder IPC size limit); Intent
        // extras are the fallback for any caller that hasn't been updated yet.
        val launch = NoteActivity.pendingLaunch
        NoteActivity.pendingLaunch = null
        val initialInk   = launch?.initialInkBytes ?: intent.getByteArrayExtra(EXTRA_INITIAL_INK_PNG)
        val initialInkId = launch?.initialInkId    ?: intent.getStringExtra(EXTRA_INITIAL_INK_ID)
        if (initialInk != null && initialInkId != null) {
            capturedInkBytes = initialInk
            inkId = initialInkId
        }
        capturedStrokeJson = launch?.strokeJson ?: intent.getStringExtra(EXTRA_INITIAL_STROKE_JSON)

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
                // Large data via static — avoids Binder IPC size limit.
                val inkResult = InkNoteActivity.pendingResult
                InkNoteActivity.pendingResult = null
                val bytes = inkResult?.pngBytes
                if (bytes != null && bytes.isNotEmpty()) {
                    capturedInkBytes = bytes
                    if (inkId == null) inkId = newId()
                    updateInkButton(true)
                }
                inkResult?.strokeJson?.let { capturedStrokeJson = it }
            }
        } else {
            @Suppress("DEPRECATION")
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    // -------------------------------------------------------------------------
    // Save
    // -------------------------------------------------------------------------

    private fun onSave() {
        val note = editText.text.toString().trim()
        val ink = capturedInkBytes
        val id  = inkId
        // Pass large data via static to avoid Binder IPC size limit.
        NoteActivity.pendingResult = NoteResult(inkBytes = ink, inkId = id, strokeJson = capturedStrokeJson)
        val result = Intent()
            .putExtra(EXTRA_NOTE, note)
            .putExtra(EXTRA_RESULT_TOOL, selectedTool.name)
            .putExtra(EXTRA_RESULT_TAG, selectedTag?.name)
        if (id != null) result.putExtra(EXTRA_INK_ID, id) // small string, safe to keep
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

        root.addView(hDivider(), LinearLayout.LayoutParams(MATCH_PARENT, dp(1f)))
        root.addView(buildSaveButton(existingNote), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        return root
    }

    private fun buildHeader(existingNote: String?): View {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4f), dp(8f), dp(16f), dp(4f))
        }
        header.addView(
            ChromeIconButton(this, R.drawable.ic_arrow_back) {
                setResult(RESULT_CANCELED)
                finish()
            },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT),
        )
        header.addView(TextView(this).apply {
            text = if (existingNote.isNullOrEmpty()) "Add note" else "Edit note"
            typeface = Typeface.create(ReaderTheme.body(context), Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(ReaderTheme.INK_87)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(ReaderTheme.INK_54)
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(14f), dp(4f), 0, dp(4f))
        }, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return frame
    }

    private fun buildInkButton(selectedText: String): LinearLayout {
        inkLabel = TextView(this).apply {
            text = "Add ink"
            typeface = ReaderTheme.body(this@NoteActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(ReaderTheme.INK_54)
        }
        val btn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = inkBackground(false)
        }
        btn.addView(inkLabel)
        btn.setOnTouchListener(PenTapListener(this) {
            if (inkId == null) inkId = newId()
            val sj = capturedStrokeJson
            // Pass large data via static to avoid Binder IPC size limit.
            InkNoteActivity.pendingLaunch = InkNoteActivity.InkLaunch(
                existingInkBytes = if (sj == null) capturedInkBytes else null,
                strokeJson = sj,
            )
            val intent = Intent(this, InkNoteActivity::class.java)
                .putExtra(InkNoteActivity.EXTRA_SELECTED_TEXT, selectedText)
            startActivityForResult(intent, REQ_PANEL_INK)
        })
        inkButton = btn
        return btn
    }

    private fun updateInkButton(captured: Boolean) {
        inkLabel.text   = if (captured) "Ink saved" else "Add ink"
        inkLabel.setTextColor(if (captured) ReaderTheme.PAPER else ReaderTheme.INK_54)
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
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
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
                typeface = ReaderTheme.body(this@NoteActivity)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(14f), dp(7f), dp(14f), dp(7f))
                setTextColor(ReaderTheme.INK_54)
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

    private fun buildSaveButton(existingNote: String?): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setBackgroundColor(ReaderTheme.INK_87)
        minimumHeight = dp(60f)
        isClickable = true
        isFocusable = true
        setOnTouchListener(PenTapListener(this@NoteActivity) { onSave() })
        addView(TextView(this@NoteActivity).apply {
            text = if (existingNote.isNullOrEmpty()) "Save" else "Update"
            typeface = ReaderTheme.body(this@NoteActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(ReaderTheme.PAPER)
        })
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun hDivider(): View = View(this).apply { setBackgroundColor(ReaderTheme.INK_12) }
    private fun vSpace(@Suppress("UNUSED_PARAMETER") dp: Float): View = View(this)
    private fun dp(v: Float): Int = ReaderTheme.dp(this, v).toInt()

    companion object {
        const val EXTRA_NOTE            = "note"
        const val EXTRA_SELECTED_TEXT   = "selected_text"
        const val EXTRA_INITIAL_TOOL    = "initial_tool"
        const val EXTRA_RESULT_TOOL     = "result_tool"
        const val EXTRA_RESULT_TAG      = "result_tag"
        const val EXTRA_INK_PNG             = "ink_png"
        const val EXTRA_INK_ID              = "ink_id"
        const val EXTRA_STROKE_JSON         = "stroke_json"
        const val EXTRA_INITIAL_STROKE_JSON = "initial_stroke_json"
        /** Optional: ByteArray of existing ink PNG to preload (edit flow). */
        const val EXTRA_INITIAL_INK_PNG = "initial_ink_png"
        /** Optional: annotation ID matching [EXTRA_INITIAL_INK_PNG]. */
        const val EXTRA_INITIAL_INK_ID  = "initial_ink_id"

        @Volatile var pendingLaunch: NoteLaunch? = null
        @Volatile var pendingResult: NoteResult? = null

        private const val REQ_PANEL_INK = 1008
    }

    /** Large data passed into NoteActivity — bypasses Binder IPC size limit. */
    data class NoteLaunch(
        val initialInkBytes: ByteArray? = null,
        val initialInkId: String? = null,
        val strokeJson: String? = null,
    )

    /** Large data returned from NoteActivity — bypasses Binder IPC size limit. */
    data class NoteResult(
        val inkBytes: ByteArray? = null,
        val inkId: String? = null,
        val strokeJson: String? = null,
    )
}

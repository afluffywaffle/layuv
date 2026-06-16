package com.afluffywaffle.layuv.reader

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.afluffywaffle.layuv.R
import com.afluffywaffle.layuv.docx.DocxStore
import com.afluffywaffle.layuv.docx.model.Annotation
import com.afluffywaffle.layuv.docx.model.AnnotationTool
import java.io.File
import java.util.concurrent.Executors

/**
 * Full-screen annotations panel (§2.7 of UI_PORT_PLAN.md).
 *
 * Tabs:  Marks (all non-bookmark) / Bookmarks
 * Marks: horizontal filter chips (per tool type present + "Has note") above a
 *        ScrollView whose annotations are grouped into sectioned tiles. Each
 *        section header (tool icon + label + count) is tappable to expand/collapse.
 * Edit mode: long-press any row → checkboxes appear, bottom bar shows
 *        "Cancel" and "Delete (N)" (greyscale, never red).
 */
class AnnotationsPanelActivity : Activity() {

    companion object {
        const val EXTRA_DOCX_PATH = "docx_path"
        const val EXTRA_FRACTION  = "fraction"
        private const val TAG = "LeamhAnnotPanel"
    }

    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var allAnnotations: List<Annotation> = emptyList()
    private var docxFile: File? = null
    private var docxBytes: ByteArray? = null

    // Filter chips — set of active tool-type filters (empty = show all)
    private val activeFilters = mutableSetOf<AnnotationTool>()
    private var filterHasNote = false

    // Expand/collapse per section (default: all expanded)
    private val sectionExpanded = mutableMapOf<AnnotationTool, Boolean>()

    // Edit mode
    private var editMode = false
    private val selectedIds = mutableSetOf<String>()

    // Root views rebuilt on data changes
    private lateinit var filterRow: HorizontalScrollView
    private lateinit var filterChips: LinearLayout
    private lateinit var mainScroll: ScrollView
    private lateinit var listContainer: LinearLayout
    private lateinit var bottomBar: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_DOCX_PATH) ?: run { finish(); return }
        docxFile = File(path)
        setContentView(buildUi())
        loadAnnotations(File(path))
    }

    override fun onDestroy() {
        super.onDestroy()
        ioExecutor.shutdownNow()
    }

    // -------------------------------------------------------------------------
    // UI skeleton (rebuilt once; inner content is rebuilt via rebuildContent)
    // -------------------------------------------------------------------------

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ReaderTheme.PAPER)
        }

        // Header
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4f), dp(8f), dp(16f), dp(4f))
        }
        headerRow.addView(
            ChromeIconButton(this, R.drawable.ic_arrow_back) { finish() },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT),
        )
        headerRow.addView(TextView(this).apply {
            text = "Annotations"
            typeface = ReaderTheme.bodyBold(context)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(ReaderTheme.INK_87)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        root.addView(headerRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        root.addView(hDivider(), LinearLayout.LayoutParams(MATCH_PARENT, dp(1f)))

        // Filter chips
        filterChips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8f), dp(8f), dp(8f), dp(6f))
        }
        filterRow = HorizontalScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isHorizontalScrollBarEnabled = false
            addView(filterChips, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
        root.addView(filterRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // Scrollable list area
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        mainScroll = ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(listContainer, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        root.addView(mainScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        root.addView(hDivider(), LinearLayout.LayoutParams(MATCH_PARENT, dp(1f)))

        // Bottom bar (edit controls or "N annotations" info)
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(ReaderTheme.PAPER)
            minimumHeight = dp(56f)
            setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
        }
        root.addView(bottomBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        return root
    }

    // -------------------------------------------------------------------------
    // Data loading
    // -------------------------------------------------------------------------

    private fun loadAnnotations(file: File) {
        ioExecutor.execute {
            try {
                val bytes = file.readBytes()
                val doc = DocxStore.load(bytes)
                main.post {
                    docxBytes = bytes
                    allAnnotations = doc.annotations.map { it.annotation }
                        .sortedBy { it.position }
                    refreshAll()
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadAnnotations failed", e)
                main.post {
                    Toast.makeText(this, "Could not read annotations.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Derived lists
    // -------------------------------------------------------------------------

    private fun marksAnnotations(): List<Annotation> {
        var list = allAnnotations.filter { it.tool != AnnotationTool.bookmark }
        if (activeFilters.isNotEmpty()) list = list.filter { it.tool in activeFilters }
        if (filterHasNote) list = list.filter { !it.note.isNullOrEmpty() }
        return list
    }

    private fun visibleAnnotations(): List<Annotation> = marksAnnotations()

    /** Tool types present in the marks list (for chip generation). */
    private fun presentMarkTools(): List<AnnotationTool> {
        val all = allAnnotations.filter { it.tool != AnnotationTool.bookmark }
        return AnnotationTool.entries
            .filter { it != AnnotationTool.bookmark && all.any { a -> a.tool == it } }
    }

    private fun hasAnyNote(): Boolean =
        allAnnotations.any { it.tool != AnnotationTool.bookmark && !it.note.isNullOrEmpty() }

    // -------------------------------------------------------------------------
    // Refresh
    // -------------------------------------------------------------------------

    private fun refreshAll() {
        updateFilterChips()
        rebuildList()
        updateBottomBar()
    }

    private fun updateFilterChips() {
        filterChips.removeAllViews()

        val tools = presentMarkTools()
        if (tools.isEmpty()) return

        // "All" chip — clears all filters
        val allActive = activeFilters.isEmpty() && !filterHasNote
        filterChips.addView(
            buildFilterChip("All", allActive) {
                activeFilters.clear()
                filterHasNote = false
                rebuildList()
                updateFilterChips()
                updateBottomBar()
            },
            chipLayoutParams(),
        )

        // Per-tool chips
        for (tool in tools) {
            val active = tool in activeFilters
            filterChips.addView(
                buildFilterChip(toolSectionLabel(tool), active) {
                    if (active) activeFilters.remove(tool) else activeFilters.add(tool)
                    rebuildList()
                    updateFilterChips()
                    updateBottomBar()
                },
                chipLayoutParams(),
            )
        }

        // "Has note" chip
        if (hasAnyNote()) {
            filterChips.addView(
                buildFilterChip("Has note", filterHasNote) {
                    filterHasNote = !filterHasNote
                    rebuildList()
                    updateFilterChips()
                    updateBottomBar()
                },
                chipLayoutParams(),
            )
        }
    }

    // -------------------------------------------------------------------------
    // List building — sectioned tiles
    // -------------------------------------------------------------------------

    private fun rebuildList() {
        listContainer.removeAllViews()

        val visible = visibleAnnotations()
        if (visible.isEmpty()) {
            listContainer.addView(emptyLabel())
            return
        }

        // If a filter is active: flat list, no sections
        val filtered = activeFilters.isNotEmpty() || filterHasNote
        if (filtered) {
            buildFlatList(visible)
        } else {
            buildSectionedList(visible)
        }
    }

    private fun buildFlatList(annotations: List<Annotation>) {
        for (ann in annotations) {
            listContainer.addView(rowDivider())
            listContainer.addView(buildRow(ann))
        }
    }

    private fun buildSectionedList(annotations: List<Annotation>) {
        // Group by tool in display order
        val order = listOf(
            AnnotationTool.highlight, AnnotationTool.underline,
            AnnotationTool.doubleUnderline, AnnotationTool.strikethrough,
            AnnotationTool.wavyUnderline, AnnotationTool.inkAnnotation,
            AnnotationTool.comment,
        )
        val grouped = LinkedHashMap<AnnotationTool, MutableList<Annotation>>()
        for (t in order) grouped[t] = mutableListOf()
        for (ann in annotations) grouped.getOrPut(ann.tool) { mutableListOf() }.add(ann)

        for ((tool, group) in grouped) {
            if (group.isEmpty()) continue
            val expanded = sectionExpanded.getOrDefault(tool, true)

            // Section header
            listContainer.addView(buildSectionHeader(tool, group.size, expanded))

            // Section rows (collapsible container)
            val rowsContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = if (expanded) View.VISIBLE else View.GONE
            }
            for (ann in group) {
                rowsContainer.addView(rowDivider())
                rowsContainer.addView(buildRow(ann))
            }
            listContainer.addView(rowsContainer)

            // Wire header tap to toggle rows
            val header = listContainer.getChildAt(listContainer.childCount - 2)
            header.setOnTouchListener(PenTapListener(this) {
                val nowExpanded = !sectionExpanded.getOrDefault(tool, true)
                sectionExpanded[tool] = nowExpanded
                rowsContainer.visibility = if (nowExpanded) View.VISIBLE else View.GONE
                // Refresh the header's chevron text in-place
                (header as? LinearLayout)?.let { h ->
                    val chevron = h.getChildAt(h.childCount - 1) as? TextView
                    chevron?.text = if (nowExpanded) "−" else "+"
                }
            })
        }
    }

    private fun buildSectionHeader(tool: AnnotationTool, count: Int, expanded: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0x0A000000) // FILL_04 (4% black tint)
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            minimumHeight = dp(44f)
            isClickable = true
            isFocusable = true
        }

        // Tool icon (28dp)
        row.addView(ToolIconView(this, tool).apply {
            layoutParams = LinearLayout.LayoutParams(dp(28f), dp(28f)).apply {
                marginEnd = dp(10f)
            }
        })

        // Section label + count
        row.addView(TextView(this).apply {
            text = "${toolSectionLabel(tool)} ($count)"
            typeface = ReaderTheme.bodyBold(context)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(ReaderTheme.INK_87)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        // Expand/collapse glyph
        row.addView(TextView(this).apply {
            text = if (expanded) "−" else "+"
            typeface = ReaderTheme.body(context)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(ReaderTheme.INK_54)
            gravity = Gravity.CENTER
            setPadding(dp(8f), 0, 0, 0)
        })

        return row
    }

    // -------------------------------------------------------------------------
    // Row building
    // -------------------------------------------------------------------------

    private fun buildRow(ann: Annotation): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            minimumHeight = dp(64f)
            isClickable = true
            isFocusable = true
            setBackgroundColor(ReaderTheme.PAPER)
        }

        if (editMode) {
            // Checkbox
            val checked = ann.id in selectedIds
            val cbIcon = if (checked) R.drawable.ic_check_box else R.drawable.ic_check_box_blank
            val cbView = ImageView(this).apply {
                setImageResource(cbIcon)
                setColorFilter(if (checked) ReaderTheme.INK_87 else ReaderTheme.INK_38)
                layoutParams = LinearLayout.LayoutParams(dp(32f), dp(32f)).apply {
                    marginEnd = dp(12f)
                }
            }
            row.addView(cbView)
        } else {
            // Tool icon
            row.addView(ToolIconView(this, ann.tool).apply {
                layoutParams = LinearLayout.LayoutParams(dp(40f), dp(40f)).apply {
                    marginEnd = dp(12f)
                }
            })
        }

        // Text column
        val textCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textCol.addView(TextView(this).apply {
            text = ann.selectedText.take(80)
            typeface = ReaderTheme.body(this@AnnotationsPanelActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(ReaderTheme.INK)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        val note = ann.note
        if (!note.isNullOrEmpty()) {
            textCol.addView(TextView(this).apply {
                text = note.take(60)
                typeface = ReaderTheme.bodyItalic(this@AnnotationsPanelActivity)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(ReaderTheme.INK_54)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
        row.addView(textCol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        if (editMode) {
            row.setOnTouchListener(PenTapListener(this) {
                if (ann.id in selectedIds) selectedIds.remove(ann.id)
                else selectedIds.add(ann.id)
                rebuildList()
                updateBottomBar()
            })
        } else {
            row.setOnTouchListener(PenTapListener(this) {
                setResult(RESULT_OK, Intent().putExtra(EXTRA_FRACTION, ann.position))
                finish()
            })
            row.setOnLongClickListener {
                enterEditMode(ann.id)
                true
            }
        }

        return row
    }

    // -------------------------------------------------------------------------
    // Edit mode
    // -------------------------------------------------------------------------

    private fun enterEditMode(firstSelected: String) {
        editMode = true
        selectedIds.clear()
        selectedIds.add(firstSelected)
        rebuildList()
        updateBottomBar()
    }

    private fun exitEditMode() {
        editMode = false
        selectedIds.clear()
        rebuildList()
        updateBottomBar()
    }

    private fun deleteSelected() {
        val ids = selectedIds.toSet()
        if (ids.isEmpty()) return
        val file = docxFile ?: return
        LeamhDialog.confirmDelete(
            context = this,
            message = "Delete ${ids.size} annotation${if (ids.size == 1) "" else "s"}?",
            skipPrefKey = null,
            onConfirm = {
                // Route through the shared DocxWriteQueue so this delete is
                // serialized against every reader write and can never interleave
                // on the temp file or clobber a concurrent save.
                DocxWriteQueue.submit(
                    file,
                    transform = { base ->
                        // Re-derive from the CURRENT on-disk annotation set and
                        // remove only the selected ids — robust to any annotation
                        // the reader committed after this panel was opened.
                        val current = DocxStore.load(base).annotations.map { it.annotation }
                        DocxStore.write(base, current.filter { it.id !in ids })
                    },
                    onSuccess = { newBytes ->
                        val freshDoc = DocxStore.load(newBytes)
                        main.post {
                            docxBytes = newBytes
                            allAnnotations = freshDoc.annotations.map { it.annotation }
                                .sortedBy { it.position }
                            exitEditMode()
                            setResult(RESULT_FIRST_USER)
                            refreshAll()
                        }
                    },
                    onError = { e ->
                        Log.e(TAG, "deleteSelected failed", e)
                        main.post {
                            Toast.makeText(this, "Could not delete annotations.", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            },
        )
    }

    // -------------------------------------------------------------------------
    // Bottom bar
    // -------------------------------------------------------------------------

    private fun updateBottomBar() {
        bottomBar.removeAllViews()
        if (editMode) {
            buildEditBar()
        } else {
            buildInfoBar()
        }
    }

    private fun buildEditBar() {
        val cancelBtn = textButton("Cancel") { exitEditMode() }
        bottomBar.addView(cancelBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))

        bottomBar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))

        val n = selectedIds.size
        val deleteLabel = if (n == 0) "Delete" else "Delete ($n)"
        val deleteBtn = textButton(deleteLabel) { if (selectedIds.isNotEmpty()) deleteSelected() }
        deleteBtn.alpha = if (n > 0) 1f else 0.35f
        bottomBar.addView(deleteBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
    }

    private fun buildInfoBar() {
        val visible = visibleAnnotations()
        val label = when {
            visible.isEmpty() -> ""
            visible.size == 1 -> "1 annotation"
            else -> "${visible.size} annotations"
        }
        if (label.isNotEmpty()) {
            bottomBar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            bottomBar.addView(TextView(this).apply {
                text = label
                typeface = ReaderTheme.body(this@AnnotationsPanelActivity)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(ReaderTheme.INK_38)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            bottomBar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        }
    }

    // -------------------------------------------------------------------------
    // Widget helpers
    // -------------------------------------------------------------------------

    private fun buildFilterChip(label: String, active: Boolean, onClick: () -> Unit): View {
        return TextView(this).apply {
            text = label
            typeface = Typeface.create(ReaderTheme.body(context),
                if (active) Typeface.BOLD else Typeface.NORMAL)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(if (active) ReaderTheme.INK_87 else ReaderTheme.INK_54)
            gravity = Gravity.CENTER
            setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
            minimumHeight = dp(32f)
            // Rounded border (2dp solid INK_87 when active, 1dp INK_38 when inactive)
            val borderColor = if (active) ReaderTheme.INK_87 else ReaderTheme.INK_38
            background = buildChipDrawable(borderColor, if (active) 2 else 1)
            setOnTouchListener(PenTapListener(this@AnnotationsPanelActivity, onClick))
        }
    }

    private fun buildChipDrawable(
        borderColor: Int,
        borderWidthDp: Int,
    ): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            setColor(ReaderTheme.PAPER)
            setStroke(dp(borderWidthDp.toFloat()), borderColor)
            cornerRadius = dp(6f).toFloat()
        }
    }

    private fun chipLayoutParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            marginEnd = dp(8f)
        }

    private fun textButton(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            typeface = ReaderTheme.body(this@AnnotationsPanelActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(ReaderTheme.INK_87)
            gravity = Gravity.CENTER
            setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
            minimumHeight = dp(48f)
            setOnTouchListener(PenTapListener(this@AnnotationsPanelActivity, onClick))
        }

    private fun emptyLabel(): View = TextView(this).apply {
        text = "No marks yet."
        typeface = ReaderTheme.body(this@AnnotationsPanelActivity)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(ReaderTheme.INK_38)
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(40f)
        }
    }

    private fun hDivider(): View = View(this).apply { setBackgroundColor(ReaderTheme.INK_12) }
    private fun rowDivider(): View = View(this).apply {
        setBackgroundColor(0x14000000)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(1f))
    }
    private fun dp(v: Float): Int = ReaderTheme.dp(this, v).toInt()

    private fun toolSectionLabel(tool: AnnotationTool): String = when (tool) {
        AnnotationTool.highlight       -> "Highlights"
        AnnotationTool.underline       -> "Underlines"
        AnnotationTool.doubleUnderline -> "Double underlines"
        AnnotationTool.strikethrough   -> "Strikethrough"
        AnnotationTool.wavyUnderline   -> "Wavy underlines"
        AnnotationTool.bookmark        -> "Bookmarks"  // not shown; kept for exhaustive when
        AnnotationTool.inkAnnotation   -> "Ink notes"
        AnnotationTool.comment         -> "Comments"
    }
}

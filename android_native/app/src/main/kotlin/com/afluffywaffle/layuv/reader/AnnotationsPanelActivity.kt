package com.afluffywaffle.layuv.reader

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.afluffywaffle.layuv.docx.DocxStore
import com.afluffywaffle.layuv.docx.model.Annotation
import com.afluffywaffle.layuv.docx.model.AnnotationTool
import java.io.File
import java.util.concurrent.Executors

/**
 * Paged annotations list. Two sections toggled by a pair of buttons at the top:
 *   Marks    — all non-bookmark annotations (highlight, underline, comment, …)
 *   Bookmarks — bookmark annotations only
 *
 * No animated tabs, no scroll (e-ink rules). A fixed-height page of rows is shown
 * at a time; Prev / Next buttons navigate between pages.
 *
 * Each row: [ToolIconView] + selectedText snippet + optional note.
 *   Tap       → return EXTRA_FRACTION to the caller and finish.
 *   Long-press → confirm-delete dialog → write back to DOCX → refresh list.
 *
 * Caller passes the DOCX path and the current annotation list via extras:
 *   EXTRA_DOCX_PATH  — absolute path to the DOCX file (write-back target)
 *   EXTRA_FRACTIONS  — DoubleArray of annotation positions (same order as EXTRA_IDS)
 *   EXTRA_IDS        — StringArray of annotation IDs (same order as EXTRA_FRACTIONS)
 * On row tap the activity returns RESULT_OK with EXTRA_FRACTION set to the tapped
 * annotation's position fraction so [ReaderActivity] can jump there.
 */
class AnnotationsPanelActivity : Activity() {

    companion object {
        const val EXTRA_DOCX_PATH  = "docx_path"
        const val EXTRA_FRACTION   = "fraction"
        private const val TAG = "LeamhAnnotPanel"
        private const val PAGE_ROWS = 8
    }

    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    // Loaded from the DOCX on entry; refreshed after delete.
    private var allAnnotations: List<Annotation> = emptyList()
    private var docxFile: File? = null
    private var docxBytes: ByteArray? = null

    // Displayed list (filtered by section) and pagination state.
    private var showBookmarks = false
    private var currentPage = 0

    // Views mutated by buildList()
    private lateinit var listContainer: LinearLayout
    private lateinit var marksButton: Button
    private lateinit var bookmarksButton: Button
    private lateinit var prevButton: Button
    private lateinit var nextButton: Button
    private lateinit var pageLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_DOCX_PATH)
        if (path == null) {
            finish()
            return
        }
        docxFile = File(path)
        setContentView(buildUi())
        loadAnnotations(File(path))
    }

    // --- UI ------------------------------------------------------------------

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ReaderTheme.PAPER)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }

        // Header with an always-available exit. E-ink devices give the user no
        // reliable system Back affordance, so the panel MUST own its own close
        // button or the user is trapped (there are no rows to tap on an empty list).
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = dp(8f)
            setPadding(p, p, p, 0)
        }
        headerRow.addView(
            navButton("‹ Done") { finish() },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT),
        )
        headerRow.addView(TextView(this).apply {
            text = "Annotations"
            typeface = ReaderTheme.chrome(this@AnnotationsPanelActivity)
            setTextColor(ReaderTheme.INK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(8f) })
        root.addView(headerRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // Section toggle row
        val toggleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = dp(8f)
            setPadding(p, p, p, 0)
        }
        marksButton = sectionButton("Marks") {
            if (showBookmarks) { showBookmarks = false; currentPage = 0; refreshSection() }
        }
        bookmarksButton = sectionButton("Bookmarks") {
            if (!showBookmarks) { showBookmarks = true; currentPage = 0; refreshSection() }
        }
        toggleRow.addView(marksButton, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        toggleRow.addView(bookmarksButton, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        root.addView(toggleRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // Divider
        root.addView(View(this).apply {
            setBackgroundColor(0x26000000)
        }, LinearLayout.LayoutParams(MATCH_PARENT, dp(1f)))

        // Annotation rows (rebuilt by buildList())
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(listContainer, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // Pagination row
        val pageRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = dp(8f)
            setPadding(p, p, p, p)
        }
        prevButton = navButton("← Prev") {
            if (currentPage > 0) { currentPage--; buildList() }
        }
        nextButton = navButton("Next →") {
            val filtered = filteredAnnotations()
            val pages = pageCount(filtered.size)
            if (currentPage < pages - 1) { currentPage++; buildList() }
        }
        pageLabel = TextView(this).apply {
            typeface = ReaderTheme.chrome(this@AnnotationsPanelActivity)
            setTextColor(ReaderTheme.INK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
        }
        pageRow.addView(prevButton, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        pageRow.addView(pageLabel, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        pageRow.addView(nextButton, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        root.addView(pageRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        refreshSection()
        return root
    }

    private fun sectionButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        typeface = ReaderTheme.chrome(this@AnnotationsPanelActivity)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(ReaderTheme.INK)
        minHeight = dp(56f)
        setOnClickListener { onClick() }
    }

    private fun navButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        typeface = ReaderTheme.chrome(this@AnnotationsPanelActivity)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(ReaderTheme.INK)
        minHeight = dp(56f)
        setOnClickListener { onClick() }
    }

    // --- Data loading --------------------------------------------------------

    private fun loadAnnotations(file: File) {
        ioExecutor.execute {
            try {
                val bytes = file.readBytes()
                val doc = DocxStore.load(bytes)
                main.post {
                    docxBytes = bytes
                    allAnnotations = doc.annotations.map { it.annotation }
                    buildList()
                }
            } catch (e: Exception) {
                Log.e(TAG, "failed to load annotations", e)
                main.post {
                    Toast.makeText(this, "Could not read annotations.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // --- List building -------------------------------------------------------

    private fun filteredAnnotations(): List<Annotation> =
        if (showBookmarks) allAnnotations.filter { it.tool == AnnotationTool.bookmark }
        else allAnnotations.filter { it.tool != AnnotationTool.bookmark }

    private fun pageCount(size: Int): Int = maxOf(1, (size + PAGE_ROWS - 1) / PAGE_ROWS)

    private fun refreshSection() {
        // Update button visual weight to reflect active section.
        marksButton.alpha = if (!showBookmarks) 1f else 0.45f
        bookmarksButton.alpha = if (showBookmarks) 1f else 0.45f
        buildList()
    }

    private fun buildList() {
        listContainer.removeAllViews()
        val filtered = filteredAnnotations()
        val pages = pageCount(filtered.size)
        currentPage = currentPage.coerceIn(0, maxOf(0, pages - 1))
        pageLabel.text = if (filtered.isEmpty()) "" else "${currentPage + 1} / $pages"
        prevButton.isEnabled = currentPage > 0
        nextButton.isEnabled = currentPage < pages - 1
        // Greyscale e-ink: the default disabled tint is too subtle, so dim explicitly.
        prevButton.alpha = if (prevButton.isEnabled) 1f else 0.3f
        nextButton.alpha = if (nextButton.isEnabled) 1f else 0.3f

        if (filtered.isEmpty()) {
            listContainer.addView(emptyLabel())
            return
        }

        val start = currentPage * PAGE_ROWS
        val end = minOf(start + PAGE_ROWS, filtered.size)
        for (i in start until end) {
            val ann = filtered[i]
            listContainer.addView(rowDivider())
            listContainer.addView(buildRow(ann))
        }
        // Fill remaining space so rows don't float up on short lists.
        listContainer.addView(View(this), LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
    }

    private fun buildRow(ann: Annotation): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val h = dp(8f)
            val v = dp(10f)
            setPadding(h, v, h, v)
            minimumHeight = dp(64f)
            isClickable = true
            isFocusable = true
            setBackgroundColor(ReaderTheme.PAPER)
        }

        // Tool icon
        row.addView(ToolIconView(this, ann.tool).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48f), dp(48f)).apply {
                marginEnd = dp(12f)
            }
        })

        // Text column: selected text + optional note
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        textCol.addView(TextView(this).apply {
            text = ann.selectedText.take(80)
            typeface = ReaderTheme.body(this@AnnotationsPanelActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(ReaderTheme.INK)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        val noteText = ann.note
        if (!noteText.isNullOrEmpty()) {
            textCol.addView(TextView(this).apply {
                text = noteText.take(60)
                typeface = ReaderTheme.bodyItalic(this@AnnotationsPanelActivity)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(0x99000000.toInt())
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
        row.addView(textCol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        // Tap → jump
        row.setOnClickListener {
            val result = Intent().putExtra(EXTRA_FRACTION, ann.position)
            setResult(RESULT_OK, result)
            finish()
        }

        // Long-press → delete
        row.setOnLongClickListener {
            confirmDelete(ann)
            true
        }

        return row
    }

    private fun rowDivider(): View = View(this).apply {
        setBackgroundColor(0x14000000)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(1f))
    }

    private fun emptyLabel(): View = TextView(this).apply {
        text = if (showBookmarks) "No bookmarks yet." else "No marks yet."
        typeface = ReaderTheme.body(this@AnnotationsPanelActivity)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(0x66000000)
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(32f)
        }
    }

    // --- Delete --------------------------------------------------------------

    private fun confirmDelete(ann: Annotation) {
        AlertDialog.Builder(this)
            .setMessage("Delete \"${ann.selectedText.take(40)}\"?")
            .setPositiveButton("Delete") { _, _ -> deleteAnnotation(ann) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteAnnotation(ann: Annotation) {
        val file = docxFile ?: return
        val bytes = docxBytes ?: return
        ioExecutor.execute {
            try {
                val updated = allAnnotations.filter { it.id != ann.id }
                val newBytes = DocxStore.write(bytes, updated)
                file.writeBytes(newBytes)
                val freshDoc = DocxStore.load(newBytes)
                main.post {
                    docxBytes = newBytes
                    allAnnotations = freshDoc.annotations.map { it.annotation }
                    buildList()
                    // Signal the caller to reload annotations too.
                    setResult(RESULT_FIRST_USER)
                }
            } catch (e: Exception) {
                Log.e(TAG, "deleteAnnotation failed", e)
                main.post {
                    Toast.makeText(this, "Could not delete annotation.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // --- Helpers -------------------------------------------------------------

    private fun dp(v: Float): Int = ReaderTheme.dp(this, v).toInt()

    override fun onDestroy() {
        super.onDestroy()
        ioExecutor.shutdownNow()
    }
}

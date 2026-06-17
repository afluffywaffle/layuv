package com.afluffywaffle.layuv.reader

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Environment
import android.text.TextUtils
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * A paged, folder-navigating DOCX picker. Rooted at the writable user storage
 * (`/storage/emulated/0`) and never goes above it, so the read-only system /
 * media locations the Supernote's SAF exposes never appear (see
 * [[native-android-port]]). NO scrolling — entries are paged like the reader
 * (Prev/Next), since fling scrolling ghosts on e-ink. Returns the chosen file's
 * absolute path, so the reader always opens by path (writable).
 */
class FileBrowserActivity : Activity() {

    private data class Entry(val file: File, val isDir: Boolean)
    private data class Crumb(val label: String, val file: File?)

    private val root: File = Environment.getExternalStorageDirectory()
    private var currentDir: File = root
    private var entries: List<Entry> = emptyList()
    private var page = 0
    private var rowsPerPage = 0

    private lateinit var crumbBar: LinearLayout
    private lateinit var pageView: TextView
    private lateinit var prevButton: Button
    private lateinit var nextButton: Button
    private lateinit var body: LinearLayout

    private val rowHeight by lazy { dp(68f) }
    private val dividerHeight by lazy { dp(1f).coerceAtLeast(1) }

    private val swipeDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val dx = e2.x - (e1?.x ?: return false)
                val dy = e2.y - (e1?.y ?: return false)
                if (kotlin.math.abs(dx) > kotlin.math.abs(dy) && kotlin.math.abs(dx) > dp(60f)) {
                    if (dx < 0) { page++; render() } else if (page > 0) { page--; render() }
                    return true
                }
                return false
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        swipeDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Open a DOCX"
        setContentView(buildUi())

        if (!Environment.isExternalStorageManager()) {
            Toast.makeText(this, "All-files access is required to browse documents.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // rowsPerPage needs the body's measured height; render once it's laid out.
        body.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val available = bottom - top
            val rpp = (available / (rowHeight + dividerHeight)).coerceAtLeast(1)
            if (rpp != rowsPerPage) {
                rowsPerPage = rpp
                render()
            }
        }
        listDir(root)
    }

    // --- UI ------------------------------------------------------------------

    private fun buildUi(): View {
        val rootView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ReaderTheme.PAPER)
        }

        // Tappable breadcrumb: "Internal storage › Drafts › Ch04". Tapping an
        // ancestor jumps straight there; its sub-folders (the sisters) then list.
        crumbBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = dp(6f)
            setPadding(p, p, p, p)
        }

        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ReaderTheme.PAPER)
        }

        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = dp(8f)
            setPadding(p, p, p, p)
        }
        prevButton = chromeButton("‹ Prev") { if (page > 0) { page--; render() } }
        nextButton = chromeButton("Next ›") { page++; render() }
        pageView = TextView(this).apply {
            typeface = ReaderTheme.body(this@FileBrowserActivity)
            setTextColor(ReaderTheme.INK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
        }
        val cancelButton = chromeButton("Cancel") { finish() }
        bottomBar.addView(cancelButton)
        bottomBar.addView(prevButton)
        bottomBar.addView(pageView, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        bottomBar.addView(nextButton)

        rootView.addView(crumbBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        rootView.addView(body, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        rootView.addView(bottomBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return rootView
    }

    private fun chromeButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        typeface = ReaderTheme.bodyBold(this@FileBrowserActivity)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(ReaderTheme.INK)
        minHeight = dp(56f)
        minimumHeight = dp(56f)
        setOnClickListener { onClick() }
    }

    // --- Navigation ----------------------------------------------------------

    private fun listDir(dir: File) {
        currentDir = dir
        page = 0
        val children = dir.listFiles()
        val dirs = children
            ?.filter { it.isDirectory && !it.name.startsWith(".") && it.name != "Android" }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
        val docs = children
            ?.filter { it.isFile && it.name.endsWith(".docx", ignoreCase = true) && !it.name.startsWith("~$") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
        entries = dirs.map { Entry(it, true) } + docs.map { Entry(it, false) }
        render()
    }

    private fun render() {
        if (rowsPerPage <= 0) return
        body.removeAllViews()
        renderCrumbs()

        if (entries.isEmpty()) {
            body.addView(TextView(this).apply {
                typeface = ReaderTheme.body(this@FileBrowserActivity)
                setTextColor(MUTED)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                gravity = Gravity.CENTER
                text = "No folders or .docx files here"
                val p = dp(24f)
                setPadding(p, p, p, p)
            })
            pageView.text = ""
            setEnabled(prevButton, false)
            setEnabled(nextButton, false)
            return
        }

        val pageCount = (entries.size + rowsPerPage - 1) / rowsPerPage
        page = page.coerceIn(0, pageCount - 1)
        val start = page * rowsPerPage
        val end = minOf(start + rowsPerPage, entries.size)
        for (i in start until end) {
            body.addView(rowFor(entries[i]))
            body.addView(divider())
        }

        pageView.text = "${page + 1} / $pageCount"
        setEnabled(prevButton, page > 0)
        setEnabled(nextButton, page < pageCount - 1)
    }

    private fun rowFor(entry: Entry): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = rowHeight
        isClickable = true
        val h = dp(16f)
        val v = dp(10f)
        setPadding(h, v, h, v)
        addView(TextView(context).apply {
            typeface = ReaderTheme.body(context)
            setTextColor(ReaderTheme.INK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            text = if (entry.isDir) "${entry.file.name}/" else entry.file.name
        })
        addView(TextView(context).apply {
            // Body font + darker + larger: the metadata line was tiny and faint.
            typeface = ReaderTheme.body(context)
            setTextColor(SUBTITLE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 1
            text = if (entry.isDir) {
                "Folder"
            } else {
                val modified = DateUtils.getRelativeTimeSpanString(
                    entry.file.lastModified(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
                )
                "$modified · ${entry.file.length() / 1024} KB"
            }
        })
        setOnClickListener {
            if (entry.isDir) {
                listDir(entry.file)
            } else {
                setResult(RESULT_OK, Intent().putExtra(EXTRA_PATH, entry.file.absolutePath))
                finish()
            }
        }
    }

    private fun divider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dividerHeight)
        setBackgroundColor(DIVIDER)
    }

    private fun setEnabled(button: Button, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.35f
    }

    // --- Breadcrumb ----------------------------------------------------------
    // Windows-style: tap a crumb label to jump to that folder; tap the "›" arrow
    // after it to pop that folder's sub-folders and jump to a sibling directly,
    // without backing out.

    private fun renderCrumbs() {
        crumbBar.removeAllViews()
        val segs = segmentsFor(currentDir)
        val shown: List<Crumb> = if (segs.size <= MAX_CRUMBS) {
            segs.map { Crumb(it.first, it.second) }
        } else {
            // root › … › parent › current  — root and the tail stay reachable.
            listOf(Crumb(segs.first().first, segs.first().second), Crumb("…", null)) +
                segs.takeLast(MAX_CRUMBS - 2).map { Crumb(it.first, it.second) }
        }
        for (crumb in shown) {
            crumbBar.addView(crumbLabel(crumb))
            crumbBar.addView(arrowFor(crumb.file))
        }
    }

    /** Storage root → [dir] as (label, dir) pairs; root shows as "Internal storage". */
    private fun segmentsFor(dir: File): List<Pair<String, File>> {
        val rootPath = root.absolutePath
        val list = ArrayList<Pair<String, File>>()
        var f: File? = dir
        while (f != null && f.absolutePath.startsWith(rootPath)) {
            list.add(0, (if (f.absolutePath == rootPath) "Internal storage" else f.name) to f)
            if (f.absolutePath == rootPath) break
            f = f.parentFile
        }
        return list
    }

    private fun crumbLabel(crumb: Crumb): TextView = TextView(this).apply {
        text = crumb.label
        // Bold + larger so ancestor crumbs read clearly on e-ink — thin regular
        // weight looked grey/faint. The "…" marker stays muted regular.
        // Literata (body), not Source Sans: the sans chrome font renders thin and
        // grey on this EPD panel even when bold/black; Literata sits dark and crisp.
        val isEllipsis = crumb.file == null
        typeface = if (isEllipsis) ReaderTheme.body(context)
        else Typeface.create(ReaderTheme.body(context), Typeface.BOLD)
        setTextColor(if (isEllipsis) MUTED else ReaderTheme.INK)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.MIDDLE
        maxWidth = dp(190f)
        minHeight = dp(52f)
        gravity = Gravity.CENTER_VERTICAL
        val h = dp(8f)
        setPadding(h, dp(6f), h, dp(6f))
        crumb.file?.let { dir -> setOnClickListener { listDir(dir) } }
    }

    /** The "›" after a crumb: tap to pop [dir]'s sub-folders and jump to one. */
    private fun arrowFor(dir: File?): TextView = TextView(this).apply {
        text = "›"
        typeface = Typeface.create(ReaderTheme.body(context), Typeface.BOLD)
        setTextColor(if (dir == null) MUTED else ReaderTheme.INK)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        minHeight = dp(52f)
        minWidth = dp(44f)
        gravity = Gravity.CENTER
        if (dir != null) setOnClickListener { showSubfolders(it, dir) }
    }

    private fun showSubfolders(anchor: View, dir: File) {
        val subs = subDirs(dir)
        val popup = PopupMenu(this, anchor)
        if (subs.isEmpty()) {
            popup.menu.add("No sub-folders").isEnabled = false
        } else {
            subs.forEachIndexed { i, f -> popup.menu.add(0, i, i, f.name) }
            popup.setOnMenuItemClickListener { item ->
                subs.getOrNull(item.itemId)?.let { listDir(it) }
                true
            }
        }
        popup.show()
    }

    private fun subDirs(dir: File): List<File> = dir.listFiles()
        ?.filter { it.isDirectory && !it.name.startsWith(".") && it.name != "Android" }
        ?.sortedBy { it.name.lowercase() }
        ?: emptyList()

    private fun dp(value: Float): Int = ReaderTheme.dp(this, value).toInt()

    companion object {
        const val EXTRA_PATH = "path"
        private const val MUTED = 0xFF6E6A62.toInt()
        // Dark enough to read on e-ink, still subordinate to the black filename.
        private const val SUBTITLE = 0xFF33302A.toInt()
        private const val DIVIDER = 0xFFDCD7CD.toInt()
        // Cap visible crumbs (root › … › parent › current) so deep paths don't
        // overflow the bar; ancestors stay reachable via root + arrow popups.
        private const val MAX_CRUMBS = 4
    }
}

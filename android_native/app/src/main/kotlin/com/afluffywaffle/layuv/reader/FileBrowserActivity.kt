package com.afluffywaffle.layuv.reader

import android.app.Activity
import android.content.Context
import android.content.Intent
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
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import java.io.File
import org.json.JSONArray

/**
 * Split-view file picker: recents in the top 1/3, folder browser in the bottom
 * 2/3. Recents section is hidden when empty. Paged like the reader (Prev/Next +
 * swipe) — no scrolling on e-ink.
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
    private lateinit var recentsSection: LinearLayout
    private lateinit var pageView: TextView
    private lateinit var prevButton: TextView
    private lateinit var nextButton: TextView
    private lateinit var body: LinearLayout

    private val rowHeight by lazy { dp(68f) }
    private val dividerHeight by lazy { dp(1f).coerceAtLeast(1) }

    // Swipe left = next page, swipe right = prev page (touch + stylus).
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
        val prefs = getSharedPreferences("leamh", Context.MODE_PRIVATE)
        ReaderTheme.bodyFont = prefs.getString("body_font", "literata") ?: "literata"
        setContentView(buildUi())

        if (!Environment.isExternalStorageManager()) {
            Toast.makeText(this, "All-files access is required to browse documents.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        body.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val available = bottom - top
            val rpp = (available / (rowHeight + dividerHeight)).coerceAtLeast(1)
            if (rpp != rowsPerPage) {
                rowsPerPage = rpp
                render()
            }
        }

        renderRecents()
        listDir(root)
    }

    // --- UI ------------------------------------------------------------------

    private fun buildUi(): View {
        val rootView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ReaderTheme.PAPER)
        }

        // Top 1/3 — recents (hidden when empty).
        recentsSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        // Divider between recents and browser.
        val splitDivider = View(this).apply {
            setBackgroundColor(ReaderTheme.INK_12)
            visibility = View.GONE
            tag = "splitDivider"
        }

        // Bottom 2/3 — folder browser.
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
        val browserSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        browserSection.addView(crumbBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        browserSection.addView(body, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // Bottom bar — Cancel / Prev / page count / Next.
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = dp(8f)
            setPadding(p, p, p, p)
        }
        prevButton = navButton("‹ Prev") { if (page > 0) { page--; render() } }
        nextButton = navButton("Next ›") { page++; render() }
        pageView = TextView(this).apply {
            typeface = ReaderTheme.body(this@FileBrowserActivity)
            setTextColor(ReaderTheme.INK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
        }
        bottomBar.addView(navButton("Cancel") { finish() })
        bottomBar.addView(prevButton)
        bottomBar.addView(pageView, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        bottomBar.addView(nextButton)

        // Recents gets weight=1 (1/3), browser gets weight=2 (2/3).
        rootView.addView(recentsSection, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        rootView.addView(splitDivider, LinearLayout.LayoutParams(MATCH_PARENT, dp(1f)))
        rootView.addView(browserSection, LinearLayout.LayoutParams(MATCH_PARENT, 0, 2f))
        rootView.addView(bottomBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return rootView
    }

    /** TextView styled like a nav button — Material Button overrides typeface; TextView doesn't. */
    private fun navButton(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        typeface = ReaderTheme.bodyBold(this@FileBrowserActivity)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(ReaderTheme.INK)
        minHeight = dp(56f)
        minimumHeight = dp(56f)
        gravity = Gravity.CENTER
        val h = dp(12f)
        setPadding(h, 0, h, 0)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    // --- Recents -------------------------------------------------------------

    private fun renderRecents() {
        recentsSection.removeAllViews()
        val recents = loadRecents().take(MAX_RECENTS_SHOWN)
        val splitDivider = (recentsSection.parent as? LinearLayout)
            ?.findViewWithTag<View>("splitDivider")

        if (recents.isEmpty()) {
            recentsSection.visibility = View.GONE
            splitDivider?.visibility = View.GONE
            return
        }

        recentsSection.visibility = View.VISIBLE
        splitDivider?.visibility = View.VISIBLE
        recentsSection.addView(sectionHeader("RECENTS"))
        for (path in recents) {
            recentsSection.addView(rowFor(Entry(File(path), false)))
            recentsSection.addView(divider())
        }
    }

    private fun loadRecents(): List<String> {
        val prefs = getSharedPreferences("leamh", Context.MODE_PRIVATE)
        val raw = prefs.getString("recent_files", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }.filter { File(it).exists() }
        } catch (e: Exception) { emptyList() }
    }

    // --- Browser -------------------------------------------------------------

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
            setNavEnabled(prevButton, false)
            setNavEnabled(nextButton, false)
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
        setNavEnabled(prevButton, page > 0)
        setNavEnabled(nextButton, page < pageCount - 1)
    }

    // --- Shared row/divider helpers ------------------------------------------

    private fun rowFor(entry: Entry): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = rowHeight
        isClickable = true
        val h = dp(16f); val v = dp(10f)
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

    private fun sectionHeader(label: String): TextView = TextView(this).apply {
        text = label
        typeface = ReaderTheme.bodyBold(context)
        setTextColor(MUTED)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        val h = dp(16f); val v = dp(8f)
        setPadding(h, v, h, v / 2)
    }

    private fun setNavEnabled(button: TextView, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.35f
    }

    // --- Breadcrumb ----------------------------------------------------------

    private fun renderCrumbs() {
        crumbBar.removeAllViews()
        val segs = segmentsFor(currentDir)
        val shown: List<Crumb> = if (segs.size <= MAX_CRUMBS) {
            segs.map { Crumb(it.first, it.second) }
        } else {
            listOf(Crumb(segs.first().first, segs.first().second), Crumb("…", null)) +
                segs.takeLast(MAX_CRUMBS - 2).map { Crumb(it.first, it.second) }
        }
        for (crumb in shown) {
            crumbBar.addView(crumbLabel(crumb))
            crumbBar.addView(arrowFor(crumb.file))
        }
    }

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
        val isEllipsis = crumb.file == null
        typeface = if (isEllipsis) ReaderTheme.body(context) else ReaderTheme.bodyBold(context)
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

    private fun arrowFor(dir: File?): TextView = TextView(this).apply {
        text = "›"
        typeface = ReaderTheme.bodyBold(context)
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
        private const val SUBTITLE = 0xFF33302A.toInt()
        private const val DIVIDER = 0xFFDCD7CD.toInt()
        private const val MAX_CRUMBS = 4
        private const val MAX_RECENTS_SHOWN = 5
    }
}

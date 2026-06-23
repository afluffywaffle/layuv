package com.afluffywaffle.layuv.reader

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.style.UnderlineSpan
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import com.afluffywaffle.layuv.R
import com.afluffywaffle.layuv.docx.DocxStore
import org.json.JSONArray
import org.json.JSONException
import java.io.File
import java.util.concurrent.Executors

/**
 * Full-screen document search. Loads the plain text, finds all case-insensitive
 * substring matches, and shows them in a paginated list with context snippets and
 * page numbers. Page size is computed dynamically from screen height so it differs
 * between the Nomad and the Manta without manual tuning.
 *
 * Persists the last query per document and maintains a global recent-searches list.
 */
class SearchActivity : Activity() {

    companion object {
        const val EXTRA_DOCX_PATH = "docx_path"
        const val EXTRA_PAGE_STARTS = "page_starts"
        const val EXTRA_CHAR_OFFSET = "char_offset"
        const val EXTRA_CHAR_END = "char_end"
        private const val PREFS = "leamh"
        private const val KEY_RECENT = "recent_searches"
        private const val MAX_RECENT = 10
        private const val SOFT_LIMIT = 500
        private const val SNIPPET_CONTEXT = 70   // chars either side in the list row
        private const val EXPANDED_CONTEXT = 200 // chars either side in the tap popup
        private const val TAG = "LeamhSearch"
    }

    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    private var plainText: String? = null
    private var pageStarts: IntArray = IntArray(0)
    private var docxPath: String = ""

    private var currentQuery = ""
    private var allResults: List<Int> = emptyList()
    private var currentResultPage = 0
    private var pageSize = 10 // conservative default; recomputed from real height in showResults()
    private var availableListHeight = 0 // set by ViewTreeObserver after first layout

    // Swipe tracking for dispatchTouchEvent — fires before any child PenTapListener.
    private var swipeDownX = 0f
    private var swipeDownY = 0f

    private lateinit var searchField: EditText
    private lateinit var statusLabel: TextView
    private lateinit var statusRow: View
    private lateinit var listContainer: LinearLayout
    private lateinit var paginationRow: LinearLayout
    private lateinit var pageLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        docxPath = intent.getStringExtra(EXTRA_DOCX_PATH) ?: run { finish(); return }
        pageStarts = intent.getIntArrayExtra(EXTRA_PAGE_STARTS) ?: IntArray(0)
        setContentView(buildUi())
        loadDocument()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { swipeDownX = ev.x; swipeDownY = ev.y }
            MotionEvent.ACTION_UP -> {
                val dx = ev.x - swipeDownX
                val dy = ev.y - swipeDownY
                if (Math.abs(dx) > dp(60f) && Math.abs(dx) > Math.abs(dy)) {
                    navigateResultPage(if (dx < 0) +1 else -1)
                }
            }
            MotionEvent.ACTION_CANCEL -> { swipeDownX = 0f; swipeDownY = 0f }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onDestroy() {
        super.onDestroy()
        ioExecutor.shutdownNow()
    }

    // ── UI ────────────────────────────────────────────────────────────────────

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ReaderTheme.PAPER)
        }

        // Header: back | "Search" (flex)
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4f), dp(8f), dp(8f), dp(4f))
        }
        headerRow.addView(
            ChromeIconButton(this, R.drawable.ic_arrow_back) { finish() },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT),
        )
        headerRow.addView(
            TextView(this).apply {
                text = "Search"
                typeface = ReaderTheme.bodyBold(this@SearchActivity)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setTextColor(ReaderTheme.INK_87)
            },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
        )
        root.addView(headerRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(hDivider(), LinearLayout.LayoutParams(MATCH_PARENT, dp(1f)))

        // Search row: [bordered field with left clear icon (flex)] | Recent | Find
        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20f), dp(12f), dp(16f), dp(12f))
        }

        // Clear icon — lives inside the field frame on the LEFT, shown whenever field has text.
        // Use INVISIBLE (not GONE) so there's no layout change on show/hide — purely a redraw,
        // which is more reliable on e-ink. ImageView handles invalidation correctly.
        val clearSlotW = dp(44f)
        val clearIcon = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(ReaderTheme.INK_87)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
            visibility = View.INVISIBLE
            setOnTouchListener(PenTapListener(this@SearchActivity) { clearSearch() })
        }

        val fieldH = dp(52f)

        searchField = EditText(this).apply {
            hint = "Word, phrase, or partial word…"
            setHintTextColor(ReaderTheme.INK_38)
            typeface = ReaderTheme.body(this@SearchActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(ReaderTheme.INK_87)
            setHighlightColor(android.graphics.Color.argb(60, 0, 0, 0)) // e-ink-safe selection colour
            gravity = Gravity.CENTER_VERTICAL
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            maxLines = 1
            background = null
            // Left pad reserves space for the clear icon slot; right pad gives breathing room.
            setPadding(clearSlotW, 0, dp(8f), 0)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) { runSearch(); true } else false
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {
                    clearIcon.visibility = if (s.isNullOrEmpty()) View.INVISIBLE else View.VISIBLE
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }

        val fieldFrame = FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(ReaderTheme.PAPER)
                setStroke(dp(1f), ReaderTheme.INK_54)
                cornerRadius = ReaderTheme.dp(this@SearchActivity, ReaderTheme.RADIUS_BTN)
            }
            // Fixed-height parent so MATCH_PARENT children resolve without measurement ambiguity.
            addView(searchField, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(
                clearIcon,
                FrameLayout.LayoutParams(clearSlotW, fieldH, Gravity.START or Gravity.CENTER_VERTICAL),
            )
        }
        searchRow.addView(fieldFrame, LinearLayout.LayoutParams(0, fieldH, 1f))
        val recentAnchor = TextView(this).apply {
            text = "Recent"
            typeface = ReaderTheme.body(this@SearchActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(ReaderTheme.INK_54)
            gravity = Gravity.CENTER
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            minimumHeight = dp(48f)
        }
        recentAnchor.setOnTouchListener(PenTapListener(this) { showRecentSearches(recentAnchor) })
        searchRow.addView(recentAnchor, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            marginStart = dp(12f)
        })
        val findBtn = TextView(this).apply {
            text = "Find"
            typeface = ReaderTheme.bodyBold(this@SearchActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(ReaderTheme.INK_87)
            gravity = Gravity.CENTER
            setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
            minimumHeight = dp(48f)
            minimumWidth = dp(72f)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(ReaderTheme.FILL_08)
                cornerRadius = ReaderTheme.dp(this@SearchActivity, ReaderTheme.RADIUS_BTN)
            }
            setOnTouchListener(PenTapListener(this@SearchActivity) { runSearch() })
        }
        searchRow.addView(findBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            marginStart = dp(12f)
        })
        root.addView(searchRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(hDivider(), LinearLayout.LayoutParams(MATCH_PARENT, dp(1f)))

        // Status row — hidden until a search runs
        statusLabel = TextView(this).apply {
            typeface = ReaderTheme.chrome(this@SearchActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(ReaderTheme.INK_54)
            setPadding(dp(20f), dp(10f), dp(20f), dp(10f))
        }
        statusRow = statusLabel
        statusRow.visibility = View.GONE
        root.addView(statusRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // Scrollable result list — expands to fill remaining height
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Capture actual available height once laid out; used to size pages accurately.
            viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    if (listContainer.height > 0 && availableListHeight == 0) {
                        availableListHeight = listContainer.height
                        viewTreeObserver.removeOnGlobalLayoutListener(this)
                    }
                }
            })
        }
        root.addView(listContainer, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        root.addView(hDivider(), LinearLayout.LayoutParams(MATCH_PARENT, dp(1f)))

        // Pagination row — hidden until results span multiple pages
        paginationRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
            setBackgroundColor(ReaderTheme.PAPER)
            minimumHeight = dp(56f)
            visibility = View.GONE
        }
        val prevBtn = textButton("← Prev") { navigateResultPage(-1) }
        pageLabel = TextView(this).apply {
            typeface = ReaderTheme.body(this@SearchActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(ReaderTheme.INK_54)
            gravity = Gravity.CENTER
        }
        val nextBtn = textButton("Next →") { navigateResultPage(+1) }
        paginationRow.addView(prevBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        paginationRow.addView(pageLabel, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        paginationRow.addView(nextBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        root.addView(paginationRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        return root
    }

    // ── Document loading ──────────────────────────────────────────────────────

    private fun loadDocument() {
        ioExecutor.execute {
            try {
                val bytes = File(docxPath).readBytes()
                val text = DocxStore.load(bytes).plainText
                main.post {
                    plainText = text
                    // Restore and auto-run the last query for this document
                    val saved = prefs.getString("search_query:$docxPath", null)
                    if (!saved.isNullOrEmpty()) {
                        searchField.setText(saved)
                        searchField.setSelection(saved.length)
                        runSearch()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadDocument failed", e)
                main.post { Toast.makeText(this, "Could not read document.", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    // ── Search ────────────────────────────────────────────────────────────────

    private fun runSearch() {
        val text = plainText ?: run {
            Toast.makeText(this, "Document is still loading…", Toast.LENGTH_SHORT).show()
            return
        }
        val query = searchField.text.toString().trim()
        if (query.isEmpty()) {
            Toast.makeText(this, "Enter a word or phrase to search.", Toast.LENGTH_SHORT).show()
            return
        }
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchField.windowToken, 0)

        currentQuery = query
        prefs.edit().putString("search_query:$docxPath", query).apply()
        saveRecentSearch(query)

        ioExecutor.execute {
            val results = findAll(text, query)
            main.post {
                allResults = results
                currentResultPage = 0
                showResults()
            }
        }
    }

    private fun findAll(text: String, query: String): List<Int> {
        val lower = text.lowercase()
        val lowerQuery = query.lowercase()
        val results = mutableListOf<Int>()
        var idx = 0
        while (idx <= lower.length - lowerQuery.length) {
            val pos = lower.indexOf(lowerQuery, idx)
            if (pos < 0) break
            results.add(pos)
            idx = pos + lowerQuery.length
        }
        return results
    }

    private fun clearSearch() {
        searchField.setText("")
        currentQuery = ""
        allResults = emptyList()
        currentResultPage = 0
        prefs.edit().remove("search_query:$docxPath").apply()
        listContainer.removeAllViews()
        statusRow.visibility = View.GONE
        paginationRow.visibility = View.GONE
        searchField.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(searchField, InputMethodManager.SHOW_IMPLICIT)
    }

    // ── Results ───────────────────────────────────────────────────────────────

    private fun showResults() {
        val total = allResults.size
        statusRow.visibility = View.VISIBLE
        statusLabel.text = when {
            total == 0 -> "No results for \"$currentQuery\"."
            total == 1 -> "1 result"
            total > SOFT_LIMIT -> "$total results — try a longer phrase to narrow."
            else -> "$total results"
        }
        listContainer.removeAllViews()
        if (total == 0) {
            paginationRow.visibility = View.GONE
            return
        }
        // Recompute page size using actual measured height if available.
        pageSize = computePageSize()
        val totalPages = (total + pageSize - 1) / pageSize
        paginationRow.visibility = if (totalPages > 1) View.VISIBLE else View.GONE
        rebuildResultList()
    }

    private fun rebuildResultList() {
        listContainer.removeAllViews()
        val text = plainText ?: return
        val totalPages = (allResults.size + pageSize - 1) / pageSize
        updatePageLabel(currentResultPage, totalPages)
        val start = currentResultPage * pageSize
        val end = minOf(start + pageSize, allResults.size)
        for (i in start until end) {
            if (i > start) listContainer.addView(rowDivider())
            listContainer.addView(buildResultRow(text, allResults[i]))
        }
    }

    private fun buildResultRow(text: String, charOffset: Int): View {
        val query = currentQuery
        val rawStart = maxOf(0, charOffset - SNIPPET_CONTEXT)
        val rawEnd = minOf(text.length, charOffset + query.length + SNIPPET_CONTEXT)
        val ctxStart = if (rawStart > 0) minOf(snapWordEnd(text, rawStart), charOffset) else 0
        val ctxEnd = if (rawEnd < text.length) maxOf(snapWordStart(text, rawEnd), charOffset + query.length) else text.length
        val raw = text.substring(ctxStart, ctxEnd)
        val prefix = if (ctxStart > 0) "… " else ""
        val suffix = if (ctxEnd < text.length) " …" else ""
        val display = prefix + raw + suffix
        val mStart = charOffset - ctxStart + prefix.length
        val mEnd = mStart + query.length
        val spannable = SpannableStringBuilder(display)
        if (mStart >= 0 && mEnd <= display.length) {
            spannable.setSpan(UnderlineSpan(), mStart, mEnd, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        val pageNum = if (pageStarts.isNotEmpty()) pageForOffset(charOffset) else null

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20f), dp(14f), dp(16f), dp(14f))
            minimumHeight = dp(64f)
            isClickable = true
            isFocusable = true
            setBackgroundColor(ReaderTheme.PAPER)
            setOnTouchListener(PenTapListener(this@SearchActivity) {
                showExpandedSnippet(text, charOffset)
            })
        }
        row.addView(
            TextView(this).apply {
                this.text = spannable
                typeface = ReaderTheme.body(this@SearchActivity)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, ReaderTheme.BODY_TEXT_SP - 2f)
                setTextColor(ReaderTheme.INK)
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
        )
        if (pageNum != null) {
            row.addView(
                TextView(this).apply {
                    this.text = "pg. $pageNum"
                    typeface = ReaderTheme.body(this@SearchActivity)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    setTextColor(ReaderTheme.INK_54)
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    setPadding(dp(12f), 0, 0, 0)
                },
                LinearLayout.LayoutParams(dp(72f), WRAP_CONTENT),
            )
        }
        return row
    }

    private fun showExpandedSnippet(text: String, charOffset: Int) {
        val query = currentQuery
        val rawStart = maxOf(0, charOffset - EXPANDED_CONTEXT)
        val rawEnd = minOf(text.length, charOffset + query.length + EXPANDED_CONTEXT)
        val ctxStart = if (rawStart > 0) minOf(snapWordEnd(text, rawStart), charOffset) else 0
        val ctxEnd = if (rawEnd < text.length) maxOf(snapWordStart(text, rawEnd), charOffset + query.length) else text.length
        val raw = text.substring(ctxStart, ctxEnd)
        val prefix = if (ctxStart > 0) "… " else ""
        val suffix = if (ctxEnd < text.length) " …" else ""
        val display = prefix + raw + suffix
        val mStart = charOffset - ctxStart + prefix.length
        val mEnd = mStart + query.length
        val spannable = SpannableStringBuilder(display)
        if (mStart >= 0 && mEnd <= display.length) {
            spannable.setSpan(UnderlineSpan(), mStart, mEnd, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        val pageNum = if (pageStarts.isNotEmpty()) pageForOffset(charOffset) else null

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f), dp(20f), dp(24f), dp(16f))
        }
        if (pageNum != null) {
            content.addView(TextView(this).apply {
                this.text = "Page $pageNum"
                typeface = ReaderTheme.chromeBold(this@SearchActivity)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(ReaderTheme.INK_54)
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(12f) })
        }
        content.addView(TextView(this).apply {
            this.text = spannable
            typeface = ReaderTheme.body(this@SearchActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ReaderTheme.BODY_TEXT_SP)
            setTextColor(ReaderTheme.INK)
        })
        content.addView(View(this).apply {
            setBackgroundColor(ReaderTheme.INK_12)
        }, LinearLayout.LayoutParams(MATCH_PARENT, dp(1f)).apply { topMargin = dp(16f) })

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8f), 0, 0)
        }
        var popup: PopupWindow? = null
        btnRow.addView(textButton("Back to results") { popup?.dismiss() })
        btnRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        if (pageNum != null) {
            btnRow.addView(textButton("Go to page") {
                popup?.dismiss()
                setResult(RESULT_OK, Intent()
                    .putExtra(EXTRA_CHAR_OFFSET, charOffset)
                    .putExtra(EXTRA_CHAR_END, charOffset + currentQuery.length))
                finish()
            })
        }
        content.addView(btnRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val scrollContent = ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(content, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        val frame = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ReaderTheme.PAPER)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(ReaderTheme.PAPER)
                setStroke(dp(1f), ReaderTheme.INK_26)
                cornerRadius = ReaderTheme.dp(this@SearchActivity, ReaderTheme.RADIUS_CARD)
            }
            addView(scrollContent, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }

        val screenW = resources.displayMetrics.widthPixels
        val maxH = (resources.displayMetrics.heightPixels * 0.75f).toInt()
        val pw = PopupWindow(frame, (screenW * 0.85f).toInt(), WRAP_CONTENT, true).apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x00000000))
            isOutsideTouchable = true
        }
        // Constrain height after measurement
        frame.measure(
            View.MeasureSpec.makeMeasureSpec((screenW * 0.85f).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        if (frame.measuredHeight > maxH) pw.height = maxH
        popup = pw
        pw.showAtLocation(listContainer, Gravity.CENTER, 0, 0)
    }

    // ── Pagination ────────────────────────────────────────────────────────────

    private fun navigateResultPage(delta: Int) {
        val totalPages = (allResults.size + pageSize - 1) / pageSize
        val newPage = (currentResultPage + delta).coerceIn(0, totalPages - 1)
        if (newPage == currentResultPage) return
        currentResultPage = newPage
        rebuildResultList()
    }

    private fun updatePageLabel(page: Int, totalPages: Int) {
        pageLabel.text = "Page ${page + 1} of $totalPages"
    }

    // ── Recent searches ───────────────────────────────────────────────────────

    private fun saveRecentSearch(query: String) {
        val list = loadRecentSearches().toMutableList()
        list.remove(query)
        list.add(0, query)
        while (list.size > MAX_RECENT) list.removeLast()
        prefs.edit().putString(KEY_RECENT, JSONArray(list).toString()).apply()
    }

    private fun loadRecentSearches(): List<String> {
        val raw = prefs.getString(KEY_RECENT, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { arr.getString(it) }
        } catch (_: JSONException) {
            emptyList()
        }
    }

    private fun showRecentSearches(anchor: View) {
        val recents = loadRecentSearches()
        if (recents.isEmpty()) {
            Toast.makeText(this, "No recent searches.", Toast.LENGTH_SHORT).show()
            return
        }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4f), 0, dp(4f))
        }
        var popup: PopupWindow? = null
        for (query in recents) {
            list.addView(TextView(this).apply {
                text = query
                typeface = ReaderTheme.body(this@SearchActivity)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(ReaderTheme.INK_87)
                setPadding(dp(20f), dp(14f), dp(20f), dp(14f))
                minimumHeight = dp(56f)
                gravity = Gravity.CENTER_VERTICAL
                setOnTouchListener(PenTapListener(this@SearchActivity) {
                    popup?.dismiss()
                    searchField.setText(query)
                    searchField.setSelection(query.length)
                    runSearch()
                })
            })
        }
        val frame = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(ReaderTheme.PAPER)
                setStroke(dp(1f), ReaderTheme.INK_26)
                cornerRadius = ReaderTheme.dp(this@SearchActivity, ReaderTheme.RADIUS_CARD)
            }
            addView(list, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        val screenW = resources.displayMetrics.widthPixels
        val pw = PopupWindow(frame, (screenW * 0.5f).toInt(), WRAP_CONTENT, true).apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x00000000))
            isOutsideTouchable = true
        }
        popup = pw
        pw.showAsDropDown(anchor, 0, 0)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Binary search to convert a char offset to a 1-indexed page number. */
    private fun pageForOffset(charOffset: Int): Int {
        if (pageStarts.isEmpty()) return 0
        var lo = 0; var hi = pageStarts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (pageStarts[mid] <= charOffset) lo = mid else hi = mid - 1
        }
        return lo + 1
    }

    /**
     * Derive how many result rows fit the screen. Uses the actual body text size
     * and display metrics so Nomad and Manta auto-scale without manual constants.
     */
    private fun computePageSize(): Int {
        val bodyPx = ReaderTheme.sp(this, ReaderTheme.BODY_TEXT_SP - 2f)
        // Assume up to 4 lines of snippet text (conservative — prevents overflow).
        val rowH = (bodyPx * 4 * 1.4f + ReaderTheme.dp(this, 30f)).toInt()
        val available = if (availableListHeight > 0) {
            availableListHeight
        } else {
            // Fallback before first layout: subtract fixed chrome heights.
            val reserved = ReaderTheme.dp(this, 64f + 64f + 40f + 56f).toInt()
            resources.displayMetrics.heightPixels - reserved
        }
        return (available / rowH).coerceIn(3, 12)
    }

    private fun textButton(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            typeface = ReaderTheme.body(this@SearchActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(ReaderTheme.INK_87)
            gravity = Gravity.CENTER
            setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
            minimumHeight = dp(48f)
            setOnTouchListener(PenTapListener(this@SearchActivity, onTap = onClick))
        }

    /** Advance past any partial word at [pos] — finds the start of the next complete word. */
    private fun snapWordEnd(text: String, pos: Int): Int {
        var i = pos
        while (i < text.length && text[i] != ' ' && text[i] != '\n') i++
        return if (i < text.length) i + 1 else i
    }

    /** Retreat past any partial word at [pos] — finds the end of the previous complete word. */
    private fun snapWordStart(text: String, pos: Int): Int {
        var i = pos
        while (i > 0 && text[i - 1] != ' ' && text[i - 1] != '\n') i--
        return i
    }

}

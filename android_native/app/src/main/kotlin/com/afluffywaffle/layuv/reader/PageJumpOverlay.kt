package com.afluffywaffle.layuv.reader

import android.content.Context
import com.afluffywaffle.layuv.R
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/** One heading row for the Outline tab. [level] is 0-based (0 = Heading 1). */
data class OutlineItem(val text: String, val level: Int, val pageIndex: Int)

/**
 * Full-screen navigation pane. A tab strip (Outline | Bookmarks) over a paginated
 * jump list, with the page shuttle pinned at the bottom:
 *
 *   ┌──────────────────────────────────────────────┐
 *   │        [ Outline ]    [ Bookmarks ]          │  ← tab strip
 *   ├──────────────────────────────────────────────┤
 *   │   Chapter One                       p. 1     │  ┐
 *   │     A Subsection                    p. 4     │  │ list region (fills)
 *   │   Chapter Two                       p. 9     │  │ paginated via ← →
 *   ├──────────────────────────────────────────────┤
 *   │        ┌──────────────────────┐  "12 / 340"  │  ┐
 *   │        │ preview of target …  │              │  │ shuttle — UNCHANGED
 *   │     ◀◀◀◀◀◀[   thumb   ]▶▶▶▶▶▶▶▶              │  │ (preview + scrub +
 *   │   Cancel        ← →         Confirm           │  ┘  Cancel/←→/Confirm)
 *   └──────────────────────────────────────────────┘
 *
 * E-ink behavior: dragging the shuttle updates the preview only — the reader does
 * NOT scroll until "Confirm". Tapping an Outline/Bookmark row jumps immediately
 * and dismisses. ← / → step one page at a time for stylus precision.
 */
class PageJumpOverlay(
    private val context: Context,
    private val previewProvider: (pageIndex: Int) -> String,
    private val onConfirm: (pageIndex: Int) -> Unit,
    /** Reader body text size in sp — preview matches so the excerpt feels like the real page. */
    private val bodySizeSp: Float = ReaderTheme.BODY_TEXT_SP,
    /** Called when the overlay is dismissed by any path (Cancel, Confirm, or programmatic). */
    private val onDismiss: () -> Unit = {},
) {
    private var pageCount = 1
    private var scrubFraction = 0f

    // Tabs
    private var selectedTab = TAB_OUTLINE
    private var outline: List<OutlineItem> = emptyList()

    // Paginated jump-list state (shared by both tabs)
    private var bookmarkPages: List<Int> = emptyList()  // 0-based page indices
    private var listPage = 0          // which page of the current tab's list is showing
    private var itemsPerPage = 6      // recalculated at show() time from screen height

    private val targetPage: Int
        get() = (scrubFraction * (pageCount - 1)).roundToInt().coerceIn(0, pageCount - 1)

    // -------------------------------------------------------------------------
    // Views
    // -------------------------------------------------------------------------

    /** Called when the user taps the bookmark icon in the preview card. Receives target page index. */
    var onBookmarkPage: ((pageIndex: Int) -> Unit)? = null
    /** Returns true when the given page index has a bookmark (used to show filled vs outline icon). */
    var isPageBookmarked: ((pageIndex: Int) -> Boolean)? = null

    private val previewText: TextView
    private val pageLabel: TextView
    private val previewBookmarkIcon: ImageView
    private val track: ScrubTrackView
    private val popup: PopupWindow
    private val listRegion: SwipeListLayout
    private val outlineTab: TextView
    private val bookmarkTab: TextView

    init {
        val screenW = context.resources.displayMetrics.widthPixels

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ReaderTheme.PAPER)
            // Software layer: alpha compositing in PopupWindow broken under hardware acceleration on e-ink
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }

        // ── Tab strip: Outline | Bookmarks ────────────────────────────────────
        outlineTab = tabLabel("Outline") { switchTab(TAB_OUTLINE) }
        bookmarkTab = tabLabel("Bookmarks") { switchTab(TAB_BOOKMARKS) }
        val tabRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8f).toInt(), 0, dp(8f).toInt())
        }
        tabRow.addView(outlineTab, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            marginEnd = dp(8f).toInt()
        })
        tabRow.addView(bookmarkTab, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            marginStart = dp(8f).toInt()
        })
        root.addView(tabRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(View(context).apply { setBackgroundColor(ReaderTheme.INK_26) },
            LinearLayout.LayoutParams(MATCH_PARENT, dp(1f).toInt()))

        // ── List region (fills the freed vertical space) ──────────────────────
        listRegion = SwipeListLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            onFlingLeft  = { advanceListPage(+1) }
            onFlingRight = { advanceListPage(-1) }
        }
        root.addView(listRegion, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // ── Divider above the shuttle ─────────────────────────────────────────
        root.addView(View(context).apply { setBackgroundColor(ReaderTheme.INK_26) },
            LinearLayout.LayoutParams(MATCH_PARENT, dp(1f).toInt()))

        // ── Preview row (shuttle) ─────────────────────────────────────────────
        val previewRow = FrameLayout(context).apply {
            setPadding(0, dp(12f).toInt(), 0, dp(8f).toInt())
        }

        val cardW = (screenW * 0.70f).toInt()

        val previewCard = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(8f)
                setColor(ReaderTheme.FILL_06)
            }
            setPadding(dp(12f).toInt(), dp(10f).toInt(), dp(12f).toInt(), dp(10f).toInt())
        }

        previewText = TextView(context).apply {
            typeface = ReaderTheme.body(context)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, bodySizeSp)
            setTextColor(ReaderTheme.INK_87)
            setLineSpacing(0f, ReaderTheme.LINE_SPACING_MULT)
            minLines = 4  // constant height — prevents popup surface reallocation while scrubbing
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
        }
        pageLabel = TextView(context).apply {
            typeface = ReaderTheme.bodyBold(context)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, bodySizeSp)
            setTextColor(ReaderTheme.INK_87)
            gravity = Gravity.TOP or Gravity.END
        }

        previewCard.addView(previewText, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        previewCard.addView(View(context), LinearLayout.LayoutParams(dp(12f).toInt(), 1))
        previewCard.addView(pageLabel, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))

        // Bookmark icon floats in the left margin of the preview row — horizontally centred
        // in the gap between screen edge and card, vertically centred with the card.
        val bookmarkIconPx = dp(66f).toInt()
        val leftGap = (screenW - cardW) / 2
        val bookmarkMarginStart = ((leftGap - bookmarkIconPx) / 2).coerceAtLeast(0)
        previewBookmarkIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_bookmark_outline)
            setColorFilter(ReaderTheme.INK_26, android.graphics.PorterDuff.Mode.SRC_IN)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setOnTouchListener(PenTapListener(context) {
                onBookmarkPage?.invoke(targetPage)
                refreshPreview()
            })
        }

        previewRow.addView(previewCard,
            FrameLayout.LayoutParams(cardW, WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        previewRow.addView(previewBookmarkIcon,
            FrameLayout.LayoutParams(bookmarkIconPx, bookmarkIconPx, Gravity.START or Gravity.CENTER_VERTICAL).apply {
                marginStart = bookmarkMarginStart
            })
        root.addView(previewRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // ── Scrub track row ───────────────────────────────────────────────────
        val trackW = (screenW * 0.75f).toInt()
        track = ScrubTrackView(context).apply {
            onFractionChanged = { f ->
                scrubFraction = f
                refreshPreview()
            }
        }
        val trackRow = FrameLayout(context).apply {
            setPadding(0, dp(4f).toInt(), 0, dp(4f).toInt())
        }
        trackRow.addView(track,
            FrameLayout.LayoutParams(trackW, dp(56f).toInt(), Gravity.CENTER_HORIZONTAL))
        root.addView(trackRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // ── Button row: Cancel | ← | → | Confirm ─────────────────────────────
        root.addView(buildButtonRow(), LinearLayout.LayoutParams(MATCH_PARENT, dp(64f).toInt()))

        // Full-screen so the list fills the page; height MATCH_PARENT (was WRAP_CONTENT drawer).
        popup = PopupWindow(root, MATCH_PARENT, MATCH_PARENT, true).apply {
            isOutsideTouchable = false  // palm rejection: tapping outside must not dismiss
            setOnDismissListener { onDismiss() }
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    fun show(
        anchor: View,
        currentPage: Int,
        pageCount: Int,
        bookmarkFractions: List<Float> = emptyList(),
        bookmarkPageIndices: List<Int> = emptyList(),
        outline: List<OutlineItem> = emptyList(),
    ) {
        this.pageCount = pageCount.coerceAtLeast(1)
        this.bookmarkPages = bookmarkPageIndices
        this.outline = outline
        this.listPage = 0
        this.itemsPerPage = computeItemsPerPage()
        // Open to the tab most likely useful: Outline when the document has headings,
        // otherwise Bookmarks.
        this.selectedTab = if (outline.isNotEmpty()) TAB_OUTLINE else TAB_BOOKMARKS
        scrubFraction = if (this.pageCount > 1) currentPage.toFloat() / (this.pageCount - 1) else 0f
        track.setFraction(scrubFraction)
        track.setBookmarkFractions(bookmarkFractions)
        styleTabs()
        rebuildList()
        refreshPreview()
        if (!popup.isShowing) popup.showAtLocation(anchor, Gravity.CENTER, 0, 0)
    }

    val isShowing: Boolean get() = popup.isShowing

    fun dismiss() { if (popup.isShowing) popup.dismiss() }

    // -------------------------------------------------------------------------
    // Tabs
    // -------------------------------------------------------------------------

    private fun switchTab(tab: Int) {
        if (selectedTab == tab) return
        selectedTab = tab
        listPage = 0
        styleTabs()
        rebuildList()
    }

    private fun styleTabs() {
        applyTabStyle(outlineTab, selectedTab == TAB_OUTLINE)
        applyTabStyle(bookmarkTab, selectedTab == TAB_BOOKMARKS)
    }

    private fun applyTabStyle(tab: TextView, selected: Boolean) {
        // Greyscale-safe: selected reads as a filled pill in bold ink; unselected is dim.
        tab.typeface = if (selected) ReaderTheme.bodyBold(context) else ReaderTheme.body(context)
        tab.setTextColor(if (selected) ReaderTheme.INK_87 else ReaderTheme.INK_54)
        tab.background = if (selected) {
            android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(100f)
                setColor(ReaderTheme.FILL_06)
                setStroke(dp(1f).toInt(), ReaderTheme.INK_26)
            }
        } else null
    }

    private fun tabLabel(text: String, onTap: () -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            typeface = ReaderTheme.body(context)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, bodySizeSp)
            setTextColor(ReaderTheme.INK_54)
            gravity = Gravity.CENTER
            minWidth = dp(140f).toInt()
            minHeight = dp(48f).toInt()
            setPadding(dp(24f).toInt(), dp(10f).toInt(), dp(24f).toInt(), dp(10f).toInt())
            setOnTouchListener(PenTapListener(context, onTap = onTap))
        }

    // -------------------------------------------------------------------------
    // List region (both tabs)
    // -------------------------------------------------------------------------

    /**
     * Rows that fit between the tab strip and the shuttle. Estimated from screen
     * height (~55%); the Manta (lower dpi → more dp) gets more rows than the Nomad.
     */
    private fun computeItemsPerPage(): Int {
        val dm = context.resources.displayMetrics
        val screenHeightDp = dm.heightPixels / dm.density
        // 55% of screen height is the list budget; subtract the pagination nav row so
        // it always has room to render when there are multiple pages.
        val paginationRowDp = 48f
        val availableDp = (screenHeightDp * 0.55f - paginationRowDp).coerceAtLeast(0f)
        val rowDp = bodySizeSp + 28f + 1f   // text height + vertical padding + divider
        return (availableDp / rowDp).toInt().coerceIn(3, 12)
    }

    private fun advanceListPage(delta: Int) {
        val rows = when (selectedTab) {
            TAB_OUTLINE -> outline.size
            else -> bookmarkPages.size
        }
        val total = (rows + itemsPerPage - 1) / itemsPerPage
        val next = (listPage + delta).coerceIn(0, total - 1)
        if (next != listPage) { listPage = next; rebuildList() }
    }

    private fun rebuildList() {
        listRegion.removeAllViews()
        when (selectedTab) {
            TAB_OUTLINE -> buildRows(
                empty = "No headings in this document.",
                rows = outline.map { RowSpec(it.text, it.pageIndex, it.level) },
            )
            else -> buildRows(
                empty = "No bookmarks yet.",
                rows = bookmarkPages.map { p ->
                    RowSpec(previewProvider(p).replace('\n', ' ').trim(), p, indent = 0)
                },
            )
        }
    }

    private fun buildRows(empty: String, rows: List<RowSpec>) {
        if (rows.isEmpty()) {
            listRegion.addView(TextView(context).apply {
                text = empty
                typeface = ReaderTheme.body(context)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, bodySizeSp)
                setTextColor(ReaderTheme.INK_54)
                gravity = Gravity.CENTER
                setPadding(dp(16f).toInt(), dp(32f).toInt(), dp(16f).toInt(), dp(16f).toInt())
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            return
        }

        val totalListPages = (rows.size + itemsPerPage - 1) / itemsPerPage
        listPage = listPage.coerceIn(0, totalListPages - 1)
        val slice = rows.drop(listPage * itemsPerPage).take(itemsPerPage)

        for (spec in slice) {
            val itemRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    dp(16f + spec.indent * 16f).toInt(), dp(14f).toInt(),
                    dp(16f).toInt(), dp(14f).toInt(),
                )
                isClickable = true
                isFocusable = true
                setOnTouchListener(PenTapListener(context) { onConfirm(spec.pageIndex); dismiss() })
            }
            itemRow.addView(TextView(context).apply {
                text = spec.primary
                typeface = ReaderTheme.body(context)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, bodySizeSp)
                setTextColor(ReaderTheme.INK_87)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            })
            itemRow.addView(TextView(context).apply {
                text = "p. ${spec.pageIndex + 1}"
                typeface = ReaderTheme.body(context)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, bodySizeSp * 0.85f)
                setTextColor(ReaderTheme.INK_54)
                minWidth = dp(56f).toInt()
                gravity = Gravity.END
            })
            listRegion.addView(itemRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            listRegion.addView(View(context).apply { setBackgroundColor(ReaderTheme.INK_12) },
                LinearLayout.LayoutParams(MATCH_PARENT, dp(1f).toInt()))
        }

        // Spacer pushes the nav row to the bottom of listRegion regardless of item count.
        listRegion.addView(View(context), LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // Pagination nav — pinned to bottom; always rendered so the divider line is stable.
        val navRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f).toInt(), dp(4f).toInt(), dp(16f).toInt(), dp(4f).toInt())
        }
        if (totalListPages > 1) {
            navRow.addView(pillBtn("←", if (listPage > 0) ReaderTheme.INK_87 else ReaderTheme.INK_26) {
                if (listPage > 0) { listPage--; rebuildList() }
            })
            navRow.addView(TextView(context).apply {
                text = "${listPage + 1} / $totalListPages"
                typeface = ReaderTheme.body(context)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, bodySizeSp * 0.85f)
                setTextColor(ReaderTheme.INK_54)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            })
            navRow.addView(pillBtn("→", if (listPage < totalListPages - 1) ReaderTheme.INK_87 else ReaderTheme.INK_26) {
                if (listPage < totalListPages - 1) { listPage++; rebuildList() }
            })
        }
        listRegion.addView(navRow, LinearLayout.LayoutParams(MATCH_PARENT, dp(48f).toInt()))
    }

    private data class RowSpec(val primary: String, val pageIndex: Int, val indent: Int)

    // -------------------------------------------------------------------------
    // Shuttle (unchanged behavior)
    // -------------------------------------------------------------------------

    private fun refreshPreview() {
        val page = targetPage
        previewText.text = previewProvider(page)
        pageLabel.text = "${page + 1} / $pageCount"
        val bookmarked = isPageBookmarked?.invoke(page) == true
        previewBookmarkIcon.setImageResource(
            if (bookmarked) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark_outline
        )
        previewBookmarkIcon.setColorFilter(
            if (bookmarked) ReaderTheme.INK_87 else ReaderTheme.INK_26,
            android.graphics.PorterDuff.Mode.SRC_IN
        )
    }

    private fun stepBack() {
        if (pageCount <= 1) return
        scrubFraction = (scrubFraction - 1f / (pageCount - 1)).coerceIn(0f, 1f)
        track.setFraction(scrubFraction)
        refreshPreview()
    }

    private fun stepForward() {
        if (pageCount <= 1) return
        scrubFraction = (scrubFraction + 1f / (pageCount - 1)).coerceIn(0f, 1f)
        track.setFraction(scrubFraction)
        refreshPreview()
    }

    private fun commitJump() {
        onConfirm(targetPage)
        dismiss()
    }

    private fun buildButtonRow(): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // Cancel — left quarter
        row.addView(
            pillBtn("Cancel", ReaderTheme.INK_54) { dismiss() },
            LinearLayout.LayoutParams(0, MATCH_PARENT, 1f),
        )
        // ← | → inside a single capsule with a centre divider
        val arrowCapsule = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(100f)
                setColor(ReaderTheme.FILL_06)
                setStroke(dp(1f).toInt(), ReaderTheme.INK_26)
            }
        }
        arrowCapsule.addView(
            pillBtn("←", ReaderTheme.INK_87, textSp = 22f) { stepBack() }.apply {
                setPadding(0, 0, 0, dp(4f).toInt())
            },
            LinearLayout.LayoutParams(dp(56f).toInt(), MATCH_PARENT),
        )
        arrowCapsule.addView(View(context).apply {
            setBackgroundColor(ReaderTheme.INK_26)
        }, LinearLayout.LayoutParams(dp(1f).toInt(), MATCH_PARENT))
        arrowCapsule.addView(
            pillBtn("→", ReaderTheme.INK_87, textSp = 22f) { stepForward() }.apply {
                setPadding(0, 0, 0, dp(4f).toInt())
            },
            LinearLayout.LayoutParams(dp(56f).toInt(), MATCH_PARENT),
        )
        val capsuleLp = LinearLayout.LayoutParams(dp(114f).toInt(), MATCH_PARENT)
        capsuleLp.topMargin = dp(10f).toInt()
        capsuleLp.bottomMargin = dp(10f).toInt()
        row.addView(arrowCapsule, capsuleLp)
        // Confirm — right quarter
        row.addView(
            pillBtn("Confirm", ReaderTheme.INK_87) { commitJump() },
            LinearLayout.LayoutParams(0, MATCH_PARENT, 1f),
        )
        return row
    }

    private fun pillBtn(label: String, color: Int, textSp: Float = 15f, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            typeface = ReaderTheme.bodyBold(context)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
            setTextColor(color)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setOnTouchListener(PenTapListener(context, onTap = onClick))
        }

    private fun dp(v: Float) = ReaderTheme.dp(context, v)

    private companion object {
        const val TAB_OUTLINE = 0
        const val TAB_BOOKMARKS = 1
    }
}

// =============================================================================
// ScrubTrackView — the draggable track inside PageJumpOverlay
// =============================================================================

/**
 * The horizontal scrub track. Thumb is a rounded rect (14×40dp, radius 4dp).
 */
private class ScrubTrackView(context: Context) : View(context) {

    var onFractionChanged: ((Float) -> Unit)? = null

    private var fraction = 0f
    private var bookmarkFractions: List<Float> = emptyList()

    private val density = resources.displayMetrics.density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ReaderTheme.INK_26
        strokeWidth = dp(2f)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ReaderTheme.INK_87
        style = Paint.Style.FILL
    }
    private val bookmarkTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ReaderTheme.INK_87
        strokeWidth = dp(2.5f)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val thumbW = dp(14f)
    private val thumbH = dp(40f)
    private val thumbR = dp(4f)
    private val bookmarkTickHalfH = dp(15f)
    private val thumbRect = RectF()

    fun setFraction(f: Float) {
        fraction = f.coerceIn(0f, 1f)
        invalidate()
    }

    fun setBookmarkFractions(fractions: List<Float>) {
        bookmarkFractions = fractions
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val cx = height / 2f
        val usable = w - thumbW

        // Bookmark marks
        for (f in bookmarkFractions) {
            val tx = thumbW / 2f + f * usable
            canvas.drawLine(tx, cx - bookmarkTickHalfH, tx, cx + bookmarkTickHalfH, bookmarkTickPaint)
        }

        // Track line
        canvas.drawLine(thumbW / 2f, cx, w - thumbW / 2f, cx, trackPaint)

        // Thumb: rounded rect centred on the fraction position
        val thumbCx = thumbW / 2f + fraction * usable
        thumbRect.set(
            thumbCx - thumbW / 2f,
            cx - thumbH / 2f,
            thumbCx + thumbW / 2f,
            cx + thumbH / 2f,
        )
        canvas.drawRoundRect(thumbRect, thumbR, thumbR, thumbPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE -> {
                val usable = (width - thumbW).coerceAtLeast(1f)
                fraction = ((event.x - thumbW / 2f) / usable).coerceIn(0f, 1f)
                invalidate()
                onFractionChanged?.invoke(fraction)
                return true
            }
        }
        return true
    }

    private fun dp(v: Float) = v * density
}

/**
 * LinearLayout that intercepts horizontal flings for list-page navigation.
 * Child views (rows with PenTapListener) normally consume ACTION_DOWN, so
 * a parent OnTouchListener never sees the full gesture. onInterceptTouchEvent
 * takes over as soon as horizontal intent is clear, then onTouchEvent fires.
 */
private class SwipeListLayout(context: Context) : LinearLayout(context) {
    var onFlingLeft: (() -> Unit)? = null
    var onFlingRight: (() -> Unit)? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val minSwipe = (48f * context.resources.displayMetrics.density).toInt()
    private var startX = 0f
    private var startY = 0f

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { startX = ev.x; startY = ev.y }
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(ev.x - startX)
                val dy = abs(ev.y - startY)
                if (dx > slop && dx > dy * 1.5f) return true  // claim the gesture
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_UP) {
            val dx = ev.x - startX
            if (abs(dx) > minSwipe) {
                if (dx < 0) onFlingLeft?.invoke() else onFlingRight?.invoke()
            }
        }
        return true
    }
}

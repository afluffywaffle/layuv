package com.afluffywaffle.layuv.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Full-width bottom overlay for page navigation. Mirrors Flutter's _buildJumpOverlay():
 *
 *   ┌──────────────────────────────────────────────────────────────────────────┐
 *   │  1dp divider (top shadow substitute)                                     │
 *   │                                                                          │
 *   │        ┌──────────────────────────────────────┐  "12 / 340"             │
 *   │        │ preview — first lines of target page │                          │
 *   │        └──────────────────────────────────────┘   ← 50% screen width    │
 *   │                                                                          │
 *   │     ◀◀◀◀◀◀◀◀◀◀◀◀◀◀◀◀◀[   thumb   ]▶▶▶▶▶▶▶▶▶▶▶▶▶▶▶▶▶▶  ← 75% width  │
 *   │                                                                          │
 *   │   Cancel          ←              →           Confirm                    │
 *   └──────────────────────────────────────────────────────────────────────────┘
 *
 * E-ink behavior: dragging updates the preview only — the reader does NOT scroll
 * until "Confirm" is tapped. ← / → step one page at a time for stylus precision.
 * Tapping anywhere outside the overlay (via the dimming layer) = Cancel.
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

    // Paginated bookmark list state
    private var bookmarkPages: List<Int> = emptyList()  // 0-based page indices
    private var bookmarkListPage = 0  // which page of the bookmark list is showing
    private var bookmarksPerPage = 3  // recalculated at show() time from screen height

    private val targetPage: Int
        get() = (scrubFraction * (pageCount - 1)).roundToInt().coerceIn(0, pageCount - 1)

    // -------------------------------------------------------------------------
    // Views
    // -------------------------------------------------------------------------

    private val previewText: TextView
    private val pageLabel: TextView
    private val track: ScrubTrackView
    private val popup: PopupWindow
    private var bookmarkRow: LinearLayout? = null

    init {
        val screenW = context.resources.displayMetrics.widthPixels

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ReaderTheme.PAPER)
            // Software layer: alpha compositing in PopupWindow broken under hardware acceleration on e-ink
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }

        // Top divider (visual shadow substitute on e-ink)
        root.addView(View(context).apply {
            setBackgroundColor(ReaderTheme.INK_26)
        }, LinearLayout.LayoutParams(MATCH_PARENT, dp(1f).toInt()))

        // ── Bookmark list (only visible when bookmarks exist) ─────────────────
        bookmarkRow = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        root.addView(bookmarkRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // ── Preview row ───────────────────────────────────────────────────────
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

        previewRow.addView(previewCard,
            FrameLayout.LayoutParams(cardW, WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
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

        popup = PopupWindow(root, MATCH_PARENT, WRAP_CONTENT, true).apply {
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
    ) {
        this.pageCount = pageCount.coerceAtLeast(1)
        this.bookmarkPages = bookmarkPageIndices
        this.bookmarkListPage = 0
        this.bookmarksPerPage = computeBookmarksPerPage()
        scrubFraction = if (pageCount > 1) currentPage.toFloat() / (pageCount - 1) else 0f
        track.setFraction(scrubFraction)
        track.setBookmarkFractions(bookmarkFractions)
        rebuildBookmarkRow()
        refreshPreview()
        if (!popup.isShowing) popup.showAtLocation(anchor, Gravity.BOTTOM, 0, 0)
    }

    val isShowing: Boolean get() = popup.isShowing

    fun dismiss() { if (popup.isShowing) popup.dismiss() }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    /**
     * How many bookmark rows fit in ~40% of the screen height.
     * Each row occupies bodySizeSp + 24dp padding + 1dp divider.
     * The formula naturally gives more rows on the Manta (lower dpi → more dp)
     * than on the Nomad (higher dpi → fewer dp), matching their physical screen sizes.
     * Clamped to [2, 12] as a sanity bound.
     */
    private fun computeBookmarksPerPage(): Int {
        val dm = context.resources.displayMetrics
        val screenHeightDp = dm.heightPixels / dm.density
        val rowDp = bodySizeSp + 24f + 1f   // text + vertical padding + divider
        return ((screenHeightDp * 0.40f) / rowDp).toInt().coerceIn(2, 12)
    }

    private fun rebuildBookmarkRow() {
        val row = bookmarkRow ?: return
        row.removeAllViews()
        if (bookmarkPages.isEmpty()) {
            row.visibility = View.GONE
            return
        }
        row.visibility = View.VISIBLE

        val totalListPages = (bookmarkPages.size + bookmarksPerPage - 1) / bookmarksPerPage
        val slice = bookmarkPages.drop(bookmarkListPage * bookmarksPerPage).take(bookmarksPerPage)

        // Top divider + "Bookmarks" header
        row.addView(View(context).apply {
            setBackgroundColor(ReaderTheme.INK_12)
        }, LinearLayout.LayoutParams(MATCH_PARENT, dp(1f).toInt()))

        row.addView(TextView(context).apply {
            text = "Bookmarks"
            typeface = ReaderTheme.bodyBold(context)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, bodySizeSp * 0.90f)
            setTextColor(ReaderTheme.INK_54)
            setPadding(dp(16f).toInt(), dp(8f).toInt(), dp(16f).toInt(), dp(6f).toInt())
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        row.addView(View(context).apply {
            setBackgroundColor(ReaderTheme.INK_12)
        }, LinearLayout.LayoutParams(MATCH_PARENT, dp(1f).toInt()))

        for (pageIdx in slice) {
            val preview = previewProvider(pageIdx).replace('\n', ' ').trim()

            val itemRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16f).toInt(), dp(12f).toInt(), dp(16f).toInt(), dp(12f).toInt())
                isClickable = true
                isFocusable = true
                setOnTouchListener(PenTapListener(context) { onConfirm(pageIdx); dismiss() })
            }
            itemRow.addView(TextView(context).apply {
                text = "p. ${pageIdx + 1}"
                typeface = ReaderTheme.body(context)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, bodySizeSp * 0.85f)
                setTextColor(ReaderTheme.INK_54)
                minWidth = dp(52f).toInt()
            })
            itemRow.addView(TextView(context).apply {
                text = preview
                typeface = ReaderTheme.body(context)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, bodySizeSp)
                setTextColor(ReaderTheme.INK_87)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            })
            row.addView(itemRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

            row.addView(View(context).apply {
                setBackgroundColor(ReaderTheme.INK_12)
            }, LinearLayout.LayoutParams(MATCH_PARENT, dp(1f).toInt()))
        }

        // Pagination arrows — only when there are multiple list pages
        if (totalListPages > 1) {
            val navRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16f).toInt(), dp(4f).toInt(), dp(16f).toInt(), dp(4f).toInt())
            }
            navRow.addView(pillBtn("←", if (bookmarkListPage > 0) ReaderTheme.INK_87 else ReaderTheme.INK_26) {
                if (bookmarkListPage > 0) { bookmarkListPage--; rebuildBookmarkRow() }
            })
            navRow.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            navRow.addView(pillBtn("→", if (bookmarkListPage < totalListPages - 1) ReaderTheme.INK_87 else ReaderTheme.INK_26) {
                if (bookmarkListPage < totalListPages - 1) { bookmarkListPage++; rebuildBookmarkRow() }
            })
            row.addView(navRow, LinearLayout.LayoutParams(MATCH_PARENT, dp(48f).toInt()))
            row.addView(View(context).apply {
                setBackgroundColor(ReaderTheme.INK_12)
            }, LinearLayout.LayoutParams(MATCH_PARENT, dp(1f).toInt()))
        }
    }

    private fun refreshPreview() {
        val page = targetPage
        previewText.text = previewProvider(page)
        pageLabel.text = "${page + 1} / $pageCount"
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
            setOnTouchListener(PenTapListener(context, onClick))
        }

    private fun dp(v: Float) = ReaderTheme.dp(context, v)
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

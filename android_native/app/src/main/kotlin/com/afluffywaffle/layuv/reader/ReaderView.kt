package com.afluffywaffle.layuv.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import com.afluffywaffle.layuv.docx.Anchoring
import com.afluffywaffle.layuv.docx.ResolvedAnnotation
import com.afluffywaffle.layuv.docx.TextSpan
import com.afluffywaffle.layuv.docx.model.AnnotationTool
import java.util.concurrent.Executors

/**
 * The reader surface. A LAYER_TYPE_SOFTWARE [View] that owns its own [onDraw]
 * (translate + clip + StaticLayout.draw, one pass per column) and every EPD
 * waveform via [Epd] — no Compose, no framework compositor.
 *
 * Navigation: tap left half = prev, tap right half = next (no swipe, e-ink rules).
 * Selection: long-press (finger) or drag (stylus) → word-snap → [onSelectionReady]
 * callback fires with the final char range and popup anchor coordinates.
 */
class ReaderView(context: Context) : View(context) {

    var onPageChanged: ((page: Int, pageCount: Int) -> Unit)? = null
    var onSelectionReady: ((start: Int, end: Int, anchorX: Int, anchorY: Int) -> Unit)? = null

    private val epd = Epd()
    private val highlights = HighlightPainter(context)
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private val hPadding = ReaderTheme.dp(context, ReaderTheme.H_PADDING_DP)
    private val vPadding = ReaderTheme.dp(context, ReaderTheme.V_PADDING_DP)
    private val columnGap = ReaderTheme.dp(context, ReaderTheme.COLUMN_GAP_DP)
    private val bodySize = ReaderTheme.sp(context, ReaderTheme.BODY_TEXT_SP)

    private var text: CharSequence? = null
    private var annotations: List<ResolvedAnnotation> = emptyList()
    private var desiredColumns = 1

    private var pageLayout: PageLayout? = null
    private var currentPage = 0
    private var pendingCharOffset = 0
    private var paginateGeneration = 0

    // --- Selection state ------------------------------------------------------

    // Char offsets in the canonical plain text. -1 = no selection.
    private var selectionStart = -1
    private var selectionEnd = -1
    // True while the user is still actively dragging the selection (before
    // onSelectionReady fires); kept false after the popup is shown so that
    // tapping outside the popup without picking a tool falls through to
    // cancelSelection() rather than re-entering selection mode.
    private var isSelecting = false

    // Stylus-specific tracking across ACTION_DOWN → ACTION_MOVE → ACTION_UP.
    private var stylusDownX = 0f
    private var stylusDownY = 0f
    private var stylusMoved = false
    private val tapSlopPx = ReaderTheme.dp(context, 8f)

    // --- Hint / loading -------------------------------------------------------

    private val hintPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ReaderTheme.INK
        typeface = ReaderTheme.body(context)
        textSize = ReaderTheme.sp(context, 16f)
        alpha = 150
    }
    private var hint: String? = null

    // --- Gesture detector -----------------------------------------------------

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (pageLayout == null) return false
            // If selection is drawn, a tap dismisses it instead of turning a page.
            if (selectionStart >= 0) {
                cancelSelection()
                return true
            }
            if (e.x < width / 2f) prev() else next()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            val char = charAtPoint(e.x, e.y) ?: return
            val t = text?.toString() ?: return
            val snapped = Anchoring.snapToWordBoundaries(t, char, (char + 1).coerceAtMost(t.length))
            selectionStart = snapped.start
            selectionEnd = snapped.end
            isSelecting = true
            epd.selection(this@ReaderView)
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }
    })

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        isClickable = true
        isLongClickable = true
    }

    // --- Public API -----------------------------------------------------------

    fun showContent(
        text: CharSequence,
        annotations: List<ResolvedAnnotation>,
        columns: Int,
        startCharOffset: Int,
    ) {
        this.text = text
        this.annotations = annotations
        this.desiredColumns = columns.coerceIn(1, 2)
        this.hint = null
        this.pendingCharOffset = startCharOffset
        this.pageLayout = null
        this.currentPage = 0
        cancelSelection()
        repaginate(fullClear = true)
    }

    fun showHint(message: String) {
        text = null
        pageLayout = null
        hint = message
        invalidate()
    }

    fun setColumns(columns: Int) {
        val c = columns.coerceIn(1, 2)
        if (c == desiredColumns) return
        desiredColumns = c
        repaginate(fullClear = true)
    }

    fun columns(): Int = desiredColumns

    /** Update the annotation list and redraw without repaginating (text unchanged). */
    fun updateAnnotations(annotations: List<ResolvedAnnotation>) {
        this.annotations = annotations
        invalidate()
    }

    /** The plain text string, or null if no document is loaded. */
    fun textString(): String? = text?.toString()

    /** Cancel any active selection and redraw. */
    fun cancelSelection() {
        selectionStart = -1
        selectionEnd = -1
        isSelecting = false
        epd.selection(this)
    }

    /** Trigger a full EPD clear on this view. */
    fun fullClear() = epd.fullClear(this)

    fun next() {
        val pl = pageLayout ?: return
        if (currentPage >= pl.pageCount - 1) return
        currentPage++
        pendingCharOffset = pl.charStartOfPage(currentPage)
        notifyPage()
        epd.pageTurn(this)
    }

    fun prev() {
        val pl = pageLayout ?: return
        if (currentPage <= 0) return
        currentPage--
        pendingCharOffset = pl.charStartOfPage(currentPage)
        notifyPage()
        epd.pageTurn(this)
    }

    fun currentCharOffset(): Int = pageLayout?.charStartOfPage(currentPage) ?: pendingCharOffset

    /** Navigate to the page containing [charOffset] and apply a full EPD clear. */
    fun jumpToChar(charOffset: Int) {
        val pl = pageLayout
        if (pl != null) {
            currentPage = pl.pageForChar(charOffset)
            pendingCharOffset = charOffset
            notifyPage()
            epd.fullClear(this)
        } else {
            pendingCharOffset = charOffset
        }
    }

    fun textLength(): Int = text?.length ?: 0

    fun pageInfo(): Pair<Int, Int> = currentPage to (pageLayout?.pageCount ?: 1)

    // --- Layout / pagination --------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (text != null) repaginate(fullClear = true)
    }

    private fun contentWidthPx(): Int = (width - hPadding * 2).toInt()
    private fun contentHeightPx(): Int = (height - vPadding * 2).toInt()

    private fun makePaint(): TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ReaderTheme.INK
        typeface = ReaderTheme.body(context)
        textSize = bodySize
    }

    private fun repaginate(fullClear: Boolean) {
        val t = text ?: return
        val w = contentWidthPx()
        val h = contentHeightPx()
        if (w <= 0 || h <= 0) return

        val gen = ++paginateGeneration
        val cols = desiredColumns
        val gap = columnGap.toInt()
        val targetChar = pageLayout?.charStartOfPage(currentPage) ?: pendingCharOffset
        val paint = makePaint()

        executor.execute {
            val t0 = System.nanoTime()
            val pl = PageLayout.paginate(t, paint, w, h, cols, gap)
            val ms = (System.nanoTime() - t0) / 1_000_000
            main.post {
                if (gen != paginateGeneration) return@post
                pageLayout = pl
                currentPage = pl.pageForChar(targetChar)
                pendingCharOffset = targetChar
                Log.i(
                    TAG,
                    "paginate: chars=${t.length} lines=${pl.layout.lineCount} " +
                        "cols=$cols pages=${pl.pageCount} in ${ms}ms",
                )
                notifyPage()
                if (fullClear) epd.fullClear(this) else invalidate()
            }
        }
    }

    private fun notifyPage() {
        val pl = pageLayout ?: return
        onPageChanged?.invoke(currentPage, pl.pageCount)
    }

    // --- Drawing --------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(ReaderTheme.PAPER)
        val pl = pageLayout
        if (pl == null) {
            drawHint(canvas)
            return
        }

        val contentLeft = hPadding
        val contentTop = vPadding
        val colWidth = pl.columnWidthPx
        val colHeight = pl.columnHeightPx
        val firstCol = pl.firstColumnOfPage(currentPage)

        for (colInPage in 0 until pl.columns) {
            val column = firstCol + colInPage
            if (column >= pl.columnCount) break
            val startLine = pl.lineStartOfColumn(column)
            val endLine = pl.lineEndOfColumn(column)
            if (endLine <= startLine) continue

            val colX = contentLeft + colInPage * (colWidth + pl.columnGapPx)
            val lineTop = pl.layout.getLineTop(startLine)
            val packedHeight = (pl.layout.getLineBottom(endLine - 1) - lineTop)
                .coerceAtMost(colHeight)

            canvas.save()
            canvas.clipRect(colX, contentTop, colX + colWidth, contentTop + packedHeight)
            canvas.translate(colX, contentTop - lineTop)
            pl.layout.draw(canvas)
            if (annotations.isNotEmpty()) {
                highlights.drawColumn(canvas, pl.layout, startLine, endLine, annotations)
            }
            // Active selection drawn on top of saved annotations so it reads clearly.
            if (selectionStart >= 0 && selectionEnd > selectionStart) {
                val colStartChar = pl.layout.getLineStart(startLine)
                val colEndChar = pl.layout.getLineEnd(endLine - 1)
                if (selectionStart < colEndChar && selectionEnd > colStartChar) {
                    highlights.drawSpan(
                        canvas, pl.layout, startLine, endLine,
                        TextSpan(selectionStart, selectionEnd),
                        AnnotationTool.highlight,
                    )
                }
            }
            canvas.restore()
        }
    }

    private fun drawHint(canvas: Canvas) {
        val message = hint ?: return
        val x = width / 2f - hintPaint.measureText(message) / 2f
        val y = height / 2f
        canvas.drawText(message, x.coerceAtLeast(hPadding), y, hintPaint)
    }

    // --- Touch handling -------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val toolType = event.getToolType(0)
        if (toolType == MotionEvent.TOOL_TYPE_STYLUS) {
            return handleStylusEvent(event)
        }

        // While a finger-selection drag is in progress, route MOVE/UP here rather
        // than to the GestureDetector (which doesn't know about selection mode).
        if (isSelecting) {
            when (event.action) {
                MotionEvent.ACTION_MOVE -> {
                    val char = charAtPoint(event.x, event.y) ?: return true
                    val newEnd = char.coerceAtLeast(selectionStart + 1)
                    if (newEnd != selectionEnd) {
                        selectionEnd = newEnd
                        epd.selection(this)
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    finaliseSelection()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    cancelSelection()
                    return true
                }
            }
        }

        return gestures.onTouchEvent(event) || super.onTouchEvent(event)
    }

    private fun handleStylusEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                stylusDownX = event.x
                stylusDownY = event.y
                stylusMoved = false
                selectionStart = charAtPoint(event.x, event.y) ?: return false
                selectionEnd = selectionStart
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - stylusDownX
                val dy = event.y - stylusDownY
                if (!stylusMoved && dx * dx + dy * dy > tapSlopPx * tapSlopPx) {
                    stylusMoved = true
                    isSelecting = true
                }
                if (stylusMoved) {
                    val char = charAtPoint(event.x, event.y) ?: return true
                    val newEnd = char.coerceAtLeast(selectionStart + 1)
                    if (newEnd != selectionEnd) {
                        selectionEnd = newEnd
                        val sel = selectionAnchorInView()
                        if (sel != null) epd.region(this, 0, sel.second, width, (sel.second + hPadding * 3).toInt())
                        else epd.selection(this)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!stylusMoved) {
                    // Short stylus tap → page navigation, no selection.
                    selectionStart = -1
                    selectionEnd = -1
                    isSelecting = false
                    if (event.x < width / 2f) prev() else next()
                } else {
                    finaliseSelection()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelSelection()
                return true
            }
        }
        return false
    }

    /** Snap the current raw selection to word boundaries and fire [onSelectionReady]. */
    private fun finaliseSelection() {
        val t = text?.toString() ?: run { cancelSelection(); return }
        if (selectionStart < 0 || selectionEnd <= selectionStart) {
            cancelSelection()
            return
        }
        val snapped = Anchoring.snapToWordBoundaries(t, selectionStart, selectionEnd)
        selectionStart = snapped.start
        selectionEnd = snapped.end
        isSelecting = false
        epd.selection(this)
        val anchor = selectionAnchorInView()
        onSelectionReady?.invoke(
            selectionStart, selectionEnd,
            anchor?.first ?: (width / 2),
            anchor?.second ?: (height / 4),
        )
    }

    // --- Coordinate helpers ---------------------------------------------------

    /**
     * Maps a view-relative touch point to the nearest char offset in the
     * canonical plain text (using the layout for the current page). Returns null
     * if the layout isn't ready or the point is outside the content area.
     */
    private fun charAtPoint(vx: Float, vy: Float): Int? {
        val pl = pageLayout ?: return null
        val cx = vx - hPadding
        val cy = vy - vPadding
        if (cy < 0) return null

        val cols = pl.columns
        val colWidth = pl.columnWidthPx.toFloat()
        val colGap = pl.columnGapPx.toFloat()
        val firstCol = pl.firstColumnOfPage(currentPage)

        val colInPage = when {
            cols == 1 -> 0
            cx < colWidth -> 0
            else -> 1
        }.coerceIn(0, cols - 1)

        val colX = colInPage * (colWidth + colGap)
        val localX = cx - colX

        val column = firstCol + colInPage
        if (column >= pl.columnCount) return null

        val startLine = pl.lineStartOfColumn(column)
        val endLine = pl.lineEndOfColumn(column)
        if (endLine <= startLine) return null

        val lineTopOfCol = pl.layout.getLineTop(startLine).toFloat()
        val layoutY = (cy + lineTopOfCol).toInt()
        val line = pl.layout.getLineForVertical(layoutY).coerceIn(startLine, endLine - 1)

        return pl.layout.getOffsetForHorizontal(line, localX)
    }

    /**
     * View-relative coordinates of the midpoint above the first line of the
     * current selection — used to anchor the annotation popup.
     */
    private fun selectionAnchorInView(): Pair<Int, Int>? {
        val pl = pageLayout ?: return null
        if (selectionStart < 0 || selectionEnd <= selectionStart) return null

        val firstCol = pl.firstColumnOfPage(currentPage)
        var colInPage = 0
        for (c in 0 until pl.columns) {
            val col = firstCol + c
            if (col >= pl.columnCount) break
            val colStart = pl.layout.getLineStart(pl.lineStartOfColumn(col))
            val colEnd = pl.layout.getLineEnd(pl.lineEndOfColumn(col) - 1)
            if (selectionStart in colStart..colEnd) {
                colInPage = c
                break
            }
        }

        val column = firstCol + colInPage
        if (column >= pl.columnCount) return null
        val startLine = pl.lineStartOfColumn(column)
        val endLine = pl.lineEndOfColumn(column)
        val lineTopOfCol = pl.layout.getLineTop(startLine).toFloat()

        val selLine = pl.layout.getLineForOffset(selectionStart).coerceIn(startLine, endLine - 1)
        val lineTop = pl.layout.getLineTop(selLine).toFloat()

        val xStart = pl.layout.getPrimaryHorizontal(selectionStart)
        val lineEndChar = pl.layout.getLineEnd(selLine)
        val xEnd = pl.layout.getPrimaryHorizontal(minOf(selectionEnd, lineEndChar - 1).coerceAtLeast(selectionStart))
        val midX = (xStart + xEnd) / 2f

        val colX = hPadding + colInPage * (pl.columnWidthPx + pl.columnGapPx).toFloat()
        val viewX = (colX + midX).toInt()
        val viewY = (vPadding + lineTop - lineTopOfCol).toInt()

        return Pair(viewX, viewY)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        executor.shutdownNow()
    }

    companion object {
        private const val TAG = "LeamhReader"
    }
}

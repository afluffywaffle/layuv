package com.afluffywaffle.layuv.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * The reader's edge-navigation affordance, factored out as a standalone [View] so
 * Help can present the *same* navigation the reader uses. Tall strips run down the
 * left and right edges; a faint dotted rail marks each strip's inner edge (hinting the
 * whole column is tappable) and a midline meets that rail to split the strip top
 * (= next) / bottom (= prev), with a faint chevron in each half — top points right
 * (forward), bottom points left (back). Strip width is [NAV_STRIP_DP], matching the
 * reader's baked-in nav so the two surfaces read the same.
 *
 * Two uses:
 *  - **Interactive overlay** (Help paging): pass [onNext]/[onPrev]; a tap inside a
 *    strip turns the page. Taps elsewhere fall through (returns false on DOWN), and a
 *    drag is ignored so a swipe handled by the host isn't double-counted.
 *  - **Static diagram** (Help "Reading" page): leave the callbacks null and set
 *    [diagram] = true to also draw a rounded page outline, strip separators, and
 *    small Next/Prev/page labels.
 *
 * E-ink: static vector strokes, no animation. The host invalidates on page change.
 */
class EdgeNavView(
    context: Context,
    private val diagram: Boolean = false,
    private val onNext: (() -> Unit)? = null,
    private val onPrev: (() -> Unit)? = null,
) : View(context) {

    private val stripWidth = ReaderTheme.dp(context, NAV_STRIP_DP)
    private val chevronHalfW = ReaderTheme.dp(context, 8f)
    private val chevronHalfH = ReaderTheme.dp(context, 12f)
    private val path = Path()

    private val hairlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ReaderTheme.INK
        alpha = 90 // divider between the top (next) and bottom (prev) zones
        style = Paint.Style.STROKE
        strokeWidth = ReaderTheme.dp(context, 1.5f)
    }
    private val chevronPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ReaderTheme.INK
        alpha = 55
        style = Paint.Style.STROKE
        strokeWidth = ReaderTheme.dp(context, 2.5f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    // Live overlay: a faint, finely dotted line at each strip's inner edge (and the
    // midline). Hints the WHOLE column is a tap zone, while staying quiet enough not
    // to compete with the text. Shared spec — see [ReaderTheme.dottedLinePaint].
    private val lanePaint = ReaderTheme.dottedLinePaint(context)

    // Diagram-only paints (page outline, strip separators, labels).
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ReaderTheme.INK_26
        style = Paint.Style.STROKE
        strokeWidth = ReaderTheme.dp(context, 2f)
    }
    private val separatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ReaderTheme.INK_26
        style = Paint.Style.STROKE
        strokeWidth = ReaderTheme.dp(context, 1f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ReaderTheme.INK_54
        textAlign = Paint.Align.CENTER
        textSize = ReaderTheme.sp(context, 12f)
        typeface = ReaderTheme.chrome(context)
    }
    private val textLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ReaderTheme.INK_38
        style = Paint.Style.STROKE
        strokeWidth = ReaderTheme.dp(context, 3.5f)
        strokeCap = Paint.Cap.ROUND
    }

    private var downX = 0f
    private var downY = 0f
    private val tapSlop = ReaderTheme.dp(context, 12f)

    init {
        // Software layer so the dashed lane line renders reliably (and matches the
        // reader, which is a software-layer surface).
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (diagram) {
            val r = ReaderTheme.dp(context, ReaderTheme.RADIUS_CARD)
            val inset = outlinePaint.strokeWidth
            canvas.drawRoundRect(RectF(inset, inset, w - inset, h - inset), r, r, outlinePaint)
            canvas.drawLine(stripWidth, inset, stripWidth, h - inset, separatorPaint)
            canvas.drawLine(w - stripWidth, inset, w - stripWidth, h - inset, separatorPaint)
            drawTextLines(canvas, w, h)
        } else {
            // Faint dotted rails marking the inner edge of each tap strip.
            canvas.drawLine(stripWidth, 0f, stripWidth, h, lanePaint)
            canvas.drawLine(w - stripWidth, 0f, w - stripWidth, h, lanePaint)
        }
        drawStrip(canvas, 0f, h)
        drawStrip(canvas, w - stripWidth, h)
    }

    private fun drawStrip(canvas: Canvas, left: Float, h: Float) {
        val cx = left + stripWidth / 2f
        val midY = h / 2f
        // Midline splitting top (next) / bottom (prev). In the live overlay it shares
        // the rail's faint dotted style and meets it, so it reads as anchored rather
        // than a floating stub; the teaching diagram keeps a crisp solid divider.
        canvas.drawLine(left, midY, left + stripWidth, midY, if (diagram) hairlinePaint else lanePaint)
        drawChevron(canvas, cx, h / 4f, pointRight = true)       // top = next
        drawChevron(canvas, cx, h * 3f / 4f, pointRight = false) // bottom = prev
        if (diagram) {
            val gap = chevronHalfH + labelPaint.textSize + ReaderTheme.dp(context, 4f)
            canvas.drawText("Next", cx, h / 4f + gap, labelPaint)
            canvas.drawText("Prev", cx, h * 3f / 4f + gap, labelPaint)
        }
    }

    /** A few faint lines in the page body to symbolise text (diagram only). */
    private fun drawTextLines(canvas: Canvas, w: Float, h: Float) {
        val sidePad = ReaderTheme.dp(context, 22f)
        val left = stripWidth + sidePad
        val right = w - stripWidth - sidePad
        if (right <= left) return
        val count = 5
        val gap = ReaderTheme.dp(context, 16f)
        var y = h / 2f - gap * (count - 1) / 2f
        for (i in 0 until count) {
            // Ragged last line, like the end of a paragraph.
            val lineRight = if (i == count - 1) left + (right - left) * 0.55f else right
            canvas.drawLine(left, y, lineRight, y, textLinePaint)
            y += gap
        }
    }

    private fun drawChevron(canvas: Canvas, cx: Float, cy: Float, pointRight: Boolean) {
        val w = chevronHalfW
        val h = chevronHalfH
        path.rewind()
        if (pointRight) {
            path.moveTo(cx - w, cy - h); path.lineTo(cx + w, cy); path.lineTo(cx - w, cy + h)
        } else {
            path.moveTo(cx + w, cy - h); path.lineTo(cx - w, cy); path.lineTo(cx + w, cy + h)
        }
        canvas.drawPath(path, chevronPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (onNext == null && onPrev == null) return false // diagram: non-interactive
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val inStrip = event.x < stripWidth || event.x > width - stripWidth
                if (!inStrip) return false // let center taps/scrolls fall through
                downX = event.x
                downY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (dx * dx + dy * dy > tapSlop * tapSlop) return true // a swipe — host handles it
                if (event.y < height / 2f) onNext?.invoke() else onPrev?.invoke()
                return true
            }
        }
        return false
    }

    companion object {
        /** Strip width, matching [ReaderView]'s `NAV_STRIP_DP`. */
        const val NAV_STRIP_DP = 80f
    }
}

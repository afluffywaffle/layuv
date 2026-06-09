package com.afluffywaffle.layuv.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint
import android.view.View
import com.afluffywaffle.layuv.docx.model.AnnotationTool

/**
 * A custom [View] that draws a single annotation tool icon using [Canvas].
 * Icons mirror the Flutter app's custom-painted toolbar shapes:
 *   highlight     — filled rounded square (marker box)
 *   underline     — "U" arc + single dotted underline
 *   doubleUnder   — "U" arc + double dotted underline
 *   strikethrough — "S" curve + horizontal strike
 *   bookmark      — tab outline with a V-notch at the bottom
 *   comment       — rounded-rectangle speech bubble + small tail
 * All other tools fall back to a plain text label.
 *
 * Size: the view should be given a fixed square layout (e.g. 64dp × 64dp).
 * Ink colour is [ReaderTheme.INK]; background is transparent.
 */
class ToolIconView(context: Context, val tool: AnnotationTool) : View(context) {

    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ReaderTheme.INK
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ReaderTheme.INK
        style = Paint.Style.FILL
    }
    private val textPaint: TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).also { tp ->
        tp.color = ReaderTheme.INK
        tp.typeface = ReaderTheme.chrome(context)
    }
    private val path = Path()
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val sz = minOf(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        // Icon occupies ~40 % of the button square, centred.
        val r = sz * 0.20f
        ink.strokeWidth = sz * 0.055f

        when (tool) {
            AnnotationTool.highlight -> drawHighlight(canvas, cx, cy, r)
            AnnotationTool.underline -> drawUnderline(canvas, cx, cy, r, double = false)
            AnnotationTool.doubleUnderline -> drawUnderline(canvas, cx, cy, r, double = true)
            AnnotationTool.strikethrough -> drawStrikethrough(canvas, cx, cy, r)
            AnnotationTool.bookmark -> drawBookmark(canvas, cx, cy, r)
            AnnotationTool.comment -> drawComment(canvas, cx, cy, r)
            else -> drawFallbackText(canvas, cx, cy, sz)
        }
    }

    // Filled rounded square — like a marker/highlighter swatch.
    private fun drawHighlight(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        rect.set(cx - r, cy - r * 0.85f, cx + r, cy + r * 0.85f)
        fill.alpha = 220
        canvas.drawRoundRect(rect, r * 0.25f, r * 0.25f, fill)
        fill.alpha = 255
    }

    // "U" arc shape above a dotted underline (or double).
    private fun drawUnderline(canvas: Canvas, cx: Float, cy: Float, r: Float, double: Boolean) {
        // U arc: two vertical strokes + bottom arc
        val top = cy - r * 0.8f
        val arcBot = cy + r * 0.2f
        path.rewind()
        path.moveTo(cx - r * 0.65f, top)
        path.lineTo(cx - r * 0.65f, arcBot)
        path.arcTo(
            RectF(cx - r * 0.65f, arcBot - r * 0.55f, cx + r * 0.65f, arcBot + r * 0.55f),
            180f, -180f,
        )
        path.lineTo(cx + r * 0.65f, top)
        canvas.drawPath(path, ink)

        val dotted = Paint(ink).apply {
            pathEffect = DashPathEffect(floatArrayOf(ink.strokeWidth * 1.4f, ink.strokeWidth * 1.4f), 0f)
        }
        val lineY1 = cy + r * 1.05f
        path.rewind(); path.moveTo(cx - r, lineY1); path.lineTo(cx + r, lineY1)
        canvas.drawPath(path, dotted)
        if (double) {
            val lineY2 = lineY1 + ink.strokeWidth * 3f
            path.rewind(); path.moveTo(cx - r, lineY2); path.lineTo(cx + r, lineY2)
            canvas.drawPath(path, dotted)
        }
    }

    // "S"-ish curve with a horizontal dotted strike through its middle.
    private fun drawStrikethrough(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        // Two opposing arcs that together suggest an "S"
        path.rewind()
        path.addArc(RectF(cx - r * 0.65f, cy - r * 0.85f, cx + r * 0.15f, cy - r * 0.05f), 0f, -200f)
        path.addArc(RectF(cx - r * 0.15f, cy + r * 0.05f, cx + r * 0.65f, cy + r * 0.85f), 180f, -200f)
        canvas.drawPath(path, ink)

        val dotted = Paint(ink).apply {
            pathEffect = DashPathEffect(floatArrayOf(ink.strokeWidth * 1.4f, ink.strokeWidth * 1.4f), 0f)
        }
        path.rewind(); path.moveTo(cx - r, cy); path.lineTo(cx + r, cy)
        canvas.drawPath(path, dotted)
    }

    // Bookmark tab outline: rectangle open at top, V-notch cut into the bottom.
    private fun drawBookmark(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val left = cx - r * 0.65f
        val right = cx + r * 0.65f
        val top = cy - r * 0.95f
        val bot = cy + r * 0.95f
        val notchDepth = r * 0.45f
        path.rewind()
        path.moveTo(left, top)
        path.lineTo(right, top)
        path.lineTo(right, bot)
        path.lineTo(cx, bot - notchDepth)
        path.lineTo(left, bot)
        path.close()
        canvas.drawPath(path, ink)
    }

    // Speech-bubble outline: rounded rect + small downward tail at bottom-left.
    private fun drawComment(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val left = cx - r
        val right = cx + r
        val top = cy - r * 0.75f
        val bot = cy + r * 0.45f
        val cr = r * 0.3f
        rect.set(left, top, right, bot)
        path.rewind()
        path.addRoundRect(rect, cr, cr, Path.Direction.CW)
        canvas.drawPath(path, ink)

        // Tail: small downward triangle below bottom-left of the bubble.
        path.rewind()
        path.moveTo(cx - r * 0.5f, bot)
        path.lineTo(cx - r * 0.7f, bot + r * 0.55f)
        path.lineTo(cx - r * 0.15f, bot)
        path.close()
        canvas.drawPath(path, ink)
    }

    private fun drawFallbackText(canvas: Canvas, cx: Float, cy: Float, sz: Float) {
        textPaint.textSize = sz * 0.28f
        val label = tool.name.take(2)
        val tw = textPaint.measureText(label)
        val fm = textPaint.fontMetrics
        canvas.drawText(label, cx - tw / 2f, cy - (fm.ascent + fm.descent) / 2f, textPaint)
    }
}

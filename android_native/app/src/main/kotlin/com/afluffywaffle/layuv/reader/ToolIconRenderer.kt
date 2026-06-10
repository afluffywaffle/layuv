package com.afluffywaffle.layuv.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint
import com.afluffywaffle.layuv.docx.model.AnnotationTool

/**
 * Draws a single annotation tool icon onto an arbitrary [Canvas] at a given
 * centre and square [size]. Shared by [ToolIconView] (the 64dp popup buttons)
 * and the reader's margin indicators (small per-annotation glyphs). Shapes mirror
 * the Flutter app's custom-painted toolbar icons.
 */
class ToolIconRenderer(context: Context) {

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
    private val light = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = (0xFFCCCCCC).toInt()   // light grey for de-emphasised text lines
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).also {
        it.color = ReaderTheme.INK
        it.typeface = ReaderTheme.chrome(context)
    }
    private val path = Path()
    private val rect = RectF()

    /** Draw [tool] centred at ([cx],[cy]); [size] is the icon's square extent. */
    fun draw(canvas: Canvas, tool: AnnotationTool, cx: Float, cy: Float, size: Float) {
        val r = size * 0.20f
        ink.strokeWidth = size * 0.055f
        light.strokeWidth = size * 0.055f
        when (tool) {
            AnnotationTool.highlight -> drawHighlight(canvas, cx, cy, r)
            AnnotationTool.underline -> drawUnderline(canvas, cx, cy, r, double = false)
            AnnotationTool.doubleUnderline -> drawUnderline(canvas, cx, cy, r, double = true)
            AnnotationTool.strikethrough -> drawStrikethrough(canvas, cx, cy, r)
            AnnotationTool.bookmark -> drawBookmark(canvas, cx, cy, r)
            AnnotationTool.comment -> drawComment(canvas, cx, cy, r)
            else -> drawFallbackText(canvas, cx, cy, size, tool)
        }
    }

    private fun drawHighlight(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        // [ light-grey text lines ] — brackets mark the selection boundary;
        // lightened lines inside read as "text that has been highlighted".
        val lineW = r * 1.8f
        val bW    = r * 0.35f    // bracket arm (horizontal)
        val bH    = r * 1.25f    // bracket half-height
        val inset = bW * 1.3f    // gap between bracket stem and text line start
        val gap   = r * 0.40f    // vertical gap from centre to each text line

        // Lightened text lines (full-width and shorter "last line")
        canvas.drawLine(cx - lineW + inset, cy - gap, cx + lineW - inset,         cy - gap, light)
        canvas.drawLine(cx - lineW + inset, cy + gap, cx + lineW * 0.68f - inset, cy + gap, light)

        // Left bracket [
        canvas.drawLine(cx - lineW,       cy - bH, cx - lineW + bW, cy - bH, ink)
        canvas.drawLine(cx - lineW,       cy - bH, cx - lineW,      cy + bH, ink)
        canvas.drawLine(cx - lineW,       cy + bH, cx - lineW + bW, cy + bH, ink)

        // Right bracket ]
        canvas.drawLine(cx + lineW,       cy - bH, cx + lineW - bW, cy - bH, ink)
        canvas.drawLine(cx + lineW,       cy - bH, cx + lineW,      cy + bH, ink)
        canvas.drawLine(cx + lineW,       cy + bH, cx + lineW - bW, cy + bH, ink)
    }

    private fun drawUnderline(canvas: Canvas, cx: Float, cy: Float, r: Float, double: Boolean) {
        // U shape (letter body)
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
        // Solid underline(s)
        val lineY1 = cy + r * 1.05f
        canvas.drawLine(cx - r, lineY1, cx + r, lineY1, ink)
        if (double) {
            val lineY2 = lineY1 + ink.strokeWidth * 3f
            canvas.drawLine(cx - r, lineY2, cx + r, lineY2, ink)
        }
    }

    private fun drawStrikethrough(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        // "S" with a solid horizontal line through its midpoint — matches Word's icon
        textPaint.textSize = r * 2.5f
        val tw = textPaint.measureText("S")
        val fm = textPaint.fontMetrics
        canvas.drawText("S", cx - tw / 2f, cy - (fm.ascent + fm.descent) / 2f, textPaint)
        canvas.drawLine(cx - r * 1.1f, cy, cx + r * 1.1f, cy, ink)
    }

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

        path.rewind()
        path.moveTo(cx - r * 0.5f, bot)
        path.lineTo(cx - r * 0.7f, bot + r * 0.55f)
        path.lineTo(cx - r * 0.15f, bot)
        path.close()
        canvas.drawPath(path, ink)
    }

    private fun drawFallbackText(canvas: Canvas, cx: Float, cy: Float, size: Float, tool: AnnotationTool) {
        textPaint.textSize = size * 0.28f
        val label = tool.name.take(2)
        val tw = textPaint.measureText(label)
        val fm = textPaint.fontMetrics
        canvas.drawText(label, cx - tw / 2f, cy - (fm.ascent + fm.descent) / 2f, textPaint)
    }
}

package com.afluffywaffle.layuv.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.text.StaticLayout
import com.afluffywaffle.layuv.docx.ResolvedAnnotation
import com.afluffywaffle.layuv.docx.model.AnnotationTool

/**
 * Draws resolved annotation spans onto the reader canvas.
 *
 * - [AnnotationTool.highlight] / [AnnotationTool.comment]: no decoration here —
 *   text rendered in light grey via [android.text.style.ForegroundColorSpan]
 *   baked into the layout by [ReaderView.buildSpanned].
 * - [AnnotationTool.bookmark]: margin icon only, no text decoration.
 * - [AnnotationTool.underline] / [AnnotationTool.doubleUnderline]: solid line(s).
 * - [AnnotationTool.strikethrough]: solid midline.
 * - Active selection: dotted underline (the only remaining use of [DashPathEffect]).
 *
 * Uses [DashPathEffect] + [Canvas.drawPath] for dotted lines; a dashed
 * [Canvas.drawLine] does not render reliably on a software-layer canvas.
 * All coordinates are layout coordinates: call inside the same canvas translate
 * the column text was drawn with.
 */
class HighlightPainter(context: Context) {
    private val underlineOffset = ReaderTheme.dp(context, ReaderTheme.UNDERLINE_OFFSET_DP)
    private val doubleGap = ReaderTheme.dp(context, ReaderTheme.UNDERLINE_OFFSET_DP * 0.9f)

    // Highlight (and comment) marks stay DOTTED so they read as distinct from a
    // plain underline; underline / double / strikethrough are SOLID (matches the
    // Flutter app's e-ink styling).
    private val dotted = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ReaderTheme.INK
        strokeWidth = ReaderTheme.dp(context, ReaderTheme.UNDERLINE_STROKE_DP)
        pathEffect = DashPathEffect(
            floatArrayOf(
                ReaderTheme.dp(context, ReaderTheme.UNDERLINE_DASH_ON_DP),
                ReaderTheme.dp(context, ReaderTheme.UNDERLINE_DASH_OFF_DP),
            ),
            0f,
        )
    }

    private val solid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ReaderTheme.INK
        strokeWidth = ReaderTheme.dp(context, ReaderTheme.UNDERLINE_STROKE_DP)
    }

    private val path = Path()

    /**
     * Draw a dotted underline beneath the active selection [span] across lines
     * [startLine, endLineExclusive). Same visual as comment/bookmark marks, which
     * reads well on e-ink and avoids the full-refresh cost of a filled band.
     * Call inside the same canvas translate the column text was drawn with.
     */
    fun drawSelection(
        canvas: Canvas,
        layout: StaticLayout,
        startLine: Int,
        endLineExclusive: Int,
        span: com.afluffywaffle.layuv.docx.TextSpan,
    ) {
        if (endLineExclusive <= startLine) return
        val firstLine = layout.getLineForOffset(span.start).coerceIn(startLine, endLineExclusive - 1)
        val lastLine = layout.getLineForOffset((span.end - 1).coerceAtLeast(span.start)).coerceIn(startLine, endLineExclusive - 1)
        for (line in firstLine..lastLine) {
            val ls = maxOf(span.start, layout.getLineStart(line))
            val le = minOf(span.end, layout.getLineEnd(line))
            if (ls >= le) continue
            var xStart = layout.getPrimaryHorizontal(ls)
            var xEnd = layout.getPrimaryHorizontal(le)
            if (xEnd <= xStart) xEnd = layout.getLineRight(line)
            if (xEnd < xStart) { val t = xStart; xStart = xEnd; xEnd = t }
            line(canvas, xStart, xEnd, layout.getLineBaseline(line).toFloat() + underlineOffset, dotted)
        }
    }

    /**
     * Decorate a single [span] (preview) intersecting lines
     * [startLine, endLineExclusive) with the dotted annotation decoration.
     */
    fun drawSpan(
        canvas: Canvas,
        layout: StaticLayout,
        startLine: Int,
        endLineExclusive: Int,
        span: com.afluffywaffle.layuv.docx.TextSpan,
        tool: AnnotationTool,
    ) {
        if (endLineExclusive <= startLine) return
        val firstLine = layout.getLineForOffset(span.start).coerceIn(startLine, endLineExclusive - 1)
        val lastLine = layout.getLineForOffset((span.end - 1).coerceAtLeast(span.start)).coerceIn(startLine, endLineExclusive - 1)
        for (line in firstLine..lastLine) {
            val ls = maxOf(span.start, layout.getLineStart(line))
            val le = minOf(span.end, layout.getLineEnd(line))
            if (ls >= le) continue
            drawDecoration(canvas, layout, line, ls, le, tool)
        }
    }

    /** Decorate every annotation span that intersects lines [startLine, endLineExclusive). */
    fun drawColumn(
        canvas: Canvas,
        layout: StaticLayout,
        startLine: Int,
        endLineExclusive: Int,
        annotations: List<ResolvedAnnotation>,
    ) {
        if (endLineExclusive <= startLine) return
        val colStartChar = layout.getLineStart(startLine)
        val colEndChar = layout.getLineEnd(endLineExclusive - 1)

        for (resolved in annotations) {
            val span = resolved.span ?: continue
            val s = maxOf(span.start, colStartChar)
            val e = minOf(span.end, colEndChar)
            if (s >= e) continue

            val firstLine = layout.getLineForOffset(s)
            val lastLine = layout.getLineForOffset(e - 1)
            for (line in firstLine..lastLine) {
                val ls = maxOf(s, layout.getLineStart(line))
                val le = minOf(e, layout.getLineEnd(line))
                if (ls >= le) continue
                drawDecoration(canvas, layout, line, ls, le, resolved.annotation.tool)
            }
        }
    }

    private fun drawDecoration(
        canvas: Canvas,
        layout: StaticLayout,
        line: Int,
        startChar: Int,
        endChar: Int,
        tool: AnnotationTool,
    ) {
        var xStart = layout.getPrimaryHorizontal(startChar)
        var xEnd = layout.getPrimaryHorizontal(endChar)
        // getPrimaryHorizontal at a line-boundary/newline offset can collapse to
        // the line start; fall back to the line's right text edge in that case.
        if (xEnd <= xStart) xEnd = layout.getLineRight(line)
        if (xEnd < xStart) {
            val t = xStart; xStart = xEnd; xEnd = t
        }

        val baseline = layout.getLineBaseline(line).toFloat()
        when (tool) {
            AnnotationTool.strikethrough -> {
                val ascent = layout.paint.fontMetrics.ascent // negative
                line(canvas, xStart, xEnd, baseline + ascent * 0.35f, solid)
            }
            AnnotationTool.doubleUnderline -> {
                line(canvas, xStart, xEnd, baseline + underlineOffset, solid)
                line(canvas, xStart, xEnd, baseline + underlineOffset + doubleGap, solid)
            }
            AnnotationTool.underline -> line(canvas, xStart, xEnd, baseline + underlineOffset, solid)
            AnnotationTool.highlight,
            AnnotationTool.comment -> { /* grey text via ForegroundColorSpan in buildSpanned — no line */ }
            AnnotationTool.bookmark -> { /* margin icon only — no line decoration */ }
            else -> { /* unknown tool — no decoration */ }
        }
    }

    private fun line(canvas: Canvas, x0: Float, x1: Float, y: Float, paint: Paint) {
        path.rewind()
        path.moveTo(x0, y)
        path.lineTo(x1, y)
        canvas.drawPath(path, paint)
    }
}

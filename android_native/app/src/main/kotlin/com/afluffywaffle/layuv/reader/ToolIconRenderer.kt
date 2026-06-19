package com.afluffywaffle.layuv.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.util.SparseArray
import com.afluffywaffle.layuv.R
import com.afluffywaffle.layuv.docx.model.AnnotationTool

/**
 * Draws annotation tool icons onto an arbitrary [Canvas], matching the Flutter
 * app's `ToolIcon` widget glyph-for-glyph.
 *
 * The [size] passed to [draw] is the ICON EXTENT — the same value Flutter passes
 * as `ToolIcon(size:)` (e.g. 28dp on e-ink). The glyph is drawn to FILL a
 * [size]×[size] box centred at (cx,cy); it is NOT shrunk inside a larger button
 * cell. Callers centre that box inside their button/margin slot.
 *
 * Shapes mirror Flutter exactly: a 1:0.7 grey rectangle with a thin black38
 * border (highlight); bold Literata "U"/"S"/"W" + hairline rules (underline /
 * double / strike / wavy); Material vector drawables tinted black87 for bookmark,
 * comment and ink. Shared by [ToolIconView] (popup buttons) and the reader's
 * margin indicators.
 */
class ToolIconRenderer(private val context: Context) {

    private fun px(dp: Float): Float = ReaderTheme.dp(context, dp)

    // Underline / strike / wavy rules (Flutter black87).
    private val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ReaderTheme.INK_87
        style = Paint.Style.FILL
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ReaderTheme.INK_87
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ReaderTheme.INK_87
        style = Paint.Style.FILL
    }
    // 15%-black highlight swatch fill (e-ink) — never yellow.
    private val hlFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ReaderTheme.HL_FILL
        style = Paint.Style.FILL
    }
    // Thin black38 border around the highlight swatch (Flutter width 0.5).
    private val hlBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ReaderTheme.INK_38
        style = Paint.Style.STROKE
    }
    // Paper-coloured fill for the lock-badge circle background.
    private val paperFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ReaderTheme.PAPER
        style = Paint.Style.FILL
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).also {
        it.color = ReaderTheme.INK_87
        it.typeface = ReaderTheme.body(context)
        it.isFakeBoldText = true
    }
    private val rect = RectF()
    private val wavePath = Path()
    private val drawableCache = SparseArray<Drawable>()

    /** Draw [tool] to fill a [size]×[size] box centred at ([cx],[cy]). */
    fun draw(canvas: Canvas, tool: AnnotationTool, cx: Float, cy: Float, size: Float) {
        when (tool) {
            AnnotationTool.highlight       -> drawHighlight(canvas, cx, cy, size)
            AnnotationTool.underline       -> drawUnderline(canvas, cx, cy, size, double = false)
            AnnotationTool.doubleUnderline -> drawUnderline(canvas, cx, cy, size, double = true)
            AnnotationTool.strikethrough   -> drawStrikethrough(canvas, cx, cy, size)
            AnnotationTool.wavyUnderline   -> drawWavy(canvas, cx, cy, size)
            AnnotationTool.bookmark        -> drawVecIcon(canvas, R.drawable.ic_bookmark_outline, cx, cy, size)
            AnnotationTool.comment         -> drawVecIcon(canvas, R.drawable.ic_chat_outline, cx, cy, size)
            AnnotationTool.inkAnnotation   -> drawVecIcon(canvas, R.drawable.ic_edit_outline, cx, cy, size)
        }
    }

    // --- Tool icons (Flutter-matching) ----------------------------------------

    /** Grey rectangle (1:0.7) with a thin black38 border — Flutter e-ink highlight. */
    private fun drawHighlight(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val halfW = size / 2f
        val halfH = size * 0.35f // height = size * 0.7
        rect.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
        canvas.drawRect(rect, hlFill)
        hlBorder.strokeWidth = px(0.6f)
        canvas.drawRect(rect, hlBorder)
    }

    /**
     * Bold Literata "U" + one or two hairline rules at the box bottom — matches
     * Flutter's underline / doubleUnderline icons (bar height 1, inset 2, bottom 1/3).
     */
    private fun drawUnderline(canvas: Canvas, cx: Float, cy: Float, size: Float, double: Boolean) {
        drawGlyph(canvas, "U", cx, cy, size)
        val boxBottom = cy + size / 2f
        val left = cx - size / 2f + px(2f)
        val right = cx + size / 2f - px(2f)
        val t = px(1f)
        bar(canvas, left, right, boxBottom - px(1f), t)
        if (double) bar(canvas, left, right, boxBottom - px(3f), t)
    }

    /** Draw a filled rule whose bottom edge is at [bottomY], [thickness] tall. */
    private fun bar(canvas: Canvas, left: Float, right: Float, bottomY: Float, thickness: Float) {
        rect.set(left, bottomY - thickness, right, bottomY)
        canvas.drawRect(rect, rule)
    }

    /** Bold Literata "S" with a font-intrinsic strike line — Flutter strikethrough. */
    private fun drawStrikethrough(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        textPaint.isStrikeThruText = true
        drawGlyph(canvas, "S", cx, cy, size)
        textPaint.isStrikeThruText = false
    }

    /** Bold Literata "W" + a wavy rule — Flutter wavyUnderline (dormant tool). */
    private fun drawWavy(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        drawGlyph(canvas, "W", cx, cy, size)
        val left = cx - size / 2f + px(2f)
        val right = cx + size / 2f - px(2f)
        val y = cy + size / 2f - px(2f)
        val amp = px(1.2f)
        wavePaint.strokeWidth = px(1f)
        wavePath.reset()
        wavePath.moveTo(left, y)
        var x = left
        val step = (right - left) / 6f
        var up = true
        while (x < right) {
            val nx = (x + step).coerceAtMost(right)
            wavePath.quadTo((x + nx) / 2f, if (up) y - amp else y + amp, nx, y)
            x = nx; up = !up
        }
        canvas.drawPath(wavePath, wavePaint)
    }

    /** Bold Literata glyph centred in the box at Flutter's fontSize = size * 0.875. */
    private fun drawGlyph(canvas: Canvas, ch: String, cx: Float, cy: Float, size: Float) {
        textPaint.textSize = size * 0.875f
        val tw = textPaint.measureText(ch)
        val fm = textPaint.fontMetrics
        canvas.drawText(ch, cx - tw / 2f, cy - (fm.ascent + fm.descent) / 2f, textPaint)
    }

    // --- Material vector icon helpers -----------------------------------------

    /**
     * Draw a Material vector drawable centred at ([cx],[cy]) filling a [size] px
     * square. Drawables are cached per resource ID and tinted black87 to match
     * Flutter's `Icon(color: Colors.black87)`.
     */
    fun drawVecIcon(canvas: Canvas, resId: Int, cx: Float, cy: Float, size: Float) {
        val d = drawableCache[resId] ?: run {
            val new = context.getDrawable(resId)!!.mutate().also { it.setTint(ReaderTheme.INK_87) }
            drawableCache.put(resId, new)
            new
        }
        val half = (size / 2f).toInt()
        d.setBounds((cx - half).toInt(), (cy - half).toInt(),
                    (cx + half).toInt(), (cy + half).toInt())
        d.draw(canvas)
    }

    /** Lock icon — draws the Material ic_lock drawable at [size]. */
    fun drawPadlock(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        drawVecIcon(canvas, R.drawable.ic_lock, cx, cy, size)
    }

    /**
     * Paper-coloured circle badge with the Material lock icon inside — matches
     * Flutter's ToolButton lock overlay. Draw on top of a tool icon.
     * [cx],[cy] = badge centre; [badgeR] = badge radius.
     */
    fun drawLockBadge(canvas: Canvas, cx: Float, cy: Float, badgeR: Float) {
        canvas.drawCircle(cx, cy, badgeR, paperFill)
        drawVecIcon(canvas, R.drawable.ic_lock, cx, cy, badgeR * 1.55f)
    }

    /**
     * Combo margin icon for annotations that have BOTH a written note and ink.
     * Draws the chat-bubble at full [size], then overlays the pencil icon at 55%
     * in the bottom-right corner so both are recognisable at a glance.
     */
    fun drawComboNoteInk(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        // Shift the chat bubble slightly up-left to leave room for the badge.
        val offset = size * 0.15f
        drawVecIcon(canvas, R.drawable.ic_chat_outline, cx - offset, cy - offset, size)
        val badgeSize = size * 0.55f
        val badgeCx = cx + size * 0.3f
        val badgeCy = cy + size * 0.3f
        // Small paper circle behind badge so it doesn't bleed into the chat icon.
        canvas.drawCircle(badgeCx, badgeCy, badgeSize * 0.56f, paperFill)
        drawVecIcon(canvas, R.drawable.ic_edit_outline, badgeCx, badgeCy, badgeSize)
    }

    /** Three-dot overflow icon (Icons.more_horiz): three filled circles in a row. */
    fun drawOverflow(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val dotR = size * 0.085f
        val spacing = size * 0.26f
        canvas.drawCircle(cx - spacing, cy, dotR, fill)
        canvas.drawCircle(cx,           cy, dotR, fill)
        canvas.drawCircle(cx + spacing, cy, dotR, fill)
    }
}

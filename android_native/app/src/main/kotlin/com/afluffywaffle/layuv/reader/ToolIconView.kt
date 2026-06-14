package com.afluffywaffle.layuv.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import com.afluffywaffle.layuv.docx.model.AnnotationTool

/**
 * A custom [View] that draws a single annotation tool icon (the shapes live in
 * [ToolIconRenderer], shared with the reader's margin indicators). Give it a
 * fixed square layout (e.g. 64dp × 64dp); background is transparent. The icon is
 * drawn at a fixed 28dp extent centred in the view — matching Flutter's
 * `ToolIcon(size: 28)` centred in a 64dp `ToolButton`.
 *
 * [locked] = true: draws the tool icon + a small paper-coloured circle badge with
 * the Material lock icon in the bottom-right corner — matches Flutter's locked
 * ToolButton overlay.
 * [showLockHint] = true: draws the Photoshop-style filled corner triangle in the
 * bottom-right (signals long-press reveals the apply-once / lock picker). Only
 * drawn when [locked] is false. The caller sets this only on lockable tools
 * (every tool except comment / inkAnnotation), mirroring Flutter's `onLock != null`.
 */
class ToolIconView(context: Context, val tool: AnnotationTool) : View(context) {

    var locked: Boolean = false
        set(value) {
            if (field != value) { field = value; invalidate() }
        }

    var showLockHint: Boolean = false
        set(value) {
            if (field != value) { field = value; invalidate() }
        }

    private val renderer = ToolIconRenderer(context)
    private val iconSize = ReaderTheme.dp(context, ReaderTheme.ICON_DP)

    // Filled bottom-right corner triangle (Flutter _CornerHintPainter, black54).
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ReaderTheme.INK_54
        style = Paint.Style.FILL
    }
    private val hintPath = Path()

    override fun onDraw(canvas: Canvas) {
        renderer.draw(canvas, tool, width / 2f, height / 2f, iconSize)

        if (locked) {
            // Paper-coloured circle badge with Material lock icon — matches Flutter.
            val badgeR = ReaderTheme.dp(context, 7f)
            val inset = ReaderTheme.dp(context, 2f)
            renderer.drawLockBadge(canvas, width - badgeR - inset, height - badgeR - inset, badgeR)
        } else if (showLockHint) {
            // 6×6 filled right-triangle, inset 4dp from the bottom-right corner.
            val m = ReaderTheme.dp(context, 4f)
            val s = ReaderTheme.dp(context, 6f)
            val right = width - m
            val bottom = height - m
            val left = right - s
            val top = bottom - s
            hintPath.reset()
            hintPath.moveTo(right, top)
            hintPath.lineTo(right, bottom)
            hintPath.lineTo(left, bottom)
            hintPath.close()
            canvas.drawPath(hintPath, hintPaint)
        }
    }
}

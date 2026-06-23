package com.afluffywaffle.layuv.reader

import android.content.Context
import android.graphics.Canvas
import android.view.View

/**
 * A faint, finely dotted horizontal hairline, matching the edge-nav rails (see
 * [ReaderTheme.dottedLinePaint]). Used to anchor chrome that would otherwise float —
 * e.g. the reader's bottom bar, which has no solid border. Give it a small fixed
 * height in the layout (a couple of dp); the line is drawn at its vertical centre.
 *
 * Software-layered so the dash renders crisply on e-ink.
 */
class DottedDivider(context: Context) : View(context) {

    private val paint = ReaderTheme.dottedLinePaint(context)

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        val y = height / 2f
        canvas.drawLine(0f, y, width.toFloat(), y, paint)
    }
}

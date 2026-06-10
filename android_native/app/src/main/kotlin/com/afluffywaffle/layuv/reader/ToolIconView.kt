package com.afluffywaffle.layuv.reader

import android.content.Context
import android.graphics.Canvas
import android.view.View
import com.afluffywaffle.layuv.docx.model.AnnotationTool

/**
 * A custom [View] that draws a single annotation tool icon (the shapes live in
 * [ToolIconRenderer], shared with the reader's margin indicators). Give it a
 * fixed square layout (e.g. 64dp × 64dp); background is transparent.
 */
class ToolIconView(context: Context, val tool: AnnotationTool) : View(context) {

    private val renderer = ToolIconRenderer(context)

    override fun onDraw(canvas: Canvas) {
        val sz = minOf(width, height).toFloat()
        renderer.draw(canvas, tool, width / 2f, height / 2f, sz)
    }
}

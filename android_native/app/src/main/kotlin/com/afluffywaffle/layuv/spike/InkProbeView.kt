package com.afluffywaffle.layuv.spike

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.Log
import android.view.MotionEvent
import android.view.View

/**
 * SPIKE probe — answers the one question that decides the inking architecture:
 * does THIS app still receive stylus MotionEvents while drawPath is rendering
 * its low-latency overlay?
 *
 * It logs every DOWN/UP (with tool type + pressure) and a sampled MOVE count to
 * logcat (tag DrawPathSpike), and ALSO renders the captured stroke itself into a
 * retained bitmap-less Path list. If app-side strokes appear UNDER drawPath's
 * overlay, that proves we own the geometry and can persist/redraw/clear it.
 */
class InkProbeView(context: Context) : View(context) {

    private val committed = ArrayList<Path>()
    private var current: Path? = null
    private var lastX = 0f
    private var lastY = 0f
    private var moveCount = 0

    private val paint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = false
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /** True = app renders its own captured strokes too (to compare with drawPath). */
    var appSideRender = false

    fun clearAppStrokes() {
        committed.clear(); current = null; invalidate()
    }

    private fun toolName(e: MotionEvent): String = when (e.getToolType(0)) {
        MotionEvent.TOOL_TYPE_STYLUS -> "STYLUS"
        MotionEvent.TOOL_TYPE_FINGER -> "FINGER"
        MotionEvent.TOOL_TYPE_ERASER -> "ERASER"
        else -> "tool#${e.getToolType(0)}"
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                moveCount = 0
                lastX = event.x; lastY = event.y
                current = Path().apply { moveTo(lastX, lastY) }
                Log.i(DrawPathClient.TAG, "TOUCH DOWN ${toolName(event)} x=${event.x.toInt()} y=${event.y.toInt()} p=${"%.2f".format(event.pressure)}")
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.historySize) {
                    val hx = event.getHistoricalX(i); val hy = event.getHistoricalY(i)
                    current?.quadTo(lastX, lastY, (hx + lastX) / 2, (hy + lastY) / 2)
                    lastX = hx; lastY = hy; moveCount++
                }
                current?.quadTo(lastX, lastY, (event.x + lastX) / 2, (event.y + lastY) / 2)
                lastX = event.x; lastY = event.y; moveCount++
                if (appSideRender) invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                current?.let { committed.add(it) }
                current = null
                Log.i(DrawPathClient.TAG, "TOUCH UP   ${toolName(event)} points(incl history)=$moveCount strokes=${committed.size}")
                if (appSideRender) invalidate()
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        if (!appSideRender) return
        for (p in committed) canvas.drawPath(p, paint)
        current?.let { canvas.drawPath(it, paint) }
    }
}

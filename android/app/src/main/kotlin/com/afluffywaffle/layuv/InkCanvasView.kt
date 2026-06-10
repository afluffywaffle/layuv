package com.afluffywaffle.layuv

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode
import java.io.ByteArrayOutputStream

class InkCanvasView(context: Context) : View(context) {
    // Set by InkActivity once the working EPD mode is determined.
    var epdMode: UpdateMode? = null

    private var bitmap: Bitmap? = null
    private var bitmapCanvas: Canvas? = null

    private val strokePaint = Paint().apply {
        color = Color.BLACK
        strokeWidth = 5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = false  // e-ink is greyscale — antialiasing is wasted work
    }

    private val currentPath = Path()
    private var lastX = 0f
    private var lastY = 0f
    private var moveCount = 0

    // Dirty rect for the current stroke segment — used for partial EPD refresh.
    private var dirtyLeft = 0f
    private var dirtyTop = 0f
    private var dirtyRight = 0f
    private var dirtyBottom = 0f
    private val strokePadding = 8f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            val newBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val newCanvas = Canvas(newBitmap)
            newCanvas.drawColor(Color.WHITE)
            bitmap?.let { old ->
                newCanvas.drawBitmap(old, 0f, 0f, null)
                old.recycle()
            }
            bitmap = newBitmap
            bitmapCanvas = newCanvas
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val bc = bitmapCanvas ?: return false

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.x
                val y = event.y
                currentPath.reset()
                currentPath.moveTo(x, y)
                lastX = x
                lastY = y
                moveCount = 0
                resetDirty(x, y)
            }
            MotionEvent.ACTION_MOVE -> {
                // Consume historical batched points first so the stroke stays
                // close to the pen tip even when Android coalesces events.
                for (i in 0 until event.historySize) {
                    val hx = event.getHistoricalX(i)
                    val hy = event.getHistoricalY(i)
                    currentPath.quadTo(lastX, lastY, (hx + lastX) / 2, (hy + lastY) / 2)
                    expandDirty(lastX, lastY, hx, hy)
                    lastX = hx
                    lastY = hy
                    moveCount++
                }
                val x = event.x
                val y = event.y
                currentPath.quadTo(lastX, lastY, (x + lastX) / 2, (y + lastY) / 2)
                expandDirty(lastX, lastY, x, y)
                lastX = x
                lastY = y
                moveCount++
                if (moveCount % 3 == 0) {
                    bc.drawPath(currentPath, strokePaint)
                    epdRefresh()
                    resetDirty(x, y)
                }
            }
            MotionEvent.ACTION_UP -> {
                val x = event.x
                val y = event.y
                currentPath.lineTo(x, y)
                expandDirty(lastX, lastY, x, y)
                bc.drawPath(currentPath, strokePaint)
                currentPath.reset()
                epdRefresh()
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        bitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        if (!currentPath.isEmpty) {
            canvas.drawPath(currentPath, strokePaint)
        }
    }

    private fun resetDirty(x: Float, y: Float) {
        dirtyLeft = x; dirtyTop = y; dirtyRight = x; dirtyBottom = y
    }

    private fun expandDirty(x1: Float, y1: Float, x2: Float, y2: Float) {
        dirtyLeft = minOf(dirtyLeft, x1, x2)
        dirtyTop = minOf(dirtyTop, y1, y2)
        dirtyRight = maxOf(dirtyRight, x1, x2)
        dirtyBottom = maxOf(dirtyBottom, y1, y2)
    }

    private fun epdRefresh() {
        val mode = epdMode
        if (mode != null) {
            try {
                val l = (dirtyLeft - strokePadding).toInt().coerceAtLeast(0)
                val t = (dirtyTop - strokePadding).toInt().coerceAtLeast(0)
                val r = (dirtyRight + strokePadding).toInt().coerceAtMost(width)
                val b = (dirtyBottom + strokePadding).toInt().coerceAtMost(height)
                EpdController.invalidate(this, l, t, r, b, mode)
                return
            } catch (_: Exception) {}
        }
        invalidate()
    }

    fun clear() {
        bitmapCanvas?.drawColor(Color.WHITE)
        currentPath.reset()
        dirtyLeft = 0f; dirtyTop = 0f
        dirtyRight = width.toFloat(); dirtyBottom = height.toFloat()
        epdRefresh()
    }

    fun toPngBytes(): ByteArray? {
        val bm = bitmap ?: return null
        val out = ByteArrayOutputStream()
        return if (bm.compress(Bitmap.CompressFormat.PNG, 100, out)) out.toByteArray() else null
    }
}

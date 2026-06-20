package com.afluffywaffle.layuv.reader

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * An [View.OnTouchListener] that fires [onTap] reliably under the Supernote e-ink
 * pen layer. That layer holds the stylus pen-UP over UI, so a plain
 * `OnClickListener` may not resolve until the next touch arrives — a pen tap on a
 * button appears to do nothing until you tap again.
 *
 * Direct port of the Flutter `PenTappable`: for a stylus it commits on a brief
 * dwell after pen-down (90ms) OR on pen-up, whichever comes first; movement past a
 * 14dp slop cancels it (a drag/scroll, not a tap). Finger / non-stylus input fires
 * a normal tap-on-release. A new pointer supersedes any still-pending gesture, so
 * a tap is never blocked by a held pen-up. No ripple — e-ink shows none anyway.
 */
class PenTapListener(
    context: Context,
    private val tag: String = "PenTapListener",
    private val onDrag: (() -> Unit)? = null,
    private val onTap: () -> Unit,
) : View.OnTouchListener {

    private val dwellMs = ReaderTheme.PEN_DWELL_MS
    private val slopPx = ReaderTheme.dp(context, ReaderTheme.PEN_SLOP_DP)
    private val handler = Handler(Looper.getMainLooper())

    private var ptrId = -1
    private var fired = false
    private var moved = false
    private var downX = 0f
    private var downY = 0f
    private var dwell: Runnable? = null

    override fun onTouch(v: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelDwell()
                ptrId = e.getPointerId(0)
                fired = false
                moved = false
                downX = e.x
                downY = e.y
                val tool = e.getToolType(0)
                val toolName = when (tool) {
                    MotionEvent.TOOL_TYPE_FINGER  -> "FINGER"
                    MotionEvent.TOOL_TYPE_STYLUS  -> "STYLUS"
                    MotionEvent.TOOL_TYPE_ERASER  -> "ERASER"
                    MotionEvent.TOOL_TYPE_MOUSE   -> "MOUSE"
                    else -> "UNKNOWN($tool)"
                }
                val stylus = tool == MotionEvent.TOOL_TYPE_STYLUS ||
                    tool == MotionEvent.TOOL_TYPE_ERASER
                Log.d(tag, "DOWN tool=$toolName x=${e.x.toInt()} y=${e.y.toInt()} view=${v.javaClass.simpleName} stylus=$stylus dwellMs=$dwellMs")
                if (stylus) {
                    val r = Runnable {
                        Log.d(tag, "DWELL fired moved=$moved fired=$fired")
                        if (!moved && !fired) fire()
                    }
                    dwell = r
                    handler.postDelayed(r, dwellMs)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moved && hypot(e.x - downX, e.y - downY) > slopPx) {
                    Log.d(tag, "MOVE exceeded slop — cancelling tap")
                    moved = true
                    cancelDwell()
                    onDrag?.invoke()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                Log.d(tag, "UP fired=$fired moved=$moved")
                if (!fired && !moved) fire()
                reset()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                Log.d(tag, "CANCEL")
                reset()
                return true
            }
        }
        return false
    }

    private fun fire() {
        if (fired) return
        fired = true
        cancelDwell()
        Log.d(tag, "fire → onTap()")
        onTap()
    }

    private fun cancelDwell() {
        dwell?.let { handler.removeCallbacks(it) }
        dwell = null
    }

    private fun reset() {
        cancelDwell()
        ptrId = -1
        moved = false
        fired = false
    }
}

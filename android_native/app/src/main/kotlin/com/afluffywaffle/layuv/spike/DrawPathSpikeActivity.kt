package com.afluffywaffle.layuv.spike

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * SPIKE — interactive harness for the Supernote drawPath low-latency
 * handwriting service.
 *
 * Launch directly (it is NOT in the launcher):
 *   adb shell am start -n com.afluffywaffle.layuv.dev/com.afluffywaffle.layuv.spike.DrawPathSpikeActivity
 *
 * Hidden-API reflection on ServiceManager is required; if `getService` is
 * blocked, run once:  adb shell settings put global hidden_api_policy 0
 *
 * "Full init" runs the documented sequence (reset → pen → disable header), then
 * draw on the white area with the STYLUS — drawPath renders the strokes itself
 * (this app has no onDraw for ink). The individual buttons isolate each call so
 * the disable-area behaviour can be tuned by hand. Every result is shown in the
 * status box and logged to logcat (tag DrawPathSpike); the device's own
 * `drawAPP` logcat echoes the live penType/penWidth and disable size.
 */
class DrawPathSpikeActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var canvas: InkProbeView
    private var headerHeightPx = 0

    private val pkg: String get() = packageName

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        status = TextView(this).apply {
            setTextColor(Color.BLACK)
            textSize = 12f
            setPadding(20, 16, 20, 16)
            text = "ready — tap Full init, then draw with the stylus"
        }

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(btn("Full init") { fullInit() })
        row1.addView(btn("Reset") { log(DrawPathClient.sendReset(pkg)) })
        row1.addView(btn("Pen") { log(DrawPathClient.sendPen(pkg, 10, 200, 0)) })

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        // Test which flag value actually disables the strip. Draw on the header
        // after tapping each, then check drawAPP's "disablse size"/"intersectDisable".
        row2.addView(btn("Disable f0") { log(disableHeader(0)) })
        row2.addView(btn("Disable f1") { log(disableHeader(1)) })
        lateinit var renderBtn: Button
        renderBtn = btn("App render: off") {
            canvas.appSideRender = !canvas.appSideRender
            renderBtn.text = "App render: ${if (canvas.appSideRender) "ON" else "off"}"
            canvas.invalidate()
            log("app-side rendering = ${canvas.appSideRender}")
        }
        row2.addView(renderBtn)

        val row3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row3.addView(btn("Clear (code 6)") {
            // Real drawPath clearScreen — flushes ITS retained buffer (full screen),
            // plus our own app-side strokes.
            val r = DrawPathClient.clearScreen(pkg)
            canvas.clearAppStrokes()
            log("clearScreen → $r")
        })

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F0F0F0"))
            addView(ScrollView(this@DrawPathSpikeActivity).apply { addView(status) },
                LinearLayout.LayoutParams(MATCH_PARENT, dp(120f)))
            addView(row1, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(row2, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(row3, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }

        canvas = InkProbeView(this)

        root.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(canvas, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)

        header.post { headerHeightPx = header.height }
    }

    override fun onResume() {
        super.onResume()
        // Re-run the init each resume — drawPath state is per-foreground-app.
        canvas.post { fullInit() }
    }

    private fun fullInit() {
        if (!DrawPathClient.available()) {
            setStatus("FAIL: service_myservice unreachable.\n" +
                    "Run: adb shell settings put global hidden_api_policy 0")
            return
        }
        val out = buildString {
            append("pkg=").append(pkg).append('\n')
            append(DrawPathClient.sendReset(pkg)).append('\n')   // 1. mandatory reset
            append(DrawPathClient.sendPen(pkg, 10, 200, 0)).append('\n') // 2. pen
            // 3. disable header. flag 0 = non-writable/disable (CONFIRMED Nomad
            // 2026-06-09: ink suppressed in the rect, rest of screen stays
            // writable). flag 1 is a writable WHITELIST that would block the canvas.
            append(disableHeader(0)).append('\n')
            append("screen=").append(resources.displayMetrics.widthPixels)
            append("x").append(resources.displayMetrics.heightPixels).append('\n')
            append("→ draw below; try writing on the grey header too")
        }
        setStatus(out)
    }

    private fun disableHeader(flag: Int): String {
        val w = resources.displayMetrics.widthPixels
        val h = if (headerHeightPx > 0) headerHeightPx else dp(220f)
        return DrawPathClient.setWritableAreas(pkg, listOf(intArrayOf(0, 0, w, h, flag)), "disable-hdr-f$flag")
    }

    private fun setStatus(s: String) { status.text = s; Log.i(DrawPathClient.TAG, "STATUS:\n$s") }
    private fun log(line: String) { status.text = line }

    private fun btn(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        setTextColor(Color.BLACK)
        minHeight = dp(56f)
        layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        setOnClickListener { onClick() }
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()
}

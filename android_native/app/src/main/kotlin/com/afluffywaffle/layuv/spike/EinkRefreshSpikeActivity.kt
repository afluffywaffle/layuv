package com.afluffywaffle.layuv.spike

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * SPIKE — interactive harness for the Supernote native EPD refresh path
 * ([EinkClient] → android.os.EinkManager). Confirms which waveform modes
 * actually drive the panel so [com.afluffywaffle.layuv.reader.Epd] can drop the
 * Onyx EpdController (a Boox no-op on Ratta) for the real Supernote calls.
 *
 * Launch (NOT in launcher):
 *   adb shell am start -n com.afluffywaffle.layuv.dev/com.afluffywaffle.layuv.spike.EinkRefreshSpikeActivity
 *
 * Hidden-API reflection needs:  adb shell settings put global hidden_api_policy 0
 *
 * Usage: pick a mode (GC16 / GL16 / DU / A2), then tap "Toggle frame" a few
 * times. The view flips between two inverted high-contrast frames so the active
 * waveform is visible — GC16 flashes clean; DU/A2 are fast but accumulate
 * ghosting in the grey ramp + fine text. "screenRefresh" / "oneFullFrame" should
 * flush that ghosting with a clean full update.
 */
class EinkRefreshSpikeActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var testView: TestView
    private var mode = EinkClient.FULL_GC16

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        status = TextView(this).apply {
            setTextColor(Color.BLACK); textSize = 12f; setPadding(20, 16, 20, 16)
            text = "ready — eink available=${EinkClient.available(this@EinkRefreshSpikeActivity)}; pick a mode then Toggle frame"
        }

        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        modeRow.addView(btn("GC16") { selectMode(EinkClient.FULL_GC16, "GC16") })
        modeRow.addView(btn("GL16") { selectMode(EinkClient.PART_GL16, "GL16") })
        modeRow.addView(btn("DU") { selectMode(EinkClient.DU, "DU") })
        modeRow.addView(btn("A2") { selectMode(EinkClient.A2, "A2") })

        val actRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actRow.addView(btn("Toggle") {
            testView.toggle()
            log(EinkClient.applyMode(this, testView, mode))
        })
        actRow.addView(btn("screenRefresh") { log(EinkClient.screenRefresh(this, false, 0)) })
        actRow.addView(btn("oneFullFrame") { log(EinkClient.sendOneFullFrame(this)) })

        // Diagnostic row: confirm setMode worked + disable HWC auto-waveform
        val diagRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        diagRow.addView(btn("Read mode") { log(EinkClient.getMode(this)) })
        diagRow.addView(btn("Dis. auto") { log(EinkClient.disableAutoMode(this)) })
        diagRow.addView(btn("×10 toggle") {
            repeat(10) { testView.toggle(); testView.invalidate() }
            log(EinkClient.applyMode(this, testView, mode))
        })

        // Per-view waveform row — bypasses sys.eink.mode global property via
        // View.setEinkUpdateMode(dataMode, dispMode), patched into the Supernote framework.
        // Each button: toggle frame + apply waveform directly to the view.
        val viewModeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        viewModeRow.addView(btn("GC16-V") {
            testView.toggle()
            log(EinkClient.setViewMode(testView, 0, 2))
        })
        viewModeRow.addView(btn("GL16-V") {
            testView.toggle()
            log(EinkClient.setViewMode(testView, 0, 8))
        })
        viewModeRow.addView(btn("DU-V") {
            testView.toggle()
            log(EinkClient.setViewMode(testView, 0, 14))
        })
        viewModeRow.addView(btn("A2-V") {
            testView.toggle()
            log(EinkClient.setViewMode(testView, 0, 12))
        })

        testView = TestView(this)

        root.addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(modeRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(actRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(diagRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(viewModeRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(testView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun selectMode(m: String, name: String) {
        mode = m
        log("mode → $name ($m)\n" + EinkClient.setMode(this, m))
    }

    private fun log(s: String) {
        status.text = "mode=$mode\n$s"
        Log.i(EinkClient.TAG, "STATUS: $s")
    }

    private fun btn(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label; isAllCaps = false; textSize = 13f; setTextColor(Color.BLACK)
        minHeight = (56 * resources.displayMetrics.density).toInt()
        layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        setOnClickListener { onClick() }
    }

    /**
     * Two inverted high-contrast frames plus a grey ramp and a fine-text block —
     * toggling between them reveals the active waveform and any ghosting it
     * leaves. Software-layered (e-ink owns onDraw; no hardware compositor).
     */
    private class TestView(context: Context) : View(context) {
        private var flipped = false
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

        fun toggle() { flipped = !flipped }

        override fun onDraw(canvas: Canvas) {
            val bg = if (flipped) Color.BLACK else Color.WHITE
            val fg = if (flipped) Color.WHITE else Color.BLACK
            canvas.drawColor(bg)

            paint.color = fg
            paint.textSize = 120f
            canvas.drawText(if (flipped) "FRAME B" else "FRAME A", 80f, 240f, paint)

            // Grey ramp — exposes greyscale-waveform quality / ghosting.
            for (i in 0 until 8) {
                paint.color = Color.rgb(i * 32, i * 32, i * 32)
                canvas.drawRect(80f + i * 200, 320f, 260f + i * 200, 560f, paint)
            }

            // Dense text — ghosting reads clearly in fine glyphs.
            paint.color = fg
            paint.textSize = 36f
            for (line in 0 until 12) {
                canvas.drawText(
                    "The quick brown fox jumps over the lazy dog 0123456789",
                    80f, 680f + line * 60f, paint,
                )
            }
        }
    }
}

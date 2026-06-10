package com.afluffywaffle.layuv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode

class InkActivity : Activity() {
    companion object {
        const val EXTRA_PNG_BYTES = "png_bytes"
    }

    private lateinit var canvasView: InkCanvasView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        canvasView = InkCanvasView(this)

        val clearBtn = Button(this).apply {
            text = "Clear"
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.parseColor("#CCCCCC"))
        }
        val doneBtn = Button(this).apply {
            text = "Done"
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.parseColor("#CCCCCC"))
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(clearBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(doneBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            addView(btnRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(canvasView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        setContentView(root)

        // Set fast partial-refresh EPD mode and record which mode worked so
        // InkCanvasView can call EpdController.invalidate() explicitly on each stroke.
        // Also apply a transient app-scope fast mode to reduce per-call overhead.
        // HAND_WRITING_REPAINT_MODE is absent on older Supernote firmware (RK3026); fall back to DU.
        canvasView.post {
            for (mode in listOf(UpdateMode.HAND_WRITING_REPAINT_MODE, UpdateMode.DU)) {
                try {
                    EpdController.setViewDefaultUpdateMode(canvasView, mode)
                    canvasView.epdMode = mode
                    try { EpdController.applyTransientUpdate(mode) } catch (_: Exception) {}
                    break
                } catch (_: Exception) {}
            }
        }

        clearBtn.setOnClickListener { canvasView.clear() }
        doneBtn.setOnClickListener {
            val bytes = canvasView.toPngBytes()
            if (bytes != null) {
                setResult(RESULT_OK, Intent().putExtra(EXTRA_PNG_BYTES, bytes))
            } else {
                setResult(RESULT_CANCELED)
            }
            finish()
        }
    }
}

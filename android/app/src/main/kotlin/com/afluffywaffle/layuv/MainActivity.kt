package com.afluffywaffle.layuv

import android.app.Activity
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.android.FlutterSurfaceView
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val inkChannel = "com.afluffywaffle.layuv/ink"
    private val inkRequestCode = 1001
    private var pendingInkResult: MethodChannel.Result? = null

    // Cached reference to Flutter's rendering surface and the EPD mode that
    // worked on first use — avoids repeated view-tree traversal and mode probing.
    private var flutterSurfaceView: View? = null
    private var workingEpdMode: UpdateMode? = null

    private fun getFlutterSurfaceView(): View? {
        if (flutterSurfaceView != null) return flutterSurfaceView
        flutterSurfaceView = findView(window.decorView) { it is FlutterSurfaceView }
        return flutterSurfaceView
    }

    private fun findView(root: View, predicate: (View) -> Boolean): View? {
        if (predicate(root)) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = findView(root.getChildAt(i), predicate)
                if (found != null) return found
            }
        }
        return null
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, inkChannel)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "presentInkCanvas" -> {
                        pendingInkResult = result
                        startActivityForResult(
                            Intent(this, InkActivity::class.java),
                            inkRequestCode,
                        )
                    }
                    "setEpdFastMode" -> {
                        for (mode in listOf(UpdateMode.HAND_WRITING_REPAINT_MODE, UpdateMode.DU)) {
                            try {
                                EpdController.applyTransientUpdate(mode)
                                break
                            } catch (_: Exception) {}
                        }
                        result.success(null)
                    }
                    "clearEpdMode" -> {
                        // No reset API available in this SDK version.
                        result.success(null)
                    }
                    "epdInvalidateRect" -> {
                        val args = call.arguments as? List<*>
                        val view = getFlutterSurfaceView()
                        if (args != null && args.size == 4 && view != null) {
                            // Flutter reports logical pixels; convert to physical.
                            val d = resources.displayMetrics.density
                            val l = ((args[0] as? Number)?.toFloat()?.times(d))?.toInt() ?: 0
                            val t = ((args[1] as? Number)?.toFloat()?.times(d))?.toInt() ?: 0
                            val r = ((args[2] as? Number)?.toFloat()?.times(d))?.toInt() ?: 0
                            val b = ((args[3] as? Number)?.toFloat()?.times(d))?.toInt() ?: 0
                            val mode = workingEpdMode
                            if (mode != null) {
                                try { EpdController.invalidate(view, l, t, r, b, mode) } catch (_: Exception) {}
                            } else {
                                for (m in listOf(UpdateMode.HAND_WRITING_REPAINT_MODE, UpdateMode.DU)) {
                                    try {
                                        EpdController.invalidate(view, l, t, r, b, m)
                                        workingEpdMode = m
                                        break
                                    } catch (_: Exception) {}
                                }
                            }
                        }
                        result.success(null)
                    }
                    else -> result.notImplemented()
                }
            }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == inkRequestCode) {
            val result = pendingInkResult ?: return
            pendingInkResult = null
            if (resultCode == Activity.RESULT_OK && data != null) {
                val bytes = data.getByteArrayExtra(InkActivity.EXTRA_PNG_BYTES)
                result.success(bytes)
            } else {
                result.success(null)
            }
        } else {
            @Suppress("DEPRECATION")
            super.onActivityResult(requestCode, resultCode, data)
        }
    }
}

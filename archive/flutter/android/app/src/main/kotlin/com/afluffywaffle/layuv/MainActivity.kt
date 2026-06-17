package com.afluffywaffle.layuv

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val einkSpikeChannel = "com.afluffywaffle.layuv/eink_spike"

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // drawPath low-latency ink over a Flutter window (Supernote / Ratta).
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "com.afluffywaffle.layuv/drawpath")
            .setMethodCallHandler { call, result ->
                val app = packageName
                when (call.method) {
                    "configure" -> {
                        // Disable the top toolbar strip. Compute physical px from
                        // the device's real metrics (Flutter's MediaQuery is
                        // degenerate here) + a dpr-agnostic fraction from Dart.
                        val frac = call.argument<Double>("canvasTopFraction") ?: 0.1
                        val dm = resources.displayMetrics
                        val w = dm.widthPixels
                        val hPx = (frac * dm.heightPixels).toInt()
                        val rects = intArrayOf(0, 0, w, hPx, 0) // l,t,w,h,flag(0=disable)
                        val sb = StringBuilder()
                        sb.append(DrawPathClient.sendReset(app)).append(" | ")
                        sb.append(DrawPathClient.sendPen(
                            app,
                            call.argument<Int>("penType") ?: 10,
                            call.argument<Int>("penWidth") ?: 200,
                            call.argument<Int>("penColor") ?: 0,
                        )).append(" | ")
                        sb.append(DrawPathClient.setWritableAreas(app, rects, "disable(${w}x$hPx)"))
                        result.success(sb.toString())
                    }
                    // Whitelist-only config for the ink canvas: only the area
                    // below the toolbar receives drawPath ink (flag 1). Computed
                    // from real device density — Flutter metrics are unreliable.
                    "configureForInk" -> {
                        val toolbarDp = call.argument<Int>("toolbarDp") ?: 160
                        val penWidth = call.argument<Int>("penWidth") ?: 150
                        val penColor = call.argument<Int>("penColor") ?: 0
                        val dm = resources.displayMetrics
                        val toolbarPx = (toolbarDp * dm.density).toInt()
                        val w = dm.widthPixels
                        val h = dm.heightPixels
                        val sb = StringBuilder()
                        sb.append(DrawPathClient.sendReset(app)).append(" | ")
                        sb.append(DrawPathClient.sendPen(app, 10, penWidth, penColor)).append(" | ")
                        // flag 1 = writable whitelist: only canvas area below toolbar
                        val canvas = intArrayOf(0, toolbarPx, w, h - toolbarPx, 1)
                        sb.append(DrawPathClient.setWritableAreas(app, canvas,
                            "whitelist(canvas 0,$toolbarPx ${w}x${h - toolbarPx})"))
                        result.success(sb.toString())
                    }
                    // Eraser mode: whitelist only the top strip so drawPath ink
                    // is blocked in the canvas area below.
                    "disableInk" -> {
                        val toolbarDp = call.argument<Int>("toolbarDp") ?: 160
                        val dm = resources.displayMetrics
                        val toolbarPx = (toolbarDp * dm.density).toInt()
                        val w = dm.widthPixels
                        val sb = StringBuilder()
                        sb.append(DrawPathClient.sendReset(app)).append(" | ")
                        val topStrip = intArrayOf(0, 0, w, toolbarPx, 1)
                        sb.append(DrawPathClient.setWritableAreas(app, topStrip,
                            "eraseMode(${w}x$toolbarPx)"))
                        result.success(sb.toString())
                    }
                    "clear" -> result.success(DrawPathClient.clearScreen(app))
                    "available" -> result.success(DrawPathClient.available())
                    else -> result.notImplemented()
                }
            }

        // Ratta-native e-ink refresh (android.os.EinkManager). Decides whether
        // Flutter page-flips refresh cleanly on Supernote.
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, einkSpikeChannel)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "fullRefresh" -> result.success(RattaEinkSpike.fullRefresh(this))
                    "screenRefresh" -> result.success(
                        RattaEinkSpike.screenRefresh(
                            this,
                            (call.argument<Boolean>("force")) ?: true,
                            (call.argument<Int>("mode")) ?: 0,
                        ),
                    )
                    "setScreenMode" -> result.success(
                        RattaEinkSpike.setScreenModeByName(
                            this,
                            call.argument<String>("name") ?: "EINK_SCREEN_MODE_CLEAR",
                        ),
                    )
                    else -> result.notImplemented()
                }
            }
    }
}

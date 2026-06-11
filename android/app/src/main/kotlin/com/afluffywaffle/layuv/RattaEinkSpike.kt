package com.afluffywaffle.layuv

import android.content.Context
import android.util.Log

/**
 * SPIKE — Ratta (Supernote) native e-ink control via `android.os.EinkManager`,
 * reached with `getSystemService("eink")` + reflection (hidden API).
 *
 * Discovered from plateaukao/AssistiveTouch + dumping framework.jar. This is the
 * CORRECT refresh API for Supernote (the Onyx EpdController used elsewhere is
 * Boox and no-ops on Ratta). Method signatures (from the framework dex):
 *   sendOneFullFrame()                       — full-screen refresh (the swipe)
 *   screenRefresh(boolean force, int mode)   — full refresh w/ flag + mode
 *   setScreenMode(int mode, boolean apply)   — waveform mode
 * Screen-mode constants (int) read by name: EINK_SCREEN_MODE_CLEAR/DEFAULT/SMOOTH/SPEED.
 *
 * The whole point of this spike: does an explicit full refresh make Flutter
 * page-flips look CLEAN on e-ink? If yes, the native-port premise is moot.
 */
object RattaEinkSpike {
    private const val TAG = "RattaEinkSpike"

    private fun service(ctx: Context): Any? = try {
        ctx.getSystemService("eink").also { Log.i(TAG, "getSystemService(eink) -> $it") }
    } catch (e: Throwable) {
        Log.e(TAG, "getSystemService(eink) failed: ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    /** EinkManager.sendOneFullFrame() — the clean full refresh. */
    fun fullRefresh(ctx: Context): String {
        val svc = service(ctx) ?: return "FAIL: no eink service"
        return try {
            svc.javaClass.getMethod("sendOneFullFrame").invoke(svc)
            "ok: sendOneFullFrame".also { Log.i(TAG, it) }
        } catch (e: Throwable) {
            "sendOneFullFrame THREW: ${e.javaClass.simpleName}: ${e.message}".also { Log.e(TAG, it) }
        }
    }

    /** EinkManager.screenRefresh(force, mode). */
    fun screenRefresh(ctx: Context, force: Boolean, mode: Int): String {
        val svc = service(ctx) ?: return "FAIL: no eink service"
        return try {
            svc.javaClass.getMethod(
                "screenRefresh", Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            ).invoke(svc, force, mode)
            "ok: screenRefresh(force=$force, mode=$mode)".also { Log.i(TAG, it) }
        } catch (e: Throwable) {
            "screenRefresh THREW: ${e.javaClass.simpleName}: ${e.message}".also { Log.e(TAG, it) }
        }
    }

    /** EinkManager.setScreenMode(mode, apply) where mode is resolved by constant name. */
    fun setScreenModeByName(ctx: Context, name: String): String {
        val svc = service(ctx) ?: return "FAIL: no eink service"
        return try {
            val mode = svc.javaClass.getField(name).getInt(null)
            svc.javaClass.getMethod(
                "setScreenMode", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            ).invoke(svc, mode, true)
            "ok: setScreenMode($name=$mode)".also { Log.i(TAG, it) }
        } catch (e: Throwable) {
            "setScreenMode($name) THREW: ${e.javaClass.simpleName}: ${e.message}".also { Log.e(TAG, it) }
        }
    }
}

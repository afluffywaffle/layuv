package com.afluffywaffle.layuv.spike

import android.content.Context
import android.graphics.Rect
import android.os.IBinder
import android.util.Log
import android.view.View

/**
 * SPIKE — client for the Supernote native EPD refresh path. This is the Ratta
 * equivalent of Onyx's `EpdController` and the CORRECT refresh route on
 * Supernote; the Onyx `EpdController` in `reader/Epd.kt` is a Boox no-op here.
 *
 * Model: the EPD waveform is selected by the system property `sys.eink.mode`
 * (set via `EinkManager.setMode` / `IEinkManager.setProperty`), then a normal
 * redraw (`View.invalidate`) composites with that waveform. Forced full flushes
 * go through `screenRefresh()` / `sendOneFullFrame()`.
 *
 * `android.os.EinkManager` is a clean AIDL wrapper around the `eink` service
 * (`android.os.IEinkManager`) — obtained via `context.getSystemService("eink")`.
 * We reach it by reflection (hidden API); falls back to `ServiceManager` +
 * `IEinkManager.Stub.asInterface` if the system-service lookup returns null.
 * Hidden-API reflection needs `settings put global hidden_api_policy 0` (same
 * caveat as [DrawPathClient]).
 *
 * Waveform mode values for `sys.eink.mode`, mapped to reader Epd ops:
 *   "2"  FULL_GC16 — full clean greyscale   (Onyx GC  → fullClear)
 *   "8"  PART_GL16 — partial greyscale, fast (Onyx GU  → pageTurn)
 *   "14" DU        — direct update 1-bit     (Onyx DU  → selection / region)
 *   "12" A2        — fast 1-bit
 *   "7"  PART_GC16 — partial clean greyscale; "0" AUTO
 */
object EinkClient {
    const val TAG = "LeamhEinkSpike"

    const val FULL_GC16 = "2"
    const val PART_GC16 = "7"
    const val PART_GL16 = "8"
    const val A2 = "12"
    const val DU = "14"
    const val AUTO = "0"

    private const val SERVICE = "eink"
    private const val PROP_MODE = "sys.eink.mode"
    private const val PROP_ONE_FULL = "sys.eink.one_full_mode_timeline"

    private var manager: Any? = null   // android.os.EinkManager
    private var service: Any? = null   // android.os.IEinkManager proxy (fallback)
    private var resolved = false
    private var oneFullCounter = 1

    private fun resolve(context: Context) {
        if (resolved) return
        resolved = true
        manager = try {
            context.getSystemService(SERVICE)?.also {
                Log.i(TAG, "getSystemService(eink) -> ${it.javaClass.name}")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "getSystemService(eink) failed: ${e.javaClass.simpleName}: ${e.message}"); null
        }
        if (manager == null) {
            service = try {
                val sm = Class.forName("android.os.ServiceManager")
                val binder = sm.getMethod("getService", String::class.java).invoke(null, SERVICE) as? IBinder
                val stub = Class.forName("android.os.IEinkManager\$Stub")
                stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)?.also {
                    Log.i(TAG, "IEinkManager via ServiceManager -> ${it.javaClass.name}")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "ServiceManager(eink) failed: ${e.javaClass.simpleName}: ${e.message}"); null
            }
        }
    }

    fun available(context: Context): Boolean {
        resolve(context); return manager != null || service != null
    }

    private fun invokeManager(method: String, sig: Array<Class<*>>, vararg args: Any?): Boolean {
        val m = manager ?: return false
        m.javaClass.getMethod(method, *sig).invoke(m, *args); return true
    }

    private fun invokeService(method: String, sig: Array<Class<*>>, vararg args: Any?): Boolean {
        val s = service ?: return false
        s.javaClass.getMethod(method, *sig).invoke(s, *args); return true
    }

    /** Set `sys.eink.mode` to [mode] (one of the EPD_* strings above). */
    fun setMode(context: Context, mode: String): String {
        resolve(context)
        return try {
            when {
                invokeManager("setMode", arrayOf(String::class.java), mode) -> "setMode($mode) [mgr] ok"
                invokeService("setProperty", arrayOf(String::class.java, String::class.java), PROP_MODE, mode) ->
                    "setProperty(mode=$mode) [svc] ok"
                else -> "FAIL: no eink service"
            }
        } catch (e: Throwable) {
            "setMode($mode) THREW: ${e.javaClass.simpleName}: ${e.message}"
        }.also { Log.i(TAG, it) }
    }

    /** Full-screen refresh (the GC/`fullClear` flush). */
    fun screenRefresh(context: Context, afterWindowHide: Boolean, reserved: Int): String {
        resolve(context)
        val sig = arrayOf<Class<*>>(Boolean::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
        return try {
            when {
                invokeManager("screenRefresh", sig, afterWindowHide, reserved) -> "screenRefresh($afterWindowHide,$reserved) [mgr] ok"
                invokeService("screenRefresh", sig, afterWindowHide, reserved) -> "screenRefresh($afterWindowHide,$reserved) [svc] ok"
                else -> "FAIL: no eink service"
            }
        } catch (e: Throwable) {
            "screenRefresh THREW: ${e.javaClass.simpleName}: ${e.message}"
        }.also { Log.i(TAG, it) }
    }

    /** Push one clean full frame (flushes accumulated ghosting). */
    fun sendOneFullFrame(context: Context): String {
        resolve(context)
        val num = (oneFullCounter++).toString()
        return try {
            when {
                invokeManager("sendOneFullFrame", arrayOf()) -> "sendOneFullFrame [mgr] ok"
                invokeService("setProperty", arrayOf(String::class.java, String::class.java), PROP_ONE_FULL, num) ->
                    "oneFullFrame(prop=$num) [svc] ok"
                else -> "FAIL: no eink service"
            }
        } catch (e: Throwable) {
            "sendOneFullFrame THREW: ${e.javaClass.simpleName}: ${e.message}"
        }.also { Log.i(TAG, it) }
    }

    /**
     * Read back `sys.eink.mode` via `EinkManager.getMode()`. Confirms the
     * property was actually updated after [setMode].
     */
    fun getMode(context: Context): String {
        resolve(context)
        return try {
            val m = manager ?: return "getMode: no manager"
            val result = m.javaClass.getMethod("getMode").invoke(m) as? String
            "getMode() = $result"
        } catch (e: Throwable) {
            "getMode THREW: ${e.javaClass.simpleName}: ${e.message}"
        }.also { Log.i(TAG, it) }
    }

    /**
     * Disable HWC auto-waveform selection (`enableFullUiAuto(false, false)`).
     * The compositor picks its own waveform in auto mode and ignores
     * `sys.eink.mode`; call this first so manual [setMode] takes effect.
     */
    fun disableAutoMode(context: Context): String {
        resolve(context)
        return try {
            val sig = arrayOf<Class<*>>(Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!)
            when {
                invokeManager("enableFullUiAuto", sig, false, false) -> "enableFullUiAuto(false,false) [mgr] ok"
                else -> "FAIL: no eink service"
            }
        } catch (e: Throwable) {
            "disableAutoMode THREW: ${e.javaClass.simpleName}: ${e.message}"
        }.also { Log.i(TAG, it) }
    }

    // -------------------------------------------------------------------------
    // EinkPwInternalY — partial-window regional refresh (hidden API, needs policy 0).
    // Used internally by drawPath for its own EPD rect updates.
    // -------------------------------------------------------------------------

    private var pwInternal: Any? = null  // htfyun.penwrite.ctrl.EinkPwInternalY instance
    private var pwClass: Class<*>? = null

    /**
     * Init the EinkPwInternalY singleton. Call once before [postRectForPw].
     * If this fails, the class doesn't exist on this firmware or hidden_api_policy
     * is still enforced.
     */
    fun initPwInternal(context: Context): String {
        return try {
            val cls = Class.forName("htfyun.penwrite.ctrl.EinkPwInternalY")
            val inst = cls.getMethod("getEinkPwInternal").invoke(null)
            cls.getMethod("initForPw", Context::class.java).invoke(inst, context)
            pwInternal = inst
            pwClass = cls
            "EinkPwInternalY.initForPw ok — inst=${inst.javaClass.simpleName}"
        } catch (e: Throwable) {
            "initPwInternal THREW: ${e.javaClass.simpleName}: ${e.message}"
        }.also { Log.i(TAG, it) }
    }

    /**
     * Post a regional EPD refresh via htfyun.penwrite.ctrl.EinkPwInternalY.
     * Call [initPwInternal] first.
     *
     * Waveform probes (dispMode / dataMode / a2Gate):
     *   postRectForPw(rect, 3,  3, 0)   — GL16 quality text (page turns)
     *   postRectForPw(rect, 16, 1, 183) — fast A2 (ink)
     *   postRectForPw(rect, 16, 9, 183) — default ink bitfield
     */
    fun postRectForPw(rect: Rect, dispMode: Int, dataMode: Int, a2Gate: Int): String {
        val inst = pwInternal ?: return "FAIL: not initialized — call PW init first"
        val cls = pwClass ?: return "FAIL: no class"
        return try {
            cls.getMethod(
                "postRectForPw",
                Rect::class.java,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
            ).invoke(inst, rect, dispMode, dataMode, a2Gate)
            "postRectForPw(disp=$dispMode,data=$dataMode,a2=$a2Gate) ok"
        } catch (e: Throwable) {
            "postRectForPw THREW: ${e.javaClass.simpleName}: ${e.message}"
        }.also { Log.i(TAG, it) }
    }

    /** The `Epd.apply()` analog: select [mode], then redraw [view]. */
    fun applyMode(context: Context, view: View, mode: String): String {
        val r = setMode(context, mode)
        view.invalidate()
        return r
    }

    /**
     * Per-view waveform setter — calls `View.setEinkUpdateMode(dataMode, dispMode)`,
     * a method Supernote patches directly into the framework View class. This bypasses
     * the `sys.eink.mode` global property (which bounces back to 7 immediately after
     * being set — the property is consumed and reset per-frame by the HWC).
     *
     * [dispMode] is the EinkMode integer: 2=GC16, 7=PART_GC16, 8=GL16, 12=A2, 14=DU.
     * [dataMode] controls pixel format; 0 lets the framework choose (safest default).
     *
     * Walks the class hierarchy so it finds the method on `android.view.View` regardless
     * of which View subclass [view] is.
     */
    fun setViewMode(view: View, dataMode: Int, dispMode: Int): String {
        return try {
            var cls: Class<*>? = view.javaClass
            var m: java.lang.reflect.Method? = null
            while (cls != null && m == null) {
                m = try {
                    cls.getDeclaredMethod(
                        "setEinkUpdateMode",
                        Int::class.javaPrimitiveType!!,
                        Int::class.javaPrimitiveType!!,
                    )
                } catch (_: NoSuchMethodException) { null }
                if (m == null) cls = cls.superclass
            }
            if (m == null) return "setEinkUpdateMode: not found (framework not patched?)".also { Log.i(TAG, it) }
            m.isAccessible = true
            m.invoke(view, dataMode, dispMode)
            view.invalidate()
            "setEinkUpdateMode($dataMode,$dispMode) ok"
        } catch (e: Throwable) {
            "setEinkUpdateMode THREW: ${e.javaClass.simpleName}: ${e.message}"
        }.also { Log.i(TAG, it) }
    }
}

package com.afluffywaffle.layuv.spike

import android.os.IBinder
import android.os.Parcel
import android.util.Log

/**
 * SPIKE — client for the Supernote drawPath low-latency handwriting service
 * (drawPath.apk, package com.ratta.drawpath).
 *
 * Transaction codes (based on Ratta's drawPath PDF + on-device probing):
 *   code 1  setWritableAndNonWritableArea(app, FlagRect[])  — disable/whitelist areas
 *   code 2  setPenInfo(app, type, width, color)             — pen attributes
 *   code 4  askTrailData(app, ...)                          — get strokes back
 *   code 6  clearScreen(app, int=255)                       — programmatic full clear
 *   code 9  setWalcomEmrInfo(app, int)                      — Wacom EMR digitizer
 *   code 16 askDeletedData(...)                             — eraser/deleted strokes
 *
 * Service: "service_myservice" (ServiceManager). Interface token:
 * "android.demo.IMyService". NATIVE C++ binder — do NOT call readException() on
 * the reply (it is not an AIDL envelope; first int is a constant ack). Success =
 * transact()==true with no RemoteException.
 *
 * FlagRect parcel layout (code 1): token, String app, int count, then per rect
 * { int left, int top, int width, int height, int flag }. CONFIRMED on Nomad
 * 2026-06-09: flag 0 = non-writable/disable (blacklist — ink suppressed in the
 * rect, rest of screen stays writable; use for toolbar protection); flag 1 =
 * writable whitelist (that rect becomes the ONLY drawable area). The 18888
 * full-screen sentinel rect is special-cased as a reset (clears the area list).
 */
object DrawPathClient {
    const val TAG = "DrawPathSpike"
    private const val SERVICE = "service_myservice"
    private const val TOKEN = "android.demo.IMyService"

    private const val CODE_WRITABLE_AREA = 1
    private const val CODE_PEN = 2
    private const val CODE_CLEAR = 6
    private const val CODE_WALCOM_EMR = 9

    /** Whole-screen rect for the mandatory post-resume reset. */
    val RESET_RECT = intArrayOf(0, 0, 18888, 18888)

    private var cached: IBinder? = null

    private fun binder(): IBinder? {
        cached?.let { if (it.isBinderAlive) return it }
        cached = try {
            val sm = Class.forName("android.os.ServiceManager")
            val getService = sm.getMethod("getService", String::class.java)
            (getService.invoke(null, SERVICE) as? IBinder).also {
                Log.i(TAG, "ServiceManager.getService($SERVICE) -> $it")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "getService($SERVICE) failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
        return cached
    }

    fun available(): Boolean = binder() != null

    /** code 2 — pen attributes. */
    fun sendPen(appName: String, type: Int, width: Int, color: Int): String =
        transactInts("pen", CODE_PEN, appName) {
            it.writeInt(type); it.writeInt(width); it.writeInt(color)
        } + " type=$type w=$width c=$color"

    /**
     * code 6 — programmatic full-screen clear (what the swipe gesture does).
     * CONFIRMED on Nomad 2026-06-09: flushes drawPath's retained buffer; ink
     * vanishes from the EPD.
     */
    fun clearScreen(appName: String): String =
        transactInts("clearScreen", CODE_CLEAR, appName) {
            it.writeInt(255) // constant the Bp proxy always writes
        }

    /**
     * code 1 — setWritableAndNonWritableArea. Each rect = [left, top, width,
     * height, flag]. CONFIRMED: flag 0 = disable (blacklist that rect, rest
     * writable); flag 1 = writable whitelist (only that rect writable).
     */
    fun setWritableAreas(appName: String, rects: List<IntArray>, label: String = "areas"): String =
        transactInts(label, CODE_WRITABLE_AREA, appName) {
            it.writeInt(rects.size)
            for (r in rects) {
                it.writeInt(r[0]); it.writeInt(r[1]); it.writeInt(r[2]); it.writeInt(r[3])
                it.writeInt(if (r.size > 4) r[4] else 0)
            }
        } + " n=${rects.size}"

    /**
     * code 9 — setWalcomEmrInfo. Wacom EMR digitizer config on drawPath.
     * Hypothesis: controls pen-up recognition trigger (symbol SET_PEN_UP_RECG_TRIGGER
     * seen in librecgnition.so). Probe values 0, 1, 50, 100 to see which shortens
     * the ~150–300ms kernel pen-up delay. Watch pen-up latency in logcat (drawAPP)
     * and on-device feel after each call.
     */
    fun sendWalcomEmrInfo(appName: String, value: Int): String =
        transactInts("walcomEmr", CODE_WALCOM_EMR, appName) {
            it.writeInt(value)
        } + " value=$value"

    /** The mandatory post-resume reset: whole screen, flag 0 (per Ratta PDF). */
    fun sendReset(appName: String): String =
        setWritableAreas(appName, listOf(RESET_RECT + 0), label = "reset")

    private inline fun transactInts(
        label: String, code: Int, appName: String, writePayload: (Parcel) -> Unit,
    ): String {
        val b = binder() ?: return "FAIL: no binder"
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(TOKEN)
            data.writeString(appName)
            writePayload(data)
            val ok = b.transact(code, data, reply, 0)
            val r = if (reply.dataSize() >= 4) reply.readInt() else -1
            "$label[c$code]: ok=$ok reply0=$r".also { Log.i(TAG, it) }
        } catch (e: Throwable) {
            "$label[c$code] THREW: ${e.javaClass.simpleName}: ${e.message}".also { Log.e(TAG, it) }
        } finally {
            data.recycle(); reply.recycle()
        }
    }
}

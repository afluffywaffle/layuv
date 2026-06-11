package com.afluffywaffle.layuv

import android.os.IBinder
import android.os.Parcel
import android.util.Log

/**
 * SPIKE — client for the Supernote drawPath low-latency handwriting service,
 * ported verbatim from the native-port spike (logic is host-agnostic Binder
 * code). Confirmed on the Manta:
 *   service "service_myservice", token "android.demo.IMyService"
 *   code 1 setWritableAndNonWritableArea(app, FlagRect[])  — flag 0 = disable
 *   code 2 setPenInfo(app, type, width, color)
 *   code 6 clearScreen(app, 255)
 * NATIVE C++ binder (not AIDL): success = transact()==true; don't readException().
 */
object DrawPathClient {
    const val TAG = "DrawPathInkSpike"
    private const val SERVICE = "service_myservice"
    private const val TOKEN = "android.demo.IMyService"
    private const val CODE_WRITABLE_AREA = 1
    private const val CODE_PEN = 2
    private const val CODE_CLEAR = 6

    val RESET_RECT = intArrayOf(0, 0, 18888, 18888)

    private var cached: IBinder? = null

    private fun binder(): IBinder? {
        cached?.let { if (it.isBinderAlive) return it }
        cached = try {
            val sm = Class.forName("android.os.ServiceManager")
            val getService = sm.getMethod("getService", String::class.java)
            (getService.invoke(null, SERVICE) as? IBinder).also {
                Log.i(TAG, "getService($SERVICE) -> $it")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "getService failed: ${e.javaClass.simpleName}: ${e.message}"); null
        }
        return cached
    }

    fun available(): Boolean = binder() != null

    fun sendPen(app: String, type: Int, width: Int, color: Int): String =
        transact("pen", CODE_PEN, app) { it.writeInt(type); it.writeInt(width); it.writeInt(color) }

    fun clearScreen(app: String): String =
        transact("clear", CODE_CLEAR, app) { it.writeInt(255) }

    /** rects flat: [l,t,w,h,flag, l,t,w,h,flag, ...]; flag 0 = disable. */
    fun setWritableAreas(app: String, flat: IntArray, label: String = "areas"): String =
        transact(label, CODE_WRITABLE_AREA, app) {
            val n = flat.size / 5
            it.writeInt(n)
            for (i in 0 until n) {
                val o = i * 5
                it.writeInt(flat[o]); it.writeInt(flat[o + 1]); it.writeInt(flat[o + 2])
                it.writeInt(flat[o + 3]); it.writeInt(flat[o + 4])
            }
        } + " n=${flat.size / 5}"

    fun sendReset(app: String): String =
        setWritableAreas(app, RESET_RECT + 0, "reset")

    private inline fun transact(label: String, code: Int, app: String, payload: (Parcel) -> Unit): String {
        val b = binder() ?: return "FAIL: no binder"
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(TOKEN)
            data.writeString(app)
            payload(data)
            val ok = b.transact(code, data, reply, 0)
            "$label[c$code]: ok=$ok".also { Log.i(TAG, it) }
        } catch (e: Throwable) {
            "$label[c$code] THREW: ${e.javaClass.simpleName}: ${e.message}".also { Log.e(TAG, it) }
        } finally {
            data.recycle(); reply.recycle()
        }
    }
}

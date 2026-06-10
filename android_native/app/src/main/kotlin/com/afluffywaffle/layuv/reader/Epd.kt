package com.afluffywaffle.layuv.reader

import android.util.Log
import android.view.View
import java.lang.reflect.Method

/**
 * EPD refresh for the reader surface. Page turn, full clear, and selection all
 * funnel through [apply].
 *
 * DEFAULT PATH — auto-EPD via a plain `View.invalidate`: the device's own HWC
 * picks the waveform. This is the route confirmed working for page turns on the
 * Manta AND the only one that visibly refreshes partial updates on the Nomad.
 *
 * MANUAL waveform control (`View.setEinkUpdateMode(dataMode, dispMode)`, a method
 * patched into the Supernote framework's View class) is implemented but OFF by
 * default behind [MANUAL_WAVEFORM]. It works visibly on the Manta but, on the
 * Nomad, only updates the framebuffer — the panel does NOT visibly refresh for
 * partial modes (GL16/A2), so the reader appears to advance only on the periodic
 * GC16 full clear (every [FULL_EVERY] turns) and then jumps several pages. Until
 * the dispMode map is validated per device, leave it off.
 *
 * Confirmed waveform map (Manta only, 2026-06-09, spike: EinkRefreshSpikeActivity):
 *   dispMode 2  GC16 — clean full refresh; clears all ghosting        → fullClear, pageTurn/N
 *   dispMode 8  GL16 — fast partial greyscale; same quality as GC16  → pageTurn
 *   dispMode 12 A2   — fast, no ghosting; also clears DU ghosting    → selection / region
 *   dispMode 14 DU   — 1-bit; heavy instant ghosting, greys go black → NOT USED
 *
 * NOTE: the `sys.eink.mode` global property approach does NOT work — the HWC
 * reads and resets it per-frame before `View.invalidate` fires, so the set never
 * takes effect. Per-view mode via `setEinkUpdateMode` is the only manual path.
 */
class Epd {
    private var turnsSinceFullClear = 0

    /** Page-turn — GL16 for most turns; auto-escalates to GC16 every [FULL_EVERY] turns. */
    fun pageTurn(view: View) {
        turnsSinceFullClear++
        if (turnsSinceFullClear >= FULL_EVERY) {
            turnsSinceFullClear = 0
            apply(view, DISP_GC16)
        } else {
            apply(view, DISP_GL16)
        }
    }

    /** Full clean refresh — GC16. Use on book open, column change, or position restore. */
    fun fullClear(view: View) {
        turnsSinceFullClear = 0
        apply(view, DISP_GC16)
    }

    /** Fast A2 refresh — used when drawing or clearing a text selection. */
    fun selection(view: View) = apply(view, DISP_A2)

    /**
     * Fast A2 refresh for a changed rect. Coordinates are view-local px.
     * True partial-window HAL refresh is deferred; A2 on the full view is
     * fast enough for selection drag on the current content sizes.
     */
    fun region(view: View, left: Int, top: Int, right: Int, bottom: Int) = apply(view, DISP_A2)

    private fun apply(view: View, dispMode: Int) {
        if (MANUAL_WAVEFORM) {
            // Set the waveform on the window root (DecorView), NOT the child view:
            // the per-child call suppresses that view's auto-EPD without firing its
            // own refresh. The child's invalidate() below dirties the region.
            //
            // CONFIRMED BROKEN on the Nomad: this DOES update the framebuffer, but the
            // panel does NOT visibly refresh for PARTIAL modes (GL16/A2) — only the
            // GC16 full clear (every FULL_EVERY turns) punches through, so the reader
            // appears to advance only on every 6th tap and then jumps several pages.
            // Left behind this flag for re-validation on the Manta (where the dispMode
            // map was first confirmed); default OFF so all panels use auto-EPD.
            val m = einkUpdateMethod(view)
            if (m != null) {
                val target = view.rootView ?: view
                try {
                    m.invoke(target, DATA_MODE, dispMode)
                    Log.i(TAG, "setEinkUpdateMode($DATA_MODE,$dispMode) on ${target.javaClass.simpleName}")
                } catch (e: Throwable) {
                    Log.w(TAG, "setEinkUpdateMode($DATA_MODE,$dispMode) threw: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
        // Auto-EPD path: a plain invalidate lets the device's own HWC pick a visible
        // waveform. This is the route confirmed working for page turns on the Manta
        // and the only one that visibly refreshes partial updates on the Nomad.
        view.invalidate()
    }

    companion object {
        const val TAG = "LeamhEpd"

        // Manual EPD waveform control via View.setEinkUpdateMode. Confirmed visibly
        // working on the Manta, but does NOT visibly refresh the Nomad panel for
        // partial modes (see apply()), so it is OFF by default — every device uses
        // the auto-EPD path (plain invalidate). Flip to true only to re-test the
        // dispMode map on the Manta.
        private const val MANUAL_WAVEFORM = false

        private const val FULL_EVERY = 6
        private const val DATA_MODE = 0  // let the framework choose pixel format

        // Confirmed dispMode values (Manta 2026-06-09):
        private const val DISP_GC16 = 2   // full clean greyscale
        private const val DISP_GL16 = 8   // fast partial greyscale
        private const val DISP_A2   = 12  // fast, no ghosting

        private var methodResolved = false
        private var cachedMethod: Method? = null

        private fun einkUpdateMethod(view: View): Method? {
            if (methodResolved) return cachedMethod
            methodResolved = true
            var cls: Class<*>? = view.javaClass
            while (cls != null) {
                cachedMethod = try {
                    cls.getDeclaredMethod(
                        "setEinkUpdateMode",
                        Int::class.javaPrimitiveType!!,
                        Int::class.javaPrimitiveType!!,
                    ).also { it.isAccessible = true }
                } catch (_: NoSuchMethodException) { null }
                if (cachedMethod != null) {
                    Log.i(TAG, "setEinkUpdateMode found on ${cls.name}")
                    return cachedMethod
                }
                cls = cls.superclass
            }
            Log.w(TAG, "setEinkUpdateMode not found — plain invalidate() only (non-Supernote build)")
            return null
        }
    }
}

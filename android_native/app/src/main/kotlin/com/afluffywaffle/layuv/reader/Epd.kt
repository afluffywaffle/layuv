package com.afluffywaffle.layuv.reader

import android.view.View

/**
 * EPD refresh for the reader surface. All page turns and full clears funnel
 * through this class.
 *
 * REFRESH STRATEGY
 * ────────────────
 * Partial updates (pageTurn, selection, region) — plain View.invalidate().
 * The device's HWC picks a visible waveform automatically; this is the route
 * confirmed working on both the Manta and the Nomad.
 *
 * Full clears (book open, column change, every-N-turns ghost flush) —
 * RattaEink.sendOneFullFrame() followed by View.invalidate(). On Supernote
 * hardware this drives android.os.EinkManager.sendOneFullFrame(), a clean
 * full-panel refresh. On non-Supernote builds RattaEink is a no-op and the
 * plain invalidate() still fires.
 *
 * PRIOR APPROACHES TRIED AND ABANDONED
 * ─────────────────────────────────────
 * Manual waveform via View.setEinkUpdateMode(dataMode, dispMode) (a method
 * Supernote patches into the framework View class) was confirmed working on
 * the Manta but does NOT visibly refresh the Nomad panel for partial modes
 * (GL16=8, A2=12) — only the GC16=2 full clear punched through, causing the
 * reader to appear stuck and then jump several pages at once. Removed.
 *
 * sys.eink.mode global property via EinkManager.setMode() does not work: the
 * HWC reads and resets it per-frame before View.invalidate() fires. Removed.
 *
 * Confirmed waveform map (Manta, 2026-06-09, EinkRefreshSpikeActivity):
 *   dispMode 2  GC16 — clean full refresh; clears all ghosting
 *   dispMode 8  GL16 — fast partial greyscale
 *   dispMode 12 A2   — fast, no ghosting; also clears DU ghosting
 *   dispMode 14 DU   — 1-bit; heavy ghosting, greys go black — not used
 */
class Epd {
    private var turnsSinceFullClear = 0

    /** Page-turn — auto-EPD for most turns; escalates to a full clear every [FULL_EVERY] turns. */
    fun pageTurn(view: View) {
        turnsSinceFullClear++
        if (turnsSinceFullClear >= FULL_EVERY) {
            turnsSinceFullClear = 0
            fullClear(view)
        } else {
            view.invalidate()
        }
    }

    /** Full clean refresh — use on book open, column change, or position restore. */
    fun fullClear(view: View) {
        turnsSinceFullClear = 0
        RattaEink.sendOneFullFrame(view.context)
        view.invalidate()
    }

    /** Fast refresh for a text selection change — auto-EPD is fast enough. */
    fun selection(view: View) = view.invalidate()

    /**
     * Fast refresh for a changed rect. Coordinates are view-local px.
     * True partial-window HAL refresh (EinkPwInternalY) operates on the PW
     * hardware overlay layer, not the app View layer — confirmed not applicable
     * here (2026-06-12). Auto-EPD on the full view is the correct path.
     */
    fun region(view: View, left: Int, top: Int, right: Int, bottom: Int) = view.invalidate()

    companion object {
        private const val FULL_EVERY = 6
    }
}

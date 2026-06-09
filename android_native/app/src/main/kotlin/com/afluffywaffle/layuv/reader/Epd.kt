package com.afluffywaffle.layuv.reader

import android.util.Log
import android.view.View
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode

/**
 * The reader OWNS every EPD waveform — there is no framework doing it for us
 * (that is the whole point of the native port). This wraps the Onyx
 * [EpdController] with the small policy the reader needs:
 *
 *  - [pageTurn]  — GU (clean greyscale) for most turns, a full GC every
 *                  [FULL_EVERY] turns to flush accumulated e-ink ghosting.
 *  - [fullClear] — GC now (open a book, change columns, restore position).
 *  - [region]    — DU (fast 1-bit) on just the changed rect (selection drag).
 *
 * Every call falls back to a plain [View.invalidate] when the Onyx native layer
 * is absent (emulator, non-Onyx device) so the reader still renders for desktop
 * debugging. Waveform availability per firmware is a device spike to confirm on
 * the Nomad/Manta — the tag below is grep-friendly in `adb logcat`.
 */
class Epd {
    private var turnsSinceFullClear = 0

    fun pageTurn(view: View) {
        turnsSinceFullClear++
        if (turnsSinceFullClear >= FULL_EVERY) {
            turnsSinceFullClear = 0
            apply(view, UpdateMode.GC)
        } else {
            apply(view, UpdateMode.GU)
        }
    }

    fun fullClear(view: View) {
        turnsSinceFullClear = 0
        apply(view, UpdateMode.GC)
    }

    /** DU waveform over the full view — used when drawing or clearing a selection. */
    fun selection(view: View) = apply(view, UpdateMode.DU)

    /** Partial refresh of one rect (selection feedback). Coordinates are view-local px. */
    fun region(view: View, left: Int, top: Int, right: Int, bottom: Int) {
        try {
            EpdController.invalidate(view, left, top, right, bottom, UpdateMode.DU)
        } catch (e: Throwable) {
            // No Onyx layer — repaint the whole view instead.
            view.invalidate()
        }
    }

    /**
     * Apply [mode] to the next redraw of [view]. applyTransientUpdate sets the
     * waveform for the upcoming update only, then invalidate() does the redraw —
     * one refresh, correct waveform. If the Onyx layer throws (emulator), the
     * invalidate still happens so onDraw runs.
     */
    private fun apply(view: View, mode: UpdateMode) {
        try {
            EpdController.applyTransientUpdate(mode)
        } catch (e: Throwable) {
            if (!warnedNoEpd) {
                Log.i(TAG, "EpdController unavailable; falling back to plain invalidate (${e.javaClass.simpleName})")
                warnedNoEpd = true
            }
        }
        view.invalidate()
    }

    companion object {
        const val TAG = "LeamhEpd"
        private const val FULL_EVERY = 6
        private var warnedNoEpd = false
    }
}

package com.afluffywaffle.layuv.reader

import android.content.Context
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.LinearLayout

/**
 * Shared View-builder helpers factored out of the per-Activity duplicates
 * (P4 code-size pass). These are extension functions on [Context], so call sites
 * stay exactly `dp(8f)` / `hDivider()` — only the local copies are removed, and
 * being in the same package they resolve with no extra import.
 *
 * Only the *identical* variants are unified here. A few outliers deliberately keep
 * their own private copy because they differ in behaviour; a member function
 * shadows these extensions, so those files are unaffected:
 *   - `ReaderActivity.dp` uses `roundToInt()` (different px rounding)
 *   - `PageJumpOverlay.dp` returns Float
 *   - `AnnotationPopup` / `LeamhDialog` resolve their Context via a held field
 */

/** dp → px (truncated): the per-Activity `ReaderTheme.dp(this, v).toInt()`. */
fun Context.dp(v: Float): Int = ReaderTheme.dp(this, v).toInt()

/** Hairline horizontal divider (INK_12); the caller's LayoutParams set its height. */
fun Context.hDivider(): View = View(this).apply { setBackgroundColor(ReaderTheme.INK_12) }

/** 1px full-width row divider (FILL_08 == 0x14000000). */
fun Context.rowDivider(): View = View(this).apply {
    setBackgroundColor(ReaderTheme.FILL_08)
    layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(1f))
}

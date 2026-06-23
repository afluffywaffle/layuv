package com.afluffywaffle.layuv.reader

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

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

/** Themed text button — bold for primary/nav labels (e-ink legibility), else regular. */
fun Context.textButton(label: String, bold: Boolean = false, onTap: () -> Unit): TextView =
    TextView(this).apply {
        text = label
        typeface = if (bold) ReaderTheme.bodyBold(this@textButton) else ReaderTheme.body(this@textButton)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(ReaderTheme.INK_87)
        gravity = Gravity.CENTER
        setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
        minimumHeight = dp(48f)
        setOnTouchListener(PenTapListener(this@textButton, onTap = onTap))
    }

/** Pill button — filled (INK_87 bg, paper text) or outlined (FILL_04 bg, INK_87 text). */
fun Context.pillButton(label: String, filled: Boolean, onTap: () -> Unit): TextView =
    TextView(this).apply {
        text = label
        typeface = ReaderTheme.bodyBold(this@pillButton)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(if (filled) ReaderTheme.PAPER else ReaderTheme.INK_87)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = ReaderTheme.dp(this@pillButton, ReaderTheme.RADIUS_BTN)
            setColor(if (filled) ReaderTheme.INK_87 else ReaderTheme.FILL_04)
            setStroke(dp(1f), ReaderTheme.INK_87)
        }
        setPadding(dp(20f), dp(10f), dp(20f), dp(10f))
        minimumHeight = dp(48f)
        setOnTouchListener(PenTapListener(this@pillButton, onTap = onTap))
    }

/** Small bold inline action label (Reply / Edit / Delete style). */
fun Context.smallAction(label: String, onTap: () -> Unit): TextView =
    TextView(this).apply {
        text = label
        typeface = ReaderTheme.bodyBold(this@smallAction)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(ReaderTheme.INK_87)
        gravity = Gravity.CENTER
        setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
        minimumHeight = dp(48f)
        setOnTouchListener(PenTapListener(this@smallAction, onTap = onTap))
    }

/** 48dp themed icon button (paper fill, INK_26 border). */
fun Context.iconButton(iconRes: Int, contentDesc: String, onTap: () -> Unit): ImageView =
    ImageView(this).apply {
        setImageResource(iconRes)
        setColorFilter(ReaderTheme.INK_54)
        contentDescription = contentDesc
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = ReaderTheme.dp(this@iconButton, ReaderTheme.RADIUS_BTN)
            setColor(ReaderTheme.FILL_04)
            setStroke(dp(1f), ReaderTheme.INK_26)
        }
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
        minimumWidth = dp(48f)
        minimumHeight = dp(48f)
        setOnTouchListener(PenTapListener(this@iconButton, onTap = onTap))
    }

/** Chip/segment background — selected = clear fill + bold INK_87 border; else FILL_06 + INK_26. */
fun Context.chipBackground(selected: Boolean): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = ReaderTheme.dp(this@chipBackground, ReaderTheme.RADIUS_BTN)
    setColor(if (selected) 0 else ReaderTheme.FILL_06)
    setStroke(dp(if (selected) 2f else 1f), if (selected) ReaderTheme.INK_87 else ReaderTheme.INK_26)
}

/** Paper popup/card background with a hairline INK_26 border. */
fun Context.popupBackground(): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = ReaderTheme.dp(this@popupBackground, ReaderTheme.RADIUS_BTN)
    setColor(ReaderTheme.PAPER)
    setStroke(dp(1f), ReaderTheme.INK_26)
}

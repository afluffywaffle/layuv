package com.afluffywaffle.layuv.reader

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.afluffywaffle.layuv.R

/**
 * Small reader-settings popup (paper card, no animation — e-ink safe). Each row
 * shows a setting and its current value; tapping the row CYCLES the value and
 * applies it live. Replaces the old full-screen settings page.
 *
 *   Columns    — 1 column  ⇄ 2 columns
 *   Page turn  — Both sides → Left only → Right only → …
 */
class SettingsPopup(private val activity: Activity) {

    private var popup: PopupWindow? = null

    fun show(
        anchor: View,
        columns: Int,
        navSide: String,
        onColumns: (Int) -> Unit,
        onNavSide: (String) -> Unit,
    ) {
        dismiss()

        var cols = columns
        var side = navSide

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.popup_bg)
            val p = dp(4f)
            setPadding(p, p, p, p)
        }

        card.addView(settingRow("Columns", columnsLabel(cols)) { value ->
            cols = if (cols >= 2) 1 else 2
            value.text = columnsLabel(cols)
            onColumns(cols)
        })
        card.addView(divider())
        card.addView(settingRow("Page turn", navLabel(side)) { value ->
            side = when (side) {
                "both" -> "left"
                "left" -> "right"
                else -> "both"
            }
            value.text = navLabel(side)
            onNavSide(side)
        })

        // Fixed width so each row can right-align its value (a weighted child
        // measures to 0 under an UNSPECIFIED width spec — the value would vanish).
        val w = dp(300f)
        card.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val h = card.measuredHeight

        val pw = PopupWindow(card, w, WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ColorDrawable(0x00000000))
            isOutsideTouchable = true
            setOnDismissListener { popup = null }
        }
        popup = pw

        // Anchor the card ABOVE the trigger button (it lives in the bottom toolbar).
        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        val x = loc[0].coerceAtMost(activity.resources.displayMetrics.widthPixels - w - dp(8f))
        val y = (loc[1] - h - dp(8f)).coerceAtLeast(dp(8f))
        pw.showAtLocation(anchor, Gravity.TOP or Gravity.START, x, y)
    }

    fun dismiss() {
        popup?.dismiss()
        popup = null
    }

    private fun settingRow(label: String, value: String, onTap: (TextView) -> Unit): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56f)
            val h = dp(16f)
            val v = dp(8f)
            setPadding(h, v, h, v)
            isClickable = true
        }
        row.addView(TextView(activity).apply {
            text = label
            typeface = ReaderTheme.chrome(activity)
            setTextColor(ReaderTheme.INK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(24f) })
        val valueView = TextView(activity).apply {
            text = value
            typeface = ReaderTheme.body(activity)
            setTextColor(ReaderTheme.INK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.END
        }
        row.addView(valueView, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.setOnClickListener { onTap(valueView) }
        return row
    }

    private fun divider(): View = View(activity).apply {
        setBackgroundColor(0x1A000000)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1f))
    }

    private fun columnsLabel(columns: Int): String = if (columns >= 2) "2 columns" else "1 column"

    private fun navLabel(side: String): String = when (side) {
        "left" -> "Left side only"
        "right" -> "Right side only"
        else -> "Both sides"
    }

    private fun dp(v: Float): Int = ReaderTheme.dp(activity, v).toInt()
}

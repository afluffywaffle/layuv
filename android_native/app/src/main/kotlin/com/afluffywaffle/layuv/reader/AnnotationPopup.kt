package com.afluffywaffle.layuv.reader

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.PopupWindow
import com.afluffywaffle.layuv.R
import com.afluffywaffle.layuv.docx.model.AnnotationTool

/**
 * Six-tool annotation popup (highlight, underline, double underline,
 * strikethrough, bookmark, comment) — mirrors the Flutter AnnotationToolbar.
 * Paper bg + black border, no shadow, no animation (e-ink safe).
 * Each button is a [ToolIconView] drawn with Canvas (no text labels).
 */
class AnnotationPopup(private val activity: Activity) {

    private var popup: PopupWindow? = null

    private val tools = listOf(
        AnnotationTool.highlight,
        AnnotationTool.underline,
        AnnotationTool.doubleUnderline,
        AnnotationTool.strikethrough,
        AnnotationTool.bookmark,
        AnnotationTool.comment,
    )

    /**
     * Show the popup above [anchorX, anchorY] (view-relative coordinates).
     * [onDismiss] is called if the popup is dismissed without a tool being chosen
     * (e.g. outside tap). [onTool] is called with the chosen tool.
     */
    fun show(
        anchor: View,
        anchorX: Int,
        anchorY: Int,
        onDismiss: () -> Unit,
        onTool: (AnnotationTool) -> Unit,
    ) {
        dismiss()

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.popup_bg)
        }

        val btnSize = dp(64f)
        var toolChosen = false

        tools.forEachIndexed { i, tool ->
            if (i > 0) {
                row.addView(View(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(1f), btnSize).apply {
                        setMargins(0, dp(12f), 0, dp(12f))
                    }
                    setBackgroundColor(0x26000000)
                })
            }
            row.addView(ToolIconView(activity, tool).apply {
                layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    toolChosen = true
                    dismiss()
                    onTool(tool)
                }
            })
        }

        row.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val popupW = row.measuredWidth
        val popupH = row.measuredHeight

        val pw = PopupWindow(row, WRAP_CONTENT, WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ColorDrawable(0x00000000))
            isOutsideTouchable = true
            setOnDismissListener {
                popup = null
                if (!toolChosen) onDismiss()
            }
        }
        popup = pw

        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)

        val x = (loc[0] + anchorX - popupW / 2).coerceAtLeast(dp(8f))
        val y = (loc[1] + anchorY - popupH - dp(12f)).coerceAtLeast(dp(8f))

        pw.showAtLocation(anchor, Gravity.TOP or Gravity.START, x, y)
    }

    fun dismiss() {
        popup?.dismiss()
        popup = null
    }

    private fun dp(v: Float): Int = ReaderTheme.dp(activity, v).toInt()
}

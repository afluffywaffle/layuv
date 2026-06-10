package com.afluffywaffle.layuv.reader

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
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
     * [onTool] is called with the chosen tool. When [onDelete] is non-null a
     * "Delete annotation" row is added below the tool strip (edit mode).
     * When [note] is non-null the note text is shown above the tool strip (read-only).
     * The popup is non-focusable and not outside-touchable so touches pass
     * through to the [ReaderView] underneath.
     */
    fun show(
        anchor: View,
        anchorX: Int,
        anchorY: Int,
        onTool: (AnnotationTool) -> Unit,
        onDelete: (() -> Unit)? = null,
        note: String? = null,
    ) {
        dismiss()

        val btnSize = dp(64f)
        val toolStripW = 6 * btnSize + 5 * dp(1f)

        val toolRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        tools.forEachIndexed { i, tool ->
            if (i > 0) {
                toolRow.addView(View(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(1f), btnSize).apply {
                        setMargins(0, dp(12f), 0, dp(12f))
                    }
                    setBackgroundColor(0x26000000)
                })
            }
            toolRow.addView(ToolIconView(activity, tool).apply {
                layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
                isClickable = true
                isFocusable = true
                setOnClickListener { dismiss(); onTool(tool) }
            })
        }

        val popupContent: View = if (note == null && onDelete == null) {
            toolRow.apply { setBackgroundResource(R.drawable.popup_bg) }
        } else {
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.popup_bg)
                if (note != null) {
                    addView(TextView(activity).apply {
                        text = note
                        typeface = ReaderTheme.body(activity)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                        setTextColor(ReaderTheme.INK)
                        setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
                        layoutParams = LinearLayout.LayoutParams(toolStripW, WRAP_CONTENT)
                    })
                    addView(View(activity).apply {
                        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(1f))
                        setBackgroundColor(0x26000000)
                    })
                }
                addView(toolRow)
                if (onDelete != null) {
                    addView(View(activity).apply {
                        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(1f))
                        setBackgroundColor(0x26000000)
                    })
                    addView(Button(activity).apply {
                        text = "Delete annotation"
                        isAllCaps = false
                        typeface = ReaderTheme.body(activity)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                        setTextColor(ReaderTheme.INK)
                        setBackgroundColor(0)
                        minHeight = dp(56f)
                        minimumHeight = dp(56f)
                        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                        setOnClickListener { dismiss(); onDelete() }
                    })
                }
            }
        }

        popupContent.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val popupW = popupContent.measuredWidth
        val popupH = popupContent.measuredHeight

        val pw = PopupWindow(popupContent, WRAP_CONTENT, WRAP_CONTENT, false).apply {
            setBackgroundDrawable(ColorDrawable(0x00000000))
            isOutsideTouchable = false
            setOnDismissListener { popup = null }
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

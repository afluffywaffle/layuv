package com.afluffywaffle.layuv.reader

import android.app.Dialog
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.afluffywaffle.layuv.R

/**
 * Themed confirmation dialogs: paper background (#F5F0E8), Literata body text,
 * Source Sans 3 Bold buttons. Replaces stock AlertDialog throughout the native
 * reader so every destructive action looks consistent with the Flutter app's
 * visual style.
 *
 * Use [confirmDelete] for annotation / note deletion — it checks and saves a
 * per-document "don't ask again" pref so the dialog can be skipped on repeat.
 */
object LeamhDialog {

    /**
     * Delete-confirm dialog with a "Don't ask again for this document" checkbox.
     * [skipPrefKey] format: `"delete_confirm_skip:<absoluteFilePath>"`.
     * If the pref is already set, calls [onConfirm] immediately without showing.
     */
    /**
     * [skipPrefKey] null = always show without the "don't ask again" checkbox
     * (used for bulk multi-select deletes where the count already communicates scope).
     */
    fun confirmDelete(
        context: Context,
        message: String,
        skipPrefKey: String?,
        onConfirm: () -> Unit,
    ) {
        val prefs = context.getSharedPreferences("leamh", Context.MODE_PRIVATE)
        if (skipPrefKey != null && prefs.getBoolean(skipPrefKey, false)) {
            onConfirm()
            return
        }

        var dontAskChecked = false

        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setElevation(0f)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.picker_bg)
            val p = dp(context, 20f)
            setPadding(p, p, p, dp(context, 12f))
        }

        root.addView(TextView(context).apply {
            text = message
            typeface = ReaderTheme.body(context)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(ReaderTheme.INK_87)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.bottomMargin = dp(context, 16f) }
        })

        // "Don't ask again" checkbox row
        val checkRow = TextView(context).apply {
            text = "Don't ask again for this document"
            typeface = ReaderTheme.body(context)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(ReaderTheme.INK_54)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(context, 8f), 0, dp(context, 16f))
            compoundDrawablePadding = dp(context, 8f)
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        fun refreshCheckbox() {
            val res = if (dontAskChecked) R.drawable.ic_check_box else R.drawable.ic_check_box_blank
            val d = context.getDrawable(res)!!.mutate()
            d.setTint(ReaderTheme.INK_54)
            val sz = dp(context, 20f)
            d.setBounds(0, 0, sz, sz)
            checkRow.setCompoundDrawables(d, null, null, null)
        }
        refreshCheckbox()
        checkRow.setOnTouchListener(PenTapListener(context) {
            dontAskChecked = !dontAskChecked
            refreshCheckbox()
        })
        if (skipPrefKey != null) root.addView(checkRow)

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        btnRow.addView(labelButton(context, "Cancel", ReaderTheme.INK_45) {
            dialog.dismiss()
        })
        btnRow.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(context, 4f), 1)
        })
        btnRow.addView(labelButton(context, "Delete", ReaderTheme.INK_87) {
            if (dontAskChecked && skipPrefKey != null) prefs.edit().putBoolean(skipPrefKey, true).apply()
            dialog.dismiss()
            onConfirm()
        })
        root.addView(btnRow)

        dialog.setContentView(root)
        dialog.window?.setLayout(dp(context, 288f), WindowManager.LayoutParams.WRAP_CONTENT)
        dialog.show()
    }

    /**
     * Generic two-button confirmation dialog — paper background, Literata body text,
     * PenTapListener buttons. Use this anywhere a destructive or irreversible action
     * needs a confirm/cancel step without the "don't ask again" logic of [confirmDelete].
     */
    fun confirm(
        context: Context,
        message: String,
        positiveLabel: String = "OK",
        negativeLabel: String = "Cancel",
        onConfirm: () -> Unit,
    ) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setElevation(0f)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.picker_bg)
            val p = dp(context, 20f)
            setPadding(p, p, p, dp(context, 12f))
        }

        root.addView(TextView(context).apply {
            text = message
            typeface = ReaderTheme.body(context)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(ReaderTheme.INK_87)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.bottomMargin = dp(context, 16f) }
        })

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        btnRow.addView(labelButton(context, negativeLabel, ReaderTheme.INK_45) { dialog.dismiss() })
        btnRow.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(context, 4f), 1)
        })
        btnRow.addView(labelButton(context, positiveLabel, ReaderTheme.INK_87) {
            dialog.dismiss()
            onConfirm()
        })
        root.addView(btnRow)

        dialog.setContentView(root)
        dialog.window?.setLayout(dp(context, 288f), WindowManager.LayoutParams.WRAP_CONTENT)
        dialog.show()
    }

    /**
     * "Go to page" dialog. Shows a number input pre-filled with [currentPage] (1-based).
     * [onConfirm] is called with a 0-based page index.
     */
    fun goToPage(context: Context, currentPage: Int, pageCount: Int, onConfirm: (Int) -> Unit) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setElevation(0f)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.picker_bg)
            val p = dp(context, 20f)
            setPadding(p, p, p, dp(context, 12f))
        }

        root.addView(TextView(context).apply {
            text = "Go to page (1 – $pageCount)"
            typeface = ReaderTheme.body(context)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(ReaderTheme.INK_87)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.bottomMargin = dp(context, 12f) }
        })

        val input = EditText(context).apply {
            typeface = ReaderTheme.body(context)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(ReaderTheme.INK_87)
            setHighlightColor(android.graphics.Color.argb(60, 0, 0, 0)) // e-ink-safe selection colour
            inputType = InputType.TYPE_CLASS_NUMBER
            imeOptions = EditorInfo.IME_ACTION_GO
            gravity = Gravity.CENTER
            setText(currentPage.toString())
            selectAll()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.bottomMargin = dp(context, 16f) }
        }
        root.addView(input)

        fun commit() {
            val n = input.text.toString().toIntOrNull() ?: return
            val pageIndex = (n - 1).coerceIn(0, pageCount - 1)
            dialog.dismiss()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(input.windowToken, 0)
            onConfirm(pageIndex)
        }

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { commit(); true } else false
        }

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        btnRow.addView(labelButton(context, "Cancel", ReaderTheme.INK_45) { dialog.dismiss() })
        btnRow.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(context, 4f), 1)
        })
        btnRow.addView(labelButton(context, "Go", ReaderTheme.INK_87) { commit() })
        root.addView(btnRow)

        dialog.setContentView(root)
        dialog.window?.setLayout(dp(context, 288f), WindowManager.LayoutParams.WRAP_CONTENT)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        input.requestFocus()
    }

    /**
     * Single-button info dialog with a **scrollable** body — for "learn more" /
     * required-acknowledgment popups. The body scrolls within ~60% of the screen so a
     * long explanation can't push the button off-screen. [onAccept] runs after dismiss
     * (default no-op for plain info; pass a callback to treat the tap as an acceptance).
     */
    fun info(
        context: Context,
        message: String,
        buttonLabel: String = "Got it",
        onAccept: () -> Unit = {},
    ) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setElevation(0f)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // Bolder, attention-drawing frame — these are the privacy/security topics.
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = ReaderTheme.dp(context, ReaderTheme.RADIUS_CARD)
                setColor(ReaderTheme.PAPER)
                setStroke(ReaderTheme.dp(context, 2.5f).toInt(), ReaderTheme.INK_54)
            }
            val p = dp(context, 22f)
            setPadding(p, p, p, dp(context, 12f))
        }

        val body = TextView(context).apply {
            text = message
            typeface = ReaderTheme.body(context)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(ReaderTheme.INK_87)
            setLineSpacing(0f, 1.3f)
        }
        val maxH = (context.resources.displayMetrics.heightPixels * 0.82f).toInt()
        val scroll = object : ScrollView(context) {
            override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
            }
        }.apply { addView(body) }
        root.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .also { it.bottomMargin = dp(context, 12f) },
        )

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        btnRow.addView(labelButton(context, buttonLabel, ReaderTheme.INK_87) {
            dialog.dismiss()
            onAccept()
        })
        root.addView(btnRow)

        dialog.setContentView(root)
        val maxW = ReaderTheme.dp(context, 640f).toInt()
        val w = minOf((context.resources.displayMetrics.widthPixels * 0.82f).toInt(), maxW)
        dialog.window?.setLayout(w, WindowManager.LayoutParams.WRAP_CONTENT)
        dialog.show()
    }

    private fun labelButton(context: Context, label: String, color: Int, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            typeface = ReaderTheme.chromeBold(context)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(color)
            val hPad = dp(context, 12f)
            val vPad = dp(context, 14f)
            setPadding(hPad, vPad, hPad, vPad)
            isClickable = true
            isFocusable = true
            setOnTouchListener(PenTapListener(context, onTap = onClick))
        }

    private fun dp(context: Context, v: Float): Int = ReaderTheme.dp(context, v).toInt()
}

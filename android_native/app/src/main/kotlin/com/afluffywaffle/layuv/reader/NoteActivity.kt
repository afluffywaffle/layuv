package com.afluffywaffle.layuv.reader

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Full-screen text-entry screen for the "comment" annotation tool. */
class NoteActivity : Activity() {

    private lateinit var editText: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val existing = intent.getStringExtra(EXTRA_NOTE)
        setContentView(buildUi(existing))
        if (!existing.isNullOrEmpty()) {
            editText.setText(existing)
            editText.setSelection(existing.length)
        }
    }

    private fun buildUi(existing: String?): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ReaderTheme.PAPER)
            setPadding(dp(20f), dp(24f), dp(20f), dp(16f))
        }

        root.addView(TextView(this).apply {
            text = if (existing.isNullOrEmpty()) "Add note" else "Edit note"
            typeface = Typeface.create(ReaderTheme.body(context), Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(ReaderTheme.INK)
        })

        editText = EditText(this).apply {
            typeface = ReaderTheme.body(context)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(ReaderTheme.INK)
            setHintTextColor(0xFF9E9A92.toInt())
            hint = "Type your note here…"
            minLines = 6
            gravity = Gravity.TOP or Gravity.START
            setBackgroundColor(0)
            setPadding(0, dp(12f), 0, dp(12f))
        }
        root.addView(editText, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(16f), 0, 0)
        }
        buttons.addView(bodyButton("Cancel") {
            setResult(RESULT_CANCELED)
            finish()
        })
        buttons.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(16f), 1)
        })
        buttons.addView(bodyButton("Save") {
            val note = editText.text.toString().trim()
            setResult(RESULT_OK, Intent().putExtra(EXTRA_NOTE, note))
            finish()
        })
        root.addView(buttons, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return root
    }

    private fun bodyButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        typeface = ReaderTheme.body(this@NoteActivity)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        setTextColor(ReaderTheme.INK)
        minHeight = dp(56f)
        minimumHeight = dp(56f)
        setOnClickListener { onClick() }
    }

    private fun dp(v: Float): Int = ReaderTheme.dp(this, v).toInt()

    companion object {
        const val EXTRA_NOTE = "note"
    }
}

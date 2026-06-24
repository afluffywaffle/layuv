package com.afluffywaffle.layuv.reader

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import com.afluffywaffle.layuv.R

/**
 * Read-only full-screen viewer for a single Ask AI reply (or rewrite preview),
 * reached from the panel's Expand button — the way a long comment expands out of
 * the annotations pane. Reader typography on warm paper, with the SAME side
 * screen-flip strip as the chat panel: ▲/▼ + a collapse (shrink) button, grouped
 * at the bottom of the user's chosen side, with a "⇆" handedness toggle that shares
 * the panel's `ai_flip_side` pref. No smooth scroll (e-ink safe). Text comes in via
 * Intent extras; chapters are small, so this stays well under the binder limit.
 */
class AiReplyActivity : Activity() {

    companion object {
        const val EXTRA_TITLE = "ai_reply_title"
        const val EXTRA_TEXT = "ai_reply_text"
        private const val KEY_FLIP_SIDE = "ai_flip_side"
    }

    private lateinit var scroll: ScrollView
    private lateinit var leftStrip: LinearLayout
    private lateinit var rightStrip: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ReaderTheme.seedBodyFont(this)

        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Reply"
        val body = intent.getStringExtra(EXTRA_TEXT) ?: ""

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ReaderTheme.PAPER)
        }

        // Top bar: title + handedness toggle (mirrors the panel header's ⇆).
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(6f), dp(8f), dp(6f))
        }
        bar.addView(TextView(this).apply {
            text = title
            typeface = ReaderTheme.chromeBold(this@AiReplyActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(ReaderTheme.INK_87)
        })
        bar.addView(Space(this), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        bar.addView(textButton("⇆", bold = true) { toggleSide() }.also {
            it.contentDescription = "Move the scroll buttons to the other side"
        })
        root.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(rowDivider())

        // Body: the reply flanked by side flip strips; only the chosen side shows.
        val text = TextView(this).apply {
            this.text = body
            typeface = ReaderTheme.body(this@AiReplyActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ReaderTheme.BODY_TEXT_SP)
            setTextColor(ReaderTheme.INK_87)
            setLineSpacing(0f, ReaderTheme.LINE_SPACING_MULT)
            setPadding(dp(20f), dp(16f), dp(20f), dp(40f))
            setTextIsSelectable(true)
        }
        scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(text)
        }
        leftStrip = buildFlipStrip()
        rightStrip = buildFlipStrip()
        val bodyRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bodyRow.addView(leftStrip)
        bodyRow.addView(scroll, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        bodyRow.addView(rightStrip)
        root.addView(bodyRow, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        applySide()

        setContentView(root)
    }

    /** ▲/▼ flip + a collapse (shrink) button, grouped at the bottom of the chosen side. */
    private fun buildFlipStrip(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        setPadding(dp(2f), 0, dp(2f), dp(8f))
        addView(flipButton("▲") { flip(-1) })
        addView(flipButton("▼") { flip(+1) })
        addView(collapseButton())
    }

    /** Inward-arrows icon — the reverse of the panel's expand; shrinks back to the chat. */
    private fun collapseButton(): View = ImageView(this).apply {
        setImageResource(R.drawable.ic_collapse)
        setColorFilter(ReaderTheme.INK_87)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        minimumWidth = dp(48f)
        minimumHeight = dp(48f)
        setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
        contentDescription = "Shrink back to the chat"
        setOnTouchListener(PenTapListener(this@AiReplyActivity, onTap = ::finish))
    }

    private fun prefs() = getSharedPreferences("leamh", Context.MODE_PRIVATE)

    private fun applySide() {
        val left = prefs().getString(KEY_FLIP_SIDE, "right") == "left"
        leftStrip.visibility = if (left) View.VISIBLE else View.GONE
        rightStrip.visibility = if (left) View.GONE else View.VISIBLE
    }

    private fun toggleSide() {
        val left = prefs().getString(KEY_FLIP_SIDE, "right") == "left"
        prefs().edit().putString(KEY_FLIP_SIDE, if (left) "right" else "left").apply()
        applySide()
    }

    /** Screen flip: jump one pane height per tap, then a single clean frame (no smooth scroll). */
    private fun flip(dir: Int) {
        val step = (scroll.height - dp(24f)).coerceAtLeast(dp(48f))
        scroll.scrollBy(0, dir * step)
        if (RattaEink.available(this)) RattaEink.sendOneFullFrame(this) else scroll.invalidate()
    }

    private fun flipButton(glyph: String, onTap: () -> Unit): View = TextView(this).apply {
        text = glyph
        typeface = ReaderTheme.chromeBold(this@AiReplyActivity)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(ReaderTheme.INK_87)
        gravity = Gravity.CENTER
        minimumWidth = dp(48f)
        minimumHeight = dp(48f)
        setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
        setOnTouchListener(PenTapListener(this@AiReplyActivity, onTap = onTap))
    }
}

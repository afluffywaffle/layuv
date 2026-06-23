package com.afluffywaffle.layuv.reader

import android.app.Activity
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView

/**
 * Read-only full-screen viewer for a single Ask AI reply (or rewrite preview),
 * reached from the panel's Expand button — the way a long comment expands out of
 * the annotations panel. Reader typography on warm paper, with the same discrete
 * screen-flip nav as the chat panel (no smooth scroll, e-ink safe). The text comes
 * in via Intent extras; chapters are small, so this stays well under the binder
 * transaction limit.
 */
class AiReplyActivity : Activity() {

    companion object {
        const val EXTRA_TITLE = "ai_reply_title"
        const val EXTRA_TEXT = "ai_reply_text"
    }

    private lateinit var scroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ReaderTheme.seedBodyFont(this)

        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Reply"
        val body = intent.getStringExtra(EXTRA_TEXT) ?: ""

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ReaderTheme.PAPER)
        }

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
        bar.addView(flipButton("▲") { flip(-1) })
        bar.addView(flipButton("▼") { flip(+1) })
        bar.addView(textButton("Close", bold = true) { finish() })
        root.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(rowDivider())

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
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        setContentView(root)
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

package com.afluffywaffle.layuv.reader

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import com.afluffywaffle.layuv.R

/**
 * Read-only full-screen viewer for a single Ask AI reply (or rewrite preview),
 * reached from the panel's Expand button. Reader typography on warm paper, navigated
 * with the SAME edge-nav bar the reader uses ([EdgeNavView]) — but on a single side,
 * carried over from the chat panel's handedness pref (`ai_flip_side`), with a "⇆"
 * toggle to switch it. Top of the rail = down a pane, bottom = up (matching the
 * reader's top=next/bottom=prev). A collapse (inward-arrows) button shrinks back to
 * the chat. No smooth scroll (e-ink safe).
 */
class AiReplyActivity : Activity() {

    companion object {
        const val EXTRA_TITLE = "ai_reply_title"
        const val EXTRA_TEXT = "ai_reply_text"
        /** Extra returned with RESULT_OK when the user types a follow-up reply here. */
        const val EXTRA_REPLY = "ai_reply_reply"
        /** Request code used by [AskAiPanel] to launch this activity for a result. */
        const val REQUEST_CODE = 1012
        private const val KEY_FLIP_SIDE = "ai_flip_side"
    }

    private lateinit var scroll: ScrollView
    private lateinit var bodyText: TextView
    private lateinit var edgeNav: EdgeNavView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ReaderTheme.seedBodyFont(this)

        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Reply"
        val body = intent.getStringExtra(EXTRA_TEXT) ?: ""

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ReaderTheme.PAPER)
        }

        // Top bar: title + handedness toggle + collapse (shrink) — mirrors the panel.
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
            it.contentDescription = "Move the nav rail to the other side"
        })
        bar.addView(collapseIcon())
        root.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(rowDivider())

        // Body: the reply, with a single-side reader-style edge-nav rail overlaid.
        bodyText = TextView(this).apply {
            text = body
            typeface = ReaderTheme.body(this@AiReplyActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ReaderTheme.BODY_TEXT_SP)
            setTextColor(ReaderTheme.INK_87)
            setLineSpacing(0f, ReaderTheme.LINE_SPACING_MULT)
            setTextIsSelectable(true)
        }
        scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(bodyText)
        }
        edgeNav = EdgeNavView(
            this,
            diagram = false,
            side = currentSide(),
            onNext = { flip(+1) }, // top of the rail = forward = down a pane
            onPrev = { flip(-1) }, // bottom = back = up a pane
        )
        val frame = FrameLayout(this)
        frame.addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        frame.addView(edgeNav, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(frame, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        applyNavSide()

        // Reply bar: lets the user type a follow-up without collapsing back to the chat panel.
        // On send, the activity finishes with RESULT_OK + EXTRA_REPLY so AskAiPanel submits it.
        root.addView(rowDivider())
        val replyInput = EditText(this).apply {
            typeface = ReaderTheme.body(this@AiReplyActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ReaderTheme.BODY_TEXT_SP)
            setTextColor(ReaderTheme.INK_87)
            setHintTextColor(0xFF9E9A92.toInt())
            setHighlightColor(Color.argb(60, 0, 0, 0))
            hint = "Reply…"
            minLines = 1
            maxLines = 4
            gravity = Gravity.TOP or Gravity.START
            background = popupBackground()
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            minimumHeight = dp(48f)
        }
        val replyButton = textButton("Reply", bold = true) {
            val text = replyInput.text.toString().trim()
            if (text.isEmpty()) return@textButton
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(replyInput.windowToken, 0)
            setResult(RESULT_OK, Intent().putExtra(EXTRA_REPLY, text))
            finish()
        }
        val replyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            addView(replyInput, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(replyButton, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                .also { it.leftMargin = dp(8f) })
        }
        root.addView(replyRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        setContentView(root)
    }

    private fun collapseIcon(): View = ImageView(this).apply {
        setImageResource(R.drawable.ic_collapse)
        setColorFilter(ReaderTheme.INK_87)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        minimumWidth = dp(48f)
        minimumHeight = dp(48f)
        setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
        contentDescription = "Shrink back to the chat"
        setOnTouchListener(PenTapListener(this@AiReplyActivity, onTap = ::finish))
    }

    private fun prefs() = getSharedPreferences("leamh", Context.MODE_PRIVATE)
    private fun currentSide(): String =
        if (prefs().getString(KEY_FLIP_SIDE, "right") == "left") "left" else "right"

    /** Place the nav rail on the chosen side and inset the text there so it clears the rail. */
    private fun applyNavSide() {
        val side = currentSide()
        edgeNav.setSide(side)
        val gutter = dp(EdgeNavView.NAV_STRIP_DP)
        val normal = dp(20f)
        bodyText.setPadding(
            if (side == "left") gutter else normal,
            dp(16f),
            if (side == "right") gutter else normal,
            dp(40f),
        )
    }

    private fun toggleSide() {
        val left = prefs().getString(KEY_FLIP_SIDE, "right") == "left"
        prefs().edit().putString(KEY_FLIP_SIDE, if (left) "right" else "left").apply()
        applyNavSide()
    }

    /** Screen flip: jump one pane height per tap, then a single clean frame (no smooth scroll). */
    private fun flip(dir: Int) {
        val step = (scroll.height - dp(24f)).coerceAtLeast(dp(48f))
        scroll.scrollBy(0, dir * step)
        if (RattaEink.available(this)) RattaEink.sendOneFullFrame(this) else scroll.invalidate()
    }
}

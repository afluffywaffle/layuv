package com.afluffywaffle.layuv.reader

import android.app.Activity
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.ActionMode
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView

/**
 * Shared text-selection styling for NoteActivity — the reader's dotted underline
 * plus a themed popup, used by BOTH the read-only entry-detail overlay
 * (SelectableBodyText) and the editable compose fields (ComposeEditText) so
 * selection looks the same on every surface, instead of Android's blue fill +
 * floating toolbar.
 *
 * Pure presentation: depends only on the [activity] (as a Context) and
 * [ReaderTheme] — holds no NoteActivity state. Extracted verbatim from
 * NoteActivity to keep that file focused on the note-editing flow.
 */
class NoteSelectionStyle(private val activity: Activity) {

    private val selDottedPaint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = ReaderTheme.INK
            strokeWidth = ReaderTheme.dp(activity, ReaderTheme.UNDERLINE_STROKE_DP)
            pathEffect = DashPathEffect(
                floatArrayOf(
                    ReaderTheme.dp(activity, ReaderTheme.UNDERLINE_DASH_ON_DP),
                    ReaderTheme.dp(activity, ReaderTheme.UNDERLINE_DASH_OFF_DP),
                ),
                0f,
            )
        }
    }

    private fun dp(v: Float): Int = ReaderTheme.dp(activity, v).toInt()

    /**
     * Draws the reader's dotted underline beneath [tv]'s current selection range —
     * call from [tv]'s onDraw. Accounts for the view's scroll (the editable compose
     * field can scroll vertically), so the read-only overlay (scroll always 0) is
     * just the special case scrollX/scrollY == 0.
     */
    fun drawSelectionUnderline(tv: TextView, canvas: Canvas, path: Path) {
        val l = tv.layout ?: return
        val lo = minOf(tv.selectionStart, tv.selectionEnd)
        val hi = maxOf(tv.selectionStart, tv.selectionEnd)
        if (lo < 0 || lo >= hi) return
        val underlineOffset = ReaderTheme.dp(activity, ReaderTheme.UNDERLINE_OFFSET_DP)
        canvas.save()
        canvas.translate(
            (tv.totalPaddingLeft - tv.scrollX).toFloat(),
            (tv.totalPaddingTop - tv.scrollY).toFloat(),
        )
        val firstLine = l.getLineForOffset(lo)
        val lastLine = l.getLineForOffset((hi - 1).coerceAtLeast(lo))
        for (line in firstLine..lastLine) {
            val ls = maxOf(lo, l.getLineStart(line))
            val le = minOf(hi, l.getLineEnd(line))
            if (ls >= le) continue
            var x0 = l.getPrimaryHorizontal(ls)
            var x1 = l.getPrimaryHorizontal(le)
            if (x1 <= x0) x1 = l.getLineRight(line)
            if (x1 < x0) { val t = x0; x0 = x1; x1 = t }
            val y = l.getLineBaseline(line).toFloat() + underlineOffset
            path.rewind()
            path.moveTo(x0, y)
            path.lineTo(x1, y)
            canvas.drawPath(path, selDottedPaint)
        }
        canvas.restore()
    }

    /**
     * Builds + shows a themed selection popup (paper card, INK_26 border, divided
     * chrome-bold buttons) positioned ~60dp above [tv]'s selection start. [actions]
     * are (label, handler) pairs. Returns the PopupWindow so the caller stores +
     * dismisses it.
     */
    fun showSelectionPopup(tv: TextView, actions: List<Pair<String, () -> Unit>>): PopupWindow {
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = ReaderTheme.dp(activity, ReaderTheme.RADIUS_BTN)
                setColor(ReaderTheme.PAPER)
                setStroke(dp(1f), ReaderTheme.INK_26)
            }
        }
        actions.forEachIndexed { i, (label, action) ->
            if (i > 0) content.addView(View(activity).apply {
                setBackgroundColor(ReaderTheme.INK_26)
                layoutParams = LinearLayout.LayoutParams(dp(1f), MATCH_PARENT).also {
                    it.topMargin = dp(10f); it.bottomMargin = dp(10f)
                }
            })
            content.addView(TextView(activity).apply {
                text = label
                typeface = ReaderTheme.chromeBold(activity)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(ReaderTheme.INK_87)
                gravity = Gravity.CENTER
                setPadding(dp(20f), dp(12f), dp(20f), dp(12f))
                minimumHeight = dp(48f)
                setOnTouchListener(PenTapListener(activity) { action() })
            })
        }
        val popup = PopupWindow(content, WRAP_CONTENT, WRAP_CONTENT, true).apply {
            elevation = ReaderTheme.dp(activity, 4f)
            isOutsideTouchable = true
            setBackgroundDrawable(null)
        }
        // Position the popup just above the selected text's first line.
        val l = tv.layout
        val screenLoc = IntArray(2).also { tv.getLocationInWindow(it) }
        val xScreen: Int
        val yScreen: Int
        if (l != null) {
            val anchorOff = minOf(tv.selectionStart, tv.selectionEnd).coerceAtLeast(0)
            val line = l.getLineForOffset(anchorOff)
            val lineTop = tv.totalPaddingTop + l.getLineTop(line) - tv.scrollY
            xScreen = (screenLoc[0] + tv.totalPaddingLeft +
                l.getPrimaryHorizontal(anchorOff).toInt() - tv.scrollX)
                .coerceIn(screenLoc[0], screenLoc[0] + tv.width - dp(140f))
            yScreen = screenLoc[1] + lineTop - dp(60f)
        } else {
            xScreen = screenLoc[0] + dp(16f)
            yScreen = screenLoc[1] - dp(60f)
        }
        popup.showAtLocation(tv, Gravity.NO_GRAVITY, xScreen, yScreen.coerceAtLeast(0))
        return popup
    }

    /** Clears the system floating ActionMode (Copy/Share/…) so our themed popup is
     *  the only selection toolbar. Returning true from onCreateActionMode lets the
     *  selection itself proceed (handles stay live); the empty menu hides the bar. */
    fun suppressingActionModeCallback() = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu) = true
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.clear(); return true
        }
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = false
        override fun onDestroyActionMode(mode: ActionMode) {}
    }
}

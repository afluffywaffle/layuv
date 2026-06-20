package com.afluffywaffle.layuv.reader

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.util.Log
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
 * Six-tool annotation strip + ••• overflow (copy, share). Mirrors the Flutter
 * AnnotationToolbar. Paper bg + black border, no shadow, no animation (e-ink safe).
 * Each tool button is a canvas-drawn icon at 64dp. The ••• button opens a small
 * card above it with Copy and Share as labelled text rows.
 *
 * Tool lock — two-step:
 *   1. Long-press a tool → a "locked" copy of that icon drops down below the strip.
 *   2. Tap the dropped-down icon → lock activates ([onLockTool] fires).
 * The confirm popup is dismissed by any other action (tap a tool, overflow, dismiss).
 * Second long-press on the same tool toggles the confirm off without locking.
 */
class AnnotationPopup(private val activity: Activity) {

    var onDismiss: (() -> Unit)? = null

    private var popup: PopupWindow? = null
    private var overflowPopup: PopupWindow? = null
    private var lockConfirmPopup: PopupWindow? = null
    private var pendingLockTool: AnnotationTool? = null
    private val renderer = ToolIconRenderer(activity)

    private val tools = listOf(
        AnnotationTool.highlight,
        AnnotationTool.underline,
        AnnotationTool.doubleUnderline,
        AnnotationTool.strikethrough,
        AnnotationTool.inkAnnotation,
        AnnotationTool.comment,
    )

    /**
     * Show the popup above [anchorX, anchorY] (view-relative coordinates).
     * [onTool] is called with the chosen tool. When [onDelete] is non-null a
     * "Delete annotation" row is added below the tool strip (edit mode).
     * When [note] is non-null the note text is shown above the tool strip, capped
     * to 5 lines with ellipsis. When [onReadNote] is non-null the note area is
     * tappable — tap opens the full note (NoteActivity) for reading/editing.
     * When [onCopy] or [onShare] are non-null a ••• overflow button appears at the
     * right of the strip; tapping it reveals Copy / Share as labelled text rows.
     *
     * Tool lock: pass [lockedTool] to highlight the currently locked tool with a
     * filled bar. [onLockTool] fires after the two-step confirm (long-press then tap
     * the dropped-down icon). Tapping the already-locked tool calls [onUnlock]
     * instead of [onTool]. Pass [onUnlock] without [onLockTool] (e.g. annotation-tap
     * popup) to allow unlocking but not re-locking.
     */
    fun show(
        anchor: View,
        anchorX: Int,
        anchorY: Int,
        onTool: (AnnotationTool) -> Unit,
        onDelete: (() -> Unit)? = null,
        note: String? = null,
        onReadNote: (() -> Unit)? = null,
        onShare: (() -> Unit)? = null,
        onCopy: (() -> Unit)? = null,
        lockedTool: AnnotationTool? = null,
        onLockTool: ((AnnotationTool) -> Unit)? = null,
        onUnlock: (() -> Unit)? = null,
    ) {
        dismiss()

        val btnSize = dp(64f)
        val hPad = dp(8f)
        val vPad = dp(8f)
        val hasOverflow = true // ••• always shows for outside-dismiss toggle
        val toolsBtnW = (tools.size + (if (hasOverflow) 1 else 0)) * btnSize
        // 1dp divider + 1 dismiss-X button appended after all tool/overflow buttons
        val toolStripW = toolsBtnW + dp(1f) + btnSize
        val popupW = toolStripW + hPad * 2

        val toolRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tools.forEach { tool ->
            // Comment and inkAnnotation open their own activities, so not lockable.
            val lockable = onLockTool != null &&
                tool != AnnotationTool.comment &&
                tool != AnnotationTool.inkAnnotation
            toolRow.addView(ToolIconView(activity, tool).apply {
                layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
                isClickable = true
                isFocusable = true
                isLongClickable = lockable
                showLockHint = lockable
                locked = (tool == lockedTool)
                setOnClickListener {
                    if (tool == lockedTool) { dismiss(); onUnlock?.invoke() }
                    else { dismiss(); onTool(tool) }
                }
                setOnLongClickListener {
                    if (lockable) {
                        if (pendingLockTool == tool && lockConfirmPopup != null) {
                            // Second long-press on the same tool — dismiss the picker.
                            lockConfirmPopup?.dismiss()
                            lockConfirmPopup = null
                            pendingLockTool = null
                        } else {
                            showLockPicker(
                                this, anchor, tool,
                                onApplyOnce = { dismiss(); onTool(tool) },
                                onLock = { dismiss(); onLockTool!!(tool) },
                            )
                        }
                        true
                    } else false
                }
            })
        }

        if (hasOverflow) {
            toolRow.addView(object : View(activity) {
                override fun onDraw(canvas: Canvas) =
                    renderer.drawOverflow(canvas, width / 2f, height / 2f, ReaderTheme.dp(activity, ReaderTheme.ICON_DP))
            }.apply {
                layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
                isClickable = true; isFocusable = true
                setOnClickListener {
                    if (overflowPopup != null) {
                        overflowPopup?.dismiss()
                        overflowPopup = null
                    } else {
                        showOverflow(this, anchor, btnSize, onCopy, onShare)
                    }
                }
            })
        }

        // Dismiss (X) button — always rightmost, separated by a 1dp divider
        toolRow.addView(View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(1f), dp(44f))
            setBackgroundColor(ReaderTheme.INK_12)
        })
        toolRow.addView(object : View(activity) {
            override fun onDraw(canvas: Canvas) =
                renderer.drawVecIcon(canvas, R.drawable.ic_close, width / 2f, height / 2f, ReaderTheme.dp(activity, ReaderTheme.ICON_DP))
        }.apply {
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
            isClickable = true; isFocusable = true
            setOnClickListener { dismiss() }
        })

        val popupContent: View = if (note == null && onDelete == null) {
            toolRow.apply {
                setBackgroundResource(R.drawable.toolbar_bg)
                setPadding(hPad, vPad, hPad, vPad)
            }
        } else {
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.toolbar_bg)
                if (note != null) {
                    addView(TextView(activity).apply {
                        text = note
                        typeface = ReaderTheme.body(activity)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                        setTextColor(ReaderTheme.INK_87)
                        maxLines = 5
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
                        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                        if (onReadNote != null) {
                            isClickable = true
                            isFocusable = true
                            setOnClickListener { dismiss(); onReadNote() }
                        }
                    })
                    addView(hDivider())
                }
                addView(toolRow.apply { setPadding(hPad, vPad, hPad, vPad) })
                if (onDelete != null) {
                    addView(hDivider())
                    addView(Button(activity).apply {
                        text = "Delete annotation"
                        isAllCaps = false
                        typeface = ReaderTheme.body(activity)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                        setTextColor(ReaderTheme.INK_87)
                        setBackgroundColor(0)
                        stateListAnimator = null
                        minHeight = dp(56f)
                        minimumHeight = dp(56f)
                        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                        setOnClickListener { dismiss(); onDelete() }
                    })
                }
            }
        }

        popupContent.measure(
            View.MeasureSpec.makeMeasureSpec(popupW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val popupH = popupContent.measuredHeight

        val pw = PopupWindow(popupContent, popupW, WRAP_CONTENT, false).apply {
            setBackgroundDrawable(ColorDrawable(0x00000000))
            isOutsideTouchable = (lockedTool != null) || isOutsideDismissEnabled()
            setOnDismissListener { popup = null }
        }
        popup = pw

        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)

        val x = (loc[0] + anchorX - popupW / 2).coerceAtLeast(dp(8f))
        val y = (loc[1] + anchorY - popupH - dp(12f)).coerceAtLeast(dp(8f))

        pw.showAtLocation(anchor, Gravity.TOP or Gravity.START, x, y)
    }

    /**
     * The action toolbar shown when an existing annotation is tapped: two 64dp
     * icon-only buttons (chat + delete) separated by a 1dp divider, matching the
     * style of the main annotation toolbar.
     */
    fun showActions(
        anchor: View,
        anchorX: Int,
        anchorY: Int,
        onComment: () -> Unit,
        onDelete: () -> Unit,
    ) {
        dismiss()

        val btnSize = dp(64f)
        val hPad = dp(8f)
        val vPad = dp(8f)
        // [chat] [1dp] [delete] [1dp] [X]
        val popupW = btnSize * 3 + dp(2f) + hPad * 2
        val iconExtent = ReaderTheme.dp(activity, ReaderTheme.ICON_DP)

        fun iconButton(iconRes: Int, label: String, onClick: () -> Unit): View =
            object : View(activity) {
                override fun onDraw(canvas: Canvas) =
                    renderer.drawVecIcon(canvas, iconRes, width / 2f, height / 2f, iconExtent)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
                isClickable = true
                isFocusable = true
                setOnTouchListener(PenTapListener(activity, tag = "ActionPopup/$label", onTap = onClick))
            }

        val pill = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.toolbar_bg)
            setPadding(hPad, vPad, hPad, vPad)
            addView(iconButton(R.drawable.ic_chat_outline, "Comment") { dismiss(); onComment() })
            addView(View(activity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(1f), dp(44f))
                setBackgroundColor(ReaderTheme.INK_12)
            })
            addView(iconButton(R.drawable.ic_delete_outline, "Delete") { dismiss(); onDelete() })
            addView(View(activity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(1f), dp(44f))
                setBackgroundColor(ReaderTheme.INK_12)
            })
            addView(iconButton(R.drawable.ic_close, "Close") { dismiss() })
        }

        pill.measure(
            View.MeasureSpec.makeMeasureSpec(popupW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val popupH = pill.measuredHeight

        val pw = PopupWindow(pill, popupW, WRAP_CONTENT, false).apply {
            setBackgroundDrawable(ColorDrawable(0x00000000))
            isOutsideTouchable = isOutsideDismissEnabled()
            setOnDismissListener { popup = null }
        }
        popup = pw

        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        val x = (loc[0] + anchorX - popupW / 2).coerceAtLeast(dp(8f))
        val y = (loc[1] + anchorY - popupH - dp(12f)).coerceAtLeast(dp(8f))
        Log.d("AnnotationPopup", "showActions: anchorX=$anchorX anchorY=$anchorY " +
            "anchorWin=(${loc[0]},${loc[1]}) popupW=$popupW popupH=$popupH finalX=$x finalY=$y")
        pw.showAtLocation(anchor, Gravity.TOP or Gravity.START, x, y)
    }

    /** Dismiss and fire [onDismiss] (e.g. explicit ✕ or tool-tap). */
    fun dismiss() {
        if (popup != null) onDismiss?.invoke()
        dismissQuiet()
    }

    /** Dismiss without firing [onDismiss] — use when a gesture is already in flight. */
    fun dismissQuiet() {
        lockConfirmPopup?.dismiss()
        lockConfirmPopup = null
        pendingLockTool = null
        overflowPopup?.dismiss()
        overflowPopup = null
        popup?.dismiss()
        popup = null
    }

    /**
     * The lock picker card above [toolBtn] — two 44dp rows ("Apply once" / "Lock
     * tool") on a paper card (radius 10, black12 border). Mirrors Flutter's
     * LockPickerOverlay. [onApplyOnce] applies the tool a single time; [onLock]
     * locks it. Any other action dismisses without locking.
     */
    private fun showLockPicker(
        toolBtn: View,
        anchor: View,
        tool: AnnotationTool,
        onApplyOnce: () -> Unit,
        onLock: () -> Unit,
    ) {
        lockConfirmPopup?.dismiss()
        lockConfirmPopup = null
        pendingLockTool = tool

        val cardW = dp(148f)
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.picker_bg)
            addView(pickerRow(tool, showLock = false, label = "Apply once") {
                lockConfirmPopup?.dismiss(); lockConfirmPopup = null; pendingLockTool = null
                onApplyOnce()
            })
            addView(hDivider())
            addView(pickerRow(tool, showLock = true, label = "Lock tool") {
                lockConfirmPopup?.dismiss(); lockConfirmPopup = null; pendingLockTool = null
                onLock()
            })
        }

        card.measure(
            View.MeasureSpec.makeMeasureSpec(cardW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val cardH = card.measuredHeight

        val lw = PopupWindow(card, cardW, WRAP_CONTENT, false).apply {
            setBackgroundDrawable(ColorDrawable(0x00000000))
            isOutsideTouchable = isOutsideDismissEnabled()
            setOnDismissListener { lockConfirmPopup = null; pendingLockTool = null }
        }
        lockConfirmPopup = lw

        val (winX, winY) = screenToAnchorWin(toolBtn, anchor)
        // Centre the card on the tool button and show it above (12dp gap, Flutter parity).
        val x = (winX + toolBtn.width / 2 - cardW / 2).coerceAtLeast(dp(8f))
        val y = (winY - cardH - dp(12f)).coerceAtLeast(dp(8f))
        lw.showAtLocation(anchor, Gravity.TOP or Gravity.START, x, y)
    }

    /** One lock-picker row: a 24dp tool-icon slot (18dp glyph, + lock badge) + label. */
    private fun pickerRow(
        tool: AnnotationTool,
        showLock: Boolean,
        label: String,
        onClick: () -> Unit,
    ): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14f), 0, dp(14f), 0)
        isClickable = true
        isFocusable = true
        setOnTouchListener(PenTapListener(activity, onTap = onClick))
        val iconExtent = ReaderTheme.dp(activity, 18f)
        addView(object : View(activity) {
            override fun onDraw(canvas: Canvas) {
                val cx = width / 2f
                val cy = height / 2f
                renderer.draw(canvas, tool, cx, cy, iconExtent)
                if (showLock) {
                    val half = iconExtent / 2f
                    val inset = ReaderTheme.dp(activity, 1f)
                    renderer.drawLockBadge(canvas, cx + half - inset, cy + half - inset, ReaderTheme.dp(activity, 5f))
                }
            }
        }, LinearLayout.LayoutParams(dp(24f), dp(24f)))
        addView(TextView(activity).apply {
            text = label
            typeface = ReaderTheme.body(activity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(ReaderTheme.INK_87)
            setPadding(dp(10f), 0, 0, 0)
        })
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(44f))
    }

    private fun showOverflow(
        threeDotsBtn: View,
        anchor: View,
        btnSize: Int,
        onCopy: (() -> Unit)?,
        onShare: (() -> Unit)?,
    ) {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.picker_bg)
        }
        var addedOne = false
        if (onCopy != null) {
            card.addView(overflowRow("Copy") { dismiss(); onCopy() })
            addedOne = true
        }
        if (onShare != null) {
            if (addedOne) card.addView(hDivider())
            card.addView(overflowRow("Share") { dismiss(); onShare() })
            addedOne = true
        }
        val outsideDismiss = isOutsideDismissEnabled()
        if (addedOne) card.addView(hDivider())
        card.addView(overflowRow(if (outsideDismiss) "Tap outside: on" else "Tap outside: off") {
            setOutsideDismissEnabled(!outsideDismiss)
            dismiss()
        })

        val w = dp(200f)
        card.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val h = card.measuredHeight

        val ow = PopupWindow(card, w, WRAP_CONTENT, false).apply {
            setBackgroundDrawable(ColorDrawable(0x00000000))
            isOutsideTouchable = false
            setOnDismissListener { overflowPopup = null }
        }
        overflowPopup = ow

        val (winX, winY) = screenToAnchorWin(threeDotsBtn, anchor)
        // Right-align the overflow card with the ••• button and show it above.
        val x = (winX + btnSize - w).coerceAtLeast(dp(8f))
        val y = (winY - h - dp(4f)).coerceAtLeast(dp(8f))
        ow.showAtLocation(anchor, Gravity.TOP or Gravity.START, x, y)
    }

    /**
     * Translates [view]'s absolute screen position into the coordinate space of
     * [anchor]'s window — what [PopupWindow.showAtLocation] expects. Necessary
     * for views inside a PopupWindow, whose [View.getLocationInWindow] returns
     * coords relative to the popup's own window rather than the host window.
     */
    private fun screenToAnchorWin(view: View, anchor: View): Pair<Int, Int> {
        val vScreen = IntArray(2); view.getLocationOnScreen(vScreen)
        val aScreen = IntArray(2); anchor.getLocationOnScreen(aScreen)
        val aWin = IntArray(2); anchor.getLocationInWindow(aWin)
        return (vScreen[0] - aScreen[0] + aWin[0]) to (vScreen[1] - aScreen[1] + aWin[1])
    }

    private fun overflowRow(label: String, onClick: () -> Unit): View = Button(activity).apply {
        text = label
        isAllCaps = false
        typeface = ReaderTheme.body(activity)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(ReaderTheme.INK_87)
        setBackgroundColor(0)
        minHeight = dp(56f)
        minimumHeight = dp(56f)
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setPadding(dp(16f), 0, dp(16f), 0)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        setOnClickListener { onClick() }
    }

    private fun hDivider(): View = View(activity).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(1f))
        setBackgroundColor(ReaderTheme.INK_12)
    }

    private fun isOutsideDismissEnabled(): Boolean =
        activity.getSharedPreferences("leamh", Context.MODE_PRIVATE)
            .getBoolean("pref_outside_dismiss", false)

    private fun setOutsideDismissEnabled(enabled: Boolean) {
        activity.getSharedPreferences("leamh", Context.MODE_PRIVATE)
            .edit().putBoolean("pref_outside_dismiss", enabled).apply()
    }

    private fun dp(v: Float): Int = ReaderTheme.dp(activity, v).toInt()

}

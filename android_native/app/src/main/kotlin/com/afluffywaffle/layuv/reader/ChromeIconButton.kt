package com.afluffywaffle.layuv.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import com.afluffywaffle.layuv.R
import com.afluffywaffle.layuv.docx.model.AnnotationTool

/**
 * A square chrome icon button: a single Material vector drawable centred at the
 * standard 28dp icon extent, with Flutter's pill-button padding (h16/v12 on
 * e-ink) baked into the measured size so the touch target is ~60×52dp. Tinted
 * black87 normally, black26 when [dimmed]. Taps go through [PenTapListener] so the
 * stylus pen-up is handled on e-ink. No ripple, no elevation.
 *
 * Mirrors `_pillButton(child: Icon(...))` in the Flutter AppBarPill.
 */
class ChromeIconButton(
    context: Context,
    iconRes: Int,
    onTap: () -> Unit,
) : View(context) {

    private var icon = context.getDrawable(iconRes)!!.mutate()
    private val iconPx = ReaderTheme.dp(context, ReaderTheme.ICON_DP).toInt()
    private val padH = ReaderTheme.dp(context, 16f).toInt()
    private val padV = ReaderTheme.dp(context, 12f).toInt()

    /** Dimmed (black26) — laid out the same, so the pill width never jumps. */
    var dimmed: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                icon.setTint(if (value) ReaderTheme.INK_26 else ReaderTheme.INK_87)
                invalidate()
            }
        }

    /** Swap the drawable (e.g. outline ↔ filled). Preserves current tint state. */
    fun setIconRes(resId: Int) {
        icon = context.getDrawable(resId)!!.mutate()
        icon.setTint(if (dimmed) ReaderTheme.INK_26 else ReaderTheme.INK_87)
        invalidate()
    }

    init {
        icon.setTint(ReaderTheme.INK_87)
        setOnTouchListener(PenTapListener(context, onTap = onTap))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(iconPx + padH * 2, iconPx + padV * 2)
    }

    override fun onDraw(canvas: Canvas) {
        val left = (width - iconPx) / 2
        val top = (height - iconPx) / 2
        icon.setBounds(left, top, left + iconPx, top + iconPx)
        icon.draw(canvas)
    }
}

/**
 * The "Ask AI" toolbar button: the chat-bubble icon with "AI" (Source Sans 3 Bold)
 * drawn centred inside it, so it reads clearly as the AI-agent chat — not the
 * comment bubble. Same size/touch as [ChromeIconButton].
 */
class AiChatButton(
    context: Context,
    onTap: () -> Unit,
) : View(context) {

    private val icon = context.getDrawable(R.drawable.ic_ai_chat)!!.mutate().apply { setTint(ReaderTheme.INK_87) }
    private val iconPx = ReaderTheme.dp(context, ReaderTheme.ICON_DP).toInt()
    private val padH = ReaderTheme.dp(context, 16f).toInt()
    private val padV = ReaderTheme.dp(context, 12f).toInt()
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ReaderTheme.INK_87
        typeface = ReaderTheme.sourceSansBold(context)
        textAlign = Paint.Align.CENTER
    }

    init {
        setOnTouchListener(PenTapListener(context, onTap = onTap))
        // Size "AI" to sit inside the bubble (~52% of the icon width).
        label.textSize = iconPx.toFloat()
        label.textSize = label.textSize * (iconPx * 0.52f / label.measureText("AI"))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(iconPx + padH * 2, iconPx + padV * 2)
    }

    override fun onDraw(canvas: Canvas) {
        val left = (width - iconPx) / 2
        val top = (height - iconPx) / 2
        icon.setBounds(left, top, left + iconPx, top + iconPx)
        icon.draw(canvas)

        // "AI" centred in the bubble body (the Material bubble's interior centres near y=10/24).
        val cx = left + iconPx / 2f
        val cy = top + iconPx * (10f / 24f)
        val baseline = cy - (label.fontMetrics.ascent + label.fontMetrics.descent) / 2f
        canvas.drawText("AI", cx, baseline, label)
    }
}

/**
 * The AppBarPill lock slot: the locked tool's icon + a paper-circle lock badge in
 * the bottom-right corner of the icon. Tapping unlocks. Mirrors Flutter's
 * `_lockSlot(tool)` (28dp icon, 12dp badge at right:-4/bottom:-4, lock 8dp).
 */
class LockSlotView(
    context: Context,
    tool: AnnotationTool,
    onTap: () -> Unit,
) : View(context) {

    private val renderer = ToolIconRenderer(context)
    private val iconPx = ReaderTheme.dp(context, ReaderTheme.ICON_DP)
    private val padH = ReaderTheme.dp(context, 16f).toInt()
    private val padV = ReaderTheme.dp(context, 12f).toInt()
    private val badgeR = ReaderTheme.dp(context, 6f)
    private val badgeInset = ReaderTheme.dp(context, 2f)

    var tool: AnnotationTool = tool
        set(value) {
            if (field != value) { field = value; invalidate() }
        }

    init {
        setOnTouchListener(PenTapListener(context, onTap = onTap))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(iconPx.toInt() + padH * 2, iconPx.toInt() + padV * 2)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        renderer.draw(canvas, tool, cx, cy, iconPx)
        val half = iconPx / 2f
        renderer.drawLockBadge(canvas, cx + half - badgeInset, cy + half - badgeInset, badgeR)
    }
}

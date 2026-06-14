package com.afluffywaffle.layuv.reader

import android.app.Activity
import android.graphics.Canvas
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.afluffywaffle.layuv.R

/**
 * Full-screen settings screen (Phase 3). Three radio groups written directly to
 * the shared "leamh" prefs — changes are live, no Save button needed.
 *
 * Pref keys (EXACT, shared with ReaderActivity + Flutter app):
 *   eink_nav_side      — "both" | "left" | "right"
 *   eink_nav_reversed  — Boolean
 *   ink_rule_lines     — Boolean
 */
class SettingsActivity : Activity() {

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private val renderer by lazy { ToolIconRenderer(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ReaderTheme.PAPER)
        }

        // Header: back arrow + "Settings" title (matches NoteActivity + AnnotationsPanelActivity)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4f), dp(8f), dp(16f), dp(4f))
        }
        header.addView(
            ChromeIconButton(this, R.drawable.ic_arrow_back) { finish() },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT),
        )
        header.addView(
            TextView(this).apply {
                text = "Settings"
                typeface = ReaderTheme.bodyBold(context)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setTextColor(ReaderTheme.INK_87)
            },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
        )
        root.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(hDivider(), LinearLayout.LayoutParams(MATCH_PARENT, dp(1f)))

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // --- PAGE TURN SIDE ---
        body.addView(sectionHeader("PAGE TURN"))
        body.addView(
            radioGroup(
                prefs.getString(KEY_NAV_SIDE, "both") ?: "both",
                listOf("both" to "Both sides", "left" to "Left side only", "right" to "Right side only"),
            ) { value -> prefs.edit().putString(KEY_NAV_SIDE, value).apply() },
        )

        body.addView(sectionSpacer())

        // --- NAVIGATION DIRECTION ---
        body.addView(sectionHeader("NAVIGATION DIRECTION"))
        body.addView(
            radioGroup(
                if (prefs.getBoolean(KEY_NAV_REVERSED, false)) "true" else "false",
                listOf("false" to "Normal", "true" to "Reversed"),
            ) { value -> prefs.edit().putBoolean(KEY_NAV_REVERSED, value == "true").apply() },
        )

        body.addView(sectionSpacer())

        // --- INK CANVAS ---
        body.addView(sectionHeader("INK CANVAS"))
        body.addView(
            radioGroup(
                if (prefs.getBoolean(KEY_RULE_LINES, false)) "true" else "false",
                listOf("false" to "Plain", "true" to "Rule lines"),
            ) { value -> prefs.edit().putBoolean(KEY_RULE_LINES, value == "true").apply() },
        )

        val scroll = ScrollView(this)
        scroll.addView(body, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        return root
    }

    /** Uppercase Source Sans 3, 12sp, INK_54 — matches Flutter settings section header. */
    private fun sectionHeader(title: String): View = TextView(this).apply {
        text = title
        typeface = ReaderTheme.body(context)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(ReaderTheme.INK_54)
        letterSpacing = 0.08f
        setPadding(dp(20f), dp(20f), dp(20f), dp(6f))
    }

    /**
     * Builds a group of radio rows. Each row is 56dp min-height, label left + check
     * right. Tapping a row writes the pref and swaps the check glyph — no colour
     * changes, e-ink safe.
     */
    private fun radioGroup(
        selected: String,
        options: List<Pair<String, String>>,
        onPick: (String) -> Unit,
    ): View {
        var current = selected

        // Check-icon views paired with their values so we can swap state on tap.
        val checks = mutableListOf<Pair<String, View>>()

        val group = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        options.forEachIndexed { index, (value, label) ->
            if (index > 0) {
                group.addView(rowDivider(), LinearLayout.LayoutParams(MATCH_PARENT, dp(1f)))
            }

            val checkView = makeCheckView(value == current)
            checks.add(value to checkView)

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(56f)
                isClickable = true
                isFocusable = true
                stateListAnimator = null
                setPadding(dp(20f), dp(8f), dp(20f), dp(8f))
                addView(
                    TextView(context).apply {
                        text = label
                        typeface = ReaderTheme.body(context)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                        setTextColor(ReaderTheme.INK)
                    },
                    LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
                )
                addView(checkView, LinearLayout.LayoutParams(dp(24f), dp(24f)))
                setOnTouchListener(PenTapListener(context) {
                    if (value != current) {
                        current = value
                        checks.forEach { (v, cv) -> setCheckVisible(cv, v == current) }
                        onPick(value)
                    }
                })
            }

            group.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }

        return group
    }

    /** Custom View that draws ic_check (INK_87) when [visible] is true (stored in tag). */
    private fun makeCheckView(visible: Boolean): View = object : View(this) {
        override fun onDraw(canvas: Canvas) {
            if (tag == true) {
                renderer.drawVecIcon(
                    canvas, R.drawable.ic_check,
                    width / 2f, height / 2f,
                    ReaderTheme.dp(context, 20f),
                )
            }
        }
    }.apply { tag = visible }

    private fun setCheckVisible(view: View, visible: Boolean) {
        view.tag = visible
        view.invalidate()
    }

    /** 1dp INK_12 — full-width header / body separator. */
    private fun hDivider(): View = View(this).apply { setBackgroundColor(ReaderTheme.INK_12) }

    /** 1dp FILL_08 — between rows within a group. */
    private fun rowDivider(): View = View(this).apply { setBackgroundColor(ReaderTheme.FILL_08) }

    /** 8dp spacer with a 1dp INK_12 top line — between groups. */
    private fun sectionSpacer(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(hDivider(), LinearLayout.LayoutParams(MATCH_PARENT, dp(1f)))
    }

    private fun dp(v: Float): Int = ReaderTheme.dp(this, v).toInt()

    companion object {
        private const val PREFS = "leamh"
        const val KEY_NAV_SIDE = "eink_nav_side"
        const val KEY_NAV_REVERSED = "eink_nav_reversed"
        const val KEY_RULE_LINES = "ink_rule_lines"
    }
}

package com.afluffywaffle.layuv.reader

import android.content.Context
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue

/**
 * Design constants and font loading for the reader. Mirrors the Flutter app's
 * paper/ink palette and Literata/Source Sans 3 typography so a book looks the
 * same across platforms. All sizes are in dp/sp; convert with [dp]/[sp] using a
 * live [Context] (never hardcode pixels — e-ink panels vary in density).
 */
object ReaderTheme {
    // Warm paper + ink. Must match colors.xml / the Flutter const Color(0xFFF5F0E8).
    const val PAPER = 0xFFF5F0E8.toInt()
    const val INK = Color.BLACK

    // --- Chrome palette (greyscale alpha-over-black; mirrors Flutter's black87..black04) ---
    // Use these for every chrome surface so opacity tones match the Flutter app exactly.
    // Never introduce a colour-only affordance: disabled/selected states use these alphas only.
    const val INK_87 = 0xDD000000.toInt() // primary text + icons (Flutter black87)
    const val INK_54 = 0x8A000000.toInt() // secondary text, corner-hint triangle (black54)
    const val INK_45 = 0x73000000.toInt() // unselected tab label, section count (black45)
    const val INK_38 = 0x61000000.toInt() // percentage, empty-state, highlight-icon border (black38)
    const val INK_26 = 0x42000000.toInt() // disabled/dimmed icons + standard outline (black26)
    const val INK_12 = 0x1F000000.toInt() // dividers, active filter chip, container borders (black12)
    // Named translucent fills (over paper).
    const val FILL_06 = 0x0F000000.toInt() // pill bg, quote box, expanded-note box, unselected tool chip
    const val FILL_08 = 0x14000000.toInt() // tag chip inactive, row divider
    const val FILL_04 = 0x0A000000.toInt() // ink-button unselected, note text field
    const val HL_FILL = 0x26000000.toInt() // highlight swatch (15% black) — NEVER yellow
    // Highlighted/commented text: medium grey on warm paper. Dark enough to read
    // clearly under the GC16 full refresh (light greys like 0xAAAAAA wash out on
    // e-ink) yet plainly lighter than the black body text so the mark is obvious.
    // Renders only via a full refresh — see ReaderView.updateAnnotations.
    const val HIGHLIGHT_TEXT = 0xFF808080.toInt()

    // Highlight/comment background fill (P2): a light grey band behind black body
    // text — easier to catch in a body of text than grey text on e-ink. Kept
    // subtle (~12%); if it washes out under the GC16 full refresh, nudge the alpha
    // up toward the jump-highlight's ~20% (Color.argb(50, 0, 0, 0)).
    const val HIGHLIGHT_FILL = 0x1F000000.toInt()

    // --- Chrome geometry (dp) ---
    const val RADIUS_PILL = 20f
    const val RADIUS_MENU = 12f
    const val RADIUS_CARD = 10f
    const val RADIUS_BTN = 8f
    const val RADIUS_CHIP = 6f
    const val RADIUS_TAG = 20f
    const val RADIUS_SHEET = 16f
    const val TOOL_BTN_DP = 64f   // annotation tool button square
    const val TOOLBAR_H_DP = 80f  // selection / action toolbar height
    const val ICON_DP = 28f       // standard chrome icon
    const val ICON_SM_DP = 16f    // list leading / filter chip icon
    const val ICON_TINY_DP = 12f  // lock badge glyph scale
    const val ROW_MIN_DP = 48f    // minimum tap-target row (prefer 64)
    const val SCREEN_MARGIN_DP = 16f
    const val ANCHOR_GAP_DP = 8f  // gap between an overlay and its anchor

    // --- E-ink tap primitive tuning ---
    // The OS holds stylus pen-up over UI, so plain OnClickListener can miss. Chrome
    // buttons use a dwell-tap (release within slop after a short press); CircleTap
    // mirrors Flutter's CircleTappable for pen-circle-over-icon gestures.
    const val PEN_DWELL_MS = 90L
    const val PEN_SLOP_DP = 14f
    const val CIRCLE_RADIUS_DP = 90f
    const val CIRCLE_MAX_MS = 500L

    // Body typography.
    const val BODY_TEXT_SP = 19f
    const val LINE_SPACING_MULT = 1.32f

    // Page geometry.
    const val H_PADDING_DP = 26f
    const val V_PADDING_DP = 20f
    const val COLUMN_GAP_DP = 34f

    // Highlight (dotted underline) geometry — no fill, e-ink safe.
    const val UNDERLINE_OFFSET_DP = 3f
    const val UNDERLINE_STROKE_DP = 1.4f
    const val UNDERLINE_DASH_ON_DP = 2.2f
    const val UNDERLINE_DASH_OFF_DP = 2.2f

    private const val LITERATA = "fonts/Literata.ttf"
    private const val LITERATA_ITALIC = "fonts/Literata-Italic.ttf"
    private const val SOURCE_SANS = "fonts/SourceSans3.ttf"

    private val cache = HashMap<String, Typeface>()

    /** "literata" or "source_sans" — set from SharedPreferences in ReaderActivity.onCreate(). */
    var bodyFont: String = "literata"

    fun body(context: Context): Typeface =
        if (bodyFont == "literata") font(context, LITERATA) else chrome(context)

    fun bodyItalic(context: Context): Typeface =
        if (bodyFont == "literata") font(context, LITERATA_ITALIC) else chrome(context)

    fun bodyBold(context: Context): Typeface = chromeBold(context)

    /** Regular weight of the active body font (Literata Regular or Source Sans 3 wght=400). */
    fun chrome(context: Context): Typeface =
        if (bodyFont == "literata") font(context, LITERATA)
        else cache.getOrPut("$SOURCE_SANS:wght400") {
            Typeface.Builder(context.assets, SOURCE_SANS)
                .setFontVariationSettings("'wght' 400")
                .build()
        }

    /** Bold weight of the active body font (Literata wght=700 or Source Sans 3 wght=700). */
    fun chromeBold(context: Context): Typeface =
        if (bodyFont == "literata") {
            cache.getOrPut("$LITERATA:wght700") {
                Typeface.Builder(context.assets, LITERATA)
                    .setFontVariationSettings("'wght' 700")
                    .build()
            }
        } else {
            cache.getOrPut("$SOURCE_SANS:wght700") {
                Typeface.Builder(context.assets, SOURCE_SANS)
                    .setFontVariationSettings("'wght' 700")
                    .build()
            }
        }

    /** Source Sans 3 Bold regardless of the body font — for fixed chrome glyphs like the AI badge. */
    fun sourceSansBold(context: Context): Typeface =
        cache.getOrPut("$SOURCE_SANS:wght700") {
            Typeface.Builder(context.assets, SOURCE_SANS)
                .setFontVariationSettings("'wght' 700")
                .build()
        }

    @Synchronized
    private fun font(context: Context, path: String): Typeface =
        cache.getOrPut(path) { Typeface.createFromAsset(context.assets, path) }

    /** Maps the "body_font_size" pref value to sp. Default (unknown key) = medium. */
    fun bodySizeSp(prefValue: String): Float = when (prefValue) {
        "small" -> 16f
        "large" -> 22f
        else    -> BODY_TEXT_SP
    }

    /** Maps the "line_spacing" pref value to a StaticLayout multiplier. Default = comfortable. */
    fun lineSpacingMult(prefValue: String): Float = when (prefValue) {
        "normal"   -> 1.15f
        "spacious" -> LINE_SPACING_MULT
        else       -> 1.25f
    }

    /**
     * Seed [bodyFont] from the "body_font" pref. EVERY Activity must call this in
     * onCreate BEFORE building its UI: [bodyFont] is a process-wide static, so on
     * whichever Activity Android recreates first after process death it would
     * otherwise stay at the "literata" default and render the wrong typeface.
     */
    fun seedBodyFont(context: Context) {
        val prefs = context.getSharedPreferences("leamh", Context.MODE_PRIVATE)
        bodyFont = prefs.getString("body_font", "literata") ?: "literata"
    }

    /**
     * The faint, finely dotted hairline used for edge-nav rails/midlines and chrome
     * dividers (e.g. the reader's bottom bar). One source of truth so every surface
     * matches: ink ~17% alpha, ~1dp dots on a ~3dp pitch. Returns a fresh [Paint] per
     * call (callers may keep their own instance). Draw on a software layer for crisp
     * dashing on e-ink.
     */
    fun dottedLinePaint(context: Context): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = INK
        alpha = 44
        style = Paint.Style.STROKE
        strokeWidth = dp(context, 1f)
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(dp(context, 0.5f), dp(context, 2.5f)), 0f)
    }

    fun dp(context: Context, value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics)

    fun sp(context: Context, value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, context.resources.displayMetrics)
}

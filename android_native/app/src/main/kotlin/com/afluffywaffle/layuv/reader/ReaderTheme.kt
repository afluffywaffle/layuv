package com.afluffywaffle.layuv.reader

import android.content.Context
import android.graphics.Color
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

    fun body(context: Context): Typeface = font(context, LITERATA)
    fun bodyItalic(context: Context): Typeface = font(context, LITERATA_ITALIC)
    fun chrome(context: Context): Typeface = font(context, SOURCE_SANS)

    @Synchronized
    private fun font(context: Context, path: String): Typeface =
        cache.getOrPut(path) { Typeface.createFromAsset(context.assets, path) }

    fun dp(context: Context, value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics)

    fun sp(context: Context, value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, context.resources.displayMetrics)
}

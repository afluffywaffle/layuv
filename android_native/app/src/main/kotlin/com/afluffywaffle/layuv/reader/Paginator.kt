package com.afluffywaffle.layuv.reader

import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/**
 * One whole-book [StaticLayout] sliced into pages by line ranges.
 *
 * The book is laid out ONCE at [columnWidthPx]; a page is just a set of
 * contiguous line indices, and a column is a run of lines that fits
 * [columnHeightPx]. A char offset into the layout IS an index into the canonical
 * plain text (same string), so [pageForChar] / [charStartOfPage] convert freely
 * between reading position and page — see [[native-android-port]].
 *
 * Two-column "newspaper" flow falls out for free: with [columns] == 2 the text
 * flows down the left column then continues at the top of the right, so page p
 * owns columns [p*2, p*2+2).
 */
class PageLayout(
    val layout: StaticLayout,
    val columns: Int,
    val columnWidthPx: Int,
    val columnGapPx: Int,
    val contentWidthPx: Int,
    val columnHeightPx: Int,
    /** First layout line of each column, plus a trailing sentinel == lineCount. */
    private val columnStartLines: IntArray,
) {
    val columnCount: Int get() = columnStartLines.size - 1
    val pageCount: Int get() = if (columnCount == 0) 1 else (columnCount + columns - 1) / columns

    fun firstColumnOfPage(page: Int): Int = page * columns

    /** First layout line shown in [column]. */
    fun lineStartOfColumn(column: Int): Int = columnStartLines[column]

    /** One-past-the-last layout line of [column]. */
    fun lineEndOfColumn(column: Int): Int = columnStartLines[column + 1]

    /** Char offset (into the plain text) of the first character on [page]. */
    fun charStartOfPage(page: Int): Int {
        val col = firstColumnOfPage(page).coerceIn(0, columnCount - 1)
        return layout.getLineStart(columnStartLines[col])
    }

    /** The page that contains [charOffset]. */
    fun pageForChar(charOffset: Int): Int {
        if (columnCount == 0) return 0
        val clamped = charOffset.coerceIn(0, layout.text.length)
        val line = layout.getLineForOffset(clamped)
        // Find the column whose [start, end) line range covers this line.
        var col = columnStartLines.size - 2
        for (c in 0 until columnStartLines.size - 1) {
            if (line < columnStartLines[c + 1]) {
                col = c
                break
            }
        }
        return (col / columns).coerceIn(0, pageCount - 1)
    }

    companion object {
        /**
         * Build the layout and compute column line-breaks for [text] at the given
         * content box. [columns] is 1 or 2; [columnGapPx] is the gutter between
         * two columns (ignored for one column).
         */
        fun paginate(
            text: CharSequence,
            paint: TextPaint,
            contentWidthPx: Int,
            contentHeightPx: Int,
            columns: Int,
            columnGapPx: Int,
            lineSpacingMult: Float = ReaderTheme.LINE_SPACING_MULT,
        ): PageLayout {
            val cols = columns.coerceIn(1, 2)
            val gap = if (cols == 2) columnGapPx else 0
            val columnWidth = ((contentWidthPx - gap * (cols - 1)) / cols).coerceAtLeast(1)

            val layout = StaticLayout.Builder
                .obtain(text, 0, text.length, paint, columnWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, lineSpacingMult)
                .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .setIncludePad(false)
                .build()

            val columnStarts = computeColumnStarts(layout, contentHeightPx)
            return PageLayout(
                layout = layout,
                columns = cols,
                columnWidthPx = columnWidth,
                columnGapPx = gap,
                contentWidthPx = contentWidthPx,
                columnHeightPx = contentHeightPx,
                columnStartLines = columnStarts,
            )
        }

        /**
         * Walk the laid-out lines, packing each column with as many lines as fit
         * [columnHeightPx]. A line is included only if its BOTTOM still fits
         * (measured from the column's first line top), so nothing is ever clipped
         * mid-line — no safe-height fudge needed. A line taller than the box on
         * its own still gets its own column (avoids an infinite loop).
         *
         * Returns column-first-line indices with a trailing sentinel == lineCount.
         */
        private fun computeColumnStarts(layout: StaticLayout, columnHeightPx: Int): IntArray {
            val lineCount = layout.lineCount
            if (lineCount == 0) return intArrayOf(0, 0)

            val starts = ArrayList<Int>()
            var line = 0
            while (line < lineCount) {
                starts.add(line)
                val columnTop = layout.getLineTop(line)
                var last = line
                while (last + 1 < lineCount &&
                    layout.getLineBottom(last + 1) - columnTop <= columnHeightPx
                ) {
                    last++
                }
                line = last + 1
            }
            starts.add(lineCount) // sentinel
            return starts.toIntArray()
        }
    }
}

package com.afluffywaffle.layuv.docx

import kotlin.math.abs
import kotlin.math.roundToInt

/** A half-open character range [start, end) in the canonical plain text. */
data class TextSpan(val start: Int, val end: Int)

/**
 * Locating a stored annotation within the current plain text, and snapping a
 * raw selection out to word boundaries.
 *
 * [locateInPlain] / [findClosest] / [normaliseQuotes] mirror
 * lib/models/docx_store.dart; [snapToWordBoundaries] / [isWordBoundary] mirror
 * lib/utils/annotation_utils.dart. All offsets are UTF-16 code-unit indices, as
 * in Dart, so they agree with [PlainTextMapper] and the Dart-generated goldens.
 */
object Anchoring {

    /**
     * Three-tier locate (first hit wins), exactly as docx_store:
     *  1. context match — `prefix + selectedText + suffix` via indexOf
     *  2. nearest occurrence of `selectedText` to `positionHint * length`
     *  3. quote-normalised retry of (1) then (2)
     * Returns null if [selectedText] is empty or cannot be located.
     */
    fun locateInPlain(
        plain: String,
        selectedText: String,
        prefix: String,
        suffix: String,
        positionHint: Double = 0.0,
    ): TextSpan? {
        if (selectedText.isEmpty()) return null

        if (prefix.isNotEmpty() || suffix.isNotEmpty()) {
            val needle = prefix + selectedText + suffix
            val idx = plain.indexOf(needle)
            if (idx >= 0) {
                val start = idx + prefix.length
                return TextSpan(start, start + selectedText.length)
            }
        }

        val hintPos = (positionHint * plain.length).roundToInt().coerceIn(0, plain.length)
        val best = findClosest(plain, selectedText, hintPos)
        if (best >= 0) return TextSpan(best, best + selectedText.length)

        val plainN = normaliseQuotes(plain)
        val selectedN = normaliseQuotes(selectedText)
        val prefixN = normaliseQuotes(prefix)
        val suffixN = normaliseQuotes(suffix)

        if (prefixN.isNotEmpty() || suffixN.isNotEmpty()) {
            val needle = prefixN + selectedN + suffixN
            val idx = plainN.indexOf(needle)
            if (idx >= 0) {
                val start = idx + prefixN.length
                return TextSpan(start, start + selectedN.length)
            }
        }
        val bestN = findClosest(plainN, selectedN, hintPos)
        if (bestN >= 0) return TextSpan(bestN, bestN + selectedN.length)

        return null
    }

    /** Index of the occurrence of [needle] in [hay] closest to [hintPos], or -1. */
    fun findClosest(hay: String, needle: String, hintPos: Int): Int {
        var best = -1
        var bestDist = Int.MAX_VALUE
        var from = 0
        while (true) {
            val idx = hay.indexOf(needle, from)
            if (idx < 0) break
            val dist = abs(idx - hintPos)
            if (dist < bestDist) {
                bestDist = dist
                best = idx
            }
            from = idx + 1
        }
        return best
    }

    /** Smart quotes/dashes → ASCII. 1:1 char replacement, so indices are preserved. */
    fun normaliseQuotes(s: String): String = s
        .replace('“', '"') // “
        .replace('”', '"') // ”
        .replace('‘', '\'') // ‘
        .replace('’', '\'') // ’
        .replace('–', '-') // –
        .replace('—', '-') // —

    private val WORD_BOUNDARY = hashSetOf(
        ' ', '\n', '\r', '\t', '.', ',', '!', '?', ';', ':', '"', '\'',
        '(', ')', '[', ']', '—', '–',
    )

    /** Expands [start, end) outward to full word boundaries within [text]. Only expands. */
    fun snapToWordBoundaries(text: String, start: Int, end: Int): TextSpan {
        var s = start
        while (s > 0 && !isWordBoundary(text[s - 1])) s--
        var e = end
        while (e < text.length && !isWordBoundary(text[e])) e++
        return TextSpan(s, e)
    }

    fun isWordBoundary(c: Char): Boolean = c in WORD_BOUNDARY
}

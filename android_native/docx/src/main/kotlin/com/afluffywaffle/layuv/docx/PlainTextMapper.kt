package com.afluffywaffle.layuv.docx

/** A bold and/or italic run over `[start, end)` char offsets into [PlainMap.plain]. */
data class FormatSpan(val start: Int, val end: Int, val bold: Boolean, val italic: Boolean)

/**
 * The canonical plain text [plain] plus, for each of its UTF-16 code units,
 * the char offset [xmlOffsets] into the source document.xml. The two arrays
 * are parallel: `xmlOffsets.size == plain.length`.
 *
 * [formats] is a READ-ONLY, additive overlay (direct `<w:b>`/`<w:i>` run
 * formatting) for display only — it does NOT affect [plain]/[xmlOffsets], so
 * anchoring and write-back round-trip exactly as before.
 */
class PlainMap(
    val plain: String,
    val xmlOffsets: IntArray,
    val formats: List<FormatSpan> = emptyList(),
)

/**
 * Builds the ONE canonical plain-text string the native reader renders AND
 * anchors annotations against (offsets into [PlainMap.plain] ARE the anchor
 * coordinate system).
 *
 * This is the CLEAN extraction — see [[native-android-port]]. It is
 * byte-identical to docx_store._buildPlainMap (plain + xmlOffsets) on ordinary
 * prose, but parses real element names so `<w:tab/>`, `<w:tbl>`, `<w:tr>`,
 * `<w:tc>` are no longer mis-read as `<w:t>` (the legacy regex bug), and it
 * decodes numeric character references.
 *
 * Extraction rules (mirror of `buildCleanMap` in
 * android_native/tools/golden_gen/gen_goldens.dart):
 * ```
 *   <w:t>…</w:t>      decoded text; one xmlOffset per code unit (base+i)
 *   <w:tab/>          '\t'   offset = tag start
 *   <w:br/> <w:cr/>   '\n'   offset = tag start
 *   </w:p>            '\n'   offset = tag start   (trailing newline kept)
 *   everything else   ignored
 * ```
 * Iteration is over UTF-16 code units (Kotlin `Char`), matching Dart's
 * `String[i]` indexing, so surrogate pairs (emoji) split identically and
 * offsets stay aligned with the Dart-generated goldens.
 */
object PlainTextMapper {

    private const val WT_CLOSE = "</w:t>"
    private val VAL_RE = Regex("w:val=\"([^\"]*)\"")

    /** A toggle property like `<w:b/>` is ON unless `w:val` says otherwise. */
    private fun toggleOn(tag: String): Boolean {
        val v = VAL_RE.find(tag)?.groupValues?.get(1) ?: return true
        return v != "false" && v != "0" && v != "off" && v != "none"
    }

    fun build(xml: String): PlainMap {
        val sb = StringBuilder()
        val offsets = ArrayList<Int>()
        val formats = ArrayList<FormatSpan>()
        // Direct run formatting, tracked only inside a <w:r> (so a <w:pPr> paragraph-
        // mark rPr never leaks onto the text). Style-based bold/italic (rStyle /
        // heading styles) is NOT resolved here — direct <w:b>/<w:i> only.
        var inRun = false
        var runBold = false
        var runItalic = false
        val n = xml.length
        var i = 0
        while (i < n) {
            val lt = xml.indexOf('<', i)
            if (lt < 0) break
            val gt = xml.indexOf('>', lt)
            if (gt < 0) break

            val tag = xml.substring(lt, gt + 1) // includes '<' and '>'
            val isEnd = tag.startsWith("</")
            val isSelfClose = tag.endsWith("/>")

            // Element name = chars after '<' (or '</') up to whitespace/'/'/'>'.
            val nameStart = if (isEnd) 2 else 1
            var k = nameStart
            while (k < tag.length) {
                val c = tag[k]
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '/' || c == '>') break
                k++
            }
            val name = tag.substring(nameStart, k)

            if (!isEnd && !isSelfClose && name == "w:t") {
                val contentStart = gt + 1
                val close = xml.indexOf(WT_CLOSE, contentStart)
                val end = if (close < 0) n else close
                val decoded = XmlEntities.decode(xml.substring(contentStart, end))
                val spanStart = sb.length
                for (j in decoded.indices) {
                    sb.append(decoded[j])
                    offsets.add(contentStart + j)
                }
                if ((runBold || runItalic) && sb.length > spanStart) {
                    formats.add(FormatSpan(spanStart, sb.length, runBold, runItalic))
                }
                i = if (close < 0) n else close + WT_CLOSE.length
                continue
            }

            when {
                !isEnd && name == "w:tab" -> {
                    sb.append('\t'); offsets.add(lt)
                }
                !isEnd && (name == "w:br" || name == "w:cr") -> {
                    sb.append('\n'); offsets.add(lt)
                }
                isEnd && name == "w:p" -> {
                    sb.append('\n'); offsets.add(lt)
                }
                // Run formatting (display-only overlay; never touches sb/offsets).
                !isEnd && !isSelfClose && name == "w:r" -> {
                    inRun = true; runBold = false; runItalic = false
                }
                isEnd && name == "w:r" -> {
                    inRun = false; runBold = false; runItalic = false
                }
                !isEnd && inRun && name == "w:b" -> runBold = toggleOn(tag)
                !isEnd && inRun && name == "w:i" -> runItalic = toggleOn(tag)
            }
            i = gt + 1
        }
        return PlainMap(sb.toString(), offsets.toIntArray(), formats)
    }
}

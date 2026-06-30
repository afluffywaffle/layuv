package com.afluffywaffle.layuv.docx

/** A bold and/or italic run over `[start, end)` char offsets into [PlainMap.plain]. */
data class FormatSpan(val start: Int, val end: Int, val bold: Boolean, val italic: Boolean)

/**
 * A heading paragraph for the document-outline navigator. [level] is 0-based
 * (0 = Heading 1). [charOffset] is the start of the heading's text in
 * [PlainMap.plain] — divide by `plain.length` for the 0.0–1.0 jump fraction,
 * the same coordinate system as annotation positions.
 */
data class Heading(val text: String, val level: Int, val charOffset: Int)

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
    /** Heading paragraphs in document order — drives the navigation outline. */
    val headings: List<Heading> = emptyList(),
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

    /**
     * [styles] is optional: when provided (from [StyleResolver.parse]), paragraph
     * styles (`<w:pStyle>`) and run character styles (`<w:rStyle>`) contribute to
     * the effective bold/italic for each run. When null, only direct `<w:b>`/`<w:i>`
     * properties are used (same as the previous behaviour).
     */
    fun build(xml: String, styles: Map<String, StyleResolver.Props>? = null): PlainMap {
        val sb = StringBuilder()
        val offsets = ArrayList<Int>()
        val formats = ArrayList<FormatSpan>()
        val headings = ArrayList<Heading>()
        // Outline tracking: where the current paragraph's text begins in [sb], and
        // its heading level (null = not a heading), captured from <w:pStyle>.
        var paraStart = 0
        var paraHeadingLevel: Int? = null
        // Direct run formatting, tracked inside <w:r>.
        var inRun = false
        var runBold = false
        var runItalic = false
        // Style-based formatting (paragraph level and run character style).
        var inPPr = false   // inside <w:pPr> (only when NOT inside a run)
        var inRPr = false   // inside <w:rPr> (only when inside a run)
        var pStyleBold = false
        var pStyleItalic = false
        var rStyleBold = false
        var rStyleItalic = false

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
                val spanStart = sb.length
                // Walk raw XML, decoding entities inline and recording each decoded
                // char's raw start offset. Using decoded-string indices (contentStart+j)
                // would be wrong after any entity whose raw length differs from 1.
                var r = contentStart
                while (r < end) {
                    if (xml[r] == '&') {
                        val semi = xml.indexOf(';', r)
                        if (semi in r + 1 until end + 1) {
                            val entity = xml.substring(r, semi + 1)
                            val entityDecoded = XmlEntities.decode(entity)
                            for (ch in entityDecoded) { sb.append(ch); offsets.add(r) }
                            r = semi + 1
                            continue
                        }
                    }
                    sb.append(xml[r]); offsets.add(r)
                    r++
                }
                val effBold   = runBold   || rStyleBold   || pStyleBold
                val effItalic = runItalic || rStyleItalic || pStyleItalic
                if ((effBold || effItalic) && sb.length > spanStart) {
                    formats.add(FormatSpan(spanStart, sb.length, effBold, effItalic))
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
                // New paragraph: reset paragraph-level style state.
                !isEnd && !isSelfClose && name == "w:p" -> {
                    pStyleBold = false; pStyleItalic = false; inPPr = false
                    paraStart = sb.length; paraHeadingLevel = null
                }
                isEnd && name == "w:p" -> {
                    // Record the outline entry from this paragraph's text BEFORE the
                    // trailing newline is appended. Skip blank headings.
                    val lvl = paraHeadingLevel
                    if (lvl != null) {
                        val text = sb.substring(paraStart, sb.length).trim()
                        if (text.isNotEmpty()) headings.add(Heading(text, lvl, paraStart))
                    }
                    sb.append('\n'); offsets.add(lt)
                }
                // Paragraph properties block (only outside runs).
                !isEnd && !isSelfClose && name == "w:pPr" && !inRun -> inPPr = true
                isEnd && name == "w:pPr" -> inPPr = false
                // Paragraph style reference → resolve bold/italic from the style map.
                isSelfClose && name == "w:pStyle" && inPPr && styles != null -> {
                    val sid = VAL_RE.find(tag)?.groupValues?.get(1)
                    val sp = if (sid != null) styles[sid] else null
                    pStyleBold   = sp?.bold   ?: false
                    pStyleItalic = sp?.italic ?: false
                    paraHeadingLevel = sp?.outlineLevel
                }
                // Run open/close.
                !isEnd && !isSelfClose && name == "w:r" -> {
                    inRun = true; runBold = false; runItalic = false
                    rStyleBold = false; rStyleItalic = false; inRPr = false
                }
                isEnd && name == "w:r" -> {
                    inRun = false; runBold = false; runItalic = false
                    rStyleBold = false; rStyleItalic = false; inRPr = false
                }
                // Run properties block (only inside runs).
                !isEnd && !isSelfClose && name == "w:rPr" && inRun -> inRPr = true
                isEnd && name == "w:rPr" && inRun -> inRPr = false
                // Run character style → resolve bold/italic.
                isSelfClose && name == "w:rStyle" && inRun && inRPr && styles != null -> {
                    val sid = VAL_RE.find(tag)?.groupValues?.get(1)
                    val sp = if (sid != null) styles[sid] else null
                    rStyleBold   = sp?.bold   ?: false
                    rStyleItalic = sp?.italic ?: false
                }
                // Direct run bold/italic (override style-based; e.g. <w:b w:val="false"/>).
                !isEnd && inRun && name == "w:b" -> runBold = toggleOn(tag)
                !isEnd && inRun && name == "w:i" -> runItalic = toggleOn(tag)
            }
            i = gt + 1
        }
        return PlainMap(sb.toString(), offsets.toIntArray(), formats, headings)
    }
}

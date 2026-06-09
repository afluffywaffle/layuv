package com.afluffywaffle.layuv.docx

/**
 * The canonical plain text [plain] plus, for each of its UTF-16 code units,
 * the char offset [xmlOffsets] into the source document.xml. The two arrays
 * are parallel: `xmlOffsets.size == plain.length`.
 */
class PlainMap(val plain: String, val xmlOffsets: IntArray)

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

    fun build(xml: String): PlainMap {
        val sb = StringBuilder()
        val offsets = ArrayList<Int>()
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
                for (j in decoded.indices) {
                    sb.append(decoded[j])
                    offsets.add(contentStart + j)
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
            }
            i = gt + 1
        }
        return PlainMap(sb.toString(), offsets.toIntArray())
    }
}

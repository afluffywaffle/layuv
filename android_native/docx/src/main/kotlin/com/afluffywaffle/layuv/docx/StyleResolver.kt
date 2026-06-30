package com.afluffywaffle.layuv.docx

/**
 * Parses `word/styles.xml` to produce effective bold/italic properties per
 * style ID. Inheritance via `<w:basedOn>` is resolved transitively (depth-
 * capped at 20 to handle corrupt cycles without throwing).
 *
 * Only the style-level `<w:rPr>` (direct child of `<w:style>`) is examined —
 * paragraph-mark `<w:rPr>` inside `<w:pPr>` is excluded by stripping the
 * `<w:pPr>` block before matching.
 */
object StyleResolver {

    /**
     * [outlineLevel] is the document-outline depth (0 = top level / Heading 1)
     * when this style is a heading, or null for body styles. Derived from an
     * explicit `<w:outlineLvl>` in the style's `<w:pPr>`, falling back to the
     * conventional `Heading N` styleId. Used to build the navigation outline;
     * does NOT affect plain text or anchoring.
     */
    data class Props(val bold: Boolean, val italic: Boolean, val outlineLevel: Int? = null)

    private val STYLE_ID   = Regex("""w:styleId="([^"]+)"""")
    private val BASED_ON   = Regex("""<w:basedOn\s+w:val="([^"]+)"\s*/>""")
    private val PPR_BLOCK  = Regex("""<w:pPr>.*?</w:pPr>""", RegexOption.DOT_MATCHES_ALL)
    private val OUTLINE_LVL = Regex("""<w:outlineLvl\s+w:val="(\d+)"\s*/>""")
    // Word/Pages/Google Docs all keep the English internal styleId "Heading1".."Heading9"
    // (the localized label lives in <w:name>, not styleId). Tolerate an optional space.
    private val HEADING_ID = Regex("""^heading\s*([1-9])$""", RegexOption.IGNORE_CASE)
    private val RPR_BLOCK  = Regex("""<w:rPr>(.*?)</w:rPr>""", RegexOption.DOT_MATCHES_ALL)
    // <w:b> / <w:i> but NOT <w:bCs> / <w:iCs> (complex-script equivalents)
    private val B_ELEM     = Regex("""<w:b(?!C)(?:\s[^>]*)?>""")
    private val I_ELEM     = Regex("""<w:i(?!C)(?:\s[^>]*)?>""")
    private val VAL_RE     = Regex("""w:val="([^"]+)"""")

    fun parse(stylesXml: String): Map<String, Props> {
        val raw = mutableMapOf<String, Pair<String?, Props>>()

        var pos = 0
        while (true) {
            val start = stylesXml.indexOf("<w:style", pos)
            if (start < 0) break
            // Skip self-closed tags like <w:style w:styleId="X"/> (no content).
            val tagEnd = stylesXml.indexOf('>', start + "<w:style".length)
            if (tagEnd < 0) break
            if (stylesXml[tagEnd - 1] == '/') { pos = tagEnd + 1; continue }

            val end = stylesXml.indexOf("</w:style>", start)
            if (end < 0) break
            val block = stylesXml.substring(start, end + "</w:style>".length)
            pos = end + "</w:style>".length

            val styleId = STYLE_ID.find(block)?.groupValues?.get(1) ?: continue
            val basedOn = BASED_ON.find(block)?.groupValues?.get(1)

            // Outline level must be read BEFORE stripping <w:pPr> (it lives there).
            // Explicit <w:outlineLvl> wins; else infer from a conventional Heading styleId.
            val outline = OUTLINE_LVL.find(block)?.groupValues?.get(1)?.toIntOrNull()
                ?: HEADING_ID.find(styleId)?.groupValues?.get(1)?.toIntOrNull()?.let { it - 1 }

            // Strip <w:pPr> so we only see the style-level <w:rPr>.
            val blockNoPPr = PPR_BLOCK.replace(block, "")
            val rprContent = RPR_BLOCK.find(blockNoPPr)?.groupValues?.get(1) ?: ""

            raw[styleId] = basedOn to Props(
                bold = hasBold(rprContent),
                italic = hasItalic(rprContent),
                outlineLevel = outline,
            )
        }

        // Resolve inheritance transitively.
        val resolved = mutableMapOf<String, Props>()
        fun resolve(id: String, depth: Int = 0): Props {
            resolved[id]?.let { return it }
            if (depth > 20) return Props(false, false)
            val (basedOn, direct) = raw[id] ?: return Props(false, false)
            val parent = if (basedOn != null) resolve(basedOn, depth + 1) else Props(false, false)
            return Props(
                bold = direct.bold || parent.bold,
                italic = direct.italic || parent.italic,
                outlineLevel = direct.outlineLevel ?: parent.outlineLevel,
            ).also { resolved[id] = it }
        }
        raw.keys.forEach { resolve(it) }
        return resolved
    }

    private fun hasBold(rpr: String): Boolean {
        val m = B_ELEM.find(rpr) ?: return false
        val v = VAL_RE.find(m.value)?.groupValues?.get(1)
        return v == null || (v != "false" && v != "0" && v != "off")
    }

    private fun hasItalic(rpr: String): Boolean {
        val m = I_ELEM.find(rpr) ?: return false
        val v = VAL_RE.find(m.value)?.groupValues?.get(1)
        return v == null || (v != "false" && v != "0" && v != "off")
    }
}

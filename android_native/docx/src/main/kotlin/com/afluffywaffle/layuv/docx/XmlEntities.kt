package com.afluffywaffle.layuv.docx

/**
 * XML entity decode/encode for OOXML text.
 *
 * [decode] handles the five named entities AND numeric character references
 * (`&#233;`, `&#x1F600;`) — unlike docx_store._unesc, which only decodes the
 * five named ones. Single left-to-right pass, so `&amp;lt;` decodes to the
 * literal `&lt;` (not `<`). Mirrors `_decodeEntities` in
 * android_native/tools/golden_gen/gen_goldens.dart.
 */
object XmlEntities {
    private val ENTITY = Regex("&(#x[0-9A-Fa-f]+|#[0-9]+|amp|lt|gt|quot|apos);")

    /** Mirror of docx_store._esc — `&` first so it isn't double-escaped. */
    fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    fun decode(s: String): String = ENTITY.replace(s) { m ->
        when (val e = m.groupValues[1]) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            else -> {
                val code = if (e.startsWith("#x")) {
                    e.substring(2).toInt(16)
                } else {
                    e.substring(1).toInt()
                }
                // Character.toChars handles supplementary code points (emoji)
                // by emitting a UTF-16 surrogate pair, matching Dart's
                // String.fromCharCode.
                String(Character.toChars(code))
            }
        }
    }
}

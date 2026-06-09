package com.afluffywaffle.layuv.docx

/**
 * Compact JSON serializer for `leamh/annotations.json` matching Dart's
 * `jsonEncode` escaping (escape `"` `\` and control chars as Dart does; leave
 * `/` and non-ASCII raw — unlike org.json, which escapes `/`). Output is valid
 * JSON the Dart store reads back identically. Byte-identity with Dart isn't
 * required (annotations.json is compared semantically), only Dart-readability.
 */
internal object JsonWriter {
    fun encode(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> writeString(sb, v)
            is Boolean -> sb.append(v.toString())
            is Int, is Long -> sb.append(v.toString())
            is Double -> sb.append(encodeDouble(v))
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, vv) in v) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k.toString())
                    sb.append(':')
                    write(sb, vv)
                }
                sb.append('}')
            }
            is List<*> -> {
                sb.append('[')
                var first = true
                for (e in v) {
                    if (!first) sb.append(',')
                    first = false
                    write(sb, e)
                }
                sb.append(']')
            }
            else -> writeString(sb, v.toString())
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    private fun encodeDouble(d: Double): String =
        if (!d.isInfinite() && !d.isNaN() && d == d.toLong().toDouble()) "${d.toLong()}.0" else d.toString()
}

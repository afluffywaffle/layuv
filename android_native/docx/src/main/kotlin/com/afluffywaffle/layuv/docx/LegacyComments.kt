package com.afluffywaffle.layuv.docx

import com.afluffywaffle.layuv.docx.model.Annotation
import com.afluffywaffle.layuv.docx.model.AnnotationTag
import com.afluffywaffle.layuv.docx.model.AnnotationTool
import com.afluffywaffle.layuv.docx.model.Timestamps

/**
 * Imports `word/comments.xml` — both the legacy Léamh comment format
 * (`[tool:X] [tag:Y] N% — "text"`, author = annotation id) and native Word
 * comments (resolved against the document's `<w:commentRangeStart/End>`). The
 * read fallback used when `leamh/annotations.json` is absent.
 *
 * Mirror of docx_store `_parseComments` + `_extractFromCommentRange`.
 */
object LegacyComments {

    private val COMMENT = Regex("<w:comment\\s([^>]*)>(.*?)</w:comment>", RegexOption.DOT_MATCHES_ALL)
    private val ID_ATTR = Regex("w:id=\"([^\"]*)\"")
    private val AUTHOR_ATTR = Regex("w:author=\"([^\"]*)\"")
    private val DATE_ATTR = Regex("w:date=\"([^\"]*)\"")
    private val WT = Regex("<w:t[^>]*>(.*?)</w:t>", RegexOption.DOT_MATCHES_ALL)

    // [tool:X] [tag:Y] N% — "text"   (— is U+2014 em-dash)
    private val LEGACY = Regex(
        "\\[tool:(\\w+)\\](?:\\s\\[tag:(\\w+)\\])?\\s(\\d+)%\\s—\\s\"(.*)\"",
        RegexOption.DOT_MATCHES_ALL,
    )

    fun parseComments(commentsXml: String, documentXml: String, map: PlainMap): List<Annotation> {
        val results = ArrayList<Annotation>()
        for (cm in COMMENT.findAll(commentsXml)) {
            try {
                val attrs = cm.groupValues[1]
                val body = cm.groupValues[2]
                val commentId = ID_ATTR.find(attrs)?.groupValues?.get(1) ?: ""
                val authorRaw = XmlEntities.decode(AUTHOR_ATTR.find(attrs)?.groupValues?.get(1) ?: "")
                val dateStr = DATE_ATTR.find(attrs)?.groupValues?.get(1) ?: continue
                val timestamp = Timestamps.parse(dateStr)
                val texts = WT.findAll(body).map { XmlEntities.decode(it.groupValues[1]) }.toList()
                if (texts.isEmpty()) continue

                val legacyMatch = texts.firstNotNullOfOrNull { LEGACY.find(it) }
                if (legacyMatch != null && authorRaw.isNotEmpty()) {
                    val tool = AnnotationTool.fromName(legacyMatch.groupValues[1])
                    val tagName = legacyMatch.groupValues[2]
                    val tag = if (tagName.isNotEmpty()) AnnotationTag.fromName(tagName) else null
                    val headerIdx = texts.indexOfFirst { LEGACY.containsMatchIn(it) }
                    val noteTexts = texts.subList(headerIdx + 1, texts.size)
                    val note = if (noteTexts.isNotEmpty()) noteTexts.joinToString("\n") else null
                    results.add(
                        Annotation(
                            id = authorRaw,
                            selectedText = legacyMatch.groupValues[4],
                            prefix = "",
                            suffix = "",
                            tool = tool,
                            tag = tag,
                            note = if (note.isNullOrEmpty()) null else note,
                            timestamp = timestamp,
                            position = legacyMatch.groupValues[3].toInt() / 100.0,
                        ),
                    )
                    continue
                }

                // Native Word comment.
                if (commentId.isEmpty()) continue
                val ex = extractFromCommentRange(documentXml, commentId, map)
                if (ex.text.isEmpty()) continue
                val note = texts.filter { it.trim().isNotEmpty() }.joinToString(" ").trim()
                results.add(
                    Annotation(
                        id = "word_$commentId",
                        selectedText = ex.text,
                        prefix = ex.prefix,
                        suffix = ex.suffix,
                        tool = AnnotationTool.comment,
                        note = note.ifEmpty { null },
                        timestamp = timestamp,
                        position = ex.position,
                    ),
                )
            } catch (e: Exception) {
                // skip malformed comment
            }
        }
        results.sortBy { it.position }
        return results
    }

    private data class Extracted(val text: String, val prefix: String, val suffix: String, val position: Double)

    private fun extractFromCommentRange(documentXml: String, commentId: String, map: PlainMap): Extracted {
        val startMarker = "<w:commentRangeStart w:id=\"$commentId\"/>"
        val endMarker = "<w:commentRangeEnd w:id=\"$commentId\"/>"
        val si = documentXml.indexOf(startMarker)
        val ei = documentXml.indexOf(endMarker)
        if (si < 0 || ei < 0 || ei <= si) return Extracted("", "", "", 0.0)

        val segment = documentXml.substring(si + startMarker.length, ei)
        val text = WT.findAll(segment).joinToString("") { XmlEntities.decode(it.groupValues[1]) }
        if (text.isEmpty()) return Extracted("", "", "", 0.0)

        val rangeStart = si + startMarker.length
        var plainIdx = 0
        for (k in map.xmlOffsets.indices) {
            if (map.xmlOffsets[k] >= rangeStart) {
                plainIdx = k
                break
            }
        }
        val plain = map.plain
        val position = if (plain.isNotEmpty()) (plainIdx.toDouble() / plain.length).coerceIn(0.0, 1.0) else 0.0
        val plainEnd = (plainIdx + text.length).coerceIn(0, plain.length)
        return Extracted(
            text = text,
            prefix = plain.substring((plainIdx - 20).coerceIn(0, plainIdx), plainIdx),
            suffix = plain.substring(plainEnd, (plainEnd + 20).coerceIn(plainEnd, plain.length)),
            position = position,
        )
    }
}

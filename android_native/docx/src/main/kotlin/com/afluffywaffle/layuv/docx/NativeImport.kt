package com.afluffywaffle.layuv.docx

import com.afluffywaffle.layuv.docx.model.Annotation
import com.afluffywaffle.layuv.docx.model.AnnotationTool
import java.time.Instant

/**
 * Imports existing Word run formatting (`<w:highlight>`, `<w:u>`, `<w:strike>`)
 * as Léamh annotations — the read fallback used when `leamh/annotations.json` is
 * absent (a fresh or foreign DOCX). Adjacent runs sharing a tool are merged.
 *
 * Mirror of docx_store `_importNativeFormatting` + `_annotationFromSegment`, but
 * mapped against the clean [PlainMap] (byte-identical to `_buildPlainMap` on
 * prose, which is all this path sees). `baseMicros`/`now` replace the Dart
 * `DateTime.now()` id/timestamp so the result is deterministic and testable.
 */
object NativeImport {

    private val RUN = Regex("<w:r(?:\\s[^>]*)?>(?<!/>).*?</w:r>", RegexOption.DOT_MATCHES_ALL)
    private val WT = Regex("<w:t(?:[^>]*)>(.*?)</w:t>", RegexOption.DOT_MATCHES_ALL)
    private val RPR = Regex("<w:rPr>(.*?)</w:rPr>", RegexOption.DOT_MATCHES_ALL)

    private data class Segment(val tool: AnnotationTool, val plainStart: Int, val plainEnd: Int)

    fun importNativeFormatting(
        documentXml: String,
        map: PlainMap,
        baseMicros: Long,
        now: Instant,
    ): List<Annotation> {
        if (map.plain.isEmpty()) return emptyList()
        val plain = map.plain
        val offsets = map.xmlOffsets

        val segments = ArrayList<Segment>()
        for (runMatch in RUN.findAll(documentXml)) {
            val runContent = runMatch.value

            val rPrMatch = RPR.find(runContent) ?: continue
            var rPr = rPrMatch.groupValues[1]
            val rPrChangeIdx = rPr.indexOf("<w:rPrChange")
            if (rPrChangeIdx >= 0) rPr = rPr.substring(0, rPrChangeIdx)

            val tool = when {
                rPr.contains("<w:highlight") -> AnnotationTool.highlight
                rPr.contains("w:val=\"wave\"") -> AnnotationTool.wavyUnderline
                rPr.contains("w:val=\"double\"") -> AnnotationTool.doubleUnderline
                rPr.contains("<w:u ") || rPr.contains("<w:u/>") -> AnnotationTool.underline
                rPr.contains("<w:strike") -> AnnotationTool.strikethrough
                else -> null
            } ?: continue

            val text = WT.findAll(runContent).joinToString("") { XmlEntities.decode(it.groupValues[1]) }
            if (text.isEmpty()) continue

            val firstWt = WT.find(runContent) ?: continue
            val wtContentStart = runMatch.range.first + firstWt.range.first + firstWt.value.indexOf('>') + 1

            // Binary search: first plain index whose xml offset >= wtContentStart.
            var lo = 0
            var hi = offsets.size - 1
            var plainStart = -1
            while (lo <= hi) {
                val mid = (lo + hi) / 2
                if (offsets[mid] >= wtContentStart) {
                    plainStart = mid
                    hi = mid - 1
                } else {
                    lo = mid + 1
                }
            }
            if (plainStart < 0) continue
            val plainEnd = (plainStart + text.length).coerceIn(0, plain.length)
            if (plain.substring(plainStart, plainEnd) != text) continue

            segments.add(Segment(tool, plainStart, plainEnd))
        }

        if (segments.isEmpty()) return emptyList()

        val results = ArrayList<Annotation>()
        var curr = segments[0]
        for (i in 1 until segments.size) {
            val next = segments[i]
            curr = if (next.tool == curr.tool && next.plainStart <= curr.plainEnd) {
                Segment(curr.tool, curr.plainStart, maxOf(next.plainEnd, curr.plainEnd))
            } else {
                results.add(annotationFromSegment(curr, plain, results.size, baseMicros, now))
                next
            }
        }
        results.add(annotationFromSegment(curr, plain, results.size, baseMicros, now))
        return results
    }

    private fun annotationFromSegment(
        seg: Segment,
        plain: String,
        index: Int,
        baseMicros: Long,
        now: Instant,
    ): Annotation {
        val text = plain.substring(seg.plainStart, seg.plainEnd)
        val pos = if (plain.isNotEmpty()) {
            (seg.plainStart.toDouble() / plain.length).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        val prefix = plain.substring((seg.plainStart - 20).coerceIn(0, seg.plainStart), seg.plainStart)
        val suffix = plain.substring(seg.plainEnd, (seg.plainEnd + 20).coerceIn(seg.plainEnd, plain.length))
        return Annotation(
            id = (baseMicros + index).toString(),
            selectedText = text,
            prefix = prefix,
            suffix = suffix,
            tool = seg.tool,
            timestamp = now,
            position = pos,
        )
    }
}

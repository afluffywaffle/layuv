package com.afluffywaffle.layuv.docx

import com.afluffywaffle.layuv.docx.model.Annotation
import com.afluffywaffle.layuv.docx.model.AnnotationTool

/**
 * Injects run properties (`<w:rPr>`) and comment/bookmark anchors into
 * `word/document.xml`. Mirror of docx_store `_injectNativeFormatting` and its
 * run helpers, run over the clean [PlainTextMapper] (byte-identical to the
 * legacy `_buildPlainMap` on prose, which is what the reader produces).
 *
 * Run splitting: `<w:rPr>` applies to a whole `<w:r>`, so when a selection
 * starts/ends mid-run the run is split at that boundary first, then rPr is
 * injected only into the covered fragment(s). Annotations are processed one at a
 * time, rebuilding the plain map + run lists after each because splits grow the
 * XML. Comment/bookmark anchors are collected and applied in one final pass
 * (they don't change run structure), so stored positions never go stale.
 */
object RunPropertyInjector {

    private val RUN_OPEN = Regex("<w:r(?:\\s[^>]*)?>(?<!/>)")
    private val RUN_CLOSE = Regex("</w:r>")
    private val WT = Regex("<w:t(?:[^>]*)>(.*?)</w:t>", RegexOption.DOT_MATCHES_ALL)
    private val RPR_BLOCK = Regex("<w:rPr>.*?</w:rPr>", RegexOption.DOT_MATCHES_ALL)
    private val RPR_CHANGE = Regex("<w:rPrChange\\b[^>]*>.*?</w:rPrChange>", RegexOption.DOT_MATCHES_ALL)

    private val STRIP_CR_START = Regex("<w:commentRangeStart\\b[^>]*/>")
    private val STRIP_CR_END = Regex("<w:commentRangeEnd\\b[^>]*/>")
    private val STRIP_CR_REF = Regex(
        "<w:r><w:rPr><w:rStyle w:val=\"CommentReference\"/></w:rPr><w:commentReference\\b[^>]*/></w:r>",
    )
    private val NON_ID = Regex("[^a-zA-Z0-9_]")

    /** OOXML run property for a tool (inner content only). Mirror of `_rPrForTool`. */
    fun rPrForTool(tool: AnnotationTool): String = when (tool) {
        AnnotationTool.highlight, AnnotationTool.comment, AnnotationTool.inkAnnotation ->
            "<w:highlight w:val=\"yellow\"/>"
        AnnotationTool.underline -> "<w:u w:val=\"single\"/>"
        AnnotationTool.doubleUnderline -> "<w:u w:val=\"double\"/>"
        AnnotationTool.strikethrough -> "<w:strike/>"
        AnnotationTool.wavyUnderline -> "<w:u w:val=\"wave\"/>"
        AnnotationTool.bookmark -> ""
    }

    private data class Anchor(
        val commentId: Int,
        val selectedText: String,
        val prefix: String,
        val suffix: String,
        val position: Double,
    )

    private data class Bookmark(
        val annotationId: String,
        val selectedText: String,
        val prefix: String,
        val suffix: String,
        val position: Double,
    )

    private data class Ins(val pos: Int, val tag: String)

    private fun MatchResult.start() = range.first
    private fun MatchResult.end() = range.last + 1

    fun inject(
        documentXml: String,
        annotations: List<Annotation>,
        noteAnnotations: List<Annotation>,
    ): String {
        if (annotations.isEmpty()) return documentXml
        var xml = documentXml

        // Strip any pre-existing comment markers — we always re-inject from the
        // annotation list, so starting fresh prevents duplicates.
        xml = xml.replace(STRIP_CR_START, "")
        xml = xml.replace(STRIP_CR_END, "")
        xml = xml.replace(STRIP_CR_REF, "")

        val noteCommentId = HashMap<String, Int>()
        for (i in noteAnnotations.indices) noteCommentId[noteAnnotations[i].id] = i

        val anchorInsertions = ArrayList<Anchor>()
        val bookmarkInsertions = ArrayList<Bookmark>()

        for (a in annotations) {
            val map = PlainTextMapper.build(xml)
            if (map.plain.isEmpty()) continue

            val loc = Anchoring.locateInPlain(map.plain, a.selectedText, a.prefix, a.suffix, a.position)
                ?: continue

            var runOpens = RUN_OPEN.findAll(xml).toList()
            var runCloses = RUN_CLOSE.findAll(xml).toList()

            val startXmlPos = map.xmlOffsets[loc.start]
            val endXmlPos = map.xmlOffsets[loc.end - 1]

            var sIdx = findRunIdxBS(runOpens, startXmlPos)
            var eIdx = findRunIdxBS(runOpens, endXmlPos)
            if (sIdx < 0 || eIdx < 0) continue

            val startRC = findRunClose(runCloses, runOpens[sIdx]) ?: continue
            val endRC = findRunClose(runCloses, runOpens[eIdx]) ?: continue

            if (a.tool == AnnotationTool.bookmark) {
                bookmarkInsertions.add(Bookmark(a.id, a.selectedText, a.prefix, a.suffix, a.position))
                continue
            }

            val rPrContent = rPrForTool(a.tool)
            if (rPrContent.isEmpty()) continue

            val startOffset = approxCharOffsetInRun(xml, runOpens[sIdx], startRC, startXmlPos)
            val endOffset = approxCharOffsetInRun(xml, runOpens[eIdx], endRC, endXmlPos)
            val startRunLen = getRunPlainText(xml, runOpens[sIdx], startRC).length
            val endRunLen = getRunPlainText(xml, runOpens[eIdx], endRC).length

            val needStartSplit = startOffset > 0 && startRunLen > 1
            val needEndSplit = (endOffset + 1) < endRunLen && endRunLen > 1

            if (sIdx == eIdx) {
                // Same run — split at end first (higher position), then at start.
                if (needEndSplit) {
                    val newXml = splitRunAt(xml, runOpens[sIdx], startRC, endOffset + 1)
                    if (newXml != xml) {
                        xml = newXml
                        runOpens = RUN_OPEN.findAll(xml).toList()
                        runCloses = RUN_CLOSE.findAll(xml).toList()
                    }
                }
                if (needStartSplit) {
                    val ro = runOpens[sIdx]
                    val rc = findRunClose(runCloses, ro)!!
                    val newXml = splitRunAt(xml, ro, rc, startOffset)
                    if (newXml != xml) {
                        xml = newXml
                        runOpens = RUN_OPEN.findAll(xml).toList()
                        runCloses = RUN_CLOSE.findAll(xml).toList()
                        sIdx += 1
                    }
                }
                eIdx = sIdx
            } else {
                // Different runs — split end first (higher XML position).
                if (needEndSplit) {
                    val newXml = splitRunAt(xml, runOpens[eIdx], endRC, endOffset + 1)
                    if (newXml != xml) {
                        xml = newXml
                        runOpens = RUN_OPEN.findAll(xml).toList()
                        runCloses = RUN_CLOSE.findAll(xml).toList()
                    }
                }
                if (needStartSplit) {
                    val ro = runOpens[sIdx]
                    val rc = findRunClose(runCloses, ro)!!
                    val newXml = splitRunAt(xml, ro, rc, startOffset)
                    if (newXml != xml) {
                        xml = newXml
                        runOpens = RUN_OPEN.findAll(xml).toList()
                        runCloses = RUN_CLOSE.findAll(xml).toList()
                        sIdx += 1
                        eIdx += 1
                    }
                }
            }

            // rPr injection into fully-covered runs.
            val rPrInsertions = ArrayList<Ins>()
            for (idx in sIdx..eIdx) {
                val rO = runOpens[idx]
                val rC = findRunClose(runCloses, rO) ?: continue
                val runContent = xml.substring(rO.end(), rC.start())
                val rPrEndIdx = runContent.indexOf("</w:rPr>")
                val wtIdx = if (runContent.contains("<w:t")) runContent.indexOf("<w:t") else runContent.length
                val hasRPr = rPrEndIdx >= 0 && runContent.indexOf("<w:rPr") < wtIdx
                if (hasRPr) {
                    val existingRPr = runContent.substring(0, rPrEndIdx)
                    if (!existingRPr.contains(rPrContent)) {
                        rPrInsertions.add(Ins(rO.end() + rPrEndIdx, rPrContent))
                    }
                } else {
                    rPrInsertions.add(Ins(rO.end(), "<w:rPr>$rPrContent</w:rPr>"))
                }
            }
            rPrInsertions.sortByDescending { it.pos }
            for (ins in rPrInsertions) {
                xml = xml.substring(0, ins.pos) + ins.tag + xml.substring(ins.pos)
            }

            val commentId = noteCommentId[a.id]
            if (commentId != null) {
                anchorInsertions.add(Anchor(commentId, a.selectedText, a.prefix, a.suffix, a.position))
            }
        }

        // Resolve bookmark/comment anchors against the FINAL xml.
        val finalInsertions = ArrayList<Ins>()
        if (anchorInsertions.isNotEmpty() || bookmarkInsertions.isNotEmpty()) {
            val finalMap = PlainTextMapper.build(xml)
            val finalOpens = RUN_OPEN.findAll(xml).toList()
            val finalCloses = RUN_CLOSE.findAll(xml).toList()

            for (bk in bookmarkInsertions) {
                val loc = Anchoring.locateInPlain(finalMap.plain, bk.selectedText, bk.prefix, bk.suffix, bk.position)
                    ?: continue
                val startXmlPos = finalMap.xmlOffsets[loc.start]
                val endXmlPos = finalMap.xmlOffsets[loc.end - 1]
                val sIdx = findRunIdxBS(finalOpens, startXmlPos)
                val eIdx = findRunIdxBS(finalOpens, endXmlPos)
                if (sIdx < 0 || eIdx < 0) continue
                val endRC = findRunClose(finalCloses, finalOpens[eIdx]) ?: continue
                val safeId = bk.annotationId.replace(NON_ID, "_")
                val bkId = 10000 + sIdx
                finalInsertions.add(Ins(finalOpens[sIdx].start(), "<w:bookmarkStart w:id=\"$bkId\" w:name=\"leamh_$safeId\"/>"))
                finalInsertions.add(Ins(endRC.end(), "<w:bookmarkEnd w:id=\"$bkId\"/>"))
            }

            for (anc in anchorInsertions) {
                val loc = Anchoring.locateInPlain(finalMap.plain, anc.selectedText, anc.prefix, anc.suffix, anc.position)
                    ?: continue
                val startXmlPos = finalMap.xmlOffsets[loc.start]
                val endXmlPos = finalMap.xmlOffsets[loc.end - 1]
                val sIdx = findRunIdxBS(finalOpens, startXmlPos)
                val eIdx = findRunIdxBS(finalOpens, endXmlPos)
                if (sIdx < 0 || eIdx < 0) continue
                val eClose = findRunClose(finalCloses, finalOpens[eIdx]) ?: continue
                finalInsertions.add(Ins(finalOpens[sIdx].start(), "<w:commentRangeStart w:id=\"${anc.commentId}\"/>"))
                finalInsertions.add(
                    Ins(
                        eClose.end(),
                        "<w:commentRangeEnd w:id=\"${anc.commentId}\"/>" +
                            "<w:r><w:rPr><w:rStyle w:val=\"CommentReference\"/></w:rPr>" +
                            "<w:commentReference w:id=\"${anc.commentId}\"/></w:r>",
                    ),
                )
            }
        }

        finalInsertions.sortByDescending { it.pos }
        for (ins in finalInsertions) {
            xml = xml.substring(0, ins.pos) + ins.tag + xml.substring(ins.pos)
        }
        return xml
    }

    // ---- run helpers (mirror of docx_store) ----

    private fun findRunIdxBS(runOpens: List<MatchResult>, xmlPos: Int): Int {
        var lo = 0
        var hi = runOpens.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (runOpens[mid].start() <= xmlPos) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }

    private fun findRunClose(runCloses: List<MatchResult>, runOpen: MatchResult): MatchResult? {
        for (m in runCloses) if (m.start() > runOpen.start()) return m
        return null
    }

    private fun getRunPlainText(xml: String, runOpen: MatchResult, runClose: MatchResult): String {
        val runContent = xml.substring(runOpen.end(), runClose.start())
        return WT.findAll(runContent).joinToString("") { XmlEntities.decode(it.groupValues[1]) }
    }

    private fun approxCharOffsetInRun(xml: String, runOpen: MatchResult, runClose: MatchResult, xmlCharPos: Int): Int {
        val runContent = xml.substring(runOpen.end(), runClose.start())
        var charsBefore = 0
        for (wt in WT.findAll(runContent)) {
            val wtContentStart = runOpen.end() + wt.start() + wt.value.indexOf('>') + 1
            val wtRawLen = wt.groupValues[1].length
            if (xmlCharPos >= wtContentStart && xmlCharPos < wtContentStart + wtRawLen) {
                return charsBefore + (xmlCharPos - wtContentStart)
            }
            charsBefore += XmlEntities.decode(wt.groupValues[1]).length
        }
        return charsBefore
    }

    /** Splits a single-`<w:t>` run at charPos (0-indexed in its plain text). */
    private fun splitRunAt(xml: String, runOpen: MatchResult, runClose: MatchResult, charPos: Int): String {
        val runContent = xml.substring(runOpen.end(), runClose.start())
        val wtList = WT.findAll(runContent).toList()
        if (wtList.size != 1) return xml

        val fullText = XmlEntities.decode(wtList[0].groupValues[1])
        if (charPos <= 0 || charPos >= fullText.length) return xml

        // Strip <w:rPrChange> so the non-greedy rPr match doesn't stop at its inner </w:rPr>.
        val rPrChangeStripped = runContent.replace(RPR_CHANGE, "")
        val rPrXml = RPR_BLOCK.find(rPrChangeStripped)?.value ?: ""

        val openTag = xml.substring(runOpen.start(), runOpen.end())
        val t1 = XmlEntities.escape(fullText.substring(0, charPos))
        val t2 = XmlEntities.escape(fullText.substring(charPos))

        val run1 = "$openTag$rPrXml<w:t xml:space=\"preserve\">$t1</w:t></w:r>"
        val run2 = "$openTag$rPrXml<w:t xml:space=\"preserve\">$t2</w:t></w:r>"

        return xml.substring(0, runOpen.start()) + run1 + run2 + xml.substring(runClose.end())
    }
}

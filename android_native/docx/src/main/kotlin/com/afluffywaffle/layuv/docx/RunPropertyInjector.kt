package com.afluffywaffle.layuv.docx

import com.afluffywaffle.layuv.docx.model.Annotation
import com.afluffywaffle.layuv.docx.model.AnnotationTool

/**
 * Injects run properties (`<w:rPr>`) and comment/bookmark anchors into
 * `word/document.xml`. Mirror of docx_store `_injectNativeFormatting` and its
 * run helpers, run over the clean [PlainTextMapper] (byte-identical to the legacy
 * `_buildPlainMap` on prose, which is what the reader produces).
 *
 * Run splitting: `<w:rPr>` applies to a whole `<w:r>`, so when a selection
 * starts/ends mid-run the run is split at that boundary first, then rPr is
 * injected only into the covered fragment(s). Comment/bookmark anchors are
 * collected and applied in one final pass.
 *
 * ## Performance — incremental bookkeeping
 *
 * The decisions (locate, run lookup, split, rPr) are EXACTLY the old sequential
 * algorithm, so the output is byte-identical (proven by [RunPropertyInjectorEquivalenceTest]
 * against a frozen copy, and by the unchanged write goldens). What changed: the old
 * code rebuilt the whole-document plain map + run lists (`PlainTextMapper.build` + two
 * regex scans over the entire file) once PER ANNOTATION — `O(annotations × document
 * length)`, tens of seconds on the e-ink CPU for a long chapter. Here the map + run
 * positions are built ONCE and maintained incrementally as each rPr/split mutation is
 * applied (a suffix shift for an insert; a small local re-derive for a split — the
 * plain text is invariant, so the `xmlOffsets` array never changes size). The
 * canonical-string invariant is untouched. [SELF_CHECK] re-derives the structures from
 * scratch after every mutation in tests, so any bookkeeping drift fails loudly.
 */
object RunPropertyInjector {

    /** Tests set this true: after every mutation, assert the incremental state == a full rebuild. */
    var SELF_CHECK = false

    private val RUN_OPEN = Regex("<w:r(?:\\s[^>]*)?>(?<!/>)")
    private val RUN_CLOSE = Regex("</w:r>")
    private val WT = Regex("<w:t(?:[^>]*)>(.*?)</w:t>", RegexOption.DOT_MATCHES_ALL)

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

    /**
     * The document under mutation: its xml plus the canonical plain text and the
     * parallel-maintained structures the injection loop needs — `offsets[i]` is the
     * xml position of plain char `i` (invariant size), and [runOpens]/[runCloses] are
     * the sorted xml start positions of `<w:r…>` / `</w:r>`. [insert] and [split] are
     * the ONLY mutators; both keep all three structures current.
     */
    private class Doc(initial: String) {
        var xml = initial
            private set
        val plain: String
        val offsets: IntArray
        val runOpens = ArrayList<Int>()
        val runCloses = ArrayList<Int>()

        init {
            val m = PlainTextMapper.build(initial)
            plain = m.plain
            offsets = m.xmlOffsets.copyOf()
            RUN_OPEN.findAll(initial).forEach { runOpens.add(it.range.first) }
            RUN_CLOSE.findAll(initial).forEach { runCloses.add(it.range.first) }
        }

        /** Index of the run whose open is the greatest <= [xmlPos], or -1. */
        fun runIdx(xmlPos: Int): Int {
            var lo = 0; var hi = runOpens.size - 1; var found = -1
            while (lo <= hi) {
                val mid = (lo + hi) / 2
                if (runOpens[mid] <= xmlPos) { found = mid; lo = mid + 1 } else hi = mid - 1
            }
            return found
        }

        /** Start position of the first `</w:r>` after [openPos], or -1. */
        fun closeAfter(openPos: Int): Int {
            for (c in runCloses) if (c > openPos) return c
            return -1
        }

        /** Position just past the run-open tag's `>`. */
        fun openEnd(openPos: Int): Int = xml.indexOf('>', openPos) + 1

        fun runPlainText(openPos: Int, closePos: Int): String {
            val content = xml.substring(openEnd(openPos), closePos)
            return WT.findAll(content).joinToString("") { XmlEntities.decode(it.groupValues[1]) }
        }

        fun charOffsetInRun(openPos: Int, closePos: Int, xmlCharPos: Int): Int {
            val oEnd = openEnd(openPos)
            val content = xml.substring(oEnd, closePos)
            var charsBefore = 0
            for (wt in WT.findAll(content)) {
                val wtContentStart = oEnd + wt.range.first + wt.value.indexOf('>') + 1
                val rawLen = wt.groupValues[1].length
                if (xmlCharPos >= wtContentStart && xmlCharPos < wtContentStart + rawLen) {
                    return charsBefore + (xmlCharPos - wtContentStart)
                }
                charsBefore += XmlEntities.decode(wt.groupValues[1]).length
            }
            return charsBefore
        }

        /** Insert [str] at xml position [pos] (an rPr fragment — adds no run boundary). */
        fun insert(pos: Int, str: String) {
            xml = xml.substring(0, pos) + str + xml.substring(pos)
            val d = str.length
            shiftOffsets(pos, d)
            shiftList(runOpens, pos, d)
            shiftList(runCloses, pos, d)
            if (SELF_CHECK) selfCheck("insert@$pos")
        }

        /**
         * Split the single-`<w:t>` run at [openPos]/[closePos] at run-plain offset [charPos],
         * exactly as the old `splitRunAt`. Returns true if a split happened (false when the
         * run isn't a lone `<w:t>` or [charPos] is at an edge — same no-op condition as before).
         */
        fun split(openPos: Int, closePos: Int, charPos: Int): Boolean {
            val oEnd = openEnd(openPos)
            val runContent = xml.substring(oEnd, closePos)
            val wtList = WT.findAll(runContent).toList()
            if (wtList.size != 1) return false
            val fullText = XmlEntities.decode(wtList[0].groupValues[1])
            if (charPos <= 0 || charPos >= fullText.length) return false

            val openTag = xml.substring(openPos, oEnd)
            val t1 = XmlEntities.escape(fullText.substring(0, charPos))
            val t2 = XmlEntities.escape(fullText.substring(charPos))
            val wtMatch = wtList[0]
            val beforeWt = runContent.substring(0, wtMatch.range.first)
            val afterWt = runContent.substring(wtMatch.range.last + 1)
            val run1 = "$openTag${beforeWt}<w:t xml:space=\"preserve\">$t1</w:t></w:r>"
            val run2 = "$openTag${beforeWt}<w:t xml:space=\"preserve\">$t2</w:t>${afterWt}</w:r>"
            val newRun = run1 + run2

            val closeEnd = closePos + "</w:r>".length
            val delta = newRun.length - (closeEnd - openPos)
            xml = xml.substring(0, openPos) + newRun + xml.substring(closeEnd)

            // Offsets: the run's plain chars are offsets in [openPos, closeEnd) — re-derive
            // them from the rebuilt run (handles escaping + the <w:t xml:space> rewrite), then
            // shift everything after the run by delta.
            val iLo = lowerBoundOffsets(openPos)
            val iHi = lowerBoundOffsets(closeEnd)
            val mini = PlainTextMapper.build(newRun)
            // The split preserves visible text, so the rebuilt run has the same plain chars.
            require(mini.xmlOffsets.size == iHi - iLo) {
                "split plain-length mismatch: ${mini.xmlOffsets.size} vs ${iHi - iLo}"
            }
            for (j in 0 until (iHi - iLo)) offsets[iLo + j] = mini.xmlOffsets[j] + openPos
            for (i in iHi until offsets.size) offsets[i] += delta

            // Run lists: replace the old run's [open, close] with the two new runs' boundaries,
            // shift runs after the region by delta. No other run lies inside [openPos, closeEnd).
            val localOpens = RUN_OPEN.findAll(newRun).map { it.range.first + openPos }.toList()
            val localCloses = RUN_CLOSE.findAll(newRun).map { it.range.first + openPos }.toList()
            replaceRegion(runOpens, openPos, closeEnd, delta, localOpens)
            replaceRegion(runCloses, openPos, closeEnd, delta, localCloses)

            if (SELF_CHECK) selfCheck("split@$openPos+$charPos")
            return true
        }

        private fun shiftOffsets(from: Int, delta: Int) {
            var i = lowerBoundOffsets(from)
            while (i < offsets.size) { offsets[i] += delta; i++ }
        }

        /** First plain index whose offset is >= [v] (offsets are strictly increasing). */
        private fun lowerBoundOffsets(v: Int): Int {
            var lo = 0; var hi = offsets.size
            while (lo < hi) { val m = (lo + hi) ushr 1; if (offsets[m] < v) lo = m + 1 else hi = m }
            return lo
        }

        private fun shiftList(list: MutableList<Int>, from: Int, delta: Int) {
            for (i in list.indices) if (list[i] >= from) list[i] += delta
        }

        /** Drop entries in [start, end), shift entries >= end by [delta], insert [locals]; keep sorted. */
        private fun replaceRegion(list: MutableList<Int>, start: Int, end: Int, delta: Int, locals: List<Int>) {
            val rebuilt = ArrayList<Int>(list.size + locals.size)
            for (p in list) if (p < start) rebuilt.add(p)
            rebuilt.addAll(locals)
            for (p in list) if (p >= end) rebuilt.add(p + delta)
            list.clear(); list.addAll(rebuilt)
        }

        private fun selfCheck(label: String) {
            val m = PlainTextMapper.build(xml)
            require(m.plain == plain) { "self-check plain changed after $label" }
            require(m.xmlOffsets.contentEquals(offsets)) { "self-check offsets mismatch after $label" }
            val ro = RUN_OPEN.findAll(xml).map { it.range.first }.toList()
            val rc = RUN_CLOSE.findAll(xml).map { it.range.first }.toList()
            require(ro == runOpens) { "self-check runOpens mismatch after $label" }
            require(rc == runCloses) { "self-check runCloses mismatch after $label" }
        }
    }

    fun inject(
        documentXml: String,
        annotations: List<Annotation>,
        noteAnnotations: List<Annotation>,
    ): String {
        if (annotations.isEmpty()) return documentXml

        // Strip any pre-existing comment markers — we always re-inject from the
        // annotation list, so starting fresh prevents duplicates.
        var stripped = documentXml
        stripped = stripped.replace(STRIP_CR_START, "")
        stripped = stripped.replace(STRIP_CR_END, "")
        stripped = stripped.replace(STRIP_CR_REF, "")

        val noteCommentId = HashMap<String, Int>()
        for (i in noteAnnotations.indices) noteCommentId[noteAnnotations[i].id] = i

        val anchorInsertions = ArrayList<Anchor>()
        val bookmarkInsertions = ArrayList<Bookmark>()

        val doc = Doc(stripped)
        if (doc.plain.isNotEmpty()) {
            for (a in annotations) {
                val loc = Anchoring.locateInPlain(doc.plain, a.selectedText, a.prefix, a.suffix, a.position)
                    ?: continue
                val startXmlPos = doc.offsets[loc.start]
                val endXmlPos = doc.offsets[loc.end - 1]

                var sIdx = doc.runIdx(startXmlPos)
                var eIdx = doc.runIdx(endXmlPos)
                if (sIdx < 0 || eIdx < 0) continue
                val startRC = doc.closeAfter(doc.runOpens[sIdx]).takeIf { it >= 0 } ?: continue
                val endRC = doc.closeAfter(doc.runOpens[eIdx]).takeIf { it >= 0 } ?: continue

                if (a.tool == AnnotationTool.bookmark) {
                    bookmarkInsertions.add(Bookmark(a.id, a.selectedText, a.prefix, a.suffix, a.position))
                    continue
                }
                val rPrContent = rPrForTool(a.tool)
                if (rPrContent.isEmpty()) continue

                val startOffset = doc.charOffsetInRun(doc.runOpens[sIdx], startRC, startXmlPos)
                val endOffset = doc.charOffsetInRun(doc.runOpens[eIdx], endRC, endXmlPos)
                val startRunLen = doc.runPlainText(doc.runOpens[sIdx], startRC).length
                val endRunLen = doc.runPlainText(doc.runOpens[eIdx], endRC).length

                val needStartSplit = startOffset > 0 && startRunLen > 1
                val needEndSplit = (endOffset + 1) < endRunLen && endRunLen > 1

                if (sIdx == eIdx) {
                    // Same run — split at end first (higher position), then at start.
                    if (needEndSplit) doc.split(doc.runOpens[sIdx], startRC, endOffset + 1)
                    if (needStartSplit) {
                        val ro = doc.runOpens[sIdx]
                        val rc = doc.closeAfter(ro)
                        if (rc >= 0 && doc.split(ro, rc, startOffset)) sIdx += 1
                    }
                    eIdx = sIdx
                } else {
                    // Different runs — split end first (higher XML position).
                    if (needEndSplit) doc.split(doc.runOpens[eIdx], endRC, endOffset + 1)
                    if (needStartSplit) {
                        val ro = doc.runOpens[sIdx]
                        val rc = doc.closeAfter(ro)
                        if (rc >= 0 && doc.split(ro, rc, startOffset)) { sIdx += 1; eIdx += 1 }
                    }
                }

                // rPr injection into fully-covered runs.
                val rPrInsertions = ArrayList<Ins>()
                for (idx in sIdx..eIdx) {
                    val rO = doc.runOpens[idx]
                    val rC = doc.closeAfter(rO)
                    if (rC < 0) continue
                    val rOEnd = doc.openEnd(rO)
                    val runContent = doc.xml.substring(rOEnd, rC)
                    val rPrEndIdx = runContent.indexOf("</w:rPr>")
                    val wtIdx = if (runContent.contains("<w:t")) runContent.indexOf("<w:t") else runContent.length
                    val hasRPr = rPrEndIdx >= 0 && runContent.indexOf("<w:rPr") < wtIdx
                    if (hasRPr) {
                        val existingRPr = runContent.substring(0, rPrEndIdx)
                        if (!existingRPr.contains(rPrContent)) {
                            rPrInsertions.add(Ins(rOEnd + rPrEndIdx, rPrContent))
                        }
                    } else {
                        rPrInsertions.add(Ins(rOEnd, "<w:rPr>$rPrContent</w:rPr>"))
                    }
                }
                rPrInsertions.sortByDescending { it.pos }
                for (ins in rPrInsertions) doc.insert(ins.pos, ins.tag)

                val commentId = noteCommentId[a.id]
                if (commentId != null) {
                    anchorInsertions.add(Anchor(commentId, a.selectedText, a.prefix, a.suffix, a.position))
                }
            }
        }

        var xml = doc.xml

        // Resolve bookmark/comment anchors against the FINAL xml.
        val finalInsertions = ArrayList<Ins>()
        if (anchorInsertions.isNotEmpty() || bookmarkInsertions.isNotEmpty()) {
            val finalMap = PlainTextMapper.build(xml)
            val finalOpens = RUN_OPEN.findAll(xml).toList()
            val finalCloses = RUN_CLOSE.findAll(xml).toList()

            bookmarkInsertions.forEachIndexed { bkIdx, bk ->
                val loc = Anchoring.locateInPlain(finalMap.plain, bk.selectedText, bk.prefix, bk.suffix, bk.position)
                    ?: return@forEachIndexed
                val startXmlPos = finalMap.xmlOffsets[loc.start]
                val endXmlPos = finalMap.xmlOffsets[loc.end - 1]
                val sIdx = findRunIdxBS(finalOpens, startXmlPos)
                val eIdx = findRunIdxBS(finalOpens, endXmlPos)
                if (sIdx < 0 || eIdx < 0) return@forEachIndexed
                val endRC = findRunClose(finalCloses, finalOpens[eIdx]) ?: return@forEachIndexed
                val safeId = bk.annotationId.replace(NON_ID, "_")
                val bkId = 100000 + bkIdx
                finalInsertions.add(Ins(finalOpens[sIdx].range.first, "<w:bookmarkStart w:id=\"$bkId\" w:name=\"leamh_$safeId\"/>"))
                finalInsertions.add(Ins(endRC.range.last + 1, "<w:bookmarkEnd w:id=\"$bkId\"/>"))
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
                finalInsertions.add(Ins(finalOpens[sIdx].range.first, "<w:commentRangeStart w:id=\"${anc.commentId}\"/>"))
                finalInsertions.add(
                    Ins(
                        eClose.range.last + 1,
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

    // ---- anchor-phase helpers (operate on the final-pass MatchResult lists) ----

    private fun findRunIdxBS(runOpens: List<MatchResult>, xmlPos: Int): Int {
        var lo = 0; var hi = runOpens.size - 1; var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (runOpens[mid].range.first <= xmlPos) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        return found
    }

    private fun findRunClose(runCloses: List<MatchResult>, runOpen: MatchResult): MatchResult? {
        for (m in runCloses) if (m.range.first > runOpen.range.first) return m
        return null
    }
}

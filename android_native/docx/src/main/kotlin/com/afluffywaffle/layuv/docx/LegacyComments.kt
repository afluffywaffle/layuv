package com.afluffywaffle.layuv.docx

import com.afluffywaffle.layuv.docx.model.Annotation
import com.afluffywaffle.layuv.docx.model.AnnotationTag
import com.afluffywaffle.layuv.docx.model.AnnotationTool
import com.afluffywaffle.layuv.docx.model.ThreadEntry
import com.afluffywaffle.layuv.docx.model.Timestamps
import java.time.Instant

/**
 * Imports `word/comments.xml` — both the legacy Léamh comment format
 * (`[tool:X] [tag:Y] N% — "text"`, author = annotation id) and native Word
 * comments (resolved against the document's `<w:commentRangeStart/End>`). The
 * read fallback used when `leamh/annotations.json` is absent.
 *
 * Word reply threads (described by `word/commentsExtended.xml` via
 * `w15:paraIdParent`) are flattened on import: a reply does NOT become its own
 * [Annotation]; instead it becomes a `source="word"` [ThreadEntry] on its parent
 * comment's annotation. This is a one-time conversion — Léamh's flat thread
 * format is the source of truth from then on.
 *
 * Mirror of docx_store `_parseComments` + `_extractFromCommentRange`.
 */
object LegacyComments {

    private val COMMENT = Regex("<w:comment\\s([^>]*)>(.*?)</w:comment>", RegexOption.DOT_MATCHES_ALL)
    private val ID_ATTR = Regex("w:id=\"([^\"]*)\"")
    private val AUTHOR_ATTR = Regex("w:author=\"([^\"]*)\"")
    private val DATE_ATTR = Regex("w:date=\"([^\"]*)\"")
    private val WT = Regex("<w:t[^>]*>(.*?)</w:t>", RegexOption.DOT_MATCHES_ALL)

    // commentsExtended.xml threading. `paraId="..."` matches only the entry's own
    // id (never `paraIdParent="..."`, which has "Parent" between "paraId" and "=").
    private val COMMENT_EX = Regex("<w15:commentEx\\b([^>]*)>")
    private val PARA_ID = Regex("paraId=\"([^\"]*)\"")
    private val PARA_ID_PARENT = Regex("paraIdParent=\"([^\"]*)\"")

    // [tool:X] [tag:Y] N% — "text"   (— is U+2014 em-dash)
    private val LEGACY = Regex(
        "\\[tool:(\\w+)\\](?:\\s\\[tag:(\\w+)\\])?\\s(\\d+)%\\s—\\s\"(.*)\"",
        RegexOption.DOT_MATCHES_ALL,
    )

    /** A single parsed `<w:comment>`, before deciding annotation-vs-reply. */
    private data class RawComment(
        val commentId: String,
        // Every `w14:paraId` in the comment body. Word puts one per paragraph and
        // a reply's `w15:paraIdParent` may reference any of them (commonly the
        // last), so we index them all rather than just the first.
        val paraIds: List<String>,
        val authorRaw: String,
        val timestamp: Instant,
        val texts: List<String>,
    )

    fun parseComments(
        commentsXml: String,
        documentXml: String,
        map: PlainMap,
        commentsExtendedXml: String? = null,
    ): List<Annotation> {
        // 1. Word reply links: a reply comment's own paraId -> its parent's paraId.
        val replyParentByParaId = parseReplyLinks(commentsExtendedXml)

        // 2. Parse every <w:comment> once, indexing every paraId -> its comment id.
        val raws = ArrayList<RawComment>()
        val paraIdToCommentId = HashMap<String, String>()
        for (cm in COMMENT.findAll(commentsXml)) {
            val rc = try { parseRawComment(cm) } catch (e: Exception) { null } ?: continue
            raws.add(rc)
            for (pid in rc.paraIds) paraIdToCommentId[pid] = rc.commentId
        }

        // 3. Map every reply to the ROOT (non-reply) ancestor of its chain, so
        //    multi-level threads (P <- R1 <- R2) flatten fully onto the root P
        //    rather than dropping deeper replies.
        val immediateParent = HashMap<String, String>() // replyCommentId -> immediate parent commentId
        if (replyParentByParaId.isNotEmpty()) {
            for (rc in raws) {
                val parentParaId = rc.paraIds.firstNotNullOfOrNull { replyParentByParaId[it] } ?: continue
                val parentCid = paraIdToCommentId[parentParaId] ?: continue
                if (parentCid != rc.commentId) immediateParent[rc.commentId] = parentCid
            }
        }
        val replyIds = immediateParent.keys
        val rootOf = HashMap<String, String>() // replyCommentId -> root (non-reply) commentId
        for (replyId in replyIds) {
            var cur = replyId
            var steps = 0
            // Walk up to the first non-reply ancestor; bound steps to defuse cycles.
            while (cur in immediateParent && steps <= raws.size) {
                cur = immediateParent.getValue(cur)
                steps++
            }
            rootOf[replyId] = cur
        }
        val repliesByRoot: Map<String, List<RawComment>> =
            if (replyIds.isEmpty()) emptyMap()
            else raws.filter { it.commentId in replyIds }.groupBy { rootOf.getValue(it.commentId) }

        // 4. Build annotations for the non-reply comments, attaching reply threads.
        val results = ArrayList<Annotation>()
        for (rc in raws) {
            if (rc.commentId in replyIds) continue // flattened into its root below
            try {
                val ann = buildAnnotation(rc, documentXml, map) ?: continue
                val replies = repliesByRoot[rc.commentId]
                results.add(
                    if (replies.isNullOrEmpty()) ann
                    else ann.copy(threadEntries = buildThread(ann, rc, replies)),
                )
            } catch (e: Exception) {
                // skip malformed comment
            }
        }
        results.sortBy { it.position }
        return results
    }

    /** child paraId -> parent paraId, for every `<w15:commentEx>` with a parent. */
    private fun parseReplyLinks(commentsExtendedXml: String?): Map<String, String> {
        if (commentsExtendedXml == null) return emptyMap()
        val links = HashMap<String, String>()
        for (m in COMMENT_EX.findAll(commentsExtendedXml)) {
            val attrs = m.groupValues[1]
            val parent = PARA_ID_PARENT.find(attrs)?.groupValues?.get(1) ?: continue
            val paraId = PARA_ID.find(attrs)?.groupValues?.get(1) ?: continue
            links[paraId] = parent
        }
        return links
    }

    private fun parseRawComment(cm: MatchResult): RawComment? {
        val attrs = cm.groupValues[1]
        val body = cm.groupValues[2]
        val commentId = ID_ATTR.find(attrs)?.groupValues?.get(1) ?: ""
        val authorRaw = XmlEntities.decode(AUTHOR_ATTR.find(attrs)?.groupValues?.get(1) ?: "")
        val dateStr = DATE_ATTR.find(attrs)?.groupValues?.get(1) ?: return null
        val texts = WT.findAll(body).map { XmlEntities.decode(it.groupValues[1]) }.toList()
        if (texts.isEmpty()) return null
        return RawComment(
            commentId = commentId,
            paraIds = PARA_ID.findAll(body).map { it.groupValues[1] }.toList(),
            authorRaw = authorRaw,
            timestamp = Timestamps.parse(dateStr),
            texts = texts,
        )
    }

    /** Builds the standalone [Annotation] for a non-reply comment (legacy or native). */
    private fun buildAnnotation(rc: RawComment, documentXml: String, map: PlainMap): Annotation? {
        val legacyMatch = rc.texts.firstNotNullOfOrNull { LEGACY.find(it) }
        if (legacyMatch != null && rc.authorRaw.isNotEmpty()) {
            val tool = AnnotationTool.fromName(legacyMatch.groupValues[1])
            val tagName = legacyMatch.groupValues[2]
            val tag = if (tagName.isNotEmpty()) AnnotationTag.fromName(tagName) else null
            val headerIdx = rc.texts.indexOfFirst { LEGACY.containsMatchIn(it) }
            val noteTexts = rc.texts.subList(headerIdx + 1, rc.texts.size)
            val note = if (noteTexts.isNotEmpty()) noteTexts.joinToString("\n") else null
            return Annotation(
                id = rc.authorRaw,
                selectedText = legacyMatch.groupValues[4],
                prefix = "",
                suffix = "",
                tool = tool,
                tag = tag,
                note = if (note.isNullOrEmpty()) null else note,
                timestamp = rc.timestamp,
                position = legacyMatch.groupValues[3].toInt() / 100.0,
                paragraph = PlainTextMapper.paragraphIndex(
                    map.plain,
                    (legacyMatch.groupValues[3].toInt() / 100.0 * map.plain.length).toInt(),
                ),
            )
        }

        // Native Word comment.
        if (rc.commentId.isEmpty()) return null
        val ex = extractFromCommentRange(documentXml, rc.commentId, map)
        if (ex.text.isEmpty()) return null
        val note = nativeNoteText(rc.texts)
        return Annotation(
            id = "word_${rc.commentId}",
            selectedText = ex.text,
            prefix = ex.prefix,
            suffix = ex.suffix,
            tool = AnnotationTool.comment,
            note = note.ifEmpty { null },
            timestamp = rc.timestamp,
            position = ex.position,
            paragraph = ex.paragraph,
        )
    }

    /**
     * Flattens a parent comment plus its Word replies into a chronological thread.
     * Entry 0 is the parent's own text (== the annotation's [Annotation.note]);
     * each reply follows in timestamp order. All are `source="word"` (read-only
     * in Léamh until the user replies).
     */
    private fun buildThread(parent: Annotation, parentRaw: RawComment, replies: List<RawComment>): List<ThreadEntry> {
        val entries = ArrayList<ThreadEntry>()
        // Entry 0 is the parent's own text (== note); skip it if the parent body
        // was empty so the thread never leads with a blank comment.
        val parentText = parent.note
        if (!parentText.isNullOrEmpty()) {
            entries.add(ThreadEntry(parentText, parentRaw.timestamp.toEpochMilli(), ThreadEntry.SOURCE_WORD))
        }
        for (r in replies.sortedBy { it.timestamp }) {
            val text = nativeNoteText(r.texts)
            if (text.isNotEmpty()) {
                entries.add(ThreadEntry(text, r.timestamp.toEpochMilli(), ThreadEntry.SOURCE_WORD))
            }
        }
        return entries
    }

    private fun nativeNoteText(texts: List<String>): String =
        texts.filter { it.trim().isNotEmpty() }.joinToString(" ").trim()

    private data class Extracted(
        val text: String,
        val prefix: String,
        val suffix: String,
        val position: Double,
        val paragraph: Int = 0,
    )

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
            paragraph = PlainTextMapper.paragraphIndex(plain, plainIdx),
        )
    }
}

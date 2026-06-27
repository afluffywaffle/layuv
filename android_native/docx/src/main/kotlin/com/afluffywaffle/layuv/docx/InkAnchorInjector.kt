package com.afluffywaffle.layuv.docx

import com.afluffywaffle.layuv.docx.model.Annotation

/**
 * Inserts an inline-drawing paragraph (`<wp:inline>`) into `word/document.xml`
 * immediately after each paragraph that contains a `<w:commentRangeStart>` for an
 * ink annotation.
 *
 * This makes ink images visible in Pages and Google Docs, which do not render
 * images embedded inside `word/comments.xml` comment bodies. The drawing paragraph
 * references `word/_rels/document.xml.rels` (via [InkDrawing.docRelId]) — a
 * separate rel entry from the one in `comments.xml.rels`.
 *
 * Drawing IDs start at 200_000 to stay clear of any doc-native drawings (which
 * are typically in the low thousands) and the comment-side IDs (1-based, also low).
 *
 * Called from [DocxStore.writeIntoEntries] after [RunPropertyInjector.inject].
 */
internal object InkAnchorInjector {

    private val COMMENT_RANGE_START_ID = Regex("""<w:commentRangeStart\s+w:id="(\d+)"\s*/>""")
    private val PARA_END = Regex("""</w:p>""")

    fun inject(
        documentXml: String,
        inkAnnotations: List<Annotation>,
        commentIdMap: Map<String, Int>, // annotationId → commentId assigned in this write
    ): String {
        if (inkAnnotations.isEmpty()) return documentXml

        val inkByCommentId: Map<Int, Annotation> = inkAnnotations
            .mapNotNull { a -> commentIdMap[a.id]?.let { id -> id to a } }
            .toMap()
        if (inkByCommentId.isEmpty()) return documentXml

        // Collect (insertionPoint, drawingXml) pairs — one per ink annotation found.
        // drawingId must be unique within document.xml; base 200_000 avoids collisions.
        val insertions = mutableListOf<Pair<Int, String>>()
        var drawingId = 200_000

        for (match in COMMENT_RANGE_START_ID.findAll(documentXml)) {
            val commentId = match.groupValues[1].toIntOrNull() ?: continue
            val ann = inkByCommentId[commentId] ?: continue

            // Find the </w:p> that closes the paragraph containing this commentRangeStart.
            val paraEnd = PARA_END.find(documentXml, match.range.last + 1) ?: continue

            // Guard: if the anchor paragraph is inside a table cell (<w:tc>), the found
            // </w:p> closes the cell paragraph. Insert after </w:tbl> instead so the
            // drawing paragraph lands in the body stream, not inside the cell.
            val afterPara = paraEnd.range.last + 1
            val insertAt = run {
                val tcClose  = documentXml.indexOf("</w:tc>", afterPara)
                val nextPara = documentXml.indexOf("<w:p", afterPara)
                if (tcClose >= 0 && (nextPara < 0 || tcClose < nextPara)) {
                    val tblClose = documentXml.indexOf("</w:tbl>", afterPara)
                    if (tblClose >= 0) tblClose + "</w:tbl>".length else afterPara
                } else {
                    afterPara
                }
            }

            val relId = InkDrawing.docRelId(ann.id)
            insertions.add(insertAt to InkDrawing.build(relId, drawingId++))
        }

        if (insertions.isEmpty()) return documentXml

        // Apply in reverse order so earlier offsets remain valid.
        insertions.sortByDescending { it.first }
        val sb = StringBuilder(documentXml)
        for ((pos, xml) in insertions) {
            sb.insert(pos, xml)
        }
        // The injected drawing uses the wp:/a:/pic:/r: prefixes (see InkDrawing.build).
        // word/comments.xml declares these on its <w:comments> root, but the document
        // body root often declares only xmlns:w (Léamh drafts, Pages/GDocs exports), so
        // the drawing would reference undeclared prefixes → malformed OOXML (Word repair
        // prompt, Pages/GDocs drop the image). Ensure they exist on <w:document>.
        return ensureDrawingNamespaces(sb.toString())
    }

    // Drawing namespaces the inline <wp:inline> markup depends on. Mirrors the
    // declarations CommentWriter puts on the <w:comments> root.
    private val DRAWING_NAMESPACES = listOf(
        "wp" to "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing",
        "a" to "http://schemas.openxmlformats.org/drawingml/2006/main",
        "pic" to "http://schemas.openxmlformats.org/drawingml/2006/picture",
        "r" to "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
    )

    /**
     * Inserts any MISSING `xmlns:wp/a/pic/r` declarations onto the `<w:document …>`
     * open tag. Idempotent: a prefix already declared is left untouched, so repeated
     * writes (the clean-snapshot/re-inject cycle) never accumulate duplicates.
     */
    private fun ensureDrawingNamespaces(documentXml: String): String {
        val tagStart = documentXml.indexOf("<w:document")
        if (tagStart < 0) return documentXml
        val tagEnd = documentXml.indexOf('>', tagStart)
        if (tagEnd < 0) return documentXml

        val openTag = documentXml.substring(tagStart, tagEnd) // excludes the closing '>'
        val additions = StringBuilder()
        for ((prefix, uri) in DRAWING_NAMESPACES) {
            if (!openTag.contains("xmlns:$prefix=")) {
                additions.append(" xmlns:$prefix=\"$uri\"")
            }
        }
        if (additions.isEmpty()) return documentXml
        return documentXml.substring(0, tagEnd) + additions + documentXml.substring(tagEnd)
    }
}

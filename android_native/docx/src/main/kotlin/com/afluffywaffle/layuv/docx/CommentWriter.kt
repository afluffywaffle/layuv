package com.afluffywaffle.layuv.docx

import com.afluffywaffle.layuv.docx.model.Annotation
import com.afluffywaffle.layuv.docx.model.Timestamps

/**
 * Builds `word/comments.xml` (one `<w:comment>` per annotation with a note, tag,
 * or ink) and the comment-related relationship/content entries. Mirror of
 * docx_store `_buildNoteComment`, the comments.xml assembly in
 * `_writeAllAnnotations`, `_ensureRelsEntry`, and `_ensureCommentsRels`.
 */
object CommentWriter {

    /** A `<w:comment>` whose author is the annotation id; body = note/thread, [tag], ink drawing. */
    fun buildNoteComment(xmlId: Int, a: Annotation, inkRelId: String?): String {
        // When the annotation carries a thread, each entry is its own paragraph:
        // the first (== note) is plain; later entries (replies / added comments)
        // are prefixed with their write time so Word/Pages readers see the thread.
        // With no thread, behaviour is unchanged — a single note paragraph.
        val noteXml = if (a.threadEntries.isNotEmpty()) {
            buildString {
                a.threadEntries.forEachIndexed { i, entry ->
                    val text = if (i == 0) {
                        entry.text
                    } else {
                        "[${Timestamps.formatThreadPrefix(entry.timestamp)}] ${entry.text}"
                    }
                    if (text.isNotEmpty()) {
                        append("<w:p><w:r><w:t xml:space=\"preserve\">${XmlEntities.escape(text)}</w:t></w:r></w:p>")
                    }
                }
            }
        } else {
            val note = a.note
            if (note != null && note.isNotEmpty()) {
                "<w:p><w:r><w:t xml:space=\"preserve\">${XmlEntities.escape(note)}</w:t></w:r></w:p>"
            } else {
                ""
            }
        }
        val tagXml = if (a.tag != null) {
            "<w:p><w:r><w:t xml:space=\"preserve\">[${a.tag.name}]</w:t></w:r></w:p>"
        } else {
            ""
        }
        val drawingXml = if (inkRelId != null) InkDrawing.build(inkRelId, xmlId + 1) else ""
        val bodyParts = listOf(noteXml, tagXml, drawingXml).filter { it.isNotEmpty() }
        val bodyXml = if (bodyParts.isEmpty()) "<w:p/>" else bodyParts.joinToString("")

        return "<w:comment w:id=\"$xmlId\" w:author=\"${XmlEntities.escape(a.id)}\"" +
            " w:date=\"${Timestamps.format(a.timestamp)}\">\n" +
            "  <w:p>\n" +
            "    <w:pPr><w:pStyle w:val=\"CommentText\"/></w:pPr>\n" +
            "    <w:r><w:rPr><w:rStyle w:val=\"CommentReference\"/></w:rPr>" +
            "<w:annotationRef/></w:r>\n" +
            "  </w:p>\n" +
            "  $bodyXml</w:comment>"
    }

    fun buildCommentsXml(commentAnnotations: List<Annotation>): String {
        val blocks = commentAnnotations.mapIndexed { i, a ->
            buildNoteComment(i, a, if (a.hasInk) InkDrawing.relId(a.id) else null)
        }.joinToString("\n")
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<w:comments" +
            " xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"" +
            " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"" +
            " xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\"" +
            " xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"" +
            " xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"" +
            " xmlns:w14=\"http://schemas.microsoft.com/office/word/2010/wordml\"" +
            ">\n" +
            "$blocks\n" +
            "</w:comments>"
    }

    const val EMPTY_COMMENTS =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<w:comments" +
            " xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"/>"

    private val COMMENT_EX_CHILD = Regex(
        "<w15:commentEx\\b[^>]*/>|<w15:commentEx\\b[\\s\\S]*?</w15:commentEx>",
    )

    /**
     * Strips every `<w15:commentEx>` child from a `word/commentsExtended.xml`
     * payload, leaving the root element (and its namespaces) intact.
     *
     * Word's comment-threading sidecar links replies to parents by `w:paraId`.
     * Léamh rebuilds `comments.xml` paragraphs without those ids, so any retained
     * `commentEx` entry would dangle. We empty the part (rather than delete it) so
     * its existing content-type override and document relationship stay valid.
     */
    fun emptyCommentsExtended(raw: String): String = raw.replace(COMMENT_EX_CHILD, "")

    /** Adds the document→comments relationship if not already present. */
    fun ensureRelsEntry(raw: String): String {
        if (raw.contains("comments.xml")) return raw
        val rel = "<Relationship Id=\"rId_leamh_comments\"" +
            " Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/comments\"" +
            " Target=\"comments.xml\"/>"
        // Expand self-closed root so replaceFirst finds its target.
        val expanded = raw.replace("<Relationships/>", "<Relationships></Relationships>")
        return expanded.replaceFirst("</Relationships>", "$rel\n</Relationships>")
    }

    private val INK_REL_PATTERN = Regex("<Relationship[^>]+rId_ink_[^>]*/?>")
    private val INK_DOC_REL_PATTERN = Regex("<Relationship[^>]+rId_ink_doc_[^>]*/?>")
    private val ANY_REL_PATTERN = Regex("<Relationship [^>]*/?>")

    /**
     * Ensures `word/_rels/document.xml.rels` contains image relationships for
     * [inkAnnotations] using [InkDrawing.docRelId]. Existing Léamh ink-doc rels
     * are stripped and rebuilt; all other entries are preserved.
     */
    fun ensureDocInkRels(raw: String, inkAnnotations: List<Annotation>): String {
        val stripped = raw.replace(INK_DOC_REL_PATTERN, "")
        val newEntries = inkAnnotations.joinToString("\n") { a ->
            "<Relationship Id=\"${InkDrawing.docRelId(a.id)}\"" +
                " Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\"" +
                " Target=\"media/ink_${a.id}.png\"/>"
        }
        val expanded = stripped.replace("<Relationships/>", "<Relationships></Relationships>")
        return expanded.replaceFirst("</Relationships>", "$newEntries\n</Relationships>")
    }

    /**
     * Rebuilds `word/_rels/comments.xml.rels` for [inkAnnotations].
     *
     * [existingRels] is the current file content (if any). Pre-existing rels that
     * are NOT Léamh ink entries are preserved so foreign-DOCX comment images survive
     * the write-back.
     */
    fun buildCommentsRels(inkAnnotations: List<Annotation>, existingRels: String? = null): String {
        val preserved = if (existingRels != null) {
            val stripped = existingRels.replace(INK_REL_PATTERN, "")
            ANY_REL_PATTERN.findAll(stripped).map { it.value }.toList()
        } else {
            emptyList()
        }
        val newEntries = inkAnnotations.map { a ->
            "<Relationship Id=\"${InkDrawing.relId(a.id)}\"" +
                " Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\"" +
                " Target=\"media/ink_${a.id}.png\"/>"
        }
        val allEntries = (preserved + newEntries).joinToString("\n")
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<Relationships" +
            " xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">\n" +
            "$allEntries\n" +
            "</Relationships>"
    }
}

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

    /** A `<w:comment>` whose author is the annotation id; body = note, [tag], ink drawing. */
    fun buildNoteComment(xmlId: Int, a: Annotation, inkRelId: String?): String {
        val note = a.note
        val noteXml = if (note != null && note.isNotEmpty()) {
            "<w:p><w:r><w:t xml:space=\"preserve\">${XmlEntities.escape(note)}</w:t></w:r></w:p>"
        } else {
            ""
        }
        val tagXml = if (a.tag != null) {
            "<w:p><w:r><w:t xml:space=\"preserve\">[${a.tag.name}]</w:t></w:r></w:p>"
        } else {
            ""
        }
        val drawingXml = if (inkRelId != null) InkDrawing.build(inkRelId) else ""
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

    /** Adds the document→comments relationship if not already present. */
    fun ensureRelsEntry(raw: String): String {
        if (raw.contains("comments.xml")) return raw
        val rel = "<Relationship Id=\"rId_leamh_comments\"" +
            " Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/comments\"" +
            " Target=\"comments.xml\"/>"
        return raw.replaceFirst("</Relationships>", "$rel\n</Relationships>")
    }

    /** Full `word/_rels/comments.xml.rels` mapping each ink rel-id to its media PNG. */
    fun buildCommentsRels(inkAnnotations: List<Annotation>): String {
        val entries = inkAnnotations.joinToString("\n") { a ->
            "<Relationship Id=\"${InkDrawing.relId(a.id)}\"" +
                " Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\"" +
                " Target=\"media/ink_${a.id}.png\"/>"
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<Relationships" +
            " xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">\n" +
            "$entries\n" +
            "</Relationships>"
    }
}

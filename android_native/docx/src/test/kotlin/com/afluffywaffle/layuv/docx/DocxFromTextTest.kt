package com.afluffywaffle.layuv.docx

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Builds a small but realistic annotated/conversational DOCX in-memory: the
 * structural parts Word needs, the comment family, ink media, and every Léamh
 * sidecar. Shared with [DocxStoreAiChatTest] (same package).
 */
fun buildAnnotatedDocxFixture(): ByteArray {
    val doc =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
        "<w:body>" +
        "<w:p><w:r><w:t>Old paragraph one.</w:t></w:r></w:p>" +
        "<w:p><w:r><w:t>Old paragraph two.</w:t></w:r></w:p>" +
        "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>" +
        "<w:pgMar w:top=\"1440\" w:bottom=\"1440\" w:left=\"1440\" w:right=\"1440\"/></w:sectPr>" +
        "</w:body></w:document>"
    val rels =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>" +
        "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/comments\" Target=\"comments.xml\"/>" +
        "</Relationships>"
    val contentTypes =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
        "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
        "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
        "<Default Extension=\"json\" ContentType=\"application/json\"/>" +
        "<Default Extension=\"png\" ContentType=\"image/png\"/>" +
        "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
        "<Override PartName=\"/word/comments.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.comments+xml\"/>" +
        "</Types>"

    fun b(s: String) = s.toByteArray(Charsets.UTF_8)
    val entries = LinkedHashMap<String, ByteArray>()
    entries["[Content_Types].xml"] = b(contentTypes)
    entries["_rels/.rels"] = b("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"/>")
    entries["word/document.xml"] = b(doc)
    entries["leamh/document_clean.xml"] = b(doc)
    entries["word/styles.xml"] = b("<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"></w:styles>")
    entries["word/_rels/document.xml.rels"] = b(rels)
    entries["word/comments.xml"] = b("<w:comments xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"></w:comments>")
    entries["word/commentsExtended.xml"] = b("<w15:commentsEx xmlns:w15=\"x\"></w15:commentsEx>")
    entries["word/_rels/comments.xml.rels"] = b("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"/>")
    entries["leamh/annotations.json"] = b("[]")
    entries["leamh/position.json"] = b("{}")
    entries["leamh/aichat.json"] = b("[{\"role\":\"user\",\"text\":\"hi\",\"truncated\":false}]")
    entries["word/media/ink_123.png"] = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
    return DocxArchive.write(entries)
}

class DocxFromTextTest {

    @Test
    fun stripsSidecarsRegeneratesBodyAndPreservesStructure() {
        val out = DocxFromText.build(buildAnnotatedDocxFixture(), "Para one.\n\nPara two.")
        val arc = DocxArchive.read(out)

        // Every annotated/conversational sidecar is gone.
        for (gone in listOf(
            "leamh/annotations.json", "leamh/position.json", "leamh/document_clean.xml",
            "leamh/aichat.json", "word/comments.xml", "word/commentsExtended.xml",
            "word/_rels/comments.xml.rels",
        )) {
            assertFalse(arc.contains(gone), "should strip $gone")
        }
        assertTrue(arc.names.none { it.startsWith("word/media/ink_") }, "ink media stripped")

        // Structural parts cloned from the source survive.
        assertTrue(arc.contains("word/styles.xml"), "styles.xml preserved")
        assertTrue(arc.contains("[Content_Types].xml"))

        // Body regenerated and re-parseable by the canonical extractor.
        val doc = arc.text("word/document.xml")!!
        assertEquals("Para one.\nPara two.\n", PlainTextMapper.build(doc).plain)

        // Body-level sectPr (page size/margins) preserved verbatim.
        assertTrue(doc.contains("<w:sectPr"), "sectPr preserved")
        assertTrue(doc.contains("w:w=\"11906\""), "page size preserved")

        // Comments relationship stripped from document.xml.rels; styles kept.
        val rels = arc.text("word/_rels/document.xml.rels")!!
        assertFalse(rels.contains("/comments\""), "comments rel stripped")
        assertTrue(rels.contains("/styles\""), "styles rel kept")
    }

    @Test
    fun reopensCleanWithZeroAnnotations() {
        val out = DocxFromText.build(buildAnnotatedDocxFixture(), "Para one.\n\nPara two.")
        val loaded = DocxStore.load(out)
        assertTrue(loaded.annotations.isEmpty(), "draft must open annotation-less")
        assertEquals("Para one.\nPara two.\n", loaded.plainText)
        assertEquals(null, loaded.position, "draft must carry no reading position")
    }

    @Test
    fun softLineBreaksWithinAParagraph() {
        val out = DocxFromText.build(buildAnnotatedDocxFixture(), "Line one.\nLine two.")
        val plain = PlainTextMapper.build(DocxArchive.read(out).text("word/document.xml")!!).plain
        // Single newline -> <w:br/> inside one paragraph; paragraph close adds the trailing newline.
        assertEquals("Line one.\nLine two.\n", plain)
    }
}

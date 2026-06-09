package com.afluffywaffle.layuv.docx

import com.afluffywaffle.layuv.docx.model.Annotation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * Write-back byte-equality test. The golden DOCX entries are produced by the
 * REAL Dart DocxStore (writeback_golden_test.dart). The XML parts contain no
 * floating-point values, so the Kotlin output must match them byte-for-byte;
 * annotations.json (which has the `position` double) is compared semantically.
 */
class DocxStoreWriteTest {

    private val outputBytes = DocxStore.write(resourceBytes("/golden/writeback/input.docx"), inputAnnotations())
    private val output = DocxArchive.read(outputBytes)

    @Test fun documentXmlByteEqual() = assertEntry("word/document.xml", "document.xml")

    @Test fun commentsXmlByteEqual() = assertEntry("word/comments.xml", "comments.xml")

    @Test fun contentTypesByteEqual() = assertEntry("[Content_Types].xml", "content_types.xml")

    @Test fun documentRelsByteEqual() = assertEntry("word/_rels/document.xml.rels", "document.xml.rels")

    @Test fun commentsRelsByteEqual() = assertEntry("word/_rels/comments.xml.rels", "comments.xml.rels")

    @Test fun cleanSnapshotByteEqual() = assertEntry("leamh/document_clean.xml", "document_clean.xml")

    @Test
    fun annotationsJsonSemanticEqual() {
        val written = parseAnnotations(output.text("leamh/annotations.json")!!)
        assertEquals(inputAnnotations(), written)
    }

    @Test
    fun reopensAndReAnchors() {
        val loaded = DocxStore.load(outputBytes)
        assertEquals(4, loaded.annotations.size)
        // ink PNG carried over -> hasInk stays true.
        assertEquals(true, loaded.annotations.first { it.annotation.id == "ink4" }.annotation.hasInk)
        // every annotation's text is still locatable in the (clean) plain text.
        for (ra in loaded.annotations) assertNotNull(ra.span, "span for ${ra.annotation.id}")
    }

    private fun assertEntry(entryName: String, goldenName: String) =
        assertEquals(resourceText("/golden/writeback/$goldenName"), output.text(entryName), "entry $entryName")

    private fun inputAnnotations(): List<Annotation> =
        parseAnnotations(resourceText("/golden/writeback/annotations.json"))

    @Suppress("UNCHECKED_CAST")
    private fun parseAnnotations(json: String): List<Annotation> =
        Json.parseArray(json).map { Annotation.fromMap(it as Map<String, Any?>) }

    private fun resourceBytes(path: String): ByteArray =
        javaClass.getResourceAsStream(path)?.readBytes() ?: error("missing: $path")

    private fun resourceText(path: String): String =
        javaClass.getResource(path)?.readText(Charsets.UTF_8) ?: error("missing: $path")
}

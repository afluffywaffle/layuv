package com.afluffywaffle.layuv.docx

import com.afluffywaffle.layuv.docx.model.Annotation
import com.afluffywaffle.layuv.docx.model.AnnotationTool
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class InkAnchorInjectorTest {

    private fun inkAnn(id: String = "ink1") = Annotation(
        id = id,
        selectedText = "World",
        prefix = "Hello ",
        suffix = "",
        tool = AnnotationTool.comment,
        note = null,
        tag = null,
        timestamp = Instant.EPOCH,
        position = 0.5,
        hasInk = true,
    )

    /** Minimal Léamh/Pages-style root: declares only xmlns:w. */
    private fun minimalDoc(commentId: Int) =
        "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
            "<w:body><w:p><w:commentRangeStart w:id=\"$commentId\"/>" +
            "<w:r><w:t>Hello World</w:t></w:r>" +
            "<w:commentRangeEnd w:id=\"$commentId\"/></w:p></w:body></w:document>"

    private fun countOccurrences(haystack: String, needle: String): Int {
        var count = 0
        var i = haystack.indexOf(needle)
        while (i >= 0) {
            count++
            i = haystack.indexOf(needle, i + needle.length)
        }
        return count
    }

    @Test
    fun injectsDrawingAndDeclaresNamespacesOnMinimalRoot() {
        val ann = inkAnn()
        val out = InkAnchorInjector.inject(minimalDoc(0), listOf(ann), mapOf(ann.id to 0))

        // The inline drawing paragraph is present, anchored after the paragraph.
        assertTrue(out.contains("<wp:inline"), "expected an inline drawing paragraph")
        assertTrue(out.contains("r:embed=\"${InkDrawing.docRelId(ann.id)}\""), "drawing must reference the doc rel id")

        // The four drawing namespaces are now declared exactly once each on <w:document>.
        for (prefix in listOf("wp", "a", "pic", "r")) {
            assertEquals(
                1,
                countOccurrences(out, "xmlns:$prefix="),
                "xmlns:$prefix should be declared exactly once",
            )
        }
    }

    @Test
    fun doesNotDuplicateNamespacesAlreadyDeclared() {
        val ann = inkAnn()
        // A Word-style root already declaring the drawing namespaces.
        val richRoot =
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"" +
                " xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\"" +
                " xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"" +
                " xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"" +
                " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
                "<w:body><w:p><w:commentRangeStart w:id=\"0\"/>" +
                "<w:r><w:t>Hello World</w:t></w:r>" +
                "<w:commentRangeEnd w:id=\"0\"/></w:p></w:body></w:document>"
        val out = InkAnchorInjector.inject(richRoot, listOf(ann), mapOf(ann.id to 0))

        for (prefix in listOf("wp", "a", "pic", "r")) {
            assertEquals(
                1,
                countOccurrences(out, "xmlns:$prefix="),
                "xmlns:$prefix must not be duplicated when already present",
            )
        }
    }

    @Test
    fun noInkLeavesDocumentUnchanged() {
        val doc = minimalDoc(0)
        val out = InkAnchorInjector.inject(doc, emptyList(), emptyMap())
        assertEquals(doc, out, "with no ink annotations the document must be byte-unchanged")
    }
}

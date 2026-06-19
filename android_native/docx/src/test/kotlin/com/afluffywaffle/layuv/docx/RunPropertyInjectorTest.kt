package com.afluffywaffle.layuv.docx

import com.afluffywaffle.layuv.docx.model.Annotation
import com.afluffywaffle.layuv.docx.model.AnnotationTag
import com.afluffywaffle.layuv.docx.model.AnnotationTool
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class RunPropertyInjectorTest {

    private fun ann(
        selectedText: String,
        prefix: String = "",
        suffix: String = "",
        tool: AnnotationTool = AnnotationTool.highlight,
    ) = Annotation(
        id = "test1",
        selectedText = selectedText,
        prefix = prefix,
        suffix = suffix,
        tool = tool,
        note = null,
        tag = null,
        timestamp = Instant.EPOCH,
        position = 0.5,
        hasInk = false,
    )

    @Test
    fun splitRunPreservesTabSibling() {
        // Run has <w:tab/> after <w:t> — split must not drop it.
        val xml = """<w:body><w:p><w:r><w:rPr><w:b/></w:rPr><w:t xml:space="preserve">Hello World</w:t><w:tab/></w:r></w:p></w:body>"""
        val result = RunPropertyInjector.inject(xml, listOf(ann("World", prefix = "Hello ")), emptyList())
        assertTrue(result.contains("<w:tab/>"), "Expected <w:tab/> to be preserved after run split")
    }

    @Test
    fun splitRunPreservesBreakSibling() {
        // Run has <w:br/> after <w:t> — split must not drop it.
        val xml = """<w:body><w:p><w:r><w:t xml:space="preserve">Hello World</w:t><w:br/></w:r></w:p></w:body>"""
        val result = RunPropertyInjector.inject(xml, listOf(ann("World", prefix = "Hello ")), emptyList())
        assertTrue(result.contains("<w:br/>"), "Expected <w:br/> to be preserved after run split")
    }

    @Test
    fun splitRunPreservesRprChange() {
        // Run has <w:rPrChange> inside <w:rPr> — must survive the split.
        val xml = """<w:body><w:p><w:r><w:rPr><w:b/><w:rPrChange w:id="1" w:author="Word"><w:rPr/></w:rPrChange></w:rPr><w:t xml:space="preserve">Hello World</w:t></w:r></w:p></w:body>"""
        val result = RunPropertyInjector.inject(xml, listOf(ann("World", prefix = "Hello ")), emptyList())
        assertTrue(result.contains("<w:rPrChange"), "Expected <w:rPrChange> to be preserved after run split")
    }

    @Test
    fun injectHighlightAddsRprToAllCoveredRuns() {
        val xml = """<w:body><w:p><w:r><w:t>Hello </w:t></w:r><w:r><w:t>World</w:t></w:r></w:p></w:body>"""
        val result = RunPropertyInjector.inject(xml, listOf(ann("World", prefix = "Hello ")), emptyList())
        assertTrue(result.contains("<w:highlight w:val=\"yellow\"/>"), "Expected yellow highlight injected")
    }

    @Test
    fun noAnnotationsReturnsUnchanged() {
        val xml = """<w:body><w:p><w:r><w:t>Hello</w:t></w:r></w:p></w:body>"""
        val result = RunPropertyInjector.inject(xml, emptyList(), emptyList())
        assertTrue(result == xml)
    }

    @Test
    fun unmatchedAnnotationLeavesXmlUnchanged() {
        val xml = """<w:body><w:p><w:r><w:t>Hello</w:t></w:r></w:p></w:body>"""
        val result = RunPropertyInjector.inject(xml, listOf(ann("NotPresent")), emptyList())
        assertFalse(result.contains("<w:highlight"), "Unmatched annotation must not inject rPr")
    }
}

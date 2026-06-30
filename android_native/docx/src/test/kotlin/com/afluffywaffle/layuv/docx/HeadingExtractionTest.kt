package com.afluffywaffle.layuv.docx

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for document-outline heading extraction. Headings are additive
 * (they do not affect plain text or xmlOffsets — guarded by the golden tests),
 * so these assert only the [PlainMap.headings] overlay.
 */
class HeadingExtractionTest {

    /** Styles map with Heading1/2 plus a body style; mirrors a real styles.xml shape. */
    private val styles = StyleResolver.parse(
        """
        <w:styles>
          <w:style w:type="paragraph" w:styleId="Heading1">
            <w:name w:val="heading 1"/>
            <w:pPr><w:outlineLvl w:val="0"/></w:pPr>
            <w:rPr><w:b/></w:rPr>
          </w:style>
          <w:style w:type="paragraph" w:styleId="Heading2">
            <w:name w:val="heading 2"/>
            <w:pPr><w:outlineLvl w:val="1"/></w:pPr>
          </w:style>
          <w:style w:type="paragraph" w:styleId="Normal">
            <w:name w:val="Normal"/>
          </w:style>
        </w:styles>
        """.trimIndent(),
    )

    private fun para(styleId: String?, text: String): String {
        val pPr = if (styleId != null) "<w:pPr><w:pStyle w:val=\"$styleId\"/></w:pPr>" else ""
        return "<w:p>$pPr<w:r><w:t>$text</w:t></w:r></w:p>"
    }

    @Test
    fun extractsHeadingsInOrderWithLevels() {
        val xml = "<w:body>" +
            para("Heading1", "Chapter One") +
            para(null, "Body paragraph text.") +
            para("Heading2", "A Subsection") +
            para("Normal", "More body.") +
            "</w:body>"

        val map = PlainTextMapper.build(xml, styles)

        assertEquals(2, map.headings.size)
        assertEquals(Heading("Chapter One", 0, map.plain.indexOf("Chapter One")), map.headings[0])
        assertEquals(Heading("A Subsection", 1, map.plain.indexOf("A Subsection")), map.headings[1])
    }

    @Test
    fun charOffsetGivesCorrectJumpFraction() {
        val xml = "<w:body>" +
            para(null, "Intro.") +
            para("Heading1", "Target") +
            "</w:body>"
        val map = PlainTextMapper.build(xml, styles)
        val h = map.headings.single()
        // The offset must point at the heading text inside the canonical plain string.
        assertEquals("Target", map.plain.substring(h.charOffset, h.charOffset + "Target".length))
    }

    @Test
    fun noHeadingsWithoutStyles() {
        // Without a styles map, pStyle can't resolve — no outline, but text intact.
        val xml = "<w:body>" + para("Heading1", "Chapter One") + "</w:body>"
        val map = PlainTextMapper.build(xml, null)
        assertTrue(map.headings.isEmpty())
        assertTrue(map.plain.contains("Chapter One"))
    }

    @Test
    fun blankHeadingsSkipped() {
        val xml = "<w:body>" + para("Heading1", "   ") + para("Heading1", "Real") + "</w:body>"
        val map = PlainTextMapper.build(xml, styles)
        assertEquals(listOf("Real"), map.headings.map { it.text })
    }

    @Test
    fun outlineLevelInferredFromHeadingStyleIdWithoutOutlineLvl() {
        // A styles.xml where Heading3 lacks <w:outlineLvl> — fall back to the styleId.
        val s = StyleResolver.parse(
            """
            <w:styles>
              <w:style w:type="paragraph" w:styleId="Heading3">
                <w:name w:val="heading 3"/>
              </w:style>
            </w:styles>
            """.trimIndent(),
        )
        val xml = "<w:body>" + para("Heading3", "Deep") + "</w:body>"
        val map = PlainTextMapper.build(xml, s)
        assertEquals(Heading("Deep", 2, map.plain.indexOf("Deep")), map.headings.single())
    }
}

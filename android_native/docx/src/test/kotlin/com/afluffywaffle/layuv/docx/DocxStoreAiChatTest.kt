package com.afluffywaffle.layuv.docx

import com.afluffywaffle.layuv.docx.model.AiTurn
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DocxStoreAiChatTest {

    /** A bare DOCX with no Léamh sidecars and no `json` content-type yet. */
    private fun minimalDocx(): ByteArray {
        fun b(s: String) = s.toByteArray(Charsets.UTF_8)
        val entries = LinkedHashMap<String, ByteArray>()
        entries["[Content_Types].xml"] =
            b("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/></Types>")
        entries["word/document.xml"] =
            b("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
                "<w:body><w:p><w:r><w:t>Hi.</w:t></w:r></w:p></w:body></w:document>")
        return DocxArchive.write(entries)
    }

    @Test
    fun writeThenReadRoundTrips() {
        val turns = listOf(
            AiTurn(AiTurn.ROLE_USER, "rewrite please"),
            AiTurn(AiTurn.ROLE_ASSISTANT, "Here is the rewrite.", truncated = true),
        )
        val out = DocxStore.writeAiChat(buildAnnotatedDocxFixture(), turns)
        assertEquals(turns, DocxStore.readAiChat(out))
    }

    @Test
    fun coexistsWithAnnotations() {
        val out = DocxStore.writeAiChat(
            buildAnnotatedDocxFixture(),
            listOf(AiTurn(AiTurn.ROLE_USER, "x")),
        )
        // Writing the transcript must not drop the annotation store.
        assertTrue(DocxArchive.read(out).contains("leamh/annotations.json"))
    }

    @Test
    fun absentTranscriptReadsEmpty() {
        assertTrue(DocxStore.readAiChat(minimalDocx()).isEmpty())
    }

    @Test
    fun addsJsonContentTypeOnNeverAnnotatedDoc() {
        val out = DocxStore.writeAiChat(minimalDocx(), listOf(AiTurn(AiTurn.ROLE_USER, "x")))
        val ct = DocxArchive.read(out).text("[Content_Types].xml")!!
        assertTrue(ct.contains("Extension=\"json\""), "json content-type added")
        assertEquals(listOf(AiTurn(AiTurn.ROLE_USER, "x")), DocxStore.readAiChat(out))
    }
}

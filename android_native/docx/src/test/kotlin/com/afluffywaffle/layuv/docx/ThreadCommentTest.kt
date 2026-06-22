package com.afluffywaffle.layuv.docx

import com.afluffywaffle.layuv.docx.model.Annotation
import com.afluffywaffle.layuv.docx.model.AnnotationTool
import com.afluffywaffle.layuv.docx.model.ThreadEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Word comment-thread round-trip. Covers the three guarantees of the bottom-pane
 * feature: (a) a Word reply is flattened onto its parent annotation as a
 * `source="word"` ThreadEntry rather than a standalone annotation, (b) the
 * `commentsExtended.xml` sidecar is emptied on write so its paraId links can't
 * dangle, and (c) a threaded annotation writes one comment paragraph per entry.
 */
class ThreadCommentTest {

    private val parentEpoch = Instant.parse("2026-06-08T10:00:00.000Z").toEpochMilli()
    private val replyEpoch = Instant.parse("2026-06-08T11:00:00.000Z").toEpochMilli()

    // (a) — reply flattened onto parent ----------------------------------------

    @Test
    fun replyIsFlattenedOntoParentThread() {
        val doc = resourceText("/golden/import/thread.document.xml")
        val comments = resourceText("/golden/import/thread.comments.xml")
        val ext = resourceText("/golden/import/thread.commentsExtended.xml")
        val map = PlainTextMapper.build(doc)

        val anns = LegacyComments.parseComments(comments, doc, map, ext)

        // Only the parent survives as an annotation; the reply is folded into it.
        assertEquals(1, anns.size)
        val parent = anns[0]
        assertEquals("word_0", parent.id)
        assertEquals(2, parent.threadEntries.size)
        // Entry 0 mirrors the note (parent text); entry 1 is the reply.
        assertEquals("Parent comment text", parent.note)
        assertEquals("Parent comment text", parent.threadEntries[0].text)
        assertEquals(parentEpoch, parent.threadEntries[0].timestamp)
        assertEquals("Reply text", parent.threadEntries[1].text)
        assertEquals(replyEpoch, parent.threadEntries[1].timestamp)
        assertTrue(parent.threadEntries.all { it.source == ThreadEntry.SOURCE_WORD })
    }

    @Test
    fun withoutExtendedFileRepliesStayStandalone() {
        // No commentsExtended.xml -> no threading: both comments resolve normally.
        val doc = resourceText("/golden/import/thread.document.xml")
        val comments = resourceText("/golden/import/thread.comments.xml")
        val map = PlainTextMapper.build(doc)

        val anns = LegacyComments.parseComments(comments, doc, map, null)

        assertEquals(2, anns.size)
        assertTrue(anns.all { it.threadEntries.isEmpty() })
    }

    // (b) — commentsExtended emptied on round-trip write ------------------------

    @Test
    fun roundTripEmptiesCommentsExtended() {
        val base = resourceBytes("/golden/writeback/input.docx")
        val archive = DocxArchive.read(base)
        val entries = archive.toMutableEntries()
        entries["word/commentsExtended.xml"] =
            resourceText("/golden/import/thread.commentsExtended.xml").toByteArray(Charsets.UTF_8)
        val withExt = DocxArchive.write(entries, archive.entryMethods())

        val out = DocxStore.write(withExt, emptyList())
        val outExt = DocxArchive.read(out).text("word/commentsExtended.xml")
            ?: error("commentsExtended.xml dropped from archive")

        // Root element survives (content-type/relationship stay valid) but every
        // commentEx child — and therefore every dangling paraId link — is gone.
        assertTrue(outExt.contains("commentsEx"), "root element preserved")
        assertFalse(outExt.contains("commentEx "), "no <w15:commentEx> entries remain")
        assertFalse(outExt.contains("paraIdParent"), "no dangling reply links remain")
    }

    @Test
    fun absentCommentsExtendedIsNotCreated() {
        // input.docx has no commentsExtended.xml; a write must not invent one.
        val out = DocxStore.write(resourceBytes("/golden/writeback/input.docx"), emptyList())
        assertFalse(
            DocxArchive.read(out).names.contains("word/commentsExtended.xml"),
            "write must never create commentsExtended.xml",
        )
    }

    // (c) — one comment paragraph per thread entry ------------------------------

    @Test
    fun threadWritesOneParagraphPerEntry() {
        val ann = Annotation(
            id = "word_0",
            selectedText = "annotated span",
            prefix = "",
            suffix = "",
            tool = AnnotationTool.comment,
            note = "Parent comment text",
            timestamp = Instant.ofEpochMilli(parentEpoch),
            position = 0.1,
            threadEntries = listOf(
                ThreadEntry("Parent comment text", parentEpoch, ThreadEntry.SOURCE_WORD),
                ThreadEntry("Reply text", replyEpoch, ThreadEntry.SOURCE_WORD),
            ),
        )

        val xml = CommentWriter.buildNoteComment(0, ann, null)

        // 1 annotationRef header paragraph + 2 body paragraphs (one per entry).
        assertEquals(3, Regex("<w:p>").findAll(xml).count())
        assertTrue(xml.contains("Parent comment text"))
        assertTrue(xml.contains("Reply text"))
        // Entry 0 is plain; later entries carry a timestamp prefix.
        assertTrue(xml.contains("] Reply text"), "reply paragraph is timestamp-prefixed")
        assertFalse(xml.contains("] Parent comment text"), "first entry is not prefixed")
    }

    // (a') — multi-level and multi-paragraph threading -------------------------

    @Test
    fun multiLevelReplyChainFlattensOntoRoot() {
        // P(0) <- R1(1) <- R2(2): R2 replies to R1, which replies to P. All three
        // must land on the root annotation's thread (no deeper reply dropped).
        val doc = docWithRange("0")
        val comments = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<w:comments xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"""" +
            """ xmlns:w14="http://schemas.microsoft.com/office/word/2010/wordml">""" +
            """<w:comment w:id="0" w:author="A" w:date="2026-06-08T10:00:00.000Z"><w:p w14:paraId="AAAA0000"><w:r><w:t>Root</w:t></w:r></w:p></w:comment>""" +
            """<w:comment w:id="1" w:author="B" w:date="2026-06-08T11:00:00.000Z"><w:p w14:paraId="AAAA1111"><w:r><w:t>Reply one</w:t></w:r></w:p></w:comment>""" +
            """<w:comment w:id="2" w:author="C" w:date="2026-06-08T12:00:00.000Z"><w:p w14:paraId="AAAA2222"><w:r><w:t>Reply two deep</w:t></w:r></w:p></w:comment>""" +
            """</w:comments>"""
        val ext = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<w15:commentsEx xmlns:w15="http://schemas.microsoft.com/office/word/2012/wordml">""" +
            """<w15:commentEx w15:paraId="AAAA0000" w15:done="0"/>""" +
            """<w15:commentEx w15:paraId="AAAA1111" w15:paraIdParent="AAAA0000" w15:done="0"/>""" +
            """<w15:commentEx w15:paraId="AAAA2222" w15:paraIdParent="AAAA1111" w15:done="0"/>""" +
            """</w15:commentsEx>"""

        val anns = LegacyComments.parseComments(comments, doc, PlainTextMapper.build(doc), ext)

        assertEquals(1, anns.size)
        assertEquals(
            listOf("Root", "Reply one", "Reply two deep"),
            anns[0].threadEntries.map { it.text },
        )
    }

    @Test
    fun replyToMultiParagraphParentIsThreaded() {
        // The parent comment has two paragraphs; the reply's paraIdParent points at
        // the parent's LAST paragraph. Both paraIds must resolve to the parent.
        val doc = docWithRange("0")
        val comments = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<w:comments xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"""" +
            """ xmlns:w14="http://schemas.microsoft.com/office/word/2010/wordml">""" +
            """<w:comment w:id="0" w:author="A" w:date="2026-06-08T10:00:00.000Z">""" +
            """<w:p w14:paraId="BBBB0000"><w:r><w:t>First para</w:t></w:r></w:p>""" +
            """<w:p w14:paraId="BBBB0001"><w:r><w:t>Second para</w:t></w:r></w:p></w:comment>""" +
            """<w:comment w:id="1" w:author="B" w:date="2026-06-08T11:00:00.000Z"><w:p w14:paraId="BBBB1111"><w:r><w:t>A reply</w:t></w:r></w:p></w:comment>""" +
            """</w:comments>"""
        val ext = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<w15:commentsEx xmlns:w15="http://schemas.microsoft.com/office/word/2012/wordml">""" +
            """<w15:commentEx w15:paraId="BBBB0001" w15:done="0"/>""" +
            """<w15:commentEx w15:paraId="BBBB1111" w15:paraIdParent="BBBB0001" w15:done="0"/>""" +
            """</w15:commentsEx>"""

        val anns = LegacyComments.parseComments(comments, doc, PlainTextMapper.build(doc), ext)

        assertEquals(1, anns.size)
        val thread = anns[0].threadEntries
        assertEquals(2, thread.size)
        assertEquals("First para Second para", thread[0].text)
        assertEquals("A reply", thread[1].text)
    }

    private fun docWithRange(id: String): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p>""" +
            """<w:r><w:t>Intro. </w:t></w:r><w:commentRangeStart w:id="$id"/>""" +
            """<w:r><w:t>anchored span</w:t></w:r><w:commentRangeEnd w:id="$id"/>""" +
            """<w:r><w:t> tail.</w:t></w:r></w:p></w:body></w:document>"""

    private fun resourceText(path: String): String =
        javaClass.getResource(path)?.readText(Charsets.UTF_8) ?: error("missing resource: $path")

    private fun resourceBytes(path: String): ByteArray =
        javaClass.getResourceAsStream(path)?.readBytes() ?: error("missing resource: $path")
}

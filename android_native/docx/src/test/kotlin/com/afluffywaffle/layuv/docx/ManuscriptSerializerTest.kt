package com.afluffywaffle.layuv.docx

import com.afluffywaffle.layuv.docx.model.Annotation
import com.afluffywaffle.layuv.docx.model.AnnotationTag
import com.afluffywaffle.layuv.docx.model.AnnotationTool
import com.afluffywaffle.layuv.docx.model.ThreadEntry
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class ManuscriptSerializerTest {

    private fun ann(
        tool: AnnotationTool,
        selected: String,
        note: String? = null,
        tag: AnnotationTag? = null,
        thread: List<ThreadEntry> = emptyList(),
    ) = Annotation(
        id = "id-$selected",
        selectedText = selected,
        prefix = "",
        suffix = "",
        tool = tool,
        note = note,
        tag = tag,
        timestamp = Instant.EPOCH,
        position = 0.0,
        threadEntries = thread,
    )

    @Test
    fun rendersPreambleChapterAndEveryAnnotation() {
        val annotations = listOf(
            ann(AnnotationTool.highlight, "the old man", note = "tighten this", tag = AnnotationTag.pacing),
            ann(AnnotationTool.strikethrough, "very, very tired"),
            ann(
                AnnotationTool.comment,
                "the door",
                thread = listOf(
                    ThreadEntry("is this the right door?", 0L, ThreadEntry.SOURCE_LEAMH),
                    ThreadEntry("yes, the back one", 1L, ThreadEntry.SOURCE_LEAMH),
                ),
            ),
        )
        val prompt = ManuscriptSerializer.buildPrompt("Chapter body text here.", annotations)

        assertTrue(prompt.contains("revise a manuscript chapter"), "has preamble")
        assertTrue(prompt.contains("=== CHAPTER ==="))
        assertTrue(prompt.contains("Chapter body text here."))
        assertTrue(prompt.contains("=== ANNOTATIONS (3) ==="))
        assertTrue(prompt.contains("[Highlight] “the old man”"))
        assertTrue(prompt.contains("note: tighten this"))
        assertTrue(prompt.contains("tag: pacing"))
        assertTrue(prompt.contains("[Strikethrough — cut] “very, very tired”"))
        assertTrue(prompt.contains("[Comment] “the door”"))
        // Thread entries are folded into the note (the note mirrors only the first).
        assertTrue(prompt.contains("is this the right door?"))
        assertTrue(prompt.contains("yes, the back one"))
    }

    @Test
    fun handlesNoAnnotations() {
        val prompt = ManuscriptSerializer.buildPrompt("Just the chapter.", emptyList())
        assertTrue(prompt.contains("=== ANNOTATIONS (0) ==="))
        assertTrue(prompt.contains("(none)"))
        assertFalse(prompt.contains("note:"))
    }
}

package com.afluffywaffle.layuv.docx

import com.afluffywaffle.layuv.docx.model.Annotation
import com.afluffywaffle.layuv.docx.model.AnnotationTool

/**
 * Builds the seed prompt for the in-app "Ask AI" conversation: a rewrite request
 * over the chapter's canonical plain text plus the author's annotations.
 *
 * Pure: it receives the ALREADY-extracted plain text (`LoadedDocument.plainText`,
 * which runs [PlainTextMapper.build]) and must NOT parse XML itself — preserving
 * the ONE canonical plain-text string invariant (a fresh tag-stripper would
 * reintroduce the legacy `<w:t>`-overmatch bug).
 */
object ManuscriptSerializer {

    private const val PREAMBLE =
        "You are helping an author revise a manuscript chapter. Below is the " +
        "chapter text, followed by the author's annotations on specific passages. " +
        "Rewrite the chapter to address every annotation while preserving the " +
        "author's voice and everything that isn't flagged.\n\n" +
        "When asked for the rewrite, reply with the full revised chapter as plain " +
        "prose — paragraphs separated by a blank line, no commentary, headings, or " +
        "markup around it. If the author replies with questions or further notes, " +
        "discuss the changes conversationally before producing another rewrite."

    fun buildPrompt(plainText: String, annotations: List<Annotation>): String {
        val sb = StringBuilder()
        sb.append(PREAMBLE).append("\n\n")
        sb.append("=== CHAPTER ===\n")
        sb.append(plainText.trim()).append("\n\n")
        sb.append("=== ANNOTATIONS (").append(annotations.size).append(") ===\n")
        if (annotations.isEmpty()) {
            sb.append("(none)\n")
        } else {
            annotations.forEachIndexed { i, a ->
                sb.append(i + 1).append(". [").append(label(a.tool)).append("] ")
                    .append('“').append(a.selectedText.trim()).append('”').append('\n')
                val note = noteText(a)
                if (note.isNotBlank()) sb.append("   note: ").append(note).append('\n')
                a.tag?.let { sb.append("   tag: ").append(it.name).append('\n') }
            }
        }
        return sb.toString()
    }

    /** Full thread text when present (the note is just the first entry), else the note. */
    private fun noteText(a: Annotation): String =
        if (a.threadEntries.isNotEmpty()) {
            a.threadEntries.joinToString("\n         ") { it.text.trim() }
        } else {
            (a.note ?: "").trim()
        }

    private fun label(tool: AnnotationTool): String = when (tool) {
        AnnotationTool.highlight -> "Highlight"
        AnnotationTool.underline -> "Underline"
        AnnotationTool.doubleUnderline -> "Double underline"
        AnnotationTool.strikethrough -> "Strikethrough — cut"
        AnnotationTool.wavyUnderline -> "Wavy underline"
        AnnotationTool.bookmark -> "Bookmark"
        AnnotationTool.inkAnnotation -> "Ink note"
        AnnotationTool.comment -> "Comment"
    }
}

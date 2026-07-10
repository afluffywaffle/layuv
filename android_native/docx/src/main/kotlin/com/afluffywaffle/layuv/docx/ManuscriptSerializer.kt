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

    private val PREAMBLE =
        "You are helping an author revise a manuscript chapter, in a back-and-forth " +
        "conversation. Below is the chapter text, followed by the author's " +
        "annotations on specific passages.\n\n" +
        "Your default task is to rewrite the chapter to address every annotation " +
        "while preserving the author's voice and everything that isn't flagged. You " +
        "may ask a brief clarifying question first if an annotation is genuinely " +
        "ambiguous, and you can discuss the changes when the author replies.\n\n" +
        "Some annotations include the author's note as a HANDWRITTEN image, " +
        "referenced as \"attached image N\" (the Nth image attached, in order). Read " +
        "the handwriting in that image and treat it as the author's note for that " +
        "passage.\n\n" +
        "Format every rewrite exactly like this: put the FULL revised chapter — " +
        "plain prose, paragraphs separated by a blank line, no headings, markup, or " +
        "commentary — between a line reading " + RewriteProtocol.BEGIN + " and a " +
        "line reading " + RewriteProtocol.END + ". Put any remarks to the author " +
        "BEFORE the " + RewriteProtocol.BEGIN + " line, never inside the markers. " +
        "When you are only discussing or asking a question (not delivering a " +
        "rewrite), reply normally with no markers."

    /** The seed prompt text + the ids of ink annotations, in the order they're referenced as images. */
    data class Prompt(val text: String, val inkAnnotationIds: List<String>)

    fun buildPrompt(plainText: String, annotations: List<Annotation>): Prompt {
        val body = buildExportBody(plainText, annotations)
        return Prompt(PREAMBLE + "\n\n" + body.text, body.inkAnnotationIds)
    }

    /**
     * The chapter + annotations body WITHOUT the in-app preamble (no [RewriteProtocol]
     * `===REWRITE===` markers). Used by the "Export for AI" file path, where the user's
     * OWN project instructions (e.g. a Claude Code `CLAUDE.md`) drive the rewrite rather
     * than the in-app chat protocol. Same [Prompt] shape: the body text plus the
     * ink-annotation ids in their "attached image N" order.
     */
    fun buildExportBody(plainText: String, annotations: List<Annotation>): Prompt {
        val sb = StringBuilder()
        val inkIds = mutableListOf<String>()
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
                if (a.hasInk) {
                    inkIds.add(a.id)
                    sb.append("   handwritten note: see attached image ").append(inkIds.size).append('\n')
                }
                a.tag?.let { sb.append("   tag: ").append(it.name).append('\n') }
            }
        }
        return Prompt(sb.toString(), inkIds)
    }

    /**
     * Annotations-only export: no chapter text, just each annotation's anchor (paragraph
     * number, computed from the same exact char offset used to place the DOCX comment range —
     * NOT reverse-derived from the position fraction — plus position fraction + prefix/suffix
     * context around the quoted passage) plus note/tag/thread. Lets an AI that already has the
     * manuscript (e.g. reading the project folder directly) locate each annotation without the
     * chapter body being duplicated into the export, and without re-deriving anchoring itself.
     * [fileName] identifies which document these anchors belong to once exports from multiple
     * chapters sit side by side.
     */
    fun buildAnnotationsOnlyExport(fileName: String, annotations: List<Annotation>): Prompt {
        val sb = StringBuilder()
        val inkIds = mutableListOf<String>()
        sb.append("=== ANNOTATIONS FOR \"").append(fileName).append("\" (")
            .append(annotations.size).append(") ===\n")
        if (annotations.isEmpty()) {
            sb.append("(none)\n")
            return Prompt(sb.toString(), inkIds)
        }
        annotations.forEachIndexed { i, a ->
            val pct = Math.round(a.position * 100).toInt()
            val paraLabel = if (a.paragraph > 0) "paragraph ~${a.paragraph}, " else ""
            sb.append(i + 1).append(". [").append(label(a.tool)).append("] ").append(paraLabel)
                .append('~').append(pct).append("% through the manuscript\n")
            val prefix = a.prefix.trim()
            val suffix = a.suffix.trim()
            val quoted = a.selectedText.trim()
            val context = StringBuilder()
            if (prefix.isNotEmpty()) context.append('…').append(prefix)
            context.append('‹').append(quoted).append('›')
            if (suffix.isNotEmpty()) context.append(suffix).append('…')
            sb.append("   context: ").append(context).append('\n')
            val note = noteText(a)
            if (note.isNotBlank()) sb.append("   note: ").append(note).append('\n')
            if (a.hasInk) {
                inkIds.add(a.id)
                sb.append("   handwritten note: see attached image ").append(inkIds.size).append('\n')
            }
            a.tag?.let { sb.append("   tag: ").append(it.name).append('\n') }
        }
        return Prompt(sb.toString(), inkIds)
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
        AnnotationTool.blockquote -> "Blockquote"
    }
}

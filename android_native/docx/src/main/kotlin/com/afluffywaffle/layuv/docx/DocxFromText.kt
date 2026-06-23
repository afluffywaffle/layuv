package com.afluffywaffle.layuv.docx

/**
 * Builds a CLEAN, annotation-less, conversation-less `.docx` from rewritten
 * chapter [text] by CLONING [sourceDocx]'s archive — so the new draft inherits
 * the original's `styles.xml`, rels, theme, and page setup (`<w:sectPr>`) and
 * round-trips with Word / Pages / Google Docs — then regenerating ONLY the body
 * of `word/document.xml` and stripping every Léamh sidecar, comment part, and
 * ink medium.
 *
 * Pure JVM (no `android.*`); the only XML it emits is plain paragraphs, escaped
 * via [XmlEntities]. Used by the "Save as draft" action after an AI rewrite.
 */
object DocxFromText {

    private const val DOCUMENT = "word/document.xml"
    private const val CLEAN = "leamh/document_clean.xml"
    private const val DOC_RELS = "word/_rels/document.xml.rels"

    private val SECT_CLOSE = "</w:sectPr>"
    private val BODY_OPEN = Regex("<w:body\\b[^>]*>")
    // A Relationship whose Type ends in one of the comment-family parts we strip.
    private val COMMENT_REL =
        Regex("<Relationship\\b[^>]*(/comments|/commentsExtended|/commentsIds|/people)\"[^>]*/>")

    /** Parts left over from an annotated / conversational copy — removed wholesale. */
    private val STRIP_PARTS = listOf(
        "leamh/annotations.json",
        "leamh/position.json",
        CLEAN,
        "leamh/aichat.json",
        "word/comments.xml",
        "word/commentsExtended.xml",
        "word/commentsIds.xml",
        "word/people.xml",
        "word/_rels/comments.xml.rels",
    )

    fun build(sourceDocx: ByteArray, text: String): ByteArray {
        val archive = DocxArchive.read(sourceDocx)
        val entries = archive.toMutableEntries()

        // Body source: prefer the un-injected clean snapshot (no comment-range or
        // bookmark markup), else the live document.
        val source = archive.text(CLEAN)
            ?: archive.text(DOCUMENT)
            ?: throw IllegalArgumentException("source DOCX has no $DOCUMENT")

        entries[DOCUMENT] = replaceBody(source, text).toByteArray(Charsets.UTF_8)

        STRIP_PARTS.forEach { entries.remove(it) }
        entries.keys.removeAll { it.startsWith("word/media/ink_") }
        entries[DOC_RELS]?.let {
            entries[DOC_RELS] = COMMENT_REL.replace(it.toString(Charsets.UTF_8), "")
                .toByteArray(Charsets.UTF_8)
        }

        return DocxArchive.write(entries, archive.entryMethods())
    }

    /**
     * Replaces the inner content of `<w:body>` with paragraphs from [text],
     * preserving any trailing body-level `<w:sectPr>` (page size/margins/headers).
     * Falls back to a minimal whole-document rebuild only if no body is found.
     */
    private fun replaceBody(documentXml: String, text: String): String {
        val bodyOpen = BODY_OPEN.find(documentXml)
        val bodyClose = documentXml.lastIndexOf("</w:body>")
        if (bodyOpen == null || bodyClose < 0 || bodyClose <= bodyOpen.range.last) {
            return minimalDocument(text)
        }
        val innerStart = bodyOpen.range.last + 1
        val inner = documentXml.substring(innerStart, bodyClose)
        val sectPr = lastSectPr(inner) ?: ""
        return documentXml.substring(0, innerStart) +
            paragraphs(text) + sectPr +
            documentXml.substring(bodyClose)
    }

    /** The body-level sectPr is the final child of `<w:body>`; capture the last one verbatim. */
    private fun lastSectPr(inner: String): String? {
        val open = inner.lastIndexOf("<w:sectPr")
        if (open < 0) return null
        val tagEnd = inner.indexOf('>', open)
        if (tagEnd < 0) return null
        if (inner[tagEnd - 1] == '/') return inner.substring(open, tagEnd + 1) // self-closing
        val close = inner.indexOf(SECT_CLOSE, tagEnd)
        return if (close < 0) inner.substring(open, tagEnd + 1)
        else inner.substring(open, close + SECT_CLOSE.length)
    }

    /**
     * Splits [text] into paragraphs on blank lines; a single newline inside a
     * paragraph becomes a soft `<w:br/>`. Each line's text is XML-escaped and
     * carries `xml:space="preserve"` so leading/trailing spaces survive.
     */
    private fun paragraphs(text: String): String {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (normalized.isEmpty()) return "<w:p/>"
        val sb = StringBuilder()
        for (block in normalized.split(Regex("\n[ \t]*\n"))) {
            sb.append("<w:p>")
            block.split('\n').forEachIndexed { i, line ->
                if (i > 0) sb.append("<w:r><w:br/></w:r>")
                sb.append("<w:r><w:t xml:space=\"preserve\">")
                    .append(XmlEntities.escape(line))
                    .append("</w:t></w:r>")
            }
            sb.append("</w:p>")
        }
        return sb.toString()
    }

    private fun minimalDocument(text: String): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
        "<w:body>" + paragraphs(text) + "</w:body></w:document>"
}

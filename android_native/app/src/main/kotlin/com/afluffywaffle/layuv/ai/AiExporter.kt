package com.afluffywaffle.layuv.ai

import com.afluffywaffle.layuv.docx.DocxStore
import com.afluffywaffle.layuv.docx.ManuscriptSerializer
import com.afluffywaffle.layuv.docx.model.Annotation

/**
 * Builds the "Export for AI" artifacts for the manual Claude Code workflow: a clean
 * Markdown file (chapter text + a tidy annotation list) plus one PNG per handwritten
 * ink note. The user syncs the export folder to a Mac and runs `claude` on it, so the
 * file reads as a ready prompt instead of a `.docx` Claude must crack open.
 *
 * READ-ONLY with respect to the source `.docx`: this only reads the already-loaded
 * plain text / annotations and the ink PNGs out of the archive bytes. It writes nothing
 * itself (the caller persists [Export.files] via the write queue), so it stays a pure,
 * testable builder with only docx-engine dependencies — and the Word/Pages/GDocs
 * round-trip of the original is never touched.
 */
object AiExporter {

    /** One file to write: a name (no directory) and its bytes. */
    class Artifact(val name: String, val bytes: ByteArray)

    /** The full export: the markdown + any ink PNGs, plus a summary for the toast. */
    class Export(val files: List<Artifact>, val markdownName: String, val imageCount: Int)

    private const val BODY_HEADER =
        "This is a manuscript chapter exported from Léamh for revision. Below is the " +
        "chapter text, then the author's annotations on specific passages. Rewrite the " +
        "chapter to address every annotation while preserving the author's voice and " +
        "anything not flagged; follow this project's style guide and story bible. " +
        "Handwritten notes are referenced as \"attached image N\" — the matching image " +
        "files are listed at the end of this document."

    private fun buildHeader(cleanBase: String, version: Int): String =
        "Export v$version of \"$cleanBase\". " +
        "When returning the rewrite as a .docx file, name it ${cleanBase}_draft_v${version}.docx.\n\n" +
        BODY_HEADER

    /**
     * @param mdName the output markdown filename (e.g. "chapter.md" in subfolder mode,
     *   or "salt_road_v3_for_ai.md" in flat mode).
     * @param pngPrefix prefix for ink PNG filenames — output is `<pngPrefix>_N.png`
     *   (e.g. "ink" → "ink_1.png", "ink_2.png" in subfolder mode).
     * @param cleanBase chapter name without draft suffix (e.g. "salt_road"), used in the header.
     * @param version export version — always fileVersion + 1. Used both for the folder label
     *   and for the next-draft filename suggestion in the prompt header.
     * @param sourceDocxBytes current `.docx` bytes, for reading ink PNGs.
     */
    fun build(
        plainText: String,
        annotations: List<Annotation>,
        sourceDocxBytes: ByteArray,
        mdName: String,
        pngPrefix: String,
        cleanBase: String,
        version: Int,
    ): Export {
        val body = ManuscriptSerializer.buildExportBody(plainText, annotations)
        val files = ArrayList<Artifact>()

        // Each ink PNG is numbered to match the body's "attached image N" references.
        // Keeping N tied to the body position means a missing PNG just drops that entry
        // from the manifest without renumbering the rest.
        val images = ArrayList<Pair<Int, String>>()
        body.inkAnnotationIds.forEachIndexed { i, id ->
            val png = DocxStore.readInkPng(sourceDocxBytes, id) ?: return@forEachIndexed
            val n = i + 1
            val name = "${pngPrefix}_$n.png"
            files.add(Artifact(name, png))
            images.add(n to name)
        }

        val md = StringBuilder()
        md.append(buildHeader(cleanBase, version)).append("\n\n")
        md.append(body.text)
        if (images.isNotEmpty()) {
            md.append("\n=== IMAGE FILES ===\n")
            for ((n, name) in images) {
                md.append("attached image ").append(n).append(" = ").append(name).append('\n')
            }
        }
        files.add(Artifact(mdName, md.toString().toByteArray(Charsets.UTF_8)))
        return Export(files, mdName, images.size)
    }
}

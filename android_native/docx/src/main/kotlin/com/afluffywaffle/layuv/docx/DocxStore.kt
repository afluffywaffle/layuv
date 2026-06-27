package com.afluffywaffle.layuv.docx

import com.afluffywaffle.layuv.docx.model.AiTurn
import com.afluffywaffle.layuv.docx.model.Annotation
import com.afluffywaffle.layuv.docx.model.ReadingPosition
import java.time.Instant

/** An annotation resolved to its current char range in the canonical plain text (null if unlocatable). */
data class ResolvedAnnotation(val annotation: Annotation, val span: TextSpan?)

/** Loaded reader state for a DOCX. */
class LoadedDocument(
    val plainMap: PlainMap,
    val annotations: List<ResolvedAnnotation>,
    val position: ReadingPosition?,
) {
    val plainText: String get() = plainMap.plain
    val formatSpans: List<FormatSpan> get() = plainMap.formats
}

/**
 * Read side of the DOCX engine — mirror of lib/models/docx_store.dart.
 *
 * Primary store is `leamh/annotations.json`; PNG presence at
 * `word/media/ink_<id>.png` overrides each annotation's `hasInk`; and every
 * annotation is re-anchored against the current canonical plain text via
 * [Anchoring.locateInPlain] (so it survives external edits / engine drift). All
 * methods degrade gracefully (empty list / null) rather than throwing, matching
 * the Dart store's error contract.
 */
object DocxStore {
    private const val ANNOTATIONS = "leamh/annotations.json"
    private const val POSITION = "leamh/position.json"
    private const val AICHAT = "leamh/aichat.json"
    private const val DOCUMENT = "word/document.xml"
    private const val CLEAN = "leamh/document_clean.xml"
    private const val COMMENTS = "word/comments.xml"
    private const val COMMENTS_EXTENDED = "word/commentsExtended.xml"
    private const val DOC_RELS = "word/_rels/document.xml.rels"
    private const val COMMENTS_RELS = "word/_rels/comments.xml.rels"
    private const val CONTENT_TYPES = "[Content_Types].xml"

    /**
     * @param now clock for the native-formatting import fallback's generated
     *   ids/timestamps (mirrors Dart `DateTime.now()`); injectable for tests.
     */
    fun load(docxBytes: ByteArray, now: Instant = Instant.now()): LoadedDocument {
        val archive = DocxArchive.read(docxBytes)
        // Prefer the clean snapshot (original, un-injected body) when present —
        // run-property injection doesn't change the plain text, so the string is
        // the same either way, but the clean copy is the canonical source.
        val documentXml = archive.text(CLEAN) ?: archive.text(DOCUMENT) ?: ""
        val stylesXml = archive.text("word/styles.xml")
        val styles = if (stylesXml != null) StyleResolver.parse(stylesXml) else null
        val map = PlainTextMapper.build(documentXml, styles)
        return LoadedDocument(
            plainMap = map,
            annotations = loadAnnotations(archive, map, documentXml, now),
            position = loadPosition(archive),
        )
    }

    /**
     * Primary path: parse `leamh/annotations.json` (PNG presence overrides
     * `hasInk`). Fallback when it's absent (fresh/foreign DOCX): import existing
     * Word run formatting + comments. Either way every annotation is re-anchored
     * against the current plain text. Returns empty on any error (Dart contract).
     */
    fun loadAnnotations(
        archive: DocxArchive,
        map: PlainMap,
        documentXml: String,
        now: Instant,
    ): List<ResolvedAnnotation> = try {
        val raw = archive.text(ANNOTATIONS)
        val annotations: List<Annotation> = if (raw != null) {
            Json.parseArray(raw).filterIsInstance<Map<String, Any?>>().mapNotNull { m ->
                try {
                    val a = Annotation.fromMap(m)
                    // Non-empty PNG is the source of truth for hasInk (a 0-byte or
                    // corrupt PNG must not generate a broken <w:drawing> on next write).
                    val hasInk = archive.bytes("word/media/ink_${a.id}.png")?.isNotEmpty() == true
                    if (hasInk != a.hasInk) a.copy(hasInk = hasInk) else a
                } catch (e: Exception) {
                    e.printStackTrace()
                    null  // skip one bad record; don't drop the entire list
                }
            }
        } else {
            val baseMicros = now.epochSecond * 1_000_000 + now.nano / 1000
            val native = if (documentXml.isNotEmpty()) {
                NativeImport.importNativeFormatting(documentXml, map, baseMicros, now)
            } else {
                emptyList()
            }
            val legacy = archive.text(COMMENTS)?.let {
                LegacyComments.parseComments(it, documentXml, map, archive.text(COMMENTS_EXTENDED))
            } ?: emptyList()
            native + legacy
        }
        annotations.map { a ->
            ResolvedAnnotation(a, Anchoring.locateInPlain(map.plain, a.selectedText, a.prefix, a.suffix, a.position))
        }
    } catch (e: Exception) {
        e.printStackTrace() // surfaces in logcat as W/System.err — helps diagnose silent failures
        emptyList()
    }

    fun loadPosition(archive: DocxArchive): ReadingPosition? {
        val raw = archive.text(POSITION) ?: return null
        return try {
            ReadingPosition.fromMap(Json.parseObject(raw))
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Cheap post-write integrity canary: reads only `leamh/annotations.json` from
     * [docxBytes] and returns the annotation ids it contains. Unlike [load] it does
     * NOT rebuild the plain text or re-anchor anything (O(annotations × text length)),
     * so callers that just wrote the file can confirm it round-tripped without paying
     * a full reload. Returns an empty list when there is no annotations file (all
     * annotations removed), or null when the bytes can't be parsed (write looks torn).
     */
    fun readAnnotationIds(docxBytes: ByteArray): List<String>? = try {
        val raw = DocxArchive.read(docxBytes).text(ANNOTATIONS)
        if (raw == null) emptyList()
        else Json.parseArray(raw).filterIsInstance<Map<String, Any?>>().mapNotNull { it["id"] as? String }
    } catch (e: Exception) {
        null
    }

    // -------------------------------------------------------------------------
    // Write — mirror of _writeAllAnnotations.
    //
    // Layout written into the DOCX:
    //   leamh/annotations.json   — authoritative store for all Léamh annotations
    //   leamh/document_clean.xml — original document.xml snapshot (created once)
    //   word/document.xml        — restored from clean, then formatting injected
    //   word/comments.xml        — only for annotations with a note, tag, or ink
    //   word/_rels/*, [Content_Types].xml — kept consistent for Word/Pages/GDocs
    //
    // Ink PNGs are written separately by [saveInkPng]; this expects them present.
    // Returns the new DOCX bytes (does not touch the filesystem — the platform
    // layer writes them back to the user's file/URI).
    // -------------------------------------------------------------------------

    /**
     * Single-pass write: embeds [inkPng] and [inkStrokes] into the archive in the
     * same ZIP pass as the annotation write, avoiding the 3× read+decompress+write
     * cost of calling [saveInkPng] + [saveInkStrokes] + [write] separately.
     * Callers should always prefer this over the three-call chain when ink is present.
     */
    fun writeWithInk(
        docxBytes: ByteArray,
        annotations: List<Annotation>,
        inkPng: Pair<String, ByteArray>? = null,
        inkStrokes: Pair<String, String>? = null,
    ): ByteArray {
        val archive = DocxArchive.read(docxBytes)
        val entries = archive.toMutableEntries()
        if (inkPng != null) {
            entries["word/media/ink_${inkPng.first}.png"] = inkPng.second
        }
        if (inkStrokes != null) {
            entries["word/media/ink_${inkStrokes.first}_strokes.json"] =
                inkStrokes.second.toByteArray(Charsets.UTF_8)
        }
        writeIntoEntries(entries, annotations)
        return DocxArchive.write(entries, archive.entryMethods())
    }

    fun write(docxBytes: ByteArray, annotations: List<Annotation>): ByteArray {
        val archive = DocxArchive.read(docxBytes)
        val entries = archive.toMutableEntries()
        writeIntoEntries(entries, annotations)
        return DocxArchive.write(entries, archive.entryMethods())
    }

    private fun writeIntoEntries(entries: MutableMap<String, ByteArray>, annotations: List<Annotation>) {

        // Save the original document.xml as a clean snapshot on first write.
        if (!entries.containsKey(CLEAN)) {
            entries[DOCUMENT]?.let { entries[CLEAN] = it }
        }
        // Always restore document.xml from clean before injecting, so each write
        // starts from the original state with no leftover markup.
        entries[CLEAN]?.let { entries[DOCUMENT] = it }

        // leamh/annotations.json — primary store.
        entries[ANNOTATIONS] =
            JsonWriter.encode(annotations.map { it.toMap() }).toByteArray(Charsets.UTF_8)

        val commentAnnotations = annotations.filter {
            it.note != null || it.tag != null || it.hasInk || it.threadEntries.isNotEmpty()
        }
        val inkAnnotations = commentAnnotations.filter { it.hasInk }

        if (commentAnnotations.isNotEmpty()) {
            entries[COMMENTS] =
                CommentWriter.buildCommentsXml(commentAnnotations).toByteArray(Charsets.UTF_8)
            entries[DOC_RELS]?.let {
                entries[DOC_RELS] =
                    CommentWriter.ensureRelsEntry(it.toString(Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
            }
            if (inkAnnotations.isNotEmpty()) {
                val existingRels = entries[COMMENTS_RELS]?.toString(Charsets.UTF_8)
                entries[COMMENTS_RELS] =
                    CommentWriter.buildCommentsRels(inkAnnotations, existingRels).toByteArray(Charsets.UTF_8)
                // Also add image rels to document.xml.rels so the inline drawing paragraph
                // (injected below) resolves in Pages and Google Docs.
                entries[DOC_RELS]?.let {
                    entries[DOC_RELS] =
                        CommentWriter.ensureDocInkRels(it.toString(Charsets.UTF_8), inkAnnotations)
                            .toByteArray(Charsets.UTF_8)
                }
            }
        } else if (entries.containsKey(COMMENTS)) {
            entries[COMMENTS] = CommentWriter.EMPTY_COMMENTS.toByteArray(Charsets.UTF_8)
        }

        // Clear Word's comment-threading sidecar (only when present) so its paraId
        // references — which point at the original comment paragraphs we just
        // rebuilt — can never dangle. We never create one: an absent file has
        // nothing to clear, and adding an unreferenced part would only risk repair.
        entries[COMMENTS_EXTENDED]?.let {
            entries[COMMENTS_EXTENDED] =
                CommentWriter.emptyCommentsExtended(it.toString(Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
        }

        entries[CONTENT_TYPES]?.let {
            entries[CONTENT_TYPES] = ContentTypes.ensure(it.toString(Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
        }

        entries[DOCUMENT]?.let {
            var injected = RunPropertyInjector.inject(it.toString(Charsets.UTF_8), annotations, commentAnnotations)
            // Insert an inline drawing paragraph after each ink annotation's paragraph so the
            // image is visible in Pages and Google Docs (which ignore images in comment bodies).
            if (inkAnnotations.isNotEmpty()) {
                val commentIdMap = commentAnnotations.withIndex().associate { (i, a) -> a.id to i }
                injected = InkAnchorInjector.inject(injected, inkAnnotations, commentIdMap)
            }
            entries[DOCUMENT] = injected.toByteArray(Charsets.UTF_8)
        }
    }

    /**
     * Embeds a PNG at `word/media/ink_<annotationId>.png`. Call before [write]
     * so the PNG is present when [load] auto-detects `hasInk`. Returns new DOCX
     * bytes; does not touch the filesystem.
     */
    fun saveInkPng(docxBytes: ByteArray, annotationId: String, pngBytes: ByteArray): ByteArray {
        val archive = DocxArchive.read(docxBytes)
        val entries = archive.toMutableEntries()
        entries["word/media/ink_$annotationId.png"] = pngBytes
        return DocxArchive.write(entries, archive.entryMethods())
    }

    /**
     * Reads the ink PNG for [annotationId] from the archive, or null if absent.
     * Used to pre-populate InkNoteActivity when editing an existing ink annotation.
     */
    fun readInkPng(docxBytes: ByteArray, annotationId: String): ByteArray? =
        DocxArchive.read(docxBytes).bytes("word/media/ink_$annotationId.png")

    /**
     * Embeds stroke JSON at `word/media/ink_<annotationId>_strokes.json`. Saved
     * alongside the PNG so lasso erase works on re-opened ink notes (vector
     * strokes are restored into InkCanvasView.committed on load).
     */
    fun saveInkStrokes(docxBytes: ByteArray, annotationId: String, json: String): ByteArray {
        val archive = DocxArchive.read(docxBytes)
        val entries = archive.toMutableEntries()
        entries["word/media/ink_${annotationId}_strokes.json"] = json.toByteArray(Charsets.UTF_8)
        return DocxArchive.write(entries, archive.entryMethods())
    }

    /** Reads stored stroke JSON for [annotationId], or null if absent (rasterized note). */
    fun readInkStrokes(docxBytes: ByteArray, annotationId: String): String? =
        DocxArchive.read(docxBytes).bytes("word/media/ink_${annotationId}_strokes.json")
            ?.toString(Charsets.UTF_8)

    /**
     * Removes all `*_strokes.json` files from the archive — flattens all ink
     * annotations to PNG-only. After this call lasso erase works at pixel level
     * (shaped hole in the raster) rather than removing whole strokes.
     */
    fun removeAllInkStrokes(docxBytes: ByteArray): ByteArray {
        val archive = DocxArchive.read(docxBytes)
        val entries = archive.toMutableEntries()
        entries.keys.removeAll { it.startsWith("word/media/ink_") && it.endsWith("_strokes.json") }
        return DocxArchive.write(entries, archive.entryMethods())
    }

    /** Returns true if the archive contains at least one `*_strokes.json` file. */
    fun hasAnyInkStrokes(docxBytes: ByteArray): Boolean =
        DocxArchive.read(docxBytes).names.any {
            it.startsWith("word/media/ink_") && it.endsWith("_strokes.json")
        }

    /** Writes/updates `leamh/position.json`. Mirror of `_savePositionInner`. */
    fun writePosition(docxBytes: ByteArray, position: ReadingPosition): ByteArray {
        val archive = DocxArchive.read(docxBytes)
        val entries = archive.toMutableEntries()
        entries[POSITION] = JsonWriter.encode(position.toMap()).toByteArray(Charsets.UTF_8)
        return DocxArchive.write(entries, archive.entryMethods())
    }

    // -------------------------------------------------------------------------
    // Ask-AI conversation transcript — `leamh/aichat.json`.
    //
    // Persisted IN the chapter DOCX (same convention as annotations) so an
    // in-app "Ask AI" thread suspends/resumes across leaving the panel, process
    // death, and reboot. [writeAiChat] touches ONLY this part, so it coexists
    // with annotation writes (both read the current bytes from disk via the
    // DocxWriteQueue and layer onto the previous commit).
    // -------------------------------------------------------------------------

    /** Reads the persisted Ask-AI transcript, or empty if absent/garbage (load contract). */
    fun readAiChat(docxBytes: ByteArray): List<AiTurn> = try {
        val raw = DocxArchive.read(docxBytes).text(AICHAT) ?: return emptyList()
        Json.parseArray(raw).filterIsInstance<Map<String, Any?>>().mapNotNull { m ->
            try {
                AiTurn.fromMap(m)
            } catch (e: Exception) {
                e.printStackTrace()
                null // skip one bad record; don't drop the whole transcript
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
        emptyList()
    }

    /** Writes/replaces `leamh/aichat.json`; leaves every other part untouched. */
    fun writeAiChat(docxBytes: ByteArray, turns: List<AiTurn>): ByteArray {
        val archive = DocxArchive.read(docxBytes)
        val entries = archive.toMutableEntries()
        entries[AICHAT] = JsonWriter.encode(turns.map { it.toMap() }).toByteArray(Charsets.UTF_8)
        // Ensure the `json` default content-type exists (a chapter that was never
        // annotated may lack it) so Word accepts the new part.
        entries[CONTENT_TYPES]?.let {
            entries[CONTENT_TYPES] = ContentTypes.ensure(it.toString(Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
        }
        return DocxArchive.write(entries, archive.entryMethods())
    }
}

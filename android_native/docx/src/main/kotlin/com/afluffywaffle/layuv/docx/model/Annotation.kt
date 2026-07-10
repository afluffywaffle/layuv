package com.afluffywaffle.layuv.docx.model

import java.time.Instant

/** `DateTime.now().microsecondsSinceEpoch.toString()` — mirror of `newId()`. */
fun newId(): String {
    val now = Instant.now()
    return (now.epochSecond * 1_000_000 + now.nano / 1000).toString()
}

/**
 * One comment in an annotation's thread. `source` is `"leamh"` (created in
 * Léamh — editable/deletable) or `"word"` (imported from a Word reply chain —
 * read-only). `timestamp` is epoch milliseconds.
 *
 * The thread, when non-empty, is the canonical comment body; [Annotation.note]
 * mirrors the first entry's text for backward compatibility (older readers and
 * the macOS app only know `note`). See [Annotation.threadEntries].
 */
data class ThreadEntry(
    val text: String,
    val timestamp: Long,
    val source: String,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "text" to text,
        "timestamp" to timestamp,
        "source" to source,
    )

    companion object {
        const val SOURCE_LEAMH = "leamh"
        const val SOURCE_WORD = "word"

        fun fromMap(map: Map<String, Any?>): ThreadEntry = ThreadEntry(
            text = (map["text"] as? String) ?: "",
            timestamp = (map["timestamp"] as? Number)?.toLong() ?: 0L,
            source = (map["source"] as? String) ?: SOURCE_LEAMH,
        )
    }
}

/**
 * A Léamh annotation. Field-for-field mirror of lib/models/annotation.dart.
 *
 * [toMap]/[fromMap] reproduce Dart's `toJson`/`fromJson` structurally (the
 * `Map<String, dynamic>` layer); the JSON string itself is handled one layer
 * up (org.json on Android), exactly as Dart splits `toJson` from `jsonEncode`.
 * Key order in [toMap] matches Dart insertion order.
 */
data class Annotation(
    val id: String,
    val selectedText: String,
    val prefix: String,
    val suffix: String,
    val tool: AnnotationTool,
    val note: String? = null,
    val tag: AnnotationTag? = null,
    val timestamp: Instant,
    val position: Double = 0.0,
    /**
     * 1-indexed paragraph number this annotation anchors to, computed via
     * [com.afluffywaffle.layuv.docx.PlainTextMapper.paragraphIndex] from the exact char
     * offset available at creation/parse time — NOT reverse-derived from [position]. Like
     * [position], it reflects the document as of the last (re-)anchor and can go stale if the
     * document is edited elsewhere without Léamh re-anchoring; 0 means never computed
     * (legacy record).
     */
    val paragraph: Int = 0,
    val hasInk: Boolean = false,
    /**
     * Chronological comment thread. Empty for legacy/single-note annotations
     * (the [note] field carries those). When non-empty, the first entry's text
     * equals [note] (backward compatibility) and the thread is the source of
     * truth for the comment body written to `word/comments.xml`.
     */
    val threadEntries: List<ThreadEntry> = emptyList(),
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "selectedText" to selectedText,
        "prefix" to prefix,
        "suffix" to suffix,
        "tool" to tool.name,
        "note" to note,
        "tag" to tag?.name,
        "timestamp" to Timestamps.format(timestamp),
        "position" to position,
        "paragraph" to paragraph,
        "hasInk" to hasInk,
        "threadEntries" to threadEntries.map { it.toMap() },
    )

    companion object {
        fun fromMap(map: Map<String, Any?>): Annotation {
            // Dart: json['tool'] ?? json['toolType'] (legacy) ?? highlight.name
            val toolName =
                (map["tool"] as? String) ?: (map["toolType"] as? String) ?: AnnotationTool.highlight.name
            val timestampStr = (map["timestamp"] as? String) ?: ""
            return Annotation(
                // Dart falls back to timestamp as id for old records without one.
                id = (map["id"] as? String) ?: timestampStr.ifEmpty { newId() },
                selectedText = (map["selectedText"] as? String) ?: "",
                prefix = (map["prefix"] as? String) ?: "",
                suffix = (map["suffix"] as? String) ?: "",
                tool = AnnotationTool.fromName(toolName),
                note = map["note"] as? String,
                tag = AnnotationTag.fromName(map["tag"] as? String),
                timestamp = Timestamps.parse(timestampStr),
                position = (map["position"] as? Number)?.toDouble() ?: 0.0,
                paragraph = (map["paragraph"] as? Number)?.toInt() ?: 0,
                hasInk = (map["hasInk"] as? Boolean) ?: false,
                threadEntries = (map["threadEntries"] as? List<*>)
                    ?.filterIsInstance<Map<String, Any?>>()
                    ?.map { ThreadEntry.fromMap(it) }
                    ?: emptyList(),
            )
        }
    }
}

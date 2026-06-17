package com.afluffywaffle.layuv.docx.model

import java.time.Instant

/** `DateTime.now().microsecondsSinceEpoch.toString()` — mirror of `newId()`. */
fun newId(): String {
    val now = Instant.now()
    return (now.epochSecond * 1_000_000 + now.nano / 1000).toString()
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
    val hasInk: Boolean = false,
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
        "hasInk" to hasInk,
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
                hasInk = (map["hasInk"] as? Boolean) ?: false,
            )
        }
    }
}

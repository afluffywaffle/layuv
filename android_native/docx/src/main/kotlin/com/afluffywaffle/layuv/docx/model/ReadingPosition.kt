package com.afluffywaffle.layuv.docx.model

/** Mirror of lib/models/reading_position.dart. `fraction` is the 0.0–1.0 plain-text position. */
data class ReadingPosition(
    val mode: ReadingMode,
    val page: Int,
    val scrollOffset: Double,
    val fraction: Double = 0.0,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "mode" to mode.name,
        "page" to page,
        "scrollOffset" to scrollOffset,
        "fraction" to fraction,
    )

    companion object {
        fun fromMap(map: Map<String, Any?>): ReadingPosition = ReadingPosition(
            mode = ReadingMode.byName(map["mode"] as String),
            page = (map["page"] as Number).toInt(),
            scrollOffset = (map["scrollOffset"] as Number).toDouble(),
            fraction = (map["fraction"] as? Number)?.toDouble() ?: 0.0,
        )
    }
}

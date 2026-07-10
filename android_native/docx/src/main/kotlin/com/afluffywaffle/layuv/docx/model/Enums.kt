package com.afluffywaffle.layuv.docx.model

// The enum constant names are lowercase/camelCase ON PURPOSE: they must equal
// Dart's `enum.name` so JSON is byte-compatible (e.g. "doubleUnderline",
// "screenFlip", "inkAnnotation"). Do NOT rename to UPPER_CASE without adding a
// serialization mapping. Mirrors lib/models/annotation.dart + reading_position.dart.

@Suppress("EnumEntryName")
enum class ReadingMode {
    scroll, screenFlip, pageFlip;

    companion object {
        /** Mirrors `ReadingMode.values.byName(name)` (throws on unknown). */
        fun byName(name: String): ReadingMode = valueOf(name)
    }
}

@Suppress("EnumEntryName")
enum class AnnotationTool {
    highlight,
    underline,
    doubleUnderline,
    strikethrough,
    wavyUnderline,
    bookmark,
    inkAnnotation,
    comment,
    /** Whole-paragraph grey fill + rust left border — Word w:pBdr/paragraph w:shd import, or "Highlight Paragraph". */
    blockquote;

    companion object {
        /**
         * Mirrors `AnnotationTool.values.byName(toolName)` but with the same
         * `highlight` fallback the Dart `fromJson` applies, so an unknown/garbage
         * tool string degrades gracefully instead of throwing.
         */
        fun fromName(name: String?): AnnotationTool =
            entries.firstOrNull { it.name == name } ?: highlight
    }
}

@Suppress("EnumEntryName")
enum class AnnotationTag {
    voice, pacing, continuity, query;

    companion object {
        /** Mirrors Dart's `where((t) => t.name == tag).firstOrNull` (null if unknown). */
        fun fromName(name: String?): AnnotationTag? =
            entries.firstOrNull { it.name == name }
    }
}

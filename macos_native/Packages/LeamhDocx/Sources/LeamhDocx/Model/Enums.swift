// Enum raw values are lowercase/camelCase to match Dart's enum.name JSON serialization
// (e.g. "doubleUnderline", "screenFlip", "inkAnnotation"). Do NOT rename to UPPER_CASE
// without adding a serialization mapping. Mirrors model/Enums.kt and lib/models/annotation.dart.

public enum ReadingMode: String {
    case scroll, screenFlip, pageFlip
}

public enum AnnotationTool: String {
    case highlight
    case underline
    case doubleUnderline
    case strikethrough
    case wavyUnderline
    case bookmark
    case inkAnnotation
    case comment
    /// Whole-paragraph grey fill + rust left border — Word w:pBdr/paragraph w:shd import, or "Highlight Paragraph".
    case blockquote

    static func fromName(_ name: String?) -> AnnotationTool {
        guard let name, let tool = AnnotationTool(rawValue: name) else { return .highlight }
        return tool
    }
}

public enum AnnotationTag: String {
    case voice, pacing, continuity, query

    static func fromName(_ name: String?) -> AnnotationTag? {
        guard let name else { return nil }
        return AnnotationTag(rawValue: name)
    }
}

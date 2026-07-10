import Foundation

/// Microseconds since epoch as a String — mirrors Kotlin newId() and Dart DateTime.now().microsecondsSinceEpoch.toString()
func newId() -> String {
    let micros = Int64(Date().timeIntervalSince1970 * 1_000_000)
    return String(micros)
}

/// One comment in an annotation's thread. `source` is `"leamh"` (created in Léamh — editable)
/// or `"word"` (imported from a Word reply chain — read-only). `timestamp` is epoch milliseconds.
/// When the thread is non-empty, entry 0's text mirrors `Annotation.note` (backward compat).
/// Mirror of ThreadEntry in Annotation.kt.
public struct ThreadEntry: Equatable {
    public let text: String
    public let timestamp: Int64
    public let source: String

    public static let sourceLeamh = "leamh"
    public static let sourceWord  = "word"

    public init(text: String, timestamp: Int64, source: String) {
        self.text = text
        self.timestamp = timestamp
        self.source = source
    }

    func toMap() -> [String: Any?] {
        return [
            "text": text,
            "timestamp": timestamp,
            "source": source,
        ]
    }

    static func fromMap(_ map: [String: Any?]) -> ThreadEntry {
        ThreadEntry(
            text: (map["text"] as? String) ?? "",
            timestamp: (map["timestamp"] as? NSNumber)?.int64Value ?? 0,
            source: (map["source"] as? String) ?? sourceLeamh
        )
    }
}

/// A Léamh annotation. Field-for-field mirror of Annotation.kt / lib/models/annotation.dart.
/// toMap/fromMap reproduce Dart's toJson/fromJson structurally; JSON encoding is one layer up.
public struct Annotation: Equatable {
    public let id: String
    public let selectedText: String
    public let prefix: String
    public let suffix: String
    public let tool: AnnotationTool
    public let note: String?
    public let tag: AnnotationTag?
    public let timestamp: Date
    public let position: Double
    /// 1-indexed paragraph number this annotation anchors to, computed via
    /// `PlainTextMapper.paragraphIndex` from the exact char offset available at
    /// creation/parse time — NOT reverse-derived from `position`. Like `position`, reflects
    /// the document as of the last (re-)anchor; 0 means never computed (legacy record).
    /// Mirrors `Annotation.paragraph` in Kotlin.
    public let paragraph: Int
    public let hasInk: Bool
    /// Chronological comment thread. Empty for legacy/single-note annotations (the `note` field
    /// carries those). When non-empty, entry 0's text equals `note` and the thread is the source
    /// of truth for the comment body written to `word/comments.xml`.
    public let threadEntries: [ThreadEntry]

    public init(
        id: String,
        selectedText: String,
        prefix: String,
        suffix: String,
        tool: AnnotationTool,
        note: String? = nil,
        tag: AnnotationTag? = nil,
        timestamp: Date,
        position: Double = 0.0,
        paragraph: Int = 0,
        hasInk: Bool = false,
        threadEntries: [ThreadEntry] = []
    ) {
        self.id = id
        self.selectedText = selectedText
        self.prefix = prefix
        self.suffix = suffix
        self.tool = tool
        self.note = note
        self.tag = tag
        self.timestamp = timestamp
        self.position = position
        self.paragraph = paragraph
        self.hasInk = hasInk
        self.threadEntries = threadEntries
    }

    // Key order matches Dart insertion order for JSON compatibility.
    func toMap() -> [String: Any?] {
        return [
            "id": id,
            "selectedText": selectedText,
            "prefix": prefix,
            "suffix": suffix,
            "tool": tool.rawValue,
            "note": note as Any?,
            "tag": tag?.rawValue as Any?,
            "timestamp": Timestamps.format(timestamp),
            "position": position,
            "paragraph": paragraph,
            "hasInk": hasInk,
            "threadEntries": threadEntries.map { $0.toMap() },
        ]
    }

    static func fromMap(_ map: [String: Any?]) -> Annotation {
        // Dart: json['tool'] ?? json['toolType'] (legacy) ?? highlight.name
        let toolName = (map["tool"] as? String) ?? (map["toolType"] as? String)
        let timestampStr = (map["timestamp"] as? String) ?? ""
        let idVal = (map["id"] as? String) ?? (timestampStr.isEmpty ? newId() : timestampStr)
        return Annotation(
            id: idVal,
            selectedText: (map["selectedText"] as? String) ?? "",
            prefix: (map["prefix"] as? String) ?? "",
            suffix: (map["suffix"] as? String) ?? "",
            tool: AnnotationTool.fromName(toolName),
            note: map["note"] as? String,
            tag: AnnotationTag.fromName(map["tag"] as? String),
            timestamp: Timestamps.parse(timestampStr),
            position: (map["position"] as? NSNumber)?.doubleValue ?? 0.0,
            paragraph: (map["paragraph"] as? NSNumber)?.intValue ?? 0,
            hasInk: (map["hasInk"] as? Bool) ?? false,
            threadEntries: ((map["threadEntries"] as? [[String: Any]]) ?? [])
                .map { ThreadEntry.fromMap($0 as [String: Any?]) }
        )
    }

    func copy(
        id: String? = nil,
        selectedText: String? = nil,
        prefix: String? = nil,
        suffix: String? = nil,
        tool: AnnotationTool? = nil,
        note: String?? = nil,
        tag: AnnotationTag?? = nil,
        timestamp: Date? = nil,
        position: Double? = nil,
        paragraph: Int? = nil,
        hasInk: Bool? = nil,
        threadEntries: [ThreadEntry]? = nil
    ) -> Annotation {
        Annotation(
            id: id ?? self.id,
            selectedText: selectedText ?? self.selectedText,
            prefix: prefix ?? self.prefix,
            suffix: suffix ?? self.suffix,
            tool: tool ?? self.tool,
            note: note ?? self.note,
            tag: tag ?? self.tag,
            timestamp: timestamp ?? self.timestamp,
            position: position ?? self.position,
            paragraph: paragraph ?? self.paragraph,
            hasInk: hasInk ?? self.hasInk,
            threadEntries: threadEntries ?? self.threadEntries
        )
    }
}

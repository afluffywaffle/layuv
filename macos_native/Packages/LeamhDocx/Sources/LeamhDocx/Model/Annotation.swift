import Foundation

/// Microseconds since epoch as a String — mirrors Kotlin newId() and Dart DateTime.now().microsecondsSinceEpoch.toString()
func newId() -> String {
    let micros = Int64(Date().timeIntervalSince1970 * 1_000_000)
    return String(micros)
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
    public let hasInk: Bool

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
        hasInk: Bool = false
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
        self.hasInk = hasInk
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
            "hasInk": hasInk,
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
            hasInk: (map["hasInk"] as? Bool) ?? false
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
        hasInk: Bool? = nil
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
            hasInk: hasInk ?? self.hasInk
        )
    }
}

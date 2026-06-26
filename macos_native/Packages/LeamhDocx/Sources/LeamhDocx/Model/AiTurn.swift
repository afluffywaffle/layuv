import Foundation

/// One turn in an in-app "Ask AI" conversation, persisted in the chapter DOCX at
/// `leamh/aichat.json` so the thread suspends/resumes across leaving the panel,
/// process death, and reboot. Mirror of AiTurn.kt.
///
/// `role` is `"user"` or `"assistant"`. `truncated` marks an assistant turn that
/// stopped at the model's max_tokens (a "Continue" is offered for it). All parsing
/// is null-tolerant; the JSON string itself is handled one layer up (JsonWriter).
public struct AiTurn: Equatable {
    public let role: String
    public let text: String
    public let truncated: Bool

    public init(role: String, text: String, truncated: Bool = false) {
        self.role = role
        self.text = text
        self.truncated = truncated
    }

    public static let roleUser = "user"
    public static let roleAssistant = "assistant"

    func toMap() -> [String: Any?] {
        [
            "role": role,
            "text": text,
            "truncated": truncated,
        ]
    }

    static func fromMap(_ map: [String: Any?]) -> AiTurn {
        AiTurn(
            role: (map["role"] as? String) ?? roleUser,
            text: (map["text"] as? String) ?? "",
            truncated: (map["truncated"] as? Bool) ?? false
        )
    }
}

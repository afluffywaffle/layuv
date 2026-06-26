import Foundation

/// Wire protocol for the in-app "Ask AI" conversation. The model discusses or asks
/// questions in plain text, and when it delivers a full chapter rewrite it wraps ONLY
/// the manuscript prose between the `begin` and `end` marker lines. The app renders
/// discussion inline but turns a rewrite into a "save as a new draft" action. Single
/// source of truth for both the prompt instructions (ManuscriptSerializer) and the
/// reply parser (the Ask AI panel). Mirror of RewriteProtocol.kt.
public enum RewriteProtocol {
    public static let begin = "===REWRITE==="
    public static let end = "===END REWRITE==="

    /// `conversation` = discussion text (may be empty); `rewrite` = chapter prose, or nil if none.
    public struct Parsed: Equatable {
        public let conversation: String
        public let rewrite: String?
        public init(conversation: String, rewrite: String?) {
            self.conversation = conversation
            self.rewrite = rewrite
        }
    }

    /// Split a reply into its discussion text and (if present) the rewritten chapter.
    /// A reply with no `begin` marker is pure conversation. An unterminated block
    /// (truncated mid-rewrite) treats everything after `begin` as the partial rewrite.
    public static func parse(_ text: String) -> Parsed {
        let ws = CharacterSet.whitespacesAndNewlines
        guard let beginRange = text.range(of: begin) else {
            return Parsed(conversation: text.trimmingCharacters(in: ws), rewrite: nil)
        }
        let conversation = String(text[..<beginRange.lowerBound]).trimmingCharacters(in: ws)
        let afterBegin = beginRange.upperBound
        let rewriteRaw: String
        if let endRange = text.range(of: end, range: afterBegin..<text.endIndex) {
            rewriteRaw = String(text[afterBegin..<endRange.lowerBound])
        } else {
            rewriteRaw = String(text[afterBegin...])
        }
        let rewrite = rewriteRaw.trimmingCharacters(in: ws)
        return Parsed(conversation: conversation, rewrite: rewrite.isEmpty ? nil : rewrite)
    }
}

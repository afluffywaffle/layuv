import Foundation

/// General (non-AI) app-wide settings, UserDefaults-backed and published so settings views
/// can bind directly. Kept separate from `AiProviderSettings` so author identity isn't
/// conceptually tied to the AI provider config.
final class AppSettings: ObservableObject {

    static let shared = AppSettings()

    private enum Key {
        static let authorName = "com.afluffywaffle.layuv.author_name"
    }

    /// The global default author name written into DOCX comments (`w:author`). Empty means
    /// "unset" → callers fall back to `"Layuv"`. A per-document override may take precedence
    /// (see `DocumentStore.effectiveAuthor`).
    @Published var authorName: String {
        didSet { UserDefaults.standard.set(authorName, forKey: Key.authorName) }
    }

    /// The global default author, falling back to `"Layuv"` when unset.
    var effectiveGlobalAuthor: String {
        let t = authorName.trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? "Layuv" : t
    }

    private init() {
        authorName = UserDefaults.standard.string(forKey: Key.authorName) ?? ""
    }
}

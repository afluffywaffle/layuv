import Foundation

/// UserDefaults-backed settings for the OpenAI-compatible AI provider.
/// Published so settings views can bind directly.
final class AiProviderSettings: ObservableObject {

    static let shared = AiProviderSettings()

    private enum Key {
        static let baseUrl = "com.afluffywaffle.layuv.ai_base_url"
        static let model   = "com.afluffywaffle.layuv.ai_model"
    }

    @Published var baseUrl: String {
        didSet { UserDefaults.standard.set(baseUrl, forKey: Key.baseUrl) }
    }

    @Published var model: String {
        didSet { UserDefaults.standard.set(model, forKey: Key.model) }
    }

    /// API key is in the Keychain, not UserDefaults. This is a transient binding
    /// for the settings form only — reads/writes go through SecureKeyStore.
    @Published var apiKey: String = "" {
        didSet {
            if apiKey.isEmpty {
                SecureKeyStore.delete(key: SecureKeyStore.apiKeyName)
            } else {
                SecureKeyStore.write(key: SecureKeyStore.apiKeyName, value: apiKey)
            }
        }
    }

    var isConfigured: Bool { !baseUrl.trimmingCharacters(in: .whitespaces).isEmpty }

    private init() {
        baseUrl = UserDefaults.standard.string(forKey: Key.baseUrl) ?? ""
        model   = UserDefaults.standard.string(forKey: Key.model)   ?? "claude-sonnet-4-6"
        // Load the key into the transient field (read-only mirror; mutations go through didSet).
        apiKey  = SecureKeyStore.read(key: SecureKeyStore.apiKeyName) ?? ""
    }
}

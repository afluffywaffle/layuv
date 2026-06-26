import SwiftUI

/// AI provider configuration form: endpoint URL, model, optional API key.
/// Settings are backed by AiProviderSettings (UserDefaults + Keychain).
struct AiSettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @StateObject private var settings = AiProviderSettings.shared
    @State private var showKey = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("http://mini.local:8080", text: $settings.baseUrl)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .keyboardType(.URL)
                } header: {
                    Text("Endpoint")
                } footer: {
                    Text("The base URL of your OpenAI-compatible server. Plain HTTP is allowed for local/private addresses; everything else must use HTTPS.")
                        .font(.footnote)
                }

                Section {
                    TextField("claude-sonnet-4-6", text: $settings.model)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                } header: {
                    Text("Model")
                } footer: {
                    Text("Model ID sent in each request. Leave blank to use the server default.")
                        .font(.footnote)
                }

                Section {
                    HStack {
                        Group {
                            if showKey {
                                TextField("sk-…", text: $settings.apiKey)
                            } else {
                                SecureField("sk-…", text: $settings.apiKey)
                            }
                        }
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)

                        Button {
                            showKey.toggle()
                        } label: {
                            Image(systemName: showKey ? "eye.slash" : "eye")
                                .foregroundStyle(.secondary)
                        }
                        .buttonStyle(.plain)
                    }
                } header: {
                    Text("API Key (optional)")
                } footer: {
                    Text("Stored in the Keychain. Leave blank if your endpoint doesn't require a key (e.g. a local brain proxy).")
                        .font(.footnote)
                }

                if settings.isConfigured {
                    Section {
                        Button(role: .destructive) {
                            settings.baseUrl = ""
                            settings.model = "claude-sonnet-4-6"
                            settings.apiKey = ""
                        } label: {
                            Label("Clear AI Settings", systemImage: "trash")
                        }
                    }
                }
            }
            .navigationTitle("AI Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                        .fontWeight(.semibold)
                }
            }
        }
    }
}

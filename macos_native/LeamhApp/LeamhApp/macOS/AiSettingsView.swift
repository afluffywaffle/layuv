import SwiftUI

/// AI provider configuration form (macOS): endpoint URL, model, optional API key.
/// Settings are backed by AiProviderSettings (UserDefaults + Keychain). Mirrors the iOS form.
struct AiSettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @StateObject private var settings = AiProviderSettings.shared
    @State private var showKey = false

    var body: some View {
        VStack(spacing: 0) {
            Form {
                Section {
                    TextField("http://mini.local:8080", text: $settings.baseUrl)
                        .autocorrectionDisabled()
                        .textContentType(.URL)
                } header: {
                    Text("Endpoint")
                } footer: {
                    Text("The base URL of your OpenAI-compatible server. Plain HTTP is allowed for local/private addresses; everything else must use HTTPS.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                Section {
                    TextField("claude-sonnet-4-6", text: $settings.model)
                        .autocorrectionDisabled()
                } header: {
                    Text("Model")
                } footer: {
                    Text("Model ID sent in each request. Leave blank to use the server default.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
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
                        .foregroundStyle(.secondary)
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
            .formStyle(.grouped)

            Divider()
            HStack {
                Spacer()
                Button("Done") { dismiss() }
                    .keyboardShortcut(.defaultAction)
            }
            .padding(12)
        }
        .frame(width: 460, height: 460)
    }
}

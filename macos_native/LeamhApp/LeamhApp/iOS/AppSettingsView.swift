import SwiftUI

/// Consolidated app preferences (iOS/iPadOS), mirroring the macOS `AppSettingsView`. Presented as a
/// sheet from the reader's overflow / gear entry point so it can read the shared `DocumentStore`
/// (needed for the per-document author section). Sectioned: global app prefs first, then a clearly
/// separated "This Document" section for per-document overrides.
///
/// Note: "Left-Handed Navigation (WASD)" is macOS-only (hardware-keyboard nav) and is intentionally
/// omitted here — iOS navigates by touch.
struct AppSettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject var store: DocumentStore
    @StateObject private var app = AppSettings.shared
    @StateObject private var ai  = AiProviderSettings.shared
    @State private var showKey = false

    private var hasDocument: Bool { store.currentURL != nil }

    var body: some View {
        NavigationStack {
            Form {
                // MARK: Global app settings

                Section {
                    TextField("Layuv", text: $app.authorName)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.words)
                } header: {
                    Text("Author Name")
                } footer: {
                    Text("Written as the author of the comments Layuv saves into your DOCX. Falls back to “Layuv” when blank. A single document can override this below.")
                        .font(.footnote)
                }

                Section("Typography") {
                    Picker("Font", selection: $store.fontChoice) {
                        ForEach(FontChoice.allCases, id: \.rawValue) { Text($0.label).tag($0) }
                    }
                    Picker("Text Size", selection: $store.bodyTextSize) {
                        ForEach(BodyTextSize.allCases, id: \.rawValue) { Text($0.label).tag($0) }
                    }
                    Picker("Line Spacing", selection: $store.lineSpacing) {
                        ForEach(LineSpacing.allCases, id: \.rawValue) { Text($0.label).tag($0) }
                    }
                }

                Section {
                    Picker("Paper Theme", selection: $store.paperTheme) {
                        ForEach(PaperTheme.allCases, id: \.rawValue) { Text($0.label).tag($0) }
                    }
                    Toggle("Two Columns (Page Flip)", isOn: $store.twoColumnPaged)
                    Toggle("Follow System Dark Mode (Night)", isOn: $store.followsDarkMode)
                    Toggle("Reading Marker on Double-Tap", isOn: $store.markerOnDoubleClick)
                } header: {
                    Text("Reader")
                } footer: {
                    Text("Paper theme is remembered per document; the current pick becomes the default for new documents. Reading marker: off = single-tap marks your place and double-tap opens an annotation; on = single-tap opens an annotation and double-tap marks your place.")
                        .font(.footnote)
                }

                Section {
                    TextField("http://mini.local:8080", text: $ai.baseUrl)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .keyboardType(.URL)
                    TextField("claude-sonnet-4-6", text: $ai.model)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                    HStack {
                        Group {
                            if showKey { TextField("sk-…", text: $ai.apiKey) }
                            else       { SecureField("sk-…", text: $ai.apiKey) }
                        }
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        Button { showKey.toggle() } label: {
                            Image(systemName: showKey ? "eye.slash" : "eye")
                                .foregroundStyle(.secondary)
                        }
                        .buttonStyle(.plain)
                    }
                } header: {
                    Text("AI Provider")
                } footer: {
                    Text("OpenAI-compatible endpoint. API key is stored in the Keychain. Plain HTTP is allowed for local/private addresses only.")
                        .font(.footnote)
                }

                // MARK: Per-document settings

                Section {
                    TextField(app.effectiveGlobalAuthor, text: $store.currentDocAuthor)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.words)
                        .disabled(!hasDocument)
                } header: {
                    Text("This Document")
                } footer: {
                    Text(hasDocument
                         ? "Author override for “\(store.windowTitle)”. Leave blank to use the global author (“\(app.effectiveGlobalAuthor)”)."
                         : "Open a document to set a per-document author override.")
                        .font(.footnote)
                }
            }
            .navigationTitle("Settings")
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

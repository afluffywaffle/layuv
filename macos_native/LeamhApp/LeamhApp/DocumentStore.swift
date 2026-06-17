import Foundation
import AppKit

@MainActor
final class DocumentStore: ObservableObject {
    @Published private(set) var document: LoadedDocument?
    @Published private(set) var annotations: [ResolvedAnnotation] = []
    @Published private(set) var recentURLs: [URL] = []
    @Published private(set) var isLoading = false
    @Published var selectedAnnotationId: String?

    private var currentURL: URL?
    private let recentsKey = "com.afluffywaffle.layuv.recentFiles"

    init() { loadRecents() }

    // MARK: - File access

    func openFilePanel() {
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [.init(filenameExtension: "docx")].compactMap { $0 }
        panel.allowsMultipleSelection = false
        panel.canChooseDirectories = false
        guard panel.runModal() == .OK, let url = panel.url else { return }
        Task { await load(url: url) }
    }

    func load(url: URL) async {
        isLoading = true
        do {
            let doc = try await Task.detached(priority: .userInitiated) {
                let data = try Data(contentsOf: url)
                return try DocxStore.load(data)
            }.value
            self.document = doc
            self.annotations = doc.annotations
            self.currentURL = url
            addRecent(url)
        } catch {
            print("[DocumentStore] load failed: \(error)")
        }
        isLoading = false
    }

    // MARK: - Save

    // Reads base bytes fresh from disk per CLAUDE.md invariant, then writes atomically.
    func save() async {
        guard let url = currentURL else { return }
        let annotationsToWrite = annotations.map(\.annotation)
        do {
            try await Task.detached(priority: .userInitiated) {
                let base    = try Data(contentsOf: url)
                let updated = try DocxStore.write(base, annotations: annotationsToWrite)
                let tmpURL  = url.deletingLastPathComponent()
                    .appendingPathComponent(".\(url.lastPathComponent).tmp")
                try updated.write(to: tmpURL)
                _ = try FileManager.default.replaceItemAt(url, withItemAt: tmpURL)
            }.value
        } catch {
            print("[DocumentStore] save failed: \(error)")
        }
    }

    // MARK: - Annotation mutations

    func deleteAnnotation(id: String) {
        annotations.removeAll { $0.annotation.id == id }
    }

    // MARK: - Recents

    private func addRecent(_ url: URL) {
        var urls = recentURLs.filter { $0 != url }
        urls.insert(url, at: 0)
        recentURLs = Array(urls.prefix(10))
        // Store paths; upgrade to security-scoped bookmarks when enabling full App Sandbox.
        UserDefaults.standard.set(urls.map(\.path), forKey: recentsKey)
    }

    private func loadRecents() {
        let paths = UserDefaults.standard.stringArray(forKey: recentsKey) ?? []
        recentURLs = paths
            .map(URL.init(fileURLWithPath:))
            .filter { FileManager.default.fileExists(atPath: $0.path) }
    }
}

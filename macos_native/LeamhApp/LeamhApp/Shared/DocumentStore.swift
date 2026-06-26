import Foundation
#if os(macOS)
import AppKit
#endif

// App-layer Identifiable conformance so Annotation can be used as .sheet(item:).
extension Annotation: Identifiable {}

@MainActor
final class DocumentStore: ObservableObject {
    @Published private(set) var document: LoadedDocument?
    @Published private(set) var annotations: [ResolvedAnnotation] = []
    @Published private(set) var recentURLs: [URL] = []
    @Published private(set) var isLoading = false
    @Published var selectedAnnotationId: String?
    /// Set to open the annotation edit sheet from anywhere (VC tap, panel row, comment creation).
    @Published var editingAnnotation: Annotation?

    private var currentURL: URL?
    // Track the URL whose security scope we're holding so we can release on next load.
    private var accessedURL: URL?

    private let recentsKey  = "com.afluffywaffle.layuv.recentFiles"
    private let bookmarksKey = "com.afluffywaffle.layuv.bookmarks"

    // Security-scoped bookmarks: macOS app-sandbox needs the .withSecurityScope option;
    // iOS uses plain bookmarks for document-picker URLs (still wrapped in start/stopAccessing…).
    #if os(macOS)
    private static let bookmarkCreationOptions: URL.BookmarkCreationOptions = .withSecurityScope
    private static let bookmarkResolutionOptions: URL.BookmarkResolutionOptions = .withSecurityScope
    #else
    private static let bookmarkCreationOptions: URL.BookmarkCreationOptions = []
    private static let bookmarkResolutionOptions: URL.BookmarkResolutionOptions = []
    #endif

    init() { loadRecents() }

    // MARK: - File access

    #if os(macOS)
    func openFilePanel() {
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [.init(filenameExtension: "docx")].compactMap { $0 }
        panel.allowsMultipleSelection = false
        panel.canChooseDirectories = false
        guard panel.runModal() == .OK, let url = panel.url else { return }
        Task { await load(url: url) }
    }
    #endif

    func load(url: URL) async {
        isLoading = true
        // Release previous security-scoped access before acquiring new one.
        if let prev = accessedURL { prev.stopAccessingSecurityScopedResource() }
        accessedURL = url.startAccessingSecurityScopedResource() ? url : nil

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

    func addAnnotation(_ annotation: Annotation) {
        let span = document.map {
            Anchoring.locateInPlain(
                $0.plainText,
                selectedText: annotation.selectedText,
                prefix: annotation.prefix,
                suffix: annotation.suffix,
                positionHint: annotation.position
            )
        } ?? nil
        annotations.append(ResolvedAnnotation(annotation: annotation, span: span))
        Task { await save() }
    }

    func updateAnnotation(_ annotation: Annotation) {
        guard let idx = annotations.firstIndex(where: { $0.annotation.id == annotation.id }) else { return }
        let span = document.map {
            Anchoring.locateInPlain(
                $0.plainText,
                selectedText: annotation.selectedText,
                prefix: annotation.prefix,
                suffix: annotation.suffix,
                positionHint: annotation.position
            )
        } ?? nil
        annotations[idx] = ResolvedAnnotation(annotation: annotation, span: span)
        Task { await save() }
    }

    func deleteAnnotation(id: String) {
        annotations.removeAll { $0.annotation.id == id }
        Task { await save() }
    }

    // MARK: - Recents (security-scoped bookmarks)

    private func addRecent(_ url: URL) {
        var urls = recentURLs.filter { $0 != url }
        urls.insert(url, at: 0)
        recentURLs = Array(urls.prefix(10))

        // Persist paths list.
        UserDefaults.standard.set(urls.map(\.path), forKey: recentsKey)

        // Store/refresh bookmark for this URL.
        storeBookmark(for: url)
    }

    private func loadRecents() {
        let paths = UserDefaults.standard.stringArray(forKey: recentsKey) ?? []
        recentURLs = paths.compactMap { resolveURL(forPath: $0) }
    }

    // Creates/refreshes a (security-scoped on macOS) bookmark for url.
    private func storeBookmark(for url: URL) {
        guard let data = try? url.bookmarkData(
            options: Self.bookmarkCreationOptions,
            includingResourceValuesForKeys: nil,
            relativeTo: nil
        ) else { return }
        var map = bookmarkMap()
        map[url.path] = data
        UserDefaults.standard.set(map, forKey: bookmarksKey)
    }

    // Resolves stored bookmark for path; falls back to plain file URL if no bookmark.
    private func resolveURL(forPath path: String) -> URL? {
        let fallback = URL(fileURLWithPath: path)
        guard let map = bookmarkMap() as [String: Data]?,
              let data = map[path] else {
            return FileManager.default.fileExists(atPath: path) ? fallback : nil
        }
        var isStale = false
        guard let resolved = try? URL(
            resolvingBookmarkData: data,
            options: Self.bookmarkResolutionOptions,
            relativeTo: nil,
            bookmarkDataIsStale: &isStale
        ) else {
            return FileManager.default.fileExists(atPath: path) ? fallback : nil
        }
        if isStale { storeBookmark(for: resolved) }
        return resolved
    }

    private func bookmarkMap() -> [String: Data] {
        UserDefaults.standard.dictionary(forKey: bookmarksKey) as? [String: Data] ?? [:]
    }
}

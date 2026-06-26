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

    @Published private(set) var currentURL: URL?
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

    /// Serializes ALL DOCX writes so they never overlap on disk — the macOS/iPad analogue of
    /// Android's DocxWriteQueue (CLAUDE.md invariant #2). Each write reads base bytes fresh from
    /// disk, transforms, and atomic-renames; the next write awaits the previous.
    private var writeChain: Task<Void, Never> = Task {}

    // Reads base bytes fresh from disk per CLAUDE.md invariant, then writes atomically.
    func save() async {
        guard let url = currentURL else { return }
        let annotationsToWrite = annotations.map(\.annotation)
        await enqueueWrite(url: url) { base in
            try DocxStore.write(base, annotations: annotationsToWrite)
        }
    }

    /// The single serialized write path. Reads from disk inside the chained task so each write
    /// sees the previous write's result (never a stale in-memory base).
    private func enqueueWrite(url: URL, _ transform: @escaping @Sendable (Data) throws -> Data) async {
        let previous = writeChain
        let task = Task {
            await previous.value
            await Task.detached(priority: .userInitiated) {
                do {
                    let base = try Data(contentsOf: url)
                    let out  = try transform(base)
                    try Self.atomicReplace(out, at: url)
                } catch {
                    print("[DocumentStore] write failed: \(error)")
                }
            }.value
        }
        writeChain = task
        await task.value
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

    // MARK: - Ink (Apple Pencil)

    /// Set to present the ink editor (iPad). Annotation: Identifiable drives .fullScreenCover(item:).
    @Published var inkEditingAnnotation: Annotation?

    /// Add a new ink annotation to the in-memory list (NO disk write yet) and open the ink editor.
    /// The DOCX write happens in finishInk; cancelInk discards an unsaved brand-new annotation.
    /// Deferring the write avoids racing the editor's atomic PNG+strokes+annotations save.
    func beginInkAnnotation(_ annotation: Annotation) {
        let span = document.map {
            Anchoring.locateInPlain($0.plainText, selectedText: annotation.selectedText,
                                    prefix: annotation.prefix, suffix: annotation.suffix,
                                    positionHint: annotation.position)
        } ?? nil
        annotations.append(ResolvedAnnotation(annotation: annotation, span: span))
        inkEditingAnnotation = annotation
    }

    /// Re-open an existing ink annotation for editing.
    func editInkAnnotation(_ annotation: Annotation) {
        inkEditingAnnotation = annotation
    }

    /// Route a tapped/selected annotation to the right editor: ink canvas vs note sheet.
    /// macOS has no ink editor, so ink rows fall back to the note sheet there.
    func openAnnotation(_ annotation: Annotation) {
        #if os(macOS)
        editingAnnotation = annotation
        #else
        if annotation.hasInk { inkEditingAnnotation = annotation }
        else { editingAnnotation = annotation }
        #endif
    }

    /// Cancel the ink editor. A brand-new annotation with no committed ink is discarded;
    /// an existing ink annotation is left untouched.
    func cancelInk(_ annotation: Annotation) {
        if !annotation.hasInk {
            annotations.removeAll { $0.annotation.id == annotation.id }
        }
    }

    /// Persist a finished ink drawing: PNG + re-editable strokes blob + the annotation list
    /// (with hasInk=true), in one atomic read-from-disk → write.
    func finishInk(annotationId: String, png: Data, strokesJSON: String) async {
        guard let url = currentURL else { return }
        if let idx = annotations.firstIndex(where: { $0.annotation.id == annotationId }),
           !annotations[idx].annotation.hasInk {
            let updated = annotations[idx].annotation.copy(hasInk: true)
            annotations[idx] = ResolvedAnnotation(annotation: updated, span: annotations[idx].span)
        }
        let annotationsToWrite = annotations.map(\.annotation)
        await enqueueWrite(url: url) { base in
            var b = try DocxStore.saveInkPng(base, annotationId: annotationId, pngData: png)
            b = try DocxStore.saveInkStrokes(b, annotationId: annotationId, json: strokesJSON)
            return try DocxStore.write(b, annotations: annotationsToWrite)
        }
    }

    /// Reads the re-editable stroke blob for an ink annotation (nil if none).
    func loadInkStrokesJSON(_ annotationId: String) async -> String? {
        guard let url = currentURL else { return nil }
        return try? await Task.detached(priority: .userInitiated) {
            let base = try Data(contentsOf: url)
            return try DocxStore.readInkStrokes(base, annotationId: annotationId)
        }.value
    }

    /// Reads the flattened ink PNG for an annotation (used as a view-only backdrop when the
    /// re-editable strokes blob is absent or from another platform).
    func loadInkPng(_ annotationId: String) async -> Data? {
        guard let url = currentURL else { return nil }
        return try? await Task.detached(priority: .userInitiated) {
            let base = try Data(contentsOf: url)
            return try DocxStore.readInkPng(base, annotationId: annotationId)
        }.value
    }

    // Unique tmp name per write so even an accidental overlap can't collide on a shared path.
    private nonisolated static func atomicReplace(_ data: Data, at url: URL) throws {
        let tmpURL = url.deletingLastPathComponent()
            .appendingPathComponent(".\(url.lastPathComponent).\(UUID().uuidString).tmp")
        try data.write(to: tmpURL)
        _ = try FileManager.default.replaceItemAt(url, withItemAt: tmpURL)
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

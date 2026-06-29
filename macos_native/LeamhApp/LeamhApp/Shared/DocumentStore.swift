import Foundation
import UniformTypeIdentifiers
#if os(macOS)
import AppKit
#elseif os(iOS)
import UIKit
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
    private let fontKey      = "com.afluffywaffle.layuv.bodyFont"
    private let fontSizeKey   = "com.afluffywaffle.layuv.bodyFontSize"

    /// Reader body text size (small/medium/large). Reader-only, like Android's `body_font_size`.
    /// The @Published re-renders the reader; the readers read `bodyTextSize.points` directly.
    @Published var bodyTextSize: BodyTextSize {
        didSet {
            guard oldValue != bodyTextSize else { return }
            UserDefaults.standard.set(bodyTextSize.rawValue, forKey: fontSizeKey)
        }
    }

    private let paperThemeKey = "com.afluffywaffle.layuv.paperTheme"

    /// Reader paper theme (écri-style: parchment/bone/dusk/sage/night). Reader-only. The @Published
    /// re-renders the reader; the didSet pushes into `AppTheme.currentTheme` (the static the reader's
    /// attributed-string builders read) and persists.
    @Published var paperTheme: PaperTheme {
        didSet {
            guard oldValue != paperTheme else { return }
            AppTheme.currentTheme = paperTheme
            UserDefaults.standard.set(paperTheme.rawValue, forKey: paperThemeKey)
        }
    }

    private let followsDarkModeKey = "com.afluffywaffle.layuv.followsDarkMode"
    private let leftHandedNavKey    = "com.afluffywaffle.layuv.leftHandedNav"

    /// When ON, the reader switches to the Night paper theme while the OS is in dark mode (the user's
    /// chosen `paperTheme` stays the light pick, écri-style). When OFF, the app is pinned light and the
    /// reader always uses `paperTheme` regardless of the system appearance.
    @Published var followsDarkMode: Bool {
        didSet {
            guard oldValue != followsDarkMode else { return }
            UserDefaults.standard.set(followsDarkMode, forKey: followsDarkModeKey)
        }
    }

    /// Left-handed navigation: WASD + Q/E page keys (so the off-hand can turn pages on a trackpad).
    @Published var leftHandedNav: Bool {
        didSet {
            guard oldValue != leftHandedNav else { return }
            UserDefaults.standard.set(leftHandedNav, forKey: leftHandedNavKey)
        }
    }

    /// The reader theme to actually render: the user's pick, or Night while following a dark OS.
    func effectiveTheme(systemDark: Bool) -> PaperTheme {
        (followsDarkMode && systemDark) ? .night : paperTheme
    }

    private let twoColumnKey = "com.afluffywaffle.layuv.twoColumnPaged"

    /// iPad-only preference: use two columns in the paged reader modes (default on). iPhone is
    /// always single-column. Honoured by the reader together with an idiom + min-width guard.
    @Published var twoColumnPaged: Bool {
        didSet {
            guard oldValue != twoColumnPaged else { return }
            UserDefaults.standard.set(twoColumnPaged, forKey: twoColumnKey)
        }
    }

    private let aiExportFolderKey = "com.afluffywaffle.layuv.aiExportFolder"
    private let aiImportFolderKey = "com.afluffywaffle.layuv.aiImportFolder"

    /// Optional persisted destination for "Export for AI" (write directly instead of share sheet)
    /// and source folder for "Import rewrite" auto-find. Held as security-scoped bookmarks.
    @Published private(set) var aiExportFolder: URL?
    @Published private(set) var aiImportFolder: URL?

    /// The app-wide font. Mirrors Android's single `body_font` pref: changing it flips the
    /// reader AND all chrome. The @Published is the SwiftUI invalidation trigger; the didSet
    /// also pushes the value into the `AppTheme.current` static the font helpers read.
    @Published var fontChoice: FontChoice {
        didSet {
            guard oldValue != fontChoice else { return }
            applyFontChoice(fontChoice)
            UserDefaults.standard.set(fontChoice.rawValue, forKey: fontKey)
        }
    }

    /// Pushes the reader font choice into the process-wide static the reader helpers read.
    /// Chrome (incl. nav-bar titles) stays on San Francisco, so nothing else needs updating.
    /// Called from both `init` (seed) and `fontChoice.didSet`.
    private func applyFontChoice(_ choice: FontChoice) {
        AppTheme.current = choice
    }

    // Security-scoped bookmarks: macOS app-sandbox needs the .withSecurityScope option;
    // iOS uses plain bookmarks for document-picker URLs (still wrapped in start/stopAccessing…).
    #if os(macOS)
    private static let bookmarkCreationOptions: URL.BookmarkCreationOptions = .withSecurityScope
    private static let bookmarkResolutionOptions: URL.BookmarkResolutionOptions = .withSecurityScope
    #else
    private static let bookmarkCreationOptions: URL.BookmarkCreationOptions = []
    private static let bookmarkResolutionOptions: URL.BookmarkResolutionOptions = []
    #endif

    init() {
        // Seed the font BEFORE the first UI build. The initial property assignment does NOT
        // fire didSet, so apply the static + appearance explicitly here.
        let choice = UserDefaults.standard.string(forKey: fontKey)
            .flatMap(FontChoice.init(rawValue:)) ?? .serif
        fontChoice = choice
        bodyTextSize = UserDefaults.standard.string(forKey: fontSizeKey)
            .flatMap(BodyTextSize.init(rawValue:)) ?? .medium
        let theme = UserDefaults.standard.string(forKey: paperThemeKey)
            .flatMap(PaperTheme.init(rawValue:)) ?? .parchment
        paperTheme = theme
        AppTheme.currentTheme = theme
        followsDarkMode = UserDefaults.standard.bool(forKey: followsDarkModeKey)
        leftHandedNav   = UserDefaults.standard.bool(forKey: leftHandedNavKey)
        twoColumnPaged = (UserDefaults.standard.object(forKey: twoColumnKey) as? Bool) ?? true
        aiExportFolder = nil
        aiImportFolder = nil
        applyFontChoice(choice)
        loadRecents()
        aiExportFolder = loadFolderBookmark(key: aiExportFolderKey)
        aiImportFolder = loadFolderBookmark(key: aiImportFolderKey)
    }

    // MARK: - File access

    #if os(macOS)
    func openFilePanel() {
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [
            UTType(filenameExtension: "docx"),
            .plainText, .text, UTType(filenameExtension: "md"), UTType(filenameExtension: "markdown"),
        ].compactMap { $0 }
        panel.allowsMultipleSelection = false
        panel.canChooseDirectories = false
        guard panel.runModal() == .OK, let url = panel.url else { return }
        Task { await openAny(url: url) }
    }
    #endif

    /// Routes an opened file by type: DOCX opens directly; anything else (.txt/.md/écri) is converted
    /// to a working .docx first. The original file is never modified (CLAUDE.md invariant).
    func openAny(url: URL) async {
        if url.pathExtension.lowercased() == "docx" { await load(url: url) }
        else { await importTextFile(url: url) }
    }

    // MARK: - Text import (non-DOCX → flatten to .docx)

    /// Imports a plain-text file into a NEW working `.docx` and opens it. Recognises the écri
    /// front-matter header (theme/font/page) and seeds the reader prefs from it; non-écri text
    /// imports verbatim. The source text file is left untouched.
    func importTextFile(url: URL) async {
        isLoading = true
        let scoped = url.startAccessingSecurityScopedResource()
        let raw = (try? String(contentsOf: url, encoding: .utf8))
        if scoped { url.stopAccessingSecurityScopedResource() }
        guard let raw else { isLoading = false; return }

        let parsed = TextImport.parse(raw)
        // Honour écri per-document prefs (rawValues match 1:1; font serif/sans → FontChoice).
        if let t = parsed.ecriThemeRaw, let theme = PaperTheme(rawValue: t) { paperTheme = theme }
        if let serif = parsed.ecriFontSerif { fontChoice = serif ? .serif : .sans }

        do {
            let data = try TextImport.docx(from: parsed.text)
            guard let dest = writeConvertedDocx(data, sourceURL: url) else { isLoading = false; return }
            await load(url: dest)   // load() manages isLoading + recents + security scope
        } catch {
            print("[DocumentStore] text import failed: \(error)")
            isLoading = false
        }
    }

    /// Writes the converted DOCX next to the source if that's writable, else into app Documents.
    private func writeConvertedDocx(_ data: Data, sourceURL: URL) -> URL? {
        let name = sourceURL.deletingPathExtension().lastPathComponent + ".docx"
        let sibling = sourceURL.deletingLastPathComponent().appendingPathComponent(name)
        if (try? data.write(to: sibling)) != nil { return sibling }
        if let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first {
            let u = docs.appendingPathComponent(name)
            if (try? data.write(to: u)) != nil { return u }
        }
        return nil
    }

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
    /// Both iOS and macOS have ink editors now (macOS uses a mouse/trackpad/tablet canvas).
    func openAnnotation(_ annotation: Annotation) {
        if annotation.hasInk { inkEditingAnnotation = annotation }
        else { editingAnnotation = annotation }
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

    // MARK: - AI export / import folders (security-scoped bookmarks)

    func setAiExportFolder(_ url: URL) { aiExportFolder = persistFolderBookmark(url, key: aiExportFolderKey) }
    func setAiImportFolder(_ url: URL) { aiImportFolder = persistFolderBookmark(url, key: aiImportFolderKey) }

    private func persistFolderBookmark(_ url: URL, key: String) -> URL? {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard let data = try? url.bookmarkData(options: Self.bookmarkCreationOptions,
                                               includingResourceValuesForKeys: nil,
                                               relativeTo: nil) else { return nil }
        UserDefaults.standard.set(data, forKey: key)
        return url
    }

    private func loadFolderBookmark(key: String) -> URL? {
        guard let data = UserDefaults.standard.data(forKey: key) else { return nil }
        var stale = false
        guard let url = try? URL(resolvingBookmarkData: data,
                                 options: Self.bookmarkResolutionOptions,
                                 relativeTo: nil,
                                 bookmarkDataIsStale: &stale) else { return nil }
        if stale, let refreshed = persistFolderBookmark(url, key: key) { return refreshed }
        return url
    }

    /// Writes the AI export package (Markdown + ink PNGs) directly into `folder`. Returns true on success.
    func exportForAi(toFolder folder: URL) async -> Bool {
        let (text, images) = await buildAiExportItems()
        let docName = currentURL?.deletingPathExtension().lastPathComponent ?? "Document"
        let scoped  = folder.startAccessingSecurityScopedResource()
        defer { if scoped { folder.stopAccessingSecurityScopedResource() } }
        do {
            try (text.data(using: .utf8) ?? Data())
                .write(to: folder.appendingPathComponent("\(docName)_for_ai.md"))
            for (name, data) in images {
                try data.write(to: folder.appendingPathComponent(name))
            }
            return true
        } catch {
            print("[DocumentStore] export-to-folder failed: \(error)")
            return false
        }
    }

    /// Looks for an AI-rewritten draft in the import folder matching the open doc's name
    /// (the "<docName> Draft.docx" produced by saveAiDraft). Returns its URL if present.
    func autoFindRewrite() -> URL? {
        guard let folder = aiImportFolder,
              let docName = currentURL?.deletingPathExtension().lastPathComponent else { return nil }
        let scoped = folder.startAccessingSecurityScopedResource()
        defer { if scoped { folder.stopAccessingSecurityScopedResource() } }
        let candidate = folder.appendingPathComponent("\(docName) Draft.docx")
        return FileManager.default.fileExists(atPath: candidate.path) ? candidate : nil
    }

    /// Replaces the open document's bytes with the chosen rewritten DOCX, then reloads.
    /// Mirrors Android's importRewrite (writes the rewrite over the working file).
    func importRewrite(from url: URL) async throws {
        guard let current = currentURL else { throw DocumentStoreError("No document is open.") }
        let scoped = url.startAccessingSecurityScopedResource()
        let bytes: Data
        do { bytes = try Data(contentsOf: url) }
        catch { if scoped { url.stopAccessingSecurityScopedResource() }; throw error }
        if scoped { url.stopAccessingSecurityScopedResource() }
        await enqueueWrite(url: current) { _ in bytes }
        await load(url: current)
    }

    // MARK: - AI chat

    func loadAiChat() async -> [AiTurn] {
        guard let url = currentURL else { return [] }
        return await Task.detached(priority: .userInitiated) {
            guard let data = try? Data(contentsOf: url) else { return [] }
            return DocxStore.readAiChat(data)
        }.value
    }

    func saveAiChat(_ turns: [AiTurn]) async {
        guard let url = currentURL else { return }
        await enqueueWrite(url: url) { base in
            try DocxStore.writeAiChat(base, turns: turns)
        }
    }

    /// Builds a clean draft DOCX from the given rewrite prose and writes it to a temp file.
    /// The caller is responsible for presenting a share/save sheet with the returned URL.
    func saveAiDraft(rewrite: String) async throws -> URL {
        guard let url = currentURL else {
            throw DocumentStoreError("No document is open.")
        }
        let draftData = try await Task.detached(priority: .userInitiated) {
            let base = try Data(contentsOf: url)
            return try DocxFromText.build(sourceDocx: base, text: rewrite)
        }.value
        let docName  = url.deletingPathExtension().lastPathComponent
        let draftURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(docName) Draft.docx")
        try draftData.write(to: draftURL)
        return draftURL
    }

    /// Builds the export body (chapter + annotations text) and loads ink PNG files.
    /// Returns raw data so callers (iOS-only) can write temp files and present a share sheet.
    func buildAiExportItems() async -> (text: String, images: [(name: String, data: Data)]) {
        guard let doc = document else { return ("", []) }
        let annotations = self.annotations.map(\.annotation)
        let body = ManuscriptSerializer.buildExportBody(plainText: doc.plainText,
                                                        annotations: annotations)
        var images: [(String, Data)] = []
        for (i, id) in body.inkAnnotationIds.enumerated() {
            if let png = await loadInkPng(id) {
                images.append(("ink_\(i + 1).png", png))
            }
        }
        let docName  = currentURL?.deletingPathExtension().lastPathComponent ?? "Document"
        let header   = "=== EXPORT FOR AI — Layuv ===\n\(docName)\n\n"
        let footer   = images.isEmpty ? "" :
            "\n=== IMAGE FILES ===\n" + images.map(\.0).joined(separator: "\n") + "\n"
        return (header + body.text + footer, images)
    }
}

private struct DocumentStoreError: LocalizedError {
    let errorDescription: String?
    init(_ message: String) { errorDescription = message }
}

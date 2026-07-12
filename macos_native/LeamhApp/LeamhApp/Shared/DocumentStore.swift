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

    /// Set to present the consolidated app Settings sheet (macOS ⌘, or the More menu). Per-window,
    /// so the sheet reads/writes THIS window's document for the per-document section.
    @Published var showSettings = false

    /// Currently locked annotation tool. When non-nil, new text selections auto-annotate
    /// with this tool (no tool popover), and the reader toolbar shows a tap-to-unlock chip.
    /// In-memory only (mirrors Android's `lockedTool` — not persisted across launches).
    @Published var lockedTool: AnnotationTool?

    @Published private(set) var currentURL: URL?

    /// Reading-position marker as a 0.0–1.0 plain-text fraction, or nil when none is set.
    /// Persisted INSIDE the .docx (`leamh/position.json`) so it travels with the file across
    /// devices (macOS ⇄ iPad ⇄ Android). Set on a single click/tap in the reader.
    @Published private(set) var readingMarkerFraction: Double?

    /// Title for the document window: the DOCX `dc:title` when meaningful, else the file name.
    /// Generic placeholders Word/Pages leave behind ("Untitled", "Document") fall back to the name.
    var windowTitle: String {
        let fileName = currentURL?.deletingPathExtension().lastPathComponent
        if let t = document?.title?.trimmingCharacters(in: .whitespacesAndNewlines), !t.isEmpty {
            let generic: Set<String> = ["untitled", "untitled document", "document", "untitled.docx"]
            if !generic.contains(t.lowercased()) { return t }
        }
        return fileName ?? "Layuv"
    }

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

    private let lineSpacingKey = "com.afluffywaffle.layuv.lineSpacing"

    /// Reader line spacing (normal/comfortable/spacious). Reader-only, like Android's `line_spacing`.
    /// The @Published re-renders the reader; the readers read `lineSpacing.multiple` directly.
    @Published var lineSpacing: LineSpacing {
        didSet {
            guard oldValue != lineSpacing else { return }
            UserDefaults.standard.set(lineSpacing.rawValue, forKey: lineSpacingKey)
        }
    }

    private let paperThemeKey  = "com.afluffywaffle.layuv.paperTheme"      // global default / last-used
    private let docThemesKey   = "com.afluffywaffle.layuv.docThemes"        // [docPath: themeRawValue]

    /// When true, an in-flight `load()` is applying a document's stored theme — didSet then updates
    /// only the render static, skipping persistence so opening a doc never rewrites the global default.
    private var suppressThemePersist = false

    /// Reader paper theme (écri-style: parchment/bone/dusk/sage/night), modelled on écri's per-document
    /// theming. Reader-only. The @Published re-renders the reader; the didSet pushes into
    /// `AppTheme.currentTheme` (the static the reader's attributed-string builders read) and persists
    /// BOTH the global default (last-used, seeds un-themed docs) and a per-document override keyed by
    /// path. Engine-free: nothing is written into the DOCX. E-ink ignores colour entirely.
    @Published var paperTheme: PaperTheme {
        didSet {
            guard oldValue != paperTheme else { return }
            AppTheme.currentTheme = paperTheme
            guard !suppressThemePersist else { return }
            UserDefaults.standard.set(paperTheme.rawValue, forKey: paperThemeKey)
            if let url = currentURL { setDocTheme(paperTheme, for: url) }
        }
    }

    /// The per-document theme map (path → rawValue). App-local, plist-backed.
    private func docThemeMap() -> [String: String] {
        UserDefaults.standard.dictionary(forKey: docThemesKey) as? [String: String] ?? [:]
    }
    private func docTheme(for url: URL) -> PaperTheme? {
        docThemeMap()[url.path].flatMap(PaperTheme.init(rawValue:))
    }
    private func setDocTheme(_ theme: PaperTheme, for url: URL) {
        var map = docThemeMap()
        map[url.path] = theme.rawValue
        UserDefaults.standard.set(map, forKey: docThemesKey)
    }
    /// Applies the document's stored theme (or the global default if none) WITHOUT rewriting prefs.
    private func applyDocTheme(for url: URL) {
        let resolved = docTheme(for: url)
            ?? UserDefaults.standard.string(forKey: paperThemeKey).flatMap(PaperTheme.init(rawValue:))
            ?? .parchment
        suppressThemePersist = true
        paperTheme = resolved
        suppressThemePersist = false
    }

    private let docAuthorsKey = "com.afluffywaffle.layuv.docAuthors"   // [docPath: authorName]

    /// When set, an in-flight `load()` is applying a document's stored author override — didSet then
    /// skips persistence so opening a doc never rewrites the map with the same value.
    private var suppressDocAuthorPersist = false

    /// Per-document author override (empty = "use the global default"). Bound by the UI. On change,
    /// persists into the per-doc map keyed by the current document's path. App-local, plist-backed;
    /// nothing is written into the DOCX itself here — it only feeds `effectiveAuthor` at save time.
    @Published var currentDocAuthor: String = "" {
        didSet {
            guard oldValue != currentDocAuthor, !suppressDocAuthorPersist else { return }
            if let url = currentURL { setDocAuthor(currentDocAuthor, for: url) }
        }
    }

    private func docAuthorMap() -> [String: String] {
        UserDefaults.standard.dictionary(forKey: docAuthorsKey) as? [String: String] ?? [:]
    }
    /// The stored override for a document, trimmed; nil when unset/blank.
    private func docAuthor(for url: URL) -> String? {
        let t = (docAuthorMap()[url.path] ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? nil : t
    }
    private func setDocAuthor(_ name: String, for url: URL) {
        var map = docAuthorMap()
        let t = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.isEmpty { map.removeValue(forKey: url.path) } else { map[url.path] = t }
        UserDefaults.standard.set(map, forKey: docAuthorsKey)
    }
    /// Seeds `currentDocAuthor` from the stored override WITHOUT rewriting the map.
    private func applyDocAuthor(for url: URL) {
        suppressDocAuthorPersist = true
        currentDocAuthor = docAuthor(for: url) ?? ""
        suppressDocAuthorPersist = false
    }

    /// The author name to write into comments for the current document: a per-document override
    /// if set, else the global default (`AppSettings`), else `"Layuv"`.
    var effectiveAuthor: String {
        if let url = currentURL, let o = docAuthor(for: url) { return o }
        return AppSettings.shared.effectiveGlobalAuthor
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

    private let markerOnDoubleClickKey = "com.afluffywaffle.layuv.markerOnDoubleClick"

    /// Reading-marker gesture mapping. OFF (default): single-click drops the reading marker,
    /// double-click an annotation opens its edit sheet. ON: single-click an annotation opens its
    /// edit sheet (legacy feel), double-click drops the reading marker.
    @Published var markerOnDoubleClick: Bool {
        didSet {
            guard oldValue != markerOnDoubleClick else { return }
            UserDefaults.standard.set(markerOnDoubleClick, forKey: markerOnDoubleClickKey)
        }
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
        lineSpacing = UserDefaults.standard.string(forKey: lineSpacingKey)
            .flatMap(LineSpacing.init(rawValue:)) ?? .comfortable
        let theme = UserDefaults.standard.string(forKey: paperThemeKey)
            .flatMap(PaperTheme.init(rawValue:)) ?? .parchment
        paperTheme = theme
        AppTheme.currentTheme = theme
        followsDarkMode = UserDefaults.standard.bool(forKey: followsDarkModeKey)
        leftHandedNav   = UserDefaults.standard.bool(forKey: leftHandedNavKey)
        twoColumnPaged = (UserDefaults.standard.object(forKey: twoColumnKey) as? Bool) ?? true
        markerOnDoubleClick = UserDefaults.standard.bool(forKey: markerOnDoubleClickKey)
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
        guard let url = DocumentStore.runOpenPanel() else { return }
        Task { await openAny(url: url) }
    }

    /// Presents the standard open panel and returns the chosen file WITHOUT loading it, so the
    /// caller can open it in a NEW window (multi-window model) rather than mutating this store.
    static func runOpenPanel() -> URL? {
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [
            UTType(filenameExtension: "docx"),
            .plainText, .text, UTType(filenameExtension: "md"), UTType(filenameExtension: "markdown"),
        ].compactMap { $0 }
        panel.allowsMultipleSelection = false
        panel.canChooseDirectories = false
        guard panel.runModal() == .OK else { return nil }
        return panel.url
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

        do {
            let data = try TextImport.docx(from: parsed.text)
            guard let dest = writeConvertedDocx(data, sourceURL: url) else { isLoading = false; return }
            await load(url: dest)   // load() manages isLoading + recents + security scope + per-doc theme
            // Honour écri per-document prefs AFTER load so the theme persists against the NEW doc's
            // path (rawValues match écri 1:1; font serif/sans → FontChoice). Font is app-global.
            if let t = parsed.ecriThemeRaw, let theme = PaperTheme(rawValue: t) { paperTheme = theme }
            if let serif = parsed.ecriFontSerif { fontChoice = serif ? .serif : .sans }
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
            self.readingMarkerFraction = doc.position.map { $0.fraction }
            applyDocTheme(for: url)   // per-doc theme (or global default), without rewriting prefs
            applyDocAuthor(for: url)  // per-doc author override (blank = use global default)
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
        let author = effectiveAuthor
        await enqueueWrite(url: url) { base in
            try DocxStore.write(base, annotations: annotationsToWrite, author: author)
        }
    }

    /// Persists the reading-position marker into `leamh/position.json` (touches only that part,
    /// so it coexists with annotation writes). Preserves any existing mode/page/scrollOffset the
    /// file already carries (e.g. written by Android); only the plain-text `fraction` changes.
    func setReadingMarker(fraction: Double) async {
        guard let url = currentURL else { return }
        let clamped = min(1.0, max(0.0, fraction))
        readingMarkerFraction = clamped
        let existing = document?.position
        let position = ReadingPosition(
            mode: existing?.mode ?? .scroll,
            page: existing?.page ?? 0,
            scrollOffset: existing?.scrollOffset ?? 0.0,
            fraction: clamped)
        await enqueueWrite(url: url) { base in
            try DocxStore.writePosition(base, position: position)
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
        let author = effectiveAuthor
        await enqueueWrite(url: url) { base in
            var b = try DocxStore.saveInkPng(base, annotationId: annotationId, pngData: png)
            b = try DocxStore.saveInkStrokes(b, annotationId: annotationId, json: strokesJSON)
            return try DocxStore.write(b, annotations: annotationsToWrite, author: author)
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

    // Sandbox-safe atomic replace. A file-scoped security bookmark grants read/write on the
    // file itself but NOT permission to create sibling files in its parent directory — so a
    // `.name.tmp` next to the target fails with "Operation not permitted" (esp. in iCloud Drive).
    // Instead we stage the temp in the system item-replacement directory (same volume, always
    // writable by the sandbox), then let `replaceItemAt` perform the atomic swap into place.
    private nonisolated static func atomicReplace(_ data: Data, at url: URL) throws {
        let fm = FileManager.default
        let tmpDir = try fm.url(for: .itemReplacementDirectory, in: .userDomainMask,
                                appropriateFor: url, create: true)
        defer { try? fm.removeItem(at: tmpDir) }
        let tmpURL = tmpDir.appendingPathComponent(url.lastPathComponent)
        try data.write(to: tmpURL)
        _ = try fm.replaceItemAt(url, withItemAt: tmpURL)
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

    /// Writes the AI export package into `folder`, mirroring Android's full workflow so the
    /// cross-platform expectation is identical (see ReaderActivity.exportForAi):
    ///  • A per-chapter container `<base>/`; each pass in its own `<base>_v<N>_export/` (chapter.md
    ///    + ink PNGs). Repeated exports never overwrite.
    ///  • Version is deterministic from the working filename (`_draft_v<N>` → N+1), exactly like
    ///    Android — possible here because the bumped draft copy is reopened (below).
    ///  • A `<base>_draft_v<N>.docx` copy of the source is written; the newest 3 are kept and older
    ///    ones moved to `<base> archive/`.
    ///  • The app then reopens that draft, so the next export bumps to N+1.
    /// Android writes the draft next to the source file; sandboxed macOS can't create siblings of the
    /// user's original (file-scoped bookmark only), so the whole container lives in the user-chosen
    /// export `folder`, which IS folder-scoped. The workflow is unchanged — only the on-disk root
    /// differs, and it's a folder the user picked. Returns true on success.
    func exportForAi(toFolder folder: URL) async -> Bool {
        guard let src = currentURL else { return false }
        let rawName     = src.deletingPathExtension().lastPathComponent
        let fileVersion = Self.draftVersion(of: rawName)        // 0 if not a _draft_vN file
        let cleanBase   = Self.stripDraftSuffix(rawName)
        let version     = fileVersion + 1
        let (body, images) = await buildAiExportItems()
        let text = Self.exportHeader(cleanBase: cleanBase, version: version) + body
        let srcBytes = await Task.detached(priority: .userInitiated) {
            (try? Data(contentsOf: src)) ?? Data()
        }.value

        let scoped = folder.startAccessingSecurityScopedResource()
        // NOTE: scope is held across the reopen below (load() reads a child of `folder`); released after.
        var draftURL: URL?
        do {
            let fm = FileManager.default
            let chapterDir = folder.appendingPathComponent(cleanBase, isDirectory: true)
            try fm.createDirectory(at: chapterDir, withIntermediateDirectories: true)

            let outputDir = chapterDir.appendingPathComponent("\(cleanBase)_v\(version)_export",
                                                              isDirectory: true)
            try fm.createDirectory(at: outputDir, withIntermediateDirectories: true)
            try (text.data(using: .utf8) ?? Data())
                .write(to: outputDir.appendingPathComponent("chapter.md"))
            for (name, data) in images {
                try data.write(to: outputDir.appendingPathComponent(name))
            }

            // Bump the working copy: write <base>_draft_v<version>.docx, keep newest 3, archive rest.
            if !srcBytes.isEmpty {
                let next = chapterDir.appendingPathComponent("\(cleanBase)_draft_v\(version).docx")
                if !fm.fileExists(atPath: next.path) {
                    let tmp = next.appendingPathExtension("tmp")
                    try srcBytes.write(to: tmp)
                    _ = try? fm.replaceItemAt(next, withItemAt: tmp)
                    if fm.fileExists(atPath: tmp.path) { try? fm.removeItem(at: tmp) }
                }
                Self.archiveOldDrafts(in: chapterDir, cleanBase: cleanBase, keep: 3)
                draftURL = next
            }
        } catch {
            print("[DocumentStore] export-to-folder failed: \(error)")
            if scoped { folder.stopAccessingSecurityScopedResource() }
            return false
        }

        // Reopen the bumped draft so the next export advances the version (Android parity).
        // The draft lives inside the still-scoped export folder; load() reads it before we release.
        if let draftURL { await load(url: draftURL) }
        if scoped { folder.stopAccessingSecurityScopedResource() }
        return true
    }

    /// Strips a trailing `_draft_v<N>` so repeated passes for the same chapter share one container.
    private static func stripDraftSuffix(_ name: String) -> String {
        let cleaned = name.replacing(/_draft_v\d+$/.ignoresCase(), with: "")
        return cleaned.isEmpty ? "chapter" : cleaned
    }

    /// The version embedded in a `_draft_v<N>` filename, or 0 if the name carries none.
    private static func draftVersion(of name: String) -> Int {
        guard let m = name.firstMatch(of: /_draft_v(\d+)$/.ignoresCase()) else { return 0 }
        return Int(m.1) ?? 0
    }

    /// Header that tells the AI which version this is and how to name its rewrite, so Import rewrite
    /// can auto-find it. Mirrors AiExporter.buildHeader.
    private static func exportHeader(cleanBase: String, version: Int) -> String {
        "Export v\(version) of \"\(cleanBase)\". " +
        "When returning the rewrite as a .docx file, name it \(cleanBase)_draft_v\(version).docx.\n\n"
    }

    /// Keeps the newest `keep` `<cleanBase>_draft_v<N>.docx` files; moves older ones to `<base> archive/`.
    private static func archiveOldDrafts(in dir: URL, cleanBase: String, keep: Int) {
        let fm = FileManager.default
        guard let names = try? fm.contentsOfDirectory(atPath: dir.path) else { return }
        let re = try! Regex("^\(NSRegularExpression.escapedPattern(for: cleanBase))_draft_v(\\d+)\\.docx$")
            .ignoresCase()
        let drafts = names.compactMap { name -> (Int, String)? in
            guard let m = try? re.firstMatch(in: name), let r = m[1].substring, let v = Int(r) else { return nil }
            return (v, name)
        }.sorted { $0.0 > $1.0 }
        let toArchive = drafts.dropFirst(keep)
        guard !toArchive.isEmpty else { return }
        let archiveDir = dir.appendingPathComponent("\(cleanBase) archive", isDirectory: true)
        try? fm.createDirectory(at: archiveDir, withIntermediateDirectories: true)
        for (_, name) in toArchive {
            let dest = archiveDir.appendingPathComponent(name)
            if !fm.fileExists(atPath: dest.path) {
                try? fm.moveItem(at: dir.appendingPathComponent(name), to: dest)
            }
        }
    }

    /// Looks for the AI-rewritten draft for the open doc. Probes BOTH naming schemes Layuv produces:
    ///  • Export for AI (Android-parity): `<base>_draft_v<N>.docx`, where N comes from the working
    ///    filename — the name the export header told the AI to use. Searched in the import folder,
    ///    then the export-folder layout (`<base>/…` and the `_v<N>_export/` subfolder).
    ///  • Save as Draft (AI-chat rewrite card): `<docName> Draft.docx`, searched in the import folder.
    /// Returns the first existing candidate, scanning each folder within its own security scope.
    func autoFindRewrite() -> URL? {
        guard let rawName = currentURL?.deletingPathExtension().lastPathComponent else { return nil }

        func firstExisting(in folder: URL, _ relativePaths: [String]) -> URL? {
            let scoped = folder.startAccessingSecurityScopedResource()
            defer { if scoped { folder.stopAccessingSecurityScopedResource() } }
            for rel in relativePaths {
                let candidate = folder.appendingPathComponent(rel)
                if FileManager.default.fileExists(atPath: candidate.path) { return candidate }
            }
            return nil
        }

        // Export-for-AI versioned naming (only when the working file carries a _draft_vN version).
        let v = Self.draftVersion(of: rawName)
        if v > 0 {
            let base = Self.stripDraftSuffix(rawName)
            let rewriteName = "\(base)_draft_v\(v).docx"
            if let f = aiImportFolder, let hit = firstExisting(in: f, [rewriteName]) { return hit }
            if let f = aiExportFolder, let hit = firstExisting(in: f, [
                "\(base)/\(rewriteName)",
                rewriteName,
                "\(base)/\(base)_v\(v)_export/\(rewriteName)",
            ]) { return hit }
        }

        // Save-as-Draft naming (the "<docName> Draft.docx" produced by saveAiDraft).
        if let f = aiImportFolder, let hit = firstExisting(in: f, ["\(rawName) Draft.docx"]) { return hit }
        return nil
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

    /// Lightweight sibling to `exportForAi`: annotations + their anchors only, no chapter text.
    /// For an AI (e.g. Claude Code) that already has the manuscript file open directly and just
    /// needs to know where each annotation applies, without the chapter body being pasted again.
    /// Ink annotations still get their handwritten PNG (numbered "attached image N", same as the
    /// full export) since the note content itself is only readable from the image.
    func buildAnnotationsOnlyExport() async -> (text: String, images: [(name: String, data: Data)]) {
        let annotations = self.annotations.map(\.annotation)
        let fileName = currentURL?.lastPathComponent ?? "Document"
        let cleanBase = currentURL?.deletingPathExtension().lastPathComponent ?? "chapter"
        let body = ManuscriptSerializer.buildAnnotationsOnlyExport(fileName: fileName, annotations: annotations)
        var images: [(String, Data)] = []
        for (i, id) in body.inkAnnotationIds.enumerated() {
            if let png = await loadInkPng(id) {
                // Prefixed with the chapter name — this export writes flat into a shared folder,
                // unlike the full export's per-chapter subdirectory, so names must not collide.
                images.append(("\(cleanBase)_ink_\(i + 1).png", png))
            }
        }
        return (body.text, images)
    }

    /// Writes the annotations-only export as `<cleanBase>_annotations.md` + any ink PNGs
    /// directly in `folder` (no versioned subfolder — this is a reference artifact regenerated
    /// each time, not a numbered draft, so overwriting the previous pass is correct).
    /// Returns true on success.
    func exportAnnotationsOnly(toFolder folder: URL) async -> Bool {
        guard let src = currentURL else { return false }
        let cleanBase = src.deletingPathExtension().lastPathComponent
        let (body, images) = await buildAnnotationsOnlyExport()
        var text = body
        if !images.isEmpty {
            text += "\n=== IMAGE FILES ===\n" + images.map(\.0).joined(separator: "\n") + "\n"
        }
        let scoped = folder.startAccessingSecurityScopedResource()
        defer { if scoped { folder.stopAccessingSecurityScopedResource() } }
        do {
            let dest = folder.appendingPathComponent("\(cleanBase)_annotations.md")
            try (text.data(using: .utf8) ?? Data()).write(to: dest)
            for (name, data) in images {
                try data.write(to: folder.appendingPathComponent(name))
            }
            return true
        } catch {
            print("[DocumentStore] annotations-only export failed: \(error)")
            return false
        }
    }
}

private struct DocumentStoreError: LocalizedError {
    let errorDescription: String?
    init(_ message: String) { errorDescription = message }
}

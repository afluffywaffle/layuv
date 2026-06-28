import SwiftUI
import UniformTypeIdentifiers

/// iOS root, adaptive across iPad (regular width) and iPhone (compact width).
///
/// - Regular (iPad): NavigationSplitView with the 3-tab sidebar panel
///   (Annotations / Bookmarks / Find) and the reader in the detail column.
/// - Compact (iPhone): a reader-first NavigationStack. The panel is reached via a
///   toolbar button that presents it as a sheet (Find lives on the reader toolbar,
///   so the sheet never covers the system find bar).
///
/// File open via `.fileImporter`; the annotation edit sheet, ink editor, Ask-AI panel
/// and export share-sheet are hosted at this root so they don't collide with the
/// split-view / inspector behaviour in either width.
struct HomeView: View {
    @EnvironmentObject var store: DocumentStore
    @Environment(\.horizontalSizeClass) private var hSize

    @State private var showImporter           = false
    @State private var showAskAi              = false
    @State private var exportItems: [Any]     = []
    @State private var showExport             = false
    @State private var showPanel              = false   // iPhone: panel-as-sheet
    // Lifted from ReaderScreen so the sidebar's Find tab can trigger the reader's find bar,
    // and the Bookmarks tab can scroll the reader to a tapped annotation.
    @State private var findTrigger            = 0
    @State private var scrollToAnnotationId: String? = nil
    // System find is per-text-view, so it can't search across the discrete pages of a paged mode.
    // Tapping Find in a paged mode transiently drops the reader to scroll (full-document system
    // find); picking a nav mode from the menu clears the override and returns to pages.
    @AppStorage("com.afluffywaffle.layuv.navMode") private var navModeRaw = NavMode.scroll.rawValue
    @State private var searchScrollOverride   = false

    // AI menu (mirrors Android's AI submenu)
    @State private var showAiSettings         = false
    @State private var showImportRewrite      = false
    @State private var showExportFolderPicker = false
    @State private var showImportFolderPicker = false

    private var docxType: UTType { UTType(filenameExtension: "docx") ?? .data }

    private func triggerFind() {
        if NavMode(rawValue: navModeRaw) != .scroll { searchScrollOverride = true }
        findTrigger += 1
    }

    /// Export for AI: write directly into the chosen folder if set, else fall back to the share sheet.
    private func exportAi() {
        Task {
            if let folder = store.aiExportFolder {
                _ = await store.exportForAi(toFolder: folder)
            } else {
                let (text, images) = await store.buildAiExportItems()
                var urls: [Any] = []
                if let md = writeTmp(text.data(using: .utf8) ?? Data(), filename: "leamh_export.md") {
                    urls.append(md)
                }
                for (name, data) in images {
                    if let u = writeTmp(data, filename: name) { urls.append(u) }
                }
                exportItems = urls
                showExport  = true
            }
        }
    }

    /// Import rewrite: auto-find "<doc> Draft.docx" in the import folder, else open a picker.
    private func importRewrite() {
        if let found = store.autoFindRewrite() {
            Task { try? await store.importRewrite(from: found) }
        } else {
            showImportRewrite = true
        }
    }

    private func writeTmp(_ data: Data, filename: String) -> URL? {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(filename)
        return (try? data.write(to: url)) != nil ? url : nil
    }

    var body: some View {
        Group {
            if hSize == .compact {
                compactRoot
            } else {
                regularRoot
            }
        }
        .fileImporter(isPresented: $showImporter,
                      allowedContentTypes: [docxType],
                      allowsMultipleSelection: false) { result in
            if case .success(let urls) = result, let url = urls.first {
                Task { await store.load(url: url) }
            }
        }
        // Annotation edit sheet + ink cover hosted at the root so they don't conflict
        // with multitasking / compact-width split-view / inspector behaviour.
        .sheet(item: $store.editingAnnotation) { annotation in
            AnnotationEditSheet(annotation: annotation)
                .environmentObject(store)
                .preferredColorScheme(.light)
        }
        .fullScreenCover(item: $store.inkEditingAnnotation) { annotation in
            InkEditorView(annotation: annotation)
                .environmentObject(store)
                .preferredColorScheme(.light)
        }
        // AI panels
        .sheet(isPresented: $showAskAi) {
            AskAiView()
                .environmentObject(store)
                .preferredColorScheme(.light)
        }
        .sheet(isPresented: $showExport) {
            ShareSheet(items: exportItems)
        }
        .sheet(isPresented: $showAiSettings) {
            AiSettingsView()
                .environmentObject(store)
                .preferredColorScheme(.light)
        }
        // Import rewrite — pick the AI-rewritten DOCX, overwrite the open document.
        .fileImporter(isPresented: $showImportRewrite,
                      allowedContentTypes: [docxType],
                      allowsMultipleSelection: false) { result in
            if case .success(let urls) = result, let url = urls.first {
                Task { try? await store.importRewrite(from: url) }
            }
        }
        // Folder pickers for the AI export / import destinations.
        .fileImporter(isPresented: $showExportFolderPicker,
                      allowedContentTypes: [.folder],
                      allowsMultipleSelection: false) { result in
            if case .success(let urls) = result, let url = urls.first {
                store.setAiExportFolder(url)
            }
        }
        .fileImporter(isPresented: $showImportFolderPicker,
                      allowedContentTypes: [.folder],
                      allowsMultipleSelection: false) { result in
            if case .success(let urls) = result, let url = urls.first {
                store.setAiImportFolder(url)
            }
        }
    }

    // MARK: - Regular width (iPad)

    private var regularRoot: some View {
        NavigationSplitView {
            SidebarPanelView(
                onFind:      { triggerFind() },
                onScrollTo:  { ann in scrollToAnnotationId = ann.id },
                onOpenFile:  { showImporter = true }
            )
        } detail: {
            readerDetail(onShowPanel: nil)
        }
    }

    // MARK: - Compact width (iPhone)

    private var compactRoot: some View {
        NavigationStack {
            readerDetail(onShowPanel: store.document != nil ? { showPanel = true } : nil)
        }
        .sheet(isPresented: $showPanel) {
            NavigationStack {
                SidebarPanelView(
                    onFind:      { triggerFind() },
                    onScrollTo:  { ann in
                        showPanel = false
                        scrollToAnnotationId = ann.id
                    },
                    onOpenFile:  {
                        showPanel = false
                        showImporter = true
                    },
                    showFindTab: false
                )
                .toolbar {
                    ToolbarItem(placement: .topBarLeading) {
                        Button("Done") { showPanel = false }
                    }
                }
            }
            .presentationDetents([.large, .medium])
            .preferredColorScheme(.light)
        }
    }

    // MARK: - Shared reader column

    @ViewBuilder
    private func readerDetail(onShowPanel: (() -> Void)?) -> some View {
        if store.isLoading {
            ProgressView("Loading…")
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(AppTheme.warmPaper)
        } else if let doc = store.document {
            ReaderScreen(document: doc,
                         findTrigger: findTrigger,
                         scrollToAnnotationId: scrollToAnnotationId,
                         onShowPanel: onShowPanel,
                         onFind:   { triggerFind() },
                         onAskAi:  { showAskAi = true },
                         onAiSettings: { showAiSettings = true },
                         onExportAi: { exportAi() },
                         onImportRewrite: { importRewrite() },
                         onSetExportFolder: { showExportFolderPicker = true },
                         onSetImportFolder: { showImportFolderPicker = true },
                         searchScrollOverride: searchScrollOverride,
                         onClearSearch: { searchScrollOverride = false })
        } else {
            emptyState
        }
    }

    private var emptyState: some View {
        VStack(spacing: 24) {
            ContentUnavailableView {
                Label("No Document Open", systemImage: "doc.text")
            } description: {
                Text("Open a DOCX file to begin reading.")
            } actions: {
                Button("Open…") { showImporter = true }
                    .buttonStyle(.borderedProminent)
            }

            if !store.recentURLs.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Recent")
                        .font(AppTheme.chromeBold())
                        .foregroundStyle(.secondary)
                        .padding(.horizontal, 4)
                    ForEach(store.recentURLs.prefix(6), id: \.self) { url in
                        Button {
                            Task { await store.load(url: url) }
                        } label: {
                            HStack(spacing: 8) {
                                Image(systemName: "doc.text")
                                    .foregroundStyle(.secondary)
                                Text(url.deletingPathExtension().lastPathComponent)
                                    .font(AppTheme.body(size: 15))
                                    .lineLimit(1)
                                Spacer()
                            }
                            .contentShape(Rectangle())
                            .padding(.vertical, 6)
                            .padding(.horizontal, 8)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .frame(maxWidth: 360)
                .padding(.horizontal)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(AppTheme.warmPaper)
    }
}

// MARK: - Reader column

private struct ReaderScreen: View {
    @EnvironmentObject var store: DocumentStore
    let document: LoadedDocument
    let findTrigger: Int
    let scrollToAnnotationId: String?
    /// Non-nil only in compact width (iPhone) — shows a toolbar button to present the panel sheet.
    let onShowPanel: (() -> Void)?
    let onFind: () -> Void
    let onAskAi: () -> Void
    let onAiSettings: () -> Void
    let onExportAi: () -> Void
    let onImportRewrite: () -> Void
    let onSetExportFolder: () -> Void
    let onSetImportFolder: () -> Void
    /// While searching in a paged mode the reader is forced to scroll (global system find);
    /// `onClearSearch` returns to the saved nav mode.
    let searchScrollOverride: Bool
    let onClearSearch: () -> Void

    @AppStorage("com.afluffywaffle.layuv.navMode") private var navModeRaw = NavMode.scroll.rawValue
    private var navMode: NavMode { NavMode(rawValue: navModeRaw) ?? .scroll }
    private var effectiveNavMode: NavMode { searchScrollOverride ? .scroll : navMode }

    private var isCompact: Bool { onShowPanel != nil }
    private var docTitle: String {
        store.currentURL?.deletingPathExtension().lastPathComponent ?? "Léamh"
    }

    var body: some View {
        ReaderTextView(document: document,
                       annotations: store.annotations,
                       documentURL: store.currentURL,
                       bodyPointSize: store.bodyTextSize.points,
                       twoColumnPaged: store.twoColumnPaged,
                       navMode: effectiveNavMode,
                       findTrigger: findTrigger,
                       scrollToAnnotationId: scrollToAnnotationId)
            .ignoresSafeArea(.container, edges: .bottom)
            .navigationTitle(docTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                // iPhone: panel (Annotations / Bookmarks) button.
                if let onShowPanel {
                    ToolbarItem(placement: .topBarLeading) {
                        Button { onShowPanel() } label: {
                            Image(systemName: "list.bullet")
                        }
                        .accessibilityLabel("Annotations panel")
                    }
                }

                // Find in text (quick shortcut; on iPad the sidebar Find tab also fires this).
                ToolbarItem(placement: .topBarTrailing) {
                    Button { onFind() } label: {
                        Image(systemName: "magnifyingglass")
                    }
                    .accessibilityLabel("Find")
                }

                if isCompact {
                    // iPhone: fold the secondary actions into one overflow menu to fit the nav bar.
                    ToolbarItem(placement: .topBarTrailing) {
                        Menu {
                            Menu("Font") { fontMenuItems }
                            Menu("Text Size") { sizeMenuItems }
                            Menu("Navigation Mode") {
                                ForEach(NavMode.allCases, id: \.rawValue) { mode in
                                    Button {
                                        navModeRaw = mode.rawValue
                                        onClearSearch()
                                    } label: {
                                        if navModeRaw == mode.rawValue {
                                            Label(mode.label, systemImage: "checkmark")
                                        } else {
                                            Label(mode.label, systemImage: mode.icon)
                                        }
                                    }
                                }
                            }
                            Divider()
                            Menu("AI") { aiMenuItems }
                        } label: {
                            Image(systemName: "ellipsis.circle")
                        }
                        .accessibilityLabel("More")
                    }
                } else {
                    // iPad: font, nav mode, Ask AI, Export as individual toolbar items.
                    ToolbarItem(placement: .topBarTrailing) {
                        Menu {
                            Menu("Font") { fontMenuItems }
                            Menu("Text Size") { sizeMenuItems }
                            Divider()
                            Button {
                                store.twoColumnPaged.toggle()
                            } label: {
                                if store.twoColumnPaged {
                                    Label("Two Columns", systemImage: "checkmark")
                                } else {
                                    Text("Two Columns")
                                }
                            }
                        } label: {
                            Image(systemName: "textformat")
                        }
                        .accessibilityLabel("Typography")
                    }
                    ToolbarItem(placement: .topBarTrailing) {
                        Menu {
                            ForEach(NavMode.allCases, id: \.rawValue) { mode in
                                Button {
                                    navModeRaw = mode.rawValue
                                } label: {
                                    if navModeRaw == mode.rawValue {
                                        Label(mode.label, systemImage: "checkmark")
                                    } else {
                                        Label(mode.label, systemImage: mode.icon)
                                    }
                                }
                            }
                        } label: {
                            Image(systemName: navMode.icon)
                        }
                        .accessibilityLabel("Navigation mode: \(navMode.label)")
                    }
                    ToolbarItem(placement: .topBarTrailing) {
                        Menu {
                            aiMenuItems
                        } label: {
                            Image(systemName: "bubble.left.and.text.bubble.right")
                        }
                        .accessibilityLabel("AI")
                    }
                }
            }
    }

    @ViewBuilder private var fontMenuItems: some View {
        ForEach(FontChoice.allCases, id: \.rawValue) { choice in
            Button {
                store.fontChoice = choice
            } label: {
                if store.fontChoice == choice {
                    Label(choice.label, systemImage: "checkmark")
                } else {
                    Text(choice.label)
                }
            }
        }
    }

    @ViewBuilder private var sizeMenuItems: some View {
        ForEach(BodyTextSize.allCases, id: \.rawValue) { size in
            Button {
                store.bodyTextSize = size
            } label: {
                if store.bodyTextSize == size {
                    Label(size.label, systemImage: "checkmark")
                } else {
                    Text(size.label)
                }
            }
        }
    }

    // MARK: - AI menu (mirrors Android's AI submenu)

    @ViewBuilder private var aiMenuItems: some View {
        Button { onAskAi() } label: {
            Label("AI Chat", systemImage: "bubble.left.and.text.bubble.right")
        }
        .disabled(!AiProviderSettings.shared.isConfigured)
        Button { onAiSettings() } label: {
            Label("AI Settings…", systemImage: "gearshape")
        }
        Divider()
        Button { onExportAi() } label: {
            Label("Export for AI…", systemImage: "square.and.arrow.up")
        }
        Button { onImportRewrite() } label: {
            Label("Import rewrite…", systemImage: "square.and.arrow.down")
        }
        Divider()
        Button { onSetExportFolder() } label: {
            Label(folderLabel("Set AI export folder…", store.aiExportFolder), systemImage: "folder")
        }
        Button { onSetImportFolder() } label: {
            Label(folderLabel("Set import folder…", store.aiImportFolder), systemImage: "folder")
        }
    }

    private func folderLabel(_ base: String, _ url: URL?) -> String {
        guard let url else { return base }
        let parent = url.deletingLastPathComponent().lastPathComponent
        let name   = url.lastPathComponent
        return parent.isEmpty ? "\(base)  (\(name))" : "\(base)  (\(parent)/\(name))"
    }
}

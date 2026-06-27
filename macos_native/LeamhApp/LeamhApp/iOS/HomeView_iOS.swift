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

    private var docxType: UTType { UTType(filenameExtension: "docx") ?? .data }

    var body: some View {
        Group {
            if hSize == .compact {
                compactRoot
            } else {
                regularRoot
            }
        }
        .appFont(store.fontChoice)   // flip all SwiftUI chrome to the chosen family
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
                .appFont(store.fontChoice)
                .preferredColorScheme(.light)
        }
        .fullScreenCover(item: $store.inkEditingAnnotation) { annotation in
            InkEditorView(annotation: annotation)
                .environmentObject(store)
                .appFont(store.fontChoice)
                .preferredColorScheme(.light)
        }
        // AI panels
        .sheet(isPresented: $showAskAi) {
            AskAiView()
                .environmentObject(store)
                .appFont(store.fontChoice)
                .preferredColorScheme(.light)
        }
        .sheet(isPresented: $showExport) {
            ShareSheet(items: exportItems)
        }
    }

    // MARK: - Regular width (iPad)

    private var regularRoot: some View {
        NavigationSplitView {
            SidebarPanelView(
                findTrigger: $findTrigger,
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
                    findTrigger: $findTrigger,
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
                         onFind:   { findTrigger += 1 },
                         onAskAi:  { showAskAi = true },
                         onExport: { items in
                             exportItems = items
                             showExport  = true
                         })
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
    let onExport: ([Any]) -> Void

    @AppStorage("com.afluffywaffle.layuv.navMode") private var navModeRaw = NavMode.scroll.rawValue
    private var navMode: NavMode { NavMode(rawValue: navModeRaw) ?? .scroll }

    private var isCompact: Bool { onShowPanel != nil }
    private var docTitle: String {
        store.currentURL?.deletingPathExtension().lastPathComponent ?? "Léamh"
    }

    var body: some View {
        ReaderTextView(document: document,
                       annotations: store.annotations,
                       documentURL: store.currentURL,
                       bodyPointSize: store.bodyTextSize.points,
                       navMode: navMode,
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
                            Button { onAskAi() } label: {
                                Label("Ask AI", systemImage: "sparkles")
                            }
                            Button { exportForAi() } label: {
                                Label("Export for AI", systemImage: "square.and.arrow.up")
                            }
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
                        Button { onAskAi() } label: {
                            Image(systemName: "sparkles")
                        }
                        .accessibilityLabel("Ask AI")
                    }
                    ToolbarItem(placement: .topBarTrailing) {
                        Button { exportForAi() } label: {
                            Image(systemName: "square.and.arrow.up")
                        }
                        .accessibilityLabel("Export for AI")
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

    private func exportForAi() {
        Task {
            let (text, images) = await store.buildAiExportItems()
            var urls: [Any] = []
            if let mdURL = writeTmp(text.data(using: .utf8) ?? Data(),
                                    filename: "leamh_export.md") { urls.append(mdURL) }
            for (name, data) in images {
                if let imgURL = writeTmp(data, filename: name) { urls.append(imgURL) }
            }
            onExport(urls)
        }
    }

    private func writeTmp(_ data: Data, filename: String) -> URL? {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(filename)
        return (try? data.write(to: url)) != nil ? url : nil
    }
}

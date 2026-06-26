import SwiftUI
import UniformTypeIdentifiers

/// iPad root: NavigationSplitView with the 3-tab sidebar panel (Annotations / Bookmarks / Find)
/// and the reader in the detail column. File open via .fileImporter triggered from the sidebar.
struct HomeView: View {
    @EnvironmentObject var store: DocumentStore
    @State private var showImporter           = false
    @State private var showAskAi              = false
    @State private var exportItems: [Any]     = []
    @State private var showExport             = false
    // Lifted from ReaderScreen so the sidebar's Find tab can trigger the reader's find bar,
    // and the Bookmarks tab can scroll the reader to a tapped annotation.
    @State private var findTrigger            = 0
    @State private var scrollToAnnotationId: String? = nil

    private var docxType: UTType { UTType(filenameExtension: "docx") ?? .data }

    var body: some View {
        NavigationSplitView {
            SidebarPanelView(
                findTrigger: $findTrigger,
                onScrollTo:  { ann in scrollToAnnotationId = ann.id },
                onOpenFile:  { showImporter = true }
            )
        } detail: {
            if store.isLoading {
                ProgressView("Loading…")
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(AppTheme.warmPaper)
            } else if let doc = store.document {
                ReaderScreen(document: doc,
                             findTrigger: findTrigger,
                             scrollToAnnotationId: scrollToAnnotationId,
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
        .fileImporter(isPresented: $showImporter,
                      allowedContentTypes: [docxType],
                      allowsMultipleSelection: false) { result in
            if case .success(let urls) = result, let url = urls.first {
                Task { await store.load(url: url) }
            }
        }
        // Annotation edit sheet + ink cover hosted at the root so they don't conflict
        // with multitasking / compact-width NavigationSplitView behaviour.
        .sheet(item: $store.editingAnnotation) { annotation in
            AnnotationEditSheet(annotation: annotation)
                .environmentObject(store)
        }
        .fullScreenCover(item: $store.inkEditingAnnotation) { annotation in
            InkEditorView(annotation: annotation)
                .environmentObject(store)
        }
        // AI panels
        .sheet(isPresented: $showAskAi) {
            AskAiView()
                .environmentObject(store)
        }
        .sheet(isPresented: $showExport) {
            ShareSheet(items: exportItems)
        }
    }

    private var emptyState: some View {
        ContentUnavailableView {
            Label("No Document Open", systemImage: "doc.text")
        } description: {
            Text("Open a DOCX file to begin reading.")
        } actions: {
            Button("Open…") { showImporter = true }
                .buttonStyle(.borderedProminent)
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
    let onFind: () -> Void
    let onAskAi: () -> Void
    let onExport: ([Any]) -> Void

    @AppStorage("com.afluffywaffle.layuv.navMode") private var navModeRaw = NavMode.scroll.rawValue
    private var navMode: NavMode { NavMode(rawValue: navModeRaw) ?? .scroll }

    var body: some View {
        ReaderTextView(document: document,
                       annotations: store.annotations,
                       documentURL: store.currentURL,
                       navMode: navMode,
                       findTrigger: findTrigger,
                       scrollToAnnotationId: scrollToAnnotationId)
            .ignoresSafeArea(.container, edges: .bottom)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                // Find in text (quick shortcut; sidebar Find tab also fires this)
                ToolbarItem(placement: .topBarTrailing) {
                    Button { onFind() } label: {
                        Image(systemName: "magnifyingglass")
                    }
                    .accessibilityLabel("Find")
                }
                // Navigation mode picker
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
                // Ask AI
                ToolbarItem(placement: .topBarTrailing) {
                    Button { onAskAi() } label: {
                        Image(systemName: "sparkles")
                    }
                    .accessibilityLabel("Ask AI")
                }
                // Export for AI
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
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
                    } label: {
                        Image(systemName: "square.and.arrow.up")
                    }
                    .accessibilityLabel("Export for AI")
                }
            }
    }

    private func writeTmp(_ data: Data, filename: String) -> URL? {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(filename)
        return (try? data.write(to: url)) != nil ? url : nil
    }
}

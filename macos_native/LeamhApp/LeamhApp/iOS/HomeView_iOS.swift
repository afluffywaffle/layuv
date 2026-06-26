import SwiftUI
import UniformTypeIdentifiers

/// iPad root: a NavigationSplitView with a recents sidebar and the reader in the detail pane.
/// Files-app open via .fileImporter (UIDocumentPicker under the hood). Mirrors the macOS HomeView.
struct HomeView: View {
    @EnvironmentObject var store: DocumentStore
    @State private var selectedURL: URL?
    @State private var showImporter = false
    // AI sheets — hosted at the split-view root so they don't conflict with the
    // inspector (which renders as a sheet in compact width / multitasking).
    @State private var showAskAi     = false
    @State private var exportItems: [Any] = []
    @State private var showExport    = false

    private var docxType: UTType { UTType(filenameExtension: "docx") ?? .data }

    var body: some View {
        NavigationSplitView {
            List(store.recentURLs, id: \.self, selection: $selectedURL) { url in
                Label(url.deletingPathExtension().lastPathComponent, systemImage: "doc.text")
                    .font(AppTheme.chrome())
            }
            .navigationTitle("Léamh")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Open…", systemImage: "folder") { showImporter = true }
                }
            }
        } detail: {
            if store.isLoading {
                ProgressView("Loading…")
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(AppTheme.warmPaper)
            } else if let doc = store.document {
                ReaderScreen(document: doc,
                             onAskAi: { showAskAi = true },
                             onExport: { items in
                                 exportItems = items
                                 showExport = true
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
        .onChange(of: selectedURL) { _, url in
            guard let url else { return }
            Task { await store.load(url: url) }
        }
        // Annotation edit sheet + ink cover hosted at the root (see M2 review notes).
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

// MARK: - Reader + annotations layout

private struct ReaderScreen: View {
    @EnvironmentObject var store: DocumentStore
    let document: LoadedDocument
    let onAskAi: () -> Void
    let onExport: ([Any]) -> Void
    @State private var showAnnotations = false

    var body: some View {
        ReaderTextView(document: document, annotations: store.annotations, documentURL: store.currentURL)
            .ignoresSafeArea(.container, edges: .bottom)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        showAnnotations.toggle()
                    } label: {
                        Label("Annotations", systemImage: "list.bullet.rectangle")
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        onAskAi()
                    } label: {
                        Label("Ask AI", systemImage: "sparkles")
                    }
                }
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
                        Label("Export for AI", systemImage: "square.and.arrow.up")
                    }
                }
            }
            .inspector(isPresented: $showAnnotations) {
                AnnotationsPanel()
                    .inspectorColumnWidth(min: 280, ideal: 340, max: 460)
            }
    }

    private func writeTmp(_ data: Data, filename: String) -> URL? {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(filename)
        return (try? data.write(to: url)) != nil ? url : nil
    }
}

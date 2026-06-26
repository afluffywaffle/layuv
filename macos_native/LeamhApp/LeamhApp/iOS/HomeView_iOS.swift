import SwiftUI
import UniformTypeIdentifiers

/// iPad root: a NavigationSplitView with a recents sidebar and the reader in the detail pane.
/// Files-app open via .fileImporter (UIDocumentPicker under the hood). Mirrors the macOS HomeView.
struct HomeView: View {
    @EnvironmentObject var store: DocumentStore
    @State private var selectedURL: URL?
    @State private var showImporter = false

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
                ReaderScreen(document: doc)
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
        // Hosted at the split-view root (not on the reader) so it doesn't collide with the
        // annotations inspector, which renders as a sheet in compact width / iPad multitasking.
        .sheet(item: $store.editingAnnotation) { annotation in
            AnnotationEditSheet(annotation: annotation)
                .environmentObject(store)
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
            }
            .inspector(isPresented: $showAnnotations) {
                AnnotationsPanel(editingAnnotation: $store.editingAnnotation)
                    .inspectorColumnWidth(min: 280, ideal: 340, max: 460)
            }
    }
}

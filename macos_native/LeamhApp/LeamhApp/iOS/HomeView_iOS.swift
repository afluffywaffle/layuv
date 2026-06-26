import SwiftUI
import UniformTypeIdentifiers

/// iPad root: a NavigationSplitView with a recents sidebar and the reader in the detail
/// pane. Files-app open via .fileImporter (UIDocumentPicker under the hood). Mirrors the
/// macOS HomeView behaviourally; selection/annotation chrome arrives in M2.
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
            detail
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
    }

    @ViewBuilder private var detail: some View {
        if store.isLoading {
            ProgressView("Loading…")
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(AppTheme.warmPaper)
        } else if let doc = store.document {
            ReaderTextView(document: doc, annotations: store.annotations)
                .ignoresSafeArea(.container, edges: .bottom)
        } else {
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
}

import SwiftUI

struct HomeView: View {
    @EnvironmentObject var store: DocumentStore
    @State private var selectedURL: URL?

    var body: some View {
        NavigationSplitView {
            List(store.recentURLs, id: \.self, selection: $selectedURL) { url in
                Label(url.deletingPathExtension().lastPathComponent, systemImage: "doc.text")
                    .font(AppTheme.chrome())
            }
            .navigationTitle("Léamh")
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button("Open…", systemImage: "folder") {
                        store.openFilePanel()
                    }
                }
            }
        } detail: {
            if store.isLoading {
                ProgressView("Loading…")
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(AppTheme.warmPaper)
            } else if store.document != nil {
                ReaderScreen()
            } else {
                ContentUnavailableView {
                    Label("No Document Open", systemImage: "doc.text")
                } description: {
                    Text("Open a DOCX file to begin reading.")
                } actions: {
                    Button("Open…") { store.openFilePanel() }
                        .buttonStyle(.borderedProminent)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(AppTheme.warmPaper)
            }
        }
        .onChange(of: selectedURL) { _, url in
            guard let url else { return }
            Task { await store.load(url: url) }
        }
    }
}

// MARK: - Reader + annotations layout

struct ReaderScreen: View {
    @EnvironmentObject var store: DocumentStore
    @State private var showAnnotations   = false
    @State private var editingAnnotation: Annotation?
    @StateObject private var readerCoordinator = ReaderCoordinator()

    var body: some View {
        HSplitView {
            ReaderView(coordinator: readerCoordinator)
                .frame(minWidth: 440)

            if showAnnotations {
                AnnotationsPanel(editingAnnotation: $editingAnnotation)
                    .frame(width: 300)
            }
        }
        .toolbar {
            ToolbarItemGroup(placement: .automatic) {
                // Page navigation
                Button {
                    readerCoordinator.previousPage()
                } label: {
                    Image(systemName: "chevron.left")
                }
                .disabled(readerCoordinator.currentPage == 0)
                .help("Previous Page")

                Text("\(readerCoordinator.currentPage + 1) / \(readerCoordinator.pageCount)")
                    .font(AppTheme.chrome())
                    .monospacedDigit()
                    .frame(minWidth: 56)

                Button {
                    readerCoordinator.nextPage()
                } label: {
                    Image(systemName: "chevron.right")
                }
                .disabled(readerCoordinator.currentPage >= readerCoordinator.pageCount - 1)
                .help("Next Page")

                Divider()

                Button(showAnnotations ? "Hide Annotations" : "Annotations",
                       systemImage: "list.bullet.rectangle") {
                    withAnimation(.none) { showAnnotations.toggle() }
                }

                Button("Save", systemImage: "square.and.arrow.down") {
                    Task { await store.save() }
                }
            }
        }
        .sheet(item: $editingAnnotation) { annotation in
            AnnotationEditSheet(annotation: annotation)
                .environmentObject(store)
        }
    }
}

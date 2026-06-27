import SwiftUI

/// macOS root, mirroring the iPad layout: a NavigationSplitView whose sidebar is the
/// 3-tab panel (Annotations / Bookmarks / Find) and whose detail is the reader. Recents
/// live in the detail empty state (the sidebar is the panel now, as on iPad). The reader
/// coordinator is hoisted here so the sidebar's Find/Bookmarks tabs can drive the reader.
struct HomeView: View {
    @EnvironmentObject var store: DocumentStore
    @StateObject private var readerCoordinator = ReaderCoordinator()

    var body: some View {
        NavigationSplitView {
            SidebarPanelView(
                onFind:     { readerCoordinator.find() },
                onScrollTo: { readerCoordinator.scrollTo(annotationId: $0.id) },
                onOpenFile: { store.openFilePanel() }
            )
            .navigationSplitViewColumnWidth(min: 240, ideal: 300)
        } detail: {
            if store.isLoading {
                ProgressView("Loading…")
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(AppTheme.warmPaper)
            } else if store.document != nil {
                ReaderScreen(coordinator: readerCoordinator)
            } else {
                emptyState
            }
        }
    }

    private var emptyState: some View {
        VStack(spacing: 24) {
            ContentUnavailableView {
                Label("No Document Open", systemImage: "doc.text")
            } description: {
                Text("Open a DOCX file to begin reading.")
            } actions: {
                Button("Open…") { store.openFilePanel() }
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

// MARK: - Reader layout

struct ReaderScreen: View {
    @EnvironmentObject var store: DocumentStore
    let coordinator: ReaderCoordinator

    var body: some View {
        ReaderView(coordinator: coordinator)
            .toolbar {
                ToolbarItem(placement: .automatic) {
                    Button("Save", systemImage: "square.and.arrow.down") {
                        Task { await store.save() }
                    }
                }
            }
            .sheet(item: $store.editingAnnotation) { annotation in
                AnnotationEditSheet(annotation: annotation)
                    .environmentObject(store)
                    .preferredColorScheme(.light)
            }
    }
}

import SwiftUI

// MARK: - Sidebar panel (shared: iPad NavigationSplitView sidebar + macOS sidebar)

/// Three-tab sidebar that lives in the NavigationSplitView's sidebar column.
/// Annotations: the full AnnotationsPanel (Android-parity: search, tag filter, sort, rich rows).
/// Bookmarks:   compact list of .bookmark annotations sorted by document position — quick TOC nav.
/// Find:        triggers the reader's find UI (iOS UIFindInteraction / macOS find bar) via `onFind`.
struct SidebarPanelView: View {
    @EnvironmentObject var store: DocumentStore
    /// Triggers the reader's find UI. iOS bumps a findTrigger; macOS calls the reader coordinator.
    let onFind: () -> Void
    let onScrollTo: (Annotation) -> Void
    let onOpenFile: () -> Void
    /// iPhone hosts Find on the reader toolbar (a sheet would cover the system find bar),
    /// so the panel hides its Find tab in compact width.
    var showFindTab: Bool = true

    enum Tab { case annotations, bookmarks, find }
    @State private var tab: Tab = .annotations

    private var openPlacement: ToolbarItemPlacement {
        #if os(iOS)
        .topBarTrailing
        #else
        .automatic
        #endif
    }

    var body: some View {
        VStack(spacing: 0) {
            Picker("Panel", selection: $tab) {
                Text("Annotations").tag(Tab.annotations)
                Text("Bookmarks").tag(Tab.bookmarks)
                if showFindTab {
                    Text("Find").tag(Tab.find)
                }
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, 8)
            .padding(.vertical, 6)

            Divider()

            switch tab {
            case .annotations:
                AnnotationsPanel()
            case .bookmarks:
                BookmarksListView(onScrollTo: onScrollTo)
            case .find:
                if showFindTab {
                    FindPanel(onFind: onFind)
                } else {
                    AnnotationsPanel()
                }
            }
        }
        // navigationTitle and Open toolbar button sit here; AnnotationsPanel's own
        // .navigationTitle("Annotations") and sort button override these when that
        // tab is active (SwiftUI propagates the deepest values).
        .navigationTitle("Layuv")
        .toolbar {
            ToolbarItem(placement: openPlacement) {
                Button("Open…", systemImage: "folder") { onOpenFile() }
            }
        }
    }
}

// MARK: - Bookmarks tab

private struct BookmarksListView: View {
    @EnvironmentObject var store: DocumentStore
    let onScrollTo: (Annotation) -> Void

    private var bookmarks: [ResolvedAnnotation] {
        store.annotations
            .filter  { $0.annotation.tool == .bookmark }
            .sorted  { $0.annotation.position < $1.annotation.position }
    }

    var body: some View {
        List(bookmarks, id: \.annotation.id) { resolved in
            let a = resolved.annotation
            Button { onScrollTo(a) } label: {
                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 5) {
                        Image(systemName: "bookmark.fill")
                            .foregroundStyle(.orange)
                            .font(.caption2)
                        Text("\(Int(a.position * 100))%")
                            .font(AppTheme.chrome(size: 10))
                            .foregroundStyle(.secondary)
                    }
                    Text(a.selectedText)
                        .font(AppTheme.chromeBold())
                        .lineLimit(2)
                    if let note = a.note, !note.isEmpty {
                        Text(note)
                            .font(AppTheme.chrome(size: 11))
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                }
                .padding(.vertical, 2)
            }
            .buttonStyle(.plain)
        }
        .listStyle(.plain)
        .navigationTitle("Bookmarks")
        .overlay {
            if bookmarks.isEmpty {
                ContentUnavailableView(
                    "No Bookmarks",
                    systemImage: "bookmark",
                    description: Text("Select text in the reader and choose Bookmark to mark a passage.")
                )
            }
        }
    }
}

// MARK: - Find tab

private struct FindPanel: View {
    let onFind: () -> Void

    var body: some View {
        VStack(spacing: 20) {
            Spacer()
            Image(systemName: "doc.text.magnifyingglass")
                .font(.system(size: 48))
                .foregroundStyle(.secondary)
            Text("Find in Document")
                .font(.headline)
            Text("The find bar is now active over the reader. Type to search the full text.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 24)
            Button {
                onFind()
            } label: {
                Label("Open Find Bar", systemImage: "magnifyingglass")
            }
            .buttonStyle(.bordered)
            Spacer()
        }
        .frame(maxWidth: .infinity)
        .navigationTitle("Find")
        .onAppear {
            onFind()
        }
    }
}

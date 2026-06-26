import SwiftUI

// MARK: - Sidebar panel (replaces the recents list on iPad)

/// Three-tab sidebar that lives in the NavigationSplitView's sidebar column.
/// Annotations: the full AnnotationsPanel (Android-parity: search, tag filter, sort, rich rows).
/// Bookmarks:   compact list of .bookmark annotations sorted by document position — quick TOC nav.
/// Find:        triggers UIFindInteraction on the reader text view and shows a status hint.
struct SidebarPanelView: View {
    @EnvironmentObject var store: DocumentStore
    @Binding var findTrigger: Int
    let onScrollTo: (Annotation) -> Void
    let onOpenFile: () -> Void

    enum Tab { case annotations, bookmarks, find }
    @State private var tab: Tab = .annotations

    var body: some View {
        VStack(spacing: 0) {
            Picker("Panel", selection: $tab) {
                Text("Annotations").tag(Tab.annotations)
                Text("Bookmarks").tag(Tab.bookmarks)
                Text("Find").tag(Tab.find)
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
                FindPanel(findTrigger: $findTrigger)
            }
        }
        // navigationTitle and Open toolbar button sit here; AnnotationsPanel's own
        // .navigationTitle("Annotations") and sort button override these when that
        // tab is active (SwiftUI propagates the deepest values).
        .navigationTitle("Léamh")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
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
    @Binding var findTrigger: Int

    var body: some View {
        VStack(spacing: 20) {
            Spacer()
            Image(systemName: "doc.text.magnifyingglass")
                .font(.system(size: 48))
                .foregroundStyle(.secondary)
            Text("Find in Document")
                .font(.headline)
            Text("The system find bar is now active above the reader. Type to search the full text.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 24)
            Button {
                findTrigger += 1
            } label: {
                Label("Open Find Bar", systemImage: "magnifyingglass")
            }
            .buttonStyle(.bordered)
            Spacer()
        }
        .frame(maxWidth: .infinity)
        .navigationTitle("Find")
        .onAppear {
            findTrigger += 1
        }
    }
}

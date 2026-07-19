import SwiftUI
#if os(macOS)
import AppKit
#endif

// MARK: - Sidebar panel (shared: iPad NavigationSplitView sidebar + macOS sidebar)

/// Four-tab sidebar: Outline | Annotations | Bookmarks | Find.
/// Outline: document-structure headings for quick navigation.
/// Annotations: full AnnotationsPanel (search, tag filter, sort, rich rows).
/// Bookmarks: compact list of .bookmark annotations sorted by document position.
/// Find: triggers the reader's find UI (iOS UIFindInteraction / macOS find bar) via `onFind`.
///
/// The page shuttle appears at the bottom when the reader is in paged mode (paged=true).
/// On macOS it drives live page-turning; on iOS it is only available when screenFlip is active.
struct SidebarPanelView: View {
    @EnvironmentObject var store: DocumentStore

    /// Triggers the reader's find UI.
    let onFind: () -> Void
    let onScrollTo: (Annotation) -> Void
    let onOpenFile: () -> Void
    /// Jump the reader to an arbitrary char offset (used by Outline rows).
    let onScrollToCharOffset: (Int) -> Void
    /// Current page index (0-based), total page count, and whether the reader is in paged mode.
    /// When paged=true the shuttle is shown and changes call onGoToPage.
    let onGoToPage: (Int) -> Void
    @Binding var currentPage: Int
    @Binding var pageCount: Int
    @Binding var paged: Bool

    /// iPhone hosts Find on the reader toolbar, so the panel hides its Find tab in compact width.
    var showFindTab: Bool = true

    enum Tab { case annotations, outline, bookmarks, find }
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
                Text("Outline").tag(Tab.outline)
                Text("Bookmarks").tag(Tab.bookmarks)
                if showFindTab {
                    Text("Find").tag(Tab.find)
                }
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            .padding(.horizontal, 8)
            .padding(.vertical, 6)

            Divider()

            switch tab {
            case .annotations:
                AnnotationsPanel(onScrollTo: onScrollTo)
            case .outline:
                OutlineListView(onScrollToCharOffset: onScrollToCharOffset)
            case .bookmarks:
                BookmarksListView(onScrollTo: onScrollTo)
            case .find:
                if showFindTab {
                    FindPanel(onFind: onFind)
                } else {
                    AnnotationsPanel(onScrollTo: onScrollTo)
                }
            }

            // Page shuttle is no longer mounted in the sidebar on any platform.
            // Both iOS and macOS surface it transiently OVER the reader when the user taps
            // the toolbar page counter — a permanent sidebar slider dropped touch/live-scrub
            // and cluttered the panel. See the reader hosts' `pageScrubber` overlay.
        }
        .navigationTitle("Layuv")
        .toolbar {
            ToolbarItem(placement: openPlacement) {
                Button("Open…", systemImage: "folder") { onOpenFile() }
            }
        }
    }
}

// MARK: - Outline tab

private struct OutlineListView: View {
    @EnvironmentObject var store: DocumentStore
    let onScrollToCharOffset: (Int) -> Void

    private var headings: [Heading] {
        store.document?.headings ?? []
    }

    var body: some View {
        List(headings.indices, id: \.self) { i in
            let h = headings[i]
            Button {
                onScrollToCharOffset(h.charOffset)
            } label: {
                HStack(spacing: 0) {
                    // Indent by level: 12 pt per level (0-based), capped at 4 for legibility.
                    Spacer().frame(width: CGFloat(min(h.level, 4)) * 12)
                    Text(h.text)
                        .font(h.level == 0 ? AppTheme.chromeBold() : AppTheme.chrome())
                        .foregroundStyle(h.level == 0 ? .primary : .secondary)
                        .lineLimit(2)
                }
                .padding(.vertical, 1)
            }
            .buttonStyle(.plain)
        }
        .listStyle(.plain)
        .navigationTitle("Outline")
        .overlay {
            if headings.isEmpty {
                ContentUnavailableView(
                    "No Headings",
                    systemImage: "text.alignleft",
                    description: Text("This document has no heading paragraphs.")
                )
            }
        }
    }
}

// MARK: - Page shuttle (paged mode only)

/// Live-updating slider for paged navigation. Dragging turns pages immediately
/// (on macOS/iOS backlit screens this is safe — no e-ink refresh concern).
/// Chevron buttons on each end step ±1 page for fine-tuning (as the user specified).
struct PageShuttleView: View {
    @Binding var currentPage: Int
    let pageCount: Int
    let onGoToPage: (Int) -> Void

    // Slider value is a Double 0..<pageCount so we can use SwiftUI Slider natively.
    @State private var sliderValue: Double = 0

    var body: some View {
        VStack(spacing: 4) {
            HStack(spacing: 4) {
                // Left chevron — step back.
                Button { step(-1) } label: {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 13, weight: .semibold))
                }
                .buttonStyle(.plain)
                .disabled(currentPage <= 0)

                Slider(
                    value: $sliderValue,
                    in: 0...Double(max(1, pageCount - 1)),
                    step: 1
                ) {
                    EmptyView()
                } minimumValueLabel: {
                    EmptyView()
                } maximumValueLabel: {
                    EmptyView()
                } onEditingChanged: { _ in }
                .onChange(of: sliderValue) { newVal in
                    let target = Int(newVal.rounded())
                    if target != currentPage { onGoToPage(target) }
                }

                // Right chevron — step forward.
                Button { step(1) } label: {
                    Image(systemName: "chevron.right")
                        .font(.system(size: 13, weight: .semibold))
                }
                .buttonStyle(.plain)
                .disabled(currentPage >= pageCount - 1)
            }
            .padding(.horizontal, 12)

            Text("\(currentPage + 1) / \(pageCount)")
                .font(AppTheme.chrome(size: 11))
                .foregroundStyle(.secondary)
        }
        .padding(.vertical, 8)
        .onChange(of: currentPage) { newPage in
            // Keep slider in sync when the reader turns pages by other means (edge click etc.)
            sliderValue = Double(newPage)
        }
        .onAppear { sliderValue = Double(currentPage) }
    }

    private func step(_ delta: Int) {
        let target = (currentPage + delta).clamped(to: 0...(pageCount - 1))
        onGoToPage(target)
    }
}

private extension Comparable {
    func clamped(to range: ClosedRange<Self>) -> Self {
        min(max(self, range.lowerBound), range.upperBound)
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

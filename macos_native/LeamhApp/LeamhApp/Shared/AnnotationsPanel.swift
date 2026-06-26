import SwiftUI

struct AnnotationsPanel: View {
    @EnvironmentObject var store: DocumentStore
    @Binding var editingAnnotation: Annotation?

    @State private var searchText = ""
    @State private var activeTags: Set<AnnotationTag> = []

    // All four tags in display order.
    private let allTags: [AnnotationTag] = [.voice, .pacing, .continuity, .query]

    private var filtered: [ResolvedAnnotation] {
        store.annotations.filter { resolved in
            let a = resolved.annotation
            let matchesSearch = searchText.isEmpty
                || a.selectedText.localizedCaseInsensitiveContains(searchText)
                || (a.note?.localizedCaseInsensitiveContains(searchText) ?? false)
            let matchesTag = activeTags.isEmpty
                || (a.tag.map { activeTags.contains($0) } ?? false)
            return matchesSearch && matchesTag
        }
    }

    // Tags that actually appear in the current annotation set.
    private var presentTags: [AnnotationTag] {
        allTags.filter { tag in store.annotations.contains { $0.annotation.tag == tag } }
    }

    private var isFiltering: Bool { !searchText.isEmpty || !activeTags.isEmpty }

    var body: some View {
        VStack(spacing: 0) {
            searchBar
            Divider()

            if !presentTags.isEmpty {
                tagFilterRow
                Divider()
            }

            annotationList
        }
        .navigationTitle(isFiltering
            ? "Annotations (\(filtered.count) / \(store.annotations.count))"
            : "Annotations")
    }

    // MARK: Search bar

    private var searchBar: some View {
        HStack(spacing: 6) {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(.secondary)
                .font(.system(size: 12))
            TextField("Search text or notes…", text: $searchText)
                .textFieldStyle(.plain)
                .font(AppTheme.chrome())
            if !searchText.isEmpty {
                Button {
                    searchText = ""
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(.secondary)
                        .font(.system(size: 12))
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 6)
        .background(
            RoundedRectangle(cornerRadius: 6)
                .fill(AppTheme.controlFieldBackground)
                .overlay(RoundedRectangle(cornerRadius: 6).stroke(Color.secondary.opacity(0.25)))
        )
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .background(AppTheme.panelBackground)
    }

    // MARK: Tag chips

    private var tagFilterRow: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                ForEach(presentTags, id: \.rawValue) { tag in
                    let count   = store.annotations.filter { $0.annotation.tag == tag }.count
                    let isActive = activeTags.contains(tag)
                    TagFilterChip(tag: tag, count: count, isActive: isActive) {
                        if isActive { activeTags.remove(tag) } else { activeTags.insert(tag) }
                    }
                }
                if !activeTags.isEmpty {
                    Button("Clear") { activeTags.removeAll() }
                        .font(AppTheme.chrome(size: 11))
                        .foregroundStyle(.secondary)
                        .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
        }
    }

    // MARK: List

    private var annotationList: some View {
        // Button wrapping is required on macOS — onTapGesture is swallowed by the List.
        List(filtered, id: \.annotation.id) { resolved in
            Button { editingAnnotation = resolved.annotation } label: {
                AnnotationRow(resolved: resolved)
            }
            .buttonStyle(.plain)
        }
        #if os(macOS)
        .listStyle(.sidebar)
        #else
        .listStyle(.plain)
        #endif
        .overlay {
            if store.annotations.isEmpty {
                ContentUnavailableView("No Annotations",
                    systemImage: "text.badge.plus",
                    description: Text("Select text in the reader and choose a tool to annotate."))
            } else if filtered.isEmpty {
                ContentUnavailableView("No Results",
                    systemImage: "magnifyingglass",
                    description: Text("Try a different search term or clear the tag filter."))
            }
        }
    }
}

// MARK: - Tag filter chip

private struct TagFilterChip: View {
    let tag: AnnotationTag
    let count: Int
    let isActive: Bool
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 4) {
                Text(tag.rawValue.capitalized)
                    .font(AppTheme.chromeBold(size: 11))
                Text("\(count)")
                    .font(AppTheme.chrome(size: 10))
                    .padding(.horizontal, 4)
                    .padding(.vertical, 1)
                    .background(isActive ? Color.white.opacity(0.25) : Color.secondary.opacity(0.15))
                    .clipShape(Capsule())
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(isActive ? Color.accentColor : Color.secondary.opacity(0.1))
            .foregroundStyle(isActive ? Color.white : Color.primary)
            .clipShape(Capsule())
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Row

struct AnnotationRow: View {
    let resolved: ResolvedAnnotation
    @EnvironmentObject var store: DocumentStore

    private var a: Annotation { resolved.annotation }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .center, spacing: 6) {
                toolIcon
                Text(a.selectedText)
                    .font(AppTheme.chromeBold())
                    .lineLimit(1)
                Spacer(minLength: 0)
                if let tag = a.tag {
                    tagChip(tag.rawValue)
                }
            }
            if let note = a.note, !note.isEmpty {
                Text(note)
                    .font(AppTheme.chrome())
                    .foregroundStyle(.secondary)
                    .lineLimit(3)
            }
            Text(a.timestamp, style: .date)
                .font(AppTheme.chrome(size: 10))
                .foregroundStyle(.tertiary)
        }
        .padding(.vertical, 4)
        .swipeActions(edge: .trailing) {
            Button(role: .destructive) {
                store.deleteAnnotation(id: a.id)
            } label: {
                Label("Delete", systemImage: "trash")
            }
        }
    }

    @ViewBuilder private var toolIcon: some View {
        switch a.tool {
        case .highlight:       Image(systemName: "highlighter").foregroundStyle(.orange)
        case .underline:       Image(systemName: "underline").foregroundStyle(.blue)
        case .doubleUnderline: Image(systemName: "underline").foregroundStyle(.indigo)
        case .strikethrough:   Image(systemName: "strikethrough").foregroundStyle(.red)
        case .wavyUnderline:   Image(systemName: "underline").foregroundStyle(.teal)
        case .bookmark:        Image(systemName: "bookmark.fill").foregroundStyle(.orange)
        case .inkAnnotation:   Image(systemName: "pencil.tip").foregroundStyle(.purple)
        case .comment:         Image(systemName: "text.bubble").foregroundStyle(.green)
        }
    }

    private func tagChip(_ text: String) -> some View {
        Text(text)
            .font(AppTheme.chrome(size: 10))
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(Color.secondary.opacity(0.2))
            .clipShape(.capsule)
    }
}

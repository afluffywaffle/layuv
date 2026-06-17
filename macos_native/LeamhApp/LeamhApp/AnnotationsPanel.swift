import SwiftUI

struct AnnotationsPanel: View {
    @EnvironmentObject var store: DocumentStore

    var body: some View {
        List(store.annotations, id: \.annotation.id) { resolved in
            AnnotationRow(resolved: resolved)
        }
        .listStyle(.sidebar)
        .navigationTitle("Annotations")
        .overlay {
            if store.annotations.isEmpty {
                ContentUnavailableView("No Annotations", systemImage: "text.badge.plus")
            }
        }
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

import SwiftUI

/// Sheet for editing an annotation's note, tag, and tool.
/// Presented when the user taps an annotation row in AnnotationsPanel.
struct AnnotationEditSheet: View {
    let annotation: Annotation
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject var store: DocumentStore

    @State private var note: String
    @State private var selectedTag: AnnotationTag?
    @State private var selectedTool: AnnotationTool

    init(annotation: Annotation) {
        self.annotation = annotation
        _note        = State(initialValue: annotation.note ?? "")
        _selectedTag = State(initialValue: annotation.tag)
        _selectedTool = State(initialValue: annotation.tool)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {

            // Selected text (read-only)
            Text(annotation.selectedText)
                .font(AppTheme.body())
                .foregroundStyle(.secondary)
                .lineLimit(4)
                .padding(10)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(AppTheme.warmPaper)
                .clipShape(RoundedRectangle(cornerRadius: 6))

            // Tool
            VStack(alignment: .leading, spacing: 6) {
                Text("Tool")
                    .font(AppTheme.chromeBold())
                Picker("Tool", selection: $selectedTool) {
                    Text("Highlight").tag(AnnotationTool.highlight)
                    Text("Underline").tag(AnnotationTool.underline)
                    Text("Comment").tag(AnnotationTool.comment)
                    Text("Bookmark").tag(AnnotationTool.bookmark)
                    Text("Strikethrough").tag(AnnotationTool.strikethrough)
                    Text("Double Underline").tag(AnnotationTool.doubleUnderline)
                    Text("Wavy Underline").tag(AnnotationTool.wavyUnderline)
                    Text("Highlight Paragraph").tag(AnnotationTool.blockquote)
                }
                .pickerStyle(.menu)
                .labelsHidden()
            }

            // Note
            VStack(alignment: .leading, spacing: 6) {
                Text("Note")
                    .font(AppTheme.chromeBold())
                TextEditor(text: $note)
                    .font(AppTheme.body(size: 14))
                    .frame(minHeight: 80, maxHeight: 120)
                    .overlay(
                        RoundedRectangle(cornerRadius: 4)
                            .stroke(Color.secondary.opacity(0.3))
                    )
            }

            // Tag
            VStack(alignment: .leading, spacing: 6) {
                Text("Tag")
                    .font(AppTheme.chromeBold())
                HStack(spacing: 8) {
                    ForEach([AnnotationTag.voice, .pacing, .continuity, .query], id: \.rawValue) { tag in
                        Toggle(tag.rawValue.capitalized, isOn: Binding(
                            get: { selectedTag == tag },
                            set: { selected in selectedTag = selected ? tag : nil }
                        ))
                        .toggleStyle(.button)
                        .font(AppTheme.chrome(size: 12))
                        .controlSize(.small)
                    }
                }
            }

            Spacer(minLength: 0)

            // Actions
            HStack {
                Button("Delete", role: .destructive) {
                    store.deleteAnnotation(id: annotation.id)
                    dismiss()
                }
                Button("Cancel", role: .cancel) { dismiss() }
                    .keyboardShortcut(.escape)
                Spacer()
                Button("Save") {
                    let newNote: String? = note.trimmingCharacters(in: .whitespaces).isEmpty ? nil : note
                    let updated = annotation.copy(
                        tool: selectedTool,
                        note: .some(newNote),
                        tag: .some(selectedTag)
                    )
                    store.updateAnnotation(updated)
                    dismiss()
                }
                .buttonStyle(.borderedProminent)
                .keyboardShortcut(.return)
            }
        }
        .padding(20)
        #if os(macOS)
        .frame(width: 440, height: 380)
        #else
        .presentationDetents([.medium, .large])
        #endif
    }
}

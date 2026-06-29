import SwiftUI

/// Floating horizontal toolbar that lets the user choose an annotation tool for the current selection.
/// Shown by ReaderViewController as an NSPopover anchored to the selection endpoint.
struct ToolPickerView: View {
    let onSelect: (AnnotationTool) -> Void

    private struct Tool {
        let tool: AnnotationTool
        let icon: String
        let color: Color
        let label: String
    }

    private let tools: [Tool] = [
        Tool(tool: .highlight,       icon: "highlighter",   color: .orange, label: "Highlight"),
        Tool(tool: .underline,       icon: "underline",     color: .blue,   label: "Underline"),
        Tool(tool: .doubleUnderline, icon: "underline",     color: .indigo, label: "Double"),
        Tool(tool: .strikethrough,   icon: "strikethrough", color: .red,    label: "Strike"),
        Tool(tool: .comment,         icon: "text.bubble",   color: .green,  label: "Comment"),
        Tool(tool: .bookmark,        icon: "bookmark.fill", color: .orange, label: "Bookmark"),
        Tool(tool: .inkAnnotation,   icon: "pencil.tip",    color: .purple, label: "Ink"),
    ]

    var body: some View {
        HStack(spacing: 0) {
            ForEach(tools, id: \.label) { t in
                Button {
                    onSelect(t.tool)
                } label: {
                    VStack(spacing: 3) {
                        Image(systemName: t.icon)
                            .font(.system(size: 15, weight: .medium))
                            .foregroundStyle(t.color)
                        Text(t.label)
                            .font(.system(size: 9))
                            .foregroundStyle(.secondary)
                    }
                    .frame(width: 54, height: 46)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .help(t.label)
            }
        }
        .padding(.horizontal, 4)
        .padding(.vertical, 6)
    }
}

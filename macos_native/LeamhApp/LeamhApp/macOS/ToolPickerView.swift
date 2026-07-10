import SwiftUI

/// Floating horizontal toolbar that lets the user choose an annotation tool for the current selection.
/// Shown by ReaderViewController as an NSPopover anchored to the selection endpoint.
struct ToolPickerView: View {
    /// Apply the tool once to the current selection (unchanged tap behaviour).
    let onSelect: (AnnotationTool) -> Void
    /// Lock the tool: apply it to the current selection AND keep it active so every
    /// subsequent selection is auto-annotated with it (mirrors Android's "Lock tool").
    let onLock: (AnnotationTool) -> Void

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

    /// Which tool's press-and-hold flyout is currently open (nil = none). The flyout
    /// offers "Apply Once" / "Lock Tool" (and, for Highlight, "Highlight Paragraph").
    @State private var flyoutTool: AnnotationTool? = nil

    /// Comment and Ink are NOT lockable (they open their own edit/ink flow) — matches
    /// Android, where long-press-lock is limited to the mark-up tools.
    private func isLockable(_ tool: AnnotationTool) -> Bool {
        tool != .comment && tool != .inkAnnotation
    }

    var body: some View {
        // A real (layout-affecting) VStack — when the flyout appears the hosting
        // controller's fitting size grows and the NSPopover resizes to fit, so nothing
        // is clipped. (See ReaderView.showToolPopover: contentSize is NOT pinned.)
        VStack(spacing: 6) {
            if let ft = flyoutTool, let t = tools.first(where: { $0.tool == ft }) {
                flyout(for: t)
            }
            HStack(spacing: 0) {
                ForEach(tools, id: \.label) { t in
                    toolButton(t)
                }
            }
        }
        .padding(.horizontal, 4)
        .padding(.vertical, 6)
    }

    @ViewBuilder
    private func toolButton(_ t: Tool) -> some View {
        let label = VStack(spacing: 3) {
            Image(systemName: t.icon)
                .font(.system(size: 15, weight: .medium))
                .foregroundStyle(t.color)
            Text(t.label)
                .font(.system(size: 9))
                .foregroundStyle(.secondary)
        }
        .frame(width: 54, height: 46)
        .contentShape(Rectangle())

        if isLockable(t.tool) {
            // Every mark-up tool doubles as a Photoshop-style tool flyout: a plain tap
            // applies the tool once; press-and-hold reveals "Apply Once" / "Lock Tool"
            // (Highlight additionally offers "Highlight Paragraph"). A SwiftUI `Button`
            // action + `.onLongPressGesture` can double-fire, so drive both gestures
            // directly instead of wrapping in a Button.
            //
            // NOTE: a `.contextMenu` (right-click) approach shows nothing — the parent
            // NSPopover is `.behavior = .transient`, whose global event monitor treats
            // the `rightMouseDown` as an outside-click and dismisses the popover before
            // AppKit routes the event to SwiftUI's context menu.
            label
                .overlay(
                    // Subtle affordance that this tool has a hold-variant.
                    Image(systemName: "ellipsis")
                        .font(.system(size: 6, weight: .bold))
                        .foregroundStyle(.secondary)
                        .padding(.trailing, 6).padding(.top, 4),
                    alignment: .topTrailing
                )
                .onTapGesture {
                    flyoutTool = nil
                    onSelect(t.tool)
                }
                .onLongPressGesture(minimumDuration: 0.35) {
                    // Second hold on the same tool dismisses its flyout (matches Android).
                    flyoutTool = (flyoutTool == t.tool) ? nil : t.tool
                }
        } else {
            Button {
                onSelect(t.tool)
            } label: {
                label
            }
            .buttonStyle(.plain)
            .help(t.label)
        }
    }

    /// The press-and-hold flyout shown above the toolbar for a lockable tool.
    @ViewBuilder
    private func flyout(for t: Tool) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            flyoutRow(icon: t.icon, tint: t.color, title: "Apply Once") {
                flyoutTool = nil
                onSelect(t.tool)
            }
            flyoutRow(icon: "lock.fill", tint: t.color, title: "Lock Tool") {
                flyoutTool = nil
                onLock(t.tool)
            }
            if t.tool == .highlight {
                // Commits a `.blockquote` over the whole enclosing paragraph (grey fill +
                // rust left border, same look as an imported Word blockquote — expansion
                // handled in ReaderView.commitAnnotation).
                flyoutRow(icon: "text.quote",
                          tint: Color(red: 0.753, green: 0.439, blue: 0.188),
                          title: "Highlight Paragraph") {
                    flyoutTool = nil
                    onSelect(.blockquote)
                }
            }
        }
        .padding(.leading, 4)
    }

    private func flyoutRow(icon: String, tint: Color, title: String,
                           action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 6) {
                Image(systemName: icon)
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(tint)
                    .frame(width: 16)
                Text(title)
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(AppTheme.warmPaperInk)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .frame(width: 160, alignment: .leading)
            .background(
                RoundedRectangle(cornerRadius: 8)
                    .fill(AppTheme.warmPaper)
                    .overlay(
                        RoundedRectangle(cornerRadius: 8)
                            .stroke(Color.secondary.opacity(0.35), lineWidth: 1)
                    )
            )
        }
        .buttonStyle(.plain)
    }
}

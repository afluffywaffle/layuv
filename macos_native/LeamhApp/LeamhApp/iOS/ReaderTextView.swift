import SwiftUI
import UIKit

/// iPad reader: a TextKit 2 UITextView rendering the canonical plain text with bold/italic
/// format spans and per-tool annotation decorations on warm paper. Read-only for M1 —
/// text selection → tool popover, tap-to-edit, and Apple Pencil ink arrive in later milestones.
///
/// Annotation spans are UTF-16 offsets into `plainText` by construction (same indexing the
/// engine's anchoring uses), so they map straight onto NSAttributedString ranges.
struct ReaderTextView: UIViewRepresentable {
    let document: LoadedDocument
    let annotations: [ResolvedAnnotation]

    func makeUIView(context: Context) -> UITextView {
        let tv = UITextView(usingTextLayoutManager: true)   // TextKit 2
        tv.isEditable = false
        tv.isSelectable = true
        tv.backgroundColor = AppTheme.warmPaperUI
        tv.textContainerInset = UIEdgeInsets(top: 32, left: 24, bottom: 48, right: 24)
        tv.alwaysBounceVertical = true
        tv.showsVerticalScrollIndicator = true
        tv.contentInsetAdjustmentBehavior = .always
        return tv
    }

    func updateUIView(_ tv: UITextView, context: Context) {
        let wasEmpty = (tv.attributedText?.length ?? 0) == 0
        tv.attributedText = Self.makeAttributedString(document: document, annotations: annotations)
        // Reset to the top only on the first content load, not on annotation-only refreshes.
        if wasEmpty { tv.setContentOffset(.zero, animated: false) }
    }

    // MARK: - Attributed-string assembly (mirrors the macOS reader's buildAttributedString + applyHighlights)

    static func makeAttributedString(document doc: LoadedDocument,
                                     annotations: [ResolvedAnnotation]) -> NSAttributedString {
        let str = NSMutableAttributedString(string: doc.plainText, attributes: [
            .font:            AppTheme.uiBody(),
            .foregroundColor: UIColor.label,
        ])
        let utf16len = doc.plainText.utf16.count

        // Bold / italic format spans.
        for span in doc.formatSpans {
            let len = span.end - span.start
            guard len > 0, span.start >= 0, span.start + len <= utf16len else { continue }
            let r = NSRange(location: span.start, length: len)
            if span.bold && span.italic {
                str.addAttribute(.font, value: AppTheme.uiBodyItalic(), range: r)
            } else if span.bold {
                str.addAttribute(.font, value: AppTheme.uiBodyBold(), range: r)
            } else if span.italic {
                str.addAttribute(.font, value: AppTheme.uiBodyItalic(), range: r)
            }
        }

        // Per-tool annotation decorations. iPad has no e-ink constraints — colour fills,
        // solid underlines, strikethrough are all fine (matches the macOS reader exactly).
        for resolved in annotations {
            guard let span = resolved.span else { continue }
            let len = span.end - span.start
            guard len > 0, span.start >= 0, span.start + len <= utf16len else { continue }
            let r = NSRange(location: span.start, length: len)

            switch resolved.annotation.tool {
            case .highlight:
                str.addAttribute(.backgroundColor,
                                 value: UIColor.systemYellow.withAlphaComponent(0.45), range: r)
            case .underline:
                str.addAttribute(.underlineStyle, value: NSUnderlineStyle.single.rawValue, range: r)
                str.addAttribute(.underlineColor, value: UIColor.label, range: r)
            case .doubleUnderline:
                str.addAttribute(.underlineStyle, value: NSUnderlineStyle.double.rawValue, range: r)
                str.addAttribute(.underlineColor, value: UIColor.label, range: r)
            case .strikethrough:
                str.addAttribute(.strikethroughStyle, value: NSUnderlineStyle.single.rawValue, range: r)
                str.addAttribute(.strikethroughColor, value: UIColor.label, range: r)
            case .wavyUnderline:
                // NSUnderlineStyle has no native wavy; thick + dash is the closest approximation.
                let style = NSUnderlineStyle.patternDash.rawValue | NSUnderlineStyle.thick.rawValue
                str.addAttribute(.underlineStyle, value: style, range: r)
                str.addAttribute(.underlineColor, value: UIColor.systemTeal, range: r)
            case .bookmark:
                str.addAttribute(.backgroundColor,
                                 value: UIColor.systemOrange.withAlphaComponent(0.15), range: r)
            case .comment:
                let style = NSUnderlineStyle.patternDot.rawValue | NSUnderlineStyle.thick.rawValue
                str.addAttribute(.underlineStyle, value: style, range: r)
                str.addAttribute(.underlineColor, value: UIColor.systemGreen, range: r)
                str.addAttribute(.backgroundColor,
                                 value: UIColor.systemGreen.withAlphaComponent(0.1), range: r)
            case .inkAnnotation:
                // Ink is shown as an embedded image; no text decoration needed.
                break
            }
        }
        return str
    }
}

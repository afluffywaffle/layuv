import SwiftUI
import AppKit

// MARK: - SwiftUI bridge

struct ReaderView: NSViewControllerRepresentable {
    @EnvironmentObject var store: DocumentStore

    func makeNSViewController(context: Context) -> ReaderViewController {
        ReaderViewController()
    }

    func updateNSViewController(_ vc: ReaderViewController, context: Context) {
        guard let doc = store.document else { return }
        vc.update(document: doc, annotations: store.annotations)
    }
}

// MARK: - TextKit 2 view controller

final class ReaderViewController: NSViewController {
    // Page geometry (logical; actual column is narrower due to margins)
    static let pageWidth:  CGFloat = 680
    static let pageHeight: CGFloat = 860
    static let margin:     CGFloat = 56

    private var textView:   NSTextView!
    private var scrollView: NSScrollView!

    // Page navigation state: each "page" is pageHeight pts of scroll offset.
    private(set) var currentPage = 0

    // MARK: View lifecycle

    override func loadView() {
        scrollView = NSScrollView()
        scrollView.backgroundColor        = AppTheme.warmPaperNS
        scrollView.hasVerticalScroller    = true
        scrollView.hasHorizontalScroller  = false
        scrollView.autohidesScrollers     = true

        // NSTextView() on macOS 14 uses TextKit 2 (NSTextLayoutManager) by default.
        textView = NSTextView(frame: NSRect(x: 0, y: 0,
                                            width: Self.pageWidth,
                                            height: Self.pageHeight))
        textView.isEditable   = false
        textView.isSelectable = true
        textView.backgroundColor   = AppTheme.warmPaperNS
        textView.textContainerInset = NSSize(width: Self.margin, height: 40)
        textView.autoresizingMask  = [.width]
        textView.textContainer?.widthTracksTextView  = true
        textView.textContainer?.heightTracksTextView = false
        textView.textContainer?.size = NSSize(
            width:  Self.pageWidth - 2 * Self.margin,
            height: .greatestFiniteMagnitude
        )

        scrollView.documentView = textView
        view = scrollView
    }

    // MARK: Content update

    func update(document: LoadedDocument, annotations: [ResolvedAnnotation]) {
        let attributed = buildAttributedString(from: document)

        // Update via NSTextContentStorage transaction (TextKit 2 path).
        if let cs = textView.textContentStorage {
            cs.performEditingTransaction {
                cs.textStorage?.setAttributedString(attributed)
            }
        } else {
            textView.textStorage?.setAttributedString(attributed)
        }

        applyHighlights(annotations)
    }

    private func buildAttributedString(from doc: LoadedDocument) -> NSAttributedString {
        let str = NSMutableAttributedString(string: doc.plainText, attributes: [
            .font:            AppTheme.nsBody(),
            .foregroundColor: NSColor.labelColor,
        ])

        let utf16len = doc.plainText.utf16.count
        for span in doc.formatSpans {
            let len = span.end - span.start
            guard len > 0, span.start >= 0, span.start + len <= utf16len else { continue }
            let r = NSRange(location: span.start, length: len)
            if span.bold && span.italic {
                str.addAttribute(.font, value: AppTheme.nsBodyItalic(), range: r)
            } else if span.bold {
                str.addAttribute(.font, value: AppTheme.nsBodyBold(), range: r)
            } else if span.italic {
                str.addAttribute(.font, value: AppTheme.nsBodyItalic(), range: r)
            }
        }
        return str
    }

    // CLAUDE.md: highlight = dotted underline, black at ~15% opacity.
    private func applyHighlights(_ annotations: [ResolvedAnnotation]) {
        guard let storage = textView.textStorage else { return }
        let full = NSRange(location: 0, length: storage.length)
        storage.removeAttribute(.underlineStyle, range: full)
        storage.removeAttribute(.underlineColor, range: full)

        let dotted = NSUnderlineStyle.patternDot.rawValue | NSUnderlineStyle.single.rawValue
        for resolved in annotations {
            guard let span = resolved.span else { continue }
            let len = span.end - span.start
            guard len > 0, span.start + len <= storage.length else { continue }
            let r = NSRange(location: span.start, length: len)
            storage.addAttribute(.underlineStyle, value: dotted, range: r)
            storage.addAttribute(.underlineColor, value: AppTheme.highlightNS, range: r)
        }
    }

    // MARK: Page navigation

    var pageCount: Int {
        let docHeight = textView.frame.height
        guard docHeight > 0 else { return 1 }
        return max(1, Int(ceil(docHeight / Self.pageHeight)))
    }

    func scrollToPage(_ page: Int) {
        let clamped = max(0, min(page, pageCount - 1))
        let y = CGFloat(clamped) * Self.pageHeight
        // Use zero-duration to avoid animation (no animation on e-ink; fine on macOS too).
        scrollView.documentView?.scroll(NSPoint(x: 0, y: y))
        currentPage = clamped
    }

    func nextPage()     { scrollToPage(currentPage + 1) }
    func previousPage() { scrollToPage(currentPage - 1) }
}

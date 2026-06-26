import SwiftUI
import UIKit

/// iPad reader: a TextKit 2 UITextView (wrapped in a UIViewController, mirroring the macOS
/// ReaderViewController) rendering the canonical plain text with bold/italic format spans and
/// per-tool annotation decorations on warm paper.
///
/// M2 interactions:
///  - Select text → the native selection edit menu offers the 6 annotation tools (the iPad-native
///    equivalent of the macOS floating tool popover). Choosing one creates the annotation.
///  - Tap an existing annotation → a [Comment | Delete] action sheet; Comment opens the edit sheet.
///
/// Annotation spans are UTF-16 offsets into `plainText` by construction, so they map straight onto
/// NSAttributedString ranges and UITextView positions.
struct ReaderTextView: UIViewControllerRepresentable {
    let document: LoadedDocument
    let annotations: [ResolvedAnnotation]
    let documentURL: URL?
    @EnvironmentObject var store: DocumentStore

    func makeUIViewController(context: Context) -> ReaderViewController {
        let vc = ReaderViewController()
        vc.onAnnotationCreated = { [weak store] annotation in store?.addAnnotation(annotation) }
        // Opening the edit sheet is centralised on the store (set from VC tap or comment creation).
        vc.onAnnotationTapped  = { [weak store] annotation in store?.editingAnnotation = annotation }
        vc.onDeleteAnnotation  = { [weak store] id in store?.deleteAnnotation(id: id) }
        return vc
    }

    func updateUIViewController(_ vc: ReaderViewController, context: Context) {
        vc.update(document: document, annotations: annotations, documentURL: documentURL)
    }

    // MARK: - Attributed-string assembly (shared with the VC; mirrors the macOS reader)

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
                break   // ink is shown as an embedded image; M3
            }
        }
        return str
    }
}

// MARK: - View controller

final class ReaderViewController: UIViewController, UITextViewDelegate, UIGestureRecognizerDelegate {
    private let textView = UITextView(usingTextLayoutManager: true)
    private var currentAnnotations: [ResolvedAnnotation] = []
    private var lastDocumentURL: URL?
    private var hasRendered = false

    var onAnnotationCreated: ((Annotation) -> Void)?
    var onAnnotationTapped:  ((Annotation) -> Void)?
    var onDeleteAnnotation:  ((String) -> Void)?

    /// The 6 selection tools, matching the macOS ToolPickerView. Ink is M3 (PencilKit).
    private let tools: [(tool: AnnotationTool, title: String, icon: String)] = [
        (.highlight,       "Highlight",         "highlighter"),
        (.underline,       "Underline",         "underline"),
        (.doubleUnderline, "Double Underline",  "underline"),
        (.strikethrough,   "Strikethrough",     "strikethrough"),
        (.comment,         "Comment",           "text.bubble"),
        (.bookmark,        "Bookmark",          "bookmark.fill"),
    ]

    override func viewDidLoad() {
        super.viewDidLoad()
        textView.isEditable = false
        textView.isSelectable = true
        textView.delegate = self
        textView.backgroundColor = AppTheme.warmPaperUI
        textView.textContainerInset = UIEdgeInsets(top: 32, left: 24, bottom: 48, right: 24)
        textView.alwaysBounceVertical = true
        textView.showsVerticalScrollIndicator = true
        textView.contentInsetAdjustmentBehavior = .always
        textView.translatesAutoresizingMaskIntoConstraints = false
        view.backgroundColor = AppTheme.warmPaperUI
        view.addSubview(textView)
        NSLayoutConstraint.activate([
            textView.topAnchor.constraint(equalTo: view.topAnchor),
            textView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            textView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            textView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
        ])

        let tap = UITapGestureRecognizer(target: self, action: #selector(handleTap(_:)))
        tap.delegate = self
        tap.cancelsTouchesInView = false
        // Defer to the text view's own double-tap (word select) so double-tapping an annotated
        // word selects it instead of opening the action sheet on the first tap.
        for gr in textView.gestureRecognizers ?? [] {
            if let dbl = gr as? UITapGestureRecognizer, dbl.numberOfTapsRequired == 2 {
                tap.require(toFail: dbl)
            }
        }
        textView.addGestureRecognizer(tap)
    }

    // MARK: Content

    func update(document: LoadedDocument, annotations: [ResolvedAnnotation], documentURL: URL?) {
        currentAnnotations = annotations
        // A genuine document switch (different URL) resets to the top; annotation-only refreshes
        // of the same document keep the reader where the user was scrolled.
        let isNewDocument = !hasRendered || documentURL != lastDocumentURL
        let offset = textView.contentOffset
        textView.attributedText = ReaderTextView.makeAttributedString(document: document, annotations: annotations)
        if isNewDocument {
            textView.contentOffset = .zero
            lastDocumentURL = documentURL
            hasRendered = true
        } else {
            textView.contentOffset = offset
        }
    }

    // MARK: Selection → tool menu

    func textView(_ textView: UITextView,
                  editMenuForTextIn range: NSRange,
                  suggestedActions: [UIMenuElement]) -> UIMenu? {
        guard range.length > 0 else { return nil }
        let actions: [UIAction] = tools.map { t in
            UIAction(title: t.title, image: UIImage(systemName: t.icon)) { [weak self] _ in
                self?.commitAnnotation(tool: t.tool, range: range)
            }
        }
        let annotate = UIMenu(title: "", options: .displayInline, children: actions)
        return UIMenu(children: [annotate] + suggestedActions)
    }

    private func commitAnnotation(tool: AnnotationTool, range: NSRange) {
        let ns = (textView.text ?? "") as NSString
        let len = ns.length
        guard range.location != NSNotFound, range.length > 0, range.location + range.length <= len else { return }

        let selectedText = ns.substring(with: range)
        let prefixStart  = max(0, range.location - 40)
        let prefix       = ns.substring(with: NSRange(location: prefixStart,
                                                      length: range.location - prefixStart))
        let suffixEnd    = min(len, range.location + range.length + 40)
        let suffix       = ns.substring(with: NSRange(location: range.location + range.length,
                                                      length: suffixEnd - (range.location + range.length)))
        let position     = len > 0 ? Double(range.location) / Double(len) : 0.0

        let annotation = Annotation(
            id:           newId(),
            selectedText: selectedText,
            prefix:       prefix,
            suffix:       suffix,
            tool:         tool,
            timestamp:    Date(),
            position:     position
        )

        onAnnotationCreated?(annotation)
        // Comment annotations open the editor immediately so the user can add a note.
        if tool == .comment {
            onAnnotationTapped?(annotation)
        }
        textView.selectedTextRange = nil
    }

    // MARK: Tap an annotation → action sheet

    @objc private func handleTap(_ gesture: UITapGestureRecognizer) {
        guard textView.selectedRange.length == 0 else { return }   // don't fight an active selection
        let point = gesture.location(in: textView)
        guard let annotation = annotationAt(point) else { return }
        presentActions(for: annotation, at: point)
    }

    private func annotationAt(_ point: CGPoint) -> Annotation? {
        guard let pos = textView.closestPosition(to: point) else { return nil }
        // closestPosition snaps to an insertion boundary; bias to the glyph actually under the
        // finger (left of the caret → previous glyph) so the half-open span test is correct.
        let caret = textView.caretRect(for: pos)
        var charIdx = textView.offset(from: textView.beginningOfDocument, to: pos)
        if point.x < caret.minX { charIdx -= 1 }
        let len = (textView.text as NSString?)?.length ?? 0
        guard charIdx >= 0, charIdx < len else { return nil }

        // Require the tap to actually fall on that glyph's rect, so taps in a side margin or the
        // blank area above/below a line don't register.
        guard let from  = textView.position(from: textView.beginningOfDocument, offset: charIdx),
              let to    = textView.position(from: from, offset: 1),
              let range = textView.textRange(from: from, to: to) else { return nil }
        let glyphRect = textView.firstRect(for: range)
        guard glyphRect.height > 0, glyphRect.insetBy(dx: -4, dy: -2).contains(point) else { return nil }

        return currentAnnotations.first { resolved in
            guard let span = resolved.span else { return false }
            return charIdx >= span.start && charIdx < span.end
        }?.annotation
    }

    private func presentActions(for annotation: Annotation, at point: CGPoint) {
        let raw = annotation.selectedText
        let preview = raw.count > 80 ? String(raw.prefix(80)) + "…" : raw
        let sheet = UIAlertController(title: nil, message: preview, preferredStyle: .actionSheet)
        sheet.addAction(UIAlertAction(title: "Comment", style: .default) { [weak self] _ in
            self?.onAnnotationTapped?(annotation)
        })
        sheet.addAction(UIAlertAction(title: "Delete", style: .destructive) { [weak self] _ in
            self?.onDeleteAnnotation?(annotation.id)
        })
        sheet.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        // iPad requires a popover anchor for action sheets.
        if let pop = sheet.popoverPresentationController {
            pop.sourceView = textView
            pop.sourceRect = CGRect(origin: point, size: CGSize(width: 1, height: 1))
        }
        present(sheet, animated: true)
    }

    // MARK: UIGestureRecognizerDelegate

    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                           shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
        true   // coexist with the text view's own selection gestures
    }
}

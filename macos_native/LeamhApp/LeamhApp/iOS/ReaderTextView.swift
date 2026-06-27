import SwiftUI
import UIKit

// MARK: - Navigation mode

enum NavMode: String, CaseIterable {
    case scroll     = "scroll"
    case pageTurn   = "pageTurn"
    case screenFlip = "screenFlip"

    var label: String {
        switch self {
        case .scroll:     return "Scroll"
        case .pageTurn:   return "Page Turn"
        case .screenFlip: return "Screen Flip"
        }
    }
    var icon: String {
        switch self {
        case .scroll:     return "scroll"
        case .pageTurn:   return "book.pages"
        case .screenFlip: return "hand.tap"
        }
    }
}

// MARK: - Representable

/// iPad reader: TextKit 2 UITextView wrapped in a UIViewController, rendering canonical plain text
/// with bold/italic format spans and per-tool annotation decorations on warm paper.
///
/// Interactions:
/// - Select text → a floating icon toolbar (FloatingSelectionToolbar) appears above the selection
///   offering the 7 annotation tools as SF Symbol buttons. Choosing one creates the annotation.
/// - Tap an existing annotation → [Comment | Delete] action sheet (or "Edit Ink" for ink notes).
/// - NavMode controls how the reader is paged (scroll / page-turn / screen-flip).
/// - findTrigger: increment from the host view to present the system Find navigator.
struct ReaderTextView: UIViewControllerRepresentable {
    let document: LoadedDocument
    let annotations: [ResolvedAnnotation]
    let documentURL: URL?
    var bodyPointSize: CGFloat = AppTheme.bodySize
    var navMode: NavMode = .scroll
    var findTrigger: Int = 0
    /// Set to an annotation ID to scroll the reader to that annotation (one-shot; stays set).
    var scrollToAnnotationId: String? = nil
    @EnvironmentObject var store: DocumentStore

    func makeCoordinator() -> Coordinator { Coordinator() }

    final class Coordinator {
        var lastFindTrigger = 0
        var lastScrollAnnotationId: String? = nil
    }

    func makeUIViewController(context: Context) -> ReaderViewController {
        let vc = ReaderViewController()
        vc.onAnnotationCreated      = { [weak store] ann in store?.addAnnotation(ann) }
        vc.onAnnotationTapped       = { [weak store] ann in store?.editingAnnotation = ann }
        vc.onDeleteAnnotation       = { [weak store] id  in store?.deleteAnnotation(id: id) }
        vc.onInkAnnotationRequested = { [weak store] ann in store?.beginInkAnnotation(ann) }
        vc.onEditInk                = { [weak store] ann in store?.editInkAnnotation(ann) }
        return vc
    }

    func updateUIViewController(_ vc: ReaderViewController, context: Context) {
        vc.update(document: document, annotations: annotations,
                  documentURL: documentURL, bodySize: bodyPointSize)
        vc.navMode = navMode
        if findTrigger != context.coordinator.lastFindTrigger {
            context.coordinator.lastFindTrigger = findTrigger
            vc.activateFind()
        }
        if let id = scrollToAnnotationId, id != context.coordinator.lastScrollAnnotationId {
            context.coordinator.lastScrollAnnotationId = id
            vc.scrollToAnnotation(id: id)
        }
    }

    // MARK: - Attributed string (shared with the VC; mirrors the macOS reader)

    static func makeAttributedString(document doc: LoadedDocument,
                                     annotations: [ResolvedAnnotation],
                                     bodySize: CGFloat = AppTheme.bodySize) -> NSAttributedString {
        let str = NSMutableAttributedString(string: doc.plainText, attributes: [
            .font:            AppTheme.uiBody(size: bodySize),
            .foregroundColor: UIColor.label,
        ])
        let utf16len = doc.plainText.utf16.count

        for span in doc.formatSpans {
            let len = span.end - span.start
            guard len > 0, span.start >= 0, span.start + len <= utf16len else { continue }
            let r = NSRange(location: span.start, length: len)
            if span.bold && span.italic {
                str.addAttribute(.font, value: AppTheme.uiBodyItalic(size: bodySize), range: r)
            } else if span.bold {
                str.addAttribute(.font, value: AppTheme.uiBodyBold(size: bodySize), range: r)
            } else if span.italic {
                str.addAttribute(.font, value: AppTheme.uiBodyItalic(size: bodySize), range: r)
            }
        }

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
                str.addAttribute(.backgroundColor,
                                 value: UIColor.systemPurple.withAlphaComponent(0.12), range: r)
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
    var onInkAnnotationRequested: ((Annotation) -> Void)?
    var onEditInk:           ((Annotation) -> Void)?

    // MARK: - Nav mode

    var navMode: NavMode = .scroll {
        didSet { guard oldValue != navMode else { return }; applyNavMode() }
    }

    private var edgeTap: UITapGestureRecognizer!
    private var swipeNext: UISwipeGestureRecognizer!
    private var swipePrev: UISwipeGestureRecognizer!
    private var selectionToolbar: FloatingSelectionToolbar?

    // MARK: - Setup

    override func viewDidLoad() {
        super.viewDidLoad()
        textView.isEditable   = false
        textView.isSelectable = true
        textView.delegate     = self
        textView.backgroundColor        = AppTheme.warmPaperUI
        textView.textContainerInset     = UIEdgeInsets(top: 32, left: 24, bottom: 48, right: 24)
        textView.alwaysBounceVertical   = true
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

        // System find-in-text (UIFindInteraction, iOS 16+).
        textView.isFindInteractionEnabled = true

        // Single-tap to open annotation actions.
        let tap = UITapGestureRecognizer(target: self, action: #selector(handleTap(_:)))
        tap.delegate = self
        tap.cancelsTouchesInView = false
        for gr in textView.gestureRecognizers ?? [] {
            if let dbl = gr as? UITapGestureRecognizer, dbl.numberOfTapsRequired == 2 {
                tap.require(toFail: dbl)
            }
        }
        textView.addGestureRecognizer(tap)

        // Edge-tap for screenFlip / pageTurn (tapping left/right zone).
        edgeTap = UITapGestureRecognizer(target: self, action: #selector(handleEdgeTap(_:)))
        edgeTap.delegate = self
        edgeTap.isEnabled = false
        view.addGestureRecognizer(edgeTap)

        // Swipe gestures for pageTurn.
        swipeNext = UISwipeGestureRecognizer(target: self, action: #selector(handleSwipeNext))
        swipeNext.direction = .left
        swipeNext.isEnabled = false
        view.addGestureRecognizer(swipeNext)

        swipePrev = UISwipeGestureRecognizer(target: self, action: #selector(handleSwipePrev))
        swipePrev.direction = .right
        swipePrev.isEnabled = false
        view.addGestureRecognizer(swipePrev)
    }

    // MARK: - Content update

    func update(document: LoadedDocument, annotations: [ResolvedAnnotation],
                documentURL: URL?, bodySize: CGFloat) {
        currentAnnotations = annotations
        let isNewDocument = !hasRendered || documentURL != lastDocumentURL
        let offset = textView.contentOffset
        textView.attributedText = ReaderTextView.makeAttributedString(document: document,
                                                                       annotations: annotations,
                                                                       bodySize: bodySize)
        if isNewDocument {
            textView.contentOffset = .zero
            lastDocumentURL = documentURL
            hasRendered = true
        } else {
            textView.contentOffset = offset
        }
    }

    // MARK: - Find

    func activateFind() {
        textView.findInteraction?.presentFindNavigator(showingReplace: false)
    }

    // MARK: - Scroll to annotation (used by the Bookmarks sidebar tab)

    func scrollToAnnotation(id: String) {
        guard let resolved = currentAnnotations.first(where: { $0.annotation.id == id }),
              let span = resolved.span, span.start >= 0 else { return }
        let length = max(1, min(span.end - span.start, 80))
        textView.scrollRangeToVisible(NSRange(location: span.start, length: length))
    }

    // MARK: - Nav mode

    private func applyNavMode() {
        switch navMode {
        case .scroll:
            textView.isScrollEnabled = true
            edgeTap.isEnabled   = false
            swipeNext.isEnabled = false
            swipePrev.isEnabled = false
        case .screenFlip:
            textView.isScrollEnabled = false
            edgeTap.isEnabled   = true
            swipeNext.isEnabled = false
            swipePrev.isEnabled = false
        case .pageTurn:
            textView.isScrollEnabled = false
            edgeTap.isEnabled   = true
            swipeNext.isEnabled = true
            swipePrev.isEnabled = true
        }
    }

    @objc private func handleEdgeTap(_ gr: UITapGestureRecognizer) {
        let x = gr.location(in: view).x
        let w = view.bounds.width
        if x < w * 0.25 {
            navigatePages(forward: false)
        } else if x > w * 0.75 {
            navigatePages(forward: true)
        }
    }

    @objc private func handleSwipeNext() { navigatePages(forward: true) }
    @objc private func handleSwipePrev() { navigatePages(forward: false) }

    private func navigatePages(forward: Bool) {
        let inset   = textView.textContainerInset
        let visible = textView.bounds.height - inset.top - inset.bottom
        let current = textView.contentOffset.y
        let maxY    = max(0, textView.contentSize.height - textView.bounds.height)
        let target  = forward
            ? min(current + visible, maxY)
            : max(current - visible, 0)
        let dest = CGPoint(x: 0, y: target)

        switch navMode {
        case .scroll:
            break
        case .screenFlip:
            textView.setContentOffset(dest, animated: false)
        case .pageTurn:
            UIView.transition(with: textView, duration: 0.30,
                              options: forward ? .transitionFlipFromRight : .transitionFlipFromLeft) {
                self.textView.setContentOffset(dest, animated: false)
            }
        }
    }

    // MARK: - UITextViewDelegate — floating annotation toolbar

    func textViewDidChangeSelection(_ textView: UITextView) {
        let range = textView.selectedRange
        if range.length > 0 {
            showAnnotationToolbar(near: range)
        } else {
            selectionToolbar?.isHidden = true
        }
    }

    // Editing is disabled, so this delegate is only called on programmatic range changes,
    // but return true so the delegate chain stays consistent.
    func textView(_ textView: UITextView,
                  editMenuForTextIn range: NSRange,
                  suggestedActions: [UIMenuElement]) -> UIMenu? {
        // Annotation tools live in the floating FloatingSelectionToolbar.
        // Return only the system actions (Copy, Look Up, etc.) here.
        return UIMenu(children: suggestedActions)
    }

    private func showAnnotationToolbar(near range: NSRange) {
        // Lazily create the toolbar.
        let toolbar: FloatingSelectionToolbar
        if let existing = selectionToolbar {
            toolbar = existing
        } else {
            let t = FloatingSelectionToolbar()
            t.onSelect = { [weak self] tool in
                guard let self else { return }
                self.commitAnnotation(tool: tool, range: self.textView.selectedRange)
                self.selectionToolbar?.isHidden = true
            }
            view.addSubview(t)
            selectionToolbar = t
            toolbar = t
        }

        // Determine position above the selection's first rect.
        guard let from = textView.position(from: textView.beginningOfDocument, offset: range.location),
              let to   = textView.position(from: from, offset: range.length),
              let tr   = textView.textRange(from: from, to: to) else {
            toolbar.isHidden = true; return
        }
        let firstRect    = textView.firstRect(for: tr)
        let rectInView   = textView.convert(firstRect, to: view)
        let sz           = toolbar.intrinsicContentSize
        let toolbarH     = sz.height
        let toolbarW     = sz.width
        let yAbove       = rectInView.minY - toolbarH - 10
        let ySafe        = max(view.safeAreaInsets.top + 8, yAbove)
        let xCenter      = rectInView.midX
        let xClamped     = max(8, min(view.bounds.width - toolbarW - 8, xCenter - toolbarW / 2))

        toolbar.frame    = CGRect(x: xClamped, y: ySafe, width: toolbarW, height: toolbarH)
        toolbar.isHidden = false
        view.bringSubviewToFront(toolbar)
    }

    // MARK: - Commit annotation

    private func commitAnnotation(tool: AnnotationTool, range: NSRange) {
        let ns  = (textView.text ?? "") as NSString
        let len = ns.length
        guard range.location != NSNotFound, range.length > 0,
              range.location + range.length <= len else { return }

        let selected    = ns.substring(with: range)
        let prefixStart = max(0, range.location - 40)
        let prefix      = ns.substring(with: NSRange(location: prefixStart,
                                                      length: range.location - prefixStart))
        let suffixEnd   = min(len, range.location + range.length + 40)
        let suffix      = ns.substring(with: NSRange(location: range.location + range.length,
                                                      length: suffixEnd - range.location - range.length))
        let position    = len > 0 ? Double(range.location) / Double(len) : 0.0

        let annotation = Annotation(
            id:           newId(),
            selectedText: selected,
            prefix:       prefix,
            suffix:       suffix,
            tool:         tool,
            timestamp:    Date(),
            position:     position
        )
        if tool == .inkAnnotation {
            onInkAnnotationRequested?(annotation)
        } else {
            onAnnotationCreated?(annotation)
            if tool == .comment { onAnnotationTapped?(annotation) }
        }
        textView.selectedTextRange = nil
    }

    // MARK: - Tap an annotation → action sheet

    @objc private func handleTap(_ gesture: UITapGestureRecognizer) {
        guard textView.selectedRange.length == 0 else { return }
        let point = gesture.location(in: textView)
        guard let annotation = annotationAt(point) else { return }
        presentActions(for: annotation, at: point)
    }

    private func annotationAt(_ point: CGPoint) -> Annotation? {
        guard let pos = textView.closestPosition(to: point) else { return nil }
        let caret   = textView.caretRect(for: pos)
        var charIdx = textView.offset(from: textView.beginningOfDocument, to: pos)
        if point.x < caret.minX { charIdx -= 1 }
        let len = (textView.text as NSString?)?.length ?? 0
        guard charIdx >= 0, charIdx < len else { return nil }
        guard let from  = textView.position(from: textView.beginningOfDocument, offset: charIdx),
              let to    = textView.position(from: from, offset: 1),
              let range = textView.textRange(from: from, to: to) else { return nil }
        let glyphRect = textView.firstRect(for: range)
        guard glyphRect.height > 0, glyphRect.insetBy(dx: -4, dy: -2).contains(point) else { return nil }
        return currentAnnotations.first { r in
            guard let span = r.span else { return false }
            return charIdx >= span.start && charIdx < span.end
        }?.annotation
    }

    private func presentActions(for annotation: Annotation, at point: CGPoint) {
        let raw     = annotation.selectedText
        let preview = raw.count > 80 ? String(raw.prefix(80)) + "…" : raw
        let sheet   = UIAlertController(title: nil, message: preview, preferredStyle: .actionSheet)
        if annotation.hasInk {
            sheet.addAction(UIAlertAction(title: "Edit Ink", style: .default) { [weak self] _ in
                self?.onEditInk?(annotation)
            })
        } else {
            sheet.addAction(UIAlertAction(title: "Comment", style: .default) { [weak self] _ in
                self?.onAnnotationTapped?(annotation)
            })
        }
        sheet.addAction(UIAlertAction(title: "Delete", style: .destructive) { [weak self] _ in
            self?.onDeleteAnnotation?(annotation.id)
        })
        sheet.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        if let pop = sheet.popoverPresentationController {
            pop.sourceView = textView
            pop.sourceRect = CGRect(origin: point, size: CGSize(width: 1, height: 1))
        }
        present(sheet, animated: true)
    }

    // MARK: - UIGestureRecognizerDelegate

    func gestureRecognizer(_ gr: UIGestureRecognizer,
                           shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool { true }

    // Let the edge-tap fire only when the annotation-tap wouldn't have matched.
    func gestureRecognizer(_ gr: UIGestureRecognizer,
                           shouldRequireFailureOf other: UIGestureRecognizer) -> Bool {
        gr === edgeTap && other === (textView.gestureRecognizers?.first { $0 is UITapGestureRecognizer } ?? gr)
    }
}

// MARK: - Floating annotation toolbar

/// A pill-shaped floating icon toolbar that appears above a text selection.
/// Each button maps to one AnnotationTool; tapping commits an annotation and hides the bar.
private final class FloatingSelectionToolbar: UIView {

    var onSelect: ((AnnotationTool) -> Void)?

    private static let items: [(AnnotationTool, String, UIColor, String)] = [
        (.highlight,       "highlighter",            .systemOrange, "Highlight"),
        (.underline,       "underline",              .systemBlue,   "Underline"),
        (.doubleUnderline, "underline",              .systemIndigo, "Double underline"),
        (.strikethrough,   "strikethrough",          .systemRed,    "Strikethrough"),
        (.comment,         "text.bubble",            .systemGreen,  "Comment"),
        (.bookmark,        "bookmark.fill",          .systemOrange, "Bookmark"),
        (.inkAnnotation,   "pencil.tip.crop.circle", .systemPurple, "Ink note"),
    ]

    private static let buttonSize: CGFloat = 44
    private static let separatorW: CGFloat = 0.5

    override init(frame: CGRect) {
        super.init(frame: frame)
        setup()
    }
    required init?(coder: NSCoder) { fatalError() }

    private func setup() {
        backgroundColor     = UIColor.systemBackground
        layer.cornerRadius  = Self.buttonSize / 2
        layer.shadowColor   = UIColor.black.cgColor
        layer.shadowOpacity = 0.18
        layer.shadowRadius  = 8
        layer.shadowOffset  = CGSize(width: 0, height: 2)

        let stack = UIStackView()
        stack.axis      = .horizontal
        stack.alignment = .center
        stack.spacing   = 0
        stack.translatesAutoresizingMaskIntoConstraints = false
        addSubview(stack)
        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: topAnchor),
            stack.bottomAnchor.constraint(equalTo: bottomAnchor),
            stack.leadingAnchor.constraint(equalTo: leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: trailingAnchor),
        ])

        let cfg = UIImage.SymbolConfiguration(pointSize: 16, weight: .medium)
        for (i, (tool, icon, color, label)) in Self.items.enumerated() {
            let btn = UIButton(type: .system)
            btn.setImage(UIImage(systemName: icon, withConfiguration: cfg), for: .normal)
            btn.tintColor = color
            btn.accessibilityLabel = label
            let t = tool
            btn.addAction(UIAction { [weak self] _ in self?.onSelect?(t) }, for: .touchUpInside)
            btn.translatesAutoresizingMaskIntoConstraints = false
            btn.widthAnchor.constraint(equalToConstant: Self.buttonSize).isActive = true
            btn.heightAnchor.constraint(equalToConstant: Self.buttonSize).isActive = true
            stack.addArrangedSubview(btn)

            if i < Self.items.count - 1 {
                let sep = UIView()
                sep.backgroundColor = UIColor.separator
                sep.translatesAutoresizingMaskIntoConstraints = false
                sep.widthAnchor.constraint(equalToConstant: Self.separatorW).isActive = true
                sep.heightAnchor.constraint(equalToConstant: 22).isActive = true
                stack.addArrangedSubview(sep)
            }
        }
    }

    override var intrinsicContentSize: CGSize {
        let n = CGFloat(Self.items.count)
        let seps = n - 1
        return CGSize(width: n * Self.buttonSize + seps * Self.separatorW,
                      height: Self.buttonSize)
    }
}

import UIKit

// MARK: - Pagination

/// Splits an attributed string into per-page character ranges that fit `pageSize`, using a
/// TextKit-1 layout pass. Page breaks fall on line boundaries, so each page's substring
/// re-renders identically in its own (same-width) text view.
enum TextPaginator {
    static func paginate(_ attributed: NSAttributedString, pageSize: CGSize) -> [NSRange] {
        guard pageSize.width > 10, pageSize.height > 10, attributed.length > 0 else {
            return [NSRange(location: 0, length: attributed.length)]
        }
        let storage       = NSTextStorage(attributedString: attributed)
        let layoutManager = NSLayoutManager()
        storage.addLayoutManager(layoutManager)

        var ranges: [NSRange] = []
        var safety = 0
        while ranges.reduce(0, { $0 + $1.length }) < attributed.length, safety < 100_000 {
            safety += 1
            let container = NSTextContainer(size: pageSize)
            container.lineFragmentPadding = 0
            layoutManager.addTextContainer(container)

            let glyphRange = layoutManager.glyphRange(for: container)
            guard glyphRange.length > 0 else { break }
            let charRange = layoutManager.characterRange(forGlyphRange: glyphRange, actualGlyphRange: nil)
            // Avoid zero-progress loops at the tail.
            if let last = ranges.last, charRange.location <= last.location { break }
            ranges.append(charRange)
        }
        if ranges.isEmpty { ranges = [NSRange(location: 0, length: attributed.length)] }
        return ranges
    }
}

// MARK: - Annotating text surface

/// A reusable text surface that owns one UITextView plus the full selection→tool-toolbar→commit
/// and tap-an-annotation pipeline, parameterised by `baseOffset` (the page's first character index
/// in the whole document) and `fullPlainText` so commits compute correct global prefix/suffix/position.
///
/// Used for BOTH the scroll-mode reader (scrollable, baseOffset 0, full text) and each curl/flip
/// page (non-scrolling, baseOffset = page start, page substring). Selection + annotation therefore
/// work identically in every mode.
final class AnnotatingTextSurface: UIViewController, UITextViewDelegate, UIGestureRecognizerDelegate {

    let textView: UITextView
    private let scrollable: Bool
    private let insets: UIEdgeInsets

    /// Page's first char index in the full document (0 for scroll mode).
    var baseOffset: Int = 0
    /// Index of this page within the paginated document (used by the page-view datasource).
    var pageNumber: Int = 0
    /// The whole document's plain text — anchoring prefix/suffix/position are computed against this.
    var fullPlainText: NSString = ""
    var annotationsProvider: () -> [ResolvedAnnotation] = { [] }

    var onAnnotationCreated:      ((Annotation) -> Void)?
    var onAnnotationTapped:       ((Annotation) -> Void)?
    var onDeleteAnnotation:       ((String) -> Void)?
    var onInkAnnotationRequested: ((Annotation) -> Void)?
    var onEditInk:                ((Annotation) -> Void)?
    /// Fired when this surface starts a selection — lets the page clear the OTHER columns' selections
    /// so only one column shows the floating bar at a time.
    var onBeganSelecting:         (() -> Void)?

    private var selectionToolbar: FloatingSelectionToolbar?

    /// Clears this surface's selection and hides its floating bar (called on sibling columns).
    func clearSelectionUI() {
        selectionToolbar?.isHidden = true
        if textView.selectedTextRange != nil { textView.selectedTextRange = nil }
    }

    init(scrollable: Bool, insets: UIEdgeInsets) {
        self.scrollable = scrollable
        self.insets     = insets
        // Scroll mode uses TextKit 2 (matches the original reader); pages use TextKit 1 so their
        // line breaking matches the TextKit-1 paginator exactly.
        self.textView   = UITextView(usingTextLayoutManager: scrollable)
        super.init(nibName: nil, bundle: nil)
    }
    required init?(coder: NSCoder) { fatalError() }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor          = AppTheme.warmPaperUI
        textView.isEditable           = false
        textView.isSelectable         = true
        textView.delegate             = self
        textView.backgroundColor      = AppTheme.warmPaperUI
        textView.textContainerInset   = insets
        // Match TextPaginator (which uses lineFragmentPadding = 0) so per-page line breaking is
        // identical to the paginator's — otherwise columns wrap extra lines and clip the overflow.
        textView.textContainer.lineFragmentPadding = 0
        textView.isScrollEnabled      = scrollable
        textView.alwaysBounceVertical = scrollable
        textView.showsVerticalScrollIndicator = scrollable
        textView.isFindInteractionEnabled      = scrollable
        textView.contentInsetAdjustmentBehavior = .always
        textView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(textView)
        // Pages pin to the safe area; the scroll surface fills the view (it manages its own insets).
        if scrollable {
            NSLayoutConstraint.activate([
                textView.topAnchor.constraint(equalTo: view.topAnchor),
                textView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
                textView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
                textView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            ])
        } else {
            let guide = view.safeAreaLayoutGuide
            NSLayoutConstraint.activate([
                textView.topAnchor.constraint(equalTo: guide.topAnchor),
                textView.bottomAnchor.constraint(equalTo: guide.bottomAnchor),
                textView.leadingAnchor.constraint(equalTo: guide.leadingAnchor),
                textView.trailingAnchor.constraint(equalTo: guide.trailingAnchor),
            ])
        }

        let tap = UITapGestureRecognizer(target: self, action: #selector(handleTap(_:)))
        tap.delegate = self
        tap.cancelsTouchesInView = false
        for gr in textView.gestureRecognizers ?? [] {
            if let dbl = gr as? UITapGestureRecognizer, dbl.numberOfTapsRequired == 2 {
                tap.require(toFail: dbl)
            }
        }
        textView.addGestureRecognizer(tap)

        // Apple Pencil: a pencil drag selects text immediately (highlighter feel) — no long-press.
        // Pencil-only so it never competes with finger scroll / page-curl.
        let pencilPan = UIPanGestureRecognizer(target: self, action: #selector(handlePencilPan(_:)))
        pencilPan.allowedTouchTypes = [NSNumber(value: UITouch.TouchType.pencil.rawValue)]
        pencilPan.delegate = self
        textView.addGestureRecognizer(pencilPan)
        // Finger scrolls; the pencil is reserved for selection (above), so the text view's own
        // scroll pan must ignore pencil touches — otherwise a pencil drag scrolls instead of selecting.
        textView.panGestureRecognizer.allowedTouchTypes = [NSNumber(value: UITouch.TouchType.direct.rawValue)]
    }

    // MARK: Pencil drag → select

    private var pencilSelectionStart: UITextPosition?

    @objc private func handlePencilPan(_ gr: UIPanGestureRecognizer) {
        let point = gr.location(in: textView)
        switch gr.state {
        case .began:
            // The text view's selection interaction is inert until it's first responder — that's why
            // pencil-select only worked after a finger selection "woke" it. Activate it ourselves.
            if !textView.isFirstResponder { textView.becomeFirstResponder() }
            pencilSelectionStart = textView.closestPosition(to: point)
            selectionToolbar?.isHidden = true
        case .changed:
            guard let start = pencilSelectionStart,
                  let end   = textView.closestPosition(to: point),
                  let range = textView.textRange(from: start, to: end) else { return }
            textView.selectedTextRange = range
        case .ended, .cancelled, .failed:
            pencilSelectionStart = nil
        default:
            break
        }
    }

    func setAttributed(_ attributed: NSAttributedString) {
        textView.attributedText = attributed
    }

    func presentFind() {
        textView.findInteraction?.presentFindNavigator(showingReplace: false)
    }

    // MARK: Selection → floating tool toolbar

    func textViewDidChangeSelection(_ textView: UITextView) {
        if textView.selectedRange.length > 0 {
            onBeganSelecting?()   // clear other columns' selections so only one bar shows
            showAnnotationToolbar(near: textView.selectedRange)
        } else {
            selectionToolbar?.isHidden = true
        }
    }

    func textView(_ textView: UITextView, editMenuForTextIn range: NSRange,
                  suggestedActions: [UIMenuElement]) -> UIMenu? {
        // Suppress the system edit menu entirely — the floating annotation bar is the only
        // selection UI (it carries Copy + the annotation tools).
        UIMenu(children: [])
    }

    private func showAnnotationToolbar(near range: NSRange) {
        let toolbar: FloatingSelectionToolbar
        if let existing = selectionToolbar {
            toolbar = existing
        } else {
            let t = FloatingSelectionToolbar()
            t.onSelect = { [weak self] tool in
                guard let self else { return }
                self.commitAnnotation(tool: tool, localRange: self.textView.selectedRange)
                self.selectionToolbar?.isHidden = true
            }
            t.onCopy = { [weak self] in self?.copySelection() }
            view.addSubview(t)
            selectionToolbar = t
            toolbar = t
        }

        guard let from = textView.position(from: textView.beginningOfDocument, offset: range.location),
              let to   = textView.position(from: from, offset: range.length),
              let tr   = textView.textRange(from: from, to: to) else {
            toolbar.isHidden = true; return
        }
        let rectInView = textView.convert(textView.firstRect(for: tr), to: view)
        let sz         = toolbar.intrinsicContentSize
        let yAbove     = rectInView.minY - sz.height - 10
        let ySafe      = max(view.safeAreaInsets.top + 8, yAbove)
        let xClamped   = max(8, min(view.bounds.width - sz.width - 8, rectInView.midX - sz.width / 2))
        toolbar.frame  = CGRect(x: xClamped, y: ySafe, width: sz.width, height: sz.height)
        toolbar.isHidden = false
        view.bringSubviewToFront(toolbar)
    }

    private func copySelection() {
        if let r = textView.selectedTextRange, let text = textView.text(in: r), !text.isEmpty {
            UIPasteboard.general.string = text
        }
        selectionToolbar?.isHidden = true
        textView.selectedTextRange = nil
    }

    // MARK: Commit (offset-aware)

    private func commitAnnotation(tool: AnnotationTool, localRange: NSRange) {
        let pageNS = (textView.text ?? "") as NSString
        guard localRange.location != NSNotFound, localRange.length > 0,
              localRange.location + localRange.length <= pageNS.length else { return }

        let selected  = pageNS.substring(with: localRange)
        let globalLoc = baseOffset + localRange.location
        let globalEnd = globalLoc + localRange.length
        let full      = fullPlainText
        let len       = full.length
        guard globalEnd <= len else { return }

        let prefixStart = max(0, globalLoc - 40)
        let prefix      = full.substring(with: NSRange(location: prefixStart, length: globalLoc - prefixStart))
        let suffixEnd   = min(len, globalEnd + 40)
        let suffix      = full.substring(with: NSRange(location: globalEnd, length: suffixEnd - globalEnd))
        let position    = len > 0 ? Double(globalLoc) / Double(len) : 0.0

        let annotation = Annotation(
            id: newId(), selectedText: selected, prefix: prefix, suffix: suffix,
            tool: tool, timestamp: Date(), position: position
        )
        if tool == .inkAnnotation {
            onInkAnnotationRequested?(annotation)
        } else {
            onAnnotationCreated?(annotation)
            if tool == .comment { onAnnotationTapped?(annotation) }
        }
        textView.selectedTextRange = nil
    }

    // MARK: Tap an annotation (offset-aware)

    @objc private func handleTap(_ gesture: UITapGestureRecognizer) {
        guard textView.selectedRange.length == 0 else { return }
        let point = gesture.location(in: textView)
        guard let annotation = annotationAt(point) else { return }
        presentActions(for: annotation, at: point)
    }

    private func annotationAt(_ point: CGPoint) -> Annotation? {
        guard let pos = textView.closestPosition(to: point) else { return nil }
        let caret    = textView.caretRect(for: pos)
        var localIdx = textView.offset(from: textView.beginningOfDocument, to: pos)
        if point.x < caret.minX { localIdx -= 1 }
        let pageLen = (textView.text as NSString?)?.length ?? 0
        guard localIdx >= 0, localIdx < pageLen else { return nil }
        guard let from  = textView.position(from: textView.beginningOfDocument, offset: localIdx),
              let to    = textView.position(from: from, offset: 1),
              let range = textView.textRange(from: from, to: to) else { return nil }
        let glyphRect = textView.firstRect(for: range)
        guard glyphRect.height > 0, glyphRect.insetBy(dx: -4, dy: -2).contains(point) else { return nil }
        let globalIdx = baseOffset + localIdx
        return annotationsProvider().first { r in
            guard let span = r.span else { return false }
            return globalIdx >= span.start && globalIdx < span.end
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

    func gestureRecognizer(_ gr: UIGestureRecognizer,
                           shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool { true }
}

// MARK: - Page (1–2 columns)

/// One page of the paginated reader: a horizontal stack of 1 (compact/iPhone) or 2 (wide/iPad)
/// `AnnotatingTextSurface` columns. Each column owns its own selection/annotation pipeline and
/// `baseOffset`, so annotating works in any column. Selection does NOT span the column gap.
final class ReaderPageViewController: UIViewController {
    let pageNumber: Int
    let columns: [AnnotatingTextSurface]
    private let padding: UIEdgeInsets
    private let columnGap: CGFloat

    init(pageNumber: Int, columns: [AnnotatingTextSurface], padding: UIEdgeInsets, gap: CGFloat) {
        self.pageNumber = pageNumber
        self.columns    = columns
        self.padding    = padding
        self.columnGap  = gap
        super.init(nibName: nil, bundle: nil)
    }
    required init?(coder: NSCoder) { fatalError() }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = AppTheme.warmPaperUI

        let stack = UIStackView()
        stack.axis         = .horizontal
        stack.distribution = .fillEqually
        stack.spacing      = columnGap
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)

        for col in columns {
            addChild(col)
            stack.addArrangedSubview(col.view)
            col.didMove(toParent: self)
            // Selecting in one column clears the others so only one floating bar shows at a time.
            col.onBeganSelecting = { [weak self, weak col] in
                guard let self else { return }
                for other in self.columns where other !== col { other.clearSelectionUI() }
            }
        }

        let g = view.safeAreaLayoutGuide
        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: g.topAnchor, constant: padding.top),
            stack.bottomAnchor.constraint(equalTo: g.bottomAnchor, constant: -padding.bottom),
            stack.leadingAnchor.constraint(equalTo: g.leadingAnchor, constant: padding.left),
            stack.trailingAnchor.constraint(equalTo: g.trailingAnchor, constant: -padding.right),
        ])
    }
}

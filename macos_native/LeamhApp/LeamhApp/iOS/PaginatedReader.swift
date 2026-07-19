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
/// UITextView that draws a small reading-position marker in the left inset band at a given
/// LOCAL char index — the iOS mirror of the macOS AnnotatingTextView marker (ReaderView.swift).
final class MarkerTextView: UITextView {
    var readingMarkerLocalIndex: Int? { didSet { setNeedsDisplay() } }
    var readingMarkerColor: UIColor = .secondaryLabel { didSet { setNeedsDisplay() } }

    override func draw(_ rect: CGRect) {
        super.draw(rect)
        guard let idx = readingMarkerLocalIndex else { return }
        let len = (text as NSString?)?.length ?? 0
        guard len > 0 else { return }
        let clamped = max(0, min(idx, len - 1))
        guard let from  = position(from: beginningOfDocument, offset: clamped),
              let to    = position(from: from, offset: 1),
              let range = textRange(from: from, to: to) else { return }
        let lineRect = firstRect(for: range)
        guard lineRect.height > 0, lineRect.minX.isFinite else { return }
        guard let ctx = UIGraphicsGetCurrentContext() else { return }
        ctx.saveGState()
        ctx.resetClip()   // UITextView clips to the text container; paint the margin too
        let inset = textContainerInset
        // Soft full-width band behind the reading line.
        let bandX = inset.left - 4
        let bandW = bounds.width - inset.left - inset.right + 8
        let band = CGRect(x: bandX, y: lineRect.minY, width: bandW, height: lineRect.height)
        readingMarkerColor.withAlphaComponent(0.18).setFill()
        UIBezierPath(roundedRect: band, cornerRadius: 3).fill()
        // Margin indicator: a filled triangle pointer in the left margin, on the line's midline.
        let cy = lineRect.midY
        let back = max(2, inset.left - 17)
        let tip  = back + 9
        let hh: CGFloat = 6
        readingMarkerColor.withAlphaComponent(0.9).setFill()
        let ptr = UIBezierPath()
        ptr.move(to: CGPoint(x: back, y: cy - hh))
        ptr.addLine(to: CGPoint(x: back, y: cy + hh))
        ptr.addLine(to: CGPoint(x: tip,  y: cy))
        ptr.close()
        ptr.fill()
        ctx.restoreGState()
    }
}

final class AnnotatingTextSurface: UIViewController, UITextViewDelegate, UIGestureRecognizerDelegate {

    let textView: MarkerTextView
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
    /// Returns the currently locked tool (from the store), or nil. When non-nil, a completed selection
    /// auto-annotates with it instead of showing the floating tool bar (mirrors macOS).
    var lockedToolProvider:       (() -> AnnotationTool?)?
    /// Locks (or, with nil, unlocks) a tool in the store — set from the floating bar's "Lock Tool".
    var onLockTool:               ((AnnotationTool?) -> Void)?
    /// Fired when this surface starts a selection — lets the page clear the OTHER columns' selections
    /// so only one column shows the floating bar at a time.
    var onBeganSelecting:         (() -> Void)?
    /// Fired with a 0.0–1.0 plain-text fraction when the reader single-taps empty text to drop a
    /// reading-position marker — bubbled up to the store, which persists it into the .docx.
    var onReadingPositionPicked:  ((Double) -> Void)?

    /// Marker colour (from the paper theme). Forwarded to the marker-drawing text view.
    var readingMarkerColor: UIColor = .secondaryLabel {
        didSet { textView.readingMarkerColor = readingMarkerColor }
    }

    /// Sets (or clears, with nil) the marker glyph on this surface at a LOCAL char index.
    func setReadingMarkerLocalIndex(_ idx: Int?) { textView.readingMarkerLocalIndex = idx }

    /// Gesture mapping (from the store). false: single-tap marks, double-tap edits an annotation.
    /// true: single-tap edits an annotation, double-tap marks.
    var markerOnDoubleClick = false

    private var selectionToolbar: FloatingSelectionToolbar?
    /// Pending auto-commit for a locked tool — rescheduled on every selection change so it fires only
    /// once the selection settles (a drag posts many selection-changed callbacks).
    private var pendingLockedCommit: DispatchWorkItem?

    /// Clears this surface's selection and hides its floating bar (called on sibling columns).
    func clearSelectionUI() {
        pendingLockedCommit?.cancel(); pendingLockedCommit = nil
        selectionToolbar?.isHidden = true
        if textView.selectedTextRange != nil { textView.selectedTextRange = nil }
    }

    init(scrollable: Bool, insets: UIEdgeInsets) {
        self.scrollable = scrollable
        self.insets     = insets
        // Scroll mode uses TextKit 2 (matches the original reader); pages use TextKit 1 so their
        // line breaking matches the TextKit-1 paginator exactly.
        self.textView   = MarkerTextView(usingTextLayoutManager: scrollable)
        super.init(nibName: nil, bundle: nil)
    }
    required init?(coder: NSCoder) { fatalError() }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor          = AppTheme.currentTheme.uiPaper
        textView.isEditable           = false
        textView.isSelectable         = true
        textView.delegate             = self
        textView.backgroundColor      = AppTheme.currentTheme.uiPaper
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

        let doubleTap = UITapGestureRecognizer(target: self, action: #selector(handleDoubleTap(_:)))
        doubleTap.numberOfTapsRequired = 2
        doubleTap.delegate = self
        doubleTap.cancelsTouchesInView = false
        textView.addGestureRecognizer(doubleTap)

        let tap = UITapGestureRecognizer(target: self, action: #selector(handleTap(_:)))
        tap.delegate = self
        tap.cancelsTouchesInView = false
        tap.require(toFail: doubleTap)
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
        let sel = textView.selectedRange
        if sel.length > 0 {
            onBeganSelecting?()   // clear other columns' selections so only one bar shows
            if let locked = lockedToolProvider?() {
                // A tool is locked: skip the floating bar and auto-annotate once the selection settles.
                selectionToolbar?.isHidden = true
                scheduleLockedCommit(locked)
            } else {
                showAnnotationToolbar(near: sel)
            }
        } else {
            pendingLockedCommit?.cancel(); pendingLockedCommit = nil
            selectionToolbar?.isHidden = true
        }
    }

    /// Debounced auto-commit of a locked tool — fires ~0.35s after the selection stops changing,
    /// so a drag-select commits once on release rather than on every intermediate selection.
    private func scheduleLockedCommit(_ tool: AnnotationTool) {
        pendingLockedCommit?.cancel()
        let work = DispatchWorkItem { [weak self] in
            guard let self else { return }
            let r = self.textView.selectedRange
            guard r.length > 0 else { return }
            self.commitAnnotation(tool: tool, localRange: r)   // also clears the selection
        }
        pendingLockedCommit = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.35, execute: work)
    }

    /// Locks the tool in the store AND applies it once to the current selection (the "Lock Tool"
    /// flyout action). Subsequent selections then auto-annotate via `scheduleLockedCommit`.
    private func lockAndApply(_ tool: AnnotationTool) {
        onLockTool?(tool)
        commitAnnotation(tool: tool, localRange: textView.selectedRange)
        selectionToolbar?.isHidden = true
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
            t.onLock = { [weak self] tool in self?.lockAndApply(tool) }
            t.onCopy = { [weak self] in self?.copySelection() }
            view.addSubview(t)
            selectionToolbar = t
            toolbar = t
        }

        let sz = toolbar.intrinsicContentSize

        // On iPhone (compact width) the reader is narrow and selection handles crowd a
        // follow-the-selection bar, so pin the bar to the bottom-centre instead. On iPad
        // (regular width) keep it floating just above the selection.
        if traitCollection.horizontalSizeClass == .compact {
            let x = (view.bounds.width - sz.width) / 2
            let y = view.bounds.height - view.safeAreaInsets.bottom - sz.height - 12
            toolbar.frame = CGRect(x: max(8, x), y: y, width: sz.width, height: sz.height)
            toolbar.isHidden = false
            view.bringSubviewToFront(toolbar)
            return
        }

        guard let from = textView.position(from: textView.beginningOfDocument, offset: range.location),
              let to   = textView.position(from: from, offset: range.length),
              let tr   = textView.textRange(from: from, to: to) else {
            toolbar.isHidden = true; return
        }
        let rectInView = textView.convert(textView.firstRect(for: tr), to: view)
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

        var globalLoc = baseOffset + localRange.location
        var globalEnd = globalLoc + localRange.length
        let full      = fullPlainText
        let len       = full.length
        guard globalEnd <= len else { return }

        // "Highlight Paragraph": expand to the whole enclosing paragraph (bounded by
        // newlines, across the FULL document text — not just this page's slice — so a
        // paragraph split across a page boundary still expands correctly).
        if tool == .blockquote {
            var s = globalLoc
            while s > 0, full.character(at: s - 1) != 0x0A { s -= 1 }
            var e = globalEnd
            while e < len, full.character(at: e) != 0x0A { e += 1 }
            globalLoc = s; globalEnd = e
        }
        let selected = full.substring(with: NSRange(location: globalLoc, length: globalEnd - globalLoc))

        let prefixStart = max(0, globalLoc - 40)
        let prefix      = full.substring(with: NSRange(location: prefixStart, length: globalLoc - prefixStart))
        let suffixEnd   = min(len, globalEnd + 40)
        let suffix      = full.substring(with: NSRange(location: globalEnd, length: suffixEnd - globalEnd))
        let position    = len > 0 ? Double(globalLoc) / Double(len) : 0.0

        let annotation = Annotation(
            id: newId(), selectedText: selected, prefix: prefix, suffix: suffix,
            tool: tool, timestamp: Date(), position: position,
            paragraph: PlainTextMapper.paragraphIndex(full as String, globalLoc)
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
        if markerOnDoubleClick {
            // Single-tap edits an annotation; marking is on double-tap.
            if let annotation = annotationAt(point) { presentActions(for: annotation, at: point) }
        } else {
            // Single-tap drops a marker (even on annotations); editing is on double-tap.
            dropMarker(at: point)
        }
    }

    @objc private func handleDoubleTap(_ gesture: UITapGestureRecognizer) {
        let point = gesture.location(in: textView)
        if markerOnDoubleClick {
            dropMarker(at: point)
        } else if let annotation = annotationAt(point) {
            presentActions(for: annotation, at: point)
        }
        // Cancel any word selection the double-tap may have started.
        textView.selectedTextRange = nil
    }

    /// Drops a reading-position marker at the tapped character and reports its global fraction.
    private func dropMarker(at point: CGPoint) {
        guard let localIdx = localCharIndex(at: point) else { return }
        let global = baseOffset + localIdx
        let len = fullPlainText.length
        guard len > 0 else { return }
        setReadingMarkerLocalIndex(localIdx)              // immediate feedback on this surface
        onReadingPositionPicked?(Double(global) / Double(len))
    }

    /// LOCAL char index nearest a point in the text view, or nil if outside the text.
    private func localCharIndex(at point: CGPoint) -> Int? {
        guard let pos = textView.closestPosition(to: point) else { return nil }
        let caret    = textView.caretRect(for: pos)
        var localIdx = textView.offset(from: textView.beginningOfDocument, to: pos)
        if point.x < caret.minX { localIdx -= 1 }
        let pageLen = (textView.text as NSString?)?.length ?? 0
        guard localIdx >= 0, localIdx < pageLen else { return nil }
        return localIdx
    }

    private func annotationAt(_ point: CGPoint) -> Annotation? {
        guard let localIdx = localCharIndex(at: point) else { return nil }
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
        view.backgroundColor = AppTheme.currentTheme.uiPaper

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

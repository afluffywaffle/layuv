import SwiftUI
import AppKit

// MARK: - Page coordinator

/// Bridges page-navigation state between ReaderViewController (AppKit) and SwiftUI toolbar.
final class ReaderCoordinator: NSObject, ObservableObject {
    @Published var currentPage: Int = 0
    @Published var pageCount:   Int = 1

    weak var viewController: ReaderViewController?

    func nextPage()     { viewController?.nextPage();     sync() }
    func previousPage() { viewController?.previousPage(); sync() }

    func find()                          { viewController?.presentFind() }
    func scrollTo(annotationId: String)  { viewController?.scrollToAnnotation(id: annotationId) }

    func sync() {
        currentPage = viewController?.currentPage ?? 0
        pageCount   = max(1, viewController?.pageCount ?? 1)
    }
}

// MARK: - SwiftUI bridge

struct ReaderView: NSViewControllerRepresentable {
    @EnvironmentObject var store: DocumentStore
    let coordinator: ReaderCoordinator

    func makeCoordinator() -> ReaderCoordinator { coordinator }

    func makeNSViewController(context: Context) -> ReaderViewController {
        let vc = ReaderViewController()
        coordinator.viewController = vc

        vc.onAnnotationCreated = { [weak store] annotation in
            store?.addAnnotation(annotation)
        }
        // Opens the edit sheet — stored on DocumentStore so VC can trigger it.
        vc.onAnnotationTapped = { [weak store] annotation in
            Task { @MainActor in store?.editingAnnotation = annotation }
        }
        return vc
    }

    func updateNSViewController(_ vc: ReaderViewController, context: Context) {
        guard let doc = store.document else { return }
        vc.update(document: doc, annotations: store.annotations, bodySize: store.bodyTextSize.points)
        DispatchQueue.main.async { coordinator.sync() }
    }
}

// MARK: - NSTextView subclass for annotation tap detection

final class AnnotatingTextView: NSTextView {
    /// Returns the Annotation whose span covers the given UTF-16 index, or nil.
    var annotationAtCharIndex: ((Int) -> Annotation?)?
    /// Called when the user clicks on an existing annotation.
    var onAnnotationTapped: ((Annotation) -> Void)?

    override func mouseDown(with event: NSEvent) {
        let pointInView = convert(event.locationInWindow, from: nil)
        if let annotation = annotationAt(pointInView) {
            onAnnotationTapped?(annotation)
            // Don't call super — prevents starting a selection on annotated text.
            return
        }
        super.mouseDown(with: event)
    }

    /// Finds an annotation under the given point (in the text view's coordinate system).
    ///
    /// NSTextView.characterIndex(for:) doesn't work in TextKit 2 because it uses the
    /// old NSLayoutManager path. NSLayoutManager.characterIndex(for:in:fraction:) is the
    /// correct API — it takes text-container coordinates, not text-view coordinates.
    private func annotationAt(_ pointInView: NSPoint) -> Annotation? {
        guard let lookup = annotationAtCharIndex else { return nil }

        // Primary path: NSLayoutManager works in TK2 via the compat layer Apple provides.
        if let lm = layoutManager, let tc = textContainer {
            let inset = textContainerInset
            let ptInContainer = NSPoint(x: pointInView.x - inset.width,
                                        y: pointInView.y - inset.height)
            let idx = lm.characterIndex(for: ptInContainer, in: tc,
                                         fractionOfDistanceBetweenInsertionPoints: nil)
            let len = (string as NSString).length
            if idx < len { return lookup(idx) }
        }

        // Fallback: NSTextView's own method (may return NSNotFound in some TK2 builds).
        let idx = characterIndex(for: pointInView)
        return idx != NSNotFound ? lookup(idx) : nil
    }
}

// MARK: - TextKit 2 view controller

final class ReaderViewController: NSViewController {
    static let pageWidth:  CGFloat = 680
    static let pageHeight: CGFloat = 860
    static let margin:     CGFloat = 56

    private var textView:   AnnotatingTextView!
    private var scrollView: NSScrollView!

    private(set) var currentPage = 0
    private var currentAnnotations: [ResolvedAnnotation] = []

    var onAnnotationCreated: ((Annotation) -> Void)?
    var onAnnotationTapped:  ((Annotation) -> Void)?

    private var toolPopover:     NSPopover?
    private var pendingRange     = NSRange(location: NSNotFound, length: 0)
    private var lastShownRange   = NSRange(location: NSNotFound, length: 0)

    // MARK: View lifecycle

    override func loadView() {
        scrollView = NSScrollView()
        scrollView.backgroundColor       = AppTheme.warmPaperNS
        scrollView.hasVerticalScroller   = true
        scrollView.hasHorizontalScroller = false
        scrollView.autohidesScrollers    = true

        textView = AnnotatingTextView(frame: NSRect(x: 0, y: 0,
                                                    width: Self.pageWidth,
                                                    height: Self.pageHeight))
        textView.isEditable            = false
        textView.isSelectable          = true
        textView.usesFindBar           = true
        textView.isIncrementalSearchingEnabled = true
        textView.backgroundColor       = AppTheme.warmPaperNS
        textView.textContainerInset    = NSSize(width: Self.margin, height: 40)
        textView.autoresizingMask      = [.width]
        textView.textContainer?.widthTracksTextView  = true
        textView.textContainer?.heightTracksTextView = false
        textView.textContainer?.size = NSSize(
            width:  Self.pageWidth - 2 * Self.margin,
            height: .greatestFiniteMagnitude
        )

        // Annotation tap: look up by char index in currentAnnotations at call time.
        textView.annotationAtCharIndex = { [weak self] idx in
            self?.currentAnnotations.first {
                guard let span = $0.span else { return false }
                return idx >= span.start && idx < span.end
            }?.annotation
        }
        textView.onAnnotationTapped = { [weak self] annotation in
            self?.onAnnotationTapped?(annotation)
        }

        scrollView.documentView = textView
        view = scrollView
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(selectionChanged),
            name: NSTextView.didChangeSelectionNotification,
            object: textView
        )
    }

    deinit { NotificationCenter.default.removeObserver(self) }

    // MARK: Content update

    func update(document: LoadedDocument, annotations: [ResolvedAnnotation], bodySize: CGFloat) {
        currentAnnotations = annotations

        let attributed = buildAttributedString(from: document, bodySize: bodySize)
        if let cs = textView.textContentStorage {
            cs.performEditingTransaction {
                cs.textStorage?.setAttributedString(attributed)
            }
        } else {
            textView.textStorage?.setAttributedString(attributed)
        }

        applyHighlights(annotations)
    }

    private func buildAttributedString(from doc: LoadedDocument, bodySize: CGFloat) -> NSAttributedString {
        let str = NSMutableAttributedString(string: doc.plainText, attributes: [
            .font:            AppTheme.nsBody(size: bodySize),
            .foregroundColor: NSColor.labelColor,
        ])
        let utf16len = doc.plainText.utf16.count
        for span in doc.formatSpans {
            let len = span.end - span.start
            guard len > 0, span.start >= 0, span.start + len <= utf16len else { continue }
            let r = NSRange(location: span.start, length: len)
            if span.bold && span.italic {
                str.addAttribute(.font, value: AppTheme.nsBodyItalic(size: bodySize), range: r)
            } else if span.bold {
                str.addAttribute(.font, value: AppTheme.nsBodyBold(size: bodySize), range: r)
            } else if span.italic {
                str.addAttribute(.font, value: AppTheme.nsBodyItalic(size: bodySize), range: r)
            }
        }
        return str
    }

    /// Applies tool-specific visual styles. macOS has no e-ink constraints —
    /// use standard affordances: yellow fill, solid underlines, strikethrough, etc.
    private func applyHighlights(_ annotations: [ResolvedAnnotation]) {
        guard let storage = textView.textStorage else { return }
        let full = NSRange(location: 0, length: storage.length)
        storage.removeAttribute(.backgroundColor,    range: full)
        storage.removeAttribute(.underlineStyle,     range: full)
        storage.removeAttribute(.underlineColor,     range: full)
        storage.removeAttribute(.strikethroughStyle, range: full)
        storage.removeAttribute(.strikethroughColor, range: full)

        for resolved in annotations {
            guard let span = resolved.span else { continue }
            let len = span.end - span.start
            guard len > 0, span.start + len <= storage.length else { continue }
            let r = NSRange(location: span.start, length: len)

            switch resolved.annotation.tool {
            case .highlight:
                storage.addAttribute(.backgroundColor,
                                     value: NSColor.systemYellow.withAlphaComponent(0.45), range: r)

            case .underline:
                storage.addAttribute(.underlineStyle, value: NSUnderlineStyle.single.rawValue, range: r)
                storage.addAttribute(.underlineColor, value: NSColor.labelColor, range: r)

            case .doubleUnderline:
                storage.addAttribute(.underlineStyle, value: NSUnderlineStyle.double.rawValue, range: r)
                storage.addAttribute(.underlineColor, value: NSColor.labelColor, range: r)

            case .strikethrough:
                storage.addAttribute(.strikethroughStyle, value: NSUnderlineStyle.single.rawValue, range: r)
                storage.addAttribute(.strikethroughColor, value: NSColor.labelColor, range: r)

            case .wavyUnderline:
                // NSUnderlineStyle has no native wavy; thick+dash is the closest approximation.
                let style = NSUnderlineStyle.patternDash.rawValue | NSUnderlineStyle.thick.rawValue
                storage.addAttribute(.underlineStyle, value: style, range: r)
                storage.addAttribute(.underlineColor, value: NSColor.systemTeal, range: r)

            case .bookmark:
                storage.addAttribute(.backgroundColor,
                                     value: NSColor.systemOrange.withAlphaComponent(0.15), range: r)

            case .comment:
                // Thick dotted green underline + faint background tint — clearly visible.
                let style = NSUnderlineStyle.patternDot.rawValue | NSUnderlineStyle.thick.rawValue
                storage.addAttribute(.underlineStyle, value: style, range: r)
                storage.addAttribute(.underlineColor, value: NSColor.systemGreen, range: r)
                storage.addAttribute(.backgroundColor,
                                     value: NSColor.systemGreen.withAlphaComponent(0.1), range: r)

            case .inkAnnotation:
                // Ink is shown as an embedded image; no text decoration needed.
                break
            }
        }
    }

    // MARK: Page navigation

    var pageCount: Int {
        let h = textView.frame.height
        guard h > 0 else { return 1 }
        return max(1, Int(ceil(h / Self.pageHeight)))
    }

    func scrollToPage(_ page: Int) {
        let clamped = max(0, min(page, pageCount - 1))
        scrollView.documentView?.scroll(NSPoint(x: 0, y: CGFloat(clamped) * Self.pageHeight))
        currentPage = clamped
    }

    func nextPage()     { scrollToPage(currentPage + 1) }
    func previousPage() { scrollToPage(currentPage - 1) }

    // MARK: Find + scroll-to-annotation (driven from the sidebar panel)

    /// Presents the NSTextView find bar (Bookmarks/Find tab → reader find UI).
    func presentFind() {
        guard textView.window != nil else { return }
        textView.window?.makeFirstResponder(textView)
        let item = NSMenuItem()
        item.tag = Int(NSTextFinder.Action.showFindInterface.rawValue)
        textView.performTextFinderAction(item)
    }

    /// Scrolls the reader so the annotation's span is visible (Bookmarks tab → tap a row).
    func scrollToAnnotation(id: String) {
        guard let resolved = currentAnnotations.first(where: { $0.annotation.id == id }),
              let span = resolved.span, span.start >= 0 else { return }
        let length = max(1, min(span.end - span.start, 80))
        let clamped = min(span.start, max(0, (textView.string as NSString).length - 1))
        textView.scrollRangeToVisible(NSRange(location: clamped, length: length))
    }

    // MARK: Selection → annotation

    @objc private func selectionChanged() {
        let sel = textView.selectedRange()
        if sel.length > 0 {
            if sel.location != lastShownRange.location || sel.length != lastShownRange.length {
                lastShownRange = sel
                pendingRange   = sel
                showToolPopover(forRange: sel)
            }
        } else {
            if lastShownRange.length > 0 {
                lastShownRange = NSRange(location: NSNotFound, length: 0)
                toolPopover?.close()
            }
        }
    }

    private func showToolPopover(forRange range: NSRange) {
        guard let window = textView.window, range.length > 0 else { return }

        // TextKit 2's firstRect returns .zero for characters not yet laid out;
        // fall back to the current mouse position which is always correct after drag-select.
        var dummy = NSRange()
        let anchorLoc = NSRange(
            location: min(range.location + range.length - 1,
                          max(0, (textView.string as NSString).length - 1)),
            length: 1
        )
        var screenRect = textView.firstRect(forCharacterRange: anchorLoc, actualRange: &dummy)
        if screenRect.origin == .zero {
            screenRect = NSRect(origin: NSEvent.mouseLocation, size: CGSize(width: 1, height: 1))
        }
        let windowRect = window.convertFromScreen(screenRect)
        let viewRect   = textView.convert(windowRect, from: nil)

        if toolPopover == nil {
            let popover = NSPopover()
            popover.behavior = .transient
            popover.animates = false
            let pickerVC = NSHostingController(rootView: ToolPickerView { [weak self] tool in
                self?.commitAnnotation(tool: tool)
            })
            popover.contentViewController = pickerVC
            popover.contentSize = NSSize(width: 336, height: 62)
            toolPopover = popover
        }

        toolPopover?.show(relativeTo: viewRect, of: textView, preferredEdge: .minY)
    }

    private func commitAnnotation(tool: AnnotationTool) {
        toolPopover?.close()
        let range = pendingRange
        guard range.length > 0, range.location != NSNotFound else { return }

        let ns  = textView.string as NSString
        let len = ns.length
        guard range.location + range.length <= len else { return }

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

        // For comment annotations, open the edit sheet immediately so the user can add a note.
        if tool == .comment {
            onAnnotationTapped?(annotation)
        }

        textView.setSelectedRange(NSRange(location: range.location, length: 0))
    }
}

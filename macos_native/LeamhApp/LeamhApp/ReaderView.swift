import SwiftUI
import AppKit

// MARK: - Page coordinator

/// Bridges page-navigation state between ReaderViewController (AppKit) and SwiftUI toolbar.
/// Hold as @StateObject in ReaderScreen; pass into ReaderView so it acts as the Coordinator.
final class ReaderCoordinator: NSObject, ObservableObject {
    @Published var currentPage: Int = 0
    @Published var pageCount:   Int = 1

    weak var viewController: ReaderViewController?

    func nextPage() {
        viewController?.nextPage()
        sync()
    }

    func previousPage() {
        viewController?.previousPage()
        sync()
    }

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

        // Annotation creation callback — keep store reference weak to avoid retain cycle.
        vc.onAnnotationCreated = { [weak store] annotation in
            store?.addAnnotation(annotation)
        }
        return vc
    }

    func updateNSViewController(_ vc: ReaderViewController, context: Context) {
        guard let doc = store.document else { return }
        vc.update(document: doc, annotations: store.annotations)
        // Page count is layout-dependent; nudge after content loads.
        DispatchQueue.main.async { coordinator.sync() }
    }
}

// MARK: - TextKit 2 view controller

final class ReaderViewController: NSViewController {
    static let pageWidth:  CGFloat = 680
    static let pageHeight: CGFloat = 860
    static let margin:     CGFloat = 56

    private var textView:   NSTextView!
    private var scrollView: NSScrollView!

    // Page navigation
    private(set) var currentPage = 0

    // Annotation creation
    var onAnnotationCreated: ((Annotation) -> Void)?

    // Tool popover
    private var toolPopover: NSPopover?
    private var pendingRange = NSRange(location: NSNotFound, length: 0)
    private var lastShownRange = NSRange(location: NSNotFound, length: 0)

    // MARK: View lifecycle

    override func loadView() {
        scrollView = NSScrollView()
        scrollView.backgroundColor       = AppTheme.warmPaperNS
        scrollView.hasVerticalScroller   = true
        scrollView.hasHorizontalScroller = false
        scrollView.autohidesScrollers    = true

        textView = NSTextView(frame: NSRect(x: 0, y: 0,
                                            width: Self.pageWidth,
                                            height: Self.pageHeight))
        textView.isEditable            = false
        textView.isSelectable          = true
        textView.backgroundColor       = AppTheme.warmPaperNS
        textView.textContainerInset    = NSSize(width: Self.margin, height: 40)
        textView.autoresizingMask      = [.width]
        textView.textContainer?.widthTracksTextView  = true
        textView.textContainer?.heightTracksTextView = false
        textView.textContainer?.size = NSSize(
            width:  Self.pageWidth - 2 * Self.margin,
            height: .greatestFiniteMagnitude
        )

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

    override func viewDidLayout() {
        super.viewDidLayout()
        // Notify coordinator of updated page count after layout.
        let pc = pageCount
        let cp = currentPage
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            // Access coordinator through the representable's coordinator reference —
            // use the callback approach to avoid a direct coordinator dependency on VC.
            _ = (pc, cp) // triggers layout update via coordinator.sync() called by updateNSViewController
        }
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
    }

    // MARK: Content update

    func update(document: LoadedDocument, annotations: [ResolvedAnnotation]) {
        let attributed = buildAttributedString(from: document)

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
        scrollView.documentView?.scroll(NSPoint(x: 0, y: y))
        currentPage = clamped
    }

    func nextPage()     { scrollToPage(currentPage + 1) }
    func previousPage() { scrollToPage(currentPage - 1) }

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

        // Anchor popover at the end of selection.
        let anchorRange = NSRange(
            location: min(range.location + range.length - 1, max(0, (textView.string as NSString).length - 1)),
            length: 1
        )
        var dummy = NSRange()
        let screenRect = textView.firstRect(forCharacterRange: anchorRange, actualRange: &dummy)
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
            popover.contentSize = NSSize(width: 284, height: 62)
            toolPopover = popover
        }

        toolPopover?.show(relativeTo: viewRect, of: textView, preferredEdge: .minY)
    }

    private func commitAnnotation(tool: AnnotationTool) {
        toolPopover?.close()
        let range = pendingRange
        guard range.length > 0, range.location != NSNotFound else { return }

        let ns   = textView.string as NSString
        let len  = ns.length
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

        // Clear the text selection after creating annotation.
        textView.setSelectedRange(NSRange(location: range.location, length: 0))
    }
}

import SwiftUI
import AppKit

// MARK: - Navigation mode

/// macOS reader nav modes. Unlike iOS there is NO page-curl mode — desktop has no touch page turn
/// and the curl animation is e-ink-only baggage. `pageFlip` moves horizontally between discrete
/// page-screens with a plain horizontal SLIDE (no curl); two-column applies only in that paged mode.
/// (rawValue stays "screenFlip" so the persisted pref carries over; the label is "Page Flip".)
enum NavMode: String, CaseIterable {
    case scroll   = "scroll"
    case pageFlip = "screenFlip"

    var label: String {
        switch self {
        case .scroll:   return "Scroll"
        case .pageFlip: return "Page Flip"
        }
    }
    var icon: String {
        switch self {
        case .scroll:   return "scroll"
        case .pageFlip: return "rectangle.split.2x1"
        }
    }
}

// MARK: - Page coordinator

/// Bridges page-navigation state between ReaderViewController (AppKit) and SwiftUI toolbar.
final class ReaderCoordinator: NSObject, ObservableObject {
    @Published var currentPage: Int = 0
    @Published var pageCount:   Int = 1
    @Published var paged:       Bool = false

    weak var viewController: ReaderViewController?

    func nextPage()     { viewController?.nextPage();     sync() }
    func previousPage() { viewController?.previousPage(); sync() }

    func find()                          { viewController?.presentFind() }
    func scrollTo(annotationId: String)  { viewController?.scrollToAnnotation(id: annotationId) }
    func goToPage(_ page: Int)           { viewController?.goToPage(page); sync() }
    func scrollToCharOffset(_ offset: Int) { viewController?.scrollToCharOffset(offset); sync() }

    func sync() {
        currentPage = viewController?.currentPage ?? 0
        pageCount   = max(1, viewController?.pageCount ?? 1)
        paged       = viewController?.navMode == .pageFlip
    }
}

// MARK: - SwiftUI bridge

struct ReaderView: NSViewControllerRepresentable {
    @EnvironmentObject var store: DocumentStore
    let coordinator: ReaderCoordinator
    var navMode: NavMode = .scroll
    var twoColumn: Bool = true
    var theme: PaperTheme = .parchment
    var font: FontChoice = .serif
    var leftHanded: Bool = false

    func makeCoordinator() -> ReaderCoordinator { coordinator }

    func makeNSViewController(context: Context) -> ReaderViewController {
        let vc = ReaderViewController()
        coordinator.viewController = vc

        vc.onAnnotationCreated = { [weak store] annotation in
            store?.addAnnotation(annotation)
        }
        // Opens the edit sheet — stored on DocumentStore so VC can trigger it.
        vc.onAnnotationTapped = { [weak store] annotation in
            Task { @MainActor in store?.openAnnotation(annotation) }
        }
        vc.onInkAnnotationRequested = { [weak store] annotation in
            Task { @MainActor in store?.beginInkAnnotation(annotation) }
        }
        vc.onPageChanged = { [weak coordinator] in coordinator?.sync() }
        return vc
    }

    func updateNSViewController(_ vc: ReaderViewController, context: Context) {
        guard let doc = store.document else { return }
        // Keep the static (read by the ink canvas + attributed-string defaults) on the EFFECTIVE theme.
        AppTheme.currentTheme = theme
        AppTheme.current = font
        vc.leftHanded = leftHanded
        vc.update(document: doc, annotations: store.annotations,
                  bodySize: store.bodyTextSize.points,
                  navMode: navMode, twoColumn: twoColumn, theme: theme, font: font)
        DispatchQueue.main.async { coordinator.sync() }
    }
}

// MARK: - NSTextView subclass for annotation tap detection

final class AnnotatingTextView: NSTextView {
    /// Returns the Annotation whose span covers the given (LOCAL) UTF-16 index, or nil.
    var annotationAtCharIndex: ((Int) -> Annotation?)?
    /// Called when the user clicks on an existing annotation.
    var onAnnotationTapped: ((Annotation) -> Void)?
    /// Set on paged columns (not the scroll surface): forwards wheel/swipe to page-flip handling.
    var onScrollWheelForward: ((NSEvent) -> Void)?

    override func scrollWheel(with event: NSEvent) {
        if let fwd = onScrollWheelForward { fwd(event) } else { super.scrollWheel(with: event) }
    }

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

// MARK: - Reader view controller (scroll + screen-flip)

final class ReaderViewController: NSViewController {
    static let pageHeight: CGFloat = 860
    static let margin:     CGFloat = 56
    /// Top/bottom text inset. Generous at top so the first lines render BELOW the floating glass
    /// toolbar + the top fade, keeping the reading zone clear of the controls.
    static let vInset:     CGFloat = 72
    static let columnGap:  CGFloat = 48

    // Callbacks
    var onAnnotationCreated:      ((Annotation) -> Void)?
    var onAnnotationTapped:       ((Annotation) -> Void)?
    var onInkAnnotationRequested: ((Annotation) -> Void)?
    var onPageChanged:            (() -> Void)?

    // Mode
    private(set) var navMode:   NavMode = .scroll
    private var twoColumn = true

    // Scroll mode views
    private var scrollView: NSScrollView!
    private var scrollTextView: AnnotatingTextView!

    // Paged (screen-flip) mode views — a container holding 1–2 columns.
    private var pagedContainer: PagedContainerView!
    private var columns: [AnnotatingTextView] = []

    // Content
    private var fullAttributed = NSAttributedString(string: "")
    private var fullText: NSString = ""
    private var currentAnnotations: [ResolvedAnnotation] = []
    private var bodySize: CGFloat = AppTheme.bodySize
    private var theme: PaperTheme = .parchment
    private var font: FontChoice = .serif
    var leftHanded = false

    // Pagination (paged mode): one NSRange per column-page; a "screen" is 1 or 2 columns.
    private var pageRanges: [NSRange] = []
    private(set) var currentPage = 0   // screen index
    private var paginationDirty = true

    // Selection state (works in both modes; tracks which text view holds the selection)
    private weak var activeTextView: AnnotatingTextView?
    private var activeBaseOffset = 0
    private var toolPopover:   NSPopover?
    private var pendingRange   = NSRange(location: NSNotFound, length: 0) // GLOBAL range
    private var lastShownRange = NSRange(location: NSNotFound, length: 0)

    private var columnsPerScreen: Int { (navMode == .pageFlip && twoColumn) ? 2 : 1 }

    // MARK: View lifecycle

    override func loadView() {
        // Scroll mode
        scrollView = NSScrollView()
        scrollView.backgroundColor       = AppTheme.currentTheme.nsPaper
        scrollView.hasVerticalScroller   = true
        scrollView.hasHorizontalScroller = false
        scrollView.autohidesScrollers    = true

        scrollTextView = makeTextView()
        scrollTextView.autoresizingMask = [.width]
        scrollTextView.textContainer?.widthTracksTextView = true
        scrollView.documentView = scrollTextView

        // Paged mode container (hidden until screen-flip selected)
        pagedContainer = PagedContainerView()
        pagedContainer.wantsLayer = true   // enables the horizontal push (slide) transition on flip
        pagedContainer.backgroundColor = AppTheme.currentTheme.nsPaper
        pagedContainer.onEdgeFlip = { [weak self] forward in
            if forward { self?.nextPage() } else { self?.previousPage() }
        }
        pagedContainer.onScrollWheel = { [weak self] event in self?.handlePagedScroll(event) }
        pagedContainer.isHidden = true

        let root = NSView()
        root.addSubview(scrollView)
        root.addSubview(pagedContainer)
        scrollView.translatesAutoresizingMaskIntoConstraints = false
        pagedContainer.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            scrollView.topAnchor.constraint(equalTo: root.topAnchor),
            scrollView.bottomAnchor.constraint(equalTo: root.bottomAnchor),
            scrollView.leadingAnchor.constraint(equalTo: root.leadingAnchor),
            scrollView.trailingAnchor.constraint(equalTo: root.trailingAnchor),
            pagedContainer.topAnchor.constraint(equalTo: root.topAnchor),
            pagedContainer.bottomAnchor.constraint(equalTo: root.bottomAnchor),
            pagedContainer.leadingAnchor.constraint(equalTo: root.leadingAnchor),
            pagedContainer.trailingAnchor.constraint(equalTo: root.trailingAnchor),
        ])
        view = root
    }

    /// A fresh annotating text view configured for the reader (non-editable, warm paper).
    private func makeTextView() -> AnnotatingTextView {
        let tv = AnnotatingTextView(frame: NSRect(x: 0, y: 0, width: 600, height: Self.pageHeight))
        tv.isEditable               = false
        tv.isSelectable             = true
        tv.usesFindBar              = true
        tv.isIncrementalSearchingEnabled = true
        tv.backgroundColor          = AppTheme.currentTheme.nsPaper
        tv.textContainerInset       = NSSize(width: Self.margin, height: Self.vInset)
        tv.textContainer?.heightTracksTextView = false
        tv.textContainer?.size = NSSize(width: 600 - 2 * Self.margin, height: .greatestFiniteMagnitude)

        tv.annotationAtCharIndex = { [weak self, weak tv] localIdx in
            guard let self, let tv else { return nil }
            let global = self.baseOffset(of: tv) + localIdx
            return self.currentAnnotations.first {
                guard let span = $0.span else { return false }
                return global >= span.start && global < span.end
            }?.annotation
        }
        tv.onAnnotationTapped = { [weak self] annotation in
            self?.onAnnotationTapped?(annotation)
        }
        NotificationCenter.default.addObserver(
            self, selector: #selector(selectionChanged(_:)),
            name: NSTextView.didChangeSelectionNotification, object: tv)
        return tv
    }

    deinit { NotificationCenter.default.removeObserver(self) }

    override func viewDidLayout() {
        super.viewDidLayout()
        if navMode == .pageFlip {
            paginationDirty = true
            relayoutPaged()
        }
    }

    // MARK: Keyboard navigation

    private var keyMonitor: Any?

    override func viewDidAppear() {
        super.viewDidAppear()
        applyWindowBackground()   // window now exists — blend the toolbar strip with the paper
        guard keyMonitor == nil else { return }
        keyMonitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] event in
            guard let self else { return event }
            return self.handleKey(event) ? nil : event
        }
    }

    override func viewWillDisappear() {
        super.viewWillDisappear()
        if let m = keyMonitor { NSEvent.removeMonitor(m); keyMonitor = nil }
    }

    /// Returns true if the key was consumed as a navigation command. Skips when the reader isn't in
    /// the key window, or when an editable responder (the Find search field) is focused — so typing
    /// in Find still works.
    private func handleKey(_ event: NSEvent) -> Bool {
        guard let window = view.window, window.isKeyWindow, event.window === window else { return false }
        if let responder = window.firstResponder as? NSText, responder.isEditable { return false }
        if let tv = window.firstResponder as? NSTextView, tv.isFieldEditor { return false }

        let arrowKeys: Set<UInt16> = [123, 124, 125, 126, 116, 121, 49] // ←→↓↑ pgUp pgDn space
        let chars = event.charactersIgnoringModifiers?.lowercased() ?? ""
        let isWASD = leftHanded && "wasdqe".contains(chars) && chars.count == 1
        guard arrowKeys.contains(event.keyCode) || isWASD else { return false }
        // Don't steal Cmd/Ctrl/Option shortcuts.
        if event.modifierFlags.contains(.command) || event.modifierFlags.contains(.control) { return false }

        switch navMode {
        case .scroll:    return handleScrollKey(event.keyCode, chars: isWASD ? chars : nil)
        case .pageFlip: return handleFlipKey(event.keyCode, chars: isWASD ? chars : nil)
        }
    }

    private func handleScrollKey(_ code: UInt16, chars: String?) -> Bool {
        switch (code, chars) {
        case (125, _), (_, "s"): scrollTextView.scrollLineDown(nil)            // ↓ / s
        case (126, _), (_, "w"): scrollTextView.scrollLineUp(nil)              // ↑ / w
        case (121, _), (49, _), (_, "e"): scrollTextView.scrollPageDown(nil)   // pgDn / space / e
        case (116, _), (_, "q"): scrollTextView.scrollPageUp(nil)              // pgUp / q
        default: return false
        }
        currentPage = Int((scrollTextView.visibleRect.minY / Self.pageHeight).rounded())
        onPageChanged?()
        return true
    }

    /// Screen-flip key map. Right/Down/Space → next; Left/Up → previous. Page keys follow the
    /// requested RTL-friendly semantics: Page Up = advance (next), Page Down = previous. Left-hand
    /// (WASD): D/S → next, A/W → previous, Q (=pageUp) → next, E (=pageDown) → previous.
    private func handleFlipKey(_ code: UInt16, chars: String?) -> Bool {
        let next: Set<UInt16> = [124, 125, 49, 116]   // → ↓ space pgUp(advance)
        let prev: Set<UInt16> = [123, 126, 121]       // ← ↑ pgDn(previous)
        if let c = chars {
            if "dsq".contains(c) { nextPage(); return true }
            if "awe".contains(c) { previousPage(); return true }
            return false
        }
        if next.contains(code) { nextPage(); return true }
        if prev.contains(code) { previousPage(); return true }
        return false
    }

    // MARK: Trackpad / wheel navigation (screen-flip)

    private var scrollAccum: CGFloat = 0

    /// Forwarded from the paged columns / container. Accumulates wheel + two-finger swipe delta and
    /// flips a page once a threshold is crossed (vertical scroll → horizontal page move; horizontal
    /// swipe natural). No-op in scroll mode (the NSScrollView handles its own wheel there).
    func handlePagedScroll(_ event: NSEvent) {
        guard navMode == .pageFlip else { return }
        let dx = event.scrollingDeltaX, dy = event.scrollingDeltaY
        let delta = abs(dx) > abs(dy) ? dx : dy
        if event.phase == .began || event.momentumPhase == .began { scrollAccum = 0 }
        scrollAccum += delta
        let threshold: CGFloat = 40
        if scrollAccum <= -threshold { scrollAccum = 0; nextPage() }       // content up / swipe left → next
        else if scrollAccum >= threshold { scrollAccum = 0; previousPage() }
    }

    /// The base (global) offset for a given column text view.
    private func baseOffset(of tv: NSTextView) -> Int {
        if tv === scrollTextView { return 0 }
        guard let idx = columns.firstIndex(where: { $0 === tv }) else { return 0 }
        let pageIdx = currentPage * columnsPerScreen + idx
        return pageRanges.indices.contains(pageIdx) ? pageRanges[pageIdx].location : 0
    }

    // MARK: Content update

    func update(document: LoadedDocument, annotations: [ResolvedAnnotation],
                bodySize: CGFloat, navMode: NavMode, twoColumn: Bool,
                theme: PaperTheme, font: FontChoice) {
        let contentChanged = bodySize != self.bodySize
            || theme != self.theme
            || font != self.font
            || document.plainText as NSString != fullText
            || !annotationsEqual(annotations, currentAnnotations)
        let modeChanged = navMode != self.navMode || twoColumn != self.twoColumn
        let themeChanged = theme != self.theme

        self.bodySize = bodySize
        self.navMode = navMode
        self.twoColumn = twoColumn
        self.theme = theme
        self.font = font
        currentAnnotations = annotations
        fullText = document.plainText as NSString

        if themeChanged { applyTheme() }

        if contentChanged {
            fullAttributed = Self.makeAttributedString(document: document,
                                                       annotations: annotations,
                                                       bodySize: bodySize, theme: theme)
            setScrollContent()
            paginationDirty = true
        }

        if contentChanged || modeChanged {
            applyMode()
        }
    }

    private func annotationsEqual(_ a: [ResolvedAnnotation], _ b: [ResolvedAnnotation]) -> Bool {
        guard a.count == b.count else { return false }
        for (x, y) in zip(a, b) {
            if x.annotation.id != y.annotation.id || x.annotation.tool != y.annotation.tool
                || x.span?.start != y.span?.start || x.span?.end != y.span?.end { return false }
        }
        return true
    }

    private func setScrollContent() {
        if let cs = scrollTextView.textContentStorage {
            cs.performEditingTransaction { cs.textStorage?.setAttributedString(fullAttributed) }
        } else {
            scrollTextView.textStorage?.setAttributedString(fullAttributed)
        }
    }

    /// Repaints the reader surface backgrounds from the active theme (text colours are baked into
    /// the attributed string, which `update` rebuilds when the theme changes).
    private func applyTheme() {
        let paper = theme.nsPaper
        scrollView.backgroundColor   = paper
        scrollTextView.backgroundColor = paper
        pagedContainer.backgroundColor = paper
        for tv in columns { tv.backgroundColor = paper }
        applyWindowBackground()
    }

    /// Paint the window background with the paper colour so the (hidden-background) toolbar strip
    /// blends with the reader instead of showing the system window grey — visible in Night mode.
    private func applyWindowBackground() {
        view.window?.backgroundColor = theme.nsPaper
    }

    private func applyMode() {
        let paged = navMode == .pageFlip
        scrollView.isHidden = paged
        pagedContainer.isHidden = !paged
        if paged {
            paginationDirty = true
            relayoutPaged()
        }
        onPageChanged?()
    }

    // MARK: Paged layout

    private func relayoutPaged() {
        guard navMode == .pageFlip else { return }
        let bounds = pagedContainer.bounds
        guard bounds.width > 50, bounds.height > 50, fullAttributed.length > 0 else { return }

        let cols = columnsPerScreen
        let totalGaps = CGFloat(cols - 1) * Self.columnGap
        let colWidth = (bounds.width - totalGaps - 2 * Self.margin) / CGFloat(cols)
        let pageSize = CGSize(width: max(40, colWidth), height: max(40, bounds.height - 2 * Self.vInset))

        if paginationDirty {
            pageRanges = Self.paginate(fullAttributed, pageSize: pageSize)
            paginationDirty = false
            let screenCount = max(1, Int(ceil(Double(pageRanges.count) / Double(cols))))
            currentPage = min(currentPage, screenCount - 1)
        }

        // Ensure we have exactly `cols` column views.
        while columns.count < cols {
            let tv = makeTextView()
            tv.onScrollWheelForward = { [weak self] event in self?.handlePagedScroll(event) }
            columns.append(tv)
            pagedContainer.addSubview(tv)
        }
        while columns.count > cols {
            let tv = columns.removeLast()
            tv.removeFromSuperview()
            NotificationCenter.default.removeObserver(self, name: NSTextView.didChangeSelectionNotification, object: tv)
        }

        // Frame + fill each column with its page substring.
        for (i, tv) in columns.enumerated() {
            let x = Self.margin + CGFloat(i) * (colWidth + Self.columnGap) - Self.margin
            tv.frame = NSRect(x: x, y: 0, width: colWidth + 2 * Self.margin, height: bounds.height)
            tv.textContainer?.size = NSSize(width: colWidth, height: bounds.height - 2 * Self.vInset)
            tv.textContainerInset = NSSize(width: Self.margin, height: Self.vInset)

            let pageIdx = currentPage * cols + i
            if pageRanges.indices.contains(pageIdx) {
                let sub = fullAttributed.attributedSubstring(from: pageRanges[pageIdx])
                tv.textStorage?.setAttributedString(sub)
                tv.isHidden = false
            } else {
                tv.textStorage?.setAttributedString(NSAttributedString(string: ""))
                tv.isHidden = true
            }
        }
    }

    // MARK: Page navigation

    var pageCount: Int {
        switch navMode {
        case .scroll:
            let h = scrollTextView.frame.height
            guard h > 0 else { return 1 }
            return max(1, Int(ceil(h / Self.pageHeight)))
        case .pageFlip:
            return max(1, Int(ceil(Double(pageRanges.count) / Double(columnsPerScreen))))
        }
    }

    func scrollToPage(_ page: Int) {
        let clamped = max(0, min(page, pageCount - 1))
        scrollView.documentView?.scroll(NSPoint(x: 0, y: CGFloat(clamped) * Self.pageHeight))
        currentPage = clamped
    }

    /// Horizontal slide (push) when flipping page-screens — a plain slide, NOT a page curl.
    private func slide(forward: Bool) {
        guard let layer = pagedContainer.layer else { return }
        let t = CATransition()
        t.type = .push
        t.subtype = forward ? .fromRight : .fromLeft
        t.duration = 0.22
        t.timingFunction = CAMediaTimingFunction(name: .easeInEaseOut)
        layer.add(t, forKey: "pageFlip")
    }

    func nextPage() {
        switch navMode {
        case .scroll: scrollToPage(currentPage + 1)
        case .pageFlip:
            guard currentPage + 1 < pageCount else { return }
            currentPage += 1
            slide(forward: true)
            relayoutPaged()
        }
        onPageChanged?()
    }

    func previousPage() {
        switch navMode {
        case .scroll: scrollToPage(currentPage - 1)
        case .pageFlip:
            guard currentPage > 0 else { return }
            currentPage -= 1
            slide(forward: false)
            relayoutPaged()
        }
        onPageChanged?()
    }

    // MARK: Find + scroll-to-annotation (driven from the sidebar panel)

    /// Presents the NSTextView find bar. In paged mode this only searches the visible column, so
    /// we present it on the first column; the host drops to scroll mode for global find on iOS —
    /// on macOS the find bar searches the current screen's text.
    func presentFind() {
        let tv: NSTextView? = navMode == .scroll ? scrollTextView : columns.first
        guard let tv, tv.window != nil else { return }
        tv.window?.makeFirstResponder(tv)
        let item = NSMenuItem()
        item.tag = Int(NSTextFinder.Action.showFindInterface.rawValue)
        tv.performTextFinderAction(item)
    }

    /// Jumps directly to a page screen (0-based). Works in both modes.
    func goToPage(_ page: Int) {
        let clamped = max(0, min(page, pageCount - 1))
        switch navMode {
        case .scroll:
            scrollToPage(clamped)
        case .pageFlip:
            currentPage = clamped
            relayoutPaged()
        }
        onPageChanged?()
    }

    /// Jumps to the screen that contains the given UTF-16 char offset into the full text.
    func scrollToCharOffset(_ offset: Int) {
        switch navMode {
        case .scroll:
            let clamped = max(0, min(offset, fullText.length - 1))
            scrollTextView.scrollRangeToVisible(NSRange(location: clamped, length: 1))
            currentPage = Int((scrollTextView.visibleRect.minY / Self.pageHeight).rounded())
            onPageChanged?()
        case .pageFlip:
            if let pageIdx = pageRanges.firstIndex(where: { offset >= $0.location && offset < $0.location + $0.length }) {
                currentPage = pageIdx / columnsPerScreen
                relayoutPaged()
                onPageChanged?()
            }
        }
    }

    /// Scrolls/pages the reader so the annotation's span is visible.
    func scrollToAnnotation(id: String) {
        guard let resolved = currentAnnotations.first(where: { $0.annotation.id == id }),
              let span = resolved.span, span.start >= 0 else { return }
        switch navMode {
        case .scroll:
            let length = max(1, min(span.end - span.start, 80))
            let clamped = min(span.start, max(0, fullText.length - 1))
            scrollTextView.scrollRangeToVisible(NSRange(location: clamped, length: length))
        case .pageFlip:
            // Find the column-page containing span.start, convert to a screen index.
            if let pageIdx = pageRanges.firstIndex(where: { span.start >= $0.location && span.start < $0.location + $0.length }) {
                currentPage = pageIdx / columnsPerScreen
                relayoutPaged()
                onPageChanged?()
            }
        }
    }

    // MARK: Selection → annotation

    @objc private func selectionChanged(_ note: Notification) {
        guard let tv = note.object as? AnnotatingTextView else { return }
        // Ignore the hidden mode's text views.
        if navMode == .scroll, tv !== scrollTextView { return }
        if navMode == .pageFlip, !columns.contains(where: { $0 === tv }) { return }

        let sel = tv.selectedRange()
        if sel.length > 0 {
            activeTextView = tv
            activeBaseOffset = baseOffset(of: tv)
            let global = NSRange(location: activeBaseOffset + sel.location, length: sel.length)
            if global.location != lastShownRange.location || global.length != lastShownRange.length {
                lastShownRange = global
                pendingRange   = global
                showToolPopover(on: tv, forLocalRange: sel)
            }
        } else if lastShownRange.length > 0, tv === activeTextView {
            lastShownRange = NSRange(location: NSNotFound, length: 0)
            toolPopover?.close()
        }
    }

    private func showToolPopover(on tv: AnnotatingTextView, forLocalRange range: NSRange) {
        guard let window = tv.window, range.length > 0 else { return }

        var dummy = NSRange()
        let tvLen = (tv.string as NSString).length
        let anchorLoc = NSRange(
            location: min(range.location + range.length - 1, max(0, tvLen - 1)),
            length: 1
        )
        var screenRect = tv.firstRect(forCharacterRange: anchorLoc, actualRange: &dummy)
        if screenRect.origin == .zero {
            screenRect = NSRect(origin: NSEvent.mouseLocation, size: CGSize(width: 1, height: 1))
        }
        let windowRect = window.convertFromScreen(screenRect)
        let viewRect   = tv.convert(windowRect, from: nil)

        if toolPopover == nil {
            let popover = NSPopover()
            popover.behavior = .transient
            popover.animates = false
            let pickerVC = NSHostingController(rootView: ToolPickerView { [weak self] tool in
                self?.commitAnnotation(tool: tool)
            })
            popover.contentViewController = pickerVC
            popover.contentSize = NSSize(width: 392, height: 62)
            toolPopover = popover
        }

        toolPopover?.show(relativeTo: viewRect, of: tv, preferredEdge: .minY)
    }

    private func commitAnnotation(tool: AnnotationTool) {
        toolPopover?.close()
        let range = pendingRange   // GLOBAL range into fullText
        guard range.length > 0, range.location != NSNotFound else { return }

        let ns  = fullText
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

        if tool == .inkAnnotation {
            onInkAnnotationRequested?(annotation)
        } else {
            onAnnotationCreated?(annotation)
            // For comment annotations, open the edit sheet immediately so the user can add a note.
            if tool == .comment { onAnnotationTapped?(annotation) }
        }

        if let tv = activeTextView {
            let local = range.location - activeBaseOffset
            if local >= 0 && local <= (tv.string as NSString).length {
                tv.setSelectedRange(NSRange(location: local, length: 0))
            }
        }
    }

    // MARK: Attributed string + pagination (static helpers)

    /// Builds the full attributed string with format spans AND per-tool annotation decorations
    /// baked in, so a paged column can simply slice it (`attributedSubstring`) and keep all styling.
    static func makeAttributedString(document doc: LoadedDocument,
                                     annotations: [ResolvedAnnotation],
                                     bodySize: CGFloat,
                                     theme: PaperTheme = AppTheme.currentTheme) -> NSAttributedString {
        let ink = theme.nsInk
        let str = NSMutableAttributedString(string: doc.plainText, attributes: [
            .font:            AppTheme.nsBody(size: bodySize),
            .foregroundColor: ink,
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

        for resolved in annotations {
            guard let span = resolved.span else { continue }
            let len = span.end - span.start
            guard len > 0, span.start >= 0, span.start + len <= utf16len else { continue }
            let r = NSRange(location: span.start, length: len)
            switch resolved.annotation.tool {
            case .highlight:
                str.addAttribute(.backgroundColor, value: theme.nsHighlight, range: r)
            case .underline:
                str.addAttribute(.underlineStyle, value: NSUnderlineStyle.single.rawValue, range: r)
                str.addAttribute(.underlineColor, value: ink, range: r)
            case .doubleUnderline:
                str.addAttribute(.underlineStyle, value: NSUnderlineStyle.double.rawValue, range: r)
                str.addAttribute(.underlineColor, value: ink, range: r)
            case .strikethrough:
                str.addAttribute(.strikethroughStyle, value: NSUnderlineStyle.single.rawValue, range: r)
                str.addAttribute(.strikethroughColor, value: ink, range: r)
            case .wavyUnderline:
                let style = NSUnderlineStyle.patternDash.rawValue | NSUnderlineStyle.thick.rawValue
                str.addAttribute(.underlineStyle, value: style, range: r)
                str.addAttribute(.underlineColor, value: NSColor.systemTeal, range: r)
            case .bookmark:
                str.addAttribute(.backgroundColor, value: NSColor.systemOrange.withAlphaComponent(0.15), range: r)
            case .comment:
                let style = NSUnderlineStyle.patternDot.rawValue | NSUnderlineStyle.thick.rawValue
                str.addAttribute(.underlineStyle, value: style, range: r)
                str.addAttribute(.underlineColor, value: NSColor.systemGreen, range: r)
                str.addAttribute(.backgroundColor, value: NSColor.systemGreen.withAlphaComponent(0.1), range: r)
            case .inkAnnotation:
                str.addAttribute(.backgroundColor, value: NSColor.systemPurple.withAlphaComponent(0.12), range: r)
            }
        }
        return str
    }

    /// Splits the attributed string into per-column character ranges that fit `pageSize`, using a
    /// TextKit-1 layout pass. Page breaks fall on line boundaries (mirrors the iOS TextPaginator).
    static func paginate(_ attributed: NSAttributedString, pageSize: CGSize) -> [NSRange] {
        guard pageSize.width > 10, pageSize.height > 10, attributed.length > 0 else {
            return [NSRange(location: 0, length: attributed.length)]
        }
        let storage = NSTextStorage(attributedString: attributed)
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
            if let last = ranges.last, charRange.location <= last.location { break }
            ranges.append(charRange)
        }
        if ranges.isEmpty { ranges = [NSRange(location: 0, length: attributed.length)] }
        return ranges
    }
}

// MARK: - Paged container (edge clicks flip pages)

/// Flat container for the screen-flip columns. Clicks in the outer left/right margins (the gutters
/// outside the text columns) flip the page; no animation. Clicks inside a column text view are
/// handled by the text view (selection / annotation tap) and never reach here.
final class PagedContainerView: NSView {
    var backgroundColor: NSColor = .white { didSet { needsDisplay = true } }
    var onEdgeFlip: ((Bool) -> Void)?
    var onScrollWheel: ((NSEvent) -> Void)?

    override var isFlipped: Bool { true }
    override func draw(_ dirtyRect: NSRect) {
        backgroundColor.setFill()
        dirtyRect.fill()
    }

    override func mouseDown(with event: NSEvent) {
        let p = convert(event.locationInWindow, from: nil)
        let edge = bounds.width * 0.22
        if p.x < edge { onEdgeFlip?(false) }
        else if p.x > bounds.width - edge { onEdgeFlip?(true) }
        else { super.mouseDown(with: event) }
    }

    override func scrollWheel(with event: NSEvent) {
        if let fwd = onScrollWheel { fwd(event) } else { super.scrollWheel(with: event) }
    }
}

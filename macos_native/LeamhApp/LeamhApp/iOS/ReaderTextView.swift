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
    var twoColumnPaged: Bool = true
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
        if vc.twoColumnEnabled != twoColumnPaged {
            vc.twoColumnEnabled = twoColumnPaged
            vc.invalidatePagination()
        }
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

// MARK: - View controller (container)

/// Hosts the reader in the active NavMode: a single scrolling `AnnotatingTextSurface` (scroll mode),
/// or a `UIPageViewController` of per-page surfaces — `.pageCurl` for Page Turn, `.scroll` for
/// Screen Flip. Selection + annotation work in every mode (each surface owns that pipeline).
final class ReaderViewController: UIViewController, UIPageViewControllerDataSource, UIPageViewControllerDelegate {

    var onAnnotationCreated: ((Annotation) -> Void)?
    var onAnnotationTapped:  ((Annotation) -> Void)?
    var onDeleteAnnotation:  ((String) -> Void)?
    var onInkAnnotationRequested: ((Annotation) -> Void)?
    var onEditInk:           ((Annotation) -> Void)?

    private var fullAttributed = NSAttributedString()
    private var fullPlainText: NSString = ""
    private var currentAnnotations: [ResolvedAnnotation] = []
    private var bodySize = AppTheme.bodySize
    private var lastDocumentURL: URL?
    private var hasRendered = false

    var navMode: NavMode = .scroll {
        didSet { guard oldValue != navMode else { return }; installMode() }
    }
    /// iPad-only two-column preference (set from the representable). iPhone is always 1 column.
    var twoColumnEnabled = true

    func invalidatePagination() {
        paginatedSize = .zero
        view.setNeedsLayout()
    }

    // Scroll mode
    private var scrollSurface: AnnotatingTextSurface?
    // Paged modes — each page holds 1 (compact) or 2 (wide) column ranges.
    private var pageVC: UIPageViewController?
    private var pages: [[NSRange]] = []
    private var pageIndex = 0
    private var paginatedSize: CGSize = .zero
    /// Identity of what affects line-breaking (text length + body size). Pagination only needs to
    /// recompute when this changes — NOT when annotation decorations change.
    private var lastPaginationKey = ""
    private var edgeTap: UITapGestureRecognizer!

    private let scrollInsets   = UIEdgeInsets(top: 32, left: 24, bottom: 48, right: 24)
    private let pagePadding    = UIEdgeInsets(top: 28, left: 28, bottom: 28, right: 28)
    private let columnGap: CGFloat = 28
    /// Available text width at/above which a page uses two columns (iPad); below = one (iPhone).
    private let twoColumnMinWidth: CGFloat = 380

    private var isVerticalPaging: Bool { navMode == .screenFlip }

    // MARK: Setup

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = AppTheme.warmPaperUI
        edgeTap = UITapGestureRecognizer(target: self, action: #selector(handleEdgeTap(_:)))
        edgeTap.cancelsTouchesInView = false
        edgeTap.isEnabled = false
        view.addGestureRecognizer(edgeTap)
        installMode()
    }

    private func wire(_ surface: AnnotatingTextSurface) {
        surface.fullPlainText        = fullPlainText
        surface.annotationsProvider  = { [weak self] in self?.currentAnnotations ?? [] }
        surface.onAnnotationCreated  = { [weak self] in self?.onAnnotationCreated?($0) }
        surface.onAnnotationTapped   = { [weak self] in self?.onAnnotationTapped?($0) }
        surface.onDeleteAnnotation   = { [weak self] in self?.onDeleteAnnotation?($0) }
        surface.onInkAnnotationRequested = { [weak self] in self?.onInkAnnotationRequested?($0) }
        surface.onEditInk            = { [weak self] in self?.onEditInk?($0) }
    }

    // MARK: Content update

    func update(document: LoadedDocument, annotations: [ResolvedAnnotation],
                documentURL: URL?, bodySize: CGFloat) {
        let isNew = !hasRendered || documentURL != lastDocumentURL
        currentAnnotations = annotations
        self.bodySize      = bodySize
        fullPlainText      = document.plainText as NSString
        fullAttributed     = ReaderTextView.makeAttributedString(document: document,
                                                                 annotations: annotations,
                                                                 bodySize: bodySize)
        if isNew { lastDocumentURL = documentURL; hasRendered = true; pageIndex = 0 }
        scrollSurface?.fullPlainText = fullPlainText

        let key = "\(fullPlainText.length)-\(bodySize)"
        let layoutChanged = key != lastPaginationKey
        lastPaginationKey = key

        if scrollSurface != nil {
            let offset = scrollSurface?.textView.contentOffset ?? .zero
            scrollSurface?.setAttributed(fullAttributed)
            if isNew { scrollSurface?.textView.contentOffset = .zero }
            else     { scrollSurface?.textView.contentOffset = offset }
        } else if layoutChanged || isNew {
            // Text/size changed → re-paginate on next layout.
            paginatedSize = .zero
            view.setNeedsLayout()
        } else {
            // Only decorations changed → refresh the visible page's columns in place (no re-paginate).
            if let page = pageVC?.viewControllers?.first as? ReaderPageViewController,
               page.pageNumber < pages.count {
                let cols = pages[page.pageNumber]
                for (i, col) in page.columns.enumerated() where i < cols.count {
                    col.fullPlainText = fullPlainText
                    col.setAttributed(fullAttributed.attributedSubstring(from: cols[i]))
                }
            }
        }
    }

    // MARK: Mode install / layout

    private func installMode() {
        // Tear down whichever child is active.
        if let s = scrollSurface { remove(child: s); scrollSurface = nil }
        if let p = pageVC        { remove(child: p); pageVC = nil }

        switch navMode {
        case .scroll:
            edgeTap.isEnabled = false
            let s = AnnotatingTextSurface(scrollable: true, insets: scrollInsets)
            wire(s)
            add(child: s)
            scrollSurface = s
            s.setAttributed(fullAttributed)
        case .pageTurn, .screenFlip:
            edgeTap.isEnabled = true
            // Page Turn = horizontal page-curl; Screen Flip = vertical scroll.
            let style: UIPageViewController.TransitionStyle = (navMode == .pageTurn) ? .pageCurl : .scroll
            let orientation: UIPageViewController.NavigationOrientation = isVerticalPaging ? .vertical : .horizontal
            let p = UIPageViewController(transitionStyle: style,
                                         navigationOrientation: orientation)
            p.dataSource = self
            p.delegate   = self
            add(child: p)
            pageVC = p
            restrictPagingToFinger(p)
            paginatedSize = .zero
            view.setNeedsLayout()
        }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        guard pageVC != nil, fullAttributed.length > 0 else { return }
        let safe  = view.safeAreaInsets
        let availW = view.bounds.width  - safe.left - safe.right - pagePadding.left - pagePadding.right
        let availH = view.bounds.height - safe.top  - safe.bottom - pagePadding.top - pagePadding.bottom
        let size   = CGSize(width: availW, height: availH)
        guard size.width > 10, size.height > 10, size != paginatedSize else { return }
        paginatedSize = size

        // Two columns only on iPad, when the user enables it, and there's enough width.
        let isPad = traitCollection.userInterfaceIdiom == .pad
        let columnCount = (twoColumnEnabled && isPad && availW >= twoColumnMinWidth) ? 2 : 1
        let colW = (availW - columnGap * CGFloat(columnCount - 1)) / CGFloat(columnCount)
        // Paginate to a hair under the column height so sub-pixel rounding never clips the last line.
        let colH = availH - 4
        let columnRanges = TextPaginator.paginate(fullAttributed, pageSize: CGSize(width: colW, height: colH))
        pages = stride(from: 0, to: columnRanges.count, by: columnCount).map {
            Array(columnRanges[$0 ..< min($0 + columnCount, columnRanges.count)])
        }
        pageIndex = min(pageIndex, max(0, pages.count - 1))
        if let page = makePage(pageIndex) {
            pageVC?.setViewControllers([page], direction: .forward, animated: false)
        }
        if let p = pageVC { restrictPagingToFinger(p) }
    }

    /// Restricts the page-view's paging gestures (pageCurl pan/tap, or the .scroll style's inner
    /// scroll view) to finger touches, so an Apple Pencil drag selects text instead of turning pages.
    private func restrictPagingToFinger(_ vc: UIPageViewController) {
        let finger = [NSNumber(value: UITouch.TouchType.direct.rawValue)]
        vc.gestureRecognizers.forEach { $0.allowedTouchTypes = finger }
        for sub in vc.view.subviews {
            (sub as? UIScrollView)?.panGestureRecognizer.allowedTouchTypes = finger
        }
    }

    private func makePage(_ index: Int) -> ReaderPageViewController? {
        guard index >= 0, index < pages.count else { return nil }
        let columns = pages[index].map { range -> AnnotatingTextSurface in
            let s = AnnotatingTextSurface(scrollable: false, insets: .zero)
            wire(s)
            s.baseOffset = range.location
            s.loadViewIfNeeded()
            s.setAttributed(fullAttributed.attributedSubstring(from: range))
            return s
        }
        return ReaderPageViewController(pageNumber: index, columns: columns,
                                        padding: pagePadding, gap: columnGap)
    }

    // MARK: Child VC helpers

    private func add(child vc: UIViewController) {
        addChild(vc)
        vc.view.translatesAutoresizingMaskIntoConstraints = false
        view.insertSubview(vc.view, at: 0)
        NSLayoutConstraint.activate([
            vc.view.topAnchor.constraint(equalTo: view.topAnchor),
            vc.view.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            vc.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            vc.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
        ])
        vc.didMove(toParent: self)
    }

    private func remove(child vc: UIViewController) {
        vc.willMove(toParent: nil)
        vc.view.removeFromSuperview()
        vc.removeFromParent()
    }

    // MARK: Find / scroll-to-annotation (driven from the sidebar)

    func activateFind() {
        currentSurface?.presentFind()
    }

    func scrollToAnnotation(id: String) {
        guard let resolved = currentAnnotations.first(where: { $0.annotation.id == id }),
              let span = resolved.span, span.start >= 0 else { return }
        if let s = scrollSurface {
            let length = max(1, min(span.end - span.start, 80))
            s.textView.scrollRangeToVisible(NSRange(location: span.start, length: length))
        } else if !pages.isEmpty {
            let target = pages.firstIndex { cols in
                cols.contains { span.start >= $0.location && span.start < $0.location + $0.length }
            }
            goToPage(target ?? 0)
        }
    }

    /// First text surface of the current page (used by Find, which is per-surface).
    private var currentSurface: AnnotatingTextSurface? {
        if let s = scrollSurface { return s }
        return (pageVC?.viewControllers?.first as? ReaderPageViewController)?.columns.first
    }

    // MARK: Edge-tap paging (orientation-aware)

    @objc private func handleEdgeTap(_ gr: UITapGestureRecognizer) {
        if isVerticalPaging {
            let y = gr.location(in: view).y
            let h = view.bounds.height
            if y < h * 0.25      { goToPage(pageIndex - 1) }
            else if y > h * 0.75 { goToPage(pageIndex + 1) }
        } else {
            let x = gr.location(in: view).x
            let w = view.bounds.width
            if x < w * 0.25      { goToPage(pageIndex - 1) }
            else if x > w * 0.75 { goToPage(pageIndex + 1) }
        }
    }

    private func goToPage(_ target: Int) {
        guard target >= 0, target < pages.count, target != pageIndex,
              let page = makePage(target) else { return }
        let direction: UIPageViewController.NavigationDirection = target > pageIndex ? .forward : .reverse
        let animated = (navMode == .pageTurn)
        pageVC?.setViewControllers([page], direction: direction, animated: animated)
        pageIndex = target
    }

    // MARK: UIPageViewControllerDataSource / Delegate

    func pageViewController(_ pvc: UIPageViewController,
                            viewControllerBefore vc: UIViewController) -> UIViewController? {
        guard let p = vc as? ReaderPageViewController else { return nil }
        return makePage(p.pageNumber - 1)
    }

    func pageViewController(_ pvc: UIPageViewController,
                            viewControllerAfter vc: UIViewController) -> UIViewController? {
        guard let p = vc as? ReaderPageViewController else { return nil }
        return makePage(p.pageNumber + 1)
    }

    func pageViewController(_ pvc: UIPageViewController,
                            didFinishAnimating finished: Bool,
                            previousViewControllers: [UIViewController],
                            transitionCompleted completed: Bool) {
        if completed, let p = pvc.viewControllers?.first as? ReaderPageViewController {
            pageIndex = p.pageNumber
        }
    }
}

// MARK: - Floating annotation toolbar

/// A pill-shaped floating icon toolbar that appears above a text selection.
/// Each button maps to one AnnotationTool; tapping commits an annotation and hides the bar.
/// Internal (not file-private) so the paginated page surfaces can reuse it.
final class FloatingSelectionToolbar: UIView {

    var onSelect: ((AnnotationTool) -> Void)?
    var onCopy: (() -> Void)?

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

        // Copy leads the bar (not an annotation tool).
        let copyBtn = UIButton(type: .system)
        copyBtn.setImage(UIImage(systemName: "doc.on.doc", withConfiguration: cfg), for: .normal)
        copyBtn.tintColor = .label
        copyBtn.accessibilityLabel = "Copy"
        copyBtn.addAction(UIAction { [weak self] _ in self?.onCopy?() }, for: .touchUpInside)
        copyBtn.translatesAutoresizingMaskIntoConstraints = false
        copyBtn.widthAnchor.constraint(equalToConstant: Self.buttonSize).isActive = true
        copyBtn.heightAnchor.constraint(equalToConstant: Self.buttonSize).isActive = true
        stack.addArrangedSubview(copyBtn)
        let copySep = UIView()
        copySep.backgroundColor = UIColor.separator
        copySep.translatesAutoresizingMaskIntoConstraints = false
        copySep.widthAnchor.constraint(equalToConstant: Self.separatorW).isActive = true
        copySep.heightAnchor.constraint(equalToConstant: 22).isActive = true
        stack.addArrangedSubview(copySep)

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
        // +1 button (Copy) and its separator, in addition to the annotation tools.
        let buttons = CGFloat(Self.items.count + 1)
        let seps    = CGFloat(Self.items.count)
        return CGSize(width: buttons * Self.buttonSize + seps * Self.separatorW,
                      height: Self.buttonSize)
    }
}

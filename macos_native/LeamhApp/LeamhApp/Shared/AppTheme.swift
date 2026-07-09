import SwiftUI
#if os(macOS)
import AppKit
#elseif os(iOS)
import UIKit
#endif

/// The user-selectable app-wide font. Mirrors Android's single `body_font` preference:
/// ONE family applies to the entire UI (reader body AND all chrome); only weight varies.
/// Backed by Apple system fonts — New York (the system serif) and San Francisco (the
/// system default) — so there are no bundled font files to register.
enum FontChoice: String, CaseIterable {
    case serif   // New York
    case sans    // San Francisco

    var label: String {
        switch self {
        case .serif: return "New York"
        case .sans:  return "San Francisco"
        }
    }
    var design: Font.Design {
        switch self {
        case .serif: return .serif
        case .sans:  return .default
        }
    }
    /// The choice's own typeface, for previewing the option label in the picker.
    var previewFont: Font { .system(size: 15, design: design) }
}

/// Reader body text size. Mirrors Android's 3-step `body_font_size` (small/medium/large).
/// Affects the reader body ONLY — chrome stays at its fixed sizes, matching Android.
enum BodyTextSize: String, CaseIterable {
    case small, medium, large

    var label: String {
        switch self {
        case .small:  return "Small"
        case .medium: return "Medium"
        case .large:  return "Large"
        }
    }
    var points: CGFloat {
        switch self {
        case .small:  return 15
        case .medium: return 17
        case .large:  return 20
        }
    }
}

/// Reader line spacing. Mirrors Android's `line_spacing` pref and the multipliers in
/// `ReaderTheme.lineSpacingMult` (normal 1.15 / comfortable 1.25 / spacious 1.32).
enum LineSpacing: String, CaseIterable {
    case normal, comfortable, spacious

    var label: String {
        switch self {
        case .normal:      return "Normal"
        case .comfortable: return "Comfortable"
        case .spacious:    return "Spacious"
        }
    }
    var multiple: CGFloat {
        switch self {
        case .normal:      return 1.15
        case .comfortable: return 1.25
        case .spacious:    return 1.32
        }
    }
}

enum AppTheme {
    // CLAUDE.md: warm paper background #F5F0E8
    static let warmPaper = Color(red: 245/255, green: 240/255, blue: 232/255)

    // Neutral surfaces used by shared panels — map to native system colours per platform.
    #if os(macOS)
    static let controlFieldBackground = Color(nsColor: .textBackgroundColor)
    static let panelBackground        = Color(nsColor: .windowBackgroundColor)
    #elseif os(iOS)
    static let controlFieldBackground = Color(uiColor: .systemBackground)
    static let panelBackground        = Color(uiColor: .secondarySystemBackground)
    #endif

    static let bodySize:   CGFloat = 17
    static let chromeSize: CGFloat = 13

    /// Reader body line-height multiple. Mirrors Android's default "comfortable"
    /// (`ReaderTheme.lineSpacingMult("comfortable")` == 1.25). Applied as an
    /// `NSMutableParagraphStyle.lineHeightMultiple` on the reader's base attributes.
    static let readerLineHeightMultiple: CGFloat = LineSpacing.comfortable.multiple

    /// Paragraph style carrying the reader body line spacing. Baked into the base
    /// attributes of both readers' attributed strings so paged slices inherit it.
    static func readerParagraphStyle(lineHeight: CGFloat = readerLineHeightMultiple) -> NSParagraphStyle {
        let p = NSMutableParagraphStyle()
        p.lineHeightMultiple = lineHeight
        return p
    }

    /// Process-wide active READER font (Android `ReaderTheme.bodyFont` analogue). ONLY the
    /// reader body honours this (the ui*/ns* helpers below); all SwiftUI chrome stays on San
    /// Francisco for a consistent UI. `DocumentStore.fontChoice`'s didSet keeps this in sync and
    /// seeds it at launch; the @Published is what re-renders the reader (this static alone
    /// invalidates nothing).
    static var current: FontChoice = .serif

    /// Process-wide active reader PAPER theme (écri-style). Like `current` (font), this is the static
    /// the reader's attributed-string builders read; `DocumentStore.paperTheme`'s didSet keeps it in
    /// sync and seeds it at launch. Only the reader surface honours it; chrome stays system-appearance.
    static var currentTheme: PaperTheme = .parchment

    // SwiftUI chrome fonts — always San Francisco (system default), independent of the reader
    // font choice. The serif/sans selection applies to the reader body only.
    static func body(size: CGFloat = bodySize) -> Font       { .system(size: size) }
    static func bodyItalic(size: CGFloat = bodySize) -> Font { .system(size: size).italic() }
    static func chrome(size: CGFloat = chromeSize) -> Font   { .system(size: size) }
    static func chromeBold(size: CGFloat = chromeSize) -> Font {
        .system(size: size, weight: .bold)
    }

    #if os(macOS)
    static let warmPaperNS  = NSColor(red: 245/255, green: 240/255, blue: 232/255, alpha: 1)
    // CLAUDE.md: highlight is dotted underline, black at ~15% opacity
    static let highlightNS  = NSColor.black.withAlphaComponent(0.15)

    /// System NSFont in the active design; serif resolves to New York, sans to San Francisco.
    /// Falls back to the plain system font if the serif descriptor is unavailable.
    static func nsSystemFont(_ size: CGFloat, _ weight: NSFont.Weight) -> NSFont {
        let base = NSFont.systemFont(ofSize: size, weight: weight)
        guard current == .serif,
              let d = base.fontDescriptor.withDesign(.serif),
              let f = NSFont(descriptor: d, size: size) else { return base }
        return f
    }
    static func nsBody(size: CGFloat = bodySize) -> NSFont       { nsSystemFont(size, .regular) }
    static func nsBodyBold(size: CGFloat = bodySize) -> NSFont   { nsSystemFont(size, .bold) }
    static func nsChromeBold(size: CGFloat = chromeSize) -> NSFont { nsSystemFont(size, .bold) }
    static func nsBodyItalic(size: CGFloat = bodySize) -> NSFont {
        let b = nsSystemFont(size, .regular)
        let d = b.fontDescriptor.withSymbolicTraits(.italic)
        return NSFont(descriptor: d, size: size) ?? b
    }
    #endif

    #if os(iOS)
    static let warmPaperUI  = UIColor(red: 245/255, green: 240/255, blue: 232/255, alpha: 1)

    /// System UIFont in the active design; serif resolves to New York, sans to San Francisco.
    static func uiSystemFont(_ size: CGFloat, _ weight: UIFont.Weight) -> UIFont {
        let base = UIFont.systemFont(ofSize: size, weight: weight)
        guard current == .serif, let d = base.fontDescriptor.withDesign(.serif) else { return base }
        return UIFont(descriptor: d, size: size)
    }
    static func uiBody(size: CGFloat = bodySize) -> UIFont     { uiSystemFont(size, .regular) }
    static func uiBodyBold(size: CGFloat = bodySize) -> UIFont { uiSystemFont(size, .bold) }
    static func uiBodyItalic(size: CGFloat = bodySize) -> UIFont {
        let b = uiSystemFont(size, .regular)
        let d = b.fontDescriptor.withSymbolicTraits(.traitItalic) ?? b.fontDescriptor
        return UIFont(descriptor: d, size: size)
    }
    #endif
}


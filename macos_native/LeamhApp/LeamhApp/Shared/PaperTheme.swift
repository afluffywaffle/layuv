import SwiftUI
#if os(macOS)
import AppKit
#elseif os(iOS)
import UIKit
#endif

/// User-selectable reader paper theme, modelled after the "écri" theme set: a paper background, an
/// ink (text) colour, and a complementary highlight tint per theme. Four warm/cool light papers plus
/// one dark (`night`). The reader surface drives its background + body text + highlight fill from the
/// active theme; chrome stays on the system appearance.
///
/// Layuv adds these to the existing FontChoice / BodyTextSize reader preferences. Only the reader is
/// themed — `night` is an explicit dark *paper* (dark bg, light ink set explicitly), independent of
/// the system dark-mode appearance, so the forced-light chrome lock is unaffected.
enum PaperTheme: String, CaseIterable {
    case parchment, bone, dusk, sage, night

    var label: String {
        switch self {
        case .parchment: return "Parchment"
        case .bone:      return "Bone"
        case .dusk:      return "Dusk"
        case .sage:      return "Sage"
        case .night:     return "Night"
        }
    }

    var isDark: Bool { self == .night }

    // Paper (background). Values from écri's `paperPageUI`.
    private var paperRGB: (Double, Double, Double) {
        switch self {
        case .parchment: return (0.984, 0.969, 0.937)
        case .bone:      return (0.975, 0.965, 0.950)
        case .dusk:      return (0.945, 0.935, 0.960)
        case .sage:      return (0.925, 0.950, 0.925)
        case .night:     return (0.140, 0.120, 0.100)
        }
    }

    // Ink (body text + underline/strikethrough lines). From écri's `inkDarkUI`.
    private var inkRGB: (Double, Double, Double) {
        switch self {
        case .parchment: return (0.28, 0.18, 0.08)
        case .bone:      return (0.22, 0.20, 0.17)
        case .dusk:      return (0.22, 0.18, 0.28)
        case .sage:      return (0.16, 0.24, 0.16)
        case .night:     return (0.88, 0.82, 0.72)
        }
    }

    // Highlight fill. From écri's `highlightColor` (with alpha).
    private var highlightRGBA: (Double, Double, Double, Double) {
        switch self {
        case .parchment: return (0.98, 0.82, 0.18, 0.50)
        case .bone:      return (0.98, 0.86, 0.32, 0.45)
        case .dusk:      return (0.68, 0.50, 0.96, 0.42)
        case .sage:      return (0.42, 0.84, 0.48, 0.42)
        case .night:     return (0.90, 0.74, 0.12, 0.58)
        }
    }

    // MARK: - SwiftUI

    var paper: Color {
        Color(red: paperRGB.0, green: paperRGB.1, blue: paperRGB.2)
    }
    /// Swatch shown in the theme picker.
    var swatch: Color { paper }

    // MARK: - Platform colours (reader surface)

    #if os(macOS)
    var nsPaper: NSColor {
        NSColor(red: paperRGB.0, green: paperRGB.1, blue: paperRGB.2, alpha: 1)
    }
    var nsInk: NSColor {
        NSColor(red: inkRGB.0, green: inkRGB.1, blue: inkRGB.2, alpha: 1)
    }
    var nsHighlight: NSColor {
        NSColor(red: highlightRGBA.0, green: highlightRGBA.1, blue: highlightRGBA.2, alpha: highlightRGBA.3)
    }
    #elseif os(iOS)
    var uiPaper: UIColor {
        UIColor(red: paperRGB.0, green: paperRGB.1, blue: paperRGB.2, alpha: 1)
    }
    var uiInk: UIColor {
        UIColor(red: inkRGB.0, green: inkRGB.1, blue: inkRGB.2, alpha: 1)
    }
    var uiHighlight: UIColor {
        UIColor(red: highlightRGBA.0, green: highlightRGBA.1, blue: highlightRGBA.2, alpha: highlightRGBA.3)
    }
    #endif
}

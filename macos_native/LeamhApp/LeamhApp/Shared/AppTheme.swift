import SwiftUI
#if os(macOS)
import AppKit
#elseif os(iOS)
import UIKit
#endif

enum AppTheme {
    // CLAUDE.md: warm paper background #F5F0E8
    static let warmPaper = Color(red: 245/255, green: 240/255, blue: 232/255)

    static let bodySize:   CGFloat = 17
    static let chromeSize: CGFloat = 13

    // SwiftUI fonts — rely on the bundled .ttf files (ATSApplicationFontsPath = "Fonts").
    // PostScript names: adjust here if the font validator shows different names at first launch.
    static func body(size: CGFloat = bodySize) -> Font       { .custom("Literata", size: size) }
    static func bodyItalic(size: CGFloat = bodySize) -> Font { .custom("Literata-Italic", size: size) }
    static func chrome(size: CGFloat = chromeSize) -> Font   { .custom("SourceSans3", size: size) }
    // SourceSans3.ttf is a variable font; "SourceSans3-Bold" activates the bold axis if present.
    static func chromeBold(size: CGFloat = chromeSize) -> Font { .custom("SourceSans3-Bold", size: size) }

    #if os(macOS)
    static let warmPaperNS  = NSColor(red: 245/255, green: 240/255, blue: 232/255, alpha: 1)
    // CLAUDE.md: highlight is dotted underline, black at ~15% opacity
    static let highlightNS  = NSColor.black.withAlphaComponent(0.15)

    // NSFont equivalents for TextKit layout in ReaderViewController.
    static func nsBody(size: CGFloat = bodySize) -> NSFont {
        NSFont(name: "Literata", size: size) ?? .systemFont(ofSize: size)
    }
    static func nsBodyItalic(size: CGFloat = bodySize) -> NSFont {
        NSFont(name: "Literata-Italic", size: size) ?? .systemFont(ofSize: size)
    }
    static func nsBodyBold(size: CGFloat = bodySize) -> NSFont {
        NSFont(name: "Literata-Bold", size: size) ?? .boldSystemFont(ofSize: size)
    }
    static func nsChromeBold(size: CGFloat = chromeSize) -> NSFont {
        NSFont(name: "SourceSans3-Bold", size: size) ?? .boldSystemFont(ofSize: size)
    }
    #endif

    #if os(iOS)
    static let warmPaperUI  = UIColor(red: 245/255, green: 240/255, blue: 232/255, alpha: 1)

    // UIFont equivalents for TextKit layout in the iPad UITextView reader.
    static func uiBody(size: CGFloat = bodySize) -> UIFont {
        UIFont(name: "Literata", size: size) ?? .systemFont(ofSize: size)
    }
    static func uiBodyItalic(size: CGFloat = bodySize) -> UIFont {
        UIFont(name: "Literata-Italic", size: size) ?? .italicSystemFont(ofSize: size)
    }
    static func uiBodyBold(size: CGFloat = bodySize) -> UIFont {
        UIFont(name: "Literata-Bold", size: size) ?? .boldSystemFont(ofSize: size)
    }
    #endif
}

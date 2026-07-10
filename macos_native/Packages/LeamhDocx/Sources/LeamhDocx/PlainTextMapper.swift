import Foundation

/// A bold and/or italic run over [start, end) char offsets into PlainMap.plain.
public struct FormatSpan: Equatable {
    public let start: Int
    public let end: Int
    public let bold: Bool
    public let italic: Bool
}

/// A whole paragraph over [start, end) char offsets (end excludes the trailing `\n`)
/// styled with a Word paragraph border and/or paragraph-level shading — e.g. a
/// block-quote/callout paragraph (<w:pBdr> + <w:shd> inside <w:pPr>). Distinct from
/// run-level <w:rPr><w:shd> which imports as a .highlight annotation instead (see
/// NativeImport). Mirrors ParagraphStyleSpan in PlainTextMapper.kt.
public struct ParagraphStyleSpan: Equatable {
    public let start: Int
    public let end: Int
    public let blockquote: Bool

    public init(start: Int, end: Int, blockquote: Bool) {
        self.start = start
        self.end = end
        self.blockquote = blockquote
    }
}

/// The canonical plain text plus, for each of its UTF-16 code units, the char offset
/// xmlOffsets into the source document.xml. The two are parallel: xmlOffsets.count == plain.utf16.count.
///
/// formats is a read-only, additive overlay (direct w:b/w:i run formatting) for display only.
public struct PlainMap {
    public let plain: String
    public let xmlOffsets: [Int]
    public let formats: [FormatSpan]
    /// Heading paragraphs in document order — drives the navigation outline.
    public let headings: [Heading]
    /// Block-quote-styled paragraphs in document order — display only, same overlay contract as formats.
    public let paragraphStyles: [ParagraphStyleSpan]

    public init(plain: String, xmlOffsets: [Int], formats: [FormatSpan] = [], headings: [Heading] = [], paragraphStyles: [ParagraphStyleSpan] = []) {
        self.plain = plain
        self.xmlOffsets = xmlOffsets
        self.formats = formats
        self.headings = headings
        self.paragraphStyles = paragraphStyles
    }
}

/// A heading paragraph for the document-outline navigator. level is 0-based
/// (0 = Heading 1). charOffset is the start of the heading's text in PlainMap.plain
/// — divide by plain.utf16.count for the 0.0–1.0 jump fraction (same coordinate
/// system as annotation positions).
///
/// Parity note vs PlainTextMapper.kt: the Swift mapper has no styles.xml
/// resolution, so the heading level is inferred from the conventional
/// `Heading N` pStyle val (Word/Pages/Google Docs all emit this). It does not
/// read an explicit `<w:outlineLvl>` from a custom style. For standard documents
/// the result is identical.
public struct Heading: Equatable {
    public let text: String
    public let level: Int
    public let charOffset: Int

    public init(text: String, level: Int, charOffset: Int) {
        self.text = text
        self.level = level
        self.charOffset = charOffset
    }
}

/// Builds the ONE canonical plain-text string used for both rendering and annotation anchoring.
/// Mirrors PlainTextMapper.kt — clean extraction parsing real element names.
///
/// Extraction rules:
///   <w:t>…</w:t>     decoded text; one xmlOffset per UTF-16 code unit
///   <w:tab/>          '\t'   offset = tag start
///   <w:br/> <w:cr/>   '\n'   offset = tag start
///   </w:p>            '\n'   offset = tag start
///   everything else   ignored
public enum PlainTextMapper {

    /// 1-indexed paragraph number containing UTF-16 offset `offset` into `plain`. Exact —
    /// paragraphs in `plain` are delimited one-for-one by the `\n` each `</w:p>` emits (see
    /// the extraction rules above), so this is a plain newline count, not an approximation.
    /// Mirrors `PlainTextMapper.paragraphIndex` in Kotlin.
    public static func paragraphIndex(_ plain: String, _ offset: Int) -> Int {
        let units = plain.utf16
        guard !units.isEmpty else { return 1 }
        let clamped = max(0, min(offset, units.count))
        var count = 1
        for (i, u) in units.enumerated() {
            if i >= clamped { break }
            if u == 0x0A { count += 1 }
        }
        return count
    }

    private static let wtClose = "</w:t>"
    private static let valRE = try! NSRegularExpression(pattern: #"w:val="([^"]*)""#)
    // Conventional heading styleId "Heading1".."Heading9" (optional space), case-insensitive.
    private static let headingRE = try! NSRegularExpression(pattern: #"^[Hh]eading\s*([1-9])$"#)
    private static let fillRE = try! NSRegularExpression(pattern: #"w:fill="([0-9A-Fa-f]{6})""#)

    private static func isRealFill(_ tag: String) -> Bool {
        guard let m = fillRE.firstMatch(in: tag, range: NSRange(tag.startIndex..., in: tag)) else { return false }
        let fill = (tag as NSString).substring(with: m.range(at: 1))
        return fill.caseInsensitiveCompare("auto") != .orderedSame
            && fill.caseInsensitiveCompare("FFFFFF") != .orderedSame
    }

    /// The 0-based outline level for a pStyle val, or nil if it isn't a heading.
    private static func headingLevel(_ val: String) -> Int? {
        guard let m = headingRE.firstMatch(in: val, range: NSRange(val.startIndex..., in: val)) else { return nil }
        guard let n = Int((val as NSString).substring(with: m.range(at: 1))) else { return nil }
        return n - 1
    }

    private static func toggleOn(_ tag: String) -> Bool {
        guard let m = valRE.firstMatch(in: tag, range: NSRange(tag.startIndex..., in: tag)) else { return true }
        let v = (tag as NSString).substring(with: m.range(at: 1))
        return v != "false" && v != "0" && v != "off" && v != "none"
    }

    public static func build(_ xml: String) -> PlainMap {
        var plain = ""
        var offsets: [Int] = []
        var formats: [FormatSpan] = []
        var headings: [Heading] = []
        var paragraphStyles: [ParagraphStyleSpan] = []
        // Outline tracking: where the current paragraph's text begins in plain (UTF-16),
        // and its heading level (nil = not a heading), captured from <w:pStyle>.
        var paraStart = 0
        var paraHeadingLevel: Int? = nil
        // Paragraph border/shading tracking (only meaningful while !inRun).
        var inPBdr = false
        var paraHasBorder = false
        var paraHasShd = false

        var inRun = false
        var runBold = false
        var runItalic = false

        var i = xml.startIndex

        while i < xml.endIndex {
            guard let ltIdx = xml[i...].firstIndex(of: "<") else { break }
            guard let gtIdx = xml[ltIdx...].firstIndex(of: ">") else { break }

            // UTF-16 offset of tag start (used for xmlOffsets of synthetic chars)
            let tagStartUtf16 = xml.utf16.distance(from: xml.startIndex, to: ltIdx)

            let tag = String(xml[ltIdx...gtIdx]) // includes '<' and '>'
            let isEnd = tag.hasPrefix("</")
            let isSelfClose = tag.hasSuffix("/>")

            // Element name
            let nameStartOff = isEnd ? 2 : 1
            var nameEnd = tag.index(tag.startIndex, offsetBy: nameStartOff)
            while nameEnd < tag.endIndex {
                let c = tag[nameEnd]
                if c == " " || c == "\t" || c == "\n" || c == "\r" || c == "/" || c == ">" { break }
                nameEnd = tag.index(after: nameEnd)
            }
            let name = String(tag[tag.index(tag.startIndex, offsetBy: nameStartOff)..<nameEnd])

            if !isEnd && !isSelfClose && name == "w:t" {
                let contentStart = xml.index(after: gtIdx)
                let contentStartUtf16 = xml.utf16.distance(from: xml.startIndex, to: contentStart)

                if let closeRange = xml.range(of: wtClose, range: contentStart..<xml.endIndex) {
                    let rawContent = String(xml[contentStart..<closeRange.lowerBound])
                    let decoded = XmlEntities.decode(rawContent)
                    let spanStart = plain.utf16.count
                    // One xmlOffset per UTF-16 code unit — mirrors Kotlin's char-by-char loop.
                    // Emoji (2 UTF-16 code units) get 2 consecutive offsets; the string itself
                    // is appended whole so surrogate pairs are preserved correctly.
                    for j in 0..<decoded.utf16.count {
                        offsets.append(contentStartUtf16 + j)
                    }
                    plain += decoded
                    if (runBold || runItalic) && plain.utf16.count > spanStart {
                        formats.append(FormatSpan(start: spanStart, end: plain.utf16.count, bold: runBold, italic: runItalic))
                    }
                    i = closeRange.upperBound
                    continue
                } else {
                    i = xml.endIndex
                    continue
                }
            }

            switch (isEnd, isSelfClose, name) {
            case (false, _, "w:tab"):
                plain += "\t"; offsets.append(tagStartUtf16)
            case (false, _, "w:br"), (false, _, "w:cr"):
                plain += "\n"; offsets.append(tagStartUtf16)
            case (false, false, "w:p"):
                paraStart = plain.utf16.count
                paraHeadingLevel = nil
                inPBdr = false; paraHasBorder = false; paraHasShd = false
            case (true, _, "w:p"):
                // Record the outline entry from this paragraph's text BEFORE the
                // trailing newline is appended. Skip blank headings.
                if let lvl = paraHeadingLevel {
                    let lo = plain.utf16.index(plain.utf16.startIndex, offsetBy: paraStart)
                    let text = String(decoding: Array(plain.utf16[lo...]), as: UTF16.self)
                        .trimmingCharacters(in: .whitespacesAndNewlines)
                    if !text.isEmpty { headings.append(Heading(text: text, level: lvl, charOffset: paraStart)) }
                }
                if paraHasBorder || paraHasShd {
                    paragraphStyles.append(ParagraphStyleSpan(start: paraStart, end: plain.utf16.count, blockquote: true))
                }
                plain += "\n"; offsets.append(tagStartUtf16)
            case (false, _, "w:pStyle") where !inRun:
                if let m = valRE.firstMatch(in: tag, range: NSRange(tag.startIndex..., in: tag)) {
                    paraHeadingLevel = headingLevel((tag as NSString).substring(with: m.range(at: 1)))
                }
            case (false, false, "w:pBdr") where !inRun:
                inPBdr = true
            case (true, _, "w:pBdr"):
                inPBdr = false
            case (false, true, "w:left") where inPBdr:
                paraHasBorder = true
            case (false, true, "w:top") where inPBdr:
                paraHasBorder = true
            case (false, true, "w:bottom") where inPBdr:
                paraHasBorder = true
            case (false, true, "w:right") where inPBdr:
                paraHasBorder = true
            case (false, true, "w:shd") where !inRun && isRealFill(tag):
                paraHasShd = true
            case (false, false, "w:r"):
                inRun = true; runBold = false; runItalic = false
            case (true, _, "w:r"):
                inRun = false; runBold = false; runItalic = false
            case (false, _, "w:b") where inRun:
                runBold = toggleOn(tag)
            case (false, _, "w:i") where inRun:
                runItalic = toggleOn(tag)
            default:
                break
            }

            i = xml.index(after: gtIdx)
        }

        return PlainMap(plain: plain, xmlOffsets: offsets, formats: formats, headings: headings, paragraphStyles: paragraphStyles)
    }
}

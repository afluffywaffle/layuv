import Foundation

/// A bold and/or italic run over [start, end) char offsets into PlainMap.plain.
public struct FormatSpan: Equatable {
    public let start: Int
    public let end: Int
    public let bold: Bool
    public let italic: Bool
}

/// The canonical plain text plus, for each of its UTF-16 code units, the char offset
/// xmlOffsets into the source document.xml. The two are parallel: xmlOffsets.count == plain.utf16.count.
///
/// formats is a read-only, additive overlay (direct w:b/w:i run formatting) for display only.
public struct PlainMap {
    public let plain: String
    public let xmlOffsets: [Int]
    public let formats: [FormatSpan]

    public init(plain: String, xmlOffsets: [Int], formats: [FormatSpan] = []) {
        self.plain = plain
        self.xmlOffsets = xmlOffsets
        self.formats = formats
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

    private static let wtClose = "</w:t>"
    private static let valRE = try! NSRegularExpression(pattern: #"w:val="([^"]*)""#)

    private static func toggleOn(_ tag: String) -> Bool {
        guard let m = valRE.firstMatch(in: tag, range: NSRange(tag.startIndex..., in: tag)) else { return true }
        let v = (tag as NSString).substring(with: m.range(at: 1))
        return v != "false" && v != "0" && v != "off" && v != "none"
    }

    public static func build(_ xml: String) -> PlainMap {
        var plain = ""
        var offsets: [Int] = []
        var formats: [FormatSpan] = []

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
            case (true, _, "w:p"):
                plain += "\n"; offsets.append(tagStartUtf16)
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

        return PlainMap(plain: plain, xmlOffsets: offsets, formats: formats)
    }
}

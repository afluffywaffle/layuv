import Foundation

/// Converts a plain-text file into a clean, valid `.docx` so the DOCX-only reader can open it
/// (the "import non-DOCX → flatten to .docx" path). The original text file is never modified — the
/// caller writes the returned bytes to a NEW `.docx` working file.
///
/// Also recognises the front-matter header written by the sibling **écri** writing app, which embeds
/// per-document settings at the top of its `.txt` files:
///
///     ---
///     écri-theme: parchment|bone|dusk|sage|night
///     écri-font: serif|sans
///     écri-page: <int>
///     écri-dark: on|off
///     ---
///     <body…>
///
/// The header is STRIPPED from the imported body (else it would become literal first-paragraph text)
/// and its fields returned as neutral values — the app layer maps `ecriThemeRaw`→PaperTheme and
/// `ecriFontSerif`→FontChoice (the engine stays free of app UI types). Non-écri text simply has no
/// header and imports verbatim.
public enum TextImport {

    public struct Imported {
        public let text: String
        /// écri-theme rawValue (e.g. "parchment"), or nil. Maps 1:1 to the app's PaperTheme.
        public let ecriThemeRaw: String?
        /// écri-font: true = serif, false = sans, nil = unspecified.
        public let ecriFontSerif: Bool?
        /// écri-page (1-based), or nil.
        public let ecriPage: Int?
    }

    // MARK: - Front matter

    /// Parse an écri-style front-matter header off the top of `raw`, returning the body + fields.
    /// Mirrors écri's own `parseFrontMatter` (DocumentStore.swift): requires a leading `---\n` and a
    /// closing `\n---\n`; each line is `key: value`.
    public static func parse(_ raw: String) -> Imported {
        guard raw.hasPrefix("---\n") else {
            return Imported(text: raw, ecriThemeRaw: nil, ecriFontSerif: nil, ecriPage: nil)
        }
        let afterOpen = raw.dropFirst(4)
        guard let closeRange = afterOpen.range(of: "\n---\n") else {
            return Imported(text: raw, ecriThemeRaw: nil, ecriFontSerif: nil, ecriPage: nil)
        }
        let header = String(afterOpen[..<closeRange.lowerBound])
        let body   = String(afterOpen[closeRange.upperBound...])

        var themeRaw: String?
        var fontSerif: Bool?
        var page: Int?
        for line in header.components(separatedBy: "\n") {
            let parts = line.split(separator: ":", maxSplits: 1).map {
                $0.trimmingCharacters(in: .whitespaces)
            }
            guard parts.count == 2 else { continue }
            switch parts[0] {
            case "écri-theme": themeRaw  = parts[1]
            case "écri-font":  fontSerif = (parts[1] == "serif")
            case "écri-page":  page      = Int(parts[1])
            default: break   // écri-dark and any unknown keys ignored
            }
        }
        return Imported(text: body, ecriThemeRaw: themeRaw, ecriFontSerif: fontSerif, ecriPage: page)
    }

    // MARK: - DOCX assembly

    /// Build a complete, minimal, Word/Pages/Google-Docs-compatible `.docx` from plain `text`.
    /// Assembles the three required parts from scratch (no template file needed) — `[Content_Types]`,
    /// the package rels, and `word/document.xml` (paragraphs + a Letter `sectPr`).
    public static func docx(from text: String) throws -> Data {
        var entries = MutableDocxEntries([])
        entries[contentTypesPath] = Data(contentTypesXml.utf8)
        entries[packageRelsPath]  = Data(packageRelsXml.utf8)
        entries[documentPath]     = Data(documentXml(text).utf8)
        return try DocxArchive.write(entries)
    }

    // MARK: - Parts

    private static let contentTypesPath = "[Content_Types].xml"
    private static let packageRelsPath  = "_rels/.rels"
    private static let documentPath     = "word/document.xml"

    private static let contentTypesXml =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
        "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
        "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
        "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
        "</Types>"

    private static let packageRelsXml =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
        "</Relationships>"

    private static func documentXml(_ text: String) -> String {
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
        "<w:body>" + paragraphs(text) +
        "<w:sectPr><w:pgSz w:w=\"12240\" w:h=\"15840\"/>" +
        "<w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" w:header=\"720\" w:footer=\"720\" w:gutter=\"0\"/>" +
        "</w:sectPr></w:body></w:document>"
    }

    /// Splits `text` into paragraphs on blank lines; a single newline within a paragraph becomes a
    /// soft `<w:br/>`. Each line is XML-escaped with xml:space="preserve". Mirrors DocxFromText.
    private static func paragraphs(_ text: String) -> String {
        let normalized = text
            .replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if normalized.isEmpty { return "<w:p/>" }
        var sb = ""
        for block in splitOnBlankLines(normalized) {
            sb += "<w:p>"
            for (i, line) in block.components(separatedBy: "\n").enumerated() {
                if i > 0 { sb += "<w:r><w:br/></w:r>" }
                sb += "<w:r><w:t xml:space=\"preserve\">" + XmlEntities.escape(line) + "</w:t></w:r>"
            }
            sb += "</w:p>"
        }
        return sb
    }

    private static let blankLineRegex = try! NSRegularExpression(pattern: "\n[ \t]*\n")

    private static func splitOnBlankLines(_ s: String) -> [String] {
        let ns = s as NSString
        var result: [String] = []
        var last = 0
        for m in blankLineRegex.matches(in: s, range: NSRange(location: 0, length: ns.length)) {
            result.append(ns.substring(with: NSRange(location: last, length: m.range.location - last)))
            last = m.range.location + m.range.length
        }
        result.append(ns.substring(from: last))
        return result
    }
}

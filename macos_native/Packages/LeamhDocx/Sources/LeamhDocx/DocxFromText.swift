import Foundation

/// Builds a CLEAN, annotation-less, conversation-less `.docx` from rewritten chapter
/// `text` by CLONING `sourceDocx`'s archive — so the new draft inherits the original's
/// styles.xml, rels, theme, and page setup (`<w:sectPr>`) and round-trips with Word /
/// Pages / Google Docs — then regenerating ONLY the body of word/document.xml and
/// stripping every Léamh sidecar, comment part, and ink medium. Mirror of DocxFromText.kt.
///
/// Pure: the only XML it emits is plain paragraphs, escaped via XmlEntities. Used by the
/// "Save as draft" action after an AI rewrite.
public enum DocxFromText {

    private static let documentPath = "word/document.xml"
    private static let cleanPath    = "leamh/document_clean.xml"
    private static let docRelsPath  = "word/_rels/document.xml.rels"

    private static let sectClose = "</w:sectPr>"
    private static let bodyOpenRegex = try! NSRegularExpression(pattern: "<w:body\\b[^>]*>")
    // A Relationship whose Type ends in one of the comment-family parts we strip.
    private static let commentRelRegex = try! NSRegularExpression(
        pattern: "<Relationship\\b[^>]*(/comments|/commentsExtended|/commentsIds|/people)\"[^>]*/>"
    )

    /// Parts left over from an annotated / conversational copy — removed wholesale.
    private static let stripParts = [
        "leamh/annotations.json",
        "leamh/position.json",
        cleanPath,
        "leamh/aichat.json",
        "word/comments.xml",
        "word/commentsExtended.xml",
        "word/commentsIds.xml",
        "word/people.xml",
        "word/_rels/comments.xml.rels",
    ]

    public static func build(sourceDocx: Data, text: String) throws -> Data {
        let archive = try DocxArchive.read(sourceDocx)
        var entries = archive.toMutableEntries()

        // Body source: prefer the un-injected clean snapshot, else the live document.
        guard let source = archive.text(named: cleanPath) ?? archive.text(named: documentPath) else {
            throw DocxError.invalidArchive
        }

        entries[documentPath] = Data(replaceBody(source, text).utf8)

        for part in stripParts { entries[part] = nil }
        entries.removeAll { $0.hasPrefix("word/media/ink_") }
        if let rels = entries[docRelsPath], let s = String(data: rels, encoding: .utf8) {
            let cleaned = commentRelRegex.stringByReplacingMatches(
                in: s, range: NSRange(location: 0, length: (s as NSString).length), withTemplate: "")
            entries[docRelsPath] = Data(cleaned.utf8)
        }

        return try DocxArchive.write(entries, source: archive)
    }

    /// Replaces the inner content of `<w:body>` with paragraphs from `text`, preserving any
    /// trailing body-level `<w:sectPr>`. Falls back to a minimal rebuild if no body is found.
    private static func replaceBody(_ documentXml: String, _ text: String) -> String {
        let ns = documentXml as NSString
        let full = NSRange(location: 0, length: ns.length)
        guard let bodyOpen = bodyOpenRegex.firstMatch(in: documentXml, range: full) else {
            return minimalDocument(text)
        }
        let bodyClose = ns.range(of: "</w:body>", options: .backwards).location
        let innerStart = bodyOpen.range.location + bodyOpen.range.length
        if bodyClose == NSNotFound || bodyClose < innerStart {
            return minimalDocument(text)
        }
        let inner = ns.substring(with: NSRange(location: innerStart, length: bodyClose - innerStart))
        let sectPr = lastSectPr(inner) ?? ""
        return ns.substring(to: innerStart) + paragraphs(text) + sectPr + ns.substring(from: bodyClose)
    }

    /// The body-level sectPr is the final child of `<w:body>`; capture the last one verbatim.
    private static func lastSectPr(_ inner: String) -> String? {
        let ns = inner as NSString
        let open = ns.range(of: "<w:sectPr", options: .backwards).location
        if open == NSNotFound { return nil }
        let tagEnd = ns.range(of: ">", range: NSRange(location: open, length: ns.length - open)).location
        if tagEnd == NSNotFound { return nil }
        if ns.character(at: tagEnd - 1) == UInt16(UnicodeScalar("/").value) {  // self-closing
            return ns.substring(with: NSRange(location: open, length: tagEnd + 1 - open))
        }
        let close = ns.range(of: sectClose, range: NSRange(location: tagEnd, length: ns.length - tagEnd)).location
        if close == NSNotFound {
            return ns.substring(with: NSRange(location: open, length: tagEnd + 1 - open))
        }
        return ns.substring(with: NSRange(location: open, length: close + sectClose.count - open))
    }

    /// Splits `text` into paragraphs on blank lines; a single newline inside a paragraph
    /// becomes a soft `<w:br/>`. Each line is XML-escaped and carries xml:space="preserve".
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

    /// Mirrors Kotlin String.split(Regex): the segments between matches, including a trailing one.
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

    private static func minimalDocument(_ text: String) -> String {
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
        "<w:body>" + paragraphs(text) + "</w:body></w:document>"
    }
}

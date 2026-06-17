import Foundation

/// Imports word/comments.xml — legacy Léamh format and native Word comments.
/// Read fallback used when leamh/annotations.json is absent. Mirrors LegacyComments.kt.
enum LegacyComments {

    private static let comment = try! NSRegularExpression(
        pattern: #"<w:comment\s([^>]*)>(.*?)</w:comment>"#,
        options: .dotMatchesLineSeparators
    )
    private static let idAttr     = try! NSRegularExpression(pattern: #"w:id="([^"]*)""#)
    private static let authorAttr = try! NSRegularExpression(pattern: #"w:author="([^"]*)""#)
    private static let dateAttr   = try! NSRegularExpression(pattern: #"w:date="([^"]*)""#)
    private static let wt         = try! NSRegularExpression(
        pattern: #"<w:t[^>]*>(.*?)</w:t>"#,
        options: .dotMatchesLineSeparators
    )
    // [tool:X] [tag:Y] N% — "text"  (— is U+2014)
    private static let legacy = try! NSRegularExpression(
        pattern: #"\[tool:(\w+)\](?:\s\[tag:(\w+)\])?\s(\d+)%\s\u{2014}\s"(.*)""#,
        options: .dotMatchesLineSeparators
    )

    static func parseComments(_ commentsXml: String, documentXml: String, map: PlainMap) -> [Annotation] {
        var results: [Annotation] = []
        let ns = commentsXml as NSString
        let full = NSRange(location: 0, length: ns.length)

        for cm in comment.matches(in: commentsXml, range: full) {
            let attrs = ns.substring(with: cm.range(at: 1))
            let body  = ns.substring(with: cm.range(at: 2))

            let commentId  = firstGroup(idAttr,     in: attrs) ?? ""
            let authorRaw  = XmlEntities.decode(firstGroup(authorAttr, in: attrs) ?? "")
            guard let dateStr = firstGroup(dateAttr, in: attrs) else { continue }
            let timestamp  = Timestamps.parse(dateStr)

            let bns = body as NSString
            let texts = wt.matches(in: body, range: NSRange(location: 0, length: bns.length))
                .map { XmlEntities.decode(bns.substring(with: $0.range(at: 1))) }
            guard !texts.isEmpty else { continue }

            // Try legacy Léamh format first
            var legacyMatchPair: (match: NSTextCheckingResult, text: String)?
            for t in texts {
                if let m = legacy.firstMatch(in: t, range: NSRange(t.startIndex..., in: t)) {
                    legacyMatchPair = (m, t)
                    break
                }
            }

            if let (lm, legacyText) = legacyMatchPair, !authorRaw.isEmpty {
                let lns = legacyText as NSString
                let toolName = lns.substring(with: lm.range(at: 1))
                let tagName  = lns.substring(with: lm.range(at: 2))
                let pctStr   = lns.substring(with: lm.range(at: 3))
                let selText  = lns.substring(with: lm.range(at: 4))
                let headerIdx = texts.firstIndex(where: {
                    legacy.firstMatch(in: $0, range: NSRange($0.startIndex..., in: $0)) != nil
                })!
                let noteTexts = Array(texts[(headerIdx + 1)...])
                let note = noteTexts.isEmpty ? nil : noteTexts.joined(separator: "\n")
                results.append(Annotation(
                    id: authorRaw,
                    selectedText: selText,
                    prefix: "",
                    suffix: "",
                    tool: AnnotationTool.fromName(toolName),
                    note: (note?.isEmpty ?? true) ? nil : note,
                    tag: tagName.isEmpty ? nil : AnnotationTag.fromName(tagName),
                    timestamp: timestamp,
                    position: (Double(pctStr) ?? 0) / 100.0
                ))
                continue
            }

            // Native Word comment
            guard !commentId.isEmpty else { continue }
            let ex = extractFromCommentRange(documentXml: documentXml, commentId: commentId, map: map)
            guard !ex.text.isEmpty else { continue }
            let note = texts
                .filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
                .joined(separator: " ")
                .trimmingCharacters(in: .whitespaces)
            results.append(Annotation(
                id: "word_\(commentId)",
                selectedText: ex.text,
                prefix: ex.prefix,
                suffix: ex.suffix,
                tool: .comment,
                note: note.isEmpty ? nil : note,
                timestamp: timestamp,
                position: ex.position
            ))
        }
        return results.sorted(by: { $0.position < $1.position })
    }

    private struct Extracted { let text: String; let prefix: String; let suffix: String; let position: Double }

    private static func extractFromCommentRange(documentXml: String, commentId: String, map: PlainMap) -> Extracted {
        let startMarker = "<w:commentRangeStart w:id=\"\(commentId)\"/>"
        let endMarker   = "<w:commentRangeEnd w:id=\"\(commentId)\"/>"
        let dns = documentXml as NSString
        let si = dns.range(of: startMarker).location
        let ei = dns.range(of: endMarker).location
        guard si != NSNotFound, ei != NSNotFound, ei > si else {
            return Extracted(text: "", prefix: "", suffix: "", position: 0)
        }

        let segStart = si + (startMarker as NSString).length
        let segment  = dns.substring(with: NSRange(location: segStart, length: ei - segStart))
        let sns = segment as NSString
        let text = wt.matches(in: segment, range: NSRange(location: 0, length: sns.length))
            .map { XmlEntities.decode(sns.substring(with: $0.range(at: 1))) }
            .joined()
        guard !text.isEmpty else { return Extracted(text: "", prefix: "", suffix: "", position: 0) }

        var plainIdx = 0
        for k in map.xmlOffsets.indices {
            if map.xmlOffsets[k] >= segStart { plainIdx = k; break }
        }
        let plain = map.plain
        let totalUtf16 = plain.utf16.count
        let position = totalUtf16 > 0
            ? max(0.0, min(1.0, Double(plainIdx) / Double(totalUtf16)))
            : 0.0
        let plainEnd = min(plainIdx + text.utf16.count, totalUtf16)
        let prefixStart = max(0, plainIdx - 20)
        let prefix = plain.utf16Substring(from: prefixStart, length: plainIdx - prefixStart)
        let suffixEnd = min(plainEnd + 20, totalUtf16)
        let suffix = plain.utf16Substring(from: plainEnd, length: suffixEnd - plainEnd)
        return Extracted(text: text, prefix: prefix, suffix: suffix, position: position)
    }

    private static func firstGroup(_ regex: NSRegularExpression, in s: String) -> String? {
        guard let m = regex.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)) else { return nil }
        return (s as NSString).substring(with: m.range(at: 1))
    }
}

private extension String {
    func utf16Substring(from offset: Int, length: Int) -> String {
        let utf16 = self.utf16
        guard offset >= 0, length >= 0, offset + length <= utf16.count else { return "" }
        let start = utf16.index(utf16.startIndex, offsetBy: offset)
        let end   = utf16.index(start, offsetBy: length)
        return String(utf16[start..<end]) ?? ""
    }

    init?(_ view: String.UTF16View.SubSequence) {
        var s = ""
        for unit in view {
            if let scalar = Unicode.Scalar(unit) { s.append(Character(scalar)) }
            else { return nil }
        }
        self = s
    }
}

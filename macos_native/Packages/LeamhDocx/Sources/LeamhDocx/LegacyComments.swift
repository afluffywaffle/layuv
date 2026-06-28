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
    // [tool:X] [tag:Y] N% — "text"  (— is U+2014; literal here — `\u{2014}` is a Swift escape, not
    // an ICU one, so inside a raw string it would be an invalid pattern).
    private static let legacy = try! NSRegularExpression(
        pattern: #"\[tool:(\w+)\](?:\s\[tag:(\w+)\])?\s(\d+)%\s—\s"(.*)""#,
        options: .dotMatchesLineSeparators
    )
    // Comment-thread (Word reply) detection — mirrors LegacyComments.kt.
    private static let paraIdAttr       = try! NSRegularExpression(pattern: #"paraId="([^"]*)""#)
    private static let paraIdParentAttr = try! NSRegularExpression(pattern: #"paraIdParent="([^"]*)""#)
    private static let commentEx        = try! NSRegularExpression(pattern: #"<w15:commentEx\b([^>]*)>"#)

    private struct RawComment {
        let commentId: String
        let author: String
        let timestamp: Date
        let texts: [String]
        let paraIds: [String]
    }

    static func parseComments(_ commentsXml: String, documentXml: String, map: PlainMap,
                              commentsExtendedXml: String? = nil) -> [Annotation] {
        let ns = commentsXml as NSString
        let full = NSRange(location: 0, length: ns.length)

        // Phase 1: parse every <w:comment> once, indexing every paraId -> its comment id.
        var raws: [RawComment] = []
        var paraIdToCommentId: [String: String] = [:]
        for cm in comment.matches(in: commentsXml, range: full) {
            let attrs = ns.substring(with: cm.range(at: 1))
            let body  = ns.substring(with: cm.range(at: 2))
            let commentId  = firstGroup(idAttr,     in: attrs) ?? ""
            let authorRaw  = XmlEntities.decode(firstGroup(authorAttr, in: attrs) ?? "")
            guard let dateStr = firstGroup(dateAttr, in: attrs) else { continue }
            let bns = body as NSString
            let texts = wt.matches(in: body, range: NSRange(location: 0, length: bns.length))
                .map { XmlEntities.decode(bns.substring(with: $0.range(at: 1))) }
            guard !texts.isEmpty else { continue }
            let paraIds = allGroups(paraIdAttr, in: body)
            let rc = RawComment(commentId: commentId, author: authorRaw,
                                timestamp: Timestamps.parse(dateStr), texts: texts, paraIds: paraIds)
            raws.append(rc)
            for pid in paraIds { paraIdToCommentId[pid] = commentId }
        }

        // Phase 2: resolve Word reply links to the ROOT (non-reply) ancestor of each chain.
        let replyParentByParaId = parseReplyLinks(commentsExtendedXml)
        var immediateParent: [String: String] = [:]
        if !replyParentByParaId.isEmpty {
            for rc in raws {
                guard let parentParaId = rc.paraIds.compactMap({ replyParentByParaId[$0] }).first,
                      let parentCid = paraIdToCommentId[parentParaId],
                      parentCid != rc.commentId else { continue }
                immediateParent[rc.commentId] = parentCid
            }
        }
        let replyIds = Set(immediateParent.keys)
        var rootOf: [String: String] = [:]
        for replyId in replyIds {
            var cur = replyId
            var steps = 0
            while let p = immediateParent[cur], steps <= raws.count { cur = p; steps += 1 }
            rootOf[replyId] = cur
        }
        var repliesByRoot: [String: [RawComment]] = [:]
        for rc in raws where replyIds.contains(rc.commentId) {
            repliesByRoot[rootOf[rc.commentId] ?? rc.commentId, default: []].append(rc)
        }

        // Phase 3: build annotations for the non-reply comments, attaching reply threads.
        var results: [Annotation] = []
        for rc in raws where !replyIds.contains(rc.commentId) {
            guard let ann = buildAnnotation(rc, documentXml: documentXml, map: map) else { continue }
            if let replies = repliesByRoot[rc.commentId], !replies.isEmpty {
                results.append(ann.copy(threadEntries: buildThread(parent: ann, parentRaw: rc, replies: replies)))
            } else {
                results.append(ann)
            }
        }
        return results.sorted(by: { $0.position < $1.position })
    }

    private static func buildAnnotation(_ rc: RawComment, documentXml: String, map: PlainMap) -> Annotation? {
        let texts = rc.texts
        // Legacy Léamh format first: [tool:X] [tag:Y] N% — "text"
        var legacyMatchPair: (match: NSTextCheckingResult, text: String)?
        for t in texts {
            if let m = legacy.firstMatch(in: t, range: NSRange(t.startIndex..., in: t)) {
                legacyMatchPair = (m, t); break
            }
        }
        if let (lm, legacyText) = legacyMatchPair, !rc.author.isEmpty {
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
            return Annotation(
                id: rc.author, selectedText: selText, prefix: "", suffix: "",
                tool: AnnotationTool.fromName(toolName),
                note: (note?.isEmpty ?? true) ? nil : note,
                tag: tagName.isEmpty ? nil : AnnotationTag.fromName(tagName),
                timestamp: rc.timestamp, position: (Double(pctStr) ?? 0) / 100.0
            )
        }
        // Native Word comment.
        guard !rc.commentId.isEmpty else { return nil }
        let ex = extractFromCommentRange(documentXml: documentXml, commentId: rc.commentId, map: map)
        guard !ex.text.isEmpty else { return nil }
        let note = nativeNoteText(texts)
        return Annotation(
            id: "word_\(rc.commentId)", selectedText: ex.text, prefix: ex.prefix, suffix: ex.suffix,
            tool: .comment, note: note.isEmpty ? nil : note, timestamp: rc.timestamp, position: ex.position
        )
    }

    /// Flattens a parent comment plus its Word replies into a chronological thread. Entry 0 is the
    /// parent's own text (== the annotation's note); each reply follows in timestamp order. All
    /// source="word" (read-only in Léamh). Mirrors LegacyComments.buildThread in Kotlin.
    private static func buildThread(parent: Annotation, parentRaw: RawComment,
                                    replies: [RawComment]) -> [ThreadEntry] {
        var entries: [ThreadEntry] = []
        if let parentText = parent.note, !parentText.isEmpty {
            entries.append(ThreadEntry(text: parentText, timestamp: epochMillis(parentRaw.timestamp),
                                       source: ThreadEntry.sourceWord))
        }
        for r in replies.sorted(by: { $0.timestamp < $1.timestamp }) {
            let text = nativeNoteText(r.texts)
            if !text.isEmpty {
                entries.append(ThreadEntry(text: text, timestamp: epochMillis(r.timestamp),
                                           source: ThreadEntry.sourceWord))
            }
        }
        return entries
    }

    private static func nativeNoteText(_ texts: [String]) -> String {
        texts.filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
            .joined(separator: " ")
            .trimmingCharacters(in: .whitespaces)
    }

    private static func epochMillis(_ d: Date) -> Int64 { Int64((d.timeIntervalSince1970 * 1000).rounded()) }

    /// child paraId -> parent paraId, for every `<w15:commentEx>` with a parent.
    private static func parseReplyLinks(_ xml: String?) -> [String: String] {
        guard let xml else { return [:] }
        var links: [String: String] = [:]
        let ns = xml as NSString
        for m in commentEx.matches(in: xml, range: NSRange(location: 0, length: ns.length)) {
            let attrs = ns.substring(with: m.range(at: 1))
            guard let parent = firstGroup(paraIdParentAttr, in: attrs),
                  let paraId = firstGroup(paraIdAttr, in: attrs) else { continue }
            links[paraId] = parent
        }
        return links
    }

    private static func allGroups(_ regex: NSRegularExpression, in s: String) -> [String] {
        let ns = s as NSString
        return regex.matches(in: s, range: NSRange(location: 0, length: ns.length))
            .map { ns.substring(with: $0.range(at: 1)) }
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

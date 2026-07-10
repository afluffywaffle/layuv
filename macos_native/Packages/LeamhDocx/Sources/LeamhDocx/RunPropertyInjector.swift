import Foundation

/// Injects run properties and comment/bookmark anchors into word/document.xml.
/// Mirrors RunPropertyInjector.kt — same run-splitting algorithm, UTF-16 offsets.
enum RunPropertyInjector {

    private static let runOpen = try! NSRegularExpression(pattern: #"<w:r(?:\s[^>]*)?>(?<!/>)"#)
    private static let runClose = try! NSRegularExpression(pattern: #"</w:r>"#)
    private static let wt = try! NSRegularExpression(
        pattern: #"<w:t(?:[^>]*)>(.*?)</w:t>"#, options: .dotMatchesLineSeparators
    )
    private static let rprBlock = try! NSRegularExpression(
        pattern: #"<w:rPr>.*?</w:rPr>"#, options: .dotMatchesLineSeparators
    )
    private static let rprChange = try! NSRegularExpression(
        pattern: #"<w:rPrChange\b[^>]*>.*?</w:rPrChange>"#, options: .dotMatchesLineSeparators
    )
    private static let stripCrStart = try! NSRegularExpression(pattern: #"<w:commentRangeStart\b[^>]*/>"#)
    private static let stripCrEnd   = try! NSRegularExpression(pattern: #"<w:commentRangeEnd\b[^>]*/>"#)
    private static let stripCrRef   = try! NSRegularExpression(
        pattern: #"<w:r><w:rPr><w:rStyle w:val="CommentReference"/></w:rPr><w:commentReference\b[^>]*/></w:r>"#
    )
    private static let nonId = try! NSRegularExpression(pattern: #"[^a-zA-Z0-9_]"#)

    static func rPrForTool(_ tool: AnnotationTool) -> String {
        switch tool {
        case .highlight, .comment, .inkAnnotation:
            return "<w:highlight w:val=\"yellow\"/>"
        case .underline:
            return "<w:u w:val=\"single\"/>"
        case .doubleUnderline:
            return "<w:u w:val=\"double\"/>"
        case .strikethrough:
            return "<w:strike/>"
        case .wavyUnderline:
            return "<w:u w:val=\"wave\"/>"
        // Neither writes a run property: bookmark is margin-icon-only; blockquote is
        // whole-paragraph and persists purely in the annotations.json sidecar (never
        // written into document.xml, unlike every other tool here).
        case .bookmark, .blockquote:
            return ""
        }
    }

    private struct AnchorIns {
        let commentId: Int
        let selectedText: String; let prefix: String; let suffix: String; let position: Double
    }
    private struct BookmarkIns {
        let annotationId: String
        let selectedText: String; let prefix: String; let suffix: String; let position: Double
    }
    private struct Ins { let pos: Int; let tag: String }

    static func inject(
        _ documentXml: String,
        annotations: [Annotation],
        noteAnnotations: [Annotation]
    ) -> String {
        if annotations.isEmpty { return documentXml }
        var xml = documentXml

        // Strip pre-existing comment markers
        xml = stripAll(xml, regex: stripCrStart)
        xml = stripAll(xml, regex: stripCrEnd)
        xml = stripAll(xml, regex: stripCrRef)

        var noteCommentId: [String: Int] = [:]
        for (i, a) in noteAnnotations.enumerated() { noteCommentId[a.id] = i }

        var anchorInsertions: [AnchorIns] = []
        var bookmarkInsertions: [BookmarkIns] = []

        for a in annotations {
            let map = PlainTextMapper.build(xml)
            guard !map.plain.isEmpty else { continue }

            guard let loc = Anchoring.locateInPlain(
                map.plain, selectedText: a.selectedText,
                prefix: a.prefix, suffix: a.suffix, positionHint: a.position
            ) else { continue }

            var runOpens = allMatches(runOpen, in: xml)
            var runCloses = allMatches(runClose, in: xml)

            let startXmlPos = map.xmlOffsets[loc.start]
            let endXmlPos   = map.xmlOffsets[loc.end - 1]

            var sIdx = findRunIdxBS(runOpens, xmlPos: startXmlPos)
            var eIdx = findRunIdxBS(runOpens, xmlPos: endXmlPos)
            guard sIdx >= 0, eIdx >= 0 else { continue }
            guard let startRC = findRunClose(runCloses, after: runOpens[sIdx]) else { continue }
            guard let endRC   = findRunClose(runCloses, after: runOpens[eIdx]) else { continue }

            if a.tool == .bookmark {
                bookmarkInsertions.append(BookmarkIns(
                    annotationId: a.id,
                    selectedText: a.selectedText, prefix: a.prefix, suffix: a.suffix, position: a.position
                ))
                continue
            }

            let rPrContent = rPrForTool(a.tool)
            guard !rPrContent.isEmpty else {
                // Blockquote is a whole-paragraph tool with no run property, so it skips the
                // run-splitting/rPr injection below. But it can still carry a note — register
                // its comment anchor here before bailing, or Word never sees the comment (the
                // range markers that reference the comment id would never be emitted).
                if let commentId = noteCommentId[a.id] {
                    anchorInsertions.append(AnchorIns(
                        commentId: commentId,
                        selectedText: a.selectedText, prefix: a.prefix, suffix: a.suffix, position: a.position
                    ))
                }
                continue
            }

            let startOffset = approxCharOffsetInRun(xml, runOpen: runOpens[sIdx], runClose: startRC, xmlCharPos: startXmlPos)
            let endOffset   = approxCharOffsetInRun(xml, runOpen: runOpens[eIdx], runClose: endRC,   xmlCharPos: endXmlPos)
            let startRunLen = getRunPlainText(xml, runOpen: runOpens[sIdx], runClose: startRC).utf16.count
            let endRunLen   = getRunPlainText(xml, runOpen: runOpens[eIdx], runClose: endRC).utf16.count

            let needStartSplit = startOffset > 0 && startRunLen > 1
            let needEndSplit   = (endOffset + 1) < endRunLen && endRunLen > 1

            if sIdx == eIdx {
                if needEndSplit {
                    let newXml = splitRunAt(xml, runOpen: runOpens[sIdx], runClose: startRC, charPos: endOffset + 1)
                    if newXml != xml {
                        xml = newXml
                        runOpens  = allMatches(runOpen,  in: xml)
                        runCloses = allMatches(runClose, in: xml)
                    }
                }
                if needStartSplit {
                    let ro = runOpens[sIdx]
                    let rc = findRunClose(runCloses, after: ro)!
                    let newXml = splitRunAt(xml, runOpen: ro, runClose: rc, charPos: startOffset)
                    if newXml != xml {
                        xml = newXml
                        runOpens  = allMatches(runOpen,  in: xml)
                        runCloses = allMatches(runClose, in: xml)
                        sIdx += 1
                    }
                }
                eIdx = sIdx
            } else {
                if needEndSplit {
                    let newXml = splitRunAt(xml, runOpen: runOpens[eIdx], runClose: endRC, charPos: endOffset + 1)
                    if newXml != xml {
                        xml = newXml
                        runOpens  = allMatches(runOpen,  in: xml)
                        runCloses = allMatches(runClose, in: xml)
                    }
                }
                if needStartSplit {
                    let ro = runOpens[sIdx]
                    let rc = findRunClose(runCloses, after: ro)!
                    let newXml = splitRunAt(xml, runOpen: ro, runClose: rc, charPos: startOffset)
                    if newXml != xml {
                        xml = newXml
                        runOpens  = allMatches(runOpen,  in: xml)
                        runCloses = allMatches(runClose, in: xml)
                        sIdx += 1; eIdx += 1
                    }
                }
            }

            var rPrInsertions: [Ins] = []
            for idx in sIdx...eIdx {
                let rO = runOpens[idx]
                guard let rC = findRunClose(runCloses, after: rO) else { continue }
                let runContent = xml.utf16Substring(from: rO.end, length: rC.start - rO.end)
                let rPrEndIdx = (runContent as NSString).range(of: "</w:rPr>").location
                let wtIdx = runContent.contains("<w:t") ?
                    (runContent as NSString).range(of: "<w:t").location : runContent.utf16.count
                let hasRPr = rPrEndIdx != NSNotFound && (runContent as NSString).range(of: "<w:rPr").location < wtIdx
                if hasRPr {
                    let existingRPr = (runContent as NSString).substring(to: rPrEndIdx)
                    if !existingRPr.contains(rPrContent) {
                        rPrInsertions.append(Ins(pos: rO.end + rPrEndIdx, tag: rPrContent))
                    }
                } else {
                    rPrInsertions.append(Ins(pos: rO.end, tag: "<w:rPr>\(rPrContent)</w:rPr>"))
                }
            }
            for ins in rPrInsertions.sorted(by: { $0.pos > $1.pos }) {
                xml = xml.utf16Inserting(ins.tag, at: ins.pos)
            }

            if let commentId = noteCommentId[a.id] {
                anchorInsertions.append(AnchorIns(
                    commentId: commentId,
                    selectedText: a.selectedText, prefix: a.prefix, suffix: a.suffix, position: a.position
                ))
            }
        }

        // Resolve bookmark/comment anchors against the FINAL xml
        var finalInsertions: [Ins] = []
        if !anchorInsertions.isEmpty || !bookmarkInsertions.isEmpty {
            let finalMap    = PlainTextMapper.build(xml)
            let finalOpens  = allMatches(runOpen,  in: xml)
            let finalCloses = allMatches(runClose, in: xml)

            for (bkIdx, bk) in bookmarkInsertions.enumerated() {
                guard let loc = Anchoring.locateInPlain(
                    finalMap.plain, selectedText: bk.selectedText,
                    prefix: bk.prefix, suffix: bk.suffix, positionHint: bk.position
                ) else { continue }
                let startXmlPos = finalMap.xmlOffsets[loc.start]
                let endXmlPos   = finalMap.xmlOffsets[loc.end - 1]
                let si = findRunIdxBS(finalOpens, xmlPos: startXmlPos)
                let ei = findRunIdxBS(finalOpens, xmlPos: endXmlPos)
                guard si >= 0, ei >= 0 else { continue }
                guard let endRC = findRunClose(finalCloses, after: finalOpens[ei]) else { continue }
                let safeId = nonId.stringByReplacingMatches(
                    in: bk.annotationId,
                    range: NSRange(bk.annotationId.startIndex..., in: bk.annotationId),
                    withTemplate: "_"
                )
                let bkId = 100000 + bkIdx
                finalInsertions.append(Ins(pos: finalOpens[si].start,
                    tag: "<w:bookmarkStart w:id=\"\(bkId)\" w:name=\"leamh_\(safeId)\"/>"))
                finalInsertions.append(Ins(pos: endRC.end,
                    tag: "<w:bookmarkEnd w:id=\"\(bkId)\"/>"))
            }

            for anc in anchorInsertions {
                guard let loc = Anchoring.locateInPlain(
                    finalMap.plain, selectedText: anc.selectedText,
                    prefix: anc.prefix, suffix: anc.suffix, positionHint: anc.position
                ) else { continue }
                let startXmlPos = finalMap.xmlOffsets[loc.start]
                let endXmlPos   = finalMap.xmlOffsets[loc.end - 1]
                let si = findRunIdxBS(finalOpens, xmlPos: startXmlPos)
                let ei = findRunIdxBS(finalOpens, xmlPos: endXmlPos)
                guard si >= 0, ei >= 0 else { continue }
                guard let eClose = findRunClose(finalCloses, after: finalOpens[ei]) else { continue }
                finalInsertions.append(Ins(pos: finalOpens[si].start,
                    tag: "<w:commentRangeStart w:id=\"\(anc.commentId)\"/>"))
                finalInsertions.append(Ins(pos: eClose.end,
                    tag: "<w:commentRangeEnd w:id=\"\(anc.commentId)\"/>" +
                         "<w:r><w:rPr><w:rStyle w:val=\"CommentReference\"/></w:rPr>" +
                         "<w:commentReference w:id=\"\(anc.commentId)\"/></w:r>"))
            }
        }

        for ins in finalInsertions.sorted(by: { $0.pos > $1.pos }) {
            xml = xml.utf16Inserting(ins.tag, at: ins.pos)
        }
        return xml
    }

    // MARK: - Run helpers

    private struct MatchBounds { let start: Int; let end: Int }

    private static func allMatches(_ regex: NSRegularExpression, in s: String) -> [MatchBounds] {
        regex.matches(in: s, range: NSRange(s.startIndex..., in: s)).map {
            MatchBounds(start: $0.range.location, end: $0.range.location + $0.range.length)
        }
    }

    private static func stripAll(_ s: String, regex: NSRegularExpression) -> String {
        regex.stringByReplacingMatches(in: s, range: NSRange(s.startIndex..., in: s), withTemplate: "")
    }

    private static func findRunIdxBS(_ runOpens: [MatchBounds], xmlPos: Int) -> Int {
        var lo = 0; var hi = runOpens.count - 1; var found = -1
        while lo <= hi {
            let mid = (lo + hi) / 2
            if runOpens[mid].start <= xmlPos { found = mid; lo = mid + 1 } else { hi = mid - 1 }
        }
        return found
    }

    private static func findRunClose(_ runCloses: [MatchBounds], after runOpen: MatchBounds) -> MatchBounds? {
        runCloses.first(where: { $0.start > runOpen.start })
    }

    private static func getRunPlainText(_ xml: String, runOpen: MatchBounds, runClose: MatchBounds) -> String {
        let runContent = xml.utf16Substring(from: runOpen.end, length: runClose.start - runOpen.end)
        let ns = runContent as NSString
        var result = ""
        for m in wt.matches(in: runContent, range: NSRange(location: 0, length: ns.length)) {
            result += XmlEntities.decode(ns.substring(with: m.range(at: 1)))
        }
        return result
    }

    private static func approxCharOffsetInRun(
        _ xml: String, runOpen: MatchBounds, runClose: MatchBounds, xmlCharPos: Int
    ) -> Int {
        let runContent = xml.utf16Substring(from: runOpen.end, length: runClose.start - runOpen.end)
        let ns = runContent as NSString
        var charsBefore = 0
        for m in wt.matches(in: runContent, range: NSRange(location: 0, length: ns.length)) {
            let gtOffset = (ns.substring(with: NSRange(location: 0, length: m.range.location + m.range.length)) as NSString)
                .range(of: ">", options: .backwards, range: m.range).location
            let wtContentStart = runOpen.end + gtOffset + 1
            let wtRawLen = m.range(at: 1).length
            if xmlCharPos >= wtContentStart && xmlCharPos < wtContentStart + wtRawLen {
                return charsBefore + (xmlCharPos - wtContentStart)
            }
            charsBefore += XmlEntities.decode(ns.substring(with: m.range(at: 1))).utf16.count
        }
        return charsBefore
    }

    private static func splitRunAt(
        _ xml: String, runOpen: MatchBounds, runClose: MatchBounds, charPos: Int
    ) -> String {
        let runContent = xml.utf16Substring(from: runOpen.end, length: runClose.start - runOpen.end)
        let ns = runContent as NSString
        let wtList = wt.matches(in: runContent, range: NSRange(location: 0, length: ns.length))
        guard wtList.count == 1 else { return xml }

        let fullText = XmlEntities.decode(ns.substring(with: wtList[0].range(at: 1)))
        guard charPos > 0 && charPos < fullText.utf16.count else { return xml }

        let rPrChangeStripped = rprChange.stringByReplacingMatches(
            in: runContent, range: NSRange(location: 0, length: ns.length), withTemplate: ""
        )
        let rPrXml: String
        if let rPrMatch = rprBlock.firstMatch(
            in: rPrChangeStripped,
            range: NSRange(rPrChangeStripped.startIndex..., in: rPrChangeStripped)
        ) {
            rPrXml = (rPrChangeStripped as NSString).substring(with: rPrMatch.range)
        } else {
            rPrXml = ""
        }

        // Split fullText at charPos (UTF-16 index)
        let utf16 = fullText.utf16
        let splitIdx = utf16.index(utf16.startIndex, offsetBy: charPos)
        let t1 = XmlEntities.escape(String(utf16[..<splitIdx])!)
        let t2 = XmlEntities.escape(String(utf16[splitIdx...])!)

        let openTag = xml.utf16Substring(from: runOpen.start, length: runOpen.end - runOpen.start)
        let run1 = "\(openTag)\(rPrXml)<w:t xml:space=\"preserve\">\(t1)</w:t></w:r>"
        let run2 = "\(openTag)\(rPrXml)<w:t xml:space=\"preserve\">\(t2)</w:t></w:r>"

        return xml.utf16Replacing(from: runOpen.start, length: runClose.end - runOpen.start, with: run1 + run2)
    }
}

// MARK: - UTF-16 string helpers

private extension String {
    func utf16Substring(from offset: Int, length: Int) -> String {
        let utf16 = self.utf16
        let start = utf16.index(utf16.startIndex, offsetBy: offset)
        let end   = utf16.index(start, offsetBy: length)
        return String(utf16[start..<end]) ?? ""
    }

    func utf16Inserting(_ s: String, at offset: Int) -> String {
        let utf16 = self.utf16
        let idx = utf16.index(utf16.startIndex, offsetBy: offset)
        let before = String(utf16[..<idx]) ?? ""
        let after  = String(utf16[idx...]) ?? ""
        return before + s + after
    }

    func utf16Replacing(from offset: Int, length: Int, with replacement: String) -> String {
        let utf16 = self.utf16
        let start = utf16.index(utf16.startIndex, offsetBy: offset)
        let end   = utf16.index(start, offsetBy: length)
        let before = String(utf16[..<start]) ?? ""
        let after  = String(utf16[end...]) ?? ""
        return before + replacement + after
    }
}

private extension String.UTF16View.SubSequence {
    init?(_ view: String.UTF16View.SubSequence) { self = view }
}

private extension String {
    init?(_ utf16View: String.UTF16View.SubSequence) {
        var s = ""
        for unit in utf16View {
            if let scalar = Unicode.Scalar(unit) { s.append(Character(scalar)) }
            else { return nil }
        }
        self = s
    }
}

import Foundation

/// Imports existing Word run formatting (w:highlight, w:u, w:strike) as Léamh annotations.
/// Read fallback used when leamh/annotations.json is absent. Adjacent runs sharing a tool are merged.
/// Mirrors NativeImport.kt.
enum NativeImport {

    private static let run = try! NSRegularExpression(
        pattern: #"<w:r(?:\s[^>]*)?>(?<!/).*?</w:r>"#,
        options: .dotMatchesLineSeparators
    )
    private static let wt = try! NSRegularExpression(
        pattern: #"<w:t(?:[^>]*)>(.*?)</w:t>"#,
        options: .dotMatchesLineSeparators
    )
    private static let rpr = try! NSRegularExpression(
        pattern: #"<w:rPr>(.*?)</w:rPr>"#,
        options: .dotMatchesLineSeparators
    )

    private struct Segment { let tool: AnnotationTool; let plainStart: Int; let plainEnd: Int }

    static func importNativeFormatting(
        documentXml: String,
        map: PlainMap,
        baseMicros: Int64,
        now: Date
    ) -> [Annotation] {
        guard !map.plain.isEmpty else { return [] }
        let plain = map.plain
        let offsets = map.xmlOffsets
        let ns = documentXml as NSString

        var segments: [Segment] = []
        let fullRange = NSRange(location: 0, length: ns.length)

        for runMatch in run.matches(in: documentXml, range: fullRange) {
            let runContent = ns.substring(with: runMatch.range)
            let rcNs = runContent as NSString
            guard let rPrMatch = rpr.firstMatch(in: runContent, range: NSRange(location: 0, length: rcNs.length))
            else { continue }

            var rPrStr = rcNs.substring(with: rPrMatch.range(at: 1))
            if let rPrChangeIdx = rPrStr.range(of: "<w:rPrChange") {
                rPrStr = String(rPrStr[..<rPrChangeIdx.lowerBound])
            }

            let tool: AnnotationTool?
            if rPrStr.contains("<w:highlight") { tool = .highlight }
            else if rPrStr.contains("w:val=\"wave\"") { tool = .wavyUnderline }
            else if rPrStr.contains("w:val=\"double\"") { tool = .doubleUnderline }
            else if rPrStr.contains("<w:u ") || rPrStr.contains("<w:u/>") { tool = .underline }
            else if rPrStr.contains("<w:strike") { tool = .strikethrough }
            else { tool = nil }
            guard let resolvedTool = tool else { continue }

            let text = wt.matches(in: runContent, range: NSRange(location: 0, length: rcNs.length))
                .map { XmlEntities.decode(rcNs.substring(with: $0.range(at: 1))) }
                .joined()
            guard !text.isEmpty else { continue }

            guard let firstWtMatch = wt.firstMatch(in: runContent, range: NSRange(location: 0, length: rcNs.length))
            else { continue }
            let wtVal = rcNs.substring(with: firstWtMatch.range)
            let gtOff = (wtVal as NSString).range(of: ">").location
            let wtContentStart = runMatch.range.location + firstWtMatch.range.location + gtOff + 1

            // Binary search: first plain index whose xml offset >= wtContentStart
            var lo = 0; var hi = offsets.count - 1; var plainStart = -1
            while lo <= hi {
                let mid = (lo + hi) / 2
                if offsets[mid] >= wtContentStart { plainStart = mid; hi = mid - 1 }
                else { lo = mid + 1 }
            }
            guard plainStart >= 0 else { continue }
            let plainEnd = min(plainStart + text.utf16.count, plain.utf16.count)
            let plainSlice = plain.utf16Substring(from: plainStart, length: plainEnd - plainStart)
            guard plainSlice == text else { continue }

            segments.append(Segment(tool: resolvedTool, plainStart: plainStart, plainEnd: plainEnd))
        }

        guard !segments.isEmpty else { return [] }

        var results: [Annotation] = []
        var curr = segments[0]
        for i in 1..<segments.count {
            let next = segments[i]
            if next.tool == curr.tool && next.plainStart <= curr.plainEnd {
                curr = Segment(tool: curr.tool, plainStart: curr.plainStart, plainEnd: max(next.plainEnd, curr.plainEnd))
            } else {
                results.append(annotationFromSegment(curr, plain: plain, index: results.count, baseMicros: baseMicros, now: now))
                curr = next
            }
        }
        results.append(annotationFromSegment(curr, plain: plain, index: results.count, baseMicros: baseMicros, now: now))
        return results
    }

    private static func annotationFromSegment(
        _ seg: Segment, plain: String, index: Int, baseMicros: Int64, now: Date
    ) -> Annotation {
        let text = plain.utf16Substring(from: seg.plainStart, length: seg.plainEnd - seg.plainStart)
        let pos = plain.utf16.count > 0
            ? max(0.0, min(1.0, Double(seg.plainStart) / Double(plain.utf16.count)))
            : 0.0
        let prefixStart = max(0, seg.plainStart - 20)
        let prefix = plain.utf16Substring(from: prefixStart, length: seg.plainStart - prefixStart)
        let suffixEnd = min(seg.plainEnd + 20, plain.utf16.count)
        let suffix = plain.utf16Substring(from: seg.plainEnd, length: suffixEnd - seg.plainEnd)
        return Annotation(
            id: String(baseMicros + Int64(index)),
            selectedText: text,
            prefix: prefix,
            suffix: suffix,
            tool: seg.tool,
            timestamp: now,
            position: pos
        )
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
}

private extension String {
    init?(_ view: String.UTF16View.SubSequence) {
        var s = ""
        for unit in view {
            if let scalar = Unicode.Scalar(unit) { s.append(Character(scalar)) }
            else { return nil }
        }
        self = s
    }
}

import Foundation

/// A half-open character range [start, end) in the canonical plain text (UTF-16 code-unit indices).
public struct TextSpan: Equatable {
    public let start: Int
    public let end: Int
    public init(start: Int, end: Int) { self.start = start; self.end = end }
}

/// Locating a stored annotation within the current plain text, and snapping a raw
/// selection out to word boundaries. Mirrors Anchoring.kt / annotation_utils.dart.
/// All offsets are UTF-16 code-unit indices to match Kotlin/Dart.
public enum Anchoring {

    /// Three-tier locate (first hit wins), exactly as Kotlin/Dart:
    ///  1. context match — prefix + selectedText + suffix via indexOf
    ///  2. nearest occurrence of selectedText to positionHint * length
    ///  3. quote-normalised retry of (1) then (2)
    /// Returns nil if selectedText is empty or cannot be located.
    public static func locateInPlain(
        _ plain: String,
        selectedText: String,
        prefix: String,
        suffix: String,
        positionHint: Double = 0.0
    ) -> TextSpan? {
        guard !selectedText.isEmpty else { return nil }

        if !prefix.isEmpty || !suffix.isEmpty {
            let needle = prefix + selectedText + suffix
            if let idx = plain.utf16FindIndex(of: needle) {
                let start = idx + prefix.utf16.count
                return TextSpan(start: start, end: start + selectedText.utf16.count)
            }
        }

        let hintPos = Int((positionHint * Double(plain.utf16.count)).rounded()).clamped(0, plain.utf16.count)
        if let best = findClosest(plain, needle: selectedText, hintPos: hintPos) {
            return TextSpan(start: best, end: best + selectedText.utf16.count)
        }

        let plainN = normaliseQuotes(plain)
        let selectedN = normaliseQuotes(selectedText)
        let prefixN = normaliseQuotes(prefix)
        let suffixN = normaliseQuotes(suffix)

        if !prefixN.isEmpty || !suffixN.isEmpty {
            let needle = prefixN + selectedN + suffixN
            if let idx = plainN.utf16FindIndex(of: needle) {
                let start = idx + prefixN.utf16.count
                return TextSpan(start: start, end: start + selectedN.utf16.count)
            }
        }
        if let bestN = findClosest(plainN, needle: selectedN, hintPos: hintPos) {
            return TextSpan(start: bestN, end: bestN + selectedN.utf16.count)
        }

        return nil
    }

    /// Index (UTF-16) of the occurrence of needle in hay closest to hintPos, or nil.
    public static func findClosest(_ hay: String, needle: String, hintPos: Int) -> Int? {
        var best: Int? = nil
        var bestDist = Int.max
        var from = 0
        while true {
            guard let idx = hay.utf16FindIndex(of: needle, from: from) else { break }
            let dist = abs(idx - hintPos)
            if dist < bestDist { bestDist = dist; best = idx }
            from = idx + 1
        }
        return best
    }

    /// Smart quotes/dashes → ASCII. 1:1 char replacement, so indices are preserved.
    public static func normaliseQuotes(_ s: String) -> String {
        s.replacingOccurrences(of: "\u{201C}", with: "\"") // "
         .replacingOccurrences(of: "\u{201D}", with: "\"") // "
         .replacingOccurrences(of: "\u{2018}", with: "'")  // '
         .replacingOccurrences(of: "\u{2019}", with: "'")  // '
         .replacingOccurrences(of: "\u{2013}", with: "-")  // –
         .replacingOccurrences(of: "\u{2014}", with: "-")  // —
    }

    private static let wordBoundaryChars: Set<Character> = [
        " ", "\n", "\r", "\t", ".", ",", "!", "?", ";", ":", "\"", "'",
        "(", ")", "[", "]", "\u{2014}", "\u{2013}",
    ]

    /// Expands [start, end) outward to full word boundaries within text (UTF-16 indices). Only expands.
    public static func snapToWordBoundaries(_ text: String, start: Int, end: Int) -> TextSpan {
        let utf16 = text.utf16
        var s = start
        while s > 0 {
            let idx = utf16.index(utf16.startIndex, offsetBy: s - 1)
            if let scalar = Unicode.Scalar(utf16[idx]), isWordBoundary(Character(scalar)) { break }
            s -= 1
        }
        var e = end
        while e < utf16.count {
            let idx = utf16.index(utf16.startIndex, offsetBy: e)
            if let scalar = Unicode.Scalar(utf16[idx]), isWordBoundary(Character(scalar)) { break }
            e += 1
        }
        return TextSpan(start: s, end: e)
    }

    public static func isWordBoundary(_ c: Character) -> Bool { wordBoundaryChars.contains(c) }
}

// MARK: - String UTF-16 search helpers

private extension String {
    /// Returns the UTF-16 code-unit index of the first occurrence of needle at or after from,
    /// or nil if not found. Mirrors Kotlin's String.indexOf(needle, from).
    func utf16FindIndex(of needle: String, from: Int = 0) -> Int? {
        let h = self.utf16
        let n = needle.utf16
        guard !n.isEmpty, h.count >= n.count else { return nil }
        let limit = h.count - n.count
        guard from <= limit else { return nil }
        outer: for i in from...limit {
            for j in 0..<n.count {
                if h[h.index(h.startIndex, offsetBy: i + j)] != n[n.index(n.startIndex, offsetBy: j)] {
                    continue outer
                }
            }
            return i
        }
        return nil
    }
}

private extension Int {
    func clamped(_ lo: Int, _ hi: Int) -> Int { Swift.max(lo, Swift.min(hi, self)) }
}

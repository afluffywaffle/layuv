import Foundation

/// XML entity decode/encode for OOXML text. Mirrors XmlEntities.kt.
///
/// decode handles the five named entities AND numeric character references
/// (&#233;, &#x1F600;). Single left-to-right pass, so &amp;lt; decodes to &lt; (not <).
enum XmlEntities {

    private static let entityPattern = try! NSRegularExpression(
        pattern: #"&(#x[0-9A-Fa-f]+|#[0-9]+|amp|lt|gt|quot|apos);"#
    )

    /// Mirror of XmlEntities.escape in Kotlin — & first so it isn't double-escaped.
    static func escape(_ s: String) -> String {
        s.replacingOccurrences(of: "&", with: "&amp;")
         .replacingOccurrences(of: "<", with: "&lt;")
         .replacingOccurrences(of: ">", with: "&gt;")
         .replacingOccurrences(of: "\"", with: "&quot;")
         .replacingOccurrences(of: "'", with: "&apos;")
    }

    static func decode(_ s: String) -> String {
        guard s.contains("&") else { return s }
        let ns = s as NSString
        let full = NSRange(location: 0, length: ns.length)
        var result = ""
        var cursor = 0
        for match in entityPattern.matches(in: s, range: full) {
            let matchRange = match.range
            // Append text before this match
            if matchRange.location > cursor {
                result += ns.substring(with: NSRange(location: cursor, length: matchRange.location - cursor))
            }
            let e = ns.substring(with: match.range(at: 1))
            switch e {
            case "amp":  result += "&"
            case "lt":   result += "<"
            case "gt":   result += ">"
            case "quot": result += "\""
            case "apos": result += "'"
            default:
                let codePoint: UInt32?
                if e.hasPrefix("#x") {
                    codePoint = UInt32(e.dropFirst(2), radix: 16).flatMap { $0 <= 0x10FFFF ? $0 : nil }
                } else {
                    codePoint = UInt32(e.dropFirst()).flatMap { $0 <= 0x10FFFF ? $0 : nil }
                }
                if let cp = codePoint, let scalar = Unicode.Scalar(cp) {
                    result += String(scalar)
                } else {
                    result += ns.substring(with: matchRange) // preserve original entity unchanged
                }
            }
            cursor = matchRange.location + matchRange.length
        }
        if cursor < ns.length {
            result += ns.substring(from: cursor)
        }
        return result
    }
}

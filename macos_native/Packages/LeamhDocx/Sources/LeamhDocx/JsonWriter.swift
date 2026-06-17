import Foundation

/// Compact JSON serializer for leamh/annotations.json matching Dart's jsonEncode escaping.
/// Escapes " \ and control chars; leaves / and non-ASCII raw (unlike JSONSerialization which escapes /).
/// Mirrors JsonWriter.kt.
enum JsonWriter {

    static func encode(_ value: Any?) -> String {
        var sb = ""
        write(&sb, value)
        return sb
    }

    private static func write(_ sb: inout String, _ v: Any?) {
        switch v {
        case nil, is NSNull:
            sb += "null"
        case let s as String:
            writeString(&sb, s)
        case let b as Bool:
            sb += b ? "true" : "false"
        case let n as NSNumber:
            // Distinguish Bool from numeric (Bool is NSNumber in ObjC bridge)
            if n === kCFBooleanTrue as AnyObject || n === kCFBooleanFalse as AnyObject {
                sb += n.boolValue ? "true" : "false"
            } else {
                sb += encodeNumber(n)
            }
        case let d as [String: Any?]:
            sb += "{"
            var first = true
            for (k, vv) in d {
                if !first { sb += "," }
                first = false
                writeString(&sb, k)
                sb += ":"
                write(&sb, vv)
            }
            sb += "}"
        case let arr as [Any?]:
            sb += "["
            var first = true
            for e in arr {
                if !first { sb += "," }
                first = false
                write(&sb, e)
            }
            sb += "]"
        default:
            writeString(&sb, "\(v!)")
        }
    }

    private static func writeString(_ sb: inout String, _ s: String) {
        sb += "\""
        for c in s.unicodeScalars {
            switch c {
            case "\"": sb += "\\\""
            case "\\": sb += "\\\\"
            case "\n": sb += "\\n"
            case "\r": sb += "\\r"
            case "\t": sb += "\\t"
            case "\u{08}": sb += "\\b"
            case "\u{0C}": sb += "\\f"
            case _ where c.value < 0x20:
                sb += String(format: "\\u%04x", c.value)
            default:
                sb += String(c)
            }
        }
        sb += "\""
    }

    private static func encodeNumber(_ n: NSNumber) -> String {
        let d = n.doubleValue
        if !d.isInfinite && !d.isNaN && d == Double(Int64(exactly: n.doubleValue) ?? 0) {
            if let i = Int64(exactly: n.doubleValue) { return "\(i).0" }
        }
        // Integer types
        let objCType = String(cString: n.objCType)
        switch objCType {
        case "i", "s", "l", "q", "I", "S", "L", "Q":
            return "\(n.int64Value)"
        default:
            return "\(d)"
        }
    }
}

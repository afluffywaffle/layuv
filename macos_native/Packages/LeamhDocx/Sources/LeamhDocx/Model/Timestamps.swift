import Foundation

/// ISO-8601 timestamp handling that round-trips with Dart's DateTime.toIso8601String() / DateTime.parse().
/// Léamh writes UTC timestamps (...Z). Mirrors model/Timestamps.kt.
enum Timestamps {

    /// Matches Dart's DateTime.toUtc().toIso8601String() exactly: always at least milliseconds
    /// (3 digits), 6 digits when sub-millisecond microseconds are present, Z suffix.
    static func format(_ date: Date) -> String {
        let micros = Int64(date.timeIntervalSince1970 * 1_000_000)
        let secondsMicros = micros % 1_000_000
        let frac: String
        if secondsMicros % 1000 == 0 {
            frac = String(format: "%03d", secondsMicros / 1000)
        } else {
            frac = String(format: "%06d", secondsMicros)
        }
        let cal = Calendar(identifier: .gregorian)
        let comps = cal.dateComponents(in: TimeZone(identifier: "UTC")!, from: date)
        return String(
            format: "%04d-%02d-%02dT%02d:%02d:%02d.%@Z",
            comps.year!, comps.month!, comps.day!,
            comps.hour!, comps.minute!, comps.second!,
            frac
        )
    }

    static func parse(_ s: String) -> Date {
        // Try ISO8601DateFormatter with fractional seconds first (handles Z and offsets)
        let fmtFrac = ISO8601DateFormatter()
        fmtFrac.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let d = fmtFrac.date(from: s) { return d }

        let fmt = ISO8601DateFormatter()
        fmt.formatOptions = [.withInternetDateTime]
        if let d = fmt.date(from: s) { return d }

        // Bare local date-time (legacy) — assume UTC
        let bare = DateFormatter()
        bare.dateFormat = "yyyy-MM-dd'T'HH:mm:ss"
        bare.timeZone = TimeZone(identifier: "UTC")
        return bare.date(from: s) ?? Date(timeIntervalSince1970: 0)
    }
}

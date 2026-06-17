import Foundation

/// Mirror of ReadingPosition.kt / lib/models/reading_position.dart.
/// fraction is the 0.0–1.0 plain-text position.
public struct ReadingPosition: Equatable {
    public let mode: ReadingMode
    public let page: Int
    public let scrollOffset: Double
    public let fraction: Double

    public init(mode: ReadingMode, page: Int, scrollOffset: Double, fraction: Double = 0.0) {
        self.mode = mode
        self.page = page
        self.scrollOffset = scrollOffset
        self.fraction = fraction
    }

    func toMap() -> [String: Any?] {
        return [
            "mode": mode.rawValue,
            "page": page,
            "scrollOffset": scrollOffset,
            "fraction": fraction,
        ]
    }

    static func fromMap(_ map: [String: Any?]) -> ReadingPosition {
        ReadingPosition(
            mode: ReadingMode(rawValue: (map["mode"] as? String) ?? "") ?? .pageFlip,
            page: (map["page"] as? NSNumber)?.intValue ?? 0,
            scrollOffset: (map["scrollOffset"] as? NSNumber)?.doubleValue ?? 0.0,
            fraction: (map["fraction"] as? NSNumber)?.doubleValue ?? 0.0
        )
    }
}

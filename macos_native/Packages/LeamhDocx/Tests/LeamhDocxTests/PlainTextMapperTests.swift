import XCTest
@testable import LeamhDocx

final class PlainTextMapperTests: XCTestCase {

    private let fixtures = [
        "simple", "preserve", "multi_wt_run", "empty_para", "self_closing_run", "unicode",
        "entities", "tabs_breaks", "table",
    ]

    private let proseFixtures: Set<String> = [
        "simple", "preserve", "multi_wt_run", "empty_para", "self_closing_run", "unicode",
    ]

    func testMatchesCleanGoldens() throws {
        for name in fixtures {
            let xml = try golden("fixtures/\(name).document.xml")
            let map = PlainTextMapper.build(xml)

            let expectedPlain = try golden("clean/\(name).plain.txt")
            XCTAssertEqual(map.plain, expectedPlain, "plain text mismatch for \(name)")

            let expectedOffsets = try parseInts(golden("clean/\(name).offsets.json"))
            XCTAssertEqual(map.xmlOffsets, expectedOffsets, "xmlOffsets mismatch for \(name)")

            XCTAssertEqual(
                map.plain.utf16.count, map.xmlOffsets.count,
                "offsets must be parallel to plain for \(name)"
            )
        }
    }

    func testCleanEqualsLegacyOnProse() throws {
        for name in proseFixtures {
            let cleanPlain   = try golden("clean/\(name).plain.txt")
            let legacyPlain  = try golden("legacy/\(name).plain.txt")
            XCTAssertEqual(cleanPlain, legacyPlain, "clean and legacy plain diverge on prose fixture \(name)")

            let cleanOffsets  = try golden("clean/\(name).offsets.json")
            let legacyOffsets = try golden("legacy/\(name).offsets.json")
            XCTAssertEqual(cleanOffsets, legacyOffsets, "clean and legacy offsets diverge on prose fixture \(name)")
        }
    }

    // MARK: - Helpers

    private func golden(_ relativePath: String) throws -> String {
        let url = Bundle.module.url(
            forResource: "golden/\(relativePath)", withExtension: nil
        ) ?? Bundle.module.bundleURL
            .appendingPathComponent("Resources/golden/\(relativePath)")
        return try String(contentsOf: url, encoding: .utf8)
    }

    private func parseInts(_ json: String) -> [Int] {
        let trimmed = json.trimmingCharacters(in: .whitespacesAndNewlines)
            .trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
        guard !trimmed.isEmpty else { return [] }
        return trimmed.split(separator: ",").compactMap { Int($0.trimmingCharacters(in: .whitespaces)) }
    }
}

import XCTest
@testable import LeamhDocx

final class ModelTests: XCTestCase {

    func testAnnotationsParseAndRoundTrip() throws {
        let json = try golden("model/annotations.json")
        let arr  = try JSONSerialization.jsonObject(with: Data(json.utf8)) as! [[String: Any]]
        let list = arr.map { Annotation.fromMap($0 as [String: Any?]) }
        XCTAssertEqual(list.count, 5)

        // fromMap(toMap(x)) == x for every annotation
        for a in list {
            let rt = Annotation.fromMap(a.toMap())
            XCTAssertEqual(rt, a, "round-trip failed for \(a.id)")
        }

        let byId = Dictionary(uniqueKeysWithValues: list.map { ($0.id, $0) })

        let a1000 = try XCTUnwrap(byId["1000"])
        XCTAssertEqual(a1000.tool, .highlight)
        XCTAssertEqual(a1000.selectedText, "Hello")
        XCTAssertEqual(a1000.suffix, " world")
        XCTAssertEqual(a1000.position, 0.0)
        XCTAssertFalse(a1000.hasInk)
        XCTAssertNil(a1000.note)
        XCTAssertNil(a1000.tag)
        // UTC microsecond precision: 2026-01-02T03:04:05.000006Z
        let expectedDate = Timestamps.parse("2026-01-02T03:04:05.000006Z")
        XCTAssertEqual(a1000.timestamp, expectedDate)

        let a1001 = try XCTUnwrap(byId["1001"])
        XCTAssertEqual(a1001.tool, .comment)
        XCTAssertEqual(a1001.tag, .query)
        XCTAssertEqual(a1001.note, "a note with \"quotes\"\nand a newline")
        XCTAssertTrue(a1001.selectedText.contains("😀"), "emoji preserved")
        XCTAssertEqual(a1001.position, 0.5)

        let a1002 = try XCTUnwrap(byId["1002"])
        XCTAssertEqual(a1002.tool, .doubleUnderline)
        XCTAssertEqual(a1002.toMap()["tool"] as? String, "doubleUnderline")
        XCTAssertEqual(a1002.position, 0.123456)

        let a1003 = try XCTUnwrap(byId["1003"])
        XCTAssertEqual(a1003.tool, .inkAnnotation)
        XCTAssertTrue(a1003.hasInk)
        XCTAssertEqual(a1003.tag, .voice)
        XCTAssertEqual(a1003.position, 1.0)

        let a1004 = try XCTUnwrap(byId["1004"])
        XCTAssertEqual(a1004.tool, .bookmark)
        XCTAssertEqual(a1004.position, 0.75)
    }

    func testPositionParsesAndRoundTrips() throws {
        let json = try golden("model/position.json")
        let map  = try JSONSerialization.jsonObject(with: Data(json.utf8)) as! [String: Any]
        let pos  = ReadingPosition.fromMap(map as [String: Any?])
        XCTAssertEqual(pos.mode, .pageFlip)
        XCTAssertEqual(pos.page, 42)
        XCTAssertEqual(pos.scrollOffset, 0.0)
        XCTAssertEqual(pos.fraction, 0.333)
        XCTAssertEqual(pos, ReadingPosition.fromMap(pos.toMap()))
    }

    // MARK: - Helpers

    private func golden(_ relativePath: String) throws -> String {
        let url = Bundle.module.url(
            forResource: "golden/\(relativePath)", withExtension: nil
        ) ?? Bundle.module.bundleURL
            .appendingPathComponent("Resources/golden/\(relativePath)")
        return try String(contentsOf: url, encoding: .utf8)
    }
}

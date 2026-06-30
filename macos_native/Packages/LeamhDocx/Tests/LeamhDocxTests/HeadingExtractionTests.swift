import XCTest
@testable import LeamhDocx

/// Unit tests for document-outline heading extraction. Headings are additive
/// (they do not affect plain text or xmlOffsets — guarded by the golden tests),
/// so these assert only the PlainMap.headings overlay. Mirrors the Kotlin
/// HeadingExtractionTest; the Swift mapper infers level from the `Heading N`
/// pStyle val (no styles.xml resolution).
final class HeadingExtractionTests: XCTestCase {

    private func para(_ styleId: String?, _ text: String) -> String {
        let pPr = styleId.map { "<w:pPr><w:pStyle w:val=\"\($0)\"/></w:pPr>" } ?? ""
        return "<w:p>\(pPr)<w:r><w:t>\(text)</w:t></w:r></w:p>"
    }

    func testExtractsHeadingsInOrderWithLevels() {
        let xml = "<w:body>"
            + para("Heading1", "Chapter One")
            + para(nil, "Body paragraph text.")
            + para("Heading2", "A Subsection")
            + para("Normal", "More body.")
            + "</w:body>"

        let map = PlainTextMapper.build(xml)

        XCTAssertEqual(map.headings.count, 2)
        XCTAssertEqual(map.headings[0], Heading(text: "Chapter One", level: 0,
            charOffset: (map.plain as NSString).range(of: "Chapter One").location))
        XCTAssertEqual(map.headings[1], Heading(text: "A Subsection", level: 1,
            charOffset: (map.plain as NSString).range(of: "A Subsection").location))
    }

    func testCharOffsetPointsAtHeadingText() {
        let xml = "<w:body>" + para(nil, "Intro.") + para("Heading1", "Target") + "</w:body>"
        let map = PlainTextMapper.build(xml)
        let h = map.headings.first!
        let ns = map.plain as NSString
        XCTAssertEqual(ns.substring(with: NSRange(location: h.charOffset, length: 6)), "Target")
    }

    func testBlankHeadingsSkipped() {
        let xml = "<w:body>" + para("Heading1", "   ") + para("Heading1", "Real") + "</w:body>"
        let map = PlainTextMapper.build(xml)
        XCTAssertEqual(map.headings.map { $0.text }, ["Real"])
    }

    func testLevelInferredFromHeadingStyleId() {
        let xml = "<w:body>" + para("Heading3", "Deep") + "</w:body>"
        let map = PlainTextMapper.build(xml)
        XCTAssertEqual(map.headings.count, 1)
        XCTAssertEqual(map.headings[0].level, 2)
    }

    func testNonHeadingStylesProduceNoOutline() {
        let xml = "<w:body>" + para("Normal", "Body") + para("Quote", "A quote") + "</w:body>"
        let map = PlainTextMapper.build(xml)
        XCTAssertTrue(map.headings.isEmpty)
        XCTAssertTrue(map.plain.contains("A quote"))
    }
}

import XCTest
@testable import LeamhDocx

final class DocxStoreTests: XCTestCase {

    private lazy var docxData: Data = {
        let url = try! goldenURL("docx/sample.docx")
        return try! Data(contentsOf: url)
    }()

    private lazy var doc: LoadedDocument = {
        try! DocxStore.load(docxData)
    }()

    private lazy var expected: [String: Any] = {
        let url = try! goldenURL("docx/expected.json")
        let data = try! Data(contentsOf: url)
        return try! JSONSerialization.jsonObject(with: data) as! [String: Any]
    }()

    func testPlainTextMatches() {
        XCTAssertEqual(doc.plainText, expected["plainText"] as? String)
    }

    func testPositionParsed() throws {
        let p = try XCTUnwrap(doc.position, "position not loaded")
        let e = expected["position"] as! [String: Any]
        XCTAssertEqual(p.mode, ReadingMode(rawValue: e["mode"] as! String))
        XCTAssertEqual(p.page, (e["page"] as! NSNumber).intValue)
        XCTAssertEqual(p.scrollOffset, (e["scrollOffset"] as! NSNumber).doubleValue)
        XCTAssertEqual(p.fraction, (e["fraction"] as! NSNumber).doubleValue)
    }

    func testAnnotationsResolvedAndInkFromPng() throws {
        let eArr = expected["annotations"] as! [[String: Any?]]
        let expectedById = Dictionary(uniqueKeysWithValues: eArr.map { ($0["id"] as! String, $0) })
        XCTAssertEqual(expectedById.count, doc.annotations.count)

        for ra in doc.annotations {
            let e = try XCTUnwrap(expectedById[ra.annotation.id], "no expected entry for \(ra.annotation.id)")
            XCTAssertEqual(ra.annotation.hasInk, e["hasInk"] as? Bool, "hasInk for \(ra.annotation.id)")
            if e["span"] is NSNull || e["span"] == nil {
                XCTAssertNil(ra.span, "expected unlocatable span for \(ra.annotation.id)")
            } else {
                let s = e["span"] as! [String: Any]
                let expected = TextSpan(
                    start: (s["start"] as! NSNumber).intValue,
                    end:   (s["end"]   as! NSNumber).intValue
                )
                XCTAssertEqual(ra.span, expected, "span for \(ra.annotation.id)")
            }
        }
    }

    func testWriteRoundTrip() throws {
        let annotations = doc.annotations.map(\.annotation)
        let written  = try DocxStore.write(docxData, annotations: annotations)
        let reloaded = try DocxStore.load(written)

        XCTAssertEqual(reloaded.annotations.count, annotations.count)
        for (a, b) in zip(annotations, reloaded.annotations.map(\.annotation)) {
            XCTAssertEqual(a.id, b.id)
            XCTAssertEqual(a.tool, b.tool)
            XCTAssertEqual(a.selectedText, b.selectedText)
            XCTAssertEqual(a.note, b.note)
            XCTAssertEqual(a.tag, b.tag)
        }
    }

    // MARK: - Helpers

    private func goldenURL(_ relativePath: String) throws -> URL {
        if let url = Bundle.module.url(forResource: "golden/\(relativePath)", withExtension: nil) {
            return url
        }
        return Bundle.module.bundleURL.appendingPathComponent("Resources/golden/\(relativePath)")
    }
}

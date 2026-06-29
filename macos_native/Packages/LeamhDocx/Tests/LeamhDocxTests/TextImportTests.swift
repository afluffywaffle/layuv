import XCTest
@testable import LeamhDocx

final class TextImportTests: XCTestCase {

    // MARK: - Front matter

    func testPlainTextHasNoFrontMatter() {
        let r = TextImport.parse("Just some prose.\n\nSecond para.")
        XCTAssertEqual(r.text, "Just some prose.\n\nSecond para.")
        XCTAssertNil(r.ecriThemeRaw)
        XCTAssertNil(r.ecriFontSerif)
        XCTAssertNil(r.ecriPage)
    }

    func testParsesEcriFrontMatterAndStripsIt() {
        let raw = "---\nécri-theme: sage\nécri-font: serif\nécri-page: 4\nécri-dark: off\n---\nThe body begins here."
        let r = TextImport.parse(raw)
        XCTAssertEqual(r.text, "The body begins here.")
        XCTAssertEqual(r.ecriThemeRaw, "sage")
        XCTAssertEqual(r.ecriFontSerif, true)
        XCTAssertEqual(r.ecriPage, 4)
    }

    func testEcriFontSansParsed() {
        let raw = "---\nécri-font: sans\n---\nHi."
        let r = TextImport.parse(raw)
        XCTAssertEqual(r.ecriFontSerif, false)
        XCTAssertEqual(r.text, "Hi.")
    }

    func testMalformedHeaderWithoutCloseIsTreatedAsBody() {
        let raw = "---\nécri-theme: dusk\nno close marker"
        let r = TextImport.parse(raw)
        XCTAssertEqual(r.text, raw)        // no closing ---, whole thing is body
        XCTAssertNil(r.ecriThemeRaw)
    }

    // MARK: - DOCX assembly + round trip

    func testBuiltDocxIsReadableAndPreservesText() throws {
        let text = "First paragraph.\n\nSecond paragraph with a soft\nline break."
        let data = try TextImport.docx(from: text)
        let loaded = try DocxStore.load(data)
        // Plain text round-trips (paragraphs joined by newlines; soft break preserved as newline).
        XCTAssertTrue(loaded.plainText.contains("First paragraph."))
        XCTAssertTrue(loaded.plainText.contains("Second paragraph with a soft"))
        XCTAssertTrue(loaded.plainText.contains("line break."))
        XCTAssertTrue(loaded.annotations.isEmpty)
    }

    func testBuiltDocxEscapesXmlSpecials() throws {
        let data = try TextImport.docx(from: "A < B & C > D")
        let loaded = try DocxStore.load(data)
        XCTAssertTrue(loaded.plainText.contains("A < B & C > D"))
    }

    func testEmptyTextProducesValidDocx() throws {
        let data = try TextImport.docx(from: "")
        let loaded = try DocxStore.load(data)
        XCTAssertEqual(loaded.plainText.trimmingCharacters(in: .whitespacesAndNewlines), "")
    }
}

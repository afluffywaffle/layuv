import XCTest
@testable import LeamhDocx

/// Mirror of DocxStoreAiChatTest.kt.
final class DocxStoreAiChatTests: XCTestCase {

    /// A bare DOCX with no Léamh sidecars and no `json` content-type yet.
    private func minimalDocx() throws -> Data {
        var e = MutableDocxEntries([])
        e["[Content_Types].xml"] = Data(
            ("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
             "<Default Extension=\"xml\" ContentType=\"application/xml\"/></Types>").utf8)
        e["word/document.xml"] = Data(
            ("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
             "<w:body><w:p><w:r><w:t>Hi.</w:t></w:r></w:p></w:body></w:document>").utf8)
        return try DocxArchive.write(e)
    }

    func testWriteThenReadRoundTrips() throws {
        let turns = [
            AiTurn(role: AiTurn.roleUser, text: "rewrite please"),
            AiTurn(role: AiTurn.roleAssistant, text: "Here is the rewrite.", truncated: true),
        ]
        let out = try DocxStore.writeAiChat(try buildAnnotatedDocxFixture(), turns: turns)
        XCTAssertEqual(DocxStore.readAiChat(out), turns)
    }

    func testCoexistsWithAnnotations() throws {
        let out = try DocxStore.writeAiChat(try buildAnnotatedDocxFixture(),
                                            turns: [AiTurn(role: AiTurn.roleUser, text: "x")])
        // Writing the transcript must not drop the annotation store.
        XCTAssertTrue(try DocxArchive.read(out).contains("leamh/annotations.json"))
    }

    func testAbsentTranscriptReadsEmpty() throws {
        XCTAssertTrue(DocxStore.readAiChat(try minimalDocx()).isEmpty)
    }

    func testAddsJsonContentTypeOnNeverAnnotatedDoc() throws {
        let out = try DocxStore.writeAiChat(try minimalDocx(), turns: [AiTurn(role: AiTurn.roleUser, text: "x")])
        let ct = try XCTUnwrap(try DocxArchive.read(out).text(named: "[Content_Types].xml"))
        XCTAssertTrue(ct.contains("Extension=\"json\""), "json content-type added")
        XCTAssertEqual(DocxStore.readAiChat(out), [AiTurn(role: AiTurn.roleUser, text: "x")])
    }
}

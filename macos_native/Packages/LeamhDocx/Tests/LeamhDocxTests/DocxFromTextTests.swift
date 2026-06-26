import XCTest
@testable import LeamhDocx

/// Builds a small but realistic annotated/conversational DOCX in-memory: the structural parts
/// Word needs, the comment family, ink media, and every Léamh sidecar. Shared with
/// DocxStoreAiChatTests. Mirror of buildAnnotatedDocxFixture() in DocxFromTextTest.kt.
func buildAnnotatedDocxFixture() throws -> Data {
    let doc =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
        "<w:body>" +
        "<w:p><w:r><w:t>Old paragraph one.</w:t></w:r></w:p>" +
        "<w:p><w:r><w:t>Old paragraph two.</w:t></w:r></w:p>" +
        "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>" +
        "<w:pgMar w:top=\"1440\" w:bottom=\"1440\" w:left=\"1440\" w:right=\"1440\"/></w:sectPr>" +
        "</w:body></w:document>"
    let rels =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>" +
        "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/comments\" Target=\"comments.xml\"/>" +
        "</Relationships>"
    let contentTypes =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
        "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
        "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
        "<Default Extension=\"json\" ContentType=\"application/json\"/>" +
        "<Default Extension=\"png\" ContentType=\"image/png\"/>" +
        "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
        "<Override PartName=\"/word/comments.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.comments+xml\"/>" +
        "</Types>"

    var e = MutableDocxEntries([])
    e["[Content_Types].xml"] = Data(contentTypes.utf8)
    e["_rels/.rels"] = Data("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"/>".utf8)
    e["word/document.xml"] = Data(doc.utf8)
    e["leamh/document_clean.xml"] = Data(doc.utf8)
    e["word/styles.xml"] = Data("<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"></w:styles>".utf8)
    e["word/_rels/document.xml.rels"] = Data(rels.utf8)
    e["word/comments.xml"] = Data("<w:comments xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"></w:comments>".utf8)
    e["word/commentsExtended.xml"] = Data("<w15:commentsEx xmlns:w15=\"x\"></w15:commentsEx>".utf8)
    e["word/_rels/comments.xml.rels"] = Data("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"/>".utf8)
    e["leamh/annotations.json"] = Data("[]".utf8)
    e["leamh/position.json"] = Data("{}".utf8)
    e["leamh/aichat.json"] = Data("[{\"role\":\"user\",\"text\":\"hi\",\"truncated\":false}]".utf8)
    e["word/media/ink_123.png"] = Data([0x89, 0x50, 0x4E, 0x47])
    return try DocxArchive.write(e)
}

/// Mirror of DocxFromTextTest.kt.
final class DocxFromTextTests: XCTestCase {

    func testStripsSidecarsRegeneratesBodyAndPreservesStructure() throws {
        let out = try DocxFromText.build(sourceDocx: try buildAnnotatedDocxFixture(), text: "Para one.\n\nPara two.")
        let arc = try DocxArchive.read(out)

        for gone in [
            "leamh/annotations.json", "leamh/position.json", "leamh/document_clean.xml",
            "leamh/aichat.json", "word/comments.xml", "word/commentsExtended.xml",
            "word/_rels/comments.xml.rels",
        ] {
            XCTAssertFalse(arc.contains(gone), "should strip \(gone)")
        }
        XCTAssertFalse(arc.names.contains { $0.hasPrefix("word/media/ink_") }, "ink media stripped")

        XCTAssertTrue(arc.contains("word/styles.xml"), "styles.xml preserved")
        XCTAssertTrue(arc.contains("[Content_Types].xml"))

        let doc = try XCTUnwrap(arc.text(named: "word/document.xml"))
        XCTAssertEqual(PlainTextMapper.build(doc).plain, "Para one.\nPara two.\n")

        XCTAssertTrue(doc.contains("<w:sectPr"), "sectPr preserved")
        XCTAssertTrue(doc.contains("w:w=\"11906\""), "page size preserved")

        let rels = try XCTUnwrap(arc.text(named: "word/_rels/document.xml.rels"))
        XCTAssertFalse(rels.contains("/comments\""), "comments rel stripped")
        XCTAssertTrue(rels.contains("/styles\""), "styles rel kept")
    }

    func testReopensCleanWithZeroAnnotations() throws {
        let out = try DocxFromText.build(sourceDocx: try buildAnnotatedDocxFixture(), text: "Para one.\n\nPara two.")
        let loaded = try DocxStore.load(out)
        XCTAssertTrue(loaded.annotations.isEmpty, "draft must open annotation-less")
        XCTAssertEqual(loaded.plainText, "Para one.\nPara two.\n")
        XCTAssertNil(loaded.position, "draft must carry no reading position")
    }

    func testSoftLineBreaksWithinAParagraph() throws {
        let out = try DocxFromText.build(sourceDocx: try buildAnnotatedDocxFixture(), text: "Line one.\nLine two.")
        let plain = PlainTextMapper.build(try XCTUnwrap(try DocxArchive.read(out).text(named: "word/document.xml"))).plain
        XCTAssertEqual(plain, "Line one.\nLine two.\n")
    }
}

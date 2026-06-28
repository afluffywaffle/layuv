import XCTest
@testable import LeamhDocx

/// Word comment-thread round-trip — Swift mirror of ThreadCommentTest.kt. Covers: (a) a Word reply
/// is flattened onto its parent as a `source="word"` ThreadEntry (not a standalone annotation),
/// (b) commentsExtended.xml is emptied on write so paraId links can't dangle, (c) a threaded
/// annotation writes one comment paragraph per entry.
final class ThreadCommentTests: XCTestCase {

    private let parentEpoch = Int64((Timestamps.parse("2026-06-08T10:00:00.000Z").timeIntervalSince1970 * 1000).rounded())
    private let replyEpoch  = Int64((Timestamps.parse("2026-06-08T11:00:00.000Z").timeIntervalSince1970 * 1000).rounded())

    // (a) — reply flattened onto parent ----------------------------------------

    func testReplyIsFlattenedOntoParentThread() throws {
        let doc = try goldenText("import/thread.document.xml")
        let comments = try goldenText("import/thread.comments.xml")
        let ext = try goldenText("import/thread.commentsExtended.xml")
        let map = PlainTextMapper.build(doc)

        let anns = LegacyComments.parseComments(comments, documentXml: doc, map: map, commentsExtendedXml: ext)

        XCTAssertEqual(anns.count, 1)
        let parent = anns[0]
        XCTAssertEqual(parent.id, "word_0")
        XCTAssertEqual(parent.threadEntries.count, 2)
        XCTAssertEqual(parent.note, "Parent comment text")
        XCTAssertEqual(parent.threadEntries[0].text, "Parent comment text")
        XCTAssertEqual(parent.threadEntries[0].timestamp, parentEpoch)
        XCTAssertEqual(parent.threadEntries[1].text, "Reply text")
        XCTAssertEqual(parent.threadEntries[1].timestamp, replyEpoch)
        XCTAssertTrue(parent.threadEntries.allSatisfy { $0.source == ThreadEntry.sourceWord })
    }

    func testWithoutExtendedFileRepliesStayStandalone() throws {
        let doc = try goldenText("import/thread.document.xml")
        let comments = try goldenText("import/thread.comments.xml")
        let map = PlainTextMapper.build(doc)

        let anns = LegacyComments.parseComments(comments, documentXml: doc, map: map, commentsExtendedXml: nil)

        XCTAssertEqual(anns.count, 2)
        XCTAssertTrue(anns.allSatisfy { $0.threadEntries.isEmpty })
    }

    // (b) — commentsExtended emptied on round-trip write ------------------------

    func testRoundTripEmptiesCommentsExtended() throws {
        let base = try goldenData("writeback/input.docx")
        var entries = try DocxArchive.read(base).toMutableEntries()
        entries["word/commentsExtended.xml"] = Data(try goldenText("import/thread.commentsExtended.xml").utf8)
        let withExt = try DocxArchive.write(entries)

        let out = try DocxStore.write(withExt, annotations: [])
        let outExt = try XCTUnwrap(try DocxArchive.read(out).text(named: "word/commentsExtended.xml"))

        XCTAssertTrue(outExt.contains("commentsEx"), "root element preserved")
        XCTAssertFalse(outExt.contains("commentEx "), "no <w15:commentEx> entries remain")
        XCTAssertFalse(outExt.contains("paraIdParent"), "no dangling reply links remain")
    }

    func testAbsentCommentsExtendedIsNotCreated() throws {
        let out = try DocxStore.write(try goldenData("writeback/input.docx"), annotations: [])
        XCTAssertFalse(try DocxArchive.read(out).contains("word/commentsExtended.xml"),
                       "write must never create commentsExtended.xml")
    }

    // (c) — one comment paragraph per thread entry ------------------------------

    func testThreadWritesOneParagraphPerEntry() {
        let ann = Annotation(
            id: "word_0", selectedText: "annotated span", prefix: "", suffix: "",
            tool: .comment, note: "Parent comment text",
            timestamp: Date(timeIntervalSince1970: Double(parentEpoch) / 1000.0),
            position: 0.1,
            threadEntries: [
                ThreadEntry(text: "Parent comment text", timestamp: parentEpoch, source: ThreadEntry.sourceWord),
                ThreadEntry(text: "Reply text", timestamp: replyEpoch, source: ThreadEntry.sourceWord),
            ]
        )

        let xml = CommentWriter.buildNoteComment(xmlId: 0, annotation: ann, inkRelId: nil)

        XCTAssertEqual(xml.components(separatedBy: "<w:p>").count - 1, 3)
        XCTAssertTrue(xml.contains("Parent comment text"))
        XCTAssertTrue(xml.contains("Reply text"))
        XCTAssertTrue(xml.contains("] Reply text"), "reply paragraph is timestamp-prefixed")
        XCTAssertFalse(xml.contains("] Parent comment text"), "first entry is not prefixed")
    }

    // (a') — multi-level and multi-paragraph threading -------------------------

    func testMultiLevelReplyChainFlattensOntoRoot() {
        let doc = docWithRange("0")
        let comments = #"<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"# +
            #"<w:comments xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main""# +
            #" xmlns:w14="http://schemas.microsoft.com/office/word/2010/wordml">"# +
            #"<w:comment w:id="0" w:author="A" w:date="2026-06-08T10:00:00.000Z"><w:p w14:paraId="AAAA0000"><w:r><w:t>Root</w:t></w:r></w:p></w:comment>"# +
            #"<w:comment w:id="1" w:author="B" w:date="2026-06-08T11:00:00.000Z"><w:p w14:paraId="AAAA1111"><w:r><w:t>Reply one</w:t></w:r></w:p></w:comment>"# +
            #"<w:comment w:id="2" w:author="C" w:date="2026-06-08T12:00:00.000Z"><w:p w14:paraId="AAAA2222"><w:r><w:t>Reply two deep</w:t></w:r></w:p></w:comment>"# +
            #"</w:comments>"#
        let ext = #"<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"# +
            #"<w15:commentsEx xmlns:w15="http://schemas.microsoft.com/office/word/2012/wordml">"# +
            #"<w15:commentEx w15:paraId="AAAA0000" w15:done="0"/>"# +
            #"<w15:commentEx w15:paraId="AAAA1111" w15:paraIdParent="AAAA0000" w15:done="0"/>"# +
            #"<w15:commentEx w15:paraId="AAAA2222" w15:paraIdParent="AAAA1111" w15:done="0"/>"# +
            #"</w15:commentsEx>"#

        let anns = LegacyComments.parseComments(comments, documentXml: doc, map: PlainTextMapper.build(doc), commentsExtendedXml: ext)

        XCTAssertEqual(anns.count, 1)
        XCTAssertEqual(anns[0].threadEntries.map { $0.text }, ["Root", "Reply one", "Reply two deep"])
    }

    func testReplyToMultiParagraphParentIsThreaded() {
        let doc = docWithRange("0")
        let comments = #"<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"# +
            #"<w:comments xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main""# +
            #" xmlns:w14="http://schemas.microsoft.com/office/word/2010/wordml">"# +
            #"<w:comment w:id="0" w:author="A" w:date="2026-06-08T10:00:00.000Z">"# +
            #"<w:p w14:paraId="BBBB0000"><w:r><w:t>First para</w:t></w:r></w:p>"# +
            #"<w:p w14:paraId="BBBB0001"><w:r><w:t>Second para</w:t></w:r></w:p></w:comment>"# +
            #"<w:comment w:id="1" w:author="B" w:date="2026-06-08T11:00:00.000Z"><w:p w14:paraId="BBBB1111"><w:r><w:t>A reply</w:t></w:r></w:p></w:comment>"# +
            #"</w:comments>"#
        let ext = #"<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"# +
            #"<w15:commentsEx xmlns:w15="http://schemas.microsoft.com/office/word/2012/wordml">"# +
            #"<w15:commentEx w15:paraId="BBBB0001" w15:done="0"/>"# +
            #"<w15:commentEx w15:paraId="BBBB1111" w15:paraIdParent="BBBB0001" w15:done="0"/>"# +
            #"</w15:commentsEx>"#

        let anns = LegacyComments.parseComments(comments, documentXml: doc, map: PlainTextMapper.build(doc), commentsExtendedXml: ext)

        XCTAssertEqual(anns.count, 1)
        let thread = anns[0].threadEntries
        XCTAssertEqual(thread.count, 2)
        XCTAssertEqual(thread[0].text, "First para Second para")
        XCTAssertEqual(thread[1].text, "A reply")
    }

    // MARK: - Helpers

    private func docWithRange(_ id: String) -> String {
        #"<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"# +
            #"<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p>"# +
            #"<w:r><w:t>Intro. </w:t></w:r><w:commentRangeStart w:id=""# + id + #""/>"# +
            #"<w:r><w:t>anchored span</w:t></w:r><w:commentRangeEnd w:id=""# + id + #""/>"# +
            #"<w:r><w:t> tail.</w:t></w:r></w:p></w:body></w:document>"#
    }

    private func goldenURL(_ relativePath: String) -> URL {
        if let url = Bundle.module.url(forResource: "golden/\(relativePath)", withExtension: nil) {
            return url
        }
        return Bundle.module.bundleURL.appendingPathComponent("Resources/golden/\(relativePath)")
    }

    private func goldenText(_ relativePath: String) throws -> String {
        try String(contentsOf: goldenURL(relativePath), encoding: .utf8)
    }

    private func goldenData(_ relativePath: String) throws -> Data {
        try Data(contentsOf: goldenURL(relativePath))
    }
}

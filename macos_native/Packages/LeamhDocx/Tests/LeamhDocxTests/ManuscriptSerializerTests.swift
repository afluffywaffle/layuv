import XCTest
@testable import LeamhDocx

/// Mirror of ManuscriptSerializerTest.kt, adapted to the Swift engine's note-only annotations
/// (no ThreadEntry yet — iPad has no thread editor). Thread-folding assertions are omitted; the
/// comment annotation carries a plain note instead.
final class ManuscriptSerializerTests: XCTestCase {

    private func ann(_ tool: AnnotationTool, _ selected: String,
                     note: String? = nil, tag: AnnotationTag? = nil, hasInk: Bool = false) -> Annotation {
        Annotation(id: "id-\(selected)", selectedText: selected, prefix: "", suffix: "",
                   tool: tool, note: note, tag: tag,
                   timestamp: Date(timeIntervalSince1970: 0), position: 0.0, hasInk: hasInk)
    }

    func testRendersPreambleChapterAndEveryAnnotation() {
        let annotations = [
            ann(.highlight, "the old man", note: "tighten this", tag: .pacing),
            ann(.strikethrough, "very, very tired"),
            ann(.comment, "the door", note: "which one?"),
            ann(.highlight, "the cliff", hasInk: true),
        ]
        let result = ManuscriptSerializer.buildPrompt(plainText: "Chapter body text here.", annotations: annotations)
        let prompt = result.text

        XCTAssertTrue(prompt.contains("revise a manuscript chapter"), "has preamble")
        XCTAssertTrue(prompt.contains("=== CHAPTER ==="))
        XCTAssertTrue(prompt.contains("Chapter body text here."))
        XCTAssertTrue(prompt.contains("=== ANNOTATIONS (4) ==="))
        XCTAssertTrue(prompt.contains("[Highlight] \u{201C}the old man\u{201D}"))
        XCTAssertTrue(prompt.contains("note: tighten this"))
        XCTAssertTrue(prompt.contains("tag: pacing"))
        XCTAssertTrue(prompt.contains("[Strikethrough \u{2014} cut] \u{201C}very, very tired\u{201D}"))
        XCTAssertTrue(prompt.contains("[Comment] \u{201C}the door\u{201D}"))
        XCTAssertTrue(prompt.contains("note: which one?"))
        // An ink annotation is referenced as an attached image, and its id is surfaced for loading.
        XCTAssertTrue(prompt.contains("handwritten note: see attached image 1"))
        XCTAssertEqual(result.inkAnnotationIds, ["id-the cliff"])
    }

    func testHandlesNoAnnotations() {
        let prompt = ManuscriptSerializer.buildPrompt(plainText: "Just the chapter.", annotations: []).text
        XCTAssertTrue(prompt.contains("=== ANNOTATIONS (0) ==="))
        XCTAssertTrue(prompt.contains("(none)"))
        XCTAssertFalse(prompt.contains("note:"))
    }

    func testExportBodyDropsPreambleButKeepsChapterAndAnnotations() {
        let annotations = [
            ann(.highlight, "the old man", note: "tighten this", tag: .pacing),
            ann(.highlight, "the cliff", hasInk: true),
        ]
        let body = ManuscriptSerializer.buildExportBody(plainText: "Chapter body text here.", annotations: annotations)

        XCTAssertFalse(body.text.contains("revise a manuscript chapter"), "no preamble")
        XCTAssertFalse(body.text.contains(RewriteProtocol.begin), "no rewrite markers")
        XCTAssertTrue(body.text.hasPrefix("=== CHAPTER ==="))
        XCTAssertTrue(body.text.contains("Chapter body text here."))
        XCTAssertTrue(body.text.contains("[Highlight] \u{201C}the old man\u{201D}"))
        XCTAssertTrue(body.text.contains("note: tighten this"))
        XCTAssertTrue(body.text.contains("tag: pacing"))
        XCTAssertTrue(body.text.contains("handwritten note: see attached image 1"))
        XCTAssertEqual(body.inkAnnotationIds, ["id-the cliff"])
    }

    func testBuildPromptIsPreamblePlusExportBody() {
        let annotations = [ann(.comment, "the door", note: "which one?")]
        let plain = "Chapter body."
        let prompt = ManuscriptSerializer.buildPrompt(plainText: plain, annotations: annotations)
        let body = ManuscriptSerializer.buildExportBody(plainText: plain, annotations: annotations)
        XCTAssertTrue(prompt.text.hasSuffix(body.text), "prompt ends with the export body")
        XCTAssertTrue(prompt.text.contains("revise a manuscript chapter"))
        XCTAssertEqual(prompt.inkAnnotationIds, body.inkAnnotationIds)
    }
}

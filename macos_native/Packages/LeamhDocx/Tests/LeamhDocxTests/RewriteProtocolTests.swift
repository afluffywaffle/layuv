import XCTest
@testable import LeamhDocx

/// Mirror of RewriteProtocolTest.kt.
final class RewriteProtocolTests: XCTestCase {

    func testPlainConversationHasNoRewrite() {
        let p = RewriteProtocol.parse("Which ending did you prefer \u{2014} the quiet one?")
        XCTAssertEqual(p.conversation, "Which ending did you prefer \u{2014} the quiet one?")
        XCTAssertNil(p.rewrite)
    }

    func testExtractsRewriteBlockAndPreamble() {
        let text = "Here's a tighter take.\n\n" +
            "\(RewriteProtocol.begin)\nThe lighthouse stood.\n\nMara climbed.\n\(RewriteProtocol.end)"
        let p = RewriteProtocol.parse(text)
        XCTAssertEqual(p.conversation, "Here's a tighter take.")
        XCTAssertEqual(p.rewrite, "The lighthouse stood.\n\nMara climbed.")
    }

    func testUnterminatedBlockKeepsPartialRewrite() {
        // Truncated mid-rewrite (no END marker): everything after BEGIN is the partial.
        let text = "\(RewriteProtocol.begin)\nThe lighthouse stood and the"
        let p = RewriteProtocol.parse(text)
        XCTAssertEqual(p.conversation, "")
        XCTAssertEqual(p.rewrite, "The lighthouse stood and the")
    }
}

import XCTest
@testable import LeamhDocx

final class AnchoringTests: XCTestCase {

    func testLocate() throws {
        let json = try golden("anchoring/locate.json")
        let cases = try JSONSerialization.jsonObject(with: Data(json.utf8)) as! [[String: Any?]]

        for c in cases {
            let name         = c["name"] as! String
            let plain        = c["plain"] as! String
            let selectedText = c["selectedText"] as! String
            let prefix       = c["prefix"] as! String
            let suffix       = c["suffix"] as! String
            let positionHint = (c["positionHint"] as! NSNumber).doubleValue

            let actual = Anchoring.locateInPlain(plain, selectedText: selectedText,
                                                 prefix: prefix, suffix: suffix,
                                                 positionHint: positionHint)

            if c["expected"] is NSNull || c["expected"] == nil {
                XCTAssertNil(actual, "locate:\(name) — expected nil but got \(String(describing: actual))")
            } else {
                let e = c["expected"] as! [String: Any]
                let expected = TextSpan(start: (e["start"] as! NSNumber).intValue,
                                        end:   (e["end"]   as! NSNumber).intValue)
                XCTAssertEqual(actual, expected, "locate:\(name)")
            }
        }
    }

    func testWordsnap() throws {
        let json = try golden("anchoring/wordsnap.json")
        let cases = try JSONSerialization.jsonObject(with: Data(json.utf8)) as! [[String: Any]]

        for c in cases {
            let name  = c["name"] as! String
            let text  = c["text"] as! String
            let start = (c["start"] as! NSNumber).intValue
            let end   = (c["end"]   as! NSNumber).intValue
            let e     = c["expected"] as! [String: Any]
            let expected = TextSpan(start: (e["start"] as! NSNumber).intValue,
                                    end:   (e["end"]   as! NSNumber).intValue)

            let actual = Anchoring.snapToWordBoundaries(text, start: start, end: end)
            XCTAssertEqual(actual, expected, "wordsnap:\(name)")
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
}

import PencilKit
import UIKit

/// Converts a PencilKit drawing to/from the DOCX ink artifacts:
///  - a re-editable strokes blob (PKDrawing's native data, base64 in a small JSON wrapper) stored
///    at word/media/ink_<id>_strokes.json so the iPad can reopen and edit its own ink, and
///  - a transparent PNG (the cross-app artifact embedded via InkDrawing DrawingML, read by
///    Word/Pages/Google Docs and the AI vision path) at word/media/ink_<id>.png.
///
/// Android stores a vector-JSON stroke format instead; the two are not mutually re-editable, but
/// both produce the PNG, so ink always *displays* cross-platform and the loader degrades to nil
/// strokes (PNG-only) when it meets the other platform's blob.
enum InkRasterizer {
    private static let strokesFormat = "pencilkit"

    /// Wrap PKDrawing's native data as a small JSON blob for the strokes sidecar.
    static func strokesJSON(from drawing: PKDrawing) -> String {
        let b64 = drawing.dataRepresentation().base64EncodedString()
        let obj: [String: Any] = ["format": strokesFormat, "data": b64]
        if let data = try? JSONSerialization.data(withJSONObject: obj),
           let s = String(data: data, encoding: .utf8) {
            return s
        }
        return "{}"
    }

    /// Decode a stored strokes blob back into a PKDrawing (nil if absent or not a PencilKit blob).
    static func drawing(fromJSON json: String?) -> PKDrawing? {
        guard let json,
              let data = json.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              (obj["format"] as? String) == strokesFormat,
              let b64 = obj["data"] as? String,
              let raw = Data(base64Encoded: b64),
              let drawing = try? PKDrawing(data: raw) else { return nil }
        return drawing
    }

    /// Rasterise to a transparent PNG, letterboxed into a 2:1 box (matches the 4"×2" DrawingML
    /// extent so Word/Pages display it without distortion). Forces light mode so the ink renders
    /// dark on the page rather than inverted. nil if the drawing is empty.
    static func png(from drawing: PKDrawing) -> Data? {
        let b = drawing.bounds
        guard b.width > 0, b.height > 0 else { return nil }
        let pad: CGFloat = 24
        var w = b.width + pad * 2
        var h = b.height + pad * 2
        if w / h < 2 { w = h * 2 } else { h = w / 2 }   // expand to 2:1
        let rect = CGRect(x: b.midX - w / 2, y: b.midY - h / 2, width: w, height: h)

        var png: Data?
        UITraitCollection(userInterfaceStyle: .light).performAsCurrent {
            png = drawing.image(from: rect, scale: 2).pngData()
        }
        return png
    }
}

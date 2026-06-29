import AppKit

/// Converts macOS ink strokes to/from the DOCX ink artifacts:
///  - a re-editable `macink` JSON blob (own polyline format) stored at
///    word/media/ink_<id>_strokes.json so the Mac can reopen and edit its own ink, and
///  - a transparent PNG (the cross-app artifact embedded via InkDrawing DrawingML, read by
///    Word/Pages/iPad/Android) at word/media/ink_<id>.png.
///
/// The iPad writes a `pencilkit` blob and Android a vector-JSON blob; the three are not mutually
/// re-editable, but all produce the PNG, so ink always *displays* cross-platform and the loader
/// degrades to PNG-only (view-only) when it meets another platform's blob.
enum MacInkRasterizer {
    private static let strokesFormat = "macink"

    static func strokesJSON(from strokes: [InkStroke]) -> String {
        // Each point is [x, y, w] (w = pressure-modulated width at that sample).
        let arr: [[String: Any]] = strokes.map { s in
            ["pts": s.points.map { [Double($0.x), Double($0.y), Double($0.w)] }]
        }
        let obj: [String: Any] = ["format": strokesFormat, "strokes": arr]
        if let data = try? JSONSerialization.data(withJSONObject: obj),
           let s = String(data: data, encoding: .utf8) { return s }
        return "{}"
    }

    static func strokes(fromJSON json: String?) -> [InkStroke]? {
        guard let json,
              let data = json.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              (obj["format"] as? String) == strokesFormat,
              let raw = obj["strokes"] as? [[String: Any]] else { return nil }
        return raw.map { item in
            let strokeW = (item["w"] as? Double).map { CGFloat($0) } ?? 2.5   // legacy per-stroke width
            let pts = (item["pts"] as? [[Double]])?.compactMap { p -> InkPoint? in
                guard p.count >= 2 else { return nil }
                let w = p.count >= 3 ? CGFloat(p[2]) : strokeW
                return InkPoint(x: CGFloat(p[0]), y: CGFloat(p[1]), w: w)
            } ?? []
            return InkStroke(points: pts)
        }
    }

    /// Rasterise to a transparent PNG, letterboxed into a 2:1 box (matches the 4"×2" DrawingML
    /// extent so Word/Pages display it without distortion). Dark ink on transparency. nil if empty.
    static func png(from strokes: [InkStroke]) -> Data? {
        let pts = strokes.flatMap(\.points)
        guard pts.count > 1 else { return nil }
        let minX = pts.map(\.x).min()!, maxX = pts.map(\.x).max()!
        let minY = pts.map(\.y).min()!, maxY = pts.map(\.y).max()!
        let bw = max(1, maxX - minX), bh = max(1, maxY - minY)
        let pad: CGFloat = 24
        var w = bw + pad * 2, h = bh + pad * 2
        if w / h < 2 { w = h * 2 } else { h = w / 2 }   // expand to 2:1
        let originX = (minX + maxX) / 2 - w / 2
        let originY = (minY + maxY) / 2 - h / 2

        let scale: CGFloat = 2
        let pxW = Int(w * scale), pxH = Int(h * scale)
        guard pxW > 0, pxH > 0,
              let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: pxW, pixelsHigh: pxH,
                                         bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true,
                                         isPlanar: false, colorSpaceName: .deviceRGB,
                                         bytesPerRow: 0, bitsPerPixel: 0) else { return nil }
        guard let ctx = NSGraphicsContext(bitmapImageRep: rep) else { return nil }
        NSGraphicsContext.saveGraphicsState()
        NSGraphicsContext.current = ctx
        let c = ctx.cgContext
        c.scaleBy(x: scale, y: scale)
        c.translateBy(x: -originX, y: -originY)
        c.setStrokeColor(NSColor.black.cgColor)
        c.setLineCap(.round)
        c.setLineJoin(.round)
        // Variable width: stroke each segment at the mean of its endpoints' widths.
        for stroke in strokes where stroke.points.count > 1 {
            let p = stroke.points
            for i in 1..<p.count {
                c.setLineWidth(max(0.5, (p[i-1].w + p[i].w) / 2))
                c.beginPath()
                c.move(to: p[i-1].cg)
                c.addLine(to: p[i].cg)
                c.strokePath()
            }
        }
        NSGraphicsContext.restoreGraphicsState()
        return rep.representation(using: .png, properties: [:])
    }
}

/// DrawingML markup that embeds an ink PNG in a comment body + relationship-id scheme.
/// Mirrors InkDrawing.kt — 4"×2" in EMU (914400 EMU/inch).
enum InkDrawing {
    static let cx = 3657600 // 4 inches
    static let cy = 1828800 // 2 inches

    static func relId(_ annotationId: String) -> String {
        let safe = annotationId.unicodeScalars.map { c -> Character in
            let ch = Character(c)
            if ch.isLetter || ch.isNumber || ch == "_" { return ch } else { return "_" }
        }
        return "rId_ink_" + String(safe)
    }

    /// drawingId must be unique across all drawings in the document.
    static func build(relId: String, drawingId: Int) -> String {
        "<w:p><w:r><w:drawing>" +
        "<wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">" +
        "<wp:extent cx=\"\(cx)\" cy=\"\(cy)\"/>" +
        "<wp:docPr id=\"\(drawingId)\" name=\"Ink\"/>" +
        "<wp:cNvGraphicFramePr>" +
        "<a:graphicFrameLocks noChangeAspect=\"1\"/>" +
        "</wp:cNvGraphicFramePr>" +
        "<a:graphic>" +
        "<a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
        "<pic:pic>" +
        "<pic:nvPicPr>" +
        "<pic:cNvPr id=\"\(drawingId)\" name=\"ink.png\"/>" +
        "<pic:cNvPicPr/>" +
        "</pic:nvPicPr>" +
        "<pic:blipFill>" +
        "<a:blip r:embed=\"\(relId)\"/>" +
        "<a:stretch><a:fillRect/></a:stretch>" +
        "</pic:blipFill>" +
        "<pic:spPr>" +
        "<a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"\(cx)\" cy=\"\(cy)\"/></a:xfrm>" +
        "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom>" +
        "</pic:spPr>" +
        "</pic:pic>" +
        "</a:graphicData>" +
        "</a:graphic>" +
        "</wp:inline>" +
        "</w:drawing></w:r></w:p>"
    }
}

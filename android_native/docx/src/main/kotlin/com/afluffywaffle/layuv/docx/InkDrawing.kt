package com.afluffywaffle.layuv.docx

/**
 * The `<w:drawing><wp:inline>` markup that embeds an ink PNG in a comment body,
 * plus the relationship-id scheme. Mirror of docx_store `_buildInkDrawing`,
 * `_inkRelId`, `_inkCx`, `_inkCy`. The image is 4"×2" in EMU (914400 EMU/inch).
 */
object InkDrawing {
    const val CX = 3657600 // 4 inches
    const val CY = 1828800 // 2 inches

    fun relId(annotationId: String): String =
        "rId_ink_" + annotationId.replace(Regex("[^a-zA-Z0-9_]"), "_")

    fun build(relId: String): String =
        "<w:p><w:r><w:drawing>" +
            "<wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">" +
            "<wp:extent cx=\"$CX\" cy=\"$CY\"/>" +
            "<wp:docPr id=\"1\" name=\"Ink\"/>" +
            "<wp:cNvGraphicFramePr>" +
            "<a:graphicFrameLocks noChangeAspect=\"1\"/>" +
            "</wp:cNvGraphicFramePr>" +
            "<a:graphic>" +
            "<a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
            "<pic:pic>" +
            "<pic:nvPicPr>" +
            "<pic:cNvPr id=\"1\" name=\"ink.png\"/>" +
            "<pic:cNvPicPr/>" +
            "</pic:nvPicPr>" +
            "<pic:blipFill>" +
            "<a:blip r:embed=\"$relId\"/>" +
            "<a:stretch><a:fillRect/></a:stretch>" +
            "</pic:blipFill>" +
            "<pic:spPr>" +
            "<a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$CX\" cy=\"$CY\"/></a:xfrm>" +
            "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom>" +
            "</pic:spPr>" +
            "</pic:pic>" +
            "</a:graphicData>" +
            "</a:graphic>" +
            "</wp:inline>" +
            "</w:drawing></w:r></w:p>"
}

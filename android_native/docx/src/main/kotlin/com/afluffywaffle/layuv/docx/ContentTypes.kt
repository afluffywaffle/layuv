package com.afluffywaffle.layuv.docx

/**
 * Ensures `[Content_Types].xml` declares the parts Léamh adds (comments
 * override + png/json/xml defaults) so Word accepts the file. Mirror of
 * docx_store `_ensureContentType`; each override is inserted before `</Types>`.
 */
object ContentTypes {
    fun ensure(raw: String): String {
        var r = raw
        if (!r.contains("PartName=\"/word/comments.xml\"")) {
            r = r.replaceFirst(
                "</Types>",
                "<Override PartName=\"/word/comments.xml\"" +
                    " ContentType=\"application/vnd.openxmlformats-officedocument" +
                    ".wordprocessingml.comments+xml\"/>\n</Types>",
            )
        }
        if (!r.contains("Extension=\"png\"")) {
            r = r.replaceFirst(
                "</Types>",
                "<Default Extension=\"png\" ContentType=\"image/png\"/>\n</Types>",
            )
        }
        if (!r.contains("Extension=\"json\"")) {
            r = r.replaceFirst(
                "</Types>",
                "<Default Extension=\"json\" ContentType=\"application/json\"/>\n</Types>",
            )
        }
        if (!r.contains("Extension=\"xml\"")) {
            r = r.replaceFirst(
                "</Types>",
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>\n</Types>",
            )
        }
        return r
    }
}

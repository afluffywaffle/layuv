/// Ensures [Content_Types].xml declares the parts Léamh adds.
/// Mirrors ContentTypes.kt / docx_store._ensureContentType.
enum ContentTypes {

    static func ensure(_ raw: String) -> String {
        var r = raw.replacingOccurrences(of: "<Types/>", with: "<Types></Types>")
        if !r.contains("PartName=\"/word/comments.xml\"") {
            r = r.replacingFirstOccurrence(
                of: "</Types>",
                with: "<Override PartName=\"/word/comments.xml\"" +
                      " ContentType=\"application/vnd.openxmlformats-officedocument" +
                      ".wordprocessingml.comments+xml\"/>\n</Types>"
            )
        }
        if !r.contains("Extension=\"png\"") {
            r = r.replacingFirstOccurrence(
                of: "</Types>",
                with: "<Default Extension=\"png\" ContentType=\"image/png\"/>\n</Types>"
            )
        }
        if !r.contains("Extension=\"json\"") {
            r = r.replacingFirstOccurrence(
                of: "</Types>",
                with: "<Default Extension=\"json\" ContentType=\"application/json\"/>\n</Types>"
            )
        }
        if !r.contains("Extension=\"xml\"") {
            r = r.replacingFirstOccurrence(
                of: "</Types>",
                with: "<Default Extension=\"xml\" ContentType=\"application/xml\"/>\n</Types>"
            )
        }
        return r
    }
}

private extension String {
    func replacingFirstOccurrence(of target: String, with replacement: String) -> String {
        guard let range = self.range(of: target) else { return self }
        return self.replacingCharacters(in: range, with: replacement)
    }
}

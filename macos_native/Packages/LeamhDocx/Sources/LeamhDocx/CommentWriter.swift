import Foundation

/// Builds word/comments.xml and the comment-related relationship/content entries.
/// Mirrors CommentWriter.kt.
enum CommentWriter {

    /// `author` is written as `w:author`; nil falls back to the annotation id (legacy behaviour,
    /// keeps goldens byte-identical). The app passes the user's configured name.
    static func buildNoteComment(xmlId: Int, annotation a: Annotation, inkRelId: String?, author: String? = nil) -> String {
        // When the annotation carries a thread, each entry is its own paragraph: the first (== note)
        // is plain; later entries are prefixed with their write time so Word/Pages readers see the
        // thread. With no thread, behaviour is unchanged — a single note paragraph (byte-identical).
        let noteXml: String
        if !a.threadEntries.isEmpty {
            noteXml = a.threadEntries.enumerated().compactMap { (i, entry) -> String? in
                let text = i == 0 ? entry.text
                                  : "[\(Timestamps.formatThreadPrefix(entry.timestamp))] \(entry.text)"
                guard !text.isEmpty else { return nil }
                return "<w:p><w:r><w:t xml:space=\"preserve\">\(XmlEntities.escape(text))</w:t></w:r></w:p>"
            }.joined()
        } else if let note = a.note, !note.isEmpty {
            noteXml = "<w:p><w:r><w:t xml:space=\"preserve\">\(XmlEntities.escape(note))</w:t></w:r></w:p>"
        } else {
            noteXml = ""
        }
        let tagXml: String
        if let tag = a.tag {
            tagXml = "<w:p><w:r><w:t xml:space=\"preserve\">[\(tag.rawValue)]</w:t></w:r></w:p>"
        } else {
            tagXml = ""
        }
        let drawingXml = inkRelId.map { InkDrawing.build(relId: $0, drawingId: xmlId + 1) } ?? ""
        let bodyParts = [noteXml, tagXml, drawingXml].filter { !$0.isEmpty }
        let bodyXml = bodyParts.isEmpty ? "<w:p/>" : bodyParts.joined()

        return "<w:comment w:id=\"\(xmlId)\" w:author=\"\(XmlEntities.escape(author ?? a.id))\"" +
               " w:date=\"\(Timestamps.format(a.timestamp))\">\n" +
               "  <w:p>\n" +
               "    <w:pPr><w:pStyle w:val=\"CommentText\"/></w:pPr>\n" +
               "    <w:r><w:rPr><w:rStyle w:val=\"CommentReference\"/></w:rPr>" +
               "<w:annotationRef/></w:r>\n" +
               "  </w:p>\n" +
               "  \(bodyXml)</w:comment>"
    }

    static func buildCommentsXml(_ commentAnnotations: [Annotation], author: String? = nil) -> String {
        let blocks = commentAnnotations.enumerated().map { (i, a) in
            buildNoteComment(xmlId: i, annotation: a, inkRelId: a.hasInk ? InkDrawing.relId(a.id) : nil, author: author)
        }.joined(separator: "\n")
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
               "<w:comments" +
               " xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"" +
               " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"" +
               " xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\"" +
               " xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"" +
               " xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"" +
               " xmlns:w14=\"http://schemas.microsoft.com/office/word/2010/wordml\"" +
               ">\n" +
               "\(blocks)\n" +
               "</w:comments>"
    }

    static let emptyComments =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<w:comments" +
        " xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"/>"

    private static let commentExChild = try! NSRegularExpression(
        pattern: "<w15:commentEx\\b[^>]*/>|<w15:commentEx\\b[\\s\\S]*?</w15:commentEx>"
    )

    /// Strips every `<w15:commentEx>` child from a `word/commentsExtended.xml` payload, leaving the
    /// root element + namespaces intact. Léamh rebuilds comments.xml paragraphs without Word's paraIds,
    /// so any retained commentEx would dangle; we empty the part (not delete it) so its content-type
    /// override and relationship stay valid. Mirrors CommentWriter.emptyCommentsExtended in Kotlin.
    static func emptyCommentsExtended(_ raw: String) -> String {
        commentExChild.stringByReplacingMatches(
            in: raw, range: NSRange(raw.startIndex..., in: raw), withTemplate: ""
        )
    }

    static func ensureRelsEntry(_ raw: String) -> String {
        if raw.contains("comments.xml") { return raw }
        let rel = "<Relationship Id=\"rId_leamh_comments\"" +
                  " Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/comments\"" +
                  " Target=\"comments.xml\"/>"
        let expanded = raw.replacingOccurrences(of: "<Relationships/>", with: "<Relationships></Relationships>")
        return expanded.replacingFirstOccurrence(of: "</Relationships>", with: "\(rel)\n</Relationships>")
    }

    private static let inkRelPattern = try! NSRegularExpression(
        pattern: #"<Relationship[^>]+rId_ink_[^>]*/?>$"#,
        options: .anchorsMatchLines
    )
    private static let anyRelPattern = try! NSRegularExpression(
        pattern: #"<Relationship[^>]*/?>"#
    )

    static func buildCommentsRels(_ inkAnnotations: [Annotation], existingRels: String? = nil) -> String {
        var preserved: [String] = []
        if let existing = existingRels {
            let stripped = inkRelPattern.stringByReplacingMatches(
                in: existing,
                range: NSRange(existing.startIndex..., in: existing),
                withTemplate: ""
            )
            preserved = anyRelPattern.matches(in: stripped, range: NSRange(stripped.startIndex..., in: stripped))
                .map { (stripped as NSString).substring(with: $0.range) }
        }
        let newEntries = inkAnnotations.map { a in
            "<Relationship Id=\"\(InkDrawing.relId(a.id))\"" +
            " Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\"" +
            " Target=\"media/ink_\(a.id).png\"/>"
        }
        let allEntries = (preserved + newEntries).joined(separator: "\n")
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
               "<Relationships" +
               " xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">\n" +
               "\(allEntries)\n" +
               "</Relationships>"
    }
}

private extension String {
    func replacingFirstOccurrence(of target: String, with replacement: String) -> String {
        guard let range = self.range(of: target) else { return self }
        return self.replacingCharacters(in: range, with: replacement)
    }
}

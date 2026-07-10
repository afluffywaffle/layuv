import Foundation

/// Builds the seed prompt for the in-app "Ask AI" conversation: a rewrite request over
/// the chapter's canonical plain text plus the author's annotations. Mirror of
/// ManuscriptSerializer.kt.
///
/// Pure: it receives the ALREADY-extracted plain text (`LoadedDocument.plainText`) and
/// must NOT parse XML itself — preserving the ONE canonical plain-text string invariant.
///
/// NOTE: Android folds an annotation's full comment thread (`threadEntries`) into the
/// note line; the Swift `Annotation` has no thread model yet (iPad has no thread editor),
/// so `noteText` uses the single `note`. When a thread editor / `ThreadEntry` lands, fold
/// the thread here to match Android's noteText.
public enum ManuscriptSerializer {

    private static let preamble =
        "You are helping an author revise a manuscript chapter, in a back-and-forth " +
        "conversation. Below is the chapter text, followed by the author's " +
        "annotations on specific passages.\n\n" +
        "Your default task is to rewrite the chapter to address every annotation " +
        "while preserving the author's voice and everything that isn't flagged. You " +
        "may ask a brief clarifying question first if an annotation is genuinely " +
        "ambiguous, and you can discuss the changes when the author replies.\n\n" +
        "Some annotations include the author's note as a HANDWRITTEN image, " +
        "referenced as \"attached image N\" (the Nth image attached, in order). Read " +
        "the handwriting in that image and treat it as the author's note for that " +
        "passage.\n\n" +
        "Format every rewrite exactly like this: put the FULL revised chapter — " +
        "plain prose, paragraphs separated by a blank line, no headings, markup, or " +
        "commentary — between a line reading " + RewriteProtocol.begin + " and a " +
        "line reading " + RewriteProtocol.end + ". Put any remarks to the author " +
        "BEFORE the " + RewriteProtocol.begin + " line, never inside the markers. " +
        "When you are only discussing or asking a question (not delivering a " +
        "rewrite), reply normally with no markers."

    /// The seed prompt text + the ids of ink annotations, in the order they're referenced as images.
    public struct Prompt: Equatable {
        public let text: String
        public let inkAnnotationIds: [String]
        public init(text: String, inkAnnotationIds: [String]) {
            self.text = text
            self.inkAnnotationIds = inkAnnotationIds
        }
    }

    public static func buildPrompt(plainText: String, annotations: [Annotation]) -> Prompt {
        let body = buildExportBody(plainText: plainText, annotations: annotations)
        return Prompt(text: preamble + "\n\n" + body.text, inkAnnotationIds: body.inkAnnotationIds)
    }

    /// The chapter + annotations body WITHOUT the in-app preamble (no RewriteProtocol markers).
    /// Used by the "Export for AI" file path, where the user's OWN project instructions drive
    /// the rewrite. Same Prompt shape: body text + ink-annotation ids in "attached image N" order.
    public static func buildExportBody(plainText: String, annotations: [Annotation]) -> Prompt {
        let ws = CharacterSet.whitespacesAndNewlines
        var sb = ""
        var inkIds: [String] = []
        sb += "=== CHAPTER ===\n"
        sb += plainText.trimmingCharacters(in: ws) + "\n\n"
        sb += "=== ANNOTATIONS (\(annotations.count)) ===\n"
        if annotations.isEmpty {
            sb += "(none)\n"
        } else {
            for (i, a) in annotations.enumerated() {
                sb += "\(i + 1). [\(label(a.tool))] \u{201C}\(a.selectedText.trimmingCharacters(in: ws))\u{201D}\n"
                let note = noteText(a)
                if !note.isEmpty { sb += "   note: \(note)\n" }
                if a.hasInk {
                    inkIds.append(a.id)
                    sb += "   handwritten note: see attached image \(inkIds.count)\n"
                }
                if let tag = a.tag { sb += "   tag: \(tag.rawValue)\n" }
            }
        }
        return Prompt(text: sb, inkAnnotationIds: inkIds)
    }

    /// Full thread text when present (the note is just the first entry), else the note.
    /// Mirrors ManuscriptSerializer.noteText in Kotlin (continuation lines indent under "note: ").
    /// Annotations-only export: no chapter text, just each annotation's anchor (paragraph
    /// number, computed from the same exact char offset used to place the DOCX comment range —
    /// NOT reverse-derived from the position fraction — plus position fraction + prefix/suffix
    /// context around the quoted passage) plus note/tag/thread. Lets an AI that already has the
    /// manuscript (e.g. reading the project folder directly) locate each annotation without the
    /// chapter body being duplicated into the export, and without re-deriving anchoring itself.
    /// `fileName` identifies which document these anchors belong to once exports from multiple
    /// chapters sit side by side.
    public static func buildAnnotationsOnlyExport(fileName: String, annotations: [Annotation]) -> Prompt {
        let ws = CharacterSet.whitespacesAndNewlines
        var sb = "=== ANNOTATIONS FOR \"\(fileName)\" (\(annotations.count)) ===\n"
        var inkIds: [String] = []
        if annotations.isEmpty {
            sb += "(none)\n"
            return Prompt(text: sb, inkAnnotationIds: inkIds)
        }
        for (i, a) in annotations.enumerated() {
            let pct = Int((a.position * 100).rounded())
            let paraLabel = a.paragraph > 0 ? "paragraph ~\(a.paragraph), " : ""
            sb += "\(i + 1). [\(label(a.tool))] \(paraLabel)~\(pct)% through the manuscript\n"
            let prefix = a.prefix.trimmingCharacters(in: ws)
            let suffix = a.suffix.trimmingCharacters(in: ws)
            let quoted = a.selectedText.trimmingCharacters(in: ws)
            var context = ""
            if !prefix.isEmpty { context += "\u{2026}\(prefix)" }
            context += "\u{2039}\(quoted)\u{203A}"
            if !suffix.isEmpty { context += "\(suffix)\u{2026}" }
            sb += "   context: \(context)\n"
            let note = noteText(a)
            if !note.isEmpty { sb += "   note: \(note)\n" }
            if a.hasInk {
                inkIds.append(a.id)
                sb += "   handwritten note: see attached image \(inkIds.count)\n"
            }
            if let tag = a.tag { sb += "   tag: \(tag.rawValue)\n" }
        }
        return Prompt(text: sb, inkAnnotationIds: inkIds)
    }

    private static func noteText(_ a: Annotation) -> String {
        if !a.threadEntries.isEmpty {
            return a.threadEntries
                .map { $0.text.trimmingCharacters(in: .whitespacesAndNewlines) }
                .joined(separator: "\n         ")
        }
        return (a.note ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func label(_ tool: AnnotationTool) -> String {
        switch tool {
        case .highlight:       return "Highlight"
        case .underline:       return "Underline"
        case .doubleUnderline: return "Double underline"
        case .strikethrough:   return "Strikethrough \u{2014} cut"
        case .wavyUnderline:   return "Wavy underline"
        case .bookmark:        return "Bookmark"
        case .inkAnnotation:   return "Ink note"
        case .comment:         return "Comment"
        case .blockquote:      return "Blockquote"
        }
    }
}

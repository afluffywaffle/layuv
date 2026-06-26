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

    /// The note text (single note for now; see the type note about threads).
    private static func noteText(_ a: Annotation) -> String {
        (a.note ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
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
        }
    }
}

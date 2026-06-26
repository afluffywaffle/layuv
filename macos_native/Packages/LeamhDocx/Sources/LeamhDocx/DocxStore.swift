import Foundation

/// An annotation resolved to its current char range in the canonical plain text (nil if unlocatable).
public struct ResolvedAnnotation {
    public let annotation: Annotation
    public let span: TextSpan?
    public init(annotation: Annotation, span: TextSpan?) {
        self.annotation = annotation
        self.span = span
    }
}

/// Loaded reader state for a DOCX.
public struct LoadedDocument {
    public let plainMap: PlainMap
    public let annotations: [ResolvedAnnotation]
    public let position: ReadingPosition?

    public var plainText: String { plainMap.plain }
    public var formatSpans: [FormatSpan] { plainMap.formats }
}

/// Read/write entry point for the DOCX engine. Mirror of DocxStore.kt.
///
/// Primary store is leamh/annotations.json; PNG presence overrides hasInk; every
/// annotation is re-anchored against the current canonical plain text via
/// Anchoring.locateInPlain (survives external edits). Degrades gracefully (empty/nil)
/// rather than throwing, matching the Kotlin/Dart contract.
public enum DocxStore {

    private static let annotationsPath  = "leamh/annotations.json"
    private static let positionPath     = "leamh/position.json"
    private static let aiChatPath       = "leamh/aichat.json"
    private static let documentPath     = "word/document.xml"
    private static let cleanPath        = "leamh/document_clean.xml"
    private static let commentsPath     = "word/comments.xml"
    private static let docRelsPath      = "word/_rels/document.xml.rels"
    private static let commentsRelsPath = "word/_rels/comments.xml.rels"
    private static let contentTypesPath = "[Content_Types].xml"

    public static func load(_ docxData: Data, now: Date = Date()) throws -> LoadedDocument {
        let archive = try DocxArchive.read(docxData)
        let documentXml = archive.text(named: cleanPath) ?? archive.text(named: documentPath) ?? ""
        let map = PlainTextMapper.build(documentXml)
        return LoadedDocument(
            plainMap: map,
            annotations: loadAnnotations(archive: archive, map: map, documentXml: documentXml, now: now),
            position: loadPosition(archive: archive)
        )
    }

    public static func loadAnnotations(
        archive: DocxArchive,
        map: PlainMap,
        documentXml: String,
        now: Date
    ) -> [ResolvedAnnotation] {
        do {
            let annotations: [Annotation]
            if let raw = archive.text(named: annotationsPath) {
                let parsed = try JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [[String: Any]] ?? []
                annotations = parsed.compactMap { m -> Annotation? in
                    do {
                        var a = Annotation.fromMap(m as [String: Any?])
                        let inkKey = "word/media/ink_\(a.id).png"
                        let hasInk = (archive.data(named: inkKey)?.isEmpty == false)
                        if hasInk != a.hasInk { a = a.copy(hasInk: hasInk) }
                        return a
                    } catch { return nil }
                }
            } else {
                let baseMicros = Int64(now.timeIntervalSince1970 * 1_000_000)
                let native = documentXml.isEmpty ? [] :
                    NativeImport.importNativeFormatting(documentXml: documentXml, map: map, baseMicros: baseMicros, now: now)
                let legacy = archive.text(named: commentsPath).map {
                    LegacyComments.parseComments($0, documentXml: documentXml, map: map)
                } ?? []
                annotations = native + legacy
            }
            return annotations.map { a in
                ResolvedAnnotation(
                    annotation: a,
                    span: Anchoring.locateInPlain(map.plain, selectedText: a.selectedText,
                                                  prefix: a.prefix, suffix: a.suffix, positionHint: a.position)
                )
            }
        } catch {
            return []
        }
    }

    public static func loadPosition(archive: DocxArchive) -> ReadingPosition? {
        guard let raw = archive.text(named: positionPath) else { return nil }
        do {
            let map = try JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String: Any] ?? [:]
            return ReadingPosition.fromMap(map as [String: Any?])
        } catch {
            return nil
        }
    }

    // MARK: - Write

    /// Returns new DOCX bytes. Does not touch the filesystem — the caller writes atomically.
    public static func write(_ docxData: Data, annotations: [Annotation]) throws -> Data {
        let archive = try DocxArchive.read(docxData)
        var entries = archive.toMutableEntries()

        // Save original document.xml as clean snapshot on first write.
        if !entries.contains(cleanPath), let doc = entries[documentPath] {
            entries[cleanPath] = doc
        }
        // Always restore document.xml from clean before injecting.
        if let clean = entries[cleanPath] { entries[documentPath] = clean }

        // leamh/annotations.json
        let annotationMaps = annotations.map { $0.toMap() }
        let jsonStr = JsonWriter.encode(annotationMaps)
        entries[annotationsPath] = Data(jsonStr.utf8)

        let commentAnnotations = annotations.filter { $0.note != nil || $0.tag != nil || $0.hasInk }

        if !commentAnnotations.isEmpty {
            entries[commentsPath] = Data(CommentWriter.buildCommentsXml(commentAnnotations).utf8)
            if let docRels = entries[docRelsPath],
               let docRelsStr = String(data: docRels, encoding: .utf8) {
                entries[docRelsPath] = Data(CommentWriter.ensureRelsEntry(docRelsStr).utf8)
            }
            let inkAnnotations = commentAnnotations.filter { $0.hasInk }
            if !inkAnnotations.isEmpty {
                let existingRels = entries[commentsRelsPath].flatMap { String(data: $0, encoding: .utf8) }
                entries[commentsRelsPath] = Data(CommentWriter.buildCommentsRels(inkAnnotations, existingRels: existingRels).utf8)
            }
        } else if entries.contains(commentsPath) {
            entries[commentsPath] = Data(CommentWriter.emptyComments.utf8)
        }

        if let ctData = entries[contentTypesPath],
           let ctStr = String(data: ctData, encoding: .utf8) {
            entries[contentTypesPath] = Data(ContentTypes.ensure(ctStr).utf8)
        }

        if let docData = entries[documentPath],
           let docStr = String(data: docData, encoding: .utf8) {
            let injected = RunPropertyInjector.inject(docStr, annotations: annotations, noteAnnotations: commentAnnotations)
            entries[documentPath] = Data(injected.utf8)
        }

        return try DocxArchive.write(entries)
    }

    /// Embeds a PNG at word/media/ink_<annotationId>.png. Call before write() so PNG is
    /// present when load() auto-detects hasInk.
    public static func saveInkPng(_ docxData: Data, annotationId: String, pngData: Data) throws -> Data {
        let archive = try DocxArchive.read(docxData)
        var entries = archive.toMutableEntries()
        entries["word/media/ink_\(annotationId).png"] = pngData
        return try DocxArchive.write(entries)
    }

    public static func readInkPng(_ docxData: Data, annotationId: String) throws -> Data? {
        let archive = try DocxArchive.read(docxData)
        return archive.data(named: "word/media/ink_\(annotationId).png")
    }

    public static func saveInkStrokes(_ docxData: Data, annotationId: String, json: String) throws -> Data {
        let archive = try DocxArchive.read(docxData)
        var entries = archive.toMutableEntries()
        entries["word/media/ink_\(annotationId)_strokes.json"] = Data(json.utf8)
        return try DocxArchive.write(entries)
    }

    public static func readInkStrokes(_ docxData: Data, annotationId: String) throws -> String? {
        let archive = try DocxArchive.read(docxData)
        return archive.data(named: "word/media/ink_\(annotationId)_strokes.json")
            .flatMap { String(data: $0, encoding: .utf8) }
    }

    public static func removeAllInkStrokes(_ docxData: Data) throws -> Data {
        let archive = try DocxArchive.read(docxData)
        var entries = archive.toMutableEntries()
        entries.removeAll(where: { $0.hasPrefix("word/media/ink_") && $0.hasSuffix("_strokes.json") })
        return try DocxArchive.write(entries)
    }

    public static func hasAnyInkStrokes(_ docxData: Data) throws -> Bool {
        let archive = try DocxArchive.read(docxData)
        return archive.names.contains(where: { $0.hasPrefix("word/media/ink_") && $0.hasSuffix("_strokes.json") })
    }

    public static func writePosition(_ docxData: Data, position: ReadingPosition) throws -> Data {
        let archive = try DocxArchive.read(docxData)
        var entries = archive.toMutableEntries()
        entries[positionPath] = Data(JsonWriter.encode(position.toMap() as Any?).utf8)
        return try DocxArchive.write(entries)
    }

    // MARK: - Ask-AI transcript (leamh/aichat.json)
    //
    // Persisted IN the chapter DOCX (same convention as annotations) so an in-app "Ask AI"
    // thread suspends/resumes across leaving the panel, process death, and reboot. writeAiChat
    // touches ONLY this part, so it coexists with annotation writes.

    /// Reads the persisted Ask-AI transcript, or empty if absent/garbage (load contract).
    public static func readAiChat(_ docxData: Data) -> [AiTurn] {
        do {
            let archive = try DocxArchive.read(docxData)
            guard let raw = archive.text(named: aiChatPath) else { return [] }
            let parsed = try JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [[String: Any]] ?? []
            return parsed.map { AiTurn.fromMap($0 as [String: Any?]) }
        } catch {
            return []
        }
    }

    /// Writes/replaces leamh/aichat.json; leaves every other part untouched. Ensures the json
    /// default content-type exists (a never-annotated chapter may lack it) so Word accepts it.
    public static func writeAiChat(_ docxData: Data, turns: [AiTurn]) throws -> Data {
        let archive = try DocxArchive.read(docxData)
        var entries = archive.toMutableEntries()
        entries[aiChatPath] = Data(JsonWriter.encode(turns.map { $0.toMap() }).utf8)
        if let ct = entries[contentTypesPath], let s = String(data: ct, encoding: .utf8) {
            entries[contentTypesPath] = Data(ContentTypes.ensure(s).utf8)
        }
        return try DocxArchive.write(entries)
    }
}

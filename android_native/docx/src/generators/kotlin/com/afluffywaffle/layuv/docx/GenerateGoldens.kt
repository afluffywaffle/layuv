package com.afluffywaffle.layuv.docx

import com.afluffywaffle.layuv.docx.model.Annotation
import com.afluffywaffle.layuv.docx.model.AnnotationTag
import com.afluffywaffle.layuv.docx.model.AnnotationTool
import com.afluffywaffle.layuv.docx.model.ReadingMode
import com.afluffywaffle.layuv.docx.model.ReadingPosition
import java.io.File
import java.time.Instant

// ─────────────────────────────────────────────────────────────────────────────
// Kotlin replacement for all four Dart golden generators in tools/golden_gen/.
// Calls the real engine — no Flutter/Dart dependency.
//
// Run:  cd android_native && ./gradlew :docx:generateGoldens
// ─────────────────────────────────────────────────────────────────────────────

fun main() {
    val base = File("docx/src/test/resources/golden")
    genPlainTextMapper(base)
    genImports(base)
    genEngine(base)
    genWriteback(base)
    println("✅ All goldens written to ${base.absolutePath}")
}

// ─────────────────────────────────────────────────────────────────────────────
// 1. PlainTextMapper goldens  (replaces gen_goldens.dart)
// ─────────────────────────────────────────────────────────────────────────────

private val PROSE_NAMES = setOf(
    "simple", "preserve", "multi_wt_run", "empty_para", "self_closing_run", "unicode",
)

// Static fixture XMLs — mirrors the `fixtures` map in gen_goldens.dart verbatim.
private val FIXTURES: Map<String, String> by lazy {
    val wns = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""
    fun doc(body: String) =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<w:document $wns><w:body>$body</w:body></w:document>"
    fun p(runs: String) = "<w:p>$runs</w:p>"
    fun r(t: String, attrs: String? = null) =
        "<w:r><w:t${if (attrs == null) "" else " $attrs"}>$t</w:t></w:r>"

    mapOf(
        "simple"           to doc(p(r("Hello world.")) + p(r("Second paragraph."))),
        "preserve"         to doc(p(r("  leading and trailing  ", "xml:space=\"preserve\""))),
        "multi_wt_run"     to doc(p("<w:r><w:t>first part </w:t><w:t>second part</w:t></w:r>")),
        "empty_para"       to doc("${p(r("above"))}<w:p></w:p>${p(r("below"))}"),
        "self_closing_run" to doc(p(
            "<w:r><w:t>keep</w:t></w:r>" +
            "<w:r w:rsidR=\"00AB12CD\"/>" +
            "<w:r><w:t> this</w:t></w:r>"
        )),
        "unicode"          to doc(p(r("café — naïve 😀 end"))),
        "entities"         to doc(
            p(r("Tom &amp; Jerry &lt;tag&gt; &quot;q&quot; &apos;a&apos;")) +
            p(r("caf&#233; numeric"))
        ),
        "tabs_breaks"      to doc(p(
            "<w:r><w:t>before</w:t></w:r>" +
            "<w:r><w:tab/></w:r>" +
            "<w:r><w:t>after</w:t></w:r>" +
            "<w:r><w:br/></w:r>" +
            "<w:r><w:t>nextline-still-same-para</w:t></w:r>"
        )),
        "table"            to doc(
            "${p(r("intro"))}" +
            "<w:tbl><w:tr>" +
            "<w:tc>${p(r("cell A"))}</w:tc>" +
            "<w:tc>${p(r("cell B"))}</w:tc>" +
            "</w:tr></w:tbl>" +
            p(r("outro"))
        ),
    )
}

// Verbatim Dart _buildPlainMap (bug included: <w:t...> regex over-matches
// <w:tab/>, <w:tbl>, <w:tr>, <w:tc>).  Used only to generate legacy/ goldens.
private fun buildLegacyMap(xml: String): Pair<String, List<Int>> {
    val buf = StringBuilder()
    val offsets = mutableListOf<Int>()
    val re = Regex("<w:t(?:[^>]*)>(.*?)</w:t>|</w:p>", RegexOption.DOT_MATCHES_ALL)
    for (m in re.findAll(xml)) {
        if (m.value == "</w:p>") {
            buf.append('\n')
            offsets.add(m.range.first)
        } else {
            val text = legacyUnesc(m.groupValues[1])
            val base = m.range.first + m.value.indexOf('>') + 1
            for (i in text.indices) {
                buf.append(text[i])
                offsets.add(base + i)
            }
        }
    }
    return buf.toString() to offsets
}

// Named entity unescaping only — numeric entities NOT decoded (Dart _unesc bug).
private fun legacyUnesc(s: String): String = s
    .replace("&apos;", "'")
    .replace("&quot;", "\"")
    .replace("&gt;", ">")
    .replace("&lt;", "<")
    .replace("&amp;", "&")

private fun genPlainTextMapper(base: File) {
    val fixtureDir = File(base, "fixtures").apply { mkdirs() }
    val legacyDir  = File(base, "legacy").apply { mkdirs() }
    val cleanDir   = File(base, "clean").apply { mkdirs() }

    val report = StringBuilder()
    report.appendLine("# Golden generation report\n")
    report.appendLine("`legacy` = docx_store._buildPlainMap (reference). `clean` = native engine target.\n")
    report.appendLine("| fixture | prose? | clean==legacy? | len(legacy) | len(clean) | clean P |")
    report.appendLine("|---|---|---|---|---|---|")

    var allProseMatch = true
    for (name in FIXTURES.keys.sorted()) {
        val xml    = FIXTURES.getValue(name)
        val legacy = buildLegacyMap(xml)
        val clean  = PlainTextMapper.build(xml)

        File(fixtureDir, "$name.document.xml").writeText(xml)

        File(legacyDir, "$name.plain.txt").writeText(legacy.first)
        File(legacyDir, "$name.offsets.json").writeText(compactInts(legacy.second))

        File(cleanDir, "$name.plain.txt").writeText(clean.plain)
        File(cleanDir, "$name.offsets.json").writeText(compactInts(clean.xmlOffsets.toList()))

        val isProse = name in PROSE_NAMES
        val match = legacy.first == clean.plain &&
                compactInts(legacy.second) == compactInts(clean.xmlOffsets.toList())
        if (isProse && !match) allProseMatch = false

        val indicator = if (match) "✅" else "➖ (fixes bug)"
        val escaped = clean.plain.replace("\n", "\\n").replace("\t", "\\t")
        report.appendLine("| $name | ${if (isProse) "yes" else "no"} | $indicator | ${legacy.first.length} | ${clean.plain.length} | `$escaped` |")
    }

    File(base, "_REPORT.md").writeText(report.toString())
    if (!allProseMatch) error("❌ INVARIANT VIOLATED: a prose fixture has clean != legacy")
    println("  PlainTextMapper goldens written (${FIXTURES.size} fixtures)")
}

// ─────────────────────────────────────────────────────────────────────────────
// 2. Import goldens  (replaces gen_import_goldens.dart)
// ─────────────────────────────────────────────────────────────────────────────

// Fixed clock matching the Dart generator (DateTime.utc(2026,1,1)).
private val IMPORT_NOW = Instant.parse("2026-01-01T00:00:00Z")
private val IMPORT_BASE_MICROS = IMPORT_NOW.epochSecond * 1_000_000 + IMPORT_NOW.nano / 1000

private fun genImports(base: File) {
    val dir = File(base, "import").apply { mkdirs() }
    val wns = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""
    fun doc(body: String) =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<w:document $wns><w:body>$body</w:body></w:document>"
    fun plain(t: String) = "<w:r><w:t>$t</w:t></w:r>"
    fun fmt(rPr: String, t: String) = "<w:r><w:rPr>$rPr</w:rPr><w:t>$t</w:t></w:r>"

    // Native formatting fixture (prose only).
    val nativeDoc = doc(
        "<w:p>" +
        plain("This is ") +
        fmt("<w:highlight w:val=\"yellow\"/>", "highlighted") +
        plain(" and ") +
        fmt("<w:u w:val=\"single\"/>", "underlined") +
        plain(" text.") +
        "</w:p>" +
        "<w:p>" +
        fmt("<w:strike/>", "struck") +
        plain(" and ") +
        fmt("<w:u w:val=\"wave\"/>", "wavy") +
        plain(" words") +
        "</w:p>" +
        "<w:p>" +
        fmt("<w:u w:val=\"double\"/>", "double-underlined") +
        "</w:p>" +
        "<w:p>" +
        fmt("<w:highlight w:val=\"yellow\"/>", "foo") +
        fmt("<w:highlight w:val=\"yellow\"/>", "bar") +
        "</w:p>"
    )
    File(dir, "native_formatting.document.xml").writeText(nativeDoc)
    val nativeMap  = PlainTextMapper.build(nativeDoc)
    val nativeAnns = NativeImport.importNativeFormatting(nativeDoc, nativeMap, IMPORT_BASE_MICROS, IMPORT_NOW)
    File(dir, "native_formatting.expected.json").writeText(
        prettyEncode(nativeAnns.map { it.toMap() })
    )

    // Comments fixture (legacy Léamh + native Word).
    val commentsDoc = doc(
        "<w:p>" +
        plain("Intro text. ") +
        "<w:commentRangeStart w:id=\"1\"/>" +
        plain("annotated span") +
        "<w:commentRangeEnd w:id=\"1\"/>" +
        plain(" after.") +
        "</w:p>"
    )
    val commentsXml =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<w:comments $wns>" +
        "<w:comment w:id=\"0\" w:author=\"uuid-abc-123\" w:date=\"2026-06-08T10:00:00.000Z\">" +
        "<w:p>${plain("[tool:underline] [tag:pacing] 25% — \"the quote\"")}</w:p>" +
        "<w:p>${plain("a note line")}</w:p>" +
        "</w:comment>" +
        "<w:comment w:id=\"1\" w:author=\"Jane Reviewer\" w:date=\"2026-06-08T11:00:00.000Z\">" +
        "<w:p>${plain("Nice point here")}</w:p>" +
        "</w:comment>" +
        "</w:comments>"
    File(dir, "comments.document.xml").writeText(commentsDoc)
    File(dir, "comments.comments.xml").writeText(commentsXml)
    val commentsMap  = PlainTextMapper.build(commentsDoc)
    val commentAnns  = LegacyComments.parseComments(commentsXml, commentsDoc, commentsMap)
    File(dir, "comments.expected.json").writeText(
        prettyEncode(commentAnns.map { it.toMap() })
    )

    println("  Import goldens written")
}

// ─────────────────────────────────────────────────────────────────────────────
// 3. Engine goldens  (replaces gen_engine_goldens.dart)
// ─────────────────────────────────────────────────────────────────────────────

private val LOCATE_CASES = listOf(
    mapOf("name" to "context_match",           "plain" to "The quick brown fox jumps",  "selectedText" to "brown",    "prefix" to "quick ", "suffix" to " fox",  "positionHint" to 0.0),
    mapOf("name" to "position_hint_picks_last", "plain" to "cat dog cat dog cat",        "selectedText" to "cat",      "prefix" to "",       "suffix" to "",      "positionHint" to 0.9),
    mapOf("name" to "context_beats_hint",       "plain" to "foo bar X foo bar",          "selectedText" to "bar",      "prefix" to "foo ",   "suffix" to "",      "positionHint" to 1.0),
    mapOf("name" to "quote_normalised_fallback","plain" to "say “hello” now",   "selectedText" to "\"hello\"","prefix" to "say ",   "suffix" to " now",  "positionHint" to 0.0),
    mapOf("name" to "not_found",               "plain" to "abc def",                    "selectedText" to "xyz",      "prefix" to "",       "suffix" to "",      "positionHint" to 0.0),
    mapOf("name" to "emoji_selected",          "plain" to "react with 😀 here","selectedText" to "😀","prefix" to "with ","suffix" to " here","positionHint" to 0.0),
)

private val WORDSNAP_CASES = listOf(
    mapOf("name" to "mid_word",         "text" to "the quick brown fox", "start" to 5,  "end" to 7),
    mapOf("name" to "already_boundary", "text" to "a b c",               "start" to 0,  "end" to 1),
    mapOf("name" to "punctuation",      "text" to "Hello, world!",        "start" to 8,  "end" to 10),
    mapOf("name" to "em_dash",          "text" to "word—word",       "start" to 6,  "end" to 7),
    mapOf("name" to "no_expand_full",   "text" to "single",              "start" to 0,  "end" to 6),
)

private fun genEngine(base: File) {
    val anchorDir = File(base, "anchoring").apply { mkdirs() }
    val modelDir  = File(base, "model").apply { mkdirs() }
    val docxDir   = File(base, "docx").apply { mkdirs() }

    // ---- anchoring: locate ----
    val locateOut = LOCATE_CASES.map { c ->
        val r = Anchoring.locateInPlain(
            c["plain"] as String, c["selectedText"] as String,
            c["prefix"] as String, c["suffix"] as String,
            c["positionHint"] as Double,
        )
        linkedMapOf<String, Any?>(
            "name"         to c["name"],
            "plain"        to c["plain"],
            "selectedText" to c["selectedText"],
            "prefix"       to c["prefix"],
            "suffix"       to c["suffix"],
            "positionHint" to c["positionHint"],
            "expected"     to r?.let { linkedMapOf("start" to it.start, "end" to it.end) },
        )
    }
    File(anchorDir, "locate.json").writeText(prettyEncode(locateOut))

    // ---- anchoring: wordsnap ----
    val snapOut = WORDSNAP_CASES.map { c ->
        val r = Anchoring.snapToWordBoundaries(
            c["text"] as String, c["start"] as Int, c["end"] as Int,
        )
        linkedMapOf<String, Any?>(
            "name"     to c["name"],
            "text"     to c["text"],
            "start"    to c["start"],
            "end"      to c["end"],
            "expected" to linkedMapOf("start" to r.start, "end" to r.end),
        )
    }
    File(anchorDir, "wordsnap.json").writeText(prettyEncode(snapOut))

    // ---- model: annotations ----
    val modelAnnotations = listOf(
        Annotation(
            id = "1000", selectedText = "Hello", prefix = "", suffix = " world",
            tool = AnnotationTool.highlight,
            timestamp = Instant.parse("2026-01-02T03:04:05.000006Z"),
            position = 0.0,
        ),
        Annotation(
            id = "1001", selectedText = "naïve café 😀",
            prefix = "a “smart” ", suffix = " end\nline",
            tool = AnnotationTool.comment,
            note = "a note with \"quotes\"\nand a newline",
            tag = AnnotationTag.query,
            timestamp = Instant.parse("2026-06-08T12:00:00Z"),
            position = 0.5,
        ),
        Annotation(
            id = "1002", selectedText = "underlined twice", prefix = "see ", suffix = ".",
            tool = AnnotationTool.doubleUnderline,
            timestamp = Instant.parse("2026-06-08T12:30:00.123Z"),
            position = 0.123456,
        ),
        Annotation(
            id = "1003", selectedText = "ink here", prefix = "", suffix = "",
            tool = AnnotationTool.inkAnnotation,
            tag = AnnotationTag.voice,
            timestamp = Instant.parse("2026-06-08T13:00:00Z"),
            position = 1.0, hasInk = true,
        ),
        Annotation(
            id = "1004", selectedText = "marker", prefix = "", suffix = "",
            tool = AnnotationTool.bookmark,
            timestamp = Instant.parse("2026-06-08T14:00:00Z"),
            position = 0.75,
        ),
    )
    File(modelDir, "annotations.json").writeText(prettyEncode(modelAnnotations.map { it.toMap() }))

    // ---- model: position ----
    val pos = ReadingPosition(mode = ReadingMode.pageFlip, page = 42, scrollOffset = 0.0, fraction = 0.333)
    File(modelDir, "position.json").writeText(prettyEncode(pos.toMap()))

    // ---- docx: sample.docx + expected.json ----
    val wns = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""
    val sampleContentTypes =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
        "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
        "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
        "</Types>"
    val sampleDocXml =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<w:document $wns><w:body>" +
        "<w:p><w:r><w:t>The quick brown fox jumps over the lazy dog.</w:t></w:r></w:p>" +
        "<w:p><w:r><w:t>Second paragraph here.</w:t></w:r></w:p>" +
        "</w:body></w:document>"
    val samplePlain = PlainTextMapper.build(sampleDocXml).plain

    fun pos2(s: String): Double = samplePlain.indexOf(s).toDouble() / samplePlain.length

    val docPos = ReadingPosition(mode = ReadingMode.pageFlip, page = 1, scrollOffset = 0.0, fraction = 0.5)
    val docAnnotations = listOf(
        Annotation(id = "annA", selectedText = "brown fox", prefix = "quick ", suffix = " jumps",
            tool = AnnotationTool.highlight, timestamp = Instant.parse("2026-06-08T09:00:00Z"), position = pos2("brown fox")),
        Annotation(id = "annB", selectedText = "Second", prefix = "", suffix = " paragraph",
            tool = AnnotationTool.underline, timestamp = Instant.parse("2026-06-08T09:01:00Z"), position = pos2("Second")),
        Annotation(id = "annC", selectedText = "nonexistent phrase", prefix = "", suffix = "",
            tool = AnnotationTool.comment, timestamp = Instant.parse("2026-06-08T09:02:00Z"), position = 0.5),
        // hasInk:false in JSON, but the PNG is present — load must flip to true.
        Annotation(id = "ink1", selectedText = "lazy dog", prefix = "the ", suffix = ".",
            tool = AnnotationTool.inkAnnotation, timestamp = Instant.parse("2026-06-08T09:03:00Z"), position = pos2("lazy dog")),
        // hasInk:true in JSON, but NO PNG — load must flip to false.
        Annotation(id = "flagonly", selectedText = "quick", prefix = "The ", suffix = " brown",
            tool = AnnotationTool.highlight, timestamp = Instant.parse("2026-06-08T09:04:00Z"), position = pos2("quick"), hasInk = true),
    )

    val sampleEntries = LinkedHashMap<String, ByteArray>()
    sampleEntries["[Content_Types].xml"]      = sampleContentTypes.toByteArray(Charsets.UTF_8)
    sampleEntries["word/document.xml"]        = sampleDocXml.toByteArray(Charsets.UTF_8)
    sampleEntries["leamh/annotations.json"]   = compactEncode(docAnnotations.map { it.toMap() }).toByteArray(Charsets.UTF_8)
    sampleEntries["leamh/position.json"]      = compactEncode(docPos.toMap()).toByteArray(Charsets.UTF_8)
    // 12-byte PNG header — marks ink1 as having a real PNG so load() flips hasInk to true.
    sampleEntries["word/media/ink_ink1.png"]  = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E.toByte(), 0x47.toByte(), 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0,
    )
    File(docxDir, "sample.docx").writeBytes(DocxArchive.write(sampleEntries))

    val expectedMap = linkedMapOf<String, Any?>(
        "plainText"   to samplePlain,
        "position"    to docPos.toMap(),
        "annotations" to docAnnotations.map { a ->
            val span = Anchoring.locateInPlain(samplePlain, a.selectedText, a.prefix, a.suffix, a.position)
            linkedMapOf<String, Any?>(
                "id"     to a.id,
                "hasInk" to (a.id == "ink1"),   // only ink1 has a PNG; flagonly has no PNG
                "span"   to span?.let { linkedMapOf("start" to it.start, "end" to it.end) },
            )
        },
    )
    File(docxDir, "expected.json").writeText(prettyEncode(expectedMap))

    println("  Engine goldens written")
}

// ─────────────────────────────────────────────────────────────────────────────
// 4. Writeback goldens  (replaces writeback_golden_test.dart)
// ─────────────────────────────────────────────────────────────────────────────

private fun genWriteback(base: File) {
    val dir = File(base, "writeback").apply { mkdirs() }

    val wns = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""
    val contentTypesXml =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
        "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
        "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
        "</Types>"
    val relsDotRels =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
        "</Relationships>"
    val documentXml =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<w:document $wns><w:body>" +
        "<w:p><w:r><w:t>The quick brown fox jumps over the lazy dog.</w:t></w:r></w:p>" +
        "<w:p><w:r><w:t>Second paragraph here.</w:t></w:r></w:p>" +
        "</w:body></w:document>"
    val docRels =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "</Relationships>"

    val inputEntries = LinkedHashMap<String, ByteArray>()
    inputEntries["[Content_Types].xml"]          = contentTypesXml.toByteArray(Charsets.UTF_8)
    inputEntries["_rels/.rels"]                  = relsDotRels.toByteArray(Charsets.UTF_8)
    inputEntries["word/document.xml"]            = documentXml.toByteArray(Charsets.UTF_8)
    inputEntries["word/_rels/document.xml.rels"] = docRels.toByteArray(Charsets.UTF_8)
    // Ink PNG for ink4 — 8-byte PNG signature so DocxStore.load detects hasInk=true.
    inputEntries["word/media/ink_ink4.png"] = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E.toByte(), 0x47.toByte(), 0x0D, 0x0A, 0x1A, 0x0A,
    )
    val inputBytes = DocxArchive.write(inputEntries)
    File(dir, "input.docx").writeBytes(inputBytes)

    val plain = "The quick brown fox jumps over the lazy dog.\nSecond paragraph here.\n"
    fun pos(s: String): Double = plain.indexOf(s).toDouble() / plain.length

    val annotations = listOf(
        Annotation(
            id = "h1", selectedText = "quick brown", prefix = "The ", suffix = " fox",
            tool = AnnotationTool.highlight,
            timestamp = Instant.parse("2026-06-08T10:00:00Z"), position = pos("quick brown"),
        ),
        Annotation(
            id = "u2", selectedText = "lazy dog", prefix = "the ", suffix = ".",
            tool = AnnotationTool.underline, note = "a note", tag = AnnotationTag.query,
            timestamp = Instant.parse("2026-06-08T10:01:00Z"), position = pos("lazy dog"),
        ),
        Annotation(
            id = "b3", selectedText = "Second", prefix = "", suffix = " paragraph",
            tool = AnnotationTool.bookmark,
            timestamp = Instant.parse("2026-06-08T10:02:00Z"), position = pos("Second"),
        ),
        Annotation(
            id = "ink4", selectedText = "paragraph", prefix = "Second ", suffix = " here",
            tool = AnnotationTool.inkAnnotation, note = "ink note", hasInk = true,
            timestamp = Instant.parse("2026-06-08T10:03:00Z"), position = pos("paragraph"),
        ),
    )

    // annotations.json is the INPUT to DocxStore.write (compact JSON for the test reader).
    File(dir, "annotations.json").writeText(compactEncode(annotations.map { it.toMap() }))

    val outputBytes = DocxStore.write(inputBytes, annotations)
    val output      = DocxArchive.read(outputBytes)

    val mapping = mapOf(
        "word/document.xml"                to "document.xml",
        "word/comments.xml"                to "comments.xml",
        "[Content_Types].xml"              to "content_types.xml",
        "word/_rels/document.xml.rels"     to "document.xml.rels",
        "word/_rels/comments.xml.rels"     to "comments.xml.rels",
        "leamh/document_clean.xml"         to "document_clean.xml",
    )
    for ((entryName, goldenName) in mapping) {
        output.text(entryName)?.let { File(dir, goldenName).writeText(it) }
    }

    println("  Writeback goldens written")
}

// ─────────────────────────────────────────────────────────────────────────────
// JSON helpers
// ─────────────────────────────────────────────────────────────────────────────

// Compact integer array — e.g. "[0,1,2]"
private fun compactInts(ints: List<Int>): String = "[${ints.joinToString(",")}]"

// Compact JSON encoder — mirrors JsonWriter.encode() (which is internal to main).
// Dart-compatible: escapes " \ and control chars; leaves / and non-ASCII raw.
private fun compactEncode(value: Any?): String = buildString { appendCompact(this, value) }

private fun appendCompact(sb: StringBuilder, v: Any?) {
    when (v) {
        null       -> sb.append("null")
        is Boolean -> sb.append(v.toString())
        is Int     -> sb.append(v.toString())
        is Long    -> sb.append(v.toString())
        is Double  -> sb.append(
            if (!v.isInfinite() && !v.isNaN() && v == v.toLong().toDouble()) "${v.toLong()}.0" else v.toString()
        )
        is String  -> appendJsonStr(sb, v)
        is Map<*, *> -> {
            sb.append('{')
            var first = true
            for ((k, vv) in v) {
                if (!first) sb.append(',')
                first = false
                appendJsonStr(sb, k.toString())
                sb.append(':')
                appendCompact(sb, vv)
            }
            sb.append('}')
        }
        is List<*> -> {
            sb.append('[')
            var first = true
            for (e in v) {
                if (!first) sb.append(',')
                first = false
                appendCompact(sb, e)
            }
            sb.append(']')
        }
        else -> appendJsonStr(sb, v.toString())
    }
}

// Indented JSON encoder — matches Dart's JsonEncoder.withIndent('  ') output.
private fun prettyEncode(value: Any?, level: Int = 0): String {
    val pad  = "  ".repeat(level)
    val ipad = "  ".repeat(level + 1)
    return when (value) {
        null      -> "null"
        is Boolean -> value.toString()
        is Int    -> value.toString()
        is Long   -> value.toString()
        is Double -> if (!value.isInfinite() && !value.isNaN() && value == value.toLong().toDouble())
                         "${value.toLong()}.0" else value.toString()
        is String -> buildString { appendJsonStr(this, value) }
        is List<*> -> if (value.isEmpty()) "[]" else {
            val items = value.joinToString(",\n$ipad") { prettyEncode(it, level + 1) }
            "[\n$ipad$items\n$pad]"
        }
        is Map<*, *> -> if (value.isEmpty()) "{}" else {
            val items = value.entries.joinToString(",\n$ipad") { (k, v) ->
                "${buildString { appendJsonStr(this, k.toString()) }}: ${prettyEncode(v, level + 1)}"
            }
            "{\n$ipad$items\n$pad}"
        }
        else -> buildString { appendJsonStr(this, value.toString()) }
    }
}

// String escaping — matches Dart's jsonEncode: / not escaped, non-ASCII left raw.
private fun appendJsonStr(sb: StringBuilder, s: String) {
    sb.append('"')
    for (c in s) {
        when (c) {
            '"'      -> sb.append("\\\"")
            '\\'     -> sb.append("\\\\")
            '\n'     -> sb.append("\\n")
            '\r'     -> sb.append("\\r")
            '\t'     -> sb.append("\\t")
            '\b'     -> sb.append("\\b")
            '' -> sb.append("\\f")
            else     -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
    }
    sb.append('"')
}

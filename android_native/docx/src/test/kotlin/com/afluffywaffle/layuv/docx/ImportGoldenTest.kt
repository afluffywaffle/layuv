package com.afluffywaffle.layuv.docx

import com.afluffywaffle.layuv.docx.model.Annotation
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Golden test for the read fallback importers. Goldens come from VERBATIM copies
 * of the Dart `_importNativeFormatting` / `_parseComments` (gen_import_goldens.dart)
 * with a fixed clock; this asserts the Kotlin ports produce identical
 * annotations — including tool detection, adjacent-run merging, the legacy
 * `[tool:..] N% — "text"` format, and native Word `<w:commentRange>` resolution.
 */
class ImportGoldenTest {

    // Same fixed clock the generator used (DateTime.utc(2026,1,1)).
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val baseMicros = now.epochSecond * 1_000_000 + now.nano / 1000

    @Test
    fun nativeFormattingImport() {
        val doc = resource("/golden/import/native_formatting.document.xml")
        val map = PlainTextMapper.build(doc)
        val actual = NativeImport.importNativeFormatting(doc, map, baseMicros, now)
        assertEquals(expected("/golden/import/native_formatting.expected.json"), actual)
    }

    @Test
    fun commentsImport() {
        val doc = resource("/golden/import/comments.document.xml")
        val commentsXml = resource("/golden/import/comments.comments.xml")
        val map = PlainTextMapper.build(doc)
        val actual = LegacyComments.parseComments(commentsXml, doc, map)
        assertEquals(expected("/golden/import/comments.expected.json"), actual)
    }

    private fun expected(path: String): List<Annotation> {
        val arr = JSONArray(resource(path))
        return (0 until arr.length()).map { Annotation.fromMap(arr.getJSONObject(it).toMapAny()) }
    }

    private fun resource(path: String): String =
        javaClass.getResource(path)?.readText(Charsets.UTF_8)
            ?: error("missing resource: $path (run gen_import_goldens.dart)")

    private fun JSONObject.toMapAny(): Map<String, Any?> = buildMap {
        // this@toMapAny: inside buildMap, an unqualified get(k) would bind to
        // MutableMap.get (null), not JSONObject.get.
        for (k in this@toMapAny.keys()) {
            val v = this@toMapAny.get(k)
            put(k, if (v == JSONObject.NULL) null else v)
        }
    }
}

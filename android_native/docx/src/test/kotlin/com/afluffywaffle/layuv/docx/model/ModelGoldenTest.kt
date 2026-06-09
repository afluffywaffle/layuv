package com.afluffywaffle.layuv.docx.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The model JSON layer must round-trip with the REAL Dart Annotation /
 * ReadingPosition (golden produced by their actual toJson). Verifies fromMap
 * parses Dart output, the camelCase enum-name parity, nullable note/tag,
 * special characters (emoji/quotes/newlines), and self round-trip.
 */
class ModelGoldenTest {

    @Test
    fun annotationsParseAndRoundTrip() {
        val arr = JSONArray(resource("/golden/model/annotations.json"))
        val list = (0 until arr.length()).map { Annotation.fromMap(arr.getJSONObject(it).toMapAny()) }
        assertEquals(5, list.size)

        // fromMap(toMap(x)) == x for every annotation
        list.forEach { a -> assertEquals(a, Annotation.fromMap(a.toMap()), "round-trip failed for ${a.id}") }

        val byId = list.associateBy { it.id }

        byId.getValue("1000").let {
            assertEquals(AnnotationTool.highlight, it.tool)
            assertEquals("Hello", it.selectedText)
            assertEquals(" world", it.suffix)
            assertEquals(0.0, it.position)
            assertFalse(it.hasInk)
            assertNull(it.note)
            assertNull(it.tag)
            // UTC microsecond precision survives the round-trip
            assertEquals(Instant.parse("2026-01-02T03:04:05.000006Z"), it.timestamp)
        }
        byId.getValue("1001").let {
            assertEquals(AnnotationTool.comment, it.tool)
            assertEquals(AnnotationTag.query, it.tag)
            assertEquals("a note with \"quotes\"\nand a newline", it.note)
            assertTrue(it.selectedText.contains("😀"), "emoji preserved")
            assertEquals(0.5, it.position)
        }
        byId.getValue("1002").let {
            assertEquals(AnnotationTool.doubleUnderline, it.tool) // camelCase enum-name parity
            assertEquals("doubleUnderline", it.toMap()["tool"])
            assertEquals(0.123456, it.position)
        }
        byId.getValue("1003").let {
            assertEquals(AnnotationTool.inkAnnotation, it.tool)
            assertTrue(it.hasInk)
            assertEquals(AnnotationTag.voice, it.tag)
            assertEquals(1.0, it.position)
        }
        byId.getValue("1004").let {
            assertEquals(AnnotationTool.bookmark, it.tool)
            assertEquals(0.75, it.position)
        }
    }

    @Test
    fun positionParsesAndRoundTrips() {
        val o = JSONObject(resource("/golden/model/position.json"))
        val pos = ReadingPosition.fromMap(o.toMapAny())
        assertEquals(ReadingMode.pageFlip, pos.mode)
        assertEquals(42, pos.page)
        assertEquals(0.0, pos.scrollOffset)
        assertEquals(0.333, pos.fraction)
        assertEquals(pos, ReadingPosition.fromMap(pos.toMap()))
    }

    private fun resource(path: String): String =
        javaClass.getResource(path)?.readText(Charsets.UTF_8)
            ?: error("missing test resource: $path (run gen_engine_goldens.dart)")

    private fun JSONObject.toMapAny(): Map<String, Any?> = buildMap {
        // this@toMapAny: inside buildMap, an unqualified get(k) would bind to
        // MutableMap.get (null), not JSONObject.get.
        for (k in this@toMapAny.keys()) {
            val v = this@toMapAny.get(k)
            put(k, if (v == JSONObject.NULL) null else v)
        }
    }
}

package com.afluffywaffle.layuv.docx

import com.afluffywaffle.layuv.docx.model.ReadingMode
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * End-to-end read-path test over a real .docx built by gen_engine_goldens.dart
 * (real Dart models + zip encoder). Confirms ZIP read, P extraction,
 * annotation parse + re-anchor, PNG-overrides-hasInk, and position parse.
 */
class DocxStoreLoadTest {

    private val doc = DocxStore.load(resourceBytes("/golden/docx/sample.docx"))
    private val expected = JSONObject(resourceText("/golden/docx/expected.json"))

    @Test
    fun plainTextMatches() {
        assertEquals(expected.getString("plainText"), doc.plainText)
    }

    @Test
    fun positionParsed() {
        val p = doc.position ?: error("position not loaded")
        val e = expected.getJSONObject("position")
        assertEquals(ReadingMode.byName(e.getString("mode")), p.mode)
        assertEquals(e.getInt("page"), p.page)
        assertEquals(e.getDouble("scrollOffset"), p.scrollOffset)
        assertEquals(e.getDouble("fraction"), p.fraction)
    }

    @Test
    fun annotationsResolvedAndInkFromPng() {
        val eArr = expected.getJSONArray("annotations")
        val expectedById = (0 until eArr.length()).associate {
            val o = eArr.getJSONObject(it)
            o.getString("id") to o
        }
        assertEquals(expectedById.size, doc.annotations.size)

        for (ra in doc.annotations) {
            val e = expectedById.getValue(ra.annotation.id)
            assertEquals(e.getBoolean("hasInk"), ra.annotation.hasInk, "hasInk for ${ra.annotation.id}")
            if (e.isNull("span")) {
                assertNull(ra.span, "expected unlocatable span for ${ra.annotation.id}")
            } else {
                val s = e.getJSONObject("span")
                assertEquals(
                    TextSpan(s.getInt("start"), s.getInt("end")),
                    ra.span,
                    "span for ${ra.annotation.id}",
                )
            }
        }
    }

    private fun resourceBytes(path: String): ByteArray =
        javaClass.getResourceAsStream(path)?.readBytes()
            ?: error("missing resource: $path (run gen_engine_goldens.dart)")

    private fun resourceText(path: String): String =
        javaClass.getResource(path)?.readText(Charsets.UTF_8)
            ?: error("missing resource: $path")
}

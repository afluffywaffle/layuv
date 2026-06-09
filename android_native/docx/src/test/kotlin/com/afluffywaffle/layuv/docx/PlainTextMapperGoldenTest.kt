package com.afluffywaffle.layuv.docx

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Cross-language golden test. Goldens are generated from the Dart reference
 * (android_native/tools/golden_gen/gen_goldens.dart) so this asserts the Kotlin
 * [PlainTextMapper] produces byte-identical plain text AND xmlOffsets to the
 * Dart `buildCleanMap`, including surrogate-pair (emoji) and entity handling.
 */
class PlainTextMapperGoldenTest {

    private val fixtures = listOf(
        // prose — clean MUST equal legacy _buildPlainMap byte-for-byte
        "simple", "preserve", "multi_wt_run", "empty_para", "self_closing_run", "unicode",
        // buggy-in-legacy — clean is the corrected output
        "entities", "tabs_breaks", "table",
    )

    private val prose = setOf(
        "simple", "preserve", "multi_wt_run", "empty_para", "self_closing_run", "unicode",
    )

    @TestFactory
    fun matchesCleanGoldens(): List<DynamicTest> = fixtures.map { name ->
        DynamicTest.dynamicTest("clean:$name") {
            val xml = resource("/golden/fixtures/$name.document.xml")
            val map = PlainTextMapper.build(xml)
            assertEquals(
                resource("/golden/clean/$name.plain.txt"),
                map.plain,
                "plain text mismatch for $name",
            )
            assertEquals(
                parseInts(resource("/golden/clean/$name.offsets.json")),
                map.xmlOffsets.toList(),
                "xmlOffsets mismatch for $name",
            )
            assertEquals(
                map.plain.length,
                map.xmlOffsets.size,
                "offsets must be parallel to plain for $name",
            )
        }
    }

    /**
     * The cross-app anchoring guarantee: on ordinary prose the clean extraction
     * is byte-identical to docx_store._buildPlainMap, so annotations created in
     * the native app re-locate exactly in Flutter and Word/Pages/GDocs round
     * trips hold. Guards against a future regeneration breaking that invariant.
     */
    @TestFactory
    fun cleanEqualsLegacyOnProse(): List<DynamicTest> = prose.map { name ->
        DynamicTest.dynamicTest("clean==legacy:$name") {
            assertEquals(
                resource("/golden/legacy/$name.plain.txt"),
                resource("/golden/clean/$name.plain.txt"),
                "clean and legacy goldens diverge on prose fixture $name",
            )
            assertEquals(
                resource("/golden/legacy/$name.offsets.json"),
                resource("/golden/clean/$name.offsets.json"),
                "clean and legacy offsets diverge on prose fixture $name",
            )
        }
    }

    private fun resource(path: String): String =
        javaClass.getResource(path)?.readText(Charsets.UTF_8)
            ?: error("missing test resource: $path (run gen_goldens.dart)")

    private fun parseInts(json: String): List<Int> =
        json.trim().removeSurrounding("[", "]").let {
            if (it.isBlank()) emptyList() else it.split(",").map(String::trim).map(String::toInt)
        }
}

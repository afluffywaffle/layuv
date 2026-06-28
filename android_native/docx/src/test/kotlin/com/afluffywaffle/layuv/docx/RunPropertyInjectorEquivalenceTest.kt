package com.afluffywaffle.layuv.docx

import com.afluffywaffle.layuv.docx.model.Annotation
import com.afluffywaffle.layuv.docx.model.AnnotationTool
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Random

/**
 * Proves the optimized [RunPropertyInjector] is BYTE-IDENTICAL to the frozen
 * [LegacyRunPropertyInjector] across a large randomized battery: many annotations,
 * spans mid-run (forcing splits), overlapping/same-run spans, spans starting/ending
 * on a `<w:tab/>`, every tool, plus bookmark + comment anchors. The legacy oracle is
 * the specification; divergence = the optimization changed behaviour.
 */
class RunPropertyInjectorEquivalenceTest {

    private val words = listOf(
        "the", "manuscript", "lay", "scattered", "across", "stone", "table", "each", "page",
        "a", "testament", "to", "forgotten", "art", "vellum", "crackled", "softly", "as", "she",
        "turned", "rain", "fell", "steadily", "outside", "archive", "breath", "fogging", "cold",
        "room", "provenance", "chain", "broke", "in", "retreating", "army", "binding", "was",
        "Coptic", "script", "Carolingian", "illuminations", "suggested", "neither", "could",
    )

    /** Mixed runs (plain, bold, italic, trailing-tab, no-xml:space) so spans land mid-run and on tabs. */
    private fun syntheticDoc(rng: Random, paragraphs: Int): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        sb.append("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>")
        repeat(paragraphs) {
            sb.append("<w:p>")
            repeat(2 + rng.nextInt(5)) {
                val n = 1 + rng.nextInt(4)
                val text = (0 until n).joinToString(" ") { words[rng.nextInt(words.size)] } + " "
                val esc = XmlEntities.escape(text)
                when (rng.nextInt(7)) {
                    0 -> sb.append("<w:r><w:rPr><w:b/></w:rPr><w:t xml:space=\"preserve\">$esc</w:t></w:r>")
                    1 -> sb.append("<w:r><w:rPr><w:i/></w:rPr><w:t xml:space=\"preserve\">$esc</w:t></w:r>")
                    2 -> sb.append("<w:r><w:t xml:space=\"preserve\">$esc</w:t><w:tab/></w:r>")
                    3 -> sb.append("<w:r><w:t>$esc</w:t></w:r>") // no xml:space (split rewrites the tag)
                    else -> sb.append("<w:r><w:t xml:space=\"preserve\">$esc</w:t></w:r>")
                }
            }
            sb.append("</w:p>")
        }
        sb.append("</w:body></w:document>")
        return sb.toString()
    }

    private fun ann(id: String, plain: String, start: Int, end: Int, tool: AnnotationTool): Annotation {
        val pfxStart = (start - 18).coerceAtLeast(0)
        val sfxEnd = (end + 18).coerceAtMost(plain.length)
        return Annotation(
            id = id,
            selectedText = plain.substring(start, end),
            prefix = plain.substring(pfxStart, start),
            suffix = plain.substring(end, sfxEnd),
            tool = tool, note = null, tag = null, timestamp = Instant.EPOCH,
            position = start.toDouble() / plain.length.coerceAtLeast(1), hasInk = false,
        )
    }

    private val tools = listOf(
        AnnotationTool.highlight, AnnotationTool.underline, AnnotationTool.doubleUnderline,
        AnnotationTool.strikethrough, AnnotationTool.bookmark, AnnotationTool.comment,
    )

    @Test
    fun optimizedMatchesLegacyAcrossRandomBattery() {
        RunPropertyInjector.SELF_CHECK = true // re-derive structures after every mutation
        var cases = 0
        for (seed in 0 until 300) {
            val rng = Random(seed.toLong())
            val xml = syntheticDoc(rng, paragraphs = 4 + rng.nextInt(30))
            val plain = PlainTextMapper.build(xml).plain
            if (plain.length < 60) continue

            val count = 1 + rng.nextInt(45)
            val anns = ArrayList<Annotation>(count)
            repeat(count) { i ->
                val s = rng.nextInt(plain.length - 12)
                val e = (s + 1 + rng.nextInt(16)).coerceAtMost(plain.length)
                if (e <= s) return@repeat
                anns.add(ann("a$i", plain, s, e, tools[rng.nextInt(tools.size)]))
            }
            val notes = anns.filter { it.tool == AnnotationTool.comment }
            assertEquals(
                LegacyRunPropertyInjector.inject(xml, anns, notes),
                RunPropertyInjector.inject(xml, anns, notes),
                "seed=$seed count=$count: optimized diverged from legacy",
            )
            cases++
        }
        println("EQUIV: ${cases} randomized cases byte-identical")
    }
}

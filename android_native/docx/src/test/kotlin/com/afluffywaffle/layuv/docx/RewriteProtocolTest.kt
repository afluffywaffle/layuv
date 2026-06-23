package com.afluffywaffle.layuv.docx

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RewriteProtocolTest {

    @Test
    fun plainConversationHasNoRewrite() {
        val p = RewriteProtocol.parse("Which ending did you prefer — the quiet one?")
        assertEquals("Which ending did you prefer — the quiet one?", p.conversation)
        assertNull(p.rewrite)
    }

    @Test
    fun extractsRewriteBlockAndPreamble() {
        val text = "Here's a tighter take.\n\n" +
            "${RewriteProtocol.BEGIN}\nThe lighthouse stood.\n\nMara climbed.\n${RewriteProtocol.END}"
        val p = RewriteProtocol.parse(text)
        assertEquals("Here's a tighter take.", p.conversation)
        assertEquals("The lighthouse stood.\n\nMara climbed.", p.rewrite)
    }

    @Test
    fun unterminatedBlockKeepsPartialRewrite() {
        // Truncated mid-rewrite (no END marker): everything after BEGIN is the partial.
        val text = "${RewriteProtocol.BEGIN}\nThe lighthouse stood and the"
        val p = RewriteProtocol.parse(text)
        assertEquals("", p.conversation)
        assertEquals("The lighthouse stood and the", p.rewrite)
    }
}

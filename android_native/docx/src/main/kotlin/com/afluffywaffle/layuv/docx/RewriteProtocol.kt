package com.afluffywaffle.layuv.docx

/**
 * Wire protocol for the in-app "Ask AI" conversation. The chat is a back-and-forth:
 * the model may discuss or ask clarifying questions in plain text, and when it
 * delivers a full chapter rewrite it wraps ONLY the manuscript prose between the
 * [BEGIN] and [END] marker lines. The app renders discussion inline but turns a
 * rewrite into a "save as a new draft" action — it never dumps the chapter into the
 * chat. So this is the single source of truth for BOTH the prompt instructions
 * ([ManuscriptSerializer]) and the reply parser (the reader's Ask AI panel).
 */
object RewriteProtocol {
    const val BEGIN = "===REWRITE==="
    const val END = "===END REWRITE==="

    /** [conversation] = discussion text (may be empty); [rewrite] = chapter prose, or null if none. */
    data class Parsed(val conversation: String, val rewrite: String?)

    /**
     * Split a reply into its discussion text and (if present) the rewritten chapter.
     * A reply with no [BEGIN] marker is pure conversation. An unterminated block
     * (truncated mid-rewrite) treats everything after [BEGIN] as the partial rewrite.
     */
    fun parse(text: String): Parsed {
        val begin = text.indexOf(BEGIN)
        if (begin < 0) return Parsed(text.trim(), null)
        val conversation = text.substring(0, begin).trim()
        val afterBegin = begin + BEGIN.length
        val end = text.indexOf(END, afterBegin)
        val rewrite = (if (end < 0) text.substring(afterBegin) else text.substring(afterBegin, end)).trim()
        return Parsed(conversation, rewrite.ifEmpty { null })
    }
}

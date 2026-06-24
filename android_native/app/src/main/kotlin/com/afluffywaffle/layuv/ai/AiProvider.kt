package com.afluffywaffle.layuv.ai

/**
 * A chat-style AI backend. [send] is BLOCKING (one network round trip) — call it
 * off the main thread. The sole implementation is [OpenAiCompatibleProvider];
 * Layuv talks the OpenAI-compatible wire format to every endpoint (cloud or local).
 */
interface AiProvider {
    fun send(apiKey: String, messages: List<AiMessage>): AiResult
}

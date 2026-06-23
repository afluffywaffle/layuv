package com.afluffywaffle.layuv.ai

/**
 * A chat-style AI backend. [send] is BLOCKING (one network round trip) — call it
 * off the main thread. v1 has a single implementation, [ClaudeProvider]; the
 * interface keeps a future OpenAI/Gemini provider a drop-in.
 */
interface AiProvider {
    fun send(apiKey: String, messages: List<AiMessage>): AiResult
}

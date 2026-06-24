package com.afluffywaffle.layuv.ai

import java.net.URL

/**
 * Builds the [AiProvider] from the user's configured endpoint.
 *
 * Layuv is **AI-platform agnostic**: it speaks the OpenAI-compatible
 * chat-completions wire format to ANY endpoint the user points it at — there is
 * no built-in provider list. That single client reaches:
 *   - a cloud provider's OpenAI-compatible URL — Claude (`https://api.anthropic.com/v1`),
 *     Gemini (`https://generativelanguage.googleapis.com/v1beta/openai`),
 *     OpenAI (`https://api.openai.com/v1`);
 *   - the user's own server — Ollama / LM Studio / llama.cpp / vLLM, or a Mac
 *     "brain" exposing `/chat/completions` (the planned reference proxy).
 *
 * Connection config lives in the plain `"leamh"` prefs (`ai_base_url`, `ai_model`);
 * the API key — optional, since a local server may need none — lives in
 * [SecureKeyStore]. The cleartext boundary for `http://` endpoints is [CleartextPolicy].
 */
object AiProviderFactory {

    private fun prefs(context: android.content.Context) =
        context.getSharedPreferences("leamh", android.content.Context.MODE_PRIVATE)

    /** OpenAI-compatible base URL, e.g. `https://api.anthropic.com/v1` or `http://192.168.x.x:11434/v1`. */
    fun baseUrl(context: android.content.Context): String =
        prefs(context).getString("ai_base_url", "")?.trim().orEmpty()

    /** Model name the endpoint expects, e.g. `claude-sonnet-4-6`, `gemini-2.5-flash`, `gpt-4o-mini`, `llama3.1`. */
    fun model(context: android.content.Context): String =
        prefs(context).getString("ai_model", "")?.trim().orEmpty()

    /** True once an endpoint is configured (a base URL is set) — the gate for showing the AI UI. */
    fun isConfigured(context: android.content.Context): Boolean = baseUrl(context).isNotBlank()

    fun current(context: android.content.Context): AiProvider =
        OpenAiCompatibleProvider(baseUrl(context), model(context), requireKey = false)

    /** A friendly name for the active endpoint, derived from its host, for chat labels. */
    fun displayName(context: android.content.Context): String {
        val host = try { URL(baseUrl(context)).host.lowercase() } catch (e: Exception) { "" }
        return when {
            host.isBlank() -> "the AI"
            host.contains("anthropic") -> "Claude"
            host.contains("googleapis") -> "Gemini"
            host.contains("openai") -> "OpenAI"
            else -> "your server"
        }
    }
}

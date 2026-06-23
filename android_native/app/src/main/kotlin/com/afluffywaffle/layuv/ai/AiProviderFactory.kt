package com.afluffywaffle.layuv.ai

import android.content.Context

/**
 * Builds the [AiProvider] for the user's current selection, stored in the plain
 * `"leamh"` prefs (`ai_provider`, `ai_model`). The API key itself lives in
 * [SecureKeyStore]. v1 ships Claude (native Anthropic API) + Gemini (free tier,
 * via Gemini's OpenAI-compatible endpoint); a fully custom OpenAI-compatible /
 * local endpoint is a later addition that reuses [OpenAiCompatibleProvider].
 */
object AiProviderFactory {
    const val PROVIDER_CLAUDE = "claude"
    const val PROVIDER_GEMINI = "gemini"

    // Gemini's OpenAI-compatibility endpoint — same wire format as OpenAI/local.
    const val GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta/openai"
    const val GEMINI_DEFAULT_MODEL = "gemini-2.5-flash"

    private fun prefs(context: Context) = context.getSharedPreferences("leamh", Context.MODE_PRIVATE)

    fun selected(context: Context): String =
        prefs(context).getString("ai_provider", PROVIDER_CLAUDE) ?: PROVIDER_CLAUDE

    fun geminiModel(context: Context): String =
        prefs(context).getString("ai_model", GEMINI_DEFAULT_MODEL)?.takeIf { it.isNotBlank() }
            ?: GEMINI_DEFAULT_MODEL

    fun current(context: Context): AiProvider = when (selected(context)) {
        PROVIDER_GEMINI -> OpenAiCompatibleProvider(GEMINI_BASE, geminiModel(context))
        else -> ClaudeProvider()
    }
}

package com.afluffywaffle.layuv.ai

/** Outcome of an [AiProvider.send]. [Error.userMessage] is already human-readable. */
sealed class AiResult {
    /** [truncated] is true when the model stopped at `max_tokens` (offer "Continue"). */
    data class Ok(val text: String, val truncated: Boolean) : AiResult()
    data class Error(val userMessage: String) : AiResult()
}

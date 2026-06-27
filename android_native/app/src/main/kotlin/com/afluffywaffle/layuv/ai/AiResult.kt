package com.afluffywaffle.layuv.ai

/** Outcome of an [AiProvider.send]. [Error.userMessage] is already human-readable. */
sealed class AiResult {
    /** [truncated] is true when the model stopped at `max_tokens` (offer "Continue"). */
    data class Ok(val text: String, val truncated: Boolean) : AiResult()
    data class Error(val userMessage: String) : AiResult()

    /**
     * The request carried handwritten-note images but the endpoint rejected it with a
     * client error — almost always a text-only model that can't accept `image_url`
     * parts. The caller should re-send once WITHOUT the images (with a placeholder so
     * the rest of the rewrite still works) and tell the user the ink couldn't be read.
     */
    data class NeedsTextOnlyRetry(val userMessage: String) : AiResult()
}

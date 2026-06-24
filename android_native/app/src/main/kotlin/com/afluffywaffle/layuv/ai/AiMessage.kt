package com.afluffywaffle.layuv.ai

/** One turn in an Ask-AI conversation sent to the provider. [role] is `user`/`assistant`. */
data class AiMessage(
    val role: String,
    val text: String,
    /** PNG bytes for handwritten-note images (read by vision models); sent after the text. */
    val images: List<ByteArray> = emptyList(),
) {
    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
    }
}

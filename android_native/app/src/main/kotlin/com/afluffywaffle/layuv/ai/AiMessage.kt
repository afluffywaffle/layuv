package com.afluffywaffle.layuv.ai

/** One turn in an Ask-AI conversation sent to the provider. [role] is `user`/`assistant`. */
data class AiMessage(val role: String, val text: String) {
    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
    }
}

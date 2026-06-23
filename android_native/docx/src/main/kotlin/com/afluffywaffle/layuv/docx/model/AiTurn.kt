package com.afluffywaffle.layuv.docx.model

/**
 * One turn in an in-app "Ask AI" conversation, persisted in the chapter DOCX at
 * `leamh/aichat.json` so the thread suspends/resumes across leaving the panel,
 * process death, and reboot.
 *
 * [role] is `"user"` or `"assistant"`. [truncated] marks an assistant turn that
 * stopped at the model's `max_tokens` (a "Continue" is offered for it).
 *
 * [toMap]/[fromMap] mirror the [Annotation] pattern; the JSON string itself is
 * handled one layer up (engine `JsonWriter`/`Json`). All parsing is null-tolerant.
 */
data class AiTurn(
    val role: String,
    val text: String,
    val truncated: Boolean = false,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "role" to role,
        "text" to text,
        "truncated" to truncated,
    )

    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"

        fun fromMap(map: Map<String, Any?>): AiTurn = AiTurn(
            role = (map["role"] as? String) ?: ROLE_USER,
            text = (map["text"] as? String) ?: "",
            truncated = (map["truncated"] as? Boolean) ?: false,
        )
    }
}

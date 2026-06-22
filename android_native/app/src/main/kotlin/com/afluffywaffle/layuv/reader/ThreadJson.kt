package com.afluffywaffle.layuv.reader

import com.afluffywaffle.layuv.docx.model.ThreadEntry
import org.json.JSONArray
import org.json.JSONObject

/**
 * Compact JSON bridge for an annotation's comment thread, used to hand a
 * `List<ThreadEntry>` between [NoteActivity] and [ReaderActivity] through Intent
 * extras (the engine's own JSON layer is internal to the `docx` module). Both
 * sides degrade to an empty list on malformed input.
 */
object ThreadJson {

    fun encode(entries: List<ThreadEntry>): String {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(
                JSONObject()
                    .put("text", e.text)
                    .put("timestamp", e.timestamp)
                    .put("source", e.source),
            )
        }
        return arr.toString()
    }

    fun decode(json: String?): List<ThreadEntry> {
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                ThreadEntry(
                    text = o.optString("text", ""),
                    timestamp = o.optLong("timestamp", 0L),
                    source = o.optString("source", ThreadEntry.SOURCE_LEAMH),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

package com.afluffywaffle.layuv.ai

import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException

/**
 * Anthropic Messages API client (`POST /v1/messages`) over plain
 * [HttpURLConnection] + platform `org.json` — no OkHttp, no JSON dependency, no
 * Google Play Services. The request streams (`stream: true`) so a long chapter
 * rewrite doesn't hit the socket read timeout; the full text is accumulated and
 * returned ONCE (the e-ink UI renders it in a single pass).
 *
 * Logging is deliberately minimal: NEVER the API key, NEVER the request body
 * (the user's manuscript) — only a coarse event and the response code.
 */
class ClaudeProvider : AiProvider {

    override fun send(apiKey: String, messages: List<AiMessage>): AiResult {
        if (apiKey.isBlank()) return AiResult.Error("Set your Anthropic API key in AI settings first.")
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 30_000
                readTimeout = 180_000
                setRequestProperty("x-api-key", apiKey)
                setRequestProperty("anthropic-version", ANTHROPIC_VERSION)
                setRequestProperty("content-type", "application/json")
                setRequestProperty("accept", "text/event-stream")
            }
            conn.outputStream.use { it.write(buildBody(messages).toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            Log.i(TAG, "request sent; response $code")
            if (code != 200) mapHttpError(code, readErr(conn)) else parseStream(conn)
        } catch (e: UnknownHostException) {
            AiResult.Error("No network connection.")
        } catch (e: IOException) {
            AiResult.Error("Network error — please try again.")
        } catch (e: Exception) {
            Log.w(TAG, "ai request failed: ${e.javaClass.simpleName}")
            AiResult.Error("Something went wrong. Please try again.")
        } finally {
            conn?.disconnect()
        }
    }

    private fun buildBody(messages: List<AiMessage>): String {
        val arr = JSONArray()
        for (m in messages) {
            val msg = JSONObject().put("role", m.role)
            if (m.images.isEmpty()) {
                msg.put("content", m.text)
            } else {
                // Multimodal: text block first, then each handwritten-note PNG as a base64 image block.
                val content = JSONArray().put(JSONObject().put("type", "text").put("text", m.text))
                for (img in m.images) {
                    content.put(
                        JSONObject().put("type", "image").put(
                            "source",
                            JSONObject().put("type", "base64").put("media_type", "image/png")
                                .put("data", Base64.encodeToString(img, Base64.NO_WRAP)),
                        ),
                    )
                }
                msg.put("content", content)
            }
            arr.put(msg)
        }
        return JSONObject()
            .put("model", MODEL)
            .put("max_tokens", MAX_TOKENS)
            .put("stream", true)
            .put("messages", arr)
            .toString()
    }

    private fun parseStream(conn: HttpURLConnection): AiResult {
        val text = StringBuilder()
        var stopReason: String? = null
        BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { r ->
            while (true) {
                val line = r.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.substring(5).trim()
                if (payload.isEmpty() || payload == "[DONE]") continue
                val obj = try { JSONObject(payload) } catch (e: Exception) { continue }
                when (obj.optString("type")) {
                    "content_block_delta" -> {
                        val delta = obj.optJSONObject("delta")
                        if (delta?.optString("type") == "text_delta") text.append(delta.optString("text"))
                    }
                    "message_delta" -> {
                        val sr = obj.optJSONObject("delta")?.optString("stop_reason")
                        if (!sr.isNullOrEmpty()) stopReason = sr
                    }
                    "error" -> {
                        val msg = obj.optJSONObject("error")?.optString("message")
                        return AiResult.Error(if (msg.isNullOrBlank()) "The AI request failed." else msg)
                    }
                }
            }
        }
        val out = text.toString()
        return if (out.isBlank()) AiResult.Error("The AI returned an empty response. Please try again.")
        else AiResult.Ok(out, truncated = stopReason == "max_tokens")
    }

    private fun readErr(conn: HttpURLConnection): String? = try {
        conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
    } catch (e: Exception) {
        null
    }

    private fun mapHttpError(code: Int, body: String?): AiResult.Error {
        val apiMsg = body?.let {
            try { JSONObject(it).optJSONObject("error")?.optString("message")?.takeIf { m -> m.isNotBlank() } }
            catch (e: Exception) { null }
        }
        val msg = when (code) {
            401 -> "Your API key was rejected. Check it in AI settings."
            403 -> "This API key isn't allowed to use this model."
            429 -> "Rate limited — please wait a moment and try again."
            in 500..599 -> "Anthropic is busy right now. Please try again shortly."
            else -> apiMsg ?: "The request was rejected (HTTP $code)."
        }
        return AiResult.Error(msg)
    }

    companion object {
        private const val TAG = "AI"
        private const val ENDPOINT = "https://api.anthropic.com/v1/messages"

        /** Single swap point. `claude-opus-4-8` is higher quality at higher cost. */
        const val MODEL = "claude-sonnet-4-6"
        private const val MAX_TOKENS = 16000
        private const val ANTHROPIC_VERSION = "2023-06-01"
    }
}

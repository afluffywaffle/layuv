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
 * OpenAI-compatible chat-completions client (`POST {baseUrl}/chat/completions`,
 * Bearer auth, SSE) — covers OpenAI, Google Gemini (via its OpenAI-compat
 * endpoint), and local servers (Ollama / LM Studio / llama.cpp / vLLM, or a Mac
 * "brain"). The sole AI client in Layuv — streams for timeout safety, renders
 * once, and logs minimally (never the key or the request body / manuscript).
 */
class OpenAiCompatibleProvider(
    private val baseUrl: String,
    private val model: String,
    // Cloud endpoints (Gemini) require a key; a user's local server usually has none,
    // so the custom provider passes false and we send without an Authorization header.
    private val requireKey: Boolean = true,
) : AiProvider {

    override fun send(apiKey: String, messages: List<AiMessage>): AiResult {
        if (requireKey && apiKey.isBlank()) return AiResult.Error("Set your API key in AI settings first.")
        if (baseUrl.isBlank()) return AiResult.Error("Set the server address (base URL) in AI settings first.")
        val endpoint = baseUrl.trimEnd('/') + "/chat/completions"
        // Refuse plain HTTP to the public internet — only private/trusted hosts (see CleartextPolicy).
        CleartextPolicy.cleartextError(endpoint)?.let { return AiResult.Error(it) }
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 30_000
                readTimeout = 180_000
                if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("content-type", "application/json")
                setRequestProperty("accept", "text/event-stream")
            }
            conn.outputStream.use { it.write(buildBody(messages).toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            Log.i(TAG, "request sent; response $code")
            if (code != 200) mapHttpError(code, readErr(conn)) else parseStream(conn)
        } catch (e: UnknownHostException) {
            AiResult.Error("Couldn't reach the server — check the address and your connection.")
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
                // Multimodal: text first, then each handwritten-note PNG as a data-URI image part.
                val content = JSONArray().put(JSONObject().put("type", "text").put("text", m.text))
                for (img in m.images) {
                    val uri = "data:image/png;base64," + Base64.encodeToString(img, Base64.NO_WRAP)
                    content.put(
                        JSONObject().put("type", "image_url")
                            .put("image_url", JSONObject().put("url", uri)),
                    )
                }
                msg.put("content", content)
            }
            arr.put(msg)
        }
        return JSONObject()
            .put("model", model)
            .put("max_tokens", MAX_TOKENS)
            .put("stream", true)
            .put("messages", arr)
            .toString()
    }

    private fun parseStream(conn: HttpURLConnection): AiResult {
        val text = StringBuilder()
        var finish: String? = null
        BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { r ->
            while (true) {
                val line = r.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.substring(5).trim()
                if (payload.isEmpty() || payload == "[DONE]") continue
                val obj = try { JSONObject(payload) } catch (e: Exception) { continue }
                val c0 = obj.optJSONArray("choices")?.optJSONObject(0) ?: continue
                c0.optJSONObject("delta")?.optString("content")?.let { if (it.isNotEmpty()) text.append(it) }
                val fr = c0.optString("finish_reason")
                if (fr.isNotEmpty() && fr != "null") finish = fr
            }
        }
        val out = text.toString()
        return if (out.isBlank()) AiResult.Error("The AI returned an empty response. Please try again.")
        else AiResult.Ok(out, truncated = finish == "length")
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
            401, 403 -> "Your API key was rejected. Check it in AI settings."
            404 -> apiMsg ?: "Model or endpoint not found — check the model name."
            429 -> "Rate limited — please wait a moment and try again."
            in 500..599 -> "The AI server is busy right now. Please try again shortly."
            else -> apiMsg ?: "The request was rejected (HTTP $code)."
        }
        return AiResult.Error(msg)
    }

    companion object {
        private const val TAG = "AI"
        private const val MAX_TOKENS = 16000
    }
}

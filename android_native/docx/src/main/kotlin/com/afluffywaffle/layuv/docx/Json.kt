package com.afluffywaffle.layuv.docx

import org.json.JSONArray
import org.json.JSONObject

/**
 * Thin JSON bridge for the engine, mirroring Dart's `jsonDecode` → Map/List
 * shape that the models' `fromMap` consume. On Android `org.json` is part of the
 * platform; for desktop JVM tests it's on the classpath via `org.json:json`
 * (see build.gradle.kts: compileOnly + testImplementation).
 */
internal object Json {
    fun parseObject(s: String): Map<String, Any?> = JSONObject(s).asMap()
    fun parseArray(s: String): List<Any?> = JSONArray(s).asList()

    private fun JSONObject.asMap(): Map<String, Any?> = buildMap {
        // Qualify the JSONObject receiver: inside buildMap the innermost
        // receiver is the MutableMap being built, so an unqualified get(k)
        // would resolve to MutableMap.get (always null here), not
        // JSONObject.get — nulling every value.
        for (k in this@asMap.keys()) put(k, normalise(this@asMap.get(k)))
    }

    private fun JSONArray.asList(): List<Any?> = (0 until length()).map { normalise(get(it)) }

    private fun normalise(v: Any?): Any? = when (v) {
        null, JSONObject.NULL -> null
        is JSONObject -> v.asMap()
        is JSONArray -> v.asList()
        else -> v // String, Boolean, Integer, Long, Double, BigDecimal
    }
}

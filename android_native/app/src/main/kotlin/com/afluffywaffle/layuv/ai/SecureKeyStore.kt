package com.afluffywaffle.layuv.ai

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores the Anthropic API key in [EncryptedSharedPreferences] (Tink + Android
 * KeyStore — NOT Google Play Services, so it works on the Supernote). Every
 * access is wrapped: a keystore failure degrades to "no key" rather than
 * crashing (the store contract used across the app). The key is kept OUT of the
 * plain `"leamh"` SharedPreferences.
 */
object SecureKeyStore {
    private const val TAG = "AI"
    private const val FILE = "leamh_secure"
    private const val KEY_API = "anthropic_api_key"

    private fun prefs(context: Context) = EncryptedSharedPreferences.create(
        context,
        FILE,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun read(context: Context): String? = try {
        prefs(context).getString(KEY_API, null)?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        Log.e(TAG, "secure read failed", e)
        null
    }

    fun write(context: Context, key: String) {
        try {
            prefs(context).edit().putString(KEY_API, key.trim()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "secure write failed", e)
        }
    }

    fun clear(context: Context) {
        try {
            prefs(context).edit().remove(KEY_API).apply()
        } catch (e: Exception) {
            Log.e(TAG, "secure clear failed", e)
        }
    }

    fun hasKey(context: Context): Boolean = !read(context).isNullOrBlank()
}

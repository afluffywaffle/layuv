package com.afluffywaffle.layuv.reader

import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.afluffywaffle.layuv.ai.AiMessage
import com.afluffywaffle.layuv.ai.AiProviderFactory
import com.afluffywaffle.layuv.ai.AiResult
import com.afluffywaffle.layuv.ai.CleartextPolicy
import com.afluffywaffle.layuv.ai.SecureKeyStore
import java.util.concurrent.Executors

/**
 * AI endpoint settings — **provider-agnostic**. One screen with three fields:
 * the OpenAI-compatible endpoint (base URL), the model name, and an optional API
 * key (blank for a keyless local server). No provider list — the same form reaches
 * Claude / Gemini / OpenAI via their OpenAI-compatible URLs or the user's own
 * server / Mac reference-library server. Config lives in the plain `"leamh"` prefs; the key lives in
 * [SecureKeyStore] and is never displayed. Gated behind the Help & About → Ask AI
 * disclosures. Intentionally bare — the target user is technical.
 */
class AiSettingsActivity : Activity() {

    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ai-settings").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    private lateinit var baseUrlField: EditText
    private lateinit var modelField: EditText
    private lateinit var keyField: EditText
    private lateinit var keyToggle: TextView
    private var keyVisible = false
    private lateinit var statusLabel: TextView
    private var testing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ReaderTheme.seedBodyFont(this)
        // Defensive gate: this screen is only reachable once the user has accepted the
        // AI disclosures in Help & About → Ask AI. If not (e.g. launched directly), show a
        // locked screen pointing there rather than the fields.
        setContentView(if (disclosureAccepted()) buildUi() else buildLockedUi())
    }

    override fun onDestroy() {
        super.onDestroy()
        ioExecutor.shutdownNow()
    }

    override fun onPause() {
        super.onPause()
        // Partial save: persist the fields as-is whenever the screen loses focus, so a value
        // pasted one-at-a-time (back out → copy the next from the doc → return) isn't lost.
        persistFields()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ReaderTheme.PAPER)
        }

        // Header: Done pill + title.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(8f), dp(12f), dp(4f))
        }
        header.addView(pillButton("Done", filled = false) { finish() })
        header.addView(TextView(this).apply {
            text = "AI settings"
            typeface = ReaderTheme.chromeBold(this@AiSettingsActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(ReaderTheme.INK_87)
            setPadding(dp(12f), 0, 0, 0)
        })
        root.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(hDivider(), LinearLayout.LayoutParams(MATCH_PARENT, dp(1f)))

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(16f), dp(20f), dp(16f))
        }

        // Intro — Layuv talks to any OpenAI-compatible endpoint, no provider list.
        body.addView(TextView(this).apply {
            text = "Layuv connects to any OpenAI-compatible AI endpoint — a cloud provider or a model " +
                "you run yourself. Enter its address, the model name, and a key if it needs one."
            typeface = ReaderTheme.body(this@AiSettingsActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(ReaderTheme.INK_54)
        })

        // Endpoint (base URL).
        body.addView(sectionLabel("Endpoint (base URL)"), lp(topMargin = dp(20f)))
        baseUrlField = EditText(this).apply {
            typeface = ReaderTheme.body(this@AiSettingsActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ReaderTheme.BODY_TEXT_SP)
            setTextColor(ReaderTheme.INK_87)
            setHintTextColor(HINT)
            setHighlightColor(android.graphics.Color.argb(60, 0, 0, 0))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
            hint = "https://… or http://192.168.x.x:11434/v1"
            setText(AiProviderFactory.baseUrl(this@AiSettingsActivity))
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            background = popupBackground()
            minimumHeight = dp(48f)
        }
        body.addView(rowWithPaste(baseUrlField), lp(topMargin = dp(8f)))

        // Worked examples (plain text, not buttons — keeps the form provider-agnostic).
        body.addView(TextView(this).apply {
            text = "Examples:\n" +
                "•  Claude — https://api.anthropic.com/v1  (model e.g. claude-sonnet-4-6)\n" +
                "•  Gemini (free tier) — https://generativelanguage.googleapis.com/v1beta/openai  (gemini-2.5-flash)\n" +
                "•  OpenAI — https://api.openai.com/v1  (gpt-4o-mini)\n" +
                "•  Your own server — http://192.168.x.x:11434/v1  (Ollama / LM Studio / a Mac you run)"
            typeface = ReaderTheme.body(this@AiSettingsActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(ReaderTheme.INK_54)
        }, lp(topMargin = dp(8f)))

        // Cleartext / HTTPS guidance.
        body.addView(TextView(this).apply {
            text = "Use http:// only for a model on a network you trust — your home Wi-Fi or your own " +
                "phone hotspot. On a shared or work network, or to reach it from elsewhere, use https:// " +
                "or a VPN like Tailscale. Layuv refuses plain http:// to public internet addresses."
            typeface = ReaderTheme.body(this@AiSettingsActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(ReaderTheme.INK_54)
        }, lp(topMargin = dp(8f)))

        // Model.
        body.addView(sectionLabel("Model"), lp(topMargin = dp(20f)))
        modelField = EditText(this).apply {
            typeface = ReaderTheme.body(this@AiSettingsActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ReaderTheme.BODY_TEXT_SP)
            setTextColor(ReaderTheme.INK_87)
            setHintTextColor(HINT)
            setHighlightColor(android.graphics.Color.argb(60, 0, 0, 0))
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            hint = "The model name your endpoint expects"
            setText(AiProviderFactory.model(this@AiSettingsActivity))
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            background = popupBackground()
            minimumHeight = dp(48f)
        }
        body.addView(rowWithPaste(modelField), lp(topMargin = dp(8f)))

        // API key (optional).
        body.addView(sectionLabel("API key (optional)"), lp(topMargin = dp(20f)))
        keyField = EditText(this).apply {
            typeface = ReaderTheme.body(this@AiSettingsActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ReaderTheme.BODY_TEXT_SP)
            setTextColor(ReaderTheme.INK_87)
            setHintTextColor(HINT)
            setHighlightColor(android.graphics.Color.argb(60, 0, 0, 0))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
            hint = if (SecureKeyStore.hasKey(this@AiSettingsActivity)) "Key saved — enter a new key to replace"
            else "Leave blank only for a local server with no key"
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            background = popupBackground()
            minimumHeight = dp(48f)
        }
        val fieldRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fieldRow.addView(keyField, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        keyToggle = textButton("Show", bold = true) { toggleKeyVisible() }
        fieldRow.addView(keyToggle, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).also { it.leftMargin = dp(4f) })
        fieldRow.addView(textButton("Paste", bold = true) { pasteInto(keyField, reveal = true) },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).also { it.leftMargin = dp(4f) })
        body.addView(fieldRow, lp(topMargin = dp(8f)))

        body.addView(TextView(this).apply {
            text = "A cloud provider's API key is not the same as a chat subscription — a paid Claude or " +
                "ChatGPT plan won't work here. Gemini's API key is free."
            typeface = ReaderTheme.body(this@AiSettingsActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(ReaderTheme.INK_54)
        }, lp(topMargin = dp(8f)))

        // Save + full removal.
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnRow.addView(pillButton("Save", filled = true) { save() })
        btnRow.addView(pillButton("Remove AI configuration", filled = false) { confirmRemove() },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).also { it.leftMargin = dp(8f) })
        body.addView(btnRow, lp(topMargin = dp(16f)))

        // Test connection.
        body.addView(textButton("Test connection", bold = true) { testConnection() }.apply {
            gravity = Gravity.START
        }, lp(topMargin = dp(8f)))

        statusLabel = TextView(this).apply {
            typeface = ReaderTheme.body(this@AiSettingsActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(ReaderTheme.INK_54)
            visibility = View.GONE
        }
        body.addView(statusLabel, lp(topMargin = dp(8f)))

        // Privacy reminder. The data-policy specifics differ per endpoint, so this stays general;
        // the one-time Help gate carries the full disclosure.
        body.addView(TextView(this).apply {
            text = "Your key (if any) is encrypted on this device and sent only to the endpoint you set " +
                "above — never to the developer. A model on your own machine keeps the manuscript on your " +
                "network; a cloud provider's data policy is its own (some free tiers may train on inputs). " +
                "With nothing set, Layuv connects to nothing."
            typeface = ReaderTheme.body(this@AiSettingsActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(ReaderTheme.INK_54)
        }, lp(topMargin = dp(24f)))

        val scroll = ScrollView(this).apply { addView(body) }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        return root
    }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        typeface = ReaderTheme.chromeBold(this@AiSettingsActivity)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(ReaderTheme.INK_87)
    }

    private fun lp(topMargin: Int = 0) =
        LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also { it.topMargin = topMargin }

    private fun save() {
        val url = baseUrlField.text.toString().trim()
        if (url.isEmpty()) {
            toast("Enter the endpoint (base URL).")
            return
        }
        persistFields()
        if (keyField.text.toString().isNotBlank()) {
            logKeystoreBacking()
            keyField.setText("")
            keyField.hint = "Key saved — enter a new key to replace"
        }
        showStatus(cleartextNote(url))
        toast("Saved.")
    }

    /** Persist the current field contents — endpoint + model always, key only if a new one is typed.
     *  Called by Save AND onPause, so pasting one value at a time across app switches is never lost. */
    private fun persistFields() {
        if (!::baseUrlField.isInitialized) return // locked screen has no fields
        prefs().edit()
            .putString("ai_base_url", baseUrlField.text.toString().trim())
            .putString("ai_model", modelField.text.toString().trim())
            .apply()
        keyField.text.toString().trim().takeIf { it.isNotEmpty() }?.let { SecureKeyStore.write(this, it) }
    }

    /** Wrap a field in a row with a trailing "Paste" button (clipboard → field). */
    private fun rowWithPaste(field: EditText): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(field, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(textButton("Paste", bold = true) { pasteInto(field) },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).also { it.leftMargin = dp(4f) })
        return row
    }

    /** A friendly note about how the endpoint's address is treated by the cleartext guard. */
    private fun cleartextNote(url: String): String = when {
        url.startsWith("https://", ignoreCase = true) -> "Saved. Encrypted (HTTPS)."
        CleartextPolicy.cleartextError(url) != null ->
            "Saved, but this is a plain-HTTP public address — Layuv will refuse it. Use https:// or Tailscale."
        url.startsWith("http://", ignoreCase = true) ->
            "Saved. Plain HTTP — fine on a network you trust; use Tailscale to reach it from elsewhere."
        else -> "Saved."
    }

    /** Full opt-out: wipe the key and reset every AI acknowledgment so re-enabling re-prompts. */
    private fun confirmRemove() {
        LeamhDialog.confirm(
            context = this,
            message = "Remove your AI configuration (endpoint, model, and key)? Layuv will stop connecting " +
                "to any AI until you set it up again.",
            positiveLabel = "Remove",
            negativeLabel = "Cancel",
            onConfirm = {
                SecureKeyStore.clear(this)
                val e = prefs().edit().putBoolean(KEY_DISCLOSURE, false)
                ACK_KEYS.forEach { e.putBoolean(it, false) }
                // Clear endpoint config too → a clean no-AI state.
                e.remove("ai_base_url").remove("ai_model")
                e.apply()
                toast("AI configuration removed.")
                finish()
            },
        )
    }

    private fun prefs() = getSharedPreferences("leamh", MODE_PRIVATE)
    private fun disclosureAccepted() = prefs().getBoolean(KEY_DISCLOSURE, false)

    /** Shown when the disclosures haven't been accepted — points to the gate, no fields. */
    private fun buildLockedUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ReaderTheme.PAPER)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(8f), dp(12f), dp(4f))
        }
        header.addView(pillButton("Done", filled = false) { finish() })
        header.addView(TextView(this).apply {
            text = "AI settings"
            typeface = ReaderTheme.chromeBold(this@AiSettingsActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(ReaderTheme.INK_87)
            setPadding(dp(12f), 0, 0, 0)
        })
        root.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(hDivider(), LinearLayout.LayoutParams(MATCH_PARENT, dp(1f)))

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(24f), dp(20f), dp(16f))
        }
        body.addView(TextView(this).apply {
            text = "Review and accept the AI disclosures in Help & About → Ask AI before setting up an endpoint."
            typeface = ReaderTheme.body(this@AiSettingsActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ReaderTheme.BODY_TEXT_SP)
            setTextColor(ReaderTheme.INK_87)
        })
        body.addView(pillButton("Open Ask AI", filled = true) {
            startActivity(Intent(this, HelpActivity::class.java).putExtra(HelpActivity.EXTRA_PAGE, "Ask AI"))
            finish()
        }, lp(topMargin = dp(20f)))
        root.addView(body, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        return root
    }

    /** Best-effort: record whether the encrypted-prefs master key is hardware-backed (TEE/StrongBox). */
    private fun logKeystoreBacking() {
        try {
            val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val key = ks.getKey("_androidx_security_master_key_", null) as? javax.crypto.SecretKey ?: return
            val factory = javax.crypto.SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
            val info = factory.getKeySpec(key, android.security.keystore.KeyInfo::class.java) as android.security.keystore.KeyInfo
            @Suppress("DEPRECATION")
            val backing = if (info.isInsideSecureHardware) "hardware-backed" else "software"
            android.util.Log.i("AI", "keystore master key: $backing")
        } catch (e: Exception) {
            android.util.Log.w("AI", "keystore backing check failed: ${e.message}")
        }
    }

    /** The key to test: the just-typed one if present, else the stored one (may be null for a local server). */
    private fun keyToUse(): String? =
        keyField.text.toString().trim().ifEmpty { SecureKeyStore.read(this) }?.takeIf { it.isNotBlank() }

    /** Paste the clipboard into [field]. For the key field, [reveal] also un-masks it so you can verify. */
    private fun pasteInto(field: EditText, reveal: Boolean = false) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = cm?.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
        if (clip.isNullOrEmpty()) {
            toast("Clipboard is empty.")
            return
        }
        field.setText(clip)
        if (reveal && !keyVisible) toggleKeyVisible()
        field.setSelection(field.text.length)
    }

    private fun toggleKeyVisible() {
        keyVisible = !keyVisible
        val pos = keyField.selectionEnd
        keyField.inputType = InputType.TYPE_CLASS_TEXT or
            if (keyVisible) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD
        // Changing inputType can reset the typeface to monospace — restore the body font.
        keyField.typeface = ReaderTheme.body(this)
        keyField.setSelection(pos.coerceIn(0, keyField.text.length))
        keyToggle.text = if (keyVisible) "Hide" else "Show"
    }

    private fun testConnection() {
        if (testing) return
        val url = baseUrlField.text.toString().trim()
        if (url.isEmpty()) {
            showStatus("Enter the endpoint (base URL) first.")
            return
        }
        // current() reads saved prefs, so flush the typed endpoint/model first.
        prefs().edit()
            .putString("ai_base_url", url)
            .putString("ai_model", modelField.text.toString().trim())
            .apply()
        testing = true
        showStatus("Testing…")
        val key = keyToUse() ?: ""
        ioExecutor.execute {
            val res = AiProviderFactory.current(this)
                .send(key, listOf(AiMessage(AiMessage.ROLE_USER, "Reply with exactly: connection ok")))
            main.post {
                testing = false
                when (res) {
                    is AiResult.Ok -> showStatus("Connection OK.")
                    is AiResult.Error -> showStatus(res.userMessage)
                    // The test message carries no images, so this is unreachable; show its
                    // message anyway to keep the branch exhaustive.
                    is AiResult.NeedsTextOnlyRetry -> showStatus(res.userMessage)
                }
            }
        }
    }

    private fun showStatus(text: String) {
        statusLabel.text = text
        statusLabel.visibility = View.VISIBLE
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val KEY_DISCLOSURE = "ai_disclosure_accepted"
        private val ACK_KEYS = listOf("ai_ack_privacy", "ai_ack_storage", "ai_ack_encryption", "ai_ack_verify")
        private const val HINT = 0xFF9E9A92.toInt()
    }
}

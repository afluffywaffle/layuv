package com.afluffywaffle.layuv.reader

import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
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
import com.afluffywaffle.layuv.ai.SecureKeyStore
import java.util.concurrent.Executors

/**
 * Minimal API-key settings for the "Ask AI" feature. Masked field + Save + Clear,
 * an optional connection test, and a link to the Anthropic console. The stored key
 * is never displayed (only a "saved" hint); it lives in [SecureKeyStore], not the
 * plain `"leamh"` prefs. Intentionally bare — the target user is technical.
 */
class AiSettingsActivity : Activity() {

    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ai-settings").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    private lateinit var keyField: EditText
    private lateinit var keyToggle: TextView
    private var keyVisible = false
    private var modelField: EditText? = null
    private lateinit var statusLabel: TextView
    private var testing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ReaderTheme.seedBodyFont(this)
        // Defensive gate: this screen is only reachable once the user has accepted the
        // AI disclosures in Help & About → Ask AI. If not (e.g. launched directly), show a
        // locked screen pointing there rather than the key field.
        setContentView(if (disclosureAccepted()) buildUi() else buildLockedUi())
    }

    override fun onDestroy() {
        super.onDestroy()
        ioExecutor.shutdownNow()
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

        val isGemini = AiProviderFactory.selected(this) == AiProviderFactory.PROVIDER_GEMINI

        // Provider picker.
        body.addView(sectionLabel("Provider"))
        val providerRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        providerRow.addView(pillButton("Claude", filled = !isGemini) { selectProvider(AiProviderFactory.PROVIDER_CLAUDE) })
        providerRow.addView(
            pillButton("Gemini (free tier)", filled = isGemini) { selectProvider(AiProviderFactory.PROVIDER_GEMINI) },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).also { it.leftMargin = dp(8f) },
        )
        body.addView(providerRow, lp(topMargin = dp(8f)))

        // Key.
        body.addView(sectionLabel(if (isGemini) "Gemini API key" else "Anthropic API key"), lp(topMargin = dp(20f)))
        keyField = EditText(this).apply {
            typeface = ReaderTheme.body(this@AiSettingsActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ReaderTheme.BODY_TEXT_SP)
            setTextColor(ReaderTheme.INK_87)
            setHintTextColor(0xFF9E9A92.toInt())
            setHighlightColor(android.graphics.Color.argb(60, 0, 0, 0))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
            hint = when {
                SecureKeyStore.hasKey(this@AiSettingsActivity) -> "Key saved — enter a new key to replace"
                isGemini -> "Gemini API key (AIza…)"
                else -> "sk-ant-…"
            }
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
        fieldRow.addView(textButton("Paste", bold = true) { pasteKey() },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).also { it.leftMargin = dp(4f) })
        body.addView(fieldRow, lp(topMargin = dp(8f)))

        // Model (Gemini only — editable in case the default name changes).
        modelField = null
        if (isGemini) {
            body.addView(sectionLabel("Model"), lp(topMargin = dp(16f)))
            modelField = EditText(this).apply {
                typeface = ReaderTheme.body(this@AiSettingsActivity)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, ReaderTheme.BODY_TEXT_SP)
                setTextColor(ReaderTheme.INK_87)
                setHighlightColor(android.graphics.Color.argb(60, 0, 0, 0))
                inputType = InputType.TYPE_CLASS_TEXT
                isSingleLine = true
                setText(AiProviderFactory.geminiModel(this@AiSettingsActivity))
                setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
                background = popupBackground()
                minimumHeight = dp(48f)
            }
            body.addView(modelField, lp(topMargin = dp(8f)))
        }

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

        // Short reminder + provider-specific key link.
        body.addView(TextView(this).apply {
            text = if (isGemini) {
                "Your key is encrypted on this device and sent only to Google over HTTPS — never to the " +
                    "developer. Note: Gemini's FREE tier may use your inputs to improve their products, so " +
                    "don't use it for confidential work. With no key set, Layuv connects to nothing."
            } else {
                "Your key is encrypted on this device and sent only to Anthropic over HTTPS — never to the " +
                    "developer. With no key set, Layuv connects to nothing. Full detail: Help & About → Ask AI."
            }
            typeface = ReaderTheme.body(this@AiSettingsActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(ReaderTheme.INK_54)
        }, lp(topMargin = dp(24f)))

        body.addView(textButton(
            if (isGemini) "Get a free key at aistudio.google.com" else "Get a key at console.anthropic.com",
            bold = true,
        ) { openConsole(isGemini) }.apply { gravity = Gravity.START }, lp(topMargin = dp(8f)))

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
        val key = keyField.text.toString().trim()
        if (key.isEmpty()) {
            toast("Enter a key first.")
            return
        }
        SecureKeyStore.write(this, key)
        modelField?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            prefs().edit().putString("ai_model", it).apply()
        }
        logKeystoreBacking()
        keyField.setText("")
        keyField.hint = "Key saved — enter a new key to replace"
        toast("Saved.")
    }

    /** Switch provider and rebuild the screen (key field/hint/model adapt to it). */
    private fun selectProvider(provider: String) {
        prefs().edit().putString("ai_provider", provider).apply()
        setContentView(buildUi())
    }

    /** Full opt-out: wipe the key and reset every AI acknowledgment so re-enabling re-prompts. */
    private fun confirmRemove() {
        LeamhDialog.confirm(
            context = this,
            message = "Remove your API key and AI configuration? Layuv will stop connecting to any AI " +
                "until you set it up again.",
            positiveLabel = "Remove",
            negativeLabel = "Cancel",
            onConfirm = {
                SecureKeyStore.clear(this)
                val e = prefs().edit().putBoolean(KEY_DISCLOSURE, false)
                ACK_KEYS.forEach { e.putBoolean(it, false) }
                e.apply()
                toast("AI configuration removed.")
                finish()
            },
        )
    }

    private fun prefs() = getSharedPreferences("leamh", MODE_PRIVATE)
    private fun disclosureAccepted() = prefs().getBoolean(KEY_DISCLOSURE, false)

    /** Shown when the disclosures haven't been accepted — points to the gate, no key field. */
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
            text = "Review and accept the AI disclosures in Help & About → Ask AI before adding a key."
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

    /** The key to test: the just-typed one if present, else the stored one. */
    private fun keyToUse(): String? =
        keyField.text.toString().trim().ifEmpty { SecureKeyStore.read(this) }?.takeIf { it.isNotBlank() }

    /** Paste the clipboard into the key field and reveal it, so the user can verify it landed. */
    private fun pasteKey() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = cm?.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
        if (clip.isNullOrEmpty()) {
            toast("Clipboard is empty.")
            return
        }
        keyField.setText(clip)
        if (!keyVisible) toggleKeyVisible()
        keyField.setSelection(keyField.text.length)
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
        val key = keyToUse()
        if (key.isNullOrBlank()) {
            showStatus("Enter or save a key first.")
            return
        }
        testing = true
        showStatus("Testing…")
        ioExecutor.execute {
            val res = AiProviderFactory.current(this).send(key, listOf(AiMessage(AiMessage.ROLE_USER, "Reply with exactly: connection ok")))
            main.post {
                testing = false
                when (res) {
                    is AiResult.Ok -> showStatus("Connection OK.")
                    is AiResult.Error -> showStatus(res.userMessage)
                }
            }
        }
    }

    private fun showStatus(text: String) {
        statusLabel.text = text
        statusLabel.visibility = View.VISIBLE
    }

    private fun openConsole(gemini: Boolean) {
        val url = if (gemini) "https://aistudio.google.com/apikey"
        else "https://console.anthropic.com/settings/keys"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            toast("No browser available.")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val KEY_DISCLOSURE = "ai_disclosure_accepted"
        private val ACK_KEYS = listOf("ai_ack_privacy", "ai_ack_storage", "ai_ack_encryption", "ai_ack_verify")
    }
}

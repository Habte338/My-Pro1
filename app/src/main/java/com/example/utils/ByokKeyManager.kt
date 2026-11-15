package com.example.utils

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class ByokProvider(
    val id: String,
    val displayName: String,
    val defaultModel: String,
    val supportsMultimodal: Boolean
) {
    GEMINI(
        id = "gemini",
        displayName = "Google Gemini",
        defaultModel = "gemini-3.5-flash",
        supportsMultimodal = true
    ),
    OPENAI(
        id = "openai",
        displayName = "OpenAI",
        defaultModel = "",
        supportsMultimodal = false
    ),
    ANTHROPIC(
        id = "anthropic",
        displayName = "Anthropic (Claude)",
        defaultModel = "",
        supportsMultimodal = false
    );

    companion object {
        fun fromId(id: String): ByokProvider {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: GEMINI
        }
    }
}

/**
 * UI-safe summary of a saved BYOK entry. Never carries or exposes the plaintext API key.
 */
data class ByokKeyEntry(
    val id: String,
    val provider: ByokProvider,
    val nickname: String,
    val modelId: String,
    val maskedKey: String,
    val createdAt: Long,
    val isActive: Boolean
)

/**
 * Decrypted runtime configuration used strictly in memory on IO threads for outbound header auth.
 */
data class DecryptedByokConfig(
    val id: String,
    val provider: ByokProvider,
    val nickname: String,
    val modelId: String,
    val apiKey: String
)

/**
 * Stores user-provided API keys ("Bring Your Own Key") encrypted at rest via Android KeyStore
 * AES-256-GCM ([KeystoreCryptoManager]) in private preferences excluded from Android backup.
 *
 * Plaintext keys are never written to disk and never exposed back to the UI after saving.
 */
class ByokKeyManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "byok_secure_prefs"
        private const val KEY_ENTRIES_JSON = "encrypted_byok_entries_v1"

        fun maskApiKey(rawKey: String): String {
            val trimmed = rawKey.trim()
            return if (trimmed.length <= 4) {
                "••••"
            } else {
                "••••${trimmed.takeLast(4)}"
            }
        }
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _entries = MutableStateFlow<List<ByokKeyEntry>>(loadPublicEntries())
    val entries: StateFlow<List<ByokKeyEntry>> = _entries.asStateFlow()

    private fun readRawJsonArray(): JSONArray {
        val raw = prefs.getString(KEY_ENTRIES_JSON, null) ?: return JSONArray()
        return try {
            JSONArray(raw)
        } catch (_: Exception) {
            JSONArray()
        }
    }

    private fun writeJsonArray(array: JSONArray) {
        prefs.edit().putString(KEY_ENTRIES_JSON, array.toString()).apply()
        _entries.value = loadPublicEntries()
    }

    private fun loadPublicEntries(): List<ByokKeyEntry> {
        val array = readRawJsonArray()
        val list = mutableListOf<ByokKeyEntry>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            list.add(
                ByokKeyEntry(
                    id = obj.optString("id"),
                    provider = ByokProvider.fromId(obj.optString("provider", "gemini")),
                    nickname = obj.optString("nickname", "Personal Key"),
                    modelId = obj.optString("modelId", ""),
                    maskedKey = obj.optString("maskedKey", "••••"),
                    createdAt = obj.optLong("createdAt", 0L),
                    isActive = obj.optBoolean("isActive", false)
                )
            )
        }
        return list
    }

    /**
     * Encrypts and stores a validated user-provided key. Sets the new key as active.
     */
    @Synchronized
    fun saveValidatedKey(
        provider: ByokProvider,
        rawApiKey: String,
        nickname: String?,
        modelId: String?
    ): ByokKeyEntry {
        val cleanKey = rawApiKey.trim()
        require(cleanKey.isNotEmpty()) { "API key cannot be blank." }

        val resolvedModel = modelId?.trim().orEmpty().ifEmpty { provider.defaultModel }
        val resolvedNickname = nickname?.trim().orEmpty().ifEmpty { "${provider.displayName} Key" }
        val encryptedKey = KeystoreCryptoManager.encrypt(cleanKey)
        val masked = maskApiKey(cleanKey)
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        val array = readRawJsonArray()
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.put("isActive", false)
        }

        val entryObj = JSONObject().apply {
            put("id", id)
            put("provider", provider.id)
            put("nickname", resolvedNickname)
            put("modelId", resolvedModel)
            put("encryptedApiKey", encryptedKey)
            put("maskedKey", masked)
            put("createdAt", now)
            put("isActive", true)
        }
        array.put(entryObj)
        writeJsonArray(array)

        return ByokKeyEntry(
            id = id,
            provider = provider,
            nickname = resolvedNickname,
            modelId = resolvedModel,
            maskedKey = masked,
            createdAt = now,
            isActive = true
        )
    }

    @Synchronized
    fun setActiveKey(keyId: String) {
        val array = readRawJsonArray()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            obj.put("isActive", obj.optString("id") == keyId)
        }
        writeJsonArray(array)
    }

    @Synchronized
    fun deleteKey(keyId: String) {
        val array = readRawJsonArray()
        val updated = JSONArray()
        var deletedWasActive = false
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            if (obj.optString("id") == keyId) {
                if (obj.optBoolean("isActive", false)) {
                    deletedWasActive = true
                }
            } else {
                updated.put(obj)
            }
        }
        if (deletedWasActive && updated.length() > 0) {
            updated.optJSONObject(0)?.put("isActive", true)
        }
        writeJsonArray(updated)
    }

    @Synchronized
    fun clearAllKeys() {
        prefs.edit().remove(KEY_ENTRIES_JSON).apply()
        _entries.value = emptyList()
    }

    fun getActiveEntry(): ByokKeyEntry? {
        val list = _entries.value
        return list.firstOrNull { it.isActive } ?: list.firstOrNull()
    }

    /**
     * Decrypts only the active key on demand for outbound header authentication.
     */
    @Synchronized
    fun getActiveDecryptedConfig(): DecryptedByokConfig? {
        val array = readRawJsonArray()
        var targetObj: JSONObject? = null
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            if (targetObj == null || obj.optBoolean("isActive", false)) {
                targetObj = obj
                if (obj.optBoolean("isActive", false)) break
            }
        }
        val obj = targetObj ?: return null
        val encrypted = obj.optString("encryptedApiKey", "")
        if (encrypted.isBlank()) return null

        val decryptedKey = KeystoreCryptoManager.decrypt(encrypted)
        if (decryptedKey.isBlank() || decryptedKey == encrypted && KeystoreCryptoManager.isLegacyOrUnencrypted(encrypted).not()) {
            return null
        }

        val provider = ByokProvider.fromId(obj.optString("provider", "gemini"))
        val model = obj.optString("modelId", "").ifBlank { provider.defaultModel }
        return DecryptedByokConfig(
            id = obj.optString("id"),
            provider = provider,
            nickname = obj.optString("nickname", provider.displayName),
            modelId = model,
            apiKey = decryptedKey
        )
    }
}

package com.example.data.api

import com.example.utils.ByokProvider
import com.example.utils.DecryptedByokConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.HttpException
import java.net.SocketTimeoutException

sealed class KeyValidationResult {
    data object Valid : KeyValidationResult()
    data class Invalid(val reason: String) : KeyValidationResult()
}

interface ByokNetworkClient {
    suspend fun validateKey(provider: ByokProvider, apiKey: String, modelId: String): KeyValidationResult
    suspend fun generateText(
        config: DecryptedByokConfig,
        systemInstruction: String?,
        messages: List<Pair<String, String>> // role ("user" / "model") to text
    ): String

    suspend fun performImageOcr(
        config: DecryptedByokConfig,
        base64Image: String,
        mimeType: String,
        prompt: String
    ): String
}

/**
 * Provider-specific BYOK adapter formatting outbound requests for Google Gemini, OpenAI,
 * and Anthropic using strict header authentication and sanitized error handling.
 */
class ByokProviderAdapter(
    private val httpClient: OkHttpClient = RetrofitClient.okHttpClient,
    private val geminiService: GeminiApiService = RetrofitClient.service
) : ByokNetworkClient {

    companion object {
        private const val OPENAI_BASE_URL = "https://api.openai.com/v1"
        private const val ANTHROPIC_BASE_URL = "https://api.anthropic.com/v1"
        private const val ANTHROPIC_VERSION = "2023-06-01"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    override suspend fun validateKey(
        provider: ByokProvider,
        apiKey: String,
        modelId: String
    ): KeyValidationResult {
        val cleanKey = apiKey.trim()
        if (cleanKey.isEmpty()) {
            return KeyValidationResult.Invalid("API key is required.")
        }
        val effectiveModel = modelId.trim().ifEmpty { provider.defaultModel }
        if (provider != ByokProvider.GEMINI && effectiveModel.isEmpty()) {
            return KeyValidationResult.Invalid("Please specify a Model ID for ${provider.displayName}.")
        }

        return try {
            when (provider) {
                ByokProvider.GEMINI -> {
                    val response = geminiService.validateModelAndKey(
                        model = effectiveModel,
                        apiKey = cleanKey
                    )
                    if (response.isSuccessful) {
                        KeyValidationResult.Valid
                    } else {
                        KeyValidationResult.Invalid(mapHttpError(provider, response.code()))
                    }
                }
                ByokProvider.OPENAI -> {
                    val url = if (effectiveModel.isNotEmpty()) {
                        "$OPENAI_BASE_URL/models/$effectiveModel"
                    } else {
                        "$OPENAI_BASE_URL/models"
                    }
                    val req = Request.Builder()
                        .url(url)
                        .get()
                        .addHeader("Authorization", "Bearer $cleanKey")
                        .build()
                    httpClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            KeyValidationResult.Valid
                        } else {
                            KeyValidationResult.Invalid(mapHttpError(provider, resp.code))
                        }
                    }
                }
                ByokProvider.ANTHROPIC -> {
                    val req = Request.Builder()
                        .url("$ANTHROPIC_BASE_URL/models")
                        .get()
                        .addHeader("x-api-key", cleanKey)
                        .addHeader("anthropic-version", ANTHROPIC_VERSION)
                        .build()
                    httpClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            KeyValidationResult.Valid
                        } else {
                            KeyValidationResult.Invalid(mapHttpError(provider, resp.code))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            KeyValidationResult.Invalid("Validation request failed: ${sanitizeErrorMessage(e.message, cleanKey)}")
        }
    }

    override suspend fun generateText(
        config: DecryptedByokConfig,
        systemInstruction: String?,
        messages: List<Pair<String, String>>
    ): String {
        val model = config.modelId.ifBlank { config.provider.defaultModel }
        require(model.isNotBlank()) { "No model configured for ${config.provider.displayName}." }

        return try {
            when (config.provider) {
                ByokProvider.GEMINI -> {
                    val contents = messages.map { (role, text) ->
                        Content(role = role, parts = listOf(Part(text = text)))
                    }
                    val sys = systemInstruction?.let { Content(parts = listOf(Part(text = it))) }
                    val response = geminiService.generateContent(
                        model = model,
                        apiKey = config.apiKey,
                        request = GeminiRequest(
                            contents = contents,
                            generationConfig = GenerationConfig(maxOutputTokens = 2048),
                            systemInstruction = sys
                        )
                    )
                    response.extractText() ?: "Empty response from Gemini."
                }
                ByokProvider.OPENAI -> {
                    val payload = JSONObject().apply {
                        put("model", model)
                        put("max_tokens", 2048)
                        val msgArray = JSONArray()
                        if (!systemInstruction.isNullOrBlank()) {
                            msgArray.put(JSONObject().apply {
                                put("role", "system")
                                put("content", systemInstruction)
                            })
                        }
                        messages.forEach { (role, text) ->
                            msgArray.put(JSONObject().apply {
                                put("role", if (role == "model") "assistant" else "user")
                                put("content", text)
                            })
                        }
                        put("messages", msgArray)
                    }
                    val req = Request.Builder()
                        .url("$OPENAI_BASE_URL/chat/completions")
                        .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                        .addHeader("Authorization", "Bearer ${config.apiKey}")
                        .build()
                    httpClient.newCall(req).execute().use { resp ->
                        val bodyStr = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            throw IllegalStateException(mapHttpError(config.provider, resp.code))
                        }
                        val json = JSONObject(bodyStr)
                        val content = json.optJSONArray("choices")
                            ?.optJSONObject(0)
                            ?.optJSONObject("message")
                            ?.optString("content", "")
                            ?.trim()
                        if (content.isNullOrEmpty()) "Empty response from OpenAI." else content
                    }
                }
                ByokProvider.ANTHROPIC -> {
                    val payload = JSONObject().apply {
                        put("model", model)
                        put("max_tokens", 2048)
                        if (!systemInstruction.isNullOrBlank()) {
                            put("system", systemInstruction)
                        }
                        val msgArray = JSONArray()
                        messages.forEach { (role, text) ->
                            msgArray.put(JSONObject().apply {
                                put("role", if (role == "model") "assistant" else "user")
                                put("content", text)
                            })
                        }
                        put("messages", msgArray)
                    }
                    val req = Request.Builder()
                        .url("$ANTHROPIC_BASE_URL/messages")
                        .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                        .addHeader("x-api-key", config.apiKey)
                        .addHeader("anthropic-version", ANTHROPIC_VERSION)
                        .build()
                    httpClient.newCall(req).execute().use { resp ->
                        val bodyStr = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            throw IllegalStateException(mapHttpError(config.provider, resp.code))
                        }
                        val json = JSONObject(bodyStr)
                        val contentArr = json.optJSONArray("content")
                        val textBuilder = StringBuilder()
                        if (contentArr != null) {
                            for (i in 0 until contentArr.length()) {
                                val block = contentArr.optJSONObject(i) ?: continue
                                if (block.optString("type") == "text") {
                                    textBuilder.append(block.optString("text", ""))
                                }
                            }
                        }
                        val out = textBuilder.toString().trim()
                        if (out.isEmpty()) "Empty response from Anthropic." else out
                    }
                }
            }
        } catch (e: HttpException) {
            throw IllegalStateException(mapHttpError(config.provider, e.code()))
        } catch (_: SocketTimeoutException) {
            throw IllegalStateException("${config.provider.displayName} request timed out. Check connection or try a faster model ID.")
        } catch (e: Exception) {
            throw IllegalStateException(sanitizeErrorMessage(e.message, config.apiKey))
        }
    }

    override suspend fun performImageOcr(
        config: DecryptedByokConfig,
        base64Image: String,
        mimeType: String,
        prompt: String
    ): String {
        val model = config.modelId.ifBlank { config.provider.defaultModel }
        require(model.isNotBlank()) { "No model configured for ${config.provider.displayName}." }

        return try {
            when (config.provider) {
                ByokProvider.GEMINI -> {
                    val request = GeminiRequest(
                        contents = listOf(
                            Content(
                                parts = listOf(
                                    Part(text = prompt),
                                    Part(inlineData = InlineData(mimeType = mimeType, data = base64Image))
                                )
                            )
                        ),
                        generationConfig = GenerationConfig(
                            temperature = 0.1f,
                            maxOutputTokens = 2048,
                            responseMimeType = "application/json"
                        )
                    )
                    val response = geminiService.generateContent(model, config.apiKey, request)
                    response.extractText() ?: throw IllegalStateException("OCR returned an empty response.")
                }
                ByokProvider.OPENAI -> {
                    val contentArr = JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", prompt)
                        })
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().apply {
                                put("url", "data:$mimeType;base64,$base64Image")
                            })
                        })
                    }
                    val payload = JSONObject().apply {
                        put("model", model)
                        put("max_tokens", 2048)
                        put("messages", JSONArray().apply {
                            put(JSONObject().apply {
                                put("role", "user")
                                put("content", contentArr)
                            })
                        })
                    }
                    val req = Request.Builder()
                        .url("$OPENAI_BASE_URL/chat/completions")
                        .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                        .addHeader("Authorization", "Bearer ${config.apiKey}")
                        .build()
                    httpClient.newCall(req).execute().use { resp ->
                        val bodyStr = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            throw IllegalStateException(mapHttpError(config.provider, resp.code))
                        }
                        val json = JSONObject(bodyStr)
                        json.optJSONArray("choices")
                            ?.optJSONObject(0)
                            ?.optJSONObject("message")
                            ?.optString("content", "")
                            ?.trim()
                            ?.ifEmpty { null }
                            ?: throw IllegalStateException("OCR returned an empty response.")
                    }
                }
                ByokProvider.ANTHROPIC -> {
                    val contentArr = JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "image")
                            put("source", JSONObject().apply {
                                put("type", "base64")
                                put("media_type", mimeType)
                                put("data", base64Image)
                            })
                        })
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", prompt)
                        })
                    }
                    val payload = JSONObject().apply {
                        put("model", model)
                        put("max_tokens", 2048)
                        put("messages", JSONArray().apply {
                            put(JSONObject().apply {
                                put("role", "user")
                                put("content", contentArr)
                            })
                        })
                    }
                    val req = Request.Builder()
                        .url("$ANTHROPIC_BASE_URL/messages")
                        .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                        .addHeader("x-api-key", config.apiKey)
                        .addHeader("anthropic-version", ANTHROPIC_VERSION)
                        .build()
                    httpClient.newCall(req).execute().use { resp ->
                        val bodyStr = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            throw IllegalStateException(mapHttpError(config.provider, resp.code))
                        }
                        val json = JSONObject(bodyStr)
                        val arr = json.optJSONArray("content")
                        val sb = StringBuilder()
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                val block = arr.optJSONObject(i) ?: continue
                                if (block.optString("type") == "text") {
                                    sb.append(block.optString("text", ""))
                                }
                            }
                        }
                        sb.toString().trim().ifEmpty { null }
                            ?: throw IllegalStateException("OCR returned an empty response.")
                    }
                }
            }
        } catch (e: HttpException) {
            throw IllegalStateException(mapHttpError(config.provider, e.code()))
        } catch (_: SocketTimeoutException) {
            throw IllegalStateException("${config.provider.displayName} OCR timed out. Try a cropped image or faster model ID.")
        } catch (e: Exception) {
            throw IllegalStateException(sanitizeErrorMessage(e.message, config.apiKey))
        }
    }

    fun mapHttpError(provider: ByokProvider, code: Int): String {
        return when (code) {
            400 -> "${provider.displayName} rejected the request (HTTP 400). Check your key or model ID."
            401, 403 -> "${provider.displayName} authentication failed (HTTP $code). Verify your API key."
            404 -> "${provider.displayName} model not found (HTTP 404). Check the Model ID in Settings."
            429 -> "${provider.displayName} rate limit or quota exceeded (HTTP 429)."
            else -> "${provider.displayName} returned HTTP $code."
        }
    }

    private fun sanitizeErrorMessage(raw: String?, secret: String): String {
        val msg = raw ?: "Network error"
        return if (secret.isNotBlank()) msg.replace(secret, "[REDACTED]") else msg
    }
}

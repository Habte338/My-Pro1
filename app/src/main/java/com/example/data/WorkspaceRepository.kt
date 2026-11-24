package com.example.data

import android.util.Base64
import com.example.data.api.ByokNetworkClient
import com.example.data.api.ByokProviderAdapter
import com.example.data.api.Content
import com.example.data.api.GeminiRequest
import com.example.data.api.GenerationConfig
import com.example.data.api.InlineData
import com.example.data.api.KeyValidationResult
import com.example.data.api.Part
import com.example.data.api.RetrofitClient
import com.example.data.db.ChatMessage
import com.example.data.db.FlashcardMasteryEntity
import com.example.data.db.QuizScoreEntity
import com.example.data.db.TranscriptionSession
import com.example.data.db.VoiceRecordingEntity
import com.example.data.db.WorkspaceDao
import com.example.utils.ByokKeyManager
import com.example.utils.ByokProvider
import kotlinx.coroutines.flow.Flow
import retrofit2.HttpException
import java.io.File
import java.net.SocketTimeoutException

data class OcrResult(
    val originalLanguage: String = "",
    val transcription: String = "",
    val translation: String = "",
    val error: String? = null
)

class WorkspaceRepository(
    private val dao: WorkspaceDao,
    private val byokKeyManager: ByokKeyManager? = null,
    private val providerAdapter: ByokNetworkClient = ByokProviderAdapter()
) {
    companion object {
        private const val MAX_INLINE_MEDIA_BYTES = 18 * 1024 * 1024L
    }

    // Local DB Observables & Mutations
    val allTranscriptions: Flow<List<TranscriptionSession>> = dao.getAllTranscriptions()
    val allChatMessages: Flow<List<ChatMessage>> = dao.getAllChatMessages()

    suspend fun saveTranscription(session: TranscriptionSession): Long {
        return dao.insertTranscription(session)
    }

    suspend fun deleteTranscription(id: Int) {
        dao.deleteTranscriptionById(id)
    }

    suspend fun addChatMessage(role: String, text: String) {
        dao.insertChatMessage(ChatMessage(role = role, text = text))
    }

    suspend fun clearChat() {
        dao.clearChatHistory()
    }

    // Flashcard mastery persistence
    val allFlashcardMastery: Flow<List<FlashcardMasteryEntity>> = dao.getAllFlashcardMastery()

    suspend fun setFlashcardMastery(cardId: Int, isMastered: Boolean) {
        dao.setFlashcardMastery(
            FlashcardMasteryEntity(
                cardId = cardId,
                isMastered = isMastered,
                lastUpdated = System.currentTimeMillis()
            )
        )
    }

    // Quiz scores persistence
    val allQuizScores: Flow<List<QuizScoreEntity>> = dao.getAllQuizScores()
    val latestQuizScore: Flow<QuizScoreEntity?> = dao.getLatestQuizScore()
    val bestQuizScore: Flow<Int?> = dao.getBestQuizScore()

    suspend fun recordQuizScore(score: Int, total: Int): Long {
        return dao.insertQuizScore(
            QuizScoreEntity(
                score = score,
                totalQuestions = total,
                percentage = if (total > 0) (score * 100) / total else 0
            )
        )
    }

    // Voice recordings library persistence
    val allVoiceRecordings: Flow<List<VoiceRecordingEntity>> = dao.getAllVoiceRecordings()

    suspend fun saveVoiceRecording(recording: VoiceRecordingEntity): Long {
        return dao.insertVoiceRecording(recording)
    }

    suspend fun renameVoiceRecording(id: Int, newName: String) {
        dao.renameVoiceRecording(id, newName)
    }

    suspend fun deleteVoiceRecording(id: Int) {
        dao.deleteVoiceRecordingById(id)
    }

    suspend fun deleteVoiceRecordingByPath(filePath: String) {
        dao.deleteVoiceRecordingByPath(filePath)
    }

    suspend fun getVoiceRecordingByPath(filePath: String): VoiceRecordingEntity? {
        return dao.getVoiceRecordingByPath(filePath)
    }

    // BYOK Validation
    suspend fun validateByokKey(provider: ByokProvider, apiKey: String, modelId: String): KeyValidationResult {
        return providerAdapter.validateKey(provider, apiKey, modelId)
    }

    // Fast Multimodal Audio Transcription ONLY (no automatic translation; translation is strictly on user click)
    suspend fun transcribeAndDetectAudio(audioFile: File, sourceLangHint: String = "Auto-Detect"): OcrResult {
        val config = byokKeyManager?.getActiveDecryptedConfig()
            ?: return OcrResult(error = "No BYOK API key configured. Open Settings -> Bring Your Own Key to add your key.")
        if (!config.provider.supportsMultimodal) {
            return OcrResult(error = "${config.provider.displayName} active key supports text chat, OCR & translation. Select or add a Google Gemini BYOK key for audio transcription.")
        }
        if (!audioFile.exists() || audioFile.length() == 0L) {
            return OcrResult(error = "Audio file is empty or missing.")
        }
        if (audioFile.length() > MAX_INLINE_MEDIA_BYTES) {
            return OcrResult(error = "Audio file exceeds 18 MB limit. Trim or split the recording before transcribing.")
        }

        val model = config.modelId.ifBlank { ByokProvider.GEMINI.defaultModel }
        val base64Bytes = try {
            Base64.encodeToString(audioFile.readBytes(), Base64.NO_WRAP)
        } catch (e: Exception) {
            return OcrResult(error = "Error reading audio file: ${e.message}")
        }

        val ext = audioFile.extension.lowercase()
        val mimeType = when (ext) {
            "mp3" -> "audio/mp3"
            "wav" -> "audio/wav"
            "m4a" -> "audio/m4a"
            "aac" -> "audio/aac"
            "ogg" -> "audio/ogg"
            else -> "audio/mp4"
        }

        val hintClause = if (sourceLangHint.isNotBlank() && sourceLangHint != "Auto-Detect") {
            "Language hint: $sourceLangHint (verify from audio)."
        } else {
            "Detect the spoken language automatically (e.g. Amharic, Tigrinya, Oromo, Somali, English)."
        }

        val prompt = """
            Transcribe this audio fast and accurately. Do NOT translate.
            $hintClause
            Use proper native script (e.g. Ge'ez/Ethiopic script for Amharic or Tigrinya, never romanize).
            Output ONLY raw JSON:
            {"originalLanguage":"<language>","transcription":"<native-script transcription>"}
        """.trimIndent()

        val request = GeminiRequest(
            contents = listOf(
                Content(
                    parts = listOf(
                        Part(text = prompt),
                        Part(inlineData = InlineData(mimeType = mimeType, data = base64Bytes))
                    )
                )
            ),
            generationConfig = GenerationConfig(
                temperature = 0.1f,
                maxOutputTokens = 2048,
                responseMimeType = "application/json"
            )
        )

        return try {
            val response = RetrofitClient.service.generateContent(model, config.apiKey, request)
            val jsonText = response.extractText()
                ?: return OcrResult(error = "Voice transcription returned an empty response.")
            parseScriptOnlyJson(jsonText, defaultLanguage = "Detected Spoken Language")
        } catch (e: HttpException) {
            val errMsg = when (e.code()) {
                400 -> "Gemini rejected the audio request (HTTP 400). Check audio format or model ID."
                401, 403 -> "Gemini authentication failed (HTTP ${e.code()}). Verify your BYOK key."
                404 -> "Gemini model '$model' not found (HTTP 404). Update Model ID in Settings."
                429 -> "Gemini rate limit or quota exceeded (HTTP 429)."
                else -> "Gemini returned HTTP ${e.code()}."
            }
            OcrResult(error = errMsg)
        } catch (_: SocketTimeoutException) {
            OcrResult(error = "Audio transcription timed out. Try a shorter clip or check your connection.")
        } catch (e: Exception) {
            val sanitized = (e.message ?: "Network error").replace(config.apiKey, "[REDACTED]")
            OcrResult(error = "Audio Transcription Error: $sanitized")
        }
    }

    suspend fun transcribeAudio(audioFile: File): String {
        val result = transcribeAndDetectAudio(audioFile)
        return result.error?.let { "Error: $it" } ?: result.transcription
    }

    suspend fun translateText(text: String): String {
        val config = byokKeyManager?.getActiveDecryptedConfig()
            ?: return "Error: No BYOK API key configured. Open Settings -> Bring Your Own Key to add your key."

        val prompt = "Translate the following text into clear, modern English. Output only the translation with no explanations or prefixes:\n\n$text"
        return providerAdapter.generateText(
            config = config,
            systemInstruction = null,
            messages = listOf("user" to prompt)
        )
    }

    suspend fun translateEthiopianText(text: String, sourceLang: String, targetLang: String): String {
        val config = byokKeyManager?.getActiveDecryptedConfig()
            ?: return "Error: No BYOK API key configured. Open Settings -> Bring Your Own Key to add your key."

        val effectiveTarget = if (targetLang.isBlank() || targetLang.equals("None", ignoreCase = true)) "English" else targetLang
        val prompt = """
            Translate the following text from '$sourceLang' into clear, modern '$effectiveTarget'.
            If the target is Amharic or Tigrinya, use proper Ge'ez/Amharic script (never romanize).
            Output ONLY the translated text itself with no explanations or prefixes:

            $text
        """.trimIndent()

        return providerAdapter.generateText(
            config = config,
            systemInstruction = null,
            messages = listOf("user" to prompt)
        )
    }

    // Fast Multimodal Visual OCR ONLY (no automatic translation; translation is strictly on user click)
    suspend fun performOcrOnImage(imageFile: File, sourceLangHint: String = "Auto-Detect"): OcrResult {
        val config = byokKeyManager?.getActiveDecryptedConfig()
            ?: return OcrResult(error = "No BYOK API key configured. Open Settings -> Bring Your Own Key to add your key.")
        if (!imageFile.exists() || imageFile.length() == 0L) {
            return OcrResult(error = "Selected image file is empty or missing.")
        }
        if (imageFile.length() > MAX_INLINE_MEDIA_BYTES) {
            return OcrResult(error = "Image exceeds 18 MB limit. Please pick a smaller image.")
        }

        val base64Bytes = try {
            Base64.encodeToString(imageFile.readBytes(), Base64.NO_WRAP)
        } catch (e: Exception) {
            return OcrResult(error = "Error reading image file: ${e.message}")
        }

        val ext = imageFile.extension.lowercase()
        val mimeType = if (ext == "png") "image/png" else "image/jpeg"

        val hintClause = if (sourceLangHint.isNotBlank() && sourceLangHint != "Auto-Detect") {
            "Expected script/language hint: $sourceLangHint (verify from image)."
        } else {
            "Detect the language/script automatically (e.g. Amharic, Tigrinya, Oromo, Somali, English)."
        }

        val prompt = """
            Extract all visible printed or handwritten text from this image accurately. Do NOT translate.
            $hintClause
            Preserve native script (use Ge'ez/Amharic script for Amharic/Tigrinya; never romanize).
            Output ONLY raw JSON:
            {"originalLanguage":"<language>","transcription":"<extracted text>"}
        """.trimIndent()

        return try {
            val rawResponse = providerAdapter.performImageOcr(
                config = config,
                base64Image = base64Bytes,
                mimeType = mimeType,
                prompt = prompt
            )
            parseScriptOnlyJson(rawResponse, defaultLanguage = "Detected Script")
        } catch (e: Exception) {
            val sanitized = (e.message ?: "Network error").replace(config.apiKey, "[REDACTED]")
            OcrResult(error = "OCR Error: $sanitized")
        }
    }

    suspend fun analyzeStudyText(text: String, sourceLang: String): String {
        val config = byokKeyManager?.getActiveDecryptedConfig()
            ?: return "Error: No BYOK API key configured. Open Settings -> Bring Your Own Key to add your key."

        val prompt = """
            Provide a concise study breakdown for this $sourceLang text:
            1. Key vocabulary words (native script + gloss)
            2. Notable verb/noun structure or grammar pattern
            Do not use asterisks (*); use clean unicode bullets (•). Keep it brief and accurate.

            Text:
            $text
        """.trimIndent()

        return providerAdapter.generateText(
            config = config,
            systemInstruction = "You are a concise Ethiopian language study assistant. Avoid asterisks (*).",
            messages = listOf("user" to prompt)
        ).replace("*", "")
    }

    private fun parseScriptOnlyJson(rawText: String, defaultLanguage: String): OcrResult {
        var cleanJson = rawText.trim()
        if (cleanJson.startsWith("```")) {
            cleanJson = cleanJson
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
        }
        return try {
            val jsonObj = org.json.JSONObject(cleanJson)
            val lang = jsonObj.optString("originalLanguage", defaultLanguage).ifBlank { defaultLanguage }
            val trans = jsonObj.optString("transcription", "").trim()
            OcrResult(
                originalLanguage = lang,
                transcription = if (trans.isNotBlank()) trans else cleanJson,
                translation = ""
            )
        } catch (_: Exception) {
            val langRegex = "\"originalLanguage\"\\s*:\\s*\"([^\"]+)\"".toRegex()
            val transRegex = "\"transcription\"\\s*:\\s*\"([\\s\\S]*?)\"\\s*(?:,|\\})".toRegex()
            val lang = langRegex.find(cleanJson)?.groupValues?.get(1) ?: defaultLanguage
            val trans = transRegex.find(cleanJson)?.groupValues?.get(1)
                ?.replace("\\n", "\n")
                ?.replace("\\\"", "\"")
                ?.trim()
                ?: cleanJson
            OcrResult(originalLanguage = lang, transcription = trans, translation = "")
        }
    }

    suspend fun generateChatResponse(currentMessage: String, history: List<ChatMessage>): String {
        val config = byokKeyManager?.getActiveDecryptedConfig()
            ?: return "Error: No BYOK API key configured. Open Settings -> Bring Your Own Key to add your key."

        val mappedMessages = mutableListOf<Pair<String, String>>()
        var lastRole: String? = null

        for (msg in history) {
            val text = msg.text.trim()
            if (text.isBlank() || text.startsWith("Error:") || text.startsWith("Connection Error:")) {
                continue
            }
            val role = if (msg.role == "user") "user" else "model"
            if (role == lastRole && mappedMessages.isNotEmpty()) {
                val lastIdx = mappedMessages.lastIndex
                val prevText = mappedMessages[lastIdx].second
                mappedMessages[lastIdx] = role to "$prevText\n$text"
            } else {
                mappedMessages.add(role to text)
                lastRole = role
            }
        }

        if (lastRole == "user" && mappedMessages.isNotEmpty()) {
            val lastIdx = mappedMessages.lastIndex
            val prevText = mappedMessages[lastIdx].second
            if (!prevText.contains(currentMessage.trim())) {
                mappedMessages[lastIdx] = "user" to "$prevText\n${currentMessage.trim()}"
            }
        } else {
            mappedMessages.add("user" to currentMessage.trim())
        }

        while (mappedMessages.isNotEmpty() && mappedMessages.first().first != "user") {
            mappedMessages.removeAt(0)
        }

        val systemInstruction = "You are an Ethiopian Languages Tutor & Study Workspace Assistant. Your job is to assist users in studying and learning Amharic, Tigrinya, Oromo, Somali, and other local languages. Answer study notes, explain vocabulary, or help with quizzes. Be encouraging, precise, and use proper Ge'ez/Latin script. CRITICAL: Do NOT use any asterisks (*) for formatting, bold, headers, list bullets, or emphasis. Return only clean and neatly formatted plain text with spaces or standard unicode bullets (like •)."

        val reply = providerAdapter.generateText(
            config = config,
            systemInstruction = systemInstruction,
            messages = mappedMessages
        )
        return reply.replace("*", "")
    }
}

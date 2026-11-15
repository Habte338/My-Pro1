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
import com.example.data.db.WorkspaceDao
import com.example.utils.ByokKeyManager
import com.example.utils.ByokProvider
import kotlinx.coroutines.flow.Flow
import java.io.File

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

    // BYOK Validation
    suspend fun validateByokKey(provider: ByokProvider, apiKey: String, modelId: String): KeyValidationResult {
        return providerAdapter.validateKey(provider, apiKey, modelId)
    }

    // Multimodal Audio & OCR Calls (via active Gemini BYOK key)
    suspend fun transcribeAndDetectAudio(audioFile: File, translateTo: String): OcrResult {
        val config = byokKeyManager?.getActiveDecryptedConfig()
            ?: return OcrResult(error = "No BYOK API key configured. Open Settings -> Bring Your Own Key to add your key.")
        if (!config.provider.supportsMultimodal) {
            return OcrResult(error = "${config.provider.displayName} active key supports text chat & translation. Select or add a Google Gemini BYOK key for audio transcription.")
        }
        val model = config.modelId.ifBlank { ByokProvider.GEMINI.defaultModel }

        val base64Bytes = try {
            val bytes = audioFile.readBytes()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
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

        val prompt = """
            Perform accurate voice transcription on this audio recording.
            1. First, automatically detect the original language spoken by the speaker from all major local languages (e.g. Amharic, Tigrinya, Afaan Oromoo, Somali, English, etc.).
            2. Transcribe the spoken words carefully into editable, searchable text with proper punctuation and formatting in its native script (e.g. use proper Amharic/Ge'ez script for Amharic or Tigrinya, and never use romanized/transliterated Latin letters for Ge'ez).
            3. If the selected translation target language is NOT 'None', translate the transcribed text accurately into that language. The target translation language is: '$translateTo'.
            
            You MUST return the results in a clear JSON-like structure conforming exactly to this schema:
            {
               "originalLanguage": "Detected Spoken Language Name (e.g. Amharic / Tigrinya / Oromo / Somali / English)",
               "transcription": "The complete, highly accurate native-script transcription text with proper punctuation and spelling",
               "translation": "The translation text (or 'Same' if target is None or same as spoken language)"
            }
            Do not include any markdown backticks, prefix, suffix, or other words around the JSON object. Output ONLY the raw JSON string.
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
            generationConfig = GenerationConfig(temperature = 0.2f, responseMimeType = "application/json")
        )

        return try {
            val response = RetrofitClient.service.generateContent(model, config.apiKey, request)
            val jsonText = response.extractText()
            if (jsonText != null) {
                var cleanJson = jsonText.trim()
                if (cleanJson.startsWith("```")) {
                    cleanJson = cleanJson.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                }
                try {
                    val jsonObj = org.json.JSONObject(cleanJson)
                    val lang = jsonObj.optString("originalLanguage", "Detected Spoken Language")
                    val trans = jsonObj.optString("transcription", "")
                    val translation = jsonObj.optString("translation", "Same")
                    if (trans.isNotBlank()) {
                        OcrResult(originalLanguage = lang, transcription = trans, translation = translation)
                    } else {
                        OcrResult(originalLanguage = lang, transcription = cleanJson, translation = translation)
                    }
                } catch (_: Exception) {
                    val langRegex = "\"originalLanguage\"\\s*:\\s*\"([^\"]+)\"".toRegex()
                    val transRegex = "\"transcription\"\\s*:\\s*\"([\\s\\S]*?)\"\\s*,\\s*\"translation\"".toRegex()
                    val translatRegex = "\"translation\"\\s*:\\s*\"([\\s\\S]*?)\"".toRegex()

                    val lang = langRegex.find(cleanJson)?.groupValues?.get(1) ?: "Detected Spoken Language"
                    val trans = transRegex.find(cleanJson)?.groupValues?.get(1)?.replace("\\n", "\n")?.replace("\\\"", "\"")
                        ?: cleanJson
                    val translation = translatRegex.find(cleanJson)?.groupValues?.get(1)?.replace("\\n", "\n")?.replace("\\\"", "\"")
                        ?: if (translateTo != "None" && translateTo.lowercase() != "same") "Translation available" else "Same"

                    OcrResult(originalLanguage = lang, transcription = trans, translation = translation)
                }
            } else {
                OcrResult(error = "Voice transcription returned an empty response.")
            }
        } catch (e: Exception) {
            OcrResult(error = "Audio Transcription Error: ${e.message}")
        }
    }

    suspend fun transcribeAudio(audioFile: File): String {
        val config = byokKeyManager?.getActiveDecryptedConfig()
            ?: return "Error: No BYOK API key configured. Open Settings -> Bring Your Own Key to add your key."
        if (!config.provider.supportsMultimodal) {
            return "Error: ${config.provider.displayName} active key does not support audio transcription. Switch to a Google Gemini BYOK key."
        }
        val model = config.modelId.ifBlank { ByokProvider.GEMINI.defaultModel }

        val base64Bytes = try {
            val bytes = audioFile.readBytes()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            return "Error reading audio file: ${e.message}"
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

        val request = GeminiRequest(
            contents = listOf(
                Content(
                    parts = listOf(
                        Part(text = "Please transcribe this audio recording. Output only the transcribed text in its proper native script (e.g. use proper Amharic/Ge'ez script for Amharic or Tigrinya, and do not transliterate/romanize). Do not include any translation, annotations, prefix, or extra text."),
                        Part(inlineData = InlineData(mimeType = mimeType, data = base64Bytes))
                    )
                )
            )
        )

        val response = RetrofitClient.service.generateContent(model, config.apiKey, request)
        return response.extractText()
            ?: "Transcription failed. No text returned from Gemini."
    }

    suspend fun translateText(text: String): String {
        val config = byokKeyManager?.getActiveDecryptedConfig()
            ?: return "Error: No BYOK API key configured. Open Settings -> Bring Your Own Key to add your key."

        val prompt = "Translate the following text into clear, modern English. If the text is in Amharic, Oromo, Tigrinya, Somali or other languages, output the English translation itself. Do not write any explanations, introduction, prefix, or note:\n\n$text"
        return providerAdapter.generateText(
            config = config,
            systemInstruction = null,
            messages = listOf("user" to prompt)
        )
    }

    suspend fun translateEthiopianText(text: String, sourceLang: String, targetLang: String): String {
        val config = byokKeyManager?.getActiveDecryptedConfig()
            ?: return "Error: No BYOK API key configured. Open Settings -> Bring Your Own Key to add your key."

        val prompt = """
            Translate the following text from '$sourceLang' into clear, modern '$targetLang'.
            If the target is Amharic or Tigrinya, use proper Ge'ez/Amharic script (NEVER output transliterated Latin/Romanized letters).
            Only output the translated text itself. Do not write any explanations, notes, or prefixes:

            $text
        """.trimIndent()

        return providerAdapter.generateText(
            config = config,
            systemInstruction = null,
            messages = listOf("user" to prompt)
        )
    }

    suspend fun performOcrOnImage(imageFile: File, translateTo: String): OcrResult {
        val config = byokKeyManager?.getActiveDecryptedConfig()
            ?: return OcrResult(error = "No BYOK API key configured. Open Settings -> Bring Your Own Key to add your key.")
        if (!config.provider.supportsMultimodal) {
            return OcrResult(error = "${config.provider.displayName} active key supports text chat & translation. Select or add a Google Gemini BYOK key for visual OCR.")
        }
        val model = config.modelId.ifBlank { ByokProvider.GEMINI.defaultModel }

        val base64Bytes = try {
            val bytes = imageFile.readBytes()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            return OcrResult(error = "Error reading image file: ${e.message}")
        }

        val prompt = """
            Perform accurate printed and handwritten optical character recognition (OCR) on this image.
            1. First, automatically detect the original language and script from all major Ethiopian languages (e.g. Amharic script, Tigrinya, Oromo, Somali, or other local script).
            2. Transcribe the text carefully into editable, searchable text, using proper punctuation and formatting in its native script. Display Amharic and Tigrinya in proper Amharic/Ge'ez script (never print transliterated or romanized symbols for Ge'ez text).
            3. If the selected translation target language is NOT 'None', translate the transcribed text accurately into that language. The target translation language is: '$translateTo'.
            
            You MUST return the results in a clear JSON-like structure conforming exactly to this schema:
            {
               "originalLanguage": "Detected Language Name (e.g. Amharic / Tigrinya / Oromo / Somali)",
               "transcription": "The complete, highly accurate Ge'ez or Latin transcription text with proper punctuation",
               "translation": "The translation text (or 'Same' if target is None or same as native)"
            }
            Do not include any markdown backticks, prefix, suffix, or other words around the JSON object. Output ONLY the raw JSON string.
        """.trimIndent()

        val request = GeminiRequest(
            contents = listOf(
                Content(
                    parts = listOf(
                        Part(text = prompt),
                        Part(inlineData = InlineData(mimeType = "image/jpeg", data = base64Bytes))
                    )
                )
            ),
            generationConfig = GenerationConfig(temperature = 0.2f, responseMimeType = "application/json")
        )

        return try {
            val response = RetrofitClient.service.generateContent(model, config.apiKey, request)
            val jsonText = response.extractText()
            if (jsonText != null) {
                var cleanJson = jsonText.trim()
                if (cleanJson.startsWith("```")) {
                    cleanJson = cleanJson.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                }

                try {
                    val jsonObj = org.json.JSONObject(cleanJson)
                    val lang = jsonObj.optString("originalLanguage", "Detected Ethiopian Language")
                    val trans = jsonObj.optString("transcription", "")
                    val translation = jsonObj.optString("translation", "Same")
                    if (trans.isNotBlank()) {
                        OcrResult(originalLanguage = lang, transcription = trans, translation = translation)
                    } else {
                        OcrResult(originalLanguage = lang, transcription = cleanJson, translation = translation)
                    }
                } catch (_: Exception) {
                    val langRegex = "\"originalLanguage\"\\s*:\\s*\"([^\"]+)\"".toRegex()
                    val transRegex = "\"transcription\"\\s*:\\s*\"([\\s\\S]*?)\"\\s*,\\s*\"translation\"".toRegex()
                    val translatRegex = "\"translation\"\\s*:\\s*\"([\\s\\S]*?)\"".toRegex()

                    val lang = langRegex.find(cleanJson)?.groupValues?.get(1) ?: "Detected Ethiopian Language"
                    val trans = transRegex.find(cleanJson)?.groupValues?.get(1)?.replace("\\n", "\n")?.replace("\\\"", "\"")
                        ?: cleanJson
                    val translation = translatRegex.find(cleanJson)?.groupValues?.get(1)?.replace("\\n", "\n")?.replace("\\\"", "\"")
                        ?: if (translateTo != "None" && translateTo.lowercase() != "same") "Translation available via Translate button" else "Same"

                    OcrResult(originalLanguage = lang, transcription = trans, translation = translation)
                }
            } else {
                OcrResult(error = "OCR returned an empty response. Verify picture readability.")
            }
        } catch (e: Exception) {
            OcrResult(error = "OCR Error: ${e.message}")
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

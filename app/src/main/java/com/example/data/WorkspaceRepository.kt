package com.example.data

import android.util.Base64
import com.example.BuildConfig
import com.example.data.api.Content
import com.example.data.api.GeminiRequest
import com.example.data.api.InlineData
import com.example.data.api.Part
import com.example.data.api.RetrofitClient
import com.example.data.api.GenerationConfig
import com.example.data.db.ChatMessage
import com.example.data.db.TranscriptionSession
import com.example.data.db.WorkspaceDao
import kotlinx.coroutines.flow.Flow
import java.io.File

data class OcrResult(
    val originalLanguage: String = "",
    val transcription: String = "",
    val translation: String = "",
    val error: String? = null
)

class WorkspaceRepository(private val dao: WorkspaceDao) {

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

    // Gemini API Direct Calls
    suspend fun transcribeAndDetectAudio(audioFile: File, translateTo: String): OcrResult {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            return OcrResult(error = "Error: Gemini API Key is missing. Please configure it in the Secrets panel in AI Studio.")
        }

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
            val response = RetrofitClient.service.generateContent(apiKey, request)
            var jsonText = response.extractText()
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
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            return "Error: Gemini API Key is missing. Please configure it in the Secrets panel in AI Studio."
        }

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

        val response = RetrofitClient.service.generateContent(apiKey, request)
        return response.extractText()
            ?: "Transcription failed. No text returned from Gemini."
    }

    suspend fun translateText(text: String): String {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            return "Error: API Key is missing. Configure it in the AI Studio Secrets panel."
        }

        val request = GeminiRequest(
            contents = listOf(
                Content(
                    parts = listOf(
                        Part(text = "Translate the following text into clear, modern English. If the text is in Amharic, Oromo, Tigrinya, Somali or other languages, output the English translation itself. Do not write any explanations, introduction, prefix, or note:\n\n$text")
                    )
                )
            )
        )

        val response = RetrofitClient.service.generateContent(apiKey, request)
        return response.extractText()
            ?: "Translation failed."
    }

    suspend fun translateEthiopianText(text: String, sourceLang: String, targetLang: String): String {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            return "Error: API Key is missing. Configure it in the AI Studio Secrets panel."
        }

        val prompt = """
            Translate the following text from '$sourceLang' into clear, modern '$targetLang'.
            If the target is Amharic or Tigrinya, use proper Ge'ez/Amharic script (NEVER output transliterated Latin/Romanized letters).
            Only output the translated text itself. Do not write any explanations, notes, or prefixes:

            $text
        """.trimIndent()

        val request = GeminiRequest(
            contents = listOf(
                Content(
                    parts = listOf(
                        Part(text = prompt)
                    )
                )
            )
        )

        val response = RetrofitClient.service.generateContent(apiKey, request)
        return response.extractText()
            ?: "Translation failed."
    }

    suspend fun performOcrOnImage(imageFile: File, translateTo: String): OcrResult {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            return OcrResult(error = "Error: Gemini API Key is missing. Please configure it in the Secrets panel in AI Studio.")
        }

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
            val response = RetrofitClient.service.generateContent(apiKey, request)
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
                    // Fallback to regex or direct text if model returned non-standard JSON
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
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            return "Error: API Key is missing. Configure it in the AI Studio Secrets panel."
        }

        // Map ChatMessage entity to Gemini Content model with alternating turn sanitization
        val mappedContents = mutableListOf<Content>()
        var lastRole: String? = null

        for (msg in history) {
            val text = msg.text.trim()
            if (text.isBlank() || text.startsWith("Error:") || text.startsWith("Connection Error:")) {
                continue
            }
            val role = if (msg.role == "user") "user" else "model"
            if (role == lastRole && mappedContents.isNotEmpty()) {
                val lastIdx = mappedContents.lastIndex
                val prev = mappedContents[lastIdx]
                val prevText = prev.parts.firstOrNull()?.text ?: ""
                mappedContents[lastIdx] = Content(role = role, parts = listOf(Part(text = "$prevText\n$text")))
            } else {
                mappedContents.add(Content(role = role, parts = listOf(Part(text = text))))
                lastRole = role
            }
        }

        // Add current user message
        if (lastRole == "user" && mappedContents.isNotEmpty()) {
            val lastIdx = mappedContents.lastIndex
            val prev = mappedContents[lastIdx]
            val prevText = prev.parts.firstOrNull()?.text ?: ""
            if (!prevText.contains(currentMessage.trim())) {
                mappedContents[lastIdx] = Content(role = "user", parts = listOf(Part(text = "$prevText\n${currentMessage.trim()}")))
            }
        } else {
            mappedContents.add(Content(role = "user", parts = listOf(Part(text = currentMessage.trim()))))
        }

        // Remove any leading model turns (Gemini conversation must start with user turn)
        while (mappedContents.isNotEmpty() && mappedContents.first().role != "user") {
            mappedContents.removeAt(0)
        }

        val systemInstruction = Content(
            parts = listOf(
                Part(
                    text = "You are an Ethiopian Languages Tutor & Study Workspace Assistant. Your job is to assist users in studying and learning Amharic, Tigrinya, Oromo, Somali, and other local languages. Answer study notes, explain vocabulary, or help with quizzes. Be encouraging, precise, and use proper Ge'ez/Latin script. CRITICAL: Do NOT use any asterisks (*) for formatting, bold, headers, list bullets, or emphasis. Return only clean and neatly formatted plain text with spaces or standard unicode bullets (like •)."
                )
            )
        )

        val request = GeminiRequest(
            contents = mappedContents,
            systemInstruction = systemInstruction
        )

        val response = RetrofitClient.service.generateContent(apiKey, request)
        val plainText = response.extractText()
        return plainText?.replace("*", "") ?: "I'm sorry, I couldn't reach my servers right now."
    }
}

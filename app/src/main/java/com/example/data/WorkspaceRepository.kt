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
            var jsonText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            if (jsonText != null) {
                var cleanJson = jsonText.trim()
                if (cleanJson.startsWith("```")) {
                    cleanJson = cleanJson.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                }
                val langRegex = "\"originalLanguage\"\\s*:\\s*\"([^\"]+)\"".toRegex()
                val transRegex = "\"transcription\"\\s*:\\s*\"([^\"]+)\"".toRegex()
                val translatRegex = "\"translation\"\\s*:\\s*\"([^\"]+)\"".toRegex()
                
                val lang = langRegex.find(cleanJson)?.groupValues?.get(1) ?: "Detected Spoken Language"
                val trans = transRegex.find(cleanJson)?.groupValues?.get(1)?.replace("\\n", "\n")?.replace("\\\"", "\"") ?: "Failed to transcribe spoken audio."
                val translation = translatRegex.find(cleanJson)?.groupValues?.get(1)?.replace("\\n", "\n")?.replace("\\\"", "\"") ?: "Same"
                
                OcrResult(originalLanguage = lang, transcription = trans, translation = translation)
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
        return response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
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
        return response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
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
        return response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
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
            var jsonText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            if (jsonText != null) {
                var cleanJson = jsonText.trim()
                if (cleanJson.startsWith("```")) {
                    cleanJson = cleanJson.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                }
                val langRegex = "\"originalLanguage\"\\s*:\\s*\"([^\"]+)\"".toRegex()
                val transRegex = "\"transcription\"\\s*:\\s*\"([^\"]+)\"".toRegex()
                val translatRegex = "\"translation\"\\s*:\\s*\"([^\"]+)\"".toRegex()
                
                val lang = langRegex.find(cleanJson)?.groupValues?.get(1) ?: "Detected Ethiopian Language"
                val trans = transRegex.find(cleanJson)?.groupValues?.get(1)?.replace("\\n", "\n")?.replace("\\\"", "\"") ?: "Failed to extract printed/handwritten text."
                val translation = translatRegex.find(cleanJson)?.groupValues?.get(1)?.replace("\\n", "\n")?.replace("\\\"", "\"") ?: "Same"
                
                OcrResult(originalLanguage = lang, transcription = trans, translation = translation)
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

        // Map ChatMessage entity to Gemini Content model
        val mappedContents = mutableListOf<Content>()
        
        // Let's add chat history
        history.forEach { msg ->
            mappedContents.add(
                Content(
                    role = msg.role,
                    parts = listOf(Part(text = msg.text))
                )
            )
        }

        // Add current user message
        mappedContents.add(
            Content(
                role = "user",
                parts = listOf(Part(text = currentMessage))
            )
        )

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
        val plainText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
        return plainText?.replace("*", "") ?: "I'm sorry, I couldn't reach my servers right now."
    }
}

package com.example

import com.example.data.WorkspaceRepository
import com.example.data.api.GeminiResponse
import com.example.data.db.WorkspaceDao
import com.example.data.db.TranscriptionSession
import com.example.data.db.ChatMessage
import com.squareup.moshi.Moshi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MockDao : WorkspaceDao {
    override fun getAllTranscriptions(): Flow<List<TranscriptionSession>> = flowOf(emptyList())
    override suspend fun insertTranscription(session: TranscriptionSession): Long = 1L
    override suspend fun deleteTranscriptionById(id: Int) {}
    override fun getAllChatMessages(): Flow<List<ChatMessage>> = flowOf(emptyList())
    override suspend fun insertChatMessage(message: ChatMessage) {}
    override suspend fun clearChatHistory() {}
}

class ExampleUnitTest {
  @Test
  fun testMoshiParsing() {
    val json = """
      {
        "candidates": [
          {
            "content": {
              "parts": [
                {
                  "text": "Hello world!"
                }
              ],
              "role": "model"
            },
            "finishReason": "STOP",
            "index": 0
          }
        ]
      }
    """.trimIndent()

    val moshi = Moshi.Builder().build()
    val adapter = moshi.adapter(GeminiResponse::class.java)
    val res = adapter.fromJson(json)
    assertNotNull(res)
    assertEquals("Hello world!", res?.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text)
  }

  @Test
  fun testLiveGeminiApi() = runBlocking {
    println("DEBUG_KEY: '${com.example.BuildConfig.GEMINI_API_KEY}'")
    val repo = WorkspaceRepository(MockDao())
    val reply = repo.generateChatResponse("Say Hello in Amharic", emptyList())
    println("Gemini live reply: $reply")
    assertFalse("Reply contains error: $reply", reply.startsWith("Error:"))
    assertTrue(reply.isNotBlank())
  }
}



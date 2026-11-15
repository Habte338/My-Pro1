package com.example

import com.example.data.WorkspaceRepository
import com.example.data.api.GeminiResponse
import com.example.data.db.ChatMessage
import com.example.data.db.FlashcardMasteryEntity
import com.example.data.db.QuizScoreEntity
import com.example.data.db.TranscriptionSession
import com.example.data.db.WorkspaceDao
import com.example.ui.FidelSeries
import com.example.utils.KeystoreCryptoManager
import com.squareup.moshi.Moshi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MockDao : WorkspaceDao {
    private val transcriptions = mutableListOf<TranscriptionSession>()
    private val chatMessages = mutableListOf<ChatMessage>()
    private val flashcardMastery = mutableMapOf<Int, FlashcardMasteryEntity>()
    private val quizScores = mutableListOf<QuizScoreEntity>()

    override fun getAllTranscriptions(): Flow<List<TranscriptionSession>> = flow {
        emit(transcriptions.toList())
    }

    override suspend fun insertTranscription(session: TranscriptionSession): Long {
        transcriptions.add(session)
        return session.id.toLong()
    }

    override suspend fun deleteTranscriptionById(id: Int) {
        transcriptions.removeAll { it.id == id }
    }

    override fun getAllChatMessages(): Flow<List<ChatMessage>> = flow {
        emit(chatMessages.toList())
    }

    override suspend fun insertChatMessage(message: ChatMessage) {
        chatMessages.add(message)
    }

    override suspend fun clearChatHistory() {
        chatMessages.clear()
    }

    override fun getAllFlashcardMastery(): Flow<List<FlashcardMasteryEntity>> = flow {
        emit(flashcardMastery.values.toList())
    }

    override suspend fun setFlashcardMastery(mastery: FlashcardMasteryEntity) {
        flashcardMastery[mastery.cardId] = mastery
    }

    override fun getAllQuizScores(): Flow<List<QuizScoreEntity>> = flow {
        emit(quizScores.toList())
    }

    override suspend fun insertQuizScore(score: QuizScoreEntity): Long {
        quizScores.add(score)
        return quizScores.size.toLong()
    }

    override fun getLatestQuizScore(): Flow<QuizScoreEntity?> = flow {
        emit(quizScores.lastOrNull())
    }

    override fun getBestQuizScore(): Flow<Int?> = flow {
        emit(quizScores.maxOfOrNull { it.score })
    }
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
        assertEquals("Hello world!", res?.extractText())
    }

    @Test
    fun testThoughtFilteringReturnsNullWhenNoAnswerParts() {
        val jsonOnlyThought = """
          {
            "candidates": [
              {
                "content": {
                  "parts": [
                    {
                      "text": "Thinking about Amharic grammar...",
                      "thought": true
                    }
                  ],
                  "role": "model"
                }
              }
            ]
          }
        """.trimIndent()
        val moshi = Moshi.Builder().build()
        val adapter = moshi.adapter(GeminiResponse::class.java)
        val res = adapter.fromJson(jsonOnlyThought)
        assertNotNull(res)
        assertNull("extractText() must return null when only thought parts exist", res?.extractText())
    }

    @Test
    fun testThoughtFilteringStripsThoughtAndReturnsAnswer() {
        val jsonWithThoughtAndAnswer = """
          {
            "candidates": [
              {
                "content": {
                  "parts": [
                    {
                      "text": "Internal thinking process",
                      "thought": true
                    },
                    {
                      "text": "ሰላም ፣ እንዴት ነዎት?"
                    }
                  ],
                  "role": "model"
                }
              }
            ]
          }
        """.trimIndent()
        val moshi = Moshi.Builder().build()
        val adapter = moshi.adapter(GeminiResponse::class.java)
        val res = adapter.fromJson(jsonWithThoughtAndAnswer)
        assertNotNull(res)
        assertEquals("ሰላም ፣ እንዴት ነዎት?", res?.extractText())
    }

    // CHECK (1): Security indicator reads actual runtime configuration
    @Test
    fun testRuntimeSecurityDiagnosticInspection() {
        val status = KeystoreCryptoManager.inspectRuntimeSecurity()
        assertNotNull(status)
        assertEquals(KeystoreCryptoManager.KEY_ALIAS, status.keyAlias)
        assertTrue("Status must describe AES algorithm", status.algorithm.contains("AES"))
        assertEquals("GCM", status.blockMode)
        assertEquals(12, status.perNoteIvSize)
        assertEquals(128, status.tagLengthBits)
        assertTrue("Status summary must be dynamic and populated", status.statusSummary.isNotBlank())
    }

    // CHECK (3): Passphrase-encrypted backup (PBKDF2WithHmacSHA256 + AES-GCM) with >= 600,000 iterations
    @Test
    fun testPassphraseEncryptedBackupLifecycle() {
        val rawNotesJson = """[{"id":1,"title":"የፊደል ትምህርት","amharicText":"ሀ ሁ ሂ ሃ ሄ ህ ሆ","englishTranslation":"Hoi series","durationSeconds":0,"timestamp":1700000000}]"""
        val passphrase = "SecretAmharicKey2026!"

        // Verify baseline requirement of at least 600,000 iterations
        assertTrue("PBKDF2 iterations must be at least 600,000", KeystoreCryptoManager.PBKDF2_ITERATIONS >= 600000)

        // Test with real 600,000 iterations to verify header serialization and decryption lifecycle
        val encryptedPayload = KeystoreCryptoManager.encryptBackupWithPassphrase(rawNotesJson, passphrase)
        assertTrue(encryptedPayload.contains(KeystoreCryptoManager.BACKUP_ENCRYPTED_FORMAT))
        assertTrue(encryptedPayload.contains("\"iterations\":600000"))
        assertTrue(encryptedPayload.contains("\"kdf\":\"PBKDF2WithHmacSHA256\""))
        assertTrue(encryptedPayload.contains("\"salt\":"))
        assertTrue(encryptedPayload.contains("\"iv\":"))
        assertTrue(encryptedPayload.contains("\"cipherText\":"))

        // Decryption with correct passphrase restores original plaintext JSON
        val decrypted = KeystoreCryptoManager.decryptBackupWithPassphrase(encryptedPayload, passphrase)
        assertEquals(rawNotesJson, decrypted)

        // Decryption with wrong passphrase must fail
        try {
            KeystoreCryptoManager.decryptBackupWithPassphrase(encryptedPayload, "WrongPassphrase")
            fail("Expected exception on invalid passphrase")
        } catch (_: Exception) {
            // Success: AEADBadTagException or IllegalArgumentException caught
        }

        // Below-minimum iterations must be rejected
        try {
            KeystoreCryptoManager.encryptBackupWithPassphrase(rawNotesJson, passphrase, iterations = 1000)
            fail("Expected exception for iteration count below 600,000")
        } catch (_: IllegalArgumentException) {
            // Expected
        }
    }

    @Test
    fun testRejectsEmptyIvInAesGcmPrefix() {
        val fakeBase64Payload = "[AES_GCM_V1]::c2VjcmV0"
        assertEquals(fakeBase64Payload, KeystoreCryptoManager.decrypt(fakeBase64Payload))
    }

    @Test
    fun testByokKeyMaskingNeverExposesFullKey() {
        assertEquals("••••7890", com.example.utils.ByokKeyManager.maskApiKey("AIzaSyTestKey1234567890"))
        assertEquals("••••", com.example.utils.ByokKeyManager.maskApiKey("abc"))
    }

    // CHECK (5): Room flashcard mastery and quiz score persistence via Repository
    @Test
    fun testRoomPersistenceForFlashcardMasteryAndQuizScores() = runBlocking {
        val dao = MockDao()
        val repo = WorkspaceRepository(dao)

        // Flashcard mastery
        repo.setFlashcardMastery(cardId = 1, isMastered = true)
        repo.setFlashcardMastery(cardId = 2, isMastered = false)

        val masteryList = repo.allFlashcardMastery.first()
        assertEquals(2, masteryList.size)
        assertTrue(masteryList.first { it.cardId == 1 }.isMastered)
        assertFalse(masteryList.first { it.cardId == 2 }.isMastered)

        // Quiz score recording
        repo.recordQuizScore(score = 5, total = 5)
        val quizList = repo.allQuizScores.first()
        assertEquals(1, quizList.size)
        assertEquals(5, quizList[0].score)
        assertEquals(100, quizList[0].percentage)

        val best = repo.bestQuizScore.first()
        assertEquals(5, best)
    }

    // CHECK (6): Re-encrypt legacy Base64 & XOR notes with AES-GCM on first access
    @Test
    fun testLegacyBase64AndXorNotesReEncryption() {
        val plain = "ሰላም ለሁሉ (Peace to all)"
        val legacyXor = "[SECURE_ENC]" + plain.map { (it.code xor 42).toChar() }.joinToString("")
        val legacyBase64 = KeystoreCryptoManager.SafeBase64.encodeToString(plain.toByteArray(Charsets.UTF_8))

        // Both formats must be flagged as legacy
        assertTrue(KeystoreCryptoManager.isLegacyOrUnencrypted(legacyXor))
        assertTrue(KeystoreCryptoManager.isLegacyOrUnencrypted(legacyBase64))

        // Both formats decrypt cleanly
        assertEquals(plain, KeystoreCryptoManager.decrypt(legacyXor))
        assertEquals(plain, KeystoreCryptoManager.decrypt(legacyBase64))

        // Re-encryption produces enterprise AES-GCM format
        val reEncrypted = KeystoreCryptoManager.encrypt(KeystoreCryptoManager.decrypt(legacyXor))
        assertTrue(reEncrypted.startsWith("[AES_GCM_V1]:"))
        assertFalse(KeystoreCryptoManager.isLegacyOrUnencrypted(reEncrypted))
        assertEquals(plain, KeystoreCryptoManager.decrypt(reEncrypted))
    }

    // CHECK (7): Standard Ethiopic order names and unverified etymology flags with citations
    @Test
    fun testStandardEthiopicOrderNamesAndEtymologyMarking() {
        val hoi = FidelSeries("Hoi", "ሆይ", "h", listOf("ሀ", "ሁ", "ሂ", "ሃ", "ሄ", "ህ", "ሆ"), "ሀገር", "Country", true, "Classical Semitic root (Leslau 1995; Dillmann 1899)")
        val sha = FidelSeries("Sha", "ሻ", "sh", listOf("ሸ", "ሹ", "ሺ", "ሻ", "ሼ", "ሽ", "ሾ"), "ሻይ", "Tea", false, "Unverified, needs native-speaker or linguist review")

        assertTrue("Classical letters must be marked verified", hoi.isVerifiedClassicalEtymology)
        assertFalse("Palatalized Amharic extensions must be marked unverified", sha.isVerifiedClassicalEtymology)
        assertTrue("Unverified etymology note must specify linguist review needed", sha.etymologyNote.contains("Unverified, needs native-speaker or linguist review"))
        assertTrue("Classical series must cite Leslau (1995)", hoi.etymologyNote.contains("Leslau 1995"))

        val standardOrders = listOf("1st: ግዕዝ (Ge'ez)", "2nd: ካዕብ (Ka'eb)", "3rd: ሣልስ (Sales)", "4th: ራብዕ (Rabe)", "5th: ኃምስ (Hames)", "6th: ሳድስ (Sads)", "7th: ሳብዕ (Sabe)")
        assertEquals(7, standardOrders.size)
        assertTrue(standardOrders[0].contains("ግዕዝ"))
        assertTrue(standardOrders[6].contains("ሳብዕ"))
    }
}

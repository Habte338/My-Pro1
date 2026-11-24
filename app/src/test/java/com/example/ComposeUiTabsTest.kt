package com.example

import android.app.Application
import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.AppDatabase
import com.example.ui.ReviewWorkspaceScreen
import com.example.ui.SettingsWorkspaceScreen
import com.example.ui.WorkspaceViewModel
import com.example.ui.theme.MyApplicationTheme
import com.example.utils.ByokKeyManager
import com.example.utils.ByokProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ComposeUiTabsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: Application
    private lateinit var viewModel: WorkspaceViewModel

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        ByokKeyManager(app).clearAllKeys()
        kotlinx.coroutines.runBlocking {
            WorkspaceViewModel.clearPreferences(app)
        }
        viewModel = WorkspaceViewModel(app)
    }

    // 1. Workspace (Transcribe tab) with User Interaction — kept voice on device & strictly on-demand translation
    @Test
    fun testWorkspaceTranscribeTabInteraction() {
        composeTestRule.setContent {
            MyApplicationTheme {
                MainAppScreen(viewModel = viewModel)
            }
        }
        composeTestRule.onNodeWithText("Voice").assertExists()
        composeTestRule.onNodeWithTag("nav_tab_voice").assertExists()
        composeTestRule.onNodeWithTag("nav_tab_tutor").assertExists()
        composeTestRule.onNodeWithTag("nav_tab_library").assertExists()
        composeTestRule.onNodeWithTag("nav_tab_ocr").assertExists()
        composeTestRule.onNodeWithTag("nav_tab_review").assertExists()
        composeTestRule.onNodeWithTag("nav_tab_settings").assertExists()
        composeTestRule.onNodeWithText("አቢሲኒያ Study").assertExists()

        // Interaction 1: Tap "Preload Demo" -> keeps voice file on device and does NOT auto-translate
        composeTestRule.onNodeWithText("💾 Preload Demo").performScrollTo().assertExists().performClick()
        composeTestRule.waitUntil(timeoutMillis = 3000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            viewModel.activeTranscriptionText.value.isNotBlank() && viewModel.activeAudioFilePath.value != null
        }
        val demoVoicePath = viewModel.activeAudioFilePath.value!!
        assertTrue("Preload demo must populate active transcription", viewModel.activeTranscriptionText.value.isNotBlank())
        assertTrue(
            "Kept voice must live in persistent voice_recordings directory",
            demoVoicePath.contains("voice_recordings")
        )
        assertEquals("Translation must remain empty until user clicks Translate", "", viewModel.activeTranslationText.value)
        assertEquals("Voice preload must NOT write into OCR box", "", viewModel.ocrExtractedText.value)

        // Verify Kept on Device Play, Download & Delete buttons appear
        composeTestRule.onNodeWithTag("kept_voice_card").performScrollTo().assertExists()
        composeTestRule.onNodeWithTag("play_active_voice_button").assertExists()
        composeTestRule.onNodeWithTag("download_active_voice_button").assertExists()
        composeTestRule.onNodeWithTag("delete_active_voice_button").assertExists()

        // Interaction 2: Tap Transcribe in offline mode -> STILL does not auto-translate
        viewModel.toggleOfflineMode()
        composeTestRule.onNodeWithTag("transcribe_button").performScrollTo().assertIsEnabled().performClick()
        composeTestRule.waitForIdle()
        assertEquals("Transcribe must not auto-translate", "", viewModel.activeTranslationText.value)

        // Interaction 3: Click Translate button -> populates translation ONLY on user click
        composeTestRule.onNodeWithTag("translate_button").performScrollTo().assertIsEnabled().performClick()
        composeTestRule.waitUntil(timeoutMillis = 3000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(25)
            viewModel.activeTranslationText.value.isNotBlank()
        }
        assertTrue("Translation must populate after clicking Translate", viewModel.activeTranslationText.value.isNotBlank())

        // Interaction 4: Click Delete on kept voice -> deletes file from disk and hides kept_voice_card
        composeTestRule.onNodeWithTag("delete_active_voice_button").performScrollTo().assertIsEnabled().performClick()
        composeTestRule.waitUntil(timeoutMillis = 3000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(25)
            viewModel.activeAudioFilePath.value == null && !java.io.File(demoVoicePath).exists()
        }
        assertNull("Active voice path must clear after delete", viewModel.activeAudioFilePath.value)
        assertFalse("Deleted voice file must be removed from disk", java.io.File(demoVoicePath).exists())
        composeTestRule.onNodeWithTag("kept_voice_card").assertDoesNotExist()
    }

    // 2. Tutor AI tab with User Interaction
    @Test
    fun testTutorAiTabNavigationAndInteraction() {
        composeTestRule.setContent {
            MyApplicationTheme {
                MainAppScreen(viewModel = viewModel)
            }
        }
        composeTestRule.onNodeWithText("Tutor AI").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Tutor AI").assertExists()

        // Interaction: Type into chat_input field
        composeTestRule.onNodeWithTag("chat_input").performTextInput("ሰላም ፣ እንዴት ነዎት?")
        composeTestRule.waitForIdle()
        assertEquals("ሰላም ፣ እንዴት ነዎት?", viewModel.chatInputText.value)
    }

    // 3. Library (History tab) with User Interaction
    @Test
    fun testLibraryTabNavigationAndInteraction() {
        // Save a study note first via Workspace flow
        viewModel.restoreBackupFromJson("""[{"title":"BYOK Library Test","amharicText":"ሰላም","englishTranslation":"Peace","durationSeconds":5,"timestamp":1700000001}]""")
        composeTestRule.setContent {
            MyApplicationTheme {
                MainAppScreen(viewModel = viewModel)
            }
        }
        composeTestRule.onNodeWithText("Library").performClick()
        composeTestRule.waitUntil(timeoutMillis = 3000) {
            viewModel.transcriptionsList.value.isNotEmpty()
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("📚 Stored Study Sessions").assertExists()
        composeTestRule.onNodeWithTag("library_lazy_list").performScrollToNode(hasText("BYOK Library Test"))
        composeTestRule.onNodeWithText("BYOK Library Test").assertExists()

        // Interaction: Tap "Copy All" button on stored note card
        composeTestRule.onNodeWithText("📋 Copy All").performClick()
        composeTestRule.waitForIdle()
    }

    // 4. OCR tab with User Interaction — Scan extracts script ONLY into isolated OCR box, Translate runs ONLY on click
    @Test
    fun testOcrTabNavigationAndInteraction() {
        viewModel.setOfflineMode(true)
        // Seed distinct text in Voice Transcription box to prove OCR never overwrites Voice box
        viewModel.updateActiveTranscriptionText("VOICE_ONLY_SENTINEL")
        viewModel.updateActiveTranslationText("VOICE_TRANSLATION_SENTINEL")

        composeTestRule.setContent {
            MyApplicationTheme {
                MainAppScreen(viewModel = viewModel)
            }
        }
        composeTestRule.onNodeWithText("OCR").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("OCR").assertExists()

        // Interaction 1: Tap "Sample Doc" button (loads off UI thread)
        composeTestRule.onNodeWithTag("ocr_sample_doc_button").performScrollTo().assertIsEnabled().performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(25)
            viewModel.selectedOcrImageUri.value != null ||
                (viewModel.statusMessage.value?.startsWith("Error") == true)
        }
        assertNotNull(
            "Sample Doc interaction must select an image URI (status=${viewModel.statusMessage.value})",
            viewModel.selectedOcrImageUri.value
        )

        // Interaction 2: Tap Scan Document -> populates isolated ocrExtractedText ONLY, leaves translation blank & Voice box intact
        composeTestRule.onNodeWithTag("ocr_scan_button").performScrollTo().assertIsEnabled().performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(25)
            !viewModel.ocrLoading.value && viewModel.ocrExtractedText.value.isNotBlank()
        }
        assertTrue(viewModel.ocrExtractedText.value.isNotBlank())
        assertEquals("OCR scan must NOT auto-translate", "", viewModel.ocrTranslationText.value)
        assertEquals("OCR must NEVER overwrite Voice transcription box", "VOICE_ONLY_SENTINEL", viewModel.activeTranscriptionText.value)
        assertEquals("OCR must NEVER overwrite Voice translation box", "VOICE_TRANSLATION_SENTINEL", viewModel.activeTranslationText.value)

        // Interaction 3: Click Translate Script -> populates isolated ocrTranslationText ONLY on user demand
        composeTestRule.onNodeWithTag("ocr_translate_button").performScrollTo().assertIsEnabled().performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(25)
            viewModel.ocrTranslationText.value.isNotBlank()
        }
        assertTrue(viewModel.ocrTranslationText.value.isNotBlank())
        assertEquals("OCR translate must NOT overwrite Voice translation box", "VOICE_TRANSLATION_SENTINEL", viewModel.activeTranslationText.value)
    }

    // 5. Review Screen with Multiple User Interactions (Fidel tap, Flashcard mastery toggle, Quiz submit)
    @Test
    fun testReviewScreenDirectRenderAndSubTabsInteraction() {
        composeTestRule.setContent {
            MyApplicationTheme {
                ReviewWorkspaceScreen(viewModel = viewModel)
            }
        }
        // Verify exact Fidel matrix count header (33 series x 7 orders = 231 glyphs)
        composeTestRule.onNodeWithText("ፊደል (Ge'ez Abugida Matrix) — 33 Series × 7 Orders (231 Glyphs)").assertExists()

        // Interaction 1: Tap a glyph in Fidel Matrix to show etymology badge
        composeTestRule.onNodeWithTag("fidel_glyph_ሀ").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("fidel_etymology_badge").assertExists()
        composeTestRule.onNodeWithText("✓ Classical Ge'ez Root (Cited: Leslau 1995; Dillmann 1899)").assertExists()

        // Interaction 2: Switch to Flashcards and mark mastered in Room
        composeTestRule.onNodeWithText("🃏 Flashcards").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Card 1 of 10").assertExists()
        composeTestRule.onNodeWithTag("flashcard_mastered_button").assertHasClickAction().performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(25)
            viewModel.flashcardMasteryMap.value[1] == true
        }
        composeTestRule.waitForIdle()
        assertTrue("Card 1 mastery must persist in ViewModel, got: ${viewModel.flashcardMasteryMap.value}", viewModel.flashcardMasteryMap.value[1] == true)
        composeTestRule.onNodeWithText("✓ MASTERED (IN ROOM)").assertExists()

        // Interaction 3: Switch to Practice Quiz, answer, and advance
        composeTestRule.onNodeWithText("🎯 Practice Quiz").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Question 1 of 5").assertExists()
        composeTestRule.onNodeWithTag("quiz_option_1").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("quiz_next_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Question 2 of 5").assertExists()
    }

    // 6. Settings Screen via Bottom Nav with Password-Masked BYOK Form, Save & Encrypt Trigger, and Plaintext Warning
    @Test
    fun testSettingsScreenDirectRenderAndByokInteractions() {
        composeTestRule.setContent {
            MyApplicationTheme {
                MainAppScreen(viewModel = viewModel)
            }
        }
        composeTestRule.onNodeWithText("Settings").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Security & Keystore Status").assertExists()
        composeTestRule.onNodeWithText("No User API Key Configured").assertExists()
        composeTestRule.onNodeWithText("Bring Your Own Key (BYOK)").assertExists()

        // Interaction 1: Save & Encrypt button is disabled while API Key field is blank
        composeTestRule.onNodeWithTag("byok_add_button").performScrollTo().assertIsNotEnabled()

        // Interaction 2: Verify API key field is password-masked by default and toggle visibility works
        composeTestRule.onNodeWithTag("byok_api_key_input")
            .assert(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Password))
        composeTestRule.onNodeWithTag("byok_toggle_visibility_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("byok_api_key_input")
            .assert(androidx.compose.ui.test.SemanticsMatcher.keyNotDefined(androidx.compose.ui.semantics.SemanticsProperties.Password))
        composeTestRule.onNodeWithTag("byok_toggle_visibility_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("byok_api_key_input")
            .assert(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Password))

        // Interaction 3: Switch provider chip to OpenAI and back to Gemini
        composeTestRule.onNodeWithTag("byok_provider_openai").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("byok_provider_gemini").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        // Interaction 4: Enter Nickname, API Key, and Model -> Save & Encrypt Key triggers AES-GCM encryption
        composeTestRule.onNodeWithTag("byok_nickname_input").performScrollTo().performTextInput("Personal Study Key")
        composeTestRule.onNodeWithTag("byok_api_key_input").performScrollTo().performTextInput("test-key-12345678")
        composeTestRule.onNodeWithTag("byok_model_input").performScrollTo().performTextInput("gemini-3.5-flash")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("byok_add_button").performScrollTo().assertIsEnabled().performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(20)
            viewModel.byokKeys.value.isNotEmpty() || viewModel.byokFeedbackMessage.value != null
        }
        composeTestRule.waitForIdle()
        assertEquals(
            "Expected successful encryption feedback, got: ${viewModel.byokFeedbackMessage.value}",
            true,
            viewModel.byokFeedbackMessage.value?.first
        )
        assertTrue("Expected saved BYOK key after clicking Save & Encrypt", viewModel.byokKeys.value.isNotEmpty())

        // Verify UI updates with masked key and encrypted feedback, never showing full secret
        composeTestRule.onNodeWithTag("byok_feedback_banner").performScrollTo().assertExists()
        composeTestRule.onNodeWithText("Active: Google Gemini (••••5678)").performScrollTo().assertExists()
        composeTestRule.onNodeWithText("Personal Study Key").performScrollTo().assertExists()

        val prefs = app.getSharedPreferences("byok_secure_prefs", Context.MODE_PRIVATE)
        val rawStored = prefs.getString("encrypted_byok_entries_v1", "") ?: ""
        assertTrue("Encrypted BYOK entry must contain AES-GCM prefix", rawStored.contains("[AES_GCM_V1]:"))
        assertFalse("Raw plaintext API key must never be saved to disk", rawStored.contains("test-key-12345678"))

        // Interaction 5: Delete saved key via UI button
        val savedId = viewModel.byokKeys.value.first().id
        composeTestRule.onNodeWithTag("byok_delete_button_$savedId").performScrollTo().performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(20)
            viewModel.byokKeys.value.isEmpty()
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("No User API Key Configured").assertExists()

        // Interaction 6: Tap refresh security diagnostic
        composeTestRule.onNodeWithTag("refresh_security_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        // Interaction 7: Plaintext export triggers security warning dialog before running
        composeTestRule.onNodeWithText("📦 Export Plaintext JSON Backup").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("⚠️ Security Warning: Plaintext Export").assertExists()
        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("⚠️ Security Warning: Plaintext Export").assertDoesNotExist()
    }

    // BYOK Secure Encrypted Storage, Masking, Never-Expose-Full-Key, and Deletion Test
    @Test
    fun testByokKeyManagerEncryptsMasksAndDeletesKeys() {
        val manager = ByokKeyManager(app)
        val rawSecret = "AIzaSyExampleSecretValue9876"

        val saved = manager.saveValidatedKey(
            provider = ByokProvider.GEMINI,
            rawApiKey = rawSecret,
            nickname = "Research Key",
            modelId = ""
        )

        // Masked preview shows only last 4 characters, never the full key
        assertEquals("••••9876", saved.maskedKey)
        assertEquals("gemini-3.5-flash", saved.modelId)
        assertTrue(saved.isActive)

        // Verify raw SharedPreferences file on disk never stores the plaintext secret
        val prefs = app.getSharedPreferences("byok_secure_prefs", Context.MODE_PRIVATE)
        val rawStored = prefs.getString("encrypted_byok_entries_v1", "") ?: ""
        assertFalse("Plaintext API key must never exist in SharedPreferences", rawStored.contains(rawSecret))
        assertTrue("Stored key must use AES-GCM prefix", rawStored.contains("[AES_GCM_V1]:"))

        // Verify runtime decryption recovers exact secret for header auth
        val decrypted = manager.getActiveDecryptedConfig()
        assertNotNull(decrypted)
        assertEquals(rawSecret, decrypted?.apiKey)

        // Verify deletion clears entry
        manager.deleteKey(saved.id)
        assertTrue(manager.entries.value.isEmpty())
        assertNull(manager.getActiveDecryptedConfig())
    }

    // CHECK (4): ViewModel Restore Validation and Rejection of Malformed Files
    @Test
    fun testRestoreValidationAndRejection() {
        val res1 = viewModel.restoreBackupFromJson("")
        assertFalse(res1)
        assertTrue(viewModel.statusMessage.value?.contains("Empty") == true)

        val res2 = viewModel.restoreBackupFromJson("invalid json string here")
        assertFalse(res2)
        assertTrue(viewModel.statusMessage.value?.contains("Malformed") == true)

        val res3 = viewModel.restoreBackupFromJson("[]")
        assertFalse(res3)
        assertTrue(viewModel.statusMessage.value?.contains("zero note records") == true)

        val res4 = viewModel.restoreBackupFromJson("[{\"invalid\":\"schema\"}]")
        assertFalse(res4)
        assertTrue(viewModel.statusMessage.value?.contains("missing required") == true)
    }

    // DATA MIGRATION: Test Room v1 -> v2 Migration SQL Execution
    @Test
    fun testRoomMigration1To2SqlExecution() {
        val helper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory()
            .create(
                androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(app)
                    .name(null)
                    .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(1) {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            db.execSQL("CREATE TABLE `transcriptions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `amharicText` TEXT NOT NULL, `englishTranslation` TEXT NOT NULL, `durationSeconds` INTEGER NOT NULL, `filePath` TEXT, `timestamp` INTEGER NOT NULL)")
                            db.execSQL("CREATE TABLE `chat_messages` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `role` TEXT NOT NULL, `text` TEXT NOT NULL, `timestamp` INTEGER NOT NULL)")
                        }
                        override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                    })
                    .build()
            )
        val db = helper.writableDatabase
        AppDatabase.MIGRATION_1_2.migrate(db)

        val cursor = db.query("SELECT name FROM sqlite_master WHERE type='table' AND (name='flashcard_mastery' OR name='quiz_scores')")
        assertEquals("Both flashcard_mastery and quiz_scores tables must exist after MIGRATION_1_2", 2, cursor.count)
        cursor.close()
        db.close()
    }

    // DATA MIGRATION: Test Room v2 -> v3 Migration SQL Execution
    @Test
    fun testRoomMigration2To3SqlExecution() {
        val helper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory()
            .create(
                androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(app)
                    .name(null)
                    .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(2) {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            db.execSQL("CREATE TABLE `transcriptions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `amharicText` TEXT NOT NULL, `englishTranslation` TEXT NOT NULL, `durationSeconds` INTEGER NOT NULL, `filePath` TEXT, `timestamp` INTEGER NOT NULL)")
                            db.execSQL("CREATE TABLE `chat_messages` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `role` TEXT NOT NULL, `text` TEXT NOT NULL, `timestamp` INTEGER NOT NULL)")
                            db.execSQL("CREATE TABLE `flashcard_mastery` (`cardId` INTEGER NOT NULL PRIMARY KEY, `isMastered` INTEGER NOT NULL, `lastUpdated` INTEGER NOT NULL)")
                            db.execSQL("CREATE TABLE `quiz_scores` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `score` INTEGER NOT NULL, `totalQuestions` INTEGER NOT NULL, `percentage` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL)")
                        }
                        override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                    })
                    .build()
            )
        val db = helper.writableDatabase
        AppDatabase.MIGRATION_2_3.migrate(db)

        val cursor = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='voice_recordings'")
        assertEquals("voice_recordings table must exist after MIGRATION_2_3", 1, cursor.count)
        cursor.close()
        db.close()
    }

    @Test
    fun testViewModelRoomFlashcardAndQuizPersistence() {
        viewModel.toggleFlashcardMastery(1)
        viewModel.recordQuizResult(4, 5)
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline &&
            (viewModel.flashcardMasteryMap.value[1] != true || viewModel.allQuizScores.value.isEmpty())) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(25)
        }
        assertTrue("Room flashcard mastery must update ViewModel StateFlow, got: ${viewModel.flashcardMasteryMap.value}", viewModel.flashcardMasteryMap.value[1] == true)
        assertTrue("Room quiz scores must update ViewModel StateFlow, got: ${viewModel.allQuizScores.value}", viewModel.allQuizScores.value.isNotEmpty())
    }

    @Test
    fun testByokRepositoryRoutingAndValidation() = kotlinx.coroutines.runBlocking {
        val manager = ByokKeyManager(app)
        var capturedKey: String? = null
        var capturedProvider: ByokProvider? = null
        var capturedModel: String? = null

        val fakeNetworkClient = object : com.example.data.api.ByokNetworkClient {
            override suspend fun validateKey(
                provider: ByokProvider,
                apiKey: String,
                modelId: String
            ): com.example.data.api.KeyValidationResult {
                return if (apiKey == "valid-secret-key") {
                    com.example.data.api.KeyValidationResult.Valid
                } else {
                    com.example.data.api.KeyValidationResult.Invalid("Rejected test key")
                }
            }

            override suspend fun generateText(
                config: com.example.utils.DecryptedByokConfig,
                systemInstruction: String?,
                messages: List<Pair<String, String>>
            ): String {
                capturedKey = config.apiKey
                capturedProvider = config.provider
                capturedModel = config.modelId
                return "Mocked translation output"
            }

            override suspend fun performImageOcr(
                config: com.example.utils.DecryptedByokConfig,
                base64Image: String,
                mimeType: String,
                prompt: String
            ): String {
                capturedKey = config.apiKey
                capturedProvider = config.provider
                capturedModel = config.modelId
                return """{"originalLanguage":"Amharic","transcription":"ሰላም ጤና ይስጥልኝ"}"""
            }
        }

        val dao = MockDao()
        val repo = com.example.data.WorkspaceRepository(dao, manager, fakeNetworkClient)

        // Without any saved BYOK key, calls must prompt user to configure BYOK
        val noKeyReply = repo.translateText("ሰላም")
        assertTrue(noKeyReply.contains("No BYOK API key configured"))

        // Validation rejects invalid key
        val invalidRes = repo.validateByokKey(ByokProvider.GEMINI, "bad-key", "")
        assertTrue(invalidRes is com.example.data.api.KeyValidationResult.Invalid)

        // Real ByokProviderAdapter rejects blank key & missing OpenAI model
        val realAdapter = com.example.data.api.ByokProviderAdapter()
        assertTrue(realAdapter.validateKey(ByokProvider.GEMINI, "   ", "") is com.example.data.api.KeyValidationResult.Invalid)
        assertTrue(realAdapter.validateKey(ByokProvider.OPENAI, "sk-test", "") is com.example.data.api.KeyValidationResult.Invalid)

        // Valid key saved encrypted -> repository routes requests using decrypted key
        manager.saveValidatedKey(ByokProvider.OPENAI, "valid-secret-key", "My OpenAI", "gpt-test-model")
        val reply = repo.translateText("ሰላም")
        assertEquals("Mocked translation output", reply)
        assertEquals("valid-secret-key", capturedKey)
        assertEquals(ByokProvider.OPENAI, capturedProvider)
        assertEquals("gpt-test-model", capturedModel)

        // Multi-provider OCR returns script ONLY with empty translation (no auto-translation)
        val tmpImg = java.io.File.createTempFile("ocr_route_test", ".jpg").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
            deleteOnExit()
        }
        val ocrRes = repo.performOcrOnImage(tmpImg)
        assertNull(ocrRes.error)
        assertEquals("Amharic", ocrRes.originalLanguage)
        assertEquals("ሰላም ጤና ይስጥልኝ", ocrRes.transcription)
        assertEquals("OCR repository result must not auto-translate", "", ocrRes.translation)
    }

    // Persistent Voice Recording Kept on Device, Library Search, and SAF Download Export Test
    @Test
    fun testKeptVoiceInLibrarySearchAndExport() {
        viewModel.simulateDemoRecording()
        composeTestRule.waitUntil(timeoutMillis = 3_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(20)
            viewModel.activeAudioFilePath.value != null
        }
        val keptPath = viewModel.activeAudioFilePath.value!!
        assertTrue(java.io.File(keptPath).exists())

        // Save current session with kept voice file
        viewModel.saveCurrentSession("Kept Voice Note")
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(25)
            viewModel.transcriptionsList.value.any { it.filePath == keptPath }
        }
        val savedSession = viewModel.transcriptionsList.value.first { it.filePath == keptPath }

        // Test exportAudioFileToUri writes exact bytes to user destination URI
        val destFile = java.io.File(app.cacheDir, "downloaded_voice_copy.mp4")
        if (destFile.exists()) destFile.delete()
        viewModel.exportAudioFileToUri(app, keptPath, android.net.Uri.fromFile(destFile))
        composeTestRule.waitUntil(timeoutMillis = 3_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(25)
            destFile.exists() && destFile.length() == java.io.File(keptPath).length()
        }
        assertTrue("Exported audio file must match source size", destFile.length() == 2048L)

        // Render Library tab & verify Play/Download buttons and live Search filter
        composeTestRule.setContent {
            MyApplicationTheme {
                MainAppScreen(viewModel = viewModel)
            }
        }
        composeTestRule.onNodeWithText("Library").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("library_lazy_list")
            .performScrollToNode(hasTestTag("play_saved_voice_${savedSession.id}"))
        composeTestRule.onNodeWithTag("play_saved_voice_${savedSession.id}").assertExists()
        composeTestRule.onNodeWithTag("download_saved_voice_${savedSession.id}").assertExists()

        // Search filter hides non-matching items when query doesn't match
        composeTestRule.onNodeWithTag("library_lazy_list")
            .performScrollToNode(hasTestTag("library_search_input"))
        composeTestRule.onNodeWithTag("library_search_input").performTextInput("zzzz_no_match")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("🔍 No Matching Notes").assertExists()
    }

    // Dark Theme Persistence Across Recreated ViewModel (DataStore)
    @Test
    fun testDarkThemePersistsAcrossViewModelRestart() {
        try {
            composeTestRule.setContent {
                val isDark by viewModel.isDarkMode.collectAsState()
                MyApplicationTheme(darkTheme = isDark) {
                    MainAppScreen(viewModel = viewModel)
                }
            }
            composeTestRule.onNodeWithTag("nav_tab_settings").performClick()
            composeTestRule.waitForIdle()

            // Ensure starting from false, then toggle dark mode ON via Settings UI switch
            if (viewModel.isDarkMode.value) {
                composeTestRule.onNodeWithTag("settings_dark_mode_switch").performScrollTo().performClick()
                composeTestRule.waitForIdle()
            }
            composeTestRule.onNodeWithTag("settings_dark_mode_switch").performScrollTo().performClick()
            composeTestRule.waitUntil(timeoutMillis = 3_000) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                viewModel.isDarkMode.value
            }
            // Allow DataStore IO write to flush, then instantiate a fresh ViewModel simulating app restart
            Thread.sleep(250)
            val restartedVm = WorkspaceViewModel(app)
            composeTestRule.waitUntil(timeoutMillis = 3_000) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                Thread.sleep(25)
                restartedVm.isDarkMode.value
            }
            assertTrue("Dark theme must restore as true after restarting ViewModel", restartedVm.isDarkMode.value)
        } finally {
            // Reset back to light mode so subsequent tests start cleanly
            if (viewModel.isDarkMode.value) {
                viewModel.toggleDarkMode()
                Thread.sleep(150)
            }
        }
    }

    // Library Saved Voices Sub-Tab: Naming/Renaming, Transcribe Anytime & Deleting Voice Files
    @Test
    fun testLibrarySavedVoicesRenameTranscribeAndDelete() {
        viewModel.toggleOfflineMode()
        viewModel.updateActiveAudioDisplayName("Morning Amharic Practice")
        viewModel.simulateDemoRecording()

        composeTestRule.waitUntil(timeoutMillis = 3_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(25)
            viewModel.voiceRecordingsList.value.any { it.name == "Morning Amharic Practice" }
        }
        val voice = viewModel.voiceRecordingsList.value.first { it.name == "Morning Amharic Practice" }
        assertTrue("Voice file must exist on disk", java.io.File(voice.filePath).exists())

        composeTestRule.setContent {
            MyApplicationTheme {
                MainAppScreen(viewModel = viewModel)
            }
        }

        // Switch to Library -> Saved Voices sub-tab
        composeTestRule.onNodeWithTag("nav_tab_library").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("library_voices_tab").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("voice_card_${voice.id}").assertExists()
        composeTestRule.onNodeWithText("Morning Amharic Practice").assertExists()

        // Rename voice via Library dialog
        composeTestRule.onNodeWithTag("rename_voice_button_${voice.id}").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("rename_voice_input").performTextReplacement("Updated Greeting Clip")
        composeTestRule.onNodeWithTag("confirm_rename_voice_button").performClick()
        composeTestRule.waitUntil(timeoutMillis = 3_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(25)
            viewModel.voiceRecordingsList.value.any { it.id == voice.id && it.name == "Updated Greeting Clip" }
        }
        composeTestRule.onNodeWithText("Updated Greeting Clip").assertExists()

        // Tap Transcribe Anytime -> clears old text, loads this clip into Voice tab, and transcribes
        viewModel.updateActiveTranscriptionText("")
        composeTestRule.onNodeWithTag("library_lazy_list")
            .performScrollToNode(hasTestTag("transcribe_library_voice_${voice.id}"))
        composeTestRule.onNodeWithTag("transcribe_library_voice_${voice.id}").performClick()
        composeTestRule.waitUntil(timeoutMillis = 3_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(25)
            viewModel.activeTranscriptionText.value.isNotBlank()
        }
        composeTestRule.onNodeWithTag("voice_transcription_text_field").performScrollTo().assertExists()

        // Return to Library -> Saved Voices and Delete the saved voice clip
        composeTestRule.onNodeWithTag("nav_tab_library").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("library_voices_tab").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("library_lazy_list")
            .performScrollToNode(hasTestTag("delete_voice_button_${voice.id}"))
        composeTestRule.onNodeWithTag("delete_voice_button_${voice.id}").performClick()
        composeTestRule.waitUntil(timeoutMillis = 3_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(25)
            viewModel.voiceRecordingsList.value.none { it.id == voice.id } && !java.io.File(voice.filePath).exists()
        }
        assertFalse("Deleted voice file must be removed from disk", java.io.File(voice.filePath).exists())
        composeTestRule.onNodeWithTag("voice_card_${voice.id}").assertDoesNotExist()
    }
}

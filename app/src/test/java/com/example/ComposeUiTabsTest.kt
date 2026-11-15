package com.example

import android.app.Application
import android.content.Context
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
        viewModel = WorkspaceViewModel(app)
    }

    // 1. Workspace (Transcribe tab) with User Interaction
    @Test
    fun testWorkspaceTranscribeTabInteraction() {
        composeTestRule.setContent {
            MyApplicationTheme {
                MainAppScreen(viewModel = viewModel)
            }
        }
        composeTestRule.onNodeWithText("Workspace").assertExists()
        composeTestRule.onNodeWithText("አቢሲኒያ Study").assertExists()

        // Interaction: Tap "Preload Demo" action button
        composeTestRule.onNodeWithText("💾 Preload Demo").performScrollTo().assertExists().performClick()
        composeTestRule.waitUntil(timeoutMillis = 3000) {
            viewModel.activeTranscriptionText.value.isNotBlank()
        }
        assertTrue("Preload demo must populate active transcription", viewModel.activeTranscriptionText.value.isNotBlank())
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
        composeTestRule.onNodeWithText("BYOK Library Test").assertExists()

        // Interaction: Tap "Copy All" button on stored note card
        composeTestRule.onNodeWithText("📋 Copy All").performClick()
        composeTestRule.waitForIdle()
    }

    // 4. OCR tab with User Interaction
    @Test
    fun testOcrTabNavigationAndInteraction() {
        composeTestRule.setContent {
            MyApplicationTheme {
                MainAppScreen(viewModel = viewModel)
            }
        }
        composeTestRule.onNodeWithText("OCR").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("OCR").assertExists()

        // Interaction: Tap "Sample Doc" button
        composeTestRule.onNodeWithText("📄 Sample Doc").assertExists().performClick()
        composeTestRule.waitForIdle()
        assertNotNull("Sample Doc interaction must select an image URI", viewModel.selectedOcrImageUri.value)
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

    // 6. Settings Screen with BYOK Form + Diagnostic + Plaintext Export Warning Interactions
    @Test
    fun testSettingsScreenDirectRenderAndByokInteractions() {
        composeTestRule.setContent {
            MyApplicationTheme {
                SettingsWorkspaceScreen(viewModel = viewModel)
            }
        }
        composeTestRule.onNodeWithText("Security & Keystore Status").assertExists()
        composeTestRule.onNodeWithText("No User API Key Configured").assertExists()
        composeTestRule.onNodeWithText("Bring Your Own Key (BYOK)").assertExists()

        // Interaction 1: Add button is disabled while API Key field is blank
        composeTestRule.onNodeWithTag("byok_add_button").performScrollTo().assertIsNotEnabled()

        // Interaction 2: Switch provider chip to OpenAI and back to Gemini
        composeTestRule.onNodeWithTag("byok_provider_openai").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("byok_provider_gemini").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        // Interaction 3: Enter Nickname, API Key, and Model -> Add button becomes enabled
        composeTestRule.onNodeWithTag("byok_nickname_input").performScrollTo().performTextInput("Personal Study Key")
        composeTestRule.onNodeWithTag("byok_api_key_input").performScrollTo().performTextInput("test-key-12345678")
        composeTestRule.onNodeWithTag("byok_model_input").performScrollTo().performTextInput("gemini-3.5-flash")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("byok_add_button").assertIsEnabled()

        // Interaction 4: Tap refresh security diagnostic
        composeTestRule.onNodeWithTag("refresh_security_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        // Interaction 5: Plaintext export triggers security warning dialog before running
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
    }
}

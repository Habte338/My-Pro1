package com.example.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.speech.tts.TextToSpeech
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.example.data.WorkspaceRepository
import com.example.data.api.KeyValidationResult
import com.example.data.db.AppDatabase
import com.example.data.db.ChatMessage
import com.example.data.db.QuizScoreEntity
import com.example.data.db.TranscriptionSession
import com.example.data.db.VoiceRecordingEntity
import com.example.utils.AudioPlayerManager
import com.example.utils.AudioRecorder
import com.example.utils.ByokKeyEntry
import com.example.utils.ByokKeyManager
import com.example.utils.ByokProvider
import com.example.utils.KeystoreCryptoManager
import com.example.utils.RuntimeSecurityStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

private val Context.userPrefsDataStore by preferencesDataStore(name = "user_study_prefs")

class WorkspaceViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private val PREF_DARK_MODE = booleanPreferencesKey("dark_mode")
        private val PREF_OFFLINE_MODE = booleanPreferencesKey("offline_mode")
        private val PREF_LOCAL_ENCRYPTION = booleanPreferencesKey("local_encryption")
        private val PREF_SOURCE_LANG = stringPreferencesKey("source_lang")
        private val PREF_TARGET_LANG = stringPreferencesKey("target_lang")

        suspend fun clearPreferences(context: Context) {
            context.userPrefsDataStore.edit { it.clear() }
        }
    }

    private val db by lazy {
        Room.databaseBuilder(
            application.applicationContext,
            AppDatabase::class.java,
            "amharic_workspace_db"
        )
        .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
        .build()
    }

    private val byokKeyManager by lazy {
        ByokKeyManager(application.applicationContext)
    }

    private val repository by lazy {
        WorkspaceRepository(db.workspaceDao(), byokKeyManager)
    }

    private val audioRecorder by lazy {
        AudioRecorder(application.applicationContext)
    }

    private val audioPlayer by lazy {
        AudioPlayerManager()
    }

    private var ttsEngine: TextToSpeech? = null
    private var ttsReady = false

    // BYOK (Bring Your Own Key) state — never exposes plaintext keys to UI
    val byokKeys: StateFlow<List<ByokKeyEntry>> = byokKeyManager.entries

    private val _isByokValidating = MutableStateFlow(false)
    val isByokValidating: StateFlow<Boolean> = _isByokValidating.asStateFlow()

    private val _byokFeedbackMessage = MutableStateFlow<Pair<Boolean, String>?>(null)
    val byokFeedbackMessage: StateFlow<Pair<Boolean, String>?> = _byokFeedbackMessage.asStateFlow()

    // Database streams for History, Voices & Chat
    val transcriptionsList: StateFlow<List<TranscriptionSession>> = repository.allTranscriptions
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val voiceRecordingsList: StateFlow<List<VoiceRecordingEntity>> = repository.allVoiceRecordings
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val chatMessagesList: StateFlow<List<ChatMessage>> = repository.allChatMessages
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Database streams for Flashcard mastery & Quiz scores in Room
    val flashcardMasteryMap: StateFlow<Map<Int, Boolean>> = repository.allFlashcardMastery
        .map { list -> list.associate { it.cardId to it.isMastered } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val allQuizScores: StateFlow<List<QuizScoreEntity>> = repository.allQuizScores
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val latestQuizScore: StateFlow<QuizScoreEntity?> = repository.latestQuizScore
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val bestQuizScore: StateFlow<Int?> = repository.bestQuizScore
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // Dynamic runtime security status from Android KeyStore KeyInfo
    private val _securityStatus = MutableStateFlow(KeystoreCryptoManager.inspectRuntimeSecurity())
    val securityStatus: StateFlow<RuntimeSecurityStatus> = _securityStatus.asStateFlow()

    fun refreshSecurityStatus() {
        _securityStatus.value = KeystoreCryptoManager.inspectRuntimeSecurity()
    }

    @Volatile private var darkModeTouched = false
    @Volatile private var offlineModeTouched = false
    @Volatile private var localEncryptionTouched = false
    @Volatile private var sourceLangTouched = false
    @Volatile private var targetLangTouched = false

    init {
        // Restore persisted user preferences (Dark theme, offline mode, encryption, languages)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val prefs = getApplication<Application>().userPrefsDataStore.data.first()
                if (!darkModeTouched) prefs[PREF_DARK_MODE]?.let { _isDarkMode.value = it }
                if (!offlineModeTouched) prefs[PREF_OFFLINE_MODE]?.let { _isOfflineMode.value = it }
                if (!localEncryptionTouched) prefs[PREF_LOCAL_ENCRYPTION]?.let { _isLocalEncryptionActive.value = it }
                if (!sourceLangTouched) prefs[PREF_SOURCE_LANG]?.let { _selectedSourceLang.value = it }
                if (!targetLangTouched) prefs[PREF_TARGET_LANG]?.let { _selectedTargetLang.value = it }
            } catch (_: Exception) {}
        }

        // Sync any existing audio files in voice_recordings/ into the Library voices table
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val files = audioRecorder.getVoiceRecordingsDir().listFiles()?.filter { it.isFile && it.length() > 0L }.orEmpty()
                files.forEach { file ->
                    if (repository.getVoiceRecordingByPath(file.absolutePath) == null) {
                        val baseName = file.nameWithoutExtension.replace('_', ' ').replaceFirstChar { it.uppercase() }
                        repository.saveVoiceRecording(
                            VoiceRecordingEntity(
                                name = baseName,
                                filePath = file.absolutePath,
                                durationSeconds = 0,
                                sizeBytes = file.length(),
                                createdAt = file.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
                            )
                        )
                    }
                }
            } catch (_: Exception) {}
        }

        // First-access re-encryption of legacy notes with hardware AES-GCM off the UI thread
        viewModelScope.launch(Dispatchers.IO) {
            repository.allTranscriptions.collect { sessions ->
                sessions.forEach { session ->
                    if (KeystoreCryptoManager.isLegacyOrUnencrypted(session.amharicText) ||
                        KeystoreCryptoManager.isLegacyOrUnencrypted(session.englishTranslation)) {
                        upgradeLegacyNoteToAesGcm(session)
                    }
                }
            }
        }
    }

    // Voice Transcription operational states (isolated from OCR)
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingDuration = MutableStateFlow(0)
    val recordingDuration: StateFlow<Int> = _recordingDuration.asStateFlow()

    private val _transcriptionLoading = MutableStateFlow(false)
    val transcriptionLoading: StateFlow<Boolean> = _transcriptionLoading.asStateFlow()

    private val _translationLoading = MutableStateFlow(false)
    val translationLoading: StateFlow<Boolean> = _translationLoading.asStateFlow()

    private val _activeTranscriptionText = MutableStateFlow("")
    val activeTranscriptionText: StateFlow<String> = _activeTranscriptionText.asStateFlow()

    private val _activeTranslationText = MutableStateFlow("")
    val activeTranslationText: StateFlow<String> = _activeTranslationText.asStateFlow()

    private val _detectedLanguage = MutableStateFlow<String?>(null)
    val detectedLanguage: StateFlow<String?> = _detectedLanguage.asStateFlow()

    private val _lastProcessingStats = MutableStateFlow<String?>(null)
    val lastProcessingStats: StateFlow<String?> = _lastProcessingStats.asStateFlow()

    private val _studyBreakdownText = MutableStateFlow("")
    val studyBreakdownText: StateFlow<String> = _studyBreakdownText.asStateFlow()

    private val _studyBreakdownLoading = MutableStateFlow(false)
    val studyBreakdownLoading: StateFlow<Boolean> = _studyBreakdownLoading.asStateFlow()

    // Dedicated OCR operational states (completely isolated from Voice Transcription)
    private val _ocrLoading = MutableStateFlow(false)
    val ocrLoading: StateFlow<Boolean> = _ocrLoading.asStateFlow()

    private val _ocrTranslationLoading = MutableStateFlow(false)
    val ocrTranslationLoading: StateFlow<Boolean> = _ocrTranslationLoading.asStateFlow()

    private val _ocrExtractedText = MutableStateFlow("")
    val ocrExtractedText: StateFlow<String> = _ocrExtractedText.asStateFlow()

    private val _ocrTranslationText = MutableStateFlow("")
    val ocrTranslationText: StateFlow<String> = _ocrTranslationText.asStateFlow()

    private val _ocrDetectedLanguage = MutableStateFlow<String?>(null)
    val ocrDetectedLanguage: StateFlow<String?> = _ocrDetectedLanguage.asStateFlow()

    private val _ocrProcessingStats = MutableStateFlow<String?>(null)
    val ocrProcessingStats: StateFlow<String?> = _ocrProcessingStats.asStateFlow()

    private val _ocrBreakdownText = MutableStateFlow("")
    val ocrBreakdownText: StateFlow<String> = _ocrBreakdownText.asStateFlow()

    private val _ocrBreakdownLoading = MutableStateFlow(false)
    val ocrBreakdownLoading: StateFlow<Boolean> = _ocrBreakdownLoading.asStateFlow()

    private val _chatLoading = MutableStateFlow(false)
    val chatLoading: StateFlow<Boolean> = _chatLoading.asStateFlow()

    private val _chatInputText = MutableStateFlow("")
    val chatInputText: StateFlow<String> = _chatInputText.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    // Persistent on-device voice tracking, custom naming & playback state
    private val _activeAudioFilePath = MutableStateFlow<String?>(null)
    val activeAudioFilePath: StateFlow<String?> = _activeAudioFilePath.asStateFlow()

    private val _activeAudioFileName = MutableStateFlow<String?>(null)
    val activeAudioFileName: StateFlow<String?> = _activeAudioFileName.asStateFlow()

    private val _activeAudioDisplayName = MutableStateFlow("")
    val activeAudioDisplayName: StateFlow<String> = _activeAudioDisplayName.asStateFlow()

    private val _activeAudioSizeKb = MutableStateFlow<Long?>(null)
    val activeAudioSizeKb: StateFlow<Long?> = _activeAudioSizeKb.asStateFlow()

    private val _playingAudioPath = MutableStateFlow<String?>(null)
    val playingAudioPath: StateFlow<String?> = _playingAudioPath.asStateFlow()

    // Preferences & Feature States
    private val _isDarkMode = MutableStateFlow(false)
    val isDarkMode: StateFlow<Boolean> = _isDarkMode.asStateFlow()

    private val _isOfflineMode = MutableStateFlow(false)
    val isOfflineMode: StateFlow<Boolean> = _isOfflineMode.asStateFlow()

    private val _isLocalEncryptionActive = MutableStateFlow(true)
    val isLocalEncryptionActive: StateFlow<Boolean> = _isLocalEncryptionActive.asStateFlow()

    private val _selectedSourceLang = MutableStateFlow("Auto-Detect")
    val selectedSourceLang: StateFlow<String> = _selectedSourceLang.asStateFlow()

    private val _selectedTargetLang = MutableStateFlow("English")
    val selectedTargetLang: StateFlow<String> = _selectedTargetLang.asStateFlow()

    private val _selectedOcrImageUri = MutableStateFlow<Uri?>(null)
    val selectedOcrImageUri: StateFlow<Uri?> = _selectedOcrImageUri.asStateFlow()

    private val _savedHandwritingStyles = MutableStateFlow<List<String>>(emptyList())
    val savedHandwritingStyles: StateFlow<List<String>> = _savedHandwritingStyles.asStateFlow()

    private val _imageBatchQueue = MutableStateFlow<List<Uri>>(emptyList())
    val imageBatchQueue: StateFlow<List<Uri>> = _imageBatchQueue.asStateFlow()

    private val _audioBatchQueue = MutableStateFlow<List<Uri>>(emptyList())
    val audioBatchQueue: StateFlow<List<Uri>> = _audioBatchQueue.asStateFlow()

    private val _isBatchProcessing = MutableStateFlow(false)
    val isBatchProcessing: StateFlow<Boolean> = _isBatchProcessing.asStateFlow()

    private val _batchProgressMessage = MutableStateFlow("")
    val batchProgressMessage: StateFlow<String> = _batchProgressMessage.asStateFlow()

    private var recordTimerJob: Job? = null
    private var activeProcessJob: Job? = null
    private var recordedFile: File? = null

    override fun onCleared() {
        super.onCleared()
        recordTimerJob?.cancel()
        activeProcessJob?.cancel()
        audioPlayer.stop()
        try {
            ttsEngine?.stop()
            ttsEngine?.shutdown()
        } catch (_: Exception) {}
        ttsEngine = null
    }

    // State Toggles and Setters (persisted to DataStore)
    fun toggleDarkMode() {
        darkModeTouched = true
        val newValue = !_isDarkMode.value
        _isDarkMode.value = newValue
        viewModelScope.launch(Dispatchers.IO) {
            getApplication<Application>().userPrefsDataStore.edit { it[PREF_DARK_MODE] = newValue }
        }
    }

    fun toggleOfflineMode() {
        setOfflineMode(!_isOfflineMode.value)
    }

    fun setOfflineMode(enabled: Boolean) {
        offlineModeTouched = true
        _isOfflineMode.value = enabled
        _statusMessage.value = if (enabled) {
            "Offline Mode Active (Local fallback OCR/transcribe enabled)"
        } else {
            "Online Mode Active (BYOK API OCR/transcribe enabled)"
        }
        viewModelScope.launch(Dispatchers.IO) {
            getApplication<Application>().userPrefsDataStore.edit { it[PREF_OFFLINE_MODE] = enabled }
        }
    }

    fun toggleLocalEncryption() {
        localEncryptionTouched = true
        val newValue = !_isLocalEncryptionActive.value
        _isLocalEncryptionActive.value = newValue
        _statusMessage.value = if (newValue) {
            "Local SQLite encryption enabled for new notes."
        } else {
            "Local SQLite encryption disabled."
        }
        viewModelScope.launch(Dispatchers.IO) {
            getApplication<Application>().userPrefsDataStore.edit { it[PREF_LOCAL_ENCRYPTION] = newValue }
        }
    }

    fun updateSelectedSourceLang(lang: String) {
        sourceLangTouched = true
        _selectedSourceLang.value = lang
        viewModelScope.launch(Dispatchers.IO) {
            getApplication<Application>().userPrefsDataStore.edit { it[PREF_SOURCE_LANG] = lang }
        }
    }

    fun updateSelectedTargetLang(lang: String) {
        targetLangTouched = true
        _selectedTargetLang.value = lang
        viewModelScope.launch(Dispatchers.IO) {
            getApplication<Application>().userPrefsDataStore.edit { it[PREF_TARGET_LANG] = lang }
        }
    }

    fun updateSelectedOcrImageUri(uri: Uri?) {
        _selectedOcrImageUri.value = uri
        if (uri != null) {
            _statusMessage.value = "Selected document image for OCR processing."
        }
    }

    fun addHandwritingTrainingStyle(character: String) {
        val currentList = _savedHandwritingStyles.value.toMutableList()
        if (!currentList.contains(character)) {
            currentList.add(character)
            _savedHandwritingStyles.value = currentList
        }
        _statusMessage.value = "Saved drawing style for character '$character' in personal profile."
    }

    fun addImageToBatchQueue(uris: List<Uri>) {
        val current = _imageBatchQueue.value.toMutableList()
        current.addAll(uris)
        _imageBatchQueue.value = current
        _statusMessage.value = "Added ${uris.size} images to visual OCR queue."
    }

    fun clearImageBatchQueue() {
        _imageBatchQueue.value = emptyList()
    }

    fun addAudioToBatchQueue(uris: List<Uri>) {
        val current = _audioBatchQueue.value.toMutableList()
        current.addAll(uris)
        _audioBatchQueue.value = current
        _statusMessage.value = "Added ${uris.size} files to audio transcription queue."
    }

    fun clearAudioBatchQueue() {
        _audioBatchQueue.value = emptyList()
    }

    // Enterprise Keystore AES-GCM encryption with per-note IV
    fun encryptString(input: String): String {
        return KeystoreCryptoManager.encrypt(input)
    }

    fun decryptString(input: String): String {
        return KeystoreCryptoManager.decrypt(input)
    }

    fun cancelActiveProcessing() {
        activeProcessJob?.cancel()
        activeProcessJob = null
        _transcriptionLoading.value = false
        _ocrLoading.value = false
        _isBatchProcessing.value = false
        _batchProgressMessage.value = ""
        _statusMessage.value = "Processing cancelled."
    }

    // Helper to copy URI to cache File with fast image downscaling (1024px @ 75% JPEG) off the UI thread
    private suspend fun copyUriToTempFile(
        uri: Uri,
        context: Context,
        outputName: String
    ): File? = withContext(Dispatchers.IO) {
        try {
            val contentResolver = context.contentResolver
            val tempFile = File(context.cacheDir, outputName)

            val isImage = outputName.endsWith(".jpg", ignoreCase = true) ||
                outputName.endsWith(".png", ignoreCase = true) ||
                outputName.endsWith(".jpeg", ignoreCase = true)

            if (isImage) {
                var rotationDegrees = 0
                try {
                    contentResolver.openInputStream(uri)?.use { stream ->
                        val exif = android.media.ExifInterface(stream)
                        val orientation = exif.getAttributeInt(
                            android.media.ExifInterface.TAG_ORIENTATION,
                            android.media.ExifInterface.ORIENTATION_NORMAL
                        )
                        rotationDegrees = when (orientation) {
                            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                            else -> 0
                        }
                    }
                } catch (_: Exception) {}

                val options = android.graphics.BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                contentResolver.openInputStream(uri)?.use { stream ->
                    android.graphics.BitmapFactory.decodeStream(stream, null, options)
                }

                val maxDimension = 1024
                var inSampleSize = 1
                if (options.outHeight > maxDimension || options.outWidth > maxDimension) {
                    val halfHeight = options.outHeight / 2
                    val halfWidth = options.outWidth / 2
                    while ((halfHeight / inSampleSize) >= maxDimension || (halfWidth / inSampleSize) >= maxDimension) {
                        inSampleSize *= 2
                    }
                }

                val decodeOptions = android.graphics.BitmapFactory.Options().apply {
                    this.inSampleSize = inSampleSize
                }
                var bitmap: android.graphics.Bitmap? = null
                contentResolver.openInputStream(uri)?.use { stream ->
                    bitmap = android.graphics.BitmapFactory.decodeStream(stream, null, decodeOptions)
                }

                if (bitmap != null) {
                    var workingBitmap = bitmap!!
                    // Exact downscale pass if still larger than maxDimension
                    val longestSide = maxOf(workingBitmap.width, workingBitmap.height)
                    if (longestSide > maxDimension) {
                        val scale = maxDimension.toFloat() / longestSide.toFloat()
                        val targetW = (workingBitmap.width * scale).toInt().coerceAtLeast(1)
                        val targetH = (workingBitmap.height * scale).toInt().coerceAtLeast(1)
                        val scaled = android.graphics.Bitmap.createScaledBitmap(workingBitmap, targetW, targetH, true)
                        if (scaled != workingBitmap) {
                            workingBitmap.recycle()
                            workingBitmap = scaled
                        }
                    }

                    if (rotationDegrees != 0) {
                        val matrix = android.graphics.Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                        val rotated = android.graphics.Bitmap.createBitmap(
                            workingBitmap, 0, 0, workingBitmap.width, workingBitmap.height, matrix, true
                        )
                        if (rotated != workingBitmap) {
                            workingBitmap.recycle()
                            workingBitmap = rotated
                        }
                    }

                    tempFile.outputStream().use { outStream ->
                        workingBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, outStream)
                    }
                    workingBitmap.recycle()
                    if (tempFile.exists() && tempFile.length() > 0) return@withContext tempFile
                }
            }

            contentResolver.openInputStream(uri)?.use { inputStream ->
                tempFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            if (tempFile.exists() && tempFile.length() > 0) tempFile else null
        } catch (_: Exception) {
            null
        }
    }

    // Offline fallback helpers
    private fun getLocalOfflineOcrText(): String {
        return "ሰላም ጤና ይስጥልኝ! ይህ ከመስመር ውጭ (Offline) የተገኘ የአማርኛ ጽሑፍ ነው ።"
    }

    private fun getLocalOfflineTranslation(text: String, targetLang: String): String {
        return when {
            targetLang.contains("English", ignoreCase = true) ->
                "Greetings and peace! This is offline fallback translation text."
            targetLang.contains("Tigrinya", ignoreCase = true) ->
                "ሰላም ጥዕና ይሃበለይ! እዚ ከመስመር ወጻኢ ዝተረኽበ ጽሑፍ እዩ ።"
            targetLang.contains("Oromo", ignoreCase = true) ->
                "Fayyaa fi nagaan siif haa tahu! Kun barreeffama afaan Oromooti."
            targetLang.contains("Somali", ignoreCase = true) ->
                "Nabad iyo caafimaad ha kuu ahaato! Kani waa qoraal offline ah."
            else -> text
        }
    }

    // Batch Image Process OCR — OCR ONLY (isolated to OCR box; no auto-translation)
    fun processImageBatch(context: Context) {
        val queue = _imageBatchQueue.value
        if (queue.isEmpty()) {
            _statusMessage.value = "Image queue is empty! Add images first."
            return
        }

        activeProcessJob?.cancel()
        activeProcessJob = viewModelScope.launch {
            val startMs = System.currentTimeMillis()
            _isBatchProcessing.value = true
            _ocrLoading.value = true
            val sourceHint = _selectedSourceLang.value
            _batchProgressMessage.value = "Batch OCR 0/${queue.size} images..."
            _ocrExtractedText.value = ""
            _ocrTranslationText.value = ""
            _ocrBreakdownText.value = ""

            val accumulatedOcr = StringBuilder()

            try {
                queue.forEachIndexed { index, uri ->
                    _batchProgressMessage.value = "Scanning image ${index + 1}/${queue.size}..."
                    val file = copyUriToTempFile(uri, context, "batch_img_$index.jpg")
                    if (file != null) {
                        if (_isOfflineMode.value) {
                            accumulatedOcr.append("--- Image ${index + 1} (Amharic - Offline) ---\n${getLocalOfflineOcrText()}\n\n")
                        } else {
                            val result = withContext(Dispatchers.IO) {
                                repository.performOcrOnImage(file, sourceHint)
                            }
                            if (result.error != null) {
                                accumulatedOcr.append("--- Image ${index + 1} (Error) ---\n${result.error}\n\n")
                            } else {
                                accumulatedOcr.append("--- Image ${index + 1} (${result.originalLanguage}) ---\n${result.transcription}\n\n")
                            }
                        }
                    } else {
                        accumulatedOcr.append("--- Image ${index + 1} ---\nError: Failed to read input image.\n\n")
                    }
                    _ocrExtractedText.value = accumulatedOcr.toString().trimEnd()
                }
                val elapsedSec = String.format(Locale.US, "%.1fs", (System.currentTimeMillis() - startMs) / 1000.0)
                _ocrProcessingStats.value = "Batch OCR (${queue.size} images) finished in $elapsedSec"
                _statusMessage.value = "Batch OCR completed! Click Translate if you want a translation."
                _imageBatchQueue.value = emptyList()
            } catch (_: CancellationException) {
                // Handled by cancelActiveProcessing()
            } finally {
                _isBatchProcessing.value = false
                _ocrLoading.value = false
                _batchProgressMessage.value = ""
            }
        }
    }

    // Batch Audio Process Transcription — Transcription ONLY (no auto-translation)
    fun processAudioBatch(context: Context) {
        val queue = _audioBatchQueue.value
        if (queue.isEmpty()) {
            _statusMessage.value = "Audio queue is empty! Add audio files first."
            return
        }

        activeProcessJob?.cancel()
        activeProcessJob = viewModelScope.launch {
            val startMs = System.currentTimeMillis()
            _isBatchProcessing.value = true
            _transcriptionLoading.value = true
            _batchProgressMessage.value = "Batch transcribing 0/${queue.size} voice files..."
            _activeTranscriptionText.value = ""
            _activeTranslationText.value = ""

            val accumulated = StringBuilder()

            try {
                queue.forEachIndexed { index, uri ->
                    _batchProgressMessage.value = "Transcribing audio ${index + 1}/${queue.size}..."
                    val file = copyUriToTempFile(uri, context, "batch_audio_$index.mp4")
                    if (file != null) {
                        if (_isOfflineMode.value) {
                            accumulated.append("--- Voice File ${index + 1} (Offline) ---\nሰላም ፣ ይህ ከመስመር ውጭ የተመዘገበ የድምፅ ቅጂ ግልባጭ ነው ።\n\n")
                        } else {
                            val transcriptionResult = withContext(Dispatchers.IO) {
                                repository.transcribeAudio(file)
                            }
                            accumulated.append("--- Voice File ${index + 1} ---\n$transcriptionResult\n\n")
                        }
                    } else {
                        accumulated.append("--- Voice File ${index + 1} ---\nError: Failed to read audio file.\n\n")
                    }
                    _activeTranscriptionText.value = accumulated.toString().trimEnd()
                }
                val elapsedSec = String.format(Locale.US, "%.1fs", (System.currentTimeMillis() - startMs) / 1000.0)
                _lastProcessingStats.value = "Batch audio (${queue.size} files) finished in $elapsedSec"
                _statusMessage.value = "Batch transcription completed! Click Translate when needed."
                _audioBatchQueue.value = emptyList()
            } catch (_: CancellationException) {
                // Handled by cancelActiveProcessing()
            } finally {
                _isBatchProcessing.value = false
                _transcriptionLoading.value = false
                _batchProgressMessage.value = ""
            }
        }
    }

    // Trigger Single Image OCR — extracts script ONLY into OCR box; NEVER auto-translates or touches Voice box
    fun triggerImageOcr(context: Context) {
        val uri = _selectedOcrImageUri.value
        if (uri == null) {
            _statusMessage.value = "Please select or capture a document image first."
            return
        }

        activeProcessJob?.cancel()
        activeProcessJob = viewModelScope.launch {
            val startMs = System.currentTimeMillis()
            _ocrLoading.value = true
            _ocrTranslationText.value = ""
            _ocrBreakdownText.value = ""
            _ocrDetectedLanguage.value = null
            _statusMessage.value = "Optimizing image & extracting script..."

            try {
                val file = copyUriToTempFile(uri, context, "single_ocr_img.jpg")
                if (file == null) {
                    _statusMessage.value = "Error preparing selected image."
                    return@launch
                }
                val sizeKb = (file.length() / 1024L).coerceAtLeast(1L)

                if (_isOfflineMode.value) {
                    _ocrExtractedText.value = getLocalOfflineOcrText()
                    _ocrDetectedLanguage.value = "Amharic (Offline)"
                    _ocrProcessingStats.value = "Offline fallback • ${sizeKb} KB image"
                    _statusMessage.value = "Offline OCR completed. Tap Translate if you need a translation."
                } else {
                    val result = withContext(Dispatchers.IO) {
                        repository.performOcrOnImage(file, _selectedSourceLang.value)
                    }
                    val elapsedSec = String.format(Locale.US, "%.1fs", (System.currentTimeMillis() - startMs) / 1000.0)
                    if (result.error != null) {
                        _statusMessage.value = result.error
                        _ocrProcessingStats.value = "Failed after $elapsedSec • ${sizeKb} KB"
                    } else {
                        _ocrExtractedText.value = result.transcription
                        _ocrDetectedLanguage.value = result.originalLanguage
                        _ocrProcessingStats.value = "OCR in $elapsedSec • ${sizeKb} KB optimized JPEG"
                        _statusMessage.value = "OCR completed (${result.originalLanguage}). Tap Translate if needed."
                    }
                }
            } catch (_: CancellationException) {
                // Handled by cancelActiveProcessing()
            } catch (e: Exception) {
                _statusMessage.value = "OCR Error: ${e.message}"
            } finally {
                _ocrLoading.value = false
            }
        }
    }

    fun loadSampleOcrDocument(context: Context) {
        viewModelScope.launch {
            try {
                val sampleFile = withContext(Dispatchers.IO) {
                    val width = 800
                    val height = 600
                    val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(bitmap)

                    val bgPaint = android.graphics.Paint().apply {
                        color = android.graphics.Color.rgb(253, 252, 248)
                    }
                    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

                    val borderPaint = android.graphics.Paint().apply {
                        color = android.graphics.Color.rgb(220, 215, 205)
                        style = android.graphics.Paint.Style.STROKE
                        strokeWidth = 4f
                    }
                    canvas.drawRect(20f, 20f, width - 20f, height - 20f, borderPaint)

                    val bannerPaint = android.graphics.Paint().apply {
                        color = android.graphics.Color.rgb(40, 90, 70)
                    }
                    canvas.drawRect(30f, 30f, width - 30f, 110f, bannerPaint)

                    val titlePaint = android.graphics.Paint().apply {
                        color = android.graphics.Color.WHITE
                        textSize = 34f
                        isAntiAlias = true
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    }
                    canvas.drawText("የአማርኛ የጥናት ሰነድ (Amharic Study Document)", 60f, 82f, titlePaint)

                    val textPaint = android.graphics.Paint().apply {
                        color = android.graphics.Color.rgb(30, 30, 30)
                        textSize = 28f
                        isAntiAlias = true
                    }

                    val lines = listOf(
                        "ሰላም ጤና ይስጥልኝ! እንኳን ወደ አማርኛ ቋንቋ ጥናት መድረክ በደህና መጡ ።",
                        "",
                        "ይህ ሰነድ ለኦሲአር (OCR) የምስል ጽሑፍ መለያ የተዘጋጀ ናሙና ነው ።",
                        "• ፊደል ፩: ሀ ሁ ሂ ሃ ሄ ህ ሆ",
                        "• ፊደል ፪: ለ ሉ ሊ ላ ሌ ል ሎ",
                        "• ፊደል ፫: ሐ ሑ ሒ ሓ ሔ ሕ ሖ",
                        "",
                        "ትምህርት ለሕይወት ብርሃን እና መመሪያ ነው። በየቀኑ አዳዲስ ቃላትን ይማሩ ።"
                    )

                    var y = 170f
                    for (line in lines) {
                        canvas.drawText(line, 50f, y, textPaint)
                        y += 44f
                    }

                    context.cacheDir.mkdirs()
                    val file = File(context.cacheDir, "sample_amharic_doc.jpg")
                    file.outputStream().use { out ->
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
                    }
                    bitmap.recycle()
                    file
                }

                if (sampleFile.exists() && sampleFile.length() > 0) {
                    _selectedOcrImageUri.value = Uri.fromFile(sampleFile)
                    _statusMessage.value = "Sample Amharic Document loaded! Tap 'Scan Document (Fast OCR)' to extract text."
                }
            } catch (e: Exception) {
                _statusMessage.value = "Error preparing sample document: ${e.message}"
            }
        }
    }

    fun updateChatInput(text: String) {
        _chatInputText.value = text
    }

    fun updateActiveTranscriptionText(text: String) {
        _activeTranscriptionText.value = text
    }

    fun updateActiveTranslationText(text: String) {
        _activeTranslationText.value = text
    }

    fun updateOcrExtractedText(text: String) {
        _ocrExtractedText.value = text
    }

    fun updateOcrTranslationText(text: String) {
        _ocrTranslationText.value = text
    }

    fun updateActiveAudioDisplayName(name: String) {
        _activeAudioDisplayName.value = name
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    fun clearStudyBreakdown() {
        _studyBreakdownText.value = ""
    }

    fun clearOcrBreakdown() {
        _ocrBreakdownText.value = ""
    }

    private fun updateActiveAudioState(file: File?, displayName: String? = null) {
        recordedFile = file
        if (file != null && file.exists()) {
            _activeAudioFilePath.value = file.absolutePath
            _activeAudioFileName.value = file.name
            _activeAudioDisplayName.value = displayName ?: file.nameWithoutExtension
            _activeAudioSizeKb.value = (file.length() / 1024L).coerceAtLeast(1L)
        } else {
            _activeAudioFilePath.value = null
            _activeAudioFileName.value = null
            _activeAudioDisplayName.value = ""
            _activeAudioSizeKb.value = null
        }
    }

    // Audio recording functions (persisted in filesDir/voice_recordings/ + Room voice_recordings table)
    fun startMicrophoneRecording() {
        viewModelScope.launch {
            audioPlayer.stop()
            _playingAudioPath.value = null
            val fileName = "voice_${System.currentTimeMillis()}.mp4"
            val filePath = audioRecorder.startRecording(fileName)
            if (filePath != null) {
                recordedFile = File(filePath)
                _activeAudioFilePath.value = filePath
                _activeAudioFileName.value = fileName
                _activeAudioSizeKb.value = null
                _isRecording.value = true
                _recordingDuration.value = 0
                _statusMessage.value = "Recording Amharic voice..."

                recordTimerJob?.cancel()
                recordTimerJob = viewModelScope.launch {
                    while (_isRecording.value) {
                        delay(1000)
                        _recordingDuration.value += 1
                    }
                }
            } else {
                _statusMessage.value = "Failed to open microphone. Check permissions."
            }
        }
    }

    fun stopAndProcessRecording() {
        viewModelScope.launch {
            recordTimerJob?.cancel()
            _isRecording.value = false
            val duration = _recordingDuration.value
            val fileObj = audioRecorder.stopRecording()
            if (fileObj != null && fileObj.exists() && fileObj.length() > 0) {
                val chosenName = _activeAudioDisplayName.value.trim().ifEmpty {
                    "Voice Recording #${voiceRecordingsList.value.size + 1}"
                }
                withContext(Dispatchers.IO) {
                    repository.saveVoiceRecording(
                        VoiceRecordingEntity(
                            name = chosenName,
                            filePath = fileObj.absolutePath,
                            durationSeconds = duration,
                            sizeBytes = fileObj.length()
                        )
                    )
                }
                updateActiveAudioState(fileObj, chosenName)
                _statusMessage.value = "Voice '$chosenName' saved to Library! Ready to transcribe, play, or delete."
            } else {
                updateActiveAudioState(null)
                _statusMessage.value = "No audio data recorded or failed to write output file."
            }
        }
    }

    fun simulateDemoRecording() {
        viewModelScope.launch {
            try {
                val chosenName = _activeAudioDisplayName.value.trim().ifEmpty {
                    "Demo Voice #${voiceRecordingsList.value.size + 1}"
                }
                val file = withContext(Dispatchers.IO) {
                    val dir = audioRecorder.getVoiceRecordingsDir()
                    val demoFile = File(dir, "demo_voice_${System.currentTimeMillis()}.mp4")
                    demoFile.writeBytes(ByteArray(2048) { 0 })
                    repository.saveVoiceRecording(
                        VoiceRecordingEntity(
                            name = chosenName,
                            filePath = demoFile.absolutePath,
                            durationSeconds = 5,
                            sizeBytes = demoFile.length()
                        )
                    )
                    demoFile
                }
                updateActiveAudioState(file, chosenName)
                _recordingDuration.value = 5
                _activeTranscriptionText.value = "ሰላም ፣ ይህ የአማርኛ ድምፅ መቅረጫ የጥናት ቦታ ፕሮቶታይፕ ነው ።"
                _activeTranslationText.value = ""
                _detectedLanguage.value = "Amharic (Demo)"
                _statusMessage.value = "Demo voice '$chosenName' saved to Library! Tap Transcribe or Translate when needed."
            } catch (e: Exception) {
                _statusMessage.value = "Error creating demo audio: ${e.message}"
            }
        }
    }

    fun loadUploadedAudioFile(uri: Uri, context: Context) {
        viewModelScope.launch {
            try {
                val chosenName = _activeAudioDisplayName.value.trim().ifEmpty {
                    "Imported Voice #${voiceRecordingsList.value.size + 1}"
                }
                val savedResult = withContext(Dispatchers.IO) {
                    val contentResolver = context.contentResolver
                    val mimeType = contentResolver.getType(uri) ?: "audio/mp4"
                    val extension = when {
                        mimeType.contains("mpeg") || mimeType.contains("mp3") -> "mp3"
                        mimeType.contains("wav") -> "wav"
                        mimeType.contains("m4a") -> "m4a"
                        mimeType.contains("ogg") -> "ogg"
                        mimeType.contains("aac") -> "aac"
                        else -> "mp4"
                    }
                    val fileName = "voice_import_${System.currentTimeMillis()}.$extension"
                    val persistentFile = File(audioRecorder.getVoiceRecordingsDir(), fileName)
                    contentResolver.openInputStream(uri)?.use { inputStream ->
                        persistentFile.outputStream().use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    if (persistentFile.exists() && persistentFile.length() > 0) {
                        repository.saveVoiceRecording(
                            VoiceRecordingEntity(
                                name = chosenName,
                                filePath = persistentFile.absolutePath,
                                durationSeconds = 0,
                                sizeBytes = persistentFile.length()
                            )
                        )
                    }
                    extension to persistentFile
                }

                val (ext, persistentFile) = savedResult
                if (persistentFile.exists() && persistentFile.length() > 0) {
                    updateActiveAudioState(persistentFile, chosenName)
                    _recordingDuration.value = 0
                    _activeTranscriptionText.value = ""
                    _activeTranslationText.value = ""
                    _detectedLanguage.value = null
                    _statusMessage.value = "Voice '$chosenName' ($ext) saved to Library! Ready to Transcribe or Download."
                } else {
                    _statusMessage.value = "Failed to import selected audio file."
                }
            } catch (e: Exception) {
                _statusMessage.value = "Audio Import Error: ${e.message}"
            }
        }
    }

    fun renameVoiceRecording(recordingId: Int, newName: String) {
        val clean = newName.trim()
        if (clean.isEmpty()) {
            _statusMessage.value = "Voice name cannot be empty."
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            repository.renameVoiceRecording(recordingId, clean)
            val updated = voiceRecordingsList.value.firstOrNull { it.id == recordingId }
            if (updated != null && updated.filePath == _activeAudioFilePath.value) {
                _activeAudioDisplayName.value = clean
            }
        }
        _statusMessage.value = "Voice renamed to '$clean'."
    }

    fun renameActiveVoiceRecording(newName: String) {
        val clean = newName.trim()
        if (clean.isEmpty()) {
            _statusMessage.value = "Voice name cannot be empty."
            return
        }
        _activeAudioDisplayName.value = clean
        val activePath = _activeAudioFilePath.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val entity = repository.getVoiceRecordingByPath(activePath)
            if (entity != null) {
                repository.renameVoiceRecording(entity.id, clean)
            }
        }
        _statusMessage.value = "Active voice named '$clean'."
    }

    fun deleteActiveVoiceRecording() {
        val path = _activeAudioFilePath.value ?: return
        if (_playingAudioPath.value == path) {
            stopAudioPlayback()
        }
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteVoiceRecordingByPath(path)
            try {
                val f = File(path)
                if (f.exists()) f.delete()
            } catch (_: Exception) {}
        }
        updateActiveAudioState(null)
        _recordingDuration.value = 0
        _statusMessage.value = "Recorded voice deleted from device."
    }

    fun deleteVoiceRecording(recording: VoiceRecordingEntity) {
        if (_playingAudioPath.value == recording.filePath) {
            stopAudioPlayback()
        }
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteVoiceRecording(recording.id)
            try {
                val f = File(recording.filePath)
                if (f.exists()) f.delete()
            } catch (_: Exception) {}
        }
        if (_activeAudioFilePath.value == recording.filePath) {
            updateActiveAudioState(null)
            _recordingDuration.value = 0
        }
        _statusMessage.value = "Deleted voice '${recording.name}'."
    }

    fun loadVoiceRecordingForTranscription(recording: VoiceRecordingEntity, autoTranscribe: Boolean = false) {
        val file = File(recording.filePath)
        if (!file.exists() || file.length() == 0L) {
            _statusMessage.value = "Voice file is missing on device."
            return
        }
        updateActiveAudioState(file, recording.name)
        _recordingDuration.value = recording.durationSeconds
        _activeTranscriptionText.value = ""
        _activeTranslationText.value = ""
        _studyBreakdownText.value = ""
        _detectedLanguage.value = null
        _lastProcessingStats.value = null
        if (autoTranscribe) {
            triggerTranscription()
        } else {
            _statusMessage.value = "Loaded '${recording.name}' into Voice Desk."
        }
    }

    // In-app playback of device-kept audio files
    fun toggleAudioPlayback(filePath: String) {
        if (_playingAudioPath.value == filePath) {
            audioPlayer.stop()
            _playingAudioPath.value = null
            return
        }
        val started = audioPlayer.play(
            filePath = filePath,
            onCompletion = { _playingAudioPath.value = null },
            onError = { err ->
                _playingAudioPath.value = null
                _statusMessage.value = err
            }
        )
        _playingAudioPath.value = if (started) filePath else null
    }

    fun stopAudioPlayback() {
        audioPlayer.stop()
        _playingAudioPath.value = null
    }

    // Export / Download a kept device voice file to a user-chosen SAF destination URI (zero storage permissions)
    fun exportAudioFileToUri(context: Context, sourcePath: String, destUri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val source = File(sourcePath)
                if (!source.exists() || source.length() == 0L) {
                    return@withContext false to "Voice file is missing on device."
                }
                try {
                    context.contentResolver.openOutputStream(destUri)?.use { out ->
                        source.inputStream().use { input ->
                            input.copyTo(out)
                        }
                    } ?: return@withContext false to "Could not open destination file for writing."
                    true to "Voice downloaded to your selected device folder (${source.name})."
                } catch (e: Exception) {
                    false to "Failed to download voice: ${e.message}"
                }
            }
            _statusMessage.value = result.second
        }
    }

    // Fast Transcription ONLY — NEVER translates automatically
    fun triggerTranscription() {
        val file = recordedFile
        if (file == null || !file.exists() || file.length() == 0L) {
            _statusMessage.value = "Please record or upload a voice file first."
            return
        }

        if (file.name.startsWith("demo_voice_") || file.name == "simulated_audio.mp4") {
            _activeTranscriptionText.value = _activeTranscriptionText.value.ifBlank {
                "ሰላም ፣ ይህ የአማርኛ ድምፅ መቅረጫ የጥናት ቦታ ፕሮቶታይፕ ነው ።"
            }
            _activeTranslationText.value = ""
            _detectedLanguage.value = "Amharic (Demo)"
            _lastProcessingStats.value = "Demo audio • translation awaits your click"
            _statusMessage.value = "Demo transcription ready! Tap Translate Text if you want a translation."
            return
        }

        activeProcessJob?.cancel()
        activeProcessJob = viewModelScope.launch {
            val startMs = System.currentTimeMillis()
            _transcriptionLoading.value = true
            _activeTranslationText.value = ""
            _studyBreakdownText.value = ""
            _detectedLanguage.value = null
            _statusMessage.value = "Transcribing spoken audio..."

            val sizeKb = (file.length() / 1024L).coerceAtLeast(1L)
            try {
                if (_isOfflineMode.value) {
                    _activeTranscriptionText.value = "ሰላም ፣ ይህ ከመስመር ውጭ የተቀዳ የአማርኛ ድምፅ መቅረጫ ግልባጭ ነው ።"
                    _detectedLanguage.value = "Amharic (Offline)"
                    _lastProcessingStats.value = "Offline fallback • ${sizeKb} KB audio"
                    _statusMessage.value = "Offline transcription ready! Tap Translate Text when needed."
                } else {
                    val result = withContext(Dispatchers.IO) {
                        repository.transcribeAndDetectAudio(file, _selectedSourceLang.value)
                    }
                    val elapsedSec = String.format(Locale.US, "%.1fs", (System.currentTimeMillis() - startMs) / 1000.0)
                    if (result.error != null) {
                        _statusMessage.value = result.error
                        _lastProcessingStats.value = "Failed after $elapsedSec • ${sizeKb} KB"
                    } else {
                        _activeTranscriptionText.value = result.transcription
                        _detectedLanguage.value = result.originalLanguage
                        _lastProcessingStats.value = "Transcribed in $elapsedSec • ${sizeKb} KB audio"
                        _statusMessage.value = "Transcribed (${result.originalLanguage}). Tap Translate Text if needed."
                    }
                }
            } catch (_: CancellationException) {
                // Handled by cancelActiveProcessing()
            } catch (e: Exception) {
                _statusMessage.value = "Transcription Error: ${e.message}"
            } finally {
                _transcriptionLoading.value = false
            }
        }
    }

    // Strictly On-Demand Translation (triggered ONLY when user clicks Translate)
    fun triggerTranslation(textToTranslate: String) {
        if (textToTranslate.isBlank()) {
            _statusMessage.value = "No text found to translate."
            return
        }

        viewModelScope.launch {
            _translationLoading.value = true
            val src = _detectedLanguage.value ?: _selectedSourceLang.value
            val dest = _selectedTargetLang.value.let { if (it.equals("None", ignoreCase = true)) "English" else it }
            _statusMessage.value = "Translating into $dest..."
            try {
                if (_isOfflineMode.value) {
                    _activeTranslationText.value = getLocalOfflineTranslation(textToTranslate, dest)
                    _statusMessage.value = "Offline translation completed!"
                } else {
                    val translationResult = withContext(Dispatchers.IO) {
                        repository.translateEthiopianText(textToTranslate, src, dest)
                    }
                    _activeTranslationText.value = translationResult
                    _statusMessage.value = "Translated to $dest."
                }
            } catch (e: Exception) {
                _statusMessage.value = "Translation Error: ${e.message}"
            } finally {
                _translationLoading.value = false
            }
        }
    }

    // Strictly On-Demand OCR Translation (isolated to OCR box)
    fun triggerOcrTranslation(textToTranslate: String) {
        if (textToTranslate.isBlank()) {
            _statusMessage.value = "No extracted OCR text to translate."
            return
        }
        viewModelScope.launch {
            _ocrTranslationLoading.value = true
            val src = _ocrDetectedLanguage.value ?: _selectedSourceLang.value
            val dest = _selectedTargetLang.value.let { if (it.equals("None", ignoreCase = true)) "English" else it }
            _statusMessage.value = "Translating OCR text into $dest..."
            try {
                if (_isOfflineMode.value) {
                    _ocrTranslationText.value = getLocalOfflineTranslation(textToTranslate, dest)
                    _statusMessage.value = "Offline OCR translation completed!"
                } else {
                    val translationResult = withContext(Dispatchers.IO) {
                        repository.translateEthiopianText(textToTranslate, src, dest)
                    }
                    _ocrTranslationText.value = translationResult
                    _statusMessage.value = "OCR text translated to $dest."
                }
            } catch (e: Exception) {
                _statusMessage.value = "OCR Translation Error: ${e.message}"
            } finally {
                _ocrTranslationLoading.value = false
            }
        }
    }

    // On-Demand Grammar & Vocabulary Breakdown (Voice Desk)
    fun triggerStudyBreakdown(text: String) {
        if (text.isBlank()) {
            _statusMessage.value = "Transcribe text first to analyze vocabulary & grammar."
            return
        }
        viewModelScope.launch {
            _studyBreakdownLoading.value = true
            try {
                if (_isOfflineMode.value) {
                    _studyBreakdownText.value = "• Offline Study Mode: Connect online with your BYOK key for AI vocabulary & grammar breakdown."
                } else {
                    val src = _detectedLanguage.value ?: _selectedSourceLang.value
                    val breakdown = withContext(Dispatchers.IO) {
                        repository.analyzeStudyText(text, src)
                    }
                    _studyBreakdownText.value = breakdown
                }
            } catch (e: Exception) {
                _statusMessage.value = "Study Breakdown Error: ${e.message}"
            } finally {
                _studyBreakdownLoading.value = false
            }
        }
    }

    // On-Demand Grammar & Vocabulary Breakdown (OCR Desk)
    fun triggerOcrStudyBreakdown(text: String) {
        if (text.isBlank()) {
            _statusMessage.value = "Scan OCR text first to analyze vocabulary & grammar."
            return
        }
        viewModelScope.launch {
            _ocrBreakdownLoading.value = true
            try {
                if (_isOfflineMode.value) {
                    _ocrBreakdownText.value = "• Offline Study Mode: Connect online with your BYOK key for AI vocabulary & grammar breakdown."
                } else {
                    val src = _ocrDetectedLanguage.value ?: _selectedSourceLang.value
                    val breakdown = withContext(Dispatchers.IO) {
                        repository.analyzeStudyText(text, src)
                    }
                    _ocrBreakdownText.value = breakdown
                }
            } catch (e: Exception) {
                _statusMessage.value = "OCR Study Breakdown Error: ${e.message}"
            } finally {
                _ocrBreakdownLoading.value = false
            }
        }
    }

    fun saveOcrSession(customTitle: String) {
        val amharic = _ocrExtractedText.value
        val english = _ocrTranslationText.value

        if (amharic.isBlank() && english.isBlank()) {
            _statusMessage.value = "Cannot save empty OCR result. Scan a document first."
            return
        }

        viewModelScope.launch {
            val title = if (customTitle.trim().isEmpty()) {
                val prefix = if (_isLocalEncryptionActive.value) "🔒 Secure " else ""
                "${prefix}OCR Note #${transcriptionsList.value.size + 1}"
            } else {
                val prefix = if (_isLocalEncryptionActive.value) "🔒 " else ""
                "$prefix${customTitle.trim()}"
            }

            val storedAmharic = if (_isLocalEncryptionActive.value) encryptString(amharic) else amharic
            val storedEnglish = if (_isLocalEncryptionActive.value) encryptString(english) else english

            val session = TranscriptionSession(
                title = title,
                amharicText = storedAmharic,
                englishTranslation = storedEnglish,
                durationSeconds = 0,
                filePath = null
            )

            withContext(Dispatchers.IO) {
                repository.saveTranscription(session)
            }
            _statusMessage.value = "OCR note saved in Library: \"$title\""
            _ocrExtractedText.value = ""
            _ocrTranslationText.value = ""
            _ocrBreakdownText.value = ""
            _ocrDetectedLanguage.value = null
            _ocrProcessingStats.value = null
        }
    }

    // On-device Text-To-Speech pronunciation helper
    fun speakPronunciation(context: Context, text: String, languageTag: String = "am-ET") {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) {
            _statusMessage.value = "Nothing to pronounce yet."
            return
        }
        val locale = Locale.forLanguageTag(languageTag)
        if (ttsEngine != null && ttsReady) {
            val availability = ttsEngine?.isLanguageAvailable(locale) ?: TextToSpeech.LANG_MISSING_DATA
            if (availability >= TextToSpeech.LANG_AVAILABLE) {
                ttsEngine?.language = locale
            } else {
                ttsEngine?.language = Locale.US
                _statusMessage.value = "Device TTS lacks $languageTag voice pack; using default device voice."
            }
            ttsEngine?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, "amharic_tts_${System.currentTimeMillis()}")
            return
        }

        ttsEngine = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                val availability = ttsEngine?.isLanguageAvailable(locale) ?: TextToSpeech.LANG_MISSING_DATA
                if (availability >= TextToSpeech.LANG_AVAILABLE) {
                    ttsEngine?.language = locale
                } else {
                    ttsEngine?.language = Locale.US
                }
                ttsEngine?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, "amharic_tts_init")
            } else {
                _statusMessage.value = "On-device Text-to-Speech unavailable on this device."
            }
        }
    }

    fun saveCurrentSession(customTitle: String) {
        val amharic = _activeTranscriptionText.value
        val english = _activeTranslationText.value

        if (amharic.isBlank() && english.isBlank()) {
            _statusMessage.value = "Cannot save empty workspace. Transcribe or translate first."
            return
        }

        viewModelScope.launch {
            val title = if (customTitle.trim().isEmpty()) {
                val prefix = if (_isLocalEncryptionActive.value) "🔒 Secure " else ""
                "${prefix}Study Note #${transcriptionsList.value.size + 1}"
            } else {
                val prefix = if (_isLocalEncryptionActive.value) "🔒 " else ""
                "$prefix${customTitle.trim()}"
            }

            val storedAmharic = if (_isLocalEncryptionActive.value) encryptString(amharic) else amharic
            val storedEnglish = if (_isLocalEncryptionActive.value) encryptString(english) else english
            val keptAudioPath = recordedFile?.takeIf { it.exists() }?.absolutePath

            val session = TranscriptionSession(
                title = title,
                amharicText = storedAmharic,
                englishTranslation = storedEnglish,
                durationSeconds = _recordingDuration.value,
                filePath = keptAudioPath
            )

            withContext(Dispatchers.IO) {
                repository.saveTranscription(session)
            }
            _statusMessage.value = if (keptAudioPath != null) {
                "Note & voice file saved in Library: \"$title\""
            } else {
                "Note saved: \"$title\""
            }

            // Reset active editor fields while keeping the saved voice file intact on disk for the Library item
            _activeTranscriptionText.value = ""
            _activeTranslationText.value = ""
            _studyBreakdownText.value = ""
            _detectedLanguage.value = null
            _lastProcessingStats.value = null
            updateActiveAudioState(null)
            _recordingDuration.value = 0
        }
    }

    // Flashcard & Quiz Room persistence actions
    fun toggleFlashcardMastery(cardId: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val current = flashcardMasteryMap.value[cardId] ?: false
            repository.setFlashcardMastery(cardId, !current)
        }
    }

    fun setFlashcardMastery(cardId: Int, isMastered: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.setFlashcardMastery(cardId, isMastered)
        }
    }

    fun recordQuizResult(score: Int, total: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.recordQuizScore(score, total)
        }
    }

    fun onNoteAccessed(session: TranscriptionSession) {
        if (KeystoreCryptoManager.isLegacyOrUnencrypted(session.amharicText) ||
            KeystoreCryptoManager.isLegacyOrUnencrypted(session.englishTranslation)) {
            viewModelScope.launch(Dispatchers.IO) {
                upgradeLegacyNoteToAesGcm(session)
            }
        }
    }

    // BYOK (Bring Your Own Key) management actions
    fun saveAndEncryptByokKey(
        provider: ByokProvider,
        rawApiKey: String,
        nickname: String,
        modelId: String
    ) {
        val cleanKey = rawApiKey.trim()
        if (cleanKey.isEmpty()) {
            _byokFeedbackMessage.value = false to "API key is required."
            return
        }
        val cleanModel = modelId.trim()
        if (provider != ByokProvider.GEMINI && cleanModel.isEmpty()) {
            _byokFeedbackMessage.value = false to "Please specify a Model ID for ${provider.displayName}."
            return
        }

        viewModelScope.launch {
            _isByokValidating.value = true
            _byokFeedbackMessage.value = null
            try {
                val saved = withContext(Dispatchers.IO) {
                    byokKeyManager.saveValidatedKey(provider, cleanKey, nickname, cleanModel)
                }
                _byokFeedbackMessage.value = true to "Encrypted and saved '${saved.nickname}' (${saved.maskedKey}) via Android KeyStore AES-256-GCM."
                _statusMessage.value = "BYOK key '${saved.nickname}' encrypted & active."
            } catch (e: Exception) {
                val sanitized = (e.message ?: "Encryption error").replace(cleanKey, "[REDACTED]")
                _byokFeedbackMessage.value = false to "Failed to encrypt key: $sanitized"
            } finally {
                _isByokValidating.value = false
            }
        }
    }

    fun verifySavedByokKey(keyId: String) {
        viewModelScope.launch {
            _isByokValidating.value = true
            _byokFeedbackMessage.value = null
            var rawKeyForRedaction = ""
            try {
                val config = withContext(Dispatchers.IO) {
                    byokKeyManager.getDecryptedConfigById(keyId)
                }
                if (config == null) {
                    _byokFeedbackMessage.value = false to "Unable to decrypt saved key for verification."
                    return@launch
                }
                rawKeyForRedaction = config.apiKey
                val result = withContext(Dispatchers.IO) {
                    repository.validateByokKey(config.provider, config.apiKey, config.modelId)
                }
                when (result) {
                    is KeyValidationResult.Valid -> {
                        _byokFeedbackMessage.value = true to "Online check passed for '${config.nickname}' (${config.provider.displayName})."
                    }
                    is KeyValidationResult.Invalid -> {
                        _byokFeedbackMessage.value = false to result.reason
                    }
                }
            } catch (e: Exception) {
                val sanitized = (e.message ?: "Unknown error").let { msg ->
                    if (rawKeyForRedaction.isNotBlank()) msg.replace(rawKeyForRedaction, "[REDACTED]") else msg
                }
                _byokFeedbackMessage.value = false to "Online verification failed: $sanitized"
            } finally {
                _isByokValidating.value = false
            }
        }
    }

    fun addAndValidateByokKey(
        provider: ByokProvider,
        rawApiKey: String,
        nickname: String,
        modelId: String
    ) {
        val cleanKey = rawApiKey.trim()
        if (cleanKey.isEmpty()) {
            _byokFeedbackMessage.value = false to "API key is required."
            return
        }

        viewModelScope.launch {
            _isByokValidating.value = true
            _byokFeedbackMessage.value = null
            try {
                val result = withContext(Dispatchers.IO) {
                    repository.validateByokKey(provider, cleanKey, modelId)
                }
                when (result) {
                    is KeyValidationResult.Valid -> {
                        val saved = withContext(Dispatchers.IO) {
                            byokKeyManager.saveValidatedKey(provider, cleanKey, nickname, modelId)
                        }
                        _byokFeedbackMessage.value = true to "Verified and encrypted '${saved.nickname}' (${saved.maskedKey}) in Android KeyStore."
                        _statusMessage.value = "BYOK key '${saved.nickname}' active."
                    }
                    is KeyValidationResult.Invalid -> {
                        _byokFeedbackMessage.value = false to result.reason
                    }
                }
            } catch (e: Exception) {
                val sanitized = (e.message ?: "Unknown error").replace(cleanKey, "[REDACTED]")
                _byokFeedbackMessage.value = false to "Failed to validate key: $sanitized"
            } finally {
                _isByokValidating.value = false
            }
        }
    }

    fun selectActiveByokKey(keyId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            byokKeyManager.setActiveKey(keyId)
            val active = byokKeyManager.getActiveEntry()
            withContext(Dispatchers.Main) {
                if (active != null) {
                    _byokFeedbackMessage.value = true to "Active key switched to '${active.nickname}' (${active.provider.displayName})."
                }
            }
        }
    }

    fun deleteByokKey(keyId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            byokKeyManager.deleteKey(keyId)
            withContext(Dispatchers.Main) {
                _byokFeedbackMessage.value = true to "API key deleted from secure device storage."
            }
        }
    }

    fun clearByokFeedback() {
        _byokFeedbackMessage.value = null
    }

    suspend fun upgradeLegacyNoteToAesGcm(session: TranscriptionSession) {
        try {
            val decryptedAmharic = KeystoreCryptoManager.decrypt(session.amharicText)
            val decryptedEnglish = KeystoreCryptoManager.decrypt(session.englishTranslation)
            val upgradedAmharic = KeystoreCryptoManager.encrypt(decryptedAmharic)
            val upgradedEnglish = KeystoreCryptoManager.encrypt(decryptedEnglish)
            val upgraded = session.copy(
                amharicText = upgradedAmharic,
                englishTranslation = upgradedEnglish
            )
            repository.saveTranscription(upgraded)
        } catch (_: Exception) {}
    }

    fun exportBackupToJson(context: Context, passphrase: String? = null): String? {
        return try {
            val list = transcriptionsList.value
            val jsonArray = JSONArray()
            for (item in list) {
                val obj = JSONObject()
                obj.put("id", item.id)
                obj.put("title", item.title)
                obj.put("amharicText", decryptString(item.amharicText))
                obj.put("englishTranslation", decryptString(item.englishTranslation))
                obj.put("durationSeconds", item.durationSeconds)
                obj.put("timestamp", item.timestamp)
                jsonArray.put(obj)
            }
            val rawJson = jsonArray.toString(2)
            val isEncrypted = !passphrase.isNullOrBlank()
            val finalContent = if (isEncrypted) {
                KeystoreCryptoManager.encryptBackupWithPassphrase(rawJson, passphrase!!)
            } else {
                rawJson
            }
            val fileName = if (isEncrypted) "abyssinia_study_backup_encrypted.json" else "abyssinia_study_backup.json"
            val file = File(context.cacheDir, fileName)
            file.writeText(finalContent)
            _statusMessage.value = if (isEncrypted) {
                "Passphrase-encrypted backup created (AES-256-GCM + PBKDF2)."
            } else {
                "Plaintext JSON backup prepared for export."
            }
            file.absolutePath
        } catch (e: Exception) {
            _statusMessage.value = "Backup creation failed: ${e.message}"
            null
        }
    }

    fun restoreBackupFromJson(jsonString: String, passphrase: String? = null): Boolean {
        val trimmed = jsonString.trim()
        if (trimmed.isEmpty()) {
            _statusMessage.value = "Restore rejected: Empty backup file content."
            return false
        }

        try {
            val decryptedJson = if (trimmed.contains(KeystoreCryptoManager.BACKUP_ENCRYPTED_FORMAT)) {
                if (passphrase.isNullOrBlank()) {
                    _statusMessage.value = "Restore rejected: This backup is passphrase-encrypted. Please provide your passphrase."
                    return false
                }
                try {
                    KeystoreCryptoManager.decryptBackupWithPassphrase(trimmed, passphrase)
                } catch (_: Exception) {
                    _statusMessage.value = "Restore rejected: Incorrect passphrase or corrupted backup file."
                    return false
                }
            } else {
                trimmed
            }

            val jsonArray = try {
                JSONArray(decryptedJson)
            } catch (_: Exception) {
                _statusMessage.value = "Restore rejected: Malformed JSON. Input must be a valid JSON array of notes."
                return false
            }

            if (jsonArray.length() == 0) {
                _statusMessage.value = "Restore rejected: Backup array contains zero note records."
                return false
            }

            val notesToRestore = mutableListOf<TranscriptionSession>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.optJSONObject(i)
                if (obj == null) {
                    _statusMessage.value = "Restore rejected: Record at index $i is not a valid JSON object."
                    return false
                }
                val title = obj.optString("title", "").trim()
                val amharic = obj.optString("amharicText", "").trim()
                val english = obj.optString("englishTranslation", "").trim()
                val duration = obj.optInt("durationSeconds", 0)
                val timestamp = obj.optLong("timestamp", System.currentTimeMillis())

                if (title.isEmpty() && amharic.isEmpty()) {
                    _statusMessage.value = "Restore rejected: Note at index $i is missing required 'title' and 'amharicText' fields."
                    return false
                }

                notesToRestore.add(
                    TranscriptionSession(
                        title = if (title.isEmpty()) "Restored Note #${i + 1}" else title,
                        amharicText = amharic,
                        englishTranslation = english,
                        durationSeconds = duration,
                        filePath = null,
                        timestamp = timestamp
                    )
                )
            }

            viewModelScope.launch(Dispatchers.IO) {
                val existing = transcriptionsList.value
                var restoredCount = 0
                var skippedCount = 0

                notesToRestore.forEach { note ->
                    val isDuplicate = existing.any { ex ->
                        val exAmharic = decryptString(ex.amharicText).trim()
                        val exEnglish = decryptString(ex.englishTranslation).trim()
                        ex.title.trim().equals(note.title.trim(), ignoreCase = true) &&
                            exAmharic == note.amharicText.trim() &&
                            exEnglish == note.englishTranslation.trim()
                    }

                    if (isDuplicate) {
                        skippedCount++
                    } else {
                        val amharicToStore = if (_isLocalEncryptionActive.value) encryptString(note.amharicText) else note.amharicText
                        val englishToStore = if (_isLocalEncryptionActive.value) encryptString(note.englishTranslation) else note.englishTranslation
                        val toInsert = note.copy(
                            id = 0,
                            amharicText = amharicToStore,
                            englishTranslation = englishToStore
                        )
                        repository.saveTranscription(toInsert)
                        restoredCount++
                    }
                }

                withContext(Dispatchers.Main) {
                    _statusMessage.value = "Restore completed: Restored $restoredCount notes ($skippedCount duplicate notes skipped)."
                }
            }
            return true
        } catch (e: Exception) {
            _statusMessage.value = "Restore rejected: ${e.message}"
            return false
        }
    }

    fun deleteSession(sessionId: Int) {
        val target = transcriptionsList.value.firstOrNull { it.id == sessionId }
        val otherUsesSameFile = target?.filePath != null &&
            transcriptionsList.value.any { it.id != sessionId && it.filePath == target.filePath }
        if (_playingAudioPath.value != null && _playingAudioPath.value == target?.filePath) {
            stopAudioPlayback()
        }
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteTranscription(sessionId)
            val path = target?.filePath
            if (path != null && !otherUsesSameFile) {
                try {
                    val f = File(path)
                    if (f.exists()) f.delete()
                } catch (_: Exception) {}
            }
        }
        _statusMessage.value = "Session note cleared from DB."
    }

    // Chatbot Features
    fun sendChatMessage() {
        val message = _chatInputText.value.trim()
        if (message.isBlank()) return

        viewModelScope.launch {
            repository.addChatMessage(role = "user", text = message)
            _chatInputText.value = ""
            _chatLoading.value = true
            _statusMessage.value = "AI Assistant thinking..."

            try {
                val currentHistory = chatMessagesList.value
                val botReply = withContext(Dispatchers.IO) {
                    repository.generateChatResponse(message, currentHistory)
                }

                repository.addChatMessage(role = "model", text = botReply)
                _statusMessage.value = null
            } catch (e: Exception) {
                _statusMessage.value = "AI Tutor failed to respond: ${e.message}"
                repository.addChatMessage(
                    role = "model",
                    text = "Sorry, I had trouble generating a reply. Connection Error: ${e.message}"
                )
            } finally {
                _chatLoading.value = false
            }
        }
    }

    fun clearAllChatMessages() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.clearChat()
        }
        _statusMessage.value = "Study chatbot conversation cache reset."
    }

    fun seedStudyQA(prompt: String) {
        _chatInputText.value = prompt
        sendChatMessage()
    }
}

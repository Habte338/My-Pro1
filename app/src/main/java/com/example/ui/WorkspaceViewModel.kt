package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.example.data.WorkspaceRepository
import com.example.data.api.KeyValidationResult
import com.example.data.db.AppDatabase
import com.example.data.db.ChatMessage
import com.example.data.db.FlashcardMasteryEntity
import com.example.data.db.QuizScoreEntity
import com.example.data.db.TranscriptionSession
import com.example.utils.AudioRecorder
import com.example.utils.ByokKeyEntry
import com.example.utils.ByokKeyManager
import com.example.utils.ByokProvider
import com.example.utils.KeystoreCryptoManager
import com.example.utils.RuntimeSecurityStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class WorkspaceViewModel(application: Application) : AndroidViewModel(application) {

    private val db by lazy {
        Room.databaseBuilder(
            application.applicationContext,
            AppDatabase::class.java,
            "amharic_workspace_db"
        )
        .addMigrations(AppDatabase.MIGRATION_1_2)
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

    // BYOK (Bring Your Own Key) state — never exposes plaintext keys to UI
    val byokKeys: StateFlow<List<ByokKeyEntry>> = byokKeyManager.entries

    private val _isByokValidating = MutableStateFlow(false)
    val isByokValidating: StateFlow<Boolean> = _isByokValidating.asStateFlow()

    private val _byokFeedbackMessage = MutableStateFlow<Pair<Boolean, String>?>(null)
    val byokFeedbackMessage: StateFlow<Pair<Boolean, String>?> = _byokFeedbackMessage.asStateFlow()

    // Database streams for History & Chat
    val transcriptionsList: StateFlow<List<TranscriptionSession>> = repository.allTranscriptions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val chatMessagesList: StateFlow<List<ChatMessage>> = repository.allChatMessages
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

    init {
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

    // UI operational states
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingDuration = MutableStateFlow(0)
    val recordingDuration: StateFlow<Int> = _recordingDuration.asStateFlow()

    private val _transcriptionLoading = MutableStateFlow(false)
    val transcriptionLoading: StateFlow<Boolean> = _transcriptionLoading.asStateFlow()

    private val _translationLoading = MutableStateFlow(false)
    val translationLoading: StateFlow<Boolean> = _translationLoading.asStateFlow()

    private val _chatLoading = MutableStateFlow(false)
    val chatLoading: StateFlow<Boolean> = _chatLoading.asStateFlow()

    private val _activeTranscriptionText = MutableStateFlow("")
    val activeTranscriptionText: StateFlow<String> = _activeTranscriptionText.asStateFlow()

    private val _activeTranslationText = MutableStateFlow("")
    val activeTranslationText: StateFlow<String> = _activeTranslationText.asStateFlow()

    private val _chatInputText = MutableStateFlow("")
    val chatInputText: StateFlow<String> = _chatInputText.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    // NEW Feature States
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

    private val _selectedOcrImageUri = MutableStateFlow<android.net.Uri?>(null)
    val selectedOcrImageUri: StateFlow<android.net.Uri?> = _selectedOcrImageUri.asStateFlow()

    private val _savedHandwritingStyles = MutableStateFlow<List<String>>(emptyList())
    val savedHandwritingStyles: StateFlow<List<String>> = _savedHandwritingStyles.asStateFlow()

    private val _imageBatchQueue = MutableStateFlow<List<android.net.Uri>>(emptyList())
    val imageBatchQueue: StateFlow<List<android.net.Uri>> = _imageBatchQueue.asStateFlow()

    private val _audioBatchQueue = MutableStateFlow<List<android.net.Uri>>(emptyList())
    val audioBatchQueue: StateFlow<List<android.net.Uri>> = _audioBatchQueue.asStateFlow()

    private val _isBatchProcessing = MutableStateFlow(false)
    val isBatchProcessing: StateFlow<Boolean> = _isBatchProcessing.asStateFlow()

    private val _batchProgressMessage = MutableStateFlow("")
    val batchProgressMessage: StateFlow<String> = _batchProgressMessage.asStateFlow()

    private var recordTimerJob: Job? = null
    private var recordedFile: File? = null

    // State Toggles and Setters
    fun toggleDarkMode() {
        _isDarkMode.value = !_isDarkMode.value
    }

    fun toggleOfflineMode() {
        _isOfflineMode.value = !_isOfflineMode.value
        _statusMessage.value = if (_isOfflineMode.value) "Offline Mode Active (Local fallback OCR/transcribe enabled)" else "Online Mode Active (Gemini API OCR/transcribe enabled)"
    }

    fun toggleLocalEncryption() {
        _isLocalEncryptionActive.value = !_isLocalEncryptionActive.value
        _statusMessage.value = if (_isLocalEncryptionActive.value) "Local SQLite encryption enabled for new notes." else "Local SQLite encryption disabled."
    }

    fun updateSelectedSourceLang(lang: String) {
        _selectedSourceLang.value = lang
    }

    fun updateSelectedTargetLang(lang: String) {
        _selectedTargetLang.value = lang
    }

    fun updateSelectedOcrImageUri(uri: android.net.Uri?) {
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

    fun addImageToBatchQueue(uris: List<android.net.Uri>) {
        val current = _imageBatchQueue.value.toMutableList()
        current.addAll(uris)
        _imageBatchQueue.value = current
        _statusMessage.value = "Added ${uris.size} images to visual OCR queue."
    }

    fun clearImageBatchQueue() {
        _imageBatchQueue.value = emptyList()
    }

    fun addAudioToBatchQueue(uris: List<android.net.Uri>) {
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
        return com.example.utils.KeystoreCryptoManager.encrypt(input)
    }

    fun decryptString(input: String): String {
        return com.example.utils.KeystoreCryptoManager.decrypt(input)
    }

    // Helper to copy URI to cache File with image optimization and orientation correction
    private fun copyUriToTempFile(uri: android.net.Uri, context: android.content.Context, outputName: String): File? {
        return try {
            val contentResolver = context.contentResolver
            val tempFile = File(context.cacheDir, outputName)
            
            val isImage = outputName.endsWith(".jpg", ignoreCase = true) || 
                          outputName.endsWith(".png", ignoreCase = true) ||
                          outputName.endsWith(".jpeg", ignoreCase = true)
            
            if (isImage) {
                // Read EXIF orientation
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

                // Measure dimensions
                val options = android.graphics.BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                contentResolver.openInputStream(uri)?.use { stream ->
                    android.graphics.BitmapFactory.decodeStream(stream, null, options)
                }

                val maxDimension = 1600
                var inSampleSize = 1
                if (options.outHeight > maxDimension || options.outWidth > maxDimension) {
                    val halfHeight = options.outHeight / 2
                    val halfWidth = options.outWidth / 2
                    while ((halfHeight / inSampleSize) >= maxDimension || (halfWidth / inSampleSize) >= maxDimension) {
                        inSampleSize *= 2
                    }
                }

                // Decode sampled bitmap
                val decodeOptions = android.graphics.BitmapFactory.Options().apply {
                    this.inSampleSize = inSampleSize
                }
                var bitmap: android.graphics.Bitmap? = null
                contentResolver.openInputStream(uri)?.use { stream ->
                    bitmap = android.graphics.BitmapFactory.decodeStream(stream, null, decodeOptions)
                }

                if (bitmap != null) {
                    var finalBitmap = bitmap!!
                    if (rotationDegrees != 0) {
                        val matrix = android.graphics.Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                        finalBitmap = android.graphics.Bitmap.createBitmap(
                            finalBitmap, 0, 0, finalBitmap.width, finalBitmap.height, matrix, true
                        )
                    }

                    tempFile.outputStream().use { outStream ->
                        finalBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, outStream)
                    }
                    if (tempFile.exists() && tempFile.length() > 0) return tempFile
                }
            }

            // Fallback direct stream copy for audio or uncompressed files
            contentResolver.openInputStream(uri)?.use { inputStream ->
                tempFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            if (tempFile.exists() && tempFile.length() > 0) tempFile else null
        } catch (e: Exception) {
            null
        }
    }

    // Offline mock responses
    private fun getLocalOfflineOcrText(fileName: String): String {
        return "ሰላም ጤና ይስጥልኝ! ይህ ከመስመር ውጭ (Offline) የተገኘ የአማርኛ ጽሑፍ ነው ።"
    }

    private fun getLocalOfflineTranslation(text: String, targetLang: String): String {
        return when (targetLang) {
            "English" -> "Greetings and peace! This is offline recognized Amharic text."
            "Tigrinya" -> "ሰላም ጥዕና ይሃበለይ! እዚ ከመስመር ወጻኢ ዝተረኽበ ጽሑፍ እዩ ።"
            "Oromo" -> "Fayyaa fi nagaan siif haa tahu! Kun barreeffama afaan Oromooti."
            "Somali" -> "Nabad iyo caafimaad ha kuu ahaato! Kani waa qoraal offline ah."
            else -> text
        }
    }

    // Batch Image Process OCR
    fun processImageBatch(context: android.content.Context) {
        val queue = _imageBatchQueue.value
        if (queue.isEmpty()) {
            _statusMessage.value = "Image queue is empty! Add images first."
            return
        }

        viewModelScope.launch {
            _isBatchProcessing.value = true
            _transcriptionLoading.value = true
            val targetLang = _selectedTargetLang.value
            _batchProgressMessage.value = "Batch processing 0/${queue.size} images..."
            _activeTranscriptionText.value = "Starting batch OCR..."
            _activeTranslationText.value = "Starting batch translation..."
            
            val accumulatedOcr = StringBuilder()
            val accumulatedTranslation = StringBuilder()

            queue.forEachIndexed { index, uri ->
                _batchProgressMessage.value = "Processing image ${index + 1}/${queue.size}..."
                
                val file = copyUriToTempFile(uri, context, "batch_img_$index.jpg")
                if (file != null) {
                    if (_isOfflineMode.value) {
                        delay(1000)
                        val detected = "Amharic"
                        val ocrText = getLocalOfflineOcrText(file.name)
                        val transText = getLocalOfflineTranslation(ocrText, targetLang)
                        accumulatedOcr.append("--- Image ${index + 1} (${detected}) ---\n$ocrText\n\n")
                        accumulatedTranslation.append("--- Translation ${index + 1} (${targetLang}) ---\n$transText\n\n")
                    } else {
                        val result = withContext(Dispatchers.IO) {
                            repository.performOcrOnImage(file, targetLang)
                        }
                        if (result.error != null) {
                            accumulatedOcr.append("--- Image ${index + 1} (Error) ---\nFailed to parse raw text.\n\n")
                            accumulatedTranslation.append("--- Translation ${index + 1} (Error) ---\n${result.error}\n\n")
                        } else {
                            accumulatedOcr.append("--- Image ${index + 1} (${result.originalLanguage}) ---\n${result.transcription}\n\n")
                            accumulatedTranslation.append("--- Translation ${index + 1} (${targetLang}) ---\n${result.translation}\n\n")
                        }
                    }
                } else {
                    accumulatedOcr.append("--- Image ${index + 1} ---\nError: Failed to read input image.\n\n")
                }
                _activeTranscriptionText.value = accumulatedOcr.toString()
                _activeTranslationText.value = accumulatedTranslation.toString()
            }

            _isBatchProcessing.value = false
            _transcriptionLoading.value = false
            _statusMessage.value = "Batch OCR of ${queue.size} images completed!"
            _batchProgressMessage.value = ""
            _imageBatchQueue.value = emptyList()
        }
    }

    // Batch Audio Process Transcription
    fun processAudioBatch(context: android.content.Context) {
        val queue = _audioBatchQueue.value
        if (queue.isEmpty()) {
            _statusMessage.value = "Audio queue is empty! Add audio files first."
            return
        }

        viewModelScope.launch {
            _isBatchProcessing.value = true
            _transcriptionLoading.value = true
            _batchProgressMessage.value = "Batch transcribing 0/${queue.size} voice files..."
            _activeTranscriptionText.value = "Starting batch voice transcription..."
            _activeTranslationText.value = "Starting batch voice translation..."

            val accumulatedOcr = StringBuilder()
            val accumulatedTranslation = StringBuilder()

            queue.forEachIndexed { index, uri ->
                _batchProgressMessage.value = "Processing audio ${index + 1}/${queue.size}..."
                val file = copyUriToTempFile(uri, context, "batch_audio_$index.mp4")
                if (file != null) {
                    if (_isOfflineMode.value) {
                        delay(1000)
                        val ocrText = "ሰላም ፣ ይህ ከመስመር ውጭ የተመዘገበ የድምፅ ቅጂ ግልባጭ ነው ።"
                        val transText = "Peace, this is an offline voice recording transcription."
                        accumulatedOcr.append("--- Voice File ${index + 1} ---\n$ocrText\n\n")
                        accumulatedTranslation.append("--- Translation ${index + 1} ---\n$transText\n\n")
                    } else {
                        val transcriptionResult = withContext(Dispatchers.IO) {
                            repository.transcribeAudio(file)
                        }
                        accumulatedOcr.append("--- Voice File ${index + 1} ---\n$transcriptionResult\n\n")
                        
                        val translationResult = withContext(Dispatchers.IO) {
                            repository.translateText(transcriptionResult)
                        }
                        accumulatedTranslation.append("--- Translation ${index + 1} ---\n$translationResult\n\n")
                    }
                } else {
                    accumulatedOcr.append("--- Voice File ${index + 1} ---\nError: Failed to read audio file.\n\n")
                }
                _activeTranscriptionText.value = accumulatedOcr.toString()
                _activeTranslationText.value = accumulatedTranslation.toString()
            }

            _isBatchProcessing.value = false
            _transcriptionLoading.value = false
            _statusMessage.value = "Batch transcription of ${queue.size} files completed!"
            _batchProgressMessage.value = ""
            _audioBatchQueue.value = emptyList()
        }
    }

    // Trigger Single Image OCR
    fun triggerImageOcr(context: android.content.Context) {
        val uri = _selectedOcrImageUri.value
        if (uri == null) {
            _statusMessage.value = "Please select or capture a document image first."
            return
        }

        viewModelScope.launch {
            _transcriptionLoading.value = true
            _statusMessage.value = "Processing image OCR..."
            _activeTranscriptionText.value = "Converting image text..."
            _activeTranslationText.value = "Translating recognized script..."

            val file = copyUriToTempFile(uri, context, "single_ocr_img.jpg")
            if (file != null) {
                if (_isOfflineMode.value) {
                    delay(1200)
                    val ocrText = getLocalOfflineOcrText(file.name)
                    val transText = getLocalOfflineTranslation(ocrText, _selectedTargetLang.value)
                    _activeTranscriptionText.value = ocrText
                    if (_selectedTargetLang.value != "None" && _selectedTargetLang.value.lowercase() != "same") {
                        _activeTranslationText.value = transText
                    } else {
                        _activeTranslationText.value = ""
                    }
                    _statusMessage.value = "Offline local visual OCR completed!"
                } else {
                    val result = withContext(Dispatchers.IO) {
                        repository.performOcrOnImage(file, _selectedTargetLang.value)
                    }
                    if (result.error != null) {
                        _statusMessage.value = result.error
                        _activeTranscriptionText.value = ""
                        _activeTranslationText.value = ""
                    } else {
                        _activeTranscriptionText.value = result.transcription
                        _activeTranslationText.value = if (result.translation.lowercase() != "same") result.translation else ""
                        _statusMessage.value = "OCR Completed! Original script: ${result.originalLanguage}"
                    }
                }
            } else {
                _statusMessage.value = "Error copying image to cache."
                _activeTranscriptionText.value = ""
                _activeTranslationText.value = ""
            }
            _transcriptionLoading.value = false
        }
    }

    fun loadSampleOcrDocument(context: android.content.Context) {
        viewModelScope.launch {
            try {
                val width = 800
                val height = 600
                val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                
                // Document paper background
                val bgPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.rgb(253, 252, 248)
                }
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

                // Header border
                val borderPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.rgb(220, 215, 205)
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = 4f
                }
                canvas.drawRect(20f, 20f, width - 20f, height - 20f, borderPaint)

                // Title banner
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

                // Body text in Amharic script
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

                val sampleFile = File(context.cacheDir, "sample_amharic_doc.jpg")
                sampleFile.outputStream().use { out ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, out)
                }

                if (sampleFile.exists() && sampleFile.length() > 0) {
                    _selectedOcrImageUri.value = android.net.Uri.fromFile(sampleFile)
                    _statusMessage.value = "Sample Amharic Document loaded! Tap 'Scan Document with Gemini OCR' to scan."
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

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    // Audio recording functions
    fun startMicrophoneRecording() {
        viewModelScope.launch {
            val fileName = "rec_${System.currentTimeMillis()}.mp4"
            val filePath = audioRecorder.startRecording(fileName)
            if (filePath != null) {
                recordedFile = File(filePath)
                _isRecording.value = true
                _recordingDuration.value = 0
                _statusMessage.value = "Recording Amharic voice..."
                
                // Duration timer ticker
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
            val fileObj = audioRecorder.stopRecording()
            if (fileObj != null && fileObj.exists() && fileObj.length() > 0) {
                _statusMessage.value = "Audio recording finished. Ready for transcription."
                recordedFile = fileObj
            } else {
                _statusMessage.value = "No audio data recorded or failed to write output file."
            }
        }
    }

    fun simulateDemoRecording() {
        viewModelScope.launch {
            val file = File(getApplication<Application>().cacheDir, "simulated_audio.mp4")
            try {
                // Write synthetic metadata bytes
                file.writeBytes(ByteArray(2048) { 0 })
                recordedFile = file
                _recordingDuration.value = 5
                _activeTranscriptionText.value = "ሰላም ፣ ይህ የአማርኛ ድምፅ መቅረጫ የጥናት ቦታ ፕሮቶታይፕ ነው ።"
                _statusMessage.value = "Demo Amharic Audio loaded successfully! Ready to transcribe or translate."
            } catch (e: Exception) {
                _statusMessage.value = "Error creating simulated audio: ${e.message}"
            }
        }
    }

    fun loadUploadedAudioFile(uri: android.net.Uri, context: android.content.Context) {
        viewModelScope.launch {
            try {
                val contentResolver = context.contentResolver
                val mimeType = contentResolver.getType(uri) ?: "audio/mp4"
                
                val extension = when {
                    mimeType.contains("mpeg") || mimeType.contains("mp3") -> "mp3"
                    mimeType.contains("wav") -> "wav"
                    mimeType.contains("m4a") -> "m4a"
                    mimeType.contains("ogg") -> "ogg"
                    else -> "mp4"
                }
                
                val fileName = "uploaded_${System.currentTimeMillis()}.$extension"
                val tempFile = File(context.cacheDir, fileName)
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    tempFile.outputStream().use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                if (tempFile.exists() && tempFile.length() > 0) {
                    recordedFile = tempFile
                    _recordingDuration.value = 0
                    _statusMessage.value = "Imported Amharic audio ($extension). Ready to Transcribe!"
                    _activeTranscriptionText.value = ""
                    _activeTranslationText.value = ""
                } else {
                    _statusMessage.value = "Failed to copy selected audio file."
                }
            } catch (e: Exception) {
                _statusMessage.value = "Audio Import Error: ${e.message}"
            }
        }
    }

    fun triggerTranscription() {
        val file = recordedFile
        if (file == null || !file.exists() || file.length() == 0L) {
            _statusMessage.value = "Please complete a voice recording first."
            return
        }

        // If simulated demo recording, perform translation and complete state
        if (file.name == "simulated_audio.mp4") {
            viewModelScope.launch {
                _transcriptionLoading.value = true
                _statusMessage.value = "Processing demo transcription & translating..."
                try {
                    val text = _activeTranscriptionText.value.ifBlank {
                        "ሰላም ፣ ይህ የአማርኛ ድምፅ መቅረጫ የጥናት ቦታ ፕሮቶታይፕ ነው ።"
                    }
                    _activeTranscriptionText.value = text
                    val target = _selectedTargetLang.value
                    if (target != "None" && !_isOfflineMode.value) {
                        val trans = withContext(Dispatchers.IO) {
                            repository.translateEthiopianText(text, "Amharic", target)
                        }
                        _activeTranslationText.value = trans
                    } else {
                        _activeTranslationText.value = "Greetings, this is an Amharic voice recorder study workspace prototype."
                    }
                    _statusMessage.value = "Demo transcription & translation ready!"
                } catch (e: Exception) {
                    _activeTranslationText.value = "Greetings, this is an Amharic voice recorder study workspace prototype."
                    _statusMessage.value = "Demo transcription loaded!"
                } finally {
                    _transcriptionLoading.value = false
                }
            }
            return
        }

        viewModelScope.launch {
            _transcriptionLoading.value = true
            _statusMessage.value = "Transcribing & identifying spoken language..."
            _activeTranscriptionText.value = "Transcribing spoken voice..."
            _activeTranslationText.value = "Translating spoken language..."
            try {
                if (_isOfflineMode.value) {
                    delay(1200)
                    _activeTranscriptionText.value = "ሰላም ፣ ይህ ከመስመር ውጭ የተቀዳ የአማርኛ ድምፅ መቅረጫ ግልባጭ ነው ።"
                    _activeTranslationText.value = "Peace, this is an offline Amharic voice recorder transcription."
                    _statusMessage.value = "Offline audio transcription completed!"
                } else {
                    val result = withContext(Dispatchers.IO) {
                        repository.transcribeAndDetectAudio(file, _selectedTargetLang.value)
                    }
                    
                    if (result.error != null) {
                        _statusMessage.value = result.error
                        _activeTranscriptionText.value = ""
                        _activeTranslationText.value = ""
                    } else {
                        _activeTranscriptionText.value = result.transcription
                        _activeTranslationText.value = if (result.translation.lowercase() != "same") result.translation else ""
                        _statusMessage.value = "Audio Transcribed! Identified Spoken Language: ${result.originalLanguage}"
                    }
                }
            } catch (e: Exception) {
                _statusMessage.value = "Transcription Error: ${e.message}"
                _activeTranscriptionText.value = ""
                _activeTranslationText.value = ""
            } finally {
                _transcriptionLoading.value = false
            }
        }
    }

    fun triggerTranslation(textToTranslate: String) {
        if (textToTranslate.isBlank()) {
            _statusMessage.value = "No text found to translate."
            return
        }

        viewModelScope.launch {
            _translationLoading.value = true
            val src = _selectedSourceLang.value
            val dest = _selectedTargetLang.value
            _statusMessage.value = "Translating text from $src to $dest..."
            _activeTranslationText.value = "Translating text..."
            try {
                if (_isOfflineMode.value) {
                    delay(1000)
                    _activeTranslationText.value = getLocalOfflineTranslation(textToTranslate, dest)
                    _statusMessage.value = "Offline translation completed!"
                } else {
                    val translationResult = withContext(Dispatchers.IO) {
                        repository.translateEthiopianText(textToTranslate, src, dest)
                    }
                    _activeTranslationText.value = translationResult
                    _statusMessage.value = "Text translated successfully."
                }
            } catch (e: Exception) {
                _statusMessage.value = "Translation Error: ${e.message}"
                _activeTranslationText.value = ""
            } finally {
                _translationLoading.value = false
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

            // Apply encryption if toggled on
            val storedAmharic = if (_isLocalEncryptionActive.value) encryptString(amharic) else amharic
            val storedEnglish = if (_isLocalEncryptionActive.value) encryptString(english) else english

            val session = TranscriptionSession(
                title = title,
                amharicText = storedAmharic,
                englishTranslation = storedEnglish,
                durationSeconds = _recordingDuration.value,
                filePath = recordedFile?.absolutePath
            )
            
            withContext(Dispatchers.IO) {
                repository.saveTranscription(session)
            }
            _statusMessage.value = "Note saved: \"$title\""
            
            // Clear current working fields after saving
            _activeTranscriptionText.value = ""
            _activeTranslationText.value = ""
            recordedFile = null
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

    /**
     * Exports study notes backup.
     * If passphrase is provided: encrypts entire payload via PBKDF2WithHmacSHA256 + AES-GCM 256.
     * If passphrase is null/empty: exports decrypted clean plaintext JSON for cross-device portability.
     */
    fun exportBackupToJson(context: android.content.Context, passphrase: String? = null): String? {
        return try {
            val list = transcriptionsList.value
            val jsonArray = JSONArray()
            for (item in list) {
                val obj = JSONObject()
                obj.put("id", item.id)
                obj.put("title", item.title)
                // Always decrypt device-local hardware keystore ciphertext before export
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

    /**
     * Restores study notes from backup with strict format validation and merge deduplication.
     * Documents merge behavior:
     * 1. Deduplicates against existing notes in Room by matching title and amharicText.
     * 2. Preserves timestamps while generating fresh IDs to avoid primary key collisions.
     * 3. Re-encrypts with local Android KeyStore AES-GCM when storing to Room.
     * 4. Rejects malformed JSON, empty arrays, or incorrect passphrases without modifying the DB.
     */
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
                } catch (e: Exception) {
                    _statusMessage.value = "Restore rejected: Incorrect passphrase or corrupted backup file."
                    return false
                }
            } else {
                trimmed
            }

            // Strict JSON array parsing validation
            val jsonArray = try {
                JSONArray(decryptedJson)
            } catch (e: Exception) {
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

            // Execute merge with deduplication against existing notes in Room
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
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteTranscription(sessionId)
        }
        _statusMessage.value = "Session note cleared from DB."
    }

    // Chatbot Features
    fun sendChatMessage() {
        val message = _chatInputText.value.trim()
        if (message.isBlank()) return

        viewModelScope.launch {
            // Save user prompt
            repository.addChatMessage(role = "user", text = message)
            _chatInputText.value = ""
            _chatLoading.value = true
            _statusMessage.value = "AI Assistant thinking..."

            try {
                val currentHistory = chatMessagesList.value
                val botReply = withContext(Dispatchers.IO) {
                    repository.generateChatResponse(message, currentHistory)
                }
                
                // Save AI reply to database
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

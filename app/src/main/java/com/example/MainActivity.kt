package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.db.ChatMessage
import com.example.data.db.TranscriptionSession
import com.example.ui.WorkspaceViewModel
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: WorkspaceViewModel = viewModel()
            val isDark by viewModel.isDarkMode.collectAsStateWithLifecycle()
            MyApplicationTheme(darkTheme = isDark) {
                MainAppScreen(viewModel)
            }
        }
    }
}

enum class ActiveWorkspace {
    TRANSCRIBE, TUTOR_BOT, HISTORY, OCR
}

@OptIn(ExperimentalLayoutApi::class)
@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun MainAppScreen(viewModel: WorkspaceViewModel) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()

    // Screen states observed from ViewModel
    val transcriptions by viewModel.transcriptionsList.collectAsStateWithLifecycle()
    val chatMessages by viewModel.chatMessagesList.collectAsStateWithLifecycle()

    val isRecording by viewModel.isRecording.collectAsStateWithLifecycle()
    val recordingDuration by viewModel.recordingDuration.collectAsStateWithLifecycle()

    val transcriptionLoading by viewModel.transcriptionLoading.collectAsStateWithLifecycle()
    val translationLoading by viewModel.translationLoading.collectAsStateWithLifecycle()
    val chatLoading by viewModel.chatLoading.collectAsStateWithLifecycle()

    val activeTranscription by viewModel.activeTranscriptionText.collectAsStateWithLifecycle()
    val activeTranslation by viewModel.activeTranslationText.collectAsStateWithLifecycle()
    val chatInput by viewModel.chatInputText.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()

    var activeTab by remember { mutableStateOf(ActiveWorkspace.TRANSCRIBE) }
    var noteTitleInput by remember { mutableStateOf("") }
    
    // Shows status messages in real android toasts when updated
    LaunchedEffect(statusMessage) {
        statusMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearStatusMessage()
        }
    }

    // Microphone permission handler
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.startMicrophoneRecording()
        } else {
            Toast.makeText(context, "Microphone permission is required to record Amharic speech.", Toast.LENGTH_LONG).show()
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            // Highly polished bottom navigation bar styled in modern Material 3
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                modifier = Modifier
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .height(82.dp)
                    .border(
                        BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)),
                        RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
                    )
            ) {
                // Transcribe Tab
                NavigationBarItem(
                    selected = activeTab == ActiveWorkspace.TRANSCRIBE,
                    onClick = { activeTab = ActiveWorkspace.TRANSCRIBE },
                    icon = { Text("🎙️", fontSize = 22.sp) },
                    label = { Text("Workspace", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f),
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                )

                // Tutor AI Tab
                NavigationBarItem(
                    selected = activeTab == ActiveWorkspace.TUTOR_BOT,
                    onClick = { activeTab = ActiveWorkspace.TUTOR_BOT },
                    icon = { Text("💬", fontSize = 22.sp) },
                    label = { Text("Tutor AI", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f),
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                )

                // History Tab
                NavigationBarItem(
                    selected = activeTab == ActiveWorkspace.HISTORY,
                    onClick = { activeTab = ActiveWorkspace.HISTORY },
                    icon = { Text("📚", fontSize = 22.sp) },
                    label = { Text("Library", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f),
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                )

                // OCR Tab
                NavigationBarItem(
                    selected = activeTab == ActiveWorkspace.OCR,
                    onClick = { activeTab = ActiveWorkspace.OCR },
                    icon = { Text("📷", fontSize = 22.sp) },
                    label = { Text("OCR Scan", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f),
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(bottom = innerPadding.calculateBottomPadding())
        ) {
            // Elegant Ethiopian Tri-Color Top Accent Border (Thin & High-End)
            Row(modifier = Modifier.fillMaxWidth().height(3.dp)) {
                Box(modifier = Modifier.weight(1f).fillMaxHeight().background(Color(0xFF10B981)))
                Box(modifier = Modifier.weight(1f).fillMaxHeight().background(Color(0xFFFBBF24)))
                Box(modifier = Modifier.weight(1f).fillMaxHeight().background(Color(0xFFEF4444)))
            }

            // Top Header Banner following professional mockup guidelines
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .border(
                        BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)),
                        RoundedCornerShape(20.dp)
                    ),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("🎙️", fontSize = 22.sp)
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "አቢሲኒያ Study",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Transcription & AI Toolset",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Box(
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.secondary, RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "Gemini AI",
                            color = MaterialTheme.colorScheme.onSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Content Area based on Active Workspace Tab (Fluid container)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                when (activeTab) {
                    ActiveWorkspace.TRANSCRIBE -> {
                        TranscribeWorkspace(
                            viewModel = viewModel,
                            isRecording = isRecording,
                            transcriptionLoading = transcriptionLoading,
                            translationLoading = translationLoading,
                            activeTranscription = activeTranscription,
                            activeTranslation = activeTranslation,
                            noteTitleInput = noteTitleInput,
                            onTitleChange = { noteTitleInput = it },
                            onRequestMicrophone = {
                                val hasPermission = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                                if (hasPermission) {
                                    viewModel.startMicrophoneRecording()
                                } else {
                                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                            clipboardManager = clipboardManager,
                            onSelectTab = { activeTab = it }
                        )
                    }

                    ActiveWorkspace.TUTOR_BOT -> {
                        TutorChatWorkspace(
                            viewModel = viewModel,
                            chatMessages = chatMessages,
                            chatInput = chatInput,
                            chatLoading = chatLoading
                        )
                    }

                    ActiveWorkspace.HISTORY -> {
                        HistoryWorkspace(
                            viewModel = viewModel,
                            transcriptions = transcriptions,
                            clipboardManager = clipboardManager
                        )
                    }

                    ActiveWorkspace.OCR -> {
                        OcrWorkspaceScreen(viewModel = viewModel)
                    }
                }
            }
        }
    }
}

@Composable
fun TranscribeWorkspace(
    viewModel: WorkspaceViewModel,
    isRecording: Boolean,
    transcriptionLoading: Boolean,
    translationLoading: Boolean,
    activeTranscription: String,
    activeTranslation: String,
    noteTitleInput: String,
    onTitleChange: (String) -> Unit,
    onRequestMicrophone: () -> Unit,
    clipboardManager: androidx.compose.ui.platform.ClipboardManager,
    onSelectTab: (ActiveWorkspace) -> Unit
) {
    val durationState by viewModel.recordingDuration.collectAsStateWithLifecycle()
    var subTab by remember { mutableStateOf(0) } // 0: Voice Desk, 1: Image OCR, 2: Study Notes & Settings

    val context = LocalContext.current
    
    // File Picker for custom local audio imports
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            viewModel.loadUploadedAudioFile(it, context)
        }
    }

    // Pick visual media launch (Single image visual OCR)
    val singleImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { viewModel.updateSelectedOcrImageUri(it) }
    }

    // Pick multiple visual media launch (Batch processing queue)
    val batchImagesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            viewModel.addImageToBatchQueue(uris)
        }
    }

    // Document audio batch queue launcher
    val batchAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            viewModel.addAudioToBatchQueue(uris)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 8.dp)
    ) {
        // Pill-shaped Segmented View Switcher (One Page - No vertical page scroll!)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)), RoundedCornerShape(16.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (subTab == 0) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable { subTab = 0 }
                    .testTag("voice_desk_tab"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "🎙️ Voice Desk",
                    color = if (subTab == 0) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (subTab == 1) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable { subTab = 1 }
                    .testTag("settings_desk_tab"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "⚙️ Settings & Sync",
                    color = if (subTab == 1) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (subTab == 0) {
                // SUBTAB 0: VOICE RECORDING & AUDIO DESK
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)),
                            RoundedCornerShape(24.dp)
                        ),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "🎙️ Voice Recording & Upload",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text = "Record or upload audio files to transcribe with punctuation and formatting.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Microphone pulse indicator with professional red breath feedback
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .background(
                                    if (isRecording) Color(0xFFEF4444).copy(alpha = 0.12f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isRecording) {
                                val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                                val scale by infiniteTransition.animateFloat(
                                    initialValue = 0.8f,
                                    targetValue = 1.25f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(1000, easing = LinearEasing),
                                        repeatMode = RepeatMode.Reverse
                                    ),
                                    label = "pulseScale"
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(10.dp)
                                        .background(Color(0xFFEF4444).copy(alpha = 0.2f * scale), CircleShape)
                                )
                            }

                            IconButton(
                                onClick = {
                                    if (isRecording) {
                                        viewModel.stopAndProcessRecording()
                                    } else {
                                        onRequestMicrophone()
                                    }
                                },
                                modifier = Modifier
                                    .size(56.dp)
                                    .background(
                                        if (isRecording) Color(0xFFEF4444) else MaterialTheme.colorScheme.primary,
                                        CircleShape
                                    )
                                    .testTag("record_button")
                            ) {
                                Text(
                                    text = if (isRecording) "⏹️" else "🎙️",
                                    fontSize = 24.sp,
                                    color = Color.White
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Timer indicator
                        Text(
                            text = if (isRecording) {
                                val minutes = durationState / 60
                                val seconds = durationState % 60
                                String.format("🔴 Recording: %02d:%02d", minutes, seconds)
                            } else if (durationState > 0) {
                                "Recorded: ${durationState} seconds"
                            } else {
                                "Ready to Record / Import"
                            },
                            color = if (isRecording) Color(0xFFEF4444) else MaterialTheme.colorScheme.primary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Row for Preload & Upload
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = { viewModel.simulateDemoRecording() },
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp),
                                shape = RoundedCornerShape(20.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            ) {
                                Text("💾 Preload Demo", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = { filePickerLauncher.launch("audio/*") },
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp)
                                    .testTag("upload_audio_button"),
                                shape = RoundedCornerShape(20.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            ) {
                                Text("📂 Upload Audio", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Batch voice queue segment
                        val audioBatchQueue by viewModel.audioBatchQueue.collectAsStateWithLifecycle()
                        val isBatchProcessing by viewModel.isBatchProcessing.collectAsStateWithLifecycle()
                        val batchProgress by viewModel.batchProgressMessage.collectAsStateWithLifecycle()

                        if (audioBatchQueue.isNotEmpty()) {
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.05f))
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text("📂 Batch Audio Queue: ${audioBatchQueue.size} items queued", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = { viewModel.processAudioBatch(context) },
                                            modifier = Modifier.weight(1f).height(32.dp),
                                            shape = RoundedCornerShape(16.dp)
                                        ) {
                                            Text("Transcribe Batch", fontSize = 10.sp)
                                        }
                                        OutlinedButton(
                                            onClick = { viewModel.clearAudioBatchQueue() },
                                            modifier = Modifier.weight(1f).height(32.dp),
                                            shape = RoundedCornerShape(16.dp)
                                        ) {
                                            Text("Clear Queue", fontSize = 10.sp)
                                        }
                                    }
                                }
                            }
                        }

                        if (isBatchProcessing && batchProgress.isNotEmpty()) {
                            Text(batchProgress, color = MaterialTheme.colorScheme.secondary, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // Transcribe Button
                        Button(
                            onClick = { viewModel.triggerTranscription() },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                                .testTag("transcribe_button"),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            if (transcriptionLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White)
                            } else {
                                Text("✨ Transcribe Voice Audio", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                }

                // Amharic Entry Box (with left-border highlight)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.06f)),
                            RoundedCornerShape(24.dp)
                        ),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                        Box(
                            modifier = Modifier
                                .width(6.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.primary)
                        )
                        Column(modifier = Modifier.padding(16.dp).weight(1f)) {
                            Text(
                                text = "Transcription Workspace Text (የጽሑፍ ግልባጭ)",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            OutlinedTextField(
                                value = activeTranscription,
                                onValueChange = { viewModel.updateActiveTranscriptionText(it) },
                                placeholder = { Text("Transcribed script appears here. You may edit to revise punctuation or transcription results.", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 12.sp) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(95.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                                ),
                                shape = RoundedCornerShape(12.dp)
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            // Translate / Quick Actions Trigger
                            Button(
                                onClick = { 
                                    viewModel.triggerTranslation(activeTranscription)
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondary,
                                    contentColor = MaterialTheme.colorScheme.onSecondary
                                ),
                                modifier = Modifier
                                    .align(Alignment.End)
                                    .height(36.dp)
                                    .testTag("translate_button"),
                                shape = RoundedCornerShape(18.dp)
                            ) {
                                if (translationLoading) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onSecondary)
                                } else {
                                    Text("🌐 Translate Text", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Active Translation Outputs (Integrated on the same Voice Desk page!)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)),
                            RoundedCornerShape(24.dp)
                        ),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("📝", fontSize = 16.sp, modifier = Modifier.padding(end = 6.dp))
                                Text(
                                    text = "Workspace Translation Output",
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            IconButton(
                                onClick = {
                                    if (activeTranslation.isNotBlank()) {
                                        clipboardManager.setText(AnnotatedString(activeTranslation))
                                    }
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Text("📋", fontSize = 14.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        OutlinedTextField(
                            value = activeTranslation,
                            onValueChange = { viewModel.updateActiveTranslationText(it) },
                            placeholder = { Text("Translation output appears here.", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 12.sp) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(85.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }

                // AI Study helpers row (Integrated on the same Voice Desk page!)
                Column {
                    Text(
                        text = "✨ Study Helper Quick Shortcuts",
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )

                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            Button(
                                onClick = {
                                    if (activeTranscription.isNotBlank()) {
                                        viewModel.seedStudyQA("Summarize the key notes and insights of this transcribed content in clear, easy bullet points option:\n\nAmharic: $activeTranscription\nTranslation: $activeTranslation")
                                        onSelectTab(ActiveWorkspace.TUTOR_BOT)
                                    } else {
                                        viewModel.seedStudyQA("Prepare general summary notes on common Amharic study techniques and greetings!")
                                        onSelectTab(ActiveWorkspace.TUTOR_BOT)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text("Summarize Key Points", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        item {
                            OutlinedButton(
                                onClick = {
                                    if (activeTranscription.isNotBlank()) {
                                        viewModel.seedStudyQA("Extract definitions and vocabulary verbs and nouns found within this session:\n\nAmharic: $activeTranscription")
                                        onSelectTab(ActiveWorkspace.TUTOR_BOT)
                                    } else {
                                        viewModel.seedStudyQA("Explain high frequency local vocabularies or verbs with pronunciation tables.")
                                        onSelectTab(ActiveWorkspace.TUTOR_BOT)
                                    }
                                },
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.height(34.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
                            ) {
                                Text("Extract Vocabulary", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        item {
                            OutlinedButton(
                                onClick = {
                                    if (activeTranscription.isNotBlank()) {
                                        viewModel.seedStudyQA("Design an interactive multiple-choice study quiz with answers based directly on this text:\n\nAmharic: $activeTranscription\nTranslation: $activeTranslation")
                                        onSelectTab(ActiveWorkspace.TUTOR_BOT)
                                    } else {
                                        viewModel.seedStudyQA("Give me a quick 3-question multiple choice test on standard Amharic vocab words.")
                                        onSelectTab(ActiveWorkspace.TUTOR_BOT)
                                    }
                                },
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.height(34.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
                            ) {
                                Text("Create Quiz", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // Save Note segment
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)),
                            RoundedCornerShape(24.dp)
                        ),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "💾 Archive Workspace to Study Library",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        OutlinedTextField(
                            value = noteTitleInput,
                            onValueChange = onTitleChange,
                            label = { Text("Note Title (e.g. Greetings Quiz)") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                              ),
                            shape = RoundedCornerShape(10.dp)
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Button(
                            onClick = {
                                viewModel.saveCurrentSession(noteTitleInput)
                                onTitleChange("")
                                onSelectTab(ActiveWorkspace.HISTORY)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                                .testTag("save_button"),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Text("Archive Study Note in Library", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
            } else {
                // SUBTAB 1: TOOLS & ADVANCED SYSTEM SETTINGS
                val isDark by viewModel.isDarkMode.collectAsStateWithLifecycle()
                val isOffline by viewModel.isOfflineMode.collectAsStateWithLifecycle()
                val isEncrypted by viewModel.isLocalEncryptionActive.collectAsStateWithLifecycle()

                // Settings section
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)),
                            RoundedCornerShape(24.dp)
                        ),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("⚙️ Advanced Toolset Settings", fontSize = 14.sp, fontWeight = FontWeight.Bold)

                        // 1. Dark Mode Switch
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text("Dynamic Dark Mode Theme", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text("Toggles interface lighting modes", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = isDark, onCheckedChange = { viewModel.toggleDarkMode() })
                        }

                        Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))

                        // 2. Offline Mode Switch
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text("🔌 Local Offline Mode", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text("Uses local models when network is cut", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = isOffline, onCheckedChange = { viewModel.toggleOfflineMode() })
                        }

                        Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))

                        // 3. Database Encryption Switch
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text("🔒 SQLite Database Encryption", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text("XOR encrypts text data in local DB on-the-fly", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = isEncrypted, onCheckedChange = { viewModel.toggleLocalEncryption() })
                        }
                    }
                }

                // Cloud Sync & Local Backup Section
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)), RoundedCornerShape(24.dp)),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("📤 Backup Synchronizer (Local Cloud-Sync)", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("Export or import study databases as text documents for secure manual sync.", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                        Spacer(modifier = Modifier.height(10.dp))

                        var jsonInputText by remember { mutableStateOf("") }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    val path = viewModel.exportBackupToJson(context)
                                    if (path != null) {
                                        val backupFile = java.io.File(path)
                                        if (backupFile.exists()) {
                                            val uri = androidx.core.content.FileProvider.getUriForFile(context, "com.example.provider", backupFile)
                                            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                                type = "application/json"
                                                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(android.content.Intent.createChooser(intent, "Share SQLite Study Backup XML/JSON"))
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f).height(36.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Export Backup", fontSize = 11.sp)
                            }

                            Button(
                                onClick = {
                                    if (jsonInputText.isNotBlank()) {
                                        viewModel.restoreBackupFromJson(jsonInputText)
                                        jsonInputText = ""
                                    } else {
                                        Toast.makeText(context, "Paste the valid backup JSON string first.", Toast.LENGTH_LONG).show()
                                    }
                                },
                                modifier = Modifier.weight(1f).height(36.dp),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Restore JSON", fontSize = 11.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = jsonInputText,
                            onValueChange = { jsonInputText = it },
                            placeholder = { Text("Paste study backup JSON object here to fully restore database.", fontSize = 11.sp) },
                            modifier = Modifier.fillMaxWidth().height(65.dp),
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TutorChatWorkspace(
    viewModel: WorkspaceViewModel,
    chatMessages: List<ChatMessage>,
    chatInput: String,
    chatLoading: Boolean
) {
    val coroutineScope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize()) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)),
                    RoundedCornerShape(20.dp)
                ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("👩‍🏫", fontSize = 28.sp)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "የአማርኛ ረዳት (Amharic Tutor)",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Your persistent AI study tutor powered by Gemini.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                }
                IconButton(onClick = { viewModel.clearAllChatMessages() }) {
                    Text("🗑️", fontSize = 18.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Chat Bubble list in clean high-contrast container
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp))
                .border(
                    BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)),
                    RoundedCornerShape(24.dp)
                )
                .padding(12.dp)
        ) {
            if (chatMessages.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("✨ Ask Amharic study questions!", color = MaterialTheme.colorScheme.primary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = "Your queries and chat history are saved automatically so you can study your answers later.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text("Tap a sample study topic to begin:", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)

                    Spacer(modifier = Modifier.height(12.dp))

                    val context = LocalContext.current
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.seedStudyQA("Quiz me on basic Amharic Greetings!") },
                            modifier = Modifier.padding(horizontal = 4.dp),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text("📝 Greetings Quiz", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }

                        OutlinedButton(
                            onClick = { viewModel.seedStudyQA("Explain how Amharic noun pluralization works.") },
                            modifier = Modifier.padding(horizontal = 4.dp),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text("📚 Noun Grammar", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }

                        OutlinedButton(
                            onClick = { viewModel.seedStudyQA("Provide 5 Amharic phrases useful for travelers with transcriptions.") },
                            modifier = Modifier.padding(horizontal = 4.dp),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text("✈️ Travel Phrases", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    reverseLayout = false,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(chatMessages) { msg ->
                        ChatBubbleItem(msg)
                    }

                    if (chatLoading) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                horizontalArrangement = Arrangement.Start
                            ) {
                                Box(
                                    modifier = Modifier
                                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
                                        .padding(12.dp)
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Message input bar (With modern rounded pill styled outline)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = chatInput,
                onValueChange = { viewModel.updateChatInput(it) },
                placeholder = { Text("Ask about vocabulary, grammar...", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 14.sp) },
                modifier = Modifier
                    .weight(1f)
                    .testTag("chat_input"),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                ),
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { viewModel.sendChatMessage() })
            )

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = { viewModel.sendChatMessage() },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .size(50.dp)
                    .testTag("chat_send_button"),
                shape = CircleShape,
                contentPadding = PaddingValues(0.dp)
            ) {
                Text("🚀", fontSize = 18.sp)
            }
        }
    }
}

@Composable
fun ChatBubbleItem(message: ChatMessage) {
    val isUser = message.role == "user"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .background(
                    if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isUser) 16.dp else 2.dp,
                        bottomEnd = if (isUser) 2.dp else 16.dp
                    )
                )
                .border(
                    BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.05f)),
                    shape = RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isUser) 16.dp else 2.dp,
                        bottomEnd = if (isUser) 2.dp else 16.dp
                    )
                )
                .padding(14.dp)
        ) {
            Column {
                Text(
                    text = if (isUser) "You / ተማሪ" else "Tutor / የአማርኛ ረዳት",
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = message.text,
                    color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

fun sharePdfReport(context: Context, session: TranscriptionSession, decryptedAmharic: String, decryptedEnglish: String) {
    try {
        val pdfDocument = android.graphics.pdf.PdfDocument()
        val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, 1).create()
        val page = pdfDocument.startPage(pageInfo)
        val canvas = page.canvas
        val paint = android.graphics.Paint()
        
        paint.textSize = 20f
        paint.isFakeBoldText = true
        canvas.drawText("Abyssinia Study Session Report", 40f, 60f, paint)
        
        paint.textSize = 12f
        paint.isFakeBoldText = false
        paint.color = android.graphics.Color.DKGRAY
        canvas.drawText("Title: ${session.title}", 40f, 100f, paint)
        canvas.drawText("Duration: ${session.durationSeconds}s", 40f, 120f, paint)
        
        paint.color = android.graphics.Color.BLACK
        paint.textSize = 14f
        paint.isFakeBoldText = true
        canvas.drawText("Amharic Text:", 40f, 160f, paint)
        
        paint.textSize = 12f
        paint.isFakeBoldText = false
        var y = 190f
        decryptedAmharic.split("\n").forEach { line ->
            if (y < 400f) {
                canvas.drawText(line, 40f, y, paint)
                y += 18f
            }
        }
        
        paint.textSize = 14f
        paint.isFakeBoldText = true
        canvas.drawText("English Translation:", 40f, 440f, paint)
        
        paint.textSize = 12f
        paint.isFakeBoldText = false
        y = 470f
        decryptedEnglish.split("\n").forEach { line ->
            if (y < 800f) {
                canvas.drawText(line, 40f, y, paint)
                y += 18f
            }
        }
        
        pdfDocument.finishPage(page)
        
        val file = java.io.File(context.cacheDir, "Abyssinia_Study_Report_${session.id}.pdf")
        pdfDocument.writeTo(file.outputStream())
        pdfDocument.close()
        
        val contentUri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "com.example.provider",
            file
        )
        
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(android.content.Intent.EXTRA_STREAM, contentUri)
            putExtra(android.content.Intent.EXTRA_SUBJECT, "Share Amharic Study Note Document")
            putExtra(android.content.Intent.EXTRA_TEXT, "Exported PDF study notes from Abyssinia Study Workspace.")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(android.content.Intent.createChooser(intent, "Share PDF Note Report"))
    } catch (e: Exception) {
        Toast.makeText(context, "Error sharing PDF: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

fun shareTextFile(context: Context, session: TranscriptionSession, decryptedAmharic: String, decryptedEnglish: String) {
    try {
        val file = java.io.File(context.cacheDir, "Abyssinia_Study_Text_${session.id}.txt")
        val content = """
            Abyssinia Study Workspace Report
            Title: ${session.title}
            Duration: ${session.durationSeconds}s
            
            [Amharic Script Transcription]
            $decryptedAmharic
            
            [Translation Output]
            $decryptedEnglish
        """.trimIndent()
        
        file.writeText(content)
        val contentUri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "com.example.provider",
            file
        )
        
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_STREAM, contentUri)
            putExtra(android.content.Intent.EXTRA_SUBJECT, "Exported Study Text file")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(android.content.Intent.createChooser(intent, "Share Raw TXT document"))
    } catch (e: Exception) {
        Toast.makeText(context, "Error sharing text file: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HistoryWorkspace(
    viewModel: WorkspaceViewModel,
    transcriptions: List<TranscriptionSession>,
    clipboardManager: androidx.compose.ui.platform.ClipboardManager
) {
    val context = LocalContext.current
    
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        item {
            Text(
                text = "📚 Stored Study Sessions",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }

        if (transcriptions.isEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)),
                            RoundedCornerShape(20.dp)
                        ),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("📂 Workspace Empty", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = "Record or transcribe Amharic vocabulary and hit the Save button to start building your portfolio of studies.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        } else {
            items(transcriptions) { session ->
                val decryptedAmharic = remember(session.amharicText) { viewModel.decryptString(session.amharicText) }
                val decryptedEnglish = remember(session.englishTranslation) { viewModel.decryptString(session.englishTranslation) }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)),
                            RoundedCornerShape(24.dp)
                        ),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = session.title,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "🎤 Duration: ${session.durationSeconds}s",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp
                                )
                            }

                            Row {
                                IconButton(onClick = {
                                    clipboardManager.setText(AnnotatedString("${decryptedAmharic}\n\nTranslation:\n${decryptedEnglish}"))
                                    Toast.makeText(context, "Note Copied to Clipboard!", Toast.LENGTH_SHORT).show()
                                }) {
                                    Text("📋", fontSize = 16.sp)
                                }
                                IconButton(onClick = { viewModel.deleteSession(session.id) }) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete Session",
                                        tint = Color(0xFFEF4444)
                                    )
                                }
                            }
                        }

                        // Share / Export Action buttons row!
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { sharePdfReport(context, session, decryptedAmharic, decryptedEnglish) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(30.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                shape = RoundedCornerShape(15.dp)
                            ) {
                                Text("📄 Export PDF", fontSize = 10.sp, color = Color.White)
                            }

                            Button(
                                onClick = { shareTextFile(context, session, decryptedAmharic, decryptedEnglish) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(30.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                                shape = RoundedCornerShape(15.dp)
                            ) {
                                Text("💾 Export .TXT", fontSize = 10.sp, color = Color.White)
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Amharic Container Box (Soft violet tinted card)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.04f)), RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        ) {
                            Column {
                                Text(
                                    text = "የአማርኛ ግልባጭ (Amharic Transcription)",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = decryptedAmharic,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 13.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // English Container Box (Soft tinted card)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.04f)), RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        ) {
                            Column {
                                Text(
                                    text = "English Translation",
                                    color = MaterialTheme.colorScheme.secondary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = decryptedEnglish,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// Custom model representing Fidel syllables
data class FidelSyllable(
    val character: String,
    val translation: String,
    val pronuciation: String
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OcrWorkspaceScreen(viewModel: WorkspaceViewModel) {
    val context = LocalContext.current
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    
    val selectedOcrUri by viewModel.selectedOcrImageUri.collectAsStateWithLifecycle()
    val imageBatchQueue by viewModel.imageBatchQueue.collectAsStateWithLifecycle()
    val isBatchProcessing by viewModel.isBatchProcessing.collectAsStateWithLifecycle()
    val batchProgress by viewModel.batchProgressMessage.collectAsStateWithLifecycle()
    
    val sourceLang by viewModel.selectedSourceLang.collectAsStateWithLifecycle()
    val targetLang by viewModel.selectedTargetLang.collectAsStateWithLifecycle()
    
    val transcriptionLoading by viewModel.transcriptionLoading.collectAsStateWithLifecycle()
    val activeTranscription by viewModel.activeTranscriptionText.collectAsStateWithLifecycle()
    val activeTranslation by viewModel.activeTranslationText.collectAsStateWithLifecycle()

    var noteTitleInput by remember { mutableStateOf("") }

    // Set up Photo File Provider Uri for Camera capturing!
    val tempFile = remember {
        java.io.File(context.cacheDir, "camera_capture_ocr.jpg").apply {
            deleteOnExit()
        }
    }
    val tempUri = remember {
        androidx.core.content.FileProvider.getUriForFile(
            context,
            "com.example.provider",
            tempFile
        )
    }

    // Camera picture taker launcher
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            viewModel.updateSelectedOcrImageUri(tempUri)
            Toast.makeText(context, "Photo captured! Ready to scan.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Camera photo capture cancelled.", Toast.LENGTH_SHORT).show()
        }
    }

    // Pick visual media launch (Single image visual OCR)
    val singleImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { viewModel.updateSelectedOcrImageUri(it) }
    }

    // Pick multiple visual media launch (Batch processing queue)
    val batchImagesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            viewModel.addImageToBatchQueue(uris)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)), RoundedCornerShape(24.dp)),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("📷 Optical Character Recognition (OCR) Desk", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text("Supports printed text and handwritten document scans for Amharic, Tigrinya, Oromo, Somali, etc., with automatic original language/script detection.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                Spacer(modifier = Modifier.height(10.dp))

                // Language selection options
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Language Script", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        var expandedSource by remember { mutableStateOf(false) }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(38.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                                .clickable { expandedSource = true }
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Text(sourceLang, fontSize = 11.sp)
                            DropdownMenu(expanded = expandedSource, onDismissRequest = { expandedSource = false }) {
                                listOf("Auto-Detect", "Amharic (አማርኛ)", "Tigrinya (ትግርኛ)", "Oromo (Afaan Oromoo)", "Somali (Af-Soomaali)", "English").forEach { lang ->
                                    DropdownMenuItem(text = { Text(lang) }, onClick = {
                                        viewModel.updateSelectedSourceLang(lang)
                                        expandedSource = false
                                    })
                                }
                            }
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text("Translate Target", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                        var expandedTarget by remember { mutableStateOf(false) }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(38.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                                .clickable { expandedTarget = true }
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Text(targetLang, fontSize = 11.sp)
                            DropdownMenu(expanded = expandedTarget, onDismissRequest = { expandedTarget = false }) {
                                listOf("None", "English", "Amharic (አማርኛ)", "Tigrinya (ትግርኛ)", "Oromo (Afaan Oromoo)", "Somali (Af-Soomaali)").forEach { lang ->
                                    DropdownMenuItem(text = { Text(lang) }, onClick = {
                                        viewModel.updateSelectedTargetLang(lang)
                                        expandedTarget = false
                                    })
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Picker and Camera launch buttons
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { cameraLauncher.launch(tempUri) },
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("📸 Take Photo", fontSize = 11.sp, maxLines = 1)
                    }

                    Button(
                        onClick = { singleImageLauncher.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("🏞️ Pick Gallery", fontSize = 11.sp, maxLines = 1)
                    }

                    OutlinedButton(
                        onClick = { batchImagesLauncher.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        modifier = Modifier
                            .weight(1.1f)
                            .height(38.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("📚 Add Multi", fontSize = 11.sp, maxLines = 1)
                    }
                }

                // Batch Queue Indicators
                if (imageBatchQueue.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.05f))
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("📸 Batch OCR Images: ${imageBatchQueue.size} Files Selected", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { viewModel.processImageBatch(context) },
                                    modifier = Modifier.weight(1f).height(32.dp),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Text("Run Batch OCR", fontSize = 10.sp)
                                }
                                OutlinedButton(
                                    onClick = { viewModel.clearImageBatchQueue() },
                                    modifier = Modifier.weight(1f).height(32.dp),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Text("Clear Queue", fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }

                if (isBatchProcessing && batchProgress.isNotEmpty()) {
                    Text(batchProgress, color = MaterialTheme.colorScheme.secondary, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                }

                // Selected single image preview and Scan Button
                if (selectedOcrUri != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("📄 Image Selected", fontSize = 11.sp, modifier = Modifier.weight(1f))
                        Spacer(modifier = Modifier.width(6.dp))
                        Button(
                            onClick = { viewModel.triggerImageOcr(context) },
                            modifier = Modifier.height(30.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                            shape = RoundedCornerShape(15.dp)
                        ) {
                            if (transcriptionLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(12.dp), color = Color.White)
                            } else {
                                Text("Scan Script", fontSize = 10.sp, color = Color.White)
                            }
                        }
                    }
                }
            }
        }

        // Handwriting Style Canvas
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)), RoundedCornerShape(24.dp)),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("✍️ Personal Handwriting Style Trainer", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("Trace complex Ge'ez syllables or letters below. This feeds into personalized script recognition profiles.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                Spacer(modifier = Modifier.height(10.dp))

                var charInputText by remember { mutableStateOf("ሀ") }
                val points = remember { mutableStateListOf<androidx.compose.ui.geometry.Offset>() }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                        .clip(RoundedCornerShape(12.dp))
                ) {
                    androidx.compose.foundation.Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragStart = { points.add(it) },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        points.add(change.position)
                                    },
                                    onDragEnd = { points.add(androidx.compose.ui.geometry.Offset.Unspecified) }
                                )
                            }
                    ) {
                        for (i in 0 until points.size - 1) {
                            if (points[i] != androidx.compose.ui.geometry.Offset.Unspecified && points[i+1] != androidx.compose.ui.geometry.Offset.Unspecified) {
                                drawLine(
                                    color = Color(0xFF10B981),
                                    start = points[i],
                                    end = points[i+1],
                                    strokeWidth = 6f
                                )
                            }
                        }
                    }

                    if (points.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Draw Letter Here with Finger", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = charInputText,
                        onValueChange = { charInputText = it },
                        label = { Text("Char") },
                        modifier = Modifier.width(90.dp).height(50.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary)
                    )

                    Button(
                        onClick = {
                            if (charInputText.isNotEmpty()) {
                                viewModel.addHandwritingTrainingStyle(charInputText)
                                points.clear()
                            }
                        },
                        modifier = Modifier.weight(1f).height(38.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Register Style", fontSize = 11.sp, maxLines = 1)
                    }

                    OutlinedButton(
                        onClick = { points.clear() },
                        modifier = Modifier.height(38.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Clear Pad", fontSize = 11.sp)
                    }
                }

                val trainedProfiles by viewModel.savedHandwritingStyles.collectAsStateWithLifecycle()
                if (trainedProfiles.isNotEmpty()) {
                    Text(
                        "Registered Style Profiles: ${trainedProfiles.joinToString(", ")}",
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // --- EXTREMELY HELPFUL DETAILED SCRIPT & TRANSLATION OUTPUT DIRECTLY ON OCR PAGE! ---
        // Original Script output box
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.06f)), RoundedCornerShape(24.dp)),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Box(
                    modifier = Modifier
                        .width(6.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary)
                )
                Column(modifier = Modifier.padding(16.dp).weight(1f)) {
                    Text(
                        text = "OCR / Transcription Original Script (ጽሑፍ)",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    OutlinedTextField(
                        value = activeTranscription,
                        onValueChange = { viewModel.updateActiveTranscriptionText(it) },
                        placeholder = { Text("Recognized script appears here. Edit to modify results directly if needed.", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 12.sp) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = { viewModel.triggerTranslation(activeTranscription) },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                        modifier = Modifier.align(Alignment.End).height(36.dp),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Text("🌐 Translate Script", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                }
            }
        }

        // Translation output box
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)), RoundedCornerShape(24.dp)),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("📝", fontSize = 16.sp, modifier = Modifier.padding(end = 6.dp))
                        Text(
                            text = "OCR / Transcription English Translation",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    IconButton(
                        onClick = {
                            if (activeTranslation.isNotBlank()) {
                                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(activeTranslation))
                                Toast.makeText(context, "Translation Copied!", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Text("📋", fontSize = 14.sp)
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                OutlinedTextField(
                    value = activeTranslation,
                    onValueChange = { viewModel.updateActiveTranslationText(it) },
                    placeholder = { Text("Translation output appears here.", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 12.sp) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(90.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                )
            }
        }

        // Archive / Save Note directly on the page!
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)), RoundedCornerShape(24.dp)),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "💾 Save OCR/Transcription Result to Library",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(6.dp))

                OutlinedTextField(
                    value = noteTitleInput,
                    onValueChange = { noteTitleInput = it },
                    label = { Text("Note Title (e.g. OCR Document Scan)") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                    ),
                    shape = RoundedCornerShape(10.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                Button(
                    onClick = {
                        viewModel.saveCurrentSession(noteTitleInput)
                        noteTitleInput = ""
                        Toast.makeText(context, "Note Saved to Library!", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text("Archive Study Note in Library", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 12.sp)
                }
            }
        }
    }
}

package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
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
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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

// Unified clipboard copy helper ensuring both Compose and Android system clipboard compatibility
fun copyTextToClipboard(
    context: Context,
    clipboardManager: androidx.compose.ui.platform.ClipboardManager,
    label: String,
    text: String
) {
    if (text.isBlank()) {
        Toast.makeText(context, "Nothing to copy", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        clipboardManager.setText(AnnotatedString(text))
    } catch (_: Exception) {}

    try {
        val sysClipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        sysClipboard?.setPrimaryClip(clip)
    } catch (_: Exception) {}

    Toast.makeText(context, "$label Copied to Clipboard! 📋", Toast.LENGTH_SHORT).show()
}

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
    TRANSCRIBE, TUTOR_BOT, HISTORY, OCR, REVIEW, SETTINGS
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
                // Transcribe (Voice) Tab
                NavigationBarItem(
                    selected = activeTab == ActiveWorkspace.TRANSCRIBE,
                    onClick = { activeTab = ActiveWorkspace.TRANSCRIBE },
                    modifier = Modifier.testTag("nav_tab_voice"),
                    icon = { Text("🎙️", fontSize = 20.sp) },
                    label = {
                        Text(
                            text = "Voice",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
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
                    modifier = Modifier.testTag("nav_tab_tutor"),
                    icon = { Text("💬", fontSize = 20.sp) },
                    label = {
                        Text(
                            text = "Tutor AI",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f),
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                )

                // History (Library) Tab
                NavigationBarItem(
                    selected = activeTab == ActiveWorkspace.HISTORY,
                    onClick = { activeTab = ActiveWorkspace.HISTORY },
                    modifier = Modifier.testTag("nav_tab_library"),
                    icon = { Text("📚", fontSize = 20.sp) },
                    label = {
                        Text(
                            text = "Library",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
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
                    modifier = Modifier.testTag("nav_tab_ocr"),
                    icon = { Text("📷", fontSize = 20.sp) },
                    label = {
                        Text(
                            text = "OCR",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f),
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                )

                // Review Tab
                NavigationBarItem(
                    selected = activeTab == ActiveWorkspace.REVIEW,
                    onClick = { activeTab = ActiveWorkspace.REVIEW },
                    modifier = Modifier.testTag("nav_tab_review"),
                    icon = { Text("🔤", fontSize = 20.sp) },
                    label = {
                        Text(
                            text = "Review",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f),
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                )

                // Settings Tab
                NavigationBarItem(
                    selected = activeTab == ActiveWorkspace.SETTINGS,
                    onClick = { activeTab = ActiveWorkspace.SETTINGS },
                    modifier = Modifier.testTag("nav_tab_settings"),
                    icon = { Text("⚙️", fontSize = 20.sp) },
                    label = {
                        Text(
                            text = "Settings",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
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

                    val byokKeys by viewModel.byokKeys.collectAsStateWithLifecycle()
                    val activeKey = byokKeys.firstOrNull { it.isActive } ?: byokKeys.firstOrNull()
                    Box(
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.secondary, RoundedCornerShape(12.dp))
                            .clickable { activeTab = ActiveWorkspace.SETTINGS }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = activeKey?.let {
                                when (it.provider) {
                                    com.example.utils.ByokProvider.GEMINI -> "Gemini BYOK"
                                    com.example.utils.ByokProvider.OPENAI -> "OpenAI BYOK"
                                    com.example.utils.ByokProvider.ANTHROPIC -> "Claude BYOK"
                                }
                            } ?: "Add API Key",
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
                        com.example.ui.HistoryWorkspace(
                            viewModel = viewModel,
                            transcriptions = transcriptions,
                            clipboardManager = clipboardManager,
                            onSelectTab = { activeTab = it }
                        )
                    }

                    ActiveWorkspace.OCR -> {
                        com.example.ui.OcrWorkspaceScreen(viewModel = viewModel)
                    }
                    ActiveWorkspace.REVIEW -> {
                        com.example.ui.ReviewWorkspaceScreen(viewModel = viewModel)
                    }
                    ActiveWorkspace.SETTINGS -> {
                        com.example.ui.SettingsWorkspaceScreen(viewModel = viewModel)
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
    val activeAudioPath by viewModel.activeAudioFilePath.collectAsStateWithLifecycle()
    val activeAudioName by viewModel.activeAudioFileName.collectAsStateWithLifecycle()
    val activeAudioDisplayName by viewModel.activeAudioDisplayName.collectAsStateWithLifecycle()
    val activeAudioSizeKb by viewModel.activeAudioSizeKb.collectAsStateWithLifecycle()
    val playingAudioPath by viewModel.playingAudioPath.collectAsStateWithLifecycle()
    val targetLang by viewModel.selectedTargetLang.collectAsStateWithLifecycle()
    val detectedLanguage by viewModel.detectedLanguage.collectAsStateWithLifecycle()
    val lastProcessingStats by viewModel.lastProcessingStats.collectAsStateWithLifecycle()
    val studyBreakdownText by viewModel.studyBreakdownText.collectAsStateWithLifecycle()
    val studyBreakdownLoading by viewModel.studyBreakdownLoading.collectAsStateWithLifecycle()

    var subTab by remember { mutableStateOf(0) } // 0: Voice Desk, 1: Settings & Sync

    val context = LocalContext.current

    // SAF launcher to download the device-kept voice file to Downloads / user folder (zero permissions)
    val downloadVoiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/*")
    ) { destUri ->
        val src = activeAudioPath
        if (destUri != null && src != null) {
            viewModel.exportAudioFileToUri(context, src, destUri)
        }
    }

    // File Picker for custom local audio imports
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            viewModel.loadUploadedAudioFile(it, context)
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

                        Spacer(modifier = Modifier.height(6.dp))

                        // Optional voice name input (applies before recording or saves/renames active clip)
                        OutlinedTextField(
                            value = activeAudioDisplayName,
                            onValueChange = { viewModel.updateActiveAudioDisplayName(it) },
                            label = { Text("Voice Clip Name (optional)", fontSize = 11.sp) },
                            singleLine = true,
                            trailingIcon = if (activeAudioPath != null && activeAudioDisplayName.isNotBlank()) {
                                {
                                    IconButton(
                                        onClick = { viewModel.renameActiveVoiceRecording(activeAudioDisplayName) },
                                        modifier = Modifier.testTag("rename_active_voice_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Save voice clip name",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            } else null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                                .testTag("active_voice_name_input"),
                            shape = RoundedCornerShape(12.dp)
                        )

                        // Kept Voice on Device Card (Play, Download & Delete)
                        if (activeAudioPath != null) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp)
                                    .testTag("kept_voice_card")
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        Text(
                                            text = "💾 Kept on Device: ${activeAudioDisplayName.ifBlank { activeAudioName ?: "voice.mp4" }}",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${activeAudioName ?: "voice.mp4"}${activeAudioSizeKb?.let { " • ${it} KB" } ?: ""} • Also listed in Library -> Saved Voices",
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val isPlayingActive = playingAudioPath == activeAudioPath
                                        FilledTonalButton(
                                            onClick = { activeAudioPath?.let { viewModel.toggleAudioPlayback(it) } },
                                            enabled = !isRecording,
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(36.dp)
                                                .testTag("play_active_voice_button"),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                            shape = RoundedCornerShape(16.dp)
                                        ) {
                                            Text(if (isPlayingActive) "⏹️ Stop" else "▶️ Play", fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                        }
                                        OutlinedButton(
                                            onClick = { downloadVoiceLauncher.launch(activeAudioName ?: "amharic_voice.mp4") },
                                            enabled = !isRecording,
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(36.dp)
                                                .testTag("download_active_voice_button"),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                            shape = RoundedCornerShape(16.dp)
                                        ) {
                                            Text("⬇️ Save", fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                        }
                                        OutlinedButton(
                                            onClick = { viewModel.deleteActiveVoiceRecording() },
                                            enabled = !isRecording,
                                            border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.5f)),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444)),
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(36.dp)
                                                .testTag("delete_active_voice_button"),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                            shape = RoundedCornerShape(16.dp)
                                        ) {
                                            Text("🗑️ Delete", fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                        }
                                    }
                                }
                            }
                        }

                        // Transcribe Button + Cancel when active
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { viewModel.triggerTranscription() },
                                enabled = !transcriptionLoading,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(42.dp)
                                    .testTag("transcribe_button"),
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                if (transcriptionLoading) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Transcribing...", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                } else {
                                    Text("✨ Transcribe Voice Audio", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }
                            if (transcriptionLoading) {
                                OutlinedButton(
                                    onClick = { viewModel.cancelActiveProcessing() },
                                    modifier = Modifier
                                        .height(42.dp)
                                        .testTag("cancel_processing_button"),
                                    shape = RoundedCornerShape(20.dp)
                                ) {
                                    Text("Cancel", fontSize = 11.sp, color = Color(0xFFEF4444), fontWeight = FontWeight.Bold)
                                }
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
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Transcription Workspace Text (የጽሑፍ ግልባጭ)",
                                        color = MaterialTheme.colorScheme.primary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (detectedLanguage != null || lastProcessingStats != null) {
                                        Text(
                                            text = listOfNotNull(detectedLanguage, lastProcessingStats).joinToString(" • "),
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Row {
                                    IconButton(
                                        onClick = { viewModel.speakPronunciation(context, activeTranscription) },
                                        modifier = Modifier
                                            .size(32.dp)
                                            .testTag("speak_transcription_button")
                                    ) {
                                        Text("🔊", fontSize = 14.sp)
                                    }
                                    IconButton(
                                        onClick = {
                                            copyTextToClipboard(context, clipboardManager, "Transcription", activeTranscription)
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Text("📋", fontSize = 14.sp)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            OutlinedTextField(
                                value = activeTranscription,
                                onValueChange = { viewModel.updateActiveTranscriptionText(it) },
                                placeholder = { Text("Transcribed script appears here. Translation runs only when you click Translate.", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 12.sp) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(95.dp)
                                    .testTag("voice_transcription_text_field"),
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

                            // On-demand Target Language selector + Study Breakdown + Translate Trigger
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                var expandedVoiceTarget by remember { mutableStateOf(false) }
                                val displayTarget = if (targetLang == "None") "English" else targetLang
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(42.dp)
                                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                                        .clickable { expandedVoiceTarget = true }
                                        .padding(horizontal = 8.dp),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    Text("To: $displayTarget", fontSize = 10.sp, maxLines = 1, fontWeight = FontWeight.Medium)
                                    DropdownMenu(expanded = expandedVoiceTarget, onDismissRequest = { expandedVoiceTarget = false }) {
                                        listOf("English", "Amharic (አማርኛ)", "Tigrinya (ትግርኛ)", "Oromo (Afaan Oromoo)", "Somali (Af-Soomaali)", "Arabic", "French").forEach { lang ->
                                            DropdownMenuItem(text = { Text(lang) }, onClick = {
                                                viewModel.updateSelectedTargetLang(lang)
                                                expandedVoiceTarget = false
                                            })
                                        }
                                    }
                                }

                                OutlinedButton(
                                    onClick = { viewModel.triggerStudyBreakdown(activeTranscription) },
                                    enabled = !studyBreakdownLoading && activeTranscription.isNotBlank(),
                                    modifier = Modifier
                                        .height(42.dp)
                                        .testTag("study_breakdown_button"),
                                    shape = RoundedCornerShape(18.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                                ) {
                                    Text(if (studyBreakdownLoading) "..." else "✨ Breakdown", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                }

                                Button(
                                    onClick = { viewModel.triggerTranslation(activeTranscription) },
                                    enabled = !translationLoading && activeTranscription.isNotBlank(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.secondary,
                                        contentColor = MaterialTheme.colorScheme.onSecondary
                                    ),
                                    modifier = Modifier
                                        .height(42.dp)
                                        .testTag("translate_button"),
                                    shape = RoundedCornerShape(18.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                ) {
                                    if (translationLoading) {
                                        CircularProgressIndicator(modifier = Modifier.size(14.dp), color = MaterialTheme.colorScheme.onSecondary, strokeWidth = 2.dp)
                                    } else {
                                        Text("🌐 Translate Text", fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }

                // On-demand Study Breakdown Card
                if (studyBreakdownLoading || studyBreakdownText.isNotBlank()) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)), RoundedCornerShape(20.dp)),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("✨ Vocabulary & Grammar Breakdown", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                TextButton(onClick = { viewModel.clearStudyBreakdown() }, modifier = Modifier.height(28.dp)) {
                                    Text("Dismiss", fontSize = 10.sp)
                                }
                            }
                            Text(
                                text = "AI-generated study note — Unverified, needs native-speaker or linguist review.",
                                fontSize = 10.sp,
                                color = Color(0xFFB45309),
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            if (studyBreakdownLoading) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            } else {
                                SelectionContainer {
                                    Text(studyBreakdownText, fontSize = 12.sp, lineHeight = 18.sp)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Active Translation Outputs (Populated ONLY when user clicks Translate)
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
                                    text = "On-Demand Translation (${if (targetLang == "None") "English" else targetLang})",
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            IconButton(
                                onClick = {
                                    copyTextToClipboard(context, clipboardManager, "Translation", activeTranslation)
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Text("📋", fontSize = 14.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        OutlinedTextField(
                            value = activeTranslation,
                            onValueChange = { viewModel.updateActiveTranslationText(it) },
                            placeholder = { Text("Tap 'Translate Text' above when you want a translation.", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 12.sp) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(85.dp)
                                .testTag("voice_translation_text_field"),
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

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))

                        // 2. Offline Mode Switch
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text("🔌 Local Offline Mode", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text("Uses local models when network is cut", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = isOffline, onCheckedChange = { viewModel.toggleOfflineMode() })
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))

                        // 3. Database Encryption Switch
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("🔒 SQLite Database Encryption", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text("AES-256-GCM encrypts notes with per-note random IV via Android KeyStore", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        Text("📤 Backup Synchronizer (Local Study Sync)", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("Export plaintext JSON (with warning) or restore JSON/encrypted backups. Use Settings for passphrase-encrypted export.", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                        Spacer(modifier = Modifier.height(10.dp))

                        var jsonInputText by remember { mutableStateOf("") }
                        var showToolsPlaintextWarning by remember { mutableStateOf(false) }

                        if (showToolsPlaintextWarning) {
                            AlertDialog(
                                onDismissRequest = { showToolsPlaintextWarning = false },
                                title = { Text("⚠️ Security Warning: Plaintext Export", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFFEF4444)) },
                                text = {
                                    Text(
                                        "This exports your study notes as unencrypted plaintext JSON without AES-GCM protection. Use Settings -> Export Passphrase-Encrypted Backup for protected export.\n\nProceed with plaintext export?",
                                        fontSize = 12.sp
                                    )
                                },
                                confirmButton = {
                                    Button(
                                        onClick = {
                                            showToolsPlaintextWarning = false
                                            val path = viewModel.exportBackupToJson(context, passphrase = null)
                                            if (path != null) {
                                                val backupFile = java.io.File(path)
                                                if (backupFile.exists()) {
                                                    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.provider", backupFile)
                                                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                                        type = "application/json"
                                                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                    }
                                                    context.startActivity(android.content.Intent.createChooser(intent, "Share Study Notes Plaintext Backup"))
                                                }
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                                    ) {
                                        Text("Proceed with Plaintext")
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showToolsPlaintextWarning = false }) {
                                        Text("Cancel")
                                    }
                                }
                            )
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { showToolsPlaintextWarning = true },
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

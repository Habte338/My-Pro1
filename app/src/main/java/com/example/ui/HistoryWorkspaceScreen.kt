package com.example.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ActiveWorkspace
import com.example.copyTextToClipboard
import com.example.data.db.TranscriptionSession
import com.example.data.db.VoiceRecordingEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryWorkspace(
    viewModel: WorkspaceViewModel,
    transcriptions: List<TranscriptionSession>,
    clipboardManager: androidx.compose.ui.platform.ClipboardManager,
    onSelectTab: (ActiveWorkspace) -> Unit = {}
) {
    val context = LocalContext.current
    val playingAudioPath by viewModel.playingAudioPath.collectAsStateWithLifecycle()
    val voiceRecordings by viewModel.voiceRecordingsList.collectAsStateWithLifecycle()

    var librarySubTab by remember { mutableStateOf(0) } // 0: Study Notes, 1: Saved Voices
    var searchQuery by remember { mutableStateOf("") }
    var voiceSearchQuery by remember { mutableStateOf("") }
    var filterVoiceOnly by remember { mutableStateOf(false) }
    var pendingDownloadPath by remember { mutableStateOf<String?>(null) }

    var renameTargetVoice by remember { mutableStateOf<VoiceRecordingEntity?>(null) }
    var renameTextInput by remember { mutableStateOf("") }

    val downloadAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/*")
    ) { destUri ->
        val src = pendingDownloadPath
        pendingDownloadPath = null
        if (destUri != null && src != null) {
            viewModel.exportAudioFileToUri(context, src, destUri)
        }
    }

    val decryptedEntries = remember(transcriptions) {
        transcriptions.map { session ->
            Triple(
                session,
                viewModel.decryptString(session.amharicText),
                viewModel.decryptString(session.englishTranslation)
            )
        }
    }

    val filteredEntries = remember(decryptedEntries, searchQuery, filterVoiceOnly) {
        val q = searchQuery.trim().lowercase()
        decryptedEntries.filter { (session, amharic, translation) ->
            val matchesAudio = !filterVoiceOnly || (!session.filePath.isNullOrBlank() && File(session.filePath).exists())
            val matchesQuery = q.isEmpty() ||
                session.title.lowercase().contains(q) ||
                amharic.lowercase().contains(q) ||
                translation.lowercase().contains(q)
            matchesAudio && matchesQuery
        }
    }

    val filteredVoices = remember(voiceRecordings, voiceSearchQuery) {
        val q = voiceSearchQuery.trim().lowercase()
        voiceRecordings.filter { voice ->
            val exists = File(voice.filePath).exists()
            val matchesQuery = q.isEmpty() || voice.name.lowercase().contains(q)
            exists && matchesQuery
        }
    }

    if (renameTargetVoice != null) {
        val target = renameTargetVoice!!
        AlertDialog(
            onDismissRequest = { renameTargetVoice = null },
            title = { Text("Rename Saved Voice", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = renameTextInput,
                    onValueChange = { renameTextInput = it },
                    label = { Text("Voice Name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("rename_voice_input"),
                    shape = RoundedCornerShape(12.dp)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.renameVoiceRecording(target.id, renameTextInput)
                        renameTargetVoice = null
                    },
                    enabled = renameTextInput.isNotBlank(),
                    modifier = Modifier.testTag("confirm_rename_voice_button")
                ) {
                    Text("Save Name")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTargetVoice = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Pinned Library header & Study Notes / Saved Voices switcher
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "📚 Study Library & Saved Voices",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 2.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                    .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)), RoundedCornerShape(14.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (librarySubTab == 0) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .clickable { librarySubTab = 0 }
                        .testTag("library_notes_tab"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "📝 Study Notes (${transcriptions.size})",
                        color = if (librarySubTab == 0) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (librarySubTab == 1) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .clickable { librarySubTab = 1 }
                        .testTag("library_voices_tab"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "🎙️ Saved Voices (${voiceRecordings.size})",
                        color = if (librarySubTab == 1) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("library_lazy_list"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (librarySubTab == 0) {
                        Text(
                            text = "📚 Stored Study Sessions",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (transcriptions.isNotEmpty()) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = { Text("Search saved notes, Ge'ez script, or translations...", fontSize = 12.sp) },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search notes") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("library_search_input"),
                                shape = RoundedCornerShape(14.dp)
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                FilterChip(
                                    selected = filterVoiceOnly,
                                    onClick = { filterVoiceOnly = !filterVoiceOnly },
                                    label = { Text("🎤 With Voice Clip", fontSize = 11.sp) },
                                    modifier = Modifier.testTag("library_audio_filter_chip")
                                )
                                Text(
                                    text = "${filteredEntries.size} of ${transcriptions.size} notes",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        if (voiceRecordings.isNotEmpty()) {
                            OutlinedTextField(
                                value = voiceSearchQuery,
                                onValueChange = { voiceSearchQuery = it },
                                placeholder = { Text("Search saved voice recordings by name...", fontSize = 12.sp) },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search voices") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("library_voice_search_input"),
                                shape = RoundedCornerShape(14.dp)
                            )
                        }
                    }
                }
            }

        if (librarySubTab == 1) {
            if (filteredVoices.isEmpty()) {
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
                            Text(
                                text = if (voiceRecordings.isEmpty()) "🎙️ No Saved Voice Clips Yet" else "🔍 No Matching Voices",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (voiceRecordings.isEmpty()) {
                                    "Record or upload audio in the Voice tab — every clip is stored here so you can name, play, delete, or transcribe it anytime."
                                } else {
                                    "Try clearing your voice search query."
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }
            } else {
                items(filteredVoices, key = { "voice_${it.id}" }) { voice ->
                    val isPlayingVoice = playingAudioPath == voice.filePath
                    val sizeKb = (voice.sizeBytes / 1024L).coerceAtLeast(1L)
                    val dateText = remember(voice.createdAt) {
                        SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(voice.createdAt))
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)),
                                RoundedCornerShape(20.dp)
                            )
                            .testTag("voice_card_${voice.id}"),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = voice.name,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "$dateText • ${voice.durationSeconds}s • ${sizeKb} KB",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = {
                                            renameTextInput = voice.name
                                            renameTargetVoice = voice
                                        },
                                        modifier = Modifier
                                            .size(36.dp)
                                            .testTag("rename_voice_button_${voice.id}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Edit,
                                            contentDescription = "Rename Voice",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(19.dp)
                                        )
                                    }

                                    IconButton(
                                        onClick = { viewModel.deleteVoiceRecording(voice) },
                                        modifier = Modifier
                                            .size(36.dp)
                                            .testTag("delete_voice_button_${voice.id}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Delete Voice",
                                            tint = Color(0xFFEF4444),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilledTonalButton(
                                    onClick = { viewModel.toggleAudioPlayback(voice.filePath) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(36.dp)
                                        .testTag("play_library_voice_${voice.id}"),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Text(if (isPlayingVoice) "⏹️ Stop" else "▶️ Play", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                }

                                OutlinedButton(
                                    onClick = {
                                        pendingDownloadPath = voice.filePath
                                        downloadAudioLauncher.launch(File(voice.filePath).name)
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(36.dp)
                                        .testTag("download_library_voice_${voice.id}"),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Text("⬇️ Save", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                }

                                Button(
                                    onClick = {
                                        viewModel.loadVoiceRecordingForTranscription(voice, autoTranscribe = true)
                                        onSelectTab(ActiveWorkspace.TRANSCRIBE)
                                    },
                                    modifier = Modifier
                                        .weight(1.25f)
                                        .height(36.dp)
                                        .testTag("transcribe_library_voice_${voice.id}"),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Text("✨ Transcribe", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        } else if (filteredEntries.isEmpty()) {
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
                        Text(
                            text = if (transcriptions.isEmpty()) "📂 Workspace Empty" else "🔍 No Matching Notes",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (transcriptions.isEmpty()) {
                                "Record or transcribe Amharic vocabulary and save to start building your study library."
                            } else {
                                "Try clearing the search query or voice filter."
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        } else {
            items(filteredEntries, key = { it.first.id }) { (session, decryptedAmharic, decryptedEnglish) ->
                LaunchedEffect(session.id) {
                    viewModel.onNoteAccessed(session)
                }

                val hasStoredVoice = remember(session.filePath) {
                    val p = session.filePath
                    !p.isNullOrBlank() && File(p).exists() && File(p).length() > 0L
                }
                val isPlayingThis = hasStoredVoice && playingAudioPath == session.filePath

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)),
                            RoundedCornerShape(24.dp)
                        )
                        .testTag("note_card_${session.id}"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = session.title,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (hasStoredVoice) {
                                        "🎤 Voice kept (${session.durationSeconds}s)"
                                    } else {
                                        "📝 Text note • ${session.durationSeconds}s"
                                    },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Button(
                                    onClick = {
                                        copyTextToClipboard(
                                            context,
                                            clipboardManager,
                                            "Full Session",
                                            "${session.title}\n\n[Amharic / Ge'ez]:\n$decryptedAmharic\n\n[Translation]:\n$decryptedEnglish"
                                        )
                                    },
                                    modifier = Modifier.height(34.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                    shape = RoundedCornerShape(17.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                ) {
                                    Text("📋 Copy All", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                }

                                Spacer(modifier = Modifier.width(4.dp))

                                IconButton(
                                    onClick = {
                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_SUBJECT, session.title)
                                            putExtra(Intent.EXTRA_TEXT, "${session.title}\n\n$decryptedAmharic\n\nTranslation:\n$decryptedEnglish")
                                        }
                                        context.startActivity(Intent.createChooser(shareIntent, "Share Study Note"))
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Share,
                                        contentDescription = "Share Session",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                IconButton(
                                    onClick = { viewModel.deleteSession(session.id) },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete Session",
                                        tint = Color(0xFFEF4444),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }

                        // Kept voice controls: Play & Download to Device
                        if (hasStoredVoice && session.filePath != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val voiceFile = File(session.filePath)
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("🎙️ Voice Recording Kept on Device", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        Text(
                                            "${voiceFile.name} • ${(voiceFile.length() / 1024L).coerceAtLeast(1L)} KB",
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    FilledTonalButton(
                                        onClick = { viewModel.toggleAudioPlayback(session.filePath) },
                                        modifier = Modifier
                                            .height(32.dp)
                                            .testTag("play_saved_voice_${session.id}"),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                        shape = RoundedCornerShape(14.dp)
                                    ) {
                                        Text(if (isPlayingThis) "⏹️ Stop" else "▶️ Play", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            pendingDownloadPath = session.filePath
                                            downloadAudioLauncher.launch(voiceFile.name)
                                        },
                                        modifier = Modifier
                                            .height(32.dp)
                                            .testTag("download_saved_voice_${session.id}"),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                        shape = RoundedCornerShape(14.dp)
                                    ) {
                                        Text("⬇️ Download", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }

                        // Share / Export Action buttons row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { sharePdfReport(context, session, decryptedAmharic, decryptedEnglish) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(32.dp),
                                shape = RoundedCornerShape(16.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Text("📄 Export PDF", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                            }

                            OutlinedButton(
                                onClick = { shareTextFile(context, session, decryptedAmharic, decryptedEnglish) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(32.dp),
                                shape = RoundedCornerShape(16.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Text("💾 Export .TXT", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        // Amharic Container Box
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
                                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)), RoundedCornerShape(14.dp))
                                .padding(12.dp)
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "የአማርኛ ግልባጭ (Script)",
                                        color = MaterialTheme.colorScheme.primary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        FilledTonalButton(
                                            onClick = { viewModel.speakPronunciation(context, decryptedAmharic) },
                                            modifier = Modifier.height(28.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                            shape = RoundedCornerShape(14.dp)
                                        ) {
                                            Text("🔊 Listen", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                                        }
                                        FilledTonalButton(
                                            onClick = {
                                                copyTextToClipboard(
                                                    context,
                                                    clipboardManager,
                                                    "Amharic Script",
                                                    decryptedAmharic
                                                )
                                            },
                                            modifier = Modifier.height(28.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                            shape = RoundedCornerShape(14.dp)
                                        ) {
                                            Text("📋 Copy", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                SelectionContainer {
                                    Text(
                                        text = decryptedAmharic,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 13.sp,
                                        lineHeight = 18.sp
                                    )
                                }
                            }
                        }

                        if (decryptedEnglish.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                                    .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)), RoundedCornerShape(14.dp))
                                    .padding(12.dp)
                            ) {
                                Column {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Translation",
                                            color = MaterialTheme.colorScheme.secondary,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        FilledTonalButton(
                                            onClick = {
                                                copyTextToClipboard(
                                                    context,
                                                    clipboardManager,
                                                    "Translation",
                                                    decryptedEnglish
                                                )
                                            },
                                            modifier = Modifier.height(28.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                            shape = RoundedCornerShape(14.dp)
                                        ) {
                                            Text("📋 Copy Translation", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    SelectionContainer {
                                        Text(
                                            text = decryptedEnglish,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontSize = 13.sp,
                                            lineHeight = 18.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
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
        canvas.drawText("Translation:", 40f, 440f, paint)

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

        val file = File(context.cacheDir, "Abyssinia_Study_Report_${session.id}.pdf")
        pdfDocument.writeTo(file.outputStream())
        pdfDocument.close()

        val contentUri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            file
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, contentUri)
            putExtra(Intent.EXTRA_SUBJECT, "Share Amharic Study Note Document")
            putExtra(Intent.EXTRA_TEXT, "Exported PDF study notes from Abyssinia Study Workspace.")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share PDF Note Report"))
    } catch (e: Exception) {
        Toast.makeText(context, "Error sharing PDF: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

fun shareTextFile(context: Context, session: TranscriptionSession, decryptedAmharic: String, decryptedEnglish: String) {
    try {
        val file = File(context.cacheDir, "Abyssinia_Study_Text_${session.id}.txt")
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
            "${context.packageName}.provider",
            file
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, contentUri)
            putExtra(Intent.EXTRA_SUBJECT, "Exported Study Text file")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share Raw TXT document"))
    } catch (e: Exception) {
        Toast.makeText(context, "Error sharing text file: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

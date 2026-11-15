package com.example.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.utils.ByokProvider

@Composable
fun SettingsWorkspaceScreen(viewModel: WorkspaceViewModel) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    val isDarkMode by viewModel.isDarkMode.collectAsStateWithLifecycle()
    val isOfflineMode by viewModel.isOfflineMode.collectAsStateWithLifecycle()
    val isLocalEncryption by viewModel.isLocalEncryptionActive.collectAsStateWithLifecycle()
    val sourceLang by viewModel.selectedSourceLang.collectAsStateWithLifecycle()
    val targetLang by viewModel.selectedTargetLang.collectAsStateWithLifecycle()
    val transcriptions by viewModel.transcriptionsList.collectAsStateWithLifecycle()
    val chatMessages by viewModel.chatMessagesList.collectAsStateWithLifecycle()
    val securityStatus by viewModel.securityStatus.collectAsStateWithLifecycle()

    val byokKeys by viewModel.byokKeys.collectAsStateWithLifecycle()
    val isByokValidating by viewModel.isByokValidating.collectAsStateWithLifecycle()
    val byokFeedback by viewModel.byokFeedbackMessage.collectAsStateWithLifecycle()

    var selectedProvider by remember { mutableStateOf(ByokProvider.GEMINI) }
    var byokNickname by remember { mutableStateOf("") }
    var byokApiKey by remember { mutableStateOf("") }
    var byokModelId by remember { mutableStateOf("") }
    var showRawApiKey by remember { mutableStateOf(false) }

    var showRestoreDialog by remember { mutableStateOf(false) }
    var restoreInputJson by remember { mutableStateOf("") }
    var restorePassphrase by remember { mutableStateOf("") }

    var showPlaintextWarningDialog by remember { mutableStateOf(false) }
    var showPassphraseExportDialog by remember { mutableStateOf(false) }
    var exportPassphrase by remember { mutableStateOf("") }

    var showClearChatDialog by remember { mutableStateOf(false) }

    val activeByokKey = remember(byokKeys) {
        byokKeys.firstOrNull { it.isActive } ?: byokKeys.firstOrNull()
    }

    LaunchedEffect(byokFeedback) {
        if (byokFeedback?.first == true) {
            byokApiKey = ""
            byokNickname = ""
            byokModelId = ""
            showRawApiKey = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Section: Security & Keystore Diagnostic (Reads dynamic runtime configuration & KeyInfo)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🛡️", fontSize = 20.sp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Security & Keystore Status", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                    IconButton(
                        onClick = { viewModel.refreshSecurityStatus() },
                        modifier = Modifier.testTag("refresh_security_button")
                    ) {
                        Text("🔄", fontSize = 16.sp)
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))

                // Item 1: User-Provided BYOK API Authentication
                SecurityStatusRow(
                    label = "API Authentication (BYOK)",
                    status = if (activeByokKey != null) {
                        "Active: ${activeByokKey.provider.displayName} (${activeByokKey.maskedKey})"
                    } else {
                        "No User API Key Configured"
                    },
                    detail = if (activeByokKey != null) {
                        "Encrypted via KeyStore AES-256-GCM • Sent via request header only (no key in APK)"
                    } else {
                        "Zero bundled APK key — add your own key below to enable cloud AI features"
                    },
                    isSecure = activeByokKey != null
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.08f))

                // Item 2: Android KeyStore Hardware AES-256-GCM (Queried from KeyInfo)
                SecurityStatusRow(
                    label = "Hardware KeyStore Status",
                    status = securityStatus.statusSummary,
                    detail = "Security Level: ${securityStatus.securityLevel} • Alias: ${securityStatus.keyAlias} • ${securityStatus.perNoteIvSize}B IV/note",
                    isSecure = securityStatus.isHardwareBacked || securityStatus.algorithm.contains("AES")
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.08f))

                // Item 3: HTTP Request Logging
                SecurityStatusRow(
                    label = "HTTP Request Logging",
                    status = "Disabled (Level.NONE)",
                    detail = "URLs, auth headers, and request/response bodies are never logged",
                    isSecure = true
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.08f))

                // Item 4: Notes At Rest Encryption
                SecurityStatusRow(
                    label = "Database At-Rest Encryption",
                    status = if (isLocalEncryption) "AES-GCM Active" else "Plaintext Storage",
                    detail = if (isLocalEncryption) "Per-note 12-byte random IV with 128-bit authentication tag" else "Enable in preferences below for AES-GCM note encryption",
                    isSecure = isLocalEncryption
                )
            }
        }

        // Section: Bring Your Own Key (BYOK) Management
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🔑", fontSize = 20.sp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Bring Your Own Key (BYOK)", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }

                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Keys are validated before saving, encrypted on-device with Android KeyStore AES-256-GCM (excluded from cloud backup), and never bundled in the APK. Note: Direct client-to-provider HTTPS is used (no backend proxy server).",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )

                Spacer(modifier = Modifier.height(12.dp))
                Text("AI Provider:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ByokProvider.entries.forEach { provider ->
                        FilterChip(
                            selected = selectedProvider == provider,
                            onClick = {
                                selectedProvider = provider
                                viewModel.clearByokFeedback()
                            },
                            label = {
                                Text(
                                    text = when (provider) {
                                        ByokProvider.GEMINI -> "Gemini"
                                        ByokProvider.OPENAI -> "OpenAI"
                                        ByokProvider.ANTHROPIC -> "Claude"
                                    },
                                    fontSize = 11.sp
                                )
                            },
                            modifier = Modifier.testTag("byok_provider_${provider.id}"),
                            shape = RoundedCornerShape(10.dp)
                        )
                    }
                }

                if (!selectedProvider.supportsMultimodal) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "${selectedProvider.displayName} supports Tutor AI chat & text translation. Audio transcription & visual OCR require an active Gemini key.",
                        fontSize = 10.sp,
                        color = Color(0xFFB45309)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = byokNickname,
                    onValueChange = { byokNickname = it },
                    label = { Text("Nickname (Optional)", fontSize = 12.sp) },
                    placeholder = { Text("e.g. Personal Study Key", fontSize = 12.sp) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("byok_nickname_input"),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = byokApiKey,
                    onValueChange = {
                        byokApiKey = it
                        viewModel.clearByokFeedback()
                    },
                    label = { Text("API Key (Required)", fontSize = 12.sp) },
                    placeholder = { Text("Paste your provider API key...", fontSize = 12.sp) },
                    visualTransformation = if (showRawApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        TextButton(onClick = { showRawApiKey = !showRawApiKey }) {
                            Text(if (showRawApiKey) "Hide" else "Show", fontSize = 11.sp)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("byok_api_key_input"),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = byokModelId,
                    onValueChange = { byokModelId = it },
                    label = {
                        Text(
                            text = if (selectedProvider == ByokProvider.GEMINI) "Model ID (Optional)" else "Model ID (Required)",
                            fontSize = 12.sp
                        )
                    },
                    placeholder = {
                        Text(
                            text = if (selectedProvider == ByokProvider.GEMINI) {
                                "Default: ${selectedProvider.defaultModel}"
                            } else {
                                "Enter model ID from your provider account"
                            },
                            fontSize = 12.sp
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("byok_model_input"),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        viewModel.addAndValidateByokKey(
                            provider = selectedProvider,
                            rawApiKey = byokApiKey,
                            nickname = byokNickname,
                            modelId = byokModelId
                        )
                    },
                    enabled = byokApiKey.isNotBlank() && !isByokValidating,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("byok_add_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isByokValidating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Validating Key...", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Text("Add & Verify Key", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }

                byokFeedback?.let { (isSuccess, msg) ->
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        color = if (isSuccess) Color(0xFF10B981).copy(alpha = 0.12f) else Color(0xFFEF4444).copy(alpha = 0.12f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("byok_feedback_banner")
                    ) {
                        Text(
                            text = msg,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isSuccess) Color(0xFF047857) else Color(0xFFB91C1C),
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                if (byokKeys.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Text("Saved Keys (Full key is never displayed again):", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(8.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        byokKeys.forEach { entry ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (entry.isActive) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                },
                                border = BorderStroke(
                                    1.dp,
                                    if (entry.isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(entry.nickname, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                            if (entry.isActive) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    "ACTIVE",
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                        Text(
                                            text = "${entry.provider.displayName} • ${entry.maskedKey}${if (entry.modelId.isNotBlank()) " • ${entry.modelId}" else ""}",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    if (!entry.isActive) {
                                        TextButton(
                                            onClick = { viewModel.selectActiveByokKey(entry.id) },
                                            modifier = Modifier.testTag("byok_activate_button_${entry.id}")
                                        ) {
                                            Text("Use", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }

                                    TextButton(
                                        onClick = { viewModel.deleteByokKey(entry.id) },
                                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFEF4444)),
                                        modifier = Modifier.testTag("byok_delete_button_${entry.id}")
                                    ) {
                                        Text("Delete", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Section: Study & Language Preferences
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("⚙️", fontSize = 20.sp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Study & App Preferences", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Dark mode toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Dark Theme", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text("Switch between high-contrast dark and light modes", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = isDarkMode, onCheckedChange = { viewModel.toggleDarkMode() })
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.08f))

                // Local Encryption toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Encrypt Notes on Save", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text("Applies hardware AES-GCM encryption before writing to disk", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = isLocalEncryption, onCheckedChange = { viewModel.toggleLocalEncryption() })
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.08f))

                // Offline mode toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Offline Mode", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text("Uses on-device cached Ethiopian linguistic dictionaries", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = isOfflineMode, onCheckedChange = { viewModel.toggleOfflineMode() })
                }
            }
        }

        // Section: Language Selection
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🌍", fontSize = 20.sp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Language Configuration", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text("Primary Ethiopian Language:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Amharic", "Tigrinya", "Oromo", "Somali").forEach { lang ->
                        FilterChip(
                            selected = sourceLang == lang,
                            onClick = { viewModel.updateSelectedSourceLang(lang) },
                            label = { Text(lang, fontSize = 11.sp) },
                            shape = RoundedCornerShape(10.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text("Default Translation Target:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("English", "Arabic", "French", "None").forEach { lang ->
                        FilterChip(
                            selected = targetLang == lang,
                            onClick = { viewModel.updateSelectedTargetLang(lang) },
                            label = { Text(lang, fontSize = 11.sp) },
                            shape = RoundedCornerShape(10.dp)
                        )
                    }
                }
            }
        }

        // Section: Data Backup, Storage & Maintenance
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("💾", fontSize = 20.sp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Database & Backup Management", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Backup Format: Plaintext JSON (for portability) or Passphrase-Protected (PBKDF2 + AES-GCM).",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Stats badges
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${transcriptions.size}", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Text("Saved Notes", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    Surface(
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${chatMessages.size}", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                            Text("Chat Messages", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Option A: Export Plaintext Backup Button (With Mandatory Warning)
                Button(
                    onClick = { showPlaintextWarningDialog = true },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("📦 Export Plaintext JSON Backup", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Option B: Export Passphrase-Protected Backup Button
                FilledTonalButton(
                    onClick = { showPassphraseExportDialog = true },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("🔐 Export Passphrase-Encrypted Backup", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Restore Backup Button
                OutlinedButton(
                    onClick = { showRestoreDialog = true },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("📥 Restore Backup from JSON", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Clear Chat History Button
                TextButton(
                    onClick = { showClearChatDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFEF4444))
                ) {
                    Text("🗑️ Reset Chat History", fontSize = 12.sp)
                }
            }
        }

        // Section: About App
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Amharic Study Workspace", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Version 2.0 • Android M3 Architecture • BYOK AI Workspace", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Designed for Ethiopian language research, Ge'ez transcription, and digital study preservation.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    // Plaintext Export Security Warning Dialog
    if (showPlaintextWarningDialog) {
        AlertDialog(
            onDismissRequest = { showPlaintextWarningDialog = false },
            title = { Text("⚠️ Security Warning: Plaintext Export", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFFEF4444)) },
            text = {
                Text(
                    "Your study notes will be exported in unencrypted JSON without hardware AES-GCM protection. Anyone with access to the exported file or device storage can view your study transcripts and notes.\n\nFor protected export, choose 'Export Passphrase-Encrypted Backup'.\n\nDo you want to proceed with unencrypted plaintext export?",
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showPlaintextWarningDialog = false
                        val path = viewModel.exportBackupToJson(context, passphrase = null)
                        if (path != null) {
                            val file = java.io.File(path)
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.provider",
                                file
                            )
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/json"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share Study Notes Plaintext Backup"))
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                ) {
                    Text("Proceed with Plaintext")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPlaintextWarningDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Passphrase Export Dialog
    if (showPassphraseExportDialog) {
        AlertDialog(
            onDismissRequest = { showPassphraseExportDialog = false },
            title = { Text("Export Encrypted Backup", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Enter a secure passphrase to encrypt your backup with AES-256-GCM and PBKDF2 (600,000 iterations):", fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = exportPassphrase,
                        onValueChange = { exportPassphrase = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Enter passphrase...", fontSize = 12.sp) },
                        shape = RoundedCornerShape(10.dp),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (exportPassphrase.isNotBlank()) {
                            val path = viewModel.exportBackupToJson(context, passphrase = exportPassphrase)
                            showPassphraseExportDialog = false
                            exportPassphrase = ""
                            if (path != null) {
                                val file = java.io.File(path)
                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.provider",
                                    file
                                )
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/json"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(intent, "Share Encrypted Backup"))
                            }
                        }
                    }
                ) {
                    Text("Encrypt & Export")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPassphraseExportDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Restore Backup Dialog (Documents Merge Deduplication & Format Validation)
    if (showRestoreDialog) {
        AlertDialog(
            onDismissRequest = { showRestoreDialog = false },
            title = { Text("Restore Study Notes Backup", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Merge Behavior:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text("• Deduplication: Identical notes already in your library are safely skipped.\n• Fresh IDs: Generated to avoid primary key conflicts.\n• Validation: Malformed JSON or corrupted files are strictly rejected.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = restoreInputJson,
                        onValueChange = { restoreInputJson = it },
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        placeholder = { Text("[{\"title\":\"...\", \"amharicText\":\"...\"}] or encrypted payload", fontSize = 11.sp) },
                        shape = RoundedCornerShape(10.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = restorePassphrase,
                        onValueChange = { restorePassphrase = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Passphrase (leave blank if plaintext backup)", fontSize = 11.sp) },
                        shape = RoundedCornerShape(10.dp),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (restoreInputJson.isNotBlank()) {
                            val pass = if (restorePassphrase.isBlank()) null else restorePassphrase
                            val result = viewModel.restoreBackupFromJson(restoreInputJson, pass)
                            if (result) {
                                showRestoreDialog = false
                                restoreInputJson = ""
                                restorePassphrase = ""
                            }
                        }
                    }
                ) {
                    Text("Restore")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Clear Chat Confirmation Dialog
    if (showClearChatDialog) {
        AlertDialog(
            onDismissRequest = { showClearChatDialog = false },
            title = { Text("Reset Chat History?", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to clear all dialogue messages with Tutor AI? This cannot be undone.", fontSize = 13.sp) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearAllChatMessages()
                        showClearChatDialog = false
                        Toast.makeText(context, "Chat history cleared", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                ) {
                    Text("Clear All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearChatDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun SecurityStatusRow(label: String, status: String, detail: String, isSecure: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(status, fontSize = 12.sp, color = if (isSecure) Color(0xFF10B981) else Color(0xFFF59E0B), fontWeight = FontWeight.Bold)
            Text(detail, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(if (isSecure) "🔒" else "⚠️", fontSize = 18.sp)
    }
}

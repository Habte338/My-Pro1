package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.copyTextToClipboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun OcrWorkspaceScreen(viewModel: WorkspaceViewModel) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val selectedOcrUri by viewModel.selectedOcrImageUri.collectAsStateWithLifecycle()
    val imageBatchQueue by viewModel.imageBatchQueue.collectAsStateWithLifecycle()
    val isBatchProcessing by viewModel.isBatchProcessing.collectAsStateWithLifecycle()
    val batchProgress by viewModel.batchProgressMessage.collectAsStateWithLifecycle()

    val sourceLang by viewModel.selectedSourceLang.collectAsStateWithLifecycle()
    val targetLang by viewModel.selectedTargetLang.collectAsStateWithLifecycle()

    val ocrLoading by viewModel.ocrLoading.collectAsStateWithLifecycle()
    val ocrTranslationLoading by viewModel.ocrTranslationLoading.collectAsStateWithLifecycle()
    val ocrExtractedText by viewModel.ocrExtractedText.collectAsStateWithLifecycle()
    val ocrTranslationText by viewModel.ocrTranslationText.collectAsStateWithLifecycle()
    val ocrDetectedLanguage by viewModel.ocrDetectedLanguage.collectAsStateWithLifecycle()
    val ocrProcessingStats by viewModel.ocrProcessingStats.collectAsStateWithLifecycle()
    val ocrBreakdownText by viewModel.ocrBreakdownText.collectAsStateWithLifecycle()
    val ocrBreakdownLoading by viewModel.ocrBreakdownLoading.collectAsStateWithLifecycle()

    var noteTitleInput by remember { mutableStateOf("") }
    var activeCameraUri by remember { mutableStateOf<Uri?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && activeCameraUri != null) {
            viewModel.updateSelectedOcrImageUri(activeCameraUri!!)
            Toast.makeText(context, "Photo captured! Ready to scan.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Camera photo capture cancelled or failed.", Toast.LENGTH_SHORT).show()
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            try {
                val photoFile = File(context.cacheDir, "camera_capture_ocr_${System.currentTimeMillis()}.jpg")
                photoFile.parentFile?.mkdirs()
                if (!photoFile.exists()) {
                    photoFile.createNewFile()
                }
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.provider",
                    photoFile
                )
                activeCameraUri = uri
                cameraLauncher.launch(uri)
            } catch (e: Exception) {
                Toast.makeText(context, "Camera error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "Camera permission is required to capture documents.", Toast.LENGTH_LONG).show()
        }
    }

    fun launchCameraFlow() {
        val hasCamPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (hasCamPermission) {
            try {
                val photoFile = File(context.cacheDir, "camera_capture_ocr_${System.currentTimeMillis()}.jpg")
                photoFile.parentFile?.mkdirs()
                if (!photoFile.exists()) {
                    photoFile.createNewFile()
                }
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.provider",
                    photoFile
                )
                activeCameraUri = uri
                cameraLauncher.launch(uri)
            } catch (e: Exception) {
                Toast.makeText(context, "Camera error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val genericImagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            viewModel.updateSelectedOcrImageUri(uri)
            Toast.makeText(context, "Image selected! Ready to scan.", Toast.LENGTH_SHORT).show()
        }
    }

    val singleImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.updateSelectedOcrImageUri(uri)
            Toast.makeText(context, "Image selected! Ready to scan.", Toast.LENGTH_SHORT).show()
        }
    }

    val batchImagesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            viewModel.addImageToBatchQueue(uris)
        }
    }

    // Non-blocking async image preview decode off the UI thread
    val previewBitmap by produceState<Bitmap?>(initialValue = null, selectedOcrUri) {
        value = withContext(Dispatchers.IO) {
            selectedOcrUri?.let { uri ->
                try {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val options = BitmapFactory.Options().apply {
                            inSampleSize = 4
                        }
                        BitmapFactory.decodeStream(stream, null, options)
                    }
                } catch (_: Exception) {
                    null
                }
            }
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
                Text(
                    "Fast native-script text extraction for Amharic, Tigrinya, Oromo, and Somali. Images are auto-resized for speed; translation runs only when you click Translate.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Language selection options
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Source Script Hint", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
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
                            Text(sourceLang, fontSize = 11.sp, maxLines = 1)
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
                        Text("Translate Target (On-Demand)", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
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
                            Text(if (targetLang == "None") "English" else targetLang, fontSize = 11.sp, maxLines = 1)
                            DropdownMenu(expanded = expandedTarget, onDismissRequest = { expandedTarget = false }) {
                                listOf("English", "Amharic (አማርኛ)", "Tigrinya (ትግርኛ)", "Oromo (Afaan Oromoo)", "Somali (Af-Soomaali)", "Arabic", "French").forEach { lang ->
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
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = { launchCameraFlow() },
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        Text("📸 Camera", fontSize = 10.sp, maxLines = 1)
                    }

                    Button(
                        onClick = {
                            try {
                                singleImageLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            } catch (_: Exception) {
                                genericImagePickerLauncher.launch("image/*")
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        Text("🏞️ Gallery", fontSize = 10.sp, maxLines = 1)
                    }

                    FilledTonalButton(
                        onClick = { viewModel.loadSampleOcrDocument(context) },
                        modifier = Modifier
                            .weight(1.1f)
                            .height(42.dp)
                            .testTag("ocr_sample_doc_button"),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        Text("📄 Sample Doc", fontSize = 10.sp, maxLines = 1)
                    }

                    OutlinedButton(
                        onClick = {
                            try {
                                batchImagesLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            } catch (_: Exception) {
                                genericImagePickerLauncher.launch("image/*")
                            }
                        },
                        modifier = Modifier
                            .weight(0.9f)
                            .height(42.dp),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        Text("📚 Batch", fontSize = 10.sp, maxLines = 1)
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
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("📄 Document Image Ready for Scan", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                                TextButton(
                                    onClick = { viewModel.updateSelectedOcrImageUri(null) },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text("✕ Remove", fontSize = 11.sp, color = Color(0xFFEF4444))
                                }
                            }

                            if (previewBitmap != null) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Image(
                                    bitmap = previewBitmap!!.asImageBitmap(),
                                    contentDescription = "Selected document preview",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(150.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color.Black.copy(alpha = 0.05f)),
                                    contentScale = ContentScale.Fit
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { viewModel.triggerImageOcr(context) },
                                    enabled = !ocrLoading,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(44.dp)
                                        .testTag("ocr_scan_button"),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    if (ocrLoading) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Extracting Script...", fontSize = 12.sp, color = Color.White)
                                    } else {
                                        Text("🔍 Scan Document (Fast OCR)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    }
                                }

                                if (ocrLoading) {
                                    OutlinedButton(
                                        onClick = { viewModel.cancelActiveProcessing() },
                                        modifier = Modifier
                                            .height(44.dp)
                                            .testTag("ocr_cancel_button"),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text("Cancel", fontSize = 11.sp, color = Color(0xFFEF4444), fontWeight = FontWeight.Bold)
                                    }
                                }
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
                Text("Trace Ge'ez syllables below to practice stroke shape and register personal study styles.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                Spacer(modifier = Modifier.height(10.dp))

                var charInputText by remember { mutableStateOf("ሀ") }
                val points = remember { mutableStateListOf<Offset>() }

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
                                    onDragEnd = { points.add(Offset.Unspecified) }
                                )
                            }
                    ) {
                        for (i in 0 until points.size - 1) {
                            if (points[i] != Offset.Unspecified && points[i + 1] != Offset.Unspecified) {
                                drawLine(
                                    color = Color(0xFF10B981),
                                    start = points[i],
                                    end = points[i + 1],
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
                        textStyle = TextStyle(fontSize = 12.sp),
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Extracted Script (ጽሑፍ)",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (ocrDetectedLanguage != null || ocrProcessingStats != null) {
                                Text(
                                    text = listOfNotNull(ocrDetectedLanguage, ocrProcessingStats).joinToString(" • "),
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Row {
                            IconButton(
                                onClick = { viewModel.speakPronunciation(context, ocrExtractedText) },
                                modifier = Modifier
                                    .size(32.dp)
                                    .testTag("ocr_speak_button")
                            ) {
                                Text("🔊", fontSize = 14.sp)
                            }
                            IconButton(
                                onClick = {
                                    copyTextToClipboard(
                                        context,
                                        clipboardManager,
                                        "Original Script",
                                        ocrExtractedText
                                    )
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Text("📋", fontSize = 14.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    SelectionContainer {
                        OutlinedTextField(
                            value = ocrExtractedText,
                            onValueChange = { viewModel.updateOcrExtractedText(it) },
                            placeholder = { Text("Recognized script appears here. Edit directly if needed.", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 12.sp) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(100.dp)
                                .testTag("ocr_extracted_text_field"),
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
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.triggerOcrStudyBreakdown(ocrExtractedText) },
                            enabled = !ocrBreakdownLoading && ocrExtractedText.isNotBlank(),
                            modifier = Modifier
                                .height(40.dp)
                                .testTag("ocr_breakdown_button"),
                            shape = RoundedCornerShape(18.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                        ) {
                            Text(if (ocrBreakdownLoading) "Analyzing..." else "✨ Study Breakdown", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }

                        Button(
                            onClick = { viewModel.triggerOcrTranslation(ocrExtractedText) },
                            enabled = !ocrTranslationLoading && ocrExtractedText.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                            modifier = Modifier
                                .height(40.dp)
                                .testTag("ocr_translate_button"),
                            shape = RoundedCornerShape(18.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                        ) {
                            if (ocrTranslationLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), color = MaterialTheme.colorScheme.onSecondary, strokeWidth = 2.dp)
                            } else {
                                Text("🌐 Translate Script", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }

        // On-demand Study Breakdown Card
        if (ocrBreakdownLoading || ocrBreakdownText.isNotBlank()) {
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
                        TextButton(onClick = { viewModel.clearOcrBreakdown() }, modifier = Modifier.height(28.dp)) {
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
                    if (ocrBreakdownLoading) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        SelectionContainer {
                            Text(ocrBreakdownText, fontSize = 12.sp, lineHeight = 18.sp)
                        }
                    }
                }
            }
        }

        // Translation output box (only populated on user Translate click)
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
                            text = "On-Demand Translation (${if (targetLang == "None") "English" else targetLang})",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    IconButton(
                        onClick = {
                            copyTextToClipboard(
                                context,
                                clipboardManager,
                                "Translation",
                                ocrTranslationText
                            )
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Text("📋", fontSize = 14.sp)
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                SelectionContainer {
                    OutlinedTextField(
                        value = ocrTranslationText,
                        onValueChange = { viewModel.updateOcrTranslationText(it) },
                        placeholder = { Text("Tap 'Translate Script' above when you want a translation.", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 12.sp) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(90.dp)
                            .testTag("ocr_translation_text_field"),
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
        }

        // Archive / Save Note directly on the page
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)), RoundedCornerShape(24.dp)),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "💾 Save OCR Result to Library",
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
                        viewModel.saveOcrSession(noteTitleInput)
                        noteTitleInput = ""
                        Toast.makeText(context, "OCR Note Saved to Library!", Toast.LENGTH_SHORT).show()
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

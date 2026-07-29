package com.sa.assistant.ui.pdfstudio

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.sa.assistant.data.model.PdfEntry
import com.sa.assistant.data.model.PendingPdfImage
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfStudioScreen(
    onManagePages: (String) -> Unit = {},
    onMarkEdit: (String) -> Unit = {},
    viewModel: PdfStudioViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var pendingCaptureFile by remember { mutableStateOf<File?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            pendingCaptureFile?.let { viewModel.onCameraCaptured(it) }
        }
        pendingCaptureFile = null
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 20)
    ) { uris -> viewModel.onGalleryImagesPicked(uris) }

    var pdfEntryPendingDelete by remember { mutableStateOf<PdfEntry?>(null) }
    var showNameDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is PdfStudioUiEvent.Error -> scope.launch { snackbarHostState.showSnackbar(event.message) }
                is PdfStudioUiEvent.PdfBuilt -> scope.launch {
                    snackbarHostState.showSnackbar("${event.displayName}.pdf ban gaya (${event.pageCount} pages)")
                }
                is PdfStudioUiEvent.ViewPdf -> {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(event.uri, "application/pdf")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    runCatching { context.startActivity(Intent.createChooser(intent, "PDF kholein")) }
                        .onFailure { scope.launch { snackbarHostState.showSnackbar("PDF kholne wala app nahi mila") } }
                }
                is PdfStudioUiEvent.SharePdf -> {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/pdf"
                        putExtra(Intent.EXTRA_STREAM, event.uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "PDF share karein"))
                }
            }
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Text("PDF Studio", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Camera ya Gallery se scan karo, phir ek real PDF banao",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ActionCard(
                    icon = Icons.Filled.Camera,
                    label = "Camera se Scan karo",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val destFile = viewModel.newScanCaptureFile()
                        pendingCaptureFile = destFile
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", destFile)
                        cameraLauncher.launch(uri)
                    }
                )
                ActionCard(
                    icon = Icons.Filled.Photo,
                    label = "Gallery se chunein",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                )
            }

            if (state.pendingImages.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Text(
                    "Pending images (${state.pendingImages.size})",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.pendingImages, key = { it.id }) { image ->
                        PendingImageThumb(image = image, onRemove = { viewModel.removePendingImage(image.id) })
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { showNameDialog = true },
                        enabled = !state.isBuilding
                    ) {
                        if (state.isBuilding) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Ban raha hai...")
                        } else {
                            Icon(Icons.Filled.PictureAsPdf, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Images ko PDF banaye")
                        }
                    }
                    OutlinedButton(onClick = { viewModel.clearPending() }, enabled = !state.isBuilding) {
                        Text("Clear")
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Text("Saved PDFs", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))

            when {
                state.isLoadingSaved -> Box(
                    Modifier.fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                state.savedPdfs.isEmpty() -> Text(
                    "Abhi koi PDF saved nahi hai — upar se scan karke pehla PDF banao.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    modifier = Modifier.padding(vertical = 12.dp)
                )

                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.savedPdfs, key = { it.file.absolutePath }) { entry ->
                        SavedPdfRow(
                            entry = entry,
                            onOpen = { viewModel.onOpenPdf(entry) },
                            onManage = { onManagePages(entry.file.absolutePath) },
                            onMark = { onMarkEdit(entry.file.absolutePath) },
                            onShare = { viewModel.onSharePdf(entry) },
                            onDelete = { pdfEntryPendingDelete = entry }
                        )
                    }
                }
            }
        }
    }

    if (showNameDialog) {
        NamePdfDialog(
            onDismiss = { showNameDialog = false },
            onConfirm = { name ->
                showNameDialog = false
                viewModel.buildPdf(name)
            }
        )
    }

    pdfEntryPendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pdfEntryPendingDelete = null },
            title = { Text("PDF delete karein?") },
            text = { Text("\"${entry.displayName}.pdf\" hamesha ke liye delete ho jayega.") },
            confirmButton = {
                Button(onClick = {
                    viewModel.deletePdf(entry)
                    pdfEntryPendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                OutlinedButton(onClick = { pdfEntryPendingDelete = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ActionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .height(96.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.height(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun PendingImageThumb(image: PendingPdfImage, onRemove: () -> Unit) {
    Box(modifier = Modifier.size(84.dp)) {
        AsyncImage(
            model = image.uri,
            contentDescription = image.label,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
        )
        IconButton(
            onClick = onRemove,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(24.dp)
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f), RoundedCornerShape(50))
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Remove",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
private fun SavedPdfRow(
    entry: PdfEntry,
    onOpen: () -> Unit,
    onManage: () -> Unit,
    onMark: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.PictureAsPdf, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.displayName, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${entry.pageCount} pages · ${formatSize(entry.sizeBytes)} · ${formatDate(entry.createdAtMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
            IconButton(onClick = onManage) {
                Icon(Icons.Filled.Edit, contentDescription = "Pages manage karo (merge/split/rotate)")
            }
            IconButton(onClick = onMark) {
                Icon(Icons.Filled.Brush, contentDescription = "Mark/Edit karo (highlight/underline/draw)")
            }
            IconButton(onClick = onShare) {
                Icon(Icons.Filled.Share, contentDescription = "Share")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NamePdfDialog(onDismiss: () -> Unit, onConfirm: (String?) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("PDF ka naam") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text("e.g. Aadhar_Card") },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(text.ifBlank { null }) }) { Text("Banaye") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun formatSize(bytes: Long): String {
    val kb = bytes / 1024.0
    return if (kb < 1024) "%.0f KB".format(kb) else "%.1f MB".format(kb / 1024.0)
}

private fun formatDate(millis: Long): String =
    SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(millis))

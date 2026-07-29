package com.sa.assistant.ui.chat

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.sa.assistant.data.model.AttachmentKind
import com.sa.assistant.data.model.ChatMessage
import com.sa.assistant.socket.SaConnectionState
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { viewModel.onContentUriPicked(it, AttachmentKind.IMAGE) }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { viewModel.onContentUriPicked(it, AttachmentKind.FILE) }
    }
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { viewModel.onContentUriPicked(it, AttachmentKind.PDF) }
    }

    var pendingCaptureFile by remember { mutableStateOf<java.io.File?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) pendingCaptureFile?.let { viewModel.onCameraCaptured(it) }
        pendingCaptureFile = null
    }

    // One-off events: clipboard confirmation, share sheets, errors. These
    // are Android side-effects (startActivity / Toast), not steady state,
    // so they're consumed here rather than stored in uiState.
    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is ChatUiEvent.ShareText -> {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, event.text)
                    }
                    context.startActivity(Intent.createChooser(send, "Share via"))
                }
                is ChatUiEvent.SharePdf -> {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "application/pdf"
                        putExtra(Intent.EXTRA_STREAM, event.uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(send, "Chat PDF share karein"))
                }
                is ChatUiEvent.CopiedToClipboard -> {
                    android.widget.Toast.makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT).show()
                }
                is ChatUiEvent.Error -> {
                    android.widget.Toast.makeText(context, event.message, android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ChatTopBar(
            connectionState = state.connectionState,
            isExporting = state.isExporting,
            onExportClick = viewModel::exportChatToPdf
        )

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(state.messages, key = { "${it.id}-${it.isFromUser}" }) { message ->
                MessageBubble(
                    message = message,
                    onCopy = { viewModel.copyMessage(message) },
                    onShare = { viewModel.shareMessage(message) },
                    onSpeak = { viewModel.speakMessage(message) }
                )
            }
        }

        if (state.pendingAttachments.isNotEmpty() || state.isProcessingAttachment) {
            AttachmentPreviewRow(
                attachments = state.pendingAttachments,
                isProcessing = state.isProcessingAttachment,
                onRemove = viewModel::removeAttachment
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { imagePicker.launch("image/*") }) {
                Icon(Icons.Filled.Image, contentDescription = "Image upload")
            }
            IconButton(onClick = {
                val destFile = viewModel.newCaptureFile()
                pendingCaptureFile = destFile
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", destFile)
                cameraLauncher.launch(uri)
            }) {
                Icon(Icons.Filled.Camera, contentDescription = "Camera")
            }
            IconButton(onClick = { pdfPicker.launch("application/pdf") }) {
                Icon(Icons.Filled.PictureAsPdf, contentDescription = "PDF upload")
            }
            IconButton(onClick = { filePicker.launch("*/*") }) {
                Icon(Icons.Filled.AttachFile, contentDescription = "File upload")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = state.inputText,
                onValueChange = viewModel::onInputChanged,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type a message...") }
            )
            IconButton(onClick = viewModel::sendCurrentInput) {
                Icon(Icons.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

@Composable
private fun ChatTopBar(
    connectionState: SaConnectionState,
    isExporting: Boolean,
    onExportClick: () -> Unit
) {
    val (label, color) = when (connectionState) {
        SaConnectionState.CONNECTED -> "Connected to Termux server" to MaterialTheme.colorScheme.primary
        SaConnectionState.CONNECTING -> "Connecting..." to MaterialTheme.colorScheme.secondary
        SaConnectionState.DISCONNECTED -> "Termux server se connect nahi hai" to MaterialTheme.colorScheme.error
    }
    Surface(color = color.copy(alpha = 0.12f), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = label, color = color, style = MaterialTheme.typography.labelSmall)
            if (isExporting) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onExportClick) {
                    Icon(Icons.Filled.PictureAsPdf, contentDescription = "Export chat to PDF", tint = color)
                }
            }
        }
    }
}

@Composable
private fun AttachmentPreviewRow(
    attachments: List<com.sa.assistant.data.model.Attachment>,
    isProcessing: Boolean,
    onRemove: (com.sa.assistant.data.model.Attachment) -> Unit
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(attachments) { attachment ->
            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(attachment.displayName, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    IconButton(onClick = { onRemove(attachment) }, modifier = Modifier.size(20.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove attachment")
                    }
                }
            }
        }
        if (isProcessing) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text(" Processing...", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(message: ChatMessage, onCopy: () -> Unit, onShare: () -> Unit, onSpeak: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    val alignment = if (message.isFromUser) Alignment.CenterEnd else Alignment.CenterStart
    val bubbleColor = if (message.isFromUser) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
    } else {
        MaterialTheme.colorScheme.surface
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.align(alignment)) {
            Surface(
                color = bubbleColor,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .padding(vertical = 2.dp)
                    .combinedClickable(onClick = {}, onLongClick = { menuOpen = true })
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    if (message.isPending && message.text == "…") {
                        ThinkingDots()
                    } else {
                        Text(text = message.text, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (message.attachments.isNotEmpty()) {
                        Text(
                            text = message.attachments.joinToString(", ") { it.displayName },
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("Copy") }, onClick = { onCopy(); menuOpen = false })
                DropdownMenuItem(text = { Text("Share") }, onClick = { onShare(); menuOpen = false })
                if (!message.isFromUser) {
                    DropdownMenuItem(text = { Text("Speak") }, onClick = { onSpeak(); menuOpen = false })
                }
            }
        }
    }
}

/** Simple animated "..." shown while a reply is streaming in / not yet started. */
@Composable
private fun ThinkingDots() {
    val transition = rememberInfiniteTransition(label = "thinking")
    val dotCount by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "dots"
    )
    Text(text = "Thinking" + ".".repeat((dotCount.toInt() % 3) + 1), style = MaterialTheme.typography.bodyMedium)
}

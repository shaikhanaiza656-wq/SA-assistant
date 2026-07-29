package com.sa.assistant.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sa.assistant.core.export.ChatPdfExporter
import com.sa.assistant.core.files.AttachmentStorage
import com.sa.assistant.data.model.Attachment
import com.sa.assistant.data.model.AttachmentKind
import com.sa.assistant.data.model.ChatMessage
import com.sa.assistant.data.model.SaAttachmentPayload
import com.sa.assistant.data.repository.ChatRepository
import com.sa.assistant.socket.SaConnectionState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val connectionState: SaConnectionState = SaConnectionState.DISCONNECTED,
    val inputText: String = "",
    val pendingAttachments: List<Attachment> = emptyList(),
    val isProcessingAttachment: Boolean = false,
    val isExporting: Boolean = false
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val repository: ChatRepository,
    private val attachmentStorage: AttachmentStorage,
    private val pdfExporter: ChatPdfExporter,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _events = Channel<ChatUiEvent>(Channel.BUFFERED)
    val events: Flow<ChatUiEvent> = _events.receiveAsFlow()

    init {
        repository.connect()

        viewModelScope.launch {
            repository.connectionState.collect { state ->
                _uiState.value = _uiState.value.copy(connectionState = state)
            }
        }

        // Streaming-aware: a reply may arrive as several chunks sharing
        // one id (done=false ... done=false ... done=true) or as a single
        // done=true chunk (Phase-1-style servers). Either way this just
        // appends onto whatever's already there for that id.
        viewModelScope.launch {
            repository.responses.collect { response ->
                val current = _uiState.value.messages
                val existingIndex = current.indexOfLast { it.id == response.id && !it.isFromUser }

                val updated = if (existingIndex >= 0) {
                    val existing = current[existingIndex]
                    val merged = existing.copy(
                        text = if (existing.isPending && existing.text == "…") response.reply
                               else existing.text + response.reply,
                        isPending = !response.done
                    )
                    current.toMutableList().also { it[existingIndex] = merged }
                } else {
                    current + ChatMessage(
                        id = response.id,
                        text = response.reply,
                        isFromUser = false,
                        isPending = !response.done
                    )
                }
                _uiState.value = _uiState.value.copy(messages = updated)
            }
        }
    }

    fun onInputChanged(text: String) {
        _uiState.value = _uiState.value.copy(inputText = text)
    }

    // --- Attachments -------------------------------------------------

    fun onContentUriPicked(uri: Uri, kind: AttachmentKind) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessingAttachment = true)
            runCatching { attachmentStorage.importFromUri(uri, kind) }
                .onSuccess { attachment ->
                    _uiState.value = _uiState.value.copy(
                        pendingAttachments = _uiState.value.pendingAttachments + attachment
                    )
                }
                .onFailure { _events.trySend(ChatUiEvent.Error("Attachment add nahi hua: ${it.message}")) }
            _uiState.value = _uiState.value.copy(isProcessingAttachment = false)
        }
    }

    /** Called after a successful camera capture into [file] (see [newCaptureFile]). */
    fun onCameraCaptured(file: File) {
        val attachment = attachmentStorage.fromCapturedFile(file)
        _uiState.value = _uiState.value.copy(
            pendingAttachments = _uiState.value.pendingAttachments + attachment
        )
    }

    /** Destination file + content Uri the camera intent should write the photo to. */
    fun newCaptureFile(): File = attachmentStorage.newCaptureFile()

    fun removeAttachment(attachment: Attachment) {
        _uiState.value = _uiState.value.copy(
            pendingAttachments = _uiState.value.pendingAttachments - attachment
        )
    }

    // --- Sending -------------------------------------------------------

    fun sendCurrentInput() {
        val text = _uiState.value.inputText.trim()
        val attachments = _uiState.value.pendingAttachments
        if (text.isEmpty() && attachments.isEmpty()) return

        _uiState.value = _uiState.value.copy(inputText = "", pendingAttachments = emptyList())

        viewModelScope.launch {
            val payloads = attachments.map { SaAttachmentPayload(path = it.localPath, mimeType = it.mimeType) }
            val id = repository.sendMessage(text.ifEmpty { "[attachment]" }, payloads)
            _uiState.value = _uiState.value.copy(
                messages = _uiState.value.messages + listOf(
                    ChatMessage(id = id, text = text, isFromUser = true, attachments = attachments),
                    ChatMessage(id = id, text = "…", isFromUser = false, isPending = true)
                )
            )
        }
    }

    // --- Message actions: copy / share ---------------------------------

    fun copyMessage(message: ChatMessage) {
        val clipboard = appContext.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("SA chat message", message.text))
        _events.trySend(ChatUiEvent.CopiedToClipboard)
    }

    fun shareMessage(message: ChatMessage) {
        _events.trySend(ChatUiEvent.ShareText(message.text))
    }

    // --- Export chat to PDF ---------------------------------------------

    fun exportChatToPdf() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isExporting = true)
            runCatching { pdfExporter.export(_uiState.value.messages) }
                .onSuccess { uri -> _events.trySend(ChatUiEvent.SharePdf(uri)) }
                .onFailure { _events.trySend(ChatUiEvent.Error("PDF export fail hua: ${it.message}")) }
            _uiState.value = _uiState.value.copy(isExporting = false)
        }
    }

    override fun onCleared() {
        super.onCleared()
        repository.disconnect()
    }
}

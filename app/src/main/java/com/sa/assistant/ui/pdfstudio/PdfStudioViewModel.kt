package com.sa.assistant.ui.pdfstudio

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sa.assistant.core.pdf.ImageToPdfBuilder
import com.sa.assistant.core.pdf.PdfStorage
import com.sa.assistant.data.model.PdfEntry
import com.sa.assistant.data.model.PendingPdfImage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import javax.inject.Inject

data class PdfStudioUiState(
    val pendingImages: List<PendingPdfImage> = emptyList(),
    val savedPdfs: List<PdfEntry> = emptyList(),
    val isBuilding: Boolean = false,
    val isLoadingSaved: Boolean = true
)

/**
 * Backs [PdfStudioScreen]. Every action here does real, on-disk work —
 * queuing an image doesn't fabricate a thumbnail record, it holds the
 * actual Uri; "PDF banaye" doesn't flip a fake success flag, it calls
 * [ImageToPdfBuilder.build] and only reports success for the page count
 * that was actually written.
 */
@HiltViewModel
class PdfStudioViewModel @Inject constructor(
    private val pdfStorage: PdfStorage,
    private val imageToPdfBuilder: ImageToPdfBuilder
) : ViewModel() {

    private val _uiState = MutableStateFlow(PdfStudioUiState())
    val uiState: StateFlow<PdfStudioUiState> = _uiState.asStateFlow()

    private val _events = Channel<PdfStudioUiEvent>(Channel.BUFFERED)
    val events: Flow<PdfStudioUiEvent> = _events.receiveAsFlow()

    init {
        refreshSavedPdfs()
    }

    /** Gallery / Photo Picker returns content:// Uris directly usable by
     *  the builder — no copy needed since we only read them once. */
    fun onGalleryImagesPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val additions = uris.map { uri ->
            PendingPdfImage(
                id = UUID.randomUUID().toString(),
                uri = uri,
                sourceFile = null,
                label = "Gallery"
            )
        }
        _uiState.value = _uiState.value.copy(pendingImages = _uiState.value.pendingImages + additions)
    }

    /** Fresh destination file for a new camera scan (same pattern as
     *  [com.sa.assistant.ui.chat.ChatViewModel.newCaptureFile] — the
     *  screen turns this into a FileProvider Uri and hands it to
     *  [androidx.activity.result.contract.ActivityResultContracts.TakePicture]). */
    fun newScanCaptureFile(): File = pdfStorage.newScanCaptureFile()

    /** Camera capture already wrote a real JPEG to app storage (see
     *  [com.sa.assistant.core.files.AttachmentStorage.newCaptureFile] —
     *  reused here via the same file:// pattern). */
    fun onCameraCaptured(file: File) {
        if (!file.exists() || file.length() == 0L) {
            emitEvent(PdfStudioUiEvent.Error("Camera se photo nahi mili — dobara try karo"))
            return
        }
        val addition = PendingPdfImage(
            id = UUID.randomUUID().toString(),
            uri = Uri.fromFile(file),
            sourceFile = file,
            label = "Camera"
        )
        _uiState.value = _uiState.value.copy(pendingImages = _uiState.value.pendingImages + addition)
    }

    fun removePendingImage(id: String) {
        _uiState.value = _uiState.value.copy(
            pendingImages = _uiState.value.pendingImages.filterNot { it.id == id }
        )
    }

    fun clearPending() {
        _uiState.value = _uiState.value.copy(pendingImages = emptyList())
    }

    fun buildPdf(requestedName: String?) {
        val images = _uiState.value.pendingImages
        if (images.isEmpty()) {
            emitEvent(PdfStudioUiEvent.Error("Pehle camera se scan karo ya gallery se image chuno"))
            return
        }
        if (_uiState.value.isBuilding) return

        _uiState.value = _uiState.value.copy(isBuilding = true)
        viewModelScope.launch {
            val outputFile = pdfStorage.newOutputFile(requestedName)
            try {
                val pageCount = imageToPdfBuilder.build(images, outputFile)
                _uiState.value = _uiState.value.copy(pendingImages = emptyList())
                emitEvent(PdfStudioUiEvent.PdfBuilt(outputFile.nameWithoutExtension, pageCount))
                refreshSavedPdfs()
            } catch (e: ImageToPdfBuilder.PdfBuildException) {
                emitEvent(PdfStudioUiEvent.Error(e.message ?: "PDF banane mein error aaya"))
            } finally {
                _uiState.value = _uiState.value.copy(isBuilding = false)
            }
        }
    }

    fun refreshSavedPdfs() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingSaved = true)
            val list = pdfStorage.listSaved()
            _uiState.value = _uiState.value.copy(savedPdfs = list, isLoadingSaved = false)
        }
    }

    fun onOpenPdf(entry: PdfEntry) {
        emitEvent(PdfStudioUiEvent.ViewPdf(pdfStorage.shareUri(entry.file)))
    }

    fun onSharePdf(entry: PdfEntry) {
        emitEvent(PdfStudioUiEvent.SharePdf(pdfStorage.shareUri(entry.file)))
    }

    fun deletePdf(entry: PdfEntry) {
        viewModelScope.launch {
            val removed = pdfStorage.delete(entry)
            if (!removed) {
                emitEvent(PdfStudioUiEvent.Error("Delete nahi ho paya"))
            }
            refreshSavedPdfs()
        }
    }

    private fun emitEvent(event: PdfStudioUiEvent) {
        _events.trySend(event)
    }
}

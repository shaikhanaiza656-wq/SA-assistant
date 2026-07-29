package com.sa.assistant.ui.pdfpages

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sa.assistant.core.pdf.PdfPageEditor
import com.sa.assistant.core.pdf.PdfStorage
import com.sa.assistant.core.pdf.SourcePageRef
import com.sa.assistant.data.model.ManagedPage
import com.sa.assistant.data.model.PdfEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject

data class PdfPageManagerUiState(
    val sourceFileName: String = "",
    val pages: List<ManagedPage> = emptyList(),
    val selectedKeys: Set<String> = emptySet(),
    val thumbnails: Map<String, Bitmap> = emptyMap(),
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val availableForMerge: List<PdfEntry> = emptyList()
)

/**
 * Backs [PdfPageManagerScreen] — the Phase 3 Part 2A screen for
 * merge / split / rotate / reorder / delete on an already-saved PDF.
 * Every mutation here (rotate, delete, reorder, merge) only ever edits
 * the in-memory [ManagedPage] list; nothing touches disk until
 * [saveOverwrite], [saveAs] or [extractSelected] actually calls
 * [PdfPageEditor.build] and writes real bytes.
 */
@HiltViewModel
class PdfPageManagerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val pdfStorage: PdfStorage,
    private val pdfPageEditor: PdfPageEditor
) : ViewModel() {

    private val sourceFile: File = File(
        requireNotNull(savedStateHandle.get<String>("path")) { "PDF path navigation arg missing" }
    )

    private val _uiState = MutableStateFlow(
        PdfPageManagerUiState(sourceFileName = sourceFile.nameWithoutExtension)
    )
    val uiState: StateFlow<PdfPageManagerUiState> = _uiState.asStateFlow()

    private val _events = Channel<PdfPageManagerUiEvent>(Channel.BUFFERED)
    val events: Flow<PdfPageManagerUiEvent> = _events.receiveAsFlow()

    init {
        loadPages()
        refreshMergeCandidates()
    }

    private fun loadPages() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            if (!sourceFile.exists()) {
                emitEvent(PdfPageManagerUiEvent.Error("PDF file nahi mili — delete ho chuki hogi"))
                _uiState.value = _uiState.value.copy(isLoading = false)
                return@launch
            }
            try {
                val count = pdfPageEditor.pageCount(sourceFile)
                val pages = (0 until count).map { idx ->
                    ManagedPage(key = UUID.randomUUID().toString(), sourceFile = sourceFile, pageIndex = idx)
                }
                _uiState.value = _uiState.value.copy(pages = pages, isLoading = false)
            } catch (e: IOException) {
                emitEvent(PdfPageManagerUiEvent.Error("PDF padhne mein error: ${e.message}"))
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    /** Called by the screen for each visible page card; a no-op if that page's thumbnail is already cached. */
    fun ensureThumbnail(page: ManagedPage, maxDimension: Int = 420) {
        if (_uiState.value.thumbnails.containsKey(page.key)) return
        viewModelScope.launch {
            try {
                val raw = pdfPageEditor.renderPage(page.sourceFile, page.pageIndex, maxDimension)
                val bmp = if (page.rotationDegrees != 0) rotateBitmap(raw, page.rotationDegrees) else raw
                _uiState.value = _uiState.value.copy(thumbnails = _uiState.value.thumbnails + (page.key to bmp))
            } catch (e: IOException) {
                // A single failed thumbnail render isn't fatal — the page
                // stays correctly tracked in the list either way, it just
                // shows a blank placeholder instead of a preview.
            }
        }
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    fun rotatePage(key: String, deltaDegrees: Int) {
        val updated = _uiState.value.pages.map { p ->
            if (p.key == key) p.copy(rotationDegrees = ((p.rotationDegrees + deltaDegrees) % 360 + 360) % 360) else p
        }
        _uiState.value = _uiState.value.copy(pages = updated, thumbnails = _uiState.value.thumbnails - key)
        updated.find { it.key == key }?.let { ensureThumbnail(it) }
    }

    fun toggleSelected(key: String) {
        val current = _uiState.value.selectedKeys
        _uiState.value = _uiState.value.copy(
            selectedKeys = if (key in current) current - key else current + key
        )
    }

    fun movePage(key: String, direction: Int) {
        val pages = _uiState.value.pages.toMutableList()
        val index = pages.indexOfFirst { it.key == key }
        if (index < 0) return
        val target = index + direction
        if (target !in pages.indices) return
        val item = pages.removeAt(index)
        pages.add(target, item)
        _uiState.value = _uiState.value.copy(pages = pages)
    }

    fun deleteSelected() {
        val toRemove = _uiState.value.selectedKeys
        if (toRemove.isEmpty()) return
        val remaining = _uiState.value.pages.filterNot { it.key in toRemove }
        if (remaining.isEmpty()) {
            emitEvent(PdfPageManagerUiEvent.Error("Saare pages delete nahi ho sakte — kam se kam ek page rakhna hoga"))
            return
        }
        _uiState.value = _uiState.value.copy(
            pages = remaining,
            selectedKeys = emptySet(),
            thumbnails = _uiState.value.thumbnails - toRemove
        )
    }

    fun deletePage(key: String) {
        val remaining = _uiState.value.pages.filterNot { it.key == key }
        if (remaining.isEmpty()) {
            emitEvent(PdfPageManagerUiEvent.Error("Aakhri page delete nahi kar sakte — PDF khaali nahi ho sakta"))
            return
        }
        _uiState.value = _uiState.value.copy(
            pages = remaining,
            selectedKeys = _uiState.value.selectedKeys - key,
            thumbnails = _uiState.value.thumbnails - key
        )
    }

    private fun refreshMergeCandidates() {
        viewModelScope.launch {
            val all = pdfStorage.listSaved()
            _uiState.value = _uiState.value.copy(
                availableForMerge = all.filter { it.file.absolutePath != sourceFile.absolutePath }
            )
        }
    }

    /** Appends every real page of [entry] onto the end of the current list — this is the "merge" operation. */
    fun mergeFrom(entry: PdfEntry) {
        viewModelScope.launch {
            try {
                val count = pdfPageEditor.pageCount(entry.file)
                if (count == 0) {
                    emitEvent(PdfPageManagerUiEvent.Error("${entry.displayName}.pdf mein koi page nahi mila"))
                    return@launch
                }
                val added = (0 until count).map { idx ->
                    ManagedPage(key = UUID.randomUUID().toString(), sourceFile = entry.file, pageIndex = idx)
                }
                _uiState.value = _uiState.value.copy(pages = _uiState.value.pages + added)
            } catch (e: IOException) {
                emitEvent(PdfPageManagerUiEvent.Error("Merge karte waqt error: ${e.message}"))
            }
        }
    }

    /** Writes the currently-selected pages out as a brand-new PDF — this is the "split" operation. */
    fun extractSelected(requestedName: String?) {
        val selected = _uiState.value.selectedKeys
        if (selected.isEmpty()) {
            emitEvent(PdfPageManagerUiEvent.Error("Pehle kam se kam ek page select karo"))
            return
        }
        val refs = _uiState.value.pages
            .filter { it.key in selected }
            .map { SourcePageRef(it.sourceFile, it.pageIndex, it.rotationDegrees) }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            try {
                val outFile = pdfStorage.newOutputFile(requestedName)
                val written = pdfPageEditor.build(refs, outFile)
                emitEvent(PdfPageManagerUiEvent.Extracted(outFile.nameWithoutExtension, written))
                refreshMergeCandidates()
            } catch (e: PdfPageEditor.PdfEditException) {
                emitEvent(PdfPageManagerUiEvent.Error(e.message ?: "Extract nahi ho paya"))
            } finally {
                _uiState.value = _uiState.value.copy(isSaving = false)
            }
        }
    }

    fun saveOverwrite() = save(overwrite = true, requestedName = null)

    fun saveAs(requestedName: String?) = save(overwrite = false, requestedName = requestedName)

    private fun save(overwrite: Boolean, requestedName: String?) {
        val pages = _uiState.value.pages
        if (pages.isEmpty()) {
            emitEvent(PdfPageManagerUiEvent.Error("Save karne ke liye kam se kam ek page chahiye"))
            return
        }
        val refs = pages.map { SourcePageRef(it.sourceFile, it.pageIndex, it.rotationDegrees) }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            try {
                if (overwrite) {
                    val tempFile = File(
                        sourceFile.parentFile,
                        "${sourceFile.nameWithoutExtension}_tmp_${System.currentTimeMillis()}.pdf"
                    )
                    val written = pdfPageEditor.build(refs, tempFile)
                    // Source pages are fully rendered into tempFile before
                    // we touch the original, so overwriting only happens
                    // once the new content is safely on disk.
                    if (!sourceFile.delete()) {
                        tempFile.delete()
                        throw PdfPageEditor.PdfEditException("Purani file overwrite nahi ho payi")
                    }
                    if (!tempFile.renameTo(sourceFile)) {
                        throw PdfPageEditor.PdfEditException("Save fail ho gaya — temp file rename nahi hui")
                    }
                    emitEvent(PdfPageManagerUiEvent.Saved(sourceFile.nameWithoutExtension, written))
                } else {
                    val outFile = pdfStorage.newOutputFile(requestedName)
                    val written = pdfPageEditor.build(refs, outFile)
                    emitEvent(PdfPageManagerUiEvent.Saved(outFile.nameWithoutExtension, written))
                }
                refreshMergeCandidates()
            } catch (e: PdfPageEditor.PdfEditException) {
                emitEvent(PdfPageManagerUiEvent.Error(e.message ?: "Save nahi ho paya"))
            } finally {
                _uiState.value = _uiState.value.copy(isSaving = false)
            }
        }
    }

    private fun emitEvent(event: PdfPageManagerUiEvent) {
        _events.trySend(event)
    }
}

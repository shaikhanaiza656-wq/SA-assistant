package com.sa.assistant.ui.pdfmark

import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sa.assistant.core.pdf.PdfAnnotationEditor
import com.sa.assistant.core.pdf.PdfStorage
import com.sa.assistant.data.model.AnnotationTool
import com.sa.assistant.data.model.NormPoint
import com.sa.assistant.data.model.PdfAnnotation
import com.sa.assistant.data.model.ShapeType
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

/** Default highlighter yellow — opaque; [PdfAnnotation.Highlight] applies its own translucency on top. */
private const val DEFAULT_COLOR_ARGB: Int = 0xFFFFD600.toInt()

data class PdfMarkUiState(
    val sourceFileName: String = "",
    val pageCount: Int = 0,
    val currentPageIndex: Int = 0,
    val pageBitmap: Bitmap? = null,
    val isPageLoading: Boolean = false,
    val annotationsByPage: Map<Int, List<PdfAnnotation>> = emptyMap(),
    val activeTool: AnnotationTool = AnnotationTool.HIGHLIGHT,
    val activeColorArgb: Int = DEFAULT_COLOR_ARGB,
    val activeShapeType: ShapeType = ShapeType.RECTANGLE,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false
) {
    val currentPageAnnotations: List<PdfAnnotation>
        get() = annotationsByPage[currentPageIndex] ?: emptyList()
}

/**
 * Backs [PdfMarkScreen] — Phase 3 Part 2B (complete): highlight,
 * underline, strikethrough, free-hand draw, text, sticky note and
 * shape (rectangle/oval/line/arrow) tools, with real undo/redo.
 * Every mutation here only ever edits the in-memory annotation map;
 * nothing touches the PDF on disk until [saveOverwrite] or [saveAs]
 * actually calls [PdfAnnotationEditor.build] and writes real bytes —
 * same discipline as [com.sa.assistant.ui.pdfpages.PdfPageManagerViewModel].
 */
@HiltViewModel
class PdfMarkViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val pdfStorage: PdfStorage,
    private val annotationEditor: PdfAnnotationEditor
) : ViewModel() {

    private val sourceFile: File = File(
        requireNotNull(savedStateHandle.get<String>("path")) { "PDF path navigation arg missing" }
    )

    private val _uiState = MutableStateFlow(
        PdfMarkUiState(sourceFileName = sourceFile.nameWithoutExtension)
    )
    val uiState: StateFlow<PdfMarkUiState> = _uiState.asStateFlow()

    private val _events = Channel<PdfMarkUiEvent>(Channel.BUFFERED)
    val events: Flow<PdfMarkUiEvent> = _events.receiveAsFlow()

    // Full-map snapshots, not diffs — annotation counts stay small enough
    // per page that this is simple and always correct, which matters more
    // here than shaving a few allocations.
    private val undoStack = ArrayDeque<Map<Int, List<PdfAnnotation>>>()
    private val redoStack = ArrayDeque<Map<Int, List<PdfAnnotation>>>()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            if (!sourceFile.exists()) {
                emitEvent(PdfMarkUiEvent.Error("PDF file nahi mili — delete ho chuki hogi"))
                _uiState.value = _uiState.value.copy(isLoading = false)
                return@launch
            }
            try {
                val count = annotationEditor.pageCount(sourceFile)
                if (count == 0) {
                    emitEvent(PdfMarkUiEvent.Error("Is PDF mein koi page nahi mila"))
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    return@launch
                }
                _uiState.value = _uiState.value.copy(pageCount = count, isLoading = false)
                loadPageBitmap(0)
            } catch (e: IOException) {
                emitEvent(PdfMarkUiEvent.Error("PDF padhne mein error: ${e.message}"))
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    private fun loadPageBitmap(index: Int, maxDimension: Int = 1600) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isPageLoading = true, currentPageIndex = index)
            try {
                val bmp = annotationEditor.renderPage(sourceFile, index, maxDimension)
                _uiState.value = _uiState.value.copy(pageBitmap = bmp, isPageLoading = false)
            } catch (e: IOException) {
                emitEvent(PdfMarkUiEvent.Error("Page render nahi ho paya: ${e.message}"))
                _uiState.value = _uiState.value.copy(isPageLoading = false)
            }
        }
    }

    fun goToPage(index: Int) {
        if (index !in 0 until _uiState.value.pageCount) return
        if (index == _uiState.value.currentPageIndex) return
        loadPageBitmap(index)
    }

    fun nextPage() = goToPage(_uiState.value.currentPageIndex + 1)
    fun previousPage() = goToPage(_uiState.value.currentPageIndex - 1)

    fun setTool(tool: AnnotationTool) {
        _uiState.value = _uiState.value.copy(activeTool = tool)
    }

    fun setColor(colorArgb: Int) {
        _uiState.value = _uiState.value.copy(activeColorArgb = colorArgb)
    }

    fun setShapeType(shapeType: ShapeType) {
        _uiState.value = _uiState.value.copy(activeShapeType = shapeType)
    }

    fun addHighlight(minX: Float, minY: Float, maxX: Float, maxY: Float) {
        val page = _uiState.value.currentPageIndex
        val color = _uiState.value.activeColorArgb
        mutateCurrentPage { it + PdfAnnotation.Highlight(newId(), page, color, minX, minY, maxX, maxY) }
    }

    fun addUnderline(startX: Float, endX: Float, y: Float) {
        val page = _uiState.value.currentPageIndex
        val color = _uiState.value.activeColorArgb
        mutateCurrentPage { it + PdfAnnotation.Underline(newId(), page, color, startX, endX, y) }
    }

    fun addStrikethrough(startX: Float, endX: Float, y: Float) {
        val page = _uiState.value.currentPageIndex
        val color = _uiState.value.activeColorArgb
        mutateCurrentPage { it + PdfAnnotation.Strikethrough(newId(), page, color, startX, endX, y) }
    }

    fun addFreehand(points: List<NormPoint>) {
        if (points.size < 2) return
        val page = _uiState.value.currentPageIndex
        val color = _uiState.value.activeColorArgb
        mutateCurrentPage { it + PdfAnnotation.Freehand(newId(), page, color, points) }
    }

    fun addText(x: Float, y: Float, text: String) {
        if (text.isBlank()) return
        val page = _uiState.value.currentPageIndex
        val color = _uiState.value.activeColorArgb
        mutateCurrentPage { it + PdfAnnotation.TextBox(newId(), page, color, x, y, text) }
    }

    fun addStickyNote(x: Float, y: Float, text: String) {
        if (text.isBlank()) return
        val page = _uiState.value.currentPageIndex
        val color = _uiState.value.activeColorArgb
        mutateCurrentPage { it + PdfAnnotation.StickyNote(newId(), page, color, x, y, text) }
    }

    fun addShape(startX: Float, startY: Float, endX: Float, endY: Float) {
        val page = _uiState.value.currentPageIndex
        val color = _uiState.value.activeColorArgb
        val shapeType = _uiState.value.activeShapeType
        mutateCurrentPage { it + PdfAnnotation.Shape(newId(), page, color, shapeType, startX, startY, endX, endY) }
    }

    fun clearCurrentPage() {
        if (_uiState.value.currentPageAnnotations.isEmpty()) return
        mutateCurrentPage { emptyList() }
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        redoStack.addLast(_uiState.value.annotationsByPage)
        val previous = undoStack.removeLast()
        _uiState.value = _uiState.value.copy(annotationsByPage = previous)
        refreshUndoRedoFlags()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        undoStack.addLast(_uiState.value.annotationsByPage)
        val next = redoStack.removeLast()
        _uiState.value = _uiState.value.copy(annotationsByPage = next)
        refreshUndoRedoFlags()
    }

    fun saveOverwrite() = save(overwrite = true, requestedName = null)

    fun saveAs(requestedName: String?) = save(overwrite = false, requestedName = requestedName)

    private fun save(overwrite: Boolean, requestedName: String?) {
        val annotations = _uiState.value.annotationsByPage
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            try {
                if (overwrite) {
                    val tempFile = File(
                        sourceFile.parentFile,
                        "${sourceFile.nameWithoutExtension}_tmp_${System.currentTimeMillis()}.pdf"
                    )
                    val written = annotationEditor.build(sourceFile, annotations, tempFile)
                    // New content is fully rendered into tempFile before we
                    // touch the original, so overwriting only happens once
                    // the annotated version is safely on disk.
                    if (!sourceFile.delete()) {
                        tempFile.delete()
                        throw PdfAnnotationEditor.PdfAnnotateException("Purani file overwrite nahi ho payi")
                    }
                    if (!tempFile.renameTo(sourceFile)) {
                        throw PdfAnnotationEditor.PdfAnnotateException("Save fail ho gaya — temp file rename nahi hui")
                    }
                    emitEvent(PdfMarkUiEvent.Saved(sourceFile.nameWithoutExtension, written))
                    // Marks are now baked into the file on disk — clear the
                    // in-memory overlay and undo history so it isn't drawn
                    // twice, then re-render the current page fresh.
                    undoStack.clear()
                    redoStack.clear()
                    _uiState.value = _uiState.value.copy(annotationsByPage = emptyMap(), canUndo = false, canRedo = false)
                    loadPageBitmap(_uiState.value.currentPageIndex)
                } else {
                    val outFile = pdfStorage.newOutputFile(requestedName)
                    val written = annotationEditor.build(sourceFile, annotations, outFile)
                    emitEvent(PdfMarkUiEvent.Saved(outFile.nameWithoutExtension, written))
                }
            } catch (e: PdfAnnotationEditor.PdfAnnotateException) {
                emitEvent(PdfMarkUiEvent.Error(e.message ?: "Save nahi ho paya"))
            } finally {
                _uiState.value = _uiState.value.copy(isSaving = false)
            }
        }
    }

    private fun mutateCurrentPage(transform: (List<PdfAnnotation>) -> List<PdfAnnotation>) {
        pushUndoSnapshot()
        val page = _uiState.value.currentPageIndex
        val current = _uiState.value.annotationsByPage
        val updated = current + (page to transform(current[page] ?: emptyList()))
        _uiState.value = _uiState.value.copy(annotationsByPage = updated)
    }

    private fun pushUndoSnapshot() {
        undoStack.addLast(_uiState.value.annotationsByPage)
        if (undoStack.size > 50) undoStack.removeFirst()
        redoStack.clear()
        refreshUndoRedoFlags()
    }

    private fun refreshUndoRedoFlags() {
        _uiState.value = _uiState.value.copy(canUndo = undoStack.isNotEmpty(), canRedo = redoStack.isNotEmpty())
    }

    private fun newId() = UUID.randomUUID().toString()

    private fun emitEvent(event: PdfMarkUiEvent) {
        _events.trySend(event)
    }
}

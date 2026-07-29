package com.sa.assistant.ui.pdfmark

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowRightAlt
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.StickyNote2
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.FormatStrikethrough
import androidx.compose.material.icons.outlined.FormatUnderlined
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.sa.assistant.core.pdf.PdfAnnotationRenderer
import com.sa.assistant.data.model.AnnotationTool
import com.sa.assistant.data.model.NormPoint
import com.sa.assistant.data.model.PdfAnnotation
import com.sa.assistant.data.model.ShapeType
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private val PALETTE = listOf(
    0xFFFFD600.toInt(), // yellow — default highlighter
    0xFF00E676.toInt(), // green
    0xFFFF4081.toInt(), // pink
    0xFF2979FF.toInt(), // blue
    0xFFFF1744.toInt(), // red
    0xFF212121.toInt()  // black
)

/**
 * Phase 3 Part 2B (complete, 1+2 of 2): highlight / underline /
 * strikethrough / free-hand draw / text / sticky note / shape
 * (rectangle/oval/line/arrow), with undo/redo and a shared color
 * palette across every tool. Opened from
 * [com.sa.assistant.ui.pdfstudio.PdfStudioScreen] by tapping the
 * "mark/edit" action on a saved PDF row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfMarkScreen(
    onBack: () -> Unit,
    viewModel: PdfMarkViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showMenu by remember { mutableStateOf(false) }
    var showSaveAsDialog by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is PdfMarkUiEvent.Error -> scope.launch { snackbarHostState.showSnackbar(event.message) }
                is PdfMarkUiEvent.Saved -> scope.launch {
                    snackbarHostState.showSnackbar("${event.displayName}.pdf save ho gaya (${event.pageCount} pages)")
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("${state.sourceFileName}.pdf", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.isSaving) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                    } else {
                        IconButton(onClick = { viewModel.saveOverwrite() }) {
                            Icon(Icons.Filled.Save, contentDescription = "Save")
                        }
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "More")
                            }
                            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Save As naya PDF...") },
                                    onClick = { showMenu = false; showSaveAsDialog = true }
                                )
                                DropdownMenuItem(
                                    text = { Text("Is page ke saare marks hatao") },
                                    onClick = { showMenu = false; showClearConfirm = true }
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Toolbar(
                activeTool = state.activeTool,
                activeColorArgb = state.activeColorArgb,
                activeShapeType = state.activeShapeType,
                canUndo = state.canUndo,
                canRedo = state.canRedo,
                onToolSelected = { viewModel.setTool(it) },
                onColorSelected = { viewModel.setColor(it) },
                onShapeTypeSelected = { viewModel.setShapeType(it) },
                onUndo = { viewModel.undo() },
                onRedo = { viewModel.redo() }
            )

            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when {
                    state.isLoading -> CircularProgressIndicator()
                    state.pageBitmap == null -> if (state.isPageLoading) {
                        CircularProgressIndicator()
                    } else {
                        Text(
                            "Page load nahi ho paya",
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                        )
                    }
                    else -> {
                        val bitmap = state.pageBitmap!!
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp)
                                .aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat())
                        ) {
                            androidx.compose.foundation.Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "Page ${state.currentPageIndex + 1}",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                            MarkOverlay(
                                annotations = state.currentPageAnnotations,
                                activeTool = state.activeTool,
                                activeColorArgb = state.activeColorArgb,
                                activeShapeType = state.activeShapeType,
                                onCommitHighlight = viewModel::addHighlight,
                                onCommitUnderline = viewModel::addUnderline,
                                onCommitStrikethrough = viewModel::addStrikethrough,
                                onCommitFreehand = viewModel::addFreehand,
                                onCommitText = viewModel::addText,
                                onCommitStickyNote = viewModel::addStickyNote,
                                onCommitShape = viewModel::addShape
                            )
                            if (state.isPageLoading) {
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) { CircularProgressIndicator() }
                            }
                        }
                    }
                }
            }

            PageNavBar(
                currentPage = state.currentPageIndex,
                pageCount = state.pageCount,
                onPrev = { viewModel.previousPage() },
                onNext = { viewModel.nextPage() }
            )
        }
    }

    if (showSaveAsDialog) {
        NameInputDialog(
            title = "Naye PDF ka naam",
            confirmLabel = "Save karo",
            onDismiss = { showSaveAsDialog = false },
            onConfirm = { name ->
                showSaveAsDialog = false
                viewModel.saveAs(name)
            }
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Is page ke marks hataye?") },
            text = { Text("Is page par bane sab highlight/underline/strikethrough/draw/text/note/shape marks hat jayenge.") },
            confirmButton = {
                Button(onClick = { viewModel.clearCurrentPage(); showClearConfirm = false }) { Text("Hataye") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showClearConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun Toolbar(
    activeTool: AnnotationTool,
    activeColorArgb: Int,
    activeShapeType: ShapeType,
    canUndo: Boolean,
    canRedo: Boolean,
    onToolSelected: (AnnotationTool) -> Unit,
    onColorSelected: (Int) -> Unit,
    onShapeTypeSelected: (ShapeType) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    ToolButton(
                        icon = Icons.Outlined.BorderColor,
                        label = "Highlight",
                        selected = activeTool == AnnotationTool.HIGHLIGHT,
                        onClick = { onToolSelected(AnnotationTool.HIGHLIGHT) }
                    )
                    ToolButton(
                        icon = Icons.Outlined.FormatUnderlined,
                        label = "Underline",
                        selected = activeTool == AnnotationTool.UNDERLINE,
                        onClick = { onToolSelected(AnnotationTool.UNDERLINE) }
                    )
                    ToolButton(
                        icon = Icons.Outlined.FormatStrikethrough,
                        label = "Strike",
                        selected = activeTool == AnnotationTool.STRIKETHROUGH,
                        onClick = { onToolSelected(AnnotationTool.STRIKETHROUGH) }
                    )
                    ToolButton(
                        icon = Icons.Outlined.Brush,
                        label = "Draw",
                        selected = activeTool == AnnotationTool.FREEHAND,
                        onClick = { onToolSelected(AnnotationTool.FREEHAND) }
                    )
                    ToolButton(
                        icon = Icons.Filled.TextFields,
                        label = "Text",
                        selected = activeTool == AnnotationTool.TEXT,
                        onClick = { onToolSelected(AnnotationTool.TEXT) }
                    )
                    ToolButton(
                        icon = Icons.Filled.StickyNote2,
                        label = "Note",
                        selected = activeTool == AnnotationTool.STICKY_NOTE,
                        onClick = { onToolSelected(AnnotationTool.STICKY_NOTE) }
                    )
                    ToolButton(
                        icon = Icons.Filled.Category,
                        label = "Shape",
                        selected = activeTool == AnnotationTool.SHAPE,
                        onClick = { onToolSelected(AnnotationTool.SHAPE) }
                    )
                }
                Row {
                    IconButton(onClick = onUndo, enabled = canUndo) {
                        Icon(Icons.Filled.Undo, contentDescription = "Undo")
                    }
                    IconButton(onClick = onRedo, enabled = canRedo) {
                        Icon(Icons.Filled.Redo, contentDescription = "Redo")
                    }
                }
            }
            if (activeTool == AnnotationTool.SHAPE) {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    ToolButton(
                        icon = Icons.Filled.CropSquare,
                        label = "Rect",
                        selected = activeShapeType == ShapeType.RECTANGLE,
                        onClick = { onShapeTypeSelected(ShapeType.RECTANGLE) }
                    )
                    ToolButton(
                        icon = Icons.Filled.Circle,
                        label = "Oval",
                        selected = activeShapeType == ShapeType.OVAL,
                        onClick = { onShapeTypeSelected(ShapeType.OVAL) }
                    )
                    ToolButton(
                        icon = Icons.Filled.Remove,
                        label = "Line",
                        selected = activeShapeType == ShapeType.LINE,
                        onClick = { onShapeTypeSelected(ShapeType.LINE) }
                    )
                    ToolButton(
                        icon = Icons.Filled.ArrowRightAlt,
                        label = "Arrow",
                        selected = activeShapeType == ShapeType.ARROW,
                        onClick = { onShapeTypeSelected(ShapeType.ARROW) }
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PALETTE.forEach { colorArgb ->
                    val isSelected = colorArgb == activeColorArgb
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clickable { onColorSelected(colorArgb) }
                            .then(
                                if (isSelected) {
                                    Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                } else Modifier
                            )
                            .padding(3.dp)
                            .background(Color(colorArgb), CircleShape)
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PageNavBar(currentPage: Int, pageCount: Int, onPrev: () -> Unit, onNext: () -> Unit) {
    Surface(shadowElevation = 4.dp, color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onPrev, enabled = currentPage > 0) {
                Icon(Icons.Filled.ChevronLeft, contentDescription = "Previous page")
            }
            Text(
                if (pageCount == 0) "-" else "${currentPage + 1} / $pageCount",
                style = MaterialTheme.typography.bodyMedium
            )
            IconButton(onClick = onNext, enabled = currentPage < pageCount - 1) {
                Icon(Icons.Filled.ChevronRight, contentDescription = "Next page")
            }
        }
    }
}

/**
 * Draws already-committed annotations for the current page plus a live
 * in-progress preview of whatever gesture the user's finger is currently
 * making, and commits a real [PdfAnnotation] the moment that gesture ends.
 */
@Composable
private fun MarkOverlay(
    annotations: List<PdfAnnotation>,
    activeTool: AnnotationTool,
    activeColorArgb: Int,
    activeShapeType: ShapeType,
    onCommitHighlight: (Float, Float, Float, Float) -> Unit,
    onCommitUnderline: (Float, Float, Float) -> Unit,
    onCommitStrikethrough: (Float, Float, Float) -> Unit,
    onCommitFreehand: (List<NormPoint>) -> Unit,
    onCommitText: (Float, Float, String) -> Unit,
    onCommitStickyNote: (Float, Float, String) -> Unit,
    onCommitShape: (Float, Float, Float, Float) -> Unit
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }
    val strokePoints = remember { mutableStateOf(listOf<Offset>()) }

    // Tap-placed tools (Text / Sticky Note) don't commit immediately —
    // the tap just records *where*; a dialog then asks *what*, and the
    // annotation is only added once the user confirms real text.
    var pendingTextAt by remember { mutableStateOf<Offset?>(null) }
    var pendingNoteAt by remember { mutableStateOf<Offset?>(null) }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { canvasSize = it }
            .pointerInput(activeTool) {
                if (activeTool == AnnotationTool.TEXT || activeTool == AnnotationTool.STICKY_NOTE) {
                    detectTapGestures { offset ->
                        if (activeTool == AnnotationTool.TEXT) pendingTextAt = offset else pendingNoteAt = offset
                    }
                } else {
                    detectDragGestures(
                        onDragStart = { offset ->
                            dragStart = offset
                            dragEnd = offset
                            if (activeTool == AnnotationTool.FREEHAND) strokePoints.value = listOf(offset)
                        },
                        onDrag = { change, _ ->
                            dragEnd = change.position
                            if (activeTool == AnnotationTool.FREEHAND) {
                                strokePoints.value = strokePoints.value + change.position
                            }
                        },
                        onDragEnd = {
                            val w = canvasSize.width.toFloat()
                            val h = canvasSize.height.toFloat()
                            if (w > 0f && h > 0f) {
                                when (activeTool) {
                                    AnnotationTool.HIGHLIGHT -> {
                                        val s = dragStart
                                        val e = dragEnd
                                        if (s != null && e != null) {
                                            onCommitHighlight(
                                                (minOf(s.x, e.x) / w).coerceIn(0f, 1f),
                                                (minOf(s.y, e.y) / h).coerceIn(0f, 1f),
                                                (maxOf(s.x, e.x) / w).coerceIn(0f, 1f),
                                                (maxOf(s.y, e.y) / h).coerceIn(0f, 1f)
                                            )
                                        }
                                    }
                                    AnnotationTool.UNDERLINE -> {
                                        val s = dragStart
                                        val e = dragEnd
                                        if (s != null && e != null) {
                                            onCommitUnderline(
                                                (minOf(s.x, e.x) / w).coerceIn(0f, 1f),
                                                (maxOf(s.x, e.x) / w).coerceIn(0f, 1f),
                                                (maxOf(s.y, e.y) / h).coerceIn(0f, 1f)
                                            )
                                        }
                                    }
                                    AnnotationTool.STRIKETHROUGH -> {
                                        val s = dragStart
                                        val e = dragEnd
                                        if (s != null && e != null) {
                                            onCommitStrikethrough(
                                                (minOf(s.x, e.x) / w).coerceIn(0f, 1f),
                                                (maxOf(s.x, e.x) / w).coerceIn(0f, 1f),
                                                (((s.y + e.y) / 2f) / h).coerceIn(0f, 1f)
                                            )
                                        }
                                    }
                                    AnnotationTool.FREEHAND -> {
                                        val norm = strokePoints.value.map { NormPoint((it.x / w).coerceIn(0f, 1f), (it.y / h).coerceIn(0f, 1f)) }
                                        onCommitFreehand(norm)
                                    }
                                    AnnotationTool.SHAPE -> {
                                        val s = dragStart
                                        val e = dragEnd
                                        // A shape keeps its own start->end direction (unlike
                                        // highlight's min/max box) since Line/Arrow need to
                                        // know which end the user dragged *to*.
                                        if (s != null && e != null) {
                                            onCommitShape(
                                                (s.x / w).coerceIn(0f, 1f),
                                                (s.y / h).coerceIn(0f, 1f),
                                                (e.x / w).coerceIn(0f, 1f),
                                                (e.y / h).coerceIn(0f, 1f)
                                            )
                                        }
                                    }
                                    AnnotationTool.TEXT, AnnotationTool.STICKY_NOTE, AnnotationTool.NONE -> Unit
                                }
                            }
                            dragStart = null
                            dragEnd = null
                            strokePoints.value = emptyList()
                        }
                    )
                }
            }
    ) {
        val w = size.width
        val h = size.height

        for (annotation in annotations) {
            drawAnnotation(annotation, w, h)
        }

        // Live preview of the in-progress gesture, in the currently active color.
        val previewColor = Color(activeColorArgb)
        when (activeTool) {
            AnnotationTool.HIGHLIGHT -> {
                val s = dragStart; val e = dragEnd
                if (s != null && e != null) {
                    drawRect(
                        color = previewColor.copy(alpha = 0.35f),
                        topLeft = Offset(minOf(s.x, e.x), minOf(s.y, e.y)),
                        size = androidx.compose.ui.geometry.Size(kotlin.math.abs(e.x - s.x), kotlin.math.abs(e.y - s.y))
                    )
                }
            }
            AnnotationTool.UNDERLINE -> {
                val s = dragStart; val e = dragEnd
                if (s != null && e != null) {
                    val y = maxOf(s.y, e.y)
                    drawLine(previewColor, Offset(minOf(s.x, e.x), y), Offset(maxOf(s.x, e.x), y), strokeWidth = 0.004f * w)
                }
            }
            AnnotationTool.STRIKETHROUGH -> {
                val s = dragStart; val e = dragEnd
                if (s != null && e != null) {
                    val y = (s.y + e.y) / 2f
                    drawLine(previewColor, Offset(minOf(s.x, e.x), y), Offset(maxOf(s.x, e.x), y), strokeWidth = 0.004f * w)
                }
            }
            AnnotationTool.FREEHAND -> {
                if (strokePoints.value.size >= 2) {
                    val path = Path()
                    path.moveTo(strokePoints.value[0].x, strokePoints.value[0].y)
                    for (i in 1 until strokePoints.value.size) path.lineTo(strokePoints.value[i].x, strokePoints.value[i].y)
                    drawPath(path, previewColor, style = Stroke(width = 0.006f * w, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                }
            }
            AnnotationTool.SHAPE -> {
                val s = dragStart; val e = dragEnd
                if (s != null && e != null) {
                    // Preview reuses a live PdfAnnotation.Shape through the same
                    // renderer that bakes the final PDF, so what's shown while
                    // dragging is exactly what gets saved.
                    val previewShape = PdfAnnotation.Shape(
                        id = "preview",
                        pageIndex = 0,
                        colorArgb = activeColorArgb,
                        shapeType = activeShapeType,
                        startX = (s.x / w).coerceIn(0f, 1f),
                        startY = (s.y / h).coerceIn(0f, 1f),
                        endX = (e.x / w).coerceIn(0f, 1f),
                        endY = (e.y / h).coerceIn(0f, 1f)
                    )
                    drawContext.canvas.nativeCanvas.let { nc ->
                        PdfAnnotationRenderer.drawSingle(nc, previewShape, w.toInt(), h.toInt())
                    }
                }
            }
            AnnotationTool.TEXT, AnnotationTool.STICKY_NOTE, AnnotationTool.NONE -> Unit
        }
    }

    pendingTextAt?.let { offset ->
        val w = canvasSize.width.toFloat()
        val h = canvasSize.height.toFloat()
        AnnotationTextInputDialog(
            title = "Text daalein",
            placeholder = "Yahan apna text likhein",
            onDismiss = { pendingTextAt = null },
            onConfirm = { text ->
                if (w > 0f && h > 0f) {
                    onCommitText((offset.x / w).coerceIn(0f, 1f), (offset.y / h).coerceIn(0f, 1f), text)
                }
                pendingTextAt = null
            }
        )
    }

    pendingNoteAt?.let { offset ->
        val w = canvasSize.width.toFloat()
        val h = canvasSize.height.toFloat()
        AnnotationTextInputDialog(
            title = "Sticky note",
            placeholder = "Note ka text likhein",
            onDismiss = { pendingNoteAt = null },
            onConfirm = { text ->
                if (w > 0f && h > 0f) {
                    onCommitStickyNote((offset.x / w).coerceIn(0f, 1f), (offset.y / h).coerceIn(0f, 1f), text)
                }
                pendingNoteAt = null
            }
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawAnnotation(annotation: PdfAnnotation, w: Float, h: Float) {
    when (annotation) {
        is PdfAnnotation.Highlight -> drawRect(
            color = Color(annotation.colorArgb).copy(alpha = annotation.alpha / 255f),
            topLeft = Offset(annotation.minX * w, annotation.minY * h),
            size = androidx.compose.ui.geometry.Size((annotation.maxX - annotation.minX) * w, (annotation.maxY - annotation.minY) * h)
        )
        is PdfAnnotation.Underline -> drawLine(
            color = Color(annotation.colorArgb),
            start = Offset(annotation.startX * w, annotation.y * h),
            end = Offset(annotation.endX * w, annotation.y * h),
            strokeWidth = annotation.strokeWidthFraction * w,
            cap = androidx.compose.ui.graphics.StrokeCap.Round
        )
        is PdfAnnotation.Strikethrough -> drawLine(
            color = Color(annotation.colorArgb),
            start = Offset(annotation.startX * w, annotation.y * h),
            end = Offset(annotation.endX * w, annotation.y * h),
            strokeWidth = annotation.strokeWidthFraction * w,
            cap = androidx.compose.ui.graphics.StrokeCap.Round
        )
        is PdfAnnotation.Freehand -> {
            if (annotation.points.size >= 2) {
                val path = Path()
                path.moveTo(annotation.points[0].x * w, annotation.points[0].y * h)
                for (i in 1 until annotation.points.size) {
                    path.lineTo(annotation.points[i].x * w, annotation.points[i].y * h)
                }
                drawPath(
                    path,
                    color = Color(annotation.colorArgb),
                    style = Stroke(
                        width = annotation.strokeWidthFraction * w,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                        join = androidx.compose.ui.graphics.StrokeJoin.Round
                    )
                )
            }
        }
        // Text/Sticky-Note/Shape go straight through the same renderer
        // that bakes the final saved PDF (via nativeCanvas), so there's
        // no second hand-written drawing implementation that could ever
        // drift out of sync with what actually gets saved.
        is PdfAnnotation.TextBox, is PdfAnnotation.StickyNote, is PdfAnnotation.Shape -> {
            PdfAnnotationRenderer.drawSingle(drawContext.canvas.nativeCanvas, annotation, w.toInt(), h.toInt())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnnotationTextInputDialog(
    title: String,
    placeholder: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(placeholder) },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text("Add karo") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NameInputDialog(
    title: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text("Khaali chhodo to auto-naam ban jayega") },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(text.ifBlank { null }) }) { Text(confirmLabel) }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

package com.sa.assistant.ui.pdfpages

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Save
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.sa.assistant.data.model.ManagedPage
import com.sa.assistant.data.model.PdfEntry
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Phase 3 Part 2A: merge / split / rotate / reorder / delete on an
 * already-saved PDF. Opened from [com.sa.assistant.ui.pdfstudio.PdfStudioScreen]
 * by tapping the "manage pages" action on a saved PDF row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfPageManagerScreen(
    onBack: () -> Unit,
    viewModel: PdfPageManagerViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showMergeDialog by remember { mutableStateOf(false) }
    var showExtractDialog by remember { mutableStateOf(false) }
    var showSaveAsDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is PdfPageManagerUiEvent.Error -> scope.launch { snackbarHostState.showSnackbar(event.message) }
                is PdfPageManagerUiEvent.Saved -> scope.launch {
                    snackbarHostState.showSnackbar("${event.displayName}.pdf save ho gaya (${event.pageCount} pages)")
                }
                is PdfPageManagerUiEvent.Extracted -> scope.launch {
                    snackbarHostState.showSnackbar("${event.displayName}.pdf ban gaya (${event.pageCount} pages)")
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
                                Icon(Icons.Filled.LibraryAdd, contentDescription = "More")
                            }
                            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Save As naya PDF...") },
                                    onClick = { showMenu = false; showSaveAsDialog = true }
                                )
                                DropdownMenuItem(
                                    text = { Text("Doosri PDF se pages add karo (Merge)") },
                                    onClick = { showMenu = false; showMergeDialog = true }
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                state.pages.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Is PDF mein koi page nahi mila",
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                    )
                }

                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(state.pages, key = { it.key }) { page ->
                        val index = state.pages.indexOf(page)
                        PageCard(
                            page = page,
                            pageNumber = index + 1,
                            isSelected = page.key in state.selectedKeys,
                            thumbnail = state.thumbnails[page.key],
                            canMoveUp = index > 0,
                            canMoveDown = index < state.pages.lastIndex,
                            onEnsureThumbnail = { viewModel.ensureThumbnail(page) },
                            onToggleSelect = { viewModel.toggleSelected(page.key) },
                            onRotateLeft = { viewModel.rotatePage(page.key, -90) },
                            onRotateRight = { viewModel.rotatePage(page.key, 90) },
                            onMoveUp = { viewModel.movePage(page.key, -1) },
                            onMoveDown = { viewModel.movePage(page.key, 1) },
                            onDelete = { viewModel.deletePage(page.key) }
                        )
                    }
                }
            }

            Surface(
                shadowElevation = 4.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        enabled = state.selectedKeys.isNotEmpty() && !state.isSaving,
                        onClick = { showExtractDialog = true }
                    ) {
                        Icon(Icons.Filled.ContentCut, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Split (${state.selectedKeys.size})")
                    }
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        enabled = state.selectedKeys.isNotEmpty() && !state.isSaving,
                        onClick = { viewModel.deleteSelected() }
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Delete")
                    }
                }
            }
        }
    }

    if (showMergeDialog) {
        MergePickerDialog(
            candidates = state.availableForMerge,
            onDismiss = { showMergeDialog = false },
            onPick = { entry ->
                showMergeDialog = false
                viewModel.mergeFrom(entry)
            }
        )
    }

    if (showExtractDialog) {
        NameInputDialog(
            title = "Naya PDF ka naam",
            confirmLabel = "Split karo",
            onDismiss = { showExtractDialog = false },
            onConfirm = { name ->
                showExtractDialog = false
                viewModel.extractSelected(name)
            }
        )
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
}

@Composable
private fun PageCard(
    page: ManagedPage,
    pageNumber: Int,
    isSelected: Boolean,
    thumbnail: android.graphics.Bitmap?,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onEnsureThumbnail: () -> Unit,
    onToggleSelect: () -> Unit,
    onRotateLeft: () -> Unit,
    onRotateRight: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit
) {
    LaunchedEffect(page.key, page.rotationDegrees) { onEnsureThumbnail() }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = if (isSelected) {
            androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else null
    ) {
        Column(modifier = Modifier.padding(6.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.72f)
                    .clickable(onClick = onToggleSelect)
            ) {
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail.asImageBitmap(),
                        contentDescription = "Page $pageNumber",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background, RoundedCornerShape(8.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }

                Icon(
                    imageVector = if (isSelected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                    contentDescription = if (isSelected) "Selected" else "Not selected",
                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), RoundedCornerShape(50))
                        .size(20.dp)
                )

                Text(
                    "$pageNumber",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp)
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = onRotateLeft, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.RotateLeft, contentDescription = "Rotate left", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.ArrowUpward, contentDescription = "Move up", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.ArrowDownward, contentDescription = "Move down", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onRotateRight, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.RotateRight, contentDescription = "Rotate right", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete page", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun MergePickerDialog(
    candidates: List<PdfEntry>,
    onDismiss: () -> Unit,
    onPick: (PdfEntry) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Kis PDF se pages add karein?") },
        text = {
            if (candidates.isEmpty()) {
                Text("Koi aur saved PDF nahi mili merge karne ke liye.")
            } else {
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.height(280.dp)
                ) {
                    items(candidates, key = { it.file.absolutePath }) { entry ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable { onPick(entry) },
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text("${entry.displayName}.pdf", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "${entry.pageCount} pages",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        },
        confirmButton = {
            OutlinedButton(onClick = onDismiss) { Text("Band karo") }
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

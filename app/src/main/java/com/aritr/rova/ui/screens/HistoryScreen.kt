package com.aritr.rova.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.aritr.rova.RovaApp
import com.aritr.rova.data.SessionManifest
import com.aritr.rova.ui.PreviewActivity
import com.aritr.rova.ui.recovery.RecoveryCardKind
import com.aritr.rova.ui.recovery.RecoveryCardList
import com.aritr.rova.ui.recovery.RecoveryViewModel
import com.aritr.rova.ui.recovery.VendorGuidanceIntents
import com.aritr.rova.ui.share.safeShareUri
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

private val dateGroupFormat = SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault())
private val timeDisplayFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(viewModel: HistoryViewModel = viewModel(), onNavigateToRecord: () -> Unit = {}) {
    val context = LocalContext.current
    val items by viewModel.items.collectAsStateWithLifecycle()

    // Phase 2 Slice 2.1b — recovery cards above the library list. Pulls
    // from RovaApp.recoveryReport (single source of truth) and routes
    // manifest reads through SessionStore. The `videosRoot == null`
    // branch (storage unavailable at boot) guards against the lazy
    // sessionStore initializer error path: in that case the scan never
    // runs anyway, so loadManifest just returns null and the card list
    // stays empty.
    val app = context.applicationContext as RovaApp
    val recoveryViewModel: RecoveryViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                // `videosRoot == null` (storage unavailable at boot)
                // disables both load AND discard — the scan never ran,
                // so there is nothing to load, and discardSession on a
                // missing dir is a no-op anyway. Guarding the discard
                // lambda the same way avoids a hot-path NullPointer if
                // sessionStore initialization throws.
                val sessionStoreAvailable = app.videosRoot != null
                val loadManifest: (String) -> SessionManifest? = if (sessionStoreAvailable) {
                    { id -> app.sessionStore.loadManifest(id) }
                } else {
                    { _ -> null }
                }
                val discardSession: suspend (String) -> Unit = if (sessionStoreAvailable) {
                    { id -> app.sessionStore.discardSession(id) }
                } else {
                    { _ -> }
                }
                RecoveryViewModel(
                    recoveryReport = app.recoveryReport,
                    loadManifest = loadManifest,
                    discardSession = discardSession,
                )
            }
        }
    )
    val recoveryUiState by recoveryViewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    val onRecoveryDiscard: (String) -> Unit = { sessionId ->
        recoveryViewModel.dismiss(sessionId)
    }
    val vendorHelpSlotFor: (String) -> (@Composable () -> Unit)? = { sessionId ->
        val card = recoveryUiState.cards.firstOrNull { it.sessionId == sessionId }
        if (card?.kind == RecoveryCardKind.KILLED_BY_SYSTEM) {
            {
                OutlinedButton(
                    onClick = {
                        VendorGuidanceIntents.resolveForCurrent(context)?.let { intent ->
                            try {
                                context.startActivity(intent)
                            } catch (_: ActivityNotFoundException) {
                                // Component vanished between resolve and launch — rare
                                // (firmware/component drift). Silent fail keeps the
                                // recovery card usable; user can still tap Discard.
                            }
                        }
                    }
                ) { Text("Open device settings") }
            }
        } else {
            null
        }
    }

    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedFiles by remember { mutableStateOf(setOf<File>()) }

    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    LaunchedEffect(items) {
        if (isSelectionMode) {
            val existingFiles = items.map { it.file }.toSet()
            selectedFiles = selectedFiles.intersect(existingFiles)
            if (selectedFiles.isEmpty()) isSelectionMode = false
        }
    }

    val groupedItems = remember(items) {
        items.groupBy { item ->
            dateGroupFormat.format(Date(item.file.lastModified()))
        }
    }
    val totalSize = remember(items) { items.sumOf { it.file.length() } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.24f),
                        MaterialTheme.colorScheme.background
                    )
                )
            )
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                if (isSelectionMode) {
                    TopAppBar(
                        title = { Text("${selectedFiles.size} selected") },
                        navigationIcon = {
                            IconButton(onClick = { isSelectionMode = false; selectedFiles = emptySet() }) {
                                Icon(Icons.Default.Close, "Close selection")
                            }
                        },
                        actions = {
                            IconButton(onClick = {
                                val itemsByFile = items.associateBy { it.file }
                                val uris = ArrayList<Uri>(selectedFiles.size)
                                var anyMissing = false
                                selectedFiles.forEach { file ->
                                    val item = itemsByFile[file]
                                    val uri = item?.let { safeShareUri(context, it.file, it.shareUri) }
                                    if (uri != null) uris.add(uri) else anyMissing = true
                                }
                                if (uris.isEmpty()) {
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar("Recording not ready to share yet")
                                    }
                                    return@IconButton
                                }
                                if (anyMissing) {
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar("Recording not ready to share yet")
                                    }
                                }
                                val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                    type = "video/mp4"
                                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                try {
                                    context.startActivity(Intent.createChooser(intent, "Share videos"))
                                } catch (_: ActivityNotFoundException) {
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar("No app available to share videos")
                                    }
                                }
                            }) {
                                Icon(Icons.Default.Share, "Share")
                            }
                            IconButton(onClick = {
                                // Resolve selected files to VideoItem so deleteItems
                                // can access each entry's shareUri (the only delete
                                // path that removes public-gallery rows on API 29+).
                                val itemsByFile = items.associateBy { it.file }
                                val toDelete = selectedFiles.mapNotNull { itemsByFile[it] }
                                isSelectionMode = false
                                selectedFiles = emptySet()
                                if (toDelete.isEmpty()) return@IconButton
                                coroutineScope.launch {
                                    val result = viewModel.deleteItems(toDelete)
                                    if (result.failed > 0) {
                                        val plural = if (result.failed == 1) "recording" else "recordings"
                                        snackbarHostState.showSnackbar(
                                            "Could not delete ${result.failed} $plural"
                                        )
                                    }
                                }
                            }) {
                                Icon(Icons.Default.Delete, "Delete")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent,
                            titleContentColor = MaterialTheme.colorScheme.onSurface
                        )
                    )
                } else {
                    TopAppBar(
                        title = {
                            Column {
                                Text("Library", style = MaterialTheme.typography.titleLarge)
                                Text(
                                    "${items.size} recordings • ${formatFileSize(totalSize)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                    )
                }
            }
        ) { innerPadding ->
            if (items.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (recoveryUiState.cards.isNotEmpty() || recoveryUiState.hiddenCount > 0) {
                        RecoveryCardList(
                            state = recoveryUiState,
                            onDiscard = onRecoveryDiscard,
                            vendorHelpSlotFor = vendorHelpSlotFor,
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(32.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
                        tonalElevation = 4.dp
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 30.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Surface(
                                shape = RoundedCornerShape(24.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(82.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.Videocam,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(36.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(18.dp))
                            Text(
                                "Your library is empty",
                                style = MaterialTheme.typography.headlineSmall
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Once you finish a recording loop, merged videos appear here with thumbnails and quick share actions.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(22.dp))
                            FilledTonalButton(onClick = onNavigateToRecord) {
                                Icon(Icons.Default.Add, null)
                                Spacer(Modifier.size(8.dp))
                                Text("Create your first session")
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (recoveryUiState.cards.isNotEmpty() || recoveryUiState.hiddenCount > 0) {
                        item(key = "recovery-cards") {
                            RecoveryCardList(
                                state = recoveryUiState,
                                onDiscard = onRecoveryDiscard,
                                vendorHelpSlotFor = vendorHelpSlotFor,
                            )
                        }
                    }
                    item {
                        HistorySummaryCard(
                            recordingCount = items.size,
                            totalSize = totalSize,
                            onNavigateToRecord = onNavigateToRecord
                        )
                    }

                    groupedItems.forEach { (date, dateItems) ->
                        stickyHeader {
                            Text(
                                text = date,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.background)
                                    .padding(vertical = 6.dp)
                            )
                        }

                        items(dateItems, key = { it.file.absolutePath }) { item ->
                            val isSelected = selectedFiles.contains(item.file)
                            VideoCard(
                                item = item,
                                isSelectionMode = isSelectionMode,
                                isSelected = isSelected,
                                onToggleSelection = {
                                    if (isSelectionMode) {
                                        selectedFiles = if (isSelected) selectedFiles - item.file else selectedFiles + item.file
                                        if (selectedFiles.isEmpty()) isSelectionMode = false
                                    } else {
                                        isSelectionMode = true
                                        selectedFiles = setOf(item.file)
                                    }
                                },
                                onPlay = {
                                    if (isSelectionMode) {
                                        selectedFiles = if (isSelected) selectedFiles - item.file else selectedFiles + item.file
                                        if (selectedFiles.isEmpty()) isSelectionMode = false
                                    } else {
                                        val intent = Intent(context, PreviewActivity::class.java).apply {
                                            putExtra("VIDEO_PATH", item.file.absolutePath)
                                            // Plumb the safe share URI through so PreviewActivity
                                            // can avoid FileProvider on Movies/Rova paths. Stringly
                                            // typed across the Intent boundary to dodge Parcelable
                                            // schema drift between process versions.
                                            item.shareUri?.let { putExtra("SHARE_URI", it.toString()) }
                                        }
                                        context.startActivity(intent)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistorySummaryCard(
    recordingCount: Int,
    totalSize: Long,
    onNavigateToRecord: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(30.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Session archive",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                text = "Review merged clips, share them, or jump straight back into capture mode.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SummaryBadge("$recordingCount recordings")
                SummaryBadge(formatFileSize(totalSize))
            }
            FilledTonalButton(onClick = onNavigateToRecord) {
                Icon(Icons.Default.Videocam, null)
                Spacer(Modifier.size(8.dp))
                Text("Record another session")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoCard(
    item: VideoItem,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onToggleSelection: () -> Unit,
    onPlay: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onPlay,
                onLongClick = onToggleSelection
            )
            .then(
                if (isSelected) {
                    Modifier.border(
                        BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                        RoundedCornerShape(26.dp)
                    )
                } else {
                    Modifier
                }
            ),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelection() }
                )
                Spacer(modifier = Modifier.size(8.dp))
            }

            Box {
                VideoThumbnail(
                    thumbnail = item.thumbnail,
                    modifier = Modifier
                        .size(width = 102.dp, height = 74.dp)
                        .clip(RoundedCornerShape(20.dp))
                )
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = Color.Black.copy(alpha = 0.58f),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                ) {
                    Text(
                        text = item.resolution,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.size(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.file.nameWithoutExtension,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = formatTime(item.file.lastModified()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                Text(
                    text = formatFileSize(item.file.length()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!isSelectionMode) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Open",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun VideoThumbnail(thumbnail: Bitmap?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (thumbnail != null) {
            val imageBitmap = remember(thumbnail) { thumbnail.asImageBitmap() }
            Image(
                bitmap = imageBitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Icon(
                Icons.Default.PlayCircle,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.78f),
                modifier = Modifier.size(28.dp)
            )
        } else {
            Icon(
                Icons.Default.PlayCircle,
                contentDescription = "Play",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SummaryBadge(text: String) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.08f)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

private fun formatFileSize(size: Long): String {
    val kb = size / 1024.0
    val mb = kb / 1024.0
    return if (mb > 1) String.format("%.1f MB", mb) else String.format("%.0f KB", kb)
}

private fun formatTime(millis: Long): String {
    return timeDisplayFormat.format(Date(millis))
}

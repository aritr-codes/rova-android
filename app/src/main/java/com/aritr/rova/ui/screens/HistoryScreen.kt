package com.aritr.rova.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.aritr.rova.RovaApp
import com.aritr.rova.data.RovaSettings
import com.aritr.rova.data.SessionManifest
import com.aritr.rova.ui.PreviewActivity
import com.aritr.rova.ui.recovery.RecoveryCardKind
import com.aritr.rova.ui.recovery.RecoveryCardList
import com.aritr.rova.ui.recovery.RecoveryViewModel
import com.aritr.rova.ui.recovery.VendorGuidanceIntents
import com.aritr.rova.ui.share.safeShareUri
import java.io.File
import kotlinx.coroutines.launch

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

    // Slice 13B — surface auto-retention cleanup outcomes. Emits only
    // when the cleanup deleted or failed at least one recording, so a
    // refresh that no-ops (library already inside the keep-latest
    // window) shows nothing.
    LaunchedEffect(viewModel) {
        viewModel.retentionNotices.collect { notice ->
            RetentionCleanupNotices.message(notice)?.let { msg ->
                snackbarHostState.showSnackbar(msg)
            }
        }
    }

    LaunchedEffect(items) {
        if (isSelectionMode) {
            val existingFiles = items.map { it.file }.toSet()
            selectedFiles = selectedFiles.intersect(existingFiles)
            if (selectedFiles.isEmpty()) isSelectionMode = false
        }
    }

    // Slice 4 — group rows by the human-friendly sticky-header label
    // (Today / Yesterday / "May 1, 2026"). The header text is computed
    // once per items snapshot from the row formatter; downstream the
    // map preserves insertion order so newest groups stay on top.
    val nowMillis = remember(items) { System.currentTimeMillis() }
    val groupedItems = remember(items, nowMillis) {
        items.groupBy { item ->
            HistoryRowFormatters.formatGroupHeader(item.file.lastModified(), nowMillis)
        }
    }
    val totalSize = remember(items) { items.sumOf { it.file.length() } }
    val retentionPill = remember(items, context) {
        val settings = RovaSettings(context)
        HistoryRowFormatters.formatRetentionPill(
            autoDeleteEnabled = settings.autoDeleteEnabled,
            keepLatest = settings.autoDeleteKeepLatest
        )
    }

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
                                    text = HistoryRowFormatters.formatLibrarySummary(
                                        recordingCount = items.size,
                                        totalBytes = totalSize
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (retentionPill != null) {
                                    Spacer(Modifier.height(6.dp))
                                    Surface(
                                        shape = RoundedCornerShape(999.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant
                                    ) {
                                        Text(
                                            text = retentionPill,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(
                                                horizontal = 10.dp,
                                                vertical = 4.dp
                                            )
                                        )
                                    }
                                }
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
                            LibraryRow(
                                item = item,
                                nowMillis = nowMillis,
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

/**
 * Slice 4 — list-row redesign for the Library. Replaces the
 * decorative `ElevatedCard` rows with a soft Surface row whose
 * primary text is the human date · time pulled from
 * [HistoryRowFormatters.formatPrimaryDateTime]; the filename
 * survives as a small monospace caption so users debugging an
 * artifact can still locate it.
 *
 * Selection contract is unchanged from the prior `VideoCard`:
 *   - tap     → play (or toggle selection in selection mode)
 *   - long-press → enter selection / toggle this row
 *   - per-row 48 dp `MoreVert` overflow → enters selection mode for
 *     this row, surfacing the existing top-app-bar Share / Delete
 *     actions without introducing a new sheet route.
 *
 * The whole row's `combinedClickable` carries a single
 * `contentDescription` so TalkBack reads `"Recording May 4 · 2:22 PM,
 * quality FHD, size 82.4 MB"` on focus, not the filename string. The
 * overflow icon button has its own `contentDescription` describing
 * its scope (`"More actions for May 4 · 2:22 PM recording"`).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryRow(
    item: VideoItem,
    nowMillis: Long,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onToggleSelection: () -> Unit,
    onPlay: () -> Unit
) {
    val primary = remember(item.file, nowMillis) {
        HistoryRowFormatters.formatPrimaryDateTime(item.file.lastModified())
    }
    val time24 = remember(item.file) {
        HistoryRowFormatters.formatTime24(item.file.lastModified())
    }
    val sizeText = remember(item.file) {
        HistoryRowFormatters.formatSize(item.file.length())
    }
    val rowA11y = remember(primary, item.file, item.resolution) {
        HistoryRowFormatters.formatRowAccessibility(
            primaryDateTime = primary,
            sizeBytes = item.file.length(),
            quality = item.resolution
        )
    }
    val moreA11y = remember(primary) {
        HistoryRowFormatters.formatMoreActionsLabel(primary)
    }
    val rowBackground = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
    } else {
        Color.Transparent
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = rowBackground,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onPlay,
                onLongClick = onToggleSelection
            )
            .semantics { contentDescription = rowA11y }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelection() }
                )
                Spacer(modifier = Modifier.size(4.dp))
            }
            Box {
                VideoThumbnail(
                    thumbnail = item.thumbnail,
                    modifier = Modifier
                        .size(width = 96.dp, height = 64.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color.Black.copy(alpha = 0.7f),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp)
                ) {
                    Text(
                        text = item.resolution,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                }
            }
            Spacer(modifier = Modifier.size(12.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 4.dp)
            ) {
                Text(
                    text = primary,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row {
                    Text(
                        text = time24,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = " · ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Text(
                        text = sizeText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = item.file.name,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    ),
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // Slice 4 — explicit per-row overflow trigger. 48 dp
            // touch target via the M3 IconButton default.
            // Long-press still works as a fast keyboard-free entry
            // path; this button is the discoverable one.
            IconButton(onClick = onToggleSelection) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = moreA11y,
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


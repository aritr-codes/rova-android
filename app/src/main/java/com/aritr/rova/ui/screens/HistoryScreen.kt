package com.aritr.rova.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.aritr.rova.RovaApp
import com.aritr.rova.data.RovaSettings
import com.aritr.rova.data.SessionConfig
import com.aritr.rova.data.SessionManifest
import com.aritr.rova.data.StopReason
import com.aritr.rova.data.Terminated
import com.aritr.rova.service.RovaRecordingService
import com.aritr.rova.service.dualrecord.VideoSide
import com.aritr.rova.ui.PreviewActivity
import com.aritr.rova.ui.recovery.RecoveryCardKind
import com.aritr.rova.ui.recovery.RecoveryCardList
import com.aritr.rova.ui.recovery.RecoveryViewModel
import com.aritr.rova.ui.recovery.VendorGuidanceIntents
import com.aritr.rova.ui.share.safeShareUri
import com.aritr.rova.ui.theme.RovaTheme
import com.aritr.rova.ui.warnings.HistoryWarningSheetHost
import com.aritr.rova.ui.warnings.HistoryWarningStrip
import com.aritr.rova.ui.warnings.WarningCenterViewModel
import com.aritr.rova.ui.warnings.WarningId
import com.aritr.rova.ui.warnings.WarningScreen
import com.aritr.rova.ui.warnings.buildWarningCenterViewModel
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel = viewModel(),
    onNavigateToRecord: () -> Unit = {},
    onOpenPlayer: (sessionId: String, side: VideoSide?) -> Unit = { _, _ -> },
    onBack: () -> Unit = {}
) {
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
                val markKeptRaw: suspend (String) -> Unit = if (sessionStoreAvailable) {
                    { id ->
                        app.sessionStore.markTerminated(
                            sessionId = id,
                            terminated = Terminated.MULTI_SEGMENT_KEPT,
                            stopReason = StopReason.NONE,
                        )
                    }
                } else {
                    { _ -> }
                }
                val startRecoveryMergeFn: (String) -> Unit = { id ->
                    RovaRecordingService.startRecoveryMerge(context, id)
                }
                RecoveryViewModel(
                    recoveryReport = app.recoveryReport,
                    loadManifest = loadManifest,
                    discardSession = discardSession,
                    markKeptRaw = markKeptRaw,
                    startRecoveryMergeFn = startRecoveryMergeFn,
                    mergeOutcome = app.recoveryMergeOutcomeSignal.state,
                )
            }
        }
    )
    val recoveryUiState by recoveryViewModel.uiState.collectAsStateWithLifecycle()

    // Phase 4.2 — WarningCenter VM for History strip routing.
    val warningVm: WarningCenterViewModel? = remember(app) { buildWarningCenterViewModel(app) }
    val historyWarnings by (warningVm?.activeWarningsFor(WarningScreen.History) ?: MutableStateFlow(emptyList()))
        .collectAsStateWithLifecycle()
    val pendingCantMergeSessionId by (warningVm?.pendingCantMergeSessionId ?: MutableStateFlow(null))
        .collectAsStateWithLifecycle()
    var sheetWarningId by remember { mutableStateOf<WarningId?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    val onRecoveryDiscard: (String) -> Unit = { sessionId ->
        recoveryViewModel.dismiss(sessionId)
    }
    val onMerge: (String) -> Unit = { sessionId -> recoveryViewModel.merge(sessionId) }
    val onKeepRaw: (String) -> Unit = { sessionId -> recoveryViewModel.keepRaw(sessionId) }
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

    // Phase 2.2 — Library "View Settings" popup state. `viewSettingsConfig` is the
    // resolved config for the dialog body; null hides the dialog. The 3-dot menu
    // opens on per-row state (see LibraryRow). On legacy file-only rows (no
    // sessionId) or storage-unavailable boots, loadSessionConfig returns null and
    // the snackbar fires instead of opening an empty dialog.
    var viewSettingsConfig by remember { mutableStateOf<SessionConfig?>(null) }
    val onOpenViewSettings: (VideoItem) -> Unit = { item ->
        coroutineScope.launch {
            val cfg = viewModel.loadSessionConfig(item.sessionId)
            if (cfg != null) {
                viewSettingsConfig = cfg
            } else {
                snackbarHostState.showSnackbar(
                    "Settings not available for this recording"
                )
            }
        }
    }
    val onEditPlaceholder: () -> Unit = {
        coroutineScope.launch {
            snackbarHostState.showSnackbar("Editor coming in a future release")
        }
    }

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
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                    HistoryWarningStrip(
                        warningIds = historyWarnings,
                        onDismiss = { warningVm?.dismissOnHistoryStrip(it) },
                        onOpenSheet = { sheetWarningId = it },
                    )
                    if (recoveryUiState.cards.isNotEmpty() || recoveryUiState.hiddenCount > 0) {
                        RecoveryCardList(
                            state = recoveryUiState,
                            onDiscard = onRecoveryDiscard,
                            vendorHelpSlotFor = vendorHelpSlotFor,
                            onMerge = onMerge,
                            onKeepRaw = onKeepRaw,
                        )
                    }
                    LibraryEmptyState(onStartRecording = onNavigateToRecord)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (historyWarnings.isNotEmpty()) {
                        item(key = "warning-strip") {
                            HistoryWarningStrip(
                                warningIds = historyWarnings,
                                onDismiss = { warningVm?.dismissOnHistoryStrip(it) },
                                onOpenSheet = { sheetWarningId = it },
                            )
                        }
                    }
                    if (recoveryUiState.cards.isNotEmpty() || recoveryUiState.hiddenCount > 0) {
                        item(key = "recovery-cards") {
                            RecoveryCardList(
                                state = recoveryUiState,
                                onDiscard = onRecoveryDiscard,
                                vendorHelpSlotFor = vendorHelpSlotFor,
                                onMerge = onMerge,
                                onKeepRaw = onKeepRaw,
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
                            // Phase 2.5 — manifest-backed rows route
                            // through the new in-app `player/{sessionId}`
                            // composable. Legacy file-only rows (no
                            // sessionId — pre-Phase-1.7 builds did not
                            // write a manifest) keep the original
                            // PreviewActivity path so users upgrading
                            // do not lose the ability to preview those
                            // artifacts. PreviewActivity removal is
                            // tracked for a follow-up slice once the
                            // upgrade-window concern fades.
                            val playItem: () -> Unit = if (item.sessionId != null) {
                                // Phase 6.1b smoke-fix #3 — pass item.side
                                // through so P+L rows route to the per-side
                                // artifact. Single-mode VideoItem.side is
                                // null (HistoryViewModel single-mode branch
                                // leaves it at its default).
                                { onOpenPlayer(item.sessionId, item.side) }
                            } else {
                                {
                                    val intent = Intent(context, PreviewActivity::class.java).apply {
                                        putExtra("VIDEO_PATH", item.file.absolutePath)
                                        item.shareUri?.let { putExtra("SHARE_URI", it.toString()) }
                                    }
                                    context.startActivity(intent)
                                }
                            }
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
                                        playItem()
                                    }
                                },
                                onMenuOpen = playItem,
                                onMenuEdit = onEditPlaceholder,
                                onMenuViewSettings = { onOpenViewSettings(item) }
                            )
                        }
                    }
                }
            }
        }
    }

    viewSettingsConfig?.let { cfg ->
        LibrarySessionConfigDialog(
            config = cfg,
            onDismiss = { viewSettingsConfig = null }
        )
    }

    sheetWarningId?.let { id ->
        if (warningVm != null) {
            HistoryWarningSheetHost(
                id = id,
                vm = warningVm,
                pendingCantMergeSessionId = pendingCantMergeSessionId,
                onKeepRawFromSheet = onKeepRaw,
                onDiscardFromSheet = onRecoveryDiscard,
                onDismiss = { sheetWarningId = null },
            )
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
 * Interaction contract:
 *   - row tap → opens playback (`onPlay`) unless selection mode is
 *     active, in which case it toggles this row's selection.
 *   - row long-press → enters or toggles multi-select (`onToggleSelection`).
 *   - per-row 48 dp `MoreVert` overflow → opens the Phase 2.2 row
 *     menu with Open / Edit / View Settings when selection mode is
 *     NOT active. While selection mode is active, the overflow
 *     falls back to toggling this row's selection and must not open
 *     the menu (so the multi-select gesture is never shadowed).
 *   - the overflow remains a 48 dp `IconButton` with its own
 *     `contentDescription` describing its scope (`"More actions for
 *     May 4 · 2:22 PM recording"`).
 *
 * The whole row's `combinedClickable` carries a single
 * `contentDescription` so TalkBack reads `"Recording May 4 · 2:22 PM,
 * quality FHD, size 82.4 MB"` on focus, not the filename string.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryRow(
    item: VideoItem,
    nowMillis: Long,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onToggleSelection: () -> Unit,
    onPlay: () -> Unit,
    onMenuOpen: () -> Unit = {},
    onMenuEdit: () -> Unit = {},
    onMenuViewSettings: () -> Unit = {}
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
            // touch target via the M3 IconButton default. Long-press
            // on the row still enters multi-select; the overflow icon
            // now opens the Phase 2.2 dropdown menu (Open / Edit /
            // View Settings). In selection mode the icon falls back
            // to the legacy "tap to toggle" so the menu cannot
            // shadow the multi-select gesture.
            Box {
                var menuExpanded by remember { mutableStateOf(false) }
                IconButton(
                    onClick = {
                        if (isSelectionMode) onToggleSelection() else menuExpanded = true
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = moreA11y,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Open") },
                        onClick = {
                            menuExpanded = false
                            onMenuOpen()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        onClick = {
                            menuExpanded = false
                            onMenuEdit()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("View Settings") },
                        onClick = {
                            menuExpanded = false
                            onMenuViewSettings()
                        }
                    )
                }
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

/**
 * Phase 2.3 — Library "No Recordings Yet" empty state.
 *
 * Renders when `HistoryViewModel.items` is empty. The decorative
 * illustration is drawn in Compose via [Canvas] (concentric primary-tint
 * rings) with a centered Material `Videocam` glyph; this avoids adding a
 * new VectorDrawable XML resource that would bake mockup-specific hex
 * values into `res/drawable/`. All ring colors derive from
 * `MaterialTheme.colorScheme.primary` so light/dark themes share one
 * source of truth.
 *
 * Layout matches `mockups/new_uiux/03-history-library.html` Phone 4
 * (Empty State) — illustration + title + body + filled primary CTA.
 * The composable is callable from inside the `if (items.isEmpty())`
 * branch in both the recovery-card-stacked and standalone cases per
 * `docs/UI_NAV_GRAPH.md` §4.3 (Phase 2.3 owner).
 *
 * The illustration carries `contentDescription = null` (decorative); the
 * title, body, and CTA each announce themselves to TalkBack so the empty
 * state reads as one logical unit followed by an actionable button.
 */
@Composable
private fun LibraryEmptyState(
    onStartRecording: () -> Unit,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(96.dp)
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val r = size.minDimension / 2f
                    drawCircle(color = primary.copy(alpha = 0.06f), radius = r)
                    drawCircle(color = primary.copy(alpha = 0.10f), radius = r * 0.74f)
                }
                Icon(
                    Icons.Default.Videocam,
                    contentDescription = null,
                    tint = primary.copy(alpha = 0.85f),
                    modifier = Modifier.size(36.dp)
                )
            }
            Spacer(modifier = Modifier.height(18.dp))
            Text(
                "No Recordings Yet",
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Your merged sessions will appear here once you complete a recording.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(22.dp))
            Button(onClick = onStartRecording) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Start Recording")
            }
        }
    }
}

@Preview(
    name = "Library · Empty · Dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES
)
@Preview(
    name = "Library · Empty · Light",
    uiMode = Configuration.UI_MODE_NIGHT_NO
)
@Composable
private fun LibraryEmptyStatePreview() {
    // Phase 2.3 review-fix — backdrop comes from
    // `MaterialTheme.colorScheme.background` inside `RovaTheme` so the
    // dark/light variants stay theme-driven. No raw screen-level hex at
    // the call site, per Phase 1 token contract.
    RovaTheme {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                LibraryEmptyState(onStartRecording = {})
            }
        }
    }
}

@Preview(
    name = "Library · Empty + Recovery placeholder · Dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES
)
@Composable
private fun LibraryEmptyStateWithRecoveryPreview() {
    // Phase 2.3 — preview-only fake recovery placeholder (a Surface
    // pretending to be a recovery card). Uses no RecoveryViewModel /
    // RecoveryCardList wiring; only proves the empty state composes
    // cleanly below an arbitrary card-shaped block at the same width.
    // Phase 2.3 review-fix — backdrop is theme-driven via
    // `MaterialTheme.colorScheme.background` (no raw hex).
    RovaTheme {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            "Recovery card placeholder",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            "Stacked above the empty state in the real screen.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                LibraryEmptyState(onStartRecording = {})
            }
        }
    }
}


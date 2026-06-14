package com.aritr.rova.ui.library

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aritr.rova.R
import com.aritr.rova.RovaApp
import com.aritr.rova.service.dualrecord.VideoSide
import com.aritr.rova.ui.components.rememberReduceMotion
import com.aritr.rova.ui.library.components.LibraryBatchBar
import com.aritr.rova.ui.library.components.LibraryDayHeader
import com.aritr.rova.ui.library.components.LibraryEmpty
import com.aritr.rova.ui.library.components.LibraryGridCard
import com.aritr.rova.ui.library.components.LibraryHeroCard
import com.aritr.rova.ui.library.components.LibraryItemSheet
import com.aritr.rova.ui.library.components.LibraryListRow
import com.aritr.rova.ui.library.components.LibraryLoading
import com.aritr.rova.ui.library.components.LibraryRenameDialog
import com.aritr.rova.ui.library.components.LibrarySelectionTopBar
import com.aritr.rova.ui.library.components.LibraryTopBar
import com.aritr.rova.ui.library.components.statusBadgeLabel
import com.aritr.rova.ui.recovery.RecoveryCardKind
import com.aritr.rova.ui.recovery.RecoveryCardList
import com.aritr.rova.ui.recovery.RecoveryViewModel
import com.aritr.rova.ui.recovery.VendorGuidanceIntents
import com.aritr.rova.ui.recovery.recoveryViewModelFactory
import com.aritr.rova.ui.PreviewActivity
import com.aritr.rova.ui.screens.HistoryViewModel
import com.aritr.rova.ui.screens.LibrarySessionConfigDialog
import com.aritr.rova.ui.share.safeShareUri
import com.aritr.rova.ui.warnings.HistoryWarningSheetHost
import com.aritr.rova.ui.warnings.HistoryWarningStrip
import com.aritr.rova.ui.warnings.WarningId
import com.aritr.rova.ui.warnings.WarningScreen
import com.aritr.rova.ui.warnings.buildWarningCenterViewModel
import com.aritr.rova.ui.screens.RetentionCleanupNotices
import com.aritr.rova.data.SessionConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.TimeZone

private const val GRID_COLUMNS = 2

/**
 * spec §5 — redesigned Library route surface (hero + grid/list, day-grouped, glass chrome) PLUS the
 * Slice 3 management layer: long-press multi-select, glass contextual top bar + bottom batch bar
 * (Share/Vault/Favorite/Delete), Snackbar-UNDO deferred delete, per-item sheet (incl. Rename), and the
 * ported recovery-card header + warning strip. Replaces `HistoryScreen` on the `"history"` route.
 *
 * Hero invariant (owner adj. 1): `collection` is built with `hero?.stableKey`, so the newest recording
 * renders in exactly one place. Deferred-delete (owner + codex): rows pending delete are hidden via
 * [PendingDelete]; the real delete commits only on the Snackbar owner (timeout/swipe), UNDO cancels,
 * screen-dispose abandons (files untouched, rows reappear next load). No manifest writes here (ADR-0030).
 */
@Composable
fun LibraryScreen(
    viewModel: HistoryViewModel = viewModel(),
    onNavigateToRecord: () -> Unit = {},
    onOpenPlayer: (sessionId: String, side: VideoSide?) -> Unit = { _, _ -> },
    onOpenVault: () -> Unit = {},
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val app = context.applicationContext as RovaApp

    val ui by viewModel.libraryUiState.collectAsStateWithLifecycle()
    val items by viewModel.items.collectAsStateWithLifecycle()
    @Suppress("UNUSED_VARIABLE")
    val reduceMotion = rememberReduceMotion()

    // Recovery header (factory relocated to ui/recovery/ — ADR-0030 §2).
    val recoveryViewModel: RecoveryViewModel = viewModel(factory = recoveryViewModelFactory(app, context))
    val recoveryUiState by recoveryViewModel.uiState.collectAsStateWithLifecycle()

    // Warning strip.
    val warningVm = remember(app) { buildWarningCenterViewModel(app) }
    val historyWarnings by (warningVm?.activeWarningsFor(WarningScreen.History) ?: MutableStateFlow(emptyList()))
        .collectAsStateWithLifecycle()
    val pendingCantMergeSessionId by (warningVm?.pendingCantMergeSessionId ?: MutableStateFlow(null))
        .collectAsStateWithLifecycle()
    var sheetWarningId by remember { mutableStateOf<WarningId?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    // Selection + deferred-delete state.
    var selection by remember { mutableStateOf(SelectionState()) }
    var pending by remember { mutableStateOf(PendingDelete.NONE) }
    var pendingJob by remember { mutableStateOf<Job?>(null) }

    // Dialog / sheet targets.
    var viewSettingsConfig by remember { mutableStateOf<SessionConfig?>(null) }
    var pendingMoveToVaultSessionId by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<LibraryRow?>(null) }
    var sheetTarget by remember { mutableStateOf<LibraryRow?>(null) }
    var pendingDeleteConfirm by remember { mutableStateOf<Set<String>?>(null) }

    val byKey = remember(items) { items.associateBy { it.stableKey } }
    val rowByKey = remember(ui.rows) { ui.rows.associateBy { it.stableKey } }
    val locale = Locale.getDefault()
    val tz = TimeZone.getDefault()
    val nowMillis = remember(ui.rows) { System.currentTimeMillis() }

    // ---- effects ----
    LaunchedEffect(Unit) { viewModel.refresh() }
    LaunchedEffect(viewModel) {
        viewModel.retentionNotices.collect { notice ->
            RetentionCleanupNotices.message(notice)?.let { snackbarHostState.showSnackbar(it) }
        }
    }
    LaunchedEffect(items) {
        selection = SelectionReducer.reconcile(selection, items.map { it.stableKey }.toSet())
    }
    val sidecarErr by viewModel.sidecarWriteError.collectAsStateWithLifecycle()
    val sidecarErrMsg = stringResource(R.string.library_sidecar_write_error)
    LaunchedEffect(sidecarErr) {
        if (sidecarErr > 0) snackbarHostState.showSnackbar(sidecarErrMsg)
    }
    // Abandon any pending delete on dispose — never commit an irreversible op outside the snackbar owner.
    DisposableEffect(Unit) {
        onDispose {
            pendingJob?.cancel()
            pending = PendingDelete.NONE
        }
    }

    // ---- labels ----
    val frag = TileSemantics.Fragments(
        durationWord = stringResource(R.string.library_a11y_duration),
        recoveredWord = stringResource(R.string.library_badge_recovered),
        interruptedWord = stringResource(R.string.library_badge_interrupted),
        dualWord = stringResource(R.string.library_badge_pl),
    )
    val recoveredLabel = stringResource(R.string.library_badge_recovered)
    val interruptedLabel = stringResource(R.string.library_badge_interrupted)
    val plLabel = stringResource(R.string.library_badge_pl)
    val eyebrow = stringResource(R.string.library_eyebrow_latest)
    val favoriteLabel = stringResource(R.string.library_action_favorite)
    val unfavoriteLabel = stringResource(R.string.library_action_unfavorite)
    val shareLabel = stringResource(R.string.library_action_share)
    val selectedLabel = stringResource(R.string.library_a11y_selected)
    val notSelectedLabel = stringResource(R.string.library_a11y_not_selected)
    val shareNoApp = stringResource(R.string.history_share_no_app)
    val deleteUndoLabel = stringResource(R.string.library_delete_undo)

    // ---- share helper (no manifest writes; reuses ShareUriResolver) ----
    fun shareItems(targets: List<com.aritr.rova.ui.screens.VideoItem>) {
        val uris = ArrayList<android.net.Uri>()
        targets.forEach { item -> safeShareUri(context, item.file, item.shareUri)?.let { uris.add(it) } }
        if (uris.isEmpty()) {
            coroutineScope.launch { snackbarHostState.showSnackbar(shareNoApp) }
            return
        }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "video/mp4"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.history_share_chooser_title)))
        } catch (_: android.content.ActivityNotFoundException) {
            coroutineScope.launch { snackbarHostState.showSnackbar(shareNoApp) }
        }
    }

    fun play(stableKey: String) {
        val item = byKey[stableKey] ?: return
        val sid = item.sessionId
        if (sid != null) {
            onOpenPlayer(sid, item.side)
        } else {
            // Legacy file-only row (no manifest): keep the PreviewActivity path.
            item.file?.let { f ->
                val intent = Intent(context, PreviewActivity::class.java).apply {
                    putExtra("VIDEO_PATH", f.absolutePath)
                    item.shareUri?.let { putExtra("SHARE_URI", it.toString()) }
                }
                context.startActivity(intent)
            }
        }
    }

    // ---- deferred-delete owner coroutine (owner + codex) ----
    fun startDeferredDelete(keys: Set<String>) {
        if (keys.isEmpty()) return
        pending = PendingDelete(pending.keys + keys)
        selection = SelectionReducer.removeAll(selection, keys)
        pendingJob = coroutineScope.launch {
            val result = snackbarHostState.showSnackbar(
                message = context.getString(R.string.library_deleted_count, keys.size),
                actionLabel = deleteUndoLabel,
                duration = SnackbarDuration.Short,
            )
            when (result) {
                SnackbarResult.ActionPerformed -> pending = pending.restore(keys) // UNDO → un-hide
                SnackbarResult.Dismissed -> {                                      // timeout/swipe → commit
                    val targets = viewModel.itemsForKeys(keys)
                    val failed = viewModel.deleteItemsKeyed(targets)
                    pending = pending.restore(failed)  // failed rows reappear; deleted ones drop via refresh
                    if (failed.isNotEmpty()) {
                        snackbarHostState.showSnackbar(
                            context.getString(R.string.library_delete_failed, failed.size),
                        )
                    }
                }
            }
        }
    }

    // ---- derived (pending rows hidden everywhere) ----
    val visibleRows = remember(ui.rows, pending) { pending.visible(ui.rows) }
    val hero = remember(visibleRows) { LibraryQuery.hero(visibleRows) }
    val collection = remember(visibleRows, hero) {
        LibraryQuery.collection(visibleRows, LibrarySort.NEWEST, LibraryFilter(), hero?.stableKey)
    }
    val groups = remember(collection, nowMillis) { LibraryDayGrouping.group(collection, nowMillis, locale, tz) }

    val vendorHelpSlotFor: (String) -> (@Composable () -> Unit)? = { sessionId ->
        val card = recoveryUiState.cards.firstOrNull { it.sessionId == sessionId }
        if (card?.kind == RecoveryCardKind.KILLED_BY_SYSTEM) {
            {
                androidx.compose.material3.OutlinedButton(onClick = {
                    VendorGuidanceIntents.resolveForCurrent(context)?.let { intent ->
                        try {
                            context.startActivity(intent)
                        } catch (_: android.content.ActivityNotFoundException) {
                        }
                    }
                }) { androidx.compose.material3.Text(stringResource(R.string.history_recovery_open_device_settings)) }
            }
        } else {
            null
        }
    }

    @Composable
    fun RecoveryAndWarnings() {
        HistoryWarningStrip(
            warningIds = historyWarnings,
            onDismiss = { warningVm?.dismissOnHistoryStrip(it) },
            onOpenSheet = { sheetWarningId = it },
        )
        if (recoveryUiState.cards.isNotEmpty() || recoveryUiState.hiddenCount > 0) {
            RecoveryCardList(
                state = recoveryUiState,
                onDiscard = { recoveryViewModel.dismiss(it) },
                vendorHelpSlotFor = vendorHelpSlotFor,
                onMerge = { recoveryViewModel.merge(it) },
                onKeepRaw = { recoveryViewModel.keepRaw(it) },
            )
        }
    }

    @Composable
    fun renderHero(row: LibraryRow) {
        LibraryHeroCard(
            row = row,
            thumbnail = byKey[row.stableKey]?.thumbnail,
            eyebrow = eyebrow,
            playDescription = TileSemantics.describe(row, frag),
            favoriteLabel = favoriteLabel,
            unfavoriteLabel = unfavoriteLabel,
            shareLabel = shareLabel,
            onPlay = { play(row.stableKey) },
            onFavorite = { viewModel.toggleFavorite(row.stableKey) },
            onShare = { byKey[row.stableKey]?.let { shareItems(listOf(it)) } },
        )
    }

    // tile interaction: tap toggles in select mode else plays. Long-press toggles in select mode,
    // else opens the per-item sheet (§5.3) — which carries a "Select" entry to start multi-select.
    fun onTileClick(key: String) {
        selection = if (selection.active) SelectionReducer.toggle(selection, key) else { play(key); selection }
    }
    fun onTileLong(key: String) {
        if (selection.active) selection = SelectionReducer.toggle(selection, key)
        else sheetTarget = rowByKey[key]
    }

    val movableSelectedExists = remember(selection, byKey) {
        selection.keys.any { (ui.rows.firstOrNull { r -> r.stableKey == it }?.topology) != com.aritr.rova.data.CaptureTopology.DualShot }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.24f),
                        MaterialTheme.colorScheme.background,
                    ),
                ),
            ),
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                if (selection.active) {
                    LibrarySelectionTopBar(
                        countLabel = stringResource(R.string.library_select_count, selection.count),
                        closeLabel = stringResource(R.string.library_action_close_selection),
                        selectAllLabel = stringResource(R.string.library_action_select_all),
                        onClose = { selection = SelectionReducer.clear(selection) },
                        onSelectAll = {
                            selection = SelectionReducer.selectAll(selection, visibleRows.map { it.stableKey })
                        },
                    )
                } else {
                    LibraryTopBar(
                        title = stringResource(R.string.history_title),
                        viewMode = ui.viewMode,
                        gridLabel = stringResource(R.string.library_view_grid),
                        listLabel = stringResource(R.string.library_view_list),
                        onToggleView = {
                            viewModel.setViewMode(
                                if (ui.viewMode == LibraryViewMode.GRID) LibraryViewMode.LIST else LibraryViewMode.GRID,
                            )
                        },
                        onBack = onBack,
                        backLabel = stringResource(R.string.history_back_cd),
                        onOpenVault = onOpenVault,
                        vaultLabel = stringResource(R.string.vault_open_entry_cd),
                    )
                }
            },
            bottomBar = {
                if (selection.active) {
                    LibraryBatchBar(
                        shareLabel = shareLabel,
                        vaultLabel = stringResource(R.string.library_action_vault),
                        vaultDisabledLabel = stringResource(R.string.library_action_vault_disabled),
                        favoriteLabel = favoriteLabel,
                        deleteLabel = stringResource(R.string.library_action_delete),
                        vaultEnabled = movableSelectedExists,
                        onShare = { shareItems(viewModel.itemsForKeys(selection.keys)) },
                        onVault = { pendingMoveToVaultSessionId = "__batch__" },
                        onFavorite = {
                            // Batch favorite = mark all selected as favorited (skip already-favorited).
                            val keys = selection.keys
                            keys.forEach { k ->
                                if (ui.rows.firstOrNull { it.stableKey == k }?.favorite == false) {
                                    viewModel.toggleFavorite(k)
                                }
                            }
                            selection = SelectionReducer.clear(selection)
                        },
                        onDelete = { pendingDeleteConfirm = selection.keys },
                    )
                }
            },
        ) { innerPadding ->
            when {
                !ui.hasLoaded -> LibraryLoading(Modifier.fillMaxSize().padding(innerPadding))
                ui.rows.isEmpty() -> Column(
                    Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    RecoveryAndWarnings()
                    LibraryEmpty(
                        title = stringResource(R.string.library_empty_title),
                        body = stringResource(R.string.library_empty_body),
                        cta = stringResource(R.string.library_empty_cta),
                        onStartRecording = onNavigateToRecord,
                    )
                }
                ui.viewMode == LibraryViewMode.GRID -> LazyVerticalGrid(
                    columns = GridCells.Fixed(GRID_COLUMNS),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(horizontal = 8.dp)
                        .semantics {
                            isTraversalGroup = true
                            collectionInfo = CollectionInfo(rowCount = -1, columnCount = GRID_COLUMNS)
                        },
                    contentPadding = PaddingValues(bottom = 20.dp),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "hdr-recovery-warn") { RecoveryAndWarnings() }
                    if (hero != null) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "hero-${hero.stableKey}") { renderHero(hero) }
                    }
                    groups.forEach { group ->
                        item(span = { GridItemSpan(maxLineSpan) }, key = "hdr-${group.label}") {
                            LibraryDayHeader(group.label, group.sizeTotalLabel)
                        }
                        itemsIndexed(group.rows, key = { _, r -> r.stableKey }) { index, row ->
                            LibraryGridCard(
                                row = row,
                                thumbnail = byKey[row.stableKey]?.thumbnail,
                                tileDescription = TileSemantics.describe(row, frag),
                                statusLabel = statusBadgeLabel(row.badge, recoveredLabel, interruptedLabel),
                                plLabel = plLabel,
                                onClick = { onTileClick(row.stableKey) },
                                modifier = Modifier.padding(4.dp),
                                itemSemantics = {
                                    collectionItemInfo = CollectionItemInfo(
                                        rowIndex = index / GRID_COLUMNS,
                                        rowSpan = 1,
                                        columnIndex = index % GRID_COLUMNS,
                                        columnSpan = 1,
                                    )
                                },
                                isSelectionMode = selection.active,
                                isSelected = row.stableKey in selection.keys,
                                onLongClick = { onTileLong(row.stableKey) },
                                selectedLabel = selectedLabel,
                                notSelectedLabel = notSelectedLabel,
                            )
                        }
                    }
                }
                else -> LazyColumn(
                    Modifier.fillMaxSize().padding(innerPadding),
                    contentPadding = PaddingValues(bottom = 20.dp),
                ) {
                    item(key = "hdr-recovery-warn") { RecoveryAndWarnings() }
                    if (hero != null) {
                        item(key = "hero-${hero.stableKey}") { renderHero(hero) }
                    }
                    groups.forEach { group ->
                        item(key = "hdr-${group.label}") { LibraryDayHeader(group.label, group.sizeTotalLabel) }
                        items(group.rows, key = { it.stableKey }) { row ->
                            LibraryListRow(
                                row = row,
                                thumbnail = byKey[row.stableKey]?.thumbnail,
                                tileDescription = TileSemantics.describe(row, frag),
                                durationFallback = "—",
                                onClick = { onTileClick(row.stableKey) },
                                isSelectionMode = selection.active,
                                isSelected = row.stableKey in selection.keys,
                                onLongClick = { onTileLong(row.stableKey) },
                                selectedLabel = selectedLabel,
                                notSelectedLabel = notSelectedLabel,
                            )
                        }
                    }
                }
            }
        }

        // ---- dialogs / sheets ----
        viewSettingsConfig?.let { cfg ->
            LibrarySessionConfigDialog(config = cfg, onDismiss = { viewSettingsConfig = null })
        }

        pendingMoveToVaultSessionId?.let { sid ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { pendingMoveToVaultSessionId = null },
                title = { androidx.compose.material3.Text(stringResource(R.string.vault_move_in)) },
                text = { androidx.compose.material3.Text(stringResource(R.string.vault_share_leaves_warning)) },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        pendingMoveToVaultSessionId = null
                        if (sid == "__batch__") {
                            val keys = selection.keys
                            selection = SelectionReducer.clear(selection)
                            coroutineScope.launch {
                                val res = viewModel.batchMoveToVault(keys)
                                snackbarHostState.showSnackbar(
                                    context.getString(R.string.library_vault_batch_result, res.moved, res.skipped),
                                )
                            }
                        } else {
                            coroutineScope.launch { viewModel.moveToVault(sid) }
                        }
                    }) { androidx.compose.material3.Text(stringResource(R.string.vault_move_in)) }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { pendingMoveToVaultSessionId = null }) {
                        androidx.compose.material3.Text(stringResource(R.string.dialog_cancel))
                    }
                },
            )
        }

        pendingDeleteConfirm?.let { keys ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { pendingDeleteConfirm = null },
                title = { androidx.compose.material3.Text(stringResource(R.string.library_delete_confirm_title)) },
                text = { androidx.compose.material3.Text(stringResource(R.string.library_delete_confirm_body, keys.size)) },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        pendingDeleteConfirm = null
                        startDeferredDelete(keys)
                    }) { androidx.compose.material3.Text(stringResource(R.string.library_action_delete)) }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { pendingDeleteConfirm = null }) {
                        androidx.compose.material3.Text(stringResource(R.string.dialog_cancel))
                    }
                },
            )
        }

        renameTarget?.let { row ->
            LibraryRenameDialog(
                currentTitle = row.title,
                titleLabel = stringResource(R.string.library_rename_title),
                fieldHint = stringResource(R.string.library_rename_hint),
                confirmLabel = stringResource(R.string.library_rename_confirm),
                cancelLabel = stringResource(R.string.dialog_cancel),
                onRename = { viewModel.renameSession(row.stableKey, it) },
                onDismiss = { renameTarget = null },
            )
        }

        sheetTarget?.let { row ->
            val item = byKey[row.stableKey]
            LibraryItemSheet(
                isFavorite = row.favorite,
                movable = row.topology != com.aritr.rova.data.CaptureTopology.DualShot && item?.sessionId != null,
                playLabel = stringResource(R.string.library_action_play),
                selectLabel = stringResource(R.string.library_action_select),
                shareLabel = shareLabel,
                favoriteLabel = favoriteLabel,
                unfavoriteLabel = unfavoriteLabel,
                renameLabel = stringResource(R.string.library_action_rename),
                vaultLabel = stringResource(R.string.library_action_vault),
                viewSettingsLabel = stringResource(R.string.library_action_view_settings),
                deleteLabel = stringResource(R.string.library_action_delete),
                onPlay = { sheetTarget = null; play(row.stableKey) },
                onSelect = { sheetTarget = null; selection = SelectionReducer.enter(SelectionState(), row.stableKey) },
                onShare = { sheetTarget = null; item?.let { shareItems(listOf(it)) } },
                onToggleFavorite = { sheetTarget = null; viewModel.toggleFavorite(row.stableKey) },
                onRename = { sheetTarget = null; renameTarget = row },
                onMoveToVault = { sheetTarget = null; pendingMoveToVaultSessionId = item?.sessionId },
                onViewSettings = {
                    sheetTarget = null
                    coroutineScope.launch {
                        val cfg = viewModel.loadSessionConfig(item?.sessionId)
                        if (cfg != null) viewSettingsConfig = cfg
                        else snackbarHostState.showSnackbar(context.getString(R.string.history_settings_unavailable))
                    }
                },
                onDelete = { sheetTarget = null; pendingDeleteConfirm = setOf(row.stableKey) },
                onDismiss = { sheetTarget = null },
            )
        }

        sheetWarningId?.let { id ->
            if (warningVm != null) {
                HistoryWarningSheetHost(
                    id = id,
                    vm = warningVm,
                    pendingCantMergeSessionId = pendingCantMergeSessionId,
                    onKeepRawFromSheet = { recoveryViewModel.keepRaw(it) },
                    onDiscardFromSheet = { recoveryViewModel.dismiss(it) },
                    onDismiss = { sheetWarningId = null },
                )
            }
        }
    }
}

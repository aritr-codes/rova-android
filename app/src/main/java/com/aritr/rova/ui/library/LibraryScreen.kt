package com.aritr.rova.ui.library

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aritr.rova.R
import com.aritr.rova.RovaApp
import com.aritr.rova.service.dualrecord.VideoSide
import com.aritr.rova.ui.components.RovaAlertDialog
import com.aritr.rova.ui.library.components.LibraryBatchBar
import com.aritr.rova.ui.library.components.LibraryDayHeader
import com.aritr.rova.ui.library.components.LibraryDualShotEmpty
import com.aritr.rova.ui.library.components.LibraryEmpty
import com.aritr.rova.ui.library.components.LibraryFavoritesEmpty
import com.aritr.rova.ui.library.components.LibraryFilteredEmpty
import com.aritr.rova.ui.library.components.LibrarySearchEmpty
import com.aritr.rova.ui.library.components.LibraryUsageLine
import com.aritr.rova.ui.library.components.LibraryItemSheet
import com.aritr.rova.ui.library.components.LibraryFilterChips
import com.aritr.rova.ui.library.components.LibraryListRow
import com.aritr.rova.ui.library.components.LibraryLoading
import com.aritr.rova.ui.library.components.LibraryRenameDialog
import com.aritr.rova.ui.library.components.LibraryScrubber
import com.aritr.rova.ui.library.components.LibrarySearchField
import com.aritr.rova.ui.library.components.LibrarySelectionTopBar
import com.aritr.rova.ui.library.components.LibrarySortSheet
import com.aritr.rova.ui.library.components.LibraryTopBar
import com.aritr.rova.ui.recovery.RecoveryCardKind
import com.aritr.rova.ui.recovery.RecoveryCardList
import com.aritr.rova.ui.recovery.RecoveryViewModel
import com.aritr.rova.ui.recovery.VendorGuidanceIntents
import com.aritr.rova.ui.recovery.recoveryViewModelFactory
import com.aritr.rova.ui.theme.LocalGlassEnvironment
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.pluralStringResource
import java.util.Locale
import java.util.TimeZone

/**
 * spec §5 / ADR-0030 amendment (2026-07-02, Direction A) — single-list Library route surface:
 * one `LazyColumn` of session rows, sticky day headers, a restrained in-timeline latest-row accent
 * (NEWEST sort only, no autoplay anywhere — trust rule for background-recorded video) PLUS the
 * Slice 3 management layer: long-press multi-select, glass contextual top bar + bottom batch bar
 * (Share/Vault/Favorite/Delete), Snackbar-UNDO deferred delete, per-item sheet (incl. Rename), and the
 * ported recovery-card header + warning strip. Replaces `HistoryScreen` on the `"history"` route.
 *
 * Deferred-delete (owner + codex): rows pending delete are hidden via [PendingDelete]; the real
 * delete commits only on the Snackbar owner (timeout/swipe), UNDO cancels, screen-dispose abandons
 * (files untouched, rows reappear next load). No manifest writes here (ADR-0030).
 */
@OptIn(ExperimentalFoundationApi::class)
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

    // Slice 4 Discovery — sort/filter from the VM; search/sort-sheet are local UI state; hoisted scroll
    // state drives the date scrubber.
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var sortSheetOpen by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Slice 5 (remediation row 23) — focus restore: remember the row that launched playback so focus
    // returns to it after popBackStack() from the player. Saveable so it survives process death.
    var pendingFocusKey by rememberSaveable { mutableStateOf<String?>(null) }
    val rowFocusRequester = remember { FocusRequester() }

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
    // PR-C midnight fix: relative day labels ("Today"/"Yesterday") go stale when the day flips
    // while the app is backgrounded — the old remember(ui.rows) stamp only refreshed on row
    // changes. Single UN-keyed state instance so the ON_RESUME observer's closure write (below)
    // always hits the live instance (a rows-keyed remember would recreate the state and strand
    // the observer's capture — the LaunchedEffect keeps the rows-refresh behavior instead).
    val nowMillisState = remember { mutableLongStateOf(System.currentTimeMillis()) }
    val nowMillis = nowMillisState.longValue
    LaunchedEffect(ui.rows) { nowMillisState.longValue = System.currentTimeMillis() }

    // Aggregated session rows key on session:<id>, which has no VideoItem — resolve via the
    // PORTRAIT-first side key (LibraryRow.sides) for thumbnail/sheet/share (spec §3.4).
    fun itemFor(row: LibraryRow): com.aritr.rova.ui.screens.VideoItem? =
        byKey[row.stableKey] ?: row.sides.firstOrNull()?.let { byKey[it.stableKey] }

    // ---- effects ----
    LaunchedEffect(Unit) { viewModel.refresh() }
    LaunchedEffect(viewModel) {
        viewModel.retentionNotices.collect { notice ->
            RetentionCleanupNotices.message(notice)?.let { snackbarHostState.showSnackbar(it) }
        }
    }
    LaunchedEffect(ui.rows) {
        // Reconcile against ROW keys, not VideoItem keys: aggregated session rows key on
        // session:<id>, which has no VideoItem (codex plan-review 2026-07-03).
        selection = SelectionReducer.reconcile(selection, ui.rows.map { it.stableKey }.toSet())
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
        portraitWord = stringResource(R.string.library_orientation_portrait),
        landscapeWord = stringResource(R.string.library_orientation_landscape),
        autoStoppedWord = stringResource(R.string.library_badge_auto_stopped),
    )
    val plLabel = stringResource(R.string.library_badge_pl)
    val eyebrow = stringResource(R.string.library_eyebrow_latest)
    val favoriteLabel = stringResource(R.string.library_action_favorite)
    val unfavoriteLabel = stringResource(R.string.library_action_unfavorite)
    val shareLabel = stringResource(R.string.library_action_share)
    val selectedLabel = stringResource(R.string.library_a11y_selected)
    val notSelectedLabel = stringResource(R.string.library_a11y_not_selected)
    val shareNoApp = stringResource(R.string.history_share_no_app)
    val deleteUndoLabel = stringResource(R.string.library_delete_undo)
    val playLabel = stringResource(R.string.library_action_play)
    val portraitWord = stringResource(R.string.library_orientation_portrait)
    val landscapeWord = stringResource(R.string.library_orientation_landscape)
    val playSideTemplate = stringResource(R.string.library_a11y_play_side)
    val sideActionTemplate = stringResource(R.string.library_side_action_label)

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

    fun play(rowKey: String, sideKey: String? = null) {
        // Aggregated session row default = Portrait (sides are PORTRAIT-first; ADR-0030 §3 —
        // the side actions make this default visible).
        val resolved = sideKey ?: rowByKey[rowKey]?.sides?.firstOrNull()?.stableKey ?: rowKey
        val item = byKey[resolved] ?: return
        val sid = item.sessionId
        if (sid != null) {
            pendingFocusKey = rowKey // restore focus here on return (row 23)
            // Hand the already-decoded tile thumbnail to the player so it paints over the black
            // shutter until the first video frame renders (no "block" flash on entry).
            com.aritr.rova.ui.screens.player.PlayerPosterHandoff.set(sid, item.thumbnail)
            onOpenPlayer(sid, item.side)
        } else {
            // Legacy file-only row (no manifest): keep the PreviewActivity path.
            item.file?.let { f ->
                pendingFocusKey = rowKey // restore focus here on return (row 23)
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
        // Fan session rows out to their per-side file keys NOW (confirm time) — the timeout
        // commit runs after the undo window, when the captured rowByKey snapshot may be stale
        // (codex plan-review 2026-07-03). `pending`/selection keep hiding by ROW key.
        val fileKeys = LibrarySessionKeys.expand(keys, rowByKey)
        // Commit any batch whose snackbar is still showing (stock showSnackbar QUEUES rather than
        // replaces — codex): dismissing it resolves the prior job Dismissed → it commits now.
        snackbarHostState.currentSnackbarData?.dismiss()
        pending = PendingDelete(pending.keys + keys)
        selection = SelectionReducer.removeAll(selection, keys)
        pendingJob = coroutineScope.launch {
            val result = snackbarHostState.showSnackbar(
                message = context.getString(R.string.library_deleted_count, keys.size),
                actionLabel = deleteUndoLabel,
                duration = SnackbarDuration.Short,
            )
            when (result) {
                // UNDO → un-hide (rows still in ui.rows reappear); abandon the delete.
                SnackbarResult.ActionPerformed -> pending = pending.restore(keys)
                SnackbarResult.Dismissed -> {
                    // Timeout/swipe = decided commit. NonCancellable so a screen-dispose mid-delete can't
                    // half-delete (the still-showing-snackbar abandon path is the cancel of showSnackbar
                    // ABOVE, which never reaches here). deleteItemsKeyed refreshes items internally.
                    val targets = viewModel.itemsForKeys(fileKeys)
                    val failed = withContext(NonCancellable) { viewModel.deleteItemsKeyed(targets) }
                    // Drop the WHOLE batch from pending: succeeded keys are gone from ui.rows, failed keys
                    // remain there and reappear once un-hidden — no stale-key leak (codex #1).
                    pending = pending.restore(keys)
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
    // Hero is gone (ADR-0030 amendment §2): the full filtered+sorted collection renders in the
    // list; the latest anchor is an in-timeline accent on the first row under NEWEST only.
    val collection = remember(visibleRows, sort, filter) {
        LibraryQuery.collection(visibleRows, sort, filter, heroKey = null)
    }
    val latestKey = remember(collection, sort) { LatestRowPolicy.latestKey(collection, sort) }
    val groups = remember(collection, sort, nowMillis, locale, tz) { LibraryDayGrouping.groupForSort(collection, sort, nowMillis, locale, tz) }
    // Scrubber segments: leading = the recovery/warnings header only (hero slot gone).
    val leadingItemCount = 1
    val scrubberSegments = remember(groups) {
        ScrubberIndex.segments(groups.map { it.label }, groups.map { it.rows.size }, leadingItemCount)
    }
    val dims = remember(ui.density) { LibraryDensityDimens.spec(ui.density) }
    val scrubberRailLabel = stringResource(R.string.library_scrubber_rail_cd)

    // Slice 5 (remediation row 23) — focus restore on return from the player. ON_RESUME also fires on
    // first entry, so guard on pendingFocusKey; clear after every attempt. Await composition via
    // snapshotFlow (codex) before requestFocus so a recycled/off-screen target is actually laid out.
    val currentGroupKeys by rememberUpdatedState(groups.map { g -> g.rows.map { it.stableKey } })
    val currentGroupHeaders by rememberUpdatedState(groups.map { it.label.isNotEmpty() })
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            // PR-C midnight fix — re-stamp so day groups/labels recompute if the day flipped
            // while backgrounded (regroup ≈12ms, keys stable → no scroll jump; PR #164 pattern).
            nowMillisState.longValue = System.currentTimeMillis()
            // Density reseed — pick up a Settings/PR-C density toggle when returning to the
            // kept-composed Library tab (same resume-pickup contract the retired cardPreview used).
            viewModel.refreshDensity()
            val key = pendingFocusKey ?: return@LifecycleEventObserver
            val index = FocusRestorePolicy.targetItemIndex(key, currentGroupKeys, currentGroupHeaders)
            if (index == null) {
                pendingFocusKey = null
                return@LifecycleEventObserver
            }
            coroutineScope.launch {
                // Jitter fix (2026-07-01): only scroll when the opened tile isn't already on screen.
                // The saveable lazy state restores the pre-open position on the pop, so the common
                // return needs no scroll; a redundant scrollToItem jump-scrolls the list and stalls
                // the UI thread. Focus is still restored below in every case (all input modalities).
                val visibleKeys = { listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String } }
                if (FocusRestorePolicy.shouldScroll(key, visibleKeys())) {
                    listState.scrollToItem(index)
                    snapshotFlow { key in visibleKeys() }.first { it }
                }
                withFrameNanos { } // one frame so the conditional focusRequester is attached before we request
                runCatching { rowFocusRequester.requestFocus() }
                pendingFocusKey = null
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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
        // Focus SEPARATION (remediation row 32 / HIST-02): the recovery + warning host is its own
        // traversal group so it does not merge focus/semantics with the sibling library rows.
        Column(modifier = Modifier.semantics { isTraversalGroup = true }) {
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
            // M1 (Theme Foundation) — the screen background is the active theme's own brush, so a tint pack
            // restyles it from one layer (replaces the former inline colorScheme gradient). Owner-ratified.
            .background(LocalGlassEnvironment.current.palette.background),
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
                        onBack = onBack,
                        backLabel = stringResource(R.string.history_back_cd),
                        onOpenVault = onOpenVault,
                        vaultLabel = stringResource(R.string.vault_open_entry_cd),
                        onOpenSearch = { searchActive = !searchActive; if (!searchActive) viewModel.setSearch("") },
                        searchLabel = stringResource(R.string.library_search_open_cd),
                        onOpenSort = { sortSheetOpen = true },
                        sortLabel = stringResource(R.string.library_sort_open_cd),
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
                        onShare = { shareItems(viewModel.itemsForKeys(LibrarySessionKeys.expand(selection.keys, rowByKey))) },
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
                // Body state taxonomy mirrors LibraryStatePolicy (Loading / Empty / SearchEmpty /
                // Content); the inline branch keeps the structural nesting (shared discovery chips +
                // RecoveryAndWarnings) the pure resolver can't express. See LibraryStatePolicy.
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
                    LibraryEmpty(onGoToRecord = onNavigateToRecord)
                }
                else -> Column(Modifier.fillMaxSize().padding(innerPadding)) {
                    // P6 storage/usage footprint — directly under the top bar, above the discovery bar
                    // (shown for both Content and the filtered SearchEmpty body; hidden on Loading/Empty).
                    LibraryUsageLine(ui.usage)
                    // Pinned Discovery controls (search field when active + filter chips).
                    if (searchActive) {
                        LibrarySearchField(
                            value = filter.search,
                            onValueChange = { viewModel.setSearch(it) },
                            onClear = { viewModel.setSearch("") },
                        )
                    }
                    LibraryFilterChips(
                        filter = filter,
                        onAll = { viewModel.clearFilters(); searchActive = false },
                        onToggleFavorites = { viewModel.setFavoritesOnly(!filter.favoritesOnly) },
                        onTogglePl = {
                            viewModel.setTopologyFilter(
                                if (filter.topology == com.aritr.rova.data.CaptureTopology.DualShot) null
                                else com.aritr.rova.data.CaptureTopology.DualShot,
                            )
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    if (collection.isEmpty()) {
                        // Filtered/searched to nothing (rows exist, none match) — discovery bar stays
                        // pinned above so the user can clear/adjust; the body offers Clear filters too.
                        // M2 — pick educational copy per active facet (FilteredEmptyPolicy) instead of
                        // always showing search wording for a filter that carries no search.
                        val onClearFilters: () -> Unit = { viewModel.clearFilters(); searchActive = false }
                        when (
                            FilteredEmptyPolicy.resolve(
                                hasSearch = filter.search.isNotBlank(),
                                favoritesOnly = filter.favoritesOnly,
                                isDualShot = filter.topology == com.aritr.rova.data.CaptureTopology.DualShot,
                            )
                        ) {
                            FilteredEmptyKind.Favorites ->
                                LibraryFavoritesEmpty(onClearFilters, Modifier.fillMaxSize())
                            FilteredEmptyKind.DualShot ->
                                LibraryDualShotEmpty(onClearFilters, Modifier.fillMaxSize())
                            FilteredEmptyKind.Search ->
                                LibrarySearchEmpty(onClearFilters, Modifier.fillMaxSize())
                            FilteredEmptyKind.Generic ->
                                LibraryFilteredEmpty(onClearFilters, Modifier.fillMaxSize())
                        }
                    } else {
                        Box(Modifier.fillMaxSize().weight(1f)) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = 20.dp),
                            ) {
                                item(key = "hdr-recovery-warn") { RecoveryAndWarnings() }
                                groups.forEach { group ->
                                    if (group.label.isNotEmpty()) {
                                        stickyHeader(key = "hdr-${group.dayEpochMillis}") {
                                            LibraryDayHeader(group.label, group.sizeTotalLabel)
                                        }
                                    }
                                    items(group.rows, key = { it.stableKey }) { row ->
                                        val isLatest = row.stableKey == latestKey
                                        val resumeMs = row.resumePositionMs?.takeIf { it > 0 }
                                        LibraryListRow(
                                            row = row,
                                            thumbnail = itemFor(row)?.thumbnail,
                                            tileDescription = TileSemantics.describe(row, frag),
                                            durationFallback = "—",
                                            dualShotLabel = plLabel,
                                            dims = dims,
                                            latest = isLatest,
                                            latestEyebrowText = eyebrow,
                                            latestPillText = if (isLatest) {
                                                resumeMs?.let { stringResource(R.string.library_latest_resume, SmartTitle.durationLabel(it)) } ?: playLabel
                                            } else {
                                                ""
                                            },
                                            latestPillDescription = if (isLatest) {
                                                resumeMs?.let { stringResource(R.string.library_a11y_latest_resume, SmartTitle.durationLabel(it)) }
                                                    ?: stringResource(R.string.library_a11y_latest_play)
                                            } else {
                                                ""
                                            },
                                            portraitWord = portraitWord,
                                            landscapeWord = landscapeWord,
                                            playSideDescriptionTemplate = playSideTemplate,
                                            sideActionLabelTemplate = sideActionTemplate,
                                            onPlaySide = { s ->
                                                if (selection.active) onTileClick(row.stableKey) else play(row.stableKey, s.stableKey)
                                            },
                                            onClick = { onTileClick(row.stableKey) },
                                            modifier = if (row.stableKey == pendingFocusKey) Modifier.focusRequester(rowFocusRequester) else Modifier,
                                            isSelectionMode = selection.active,
                                            isSelected = row.stableKey in selection.keys,
                                            onLongClick = { onTileLong(row.stableKey) },
                                            selectedLabel = selectedLabel,
                                            notSelectedLabel = notSelectedLabel,
                                        )
                                    }
                                }
                            }
                            // Date fast-scroll rail (self-hides when < 2 day groups).
                            LibraryScrubber(
                                segments = scrubberSegments,
                                firstVisibleItemIndex = listState.firstVisibleItemIndex,
                                railLabel = scrubberRailLabel,
                                onScrollToItemIndex = { idx -> coroutineScope.launch { listState.scrollToItem(idx) } },
                                modifier = Modifier.align(Alignment.CenterEnd),
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
            RovaAlertDialog(
                onDismissRequest = { pendingMoveToVaultSessionId = null },
                title = stringResource(R.string.vault_move_in),
                text = stringResource(R.string.vault_share_leaves_warning),
                confirmText = stringResource(R.string.vault_move_in),
                onConfirm = {
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
                },
                dismissText = stringResource(R.string.dialog_cancel),
                onDismiss = { pendingMoveToVaultSessionId = null },
            )
        }

        pendingDeleteConfirm?.let { keys ->
            RovaAlertDialog(
                onDismissRequest = { pendingDeleteConfirm = null },
                title = stringResource(R.string.library_delete_confirm_title),
                text = stringResource(R.string.library_delete_confirm_body, keys.size),
                confirmText = stringResource(R.string.library_action_delete),
                destructive = true,
                onConfirm = {
                    pendingDeleteConfirm = null
                    startDeferredDelete(keys)
                },
                dismissText = stringResource(R.string.dialog_cancel),
                onDismiss = { pendingDeleteConfirm = null },
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
            val item = itemFor(row)
            // P8 — session-identity header: title (WHEN) + meta (clips · duration · size), reusing the
            // same pure SessionCaption the list row renders (no new formatter, no manifest read).
            val sheetClipLabel = if (row.clipCount > 1) {
                pluralStringResource(R.plurals.library_hero_clip_count, row.clipCount, row.clipCount)
            } else {
                ""
            }
            val sheetMeta = SessionCaption.listMeta(
                clipCountLabel = sheetClipLabel,
                durationLabel = if (row.durationMs > 0) SmartTitle.durationLabel(row.durationMs) else "",
                sizeLabel = StorageFormat.size(row.sizeBytes, Locale.getDefault()),
            )
            LibraryItemSheet(
                isFavorite = row.favorite,
                movable = row.topology != com.aritr.rova.data.CaptureTopology.DualShot && item?.sessionId != null,
                headerTitle = row.title,
                headerMeta = sheetMeta,
                headerThumbnail = item?.thumbnail,
                playLabel = stringResource(R.string.library_action_play),
                selectLabel = stringResource(R.string.library_action_select),
                shareLabel = shareLabel,
                favoriteLabel = favoriteLabel,
                unfavoriteLabel = unfavoriteLabel,
                renameLabel = stringResource(R.string.library_action_rename),
                vaultLabel = stringResource(R.string.library_action_vault),
                vaultUnavailableReason = stringResource(R.string.library_action_vault_unavailable_dualshot),
                viewSettingsLabel = stringResource(R.string.library_action_view_settings),
                deleteLabel = stringResource(R.string.library_action_delete),
                onPlay = { sheetTarget = null; play(row.stableKey) },
                onSelect = { sheetTarget = null; selection = SelectionReducer.enter(SelectionState(), row.stableKey) },
                onShare = { sheetTarget = null; shareItems(viewModel.itemsForKeys(LibrarySessionKeys.expand(setOf(row.stableKey), rowByKey))) },
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

        if (sortSheetOpen) {
            LibrarySortSheet(
                current = sort,
                onSelect = { viewModel.setSort(it); sortSheetOpen = false },
                onDismiss = { sortSheetOpen = false },
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

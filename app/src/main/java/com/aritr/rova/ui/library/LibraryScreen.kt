package com.aritr.rova.ui.library

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aritr.rova.R
import com.aritr.rova.RovaApp
import com.aritr.rova.service.dualrecord.VideoSide
import com.aritr.rova.ui.components.RovaAlertDialog
import com.aritr.rova.ui.components.rememberReduceMotion
import com.aritr.rova.ui.library.components.BentoDayHeader
import com.aritr.rova.ui.library.components.BentoTile
import com.aritr.rova.ui.library.components.LibraryDimens
import com.aritr.rova.ui.library.components.LibraryDualShotEmpty
import com.aritr.rova.ui.library.components.LibraryEmpty
import com.aritr.rova.ui.library.components.LibraryFavoritesEmpty
import com.aritr.rova.ui.library.components.LibraryFilteredEmpty
import com.aritr.rova.ui.library.components.LibrarySearchEmpty
import com.aritr.rova.ui.library.components.LibraryItemSheet
import com.aritr.rova.ui.library.components.LibraryFilterChips
import com.aritr.rova.ui.library.components.LibraryLoading
import com.aritr.rova.ui.library.components.LibraryScrubber
import com.aritr.rova.ui.library.components.LibrarySearchField
import com.aritr.rova.ui.library.components.LibrarySelectionTopBar
import com.aritr.rova.ui.library.components.LibraryTopBar
import com.aritr.rova.ui.library.components.VaultDoorRow
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
import com.aritr.rova.ui.vault.VaultViewModel
import com.aritr.rova.ui.warnings.HistoryWarningSheetHost
import com.aritr.rova.ui.warnings.HistoryWarningStrip
import com.aritr.rova.ui.warnings.WarningId
import com.aritr.rova.ui.warnings.WarningScreen
import com.aritr.rova.ui.warnings.buildWarningCenterViewModel
import com.aritr.rova.ui.screens.RetentionCleanupNotices
import com.aritr.rova.data.SessionConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.pluralStringResource
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
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

    // bento Task 8 req 3 — vault-door count. VaultViewModel is the existing read side for
    // vault-visible sessions; HistoryViewModel's own rows are PUBLIC-only (isLibraryVisible) and
    // never see vaulted sessions, so the door needs its own small read.
    val vaultViewModel: VaultViewModel = viewModel()
    val vaultItems by vaultViewModel.items.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vaultViewModel.refresh() }

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    // Selection + deferred-delete state.
    var selection by remember { mutableStateOf(SelectionState()) }
    var pending by remember { mutableStateOf(PendingDelete.NONE) }
    var pendingJob by remember { mutableStateOf<Job?>(null) }

    // bento Task 8 req 8 — boot-only entrance stagger. `bootActive` covers the window every
    // initially-composed item needs to play its stagger delay (index * 30ms) + 500ms animation;
    // after it flips false, item moves (e.g. a delete closing a gap) use Modifier.animateItem()
    // instead of replaying an entrance. Both are skipped under reduced motion (ADR-0020).
    // Final-review #2 — SAVEABLE, so the entrance plays ONCE per screen presence. Nav-Compose
    // disposes this composition when the player is pushed on top; a plain `remember` would reset to
    // true on the pop and replay the whole entrance — and because the stagger delay is the ABSOLUTE
    // built index, a return to a scrolled-down list would hold those tiles at translateY/scale for up
    // to ~1.3s and then settle, exactly the "reposition after a moment" Item 2 forbids. The saveable
    // flag (restored via the retained History NavBackStackEntry) keeps it false across every re-entry.
    val reduceMotion = rememberReduceMotion()
    var bootActive by rememberSaveable { mutableStateOf(true) }
    if (bootActive) {
        LaunchedEffect(Unit) {
            delay(BOOT_STAGGER_WINDOW_MS)
            bootActive = false
        }
    }

    // Final-review #3 — back cancels an active selection / closes the search field BEFORE leaving the
    // screen (standard Android expectation for a management surface). Enabled only while one of them is
    // active, so otherwise system-back falls through to the nav-retention handler (MainScreen) or the
    // default exit — this inner handler is composed deeper than MainScreen's, so it wins when enabled.
    androidx.activity.compose.BackHandler(enabled = selection.active || searchActive) {
        if (selection.active) {
            selection = SelectionReducer.clear(selection)
        } else {
            searchActive = false
            viewModel.setSearch("")
        }
    }

    // Dialog / sheet targets.
    var viewSettingsConfig by remember { mutableStateOf<SessionConfig?>(null) }
    var pendingMoveToVaultSessionId by remember { mutableStateOf<String?>(null) }
    // bento Task 9 — keyed (not row-snapshot) so the sheet reflects live favorite/rename state
    // instead of freezing the row as it stood when the sheet opened.
    var sheetTargetKey by remember { mutableStateOf<String?>(null) }
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
    // bento Task 8 — BentoTile computes its own tile/pane accessibility labels internally
    // (TileSemantics.bentoLabel/bentoPaneLabel read stringResource inside the component), so the
    // per-row Fragments/eyebrow/selected-state/play-side label plumbing LibraryListRow needed is
    // gone; only the strings still consumed by dialogs/sheets/snackbars below remain.
    val favoriteLabel = stringResource(R.string.library_action_favorite)
    val unfavoriteLabel = stringResource(R.string.library_action_unfavorite)
    val shareLabel = stringResource(R.string.library_action_share)
    val shareNoApp = stringResource(R.string.history_share_no_app)
    val deleteUndoLabel = stringResource(R.string.library_delete_undo)
    val todayWord = stringResource(R.string.library_day_today)
    val yesterdayWord = stringResource(R.string.library_day_yesterday)

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
    val isFiltered = filter.favoritesOnly || filter.topology != null || filter.search.isNotBlank()
    // bento Task 8 req 1 — the in-timeline latest accent is a NEWEST-only, UNFILTERED affordance:
    // once the user narrows the view there is no single "latest" row left to anchor on.
    val latestKey = remember(collection, sort, isFiltered) {
        if (isFiltered) null else LatestRowPolicy.latestKey(collection, sort)
    }
    val groups = remember(collection, sort, nowMillis, locale, tz) { LibraryDayGrouping.groupForSort(collection, sort, nowMillis, locale, tz) }
    val groupsByEpoch = remember(groups) { groups.associateBy { it.dayEpochMillis } }
    val collectionByKey = remember(collection) { collection.associateBy { it.stableKey } }
    // Every stableKey a BentoTile might ask for: a row's own key, PLUS every side's own key — an
    // aggregated session row's stableKey ("session:<id>") has no VideoItem, same fallback shape
    // the retired itemFor() used.
    val thumbnailByKey = remember(collection, byKey) {
        val map = HashMap<String, Bitmap?>()
        collection.forEach { row ->
            map[row.stableKey] = itemFor(row)?.thumbnail
            row.sides.forEach { side -> map.putIfAbsent(side.stableKey, byKey[side.stableKey]?.thumbnail) }
        }
        map
    }

    // bento Task 8 req 2 — per-day tile layout, month dividers, and short rail labels; all pure
    // derivations feeding the single BentoListIndex build below.
    val plans = remember(groups, nowMillis, tz) {
        groups.map { g -> BentoRowPlanner.plan(g.rows.map { it.sides.size == 2 }, LibraryDateLabels.dayAge(g.dayEpochMillis, nowMillis, tz)) }
    }
    val dividerLabels = remember(groups, locale, tz) {
        val monthFmt = SimpleDateFormat("MMMM yyyy", locale).apply { timeZone = tz }
        groups.mapIndexed { i, g ->
            if (g.label.isEmpty()) return@mapIndexed null // header-less flat bucket (non-chronological sort)
            val prev = groups.getOrNull(i - 1)
            // Frozen spec (library-bento.html: `prevMonth!==null && mo!==prevMonth`) emits a month divider
            // only BETWEEN groups on a month change — NEVER above the first/newest group (peer-review parity
            // finding, 2026-07-05). No prev ⇒ first group ⇒ suppress.
            if (prev == null) return@mapIndexed null
            val sameMonth = prev.label.isNotEmpty() && isSameMonth(prev.dayEpochMillis, g.dayEpochMillis, tz)
            if (sameMonth) null else monthFmt.format(Date(g.dayEpochMillis)).uppercase(locale)
        }
    }
    val railLabels = remember(groups, nowMillis, locale, tz, todayWord, yesterdayWord) {
        groups.map { g ->
            val header = LibraryDateLabels.headerLabel(g.dayEpochMillis, nowMillis, locale, tz)
            when (header.kind) {
                DayHeaderKind.TODAY -> todayWord
                DayHeaderKind.YESTERDAY -> yesterdayWord
                else -> header.absolute
            }
        }
    }
    // bento Task 8 req 3 — the single source of item order/keys/scrubber/focus lookups, computed
    // in the SAME recomposition pass as the LazyColumn content below (no LaunchedEffect hop — the
    // ground wash and scrubber must never lag a render, ADR-0030 amendment §4).
    val built = remember(groups, plans, dividerLabels, railLabels) {
        BentoListIndex.build(groups, plans, dividerLabels, railLabels)
    }
    val scrubberRailLabel = stringResource(R.string.library_scrubber_rail_cd)

    // bento Task 8 req 4 — ground-wash pinned header: derived synchronously off LazyList layout
    // info so the wash can never lag a render (BentoWashPolicy contract).
    val pinnedEpoch by remember {
        derivedStateOf {
            BentoWashPolicy.pinnedDayEpoch(
                listState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
                    (info.key as? String)?.takeIf { it.startsWith("hdr-") }
                        ?.removePrefix("hdr-")?.toLongOrNull()?.let { it to info.offset }
                },
            )
        }
    }

    // bento Task 8 req 1 — selection prune: drop any selected key the filter/search narrowed
    // away (ADR §3). Separate from the ui.rows-keyed reconcile below, which tracks underlying
    // data changes (deletes/adds), not the active filter/search view.
    LaunchedEffect(built.visibleStableKeys) {
        selection = SelectionReducer.reconcile(selection, built.visibleStableKeys)
    }

    // Focus restore on return from the player (accessibility). ON_RESUME also fires on first entry,
    // so guard on pendingFocusKey; clear after every attempt. Focus-only — the viewport is restored by
    // the saveable LazyListState (Item 2 owner ruling: no auto-scroll).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            // PR-C midnight fix — re-stamp so day groups/labels recompute if the day flipped
            // while backgrounded (regroup ≈12ms, keys stable → LazyColumn keeps position, no scroll).
            nowMillisState.longValue = System.currentTimeMillis()
            val key = pendingFocusKey ?: return@LifecycleEventObserver
            coroutineScope.launch {
                // Item 2 (owner ruling 2026-07-05, release blocker): NEVER auto-scroll or reposition
                // on return from the player. The saveable LazyListState already restores the EXACT
                // pre-open viewport; the previous scrollToItem here fought that restoration and
                // jump-scrolled the list "after a moment", breaking spatial memory. We now only
                // restore FOCUS (accessibility) — and only when the opened tile is already within the
                // restored viewport (it was on-screen when tapped, so the restored viewport shows it),
                // so requestFocus never triggers an implicit scroll. Viewport is never disturbed.
                withFrameNanos { }
                val visible = listState.layoutInfo.visibleItemsInfo.any { it.key == key }
                if (visible) {
                    runCatching { rowFocusRequester.requestFocus() }
                }
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

    // bento Task 9 — long-press now ENTERS selection mode directly (frozen selbar entry point,
    // docs/design/library-bento.html "long-press → selection"): the details sheet no longer opens
    // from a bare long-press — its only entry is the selection top bar's info action (exactly one
    // selected). BentoTile owns tap routing itself (selecting ? onToggleSelect() : onPlay(...)).
    fun onTileLong(key: String) {
        selection = if (selection.active) SelectionReducer.toggle(selection, key) else SelectionReducer.enter(selection, key)
    }

    // Final-review #5 — key on what the lambda actually reads (selection + ui.rows), not byKey.
    val movableSelectedExists = remember(selection, ui.rows) {
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
                        infoLabel = stringResource(R.string.library_action_info_cd),
                        favoriteLabel = favoriteLabel,
                        vaultLabel = stringResource(R.string.library_action_vault),
                        vaultDisabledLabel = stringResource(R.string.library_action_vault_disabled),
                        deleteLabel = stringResource(R.string.library_action_delete),
                        infoEnabled = selection.count == 1,
                        vaultEnabled = movableSelectedExists,
                        onClose = { selection = SelectionReducer.clear(selection) },
                        onSelectAll = {
                            selection = SelectionReducer.selectAll(selection, visibleRows.map { it.stableKey })
                        },
                        onInfo = { selection.keys.singleOrNull()?.let { sheetTargetKey = it } },
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
                        onVault = { pendingMoveToVaultSessionId = "__batch__" },
                        onDelete = { pendingDeleteConfirm = selection.keys },
                    )
                } else {
                    LibraryTopBar(
                        title = stringResource(R.string.history_title),
                        onBack = onBack,
                        backLabel = stringResource(R.string.history_back_cd),
                        onOpenSearch = { searchActive = !searchActive; if (!searchActive) viewModel.setSearch("") },
                        searchLabel = stringResource(R.string.library_search_open_cd),
                        // bento Task 7 — top-bar select entry: same "activate selection mode" outcome
                        // as the per-item sheet's existing Select action / long-press, just with no
                        // row pre-selected (SelectionReducer.enter always seeds a key).
                        onOpenSelect = { selection = SelectionState(active = true) },
                        selectLabel = stringResource(R.string.library_select_open_cd),
                    )
                }
            },
            // bento Task 9 — the bottom LibraryBatchBar is retired: every batch action it carried
            // (favorite/vault/delete) now lives in the top LibrarySelectionTopBar above; batch Share
            // was dropped from the frozen design (share is sheet-only, ADR-0030 §1 "Share not Export").
        ) { innerPadding ->
            when {
                // Body state taxonomy mirrors LibraryStatePolicy (Loading / Empty / SearchEmpty /
                // Content); the inline branch keeps the structural nesting (shared discovery chips +
                // RecoveryAndWarnings) the pure resolver can't express. See LibraryStatePolicy.
                // Item 1 (owner ruling 2026-07-05) — the skeleton is a placeholder of LAST RESORT.
                // Once any rows have ever loaded, the retained VM/StateFlow keeps serving them, so a
                // re-entry renders the last-known library instantly and refresh() updates it async
                // (loadItemsList never clears _items or resets _hasLoaded mid-refresh). The skeleton
                // shows ONLY when there is genuinely nothing renderable yet (true cold first load).
                !ui.hasLoaded && ui.rows.isEmpty() -> LibraryLoading(Modifier.fillMaxSize().padding(innerPadding))
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
                    // Final-review F1 — the vault door was the ONLY route to the vault once the
                    // top-bar icon was retired (bento Task 7); a fully-empty library must still
                    // offer it when vaulted sessions exist.
                    if (vaultItems.isNotEmpty()) {
                        VaultDoorRow(count = vaultItems.size, onClick = onOpenVault, modifier = Modifier.fillMaxWidth())
                    }
                    LibraryEmpty(onGoToRecord = onNavigateToRecord)
                }
                else -> Column(Modifier.fillMaxSize().padding(innerPadding)) {
                    // F7 (owner ruling 2026-07-05) — the storage/usage footprint is no longer pinned;
                    // per ADR-0030 §7 + frozen spec §1 the stats line scrolls away with content, so it
                    // lives in the LazyColumn "stats" item below reading "{n} recordings · {size}".
                    // Final-review F2 — single mount point: the search field renders here, above the
                    // collection.isEmpty() split, so it stays mounted across the filtered-empty <->
                    // LazyColumn swap below (was previously duplicated into both branches, which
                    // unmounted the focused TextField and dropped the keyboard mid-keystroke at the
                    // zero-match boundary). Filter chips (unaffected by that split) stay in their own
                    // LazyColumn item further down.
                    if (searchActive) {
                        LibrarySearchField(
                            value = filter.search,
                            onValueChange = { viewModel.setSearch(it) },
                            onClear = { viewModel.setSearch("") },
                        )
                    }
                    // bento Task 8 review fix — filter chips render ONLY as LazyColumn item[2] (below),
                    // including when the collection is filtered to nothing (the filtered-empty views
                    // below carry their own "Clear filters" CTA, so the user can always escape a filter
                    // without a second pinned chips row).
                    val discoveryChips: @Composable () -> Unit = {
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
                    }
                    if (collection.isEmpty()) {
                        // Filtered/searched to nothing (rows exist, none match) — the body offers
                        // Clear filters itself (below); no second pinned chips row (review fix 3).
                        // M2 — pick educational copy per active facet (FilteredEmptyPolicy) instead of
                        // always showing search wording for a filter that carries no search.
                        val onClearFilters: () -> Unit = { viewModel.clearFilters(); searchActive = false }
                        Column(Modifier.fillMaxSize()) {
                            // Final-review F1 — same vault-door escape hatch as the true-empty branch.
                            if (vaultItems.isNotEmpty()) {
                                VaultDoorRow(count = vaultItems.size, onClick = onOpenVault, modifier = Modifier.fillMaxWidth())
                            }
                            Box(Modifier.fillMaxSize().weight(1f)) {
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
                            }
                        }
                    } else {
                        Box(Modifier.fillMaxSize().weight(1f)) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = 20.dp),
                            ) {
                                // ---- leading chrome (BentoListIndex.LEADING_ITEM_COUNT = 4) ----
                                item(key = "hdr-recovery-warn") {
                                    val mod = bentoItemMotion(reduceMotion, bootActive, 0)
                                    Box(mod) { RecoveryAndWarnings() }
                                }
                                item(key = "stats") {
                                    val mod = bentoItemMotion(reduceMotion, bootActive, 1)
                                    // Frozen spec §1: "{n} recordings · {size}" at rest; "{x} of {y}
                                    // recordings" under filter/search. 11.5sp textDim, scrolls with content.
                                    val statsText = if (isFiltered) {
                                        stringResource(R.string.library_stats_filtered, collection.size, visibleRows.size)
                                    } else {
                                        pluralStringResource(
                                            R.plurals.library_stats_resting,
                                            ui.usage.sessionCount,
                                            ui.usage.sessionCount,
                                            StorageFormat.size(ui.usage.totalBytes, Locale.getDefault()),
                                        )
                                    }
                                    Text(
                                        statsText,
                                        modifier = mod.padding(horizontal = LibraryDimens.screenPadH, vertical = 4.dp),
                                        color = LocalGlassEnvironment.current.palette.textDim,
                                        fontSize = 11.5.sp,
                                    )
                                }
                                item(key = "chips") {
                                    val mod = bentoItemMotion(reduceMotion, bootActive, 2)
                                    Column(mod) { discoveryChips() }
                                }
                                item(key = "vault-door") {
                                    val mod = bentoItemMotion(reduceMotion, bootActive, 3)
                                    VaultDoorRow(count = vaultItems.size, onClick = onOpenVault, modifier = mod)
                                }

                                // ---- day timeline (BentoListIndex.build) ----
                                built.entries.forEachIndexed { i, entry ->
                                    val key = built.keyForEntry[i]
                                    val staggerIdx = BentoListIndex.LEADING_ITEM_COUNT + i
                                    when (entry) {
                                        is BentoListIndex.Entry.MonthDivider -> item(key = key) {
                                            val mod = bentoItemMotion(reduceMotion, bootActive, staggerIdx)
                                            // Final-review F6 — frozen spec §1 "Grid": 10.5sp / 700 / 0.22em
                                            // tracking / textDim, centered, 22dp top / 12dp bottom (was an
                                            // undocumented 11sp/left/10dp-vertical approximation).
                                            Text(
                                                entry.label,
                                                modifier = mod
                                                    .fillMaxWidth()
                                                    .padding(horizontal = LibraryDimens.screenPadH)
                                                    .padding(top = 22.dp, bottom = 12.dp),
                                                textAlign = TextAlign.Center,
                                                color = LocalGlassEnvironment.current.palette.textDim,
                                                fontSize = 10.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                letterSpacing = 0.22.em,
                                            )
                                        }
                                        is BentoListIndex.Entry.Header -> stickyHeader(key = key) {
                                            val g = groupsByEpoch[entry.dayEpochMillis]
                                            val groupKeys = g?.rows?.map { it.stableKey } ?: emptyList()
                                            val totalDurationLabel = SmartTitle.durationLabel(g?.rows?.sumOf { it.durationMs } ?: 0L)
                                            BentoDayHeader(
                                                dayEpochMillis = entry.dayEpochMillis,
                                                nowMillis = nowMillis,
                                                recordingCount = groupKeys.size,
                                                totalDurationLabel = totalDurationLabel,
                                                pinned = entry.dayEpochMillis == pinnedEpoch,
                                                selecting = selection.active,
                                                allSelected = groupKeys.isNotEmpty() && selection.keys.containsAll(groupKeys),
                                                onSelectDay = { selection = SelectionReducer.selectAll(selection, groupKeys) },
                                            )
                                        }
                                        is BentoListIndex.Entry.BentoRow -> item(key = key) {
                                            val mod = bentoItemMotion(reduceMotion, bootActive, staggerIdx)
                                            Row(
                                                mod
                                                    .padding(horizontal = 16.dp)
                                                    .height(entry.pattern.heightDp.dp),
                                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            ) {
                                                entry.pattern.spans.forEachIndexed { paneIdx, span ->
                                                    val memberKey = entry.memberKeys.getOrNull(paneIdx)
                                                    val row = memberKey?.let { collectionByKey[it] }
                                                    if (row != null) {
                                                        // bento Task 8 req 5 — BentoTile's onPlay callback forwards the
                                                        // tapped VideoSide's `.name` (not a stableKey) for a diptych pane;
                                                        // resolve it back to that side's own stableKey here (index 0 =
                                                        // Portrait = LEFT, per BentoTile's own portrait-first ordering).
                                                        BentoTile(
                                                            row = row,
                                                            heightDp = entry.pattern.heightDp,
                                                            span = span,
                                                            isLatest = row.stableKey == latestKey,
                                                            selecting = selection.active,
                                                            selected = row.stableKey in selection.keys,
                                                            onPlay = { sideToken ->
                                                                val sideKey = sideToken?.let { token ->
                                                                    row.sides.firstOrNull { it.side.name == token }?.stableKey
                                                                }
                                                                play(row.stableKey, sideKey)
                                                            },
                                                            onToggleSelect = { onTileLong(row.stableKey) },
                                                            onEnterSelection = {},
                                                            thumbnailFor = { k -> thumbnailByKey[k] },
                                                            modifier = (
                                                                if (row.stableKey == pendingFocusKey) {
                                                                    Modifier.focusRequester(rowFocusRequester)
                                                                } else {
                                                                    Modifier
                                                                }
                                                            ).weight(span.toFloat()),
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                item(key = "endcap") {
                                    val mod = bentoItemMotion(reduceMotion, bootActive, BentoListIndex.LEADING_ITEM_COUNT + built.entries.size)
                                    Text(
                                        stringResource(R.string.library_endcap, collection.size),
                                        modifier = mod
                                            .fillMaxWidth()
                                            .padding(horizontal = 26.dp, vertical = 44.dp),
                                        textAlign = TextAlign.Center,
                                        color = LocalGlassEnvironment.current.palette.textFaint,
                                        fontSize = 10.5.sp,
                                        letterSpacing = 0.08.em,
                                    )
                                }
                            }
                            // Date fast-scroll rail (self-hides when < 2 day groups).
                            LibraryScrubber(
                                segments = built.scrubberSegments,
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
                            // bento Task 8 review fix 2 — the vault door count reads vaultViewModel's own
                            // list, which only refreshed once on screen entry; re-read now that the move
                            // (both suspend calls return only after their move completes) landed.
                            vaultViewModel.refresh()
                            snackbarHostState.showSnackbar(
                                context.getString(R.string.library_vault_batch_result, res.moved, res.skipped),
                            )
                        }
                    } else {
                        coroutineScope.launch {
                            viewModel.moveToVault(sid)
                            vaultViewModel.refresh()
                        }
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

        // bento Task 9 — keyed lookup (not a frozen row snapshot) so favorite/rename reflect live
        // as soon as the VM's row list updates, while the sheet stays open (frozen behavior).
        sheetTargetKey?.let(rowByKey::get)?.let { row ->
            val item = itemFor(row)
            val isDualForSheet = row.sides.size == 2
            val portraitThumb = row.sides.getOrNull(0)?.let { thumbnailByKey[it.stableKey] }
            val landscapeThumb = row.sides.getOrNull(1)?.let { thumbnailByKey[it.stableKey] }
            val longDateFmt = remember(locale, tz) { SimpleDateFormat("MMMM d, yyyy", locale).apply { timeZone = tz } }
            val timeFmt = remember(locale, tz) { SimpleDateFormat("h:mm a", locale).apply { timeZone = tz } }
            val clipsLabel = pluralStringResource(R.plurals.library_hero_clip_count, row.clipCount, row.clipCount)
            val orientationWord = when {
                isDualForSheet -> stringResource(R.string.library_filter_pl)
                row.orientation == LibraryOrientation.PORTRAIT -> stringResource(R.string.library_orientation_portrait)
                row.orientation == LibraryOrientation.LANDSCAPE -> stringResource(R.string.library_orientation_landscape)
                else -> ""
            }
            val factsLine1 = stringResource(
                R.string.library_facts_line1,
                longDateFmt.format(Date(row.dateMillis)),
                timeFmt.format(Date(row.dateMillis)),
                timeFmt.format(Date(row.dateMillis + row.durationMs)),
            )
            // Final-review F9 — join only non-blank segments; an unknown orientation left
            // orientationWord="" and the fixed-arity string template dangled a trailing " · ".
            val factsLine2 = listOf(
                SmartTitle.durationLabel(row.durationMs),
                clipsLabel,
                StorageFormat.size(row.sizeBytes, Locale.getDefault()),
                orientationWord,
            ).filter { it.isNotBlank() }.joinToString(" · ")
            LibraryItemSheet(
                isFavorite = row.favorite,
                movable = row.topology != com.aritr.rova.data.CaptureTopology.DualShot && item?.sessionId != null,
                isDualShot = isDualForSheet,
                title = row.title,
                factsLine1 = factsLine1,
                factsLine2 = factsLine2,
                heroThumbnail = item?.thumbnail,
                portraitThumbnail = portraitThumb,
                landscapeThumbnail = landscapeThumb,
                durationPillLabel = SmartTitle.durationLabel(row.durationMs),
                playLabel = stringResource(R.string.library_action_play),
                portraitLabel = stringResource(R.string.library_orientation_portrait),
                landscapeLabel = stringResource(R.string.library_orientation_landscape),
                closeLabel = stringResource(R.string.library_sheet_close_cd),
                renameLabel = stringResource(R.string.library_action_rename),
                renameFieldHint = stringResource(R.string.library_rename_hint),
                favoriteLabel = favoriteLabel,
                unfavoriteLabel = unfavoriteLabel,
                vaultLabel = stringResource(R.string.library_action_vault),
                vaultUnavailableReason = stringResource(R.string.library_action_vault_unavailable_dualshot),
                shareLabel = shareLabel,
                deleteLabel = stringResource(R.string.library_action_delete),
                onPlay = { sideToken ->
                    sheetTargetKey = null
                    val sideKey = sideToken?.let { token -> row.sides.firstOrNull { it.side.name == token }?.stableKey }
                    play(row.stableKey, sideKey)
                },
                onRename = { newTitle -> viewModel.renameSession(row.stableKey, newTitle) },
                onToggleFavorite = { viewModel.toggleFavorite(row.stableKey) },
                onShare = {
                    sheetTargetKey = null
                    shareItems(viewModel.itemsForKeys(LibrarySessionKeys.expand(setOf(row.stableKey), rowByKey)))
                },
                onMoveToVault = {
                    // frozen v3 codex Critical — drop from selection BEFORE closing.
                    selection = SelectionReducer.removeAll(selection, setOf(row.stableKey))
                    sheetTargetKey = null
                    pendingMoveToVaultSessionId = item?.sessionId
                },
                onDelete = {
                    selection = SelectionReducer.removeAll(selection, setOf(row.stableKey))
                    sheetTargetKey = null
                    pendingDeleteConfirm = setOf(row.stableKey)
                },
                onDismiss = { sheetTargetKey = null },
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

/** True when [a] and [b] fall in the same calendar month+year in [tz] (bento Task 8 req 2 — month dividers). */
private fun isSameMonth(a: Long, b: Long, tz: TimeZone): Boolean {
    val ca = Calendar.getInstance(tz).apply { timeInMillis = a }
    val cb = Calendar.getInstance(tz).apply { timeInMillis = b }
    return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) && ca.get(Calendar.MONTH) == cb.get(Calendar.MONTH)
}

/** How long a boot-time stagger step waits before the next item starts (bento Task 8 req 8). */
private const val BOOT_STAGGER_STEP_MS = 30L

/**
 * Frozen spec caps the stagger index at 14 (`--i:${Math.min(idx,14)}`, library-bento.html) so no tile
 * waits more than 14·30ms = 420ms: without the cap a tile at absolute index N (e.g. one scrolled into
 * view during the 3s boot window on a long list) would hold at translateY/scale for N·30ms then settle
 * late (peer-review parity finding). Bounds the delay to the spec's ceiling. (2026-07-05 re-review.)
 */
private const val BOOT_STAGGER_MAX_INDEX = 14

/** Per-item entrance animation duration. */
private const val BOOT_STAGGER_DURATION_MS = 500

/** Frozen entrance easing (spec 2026-07-04). */
private val BOOT_STAGGER_EASING = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

/** Entrance rise distance. */
private val BOOT_STAGGER_TRANSLATE_Y = 14.dp

/**
 * ponytail: a fixed cap rather than computing an exact "last item finishes" bound — generous enough
 * to cover every item likely visible at first paint (~80 items of 30ms stagger + the 500ms anim);
 * items scrolled into view after this window just use [androidx.compose.foundation.lazy.LazyItemScope.animateItem].
 */
private const val BOOT_STAGGER_WINDOW_MS = 3000L

/**
 * bento Task 8 review fix 1 — single branch point for the per-item motion modifier so the
 * reduce-motion / boot-stagger / animateItem choice lives in ONE place instead of being repeated
 * at every `item { }` call site. Under reduced motion NO animation modifier applies at all (not
 * even [androidx.compose.foundation.lazy.LazyItemScope.animateItem]'s placement animation) — ADR-0020.
 */
@Composable
private fun LazyItemScope.bentoItemMotion(reduceMotion: Boolean, bootActive: Boolean, index: Int): Modifier =
    when {
        reduceMotion -> Modifier
        bootActive -> Modifier.bentoStaggerEntrance(index)
        else -> Modifier.animateItem()
    }

/**
 * bento Task 8 req 8 — boot-only entrance (translateY 14dp + scale .97 -> rest, 500ms, frozen easing),
 * staggered 30ms per [index]. Callers apply this ONLY while `bootActive && !reduceMotion`; once boot
 * ends (or under reduced motion) they use `Modifier.animateItem()` instead — this function never
 * calls `animateItem()` itself since that requires a `LazyItemScope` receiver only available at the
 * `item { }` call site.
 */
@Composable
private fun Modifier.bentoStaggerEntrance(index: Int): Modifier {
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(minOf(index, BOOT_STAGGER_MAX_INDEX) * BOOT_STAGGER_STEP_MS)
        revealed = true
    }
    val progress by animateFloatAsState(
        targetValue = if (revealed) 1f else 0f,
        animationSpec = tween(durationMillis = BOOT_STAGGER_DURATION_MS, easing = BOOT_STAGGER_EASING),
        label = "bentoEntrance",
    )
    return this.graphicsLayer {
        translationY = (1f - progress) * BOOT_STAGGER_TRANSLATE_Y.toPx()
        val scale = 0.97f + 0.03f * progress
        scaleX = scale
        scaleY = scale
    }
}

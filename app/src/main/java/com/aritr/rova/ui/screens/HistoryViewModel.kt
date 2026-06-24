package com.aritr.rova.ui.screens

import android.app.Application
import android.content.ContentResolver
import android.content.ContentUris
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aritr.rova.RovaApp
import com.aritr.rova.data.CaptureTopology
import com.aritr.rova.data.ExportTier
import com.aritr.rova.data.RovaSettings
import com.aritr.rova.data.SessionConfig
import com.aritr.rova.data.SessionManifest
import com.aritr.rova.data.SessionStore
import com.aritr.rova.service.dualrecord.VideoSide
import com.aritr.rova.service.export.VaultMoverBuilder
import com.aritr.rova.ui.library.LibraryFilter
import com.aritr.rova.ui.library.LibraryMetadataEntry
import com.aritr.rova.ui.library.LibraryRow
import com.aritr.rova.ui.library.PruneKeepSet
import com.aritr.rova.ui.library.RecordingIdentity
import com.aritr.rova.ui.library.LibraryRowMapper
import com.aritr.rova.ui.library.LibrarySort
import com.aritr.rova.ui.library.LibraryUiState
import com.aritr.rova.ui.library.LibraryViewMode
import com.aritr.rova.ui.library.UsageAggregator
import com.aritr.rova.ui.library.ThumbnailCacheKey
import com.aritr.rova.ui.library.ThumbnailDiskCache
import com.aritr.rova.utils.RovaLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.TimeZone

data class VideoItem(
    /**
     * On-disk artifact file. Non-null for every file-backed row
     * (Tier 1 MediaStore `_DATA`-resolved, Tier 2/3 public path, P+L
     * per-side, per-segment, and legacy file-system rows). **Null only
     * for SAF content-URI rows** (ADR-0024 / B4c): a SAF artifact is a
     * `content://` document with no `java.io.File` — its identity is
     * [docUri]. Every `item.file` deref in the History UI MUST be
     * null-guarded; use [stableKey] for identity, [modifiedAtMillis] /
     * [sizeBytes] / [displayName] for the metadata a SAF row cannot get
     * from a File.
     */
    val file: File?,
    /**
     * B4c (ADR-0024) — SAF content-document URI for rows exported to a
     * user-chosen Storage Access Framework folder. Non-null **only** for
     * SAF rows; null for every File-backed row. When non-null, [file] is
     * null and [shareUri] equals this URI. The delete path validates and
     * removes via `DocumentFile.fromSingleUri(...).delete()`; metadata
     * (thumbnail/resolution) is extracted via
     * `MediaMetadataRetriever.setDataSource(context, docUri)`.
     */
    val docUri: Uri? = null,
    val thumbnail: Bitmap?,
    val resolution: String,
    /**
     * Canonical share URI for this artifact. Non-null for files surfaced
     * via `MediaStore` (Tier 1: from `manifest.pendingUri`; Tier 2/3:
     * looked up by `_DATA` path). Null for legacy app-private files
     * still carried by [legacyFileSystemScan] — those share via
     * [androidx.core.content.FileProvider] using the
     * `external-files-path` root declared in `res/xml/file_paths.xml`.
     *
     * The History share button MUST prefer this URI when present and
     * MUST guard the FileProvider fallback against
     * `IllegalArgumentException` (see [HistoryScreen]).
     */
    val shareUri: Uri? = null,
    /**
     * Session id this artifact belongs to. Non-null for finalized
     * manifest-backed recordings (every Phase 1.7+ export). Null for
     * legacy file-only entries that the fallback file-system scan
     * still surfaces for upgrade continuity.
     *
     * The History delete path uses this to call
     * [com.aritr.rova.data.SessionStore.discardSession] AFTER a
     * successful artifact delete, so the per-session manifest +
     * `videos/<sessionId>/` directory do not linger as invisible
     * disk waste once the gallery row is gone.
     */
    val sessionId: String? = null,
    /**
     * Phase 6.1b smoke-fix #3 — which side of a P+L session this row
     * represents. Non-null for P+L rows (one card per side, populated
     * from [com.aritr.rova.ui.screens.HistoryArtifactMapper.PerSideArtifact.side]);
     * null for single-mode rows and for legacy file-only entries.
     *
     * Threaded through the History card click → MainScreen nav arg →
     * PlayerViewModel → PlayerUriResolver so the player picks the
     * correct per-side `pendingUri` / `publicTargetPath` from the
     * manifest. Without this field both cards for a P+L session would
     * collide on the same shared (null) pointers and surface
     * Unavailable.
     */
    val side: VideoSide? = null,
    /**
     * Milestone 2 — non-null for rows derived from a `MULTI_SEGMENT_KEPT`
     * session's per-segment fanout. Identifies which segment of the session
     * this row represents. Null for all other rows (single-mode, P+L, legacy).
     *
     * Delete handler uses this to remove the per-segment file
     * (`sessionDir/segment_$segmentIndex.mp4`) and update the manifest's
     * segments list.
     */
    val segmentIndex: Int? = null,
    /**
     * B4c (ADR-0024) — last-modified wall-clock millis for SAF rows,
     * where [file] is null and `File.lastModified()` is unavailable.
     * Sourced from `DocumentFile.lastModified()` (falling back to the
     * manifest's `terminatedAt`/`startedAt`) so SAF rows sort and group
     * by date alongside file rows instead of clustering at epoch. Null
     * for file rows — those read [file]`.lastModified()` directly.
     */
    val modifiedAtMillis: Long? = null,
    /**
     * B4c (ADR-0024) — artifact size in bytes for SAF rows (from
     * `DocumentFile.length()`). Null for file rows, which read
     * [file]`.length()`. Drives the per-row size caption + library
     * total-size summary without a File deref.
     */
    val sizeBytes: Long? = null,
    /**
     * B4c (ADR-0024) — display name for SAF rows (from
     * `DocumentFile.name`). Null for file rows, which read [file]`.name`.
     */
    val displayName: String? = null,
) {
    /**
     * B4c — stable per-row identity that tolerates a null [file]. File
     * rows key on the absolute path (byte-identical to the prior
     * `file.absolutePath` key); SAF rows key on the content-URI string.
     * Used for LazyColumn keys, multi-select membership, and the
     * metadata cache so a null File never produces a `"null"` collision.
     */
    val stableKey: String
        get() = file?.absolutePath ?: docUri?.toString() ?: "session:$sessionId"

    /** Effective last-modified millis: [file]`.lastModified()` for file rows, [modifiedAtMillis] for SAF rows. */
    fun effectiveLastModified(): Long = file?.lastModified() ?: modifiedAtMillis ?: 0L

    /** Effective size in bytes: [file]`.length()` for file rows, [sizeBytes] for SAF rows. */
    fun effectiveSize(): Long = file?.length() ?: sizeBytes ?: 0L

    /** Effective display name: [file]`.name` for file rows, [displayName] for SAF rows. */
    fun effectiveName(): String = file?.name ?: displayName ?: stableKey
}

class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val _items = MutableStateFlow<List<VideoItem>>(emptyList())
    val items: StateFlow<List<VideoItem>> = _items.asStateFlow()

    /**
     * Nav-retention fix — distinguishes "first query has not returned yet"
     * from "queried and genuinely empty". [items] starts as `emptyList()`
     * and [refresh] is asynchronous, so without this the History screen
     * would paint the "No Recordings Yet" empty CTA for the frames between
     * first composition and the first load completing — a visible flash on
     * a cold Library open. The screen shows a loading placeholder while this
     * is false and only surfaces the empty CTA once it flips true on an
     * empty result. Flipped on the first emit (partial first-paint or final)
     * and stays true for the VM's lifetime; because the VM is now retained
     * across nav (saveState/restoreState), a returning screen reads it as
     * already-true and never re-flashes.
     */
    private val _hasLoaded = MutableStateFlow(false)
    val hasLoaded: StateFlow<Boolean> = _hasLoaded.asStateFlow()

    /**
     * Slice 13B — one-shot retention cleanup notices. Replay = 0 so a
     * screen recreated after the emit does not surface a stale
     * snackbar; `extraBufferCapacity = 1` + `DROP_OLDEST` collapses
     * rapid successive cleanups to a single visible notice. Emitted
     * ONLY when the cleanup actually deleted or failed something —
     * [RecordingRetentionCleaner.Result.NoOp] is dropped here.
     */
    private val _retentionNotices = MutableSharedFlow<RetentionCleanupNotice>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val retentionNotices: SharedFlow<RetentionCleanupNotice> = _retentionNotices.asSharedFlow()

    // Cache thumbnails and resolution to avoid re-extracting on every refresh()
    private val metadataCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Bitmap?, String>>()

    /**
     * Per-row session facts captured during the load pass (ADR-0030, Slice 2) so
     * the Library row model (duration / topology / badge / startedAt) is derived
     * without a second manifest walk. Keyed by [VideoItem.stableKey]. Legacy
     * file-scan rows have no manifest → absent → mapped with neutral defaults
     * (no badge, no duration). Assigned BEFORE [_items] emits in [loadItemsList],
     * so within a single load pass the rows the UI sees have matching facts.
     *
     * Eventual-consistency note: this field and [_items] are separate values, so a
     * recompute triggered by another input (e.g. [_sidecarRevision]) between a
     * facts assignment and the next [_items] emit — or two overlapping refreshes —
     * can briefly map a stale row against newer facts. Because facts key on
     * `stableKey`, a mismatch only yields neutral defaults (a missing duration/badge
     * for one frame), never wrong data, and self-heals on the following [_items]
     * emit. This is acceptable for a derived display flow; do NOT treat it as an
     * atomic rows+facts snapshot.
     */
    private data class RowManifestFacts(
        val startedAt: Long,
        val segmentDurationsMs: List<Long>,
        val topologyPersisted: String,
        val terminated: com.aritr.rova.data.Terminated?,
        val stopReason: com.aritr.rova.data.StopReason,
        val exportState: com.aritr.rova.data.ExportState,
    )

    @Volatile private var manifestFactsByKey: Map<String, RowManifestFacts> = emptyMap()

    /**
     * spec §7 — disk thumbnail cache so cold launches skip MediaMetadataRetriever.
     * Keyed by [ThumbnailCacheKey] (stableKey + size/mtime/duration invalidators);
     * stores the WebP-encoded keyframe. 32 MB LRU under `cacheDir/thumbnails`.
     */
    private val thumbDiskCache by lazy {
        ThumbnailDiskCache(
            File(getApplication<Application>().cacheDir, "thumbnails"),
            maxBytes = 32L * 1024 * 1024,
        )
    }

    /** Slice 4.1 — RovaSettings seam for persisting the Library view mode across launches. */
    private val settings = RovaSettings(getApplication())

    /**
     * Library grid/list toggle (decision A). Drives [libraryUiState]. Seeded from the persisted
     * [RovaSettings.libraryViewMode] (Slice 4.1, fixes "resets to Grid every launch") and
     * re-persisted in [setViewMode]; unknown/missing coerces to GRID.
     */
    private val _viewMode = MutableStateFlow(
        runCatching { LibraryViewMode.valueOf(settings.libraryViewMode) }.getOrDefault(LibraryViewMode.GRID),
    )

    /**
     * Polish P7 — mirrors [RovaSettings.libraryCardPreview] (default OFF) into [libraryUiState] so the
     * screen can gate card autoplay. [refreshCardPreview] re-reads the pref on resume because the
     * bottom-nav keeps [com.aritr.rova.ui.library.LibraryScreen] composed across tab switches, so a
     * Settings toggle would otherwise not be picked up until process recreation.
     */
    private val _cardPreview = MutableStateFlow(settings.libraryCardPreview)

    fun refreshCardPreview() {
        _cardPreview.value = settings.libraryCardPreview
    }

    /**
     * Bumped after a SUCCESSFUL sidecar write so the derived rows recompute.
     * Single-writer contract: [toggleFavorite] is the only production writer today.
     * Any FUTURE sidecar mutation (rename / lastPlayedAt / prune) MUST also bump
     * this, or `libraryUiState` will serve a stale snapshot.
     */
    private val _sidecarRevision = MutableStateFlow(0)

    /**
     * Slice 4 (spec §5.4) — Discovery sort + filter/search state. Thin reactive
     * holders; the pure [LibraryQuery] does the work and the Screen reads these into
     * its query call. `_filter.search` carries the live search query (folded into the
     * one filter object so the query call takes a single facet bundle).
     */
    private val _sort = MutableStateFlow(LibrarySort.NEWEST)
    val sort: StateFlow<LibrarySort> = _sort.asStateFlow()

    private val _filter = MutableStateFlow(LibraryFilter())
    val filter: StateFlow<LibraryFilter> = _filter.asStateFlow()

    fun setSort(value: LibrarySort) { _sort.value = value }
    fun setSearch(query: String) { _filter.update { it.copy(search = query) } }
    fun setFavoritesOnly(only: Boolean) { _filter.update { it.copy(favoritesOnly = only) } }
    fun setTopologyFilter(topology: CaptureTopology?) { _filter.update { it.copy(topology = topology) } }
    fun clearFilters() { _filter.value = LibraryFilter() }

    /**
     * Non-blocking one-shot signal that a sidecar write failed (owner adjustment 2).
     * Monotonic counter — each failure increments it so a screen collecting it can
     * surface a transient notice without blocking. 0 = nothing to show.
     */
    private val _sidecarWriteError = MutableStateFlow(0)
    val sidecarWriteError: StateFlow<Int> = _sidecarWriteError.asStateFlow()

    private val libraryStore get() = (getApplication() as? RovaApp)?.libraryMetadataStore

    /**
     * Derived Library state (ADR-0030 / spec §5): joins [items] + captured
     * [manifestFactsByKey] + the sidecar snapshot into [LibraryRow]s via
     * [LibraryRowMapper]. Recomputes when items, the view mode, or the sidecar
     * revision changes. Mapping is pure CPU work over in-memory snapshots.
     */
    val libraryUiState: StateFlow<LibraryUiState> =
        combine(items, hasLoaded, _viewMode, _sidecarRevision, _cardPreview) { rows, loaded, mode, _, cardPreview ->
            val snapshot = libraryStore?.snapshot() ?: emptyMap()
            val locale = Locale.getDefault()
            val tz = TimeZone.getDefault()
            val mapped = rows.map { item ->
                val key = RecordingIdentity.forItem(item.sessionId, item.file?.absolutePath, item.docUri?.toString())
                val canonical = snapshot[key.canonical]
                val legacy = key.legacy?.takeIf { it != key.canonical }?.let { snapshot[it] }
                val meta = LibraryMetadataEntry.merge(canonical, legacy)
                toLibraryRow(item, meta, locale, tz)
            }
            // P6: footprint over the FULL library (pure fold, no extra disk read) — see UsageAggregator.
            LibraryUiState(
                rows = mapped,
                viewMode = mode,
                hasLoaded = loaded,
                usage = UsageAggregator.aggregate(mapped),
                cardPreview = cardPreview,
            )
        }
            // The transform reads the sidecar store (lock-bearing, lazily disk-loaded)
            // and maps the list — keep it off the Main collecting context (codex review).
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    private fun toLibraryRow(
        item: VideoItem,
        meta: LibraryMetadataEntry?,
        locale: Locale,
        tz: TimeZone,
    ): LibraryRow {
        val facts = manifestFactsByKey[item.stableKey]
        val dateMillis = item.effectiveLastModified()
        return LibraryRowMapper.map(
            LibraryRowMapper.Input(
                stableKey = item.stableKey,
                startedAtMillis = facts?.startedAt ?: dateMillis,
                dateMillis = dateMillis,
                dateLabel = HistoryRowFormatters.formatPrimaryDateTime(dateMillis, locale, tz),
                sizeBytes = item.effectiveSize(),
                segmentDurationsMs = facts?.segmentDurationsMs ?: emptyList(),
                topologyPersisted = facts?.topologyPersisted ?: "Single",
                terminated = facts?.terminated,
                stopReason = facts?.stopReason ?: com.aritr.rova.data.StopReason.NONE,
                exportState = facts?.exportState ?: com.aritr.rova.data.ExportState.FINALIZED,
                customTitle = meta?.customTitle,
                favorite = meta?.favorite ?: false,
                side = item.side,
                thumbWidthPx = item.thumbnail?.width ?: 0,
                thumbHeightPx = item.thumbnail?.height ?: 0,
            ),
            locale, tz,
        )
    }

    fun setViewMode(mode: LibraryViewMode) {
        _viewMode.value = mode
        settings.libraryViewMode = mode.name // persist across launches (Slice 4.1)
    }

    /**
     * Hero/quick-action Favorite — the first sidecar WRITE path (ADR-0030). The UI
     * is NON-optimistic (owner adjustment 2): the star reflects the sidecar
     * snapshot, and only flips after a SUCCESSFUL [LibraryMetadataStore.update]
     * bumps [_sidecarRevision] and triggers a recompute. On failure we never
     * optimistically flipped, so there is no desync to roll back — we log and raise
     * a non-blocking error signal. Off-main.
     */
    private fun metaKeyForStableKey(stableKey: String): RecordingIdentity.MetaKey? {
        val item = items.value.firstOrNull { it.stableKey == stableKey } ?: return null
        return RecordingIdentity.forItem(item.sessionId, item.file?.absolutePath, item.docUri?.toString())
    }

    fun toggleFavorite(stableKey: String) {
        val store = libraryStore ?: return
        val key = metaKeyForStableKey(stableKey) ?: run {
            RovaLog.e("HistoryViewModel.toggleFavorite: item not in current list for $stableKey")
            _sidecarWriteError.update { it + 1 }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                store.update(key) { it.copy(favorite = !it.favorite) }
                _sidecarRevision.update { it + 1 } // success → recompute reads the new snapshot
            } catch (t: Throwable) {
                RovaLog.e("HistoryViewModel.toggleFavorite: sidecar write failed for $stableKey", t)
                _sidecarWriteError.update { it + 1 } // UI unchanged; surface a non-blocking notice
            }
        }
    }

    /**
     * Slice 3 — rename = sidecar `customTitle` write (ADR-0030: NEVER the manifest). Same
     * non-optimistic failure model as [toggleFavorite]; a blank title clears the custom name
     * (the row falls back to [SmartTitle]). Off-main.
     */
    fun renameSession(stableKey: String, newTitle: String) {
        val store = libraryStore ?: return
        val key = metaKeyForStableKey(stableKey) ?: run {
            RovaLog.e("HistoryViewModel.renameSession: item not in current list for $stableKey")
            _sidecarWriteError.update { it + 1 }
            return
        }
        val trimmed = newTitle.trim()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                store.update(key) { it.copy(customTitle = trimmed.ifBlank { null }) }
                _sidecarRevision.update { it + 1 }
            } catch (t: Throwable) {
                RovaLog.e("HistoryViewModel.renameSession: sidecar write failed for $stableKey", t)
                _sidecarWriteError.update { it + 1 }
            }
        }
    }

    /** Slice 3 — resolve selected stableKeys to concrete [VideoItem]s (batch share/delete need the artifacts). */
    fun itemsForKeys(keys: Set<String>): List<VideoItem> {
        val byKey = items.value.associateBy { it.stableKey }
        return keys.mapNotNull { byKey[it] }
    }

    /** Result of a batch Vault move: [moved] succeeded, [skipped] were non-movable (P+L) or failed. */
    data class VaultBatchResult(val moved: Int, val skipped: Int)

    /**
     * Slice 3 — batch Vault move. Each selected session is moved via [moveToVault], which self-gates on
     * [VaultMoverBuilder.isSingleModeMovable] (P+L not movable, ADR-0009/0025) and is fail-closed, so a
     * row-level pre-filter is not required for safety. [refresh] fires inside each move; the now-VAULTED
     * rows drop out of the PUBLIC-only listing.
     */
    suspend fun batchMoveToVault(keys: Set<String>): VaultBatchResult {
        val byKey = items.value.associateBy { it.stableKey }
        var moved = 0
        for (k in keys) {
            val sid = byKey[k]?.sessionId ?: continue
            if (moveToVault(sid)) moved++
        }
        return VaultBatchResult(moved = moved, skipped = keys.size - moved)
    }

    /**
     * Phase 1.7 commit-7 NO-GO patch round 2 — manifest-driven listing.
     * Successful exports no longer leave a file under
     * `videos/<sessionId>/Rova_*.mp4`; the artifact lives in
     * `Movies/Rova/<displayName>.mp4` (Tier 2/3 by path,
     * Tier 1 by `MediaStore` URI). Walks the on-disk session manifests,
     * filters to `FINALIZED`, and resolves each manifest to a
     * `java.io.File` via [HistoryArtifactMapper].
     *
     * Legacy entries — top-level `Rova_*.mp4` from pre-Phase-1.1 builds
     * and merged outputs at `videos/<sid>/Rova_*.mp4` from pre-Phase-1.7
     * builds — remain visible via a fallback file-system scan so users
     * upgrading do not lose visibility on prior recordings. The two
     * paths are unioned and de-duped by absolute path.
     */
    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                var newItems = loadItemsList(emitPartial = true)
                // Apply the "keep latest N finalized recordings" retention
                // policy at most once per refresh pass. The cleaner is
                // idempotent on already-trimmed state — a subsequent
                // refresh sees finalized.size <= keepLatest and no-ops —
                // so we can safely re-run loadItemsList only when the
                // cleaner actually deleted something. This avoids the
                // self-triggering refresh loop.
                val cleanupResult = applyRetentionCleanupIfEnabled(newItems)
                if (cleanupResult.deleted > 0) {
                    newItems = loadItemsList(emitPartial = true)
                }
                _items.value = newItems
                pruneSidecar(newItems)
            } finally {
                // Always clear the first-load latch, even if loadItemsList
                // threw (filesystem / MediaMetadataRetriever failure) before
                // the emit above — otherwise the screen's spinner would hang
                // forever. A failed/empty load surfaces zero rows, which the
                // screen then renders as the empty CTA: the correct degraded
                // state. Idempotent with the partial first-paint flip inside
                // loadItemsList (which clears it earlier when rows exist).
                _hasLoaded.value = true
            }
        }
    }

    /**
     * Prunes orphaned sidecar entries after each successful items refresh.
     * Keeps ALL finalized sessions on disk (including vaulted — durable) plus
     * every legacy key visible in the current Library. Off-main; fire-and-forget.
     */
    private fun pruneSidecar(visibleItems: List<VideoItem>) {
        val store = libraryStore ?: return
        val rovaApp = getApplication<Application>() as? RovaApp ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sessionStore = runCatching { rovaApp.sessionStore }.getOrNull() ?: return@launch
                val allManifests: List<SessionManifest> = sessionStore.listSessionIds()
                    .mapNotNull { sid -> runCatching { sessionStore.loadManifest(sid) }.getOrNull() }
                // A session key survives prune only if its manifest loads here; keep this the sole
                // source of finalized session ids so a transient load failure can't widen the prune.
                val finalizedIds = HistoryArtifactMapper.finalizedManifests(allManifests)
                    .map { it.sessionId }
                val visibleLegacy = visibleItems.map {
                    RecordingIdentity.legacyKey(it.file?.absolutePath, it.docUri?.toString())
                }
                val existing = store.snapshot().keys
                store.prune(PruneKeepSet.build(finalizedIds, visibleLegacy, existing))
            } catch (t: Throwable) {
                RovaLog.e("HistoryViewModel.pruneSidecar failed", t)
            }
        }
    }

    /**
     * Builds the History list snapshot used by [refresh]. Extracted so
     * the retention pass can re-run the same load after deleting
     * surplus recordings without duplicating the manifest + legacy
     * union, the de-dup, the newest-first sort, the metadata cache
     * lookup, or the cache-eviction step.
     *
     * Slice 13A — two-stage emit. The first pass builds rows from the
     * metadata cache (placeholder for misses) and pushes them to
     * [_items] immediately when [emitPartial] is true and at least one
     * row is uncached, so the History list paints with all rows
     * visible while [HistoryMetadataLoader] fans out the actual
     * `MediaMetadataRetriever` work in parallel on `Dispatchers.IO`.
     * After fan-out completes the rows are rebuilt from the now-warm
     * cache and returned. A hot cache (every refresh after the first,
     * including retention re-loads) emits exactly once because no
     * placeholders are produced.
     */
    private suspend fun loadItemsList(emitPartial: Boolean): List<VideoItem> = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        val rovaApp = app as? RovaApp
        val sessionStore = rovaApp?.let { runCatching { it.sessionStore }.getOrNull() }
        val resolver: ContentResolver = app.contentResolver

        val visibleManifests = if (sessionStore != null) {
            visibleFinalizedManifests(sessionStore)
        } else {
            emptyList()
        }
        val manifestArtifacts = if (sessionStore != null) {
            manifestDrivenArtifacts(visibleManifests, sessionStore, resolver)
        } else {
            emptyList()
        }

        val legacyArtifacts = legacyFileSystemScan(app)
            .map { ResolvedRecording(file = it, shareUri = null, sessionId = null) }

        // Union manifest-driven + legacy, de-dupe by stable key (path
        // for file rows, content-URI for SAF rows — B4c), sort
        // newest-first by mtime. Manifest-driven entries come first so a
        // duplicate key keeps the URI-bearing record rather than the
        // legacy null-URI one.
        val seen = HashSet<String>()
        val recordings = (manifestArtifacts + legacyArtifacts)
            .filter { seen.add(it.stableKey) }
            .sortedByDescending { it.effectiveLastModified() }

        // ADR-0030 (Slice 2) — capture per-row manifest facts BEFORE the first
        // emit so the derived libraryUiState reads facts consistent with the rows.
        // Legacy rows (no sessionId / no manifest) are simply absent → neutral
        // defaults in toLibraryRow. No second disk walk: reuses visibleManifests.
        val manifestBySession = visibleManifests.associateBy { it.sessionId }
        manifestFactsByKey = recordings.mapNotNull { rec ->
            val sid = rec.sessionId ?: return@mapNotNull null
            val m = manifestBySession[sid] ?: return@mapNotNull null
            rec.stableKey to factsFor(m, rec)
        }.toMap()

        // spec §7 — per-row disk-cache invalidator: (mtime, size, durationMs). A
        // changed/replaced artifact at the same key yields a different cache key,
        // so a stale thumbnail is never served. Duration comes from the facts map
        // (0 for legacy rows). Built once; reused by the extract closure + prune.
        val invalidatorByKey: Map<String, Triple<Long, Long, Long>> = recordings.associate { rec ->
            val size = rec.file?.length() ?: rec.sizeBytes ?: 0L
            val mtime = rec.effectiveLastModified()
            val dur = manifestFactsByKey[rec.stableKey]?.segmentDurationsMs?.sum() ?: 0L
            rec.stableKey to Triple(mtime, size, dur)
        }
        fun diskKeyFor(stableKey: String): String? =
            invalidatorByKey[stableKey]?.let { (mtime, size, dur) ->
                ThumbnailCacheKey.keyFor(stableKey, size, mtime, dur)
            }

        val initial = recordings.map { rec -> buildItem(rec) }
        // Explicit containsKey: ConcurrentHashMap.contains resolves to
        // containsValue under Kotlin's Map operator overloads, which is
        // not what we want here (KT-18053).
        val anyMissing = recordings.any { !metadataCache.containsKey(it.stableKey) }
        if (emitPartial && anyMissing) {
            // First-paint emit: rows show up with placeholder thumb +
            // "—" resolution while metadata loads in parallel.
            _items.value = initial
            // The query has returned (with rows) — clear the loading latch
            // so the partial first paint shows the list, not the empty CTA.
            _hasLoaded.value = true
        }

        if (anyMissing) {
            // B4c — the cache key is the stable key. For file rows that
            // is the absolute path (extract via setDataSource(path)); for
            // SAF rows it is the `content://` URI string, which
            // [extractMetadata] detects and opens via
            // setDataSource(context, uri).
            HistoryMetadataLoader.fillMissing(
                paths = recordings.map { it.stableKey },
                cache = metadataCache,
                extract = { key ->
                    // spec §7 — read the disk cache first; on a hit, recover the
                    // resolution label from the decoded frame's own dimensions
                    // (owner adjustment 3 — zero extra storage). On a miss, extract
                    // via MediaMetadataRetriever and persist the WebP keyframe.
                    val diskKey = diskKeyFor(key)
                    val cachedBytes = diskKey?.let { thumbDiskCache.get(it) }
                    if (cachedBytes != null) {
                        val bmp = VideoMetadataUtils.decodeThumb(cachedBytes)
                        bmp to VideoMetadataUtils.resolutionForBitmap(bmp)
                    } else {
                        val (bmp, res) = VideoMetadataUtils.extractMetadata(app, key)
                        if (bmp != null && diskKey != null) {
                            runCatching { thumbDiskCache.put(diskKey, VideoMetadataUtils.encodeThumb(bmp)) }
                        }
                        bmp to res
                    }
                }
            )
        }

        val finalItems = if (anyMissing) {
            recordings.map { rec -> buildItem(rec) }
        } else {
            initial
        }

        // Clean stale entries for deleted files
        val currentKeys = recordings.map { it.stableKey }.toSet()
        metadataCache.keys.retainAll(currentKeys)

        // spec §7 — prune the disk cache to the live cache-key space (deleted /
        // replaced rows). Keyed by ThumbnailCacheKey, not stableKey, so compute the
        // live cache keys from the same invalidator map.
        val liveDiskKeys = recordings.mapNotNull { diskKeyFor(it.stableKey) }.toSet()
        runCatching { thumbDiskCache.removeAllExcept(liveDiskKeys) }

        finalItems
    }

    private fun buildItem(rec: ResolvedRecording): VideoItem {
        // B4c — cache is keyed on the stable key (path for file rows,
        // content-URI for SAF rows) so a null file never NPEs here.
        val cached = metadataCache[rec.stableKey]
        return VideoItem(
            file = rec.file,
            docUri = rec.docUri,
            thumbnail = cached?.first,
            resolution = cached?.second ?: VideoMetadataUtils.UNKNOWN_RESOLUTION,
            shareUri = rec.shareUri,
            sessionId = rec.sessionId,
            side = rec.side,
            segmentIndex = rec.segmentIndex,
            modifiedAtMillis = rec.modifiedAtMillis,
            sizeBytes = rec.sizeBytes,
            displayName = rec.displayName
        )
    }

    /**
     * Applies the user's retention policy to the current snapshot if
     * the toggle is on. Routes each surplus delete through the same
     * [HistoryDeleter]-orchestrated artifact-then-discard pipeline as
     * manual History delete, so a retention-driven cleanup is
     * indistinguishable from the user tapping Delete on the same
     * entries — gallery row goes via `MediaStore`, then
     * `SessionStore.discardSession` removes the per-session
     * directory. Failures are logged via [RovaLog]; they do not
     * propagate to the UI snackbar because retention cleanup is a
     * background-y maintenance step the user did not request
     * synchronously.
     */
    private fun applyRetentionCleanupIfEnabled(
        items: List<VideoItem>
    ): RecordingRetentionCleaner.Result {
        val app = getApplication<Application>()
        val settings = RovaSettings(app)
        if (!settings.autoDeleteEnabled) return RecordingRetentionCleaner.Result.NoOp
        val resolver = app.contentResolver
        val sessionStore = (app as? RovaApp)?.let {
            runCatching { it.sessionStore }.getOrNull()
        }
        val deleter = HistoryDeleter(
            deleteArtifact = { item -> deleteOne(resolver, item) },
            discardSession = { sid -> sessionStore?.discardSession(sid) },
            onDiscardError = { sid, t ->
                RovaLog.w(
                    "HistoryViewModel.retention: discardSession failed for $sid",
                    t
                )
            }
        )
        val cleaner = RecordingRetentionCleaner(deleteItem = { deleter.delete(it) })
        val result = cleaner.clean(
            enabled = true,
            keepLatest = settings.autoDeleteKeepLatest,
            items = items
        )
        if (result.deleted > 0) {
            RovaLog.d {
                "HistoryViewModel.retention: deleted ${result.deleted} surplus" +
                    " recording(s); ${result.failed} failure(s)"
            }
        }
        // Slice 13B — surface a single concise snackbar when cleanup
        // did real work or failed. NoOp passes are silent so the
        // common case (refresh on a library already inside the
        // keep-latest window) does not spam.
        if (result.deleted > 0 || result.failed > 0) {
            _retentionNotices.tryEmit(
                RetentionCleanupNotice(deleted = result.deleted, failed = result.failed)
            )
        }
        return result
    }

    private data class ResolvedRecording(
        // B4c — nullable: SAF rows have no java.io.File (content-URI
        // artifact). Every File-backed tier still populates this.
        val file: File?,
        val shareUri: Uri?,
        val sessionId: String?,
        // Phase 6.1b smoke-fix #3 — non-null for P+L rows (one per
        // side); null for single-mode rows and legacy file-only
        // entries. Threaded into [VideoItem.side] via [buildItem].
        val side: VideoSide? = null,
        // Milestone 2 — non-null for rows derived from a
        // MULTI_SEGMENT_KEPT session's per-segment fanout; null for
        // single-mode, P+L, and legacy rows. Threaded into
        // [VideoItem.segmentIndex] via [buildItem].
        val segmentIndex: Int? = null,
        // B4c (ADR-0024) — SAF content-document URI. Non-null only for
        // SAF rows (file == null); threaded into [VideoItem.docUri].
        val docUri: Uri? = null,
        // B4c — SAF row metadata sourced from DocumentFile, used when
        // file == null so sort/group/size/name work without a File.
        val modifiedAtMillis: Long? = null,
        val sizeBytes: Long? = null,
        val displayName: String? = null
    ) {
        // B4c — mirrors VideoItem.stableKey so the union/de-dup/sort and
        // metadata-cache keying tolerate a null file.
        val stableKey: String
            get() = file?.absolutePath ?: docUri?.toString() ?: "session:$sessionId"

        fun effectiveLastModified(): Long = file?.lastModified() ?: modifiedAtMillis ?: 0L
    }

    /**
     * Loads every on-disk manifest, keeps FINALIZED + Library-visible (PUBLIC)
     * ones. Extracted (Slice 2) so the same visible-manifest list feeds both the
     * artifact resolution AND the per-row [RowManifestFacts] capture without a
     * second disk walk — see [loadItemsList].
     */
    private fun visibleFinalizedManifests(sessionStore: SessionStore): List<SessionManifest> {
        val manifests: List<SessionManifest> = sessionStore.listSessionIds()
            .mapNotNull { sid ->
                runCatching { sessionStore.loadManifest(sid) }.getOrNull()
            }
        return HistoryArtifactMapper.finalizedManifests(manifests)
            // B5 / ADR-0025 (spec O1) — the normal Library shows ONLY
            // PUBLIC recordings. A manifest whose vaultState is VAULTING /
            // VAULTED / UNVAULTING is hidden here; the separate Vault
            // screen surfaces VAULTED + UNVAULTING. AND-ed in on top of
            // the FINALIZED-keep predicate so vaulted recordings vanish
            // from the Library without touching any other filtering.
            .filter { com.aritr.rova.ui.vault.isLibraryVisible(it.vaultState) }
    }

    /**
     * ADR-0030 (Slice 2) — derives the per-row session facts the Library row model
     * needs (duration / topology / badge inputs / startedAt). For a per-segment
     * (`MULTI_SEGMENT_KEPT`) row the duration is that ONE segment (matched by
     * filename); every other row sums the session's segments.
     */
    private fun factsFor(m: SessionManifest, rec: ResolvedRecording): RowManifestFacts {
        // A DualShot per-side row must count only its own side's segments — a DualShot capture writes a
        // portrait AND a landscape segment per loop, so summing all segments reported N×2 clips and a
        // doubled duration (SessionDurations, JVM-tested).
        val durations = SessionDurations.forRow(
            segments = m.segments,
            isPerSegment = rec.segmentIndex != null,
            segmentFilename = rec.file?.name,
            side = rec.side,
        )
        return RowManifestFacts(
            startedAt = m.startedAt,
            segmentDurationsMs = durations,
            topologyPersisted = m.config.captureTopology,
            terminated = m.terminated,
            stopReason = m.stopReason,
            exportState = m.exportState,
        )
    }

    private fun manifestDrivenArtifacts(
        visibleManifests: List<SessionManifest>,
        sessionStore: SessionStore,
        resolver: ContentResolver
    ): List<ResolvedRecording> {
        return visibleManifests
            .flatMap { m ->
                // Milestone 2 — MULTI_SEGMENT_KEPT fanout. Each kept segment
                // becomes a standalone library row. Composition with the
                // existing single-mode / P+L branches: the per-segment helper
                // returns emptyList() for non-MULTI_SEGMENT_KEPT terminals,
                // so the existing single/P+L paths are unaffected.
                val perSegment = HistoryArtifactMapper.resolveArtifactsPerSegment(m)
                if (perSegment.isNotEmpty()) {
                    return@flatMap perSegment.map { seg ->
                        val sessionDir = sessionStore.sessionDir(seg.sessionId)
                        val file = java.io.File(sessionDir, seg.filename)
                        // Task 3.1 — no inner file.exists() guard: the outer
                        // .filter { rec.file.exists() && rec.file.length() > 0L }
                        // below enforces existence AND zero-length filtering
                        // uniformly across single-mode / P+L / per-segment paths.
                        // sizeBytes intentionally dropped — UI shows duration +
                        // thumbnail only; consistent with PerSideArtifact which
                        // also omits size. Plan §3 scope.
                        ResolvedRecording(
                            file = file,
                            // Segments are app-private; no MediaStore URI.
                            shareUri = null,
                            sessionId = seg.sessionId,
                            side = null,
                            segmentIndex = seg.segmentIndex
                        )
                    }
                }
                // Phase 6.1b T16 — branch on the persisted mode. P+L
                // sessions fan out to per-side rows (0/1/2 cards per
                // manifest); single-mode keeps the pre-T16 single-card
                // shape byte-identically.
                if (m.config.captureTopology == "DualShot") {
                    HistoryArtifactMapper.resolveArtifactsPerSide(m) { uri ->
                        resolveMediaStoreUriToFile(resolver, uri)
                    }.map { perSide ->
                        // Tier 2/3 P+L: mapper returns null share URI,
                        // same as single-mode resolveShareUri. Resolve
                        // via _DATA against MediaStore so the share path
                        // prefers the content URI over FileProvider
                        // (which would throw on a Movies/Rova/... path).
                        val shareUriString = perSide.shareUri
                            ?: queryMediaStoreUriByPath(resolver, perSide.file.absolutePath)
                        ResolvedRecording(
                            file = perSide.file,
                            shareUri = shareUriString?.let(Uri::parse),
                            sessionId = m.sessionId,
                            side = perSide.side
                        )
                    }
                } else if (m.exportTier == ExportTier.SAF_DESTINATION) {
                    // B4c (ADR-0024) — single-mode SAF row. The artifact
                    // is a `content://` document with no java.io.File, so
                    // instead of dropping on a null file (the File-based
                    // path would), emit a content-URI row. The doc URI is
                    // both the identity and the share URI; player nav is
                    // by sessionId (PlayerUriResolver reads
                    // safTargetDocUri from the manifest). Metadata
                    // (last-modified / size / name) is read from the
                    // DocumentFile so the row sorts/groups/sizes like a
                    // file row; null-tolerant fallbacks keep it visible
                    // even if the provider omits a field.
                    val docUriStr = HistoryArtifactMapper.resolveShareUri(m)
                        ?: return@flatMap emptyList()
                    val docUri = Uri.parse(docUriStr)
                    val doc = runCatching {
                        androidx.documentfile.provider.DocumentFile
                            .fromSingleUri(getApplication(), docUri)
                    }.getOrNull()
                    val modifiedAt = doc?.lastModified()?.takeIf { it > 0L }
                        ?: m.terminatedAt ?: m.startedAt
                    val sizeBytes = doc?.length()?.takeIf { it > 0L }
                    listOf(
                        ResolvedRecording(
                            file = null,
                            shareUri = docUri,
                            sessionId = m.sessionId,
                            docUri = docUri,
                            modifiedAtMillis = modifiedAt,
                            sizeBytes = sizeBytes,
                            displayName = doc?.name
                        )
                    )
                } else {
                    val file = HistoryArtifactMapper.resolveArtifactFile(m) { uri ->
                        resolveMediaStoreUriToFile(resolver, uri)
                    } ?: return@flatMap emptyList()
                    // Tier 1 ships the canonical content URI in the
                    // manifest; Tier 2/3 only persist the path, so look
                    // the URI up by `_DATA` against MediaStore. A null
                    // result means the scan never registered (rare —
                    // recording not yet indexed); the share path then
                    // falls back to FileProvider, which the UI guards
                    // against IllegalArgumentException.
                    val shareUriString = HistoryArtifactMapper.resolveShareUri(m)
                        ?: queryMediaStoreUriByPath(resolver, file.absolutePath)
                    listOf(
                        ResolvedRecording(
                            file = file,
                            shareUri = shareUriString?.let(Uri::parse),
                            sessionId = m.sessionId
                        )
                    )
                }
            }
            .filter { rec ->
                // Drop entries whose artifact is no longer present —
                // user may have deleted the gallery entry externally.
                // B4c — SAF rows (file == null, docUri != null) validate
                // via DocumentFile; file rows keep the existing
                // exists()+length()>0 gate byte-identically.
                runCatching {
                    val docUri = rec.docUri
                    if (docUri != null) {
                        val doc = androidx.documentfile.provider.DocumentFile
                            .fromSingleUri(getApplication(), docUri)
                        doc != null && doc.exists() && doc.length() > 0L
                    } else {
                        val f = rec.file
                        f != null && f.exists() && f.length() > 0L
                    }
                }.getOrDefault(false)
            }
    }

    /**
     * Pre-Phase-1.7 fallback. Surfaces `Rova_*.mp4` files that lived
     * under `videos/<sessionId>/` on prior builds AND top-level
     * `Rova_*.mp4` from pre-Phase-1.1 builds. Phase 1.7 builds will not
     * write to either location post-merge; this branch only matters
     * for upgrade continuity.
     */
    private fun legacyFileSystemScan(app: Application): List<File> {
        val dir = File(app.getExternalFilesDir("videos"), "")
        if (!dir.exists()) return emptyList()
        return dir.listFiles()?.flatMap { entry ->
            when {
                entry.isDirectory ->
                    // B5 / ADR-0025 — fail-closed vault privacy. A session dir
                    // with a manifest is owned by the manifest-driven path (which
                    // applies the PUBLIC-only vault filter); the legacy scan only
                    // surfaces truly manifest-less pre-Phase-1.7 dirs. Gating on
                    // manifest *presence* (not load) keeps a vaulted recording's
                    // plain Rova_*.mp4 out of the Library even if the manifest is
                    // corrupt. See [legacyScanIncludesSessionDir].
                    if (legacyScanIncludesSessionDir(
                            File(entry, SessionStore.MANIFEST_NAME).exists()
                        )
                    ) {
                        entry.listFiles()
                            ?.filter { it.extension == "mp4" && it.name.startsWith("Rova_") }
                            ?.toList() ?: emptyList()
                    } else {
                        emptyList()
                    }
                entry.extension == "mp4" &&
                    !entry.name.startsWith("segment_bg_") &&
                    !entry.name.startsWith("segment_") ->
                    listOf(entry)
                else -> emptyList()
            }
        } ?: emptyList()
    }

    /**
     * Reverse of [resolveMediaStoreUriToFile]. Looks up a `MediaStore`
     * content URI for a file the app finalized to public Movies, by
     * matching its on-disk path against the deprecated `_DATA` column.
     * Used by Tier 2/3 (manifest carries only `publicTargetPath`, not
     * a content URI) to surface a safe share URI.
     *
     * `_DATA` is deprecated for writes on API 29+ but reads remain
     * functional for entries owned by this app, which is what the
     * post-merge `MediaScannerConnection` registers. Returns `null`
     * if the row is absent (scan not yet completed, gallery entry
     * deleted) or the column read fails — the caller treats `null`
     * as "no MediaStore URI; fall back to FileProvider."
     */
    private fun queryMediaStoreUriByPath(resolver: ContentResolver, path: String): String? {
        return try {
            @Suppress("DEPRECATION")
            resolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Video.Media._ID),
                "${MediaStore.Video.Media.DATA} = ?",
                arrayOf(path),
                null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val id = c.getLong(0)
                    ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        id
                    ).toString()
                } else {
                    null
                }
            }
        } catch (t: Throwable) {
            RovaLog.w("HistoryViewModel: MediaStore _DATA-by-path lookup failed for $path", t)
            null
        }
    }

    /**
     * Resolves a `MediaStore` content URI to its on-disk file path via
     * the `_DATA` column. `_DATA` is deprecated for writes on API 29+
     * but reads remain functional for entries owned by this app, which
     * is exactly the lifecycle [com.aritr.rova.service.export.Tier1Exporter]
     * produces — insert + own + finalize. Returns `null` if the row is
     * gone (user-deleted from gallery, etc.) or the column read fails.
     */
    private fun resolveMediaStoreUriToFile(resolver: ContentResolver, uriString: String): File? {
        return try {
            val uri = Uri.parse(uriString)
            @Suppress("DEPRECATION")
            resolver.query(uri, arrayOf(MediaStore.Video.Media.DATA), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val path = c.getString(0)
                    if (path.isNullOrEmpty()) null else File(path)
                } else {
                    null
                }
            }
        } catch (t: Throwable) {
            RovaLog.w("HistoryViewModel: MediaStore _DATA lookup failed for $uriString", t)
            null
        }
    }

    /**
     * Per-batch delete result. `deleted` counts rows the user
     * successfully removed; `failed` counts rows that could not be
     * deleted (security exception, missing MediaStore row paired with
     * a stale legacy file path, etc.). The History screen surfaces
     * `failed` as a snackbar so the user is not left wondering why a
     * "deleted" item still appears in the gallery.
     */
    data class DeleteResult(val deleted: Int, val failed: Int)

    /**
     * Deletes the given recordings on `Dispatchers.IO`, prefers the
     * `MediaStore` content URI (the only delete path that removes
     * public-gallery rows on Android 10+ scoped storage), and falls
     * back to `File.delete()` only for legacy app-private artifacts
     * outside `MediaStore`. After the batch completes it triggers a
     * `refresh()`; the caller observes the `items` StateFlow for the
     * UI update and reads the returned [DeleteResult] to surface
     * partial-failure messaging.
     *
     * Pre-fix the History screen called `File.delete()` on every
     * selected entry. On API 29+ that does not actually remove the
     * row from `Movies/Rova/...` because `MediaStore` owns it — the
     * file would re-materialize on the next scan and the gallery
     * entry persisted regardless. Routing through `ContentResolver.delete`
     * with the canonical `shareUri` (already plumbed by the share
     * fixes) closes that gap.
     */
    suspend fun deleteItems(items: Collection<VideoItem>): DeleteResult {
        val total = items.size
        val failedKeys = deleteItemsKeyed(items)
        return DeleteResult(deleted = total - failedKeys.size, failed = failedKeys.size)
    }

    /**
     * Slice 3 — keyed delete used by the deferred-delete Snackbar-UNDO flow. Returns the set of
     * [VideoItem.stableKey]s whose delete FAILED, so the screen can restore exactly those rows to the
     * library while the truly-deleted rows drop out via the [refresh] that fires here. [deleteItems]
     * delegates to this and reduces to counts for legacy callers.
     */
    suspend fun deleteItemsKeyed(items: Collection<VideoItem>): Set<String> =
        withContext(Dispatchers.IO) {
            if (items.isEmpty()) return@withContext emptySet()
            val app = getApplication<Application>()
            val resolver = app.contentResolver
            // Same access pattern as `refresh()`: read sessionStore via
            // the lazy RovaApp accessor and tolerate the storage-
            // unavailable branch by passing a discard-noop seam. Avoids
            // making SessionStore global or static.
            val sessionStore = (app as? RovaApp)?.let {
                runCatching { it.sessionStore }.getOrNull()
            }
            val deleter = HistoryDeleter(
                deleteArtifact = { item -> deleteOne(resolver, item) },
                discardSession = { sid ->
                    sessionStore?.discardSession(sid)
                },
                onDiscardError = { sid, t ->
                    RovaLog.w(
                        "HistoryViewModel.deleteItems: discardSession failed for $sid",
                        t
                    )
                }
            )
            val failed = mutableSetOf<String>()
            items.forEach { item ->
                if (!deleter.delete(item)) failed += item.stableKey
            }
            refresh()
            failed
        }

    /**
     * Single-item delete with the MediaStore-first fallback ladder.
     * Returns `true` only when the row is gone after this call —
     * either the `ContentResolver.delete` reported >0 affected rows
     * OR the legacy `File.delete()` succeeded. A `SecurityException`
     * (covers `RecoverableSecurityException` on API 29+ for rows the
     * app does not own) is logged and counted as failure; we cannot
     * launch the recovery `IntentSender` from a non-Activity context
     * without restructuring the screen, and Rova-owned rows are the
     * only artifacts the History list surfaces in the first place,
     * so this path should only fire on cross-app drift.
     */
    private fun deleteOne(resolver: ContentResolver, item: VideoItem): Boolean {
        // B4c (ADR-0024) — SAF row: no java.io.File and no MediaStore
        // row. Delete the content document via DocumentFile (the SAF
        // counterpart to ContentResolver.delete for MediaStore rows).
        // discardSession cleanup runs afterwards in HistoryDeleter.
        item.docUri?.let { docUri ->
            return try {
                val doc = androidx.documentfile.provider.DocumentFile
                    .fromSingleUri(getApplication(), docUri)
                // delete() true when removed; if the doc is already gone
                // (!exists) treat as success so the row clears.
                (doc?.delete() == true) || (doc?.exists() == false)
            } catch (e: SecurityException) {
                RovaLog.w("HistoryViewModel.deleteOne: SecurityException for SAF $docUri", e)
                false
            } catch (e: Exception) {
                RovaLog.w("HistoryViewModel.deleteOne: failed for SAF $docUri", e)
                false
            }
        }
        val file = item.file
        return try {
            val uri: Uri? = item.shareUri
                ?: file?.let { queryMediaStoreUriByPath(resolver, it.absolutePath)?.let(Uri::parse) }
            if (uri != null) {
                val rows = resolver.delete(uri, null, null)
                if (rows > 0) {
                    runCatching { file?.delete() }
                    true
                } else {
                    // Row already gone (deleted out-of-band by the
                    // gallery app, or never registered). Treat the
                    // file-system entry as the source of truth.
                    file?.let { it.delete() || !it.exists() } ?: true
                }
            } else {
                file?.let { it.delete() || !it.exists() } ?: false
            }
        } catch (e: SecurityException) {
            RovaLog.w(
                "HistoryViewModel.deleteOne: SecurityException for ${file?.absolutePath}",
                e
            )
            false
        } catch (e: Exception) {
            RovaLog.w(
                "HistoryViewModel.deleteOne: failed for ${file?.absolutePath}",
                e
            )
            false
        }
    }

    /**
     * B5 / ADR-0025 (Task 22) — move-IN. Hides a normal (PUBLIC) Library
     * recording into the app-private vault: copies the public bytes to the
     * session's vault file, then drops the public artifact (gallery row /
     * SAF doc). No auth is required for move-in (sensitive direction is
     * move-OUT). Runs entirely off the main thread; on completion the
     * Library refreshes (the now-VAULTED row drops out via the
     * PUBLIC-only filter).
     *
     * Single-mode only — a P+L session has per-side pointers that
     * [VaultAndroidOps] cannot resolve; [VaultMoverBuilder.isSingleModeMovable]
     * gates them out and this no-ops with a log.
     *
     * Failure is fail-closed by [VaultMover]'s ordering: a copy that fails
     * leaves the public copy untouched (nothing hidden); a delete that
     * fails leaves the session in VAULTING for a future recovery/move
     * retry. We log and refresh either way.
     *
     * @return true when the move completed (VAULTED), false when it was
     *   skipped (P+L, missing manifest/store) or threw.
     */
    suspend fun moveToVault(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        val sessionStore = (app as? RovaApp)?.let {
            runCatching { it.sessionStore }.getOrNull()
        } ?: return@withContext false
        val manifest = runCatching { sessionStore.loadManifest(sessionId) }.getOrNull()
            ?: return@withContext false
        if (!VaultMoverBuilder.isSingleModeMovable(manifest)) {
            RovaLog.w("HistoryViewModel.moveToVault: P+L session $sessionId not movable; skipping")
            return@withContext false
        }
        val sessionDir = sessionStore.sessionDir(sessionId)
        val mover = VaultMoverBuilder.buildMoveIn(app, sessionStore, manifest, sessionDir)
        val ok = try {
            mover.moveIn(sessionId)
            true
        } catch (t: Throwable) {
            RovaLog.w("HistoryViewModel.moveToVault: move-in failed for $sessionId", t)
            false
        }
        refresh()
        ok
    }

    /**
     * Phase 2.2 — read-only adapter for the Library "View Settings"
     * popup. Resolves a History row's [VideoItem.sessionId] back to
     * the [SessionConfig] persisted at session start so the dialog can
     * surface the original clip / repeats / wait / quality picks.
     *
     * Returns `null` when:
     * - [sessionId] is `null` (legacy file-only entry — pre-Phase-1.7
     *   builds did not write a manifest);
     * - the [SessionStore] is not available (storage missing at boot);
     * - the manifest cannot be loaded or parsed.
     *
     * The dialog treats `null` as the "settings unavailable" branch
     * and surfaces a snackbar rather than crashing. Runs on
     * [Dispatchers.IO] because [SessionStore.loadManifest] does
     * synchronous filesystem I/O.
     */
    suspend fun loadSessionConfig(sessionId: String?): SessionConfig? {
        if (sessionId == null) return null
        return withContext(Dispatchers.IO) {
            val app = getApplication<Application>()
            val sessionStore = (app as? RovaApp)?.let {
                runCatching { it.sessionStore }.getOrNull()
            } ?: return@withContext null
            runCatching { sessionStore.loadManifest(sessionId)?.config }.getOrNull()
        }
    }

    override fun onCleared() {
        metadataCache.values.forEach { (thumbnail, _) ->
            thumbnail?.recycle()
        }
        metadataCache.clear()
        super.onCleared()
    }
}

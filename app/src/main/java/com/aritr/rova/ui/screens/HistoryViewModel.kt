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
import com.aritr.rova.data.ExportTier
import com.aritr.rova.data.RovaSettings
import com.aritr.rova.data.SessionConfig
import com.aritr.rova.data.SessionManifest
import com.aritr.rova.data.SessionStore
import com.aritr.rova.service.dualrecord.VideoSide
import com.aritr.rova.utils.RovaLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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

        val manifestArtifacts = if (sessionStore != null) {
            manifestDrivenArtifacts(sessionStore, resolver)
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

        val initial = recordings.map { rec -> buildItem(rec) }
        // Explicit containsKey: ConcurrentHashMap.contains resolves to
        // containsValue under Kotlin's Map operator overloads, which is
        // not what we want here (KT-18053).
        val anyMissing = recordings.any { !metadataCache.containsKey(it.stableKey) }
        if (emitPartial && anyMissing) {
            // First-paint emit: rows show up with placeholder thumb +
            // "—" resolution while metadata loads in parallel.
            _items.value = initial
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
                extract = { key -> VideoMetadataUtils.extractMetadata(app, key) }
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
            RovaLog.d(
                "HistoryViewModel.retention: deleted ${result.deleted} surplus" +
                    " recording(s); ${result.failed} failure(s)"
            )
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

    private fun manifestDrivenArtifacts(
        sessionStore: SessionStore,
        resolver: ContentResolver
    ): List<ResolvedRecording> {
        val manifests: List<SessionManifest> = sessionStore.listSessionIds()
            .mapNotNull { sid ->
                runCatching { sessionStore.loadManifest(sid) }.getOrNull()
            }
        return HistoryArtifactMapper.finalizedManifests(manifests)
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
                if (m.config.mode == "PortraitLandscape") {
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
                    entry.listFiles()
                        ?.filter { it.extension == "mp4" && it.name.startsWith("Rova_") }
                        ?.toList() ?: emptyList()
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
    suspend fun deleteItems(items: Collection<VideoItem>): DeleteResult =
        withContext(Dispatchers.IO) {
            if (items.isEmpty()) return@withContext DeleteResult(0, 0)
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
            var deleted = 0
            var failed = 0
            items.forEach { item ->
                if (deleter.delete(item)) deleted++ else failed++
            }
            refresh()
            DeleteResult(deleted = deleted, failed = failed)
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

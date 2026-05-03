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
import com.aritr.rova.data.SessionManifest
import com.aritr.rova.data.SessionStore
import com.aritr.rova.utils.RovaLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class VideoItem(
    val file: File,
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
    val shareUri: Uri? = null
)

class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val _items = MutableStateFlow<List<VideoItem>>(emptyList())
    val items: StateFlow<List<VideoItem>> = _items.asStateFlow()

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
                .map { ResolvedRecording(file = it, shareUri = null) }

            // Union manifest-driven + legacy, de-dupe by absolute path,
            // sort newest-first by mtime. Manifest-driven entries come
            // first so a duplicate path keeps the URI-bearing record
            // rather than the legacy null-URI one.
            val seen = HashSet<String>()
            val recordings = (manifestArtifacts + legacyArtifacts)
                .filter { seen.add(it.file.absolutePath) }
                .sortedByDescending { it.file.lastModified() }

            val newItems = recordings.map { rec ->
                val path = rec.file.absolutePath
                val cached = metadataCache[path]
                if (cached != null) {
                    VideoItem(
                        file = rec.file,
                        thumbnail = cached.first,
                        resolution = cached.second,
                        shareUri = rec.shareUri
                    )
                } else {
                    val thumb = VideoMetadataUtils.getThumbnail(path)
                    val res = VideoMetadataUtils.getResolutionLabel(path)
                    metadataCache[path] = Pair(thumb, res)
                    VideoItem(
                        file = rec.file,
                        thumbnail = thumb,
                        resolution = res,
                        shareUri = rec.shareUri
                    )
                }
            }

            // Clean stale entries for deleted files
            val currentPaths = recordings.map { it.file.absolutePath }.toSet()
            metadataCache.keys.retainAll(currentPaths)

            _items.value = newItems
        }
    }

    private data class ResolvedRecording(val file: File, val shareUri: Uri?)

    private fun manifestDrivenArtifacts(
        sessionStore: SessionStore,
        resolver: ContentResolver
    ): List<ResolvedRecording> {
        val manifests: List<SessionManifest> = sessionStore.listSessionIds()
            .mapNotNull { sid ->
                runCatching { sessionStore.loadManifest(sid) }.getOrNull()
            }
        return HistoryArtifactMapper.finalizedManifests(manifests)
            .mapNotNull { m ->
                val file = HistoryArtifactMapper.resolveArtifactFile(m) { uri ->
                    resolveMediaStoreUriToFile(resolver, uri)
                } ?: return@mapNotNull null
                // Tier 1 ships the canonical content URI in the manifest;
                // Tier 2/3 only persist the path, so look the URI up by
                // `_DATA` against MediaStore. A null result means the
                // scan never registered (rare — recording not yet
                // indexed); the share path then falls back to
                // FileProvider, which the UI guards against
                // IllegalArgumentException.
                val shareUriString = HistoryArtifactMapper.resolveShareUri(m)
                    ?: queryMediaStoreUriByPath(resolver, file.absolutePath)
                ResolvedRecording(
                    file = file,
                    shareUri = shareUriString?.let(Uri::parse)
                )
            }
            .filter { rec ->
                // Drop entries whose artifact is no longer on disk —
                // user may have deleted the gallery entry externally.
                runCatching { rec.file.exists() && rec.file.length() > 0L }.getOrDefault(false)
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
     * Deletes the given files on a background thread, then refreshes the list.
     */
    fun deleteFiles(files: Set<File>) {
        viewModelScope.launch(Dispatchers.IO) {
            files.forEach { it.delete() }
            refresh()
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

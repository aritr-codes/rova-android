package com.aritr.rova.ui.screens

import android.app.Application
import android.content.ContentResolver
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
    val resolution: String
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

            // Union manifest-driven + legacy, de-dupe by absolute path,
            // sort newest-first by mtime.
            val seen = HashSet<String>()
            val files = (manifestArtifacts + legacyArtifacts)
                .filter { seen.add(it.absolutePath) }
                .sortedByDescending { it.lastModified() }

            val newItems = files.map { file ->
                val path = file.absolutePath
                val cached = metadataCache[path]
                if (cached != null) {
                    VideoItem(file = file, thumbnail = cached.first, resolution = cached.second)
                } else {
                    val thumb = VideoMetadataUtils.getThumbnail(path)
                    val res = VideoMetadataUtils.getResolutionLabel(path)
                    metadataCache[path] = Pair(thumb, res)
                    VideoItem(file = file, thumbnail = thumb, resolution = res)
                }
            }

            // Clean stale entries for deleted files
            val currentPaths = files.map { it.absolutePath }.toSet()
            metadataCache.keys.retainAll(currentPaths)

            _items.value = newItems
        }
    }

    private fun manifestDrivenArtifacts(
        sessionStore: SessionStore,
        resolver: ContentResolver
    ): List<File> {
        val manifests: List<SessionManifest> = sessionStore.listSessionIds()
            .mapNotNull { sid ->
                runCatching { sessionStore.loadManifest(sid) }.getOrNull()
            }
        return HistoryArtifactMapper.finalizedManifests(manifests)
            .mapNotNull { m ->
                HistoryArtifactMapper.resolveArtifactFile(m) { uri ->
                    resolveMediaStoreUriToFile(resolver, uri)
                }
            }
            .filter { f ->
                // Drop entries whose artifact is no longer on disk —
                // user may have deleted the gallery entry externally.
                runCatching { f.exists() && f.length() > 0L }.getOrDefault(false)
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

package com.aritr.rova.ui.vault

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aritr.rova.RovaApp
import com.aritr.rova.data.SessionManifest
import com.aritr.rova.data.SessionStore
import com.aritr.rova.service.dualrecord.VideoSide
import com.aritr.rova.ui.screens.HistoryMetadataLoader
import com.aritr.rova.ui.screens.VideoItem
import com.aritr.rova.ui.screens.VideoMetadataUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * B5 / ADR-0025 (spec O1) — backing VM for the hidden vault list.
 *
 * Mirrors [com.aritr.rova.ui.screens.HistoryViewModel] in shape (same
 * [VideoItem] type, same [VideoMetadataUtils] thumbnail/resolution
 * extraction, same metadata cache + parallel fill) but applies the
 * COMPLEMENTARY filter: [isVaultVisible] (VAULTED || UNVAULTING) instead
 * of [isLibraryVisible] (PUBLIC). HistoryViewModel is deliberately NOT
 * subclassed — its manifest→row mapping resolves PUBLIC artifacts to
 * MediaStore / SAF / public-path files, while a vault row's artifact is
 * the app-private [SessionManifest.vaultFilePath] (per-side
 * portrait/landscapeVaultFilePath for P+L). The two resolution paths are
 * different enough that a shared mapper would be more fragile than a small
 * dedicated loader here. HistoryViewModel's behavior is untouched.
 *
 * Player navigation is by `sessionId` (+ `side` for P+L): the player route
 * resolves the vault artifact through
 * [com.aritr.rova.ui.screens.player.PlayerUriResolver], which reads
 * `vaultFilePath` and tags it with the FileProvider vault scheme.
 */
class VaultViewModel(application: Application) : AndroidViewModel(application) {

    private val _items = MutableStateFlow<List<VideoItem>>(emptyList())
    val items: StateFlow<List<VideoItem>> = _items.asStateFlow()

    // Same thumbnail/resolution cache shape as HistoryViewModel. Keyed on the
    // stable per-row key (the vault file's absolute path).
    private val metadataCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Bitmap?, String>>()

    /** A vault artifact resolved off a manifest, before metadata extraction. */
    private data class VaultRecording(
        val file: File,
        val sessionId: String,
        val side: VideoSide?,
    ) {
        val stableKey: String get() = file.absolutePath
        fun lastModified(): Long = file.lastModified()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _items.value = loadItemsList()
        }
    }

    private suspend fun loadItemsList(): List<VideoItem> = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        val sessionStore = (app as? RovaApp)?.let { runCatching { it.sessionStore }.getOrNull() }
            ?: return@withContext emptyList()

        val recordings = vaultRecordings(sessionStore)
            .sortedByDescending { it.lastModified() }

        val anyMissing = recordings.any { !metadataCache.containsKey(it.stableKey) }
        if (anyMissing) {
            HistoryMetadataLoader.fillMissing(
                paths = recordings.map { it.stableKey },
                cache = metadataCache,
                extract = { key -> VideoMetadataUtils.extractMetadata(app, key) }
            )
        }

        val items = recordings.map { rec -> buildItem(rec) }

        // Evict cache entries for rows that are no longer present.
        val currentKeys = recordings.map { it.stableKey }.toSet()
        metadataCache.keys.retainAll(currentKeys)

        items
    }

    private fun buildItem(rec: VaultRecording): VideoItem {
        val cached = metadataCache[rec.stableKey]
        return VideoItem(
            file = rec.file,
            thumbnail = cached?.first,
            resolution = cached?.second ?: VideoMetadataUtils.UNKNOWN_RESOLUTION,
            // Vault artifacts are app-private; share is intentionally not
            // offered from the vault list (sharing copies out of the vault —
            // spec O5). No share URI here.
            shareUri = null,
            sessionId = rec.sessionId,
            side = rec.side,
        )
    }

    /**
     * Lists manifests and emits one row per vault-visible artifact. Single-mode
     * sessions resolve [SessionManifest.vaultFilePath]; P+L sessions fan out to
     * the per-side portrait/landscape vault paths (0/1/2 rows). Rows whose vault
     * file is missing or zero-length are dropped (mirrors the History
     * exists()+length() gate).
     */
    private fun vaultRecordings(sessionStore: SessionStore): List<VaultRecording> {
        val manifests: List<SessionManifest> = sessionStore.listSessionIds()
            .mapNotNull { sid -> runCatching { sessionStore.loadManifest(sid) }.getOrNull() }
            // B5 / ADR-0025 (spec O1) — the vault shows VAULTED + UNVAULTING.
            .filter { isVaultVisible(it.vaultState) }

        return manifests.flatMap { m ->
            if (m.config.mode == "PortraitLandscape") {
                buildList {
                    m.portraitVaultFilePath?.let { path ->
                        add(VaultRecording(File(path), m.sessionId, VideoSide.PORTRAIT))
                    }
                    m.landscapeVaultFilePath?.let { path ->
                        add(VaultRecording(File(path), m.sessionId, VideoSide.LANDSCAPE))
                    }
                }
            } else {
                m.vaultFilePath?.let { path ->
                    listOf(VaultRecording(File(path), m.sessionId, side = null))
                } ?: emptyList()
            }
        }.filter { rec ->
            runCatching { rec.file.exists() && rec.file.length() > 0L }.getOrDefault(false)
        }
    }

    override fun onCleared() {
        metadataCache.values.forEach { (thumbnail, _) -> thumbnail?.recycle() }
        metadataCache.clear()
        super.onCleared()
    }
}

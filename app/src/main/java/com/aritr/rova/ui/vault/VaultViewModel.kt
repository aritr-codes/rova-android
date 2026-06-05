package com.aritr.rova.ui.vault

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aritr.rova.RovaApp
import com.aritr.rova.data.SessionManifest
import com.aritr.rova.data.SessionStore
import com.aritr.rova.service.dualrecord.VideoSide
import com.aritr.rova.service.export.VaultAndroidOps
import com.aritr.rova.service.export.VaultMoverBuilder
import com.aritr.rova.utils.RovaLog
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

    /**
     * B5 / ADR-0025 (Task 22) — move-OUT. Republishes a vaulted recording
     * to public storage (recomputed tier) and resets its state to PUBLIC.
     * Auth is already satisfied — the caller is inside the unlocked vault —
     * so no additional gate here.
     *
     * Single-mode only ([VaultMoverBuilder.isSingleModeMovable]): a P+L
     * session has per-side vault pointers [VaultAndroidOps] cannot resolve.
     *
     * Ordering is guaranteed by [VaultMover.moveOut]: UNVAULTING →
     * publishExisting → PUBLIC. On success the now-redundant app-private
     * vault file is best-effort deleted (its path is captured BEFORE the
     * move clears `vaultFilePath`). A crash between the PUBLIC commit and
     * this delete leaves only a harmless app-private orphan — the public
     * copy is the source of truth and the manifest no longer references the
     * vault file.
     */
    suspend fun moveOutOfVault(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        val sessionStore = (app as? RovaApp)?.let {
            runCatching { it.sessionStore }.getOrNull()
        } ?: return@withContext false
        val manifest = runCatching { sessionStore.loadManifest(sessionId) }.getOrNull()
            ?: return@withContext false
        if (!VaultMoverBuilder.isSingleModeMovable(manifest)) {
            RovaLog.w("VaultViewModel.moveOutOfVault: P+L session $sessionId not movable; skipping")
            return@withContext false
        }
        // Capture the private vault path BEFORE the move clears it, so the
        // best-effort cleanup below can unlink the now-redundant file.
        val vaultFilePath = manifest.vaultFilePath
        val sessionDir = sessionStore.sessionDir(sessionId)
        val outcomeHolder = arrayOfNulls<VaultAndroidOps.PublishOutcome>(1)
        val mover = VaultMoverBuilder.buildMoveOut(
            context = app,
            sessionStore = sessionStore,
            manifest = manifest,
            sessionDir = sessionDir,
            outcomeHolder = outcomeHolder,
        )
        val ok = try {
            mover.moveOut(sessionId)
            true
        } catch (t: Throwable) {
            RovaLog.w("VaultViewModel.moveOutOfVault: move-out failed for $sessionId", t)
            false
        }
        if (ok && vaultFilePath != null) {
            // Best-effort: the public copy is now the source of truth. A
            // failure here leaves a harmless app-private orphan, not a
            // privacy or data-loss issue.
            try {
                File(vaultFilePath).delete()
            } catch (t: Throwable) {
                RovaLog.w("VaultViewModel.moveOutOfVault: vault-file cleanup failed for $sessionId", t)
            }
        }
        refresh()
        ok
    }

    /**
     * B5 / ADR-0025 — permanent delete from the vault. A vaulted recording's
     * only copy is its app-private vault file (move-in removed the public
     * copy), so this is irreversible — the caller MUST confirm first.
     * Deletes the whole session directory (manifest + the vault file, and any
     * per-side files for a P+L session) via [SessionStore.discardSession].
     * UI-initiated deletion is allowed (ADR-0005 only restricts the cold-launch
     * recovery sources, not a user action).
     */
    suspend fun deleteFromVault(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        val sessionStore = (app as? RovaApp)?.let { runCatching { it.sessionStore }.getOrNull() }
            ?: return@withContext false
        val ok = runCatching { sessionStore.discardSession(sessionId) }.isSuccess
        if (!ok) RovaLog.w("VaultViewModel.deleteFromVault: discard failed for $sessionId")
        refresh()
        ok
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

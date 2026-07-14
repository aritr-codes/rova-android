package com.aritr.rova.ui.screens.player

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.aritr.rova.data.ExportTier
import com.aritr.rova.data.SessionManifest
import com.aritr.rova.service.dualrecord.VideoSide
import com.aritr.rova.ui.screens.HistoryArtifactMapper
import com.aritr.rova.ui.share.safeShareUri
import com.aritr.rova.utils.RovaLog
import java.io.File

/**
 * player-sharing.html §05 — the Android seam that turns a chosen
 * [PlayerShareArtifact] into the **shareable** `content://` URI. Pure target
 * selection is [PlayerSharePlan]; this resolves it, reusing the EXISTING
 * Library pipeline exactly as §03/§05 mandate — it invents no new resolver:
 *   - [HistoryArtifactMapper.resolveArtifactFile] / `.resolveShareUri` (merged),
 *   - [HistoryArtifactMapper.resolveArtifactsPerSide] (DualShot per-side),
 *   - a MediaStore `_DATA` lookup for Tier2/3 (the same query History runs),
 *   - [safeShareUri] (content:// pass-through, else FileProvider) as the last step.
 *
 * Fail-closed (§08): any unresolvable artifact returns null; the caller surfaces
 * a calm toast and never launches a partial share. The playback `mediaUri` is
 * never touched. Vault is excluded upstream in [PlayerSharePlan] (§10), so no
 * vault path reaches here.
 */
internal object PlayerShareUriResolver {

    /**
     * Resolve every requested artifact, or fail closed. Returns the resolved
     * URIs only when **all** requested artifacts resolve (no partial send, §08);
     * an empty list means "not ready to share" → the caller toasts and stops.
     * Runs blocking I/O (the Tier2/3 `_DATA` query) — call off the main thread.
     */
    fun resolveAll(
        context: Context,
        manifest: SessionManifest,
        sessionDir: File,
        artifacts: List<PlayerShareArtifact>,
    ): List<Uri> {
        val out = ArrayList<Uri>(artifacts.size)
        for (a in artifacts) {
            val uri = resolveOne(context, manifest, sessionDir, a) ?: return emptyList()
            out += uri
        }
        return out
    }

    private fun resolveOne(
        context: Context,
        manifest: SessionManifest,
        sessionDir: File,
        artifact: PlayerShareArtifact,
    ): Uri? {
        val resolver = context.contentResolver
        return when {
            // §03 kept-raw — the segment File under the app-private session dir;
            // FileProvider mint (safeShareUri with a null content URI).
            artifact.segmentIndex != null -> {
                val seg = manifest.segments.getOrNull(artifact.segmentIndex) ?: return null
                safeShareUri(context, File(sessionDir, seg.filename), shareUri = null)
            }
            // §04 DualShot per-side.
            artifact.side != null -> {
                // SAF per-side is a content:// doc URI already — share it
                // directly. resolveArtifactsPerSide intentionally skips SAF
                // (the History pipeline needs a java.io.File); sharing does not,
                // so a per-side SAF DualShot session stays shareable per §03.
                if (manifest.exportTier == ExportTier.SAF_DESTINATION) {
                    val doc = when (artifact.side) {
                        VideoSide.PORTRAIT -> manifest.portraitSafTargetDocUri
                        VideoSide.LANDSCAPE -> manifest.landscapeSafTargetDocUri
                    } ?: return null
                    return safeShareUri(context, file = null, shareUri = Uri.parse(doc))
                }
                // Tier1/Tier2/3: resolve the side's File via the existing fanout,
                // then complete Tier2/3 with the _DATA lookup (perSide.shareUri is
                // null there).
                val perSide = HistoryArtifactMapper.resolveArtifactsPerSide(manifest) { uriStr ->
                    tier1DataFile(resolver, uriStr)
                }.firstOrNull { it.side == artifact.side } ?: return null
                val content = perSide.shareUri?.let(Uri::parse)
                    ?: queryMediaStoreUriByPath(resolver, perSide.file.absolutePath)
                safeShareUri(context, perSide.file, content)
            }
            // §03 merged single — Tier1/SAF content URI direct, Tier2/3 via _DATA.
            else -> {
                val file = HistoryArtifactMapper.resolveArtifactFile(manifest) { uriStr ->
                    tier1DataFile(resolver, uriStr)
                }
                val content = HistoryArtifactMapper.resolveShareUri(manifest)?.let(Uri::parse)
                    ?: file?.let { queryMediaStoreUriByPath(resolver, it.absolutePath) }
                safeShareUri(context, file, content)
            }
        }
    }

    /**
     * Tier 1 `_DATA` resolution — translate a MediaStore content URI to its
     * on-disk [File] (the same lookup History wires into the mapper). Returns
     * null if the row is gone.
     */
    private fun tier1DataFile(resolver: ContentResolver, uriString: String): File? =
        try {
            @Suppress("DEPRECATION")
            resolver.query(
                Uri.parse(uriString),
                arrayOf(MediaStore.Video.Media.DATA),
                null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotEmpty() }?.let(::File) else null
            }
        } catch (t: Throwable) {
            RovaLog.w("PlayerShareUriResolver: Tier1 _DATA lookup failed for $uriString", t)
            null
        }

    /**
     * Tier 2/3 `_DATA`-by-path — find the MediaStore row whose `_DATA` equals a
     * public `Movies/Rova/<name>.mp4` path and return its content URI. `_DATA`
     * is deprecated for writes on API 29+ but reads remain functional; this
     * mirrors `HistoryViewModel.queryMediaStoreUriByPath`.
     */
    private fun queryMediaStoreUriByPath(resolver: ContentResolver, path: String): Uri? =
        try {
            @Suppress("DEPRECATION")
            resolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Video.Media._ID),
                "${MediaStore.Video.Media.DATA} = ?",
                arrayOf(path),
                null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        c.getLong(0),
                    )
                } else {
                    null
                }
            }
        } catch (t: Throwable) {
            RovaLog.w("PlayerShareUriResolver: MediaStore _DATA-by-path lookup failed for $path", t)
            null
        }
}

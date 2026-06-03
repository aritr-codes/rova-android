package com.aritr.rova.service.export

import android.content.Context
import com.aritr.rova.data.SessionManifest
import com.aritr.rova.data.SessionStore
import com.aritr.rova.service.dualrecord.VideoSide
import com.aritr.rova.utils.VideoMerger
import java.io.File

/**
 * ADR-0024 — SAF recovery builder. Constructs the recovery [SafExporter]
 * for a SAF-frozen session and runs `.recover(manifest, segments)`.
 *
 * Why this lives under `service/export/` (not inline in `RovaApp`): unlike
 * the pre-Q recovery exporters — whose `mux` lambda is a `recoveryOnlyMux`
 * that just `error()`s because pre-Q recovery never re-muxes — SAF recovery
 * genuinely RE-MUXES from the authoritative on-disk segments when the
 * private temp is gone (validate-before-delete: a still-valid SAF doc is
 * finalized without re-copy; otherwise re-mux + re-publish). That re-mux is
 * a `VideoMerger.mergeSegments(...)` call, which the load-bearing
 * `checkExportPipelineSingleEntry` invariant restricts to `service/export/`.
 * Keeping the call here keeps `RovaApp` free of any `VideoMerger` reference.
 *
 * `RovaApp.buildSafRecover` stays dumb: it only chooses the tier/side and
 * passes in the Android seam (an injected [Context] for `SafAndroidOps.*`),
 * the [SessionStore], the persisted `treeUri`, and the session directory.
 */
internal object SafRecoverBuilder {

    /**
     * Build + run the SAF recovery for [manifest]. [side] is non-null for
     * a per-side P+L resume. Re-mux (when needed) uses ONLY [side]'s
     * segments via [listSegments] so a P+L re-publish cannot mix sides.
     */
    suspend fun recover(
        context: Context,
        sessionStore: SessionStore,
        manifest: SessionManifest,
        side: VideoSide?,
        treeUri: String,
        sessionDir: File
    ): RecoveryResult {
        // Prefer the provider-authoritative display name of the already
        // committed doc; fall back to a deterministic side-suffixed name so
        // a re-publish creates a sane filename (never a URI fragment).
        val existingDoc = if (side == null) manifest.safTargetDocUri
            else if (side == VideoSide.PORTRAIT) manifest.portraitSafTargetDocUri
            else manifest.landscapeSafTargetDocUri
        val displayName = existingDoc?.let { SafAndroidOps.displayNameOf(context, it) }
            ?: defaultRecoveredName(side)
        val privateTemp = File(sessionDir, "$displayName.private")

        val exporter = SafExporter(
            displayName = displayName,
            privateTempFile = privateTemp,
            setSafPrivateTemp = { p ->
                if (side == null) sessionStore.setExportSafPrivateTemp(manifest.sessionId, p)
                else sessionStore.setExportSafPrivateTempForSide(manifest.sessionId, side, p)
            },
            setSafTarget = { d ->
                if (side == null) sessionStore.setExportSafTarget(manifest.sessionId, d)
                else sessionStore.setExportSafTargetForSide(manifest.sessionId, side, d)
            },
            setFinalizedClear = {
                if (side == null) sessionStore.setExportFinalized(manifest.sessionId, clearPrivateTempPath = true)
                else sessionStore.setExportFinalizedForSide(manifest.sessionId, side, publicTargetPath = "", clearPrivateTempPath = true)
            },
            setFailed = { sessionStore.setExportFailed(manifest.sessionId) },
            incrementRetry = { sessionStore.incrementSafTransientRetry(manifest.sessionId) },
            currentRetryCount = { sessionStore.loadManifest(manifest.sessionId)?.safTransientRetryCount ?: 0 },
            // The ONLY VideoMerger mux caller in the SAF recovery path — kept
            // under service/export/ per checkExportPipelineSingleEntry inv. 2.
            mux = { _, out -> VideoMerger.mergeSegments(listSegments(sessionDir, side), out) {} },
            createDocument = { name -> SafAndroidOps.createDocument(context, treeUri, name) },
            displayNameOf = { d -> SafAndroidOps.displayNameOf(context, d) },
            copyFileToDocument = { src, d -> SafAndroidOps.copyFileToDocument(context, src, d) },
            validateDocument = { d -> SafAndroidOps.validateDocument(context, d) },
            deleteDocument = { d -> SafAndroidOps.deleteDocument(context, d) },
            // B4c — grant-only for the permanent/transient decision (a removable
            // volume can be transiently unmounted); not-writable escalates to
            // permanent via the retry budget, not on the first hiccup.
            isPermissionHeld = { SafAndroidOps.isPersistedPermissionHeld(context, treeUri) }
        )
        return exporter.recover(manifest, listSegments(sessionDir, side))
    }

    private fun defaultRecoveredName(side: VideoSide?): String = when (side) {
        VideoSide.PORTRAIT -> "Rova_recovered_portrait.mp4"
        VideoSide.LANDSCAPE -> "Rova_recovered_landscape.mp4"
        null -> "Rova_recovered.mp4"
    }

    /**
     * Enumerate this session's authoritative segments. Filenames are
     * `segment_NNNN.mp4` (single mode) or `segment_NNNN_P.mp4` /
     * `segment_NNNN_L.mp4` (P+L), per RecoveryScanner.SEGMENT_REGEX. For a
     * per-side resume, only that side's segments are returned so the re-mux
     * produces a single-side output; single-mode returns the untagged set.
     */
    private fun listSegments(dir: File, side: VideoSide?): List<File> {
        val suffix = when (side) {
            VideoSide.PORTRAIT -> "_P"
            VideoSide.LANDSCAPE -> "_L"
            null -> null
        }
        val regex = Regex("""^segment_\d{4}(_[PL])?\.mp4$""")
        return dir.listFiles { f ->
            val name = f.name
            if (!regex.matches(name)) return@listFiles false
            when (suffix) {
                null -> !name.matches(Regex("""^segment_\d{4}_[PL]\.mp4$"""))
                else -> name.matches(Regex("""^segment_\d{4}$suffix\.mp4$"""))
            }
        }?.sortedBy { it.name } ?: emptyList()
    }
}

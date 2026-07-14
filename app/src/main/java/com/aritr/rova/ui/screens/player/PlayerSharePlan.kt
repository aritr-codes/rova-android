package com.aritr.rova.ui.screens.player

import com.aritr.rova.data.ExportState
import com.aritr.rova.data.ExportTier
import com.aritr.rova.data.SessionManifest
import com.aritr.rova.data.Terminated
import com.aritr.rova.data.VaultState
import com.aritr.rova.service.dualrecord.VideoSide

/**
 * player-sharing.html (Phase 3) — the **pure** artifact-selection decision for
 * the Player Share action. Answers §03 ("which file is shared, per recording
 * kind"), §04 (the DualShot side fork), and §05 (Share-slot presence) as a
 * total function of the already-loaded [SessionManifest] — no Android, no
 * `ContentResolver`, no `FileProvider`. The URI it decides is resolved later by
 * [PlayerShareUriResolver] (the Android seam); this file only picks the target.
 *
 * The playback `mediaUri` is deliberately NOT consulted (§03 "Critical"): it is
 * a playback string that may be a `vaultfile://` / `keptsegment://` sentinel an
 * external app cannot read. Sharing selects the artifact from the manifest, the
 * same object the resolver already loaded.
 *
 * Backbone read-only: ADR-0037 (identity transported, not reconstructed — the
 * reviewed side is the transported [VideoSide]; the sibling side is a per-side
 * manifest field, not a reconstructed identity), ADR-0025 (vault categorically
 * excluded — §10), ADR-0036 (unrelated; sharing never deletes). Adds no backend.
 */
sealed interface PlayerSharePlan {

    /**
     * §05 — no shareable artifact exists, so the Share slot is **absent**
     * (present-not-disabled, never greyed). Covers: vault (§10), not-yet-
     * FINALIZED, a kept-raw index with no segment, and a DualShot session with
     * neither side finalized.
     */
    data object Unavailable : PlayerSharePlan

    /** §03 — one artifact, no fork: straight to the system chooser (`ACTION_SEND`). */
    data class Single(val artifact: PlayerShareArtifact) : PlayerSharePlan

    /**
     * §04 — a DualShot session with **both** sides finalized: the one Rova-owned
     * surface in the flow. The reviewed side ([reviewed]) is pre-selected
     * (default); [other] is the sibling; "Both" = `listOf(reviewed, other)` via
     * `ACTION_SEND_MULTIPLE`. A single-side DualShot session collapses to
     * [Single] (its option is absent, not disabled).
     */
    data class DualChoice(
        val reviewed: PlayerShareArtifact,
        val other: PlayerShareArtifact,
    ) : PlayerSharePlan {
        /** The "Both angles" target set — two files, one grant (§04). */
        val both: List<PlayerShareArtifact> get() = listOf(reviewed, other)
    }

    companion object {

        /**
         * §03/§04/§05 — pick the share target for the reviewed identity.
         *
         * @param side the transported reviewed [VideoSide] (ADR-0037); null for
         *   single-mode. Only meaningful for a DualShot session.
         * @param segmentIndex the transported kept-raw segment index; null for
         *   merged playback.
         */
        fun resolve(
            manifest: SessionManifest?,
            side: VideoSide?,
            segmentIndex: Int?,
        ): PlayerSharePlan {
            manifest ?: return Unavailable
            // §10 — vault is categorically excluded; no artifact leaves the
            // player. This is checked FIRST so no later branch can reach a
            // vaulted per-side / segment field.
            if (manifest.vaultState == VaultState.VAULTED) return Unavailable

            // §03 kept-raw row — this exact segment file (transported index).
            if (segmentIndex != null) {
                val keptOk = manifest.terminated == Terminated.MULTI_SEGMENT_KEPT &&
                    segmentIndex in manifest.segments.indices
                return if (keptOk) {
                    Single(PlayerShareArtifact(side = null, segmentIndex = segmentIndex))
                } else {
                    Unavailable
                }
            }

            // §04 DualShot fork — per-side settle is independent (Phase 6.1b
            // D12), so shareability is read per side, NOT off the shared
            // exportState.
            if (manifest.config.captureTopology == "DualShot") {
                val portraitOk = sideShareable(manifest, VideoSide.PORTRAIT)
                val landscapeOk = sideShareable(manifest, VideoSide.LANDSCAPE)
                // PORTRAIT-first default (ADR-0030 §3) when the reviewed side was
                // not transported (defensive; the Library always mints it).
                val reviewed = side ?: VideoSide.PORTRAIT
                return when {
                    portraitOk && landscapeOk -> DualChoice(
                        reviewed = PlayerShareArtifact(reviewed, segmentIndex = null),
                        other = PlayerShareArtifact(other(reviewed), segmentIndex = null),
                    )
                    portraitOk -> Single(PlayerShareArtifact(VideoSide.PORTRAIT, segmentIndex = null))
                    landscapeOk -> Single(PlayerShareArtifact(VideoSide.LANDSCAPE, segmentIndex = null))
                    else -> Unavailable
                }
            }

            // §03 merged rows — Tier1 content URI / SAF doc URI / Tier2-3 public
            // path. Gated on FINALIZED (§03 "Not yet FINALIZED → slot absent").
            return if (mergedShareable(manifest)) {
                Single(PlayerShareArtifact(side = null, segmentIndex = null))
            } else {
                Unavailable
            }
        }

        /** The opposite angle — used only to name/resolve the sibling side (§04). */
        fun other(side: VideoSide): VideoSide =
            if (side == VideoSide.PORTRAIT) VideoSide.LANDSCAPE else VideoSide.PORTRAIT

        private fun sideShareable(manifest: SessionManifest, side: VideoSide): Boolean =
            when (manifest.exportTier) {
                ExportTier.TIER1_API29_PLUS -> perSidePending(manifest, side) != null
                ExportTier.TIER2_API26_28, ExportTier.TIER3_API24_25 ->
                    perSidePublic(manifest, side) != null
                ExportTier.SAF_DESTINATION -> perSideSaf(manifest, side) != null
            }

        private fun mergedShareable(manifest: SessionManifest): Boolean {
            if (manifest.exportState != ExportState.FINALIZED) return false
            return when (manifest.exportTier) {
                ExportTier.TIER1_API29_PLUS -> manifest.pendingUri != null
                ExportTier.TIER2_API26_28, ExportTier.TIER3_API24_25 ->
                    manifest.publicTargetPath != null
                ExportTier.SAF_DESTINATION -> manifest.safTargetDocUri != null
            }
        }

        private fun perSidePending(m: SessionManifest, side: VideoSide) = when (side) {
            VideoSide.PORTRAIT -> m.portraitPendingUri
            VideoSide.LANDSCAPE -> m.landscapePendingUri
        }

        private fun perSidePublic(m: SessionManifest, side: VideoSide) = when (side) {
            VideoSide.PORTRAIT -> m.portraitPublicTargetPath
            VideoSide.LANDSCAPE -> m.landscapePublicTargetPath
        }

        private fun perSideSaf(m: SessionManifest, side: VideoSide) = when (side) {
            VideoSide.PORTRAIT -> m.portraitSafTargetDocUri
            VideoSide.LANDSCAPE -> m.landscapeSafTargetDocUri
        }
    }
}

/**
 * player-sharing.html §03/§05 — a single shareable artifact addressed the way
 * the Library already addresses it: at most one of [side] (DualShot per-side) /
 * [segmentIndex] (kept-raw) is set; both null = the merged single file. Mutually
 * exclusive by construction (the resolver never sets both), mirroring
 * ADR-0037's `PlaybackIdentity` coordinate rule.
 */
data class PlayerShareArtifact(
    val side: VideoSide?,
    val segmentIndex: Int?,
)

package com.aritr.rova.ui.screens.player

import androidx.annotation.StringRes
import com.aritr.rova.R
import com.aritr.rova.data.AudioMode
import com.aritr.rova.data.ExportState
import com.aritr.rova.data.ExportTier
import com.aritr.rova.data.SessionManifest
import com.aritr.rova.data.StopReason
import com.aritr.rova.data.Terminated
import com.aritr.rova.service.dualrecord.VideoSide

/**
 * player-info.html (Phase 4) — the **pure** recording-information model for the
 * Player Info sheet. A total function of the already-loaded [SessionManifest]
 * plus the fields the player already holds in [PlayerUiState.Ready] (§03) — no
 * Android, no file decode, no probe. [PlayerInfoModel.build] does the whole §03
 * "render from Ready + the manifest the resolver already loaded" contract; the
 * Compose layer ([PlayerInfoSheet]) only formats + localizes what this decides.
 *
 * Frozen invariants this encodes:
 *   - §04 the app owns fps/codec/bitrate NOWHERE, so they are simply absent —
 *     never a fabricated value, never an "unknown" row. Only [requestedQuality]
 *     (the picker label, honestly labelled "requested") is shown.
 *   - §05 provenance is a plain-language reading of the REAL `terminated` ×
 *     `stopReason` enums ([provenance]); an unmapped state falls back to the
 *     neutral "Recording finished."
 *   - §06 DualShot describes BOTH angles from existing per-side manifest fields —
 *     it reconstructs no identity (ADR-0037: the reviewed side is transported).
 *   - §07 kept-raw reports what was KEPT ([keptRaw] swaps the Basics labels) and
 *     an honest "Combined file · not created" row when no merged output exists
 *     ([hasMergedOutput]).
 *   - §10 vault is NOT an interruption — a vaulted session's provenance is its
 *     real `terminated` like any other; Info opens on it read-only (FLAG_SECURE
 *     is the screen's job). No vault field is fabricated here.
 *
 * Adds no backend: every value is Ready-held or a field of the manifest the
 * resolver already loaded.
 */
data class PlayerInfoModel(
    val provenance: Provenance,
    // ── Basics (§03) ──
    val startedAt: Long,
    val durationMs: Long,
    val clips: Int,
    val totalSizeBytes: Long,
    val keptRaw: Boolean,
    // ── Capture (§03) ──
    val topology: Topology,
    val orientation: Orientation,
    /** config.resolution verbatim — the REQUESTED picker label ("SD/HD/FHD/4K"), §04. */
    val requestedQuality: String,
    val audioOn: Boolean,
    val intervalSeconds: Int,
    val loopCount: Int,
    /** §07 — a merged output exists, so the "Combined file · not created" row is suppressed. */
    val hasMergedOutput: Boolean,
    // ── Angles (§06) — non-null ONLY for DualShot ──
    val angles: Angles?,
) {
    /** §05 — a plain-language reading of `terminated` (+ `stopReason`). */
    data class Provenance(
        @StringRes val bannerRes: Int,
        @StringRes val detailRes: Int?,
        /** §01 `.prov.warn` — a gently-informative tone for an interruption. */
        val warn: Boolean,
    )

    enum class Topology { SINGLE, DUAL }

    /** §03 — the orientation POLICY, not a per-clip rotation. */
    enum class Orientation { PORTRAIT, LANDSCAPE, FOLLOW }

    /**
     * §06 — the two angles of a DualShot recording. Sizes are summed per
     * [VideoSide] from the interleaved segment list; [reviewedSide] is the
     * transported identity (ADR-0037). A side with no finalized artifact shows
     * the honest "not finalized" state ([portraitFinalized] / [landscapeFinalized]).
     */
    data class Angles(
        val reviewedSide: VideoSide,
        val portraitBytes: Long,
        val landscapeBytes: Long,
        val portraitFinalized: Boolean,
        val landscapeFinalized: Boolean,
    )

    companion object {

        /**
         * §03 — build the info model from the already-loaded manifest + the
         * player's Ready fields. `reviewedSide` is the transported [VideoSide]
         * (null for single-mode).
         */
        fun build(
            manifest: SessionManifest,
            ready: PlayerUiState.Ready,
            reviewedSide: VideoSide?,
        ): PlayerInfoModel {
            val dual = manifest.config.captureTopology == "DualShot"
            val keptRaw = manifest.terminated == Terminated.MULTI_SEGMENT_KEPT
            return PlayerInfoModel(
                provenance = provenanceOf(manifest.terminated, manifest.stopReason),
                startedAt = ready.startedAt,
                durationMs = ready.totalDurationFromSegmentsMs,
                clips = ready.totalClips,
                totalSizeBytes = manifest.segments.sumOf { it.sizeBytes },
                keptRaw = keptRaw,
                topology = if (dual) Topology.DUAL else Topology.SINGLE,
                orientation = orientationOf(manifest),
                requestedQuality = manifest.config.resolution,
                audioOn = manifest.audioMode == AudioMode.VIDEO_AUDIO,
                intervalSeconds = manifest.config.intervalSeconds,
                loopCount = manifest.config.loopCount,
                hasMergedOutput = hasMergedOutput(manifest),
                angles = if (dual) anglesOf(manifest, reviewedSide) else null,
            )
        }

        /**
         * §05 — the provenance mapping. Every branch maps a REAL [Terminated] ×
         * [StopReason] combination to plain language; `null`/unmapped falls back
         * to the neutral "Recording finished." (§05 frozen).
         */
        fun provenanceOf(terminated: Terminated?, stopReason: StopReason): Provenance =
            when (terminated) {
                Terminated.COMPLETED -> Provenance(
                    R.string.player_info_prov_completed,
                    R.string.player_info_detail_completed,
                    warn = false,
                )
                Terminated.USER_STOPPED -> Provenance(
                    R.string.player_info_prov_user_stopped,
                    R.string.player_info_detail_user,
                    warn = false,
                )
                Terminated.KILLED_BY_SYSTEM -> Provenance(
                    R.string.player_info_prov_system,
                    systemReasonDetail(stopReason),
                    warn = true,
                )
                Terminated.KILLED_FORCE_STOP -> Provenance(
                    R.string.player_info_prov_force_stop,
                    R.string.player_info_detail_force_stop,
                    warn = true,
                )
                Terminated.MULTI_SEGMENT_KEPT -> Provenance(
                    R.string.player_info_prov_kept,
                    R.string.player_info_detail_kept,
                    warn = true,
                )
                // §05 — the player never opens a live session; an unmapped/null
                // terminal degrades to the neutral fallback, never an invented state.
                null -> Provenance(R.string.player_info_prov_finished, null, warn = false)
            }

        /** §05 StopReason → detail line for a system stop (NONE = no detail). */
        @StringRes
        private fun systemReasonDetail(stopReason: StopReason): Int? = when (stopReason) {
            StopReason.LOW_STORAGE -> R.string.player_info_reason_low_storage
            StopReason.THERMAL -> R.string.player_info_reason_thermal
            StopReason.PERMISSION_REVOKED -> R.string.player_info_reason_permission
            StopReason.INIT_FAILED -> R.string.player_info_reason_init_failed
            StopReason.SCHEDULE_WINDOW -> R.string.player_info_reason_schedule
            StopReason.USER, StopReason.NONE -> null
        }

        /**
         * §03 — the orientation POLICY. `FollowDevice` → FOLLOW; `Lock` resolves
         * to Portrait/Landscape from the locked Surface rotation (0/180 = portrait,
         * 90/270 = landscape on a portrait-natural phone). An unset lock degrades
         * to FOLLOW rather than guessing.
         */
        private fun orientationOf(manifest: SessionManifest): Orientation {
            if (manifest.config.orientationPolicy != "Lock") return Orientation.FOLLOW
            return when (manifest.config.orientationLockRotation) {
                0, 2 -> Orientation.PORTRAIT
                1, 3 -> Orientation.LANDSCAPE
                else -> Orientation.FOLLOW
            }
        }

        /**
         * §07 — a merged output exists iff export FINALIZED and the tier's merged
         * pointer is present (mirrors [PlayerSharePlan.mergedShareable] without the
         * share concern). Kept-raw / mid-mux → false → the "not created" row shows.
         */
        private fun hasMergedOutput(manifest: SessionManifest): Boolean {
            if (manifest.exportState != ExportState.FINALIZED) return false
            return when (manifest.exportTier) {
                ExportTier.TIER1_API29_PLUS -> manifest.pendingUri != null
                ExportTier.TIER2_API26_28, ExportTier.TIER3_API24_25 -> manifest.publicTargetPath != null
                ExportTier.SAF_DESTINATION -> manifest.safTargetDocUri != null
            }
        }

        /**
         * §06 — per-side sizes + finalized state. A side is "finalized" if any
         * per-side artifact pointer is present (pending / public / SAF / vault —
         * vault included because §10 makes a vaulted side a normal finalized
         * recording Info still describes). The reviewed side is transported
         * (PORTRAIT-first default when not passed, ADR-0030 §3).
         */
        private fun anglesOf(manifest: SessionManifest, reviewedSide: VideoSide?): Angles {
            val portraitBytes = manifest.segments
                .filter { it.side == VideoSide.PORTRAIT }.sumOf { it.sizeBytes }
            val landscapeBytes = manifest.segments
                .filter { it.side == VideoSide.LANDSCAPE }.sumOf { it.sizeBytes }
            return Angles(
                reviewedSide = reviewedSide ?: VideoSide.PORTRAIT,
                portraitBytes = portraitBytes,
                landscapeBytes = landscapeBytes,
                portraitFinalized = sideFinalized(manifest, VideoSide.PORTRAIT),
                landscapeFinalized = sideFinalized(manifest, VideoSide.LANDSCAPE),
            )
        }

        private fun sideFinalized(manifest: SessionManifest, side: VideoSide): Boolean {
            val pointers = when (side) {
                VideoSide.PORTRAIT -> listOf(
                    manifest.portraitPendingUri,
                    manifest.portraitPublicTargetPath,
                    manifest.portraitSafTargetDocUri,
                    manifest.portraitVaultFilePath,
                )
                VideoSide.LANDSCAPE -> listOf(
                    manifest.landscapePendingUri,
                    manifest.landscapePublicTargetPath,
                    manifest.landscapeSafTargetDocUri,
                    manifest.landscapeVaultFilePath,
                )
            }
            return pointers.any { it != null }
        }

        /**
         * §01/§03 — a compact human duration ("1m 20s" / "40s" / "2m"), reused
         * for both a length value and the "Every Ns" interval line. Pure; no
         * locale (digits + fixed unit letters, matching the frozen specimen and
         * the existing `formatMmSs` convention).
         */
        fun humanDuration(millis: Long): String {
            val totalSec = (millis.coerceAtLeast(0L)) / 1000L
            val m = totalSec / 60L
            val s = totalSec % 60L
            return when {
                m > 0L && s > 0L -> "${m}m ${s}s"
                m > 0L -> "${m}m"
                else -> "${s}s"
            }
        }
    }
}

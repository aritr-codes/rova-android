package com.aritr.rova.ui.screens.player

import com.aritr.rova.data.ExportState
import com.aritr.rova.data.ExportTier
import com.aritr.rova.R
import com.aritr.rova.data.SessionManifest
import com.aritr.rova.data.VaultState
import com.aritr.rova.service.dualrecord.VideoSide
import com.aritr.rova.ui.text.UiText

/**
 * Phase 2.5 — pure resolver from a loaded [SessionManifest] to the
 * playable [PlayerUiState]. Extracted out of [PlayerViewModel] so the
 * tier-dispatch contract can be pinned by JVM unit tests without
 * Robolectric, ExoPlayer, or a real `ContentResolver`.
 *
 * Mirrors the manifest-driven approach already used by
 * [com.aritr.rova.ui.screens.HistoryArtifactMapper]:
 *  - Tier 1 (API 29+): the artifact lives under `Movies/Rova/<displayName>.mp4`
 *    but is owned by `MediaStore`. The canonical playback handle is
 *    `manifest.pendingUri` (a `content://` URI); ExoPlayer accepts it
 *    directly via `MediaItem.fromUri`, so we do NOT round-trip through
 *    `_DATA` lookup the way History does to obtain a `File` for sort
 *    keys. This avoids the deprecated-column path on the player code.
 *  - Tier 2 / Tier 3 (pre-Q): the artifact is at
 *    `manifest.publicTargetPath`. ExoPlayer accepts `file://` URIs.
 *
 * The screen never plays a non-finalized session: a row only reaches
 * the player from the History list, which is itself filtered to
 * [ExportState.FINALIZED]. The resolver re-asserts that gate so a
 * future caller (deep link, programmatic open) cannot bypass it.
 *
 * This object is intentionally NOT folded into [HistoryArtifactMapper]
 * because the two have divergent return shapes: History needs a
 * `File` (for `lastModified()` sort and `length()` size); the player
 * needs a URI string (consumed by `MediaItem.fromUri`). Sharing the
 * mapper would force a `File` round-trip on Tier 1 that the player
 * does not need, and would conflate the History-only `_DATA` lookup
 * with the player's URI-only path.
 */
internal object PlayerUriResolver {

    /**
     * Task 14 / ADR-0025 — sentinel scheme prefixed onto a VAULTED
     * recording's app-private file path. The resolver stays pure (no
     * `android.net.Uri` / `FileProvider`), so it cannot itself convert a
     * vault file into the required `content://` URI. Instead it tags the
     * raw path with this scheme; the Android wrapper
     * ([PlayerViewModel.attachExoPlayer]) detects the prefix, strips it,
     * and round-trips the file through
     * `FileProvider.getUriForFile(context, "${applicationId}.provider", file)`.
     *
     * Vaulted files live under the app-private `videos/` dir
     * (`getExternalFilesDir("videos")`), which `res/xml/file_paths.xml`
     * exposes via `<external-files-path name="videos" path="videos/"/>`.
     * Emitting a raw `file://` here (as Tier 2/3 do for public-storage
     * paths) would throw `FileUriExposedException` when ExoPlayer's
     * media source touches the private path — hence the FileProvider
     * detour for the vault case only.
     */
    const val VAULT_FILE_SCHEME = "vaultfile://"

    /**
     * Resolves a manifest into [PlayerUiState]. `null` manifest →
     * [PlayerUiState.Unavailable]; non-finalized export → Unavailable;
     * Tier 1 with no `pendingUri` → Unavailable; Tier 2/3 with no
     * `publicTargetPath` → Unavailable.
     *
     * On success, exposes the segment durations and the requested
     * per-clip duration (`config.durationSeconds * 1000L`) so the
     * timeline + info-row can render without re-reading the manifest.
     *
     * Phase 6.1b smoke-fix #3 — P+L per-side dispatch. For sessions
     * with `config.mode == "PortraitLandscape"`, the shared
     * `pendingUri` / `publicTargetPath` fields are NEVER populated
     * (Tier1Exporter / Tier2Exporter write to the per-side variants
     * `portraitPendingUri` / `landscapePendingUri` / `portraitPublicTargetPath`
     * / `landscapePublicTargetPath` — see ADR-0008 + Phase 6.1b T13).
     * The [side] argument is threaded from the History card click via
     * [VideoItem.side] → PlayerScreen → PlayerViewModel; it selects
     * which side's artifact to play. A `null` [side] on a P+L manifest
     * is a routing bug (the History flatMap always emits a side per
     * PerSideArtifact) — surface Unavailable rather than silently
     * falling through to the (null) shared pointers.
     *
     * Single-mode (`config.mode != "PortraitLandscape"`) ignores [side]
     * and continues to read the shared fields byte-identically to the
     * pre-smoke-fix-#3 behavior. The [side] default of `null`
     * preserves source-compat for single-mode callers that omit it.
     */
    fun resolve(manifest: SessionManifest?, side: VideoSide? = null): PlayerUiState {
        if (manifest == null) {
            return PlayerUiState.Unavailable(UiText.Str(R.string.player_unavailable_not_available))
        }
        if (manifest.exportState != ExportState.FINALIZED) {
            return PlayerUiState.Unavailable(UiText.Str(R.string.player_unavailable_not_finished))
        }
        val isPlusL = manifest.config.captureTopology == "DualShot"
        if (isPlusL && side == null) {
            // Defensive: HistoryViewModel.flatMap always supplies a
            // non-null side for P+L rows. A null here means the caller
            // forgot to thread it — fail closed rather than coin-flip.
            return PlayerUiState.Unavailable(UiText.Str(R.string.player_unavailable_file_not_found))
        }
        // Task 14 / ADR-0025 — vault precedence. A VAULTED recording
        // plays from its app-private merged file regardless of the
        // FROZEN `exportTier` (which only records how it WOULD have
        // published). This branch therefore runs BEFORE the tier `when`
        // so a vaulted Tier1/Tier2/Tier3/SAF recording never falls
        // through to its (now stale / deleted) public pointer. The path
        // is tagged with [VAULT_FILE_SCHEME] so the Android wrapper
        // converts it through FileProvider (vault files must never reach
        // ExoPlayer as a raw `file://` — FileUriExposedException). The
        // P+L null-side defensive gate already fired above (mode-based),
        // so a P+L vault manifest with null side is Unavailable already.
        val uri = if (manifest.vaultState == VaultState.VAULTED) {
            val vaultPath = if (isPlusL) {
                when (side) {
                    VideoSide.PORTRAIT -> manifest.portraitVaultFilePath
                    VideoSide.LANDSCAPE -> manifest.landscapeVaultFilePath
                    null -> null // unreachable — gated above
                }
            } else {
                manifest.vaultFilePath
            }
            vaultPath?.let { VAULT_FILE_SCHEME + it }
        } else when (manifest.exportTier) {
            ExportTier.TIER1_API29_PLUS ->
                if (isPlusL) {
                    when (side) {
                        VideoSide.PORTRAIT -> manifest.portraitPendingUri
                        VideoSide.LANDSCAPE -> manifest.landscapePendingUri
                        null -> null // unreachable — gated above
                    }
                } else {
                    manifest.pendingUri
                }
            ExportTier.TIER2_API26_28, ExportTier.TIER3_API24_25 ->
                if (isPlusL) {
                    val path = when (side) {
                        VideoSide.PORTRAIT -> manifest.portraitPublicTargetPath
                        VideoSide.LANDSCAPE -> manifest.landscapePublicTargetPath
                        null -> null // unreachable — gated above
                    }
                    path?.let { toFileUri(it) }
                } else {
                    manifest.publicTargetPath?.let { toFileUri(it) }
                }
            // ADR-0024 — the SAF doc URI is a `content://` Uri that
            // ExoPlayer accepts directly via MediaItem.fromUri (same shape
            // as Tier 1's MediaStore URI — no `_DATA`/`file://` round-trip).
            ExportTier.SAF_DESTINATION ->
                if (isPlusL) {
                    when (side) {
                        VideoSide.PORTRAIT -> manifest.portraitSafTargetDocUri
                        VideoSide.LANDSCAPE -> manifest.landscapeSafTargetDocUri
                        null -> null // unreachable — gated above
                    }
                } else {
                    manifest.safTargetDocUri
                }
        }
        if (uri.isNullOrEmpty()) {
            return PlayerUiState.Unavailable(UiText.Str(R.string.player_unavailable_file_not_found))
        }
        // Phase 6.1b smoke-fix #4 — P+L per-side segment filter. For a P+L
        // session the manifest's `segments` list interleaves both sides
        // (RovaRecordingService.handleDualVideoEvent.Finalize appends one
        // SegmentRecord per side per loop, see RovaRecordingService.kt:2027).
        // The player only renders the ONE side the user picked from the
        // History card, so the timeline must be filtered down to that
        // side's segments — otherwise a 2-loop P+L recording shows "Clip
        // 4 of 4" instead of "Clip 2 of 2" and the cell timeline has 4
        // cells where the played file only contains 2. Single-mode falls
        // through to the unfiltered list (SegmentRecord.side is null for
        // single-mode segments). P+L segments without a side tag (none
        // exist in production — the service always tags) would filter to
        // empty here and surface as "Recording incomplete" below, which
        // is the right failure mode.
        val sideSegments = if (isPlusL && side != null) {
            manifest.segments.filter { it.side == side }
        } else {
            manifest.segments
        }
        // Audit F#11 — a finalized manifest with zero segments should
        // not exist (every loop writes at least one segment), but a
        // corrupted segments JSON parse can leave the list empty. The
        // resolver previously surfaced this as Ready with a synthetic
        // single-cell timeline, then ExoPlayer would fire onPlayerError
        // on a 0-byte / missing file and flip back to Unavailable —
        // user sees a Ready→Unavailable flicker. Refuse to play
        // segment-less manifests up front instead.
        if (sideSegments.isEmpty()) {
            return PlayerUiState.Unavailable(UiText.Str(R.string.player_unavailable_incomplete))
        }
        // PR-6b (ADR-0032 / codex #2) — order by parsed filename sequence so
        // wall-starts align with footage concatenation, not async append order.
        val order = SegmentOrdering.orderedIndices(sideSegments.map { it.filename })
        val ordered = order.map { sideSegments[it] }
        val segmentDurations = ordered.map { it.durationMs }
        // PR-6b — resolve per-clip wall-starts: exact where present, else
        // synthesize chaining from the last resolved start (codex #1: preserves
        // exact clips; only synthesizes the missing ones — never all-or-nothing).
        val wallStarts = ArrayList<Long>(ordered.size)
        val approxMask = ArrayList<Boolean>(ordered.size)
        for ((i, seg) in ordered.withIndex()) {
            val stamp = seg.startedAtWallClock
            if (stamp != null) {
                wallStarts.add(stamp); approxMask.add(false)
            } else {
                val synth = if (i == 0) manifest.startedAt
                            else wallStarts[i - 1] + segmentDurations[i - 1]
                wallStarts.add(synth); approxMask.add(true)
            }
        }
        return PlayerUiState.Ready(
            mediaUri = uri,
            sessionId = manifest.sessionId,
            startedAt = manifest.startedAt,
            segmentDurationsMs = segmentDurations,
            perClipDurationMs = manifest.config.durationSeconds * 1000L,
            totalClips = segmentDurations.size,
            totalDurationFromSegmentsMs = segmentDurations.sum(),
            segmentWallStartsMs = wallStarts,
            wallStartIsApproxMask = approxMask
        )
    }

    /**
     * Encode a raw filesystem path as a `file://` URI string. We do
     * the encoding here rather than relying on `Uri.fromFile()` so
     * the resolver stays pure JVM (no `android.net.Uri` dependency,
     * which would force Robolectric on tests).
     *
     * The encoding is intentionally minimal — paths under
     * `Movies/Rova/Rova_<sid>.mp4` only contain ASCII filename-safe
     * characters by construction (the session id + display-name
     * formatter both restrict to filesystem-safe chars). Spaces are
     * still escaped for safety. ExoPlayer will not see a real-world
     * path with reserved URI characters.
     */
    private fun toFileUri(path: String): String {
        val normalized = path.replace('\\', '/')
        val withLeadingSlash = if (normalized.startsWith("/")) normalized else "/$normalized"
        return "file://" + withLeadingSlash.replace(" ", "%20")
    }
}

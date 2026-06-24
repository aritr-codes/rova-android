package com.aritr.rova.ui.screens.player

import com.aritr.rova.data.ExportState
import com.aritr.rova.data.ExportTier
import com.aritr.rova.data.SegmentRecord
import com.aritr.rova.data.SessionConfig
import com.aritr.rova.data.SessionManifest
import com.aritr.rova.data.VaultState
import com.aritr.rova.service.dualrecord.VideoSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 14 — pins the `vaultState == VAULTED` precedence branch of
 * [PlayerUriResolver]. JVM-only: the resolver is a pure function, so it
 * cannot call `FileProvider.getUriForFile` itself (that needs an Android
 * `Context`). Instead the resolver emits the vault file path under the
 * sentinel scheme [PlayerUriResolver.VAULT_FILE_SCHEME] — a discriminator
 * the Android wrapper ([PlayerViewModel.attachExoPlayer]) detects and
 * round-trips through `FileProvider` into a `content://` URI (ADR-0025
 * §Playback — vault recordings play via FileProvider content URI, never a
 * raw `file://`, to avoid `FileUriExposedException`).
 *
 * Contract pinned here:
 *  - A `VAULTED` manifest resolves to its `vaultFilePath` tagged with the
 *    vault sentinel scheme — and does so REGARDLESS of `exportTier`. The
 *    vault branch runs BEFORE the tier `when`, so a frozen `TIER1` /
 *    `TIER2` / `SAF` tier on a vaulted recording never wins.
 *  - For P+L (`config.mode == "PortraitLandscape"`) the per-side vault
 *    fields (`portraitVaultFilePath` / `landscapeVaultFilePath`) are
 *    selected by [side]; null side on a P+L vault manifest is the same
 *    defensive routing failure as the public per-side branch.
 */
class PlayerUriResolverVaultTest {

    private fun manifest(
        sessionId: String = "v1",
        exportState: ExportState = ExportState.FINALIZED,
        tier: ExportTier = ExportTier.TIER1_API29_PLUS,
        pendingUri: String? = null,
        publicTargetPath: String? = null,
        segments: List<SegmentRecord> = emptyList(),
        durationSeconds: Int = 30,
        mode: String = "Single",
        vaultState: VaultState = VaultState.VAULTED,
        vaultFilePath: String? = null,
        portraitVaultFilePath: String? = null,
        landscapeVaultFilePath: String? = null
    ) = SessionManifest(
        sessionId = sessionId,
        startedAt = 1_715_000_000_000L,
        config = SessionConfig(
            durationSeconds = durationSeconds,
            intervalSeconds = 0,
            resolution = "FHD",
            loopCount = segments.size.coerceAtLeast(1),
            captureTopology = mode
        ),
        segments = segments,
        exportTier = tier,
        exportState = exportState,
        pendingUri = pendingUri,
        publicTargetPath = publicTargetPath,
        vaultState = vaultState,
        vaultFilePath = vaultFilePath,
        portraitVaultFilePath = portraitVaultFilePath,
        landscapeVaultFilePath = landscapeVaultFilePath
    )

    private fun seg(durationMs: Long, side: VideoSide? = null) =
        SegmentRecord(
            filename = "segment_${durationMs}_${side?.name ?: "single"}.mp4",
            durationMs = durationMs,
            sizeBytes = 1L,
            sha1 = "0",
            side = side
        )

    @Test
    fun `VAULTED single-mode resolves vaultFilePath under vault sentinel scheme`() {
        val state = PlayerUriResolver.resolve(
            manifest(
                sessionId = "vault1",
                // Frozen tier is TIER1 but the recording is vaulted — the
                // vault branch must win, not the pendingUri tier branch.
                tier = ExportTier.TIER1_API29_PLUS,
                pendingUri = "content://media/should/not/be/used",
                vaultState = VaultState.VAULTED,
                vaultFilePath = "/data/user/0/com.aritr.rova/files/videos/Rova_vault1.mp4",
                segments = listOf(seg(10_000), seg(10_000))
            )
        )
        val ready = state as PlayerUiState.Ready
        assertEquals(
            PlayerUriResolver.VAULT_FILE_SCHEME + "/data/user/0/com.aritr.rova/files/videos/Rova_vault1.mp4",
            ready.mediaUri
        )
        assertEquals("vault1", ready.sessionId)
        assertEquals(2, ready.totalClips)
    }

    @Test
    fun `VAULTED precedence beats a Tier 2 file path tier`() {
        val state = PlayerUriResolver.resolve(
            manifest(
                tier = ExportTier.TIER2_API26_28,
                publicTargetPath = "/storage/Movies/Rova/Rova_stale.mp4",
                vaultState = VaultState.VAULTED,
                vaultFilePath = "/data/user/0/com.aritr.rova/files/videos/Rova_v2.mp4",
                segments = listOf(seg(5_000))
            )
        )
        val ready = state as PlayerUiState.Ready
        assertEquals(
            PlayerUriResolver.VAULT_FILE_SCHEME + "/data/user/0/com.aritr.rova/files/videos/Rova_v2.mp4",
            ready.mediaUri
        )
    }

    @Test
    fun `VAULTED with null vaultFilePath returns Unavailable`() {
        val state = PlayerUriResolver.resolve(
            manifest(
                vaultState = VaultState.VAULTED,
                vaultFilePath = null,
                segments = listOf(seg(5_000))
            )
        )
        assertTrue("expected Unavailable, got $state", state is PlayerUiState.Unavailable)
    }

    @Test
    fun `VAULTED P+L PORTRAIT resolves portraitVaultFilePath`() {
        val state = PlayerUriResolver.resolve(
            manifest(
                tier = ExportTier.TIER1_API29_PLUS,
                mode = "DualShot",
                vaultState = VaultState.VAULTED,
                portraitVaultFilePath = "/data/user/0/com.aritr.rova/files/videos/Rova_p.mp4",
                landscapeVaultFilePath = "/data/user/0/com.aritr.rova/files/videos/Rova_l.mp4",
                segments = listOf(
                    seg(10_000, VideoSide.PORTRAIT),
                    seg(10_000, VideoSide.LANDSCAPE)
                )
            ),
            side = VideoSide.PORTRAIT
        )
        val ready = state as PlayerUiState.Ready
        assertEquals(
            PlayerUriResolver.VAULT_FILE_SCHEME + "/data/user/0/com.aritr.rova/files/videos/Rova_p.mp4",
            ready.mediaUri
        )
    }

    @Test
    fun `VAULTED P+L LANDSCAPE resolves landscapeVaultFilePath`() {
        val state = PlayerUriResolver.resolve(
            manifest(
                tier = ExportTier.TIER1_API29_PLUS,
                mode = "DualShot",
                vaultState = VaultState.VAULTED,
                portraitVaultFilePath = "/data/user/0/com.aritr.rova/files/videos/Rova_p.mp4",
                landscapeVaultFilePath = "/data/user/0/com.aritr.rova/files/videos/Rova_l.mp4",
                segments = listOf(
                    seg(10_000, VideoSide.PORTRAIT),
                    seg(10_000, VideoSide.LANDSCAPE)
                )
            ),
            side = VideoSide.LANDSCAPE
        )
        val ready = state as PlayerUiState.Ready
        assertEquals(
            PlayerUriResolver.VAULT_FILE_SCHEME + "/data/user/0/com.aritr.rova/files/videos/Rova_l.mp4",
            ready.mediaUri
        )
    }

    @Test
    fun `VAULTED P+L with null side returns Unavailable (defensive routing)`() {
        val state = PlayerUriResolver.resolve(
            manifest(
                tier = ExportTier.TIER1_API29_PLUS,
                mode = "DualShot",
                vaultState = VaultState.VAULTED,
                portraitVaultFilePath = "/data/user/0/com.aritr.rova/files/videos/Rova_p.mp4",
                landscapeVaultFilePath = "/data/user/0/com.aritr.rova/files/videos/Rova_l.mp4",
                segments = listOf(seg(10_000, VideoSide.PORTRAIT))
            ),
            side = null
        )
        assertTrue("expected Unavailable, got $state", state is PlayerUiState.Unavailable)
    }

    @Test
    fun `non-VAULTED manifest ignores vault fields and uses tier`() {
        // A PUBLIC manifest that somehow carries a stray vaultFilePath
        // must NOT take the vault branch — vaultState is the gate.
        val state = PlayerUriResolver.resolve(
            manifest(
                tier = ExportTier.TIER1_API29_PLUS,
                pendingUri = "content://media/public/canonical",
                vaultState = VaultState.PUBLIC,
                vaultFilePath = "/data/user/0/com.aritr.rova/files/videos/Rova_stray.mp4",
                segments = listOf(seg(10_000))
            )
        )
        val ready = state as PlayerUiState.Ready
        assertEquals("content://media/public/canonical", ready.mediaUri)
    }
}

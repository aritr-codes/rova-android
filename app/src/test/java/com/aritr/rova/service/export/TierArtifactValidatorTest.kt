package com.aritr.rova.service.export

import com.aritr.rova.data.ExportState
import com.aritr.rova.data.ExportTier
import com.aritr.rova.data.SessionConfig
import com.aritr.rova.data.SessionManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Phase 6.1b T19 final-review remediation — pins the per-side P+L
 * branch added to the late-terminal artifact validator.
 *
 * Pre-T19, the `validateTierArtifact` lambda in
 * [com.aritr.rova.RovaApp.buildExportRecoveryRunner] read shared
 * `pendingUri` / `publicTargetPath`, both null for a P+L session per
 * the OQ-C lock (T11/T12 route writes to the per-side fields). The
 * late-terminal pass would therefore always return `false` for a P+L
 * session and leave the manifest at `terminated = null` — the artifact
 * was on disk but the terminal record never landed.
 *
 * This suite is pure-JVM: file-existence checks use [TemporaryFolder]
 * to create real files; the Tier 1 URI probe is supplied as a callback
 * (production wires it to `Tier1AndroidOps.validatePending`).
 */
class TierArtifactValidatorTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    private fun manifest(
        tier: ExportTier,
        captureTopology: String = "Single",
        pendingUri: String? = null,
        publicTargetPath: String? = null,
        portraitPendingUri: String? = null,
        portraitPublicTargetPath: String? = null,
        landscapePendingUri: String? = null,
        landscapePublicTargetPath: String? = null
    ) = SessionManifest(
        sessionId = "sid",
        startedAt = 0L,
        config = SessionConfig(30, 5, "FHD", 4, captureTopology = captureTopology),
        segments = emptyList(),
        exportTier = tier,
        exportState = ExportState.FINALIZED,
        pendingUri = pendingUri,
        publicTargetPath = publicTargetPath,
        portraitPendingUri = portraitPendingUri,
        portraitPublicTargetPath = portraitPublicTargetPath,
        landscapePendingUri = landscapePendingUri,
        landscapePublicTargetPath = landscapePublicTargetPath
    )

    private fun newFile(name: String, bytes: ByteArray = byteArrayOf(1, 2, 3)): File =
        tmp.newFile(name).also { it.writeBytes(bytes) }

    // ─── Single-mode regression (pre-T19 behavior preserved) ────────

    @Test
    fun `Tier 1 single-mode valid pendingUri returns true`() {
        val m = manifest(
            ExportTier.TIER1_API29_PLUS,
            captureTopology = "Single",
            pendingUri = "content://media/external/video/media/42"
        )
        val ok = TierArtifactValidator.isArtifactValid(m) { uri ->
            uri == "content://media/external/video/media/42"
        }
        assertTrue(ok)
    }

    @Test
    fun `Tier 1 single-mode null pendingUri returns false without invoking probe`() {
        val m = manifest(ExportTier.TIER1_API29_PLUS, captureTopology = "Single", pendingUri = null)
        var probed = false
        val ok = TierArtifactValidator.isArtifactValid(m) {
            probed = true
            true
        }
        assertFalse(ok)
        assertFalse("probe must not fire when pendingUri is null", probed)
    }

    @Test
    fun `Tier 1 single-mode probe returns false yields invalid`() {
        val m = manifest(
            ExportTier.TIER1_API29_PLUS,
            captureTopology = "Single",
            pendingUri = "content://media/external/video/media/999"
        )
        val ok = TierArtifactValidator.isArtifactValid(m) { false }
        assertFalse(ok)
    }

    @Test
    fun `Tier 2 single-mode existing non-empty file returns true`() {
        val f = newFile("rova_t2.mp4")
        val m = manifest(
            ExportTier.TIER2_API26_28,
            captureTopology = "Single",
            publicTargetPath = f.absolutePath
        )
        val ok = TierArtifactValidator.isArtifactValid(m) { error("tier1 probe must not fire for Tier 2") }
        assertTrue(ok)
    }

    @Test
    fun `Tier 2 single-mode missing file returns false`() {
        val m = manifest(
            ExportTier.TIER2_API26_28,
            captureTopology = "Single",
            publicTargetPath = "/does/not/exist.mp4"
        )
        assertFalse(TierArtifactValidator.isArtifactValid(m) { error("never") })
    }

    @Test
    fun `Tier 2 single-mode zero-byte file returns false`() {
        val f = newFile("rova_t2_empty.mp4", bytes = ByteArray(0))
        val m = manifest(
            ExportTier.TIER2_API26_28,
            captureTopology = "Single",
            publicTargetPath = f.absolutePath
        )
        assertFalse(TierArtifactValidator.isArtifactValid(m) { error("never") })
    }

    @Test
    fun `Tier 2 single-mode null path returns false`() {
        val m = manifest(ExportTier.TIER2_API26_28, captureTopology = "Single", publicTargetPath = null)
        assertFalse(TierArtifactValidator.isArtifactValid(m) { error("never") })
    }

    @Test
    fun `Tier 3 single-mode existing non-empty file returns true`() {
        val f = newFile("rova_t3.mp4")
        val m = manifest(
            ExportTier.TIER3_API24_25,
            captureTopology = "Single",
            publicTargetPath = f.absolutePath
        )
        assertTrue(TierArtifactValidator.isArtifactValid(m) { error("never") })
    }

    // ─── P+L branch (the fix) ───────────────────────────────────────

    @Test
    fun `Tier 1 P+L portrait-only valid yields valid`() {
        // Pin the regression: pre-T19 returned false because shared
        // pendingUri is null. Post-T19 must consult portraitPendingUri.
        val m = manifest(
            ExportTier.TIER1_API29_PLUS,
            captureTopology = "DualShot",
            pendingUri = null,
            publicTargetPath = null,
            portraitPendingUri = "content://media/external/video/media/100",
            landscapePendingUri = null
        )
        val seen = mutableListOf<String>()
        val ok = TierArtifactValidator.isArtifactValid(m) { uri ->
            seen += uri
            uri == "content://media/external/video/media/100"
        }
        assertTrue(ok)
        assertEquals(listOf("content://media/external/video/media/100"), seen)
    }

    @Test
    fun `Tier 1 P+L landscape-only valid yields valid`() {
        val m = manifest(
            ExportTier.TIER1_API29_PLUS,
            captureTopology = "DualShot",
            portraitPendingUri = null,
            landscapePendingUri = "content://media/external/video/media/200"
        )
        val ok = TierArtifactValidator.isArtifactValid(m) { uri ->
            uri == "content://media/external/video/media/200"
        }
        assertTrue(ok)
    }

    @Test
    fun `Tier 1 P+L both sides valid yields valid`() {
        val m = manifest(
            ExportTier.TIER1_API29_PLUS,
            captureTopology = "DualShot",
            portraitPendingUri = "content://media/external/video/media/100",
            landscapePendingUri = "content://media/external/video/media/200"
        )
        val ok = TierArtifactValidator.isArtifactValid(m) { true }
        assertTrue(ok)
    }

    @Test
    fun `Tier 1 P+L both sides invalid yields invalid`() {
        val m = manifest(
            ExportTier.TIER1_API29_PLUS,
            captureTopology = "DualShot",
            portraitPendingUri = "content://media/external/video/media/100",
            landscapePendingUri = "content://media/external/video/media/200"
        )
        val ok = TierArtifactValidator.isArtifactValid(m) { false }
        assertFalse(ok)
    }

    @Test
    fun `Tier 1 P+L both sides null yields invalid`() {
        val m = manifest(
            ExportTier.TIER1_API29_PLUS,
            captureTopology = "DualShot",
            portraitPendingUri = null,
            landscapePendingUri = null
        )
        assertFalse(TierArtifactValidator.isArtifactValid(m) { true })
    }

    @Test
    fun `Tier 2 P+L portrait-only valid yields valid`() {
        val f = newFile("rova_pl_p.mp4")
        val m = manifest(
            ExportTier.TIER2_API26_28,
            captureTopology = "DualShot",
            publicTargetPath = null,
            portraitPublicTargetPath = f.absolutePath,
            landscapePublicTargetPath = null
        )
        assertTrue(TierArtifactValidator.isArtifactValid(m) { error("never") })
    }

    @Test
    fun `Tier 2 P+L landscape-only valid yields valid`() {
        val f = newFile("rova_pl_l.mp4")
        val m = manifest(
            ExportTier.TIER2_API26_28,
            captureTopology = "DualShot",
            portraitPublicTargetPath = null,
            landscapePublicTargetPath = f.absolutePath
        )
        assertTrue(TierArtifactValidator.isArtifactValid(m) { error("never") })
    }

    @Test
    fun `Tier 2 P+L both sides missing yields invalid`() {
        val m = manifest(
            ExportTier.TIER2_API26_28,
            captureTopology = "DualShot",
            portraitPublicTargetPath = "/does/not/exist_p.mp4",
            landscapePublicTargetPath = "/does/not/exist_l.mp4"
        )
        assertFalse(TierArtifactValidator.isArtifactValid(m) { error("never") })
    }

    @Test
    fun `Tier 2 P+L both sides null yields invalid`() {
        val m = manifest(
            ExportTier.TIER2_API26_28,
            captureTopology = "DualShot",
            portraitPublicTargetPath = null,
            landscapePublicTargetPath = null
        )
        assertFalse(TierArtifactValidator.isArtifactValid(m) { error("never") })
    }

    @Test
    fun `Tier 3 P+L one-side valid yields valid`() {
        val f = newFile("rova_pl_t3.mp4")
        val m = manifest(
            ExportTier.TIER3_API24_25,
            captureTopology = "DualShot",
            portraitPublicTargetPath = f.absolutePath,
            landscapePublicTargetPath = null
        )
        assertTrue(TierArtifactValidator.isArtifactValid(m) { error("never") })
    }

    @Test
    fun `P+L manifest with shared pointers populated but mode P+L still reads per-side`() {
        // Defensive: even if a stale shared pointer is present on a P+L
        // manifest (legacy / buggy build), the P+L branch is taken and
        // the shared pointer is ignored. Pins the routing invariant.
        val m = manifest(
            ExportTier.TIER1_API29_PLUS,
            captureTopology = "DualShot",
            pendingUri = "content://media/external/video/media/SHOULD_NOT_BE_USED",
            portraitPendingUri = "content://media/external/video/media/100",
            landscapePendingUri = null
        )
        val seen = mutableListOf<String>()
        TierArtifactValidator.isArtifactValid(m) { uri ->
            seen += uri
            uri == "content://media/external/video/media/100"
        }
        assertTrue(
            "shared pendingUri must not be consulted on P+L; saw=$seen",
            seen.none { it.contains("SHOULD_NOT_BE_USED") }
        )
    }

    @Test
    fun `single-mode Landscape session uses shared pointer not per-side`() {
        // Pin the routing invariant in the other direction: a single-mode
        // Landscape manifest must NOT fall into the P+L branch even if
        // a stale `landscapePublicTargetPath` is present.
        val sharedFile = newFile("rova_ls_shared.mp4")
        val m = manifest(
            ExportTier.TIER2_API26_28,
            captureTopology = "Single",
            publicTargetPath = sharedFile.absolutePath,
            landscapePublicTargetPath = "/should/not/be/consulted.mp4"
        )
        assertTrue(TierArtifactValidator.isArtifactValid(m) { error("never") })
    }
}

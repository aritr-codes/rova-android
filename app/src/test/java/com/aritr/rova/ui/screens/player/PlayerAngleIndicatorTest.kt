package com.aritr.rova.ui.screens.player

import com.aritr.rova.service.dualrecord.VideoSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * player-dualshot.html §01/§06/§07 — pins the pure angle-indicator selector.
 * The indicator is the ONE DualShot surface that ships today; the in-player
 * switch is Future/gated (§03/§04) and has no code here to test.
 *
 * The invariants a review will attack: (1) single-mode shows no angle chrome
 * (§01); (2) kept-raw DualShot shows NO indicator — clips, not angles (§06/§07);
 * (3) the current side is the transported reviewedSide (§03/ADR-0037), never
 * reconstructed; (4) a non-finalized sibling degrades honestly (§06).
 */
class PlayerAngleIndicatorTest {

    private fun model(
        topology: PlayerInfoModel.Topology,
        keptRaw: Boolean = false,
        angles: PlayerInfoModel.Angles? = null,
    ) = PlayerInfoModel(
        provenance = PlayerInfoModel.Provenance(bannerRes = 0, detailRes = null, warn = false),
        startedAt = 0L,
        durationMs = 0L,
        clips = 0,
        totalSizeBytes = 0L,
        keptRaw = keptRaw,
        topology = topology,
        orientation = PlayerInfoModel.Orientation.FOLLOW,
        requestedQuality = "FHD",
        audioOn = true,
        intervalSeconds = 30,
        loopCount = 3,
        hasMergedOutput = true,
        angles = angles,
    )

    private fun angles(
        reviewedSide: VideoSide,
        portraitFinalized: Boolean = true,
        landscapeFinalized: Boolean = true,
    ) = PlayerInfoModel.Angles(
        reviewedSide = reviewedSide,
        portraitBytes = 0L,
        landscapeBytes = 0L,
        portraitFinalized = portraitFinalized,
        landscapeFinalized = landscapeFinalized,
    )

    @Test fun nullModel_noIndicator() {
        assertNull(PlayerAngleIndicator.from(null))
    }

    @Test fun single_noIndicator() {
        // §01 — strictly a P+L surface.
        assertNull(PlayerAngleIndicator.from(model(PlayerInfoModel.Topology.SINGLE)))
    }

    @Test fun keptRawDual_noIndicator() {
        // §06/§07 — interrupted P+L has no two finalized sides: no angle chrome.
        val m = model(
            PlayerInfoModel.Topology.DUAL,
            keptRaw = true,
            angles = angles(VideoSide.PORTRAIT),
        )
        assertNull(PlayerAngleIndicator.from(m))
    }

    @Test fun dualBothFinalized_portrait_isOneOfTwo() {
        val s = PlayerAngleIndicator.from(
            model(PlayerInfoModel.Topology.DUAL, angles = angles(VideoSide.PORTRAIT))
        )!!
        assertEquals(VideoSide.PORTRAIT, s.currentSide)
        assertEquals(1, s.position)
        assertEquals(VideoSide.LANDSCAPE, s.siblingSide)
        assertTrue(s.siblingFinalized)
    }

    @Test fun dualBothFinalized_landscape_isTwoOfTwo() {
        val s = PlayerAngleIndicator.from(
            model(PlayerInfoModel.Topology.DUAL, angles = angles(VideoSide.LANDSCAPE))
        )!!
        assertEquals(VideoSide.LANDSCAPE, s.currentSide)
        assertEquals(2, s.position)
        assertEquals(VideoSide.PORTRAIT, s.siblingSide)
        assertTrue(s.siblingFinalized)
    }

    @Test fun dualReviewingPortrait_landscapeMissing_isOnlyForm() {
        // §06 — the sibling didn't finish; degrade to the honest "only" state.
        val s = PlayerAngleIndicator.from(
            model(
                PlayerInfoModel.Topology.DUAL,
                angles = angles(VideoSide.PORTRAIT, landscapeFinalized = false),
            )
        )!!
        assertEquals(VideoSide.PORTRAIT, s.currentSide)
        assertFalse(s.siblingFinalized)
        assertEquals(VideoSide.LANDSCAPE, s.siblingSide)
    }

    @Test fun dualReviewingLandscape_portraitMissing_isOnlyForm() {
        val s = PlayerAngleIndicator.from(
            model(
                PlayerInfoModel.Topology.DUAL,
                angles = angles(VideoSide.LANDSCAPE, portraitFinalized = false),
            )
        )!!
        assertEquals(VideoSide.LANDSCAPE, s.currentSide)
        assertFalse(s.siblingFinalized)
        assertEquals(VideoSide.PORTRAIT, s.siblingSide)
    }

    @Test fun dualMissingAngles_noIndicator() {
        // Defensive: DUAL topology but a null angles block → no indicator.
        assertNull(PlayerAngleIndicator.from(model(PlayerInfoModel.Topology.DUAL, angles = null)))
    }
}

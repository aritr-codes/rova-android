package com.aritr.rova.ui.screens.player

import com.aritr.rova.R
import com.aritr.rova.ui.text.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2.5 hardening — pins the F#R1 invariant:
 * `PlayerStateEmitter.emit(resolved, attach)` must surface a thrown
 * `attach` as [PlayerUiState.Unavailable] instead of wedging the
 * caller on [PlayerUiState.Loading]. JVM-only — the helper is a pure
 * function over the resolved state and an attach lambda; the actual
 * `attachExoPlayer` body needs an Android [android.content.Context]
 * and is exercised on-device, not here.
 *
 * The other three cases (Loading pass-through, Unavailable
 * pass-through, Ready + successful attach) are pinned as regression
 * guards so a future refactor of the helper cannot silently break the
 * F#9 ordering contract documented in [PlayerViewModel].
 */
class PlayerStateEmitterTest {

    private fun ready(uri: String = "file:///tmp/x.mp4") = PlayerUiState.Ready(
        mediaUri = uri,
        sessionId = "s",
        startedAt = 0L,
        segmentDurationsMs = listOf(1000L),
        perClipDurationMs = 1000L,
        totalClips = 1,
        totalDurationFromSegmentsMs = 1000L,
        segmentWallStartsMs = listOf(0L),
        wallStartIsApproxMask = listOf(true)
    )

    @Test
    fun `Loading passes through unchanged and attach is never invoked`() {
        var attachCalls = 0
        val out = PlayerStateEmitter.emit(PlayerUiState.Loading) { attachCalls++ }
        assertTrue("expected Loading, got $out", out is PlayerUiState.Loading)
        assertEquals(0, attachCalls)
    }

    @Test
    fun `Unavailable passes through unchanged and attach is never invoked`() {
        var attachCalls = 0
        val input = PlayerUiState.Unavailable(UiText.Str(R.string.player_unavailable_incomplete))
        val out = PlayerStateEmitter.emit(input) { attachCalls++ }
        assertEquals(input, out)
        assertEquals(0, attachCalls)
    }

    @Test
    fun `Ready with successful attach returns Ready and forwards mediaUri to attach`() {
        var seen: String? = null
        val input = ready(uri = "content://media/external/video/42")
        val out = PlayerStateEmitter.emit(input) { uri -> seen = uri }
        assertEquals(input, out)
        assertEquals("content://media/external/video/42", seen)
    }

    @Test
    fun `F#R1 — Ready with throwing attach returns Unavailable instead of wedging on Loading`() {
        val input = ready()
        val out = PlayerStateEmitter.emit(input) {
            throw IllegalStateException("simulated ExoPlayer init failure")
        }
        assertTrue("expected Unavailable, got $out", out is PlayerUiState.Unavailable)
        assertEquals(
            UiText.Str(R.string.player_unavailable_init_failed),
            (out as PlayerUiState.Unavailable).reason
        )
    }
}

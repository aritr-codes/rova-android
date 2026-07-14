package com.aritr.rova.ui.screens.player

import com.aritr.rova.R
import com.aritr.rova.ui.screens.player.PlayerUnavailablePresentation.Action
import com.aritr.rova.ui.screens.player.PlayerUnavailablePresentation.Glyph
import com.aritr.rova.ui.text.UiText
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 2A (player-states.html §05) — pins the Unavailable presentation
 * classifier: the string-keyed decision that turns a severity-free reason
 * into {Retry|Back} × glyph archetype × detail line. Pure / JVM-only (R
 * ids compare by identity, so the tests hold regardless of the generated
 * int values). Mirrors `MergeFailureReasonTest` (M9).
 *
 * The load-bearing invariant is the transient/permanent split — a
 * mutation that flips e.g. `not_finished` to DISMISS (dropping its Retry)
 * or `file_not_found` to RETRY (offering a retry we can't honor) must turn
 * this suite red.
 */
class PlayerUnavailablePresentationTest {

    @Test
    fun `not_finished is transient — Retry, refresh glyph, its detail`() {
        val v = PlayerUnavailablePresentation.of(R.string.player_unavailable_not_finished)
        assertEquals(Action.RETRY, v.action)
        assertEquals(Glyph.RETRY, v.glyph)
        assertEquals(R.string.player_unavailable_not_finished_detail, v.detailRes)
    }

    @Test
    fun `playback_failed is transient with the runtime alert glyph`() {
        val v = PlayerUnavailablePresentation.of(R.string.player_unavailable_playback_failed)
        assertEquals(Action.RETRY, v.action)
        assertEquals(Glyph.PLAYBACK_ALERT, v.glyph)
        assertEquals(R.string.player_unavailable_playback_failed_detail, v.detailRes)
    }

    @Test
    fun `init_failed is transient — Retry, refresh glyph`() {
        val v = PlayerUnavailablePresentation.of(R.string.player_unavailable_init_failed)
        assertEquals(Action.RETRY, v.action)
        assertEquals(Glyph.RETRY, v.glyph)
        assertEquals(R.string.player_unavailable_init_failed_detail, v.detailRes)
    }

    @Test
    fun `not_available is permanent — Back only, not-found glyph`() {
        val v = PlayerUnavailablePresentation.of(R.string.player_unavailable_not_available)
        assertEquals(Action.DISMISS, v.action)
        assertEquals(Glyph.NOT_FOUND, v.glyph)
        assertEquals(R.string.player_unavailable_not_available_detail, v.detailRes)
    }

    @Test
    fun `file_not_found is permanent — never offers a Retry we can't honor`() {
        val v = PlayerUnavailablePresentation.of(R.string.player_unavailable_file_not_found)
        assertEquals(Action.DISMISS, v.action)
        assertEquals(Glyph.NOT_FOUND, v.glyph)
        assertEquals(R.string.player_unavailable_file_not_found_detail, v.detailRes)
    }

    @Test
    fun `incomplete is permanent`() {
        val v = PlayerUnavailablePresentation.of(R.string.player_unavailable_incomplete)
        assertEquals(Action.DISMISS, v.action)
        assertEquals(Glyph.NOT_FOUND, v.glyph)
        assertEquals(R.string.player_unavailable_incomplete_detail, v.detailRes)
    }

    @Test
    fun `unknown reason id fails safe to Back-only`() {
        val v = PlayerUnavailablePresentation.of(R.string.player_back) // an id the classifier doesn't model
        assertEquals(Action.DISMISS, v.action)
        assertEquals(Glyph.NOT_FOUND, v.glyph)
    }

    @Test
    fun `UiText overload reads the Str id`() {
        val v = PlayerUnavailablePresentation.of(
            UiText.Str(R.string.player_unavailable_not_finished),
        )
        assertEquals(Action.RETRY, v.action)
    }

    @Test
    fun `UiText overload falls safe for a non-Str reason`() {
        // The resolver never emits StrArgs for Unavailable, but the overload
        // must not throw — it degrades to the permanent fail-safe.
        val v = PlayerUnavailablePresentation.of(
            UiText.StrArgs(R.string.player_unavailable_not_finished, listOf("x")),
        )
        assertEquals(Action.DISMISS, v.action)
    }
}

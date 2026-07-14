package com.aritr.rova.ui.screens.player

import com.aritr.rova.service.dualrecord.VideoSide

/**
 * player-dualshot.html (Phase 5) — the **pure** selector behind the read-only
 * DualShot **angle indicator** ("which side is on screen"), the one surface this
 * spec ships today (§01 Status, §10 boundary). Derived entirely from the already-
 * built [PlayerInfoModel]; adds no backend, no VM state, no new read.
 *
 * What ships (frozen §10): the angle **indicator** + the Library-bounce interim
 * (zero player change — the Library already lists both side rows). What does NOT
 * ship: the in-player angle **switch** — it is PROPOSED/Future, gated behind the
 * RK3 ADR amendment (Library pair-transport) + owner sign-off "before any Compose
 * work" (§03/§04). The player flipping `side` itself is Rejected (§03) as a
 * sole-minter (ADR-0037) violation, so no selector is built here.
 *
 * Presence rule (§01/§06):
 *   - single-mode → no angle chrome at all (this is strictly a P+L surface).
 *   - kept-raw DualShot → NO indicator: an interrupted P+L session has no two
 *     finalized side files, so its interleaved clips are navigated as clips, not
 *     angles (§06 / §07 "No angle control is present").
 *   - finalized-side DualShot → the indicator names the on-screen angle; if the
 *     sibling side never finalized it degrades to the honest "X only · Y didn't
 *     finish" (present-not-disabled, §06) — never a fabricated frame or a dead
 *     option.
 *
 * The current side is the TRANSPORTED [PlayerInfoModel.Angles.reviewedSide]
 * (ADR-0037: transported, never reconstructed) — the same `side` the player
 * opened on.
 */
object PlayerAngleIndicator {

    /**
     * The read-only indicator state. [position] and the "of 2" count are only
     * meaningful when [siblingFinalized]; the one-sided form names the missing
     * [siblingSide] instead.
     */
    data class State(
        val currentSide: VideoSide,
        val siblingFinalized: Boolean,
    ) {
        /** PORTRAIT-first ordering (app convention): Portrait = 1, Landscape = 2. */
        val position: Int get() = if (currentSide == VideoSide.PORTRAIT) 1 else 2

        val siblingSide: VideoSide
            get() = if (currentSide == VideoSide.PORTRAIT) VideoSide.LANDSCAPE else VideoSide.PORTRAIT
    }

    /**
     * @return the indicator state for a finalized-side DualShot session, or null
     * when no angle chrome is shown (single-mode, kept-raw, or no info model).
     */
    fun from(model: PlayerInfoModel?): State? {
        if (model == null) return null
        // §01 — strictly a P+L surface; single-mode shows none of this.
        if (model.topology != PlayerInfoModel.Topology.DUAL) return null
        // §06 — kept-raw has no two finalized sides: navigated as clips, no swap.
        if (model.keptRaw) return null
        val angles = model.angles ?: return null
        val current = angles.reviewedSide
        val siblingFinalized = when (current) {
            VideoSide.PORTRAIT -> angles.landscapeFinalized
            VideoSide.LANDSCAPE -> angles.portraitFinalized
        }
        return State(currentSide = current, siblingFinalized = siblingFinalized)
    }
}

package com.aritr.rova.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp

/**
 * Player over-media chrome tokens — Family 2 (scrim + geometry) of the player
 * token registry. **Design authority:** `docs/design/player-core.html` v1.0 §02
 * (three locked families). Compose transcribes it and never diverges; a visual
 * change is made in the HTML and re-approved first.
 *
 * The player is a legitimately **dark-only, over-media** surface. Family 1
 * (over-media ink — `mediaInk` / `mediaInkDim` / `mediaInkBody`) is reused
 * VERBATIM from [RovaTrustTokens]; the player does not re-declare it. This
 * object carries only the player-specific pieces Trust does not have: the two
 * localized scrim BANDS that make chrome legible over arbitrary video, the
 * control-glyph pill fill, and the segmented-timeline bar colours.
 *
 * Phase 1 (token migration) replaces the 11 hand-rolled
 * `Color.White.copy(alpha=…)` literals + `Color.Black` scrim gradients in
 * `PlayerScreen.kt` / `SegmentedTimeline.kt` with these named tokens — same
 * rendered pixels, contrast now proven centrally by [PlayerOverlayContrastTest]
 * (player-core.html §07) instead of per-literal. Values verbatim from the
 * pre-migration source (player-core.html §02 mapping table, Δ noted there).
 */
object PlayerTokens {

    // ── Scrim bands (player-core.html §02 Family 2) ──────────────────────
    // Two LOCALIZED gradient bands, never a full-frame wash (the video is the
    // hero; the middle of the frame carries no scrim). player-core.html
    // :69–:72 / :206 / :217.

    /** Top band height — player-core.html `--scrim-top-h:96px` (:71). */
    val scrimTopHeight = 96.dp

    /** Top band: black .82 → transparent. player-core.html `--scrim-top` (:69). */
    val scrimTop: Brush = Brush.verticalGradient(
        colors = listOf(Color.Black.copy(alpha = 0.82f), Color.Transparent),
    )

    /** Bottom band: transparent → black .90. player-core.html `--scrim-bottom` (:70). */
    val scrimBottom: Brush = Brush.verticalGradient(
        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.90f)),
    )

    // ── Control-glyph pill backing (player-core.html §02) ────────────────
    // The play/pause + speed pinned-pill fill. Was Color.White.copy(alpha=.10)
    // at PlayerScreen.kt:676/:704. player-core.html `--glyph-fill` (:62).
    // (`--glyph-fill-press` .16 is declared in the spec but has no consumer
    //  until the press-state motion slice — omitted here per YAGNI.)
    val glyphFill = Color.White.copy(alpha = 0.10f)

    /**
     * player-core.html §04 (:521) + player-accessibility.html §09 — the pinned
     * transport-glyph pill fill, reduce-transparency-aware. Normally the pill is
     * the translucent [glyphFill] glass over the video/band; under OS
     * reduce-transparency the over-media substrate "renders fully opaque" (§11):
     * the pill goes opaque via the SAME [RovaTrustTokens.pinContainerAlphaFor]
     * path the Trust surfaces use (alpha → 1.0), so no video shows through.
     *
     * Realised by compositing [glyphFill] over the opaque [RovaTrustTokens.pinSurface]
     * at [RovaTrustTokens.pinContainerAlphaFor]`(true)` (= 1f) — reusing the
     * existing pinned tokens, introducing NO new token. Default (signal off) is
     * byte-identical to the shipped [glyphFill] render; the pure pick is
     * unit-testable without a Compose UI test (mirrors `pinContainerAlphaFor`).
     */
    fun pinnedPillFill(reduceTransparency: Boolean): Color =
        if (reduceTransparency) {
            glyphFill.compositeOver(
                RovaTrustTokens.pinSurface.copy(
                    alpha = RovaTrustTokens.pinContainerAlphaFor(true),
                ),
            )
        } else {
            glyphFill
        }

    // ── Segmented timeline bar (player-core.html §02 Family 2) ───────────
    // Verbatim from SegmentedTimeline.kt (the spec's own source, Δ0). The bar
    // is the spine: track .18 / fill .90 (1.0 while scrubbing) / boundary tick
    // .40. player-core.html `--bar-*` (:82–:86).
    val barTrack = Color.White.copy(alpha = 0.18f)
    val barFill = Color.White.copy(alpha = 0.90f)
    val barFillScrub = Color.White.copy(alpha = 1.0f)
    val barTick = Color.White.copy(alpha = 0.40f)

    // ── State system (player-states.html §01/§02) ───────────────────────
    // States LAYERS ON core: it adds NO new colour family, only the loading
    // spinner + runtime-flip dim + a few state geometries. Timing constants
    // (grace 400 / spinner-period 900 / resume-dwell 4000) live at the
    // PlayerScreen consumer, matching the existing PLAYER_ENTRY_POSTER_MAX_MS.

    /** Loading spinner ring diameter — player-states.html `.spinner` (:170). */
    val spinnerSize = 34.dp

    /** Spinner ring stroke — player-states.html `.spinner{border:2.5px}` (:171). */
    val spinnerStrokeWidth = 2.5.dp

    /** Spinner track ring — `border:2.5px solid rgba(255,255,255,.16)` (:171). */
    val spinnerTrack = Color.White.copy(alpha = 0.16f)

    /** Spinner head arc (the rotating highlight) — `border-top-color:var(--media-ink)` (:171). */
    val spinnerHead = Color.White.copy(alpha = 0.94f) // == RovaTrustTokens.mediaInk

    /**
     * Reduce-motion spinner — a static, EVEN ring (no rotating head), so the
     * cue never conveys meaning by motion. player-states.html
     * `:root[data-rm="1"] .spinner{border-top-color:rgba(255,255,255,.55)}` (:174).
     */
    val spinnerStatic = Color.White.copy(alpha = 0.55f)

    /**
     * Runtime Ready→Unavailable flip (§06): the frozen last frame is DIMMED,
     * never blacked out, so the error card reads while the moment stays
     * visible. player-states.html `.frozeUp{background:rgba(0,0,0,.34)}` (:198).
     */
    val runtimeFreezeDim = Color.Black.copy(alpha = 0.34f)

    // Unavailable card + resume pill geometry (player-states.html §02).
    /** Unavailable card max width — `--err-card-w:280px` (:72). */
    val errCardMaxWidth = 280.dp

    /** Unavailable card padding — `--err-card-pad:22px` (:73). */
    val errCardPadding = 22.dp

    /** Neutral state-glyph ring diameter — `--err-glyph:44px` (:73). */
    val errGlyphSize = 44.dp

    /** State action button min height — `.btn{min-height:44px}` (:192). */
    val stateButtonMinHeight = 44.dp

    /** Resume-cue offset from the bottom — `.resumecue{bottom:150px}` (:201). */
    val resumeCueBottomOffset = 150.dp
}

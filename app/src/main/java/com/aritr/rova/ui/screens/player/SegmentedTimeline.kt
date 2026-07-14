package com.aritr.rova.ui.screens.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.aritr.rova.R
import com.aritr.rova.ui.components.rememberReduceMotion
import com.aritr.rova.ui.theme.PlayerTokens
import com.aritr.rova.ui.theme.RovaMotion
import com.aritr.rova.ui.theme.RovaTokens
import java.util.concurrent.TimeUnit

/**
 * PR-6 Task 5 — interactive playback timeline (tap-seek + drag-scrub).
 *
 * Replaces the earlier read-only equal-weight cell strip with a single
 * **continuous, duration-proportional** bar:
 *  - a white-at-18% track,
 *  - a white-at-90% fill that tracks the flat playback position via
 *    [SegmentedTimelineMath.fractionFromPosition] (so a long clip occupies
 *    proportionally more of the bar — find-the-moment, not equal cells),
 *  - thin boundary **ticks** at the cumulative [SegmentedTimelineMath.cellWeights]
 *    offsets so segment edges stay legible.
 *
 * Colours come from [com.aritr.rova.ui.theme.PlayerTokens] (`barTrack` .18 /
 * `barFill` .90 / `barFillScrub` 1.0 / `barTick` .40) — the over-media Family 2
 * bar tokens transcribed from `docs/design/player-core.html` §02. The player is
 * a dark-only over-media surface; these are the pinned white-on-dark bar values
 * (Δ0 from the pre-migration literals), contrast proven by
 * `PlayerOverlayContrastTest`.
 *
 * Interaction (the VM owns ExoPlayer; this composable is pure-presentational
 * + callbacks — Task-1 math only, no new math, no persistence):
 *  - **tap** anywhere on the bar → [onSeek] at the tapped fraction,
 *  - **drag** → [onScrubStart] / [onScrubUpdate] (throttled, snap-to-boundary)
 *    / [onScrubEnd]. The scrub update is coalesced so it only fires when the
 *    snapped target position changes, not per pixel.
 *
 * Accessibility: ONE seekbar node on the bar wrapper —
 *  [progressBarRangeInfo] (current = positionMs over 0..total),
 *  [stateDescription] = "Segment i of n, mm:ss",
 *  [contentDescription] = "Recording timeline",
 *  a [setProgress] action mapping a target position → [onSeek], and two
 *  prev/next [customActions] → [onPrevSegment] / [onNextSegment]. Tick
 *  decorations are removed from the a11y tree via [clearAndSetSemantics].
 *  A polite live-region announces the current segment, throttled to fire
 *  only when the segment index changes.
 *
 * The visual bar is intentionally thin; its touch target is widened to
 * [RovaTokens.minHitTarget] (≥24dp, WCAG 2.2 AA SC 2.5.8 / checkA11yTargetSizeToken)
 * via a transparent gesture box that centers the bar.
 */
@Composable
internal fun SegmentedTimeline(
    segmentDurationsMs: List<Long>,
    positionMs: Long,
    isScrubbing: Boolean,
    onSeek: (Long) -> Unit,
    onScrubStart: () -> Unit,
    onScrubUpdate: (Long) -> Unit,
    onScrubEnd: (Long) -> Unit,
    onPrevSegment: () -> Unit,
    onNextSegment: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val totalMs = SegmentedTimelineMath.totalDurationMs(segmentDurationsMs)
    val totalForRange = totalMs.coerceAtLeast(1L)
    val segmentCount = segmentDurationsMs.size.coerceAtLeast(1)
    val segmentIndex = SegmentedTimelineMath.segmentAtPosition(positionMs, segmentDurationsMs)

    val timelineCd = stringResource(R.string.player_timeline_cd)
    val prevLabel = stringResource(R.string.player_prev_segment_cd)
    val nextLabel = stringResource(R.string.player_next_segment_cd)
    val stateDesc = stringResource(
        R.string.player_timeline_state,
        segmentIndex + 1,
        segmentCount,
        formatMmSs(positionMs),
    )

    // Live drag state. dragFrac feeds onDragEnd; lastEmittedMs coalesces the
    // throttled scrub updates so we only notify the VM when the snapped
    // target actually changes (per-pixel drag emits the same target).
    var dragFrac by remember { mutableFloatStateOf(0f) }
    var lastEmittedMs by remember { mutableIntStateOf(-1) }
    // Throttle the polite announcement to segment-index changes only.
    var lastAnnouncedIndex by remember { mutableIntStateOf(-1) }

    val snapWindowMs = (totalMs / 50).coerceIn(150L, 1500L)

    // player-gestures.html §04/§05 — the boundary haptic. A single
    // performHapticFeedback(CLOCK_TICK) as a scrub target snaps onto a clip
    // boundary (0:00 / a segment edge / the end). Reuses the platform haptic
    // system (View.performHapticFeedback — the spec's named `--haptic-tick`,
    // no new dependency, no new abstraction); the OS gates it on the system
    // haptics setting automatically. Deliberately NOT gated on
    // rememberReduceMotion: §11 is explicit that the boundary haptic "is not
    // motion — it stays, gated separately by system haptics settings" (this is
    // the authoritative reduced-motion clause; §04's looser "reduced-motion …
    // respected" is reconciled to it — the haptic survives reduced motion).
    val view = LocalView.current

    Box(
        modifier = modifier
            .fillMaxWidth()
            // Touch target ≥24dp (SC 2.5.8); the bar itself stays thin and
            // vertically centered inside this transparent gesture box
            // (contentAlignment = Center, below).
            .heightIn(min = RovaTokens.minHitTarget)
            .pointerInput(segmentDurationsMs) {
                detectTapGestures { off ->
                    val frac = (off.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    onSeek(SegmentedTimelineMath.positionFromFraction(frac, segmentDurationsMs))
                }
            }
            .pointerInput(segmentDurationsMs) {
                detectDragGestures(
                    onDragStart = {
                        lastEmittedMs = -1
                        onScrubStart()
                    },
                    onDragEnd = {
                        onScrubEnd(
                            SegmentedTimelineMath.positionFromFraction(dragFrac, segmentDurationsMs)
                        )
                    },
                    onDragCancel = {
                        onScrubEnd(
                            SegmentedTimelineMath.positionFromFraction(dragFrac, segmentDurationsMs)
                        )
                    },
                ) { change, _ ->
                    change.consume()
                    dragFrac = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    val raw = SegmentedTimelineMath.positionFromFraction(dragFrac, segmentDurationsMs)
                    val snapped = SegmentedTimelineMath.snapIfNear(
                        raw,
                        segmentDurationsMs,
                        snapWindowMs = snapWindowMs,
                    )
                    // Coalesce: only notify on a real change.
                    if (snapped.toInt() != lastEmittedMs) {
                        lastEmittedMs = snapped.toInt()
                        onScrubUpdate(snapped)
                        // §04/§05 — one CLOCK_TICK per fresh landing on a clip
                        // boundary. Coalescing above guarantees at-most-once per
                        // boundary crossing (a drag pinned inside the snap window
                        // re-emits the same value and is filtered here).
                        if (SegmentedTimelineMath.isOnBoundary(snapped, segmentDurationsMs)) {
                            view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                        }
                    }
                }
            }
            .semantics {
                contentDescription = timelineCd
                stateDescription = stateDesc
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = positionMs.toFloat().coerceIn(0f, totalForRange.toFloat()),
                    range = 0f..totalForRange.toFloat(),
                    steps = 0,
                )
                setProgress { target ->
                    onSeek(target.toLong().coerceIn(0L, totalMs))
                    true
                }
                customActions = listOf(
                    CustomAccessibilityAction(prevLabel) { onPrevSegment(); true },
                    CustomAccessibilityAction(nextLabel) { onNextSegment(); true },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        // ── Visual bar (decorative; excluded from the a11y tree) ──────────
        // The fill is the only thing that animates. The TRUE [positionMs]
        // already drives progressBarRangeInfo / stateDescription / ticks and
        // [onSeek] above — this glide is purely cosmetic. Reduced-motion (WCAG
        // 2.2 AA SC 2.3.3, ADR-0020 / checkA11yAnimationGated) and active
        // scrubbing both snap() so the fill tracks the finger / honors the
        // user's no-motion preference; otherwise it eases to the new target.
        val reduce = rememberReduceMotion()
        val targetFrac = SegmentedTimelineMath
            .fractionFromPosition(positionMs, segmentDurationsMs)
            .coerceIn(0f, 1f)
        val fillFraction by animateFloatAsState(
            targetValue = targetFrac,
            animationSpec = if (reduce || isScrubbing) snap() else RovaMotion.containerSpring(),
            label = "playheadGlide",
        )
        val weights = remember(segmentDurationsMs) {
            SegmentedTimelineMath.cellWeights(segmentDurationsMs)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .clearAndSetSemantics {}
                .background(PlayerTokens.barTrack)
        ) {
            // Playback fill — duration-proportional over the whole bar.
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fillFraction)
                    .background(if (isScrubbing) PlayerTokens.barFillScrub else PlayerTokens.barFill)
            )
            // Boundary ticks at cumulative cell-weight offsets (skip the
            // leading 0 and trailing 1 — those are the bar ends).
            var acc = 0f
            for (i in 0 until weights.size - 1) {
                acc += weights[i]
                val at = acc.coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width1px(at)
                        .background(PlayerTokens.barTick)
                )
            }
        }

        // ── Polite live-region: throttled to segment-index changes ────────
        // Only publish a (changed) contentDescription when the segment index
        // moves; on the unchanged frames we re-publish the SAME string, which
        // the platform live-region coalesces (TalkBack announces a region
        // only when its text changes). Tracking lastAnnouncedIndex keeps the
        // recomposition stable and documents the intended throttle.
        val announce = stringResource(
            R.string.player_timeline_segment_playing,
            segmentIndex + 1,
            segmentCount,
        )
        if (segmentIndex != lastAnnouncedIndex) {
            lastAnnouncedIndex = segmentIndex
        }
        Box(
            Modifier
                .height(1.dp)
                .clearAndSetSemantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = announce
                }
        )
    }
}

/**
 * Places a 1px-wide tick at horizontal fraction [at] of the parent bar.
 * Uses a layout modifier so the tick is positioned by fraction without an
 * extra Row/Spacer scaffold; width is a hairline (1px) divider.
 */
private fun Modifier.width1px(at: Float): Modifier = this.layout { measurable, constraints ->
    val tickPx = 1
    val placeable = measurable.measure(
        constraints.copy(minWidth = tickPx, maxWidth = tickPx)
    )
    val x = ((constraints.maxWidth - tickPx) * at).toInt().coerceIn(0, constraints.maxWidth - tickPx)
    layout(constraints.maxWidth, placeable.height) {
        placeable.place(x, 0)
    }
}

/** Local mm:ss formatter (PlayerScreen's is file-private). */
private fun formatMmSs(millis: Long): String {
    val total = millis.coerceAtLeast(0L)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(total)
    val seconds = TimeUnit.MILLISECONDS.toSeconds(total) % 60
    return "%d:%02d".format(minutes, seconds)
}

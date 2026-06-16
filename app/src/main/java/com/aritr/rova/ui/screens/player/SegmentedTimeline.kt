package com.aritr.rova.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aritr.rova.R

/**
 * Phase 2.5 — segmented clip-timeline strip (read-only) used at the
 * bottom of the in-app player. Renders one cell per session segment
 * per `04-video-player.html` lines 188–217:
 *  - **Done** : full white-at-55% bar
 *  - **Current** : white-at-18% backdrop with a white-at-90% fill that
 *    proportionally tracks playback position inside this segment
 *  - **Upcoming** : white-at-18% backdrop, no fill
 *
 * 3 dp height, 3 dp inter-cell gap, 2 dp inner-cell radius — matches
 * mockup `.clip-seg`. Colors stay literal `Color.White.copy(alpha = ...)`
 * because the mockup explicitly uses white-with-alpha against the dark
 * camera-overlay backdrop, regardless of M3 theme; piping through
 * `colorScheme.onSurface` would render incorrectly under light theme,
 * which the player does not support (dark-only per UI_DESIGN_TOKENS §1).
 *
 * Position is consumed via [SegmentedTimelineMath.compute] so the
 * visual state is fully derived from the manifest + playback position
 * — no internal state, no recomposition leakage.
 */
@Composable
internal fun SegmentedTimeline(
    segmentDurationsMs: List<Long>,
    positionMs: Long,
    modifier: Modifier = Modifier
) {
    val state = SegmentedTimelineMath.compute(segmentDurationsMs, positionMs)
    val totalDurationMs = segmentDurationsMs.sum().coerceAtLeast(1L)
    val timelineCd = stringResource(R.string.player_timeline_cd)
    val recordedTmpl = stringResource(R.string.player_timeline_segment_recorded)
    val playingTmpl = stringResource(R.string.player_timeline_segment_playing)
    val upcomingTmpl = stringResource(R.string.player_timeline_segment_upcoming)
    val total = state.totalClips
    Box(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .semantics {
                    isTraversalGroup = true
                    contentDescription = timelineCd
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        current = positionMs.toFloat().coerceIn(0f, totalDurationMs.toFloat()),
                        range = 0f..totalDurationMs.toFloat(),
                        steps = 0,
                    )
                }
        ) {
            state.cells.forEachIndexed { index, cell ->
                val cellLabel = when (cell) {
                    is SegmentedTimelineMath.Cell.Done -> recordedTmpl.format(index + 1, total)
                    is SegmentedTimelineMath.Cell.Current -> playingTmpl.format(index + 1, total)
                    is SegmentedTimelineMath.Cell.Upcoming -> upcomingTmpl.format(index + 1, total)
                }
                val cellModifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .semantics { contentDescription = cellLabel }
                when (cell) {
                    is SegmentedTimelineMath.Cell.Done -> {
                        Box(modifier = cellModifier.background(Color.White.copy(alpha = 0.55f)))
                    }
                    is SegmentedTimelineMath.Cell.Upcoming -> {
                        Box(modifier = cellModifier.background(Color.White.copy(alpha = 0.18f)))
                    }
                    is SegmentedTimelineMath.Cell.Current -> {
                        Box(modifier = cellModifier.background(Color.White.copy(alpha = 0.18f))) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(cell.fillFraction.coerceIn(0f, 1f))
                                    .background(Color.White.copy(alpha = 0.90f))
                            )
                        }
                    }
                }
                if (index != state.cells.lastIndex) {
                    Box(modifier = Modifier.padding(horizontal = 1.5.dp))
                }
            }
        }
        val clipAnnounce = stringResource(R.string.player_clip_n_of_m, state.currentClipIndex, state.totalClips)
        Box(
            Modifier.size(1.dp).semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = clipAnnounce
            }
        )
    }
}

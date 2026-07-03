package com.aritr.rova.ui.library.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.aritr.rova.ui.components.rememberReduceMotion
import com.aritr.rova.ui.library.ScrubberIndex
import com.aritr.rova.ui.library.ScrubberSegment
import com.aritr.rova.ui.theme.LocalGlassEnvironment
import kotlinx.coroutines.delay

/**
 * spec §5.4 — date fast-scroll rail. Pure index math lives in [ScrubberIndex]; this owns
 * only the gesture + the lazy-scroll side effect (passed in via [onScrollToItemIndex]).
 * Scroll is INSTANT (scrollToItem), so no reduced-motion gate is required. AT: a slider
 * node (progressBarRangeInfo + setProgress) + a polite live-region announcing the day label.
 */
@Composable
fun LibraryScrubber(
    segments: List<ScrubberSegment>,
    firstVisibleItemIndex: Int,
    railLabel: String,
    onScrollToItemIndex: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (segments.size < 2) return // only useful across multiple day groups
    val n = segments.size

    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var railHeightPx by remember { mutableFloatStateOf(1f) }
    var lastScrolledSeg by remember { mutableIntStateOf(-1) }
    val density = LocalDensity.current

    // Discrete selected segment (codex: quantize so progress/announce change only on group cross).
    val selectedSeg = if (dragging) ScrubberIndex.nearestSegmentIndex(segments, dragFraction)
    else ScrubberIndex.segmentIndexForItemIndex(segments, firstVisibleItemIndex)
    val label = segments[selectedSeg].label
    val thumbFraction = selectedSeg.toFloat() / (n - 1)

    // Rest-state fade: the thumb is a scroll affordance, not a permanent ornament. It stays visible
    // while dragging and for [SCRUBBER_IDLE_MS] after the last scroll/drag, then fades out so it
    // stops drawing attention at rest. Keying on `dragging` too keeps it lit through a stationary
    // drag and only starts the idle countdown once the drag ends (codex). Reduced-motion snaps.
    var recentlyActive by remember { mutableStateOf(false) }
    LaunchedEffect(firstVisibleItemIndex, dragging) {
        recentlyActive = true
        if (!dragging) {
            delay(SCRUBBER_IDLE_MS)
            recentlyActive = false
        }
    }
    val reduceMotion = rememberReduceMotion()
    val thumbAlpha by animateFloatAsState(
        targetValue = if (recentlyActive) 1f else 0f,
        animationSpec = if (reduceMotion) snap() else tween(durationMillis = SCRUBBER_FADE_MS),
        label = "scrubberThumbAlpha",
    )
    val accent = LocalGlassEnvironment.current.palette.accent

    // Thumb + bubble geometry hoisted so the bubble (rendered before the rail) can track the
    // thumb. Pure clamp in ScrubberIndex.bubbleTopPx (JVM-tested).
    val thumbSizePx = with(density) { SCRUBBER_THUMB.toPx() }
    val thumbTopPx = (thumbFraction * (railHeightPx - thumbSizePx)).coerceAtLeast(0f)
    var bubbleHeightPx by remember { mutableFloatStateOf(0f) }

    // Coalesce drag scrolls (codex: don't launch a scroll every frame — only on segment change).
    fun scrollToSeg(seg: Int) {
        if (seg != lastScrolledSeg) {
            lastScrolledSeg = seg
            onScrollToItemIndex(segments[seg].startItemIndex)
        }
    }

    Box(modifier.fillMaxHeight().width(48.dp), contentAlignment = Alignment.TopEnd) {
        // Landed-group announce — SEPARATE polite node, NOT the slider (codex: live region must not
        // sit on the frequently-updated progress node). Announces only when `label` changes.
        Box(
            Modifier.size(1.dp).semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = label
            },
        )
        // Bubble (drag only) to the LEFT of the rail, riding the thumb (PR-C fix #3: intrinsic
        // width via unbounded wrapContentWidth — the old in-rail measure went negative and
        // wrapped one char per line; vertical offset tracks the thumb, clamped inside the rail).
        if (dragging) {
            Box(
                Modifier
                    .offset {
                        IntOffset(
                            0,
                            ScrubberIndex.bubbleTopPx(thumbTopPx, thumbSizePx, bubbleHeightPx, railHeightPx)
                                .roundToInt(),
                        )
                    }
                    .padding(end = 28.dp)
                    .wrapContentWidth(align = Alignment.End, unbounded = true)
                    .onSizeChanged { bubbleHeightPx = it.height.toFloat() }
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.inverseSurface)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(label, color = MaterialTheme.colorScheme.inverseOnSurface, style = MaterialTheme.typography.labelLarge)
            }
        }
        // Rail + thumb. Slider node: discrete progress + setProgress; NO live region here.
        Box(
            Modifier
                .fillMaxHeight()
                .width(24.dp)
                .onSizeChanged { railHeightPx = it.height.toFloat().coerceAtLeast(1f) }
                .pointerInput(segments) {
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            dragFraction = (offset.y / railHeightPx).coerceIn(0f, 1f)
                            scrollToSeg(ScrubberIndex.nearestSegmentIndex(segments, dragFraction))
                        },
                        onVerticalDrag = { change, _ ->
                            dragFraction = (change.position.y / railHeightPx).coerceIn(0f, 1f)
                            scrollToSeg(ScrubberIndex.nearestSegmentIndex(segments, dragFraction))
                        },
                        onDragEnd = { dragging = false; lastScrolledSeg = -1 },
                        onDragCancel = { dragging = false; lastScrolledSeg = -1 },
                    )
                }
                .semantics {
                    contentDescription = railLabel
                    stateDescription = label
                    // Discrete: n groups → indices 0..n-1, steps = n-2 between endpoints.
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        current = selectedSeg.toFloat(),
                        range = 0f..(n - 1).toFloat(),
                        steps = (n - 2).coerceAtLeast(0),
                    )
                    setProgress { target ->
                        val seg = target.roundToInt().coerceIn(0, n - 1)
                        lastScrolledSeg = seg // keep coalescing state consistent with the AT path
                        onScrollToItemIndex(segments[seg].startItemIndex)
                        true
                    }
                },
            contentAlignment = Alignment.TopCenter,
        ) {
            val thumbY = with(density) { thumbTopPx.toDp() }
            Box(
                Modifier
                    .padding(top = thumbY)
                    .size(SCRUBBER_THUMB)
                    .alpha(thumbAlpha)
                    .clip(CircleShape)
                    .background(accent),
            )
        }
    }
}

/** How long the scrubber thumb stays fully visible after the last scroll/drag before fading. */
private const val SCRUBBER_IDLE_MS = 1100L

/** Thumb fade-in/out duration (bypassed under reduced-motion, which snaps). */
private const val SCRUBBER_FADE_MS = 260

/** Thumb dot visual size — 16dp per owner request (PR-C); the 24dp-wide gesture rail is unchanged. */
private val SCRUBBER_THUMB = 16.dp

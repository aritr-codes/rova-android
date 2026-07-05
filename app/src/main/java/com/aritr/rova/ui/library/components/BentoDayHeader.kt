package com.aritr.rova.ui.library.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSizeIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aritr.rova.R
import com.aritr.rova.ui.components.rememberReduceMotion
import com.aritr.rova.ui.library.DayHeaderKind
import com.aritr.rova.ui.library.LibraryDateLabels
import com.aritr.rova.ui.library.rememberLibraryColors
import com.aritr.rova.ui.theme.LocalGlassEnvironment
import com.aritr.rova.ui.theme.RovaIcons
import java.util.Locale
import java.util.TimeZone

private val HEADER_HEIGHT = 37.dp
private val WASH_SOLID = 31.dp
private val WASH_TOTAL = 63.dp
private val WASH_ON_MS = 120
private val WASH_OFF_MS = 200
private val WASH_EASING = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
private val SELECT_CIRCLE_BORDER = 1.5.dp

/**
 * Ground-not-chrome day header (ADR-0030 amendment 2026-07-04 §4 / bento spec Task 5). Plain label
 * in the timeline, visually IDENTICAL pinned and unpinned — the only pinned-state difference is a
 * `drawBehind` wash of the page's own top background color ([com.aritr.rova.ui.theme.RovaPalette.bgTop])
 * that solid-holds behind the label line then dissolves 26dp past the header's own locked 37dp box
 * (over the tiles sliding beneath — Compose's sticky headers draw above subsequent list items, so
 * the overflow reads as ground carrying the label, not a bar). No blur, no border, no surface fill —
 * a filled panel here would read as "a pane of UI above your content", exactly what the ground design
 * rejects.
 *
 * [pinned] arrives as a plain parameter — Task 8 derives it synchronously from LazyList layout info
 * (`BentoWashPolicy.pinnedDayEpoch`) in the same composition pass the tiles scroll in, so the wash
 * animation below (driven off [pinned] directly, no `LaunchedEffect`/coroutine hop) can never lag a
 * render and paint unveiled over thumbnails.
 */
@Composable
fun BentoDayHeader(
    dayEpochMillis: Long,
    nowMillis: Long,
    recordingCount: Int,
    totalDurationLabel: String,
    pinned: Boolean,
    selecting: Boolean,
    allSelected: Boolean,
    onSelectDay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = rememberLibraryColors()
    val bgTop = LocalGlassEnvironment.current.palette.bgTop
    val reduceMotion = rememberReduceMotion()

    val header = remember(dayEpochMillis, nowMillis) {
        LibraryDateLabels.headerLabel(dayEpochMillis, nowMillis, Locale.getDefault(), TimeZone.getDefault())
    }
    val todayWord = stringResource(R.string.library_day_today)
    val yesterdayWord = stringResource(R.string.library_day_yesterday)
    val title = when (header.kind) {
        DayHeaderKind.TODAY -> "$todayWord · ${header.absolute}"
        DayHeaderKind.YESTERDAY -> "$yesterdayWord · ${header.absolute}"
        DayHeaderKind.WEEKDAY -> "${header.weekday} · ${header.absolute}"
        DayHeaderKind.DATE -> header.absolute
    }
    val meta = pluralStringResource(
        R.plurals.library_day_count_duration,
        recordingCount,
        recordingCount,
        totalDurationLabel,
    )

    val washAlpha by animateFloatAsState(
        targetValue = if (pinned) 1f else 0f,
        animationSpec = if (reduceMotion) {
            snap()
        } else {
            tween(durationMillis = if (pinned) WASH_ON_MS else WASH_OFF_MS, easing = WASH_EASING)
        },
        label = "dayHeaderWash",
    )

    Row(
        modifier
            .fillMaxWidth()
            .height(HEADER_HEIGHT)
            .drawBehind {
                val solidFraction = WASH_SOLID.toPx() / WASH_TOTAL.toPx()
                val brush = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to bgTop,
                        solidFraction to bgTop,
                        1f to bgTop.copy(alpha = 0f),
                    ),
                    startY = 0f,
                    endY = WASH_TOTAL.toPx(),
                )
                drawRect(brush = brush, size = Size(size.width, WASH_TOTAL.toPx()), alpha = washAlpha)
            }
            .padding(horizontal = LibraryDimens.screenPadH, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            modifier = Modifier.semantics { heading() },
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 14.sp,
            lineHeight = 21.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            meta,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.5.sp,
            lineHeight = 21.sp,
        )
        if (selecting) {
            val selectAllCd = stringResource(R.string.library_day_select_all_cd, title)
            Box(
                Modifier
                    // Final-review F4 — the parent Row is clamped to the fixed 37dp HEADER_HEIGHT,
                    // so a plain sizeIn's minHeight=48dp got shrunk back down to 37dp by that parent
                    // constraint. requiredSizeIn ignores incoming constraints and forces the full
                    // 48x48 touch target (WCAG 2.2 AA, ADR-0020); harmless overdraw above/below the
                    // 37dp row, circle visual (24dp) unchanged.
                    .requiredSizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .clickable(role = Role.Button, onClick = onSelectDay)
                    .semantics { contentDescription = selectAllCd },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(24.dp)
                        .clearAndSetSemantics {}
                        .background(if (allSelected) colors.accentFill else colors.fill2, CircleShape)
                        .then(
                            if (allSelected) {
                                Modifier
                            } else {
                                Modifier.border(SELECT_CIRCLE_BORDER, colors.hairline, CircleShape)
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (allSelected) {
                        Icon(
                            imageVector = RovaIcons.Select.outline,
                            contentDescription = null,
                            tint = colors.accentInk,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }
    }
}

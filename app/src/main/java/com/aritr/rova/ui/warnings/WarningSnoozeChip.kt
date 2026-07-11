package com.aritr.rova.ui.warnings

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.aritr.rova.R
import com.aritr.rova.ui.components.rememberReduceMotion
import com.aritr.rova.ui.theme.ResolveInk
import com.aritr.rova.ui.theme.RovaWarnings
import com.aritr.rova.ui.theme.RovaWarningsV3

/**
 * Trust System V1 — post-dismiss chip (frozen spec `docs/design/warnings-recovery.html`
 * §05, `.snooze` :321–:337). Collapsed state of a dismissed warning sheet, floating
 * pinned OVER the live viewfinder. Compose transcribes the HTML and never diverges.
 *
 * M4 migration (parity plan §7): the pill fill moves from `Color.Black @ .55` (3.61:1,
 * the spec's one outright AA failure) to the unified `pinSurface @ pinContainerAlpha`;
 * the label from White @ .78 to `mediaInk` (.94, disclosed unification); the glyph to
 * the pinned `mediaInkDim`; the dot to the `snooze` INK_SITE's resolved `dot-ink`
 * (`ResolveInk`, mark over the capsule → the raw locked severity hue, byte-identical to
 * the pre-freeze dot). The severity border (α0.25), the glyph, and the hard-block-only
 * pulse all SURVIVE verbatim (§02 migration table, :1057–:1060).
 *
 * Tap → caller restores via `WarningCenterViewModel.restore(id)`.
 */
@Composable
internal fun WarningSnoozeChip(
    id: WarningId,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content = warningSheetContent(id)
    val severityColor: Color = when (warningSurfaceFor(id)) {
        WarningSurface.HardBlockSheet -> RovaWarnings.hard
        WarningSurface.SoftSheet -> RovaWarnings.soft
        WarningSurface.AdvisorySheet -> RovaWarnings.advisory
        WarningSurface.TopBanner -> RovaWarnings.escalating
    }
    val isHardBlock = warningSurfaceFor(id) == WarningSurface.HardBlockSheet

    // `snooze` INK_SITE (frozen spec :1300–:1301): the dot is a MARK resolved over
    // the pinned capsule. The capsule is always near-black over any media frame, so
    // ResolveInk takes the LIGHTEN branch and (mark mix 0) passes the raw locked
    // severity hue through — no fixed `copy(alpha)` mix (P2). Byte-identical to the
    // pre-freeze dot; proven by TrustContrastSweepTest + WarningSnoozeChipInkTest.
    val dotInk: Color = ResolveInk.of(
        hue = severityColor,
        backing = RovaWarningsV3.pinSurface,
        target = ResolveInk.TARGET_MARK,
        top = RovaWarningsV3.mediaInk,
        mix = ResolveInk.MIX_MARK,
    ).color

    // WCAG 2.2 AA SC 2.3.3 / 2.2.2 (ADR-0020, WARN-07): no pulse under the OS
    // reduced-motion toggle — the dot holds fully visible. Pulse marks a BLOCKING
    // condition only (`isHardBlock`); an advisory chip never pulses. Verbatim.
    val reduceMotion = rememberReduceMotion()
    val dotAlpha: Float = if (isHardBlock && !reduceMotion) {
        val transition = rememberInfiniteTransition(label = "snooze-chip-pulse")
        val alpha by transition.animateFloat(
            initialValue = RovaWarningsV3.snoozeChipDotPulseAlpha,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1500),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "snooze-chip-pulse-alpha",
        )
        alpha
    } else 1f

    // Outer: the 48dp min-height IS the hit box (invisible expansion, Q5 / P5).
    // The visual capsule is the inner 34dp pill — zero extra viewfinder occlusion.
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clickable(
                onClickLabel = stringResource(R.string.warning_show_details_label),
                role = Role.Button,
            ) { onExpand() },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = RovaWarningsV3.snoozeChipPillHeight)
                .clip(RoundedCornerShape(RovaWarningsV3.snoozeChipRadius))
                .background(RovaWarningsV3.pinSurface.copy(alpha = RovaWarningsV3.pinContainerAlpha))
                .border(
                    width = 1.dp,
                    color = severityColor.copy(alpha = RovaWarningsV3.snoozeChipBorderAlpha),
                    shape = RoundedCornerShape(RovaWarningsV3.snoozeChipRadius),
                )
                .padding(horizontal = RovaWarningsV3.snoozeChipPaddingH),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RovaWarningsV3.snoozeChipGap),
        ) {
            // Dot — 7dp APPX-A shape primitive (inline dp allowed). resolved dot-ink.
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(dotInk.copy(alpha = dotAlpha)),
            )
            // Glyph — 12dp APPX-A shape primitive. Monochrome pinned over-media dim
            // ink (frozen CSS `.snooze .glyph{color:var(--media-ink-dim)}`), NOT a
            // themed tint, so it renders outside the SemanticIcon palette seam.
            Icon(
                imageVector = content.glyph.outline,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = RovaWarningsV3.mediaInkDim, // semanticicon-opt-out: pinned over-media dim ink (Trust §02), never palette-themed
            )
            Text(
                text = stringResource(content.title),
                style = MaterialTheme.typography.labelMedium,
                color = RovaWarningsV3.mediaInk,
                maxLines = 1,
            )
        }
    }
}

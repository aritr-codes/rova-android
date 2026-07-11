package com.aritr.rova.ui.warnings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aritr.rova.R
import com.aritr.rova.ui.components.SemanticIcon
import com.aritr.rova.ui.theme.IconRole
import com.aritr.rova.ui.theme.ResolveInk
import com.aritr.rova.ui.theme.RovaWarningsV3

/**
 * Trust System V1 — mid-recording top banner (frozen spec `docs/design/warnings-recovery.html`
 * §05, `.banner` :295–:319). A pinned glass capsule floating OVER the live viewfinder: leading
 * severity icon box, title + sub-text, and a trailing **Stop** pill. Compose transcribes the HTML
 * and never diverges.
 *
 * M5 migration (parity plan §7): the container alpha moves from `.88` to the unified
 * `pinSurface @ pinContainerAlpha` (.94); the title from `White @ .88` to `mediaInk` (.94,
 * disclosed unification); the sub to the pinned `mediaInkDim` (.48, same value, new token); the
 * icon-box glyph to the `banner` INK_SITE's resolved mark ink (`dot-ink`); and the Stop-pill label
 * from the shipped `severityColor @ .95` (3.44–4.38:1 on a bright frame — fails SC 1.4.3) to the
 * resolved `lbl-ink`. The **CountdownRing is deleted** (Q1: it depicted a countdown the system
 * never ran; APPX-F :1054) — the trailing slot is now the Stop pill for every banner id. The
 * severity border (α.30) and the ⋯ overflow (rendered only for the two echo ids that carry an
 * overflow list, :151) SURVIVE verbatim.
 */
@Composable
internal fun WarningTopBannerV3(
    content: TopBannerContent,
    severityColor: Color,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    /** Phase 4 Slice 2 — called when user taps an overflow menu item. Null = overflow ⋯ icon hidden. */
    onOverflow: ((ActionTarget) -> Unit)? = null,
) {
    // `banner` INK_SITE (frozen spec :1296–:1299): the icon-box glyph is a MARK
    // over the 16% severity fill, and the Stop-pill label is a LABEL over the 20%
    // fill — both fills sitting on the near-black pinned capsule. The capsule over
    // any media frame is near-black, so ResolveInk takes the LIGHTEN branch: the
    // mark passes the raw locked hue through (MIX_MARK), the label lightens toward
    // `mediaInk` (MIX_LABEL). Backings are proxied on the opaque `pinSurface` (the
    // .94 capsule stand-in) — same dark branch, no live media needed — exactly as
    // the snooze chip does. Clearance is proven by TrustContrastSweepTest.
    val iconBoxFill = severityColor.copy(alpha = 0.16f)
    val stopPillFill = severityColor.copy(alpha = 0.20f)
    val glyphInk: Color = ResolveInk.of(
        hue = severityColor,
        backing = iconBoxFill.compositeOver(RovaWarningsV3.pinSurface),
        target = ResolveInk.TARGET_MARK,
        top = RovaWarningsV3.mediaInk,
        mix = ResolveInk.MIX_MARK,
    ).color
    val stopLabelInk: Color = ResolveInk.of(
        hue = severityColor,
        backing = stopPillFill.compositeOver(RovaWarningsV3.pinSurface),
        target = ResolveInk.TARGET_TEXT,
        top = RovaWarningsV3.mediaInk,
        mix = ResolveInk.MIX_LABEL,
    ).color

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(RovaWarningsV3.bannerCornerRadius))
            .background(RovaWarningsV3.pinSurface.copy(alpha = RovaWarningsV3.pinContainerAlpha))
            .border(
                width = 1.dp,
                color = severityColor.copy(alpha = 0.30f),
                shape = RoundedCornerShape(RovaWarningsV3.bannerCornerRadius),
            )
            .padding(
                horizontal = RovaWarningsV3.bannerSidePadding,
                vertical = RovaWarningsV3.bannerVerticalPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RovaWarningsV3.bannerGap),
    ) {
        Box(
            modifier = Modifier
                .size(RovaWarningsV3.bannerIconSize)
                .clip(RoundedCornerShape(RovaWarningsV3.bannerIconCornerRadius))
                .background(iconBoxFill),
            contentAlignment = Alignment.Center,
        ) {
            // ADR-0031 §4 severity-tint exception: both glyph layers take the resolved
            // `banner` mark ink (`.banner .iconbox{color:var(--dot-ink)}`), not
            // palette.accent — so this renders manually instead of via SemanticIcon.
            Box(modifier = Modifier.size(18.dp)) {
                Icon(content.glyph.outline, contentDescription = null, modifier = Modifier.fillMaxSize(),
                    tint = glyphInk)
                content.glyph.accent?.let { acc ->
                    Icon(acc, contentDescription = null, modifier = Modifier.fillMaxSize(),
                        tint = glyphInk)
                }
            }
        }

        Column(
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = stringResource(content.title),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = RovaWarningsV3.mediaInk,
                maxLines = 1,
            )
            Spacer(Modifier.size(2.dp))
            Text(
                text = stringResource(content.sub),
                style = MaterialTheme.typography.bodySmall,
                color = RovaWarningsV3.mediaInkDim,
                maxLines = 2,
            )
        }

        // Trailing STOP pill — the banner's always-present answer (3). With the
        // CountdownRing removed (Q1) this renders for EVERY banner id. The VISUAL
        // capsule stays ~32dp (inner Row, unchanged); the 48dp touch target is an
        // INVISIBLE expansion (`invisibleTouchTarget`) — the Compose transcription of
        // the frozen `.banner .stop::after` absolute overlay (§05 :317–:318). It
        // reports the pill's NATURAL size to the banner Row, so the banner is NOT
        // grown (Investigation 2), while the clickable node is measured at 48dp for a
        // true hit target. `minimumInteractiveComponentSize` is deliberately NOT used
        // here: it reserves 48dp of layout and inflates the banner (the snooze chip
        // can use it only because it is a standalone control with no sibling row).
        Box(
            modifier = Modifier
                .invisibleTouchTarget(48.dp)
                .clickable(role = Role.Button) { onAction() },
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(RovaWarningsV3.bannerIconCornerRadius))
                    .background(stopPillFill)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(content.cta).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = stopLabelInk,
                )
            }
        }

        // Phase 4 Slice 2 — overflow ⋯ icon rendered when content.overflow
        // is non-empty AND a handler is wired. Tapping opens a DropdownMenu
        // listing each WarningAction; selecting an item dispatches via
        // onOverflow with the action's ActionTarget.
        if (content.overflow.isNotEmpty() && onOverflow != null) {
            Box {
                var expanded by remember { mutableStateOf(false) }
                IconButton(
                    onClick = { expanded = true },
                    modifier = Modifier.size(40.dp),
                ) {
                    SemanticIcon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.warning_more_options_cd),
                        role = IconRole.Secondary,
                        modifier = Modifier.size(20.dp),
                    )
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    content.overflow.forEach { action ->
                        DropdownMenuItem(
                            text = { Text(stringResource(action.label)) },
                            onClick = {
                                expanded = false
                                onOverflow(action.target)
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Expands the wrapped content's touch target to at least [target] WITHOUT reserving layout —
 * the Compose transcription of the frozen `.banner .stop::after` absolute hit overlay
 * (`docs/design/warnings-recovery.html` §05 :317–:318). The clickable child is measured at
 * ≥ [target] on each axis (so its own bounds — and therefore its pointer hit region — reach
 * 48dp), while this modifier reports the content's NATURAL size to the parent, so the banner
 * `Row` is never grown (Investigation 2). The larger bounds overflow, centered and invisible.
 *
 * Geometry is the pure [TouchTargetExpansion.compute]; this is the thin Compose seam. Stock hit
 * helpers cannot do this — `minimumInteractiveComponentSize`/`requiredSize` report the enlarged
 * size to the parent, inflating a wrap-content container.
 */
private fun Modifier.invisibleTouchTarget(target: Dp): Modifier = layout { measurable, constraints ->
    val targetPx = target.roundToPx()
    val naturalWidth = measurable.maxIntrinsicWidth(Constraints.Infinity)
    val naturalHeight = measurable.maxIntrinsicHeight(Constraints.Infinity)
    val maxWidthPx = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
    val g = TouchTargetExpansion.compute(naturalWidth, naturalHeight, targetPx, maxWidthPx)
    val placeable = measurable.measure(
        constraints.copy(
            minWidth = g.placeableWidth,
            maxWidth = maxOf(constraints.maxWidth, g.placeableWidth),
            minHeight = g.placeableHeight,
            maxHeight = maxOf(constraints.maxHeight, g.placeableHeight),
        ),
    )
    layout(g.reportedWidth, g.reportedHeight) {
        placeable.place(g.offsetX, g.offsetY)
    }
}

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aritr.rova.R
import com.aritr.rova.ui.components.SemanticIcon
import com.aritr.rova.ui.theme.IconRole
import com.aritr.rova.ui.theme.ResolveInk
import com.aritr.rova.ui.theme.RovaGlyph
import com.aritr.rova.ui.theme.RovaWarnings
import com.aritr.rova.ui.theme.RovaWarningsV3

/**
 * v3 — Modal bottom sheet for all idle-reachable warnings. Routes by
 * [surface] to compute the severity accent.
 *
 * Layout:
 *   handle → overflow ⋯ (if hasOverflow) → icon-with-glow → severity chip →
 *   title → body → optional "Why this matters" expander → primary CTA →
 *   secondary CTA (if present) → tertiary CTA (if present, Phase 4.3).
 *
 * Hard-block sheets are NOT swipe-dismissible (`confirmValueChange`).
 *
 * Spec: docs/superpowers/specs/2026-05-23-phase-4-warning-reskin-v3-design.md §3.4
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WarningSheetV3(
    id: WarningId,
    surface: WarningSurface,
    expanded: Boolean,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
    /** Phase 4.3 — C2.4 CANT_MERGE tertiary "Discard session" link. No-op (default) for all other warnings. */
    onTertiary: () -> Unit = {},
    onOverflow: (ActionTarget) -> Unit,
    onToggleWhy: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val content = warningSheetContent(id)
    val accent: Color = when (surface) {
        WarningSurface.HardBlockSheet -> RovaWarnings.hard
        WarningSurface.SoftSheet -> RovaWarnings.soft
        WarningSurface.AdvisorySheet -> RovaWarnings.advisory
        WarningSurface.TopBanner -> RovaWarnings.advisory   // unreachable here
    }
    val severityLabel: String = when (surface) {
        WarningSurface.HardBlockSheet -> stringResource(R.string.warning_severity_hard)
        WarningSurface.SoftSheet -> stringResource(R.string.warning_severity_soft)
        WarningSurface.AdvisorySheet -> stringResource(R.string.warning_severity_advisory)
        WarningSurface.TopBanner -> ""
    }

    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { value ->
            surface != WarningSurface.HardBlockSheet || value != SheetValue.Hidden
        },
    )

    val isHardBlock = surface == WarningSurface.HardBlockSheet

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        // Frozen `.sheet{background:var(--pin-surface)}` (:268): the modal is opaque and
        // covers the viewfinder, so it composites against nothing — the pinned host maps
        // `--ink-high → --media-ink` (:383), which is why title/body/ghost read as media inks.
        containerColor = RovaWarningsV3.pinSurface,
        shape = RoundedCornerShape(
            topStart = RovaWarningsV3.sheetCornerRadius,
            topEnd = RovaWarningsV3.sheetCornerRadius,
            bottomStart = 0.dp,
            bottomEnd = 0.dp,
        ),
        // Frozen spec transcription note (:1143): a hard-block sheet renders NO drag handle —
        // nothing drags it (`confirmValueChange` blocks Hidden), so "the drag handle nothing
        // dragged" was removed. A dismissible sheet keeps the honest stock handle.
        dragHandle = if (isHardBlock) null else { { BottomSheetDefaults.DragHandle() } },
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {

            if (hasOverflow(id)) {
                OverflowMenu(
                    overflow = content.overflow,
                    onTarget = onOverflow,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(
                            top = RovaWarningsV3.overflowTopInset,
                            end = RovaWarningsV3.overflowRightInset,
                        ),
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = RovaWarningsV3.sheetSidePadding,
                        end = RovaWarningsV3.sheetSidePadding,
                        bottom = RovaWarningsV3.sheetBottomPadding,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(8.dp))

                IconWithGlow(glyph = content.glyph, accent = accent)

                Spacer(Modifier.height(10.dp))

                SeverityChip(label = severityLabel, accent = accent)

                Spacer(Modifier.height(8.dp))

                // Frozen `.t-title{font:600 15px;color:var(--ink-high)}` — ink-high == mediaInk
                // in the pinned host. Size overrides the Material role to the spec scale (the
                // dead `sheetTitleSize`/`sheetBodySize` tokens were removed in M6).
                Text(
                    text = stringResource(content.title),
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = RovaWarningsV3.mediaInk,
                    textAlign = TextAlign.Center,
                )

                if (content.body != 0) {
                    Spacer(Modifier.height(8.dp))
                    // Frozen `.t-body{font:400 12.5px;color:var(--ink-body)}` — ink-body == mediaInkBody
                    // (.55). The sweep pins "Sheet body" ≥ 4.5:1 on the near-black pin surface.
                    Text(
                        text = stringResource(content.body),
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 12.5.sp,
                        color = RovaWarningsV3.mediaInkBody,
                        textAlign = TextAlign.Center,
                    )
                }

                Spacer(Modifier.height(18.dp))

                if (shouldShowWhy(id)) {
                    WhyExpander(
                        expanded = expanded,
                        whyBody = stringResource(content.whyThisMatters!!),
                        onToggle = onToggleWhy,
                    )
                    Spacer(Modifier.height(10.dp))
                }

                PrimaryCta(
                    label = stringResource(content.primary.label),
                    accent = accent,
                    onClick = onPrimary,
                )

                content.secondary?.let { sec ->
                    Spacer(Modifier.height(8.dp))
                    SecondaryCta(label = stringResource(sec.label), onClick = onSecondary)
                }

                // Phase 4.3 — tertiary CTA (e.g. C2.4 CANT_MERGE "Discard session").
                // Only Link style is used for tertiary in this slice; the when-block is
                // exhaustive so future styles (Secondary/Primary) compile cleanly.
                content.tertiary?.let { ter ->
                    val terLabel = stringResource(ter.label)
                    when (ter.style) {
                        WarningActionStyle.Link -> {
                            Spacer(Modifier.height(8.dp))
                            // Frozen `.cta-dest` (:247–:252): transparent fill, 1px
                            // `color-mix(sev-hard 30%)` border, label `color-mix(sev-hard 62%,
                            // ink-high)`, `flex:0 0 auto` (NEVER full-width) and always terminal —
                            // "an irreversible action must not read as the obvious one" (§01). It is
                            // a deliberate fixed mix (APPX-D exempt). CANT_MERGE's "Discard session"
                            // is the only tertiary, and it IS destructive.
                            val destBacking = RovaWarningsV3.mediaInk.compositeOver(RovaWarningsV3.pinSurface)
                            val destInk = RovaWarnings.hard
                                .copy(alpha = ResolveInk.MIX_LABEL.toFloat())
                                .compositeOver(destBacking)
                            Row(
                                modifier = Modifier
                                    .heightIn(min = RovaWarningsV3.sheetCtaHeight)
                                    .clip(RoundedCornerShape(RovaWarningsV3.sheetCtaCornerRadius))
                                    .border(
                                        width = 1.dp,
                                        color = RovaWarnings.hard.copy(alpha = 0.30f),
                                        shape = RoundedCornerShape(RovaWarningsV3.sheetCtaCornerRadius),
                                    )
                                    .clickable(role = Role.Button) { onTertiary() }
                                    .padding(horizontal = 20.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = terLabel,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = destInk,
                                )
                            }
                        }
                        WarningActionStyle.Secondary -> {
                            Spacer(Modifier.height(8.dp))
                            SecondaryCta(label = terLabel, onClick = onTertiary)
                        }
                        WarningActionStyle.Primary -> {
                            Spacer(Modifier.height(8.dp))
                            PrimaryCta(label = terLabel, accent = accent, onClick = onTertiary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IconWithGlow(
    glyph: RovaGlyph,
    accent: Color,
) {
    // The radial glow brush needs an explicit radius in pixels — the default
    // (Float.POSITIVE_INFINITY) collapses the gradient to a flat fill, killing
    // the bloom. Match the radius to the icon's diameter so the gradient fades
    // exactly across the bloom box.
    val density = LocalDensity.current
    val glowRadiusPx = with(density) {
        (RovaWarningsV3.sheetIconSize - (RovaWarningsV3.sheetIconGlowInset * 2)).toPx() * 0.5f
    }

    Box(
        modifier = Modifier.size(RovaWarningsV3.sheetIconSize),
        contentAlignment = Alignment.Center,
    ) {
        // Glow bloom (offset + blurred)
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(
                    x = RovaWarningsV3.sheetIconGlowInset,
                    y = RovaWarningsV3.sheetIconGlowInset,
                )
                .size(
                    width = RovaWarningsV3.sheetIconSize - (RovaWarningsV3.sheetIconGlowInset * 2),
                    height = RovaWarningsV3.sheetIconSize - (RovaWarningsV3.sheetIconGlowInset * 2),
                )
                .blur(RovaWarningsV3.sheetIconGlowBlur)
                .background(brush = RovaWarningsV3.iconGlow(accent, glowRadiusPx), shape = CircleShape),
        )
        // Icon container — the frozen `sheeticon` INK_SITE. Fill `color-mix(sev 16%)`
        // over the opaque pin surface (:283); glyph = the resolved `--dot-ink` MARK over
        // that fill (:284). Near-black backing ⇒ ResolveInk LIGHTEN, and MIX_MARK is a raw
        // passthrough, so the mark is byte-identical to the pre-freeze `accent` glyph.
        val iconFill = accent.copy(alpha = 0.16f)
        val glyphInk: Color = ResolveInk.of(
            hue = accent,
            backing = iconFill.compositeOver(RovaWarningsV3.pinSurface),
            target = ResolveInk.TARGET_MARK,
            top = RovaWarningsV3.mediaInk,
            mix = ResolveInk.MIX_MARK,
        ).color
        Box(
            modifier = Modifier
                .size(RovaWarningsV3.sheetIconSize)
                .clip(RoundedCornerShape(RovaWarningsV3.sheetIconCornerRadius))
                .background(iconFill)
                .border(
                    width = 1.dp,
                    color = accent.copy(alpha = RovaWarningsV3.sheetIconInnerStrokeAlpha),
                    shape = RoundedCornerShape(RovaWarningsV3.sheetIconCornerRadius),
                ),
            contentAlignment = Alignment.Center,
        ) {
            // ADR-0031 §4 severity-tint exception: both glyph layers take the resolved
            // `sheeticon` mark ink, not palette.accent — so this renders manually, not via SemanticIcon.
            Box(modifier = Modifier.size(24.dp)) {
                Icon(glyph.outline, contentDescription = null, modifier = Modifier.fillMaxSize(), tint = glyphInk)
                glyph.accent?.let { acc ->
                    Icon(acc, contentDescription = null, modifier = Modifier.fillMaxSize(), tint = glyphInk)
                }
            }
        }
    }
}

@Composable
private fun SeverityChip(label: String, accent: Color) {
    if (label.isBlank()) return
    // The frozen `pinchip` INK_SITE (:1292). Fill `color-mix(sev 20%)` over the opaque pin
    // surface; dot = resolved `--dot-ink` MARK (raw hue passthrough, byte-identical to the
    // pre-freeze dot), label = resolved `--lbl-ink` LABEL (lightens toward mediaInk). Both
    // over the near-black backing ⇒ ResolveInk LIGHTEN. Radius r-sm 10, not the old pill.
    val chipFill = accent.copy(alpha = RovaWarningsV3.sevChipFillAlpha)
    val chipBacking = chipFill.compositeOver(RovaWarningsV3.pinSurface)
    val dotInk: Color = ResolveInk.of(
        hue = accent,
        backing = chipBacking,
        target = ResolveInk.TARGET_MARK,
        top = RovaWarningsV3.mediaInk,
        mix = ResolveInk.MIX_MARK,
    ).color
    val labelInk: Color = ResolveInk.of(
        hue = accent,
        backing = chipBacking,
        target = ResolveInk.TARGET_TEXT,
        // ResolveInk ignores `top`'s alpha and reads its RGB only, so the LABEL top must be
        // the OPAQUE composite of mediaInk over pinSurface — the frozen `--lbl-ink` mix top
        // (matches TrustInkSites `top = over(WHITE, .94, pin)`). Raw `mediaInk` (white@.94)
        // would feed pure white and drift ~5/255 lighter than the frozen authority.
        top = RovaWarningsV3.mediaInk.compositeOver(RovaWarningsV3.pinSurface),
        mix = ResolveInk.MIX_LABEL,
    ).color
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(RovaWarningsV3.sevChipRadius))
            .background(chipFill)
            .padding(
                horizontal = RovaWarningsV3.sevChipPaddingH,
                vertical = RovaWarningsV3.sevChipPaddingV,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(RovaWarningsV3.sevChipDotSize)
                .clip(CircleShape)
                .background(dotInk),
        )
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = labelInk,
        )
    }
}

@Composable
private fun WhyExpander(
    expanded: Boolean,
    whyBody: String,
    onToggle: () -> Unit,
) {
    // Frozen `.whyrow` (:285–:287): `--ink-high`-based, NEUTRAL (not severity-tinted) in the
    // pinned host. Foreground `color-mix(ink-high 78%)`, border `color-mix(ink-high 20%)` —
    // ink-high == mediaInk (white @ .94), so scale its alpha rather than paint pure white.
    val whyForeground = RovaWarningsV3.mediaInk.copy(
        alpha = RovaWarningsV3.mediaInk.alpha * RovaWarningsV3.whyRowForegroundAlpha,
    )
    val whyBorder = RovaWarningsV3.mediaInk.copy(
        alpha = RovaWarningsV3.mediaInk.alpha * RovaWarningsV3.whyRowBorderAlpha,
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(RovaWarningsV3.whyRowHeight)
                .clip(RoundedCornerShape(RovaWarningsV3.whyRowCornerRadius))
                .border(
                    width = 1.dp,
                    color = whyBorder,
                    shape = RoundedCornerShape(RovaWarningsV3.whyRowCornerRadius),
                )
                .clickable(role = Role.Button) { onToggle() }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = whyForeground,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = stringResource(R.string.warning_why_this_matters),
                style = MaterialTheme.typography.labelMedium,
                color = whyForeground,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = whyForeground,
                modifier = Modifier.size(14.dp),
            )
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            // `.whybody{color:var(--ink-dim)}` (:288) — mediaInkDim in the pinned host.
            Text(
                text = whyBody,
                style = MaterialTheme.typography.bodySmall,
                color = RovaWarningsV3.mediaInkDim,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
            )
        }
    }
}

@Composable
private fun PrimaryCta(label: String, accent: Color, onClick: () -> Unit) {
    // Frozen `.cta-sev{background:var(--sev);color:var(--sev-cta-ink)}` (:239–:242). The sheet
    // primary always resolves the severity condition (§01 fill table: "Open settings", "Allow
    // microphone"), so it is the LOCKED severity fill + near-black `severityCtaInk` — never routed
    // through DialogActionColors (APPX-C). The fill was already the severity colour; M6 graduates
    // the near-black label literal onto the `severityCtaInk` token (byte-identical #1A1A1A).
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RovaWarningsV3.sheetCtaHeight)
            .clip(RoundedCornerShape(RovaWarningsV3.sheetCtaCornerRadius))
            .background(accent)
            .clickable(role = Role.Button) { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = RovaWarningsV3.severityCtaInk,
        )
    }
}

@Composable
private fun SecondaryCta(label: String, onClick: () -> Unit) {
    // Frozen ghost `.cta-ghost` (:244–:246): fill `color-mix(ink-high 7%)`, 1px border
    // `color-mix(ink-high 12%)`, label `--ink-body` (mediaInkBody). ink-high == white in the
    // pinned host; the near-black backing clears AA at ink-body .55 ("Ghost CTA label · sheet"
    // in TrustContrastSweepTest), which is why M6 moves the label off the older .68 a11y bump.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RovaWarningsV3.sheetCtaHeight)
            .clip(RoundedCornerShape(RovaWarningsV3.sheetCtaCornerRadius))
            .background(Color.White.copy(alpha = RovaWarningsV3.secondaryCtaFillAlpha))
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = RovaWarningsV3.secondaryCtaStrokeAlpha),
                shape = RoundedCornerShape(RovaWarningsV3.sheetCtaCornerRadius),
            )
            .clickable(role = Role.Button) { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = RovaWarningsV3.mediaInkBody,
        )
    }
}

@Composable
private fun OverflowMenu(
    overflow: List<WarningAction>,
    onTarget: (ActionTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        // Frozen `.ovf{28} + .hit::after{48}` (:262, :279): visual 28dp glyph, 48dp touch. The
        // overflow is absolutely positioned in the sheet's TopEnd corner (a standalone control
        // with no sibling row), so `minimumInteractiveComponentSize` is the safe expander here —
        // unlike the banner Stop pill (which shares the banner Row and needs the invisible seam).
        IconButton(
            onClick = { menuOpen = true },
            modifier = Modifier.minimumInteractiveComponentSize(),
        ) {
            SemanticIcon(
                imageVector = Icons.Default.MoreHoriz,
                contentDescription = stringResource(R.string.warning_more_actions_cd),
                role = IconRole.Disabled,
                modifier = Modifier.size(RovaWarningsV3.overflowButtonSize),
            )
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            overflow.forEach { action ->
                DropdownMenuItem(
                    text = { Text(stringResource(action.label)) },
                    onClick = {
                        menuOpen = false
                        onTarget(action.target)
                    },
                )
            }
        }
    }
}

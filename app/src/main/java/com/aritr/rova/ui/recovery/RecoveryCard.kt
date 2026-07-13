package com.aritr.rova.ui.recovery

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.annotation.StringRes
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.aritr.rova.R
import com.aritr.rova.ui.components.ProcessingGlyph
import com.aritr.rova.ui.components.focusHighlight
import com.aritr.rova.ui.components.rememberReduceMotion
import com.aritr.rova.ui.text.UiText
import com.aritr.rova.ui.text.resolve
import com.aritr.rova.ui.theme.RovaMotion
import com.aritr.rova.ui.theme.RovaWarnings
import com.aritr.rova.ui.theme.RovaTrustTokens
import com.aritr.rova.ui.warnings.ThemedHostInk

/**
 * Trust System V1 — M8 + M9 — Library recovery card, transcribed from the frozen
 * `docs/design/warnings-recovery.html` §07 (`.recov`) + §08 (merge-failure frame),
 * DESIGN FROZEN 2026-07-09. Compose transcribes the frozen HTML and never diverges from it.
 *
 * M9 lands the merge-failed frame (frozen §08, HTML :1629–:1636): on a failure the eyebrow
 * tag → "Merge failed", the title → "Couldn't combine your clips", and the body → the
 * [FailBox] (owner-locked, localized reason + bolded "Your clips are safe."). The reason is
 * the closed-set [MergeFailureReason] `@StringRes` classified from the TYPED `ExportResult`
 * in `RecoveryMerger`; no raw exception text is reachable in the UI. Retry replaces the
 * primary label. `RecoveryUiState` gains `mergeFailedReasonRes` (raw `mergeFailedReason`
 * kept for logs only).
 *
 * The freeze re-architects the pre-freeze v3 card in four load-bearing ways:
 *
 *  1. **Themed, not pinned.** The card is no longer a `#0B0D14 @ .94` near-black
 *     island; it is an elevated **`surfaceHi`** container with a themed `--edge`
 *     border that FOLLOWS the palette (§06). Its inks resolve per-backing through
 *     [ThemedHostInk.forRecovery] (the `recov` INK_SITE), exactly the world the
 *     `TrustContrastSweepTest` proves AA on.
 *  2. **TrustRow anatomy.** A single leading 7dp severity **dot** (resolved
 *     `dot-ink`) beside the answer column — no SemanticIcon emblem, no sev-chip
 *     (HTML :362, :1625; the frozen `.recov` trustrow carries only the dot). The
 *     pulse rides that dot for `KILLED_BY_SYSTEM` only, and the involuntary-stop
 *     concept survives in the eyebrow tag + dot hue + card glow.
 *  3. **Recency eyebrow.** `TAG · recency` (uppercase), the first render consumer
 *     of the M3 [RelativeTimeLabel] the mapper already mints.
 *  4. **CTA re-roling.** Primary Merge/Retry = `cta-accent` (`colorScheme.primary`
 *     fill + `onPrimary` ink, DialogActionColors-resolved), Keep = ghost, Discard =
 *     `cta-dest` OUTLINE (never the solid red fill, never full-width, always
 *     terminal — §01's law). The progress strip is drawn ONLY while merging and its
 *     chip reads **filled/total** (fixing the pre-freeze `N / N`).
 *
 * The mapper is untouched; M9's only state delta is the additive `mergeFailedReasonRes`.
 */
@Composable
fun RecoveryCard(
    state: RecoveryCardState,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
    vendorHelpSlot: (@Composable () -> Unit)? = null,
    /** Phase 4.3 — null = button hidden (back-compat). When non-null with [onKeepRaw] non-null, the CTA row becomes a 3-button stack. */
    onMerge: (() -> Unit)? = null,
    onKeepRaw: (() -> Unit)? = null,
) {
    val severityColor: Color = severityColorFor(state.kind)
    val isHardSeverity: Boolean = state.kind == RecoveryCardKind.KILLED_BY_SYSTEM

    // M9 — the frozen merge-failed frame (§08, HTML :1629–:1636): when the last merge
    // failed and no retry is in flight, the eyebrow tag → "Merge failed", the title →
    // "Couldn't combine your clips", and the body → the failbox. `mergeFailedReasonRes`
    // is the owner-locked, localized reason; it and `mergeInProgress` are never both set.
    val failed: Boolean = state.mergeFailedReasonRes != null && state.mergeInProgress == null

    val cs = MaterialTheme.colorScheme
    // No palette CompositionLocal — polarity from the surface luminance (mirrors M7's
    // ThemedHostInk consumers). `cs.surface == RovaPalette.surfaceBase`, so the derived
    // surfaceHi is byte-identical to the sweep's `surfaceHiOf`.
    val isDark = cs.surface.luminance() < 0.5f
    val surfaceHi = RovaTrustTokens.surfaceHi(cs.surface, isLight = !isDark)
    val ink = ThemedHostInk.forRecovery(
        severity = severityColor,
        surfaceHi = surfaceHi,
        onSurface = cs.onSurface,
        onSurfaceVariant = cs.onSurfaceVariant,
        isDark = isDark,
    )
    val cornerRadius = RoundedCornerShape(RovaTrustTokens.recoveryCardCornerRadius)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(cornerRadius)
            // Frozen `.recov{background:var(--surface-hi); border:var(--hair) solid var(--edge)}`
            // (:355–:356). `--edge` is themed per palette (:1342); `outlineVariant` is the
            // Material slot carrying that palette edge (PaletteColorScheme.from :61).
            .background(surfaceHi)
            .border(width = 1.dp, color = cs.outlineVariant, shape = cornerRadius),
    ) {
        // Top glow bloom — frozen `.recov::before` (:357–:359): severity-tinted vertical
        // gradient, 28dp blur, decorative (contentDescription = null). Kept from v3.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(RovaTrustTokens.recoveryCardGlowHeight)
                .blur(RovaTrustTokens.recoveryCardGlowBlur)
                .background(brush = RovaTrustTokens.recoveryGlow(severityColor)),
        )

        // .trustrow — leading severity dot + the answer column. align-items:flex-start.
        Row(
            modifier = Modifier.padding(16.dp), // card padding s4 / s4 (was 16 / 18)
            horizontalArrangement = Arrangement.spacedBy(12.dp), // gap s3
            verticalAlignment = Alignment.Top,
        ) {
            RecoveryDot(color = ink.dot, pulsing = isHardSeverity)

            Column(modifier = Modifier.weight(1f)) {
                // A1 · what happened — TAG · recency, uppercase (frozen .t-eyebrow).
                Eyebrow(
                    tag = if (failed) stringResource(R.string.recovery_merge_failed_eyebrow)
                          else stringResource(tagLabelResFor(state.kind)),
                    recency = recencyText(state.recency),
                    color = ink.body,
                )

                Spacer(Modifier.height(8.dp)) // .t-title margin-top s2
                Text(
                    text = if (failed) stringResource(R.string.recovery_merge_failed_title)
                           else stringResource(state.titleRes),
                    fontSize = 15.sp,
                    lineHeight = 19.8.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.005).em,
                    color = cs.onSurface, // --ink-high == tHigh
                )

                // A2 · what it means. Frozen swaps `.t-body` for the `.failbox` on the
                // merge-failed frame (§08): the owner-locked reason + the bolded standing
                // reassurance. Otherwise the plain body line.
                if (failed) {
                    FailBox(
                        reasonRes = state.mergeFailedReasonRes!!,
                        reasonInk = ink.body,
                        reassuranceInk = cs.onSurface, // frozen `<b style="color:var(--tHigh)">`
                    )
                } else {
                    Spacer(Modifier.height(8.dp)) // .t-body margin-top s2
                    Text(
                        text = stringResource(state.bodyRes),
                        fontSize = 12.5.sp,
                        lineHeight = 19.4.sp,
                        color = ink.body, // --ink-body == tQuiet
                    )
                }

                // .artifacts — the clip summary lines. Always shown when present (the
                // recovered-clip count lives here, NOT in the progress strip).
                if (state.survivingArtifacts.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp)) // .artifacts margin-top s3
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { // gap s1
                        state.survivingArtifacts.forEach { line ->
                            Text(
                                text = line.resolve(),
                                fontSize = 11.5.sp,
                                lineHeight = 17.3.sp,
                                color = ink.body,
                            )
                        }
                    }
                }

                // Progress strip — frozen renders it ONLY while merging (:1638). Idle it
                // is a row of filled cells restating the clip count already on screen.
                val progress = state.mergeInProgress
                if (progress != null) {
                    Spacer(Modifier.height(16.dp)) // .progress margin-top s4
                    ProgressStrip(
                        cellCount = state.survivingArtifacts.size,
                        progress = progress,
                        cellOn = cs.primary, // --accent-fill
                        cellOff = cs.onSurface.copy(alpha = 0.05f), // --fill-1 (tHigh 5%)
                        numChipColor = ink.body, // --ink-dim
                    )
                }

                CtaBlock(
                    state = state,
                    failed = failed,
                    ink = ink,
                    accentFill = cs.primary,
                    accentInk = cs.onPrimary,
                    ghostFill = cs.onSurface.copy(alpha = 0.07f), // --ink-high 7%
                    ghostBorder = cs.onSurface.copy(alpha = 0.12f), // --ink-high 12%
                    destBacking = cs.onSurface.compositeOver(surfaceHi),
                    onMerge = onMerge,
                    onKeepRaw = onKeepRaw,
                    onDiscard = onDiscard,
                    vendorHelpSlot = vendorHelpSlot,
                )
            }
        }
    }
}

/**
 * Severity colour for the card glow + dot ink hue. LOCKED per kind (frozen §07:
 * `severityColorFor(kind)` "transcribed verbatim — a design freeze may not silently
 * re-assign meaning"). Hard = red for `KILLED_BY_SYSTEM` (recurs until an OS setting
 * changes); advisory-blue for the scheduled report; soft-amber for every other
 * terminator (the user acted, the OS acted, or the app reported — nothing recurs).
 */
private fun severityColorFor(kind: RecoveryCardKind): Color = when (kind) {
    RecoveryCardKind.KILLED_BY_SYSTEM -> RovaWarnings.hard
    RecoveryCardKind.SCHEDULED_END -> RovaWarnings.advisory   // neutral/informational, NOT an alarm
    RecoveryCardKind.KILLED_FORCE_STOP,
    RecoveryCardKind.SAFETY_STOPPED,
    RecoveryCardKind.ERROR_STOPPED,
    RecoveryCardKind.USER_STOPPED -> RovaWarnings.soft
}

/**
 * Eyebrow tag label id derived from `kind`. Copy is transcribed verbatim from the
 * frozen §07 `KINDS[].eyebrow` (M8 copy audit). Mixed-case in resources; uppercased
 * at render (frozen `.t-eyebrow{text-transform:uppercase}`).
 */
@StringRes
private fun tagLabelResFor(kind: RecoveryCardKind): Int = when (kind) {
    RecoveryCardKind.KILLED_BY_SYSTEM -> R.string.recovery_tag_killed_by_system
    RecoveryCardKind.KILLED_FORCE_STOP -> R.string.recovery_tag_force_stopped
    RecoveryCardKind.USER_STOPPED -> R.string.recovery_tag_user_stopped
    RecoveryCardKind.SAFETY_STOPPED -> R.string.recovery_tag_auto_stopped
    RecoveryCardKind.SCHEDULED_END -> R.string.recovery_tag_scheduled
    RecoveryCardKind.ERROR_STOPPED -> R.string.recovery_tag_interrupted
}

/**
 * The M3 [RelativeTimeLabel] → display string (APPX-G ladder). Resource-backed and
 * resolved here (the composable edge), never in the pure mapper. The DATE rung (>= 7d)
 * formats the absolute instant the label carries; every other rung is a fixed string
 * or a plural on [RelativeTimeLabel.count].
 */
@Composable
private fun recencyText(label: RelativeTimeLabel): String = when (label.kind) {
    RelativeTimeKind.JUST_NOW -> stringResource(R.string.recency_just_now)
    RelativeTimeKind.MINUTES ->
        pluralStringResource(R.plurals.recency_minutes, label.count ?: 0, label.count ?: 0)
    RelativeTimeKind.HOURS ->
        pluralStringResource(R.plurals.recency_hours, label.count ?: 0, label.count ?: 0)
    RelativeTimeKind.YESTERDAY -> stringResource(R.string.recency_yesterday)
    RelativeTimeKind.DAYS ->
        pluralStringResource(R.plurals.recency_days, label.count ?: 0, label.count ?: 0)
    RelativeTimeKind.DATE -> {
        val context = LocalContext.current
        val date = android.text.format.DateUtils.formatDateTime(
            context,
            label.atMillis,
            android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_NO_YEAR,
        )
        stringResource(R.string.recency_on_date, date)
    }
}

/**
 * The leading 7dp severity dot (frozen `.recov>.trustrow>.dot`). It carries the
 * resolved `dot-ink`. The pulse marks `KILLED_BY_SYSTEM` only — the one kind the user
 * must act on OUTSIDE Rova to prevent — and is the frozen shared `@keyframes pulse`
 * (`:363` references the SAME keyframe as the snooze chip `:336`): min opacity 0.45 at
 * period/2, 1600ms, `RovaMotion.easeStandard`, held fully visible under reduced motion.
 * The infinite transition is hoisted unconditionally so a slot reused across kinds
 * (list index keys) does not trip Compose's positional-composable rule.
 */
@Composable
private fun RecoveryDot(color: Color, pulsing: Boolean) {
    val transition = rememberInfiniteTransition(label = "recovery-dot-pulse")
    val period = RovaTrustTokens.snoozeChipPulsePeriodMs
    val pulseAlpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = period
                1f at 0 using RovaMotion.easeStandard
                RovaTrustTokens.snoozeChipDotPulseAlpha at period / 2 using RovaMotion.easeStandard
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "recovery-dot-pulse-alpha",
    )
    val reduceMotion = rememberReduceMotion()
    val dotAlpha: Float = if (pulsing && !reduceMotion) pulseAlpha else 1f
    Box(
        modifier = Modifier
            .padding(top = 5.dp) // frozen inline `margin-top:5px` (shape-position offset, APPX-A)
            .size(7.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = dotAlpha)),
    )
}

/**
 * `.failbox` (frozen §08 :375–:377) — the merge-failure body frame. Owner-locked reason
 * copy + the bolded standing reassurance "Your clips are safe." (frozen `<b color:tHigh>`),
 * on a `sev-hard 8%` fill with a `sev-hard 22%` hairline, r-sm 10, padding s3, margin-top s3.
 * One merged node with an **assertive** live region (frozen `:745` "assertive (failure)",
 * RECOV-14) so a merge failure interrupts the screen reader; the raw exception never appears.
 */
@Composable
private fun FailBox(reasonRes: Int, reasonInk: Color, reassuranceInk: Color) {
    val text = buildAnnotatedString {
        append(stringResource(reasonRes))
        append(" ")
        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = reassuranceInk)) {
            append(stringResource(R.string.recovery_clips_are_safe))
        }
    }
    val shape = RoundedCornerShape(RovaTrustTokens.failBoxCornerRadius)
    Box(
        modifier = Modifier
            .padding(top = 12.dp) // frozen `.failbox{margin-top:var(--s3)}`
            .fillMaxWidth()
            .clip(shape)
            .background(RovaWarnings.hard.copy(alpha = 0.08f)) // color-mix(sev-hard 8%)
            .border(width = 1.dp, color = RovaWarnings.hard.copy(alpha = 0.22f), shape = shape) // hair · sev-hard 22%
            .padding(12.dp) // frozen `.failbox{padding:var(--s3)}`
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Assertive },
    ) {
        Text(text = text, fontSize = 12.5.sp, lineHeight = 19.4.sp, color = reasonInk)
    }
}

/**
 * `.t-eyebrow` — `TAG · recency`, uppercase, 9.5sp/600, `.13em` tracking, `--ink-dim`.
 * One text node (the recency span carries tabular-nums + weight 500 via [SpanStyle]);
 * the middot separates them. Rendering it as a single node keeps TalkBack from speaking
 * a bare "·".
 */
@Composable
private fun Eyebrow(tag: String, recency: String, color: Color) {
    val text = buildAnnotatedString {
        append(tag.uppercase())
        append("  ·  ")
        withStyle(SpanStyle(fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum")) {
            append(recency.uppercase())
        }
    }
    Text(
        text = text,
        fontSize = 9.5.sp,
        lineHeight = 12.4.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.13.em,
        color = color,
    )
}

/**
 * Pure screen-reader label for the progress strip (WCAG 2.2 AA SC 4.1.2, RECOV-16).
 * The cells are decorative `Box`es with no text, so the count is otherwise invisible
 * to TalkBack. `internal` for [RecoveryProgressA11yTest].
 */
internal fun recoveryProgressContentDescription(
    cellCount: Int,
    filledCells: Int,
    merging: Boolean,
): UiText = if (merging) {
    UiText.StrArgs(R.string.recovery_progress_cd_merging, listOf(filledCells, cellCount))
} else {
    UiText.Plural(R.plurals.recovery_progress_cd_recovered, cellCount, listOf(cellCount))
}

/**
 * Filled-cell count for the merge progress strip — `floor(progress × total)`.
 * The frozen strip prints **filled / total** (`:1641`, `done/c.clips`); the pre-freeze
 * chip passed the artifact count twice and always read `N / N` (`RecoveryCard.kt:408`,
 * the artifact-count-as-progress proxy §01 forbids). `internal` for its unit test.
 */
internal fun recoveryProgressFilledCells(progress: Float, cellCount: Int): Int =
    (progress.coerceIn(0f, 1f) * cellCount).toInt()

/**
 * Frozen `.progress` (`:366`–:371) — `[ProcessingGlyph | cells (flex 1) | numchip]`,
 * drawn only while merging. Cells: 7dp tall, 3.5dp radius, `--fill-1` off / `--accent-fill`
 * on. Numchip: `filled/total`, min-width 36, tabular-nums, `--ink-dim`. The strip is one
 * merged node with the spoken filled-of-total count (RECOV-16) and a polite live region so
 * progress is announced as cells roll forward (RECOV-17). The ProcessingGlyph is the §11
 * motion-inventory "working vs hung" cue (`:864`), placed leading here (the frozen header
 * row it used to sit beside is removed).
 */
@Composable
private fun ProgressStrip(
    cellCount: Int,
    progress: Float,
    cellOn: Color,
    cellOff: Color,
    numChipColor: Color,
) {
    val cells = cellCount.coerceAtLeast(1)
    val filled = recoveryProgressFilledCells(progress, cells)
    val progressCd = recoveryProgressContentDescription(cells, filled, merging = true).resolve()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = progressCd
                liveRegion = LiveRegionMode.Polite
            },
        horizontalArrangement = Arrangement.spacedBy(12.dp), // gap s3
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Decorative branded spinner — distinguishes "working" from "hung".
        ProcessingGlyph(size = 16.dp)
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(RovaTrustTokens.recoveryProgressCellGap),
        ) {
            repeat(cells) { index ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(RovaTrustTokens.recoveryProgressCellHeight)
                        .clip(RoundedCornerShape(RovaTrustTokens.recoveryProgressCellRadius))
                        .background(if (index < filled) cellOn else cellOff),
                )
            }
        }
        Text(
            text = stringResource(R.string.recovery_progress_chip, filled, cells),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = numChipColor,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = RovaTrustTokens.recoveryNumericChipMinWidth),
        )
    }
}

/**
 * The CTA stack (frozen answers A3/A4 + the destructive terminal). Order:
 *   [merge-failed reason · assertive] · [Merge/Retry accent | Keep ghost] · [vendor] · [Discard dest].
 *
 * `showThreeCtaStack` (both merge callbacks + both labels present) gates the
 * accent/ghost row; without it only Discard shows (no surviving artifacts to merge).
 * Merge-in-flight (`mergeInProgress != null`) disables Merge + Keep; **Discard stays
 * live** — the user is never trapped (frozen :790, unchanged). Discard is `cta-dest`:
 * transparent, `sev-hard 30%` outline, `mix(sev-hard 62%, ink-high)` ink, wrap-content
 * (NEVER full-width), always terminal (§01).
 */
@Composable
private fun CtaBlock(
    state: RecoveryCardState,
    failed: Boolean,
    ink: ThemedHostInk.RecoveryInk,
    accentFill: Color,
    accentInk: Color,
    ghostFill: Color,
    ghostBorder: Color,
    destBacking: Color,
    onMerge: (() -> Unit)?,
    onKeepRaw: (() -> Unit)?,
    onDiscard: () -> Unit,
    vendorHelpSlot: (@Composable () -> Unit)?,
) {
    val showThreeCtaStack = onMerge != null && onKeepRaw != null &&
        state.mergeLabelRes != null && state.keepRawLabelRes != null
    val inFlight = state.mergeInProgress != null
    val discardLabel = stringResource(state.discardLabelRes)
    val discardCd = stringResource(R.string.recovery_discard_cd, discardLabel)

    // M9 — the merge-failure surface is the failbox (rendered in the answer column, §08).
    // The inline raw-exception reason Text (which showed the raw exception
    // message) is gone: no raw exception text is reachable in the UI anymore. The failure
    // still flips the primary to "Retry" (frozen `failed?'Retry':c.primary`, :1644).

    if (showThreeCtaStack) {
        Spacer(Modifier.height(16.dp)) // .ctas margin-top s4
        val mergeLabel = if (failed) {
            stringResource(R.string.recovery_retry_merge_label)
        } else {
            stringResource(state.mergeLabelRes!!)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { // gap s2
            AccentCta(
                label = mergeLabel,
                fill = accentFill,
                ink = accentInk,
                enabled = !inFlight,
                modifier = Modifier.weight(1f),
                onClick = { if (!inFlight) onMerge!!.invoke() },
            )
            GhostCta(
                label = stringResource(state.keepRawLabelRes!!),
                fill = ghostFill,
                border = ghostBorder,
                ink = ink.body,
                enabled = !inFlight,
                modifier = Modifier.weight(1f),
                onClick = { if (!inFlight) onKeepRaw!!.invoke() },
            )
        }
    }

    // Vendor link — frozen `.vendor` sits between the accent/ghost row and Discard.
    // The link is caller-supplied (LibraryScreen owns the OEM-intent Context); M8
    // transcribes its POSITION. The borderless `acc-ink` text-link + external-glyph
    // styling stays caller-owned (LibraryScreen + a new glyph are outside M8's file set).
    if (state.showVendorHelpSlot && vendorHelpSlot != null) {
        Spacer(Modifier.height(12.dp)) // .vendor margin-top s3
        vendorHelpSlot()
    }

    // Destructive — terminal, never full-width. Own row (frozen `.ctas{margin-top:8px}`).
    Spacer(Modifier.height(8.dp))
    Row {
        DestructiveCta(
            label = discardLabel,
            backing = destBacking,
            modifier = Modifier.semantics { contentDescription = discardCd },
            onClick = onDiscard,
        )
    }
}

/** Shared CTA chrome: min-height 48 (P5, 200% text can grow it), r-md 14, padding s3/s5. */
private val CtaShape = RoundedCornerShape(14.dp)

@Composable
private fun AccentCta(label: String, fill: Color, ink: Color, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val alpha = if (enabled) 1f else 0.38f // frozen `.cta[disabled]{opacity:.38}`
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(CtaShape)
            .background(fill.copy(alpha = alpha))
            .focusHighlight(CtaShape)
            .clickable(enabled = enabled, role = Role.Button) { onClick() }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(text = label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = ink.copy(alpha = alpha))
    }
}

@Composable
private fun GhostCta(label: String, fill: Color, border: Color, ink: Color, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val alpha = if (enabled) 1f else 0.38f
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(CtaShape)
            .background(fill.copy(alpha = fill.alpha * alpha))
            .border(width = 1.dp, color = border.copy(alpha = border.alpha * alpha), shape = CtaShape)
            .focusHighlight(CtaShape)
            .clickable(enabled = enabled, role = Role.Button) { onClick() }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(text = label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = ink.copy(alpha = alpha))
    }
}

@Composable
private fun DestructiveCta(label: String, backing: Color, modifier: Modifier, onClick: () -> Unit) {
    // Frozen `.cta-dest` (:249–:251): transparent fill, `sev-hard 30%` outline, ink
    // `color-mix(sev-hard 62%, ink-high)`. Same fixed mix the M6 sheet tertiary uses.
    val destInk = RovaWarnings.hard
        .copy(alpha = com.aritr.rova.ui.theme.ResolveInk.MIX_LABEL.toFloat())
        .compositeOver(backing)
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(CtaShape)
            .border(width = 1.dp, color = RovaWarnings.hard.copy(alpha = 0.30f), shape = CtaShape)
            .focusHighlight(CtaShape)
            .clickable(role = Role.Button) { onClick() }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(text = label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = destInk)
    }
}

/**
 * Renders the (at most one) recovery card from [state] plus a small footer line when
 * [RecoveryUiState.hiddenCount] > 0. Empty [RecoveryUiState.cards] renders nothing.
 *
 * `onDiscard` / `onMerge` / `onKeepRaw` receive the visible card's `sessionId`;
 * `vendorHelpSlotFor` is invoked with it. Signature unchanged (M8 is render-only).
 */
@Composable
fun RecoveryCardList(
    state: RecoveryUiState,
    onDiscard: (sessionId: String) -> Unit,
    modifier: Modifier = Modifier,
    vendorHelpSlotFor: (sessionId: String) -> (@Composable () -> Unit)? = { null },
    /** Phase 4.3 — non-null to enable the per-card Merge CTA. Card-local invocation passes the sessionId. */
    onMerge: ((sessionId: String) -> Unit)? = null,
    /** Phase 4.3 — non-null to enable the per-card Keep-as-raw CTA. */
    onKeepRaw: ((sessionId: String) -> Unit)? = null,
) {
    if (state.cards.isEmpty() && state.hiddenCount == 0) return
    Column(modifier = modifier.fillMaxWidth()) {
        state.cards.forEachIndexed { index, card ->
            if (index > 0) Spacer(Modifier.height(12.dp))
            RecoveryCard(
                state = card,
                onDiscard = { onDiscard(card.sessionId) },
                vendorHelpSlot = vendorHelpSlotFor(card.sessionId),
                onMerge = onMerge?.let { fn -> { fn(card.sessionId) } },
                onKeepRaw = onKeepRaw?.let { fn -> { fn(card.sessionId) } },
            )
        }
        if (state.hiddenCount > 0) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.recovery_hidden_footer, state.hiddenCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

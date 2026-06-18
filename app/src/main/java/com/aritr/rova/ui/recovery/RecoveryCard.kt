package com.aritr.rova.ui.recovery

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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import com.aritr.rova.R
import com.aritr.rova.ui.components.ProcessingGlyph
import com.aritr.rova.ui.components.SemanticIcon
import com.aritr.rova.ui.components.focusHighlight
import com.aritr.rova.ui.components.rememberReduceMotion
import com.aritr.rova.ui.text.UiText
import com.aritr.rova.ui.text.resolve
import com.aritr.rova.ui.theme.IconRole
import com.aritr.rova.ui.theme.RovaIcons
import com.aritr.rova.ui.theme.RovaWarnings
import com.aritr.rova.ui.theme.RovaWarningsV3

/**
 * Phase 4 warning re-skin v3 (PR B / Task B1) — Library recovery card.
 *
 * Re-skinned to the v3 chrome canon (ADR-0013, spec
 * `docs/superpowers/specs/2026-05-23-phase-4-warning-reskin-v3-design.md` §3.9):
 *
 *  - Top **glow bloom** (severity-tinted vertical gradient, blurred and
 *    shifted above the visible card) replaces the v2 4dp left stripe.
 *  - **Severity tag chip** with a leading dot; the dot pulses for hard
 *    severity, mirroring `WarningSnoozeChip` (pattern preserves the
 *    same `rememberInfiniteTransition`/`animateFloat` motif).
 *  - **Clip-progress strip** with a numeric "N / N" chip right-aligned
 *    when the underlying `survivingArtifacts` list is non-empty. Each
 *    artifact line is one filled cell; if no artifacts survive, the
 *    strip section is suppressed entirely.
 *  - Destructive **primary CTA** ("Discard recording") in the hard red,
 *    full width across the row. The vendor-help affordance — supplied
 *    by the wiring layer for `KILLED_BY_SYSTEM` cards only — renders
 *    as an **optional extra row** beneath the CTA.
 *
 * Signature preserved byte-for-byte from the v2 composable so
 * `HistoryScreen.kt` call sites (and `RecoveryCardList` below) do not
 * change. `RecoveryViewModel` / `RecoveryUiState` / `RecoveryViewSource`
 * are untouched — this is a render-only re-skin.
 *
 * Deviations from the plan template (`docs/superpowers/plans/2026-05-23-phase-4-warning-reskin-v3.md`
 * Task B1) are intentional and load-bearing — the real `RecoveryCardState`
 * does not carry `tagLabel` / `timestamp` / `description` / `clipsSaved` /
 * `clipsTotal` / `primaryLabel` / `secondaryLabel`, only:
 * `sessionId, kind, title, body, discardLabel, showVendorHelpSlot,
 * survivingArtifacts: List<String>`. Severity is derived from `kind`
 * (HARD ⇐ KILLED_BY_SYSTEM; SOFT ⇐ KILLED_FORCE_STOP, USER_STOPPED).
 * The tag label is derived from `kind` too. Timestamp is omitted (no
 * source field). The clip-progress strip falls back to the artifact-
 * count, which is the closest "recovered-N-segments" signal the
 * existing state exposes.
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

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(RovaWarningsV3.recoveryCardCornerRadius))
            .background(Color(0xFF0B0D14).copy(alpha = 0.94f))
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.08f),
                shape = RoundedCornerShape(RovaWarningsV3.recoveryCardCornerRadius),
            ),
    ) {
        // Top glow bloom — replaces the v2 severity stripe. The
        // verticalGradient paints the severity-tinted peak at y=0
        // (top of the 60dp box) and fades to Transparent at the
        // bottom; combined with the 28dp blur this gives the soft
        // top-edge bloom the v3 spec calls for. (An earlier draft
        // used Modifier.padding(top = (-20).dp) to "shift the peak
        // above the card", but negative padding shrinks the content
        // area downward rather than translating it, and the outer
        // card's clip would have swallowed any real upward offset
        // anyway — so the modifier is intentionally absent here.)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(RovaWarningsV3.recoveryCardGlowHeight)
                .blur(RovaWarningsV3.recoveryCardGlowBlur)
                .background(brush = RovaWarningsV3.recoveryGlow(severityColor)),
        )

        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
        ) {
            // Severity tag chip. Timestamp omitted — RecoveryCardState
            // does not carry one; introducing a derived "moments ago"
            // would couple the composable to a Clock seam, which is
            // explicitly out of scope for B1.
            // ADR-0031 P1a slice 3: a Recovery emblem leads the severity tag. Role =
            // Secondary (textDim) so the quiet emblem does not visually outrank the
            // severity tag. The severity colour stays on the tag dot + card glow, which
            // use RovaWarnings (not the locked RovaSemantics status palette), so mixing
            // the two colour systems on one mark is deliberately avoided.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SemanticIcon(
                    glyph = RovaIcons.Recovery,
                    contentDescription = null, // card title/body carry the meaning
                    modifier = Modifier.size(22.dp),
                    role = IconRole.Secondary,
                )
                SeverityTag(
                    label = stringResource(tagLabelResFor(state.kind)),
                    accent = severityColor,
                    pulsing = isHardSeverity,
                )
            }

            Spacer(Modifier.height(12.dp))

            Text(
                text = stringResource(state.titleRes),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.92f),
            )

            Spacer(Modifier.height(6.dp))

            // Description — the existing data class names this `bodyRes`.
            Text(
                text = stringResource(state.bodyRes),
                style = MaterialTheme.typography.bodySmall,
                // WCAG 2.2 AA SC 1.4.3 (ADR-0020, RECOV-05): 0.45α was ~3.3:1
                // over the elevated card surface; 0.65α clears 4.5:1.
                color = Color.White.copy(alpha = 0.65f),
            )

            // Clip-progress strip — only rendered when there is at
            // least one surviving artifact line. RecoveryCardState
            // does not expose numeric saved/total; using artifact-count
            // as the N is the closest signal available without a state
            // refactor (out of scope for B1).
            if (state.survivingArtifacts.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                ProgressStrip(
                    artifactCount = state.survivingArtifacts.size,
                    accent = severityColor,
                    progress = state.mergeInProgress,
                )
            }

            Spacer(Modifier.height(14.dp))

            // CTA row — collapses to a single destructive button when
            // onMerge/onKeepRaw are null (pre-Phase-4.3 back-compat),
            // or expands to a 3-button stack when both are non-null and
            // the state carries both labels.
            CtaRow(
                state = state,
                onMerge = onMerge,
                onKeepRaw = onKeepRaw,
                onDiscard = onDiscard,
            )

            // Optional extra row — only rendered when the underlying
            // state requests the vendor-help slot AND the caller
            // supplies one. The wiring layer in HistoryScreen.kt
            // supplies an OutlinedButton for KILLED_BY_SYSTEM only;
            // we keep that exact contract (no chevron ExtraRow) so the
            // caller's `OutlinedButton(...)` slot renders intact.
            if (state.showVendorHelpSlot && vendorHelpSlot != null) {
                Spacer(Modifier.height(10.dp))
                vendorHelpSlot()
            }
        }
    }
}

/**
 * Severity colour for the card glow + tag accent. Hard = red for
 * `KILLED_BY_SYSTEM` (the user did not stop, the OS did, and the
 * card asks for vendor-guidance follow-up). Soft = amber for the
 * other two terminator kinds where the user took action or the
 * app was force-stopped.
 */
private fun severityColorFor(kind: RecoveryCardKind): Color = when (kind) {
    RecoveryCardKind.KILLED_BY_SYSTEM -> RovaWarnings.hard
    RecoveryCardKind.KILLED_FORCE_STOP -> RovaWarnings.soft
    RecoveryCardKind.USER_STOPPED -> RovaWarnings.soft
}

/**
 * Tag-chip label id derived from `kind`. Short, uppercased in the chip
 * itself; this helper returns the mixed-case resource id so the same
 * string could be reused in other contexts without re-casing.
 */
@StringRes
private fun tagLabelResFor(kind: RecoveryCardKind): Int = when (kind) {
    RecoveryCardKind.KILLED_BY_SYSTEM -> R.string.recovery_tag_killed_by_system
    RecoveryCardKind.KILLED_FORCE_STOP -> R.string.recovery_tag_force_stopped
    RecoveryCardKind.USER_STOPPED -> R.string.recovery_tag_user_stopped
}

@Composable
private fun SeverityTag(label: String, accent: Color, pulsing: Boolean) {
    // Mirror the WarningSnoozeChip pulse — same animation spec / label
    // pattern, scoped to this composable so the transition is
    // recomposition-stable. The transition + animateFloat are hoisted
    // unconditionally above the `pulsing` branch: rememberInfiniteTransition
    // is a @Composable call and Compose's slot-positional rule forbids
    // calling it inside a conditional (if the same slot is reused for a
    // card whose `pulsing` flag flips between recompositions, Compose
    // emits a runtime warning and the lint check trips). Gating only
    // the value selection costs a single unused Float animator when
    // pulsing == false, which is negligible.
    val infiniteTransition = rememberInfiniteTransition(label = "recovery-tag-pulse")
    val pulsingAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "recovery-tag-pulse-alpha",
    )
    // WCAG 2.2 AA SC 2.3.3 / 2.2.2 (ADR-0020, RECOV-18): suppress the pulse
    // when the OS reduced-motion toggle is on — hold the dot fully visible.
    val reduceMotion = rememberReduceMotion()
    val dotAlpha: Float = if (pulsing && !reduceMotion) pulsingAlpha else 1f

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(accent.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = dotAlpha)),
        )
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = accent.copy(alpha = 0.95f),
        )
    }
}

/**
 * Pure screen-reader label for the progress strip (WCAG 2.2 AA SC 4.1.2,
 * RECOV-16). The cells are decorative `Box`es with no text, so the count is
 * otherwise invisible to TalkBack. `internal` for [RecoveryProgressA11yTest].
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
 * Clip-progress strip with leading header label + numeric `N / N` chip
 * + a row of cells.
 *
 * When [progress] is null (idle / post-merge), all cells are filled and
 * the header reads "CLIPS RECOVERED" (existing behaviour, back-compat).
 * When [progress] is non-null (merge in flight), the header flips to
 * "MERGING" and the filled-cell count is proportional to [progress] ∈
 * [0,1], giving a discrete progress bar effect over the segment cells.
 *
 * The data layer does not expose a saved/total split today — every
 * surviving artifact is, by definition, a recovered segment, so all
 * cells render as saved when idle. When a richer signal lands (e.g.
 * expected vs recovered segment counts), only the `clipsSaved` /
 * `clipsTotal` decomposition here needs to change.
 */
@Composable
private fun ProgressStrip(artifactCount: Int, accent: Color, progress: Float? = null) {
    val cellCount = artifactCount.coerceAtLeast(1)
    val filledCells = if (progress != null) {
        (progress.coerceIn(0f, 1f) * cellCount).toInt()
    } else {
        cellCount
    }
    val headerLabel = stringResource(
        if (progress != null) R.string.recovery_progress_header_merging
        else R.string.recovery_progress_header_recovered,
    )
    val progressCd = recoveryProgressContentDescription(cellCount, filledCells, progress != null).resolve()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // RECOV-16 (SC 4.1.2): merge the strip into one node with a spoken
            // count — the decorative cells carry no text otherwise.
            // RECOV-17 (SC 4.1.3): polite live region so merge progress is
            // announced as filled cells roll forward (discrete, not per-tick).
            .semantics(mergeDescendants = true) {
                contentDescription = progressCd
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (progress != null) {
                    // Icon P2 Track A — branded animated merge glyph beside the
                    // "Merging" header while a recovery merge runs (ADR-0031 §6/§8).
                    // Decorative; the strip's polite live region announces progress.
                    ProcessingGlyph(size = 16.dp)
                }
                Text(
                    text = headerLabel,
                    style = MaterialTheme.typography.labelSmall,
                    // WCAG 2.2 AA (ADR-0020, audit RECOV-06): 0.36α was ~3:1 over the
                    // elevated card — below the 4.5:1 SC 1.4.3 bar. 0.70α ≈ 6.83:1.
                    // See ContrastMathTest.
                    color = Color.White.copy(alpha = 0.70f),
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.White.copy(alpha = 0.06f))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.recovery_progress_chip, artifactCount, artifactCount),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.78f),
                    modifier = Modifier.defaultMinSize(
                        minWidth = RovaWarningsV3.recoveryNumericChipMinWidth,
                    ),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(RovaWarningsV3.recoveryProgressCellGap),
        ) {
            repeat(cellCount) { index ->
                val isFilled = index < filledCells
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(RovaWarningsV3.recoveryProgressCellHeight)
                        .clip(RoundedCornerShape(RovaWarningsV3.recoveryProgressCellRadius))
                        .background(accent.copy(alpha = if (isFilled) 0.55f else 0.15f)),
                )
            }
        }
    }
}

/**
 * Full-width destructive primary CTA. Hard-red fill + white text.
 * Mirrors the v3 PrimaryButton chrome (40dp height, 12dp radius)
 * but uses `RovaWarnings.hard` instead of `0xFF5B7FFF` because this
 * single CTA is destructive ("Discard recording"), not advisory.
 */
@Composable
private fun DestructiveCta(label: String, onClick: () -> Unit, modifier: Modifier) {
    Row(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(RovaWarnings.hard)
            .focusHighlight(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button) { onClick() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )
    }
}

/**
 * Phase 4.3 — CTA row. Collapses to the single destructive Discard
 * button when [onMerge]/[onKeepRaw] are null (pre-Phase-4.3 back-compat).
 * Expands to a 3-button stack when all four conditions are met:
 *   1. [onMerge] != null
 *   2. [onKeepRaw] != null
 *   3. [state.mergeLabelRes] != null
 *   4. [state.keepRawLabelRes] != null
 *
 * Discard is always LAST in the stack — permanent destructive action
 * anchored at the bottom regardless of stack height.
 *
 * Button enabled state: [state.mergeInProgress] != null means a merge
 * is in flight — Merge + Keep-as-raw are disabled (visual + click-guard);
 * Discard remains tappable.
 *
 * Retry flavour: when [state.mergeFailedReason] != null, the primary
 * CTA label flips to "Retry merge" while the target remains [onMerge].
 */
@Composable
private fun CtaRow(
    state: RecoveryCardState,
    onMerge: (() -> Unit)?,
    onKeepRaw: (() -> Unit)?,
    onDiscard: () -> Unit,
) {
    val showThreeCtaStack = onMerge != null && onKeepRaw != null &&
        state.mergeLabelRes != null && state.keepRawLabelRes != null
    val inFlight = state.mergeInProgress != null
    val discardLabel = stringResource(state.discardLabelRes)
    val discardCd = stringResource(R.string.recovery_discard_cd, discardLabel)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // WCAG 2.2 AA SC 3.3.1 / 4.1.3 (ADR-0020, RECOV-14): the last merge
        // failure reason was tracked in state (it flips the CTA to "Retry
        // merge") but never shown. Render it in the error colour and announce
        // it assertively so the user knows why the retry is being offered.
        val failReason = state.mergeFailedReason
        if (failReason != null && !inFlight) {
            Text(
                text = stringResource(R.string.recovery_merge_failed_prefix, failReason),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
        if (showThreeCtaStack) {
            // 1. Merge segments (primary; ink fill). Label flips to
            //    "Retry merge" when last merge failed.
            val mergeLabel = if (state.mergeFailedReason != null) {
                stringResource(R.string.recovery_retry_merge_label)
            } else {
                stringResource(state.mergeLabelRes!!)
            }
            PrimaryMergeCta(
                label = mergeLabel,
                enabled = !inFlight,
                onClick = { if (!inFlight) onMerge!!.invoke() },
            )
            // 2. Keep as raw clips (ghost; hairline border).
            GhostCta(
                label = stringResource(state.keepRawLabelRes!!),
                enabled = !inFlight,
                onClick = { if (!inFlight) onKeepRaw!!.invoke() },
            )
        }
        // 3. Discard recording — always present, last in stack.
        DestructiveCta(
            label = discardLabel,
            onClick = onDiscard,
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = discardCd
                },
        )
    }
}

/**
 * Phase 4.3 — primary advisory-ink filled CTA for Merge segments.
 * Uses [RovaWarnings.advisory] (`0xFF5B7FFF`) — the same token the
 * existing file already imports for the advisory severity colour.
 * Height + corner radius mirror the [DestructiveCta] chrome (40dp / 12dp).
 */
@Composable
private fun PrimaryMergeCta(label: String, enabled: Boolean, onClick: () -> Unit) {
    val accent = RovaWarnings.advisory  // 0xFF5B7FFF — v3 primary ink token
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(accent.copy(alpha = if (enabled) 1f else 0.40f))
            .focusHighlight(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button) { onClick() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = Color.White.copy(alpha = if (enabled) 1f else 0.55f),
        )
    }
}

/**
 * Phase 4.3 — ghost (hairline-border) CTA for Keep as raw clips.
 * Transparent fill + 1dp white-alpha border. Height + corner radius
 * mirror [DestructiveCta] (40dp / 12dp).
 */
@Composable
private fun GhostCta(label: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = if (enabled) 0.20f else 0.10f),
                shape = RoundedCornerShape(12.dp),
            )
            .focusHighlight(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button) { onClick() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = Color.White.copy(alpha = if (enabled) 0.80f else 0.40f),
        )
    }
}

/**
 * Renders the (at most one) recovery card from [state] plus a small
 * footer line when [RecoveryUiState.hiddenCount] > 0 so the user
 * sees that older interrupted sessions exist without a stacking red
 * wall. Empty [RecoveryUiState.cards] renders nothing — the consumer
 * can place this composable unconditionally.
 *
 * `onDiscard` receives the visible card's `sessionId` so the wiring
 * layer can route to `RecoveryViewModel.dismiss(sessionId)`.
 *
 * `vendorHelpSlotFor` is invoked with the visible card's `sessionId`;
 * consumers without a vendor helper pass `{ _ -> null }` (or omit;
 * the default is `null`).
 *
 * Phase 4 v3 re-skin (PR B / B1): signature unchanged. Footer text
 * tone shifted from `onSurfaceVariant` to a low-alpha white so it
 * sits cleanly under the v3 dark-glass card body.
 *
 * Phase 4.3: [onMerge] and [onKeepRaw] default to null — back-compat
 * for callers not yet wired to the merge service. When non-null, each
 * invocation receives the card's `sessionId` for routing.
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
                color = Color.White.copy(alpha = 0.45f),
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
}

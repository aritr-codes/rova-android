package com.aritr.rova.ui.recovery

import com.aritr.rova.ui.theme.RovaIcon
import com.aritr.rova.ui.theme.RovaIcons

/**
 * Pure concept→status-glyph choice for the Recovery surface (ADR-0031, icon 5b-4).
 * Compose/Android-free so it is JVM-unit-testable under `isReturnDefaultValues = true`,
 * mirroring [com.aritr.rova.ui.warnings.WarningIconSpec].
 *
 * Returns a [RovaIcon] (glyph + locked [com.aritr.rova.ui.theme.IconStatus]) when the kind has a
 * distinct status meaning, or `null` when the generic [RovaIcons.Recovery] emblem is the right
 * choice and the render site should keep its default role-tinted appearance.
 *
 * The `when` is exhaustive over [RecoveryCardKind] with NO `else` branch, so adding a new kind
 * forces a compile error here and a matching test update — mirroring the enforcement pattern used
 * in [severityColorFor] and [tagLabelResFor] inside RecoveryCard.kt.
 */
internal object RecoveryIconSpec {

    /**
     * Returns the locked-status [RovaIcon] for [kind], or `null` to keep the generic emblem.
     *
     * Reasoning per case:
     *  - [RecoveryCardKind.KILLED_BY_SYSTEM]: the OS killed the recording without user intent —
     *    this is the canonical "interrupted" concept, so [RovaIcons.Interrupted]
     *    (locked [com.aritr.rova.ui.theme.IconStatus.Interrupted] → escalating orange) surfaces it.
     *  - [RecoveryCardKind.KILLED_FORCE_STOP]: the user force-stopped the app (or it was
     *    force-stopped by another party) — still an involuntary interruption of the recording
     *    pipeline, so [RovaIcons.Interrupted] applies for the same reason.
     *  - [RecoveryCardKind.USER_STOPPED]: the user deliberately stopped the recording. The session
     *    terminated as intended; the recovery card exists because raw segments were not yet merged.
     *    There is no "interrupted" connotation and no "recovered" state yet (the card is offering
     *    to merge or discard, not confirming recovery). Returning `null` keeps the generic
     *    [RovaIcons.Recovery] emblem with its role = Secondary tint, which is correct here.
     */
    fun statusGlyphFor(kind: RecoveryCardKind): RovaIcon? = when (kind) {
        RecoveryCardKind.KILLED_BY_SYSTEM -> RovaIcons.Interrupted
        RecoveryCardKind.KILLED_FORCE_STOP -> RovaIcons.Interrupted
        RecoveryCardKind.USER_STOPPED -> null
    }
}

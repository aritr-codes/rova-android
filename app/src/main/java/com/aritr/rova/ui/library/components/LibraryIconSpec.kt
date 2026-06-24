package com.aritr.rova.ui.library.components

import com.aritr.rova.data.StopReason
import com.aritr.rova.ui.library.LibraryBadge
import com.aritr.rova.ui.theme.IconStatus
import com.aritr.rova.ui.theme.RovaGlyph
import com.aritr.rova.ui.theme.RovaGlyphs
import com.aritr.rova.ui.theme.RovaIcon
import com.aritr.rova.ui.theme.RovaIcons

/**
 * Pure state→glyph/status choices for the Library icon migration (ADR-0031, UI Phase 2 PR-4).
 * Kept Compose/Android-free so the decisions are JVM-unit-testable under `isReturnDefaultValues`,
 * mirroring the [com.aritr.rova.ui.screens.captureGlyphFor] picker-glyph pattern.
 */
internal object LibraryIconSpec {

    /** Favorite toggle: the filled star when set, the outline star when unset. */
    fun favoriteGlyph(isFavorite: Boolean): RovaGlyph =
        if (isFavorite) RovaIcons.FavoriteOn else RovaIcons.Favorite

    /**
     * Delete is the locked danger red ([IconStatus.Danger] → `RovaSemantics.error`) only in a
     * genuinely destructive context (the item sheet's quarantined Danger row). In the batch
     * action strip it is a neutral action among peers (`role = Default`), so the status is null.
     */
    fun deleteStatus(destructive: Boolean): IconStatus? =
        if (destructive) IconStatus.Danger else null

    /**
     * Status badge → locked [RovaIcon] carrying both the glyph vector and the [IconStatus] color
     * lock (ADR-0031 §4). Exhaustive — no else branch so new [LibraryBadge] values are caught at
     * compile time. Callers render via the single-layer [SemanticIcon] imageVector overload with
     * `status = badgeGlyph(badge).status`; no raw tint, no role (status wins).
     */
    fun badgeGlyph(badge: LibraryBadge, stopReason: StopReason? = null): RovaIcon = when (badge) {
        LibraryBadge.RECOVERED -> RovaIcons.Recovered
        LibraryBadge.INTERRUPTED -> RovaIcons.Interrupted
        // Reason-aware safety glyph: thermometer for THERMAL, storage for LOW_STORAGE, both with
        // the locked Interrupted color. No `.copy` — checkStatusColorLocked clean.
        LibraryBadge.AUTO_STOPPED -> when (stopReason) {
            StopReason.LOW_STORAGE -> RovaIcon(RovaGlyphs.Storage.outline, status = IconStatus.Interrupted)
            else -> RovaIcon(RovaGlyphs.Thermal.outline, status = IconStatus.Interrupted)
        }
    }
}

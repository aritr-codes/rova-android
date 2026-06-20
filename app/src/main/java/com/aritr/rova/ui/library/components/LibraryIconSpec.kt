package com.aritr.rova.ui.library.components

import com.aritr.rova.ui.theme.IconStatus
import com.aritr.rova.ui.theme.RovaGlyph
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
}

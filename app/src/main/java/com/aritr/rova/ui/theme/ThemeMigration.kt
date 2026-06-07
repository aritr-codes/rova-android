package com.aritr.rova.ui.theme

/**
 * Pure, deterministic migration from the shipped `theme_mode` (#80,
 * ThemeMode.name) to the new `theme_selection` (ThemeSelection.name). Runs in
 * the synchronous SharedPreferences read path so the first frame never flashes a
 * default (ADR-0028 §1.2). The old key is left untouched for one release.
 */
object ThemeMigration {

    /** Old `theme_mode` raw string -> new selection. */
    fun migrate(oldRaw: String?): ThemeSelection = when (oldRaw) {
        "SYSTEM" -> ThemeSelection.FOLLOW_SYSTEM
        "DARK" -> ThemeSelection.AURORA
        "LIGHT" -> ThemeSelection.DAYLIGHT
        else -> ThemeSelection.FOLLOW_SYSTEM
    }

    /** Parse a stored new-key value, tolerant of null/unknown. */
    private fun parseNew(newRaw: String?): ThemeSelection? =
        ThemeSelection.entries.firstOrNull { it.name == newRaw }

    /**
     * Resolve the effective selection: prefer a valid new-key value; otherwise
     * migrate the old key; otherwise [ThemeSelection.DEFAULT]'s follow-system
     * fallback ([migrate] of null).
     */
    fun resolve(newRaw: String?, oldRaw: String?): ThemeSelection =
        parseNew(newRaw) ?: migrate(oldRaw)
}

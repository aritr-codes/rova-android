package com.aritr.rova.ui.theme

/** User-selectable app theme. SYSTEM follows the OS light/dark setting. */
enum class ThemeMode { SYSTEM, DARK, LIGHT }

/** Pure seam: resolve the effective dark/light decision for the current frame. */
fun resolveDarkTheme(mode: ThemeMode, systemDark: Boolean): Boolean = when (mode) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
}

/**
 * Pure coercion for the persisted [ThemeMode] name. Unknown / null / missing
 * values fall back to [ThemeMode.SYSTEM] — defends against stale or
 * version-mismatched reads, mirroring the `RovaSettings.mode` coercion intent.
 */
fun themeModeFromStorage(raw: String?): ThemeMode =
    ThemeMode.entries.firstOrNull { it.name == raw } ?: ThemeMode.SYSTEM

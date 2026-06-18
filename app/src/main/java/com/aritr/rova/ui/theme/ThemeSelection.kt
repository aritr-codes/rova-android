package com.aritr.rova.ui.theme

/**
 * Flat theme selection: Follow-System plus 12 named palettes (ADR-0028 §1).
 * Declaration order is load-bearing — Wave-1 (the signature six) are the first
 * six palettes after [FOLLOW_SYSTEM]; Wave-2 (extended six) are defined here but
 * not yet exposed in the picker.
 */
enum class ThemeSelection {
    FOLLOW_SYSTEM,
    AURORA, TIDE, JADE, DUSK, ECLIPSE, DAYLIGHT,        // Wave 1 (Signature 6)
    BLOSSOM, CORAL, MEADOW, COBALT, ORCHID, GRAPHITE;   // Wave 2 (Extended 6)

    /**
     * Resolve [FOLLOW_SYSTEM] to a concrete palette using the OS dark flag
     * (dark → Aurora, light → Daylight). A concrete selection returns itself.
     */
    fun resolveConcrete(systemDark: Boolean): ThemeSelection = when (this) {
        FOLLOW_SYSTEM -> if (systemDark) AURORA else DAYLIGHT
        else -> this
    }

    companion object {
        /** Default when no preference is stored. */
        val DEFAULT: ThemeSelection = AURORA

        /** Options surfaced in the picker in PR1 (Follow-System + Wave-1). */
        val wave1Picker: List<ThemeSelection> = listOf(
            FOLLOW_SYSTEM, AURORA, TIDE, JADE, DUSK, ECLIPSE, DAYLIGHT,
        )

        /** Full picker — Follow-System + all 12 palettes (engine slice 1, 2026-06-18). */
        val allPicker: List<ThemeSelection> = listOf(FOLLOW_SYSTEM) + entries.filter { it != FOLLOW_SYSTEM }
    }
}

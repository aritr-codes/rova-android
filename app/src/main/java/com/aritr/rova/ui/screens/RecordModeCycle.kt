package com.aritr.rova.ui.screens

/**
 * Slice B — pure helper for the Mode tap-cycle chip (RecordSettingsCard ·
 * mockups/new_uiux/01d-record-home-slice-b.html · Variant C). Advances
 * one step through the segmented Mode strip's order. The unknown-string
 * else-arm keeps the cycle deterministic if `RovaSettings.mode` ever
 * holds a value outside the three known strings — defensive only;
 * `setMode(String)` is the only writer and only writes the three values.
 *
 * Tested by [com.aritr.rova.ui.screens.RecordModeCycleTest] (pure JVM).
 *
 * Project precedent for pure-helper test seams: [loopPillContent],
 * [hudStatusPillContent], [recordFabState] in RecordChrome.kt.
 */
internal fun cycleModeNext(current: String): String = when (current) {
    "Portrait" -> "Landscape"
    "Landscape" -> "PortraitLandscape"
    "PortraitLandscape" -> "Portrait"
    else -> "Portrait"
}

package com.aritr.rova.data

import com.aritr.rova.ui.screens.RecordSettingBounds

/**
 * ADR-0026 — the four read-only, code-defined recording presets. A preset is a
 * config bundle of {duration, interval, loopCount, resolution} ONLY; orientation
 * is orthogonal and never carried here (enforced by `checkPresetNoOrientation`).
 *
 * These are never persisted — they are retunable per release and reach all users.
 * Values are intentionally tunable constants; they are sensible defaults for this
 * periodic-capture paradigm, validated later against beta telemetry.
 */
object BuiltInPresets {

    const val DEFAULT_ID = "builtin.standard"

    val all: List<RovaPreset> = listOf(
        RovaPreset(name = "Quick Sample", duration = 10, intervalSeconds = 60, loopCount = 10,
            resolution = QualityPresets.FHD, id = "builtin.quick_sample", isBuiltIn = true),
        RovaPreset(name = "Standard", duration = 30, intervalSeconds = 120, loopCount = 20,
            resolution = QualityPresets.FHD, id = DEFAULT_ID, isBuiltIn = true),
        RovaPreset(name = "Long Session", duration = 60, intervalSeconds = 300, loopCount = 50,
            resolution = QualityPresets.HD, id = "builtin.long_session", isBuiltIn = true),
        RovaPreset(name = "Continuous", duration = 60, intervalSeconds = 0,
            loopCount = RecordSettingBounds.REPEATS_CONTINUOUS,
            resolution = QualityPresets.HD, id = "builtin.continuous", isBuiltIn = true),
    )

    /** The Standard preset (first-run default). */
    val default: RovaPreset = all.first { it.id == DEFAULT_ID }
}

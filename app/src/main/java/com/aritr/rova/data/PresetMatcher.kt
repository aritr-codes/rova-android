package com.aritr.rova.data

/**
 * ADR-0026 — classifies the current recording config against the built-in
 * presets. Returns the matching `builtin.*` id, or null (rendered as "Custom").
 *
 * "Active preset = built-in value match, else Custom" — selection is by config,
 * not identity. A user custom whose values equal a built-in resolves to the
 * built-in id. Resolution is compared canonically so legacy aliases ("1080p",
 * "UHD") match; loopCount is compared exactly, including the -1 continuous sentinel.
 */
object PresetMatcher {
    fun match(duration: Int, interval: Int, loopCount: Int, resolution: String?): String? {
        val res = QualityPresets.canonicalizeOrDefault(resolution)
        return BuiltInPresets.all.firstOrNull { p ->
            p.duration == duration &&
                p.interval == interval &&
                p.loopCount == loopCount &&
                QualityPresets.canonicalizeOrDefault(p.resolution) == res
        }?.id
    }
}

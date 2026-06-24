package com.aritr.rova.data

/**
 * ADR-0026 — classifies the current recording config against the built-in
 * presets (and, via [matchActive], the user's customs). Returns the matching
 * `builtin.*`/`custom.*` id, or null (rendered as "Custom").
 *
 * "Active preset = value match, built-ins first" — selection is by config, not
 * identity. A user custom whose values equal a built-in resolves to the built-in
 * id. Resolution is compared canonically so legacy aliases ("1080p", "UHD")
 * match; loopCount is compared exactly, including the -1 continuous sentinel.
 * A null or unrecognized resolution yields no match (Custom) — we never coerce
 * unknown input to the FHD default, which would be a false positive (review).
 */
object PresetMatcher {
    /** Active built-in id for this config, or null. */
    fun match(duration: Int, intervalSeconds: Int, loopCount: Int, resolution: String?): String? =
        match(BuiltInPresets.all, duration, intervalSeconds, loopCount, resolution)

    /** First value-match id within [presets] (canonicalized resolution), or null. */
    fun match(
        presets: List<RovaPreset>,
        duration: Int,
        intervalSeconds: Int,
        loopCount: Int,
        resolution: String?,
    ): String? {
        val res = QualityPresets.canonicalize(resolution) ?: return null
        return presets.firstOrNull { p ->
            p.duration == duration &&
                p.intervalSeconds == intervalSeconds &&
                p.loopCount == loopCount &&
                QualityPresets.canonicalizeOrDefault(p.resolution) == res
        }?.id
    }

    /** Built-in match takes precedence; else first custom value-match; else null. */
    fun matchActive(
        customs: List<RovaPreset>,
        duration: Int,
        intervalSeconds: Int,
        loopCount: Int,
        resolution: String?,
    ): String? =
        match(duration, intervalSeconds, loopCount, resolution)
            ?: match(customs, duration, intervalSeconds, loopCount, resolution)
}

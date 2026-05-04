package com.aritr.rova.data

/**
 * Slice 12 — single source of truth for the canonical video-quality
 * vocabulary that the Record picker, [RovaSettings] default, the
 * [SessionManifest.resolution] field, the CameraX `QualitySelector`
 * mapping in `RovaRecordingService`, the [StorageEstimator] byte-rate
 * table, and the [QualityLabels] History classifier all share.
 *
 * Surface labels are `"SD" / "HD" / "FHD" / "4K"` with [DEFAULT] = FHD
 * and picker order [PICKER_ORDER] = `SD, HD, FHD, 4K` (low → high).
 *
 * [canonicalize] folds historical aliases (`"480p"`, `"720p"`,
 * `"1080p"`, `"2160p"`, `"UHD"` — case-insensitive) onto these labels
 * so callers that persist or accept legacy strings can route through
 * one helper instead of duplicating the alias map. It returns `null`
 * on unrecognized input so the caller chooses the fallback strategy:
 * the byte-rate table treats unknown as FHD; the CameraX mapping
 * deliberately does NOT canonicalize so non-canonical settings still
 * fall through to `Quality.FHD` exactly as before.
 */
object QualityPresets {

    const val SD = "SD"
    const val HD = "HD"
    const val FHD = "FHD"
    const val UHD = "4K"

    const val DEFAULT = FHD

    val PICKER_ORDER: List<String> = listOf(SD, HD, FHD, UHD)

    fun canonicalize(raw: String?): String? = when (raw?.uppercase()) {
        SD, "480P" -> SD
        HD, "720P" -> HD
        FHD, "1080P" -> FHD
        UHD, "UHD", "2160P" -> UHD
        else -> null
    }

    fun canonicalizeOrDefault(raw: String?, default: String = DEFAULT): String =
        canonicalize(raw) ?: default
}

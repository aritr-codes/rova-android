package com.aritr.rova.data

/**
 * Single source of truth for the canonical quality labels the app
 * surfaces in its picker, manifest config, and History display:
 * `"SD" / "HD" / "FHD" / "4K"`.
 *
 * Pre-Slice-11 the History row label was derived ad-hoc with a
 * separate vocabulary (`"480p" / "720p" / "1080p" / "4K"`), so a
 * CameraX QualitySelector fallback would still appear under the
 * same label the user originally picked. Routing both the
 * requested-side picker and the actual-output read
 * ([com.aritr.rova.ui.screens.VideoMetadataUtils.extractMetadata])
 * through this helper keeps a fallback visible: a session
 * requested as `"FHD"` that recorded as 1280×720 displays as
 * `"HD"` in History.
 *
 * The bucket is decided by orienting the frame so the longer
 * axis is the "width" and the shorter is the "height", then
 * checking both against the landscape thresholds for each tier:
 * - `4K`  ⇒ `max(w,h) >= 3840  AND  min(w,h) >= 2160`
 * - `FHD` ⇒ `max(w,h) >= 1920  AND  min(w,h) >= 1080`
 * - `HD`  ⇒ `max(w,h) >= 1280  AND  min(w,h) >=  720`
 * - else  ⇒ `SD`
 *
 * The orientation-normalize step matters: a 720×1280 portrait
 * recording is `Quality.HD` (i.e. 720p rotated), not FHD. A naive
 * "either axis crosses the FHD height threshold" rule would
 * misclassify it as FHD because `1280 >= 1080` — that is the bug
 * QualityLabelsTest's portrait cases pin.
 *
 * Returns `null` for invalid inputs (`<= 0`) so the caller can
 * surface a placeholder instead of misclassifying the artifact
 * as `"SD"`.
 */
internal object QualityLabels {

    // Slice 12 — labels live in [QualityPresets]. These re-exports
    // keep the History/picker vocabulary in lockstep without a second
    // string table to drift.
    const val SD = QualityPresets.SD
    const val HD = QualityPresets.HD
    const val FHD = QualityPresets.FHD
    const val UHD = QualityPresets.UHD

    fun forDimensions(widthPx: Int, heightPx: Int): String? {
        if (widthPx <= 0 || heightPx <= 0) return null
        val longSide = if (widthPx >= heightPx) widthPx else heightPx
        val shortSide = if (widthPx >= heightPx) heightPx else widthPx
        return when {
            longSide >= 3840 && shortSide >= 2160 -> UHD
            longSide >= 1920 && shortSide >= 1080 -> FHD
            longSide >= 1280 && shortSide >= 720 -> HD
            else -> SD
        }
    }
}

package com.aritr.rova.service.dualrecord.internal

import android.graphics.SurfaceTexture
import android.hardware.camera2.params.StreamConfigurationMap
import android.util.Size

/**
 * Picks the dual-camera source resolution that lets
 * [AspectFitMath.buildSideAspectCrop] produce both a 9:16 PORTRAIT crop
 * and a 16:9 LANDSCAPE crop without stretching, without bars, and (for
 * PORTRAIT) at the encoder's native 1080×1920 resolution.
 *
 * Strategy (in order):
 *   1. Largest 4:3 landscape mode with shortEdge ≥ 1920 (PORTRAIT pixel-
 *      perfect — short edge ≥ 1920 means the PORTRAIT vertical strip
 *      after `pivot-scale(27/64, 1, 1)` covers the full 1920-pixel
 *      encoder height).
 *   2. Largest 4:3 landscape mode below the threshold (PORTRAIT upscales
 *      — soft but acceptable; the iOS DualShot pattern accepts mild
 *      quality degradation over bars).
 *   3. Hint `Size(1920, 1440)` to CameraX when no 4:3 modes exist (≤ ~1 %
 *      of devices per the competitive brief) — CameraX picks closest and
 *      we accept a subtle under-fill / over-crop on those devices.
 *
 * Pure-helper seam: [resolveDualCameraSizeFrom] takes `List<Pair<Int,Int>>`
 * (not `Size`) so JVM unit tests don't need to construct `android.util.Size`
 * which is JVM-stubbed under `testOptions.unitTests.isReturnDefaultValues
 * = true`. Same Size-avoidance precedent as Phase 6.1a's BitrateTable /
 * DualVideoRecorderConfig Int-pair overloads.
 *
 * See `docs/adr/0009-dualshot-4-3-source-aspect.md`.
 */
internal object DualCameraSizeResolver {

    private const val PIXEL_PERFECT_SHORT_EDGE = 1920
    private const val FALLBACK_HINT_W = 1920
    private const val FALLBACK_HINT_H = 1440

    /**
     * Android-facing wrapper — extracts `SurfaceTexture` output sizes from
     * the camera's [StreamConfigurationMap] and delegates to
     * [resolveDualCameraSizeFrom]. Not JVM-tested (depends on
     * `android.util.Size`).
     */
    fun resolveDualCameraSize(map: StreamConfigurationMap): Size {
        val sizes = map.getOutputSizes(SurfaceTexture::class.java)
            ?.map { it.width to it.height }
            ?: emptyList()
        val (w, h) = resolveDualCameraSizeFrom(sizes)
        return Size(w, h)
    }

    /**
     * Pure (no Android deps) — JVM-testable. Takes a list of
     * `(width, height)` pairs and returns the chosen `(width, height)`.
     */
    internal fun resolveDualCameraSizeFrom(sizes: List<Pair<Int, Int>>): Pair<Int, Int> {
        val landscape4_3 = sizes.filter { isLandscape4to3(it.first, it.second) }
        if (landscape4_3.isEmpty()) return FALLBACK_HINT_W to FALLBACK_HINT_H

        val pixelPerfect = landscape4_3.filter { it.second >= PIXEL_PERFECT_SHORT_EDGE }
        val candidates = if (pixelPerfect.isNotEmpty()) pixelPerfect else landscape4_3

        return candidates.maxByOrNull { it.first.toLong() * it.second.toLong() }!!
    }

    /**
     * 4:3 landscape: `width × 3 == height × 4` (exact, integer math) and
     * `width > height`. The ±1 px tolerance the spec mentions is reserved
     * for future floating-point rounding cases (e.g., if CameraX ever
     * starts exposing sub-pixel sizes), but integer 4:3 sensor modes
     * never produce off-by-one outputs in practice.
     */
    private fun isLandscape4to3(width: Int, height: Int): Boolean {
        if (width <= height) return false
        return width * 3 == height * 4
    }
}

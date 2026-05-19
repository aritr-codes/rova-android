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
 * Contract (no soft-degrade — see ADR-0009):
 *   1. Pick the largest landscape-oriented 4:3 mode whose short edge is
 *      ≥ 1920. This guarantees PORTRAIT pixel-perfectness — the
 *      `pivot-scale(27/64, 1, 1)` crop covers the full 1920-pixel encoder
 *      height with no upscale.
 *   2. Otherwise hint `Size(1920, 1440)` to CameraX. CameraX selects the
 *      device's closest available mode. On the rare device with no
 *      landscape 4:3 modes at all (≤ ~1 % per the competitive brief) and
 *      on devices whose only landscape 4:3 modes are below the
 *      pixel-perfect threshold (e.g. max 1280×960), this defers the choice
 *      to CameraX — never returning a sub-threshold size from the
 *      resolver itself.
 *
 * No 4:3 aspect-rounding tolerance: integer 4:3 sensor modes do not
 * produce off-by-one outputs in practice, so the eligibility check is
 * exact integer equality. Sub-pixel sizes (if Camera2 ever exposes them)
 * are out of scope for this resolver.
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
     * Pure (no Android deps) — JVM-testable. Returns the largest
     * landscape-oriented 4:3 candidate with short edge ≥ 1920, or
     * `Size(1920, 1440)` otherwise.
     */
    internal fun resolveDualCameraSizeFrom(sizes: List<Pair<Int, Int>>): Pair<Int, Int> {
        val eligible = sizes
            .asSequence()
            .filter { isLandscape4to3(it.first, it.second) }
            .filter { it.second >= PIXEL_PERFECT_SHORT_EDGE }
            .toList()
        if (eligible.isEmpty()) return FALLBACK_HINT_W to FALLBACK_HINT_H
        return eligible.maxByOrNull { it.first.toLong() * it.second.toLong() }!!
    }

    /**
     * Landscape 4:3: `width > height` AND `width * 3 == height * 4`
     * (exact integer ratio). Portrait-oriented 4:3 (e.g. 1920×2560) is
     * intentionally rejected — the dual-camera session is fixed-landscape
     * (`Surface.ROTATION_0` in `setupDualCamera`); sampling a portrait
     * source would force the PORTRAIT zone to read sideways.
     */
    private fun isLandscape4to3(width: Int, height: Int): Boolean {
        if (width <= height) return false
        return width * 3 == height * 4
    }
}

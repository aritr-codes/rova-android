package com.aritr.rova.service.dualrecord.internal

import com.aritr.rova.service.dualrecord.VideoSide

/**
 * Phase 6.1c — pure-JVM math seam for the DualShot render pipeline.
 * Holds (1) aspect-fit letterbox viewport math and (2) cropMatrix
 * construction for the per-target UV transform.
 *
 * Pulled out of [EglRouter] so the math can be pinned by JVM unit
 * tests without an EGL context. See
 * `docs/superpowers/specs/2026-05-17-phase-6.1c-dualshot-render-design.md`
 * §5.4 for the contract.
 */
internal object AspectFitMath {

    /**
     * Aspect-fit viewport inside a surface of [surfaceW] × [surfaceH]
     * for content of aspect [contentAspect] (= width/height). Returns
     * `[x, y, w, h]` for `glViewport`. Pillar-box bars when content is
     * taller than surface; letterbox bars when content is wider than
     * surface. Surface dims must be positive; contentAspect must be > 0.
     */
    fun computeFitViewport(surfaceW: Int, surfaceH: Int, contentAspect: Float): IntArray {
        require(surfaceW > 0 && surfaceH > 0) { "surface dims must be > 0; was ${surfaceW}x${surfaceH}" }
        require(contentAspect > 0f) { "contentAspect must be > 0; was $contentAspect" }
        val surfaceAspect = surfaceW.toFloat() / surfaceH.toFloat()
        return if (contentAspect >= surfaceAspect) {
            // Content wider or equal: fit width, letterbox top+bottom.
            val viewportH = (surfaceW / contentAspect).toInt()
            val viewportY = (surfaceH - viewportH) / 2
            intArrayOf(0, viewportY, surfaceW, viewportH)
        } else {
            // Content taller: fit height, pillar-box left+right.
            val viewportW = (surfaceH * contentAspect).toInt()
            val viewportX = (surfaceW - viewportW) / 2
            intArrayOf(viewportX, 0, viewportW, surfaceH)
        }
    }

    /**
     * Builds the side-specific UV center-crop matrix into [out].
     *  - [VideoSide.PORTRAIT]: pivot-scale around (0.5, 0.5) by (9/16, 1, 1)
     *    — center-crop a vertical 9:16 column from a 16:9 source.
     *  - [VideoSide.LANDSCAPE]: identity (full 16:9 source used as-is).
     *
     * `out` must be a length-16 float array; contents are overwritten.
     *
     * Phase 6.1c D-deviation from plan Task 2: inline pure-Kotlin mat4
     * math (no `android.opengl.Matrix.*`) per spec §5.4 "no Android
     * dependencies" — plan's choice silently no-ops under
     * `testOptions.unitTests.isReturnDefaultValues = true`. Matrix
     * constants derived directly from the pivot-scale composition
     * `T(0.5,0.5,0) × S(9/16,1,1) × T(-0.5,-0.5,0)`.
     */
    fun buildSideAspectCrop(side: VideoSide, out: FloatArray) {
        require(out.size == 16) { "out must be length 16, was ${out.size}" }
        // Identity baseline.
        for (i in 0..15) out[i] = 0f
        out[0] = 1f; out[5] = 1f; out[10] = 1f; out[15] = 1f
        when (side) {
            VideoSide.LANDSCAPE -> {
                // Identity — landscape sources the full source rectangle.
            }
            VideoSide.PORTRAIT -> {
                // Pivot-scale around (0.5, 0.5) by (9/16, 1, 1).
                // Column-major closed form:
                //   col 0 = (9/16, 0, 0, 0)
                //   col 3 = (0.5 - 0.5*9/16, 0, 0, 1)
                val s = 9f / 16f
                out[0] = s
                out[12] = 0.5f - 0.5f * s
            }
        }
    }

    /**
     * Builds the displayRotation correction matrix into [out]. Aligns the
     * landscape consumer surface's content with the device's "up"
     * direction so per-side aspect crops produce upright outputs.
     *
     *  - 0 (phone portrait):       +90° UV pivot-rotate around (0.5, 0.5)
     *  - 1 (phone landscape):      identity
     *  - 2 (phone upside-down):    +270° (equiv -90°)
     *  - 3 (phone landscape rev):  +180°
     *
     * Signs verified on-device per smoke-fix tradition. If a future
     * device shows upside-down output, flip the sign here.
     *
     * `out` must be length 16; [displayRotation] must be in 0..3.
     *
     * Phase 6.1c D-deviation from plan Task 2: inline pure-Kotlin mat4
     * math (no `android.opengl.Matrix.*`). Matrix constants derived
     * from `T(0.5,0.5,0) × Rz(deg) × T(-0.5,-0.5,0)`.
     */
    fun buildDisplayRotationCorrection(displayRotation: Int, out: FloatArray) {
        require(out.size == 16) { "out must be length 16, was ${out.size}" }
        require(displayRotation in 0..3) {
            "displayRotation must be Surface.ROTATION_0..ROTATION_270 (0..3), was $displayRotation"
        }
        val degrees = when (displayRotation) {
            0 -> 90f
            1 -> 0f
            2 -> 270f
            3 -> 180f
            else -> 0f  // unreachable; required-range guard above.
        }
        // Identity baseline.
        for (i in 0..15) out[i] = 0f
        out[0] = 1f; out[5] = 1f; out[10] = 1f; out[15] = 1f
        if (degrees == 0f) return
        // Pivot-rotate around (0.5, 0.5) about z-axis by `degrees`.
        // Column-major closed form:
        //   col 0 = (c, s, 0, 0)
        //   col 1 = (-s, c, 0, 0)
        //   col 3 = (0.5 - 0.5c + 0.5s, 0.5 - 0.5s - 0.5c, 0, 1)
        val rad = degrees * Math.PI.toFloat() / 180f
        val c = kotlin.math.cos(rad)
        val s = kotlin.math.sin(rad)
        out[0] = c;  out[1] = s
        out[4] = -s; out[5] = c
        out[12] = 0.5f - 0.5f * c + 0.5f * s
        out[13] = 0.5f - 0.5f * s - 0.5f * c
    }

    /**
     * cropMatrix per side:
     *  - PORTRAIT:  sideAspectCrop[PORTRAIT] × displayRotationCorrection × portraitCorrection(+90° pivot)
     *  - LANDSCAPE: sideAspectCrop[LANDSCAPE] × displayRotationCorrection
     *
     * Composes the side's UV center-crop with the device-orientation
     * correction (and, for PORTRAIT, an additional +90° UV pivot-rotate
     * about (0.5, 0.5) — see [buildCropMatrix] body for the why).
     * Built once at session start in [EglRouter.addTarget] and reused
     * for every frame of that target's lifetime.
     *
     * `out` must be length 16; [displayRotation] must be in 0..3.
     *
     * Phase 6.1c D-deviation from plan Task 2: inline pure-Kotlin mat4
     * multiply (no `android.opengl.Matrix.multiplyMM`) — see helper
     * [multiplyMat4] below. Result mathematically identical to the
     * plan's `Matrix.multiplyMM(out, 0, crop, 0, rot, 0)` call.
     */
    fun buildCropMatrix(displayRotation: Int, side: VideoSide, out: FloatArray) {
        require(out.size == 16) { "out must be length 16, was ${out.size}" }
        val rot = FloatArray(16)
        val crop = FloatArray(16)
        buildDisplayRotationCorrection(displayRotation, rot)
        buildSideAspectCrop(side, crop)

        if (side == VideoSide.PORTRAIT) {
            // Phase 6.1c on-device smoke-fix (2026-05-17, screenshot
            // `Screenshot_20260517_213700.png`): PORTRAIT preview rendered
            // 90°-CCW from natural on first device run. Right-multiply the
            // cropMatrix by an additional +90° UV pivot-rotate about
            // (0.5, 0.5) to undo it. LANDSCAPE side renders correctly and
            // stays at the identity-extra-rotation `crop × rot` composition.
            //
            // The corrective rotation has the same closed form as
            // [buildDisplayRotationCorrection] at displayRotation=0 (which
            // encodes +90° about (0.5, 0.5)). Reusing the helper keeps the
            // rotation math in one place — the semantic role here is per-
            // side UV correction, not device-rotation compensation.
            //
            // If on a future device the PORTRAIT side appears 90°-CW from
            // natural instead (i.e., over-rotated), the fix is to pass
            // `displayRotation = 2` (which encodes +270° = -90°) below.
            val portraitCorrection = FloatArray(16)
            buildDisplayRotationCorrection(0, portraitCorrection)
            val cropTimesRot = FloatArray(16)
            multiplyMat4(cropTimesRot, crop, rot)
            // out = (crop × rot) × portraitCorrection — right-to-left to UVs:
            // (1) portraitCorrection first, (2) then rot, (3) then crop.
            multiplyMat4(out, cropTimesRot, portraitCorrection)
        } else {
            // out = crop × rot (right-to-left to UVs: rotate first, then crop).
            multiplyMat4(out, crop, rot)
        }
    }

    /**
     * Column-major 4×4 matrix multiply: `out = lhs × rhs`. Mirrors
     * `android.opengl.Matrix.multiplyMM(out, 0, lhs, 0, rhs, 0)` for
     * length-16 column-major matrices. Phase 6.1c — used so [AspectFitMath]
     * is JVM-pure (spec §5.4) instead of depending on the Android
     * SDK's stubbed-out `Matrix` class.
     */
    private fun multiplyMat4(out: FloatArray, lhs: FloatArray, rhs: FloatArray) {
        require(out.size == 16 && lhs.size == 16 && rhs.size == 16)
        // Column-major: m[col * 4 + row] = element at (row, col).
        // (lhs × rhs)[col, row] = Σ_k lhs[k, row] * rhs[col, k].
        for (col in 0..3) {
            for (row in 0..3) {
                var sum = 0f
                for (k in 0..3) {
                    sum += lhs[k * 4 + row] * rhs[col * 4 + k]
                }
                out[col * 4 + row] = sum
            }
        }
    }
}

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
     * Source-aspect numerator/denominator for the dual-camera capture.
     * Pinned by [com.aritr.rova.service.RovaRecordingService.setupDualCamera]'s
     * `ResolutionSelector`, which requests a 4:3 sensor output via
     * [com.aritr.rova.service.dualrecord.internal.DualCameraSizeResolver].
     * See `docs/adr/0009-dualshot-4-3-source-aspect.md`.
     *
     * If the source aspect ever changes, both branches of
     * [buildSideAspectCrop] must be re-derived in lockstep.
     */
    internal const val SOURCE_ASPECT_W = 4f
    internal const val SOURCE_ASPECT_H = 3f

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
     * Builds the side-specific UV center-crop matrix into [out]. Assumes a
     * 4:3 source aspect (pinned by [SOURCE_ASPECT_W] / [SOURCE_ASPECT_H]
     * + [com.aritr.rova.service.RovaRecordingService.setupDualCamera]'s
     * `ResolutionSelector`). If the source aspect ever changes, both
     * branches below must be re-derived in lockstep.
     *
     *  - [VideoSide.PORTRAIT]: pivot-scale around (0.5, 0.5) by
     *    `((9/16) / (4/3), 1, 1) = (27/64, 1, 1)` — center-crop a vertical
     *    9:16 column from a 4:3 source. Fills the 1080×1920 PORTRAIT
     *    encoder with no stretch and no bars.
     *  - [VideoSide.LANDSCAPE]: pivot-scale around (0.5, 0.5) by
     *    `(1, (4/3) / (16/9), 1) = (1, 3/4, 1)` — center-crop top+bottom
     *    of a 4:3 source to a 16:9 strip. Fills the 1920×1080 LANDSCAPE
     *    encoder with no stretch and no bars; accepts a 1.33× downscale.
     *
     * `out` must be a length-16 float array; contents are overwritten.
     *
     * Phase 6.1c D-deviation from plan Task 2 (preserved): inline pure-
     * Kotlin mat4 math (no `android.opengl.Matrix.*`) per spec §5.4 "no
     * Android dependencies" — plan's choice silently no-ops under
     * `testOptions.unitTests.isReturnDefaultValues = true`. Matrix
     * constants derived from the pivot-scale composition
     * `T(0.5,0.5,0) × S(...) × T(-0.5,-0.5,0)`.
     *
     * See `docs/adr/0009-dualshot-4-3-source-aspect.md` for the rationale
     * behind the 4:3 source-aspect decision and the rejected alternatives.
     */
    fun buildSideAspectCrop(side: VideoSide, out: FloatArray) {
        require(out.size == 16) { "out must be length 16, was ${out.size}" }
        // Identity baseline.
        for (i in 0..15) out[i] = 0f
        out[0] = 1f; out[5] = 1f; out[10] = 1f; out[15] = 1f
        when (side) {
            VideoSide.LANDSCAPE -> {
                // Pivot-scale around (0.5, 0.5) by (1, (4/3) / (16/9), 1) =
                // (1, 3/4, 1). Center-crops top+bottom of a 4:3 source to a
                // 16:9 strip — fills the 1920×1080 LANDSCAPE encoder with no
                // stretch and no bars. Pre-4:3-source fix this branch was
                // identity (16:9 source flowed straight to the 16:9 encoder
                // pixel-perfect). The 4:3-source fix accepts a 1.33×
                // downscale on LANDSCAPE as the documented tradeoff vs the
                // rejected dual-capture-session alternative — see ADR-0009.
                //
                // Column-major closed form:
                //   col 1 = (0, 3/4, 0, 0)
                //   col 3 = (0, 0.5 - 0.5*3/4, 0, 1) = (0, 1/8, 0, 1)
                val s = (SOURCE_ASPECT_W / SOURCE_ASPECT_H) / (16f / 9f)
                out[5] = s
                out[13] = 0.5f - 0.5f * s
            }
            VideoSide.PORTRAIT -> {
                // Pivot-scale around (0.5, 0.5) by ((9/16) / (4/3), 1, 1) =
                // (27/64, 1, 1). Center-crops a vertical 9:16 column from a
                // 4:3 source — fills the 1080×1920 PORTRAIT encoder with no
                // stretch and no bars. Pre-4:3-source fix this branch sampled
                // 9/16 of a 16:9 source = a 1:1 square (stretched 1.78× into
                // the 9:16 encoder — the bug ADR-0009 fixes).
                //
                // Column-major closed form:
                //   col 0 = (27/64, 0, 0, 0)
                //   col 3 = (0.5 - 0.5*27/64, 0, 0, 1) = (37/128, 0, 0, 1)
                val s = (9f / 16f) / (SOURCE_ASPECT_W / SOURCE_ASPECT_H)
                out[0] = s
                out[12] = 0.5f - 0.5f * s
            }
        }
    }

    /**
     * Phase: render-architecture audit (first-principles UV pipeline).
     *
     * Produces the canonical UV alignment transform derived from
     * SENSOR_ORIENTATION relative to the current OES texture basis. Maps
     * the effective OES UV basis into the canonical UV frame (+U=screen-
     * right, +V=screen-down, origin=top-left, rear-camera-unmirrored,
     * device-natural-aligned).
     *
     * The current implementation reduces to a pivot-rotate around
     * (0.5, 0.5) by [sensorOrientation] degrees CCW. **This is an
     * implementation detail, NOT a permanent contract.** Future
     * revisions may incorporate texMatrix-convention adjustments or
     * per-OEM compensations without API breakage as long as the
     * canonical-UV-frame guarantee holds.
     *
     * Input: [sensorOrientation] ∈ {0, 90, 180, 270} (per Camera2 spec,
     * `CameraCharacteristics.SENSOR_ORIENTATION` returns multiples of
     * 90). [out] must be length 16; contents overwritten.
     *
     * Hybrid coexistence: this helper is invoked from
     * [buildUvTransformV2]; legacy [buildCropMatrix] does NOT call it.
     * See `docs/superpowers/specs/2026-05-18-render-architecture-audit-design.md` §2.1.
     */
    fun buildTextureNormalization(sensorOrientation: Int, out: FloatArray) {
        require(out.size == 16) { "out must be length 16, was ${out.size}" }
        require(sensorOrientation in setOf(0, 90, 180, 270)) {
            "sensorOrientation must be 0/90/180/270 " +
                "(CameraCharacteristics.SENSOR_ORIENTATION), was $sensorOrientation"
        }
        // Identity baseline.
        for (i in 0..15) out[i] = 0f
        out[0] = 1f; out[5] = 1f; out[10] = 1f; out[15] = 1f
        if (sensorOrientation == 0) return
        // Pivot-rotate around (0.5, 0.5) about z-axis by `sensorOrientation` degrees CCW.
        // Column-major closed form (same as buildDisplayRotationCorrection):
        //   col 0 = (c, s, 0, 0)
        //   col 1 = (-s, c, 0, 0)
        //   col 3 = (0.5 - 0.5c + 0.5s, 0.5 - 0.5s - 0.5c, 0, 1)
        val rad = sensorOrientation.toFloat() * Math.PI.toFloat() / 180f
        val c = kotlin.math.cos(rad)
        val s = kotlin.math.sin(rad)
        out[0] = c;  out[1] = s
        out[4] = -s; out[5] = c
        out[12] = 0.5f - 0.5f * c + 0.5f * s
        out[13] = 0.5f - 0.5f * s - 0.5f * c
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
     * cropMatrix = sideAspectCrop[side] × displayRotationCorrection × sideOrientationCorrection[side].
     * Composes the side's UV center-crop, the device-orientation
     * correction, and a per-side UV-orientation correction derived from
     * on-device smoke (see [buildCropMatrix] body for the empirical why).
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

        // Phase 6.1c on-device smoke-fix series (Samsung SM-A176B, 2026-05-17):
        //
        //  Round 1 (`Screenshot_20260517_213700.png`): PORTRAIT zone rendered
        //  90°-rotated from natural; LANDSCAPE zone looked plausible at first
        //  glance but was actually 180°-flipped (under-noticed because the
        //  PORTRAIT 90° sideways issue dominated the visual diff).
        //
        //  Round 2 (`Screenshot_20260517_220827.png`, post round-1 fix):
        //  PORTRAIT zone now 180°-flipped (the round-1 +90° UV rotation
        //  compounded the original 90° to 180°); LANDSCAPE zone still 180°-
        //  flipped (round-1 was PORTRAIT-only, byte-identical for LANDSCAPE).
        //
        //  Round 3 (`Screenshot_20260517_222853.png`, post round-2 fix):
        //  PORTRAIT zone now natural orientation (rotations cancel — see test
        //  #14). LANDSCAPE zone +90°-CW from natural; needs +90°-CCW UV
        //  shift. Bumped LANDSCAPE sideCorrection from +180° (displayRotation=3)
        //  to +270° (displayRotation=2). PORTRAIT untouched in round 3.
        //
        //  Separately surfaced in round 3 but NOT fixed here (outside the
        //  AspectFitMath math-seam scope): PORTRAIT zone shows the center
        //  9/16 column of the 16:9 source as a 1:1 square stretched
        //  vertically into the 9:16 encoder — aspect-ratio mismatch baked
        //  into the original sideAspectCrop design. Fix likely belongs in
        //  EglRouter's call to [computeFitViewport] (pass contentAspect=1f
        //  for PORTRAIT to letterbox the square into the vertical encoder)
        //  or in a redesigned sideAspectCrop. Owner-deferred.
        //
        // Net per-side UV correction (rounds 1 + 2 + 3):
        //   PORTRAIT:  +270° UV (= round-1's +90° + round-2's +180°)
        //   LANDSCAPE: +270° UV (= round-2's +180° + round-3's +90° CCW)
        //
        // Reuses [buildDisplayRotationCorrection]'s rotation primitives:
        //   displayRotation=2 → +270°.
        // Semantic role here is per-side UV correction, not device-rotation
        // compensation — same closed form, different intent (KDoc note).
        //
        // Both sides happen to land on the same correction (+270°) after
        // round 3, but the per-side `when` is kept to preserve clear intent
        // and to give future smoke-fixes a place to diverge.
        //
        // If a future device shows the opposite over/under-rotation, flip
        // the displayRotation constants below. Sign verification per the
        // smoke-fix tradition.
        val sideCorrection = FloatArray(16)
        when (side) {
            VideoSide.PORTRAIT -> buildDisplayRotationCorrection(2, sideCorrection)
            VideoSide.LANDSCAPE -> buildDisplayRotationCorrection(2, sideCorrection)
        }
        val cropTimesRot = FloatArray(16)
        multiplyMat4(cropTimesRot, crop, rot)
        // out = (crop × rot) × sideCorrection — applied to UV right-to-left:
        // (1) sideCorrection first, (2) then rot, (3) then crop.
        multiplyMat4(out, cropTimesRot, sideCorrection)
    }

    /**
     * Phase: render-architecture audit (first-principles UV pipeline).
     *
     * Composes the canonical UV pipeline from first-principles components:
     *
     *   out = sideAspectCrop × displayRotationCorrection × textureNormalization
     *
     * Right-to-left UV application: textureNormalization first (OES →
     * canonical UV frame), then displayRotationCorrection (device-tilt
     * compensation), then sideAspectCrop (terminal transform in
     * canonical UV space). NO empirical sideCorrection — the
     * canonical-UV-frame invariant (see
     * [buildTextureNormalization]) replaces the legacy
     * [buildCropMatrix]'s per-side `+270°` hack.
     *
     * **Caller-owned scratch buffers**: [scratchA]/[scratchB]/[scratchC]/
     * [scratchD] must each be length 16 AND PAIRWISE-DISTINCT array
     * instances (also distinct from [out]). Caller (EglRouter) holds
     * these as instance fields, reused across all `addTarget` calls.
     * [multiplyMat4]'s no-alias contract requires the distinctness.
     *
     * **Hybrid coexistence**: this helper is parallel dead-code at runtime
     * until `EglRouter.useFirstPrinciplesRender` flag flips true. Bridge-
     * tested against legacy [buildCropMatrix] in `AspectFitMathBridgeTest`.
     *
     * See `docs/superpowers/specs/2026-05-18-render-architecture-audit-design.md` §2.2.
     */
    fun buildUvTransformV2(
        displayRotation: Int,
        sensorOrientation: Int,
        side: VideoSide,
        out: FloatArray,
        scratchA: FloatArray,
        scratchB: FloatArray,
        scratchC: FloatArray,
        scratchD: FloatArray,
    ) {
        require(out.size == 16) { "out must be length 16, was ${out.size}" }
        require(scratchA.size == 16 && scratchB.size == 16 && scratchC.size == 16 && scratchD.size == 16) {
            "scratch buffers must each be length 16; were " +
                "${scratchA.size}/${scratchB.size}/${scratchC.size}/${scratchD.size}"
        }
        // Pairwise distinctness — required by multiplyMat4's no-alias contract.
        require(scratchA !== scratchB) { "scratchA must not alias scratchB" }
        require(scratchA !== scratchC) { "scratchA must not alias scratchC" }
        require(scratchA !== scratchD) { "scratchA must not alias scratchD" }
        require(scratchB !== scratchC) { "scratchB must not alias scratchC" }
        require(scratchB !== scratchD) { "scratchB must not alias scratchD" }
        require(scratchC !== scratchD) { "scratchC must not alias scratchD" }
        require(out !== scratchA) { "out must not alias scratchA" }
        require(out !== scratchB) { "out must not alias scratchB" }
        require(out !== scratchC) { "out must not alias scratchC" }
        require(out !== scratchD) { "out must not alias scratchD" }

        buildSideAspectCrop(side, scratchA)                       // scratchA = sideAspectCrop
        buildDisplayRotationCorrection(displayRotation, scratchB) // scratchB = displayRotationCorrection
        buildTextureNormalization(sensorOrientation, scratchC)    // scratchC = textureNormalization
        multiplyMat4(scratchD, scratchB, scratchC)                // scratchD = rotTimesNorm
        multiplyMat4(out, scratchA, scratchD)                     // out = sideAspectCrop × rotTimesNorm
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

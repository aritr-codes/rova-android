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
     * **Axis-swap — the 2026-05-20 stretch fix.** This crop composes AFTER
     * the OES `texMatrix`: [EglRouter.renderFrame] builds
     * `finalMatrix = uvTransform × texMatrix × mirrorMatrix`, so the crop
     * operates on `texMatrix`'s output, NOT on raw encoder UV. For a camera
     * whose `SENSOR_ORIENTATION` is 90° or 270° — every standard phone
     * camera — `texMatrix` is a reflection that SWAPS the U and V axes
     * (measured on a Samsung SM-A176B, 2026-05-20:
     * `texMatrix: (u,v) → (1-v, 1-u)`). A crop authored for the canonical
     * X=horizontal / Y=vertical frame then lands on the wrong axis — the
     * cause of the PORTRAIT-pinched / LANDSCAPE-squished 3.16× stretch in
     * the recorded DualShot files. When [sensorOrientation] is 90 or 270
     * the crop is therefore applied to the OTHER axis, which is
     * geometrically identical to running the other side's branch (see
     * `effectiveSide` below).
     *
     *  - canonical frame (sensorOrientation 0/180):
     *     - [VideoSide.PORTRAIT]: pivot-scale (0.5,0.5) by
     *       `((9/16)/(4/3), 1, 1) = (27/64, 1, 1)` — crop a vertical 9:16
     *       column from the wide 4:3 source.
     *     - [VideoSide.LANDSCAPE]: pivot-scale (0.5,0.5) by
     *       `(1, (4/3)/(16/9), 1) = (1, 3/4, 1)` — crop top+bottom of the
     *       4:3 source to a 16:9 strip (accepts a 1.33× downscale).
     *  - axis-swapped frame (sensorOrientation 90/270): the two branches
     *    exchange — PORTRAIT scales V by 3/4, LANDSCAPE scales U by 27/64.
     *    Each side still fills its encoder with no stretch and no bars.
     *
     * `out` must be a length-16 float array; contents are overwritten.
     * [sensorOrientation] must be one of 0/90/180/270.
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
    fun buildSideAspectCrop(side: VideoSide, sensorOrientation: Int, out: FloatArray) {
        require(out.size == 16) { "out must be length 16, was ${out.size}" }
        require(sensorOrientation in setOf(0, 90, 180, 270)) {
            "sensorOrientation must be 0/90/180/270 " +
                "(CameraCharacteristics.SENSOR_ORIENTATION), was $sensorOrientation"
        }
        // Identity baseline.
        for (i in 0..15) out[i] = 0f
        out[0] = 1f; out[5] = 1f; out[10] = 1f; out[15] = 1f
        // texMatrix swaps U<->V for 90°/270° sensors (the universal phone-
        // camera case). The crop composes after texMatrix, so on a swapped
        // frame the transform that fills a PORTRAIT encoder is geometrically
        // the LANDSCAPE crop and vice versa — see the KDoc axis-swap note.
        val axisSwapped = sensorOrientation % 180 != 0
        val effectiveSide = if (axisSwapped) {
            when (side) {
                VideoSide.PORTRAIT -> VideoSide.LANDSCAPE
                VideoSide.LANDSCAPE -> VideoSide.PORTRAIT
            }
        } else {
            side
        }
        when (effectiveSide) {
            VideoSide.LANDSCAPE -> {
                // Pivot-scale around (0.5, 0.5) by (1, (4/3) / (16/9), 1) =
                // (1, 3/4, 1) — scales the V (vertical) axis. Crops a 4:3
                // source to a 16:9 strip in the canonical frame; on a swapped
                // frame this is the PORTRAIT side's crop.
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
                // (27/64, 1, 1) — scales the U (horizontal) axis. Crops a 4:3
                // source to a 9:16 column in the canonical frame; on a swapped
                // frame this is the LANDSCAPE side's crop.
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
     * Generalisation of [buildSideAspectCrop] — builds a UV center-crop into
     * [out] for an arbitrary target aspect ([targetAspectW] / [targetAspectH]),
     * not just the side-fixed 9:16 / 16:9. For the canonical PORTRAIT (9,16)
     * and LANDSCAPE (16,9) inputs the matrix is bit-identical to
     * [buildSideAspectCrop] — verified in [AspectFitMathTest].
     *
     * Backs [buildPreviewCropMatrix]: the preview path crops the source to
     * the preview zone's aspect (fills the surface, no letterbox) rather
     * than the side-fixed recording aspect.
     *
     * Same axis-swap rationale as [buildSideAspectCrop]: the OES `texMatrix`
     * swaps U<->V for 90° / 270° sensor orientations, so the effective target
     * aspect in post-`texMatrix` UV coords is (H, W) instead of (W, H). The
     * canonical formula is then applied to the effective target.
     *
     * `out` must be length 16; contents are overwritten. [sensorOrientation]
     * must be one of 0 / 90 / 180 / 270. [targetAspectW] and [targetAspectH]
     * must be > 0.
     */
    internal fun buildAspectCrop(
        targetAspectW: Int,
        targetAspectH: Int,
        sensorOrientation: Int,
        out: FloatArray,
    ) {
        require(out.size == 16) { "out must be length 16, was ${out.size}" }
        require(sensorOrientation in setOf(0, 90, 180, 270)) {
            "sensorOrientation must be 0/90/180/270 " +
                "(CameraCharacteristics.SENSOR_ORIENTATION), was $sensorOrientation"
        }
        require(targetAspectW > 0 && targetAspectH > 0) {
            "target aspect dims must be > 0; was ${targetAspectW}x${targetAspectH}"
        }
        // Identity baseline.
        for (i in 0..15) out[i] = 0f
        out[0] = 1f; out[5] = 1f; out[10] = 1f; out[15] = 1f

        // texMatrix U<->V swap on 90°/270° sensors — effective target is (H, W).
        val axisSwapped = sensorOrientation % 180 != 0
        val effW = if (axisSwapped) targetAspectH else targetAspectW
        val effH = if (axisSwapped) targetAspectW else targetAspectH

        val sourceAspect = SOURCE_ASPECT_W / SOURCE_ASPECT_H  // 4/3
        val targetAspect = effW.toFloat() / effH.toFloat()

        if (kotlin.math.abs(targetAspect - sourceAspect) < 1e-6f) return  // identity

        if (targetAspect < sourceAspect) {
            // Target narrower → pivot-scale X (col 0, col 3 row 0).
            val s = targetAspect / sourceAspect
            out[0] = s
            out[12] = 0.5f - 0.5f * s
        } else {
            // Target wider → pivot-scale Y (col 1, col 3 row 1).
            val s = sourceAspect / targetAspect
            out[5] = s
            out[13] = 0.5f - 0.5f * s
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
    @Deprecated(
        "Empirical +270° sideCorrection. Use buildUvTransformV2 once " +
            "useFirstPrinciplesRender migration completes. See spec " +
            "docs/superpowers/specs/2026-05-18-render-architecture-audit-design.md §5.5 retirement plan.",
        ReplaceWith("buildUvTransformV2(displayRotation, sensorOrientation, side, out, scratchA, scratchB, scratchC, scratchD)"),
    )
    fun buildCropMatrix(displayRotation: Int, sensorOrientation: Int, side: VideoSide, out: FloatArray) {
        require(out.size == 16) { "out must be length 16, was ${out.size}" }
        val rot = FloatArray(16)
        val crop = FloatArray(16)
        buildDisplayRotationCorrection(displayRotation, rot)
        // 2026-05-20 stretch fix — sideAspectCrop is sensorOrientation-aware:
        // the OES texMatrix swaps U<->V for 90°/270° sensors, so the crop
        // axis depends on it. See AspectFitMath.buildSideAspectCrop KDoc.
        buildSideAspectCrop(side, sensorOrientation, crop)

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
     * Preview-target variant of [buildCropMatrix] — composes the same
     *   `cropMatrix = aspectCrop × displayRotationCorrection × sideOrientationCorrection`
     * chain, including the empirical +270° per-side correction from the
     * Phase 6.1c smoke-fix series, but substitutes [buildAspectCrop] (target
     * = preview zone aspect) for [buildSideAspectCrop] (target = recording
     * aspect). For the canonical (9, 16) / (16, 9) targets the matrix is
     * bit-identical to [buildCropMatrix] — verified in [AspectFitMathTest].
     *
     * Used by `EglRouter.addTarget` when `kind == TargetKind.PREVIEW`. The
     * `side` is still required: the +270° sideCorrection is per-side, and
     * the preview MUST rotate identically to its encoder so the
     * `RecordingFrameGuide` overlay lines up with the actual capture region.
     *
     * `out` must be length 16; [displayRotation] in 0..3;
     * [sensorOrientation] in {0, 90, 180, 270};
     * [targetAspectW] and [targetAspectH] > 0.
     *
     * See `docs/adr/0010-dualshot-preview-crop-divergence.md`.
     */
    fun buildPreviewCropMatrix(
        displayRotation: Int,
        sensorOrientation: Int,
        side: VideoSide,
        targetAspectW: Int,
        targetAspectH: Int,
        out: FloatArray,
    ) {
        require(out.size == 16) { "out must be length 16, was ${out.size}" }
        val rot = FloatArray(16)
        val crop = FloatArray(16)
        buildDisplayRotationCorrection(displayRotation, rot)
        buildAspectCrop(targetAspectW, targetAspectH, sensorOrientation, crop)

        // Per-side +270° UV correction — same as buildCropMatrix. Both sides
        // land on the same correction after the Phase 6.1c round-3 smoke-fix
        // but the per-side `when` is kept to preserve clear intent and to
        // give future smoke-fixes a place to diverge.
        val sideCorrection = FloatArray(16)
        when (side) {
            VideoSide.PORTRAIT -> buildDisplayRotationCorrection(2, sideCorrection)
            VideoSide.LANDSCAPE -> buildDisplayRotationCorrection(2, sideCorrection)
        }
        val cropTimesRot = FloatArray(16)
        multiplyMat4(cropTimesRot, crop, rot)
        // out = (crop × rot) × sideCorrection — applied to UV right-to-left.
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

        buildSideAspectCrop(side, sensorOrientation, scratchA)    // scratchA = sideAspectCrop
        buildDisplayRotationCorrection(displayRotation, scratchB) // scratchB = displayRotationCorrection
        buildTextureNormalization(sensorOrientation, scratchC)    // scratchC = textureNormalization
        multiplyMat4(scratchD, scratchB, scratchC)                // scratchD = rotTimesNorm
        multiplyMat4(out, scratchA, scratchD)                     // out = sideAspectCrop × rotTimesNorm
    }

    /**
     * Column-major 4×4 matrix multiply.
     *
     *     out = lhs × rhs
     *
     * **MULTIPLICATION ORDER IS LOAD-BEARING.** Reversing operands inverts
     * UV-application semantics and destroys the pipeline. Mirrors
     * `android.opengl.Matrix.multiplyMM(out, 0, lhs, 0, rhs, 0)` for
     * length-16 column-major matrices.
     *
     * **In-place aliasing NOT supported.** `out !== lhs` AND `out !== rhs`
     * required. The inner loop reads `lhs[k*4+row]` for `k=0..3` while
     * writing `out[col*4+row]` — aliasing corrupts those reads. Caller
     * must ensure `out` is a distinct array. (Use a scratch buffer and
     * copy if aliasing is needed.)
     *
     * Phase 6.1c — used so [AspectFitMath] is JVM-pure (spec §5.4)
     * instead of depending on the Android SDK's stubbed-out `Matrix`
     * class.
     */
    private fun multiplyMat4(out: FloatArray, lhs: FloatArray, rhs: FloatArray) {
        require(out.size == 16 && lhs.size == 16 && rhs.size == 16)
        require(out !== lhs) { "multiplyMat4: out must not alias lhs" }
        require(out !== rhs) { "multiplyMat4: out must not alias rhs" }
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

/**
 * Phase: render-architecture audit. Carries one render-target's complete
 * matrix state for debug instrumentation. Consumed by sub-project 2's debug
 * overlay, logcat dump, and screenshot metadata.
 *
 * **Field semantics — three component matrices + two composed matrices:**
 *  - Components (raw helper outputs, useful for debugging which factor went
 *    wrong): [normalizationMatrix], [displayRotationMatrix],
 *    [sideAspectCropMatrix]
 *  - Composed:
 *      - [uvTransform] = pinned `target.uvTransform` (result of
 *        [AspectFitMath.buildUvTransformV2])
 *      - [finalMatrix] = per-frame composed `uTexMatrix` actually uploaded
 *        to the shader: `uvTransform × texMatrix × mirrorMatrix`
 *
 * The legacy field name `cropMatrix` is GONE from this struct — it carried
 * muddled semantics (component or composed?). Replaced by the explicit
 * `sideAspectCropMatrix` (component) + `uvTransform` (composed) pair.
 *
 * [texMatrix] is frame-local — SurfaceTexture.getTransformMatrix() may
 * return different values frame-to-frame. The snapshot captures THIS
 * frame's value. NEVER cache globally.
 *
 * [timestampNs] is `System.nanoTime()` at snapshot write — correlates with
 * dropped frames, orientation changes, encoder timing, frame captures.
 *
 * See `docs/superpowers/specs/2026-05-18-render-architecture-audit-design.md` §2.3.
 */
internal data class DualShotMatrixDebugInfo(
    val side: com.aritr.rova.service.dualrecord.VideoSide,
    val sensorOrientation: Int,
    val displayRotation: Int,
    val lensFacing: com.aritr.rova.service.dualrecord.LensFacing,
    val texMatrix: FloatArray,
    val normalizationMatrix: FloatArray,
    val displayRotationMatrix: FloatArray,
    val sideAspectCropMatrix: FloatArray,
    val uvTransform: FloatArray,
    val finalMatrix: FloatArray,
    val viewport: IntArray,
    val encoderSize: Pair<Int, Int>,
    val timestampNs: Long,
)

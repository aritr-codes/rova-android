package com.aritr.rova.service.dualrecord.internal

import com.aritr.rova.service.dualrecord.VideoSide
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AspectFitMathTest {

    @Test
    fun `9-16 content in 316x352 zone gets pillar-box bars left+right`() {
        // 9:16 = 0.5625 < 316/352 = 0.898 → fit by height, narrow viewport.
        // viewportW = 352 * 9/16 = 198 (rounded). viewportH = 352.
        val v = AspectFitMath.computeFitViewport(316, 352, 9f / 16f)
        assertEquals("viewportH should fill surface height", 352, v[3])
        assertEquals("viewportW should equal 352*9/16=198", 198, v[2])
        assertEquals("viewportX should center the band (316-198)/2 = 59", 59, v[0])
        assertEquals("viewportY should be 0", 0, v[1])
    }

    @Test
    fun `16-9 content in 316x225 zone gets letterbox bars top+bottom`() {
        // 16:9 = 1.778 > 316/225 = 1.404 → fit by width, short viewport.
        // viewportW = 316. viewportH = 316 * 9/16 = 177 (rounded).
        val v = AspectFitMath.computeFitViewport(316, 225, 16f / 9f)
        assertEquals(316, v[2])
        assertEquals(177, v[3])
        assertEquals(0, v[0])
        assertEquals("viewportY should center (225-177)/2 = 24", 24, v[1])
    }

    @Test
    fun `9-16 content in 1080x1920 encoder surface fills viewport with no bars`() {
        // Encoder surface aspect already matches content aspect.
        val v = AspectFitMath.computeFitViewport(1080, 1920, 9f / 16f)
        assertArrayEquals(intArrayOf(0, 0, 1080, 1920), v)
    }

    @Test
    fun `16-9 content in 1920x1080 encoder surface fills viewport with no bars`() {
        val v = AspectFitMath.computeFitViewport(1920, 1080, 16f / 9f)
        assertArrayEquals(intArrayOf(0, 0, 1920, 1080), v)
    }

    @Test
    fun `9-16 content in 500x500 square surface gets pillar-box bars`() {
        // 9:16 = 0.5625 < 1.0 → fit by height, narrow viewport.
        // viewportW = 500 * 9/16 = 281.
        val v = AspectFitMath.computeFitViewport(500, 500, 9f / 16f)
        assertEquals(500, v[3])
        assertEquals(281, v[2])
        assertEquals((500 - 281) / 2, v[0])
        assertEquals(0, v[1])
    }

    @Test
    fun `same aspect surface and content gives full viewport`() {
        // 16:9 content in 1600:900 (16:9) surface.
        val v = AspectFitMath.computeFitViewport(1600, 900, 16f / 9f)
        assertArrayEquals(intArrayOf(0, 0, 1600, 900), v)
    }

    @Test
    fun `extreme tall surface with 16-9 content gets letterbox not negative dims`() {
        // 16:9 content (very wide) in 100x500 surface (very tall).
        // viewportW = 100. viewportH = 100 * 9/16 = 56.
        val v = AspectFitMath.computeFitViewport(100, 500, 16f / 9f)
        assertEquals(100, v[2])
        assertEquals(56, v[3])
        assertTrue("viewportY should be positive", v[1] > 0)
        assertEquals((500 - 56) / 2, v[1])
    }

    @Test
    fun `invalid surface dims throw`() {
        runCatching { AspectFitMath.computeFitViewport(0, 100, 1f) }.let {
            assertTrue("expected throw on surfaceW=0, got ${it.getOrNull()?.contentToString()}", it.isFailure)
        }
        runCatching { AspectFitMath.computeFitViewport(100, -5, 1f) }.let {
            assertTrue("expected throw on negative surfaceH, got ${it.getOrNull()?.contentToString()}", it.isFailure)
        }
        runCatching { AspectFitMath.computeFitViewport(100, 100, 0f) }.let {
            assertTrue("expected throw on contentAspect=0, got ${it.getOrNull()?.contentToString()}", it.isFailure)
        }
    }

    // ─── sideAspectCrop tests (Task 2) ─────────────────────────────

    @Test
    fun `buildSideAspectCrop PORTRAIT canonical frame matches pivot-scale 27 over 64`() {
        // Phase: 4:3-source fix (PORTRAIT-stretch). Pinned regression-lock —
        // asserts the exact 16-element column-major matrix matches
        // pivot-scale(27/64, 1, 1) around (0.5, 0.5).
        //
        //   target_aspect / source_aspect = (9/16) / (4/3) = 27/64
        //   col 0 = (27/64, 0, 0, 0)
        //   col 3 = (0.5 - 0.5*27/64, 0, 0, 1) = (37/128, 0, 0, 1)
        //
        // sensorOrientation=0 → canonical (non-axis-swapped) frame; PORTRAIT
        // crops the U/horizontal axis. The 90°/270° axis-swapped case (every
        // real phone camera) is pinned separately below.
        //
        // If this fails, the source-aspect constant in
        // [AspectFitMath.buildSideAspectCrop] PORTRAIT branch has drifted.
        val m = FloatArray(16)
        AspectFitMath.buildSideAspectCrop(VideoSide.PORTRAIT, 0, m)

        val s = 27f / 64f
        val expected = floatArrayOf(
            s,   0f,  0f,  0f,   // col 0
            0f,  1f,  0f,  0f,   // col 1
            0f,  0f,  1f,  0f,   // col 2
            0.5f - 0.5f * s, 0f, 0f, 1f,   // col 3
        )
        assertArrayEquals(expected, m, 1e-6f)
    }

    // Helper: apply a column-major mat4 to a vec4 (matches GLSL `mat4 * vec4`).
    private fun applyMat4(m: FloatArray, v: FloatArray): FloatArray {
        require(m.size == 16 && v.size == 4)
        return floatArrayOf(
            m[0]*v[0] + m[4]*v[1] + m[8]*v[2] + m[12]*v[3],
            m[1]*v[0] + m[5]*v[1] + m[9]*v[2] + m[13]*v[3],
            m[2]*v[0] + m[6]*v[1] + m[10]*v[2] + m[14]*v[3],
            m[3]*v[0] + m[7]*v[1] + m[11]*v[2] + m[15]*v[3],
        )
    }

    @Test
    fun `buildSideAspectCrop LANDSCAPE canonical frame matches pivot-scale 1 by 3 over 4`() {
        // Phase: 4:3-source fix (PORTRAIT-stretch). Pinned regression-lock —
        // asserts the exact 16-element column-major matrix matches
        // pivot-scale(1, 3/4, 1) around (0.5, 0.5).
        //
        //   source_aspect / target_aspect = (4/3) / (16/9) = 36/48 = 3/4
        //   col 1 = (0, 3/4, 0, 0)
        //   col 3 = (0, 0.5 - 0.5*3/4, 0, 1) = (0, 1/8, 0, 1)
        //
        // sensorOrientation=0 → canonical (non-axis-swapped) frame; LANDSCAPE
        // crops the V/vertical axis. The 90°/270° axis-swapped case is pinned
        // separately below.
        //
        // If this fails, the source-aspect constant in
        // [AspectFitMath.buildSideAspectCrop] LANDSCAPE branch has drifted.
        val m = FloatArray(16)
        AspectFitMath.buildSideAspectCrop(VideoSide.LANDSCAPE, 0, m)

        val s = 3f / 4f
        val expected = floatArrayOf(
            1f,  0f,  0f,  0f,   // col 0
            0f,  s,   0f,  0f,   // col 1
            0f,  0f,  1f,  0f,   // col 2
            0f,  0.5f - 0.5f * s, 0f, 1f,   // col 3
        )
        assertArrayEquals(expected, m, 1e-6f)
    }

    @Test
    fun `buildSideAspectCrop PORTRAIT at sensorOrientation 90 swaps to V-axis pivot-scale 3 over 4`() {
        // 2026-05-20 stretch fix — regression-lock for the ACTIVE on-device
        // path. Every standard phone camera has SENSOR_ORIENTATION 90 (rear)
        // or 270 (front); the OES texMatrix then swaps U<->V, so PORTRAIT's
        // crop must land on the V axis. The transform is geometrically the
        // canonical-frame LANDSCAPE crop = pivot-scale(1, 3/4, 1).
        //
        // Pre-fix the PORTRAIT branch scaled U by 27/64 here → the recorded
        // file sampled a 16:9 region into a 9:16 encoder = 3.16× vertical
        // stretch (Samsung SM-A176B smoke, 2026-05-20).
        val m = FloatArray(16)
        AspectFitMath.buildSideAspectCrop(VideoSide.PORTRAIT, 90, m)

        val s = 3f / 4f
        val expected = floatArrayOf(
            1f,  0f,  0f,  0f,   // col 0
            0f,  s,   0f,  0f,   // col 1
            0f,  0f,  1f,  0f,   // col 2
            0f,  0.5f - 0.5f * s, 0f, 1f,   // col 3
        )
        assertArrayEquals(expected, m, 1e-6f)
    }

    @Test
    fun `buildSideAspectCrop LANDSCAPE at sensorOrientation 90 swaps to U-axis pivot-scale 27 over 64`() {
        // 2026-05-20 stretch fix — regression-lock. On the axis-swapped frame
        // LANDSCAPE's crop lands on the U axis: pivot-scale(27/64, 1, 1), the
        // canonical-frame PORTRAIT crop. Pre-fix LANDSCAPE scaled V by 3/4
        // here → 3.16× vertical squish.
        val m = FloatArray(16)
        AspectFitMath.buildSideAspectCrop(VideoSide.LANDSCAPE, 90, m)

        val s = 27f / 64f
        val expected = floatArrayOf(
            s,   0f,  0f,  0f,   // col 0
            0f,  1f,  0f,  0f,   // col 1
            0f,  0f,  1f,  0f,   // col 2
            0.5f - 0.5f * s, 0f, 0f, 1f,   // col 3
        )
        assertArrayEquals(expected, m, 1e-6f)
    }

    @Test
    fun `buildSideAspectCrop sensorOrientation 270 axis-swaps same as 90`() {
        // 270° (front camera) also satisfies `% 180 != 0` → axis-swapped,
        // identical to the 90° case. Guards the `% 180` predicate.
        val p90 = FloatArray(16); val p270 = FloatArray(16)
        AspectFitMath.buildSideAspectCrop(VideoSide.PORTRAIT, 90, p90)
        AspectFitMath.buildSideAspectCrop(VideoSide.PORTRAIT, 270, p270)
        assertArrayEquals(p90, p270, 1e-6f)
    }

    @Test
    fun `buildSideAspectCrop sensorOrientation 180 stays canonical like 0`() {
        // 180° satisfies `% 180 == 0` → NOT axis-swapped, identical to 0°.
        val p0 = FloatArray(16); val p180 = FloatArray(16)
        AspectFitMath.buildSideAspectCrop(VideoSide.PORTRAIT, 0, p0)
        AspectFitMath.buildSideAspectCrop(VideoSide.PORTRAIT, 180, p180)
        assertArrayEquals(p0, p180, 1e-6f)
    }

    @Test
    fun `buildSideAspectCrop rejects illegal sensorOrientation`() {
        runCatching { AspectFitMath.buildSideAspectCrop(VideoSide.PORTRAIT, 45, FloatArray(16)) }.let {
            assertTrue("expected throw on 45", it.isFailure)
        }
        runCatching { AspectFitMath.buildSideAspectCrop(VideoSide.PORTRAIT, -90, FloatArray(16)) }.let {
            assertTrue("expected throw on -90", it.isFailure)
        }
    }

    // ── buildAspectCrop ──────────────────────────────────────────────────────
    // Generalised aspect-crop; bit-identical to buildSideAspectCrop for the
    // canonical PORTRAIT (9,16) / LANDSCAPE (16,9) targets but accepts any
    // positive (w,h) pair. Backs the preview crop (zone aspect).

    @Test
    fun buildAspectCrop_portraitAspect_sensor0_pivotScaleX_27over64() {
        val out = FloatArray(16)
        AspectFitMath.buildAspectCrop(9, 16, sensorOrientation = 0, out = out)
        // Same output as buildSideAspectCrop(PORTRAIT, 0): pivot-scale (27/64, 1, 1).
        assertEquals(27f / 64f, out[0], 1e-6f)
        assertEquals(1f, out[5], 1e-6f)
        assertEquals(1f, out[10], 1e-6f)
        assertEquals(1f, out[15], 1e-6f)
        assertEquals(0.5f - 0.5f * (27f / 64f), out[12], 1e-6f)  // 37/128
        assertEquals(0f, out[13], 1e-6f)
    }

    @Test
    fun buildAspectCrop_landscapeAspect_sensor0_pivotScaleY_3over4() {
        val out = FloatArray(16)
        AspectFitMath.buildAspectCrop(16, 9, sensorOrientation = 0, out = out)
        // Same output as buildSideAspectCrop(LANDSCAPE, 0): pivot-scale (1, 3/4, 1).
        assertEquals(1f, out[0], 1e-6f)
        assertEquals(3f / 4f, out[5], 1e-6f)
        assertEquals(1f, out[10], 1e-6f)
        assertEquals(1f, out[15], 1e-6f)
        assertEquals(0f, out[12], 1e-6f)
        assertEquals(0.5f - 0.5f * (3f / 4f), out[13], 1e-6f)  // 1/8
    }

    @Test
    fun buildAspectCrop_arbitraryNarrowTarget_sensor0_pivotScaleX() {
        val out = FloatArray(16)
        // Target 9:10 = 0.9, narrower than source 4:3 = 1.333 → pivot-scale X by
        // (9/10) / (4/3) = 27/40.
        AspectFitMath.buildAspectCrop(9, 10, sensorOrientation = 0, out = out)
        val expectedS = (9f / 10f) / (4f / 3f)  // 27/40 = 0.675
        assertEquals(expectedS, out[0], 1e-6f)
        assertEquals(1f, out[5], 1e-6f)
        assertEquals(0.5f - 0.5f * expectedS, out[12], 1e-6f)
        assertEquals(0f, out[13], 1e-6f)
    }

    @Test
    fun buildAspectCrop_arbitraryWideTarget_sensor0_pivotScaleY() {
        val out = FloatArray(16)
        // Target 7:5 = 1.4, wider than source 4:3 = 1.333 → pivot-scale Y by
        // (4/3) / (7/5) = 20/21.
        AspectFitMath.buildAspectCrop(7, 5, sensorOrientation = 0, out = out)
        val expectedS = (4f / 3f) / (7f / 5f)  // 20/21
        assertEquals(1f, out[0], 1e-6f)
        assertEquals(expectedS, out[5], 1e-6f)
        assertEquals(0f, out[12], 1e-6f)
        assertEquals(0.5f - 0.5f * expectedS, out[13], 1e-6f)
    }

    @Test
    fun buildAspectCrop_targetEqualsSourceAspect_identity() {
        val out = FloatArray(16)
        AspectFitMath.buildAspectCrop(4, 3, sensorOrientation = 0, out = out)
        // Identity — no crop.
        assertEquals(1f, out[0], 1e-6f); assertEquals(0f, out[1], 1e-6f)
        assertEquals(0f, out[4], 1e-6f); assertEquals(1f, out[5], 1e-6f)
        assertEquals(1f, out[10], 1e-6f); assertEquals(1f, out[15], 1e-6f)
        assertEquals(0f, out[12], 1e-6f); assertEquals(0f, out[13], 1e-6f)
    }

    @Test
    fun buildAspectCrop_portraitAspect_sensor90_axisSwapsToLandscapeCrop() {
        val out = FloatArray(16)
        AspectFitMath.buildAspectCrop(9, 16, sensorOrientation = 90, out = out)
        // Sensor 90° swaps texMatrix U<->V, so the canonical portrait crop
        // (out[0]) moves to out[5] with the swapped-target scale.
        // Effective target post-swap = (16, 9) → wider than source → pivot-scale Y
        // by (4/3) / (16/9) = 3/4.
        assertEquals(1f, out[0], 1e-6f)
        assertEquals(3f / 4f, out[5], 1e-6f)
        assertEquals(0f, out[12], 1e-6f)
        assertEquals(0.5f - 0.5f * (3f / 4f), out[13], 1e-6f)
    }

    @Test
    fun buildAspectCrop_landscapeAspect_sensor90_axisSwapsToPortraitCrop() {
        val out = FloatArray(16)
        AspectFitMath.buildAspectCrop(16, 9, sensorOrientation = 90, out = out)
        // Effective post-swap target = (9, 16) → narrower → pivot-scale X by 27/64.
        assertEquals(27f / 64f, out[0], 1e-6f)
        assertEquals(1f, out[5], 1e-6f)
        assertEquals(0.5f - 0.5f * (27f / 64f), out[12], 1e-6f)
        assertEquals(0f, out[13], 1e-6f)
    }

    @Test
    fun buildAspectCrop_sensor180_matchesSensor0() {
        val canon = FloatArray(16)
        val flipped = FloatArray(16)
        AspectFitMath.buildAspectCrop(9, 16, sensorOrientation = 0, out = canon)
        AspectFitMath.buildAspectCrop(9, 16, sensorOrientation = 180, out = flipped)
        // sensorOrientation % 180 == 0 → no axis swap; same as canonical.
        for (i in 0..15) assertEquals("idx=$i", canon[i], flipped[i], 1e-6f)
    }

    @Test
    fun buildAspectCrop_sensor270_matchesSensor90() {
        val sens90 = FloatArray(16)
        val sens270 = FloatArray(16)
        AspectFitMath.buildAspectCrop(9, 16, sensorOrientation = 90, out = sens90)
        AspectFitMath.buildAspectCrop(9, 16, sensorOrientation = 270, out = sens270)
        for (i in 0..15) assertEquals("idx=$i", sens90[i], sens270[i], 1e-6f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun buildAspectCrop_invalidOutSize_throws() {
        AspectFitMath.buildAspectCrop(9, 16, 0, FloatArray(15))
    }

    @Test(expected = IllegalArgumentException::class)
    fun buildAspectCrop_invalidSensorOrientation_throws() {
        AspectFitMath.buildAspectCrop(9, 16, 45, FloatArray(16))
    }

    @Test(expected = IllegalArgumentException::class)
    fun buildAspectCrop_zeroTargetWidth_throws() {
        AspectFitMath.buildAspectCrop(0, 16, 0, FloatArray(16))
    }

    @Test(expected = IllegalArgumentException::class)
    fun buildAspectCrop_zeroTargetHeight_throws() {
        AspectFitMath.buildAspectCrop(9, 0, 0, FloatArray(16))
    }

    // ─── displayRotationCorrection tests ───────────────────────────

    @Test
    fun `buildDisplayRotationCorrection 0 is plus 90 around UV center`() {
        // displayRotation=0 (phone portrait) needs +90° UV rotation.
        // Apply to (0.5, 0.5): pivot point is invariant under any rotation about it.
        val m = FloatArray(16)
        AspectFitMath.buildDisplayRotationCorrection(0, m)
        val out = applyMat4(m, floatArrayOf(0.5f, 0.5f, 0f, 1f))
        assertEquals(0.5f, out[0], 1e-5f)
        assertEquals(0.5f, out[1], 1e-5f)
        // Apply to (1, 0.5) — right-middle → after +90° CCW about (0.5, 0.5)
        // becomes (0.5, 1) (top-middle in UV space, GL y-up convention).
        val out2 = applyMat4(m, floatArrayOf(1f, 0.5f, 0f, 1f))
        assertEquals(0.5f, out2[0], 1e-5f)
        assertEquals(1f, out2[1], 1e-5f)
    }

    @Test
    fun `buildDisplayRotationCorrection 1 is identity`() {
        val m = FloatArray(16)
        AspectFitMath.buildDisplayRotationCorrection(1, m)
        val out = applyMat4(m, floatArrayOf(0.25f, 0.75f, 0f, 1f))
        assertEquals(0.25f, out[0], 1e-5f)
        assertEquals(0.75f, out[1], 1e-5f)
    }

    @Test
    fun `buildDisplayRotationCorrection invalid throws`() {
        runCatching { AspectFitMath.buildDisplayRotationCorrection(-1, FloatArray(16)) }.let {
            assertTrue("expected throw on -1", it.isFailure)
        }
        runCatching { AspectFitMath.buildDisplayRotationCorrection(4, FloatArray(16)) }.let {
            assertTrue("expected throw on 4", it.isFailure)
        }
    }

    // ─── buildCropMatrix composition tests ─────────────────────────

    @Test
    fun `buildCropMatrix PORTRAIT at rotation 0 is pure sideAspectCrop after smokefix series`() {
        // Phase 6.1c smoke-fix series (rounds 1 + 2, 2026-05-17): cropMatrix is
        //   sideAspectCrop[side] × displayRotationCorrection × sideCorrection[side]
        // For PORTRAIT at displayRotation=0:
        //   rot = R(+90°) ; sideCorrection = R(+270°) (via displayRotation=2)
        //   R(+90° pivot) × R(+270° pivot) = R(+360° pivot) = identity
        //   So cropMatrix collapses to pivot-scale(s, 1, 1) alone where
        //   s = (9/16) / (4/3) = 27/64 (ADR-0009 4:3-source fix).
        // sensorOrientation=0 → canonical frame; the composition logic under
        // test (displayRotationCorrection × sideCorrection) is independent of
        // sensorOrientation. The axis-swap is pinned in the buildSideAspectCrop
        // tests above.
        val m = FloatArray(16)
        AspectFitMath.buildCropMatrix(0, 0, VideoSide.PORTRAIT, m)

        // Pivot (0.5, 0.5) is invariant.
        val pivot = applyMat4(m, floatArrayOf(0.5f, 0.5f, 0f, 1f))
        assertEquals(0.5f, pivot[0], 1e-5f)
        assertEquals(0.5f, pivot[1], 1e-5f)

        // Right-middle (1, 0.5) → pivot-scale x: 0.5 + 27/64 * 0.5 = 0.7109375. y unchanged.
        val rm = applyMat4(m, floatArrayOf(1f, 0.5f, 0f, 1f))
        assertEquals(0.7109375f, rm[0], 1e-5f)
        assertEquals(0.5f, rm[1], 1e-5f)

        // Left-middle (0, 0.5) → pivot-scale x: 0.5 - 27/128 = 0.2890625. y unchanged.
        val lm = applyMat4(m, floatArrayOf(0f, 0.5f, 0f, 1f))
        assertEquals(0.2890625f, lm[0], 1e-5f)
        assertEquals(0.5f, lm[1], 1e-5f)
    }

    @Test
    fun `buildCropMatrix LANDSCAPE at rotation 1 is +270 pivot rotate then scale y by 3 over 4`() {
        // Phase 6.1c smoke-fix series (round 3, 2026-05-17) + ADR-0009 4:3-
        // source fix: cropMatrix is
        //   sideAspectCrop[side] × displayRotationCorrection × sideCorrection[side]
        // For LANDSCAPE at displayRotation=1:
        //   crop = pivot-scale(1, 3/4, 1) ;  rot = R(0°)  ;  sideCorrection = R(+270°)
        //   cropMatrix = pivot-scale(1, 3/4, 1) × R(+270° pivot about (0.5, 0.5)).
        //
        // R(+270° pivot) maps (u, v) → (v, 1-u) in GL y-up convention.
        // Then pivot-scale(1, 3/4, 1) maps (x, y) → (x, 0.5 + (3/4)*(y - 0.5)).
        val m = FloatArray(16)
        AspectFitMath.buildCropMatrix(1, 0, VideoSide.LANDSCAPE, m)

        // Pivot (0.5, 0.5) is invariant under both ops.
        val pivot = applyMat4(m, floatArrayOf(0.5f, 0.5f, 0f, 1f))
        assertEquals(0.5f, pivot[0], 1e-5f)
        assertEquals(0.5f, pivot[1], 1e-5f)

        // (0.25, 0.75) → R(+270° pivot about (0.5, 0.5)):
        //   relative: (-0.25, 0.25) → R(+270° y-up): (0.25, 0.25) → add pivot: (0.75, 0.75).
        // Then pivot-scale(1, 3/4, 1): x stays 0.75; y = 0.5 + (3/4)*(0.75 - 0.5) = 0.6875.
        val out = applyMat4(m, floatArrayOf(0.25f, 0.75f, 0f, 1f))
        assertEquals(0.75f, out[0], 1e-5f)
        assertEquals(0.6875f, out[1], 1e-5f)
    }

    @Test
    fun `buildCropMatrix output length check`() {
        runCatching { AspectFitMath.buildCropMatrix(0, 0, VideoSide.PORTRAIT, FloatArray(15)) }.let {
            assertTrue("expected throw on length 15 array", it.isFailure)
        }
    }
}

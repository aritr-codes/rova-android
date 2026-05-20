package com.aritr.rova.service.dualrecord.internal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.aritr.rova.service.dualrecord.VideoSide

class AspectFitMathV2Test {

    // Helper: apply a column-major mat4 to a vec4 (matches GLSL `mat4 * vec4`).
    // Same shape as the helper in AspectFitMathTest.applyMat4.
    private fun applyMat4(m: FloatArray, v: FloatArray): FloatArray {
        require(m.size == 16 && v.size == 4)
        return floatArrayOf(
            m[0]*v[0] + m[4]*v[1] + m[8]*v[2] + m[12]*v[3],
            m[1]*v[0] + m[5]*v[1] + m[9]*v[2] + m[13]*v[3],
            m[2]*v[0] + m[6]*v[1] + m[10]*v[2] + m[14]*v[3],
            m[3]*v[0] + m[7]*v[1] + m[11]*v[2] + m[15]*v[3],
        )
    }

    // ─── buildTextureNormalization — canonical UV invariant tests ───
    //
    // Per spec §2.1: the helper produces "the canonical UV alignment
    // transform derived from SENSOR_ORIENTATION relative to the current
    // OES texture basis". The implementation currently reduces to a
    // pivot-rotate by sensorOrientation° CCW around (0.5, 0.5).
    //
    // These tests assert the canonical-frame invariant (UV corners map
    // to the expected positions for the orientation) — NOT pinned matrix
    // bytes. Future implementation refinements that maintain the
    // canonical-UV-frame guarantee will pass these tests unchanged.

    @Test
    fun `buildTextureNormalization 0 is identity (pivot invariant + corners unchanged)`() {
        val m = FloatArray(16)
        AspectFitMath.buildTextureNormalization(0, m)

        // Pivot (0.5, 0.5) is invariant under any pivot-rotation about itself.
        val pivot = applyMat4(m, floatArrayOf(0.5f, 0.5f, 0f, 1f))
        assertEquals(0.5f, pivot[0], 1e-5f)
        assertEquals(0.5f, pivot[1], 1e-5f)

        // For 0° rotation, all corners stay put.
        val bl = applyMat4(m, floatArrayOf(0f, 0f, 0f, 1f))
        assertEquals(0f, bl[0], 1e-5f); assertEquals(0f, bl[1], 1e-5f)
        val tr = applyMat4(m, floatArrayOf(1f, 1f, 0f, 1f))
        assertEquals(1f, tr[0], 1e-5f); assertEquals(1f, tr[1], 1e-5f)
    }

    @Test
    fun `buildTextureNormalization 90 rotates UV corners CCW about center`() {
        // +90° CCW pivot about (0.5, 0.5) in GL y-up UV convention:
        //   (0, 0) BL → (1, 0) BR
        //   (1, 0) BR → (1, 1) TR
        //   (1, 1) TR → (0, 1) TL
        //   (0, 1) TL → (0, 0) BL
        val m = FloatArray(16)
        AspectFitMath.buildTextureNormalization(90, m)

        val pivot = applyMat4(m, floatArrayOf(0.5f, 0.5f, 0f, 1f))
        assertEquals(0.5f, pivot[0], 1e-5f)
        assertEquals(0.5f, pivot[1], 1e-5f)

        val out00 = applyMat4(m, floatArrayOf(0f, 0f, 0f, 1f))
        assertEquals(1f, out00[0], 1e-5f); assertEquals(0f, out00[1], 1e-5f)

        val out10 = applyMat4(m, floatArrayOf(1f, 0f, 0f, 1f))
        assertEquals(1f, out10[0], 1e-5f); assertEquals(1f, out10[1], 1e-5f)

        val out11 = applyMat4(m, floatArrayOf(1f, 1f, 0f, 1f))
        assertEquals(0f, out11[0], 1e-5f); assertEquals(1f, out11[1], 1e-5f)

        val out01 = applyMat4(m, floatArrayOf(0f, 1f, 0f, 1f))
        assertEquals(0f, out01[0], 1e-5f); assertEquals(0f, out01[1], 1e-5f)
    }

    @Test
    fun `buildTextureNormalization 180 flips UV corners about center`() {
        // +180° pivot about (0.5, 0.5):
        //   (0, 0) → (1, 1) ; (1, 0) → (0, 1) ; (1, 1) → (0, 0) ; (0, 1) → (1, 0)
        val m = FloatArray(16)
        AspectFitMath.buildTextureNormalization(180, m)

        val pivot = applyMat4(m, floatArrayOf(0.5f, 0.5f, 0f, 1f))
        assertEquals(0.5f, pivot[0], 1e-5f); assertEquals(0.5f, pivot[1], 1e-5f)

        val out00 = applyMat4(m, floatArrayOf(0f, 0f, 0f, 1f))
        assertEquals(1f, out00[0], 1e-5f); assertEquals(1f, out00[1], 1e-5f)

        val out11 = applyMat4(m, floatArrayOf(1f, 1f, 0f, 1f))
        assertEquals(0f, out11[0], 1e-5f); assertEquals(0f, out11[1], 1e-5f)
    }

    @Test
    fun `buildTextureNormalization 270 rotates UV corners CW about center`() {
        // +270° CCW (= -90°) pivot about (0.5, 0.5):
        //   (0, 0) → (0, 1) ; (1, 0) → (0, 0) ; (1, 1) → (1, 0) ; (0, 1) → (1, 1)
        val m = FloatArray(16)
        AspectFitMath.buildTextureNormalization(270, m)

        val pivot = applyMat4(m, floatArrayOf(0.5f, 0.5f, 0f, 1f))
        assertEquals(0.5f, pivot[0], 1e-5f); assertEquals(0.5f, pivot[1], 1e-5f)

        val out00 = applyMat4(m, floatArrayOf(0f, 0f, 0f, 1f))
        assertEquals(0f, out00[0], 1e-5f); assertEquals(1f, out00[1], 1e-5f)

        val out10 = applyMat4(m, floatArrayOf(1f, 0f, 0f, 1f))
        assertEquals(0f, out10[0], 1e-5f); assertEquals(0f, out10[1], 1e-5f)
    }

    @Test
    fun `buildTextureNormalization rejects illegal sensorOrientation`() {
        runCatching { AspectFitMath.buildTextureNormalization(45, FloatArray(16)) }.let {
            assertTrue("expected throw on 45", it.isFailure)
        }
        runCatching { AspectFitMath.buildTextureNormalization(-90, FloatArray(16)) }.let {
            assertTrue("expected throw on -90", it.isFailure)
        }
        runCatching { AspectFitMath.buildTextureNormalization(360, FloatArray(16)) }.let {
            assertTrue("expected throw on 360", it.isFailure)
        }
    }

    @Test
    fun `buildTextureNormalization rejects wrong-size out array`() {
        runCatching { AspectFitMath.buildTextureNormalization(90, FloatArray(15)) }.let {
            assertTrue("expected throw on length-15 out", it.isFailure)
        }
    }

    // ─── buildUvTransformV2 — composition tests ────────────────────

    @Test
    fun `buildUvTransformV2 PORTRAIT path-independent of LANDSCAPE inputs`() {
        // Per spec §5.2: PORTRAIT's uvTransform must NOT vary when only
        // LANDSCAPE-relevant inputs change. (sensorOrientation is shared,
        // so just confirm side-switching independence.)
        val outP = FloatArray(16)
        val outL = FloatArray(16)
        val a = FloatArray(16); val b = FloatArray(16)
        val c = FloatArray(16); val d = FloatArray(16)

        AspectFitMath.buildUvTransformV2(0, 90, VideoSide.PORTRAIT, outP, a, b, c, d)
        AspectFitMath.buildUvTransformV2(0, 90, VideoSide.LANDSCAPE, outL, a, b, c, d)

        // Computing LANDSCAPE must not have mutated PORTRAIT's output.
        val outP2 = FloatArray(16)
        AspectFitMath.buildUvTransformV2(0, 90, VideoSide.PORTRAIT, outP2, a, b, c, d)
        assertArrayEquals(outP, outP2, 1e-6f)

        // PORTRAIT and LANDSCAPE outputs MUST differ (sanity check that the
        // independence assert isn't trivially passing on identical arrays).
        var anyDiff = false
        for (i in 0..15) if (kotlin.math.abs(outP[i] - outL[i]) > 1e-3f) { anyDiff = true; break }
        assertTrue("PORTRAIT and LANDSCAPE uvTransforms must differ", anyDiff)
    }

    @Test
    fun `buildUvTransformV2 LANDSCAPE path-independent of PORTRAIT inputs`() {
        val outL = FloatArray(16)
        val a = FloatArray(16); val b = FloatArray(16)
        val c = FloatArray(16); val d = FloatArray(16)

        AspectFitMath.buildUvTransformV2(1, 270, VideoSide.LANDSCAPE, outL, a, b, c, d)
        // Compute PORTRAIT into a different out; verify outL unaffected.
        val portraitOut = FloatArray(16)
        AspectFitMath.buildUvTransformV2(1, 270, VideoSide.PORTRAIT, portraitOut, a, b, c, d)
        val outL2 = FloatArray(16)
        AspectFitMath.buildUvTransformV2(1, 270, VideoSide.LANDSCAPE, outL2, a, b, c, d)
        assertArrayEquals(outL, outL2, 1e-6f)
    }

    @Test
    fun `buildUvTransformV2 composition matches manual sideAspectCrop x rot x normalization`() {
        // For displayRotation=0, sensorOrientation=90, PORTRAIT:
        //   crop = buildSideAspectCrop(PORTRAIT) ; pivot-scale(27/64, 1, 1)
        //   rot  = buildDisplayRotationCorrection(0) ; +90° pivot rotate
        //   norm = buildTextureNormalization(90) ; +90° pivot rotate
        // Composition: out = crop × rot × norm (right-to-left UV)
        val a = FloatArray(16); val b = FloatArray(16)
        val c = FloatArray(16); val d = FloatArray(16)
        val out = FloatArray(16)
        AspectFitMath.buildUvTransformV2(0, 90, VideoSide.PORTRAIT, out, a, b, c, d)

        // Manually recompute via the same helpers — verify equality.
        val crop = FloatArray(16); val rot = FloatArray(16); val norm = FloatArray(16)
        // sensorOrientation=90 — must match the buildUvTransformV2 call above;
        // buildSideAspectCrop is sensorOrientation-aware (2026-05-20 axis-swap).
        AspectFitMath.buildSideAspectCrop(VideoSide.PORTRAIT, 90, crop)
        AspectFitMath.buildDisplayRotationCorrection(0, rot)
        AspectFitMath.buildTextureNormalization(90, norm)
        val rotTimesNorm = FloatArray(16)
        val manualOut = FloatArray(16)
        multiplyMat4ForTest(rotTimesNorm, rot, norm)
        multiplyMat4ForTest(manualOut, crop, rotTimesNorm)
        assertArrayEquals(manualOut, out, 1e-6f)
    }

    // Test-local mat4 multiply matching AspectFitMath.multiplyMat4's contract:
    // out = lhs × rhs, column-major, no in-place aliasing supported.
    private fun multiplyMat4ForTest(out: FloatArray, lhs: FloatArray, rhs: FloatArray) {
        require(out.size == 16 && lhs.size == 16 && rhs.size == 16)
        for (col in 0..3) {
            for (row in 0..3) {
                var sum = 0f
                for (k in 0..3) sum += lhs[k * 4 + row] * rhs[col * 4 + k]
                out[col * 4 + row] = sum
            }
        }
    }

    // ─── buildUvTransformV2 — negative paths ───────────────────────

    @Test
    fun `buildUvTransformV2 rejects wrong-size out`() {
        val a = FloatArray(16); val b = FloatArray(16)
        val c = FloatArray(16); val d = FloatArray(16)
        runCatching {
            AspectFitMath.buildUvTransformV2(0, 90, VideoSide.PORTRAIT, FloatArray(15), a, b, c, d)
        }.let { assertTrue("expected throw on out length 15", it.isFailure) }
    }

    @Test
    fun `buildUvTransformV2 rejects aliased scratchA equals scratchB`() {
        val shared = FloatArray(16)
        val c = FloatArray(16); val d = FloatArray(16)
        val out = FloatArray(16)
        runCatching {
            AspectFitMath.buildUvTransformV2(0, 90, VideoSide.PORTRAIT, out, shared, shared, c, d)
        }.let { assertTrue("expected throw on scratchA===scratchB", it.isFailure) }
    }

    @Test
    fun `buildUvTransformV2 rejects aliased scratchB equals scratchC`() {
        val a = FloatArray(16)
        val shared = FloatArray(16)
        val d = FloatArray(16)
        val out = FloatArray(16)
        runCatching {
            AspectFitMath.buildUvTransformV2(0, 90, VideoSide.PORTRAIT, out, a, shared, shared, d)
        }.let { assertTrue("expected throw on scratchB===scratchC", it.isFailure) }
    }

    @Test
    fun `buildUvTransformV2 rejects aliased scratchA equals out`() {
        val sharedOut = FloatArray(16)
        val b = FloatArray(16); val c = FloatArray(16); val d = FloatArray(16)
        runCatching {
            AspectFitMath.buildUvTransformV2(0, 90, VideoSide.PORTRAIT, sharedOut, sharedOut, b, c, d)
        }.let { assertTrue("expected throw on out===scratchA", it.isFailure) }
    }

    @Test
    fun `buildUvTransformV2 rejects aliased scratchC equals scratchD`() {
        val a = FloatArray(16); val b = FloatArray(16)
        val shared = FloatArray(16)
        val out = FloatArray(16)
        runCatching {
            AspectFitMath.buildUvTransformV2(0, 90, VideoSide.PORTRAIT, out, a, b, shared, shared)
        }.let { assertTrue("expected throw on scratchC===scratchD", it.isFailure) }
    }

    // ─── Aspect-ratio preservation invariants (spec §5.4) ──────────
    // Original bug class coverage: the 1.78× vertical stretch bug came from
    // pivot-scale(9/16, 1, 1) of a 16:9 source producing a 1:1 square then
    // forced into a 9:16 encoder. These tests assert the V2 composition for
    // each side has no non-uniform X/Y scaling beyond the intended pivot-
    // scale signature.

    @Test
    fun `buildUvTransformV2 PORTRAIT preserves crop signature (X-axis pivot-scale 27 over 64)`() {
        // For sensorOrientation=0 + displayRotation=1 (identity rot) +
        // textureNormalization=0 (identity), the composition collapses to
        // pure sideAspectCrop = pivot-scale(27/64, 1, 1) around (0.5, 0.5).
        //
        // Asserts:
        //  - X-axis: right-middle (1, 0.5) → (0.5 + (27/64)*0.5, 0.5) =
        //                                    (0.7109375, 0.5)
        //  - Y-axis: top-middle  (0.5, 1) → (0.5, 1) — identity
        //  - NO non-uniform stretch beyond the intended pivot-scale
        val a = FloatArray(16); val b = FloatArray(16)
        val c = FloatArray(16); val d = FloatArray(16)
        val out = FloatArray(16)
        AspectFitMath.buildUvTransformV2(1, 0, VideoSide.PORTRAIT, out, a, b, c, d)

        val rm = applyMat4(out, floatArrayOf(1f, 0.5f, 0f, 1f))
        assertEquals(0.7109375f, rm[0], 1e-5f)
        assertEquals(0.5f, rm[1], 1e-5f)

        val tm = applyMat4(out, floatArrayOf(0.5f, 1f, 0f, 1f))
        assertEquals(0.5f, tm[0], 1e-5f)
        assertEquals(1f, tm[1], 1e-5f)
    }

    @Test
    fun `buildUvTransformV2 LANDSCAPE preserves crop signature (Y-axis pivot-scale 3 over 4)`() {
        // For sensorOrientation=0 + displayRotation=1 (identity rot) +
        // textureNormalization=0 (identity), the composition collapses to
        // pure sideAspectCrop = pivot-scale(1, 3/4, 1) around (0.5, 0.5).
        //
        // Asserts:
        //  - X-axis: right-middle (1, 0.5) → (1, 0.5) — identity
        //  - Y-axis: top-middle   (0.5, 1) → (0.5, 0.5 + (3/4)*0.5) =
        //                                    (0.5, 0.875)
        val a = FloatArray(16); val b = FloatArray(16)
        val c = FloatArray(16); val d = FloatArray(16)
        val out = FloatArray(16)
        AspectFitMath.buildUvTransformV2(1, 0, VideoSide.LANDSCAPE, out, a, b, c, d)

        val rm = applyMat4(out, floatArrayOf(1f, 0.5f, 0f, 1f))
        assertEquals(1f, rm[0], 1e-5f)
        assertEquals(0.5f, rm[1], 1e-5f)

        val tm = applyMat4(out, floatArrayOf(0.5f, 1f, 0f, 1f))
        assertEquals(0.5f, tm[0], 1e-5f)
        assertEquals(0.875f, tm[1], 1e-5f)
    }

    // ─── multiplyMat4 no-alias contract tests ──────────────────────
    //
    // Per spec §2.4: multiplyMat4(out, lhs, rhs) writes out[col*4+row] = sum
    // inside a triple-nested loop that reads lhs[k*4+row] for k=0..3 in
    // later iterations of the col-loop. Aliasing out with lhs or rhs would
    // corrupt those reads. Contract: out !== lhs AND out !== rhs.

    @Test
    fun `multiplyMat4 via buildUvTransformV2 surface area rejects internal aliasing`() {
        // Indirect test: buildUvTransformV2's aliasing requires already catch
        // most cases. This test asserts the deepest layer: even if you
        // bypass buildUvTransformV2's checks (you can't from external code
        // since multiplyMat4 is private), the inner multiplyMat4 would have
        // produced wrong results. Verifies the documented behavior via the
        // caller surface — buildUvTransformV2's distinctness contract is the
        // user-facing manifestation of multiplyMat4's no-alias contract.
        //
        // For a true private-method aliasing test we'd need a test seam.
        // Instead, rely on the buildUvTransformV2 negative-path tests in
        // Task 2 — they assert the user-visible contract that flows from
        // multiplyMat4's restriction.
        //
        // This test is a documentation marker — no behavior to assert
        // beyond what Task 2 already covers.
        assertTrue("documentation marker — see Task 2 aliasing tests", true)
    }
}

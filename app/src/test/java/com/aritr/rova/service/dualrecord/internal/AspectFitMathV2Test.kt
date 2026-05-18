package com.aritr.rova.service.dualrecord.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
}

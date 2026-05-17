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
    fun `buildSideAspectCrop PORTRAIT produces center-crop scale 9 over 16 in x`() {
        // Apply matrix to corner UV (1, 0.5, 0, 1) — right-middle of [0,1]² —
        // and verify x maps to 0.5 + 9/32 = 0.78125 (the right edge of the
        // center 9:16 column of a 16:9 source).
        val m = FloatArray(16)
        AspectFitMath.buildSideAspectCrop(VideoSide.PORTRAIT, m)
        val out = applyMat4(m, floatArrayOf(1f, 0.5f, 0f, 1f))
        assertEquals(0.78125f, out[0], 1e-5f)
        assertEquals(0.5f, out[1], 1e-5f)
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
    fun `buildSideAspectCrop LANDSCAPE is identity`() {
        val m = FloatArray(16)
        AspectFitMath.buildSideAspectCrop(VideoSide.LANDSCAPE, m)
        val out = applyMat4(m, floatArrayOf(1f, 1f, 0f, 1f))
        assertEquals(1f, out[0], 1e-5f)
        assertEquals(1f, out[1], 1e-5f)
        val out2 = applyMat4(m, floatArrayOf(0f, 0f, 0f, 1f))
        assertEquals(0f, out2[0], 1e-5f)
        assertEquals(0f, out2[1], 1e-5f)
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
    fun `buildCropMatrix PORTRAIT at rotation 0 composes sideAspectCrop after rotation`() {
        // cropMatrix = sideAspectCrop × displayRotationCorrection.
        // Pivot (0.5, 0.5) is invariant under both factors → must be invariant under their product.
        val m = FloatArray(16)
        AspectFitMath.buildCropMatrix(0, VideoSide.PORTRAIT, m)
        val pivot = applyMat4(m, floatArrayOf(0.5f, 0.5f, 0f, 1f))
        assertEquals(0.5f, pivot[0], 1e-5f)
        assertEquals(0.5f, pivot[1], 1e-5f)
    }

    @Test
    fun `buildCropMatrix LANDSCAPE at rotation 1 is identity`() {
        // Both factors are identity for (rot=1, side=LANDSCAPE) → product is identity.
        val m = FloatArray(16)
        AspectFitMath.buildCropMatrix(1, VideoSide.LANDSCAPE, m)
        val out = applyMat4(m, floatArrayOf(0.25f, 0.75f, 0f, 1f))
        assertEquals(0.25f, out[0], 1e-5f)
        assertEquals(0.75f, out[1], 1e-5f)
    }

    @Test
    fun `buildCropMatrix output length check`() {
        runCatching { AspectFitMath.buildCropMatrix(0, VideoSide.PORTRAIT, FloatArray(15)) }.let {
            assertTrue("expected throw on length 15 array", it.isFailure)
        }
    }
}

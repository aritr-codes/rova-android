package com.aritr.rova.service.dualrecord.internal

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
}

package com.aritr.rova.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class MergeMotionTest {

    @Test
    fun `zero fraction maps to zero degrees`() {
        assertEquals(0f, MergeMotion.angle(0f, reduceMotion = false), 0.001f)
    }

    @Test
    fun `quarter fraction maps to ninety degrees`() {
        assertEquals(90f, MergeMotion.angle(0.25f, reduceMotion = false), 0.001f)
    }

    @Test
    fun `half fraction maps to one hundred eighty degrees`() {
        assertEquals(180f, MergeMotion.angle(0.5f, reduceMotion = false), 0.001f)
    }

    @Test
    fun `near-one fraction approaches three sixty`() {
        assertEquals(359.64f, MergeMotion.angle(0.999f, reduceMotion = false), 0.01f)
    }

    @Test
    fun `fraction of exactly one wraps to zero`() {
        assertEquals(0f, MergeMotion.angle(1f, reduceMotion = false), 0.001f)
    }

    @Test
    fun `fraction above one wraps into range`() {
        assertEquals(90f, MergeMotion.angle(1.25f, reduceMotion = false), 0.001f)
    }

    @Test
    fun `reduced motion holds at zero regardless of fraction`() {
        assertEquals(0f, MergeMotion.angle(0.5f, reduceMotion = true), 0.001f)
        assertEquals(0f, MergeMotion.angle(0.999f, reduceMotion = true), 0.001f)
    }
}

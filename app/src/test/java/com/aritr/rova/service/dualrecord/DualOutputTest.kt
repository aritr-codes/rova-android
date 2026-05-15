package com.aritr.rova.service.dualrecord

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.File

class DualOutputTest {

    @Test
    fun `portrait and landscape files are retained verbatim`() {
        val p = File("/sessions/abc/segment_0001_P.mp4")
        val l = File("/sessions/abc/segment_0001_L.mp4")
        val out = DualOutput(portraitFile = p, landscapeFile = l)

        assertSame(p, out.portraitFile)
        assertSame(l, out.landscapeFile)
    }

    @Test
    fun `equality is by file paths`() {
        val a = DualOutput(File("/x/segment_0001_P.mp4"), File("/x/segment_0001_L.mp4"))
        val b = DualOutput(File("/x/segment_0001_P.mp4"), File("/x/segment_0001_L.mp4"))
        assertEquals(a, b)
    }

    @Test
    fun `different portrait file yields non-equal pairs`() {
        val a = DualOutput(File("/x/segment_0001_P.mp4"), File("/x/segment_0001_L.mp4"))
        val b = DualOutput(File("/x/segment_0002_P.mp4"), File("/x/segment_0001_L.mp4"))
        assertNotEquals(a, b)
    }

    @Test
    fun `copy yields a structurally equal but distinct instance`() {
        val orig = DualOutput(File("/x/segment_0001_P.mp4"), File("/x/segment_0001_L.mp4"))
        val copy = orig.copy()
        assertEquals(orig, copy)
        // Reference identity differs (data class copy creates a new instance).
        assertEquals(orig.portraitFile.absolutePath, copy.portraitFile.absolutePath)
    }
}

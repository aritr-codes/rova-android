package com.aritr.rova.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassResolverTest {

    private val aurora = rovaPalettes.getValue(ThemeSelection.AURORA)
    private fun env(api: Int, reduce: Boolean) = GlassEnvironment(aurora, api, reduce)

    @Test
    fun `api31 non-record non-reduced gets real blur and no scrim`() {
        val m = GlassResolver.resolve(env(31, false), GlassRole.BottomSheet)
        assertTrue("expected blur > 0", m.blurRadius.value > 0f)
        assertNull(m.scrim)
    }

    @Test
    fun `api30 forces zero blur and a heavier fill`() {
        val m = GlassResolver.resolve(env(30, false), GlassRole.BottomSheet)
        assertEquals(0f, m.blurRadius.value, 0f)
        // Pins FALLBACK_ALPHA = 0.86; threshold 0.855 tolerates Compose Color's
        // 8-bit alpha quantization (0.86 round-trips to ~0.8588) while still
        // catching any real lowering of the constant (e.g. 0.82 -> ~0.8196).
        assertTrue("fallback fill must be ~0.86 alpha", m.fill.alpha >= 0.855f)
    }

    @Test
    fun `record chrome never blurs and always gets a scrim at any api`() {
        val hi = GlassResolver.resolve(env(34, false), GlassRole.RecordChrome)
        val lo = GlassResolver.resolve(env(24, false), GlassRole.RecordChrome)
        assertEquals(0f, hi.blurRadius.value, 0f)
        assertEquals(0f, lo.blurRadius.value, 0f)
        assertNotNull(hi.scrim)
        assertNotNull(lo.scrim)
        // Pins RECORD_ALPHA = 0.88; threshold 0.875 tolerates 8-bit alpha
        // quantization (0.88 round-trips to ~0.8784).
        assertTrue("record fill must be ~0.88 alpha", hi.fill.alpha >= 0.875f)
    }

    @Test
    fun `reduce transparency forces a fully solid fill and no blur`() {
        val m = GlassResolver.resolve(env(34, true), GlassRole.Card)
        assertEquals(0f, m.blurRadius.value, 0f)
        assertEquals(1f, m.fill.alpha, 0f)
        assertNull(m.scrim)
    }

    @Test
    fun `reduce transparency beats record-chrome — solid fill, no scrim`() {
        val m = GlassResolver.resolve(env(34, true), GlassRole.RecordChrome)
        assertEquals(0f, m.blurRadius.value, 0f)
        assertEquals(1f, m.fill.alpha, 0f)
        assertNull(m.scrim)
    }

    @Test
    fun `edges always come from the palette`() {
        val m = GlassResolver.resolve(env(31, false), GlassRole.NavBar)
        assertEquals(aurora.edge, m.edge)
        assertEquals(aurora.edgeTop, m.edgeTop)
    }
}

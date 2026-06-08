package com.aritr.rova.ui.theme

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
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
    fun `recordChrome fill is airy and never blurs`() {
        // RecordChrome only ever renders over the pinned NeutralDarkRecordPalette
        // (ADR-0028 §2.4), whose glassTint is the ~0.40 record-panel value. A
        // heavier palette tint (e.g. AURORA @ ~0.58) would float ABOVE the 0.40
        // floor via atLeastAlpha — so the airy-band contract is exercised on the
        // surface RecordChrome is actually composed over.
        val env = GlassEnvironment(NeutralDarkRecordPalette, apiLevel = 34, reduceTransparency = false)
        val m = GlassResolver.resolve(env, GlassRole.RecordChrome)
        assertEquals(0.dp, m.blurRadius)
        assertTrue("fill alpha ${m.fill.alpha} should be ~0.40", m.fill.alpha in 0.36f..0.45f)
        assertNull("RecordChrome scrim is owned by the dock brush, not the resolver", m.scrim)
    }

    @Test
    fun `recordChrome under reduce-transparency is opaque and unblurred`() {
        val env = GlassEnvironment(NeutralDarkRecordPalette, apiLevel = 34, reduceTransparency = true)
        val m = GlassResolver.resolve(env, GlassRole.RecordChrome)
        assertEquals(0.dp, m.blurRadius)
        assertTrue(m.fill.alpha >= 0.99f)
        assertNull(m.scrim)
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

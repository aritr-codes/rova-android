package com.aritr.rova.ui.recovery

import com.aritr.rova.ui.recovery.VendorGuidanceIntents.Candidate
import com.aritr.rova.ui.recovery.VendorGuidanceIntents.VendorComponentRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2 Slice 2.2a — pure tests for the vendor-guidance candidate
 * selector. Targets [VendorGuidanceIntents.selectCandidate] only;
 * `toIntent` and `resolveForCurrent` are the Android boundary and are
 * compiled (not unit-tested) because the project's
 * `unitTests.isReturnDefaultValues = true` would silently no-op
 * `Intent` setters and produce misleading green tests.
 */
class VendorGuidanceIntentsTest {

    private val appId = "com.aritr.rova"

    private fun resolver(
        resolvable: Set<VendorComponentRef> = emptySet(),
        appSettingsResolves: Boolean = true
    ): Pair<(VendorComponentRef) -> Boolean, (String) -> Boolean> =
        ({ ref: VendorComponentRef -> ref in resolvable }) to
            ({ _: String -> appSettingsResolves })

    private fun select(
        manufacturer: String,
        resolvable: Set<VendorComponentRef> = emptySet(),
        appSettingsResolves: Boolean = true,
        appId: String = this.appId
    ): Candidate? {
        val (vendor, app) = resolver(resolvable, appSettingsResolves)
        return VendorGuidanceIntents.selectCandidate(
            manufacturer = manufacturer,
            appId = appId,
            canResolveVendor = vendor,
            canResolveAppSettings = app
        )
    }

    private fun firstRefFor(manufacturer: String): VendorComponentRef =
        VendorGuidanceIntents.candidatesFor(manufacturer.lowercase())
            .first().second.first()

    // ─── per-vendor selection ──────────────────────────────────────

    @Test
    fun `MIUI chooses first resolvable MIUI candidate`() {
        val ref = firstRefFor("xiaomi")
        val r = select("Xiaomi", resolvable = setOf(ref)) as Candidate.VendorComponent
        assertEquals("MIUI", r.vendorLabel)
        assertEquals(ref, r.ref)
    }

    @Test
    fun `Samsung chooses Samsung candidate`() {
        val ref = firstRefFor("samsung")
        val r = select("Samsung", resolvable = setOf(ref)) as Candidate.VendorComponent
        assertEquals("Samsung", r.vendorLabel)
        assertEquals(ref, r.ref)
    }

    @Test
    fun `OnePlus chooses OnePlus candidate`() {
        val ref = firstRefFor("oneplus")
        val r = select("OnePlus", resolvable = setOf(ref)) as Candidate.VendorComponent
        assertEquals("OnePlus", r.vendorLabel)
        assertEquals(ref, r.ref)
    }

    @Test
    fun `Vivo chooses Vivo candidate`() {
        val ref = firstRefFor("vivo")
        val r = select("vivo", resolvable = setOf(ref)) as Candidate.VendorComponent
        assertEquals("Vivo", r.vendorLabel)
        assertEquals(ref, r.ref)
    }

    @Test
    fun `Oppo chooses Oppo candidate`() {
        val ref = firstRefFor("oppo")
        val r = select("OPPO", resolvable = setOf(ref)) as Candidate.VendorComponent
        assertEquals("Oppo", r.vendorLabel)
        assertEquals(ref, r.ref)
    }

    // ─── alias keys (sub-brands) ───────────────────────────────────

    @Test
    fun `Redmi alias maps to MIUI bucket`() {
        val ref = firstRefFor("xiaomi")
        val r = select("Redmi", resolvable = setOf(ref)) as Candidate.VendorComponent
        assertEquals("MIUI", r.vendorLabel)
    }

    @Test
    fun `Poco alias maps to MIUI bucket`() {
        val ref = firstRefFor("xiaomi")
        val r = select("POCO", resolvable = setOf(ref)) as Candidate.VendorComponent
        assertEquals("MIUI", r.vendorLabel)
    }

    @Test
    fun `iQOO alias maps to Vivo bucket`() {
        val ref = firstRefFor("vivo")
        val r = select("iQOO", resolvable = setOf(ref)) as Candidate.VendorComponent
        assertEquals("Vivo", r.vendorLabel)
    }

    @Test
    fun `Realme alias maps to Oppo bucket`() {
        val ref = firstRefFor("oppo")
        val r = select("realme", resolvable = setOf(ref)) as Candidate.VendorComponent
        assertEquals("Oppo", r.vendorLabel)
    }

    // ─── case-insensitivity & whitespace ───────────────────────────

    @Test
    fun `manufacturer matching is case-insensitive`() {
        val ref = firstRefFor("xiaomi")
        val cases = listOf("XIAOMI", "Xiaomi", "xiaomi", "xIaOmI")
        for (m in cases) {
            val r = select(m, resolvable = setOf(ref)) as Candidate.VendorComponent
            assertEquals("input=$m", "MIUI", r.vendorLabel)
        }
    }

    @Test
    fun `manufacturer leading and trailing whitespace is trimmed`() {
        val ref = firstRefFor("samsung")
        val r = select("  Samsung  ", resolvable = setOf(ref)) as Candidate.VendorComponent
        assertEquals("Samsung", r.vendorLabel)
    }

    // ─── candidate iteration order ─────────────────────────────────

    @Test
    fun `candidate order is deterministic - earlier candidate wins`() {
        val miui = VendorGuidanceIntents.candidatesFor("xiaomi").first().second
        require(miui.size >= 2) { "MIUI bucket must have at least two candidates for this test" }
        val r = select("Xiaomi", resolvable = miui.toSet()) as Candidate.VendorComponent
        assertEquals("first declared candidate wins", miui.first(), r.ref)
    }

    @Test
    fun `falls back to second MIUI candidate when first does not resolve`() {
        val miui = VendorGuidanceIntents.candidatesFor("xiaomi").first().second
        require(miui.size >= 2)
        val r = select("Xiaomi", resolvable = setOf(miui[1])) as Candidate.VendorComponent
        assertEquals(miui[1], r.ref)
    }

    // ─── fallback paths ────────────────────────────────────────────

    @Test
    fun `unknown manufacturer falls back to app settings`() {
        val r = select("UnknownBrand", resolvable = emptySet(), appSettingsResolves = true)
            as Candidate.AppDetailsSettings
        assertEquals(appId, r.packageName)
    }

    @Test
    fun `vendor matched but no candidate resolves - falls back to app settings`() {
        val r = select("Xiaomi", resolvable = emptySet(), appSettingsResolves = true)
            as Candidate.AppDetailsSettings
        assertEquals(appId, r.packageName)
    }

    @Test
    fun `fallback unresolved returns null`() {
        val r = select("UnknownBrand", resolvable = emptySet(), appSettingsResolves = false)
        assertNull(r)
    }

    @Test
    fun `vendor matched but unresolved AND app settings unresolved returns null`() {
        val r = select("Samsung", resolvable = emptySet(), appSettingsResolves = false)
        assertNull(r)
    }

    @Test
    fun `empty manufacturer string falls back to app settings`() {
        val r = select("", resolvable = emptySet(), appSettingsResolves = true)
            as Candidate.AppDetailsSettings
        assertEquals(appId, r.packageName)
    }

    // ─── candidatesFor surface ─────────────────────────────────────

    @Test
    fun `candidatesFor returns empty for unknown key`() {
        assertTrue(VendorGuidanceIntents.candidatesFor("nokia").isEmpty())
        assertTrue(VendorGuidanceIntents.candidatesFor("").isEmpty())
    }

    @Test
    fun `candidatesFor returns non-empty MIUI bucket for xiaomi`() {
        val buckets = VendorGuidanceIntents.candidatesFor("xiaomi")
        assertEquals(1, buckets.size)
        assertEquals("MIUI", buckets.single().first)
        assertTrue(buckets.single().second.isNotEmpty())
    }

    @Test
    fun `every supported vendor has at least one candidate`() {
        val keys = listOf("xiaomi", "samsung", "oneplus", "vivo", "oppo")
        for (k in keys) {
            val buckets = VendorGuidanceIntents.candidatesFor(k)
            assertTrue("vendor=$k has buckets", buckets.isNotEmpty())
            assertTrue("vendor=$k bucket non-empty", buckets.first().second.isNotEmpty())
        }
    }

    // ─── regression guard ──────────────────────────────────────────

    @Test
    fun `regression - VendorComponentRef equality matches by package and class`() {
        val a = VendorComponentRef("p", "c")
        val b = VendorComponentRef("p", "c")
        assertEquals("data class equality", a, b)
        assertEquals("hashCode equality", a.hashCode(), b.hashCode())
    }

    @Test
    fun `regression - vendor pick does NOT depend on appSettings resolver`() {
        // If a vendor candidate resolves, the app-settings probe must
        // never be consulted. Encoded as: with appSettingsResolves=false,
        // a resolvable vendor still wins.
        val ref = firstRefFor("samsung")
        val r = select("Samsung", resolvable = setOf(ref), appSettingsResolves = false)
        assertNotNull(r)
        assertTrue(r is Candidate.VendorComponent)
    }
}

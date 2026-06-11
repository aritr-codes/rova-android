package com.aritr.rova.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SessionConfigModeTest {

    private fun base(topology: String = "Single") = SessionConfig(
        durationSeconds = 5, intervalMinutes = 0, resolution = "FHD", loopCount = 2,
        captureTopology = topology,
    )

    @Test
    fun defaults_areSingle_followDevice_noLock_noLegacy() {
        val c = SessionConfig(durationSeconds = 5, intervalMinutes = 0, resolution = "FHD", loopCount = 2)
        assertEquals("Single", c.captureTopology)
        assertEquals("FollowDevice", c.orientationPolicy)
        assertEquals(-1, c.orientationLockRotation)
        assertEquals(null, c.legacyMode)
    }

    @Test
    fun toJson_writesTopologyAndPolicy_omitsLockAndLegacyWhenUnset() {
        val json = base().toJson()
        assertEquals("Single", json.getString("captureTopology"))
        assertEquals("FollowDevice", json.getString("orientationPolicy"))
        assertFalse(json.has("orientationLockRotation"))
        assertFalse(json.has("mode")) // new sessions never write the legacy key
    }

    @Test
    fun lockedConfig_roundTrips() {
        val c = base().copy(orientationPolicy = "Lock", orientationLockRotation = 1)
        val back = SessionConfig.fromJson(c.toJson())
        assertEquals("Lock", back.orientationPolicy)
        assertEquals(1, back.orientationLockRotation)
    }

    @Test
    fun dualShot_roundTrips() {
        val back = SessionConfig.fromJson(base("DualShot").toJson())
        assertEquals("DualShot", back.captureTopology)
    }

    @Test
    fun legacyManifest_portraitLandscape_derivesDualShot_andPreservesLegacyMode() {
        // A schema<=10 manifest: has "mode", no "captureTopology" (ADR-0029 S6 read-compat).
        val legacy = JSONObject().apply {
            put("durationSeconds", 5); put("intervalMinutes", 0)
            put("resolution", "FHD"); put("loopCount", 2)
            put("mode", "PortraitLandscape")
        }
        val c = SessionConfig.fromJson(legacy)
        assertEquals("DualShot", c.captureTopology)
        assertEquals("PortraitLandscape", c.legacyMode)
        // Round-trip must NOT lose the historical label (recovery rewrites manifests).
        assertEquals("PortraitLandscape", c.toJson().getString("mode"))
    }

    @Test
    fun legacyManifest_portraitAndLandscape_deriveSingle() {
        for (legacyMode in listOf("Portrait", "Landscape")) {
            val legacy = JSONObject().apply {
                put("durationSeconds", 5); put("intervalMinutes", 0)
                put("resolution", "FHD"); put("loopCount", 2)
                put("mode", legacyMode)
            }
            val c = SessionConfig.fromJson(legacy)
            assertEquals("Single", c.captureTopology)
            assertEquals(legacyMode, c.legacyMode)
        }
    }

    @Test
    fun garbageTopology_coercesToSingle() {
        val json = base().toJson().put("captureTopology", "P + L")
        assertEquals("Single", SessionConfig.fromJson(json).captureTopology)
    }

    @Test
    fun garbagePolicy_coercesToFollowDevice() {
        val json = base().toJson().put("orientationPolicy", "Auto")
        assertEquals("FollowDevice", SessionConfig.fromJson(json).orientationPolicy)
    }
}

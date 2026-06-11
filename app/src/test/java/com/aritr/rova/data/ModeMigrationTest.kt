package com.aritr.rova.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ModeMigrationTest {

    @Test
    fun portrait_migratesTo_singlePlusPortraitLock_flaggedLegacy() {
        val m = ModeMigration.migrate("Portrait")
        assertEquals("Single", m.topology)
        assertEquals("Lock", m.policy)
        assertEquals(0, m.lockRotation) // Surface.ROTATION_0
        assertEquals(true, m.legacyMigrated)
    }

    @Test
    fun landscape_migratesTo_singlePlusLandscapeLock_flaggedLegacy() {
        val m = ModeMigration.migrate("Landscape")
        assertEquals("Single", m.topology)
        assertEquals("Lock", m.policy)
        assertEquals(1, m.lockRotation) // Surface.ROTATION_90
        assertEquals(true, m.legacyMigrated)
    }

    @Test
    fun portraitLandscape_migratesTo_dualShot_followDevice() {
        val m = ModeMigration.migrate("PortraitLandscape")
        assertEquals("DualShot", m.topology)
        assertEquals("FollowDevice", m.policy)
        assertEquals(-1, m.lockRotation)
        assertEquals(false, m.legacyMigrated)
    }

    @Test
    fun nullOrGarbage_migratesTo_singleFollowDevice() {
        for (input in listOf(null, "", "P + L", "single")) {
            val m = ModeMigration.migrate(input)
            assertEquals("Single", m.topology)
            assertEquals("FollowDevice", m.policy)
            assertEquals(false, m.legacyMigrated)
        }
    }

    @Test
    fun captureTopology_validPersistedValues() {
        assertEquals(true, CaptureTopology.isValidPersisted("Single"))
        assertEquals(true, CaptureTopology.isValidPersisted("DualShot"))
        assertEquals(true, CaptureTopology.isValidPersisted("FrontBack"))
        assertEquals(false, CaptureTopology.isValidPersisted("Portrait"))
        assertEquals(false, CaptureTopology.isValidPersisted("PortraitLandscape"))
        assertEquals(false, CaptureTopology.isValidPersisted(""))
    }
}

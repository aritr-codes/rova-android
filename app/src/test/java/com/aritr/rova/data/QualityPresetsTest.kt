package com.aritr.rova.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM-only tests pinning the canonical vocabulary, the picker order,
 * and the alias-folding contract that StorageEstimator + RovaSettings
 * + RecordScreen + RovaRecordingService all rely on after Slice 12.
 *
 * `canonicalize` MUST return `null` on unknown input — callers depend
 * on that to choose their own fallback (FHD for StorageEstimator,
 * strict match in the CameraX mapping). A future "default to FHD"
 * shortcut here would silently widen the service's mapping contract.
 */
class QualityPresetsTest {

    @Test
    fun `default is FHD`() {
        assertEquals("FHD", QualityPresets.DEFAULT)
    }

    @Test
    fun `picker order is SD HD FHD 4K`() {
        assertEquals(listOf("SD", "HD", "FHD", "4K"), QualityPresets.PICKER_ORDER)
    }

    @Test
    fun `canonical labels round-trip through canonicalize`() {
        assertEquals("SD", QualityPresets.canonicalize("SD"))
        assertEquals("HD", QualityPresets.canonicalize("HD"))
        assertEquals("FHD", QualityPresets.canonicalize("FHD"))
        assertEquals("4K", QualityPresets.canonicalize("4K"))
    }

    @Test
    fun `canonicalize folds 480p aliases to SD`() {
        assertEquals("SD", QualityPresets.canonicalize("480p"))
        assertEquals("SD", QualityPresets.canonicalize("480P"))
    }

    @Test
    fun `canonicalize folds 720p to HD`() {
        assertEquals("HD", QualityPresets.canonicalize("720p"))
        assertEquals("HD", QualityPresets.canonicalize("720P"))
    }

    @Test
    fun `canonicalize folds 1080p to FHD`() {
        assertEquals("FHD", QualityPresets.canonicalize("1080p"))
        assertEquals("FHD", QualityPresets.canonicalize("1080P"))
    }

    @Test
    fun `canonicalize folds 2160p and UHD to 4K`() {
        assertEquals("4K", QualityPresets.canonicalize("2160p"))
        assertEquals("4K", QualityPresets.canonicalize("2160P"))
        assertEquals("4K", QualityPresets.canonicalize("UHD"))
        assertEquals("4K", QualityPresets.canonicalize("uhd"))
    }

    @Test
    fun `canonicalize is case-insensitive on canonical labels`() {
        assertEquals("FHD", QualityPresets.canonicalize("fhd"))
        assertEquals("HD", QualityPresets.canonicalize("hd"))
        assertEquals("4K", QualityPresets.canonicalize("4k"))
    }

    @Test
    fun `canonicalize returns null on unknown input`() {
        // Callers depend on null so they can choose their own fallback.
        assertNull(QualityPresets.canonicalize("8K"))
        assertNull(QualityPresets.canonicalize("garbage"))
        assertNull(QualityPresets.canonicalize(""))
        assertNull(QualityPresets.canonicalize(null))
    }

    @Test
    fun `canonicalizeOrDefault falls back to FHD`() {
        assertEquals("FHD", QualityPresets.canonicalizeOrDefault("garbage"))
        assertEquals("FHD", QualityPresets.canonicalizeOrDefault(null))
        assertEquals("FHD", QualityPresets.canonicalizeOrDefault(""))
    }

    @Test
    fun `canonicalizeOrDefault honors canonical and alias input`() {
        assertEquals("HD", QualityPresets.canonicalizeOrDefault("HD"))
        assertEquals("HD", QualityPresets.canonicalizeOrDefault("720p"))
        assertEquals("4K", QualityPresets.canonicalizeOrDefault("2160p"))
        assertEquals("SD", QualityPresets.canonicalizeOrDefault("480p"))
    }

    @Test
    fun `canonicalizeOrDefault accepts caller-supplied default`() {
        assertEquals("HD", QualityPresets.canonicalizeOrDefault("garbage", "HD"))
        assertEquals("SD", QualityPresets.canonicalizeOrDefault(null, "SD"))
    }
}

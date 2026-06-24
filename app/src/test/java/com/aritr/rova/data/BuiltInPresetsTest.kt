package com.aritr.rova.data

import com.aritr.rova.ui.screens.RecordSettingBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInPresetsTest {

    @Test fun thereAreExactlyFourBuiltIns() {
        assertEquals(4, BuiltInPresets.all.size)
    }

    @Test fun idsAreNamespacedAndUnique() {
        val ids = BuiltInPresets.all.map { it.id }
        assertTrue(ids.all { it.startsWith("builtin.") })
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun allAreTaggedBuiltIn() {
        assertTrue(BuiltInPresets.all.all { it.isBuiltIn })
    }

    @Test fun valueTuplesArePairwiseDistinct() {
        // Required so "modified -> Custom" is observable from values alone.
        val tuples = BuiltInPresets.all.map {
            listOf(it.duration, it.intervalSeconds, it.loopCount, QualityPresets.canonicalizeOrDefault(it.resolution))
        }
        assertEquals(tuples.size, tuples.toSet().size)
    }

    @Test fun valuesAreWithinBounds() {
        BuiltInPresets.all.forEach { p ->
            assertTrue(p.duration in RecordSettingBounds.CLIP_MIN..RecordSettingBounds.CLIP_MAX)
            assertTrue(p.intervalSeconds in RecordSettingBounds.WAIT_ALLOWED)
            val loopOk = p.loopCount == RecordSettingBounds.REPEATS_CONTINUOUS ||
                p.loopCount in RecordSettingBounds.REPEATS_MIN..RecordSettingBounds.REPEATS_MAX
            assertTrue("loopCount ${p.loopCount} out of bounds", loopOk)
            assertEquals(p.resolution, QualityPresets.canonicalizeOrDefault(p.resolution))
        }
    }

    @Test fun intervalSeconds_valuesMatchSpec() {
        val byId = BuiltInPresets.all.associateBy { it.id }
        assertEquals(60, byId["builtin.quick_sample"]!!.intervalSeconds)
        assertEquals(120, byId["builtin.standard"]!!.intervalSeconds)
        assertEquals(300, byId["builtin.long_session"]!!.intervalSeconds)
        assertEquals(0, byId["builtin.continuous"]!!.intervalSeconds)
    }

    @Test fun standardIsDefaultId() {
        assertEquals("builtin.standard", BuiltInPresets.DEFAULT_ID)
    }
}

package com.aritr.rova.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetJsonTest {

    @Test fun emptyArrayDecodesEmpty() {
        assertEquals(emptyList<RovaPreset>(), PresetJson.decode("[]"))
    }

    @Test fun envelopeRoundTrip() {
        val customs = listOf(
            RovaPreset("My Vlog", 45, 3, 15, "FHD", id = "custom.abc", isBuiltIn = false),
        )
        val decoded = PresetJson.decode(PresetJson.encode(customs))
        assertEquals(1, decoded.size)
        assertEquals("My Vlog", decoded[0].name)
        assertEquals(45, decoded[0].duration)
        assertEquals("custom.abc", decoded[0].id)
        assertTrue(!decoded[0].isBuiltIn)
    }

    @Test fun legacyArrayWithoutIdsDerivesStableCustomId() {
        val legacy = """[{"name":"Old","duration":20,"interval":2,"loopCount":5,"resolution":"HD"}]"""
        val decoded = PresetJson.decode(legacy)
        assertEquals(1, decoded.size)
        assertTrue(decoded[0].id.startsWith("custom."))
        // Stable: decoding the same legacy blob twice yields the same id.
        assertEquals(decoded[0].id, PresetJson.decode(legacy)[0].id)
    }

    @Test fun oneMalformedObjectIsSkippedRestSurvive() {
        val mixed = """[{"name":"Good","duration":20,"interval":2,"loopCount":5,"resolution":"HD"},{"name":"Bad"}]"""
        val decoded = PresetJson.decode(mixed)
        assertEquals(1, decoded.size)
        assertEquals("Good", decoded[0].name)
    }

    @Test fun customClaimingBuiltinPrefixIsRenamespaced() {
        val sneaky = """{"presetSchemaVersion":2,"presets":[{"id":"builtin.standard","name":"X","duration":30,"interval":2,"loopCount":20,"resolution":"FHD"}]}"""
        val decoded = PresetJson.decode(sneaky)
        assertEquals(1, decoded.size)
        assertTrue("must not impersonate a built-in", decoded[0].id.startsWith("custom."))
    }

    @Test fun garbageDecodesEmpty() {
        assertEquals(emptyList<RovaPreset>(), PresetJson.decode("not json"))
    }
}

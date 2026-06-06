package com.aritr.rova.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PresetMatcherTest {

    @Test fun exactStandardMatches() {
        assertEquals("builtin.standard", PresetMatcher.match(30, 2, 20, "FHD"))
    }

    @Test fun continuousSentinelMatches() {
        assertEquals("builtin.continuous", PresetMatcher.match(60, 0, -1, "HD"))
    }

    @Test fun legacyResolutionAliasCanonicalizes() {
        // "1080p" is the legacy alias for FHD — Standard should still match.
        assertEquals("builtin.standard", PresetMatcher.match(30, 2, 20, "1080p"))
    }

    @Test fun noMatchReturnsNull() {
        assertNull(PresetMatcher.match(25, 2, 20, "FHD"))
    }

    @Test fun continuousDoesNotMatchFiniteLoop() {
        // Same duration/interval/quality as Continuous but finite loop -> no match.
        assertNull(PresetMatcher.match(60, 0, 5, "HD"))
    }

    @Test fun nullResolutionDoesNotMatch() {
        // Must NOT coerce null to the FHD default (would falsely match Standard).
        assertNull(PresetMatcher.match(30, 2, 20, null))
    }

    @Test fun unknownResolutionDoesNotMatch() {
        assertNull(PresetMatcher.match(30, 2, 20, "garbage"))
    }

    private fun custom(
        name: String, d: Int, i: Int, l: Int, r: String, id: String,
    ) = RovaPreset(name = name, duration = d, interval = i, loopCount = l, resolution = r, id = id)

    @Test fun listOverloadMatchesFirstByValue() {
        val list = listOf(custom("A", 15, 3, 5, "FHD", "custom.a"))
        assertEquals("custom.a", PresetMatcher.match(list, 15, 3, 5, "FHD"))
    }

    @Test fun listOverloadCanonicalizesResolution() {
        val list = listOf(custom("A", 15, 3, 5, "FHD", "custom.a"))
        assertEquals("custom.a", PresetMatcher.match(list, 15, 3, 5, "1080p"))
    }

    @Test fun listOverloadNoMatchReturnsNull() {
        val list = listOf(custom("A", 15, 3, 5, "FHD", "custom.a"))
        assertNull(PresetMatcher.match(list, 99, 3, 5, "FHD"))
    }

    @Test fun matchActiveBuiltInTakesPrecedence() {
        val customs = listOf(custom("Mine", 30, 2, 20, "FHD", "custom.mine"))
        assertEquals("builtin.standard", PresetMatcher.matchActive(customs, 30, 2, 20, "FHD"))
    }

    @Test fun matchActiveFallsBackToCustom() {
        val customs = listOf(custom("Mine", 15, 3, 5, "FHD", "custom.mine"))
        assertEquals("custom.mine", PresetMatcher.matchActive(customs, 15, 3, 5, "FHD"))
    }

    @Test fun matchActiveDuplicateTupleReturnsFirst() {
        val customs = listOf(
            custom("First", 15, 3, 5, "FHD", "custom.first"),
            custom("Second", 15, 3, 5, "FHD", "custom.second"),
        )
        assertEquals("custom.first", PresetMatcher.matchActive(customs, 15, 3, 5, "FHD"))
    }

    @Test fun matchActiveNoMatchReturnsNull() {
        val customs = listOf(custom("Mine", 15, 3, 5, "FHD", "custom.mine"))
        assertNull(PresetMatcher.matchActive(customs, 99, 9, 9, "FHD"))
    }
}

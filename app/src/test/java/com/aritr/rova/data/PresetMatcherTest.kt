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
}

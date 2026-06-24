package com.aritr.rova.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PruneKeepSetTest {

    @Test fun keepsSessionKeyForEveryFinalizedManifest() {
        val keep = PruneKeepSet.build(
            finalizedSessionIds = listOf("s1", "s2"),
            visibleLegacyKeys = emptyList(),
            existingKeys = emptyList(),
        )
        assertTrue("session:s1" in keep)
        assertTrue("session:s2" in keep)
    }

    @Test fun keepsVisibleLegacyKeys_ignoresNulls() {
        val keep = PruneKeepSet.build(
            finalizedSessionIds = emptyList(),
            visibleLegacyKeys = listOf("/p/a.mp4", null),
            existingKeys = emptyList(),
        )
        assertTrue("/p/a.mp4" in keep)
        assertEquals("null entries filtered out, no spurious keys", setOf("/p/a.mp4"), keep)
    }

    @Test fun blanketGrace_keepsExistingNonSessionKeys_dropsOrphanSessionKeys() {
        val keep = PruneKeepSet.build(
            finalizedSessionIds = listOf("s1"),
            visibleLegacyKeys = emptyList(),
            existingKeys = listOf("/p/legacyFav.mp4", "session:deleted"),
        )
        assertTrue("/p/legacyFav.mp4" in keep)         // grace: never prune a legacy alias
        assertFalse("session:deleted" in keep)          // orphan session key not kept
        assertTrue("session:s1" in keep)
    }
}

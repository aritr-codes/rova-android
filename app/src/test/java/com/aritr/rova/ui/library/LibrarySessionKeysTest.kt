package com.aritr.rova.ui.library

import com.aritr.rova.data.CaptureTopology
import com.aritr.rova.service.dualrecord.VideoSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySessionKeysTest {

    private fun sessionRow(key: String, sideKeys: List<String>) = LibraryRow(
        stableKey = key, title = "t", dateLabel = "d", dateMillis = 1L,
        durationMs = 1L, sizeBytes = 1L, clipCount = 1,
        topology = CaptureTopology.DualShot, badge = null, favorite = false,
        sessionKey = key,
        sides = sideKeys.mapIndexed { i, k ->
            LibrarySessionSide(if (i == 0) VideoSide.PORTRAIT else VideoSide.LANDSCAPE, k, 1L, 1)
        },
    )

    private fun plainRow(key: String) = LibraryRow(
        stableKey = key, title = "t", dateLabel = "d", dateMillis = 1L,
        durationMs = 1L, sizeBytes = 1L, clipCount = 1,
        topology = CaptureTopology.Single, badge = null, favorite = false,
    )

    @Test
    fun sessionKey_expandsToBothSideKeys_inOrder() {
        val rows = mapOf("session:s1" to sessionRow("session:s1", listOf("/p.mp4", "/l.mp4")))
        assertEquals(
            setOf("/p.mp4", "/l.mp4"),
            LibrarySessionKeys.expand(listOf("session:s1"), rows),
        )
    }

    @Test
    fun plainKeys_passThrough_unknownKeysKept() {
        val rows = mapOf("a" to plainRow("a"))
        // "ghost" has no row (e.g. deleted mid-flight) — pass through so downstream
        // itemsForKeys can still resolve-or-drop it, exactly like today.
        assertEquals(setOf("a", "ghost"), LibrarySessionKeys.expand(listOf("a", "ghost"), rows))
    }

    @Test
    fun mixedSelection_expandsOnlySessionRows_deduped() {
        val rows = mapOf(
            "session:s1" to sessionRow("session:s1", listOf("/p.mp4", "/l.mp4")),
            "/x.mp4" to plainRow("/x.mp4"),
        )
        val out = LibrarySessionKeys.expand(listOf("/x.mp4", "session:s1", "/p.mp4"), rows)
        assertEquals(setOf("/x.mp4", "/p.mp4", "/l.mp4"), out)
    }

    @Test
    fun recordingIdentity_isSessionKey() {
        assertTrue(RecordingIdentity.isSessionKey("session:abc"))
        assertFalse(RecordingIdentity.isSessionKey("/storage/emulated/0/x.mp4"))
        assertFalse(RecordingIdentity.isSessionKey("content://docs/x"))
    }
}

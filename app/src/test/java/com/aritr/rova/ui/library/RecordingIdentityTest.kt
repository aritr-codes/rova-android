package com.aritr.rova.ui.library

import com.aritr.rova.service.dualrecord.VideoSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingIdentityTest {
    @Test fun sessionKey_prefixes() {
        assertEquals("session:abc", RecordingIdentity.sessionKey("abc"))
    }

    @Test fun legacyKey_prefersPathThenDocUriThenNull() {
        assertEquals("/p/a.mp4", RecordingIdentity.legacyKey("/p/a.mp4", "doc://x"))
        assertEquals("doc://x", RecordingIdentity.legacyKey(null, "doc://x"))
        assertNull(RecordingIdentity.legacyKey(null, null))
    }

    @Test fun sideSlot_singleIsEmpty_sidesAreNames() {
        assertEquals("", RecordingIdentity.sideSlot(null))
        assertEquals("PORTRAIT", RecordingIdentity.sideSlot(VideoSide.PORTRAIT))
        assertEquals("LANDSCAPE", RecordingIdentity.sideSlot(VideoSide.LANDSCAPE))
    }

    @Test fun forItem_manifestBacked_canonicalIsSession_legacyIsAlias() {
        val k = RecordingIdentity.forItem(sessionId = "s1", absolutePath = "/p/a.mp4", docUri = null)
        assertEquals("session:s1", k.canonical)
        assertEquals("/p/a.mp4", k.legacy)
    }

    @Test fun forItem_sessionless_canonicalIsLegacy_noMigration() {
        val k = RecordingIdentity.forItem(sessionId = null, absolutePath = "/p/a.mp4", docUri = null)
        assertEquals("/p/a.mp4", k.canonical)
        assertNull(k.legacy)
    }

    @Test fun forItem_sessionBackedNoFile_legacyNull() {
        val k = RecordingIdentity.forItem(sessionId = "s2", absolutePath = null, docUri = null)
        assertEquals("session:s2", k.canonical)
        assertNull(k.legacy)
    }

    @Test fun forItem_sameSession_sameCanonicalKey_regardlessOfTier() {
        // TIER1 _DATA path vs content:// — identity must not depend on the playable URI tier.
        val viaPath = RecordingIdentity.forItem(sessionId = "s1", absolutePath = "/storage/emulated/0/Movies/a.mp4", docUri = null)
        val viaContentUri = RecordingIdentity.forItem(sessionId = "s1", absolutePath = null, docUri = "content://media/external/video/media/42")
        assertEquals(viaPath.canonical, viaContentUri.canonical)
        assertEquals("session:s1", viaPath.canonical)
    }

    // ADR-0037 §4 — slotFor is the ONLY resume-slot composer.
    @Test
    fun `slotFor without segmentIndex equals legacy sideSlot`() {
        assertEquals("", RecordingIdentity.slotFor(null, null))
        assertEquals("PORTRAIT", RecordingIdentity.slotFor(VideoSide.PORTRAIT, null))
        assertEquals("LANDSCAPE", RecordingIdentity.slotFor(VideoSide.LANDSCAPE, null))
    }

    @Test
    fun `slotFor with segmentIndex ignores side and is injective per index`() {
        assertEquals("#seg0", RecordingIdentity.slotFor(null, 0))
        assertEquals("#seg3", RecordingIdentity.slotFor(null, 3))
        // ADR-0037 §1 — coordinates mutually exclusive; a side passed alongside an
        // index must not change the slot (the identity is malformed upstream, V4b,
        // but the slot function stays total and side-blind).
        assertEquals("#seg3", RecordingIdentity.slotFor(VideoSide.PORTRAIT, 3))
        // Distinct indices → distinct slots; no collision with legacy values.
        assertNotEquals(RecordingIdentity.slotFor(null, 1), RecordingIdentity.slotFor(null, 2))
        assertNotEquals("", RecordingIdentity.slotFor(null, 0))
        assertNotEquals("PORTRAIT", RecordingIdentity.slotFor(VideoSide.PORTRAIT, 0))
    }
}

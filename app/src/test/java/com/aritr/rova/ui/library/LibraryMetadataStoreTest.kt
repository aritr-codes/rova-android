package com.aritr.rova.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryMetadataStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun store() = LibraryMetadataStore(tmp.newFolder("files"))

    @Test
    fun `set then read back favorite and title`() {
        val s = store()
        s.update("/a.mp4") { it.copy(favorite = true, customTitle = "Run") }
        assertEquals(LibraryMetadataEntry(favorite = true, customTitle = "Run"), s.get("/a.mp4"))
    }

    @Test
    fun `persists across instances`() {
        val dir = tmp.newFolder("files2")
        LibraryMetadataStore(dir).update("/a.mp4") { it.copy(favorite = true) }
        assertTrue(LibraryMetadataStore(dir).get("/a.mp4")!!.favorite)
    }

    @Test
    fun `clearing an entry to empty removes it`() {
        val s = store()
        s.update("/a.mp4") { it.copy(favorite = true) }
        s.update("/a.mp4") { it.copy(favorite = false) }
        assertNull(s.get("/a.mp4"))
    }

    @Test
    fun `prune removes keys for deleted rows`() {
        val s = store()
        s.update("/a.mp4") { it.copy(favorite = true) }
        s.update("/b.mp4") { it.copy(favorite = true) }
        s.prune(setOf("/a.mp4"))
        assertTrue(s.get("/a.mp4")!!.favorite)
        assertNull(s.get("/b.mp4"))
    }

    @Test fun dualRead_returnsLegacyWhenCanonicalAbsent() {
        val tmpDir = tmp.newFolder("dualRead1")
        val store = LibraryMetadataStore(tmpDir)
        store.update("/p/a.mp4") { it.copy(favorite = true) } // legacy-only entry
        val key = RecordingIdentity.MetaKey(canonical = "session:s1", legacy = "/p/a.mp4")
        assertTrue(store.get(key)!!.favorite)
    }

    @Test fun mergeOnWrite_migratesLegacyToCanonical_andRemovesLegacy() {
        val tmpDir = tmp.newFolder("mergeOnWrite1")
        val store = LibraryMetadataStore(tmpDir)
        store.update("/p/a.mp4") { it.copy(favorite = true, customTitle = "Old") }
        val key = RecordingIdentity.MetaKey("session:s1", "/p/a.mp4")
        store.update(key) { it.withPosition("", 5_000L) } // first canonical write
        // Reload from disk to prove persistence.
        val reloaded = LibraryMetadataStore(tmpDir)
        assertNull("legacy key removed", reloaded.get("/p/a.mp4"))
        val merged = reloaded.get(key)!!
        assertTrue(merged.favorite)           // preserved through migration
        assertEquals("Old", merged.customTitle)
        assertEquals(5_000L, merged.positionFor(""))
    }

    @Test fun update_canonicalNoLegacy_writesCanonicalOnly() {
        val tmpDir = tmp.newFolder("canonicalOnly1")
        val store = LibraryMetadataStore(tmpDir)
        val key = RecordingIdentity.MetaKey("session:s9", legacy = null)
        store.update(key) { it.withPosition("", 1_000L) }
        assertEquals(1_000L, LibraryMetadataStore(tmpDir).get(key)!!.positionFor(""))
    }

    @Test fun update_pLSides_doNotOverwriteEachOther() {
        val tmpDir = tmp.newFolder("pLSides1")
        val store = LibraryMetadataStore(tmpDir)
        val key = RecordingIdentity.MetaKey("session:s1", legacy = null)
        store.update(key) { it.withPosition("PORTRAIT", 7L) }
        store.update(key) { it.withPosition("LANDSCAPE", 9L) }
        val e = LibraryMetadataStore(tmpDir).get(key)!!
        assertEquals(7L, e.positionFor("PORTRAIT"))
        assertEquals(9L, e.positionFor("LANDSCAPE"))
    }
}

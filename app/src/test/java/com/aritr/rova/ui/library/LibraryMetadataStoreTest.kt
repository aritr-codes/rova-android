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
}

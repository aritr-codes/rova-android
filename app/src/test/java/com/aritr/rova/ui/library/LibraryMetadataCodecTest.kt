package com.aritr.rova.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryMetadataCodecTest {

    @Test
    fun `round-trips entries`() {
        // Both entries non-empty: empty entries are dropped by design (see prune /
        // toJson-skip tests), so they intentionally do not survive a round-trip.
        val map = mapOf(
            "/a/b.mp4" to LibraryMetadataEntry(favorite = true, customTitle = "Beach", lastPlayedAt = 42L),
            "content://x/1" to LibraryMetadataEntry(favorite = true),
        )
        val json = LibraryMetadataCodec.toJson(map)
        val back = LibraryMetadataCodec.fromJson(json)
        assertEquals(map, back)
    }

    @Test
    fun `fromJson tolerates empty and garbage`() {
        assertTrue(LibraryMetadataCodec.fromJson("").isEmpty())
        assertTrue(LibraryMetadataCodec.fromJson("not json").isEmpty())
        assertTrue(LibraryMetadataCodec.fromJson("{}").isEmpty())
    }

    @Test
    fun `fromJson tolerates missing fields with defaults`() {
        val back = LibraryMetadataCodec.fromJson("""{"/a.mp4":{"favorite":true}}""")
        assertEquals(LibraryMetadataEntry(favorite = true), back["/a.mp4"])
    }

    @Test
    fun `positionMs round-trips and is omitted when null`() {
        val withPos = mapOf("rec1" to LibraryMetadataEntry(positionMs = 12_345L))
        val json = LibraryMetadataCodec.toJson(withPos)
        assertEquals(12_345L, LibraryMetadataCodec.fromJson(json)["rec1"]?.positionMs)

        val noPos = LibraryMetadataCodec.toJson(mapOf("rec2" to LibraryMetadataEntry(favorite = true)))
        assertFalse(noPos.contains("positionMs"))
    }

    @Test
    fun `positionMs of zero or negative is not emitted`() {
        // positionMs alone is the only field, and ≤0 must not emit → the entry serializes to nothing.
        assertFalse(LibraryMetadataCodec.toJson(mapOf("z" to LibraryMetadataEntry(positionMs = 0L))).contains("positionMs"))
        assertFalse(LibraryMetadataCodec.toJson(mapOf("n" to LibraryMetadataEntry(positionMs = -5L))).contains("positionMs"))
    }

    @Test
    fun `fromJson without positionMs yields null (backward compatible)`() {
        val back = LibraryMetadataCodec.fromJson("""{"/a.mp4":{"favorite":true,"lastPlayedAt":42}}""")
        assertEquals(LibraryMetadataEntry(favorite = true, lastPlayedAt = 42L), back["/a.mp4"])
        assertNull(back["/a.mp4"]?.positionMs)
    }

    @Test
    fun `prune drops empty entries and keys not in keepSet`() {
        val map = mapOf(
            "keep" to LibraryMetadataEntry(favorite = true),
            "empty" to LibraryMetadataEntry(),
            "gone" to LibraryMetadataEntry(customTitle = "x"),
        )
        val pruned = LibraryMetadataCodec.prune(map, keep = setOf("keep", "empty"))
        assertTrue(pruned.containsKey("keep"))
        assertFalse("empty entry dropped", pruned.containsKey("empty"))
        assertFalse("key not in keep set dropped", pruned.containsKey("gone"))
    }
}

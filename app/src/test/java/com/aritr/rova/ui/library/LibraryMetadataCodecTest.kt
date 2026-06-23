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
    fun `position round-trips and is omitted when absent`() {
        val withPos = mapOf("rec1" to LibraryMetadataEntry(positionsBySide = mapOf("" to 12_345L)))
        val json = LibraryMetadataCodec.toJson(withPos)
        assertEquals(12_345L, LibraryMetadataCodec.fromJson(json)["rec1"]?.positionFor(""))

        val noPos = LibraryMetadataCodec.toJson(mapOf("rec2" to LibraryMetadataEntry(favorite = true)))
        assertFalse(noPos.contains("positionsBySide"))
        assertFalse(noPos.contains("positionMs"))
    }

    @Test
    fun `position of zero or negative is not emitted`() {
        // A single ≤0 position is the only field → the entry serializes to nothing.
        assertFalse(LibraryMetadataCodec.toJson(mapOf("z" to LibraryMetadataEntry(positionsBySide = mapOf("" to 0L)))).contains("positionsBySide"))
        assertFalse(LibraryMetadataCodec.toJson(mapOf("n" to LibraryMetadataEntry(positionsBySide = mapOf("" to -5L)))).contains("positionsBySide"))
    }

    @Test
    fun `fromJson without position yields null (backward compatible)`() {
        val back = LibraryMetadataCodec.fromJson("""{"/a.mp4":{"favorite":true,"lastPlayedAt":42}}""")
        assertEquals(LibraryMetadataEntry(favorite = true, lastPlayedAt = 42L), back["/a.mp4"])
        assertNull(back["/a.mp4"]?.positionFor(""))
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

    @Test fun roundTrip_positionsBySide() {
        val map = mapOf("session:s1" to LibraryMetadataEntry(positionsBySide = mapOf("" to 50_000L, "PORTRAIT" to 1_200L)))
        val decoded = LibraryMetadataCodec.fromJson(LibraryMetadataCodec.toJson(map))
        assertEquals(50_000L, decoded["session:s1"]!!.positionFor(""))
        assertEquals(1_200L, decoded["session:s1"]!!.positionFor("PORTRAIT"))
    }

    @Test fun read_legacyFlatPositionMs_mapsToSingleSlot() {
        val json = """{"session:s1":{"positionMs":42000}}"""
        val decoded = LibraryMetadataCodec.fromJson(json)
        assertEquals(42_000L, decoded["session:s1"]!!.positionFor(""))
    }

    @Test fun write_dropsNonPositivePositions_andOmitsEmptyMap() {
        val map = mapOf("session:s1" to LibraryMetadataEntry(favorite = true, positionsBySide = mapOf("" to 0L)))
        val json = LibraryMetadataCodec.toJson(map)
        assertFalse("must not write positionsBySide for empty/zero", json.contains("positionsBySide"))
        assertFalse("must not write legacy positionMs", json.contains("positionMs"))
    }

    @Test fun read_prefersPositionsBySideOverLegacyFlat() {
        val json = """{"session:s1":{"positionMs":1,"positionsBySide":{"":9}}}"""
        assertEquals(9L, LibraryMetadataCodec.fromJson(json)["session:s1"]!!.positionFor(""))
    }
}

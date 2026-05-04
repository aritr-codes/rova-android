package com.aritr.rova.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * JVM-only tests for the retention cleanup policy. Pins the contract
 * the History refresh path relies on:
 *
 *   * disabled / non-positive keepLatest → no-op
 *   * keepLatest >= eligible.size → no-op
 *   * surplus eligible items get deleted via the [deleteItem] seam
 *   * legacy file-only entries (`sessionId == null`) are skipped
 *   * delete failures are counted but do not abort the batch
 *
 * The retention helper is pure, so the tests do not need an
 * `AndroidViewModel`, a real `ContentResolver`, or a `SessionStore`.
 */
class RecordingRetentionCleanerTest {

    private fun item(sessionId: String?, name: String = sessionId ?: "legacy") = VideoItem(
        file = File("/tmp/rova/$name.mp4"),
        thumbnail = null,
        resolution = "FHD",
        shareUri = null,
        sessionId = sessionId
    )

    @Test
    fun `disabled - no items deleted regardless of count`() {
        val deleted = mutableListOf<String?>()
        val cleaner = RecordingRetentionCleaner(deleteItem = { deleted += it.sessionId; true })
        val items = (1..20).map { item(sessionId = "s$it") }
        val result = cleaner.clean(enabled = false, keepLatest = 5, items = items)
        assertEquals(RecordingRetentionCleaner.Result.NoOp, result)
        assertTrue("no deletes when disabled", deleted.isEmpty())
    }

    @Test
    fun `keep zero - treated as disabled to avoid wiping the library`() {
        val deleted = mutableListOf<String?>()
        val cleaner = RecordingRetentionCleaner(deleteItem = { deleted += it.sessionId; true })
        val items = (1..3).map { item(sessionId = "s$it") }
        val result = cleaner.clean(enabled = true, keepLatest = 0, items = items)
        assertEquals(RecordingRetentionCleaner.Result.NoOp, result)
        assertTrue(deleted.isEmpty())
    }

    @Test
    fun `keep negative - treated as disabled`() {
        val cleaner = RecordingRetentionCleaner(deleteItem = { true })
        val items = (1..3).map { item(sessionId = "s$it") }
        val result = cleaner.clean(enabled = true, keepLatest = -7, items = items)
        assertEquals(RecordingRetentionCleaner.Result.NoOp, result)
    }

    @Test
    fun `enabled keep ten with twelve items - deletes two oldest`() {
        // Items are passed in newest-first per the History refresh
        // contract; the cleaner trims the tail.
        val deleted = mutableListOf<String?>()
        val cleaner = RecordingRetentionCleaner(deleteItem = { deleted += it.sessionId; true })
        val items = (1..12).map { item(sessionId = "s%02d".format(it)) }
        val result = cleaner.clean(enabled = true, keepLatest = 10, items = items)
        assertEquals(2, result.deleted)
        assertEquals(0, result.failed)
        // Newest-first input means the LAST two entries are the
        // oldest and the ones that should be deleted.
        assertEquals(listOf("s11", "s12"), deleted)
    }

    @Test
    fun `keep count respects newest-first ordering - first ten survive`() {
        val deletedSessions = mutableListOf<String?>()
        val cleaner = RecordingRetentionCleaner(deleteItem = { deletedSessions += it.sessionId; true })
        val items = (1..15).map { item(sessionId = "s%02d".format(it)) }
        val result = cleaner.clean(enabled = true, keepLatest = 10, items = items)
        assertEquals(5, result.deleted)
        // Items s01..s10 must NOT appear in the deleted list.
        val survivors = items.take(10).map { it.sessionId }
        survivors.forEach { sid ->
            assertTrue(
                "newest survivor $sid must not be deleted",
                sid !in deletedSessions
            )
        }
    }

    @Test
    fun `library at exactly keepLatest is no-op`() {
        val deleted = mutableListOf<String?>()
        val cleaner = RecordingRetentionCleaner(deleteItem = { deleted += it.sessionId; true })
        val items = (1..10).map { item(sessionId = "s%02d".format(it)) }
        val result = cleaner.clean(enabled = true, keepLatest = 10, items = items)
        assertEquals(RecordingRetentionCleaner.Result.NoOp, result)
        assertTrue(deleted.isEmpty())
    }

    @Test
    fun `legacy null-session entries are ignored entirely`() {
        // A library where every entry is legacy means nothing to
        // clean up — the cleaner cannot route the discard path
        // without a sessionId, so it skips them by design.
        val deleted = mutableListOf<File>()
        val cleaner = RecordingRetentionCleaner(deleteItem = { deleted += it.file; true })
        val items = (1..15).map { item(sessionId = null, name = "legacy_$it") }
        val result = cleaner.clean(enabled = true, keepLatest = 5, items = items)
        assertEquals(RecordingRetentionCleaner.Result.NoOp, result)
        assertTrue(deleted.isEmpty())
    }

    @Test
    fun `mixed legacy and finalized - keep window counted on finalized only`() {
        // Library: 4 finalized + 6 legacy. keepLatest = 2 → 2 finalized
        // surplus. Legacy entries are skipped regardless of position.
        val deletedSessions = mutableListOf<String?>()
        val cleaner = RecordingRetentionCleaner(deleteItem = { deletedSessions += it.sessionId; true })
        val items = listOf(
            item(sessionId = "f1"),
            item(sessionId = null, name = "legacy_a"),
            item(sessionId = "f2"),
            item(sessionId = null, name = "legacy_b"),
            item(sessionId = "f3"),
            item(sessionId = "f4"),
            item(sessionId = null, name = "legacy_c"),
        )
        val result = cleaner.clean(enabled = true, keepLatest = 2, items = items)
        assertEquals(2, result.deleted)
        assertEquals(0, result.failed)
        // Newest two finalized are f1, f2 (input order preserved
        // among finalized). Surplus = f3, f4.
        assertEquals(listOf("f3", "f4"), deletedSessions)
    }

    @Test
    fun `delete failure - counted but does not abort the batch`() {
        // Simulate the first surplus delete failing. The remaining
        // surplus entries must still be attempted so cleanup is
        // best-effort, matching the manual delete path's per-item
        // failure handling.
        val attempted = mutableListOf<String?>()
        val cleaner = RecordingRetentionCleaner(deleteItem = { item ->
            attempted += item.sessionId
            item.sessionId != "s12"
        })
        val items = (1..13).map { item(sessionId = "s%02d".format(it)) }
        val result = cleaner.clean(enabled = true, keepLatest = 10, items = items)
        // Surplus = s11, s12, s13 (oldest three). s12 fails;
        // s11 + s13 succeed.
        assertEquals(2, result.deleted)
        assertEquals(1, result.failed)
        assertEquals(listOf("s11", "s12", "s13"), attempted)
    }

    @Test
    fun `empty library is no-op`() {
        val cleaner = RecordingRetentionCleaner(deleteItem = { true })
        val result = cleaner.clean(enabled = true, keepLatest = 10, items = emptyList())
        assertEquals(RecordingRetentionCleaner.Result.NoOp, result)
    }
}

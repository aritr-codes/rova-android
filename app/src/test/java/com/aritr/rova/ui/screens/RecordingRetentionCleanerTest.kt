package com.aritr.rova.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * JVM-only tests for the retention SELECTION policy (ADR-0036 reshape:
 * the cleaner now only selects the surplus; deletion runs through the
 * shared HistoryDeleter batch transaction). Pins the contract the
 * History refresh path relies on:
 *
 *   * disabled / non-positive keepLatest → empty selection
 *   * keepLatest >= eligible.size → empty selection
 *   * newest-first input → the tail beyond the keep window is selected
 *   * legacy file-only entries (`sessionId == null`) are never selected
 */
class RecordingRetentionCleanerTest {

    private fun item(sessionId: String?, name: String = sessionId ?: "legacy") = VideoItem(
        file = File("/tmp/rova/$name.mp4"),
        thumbnail = null,
        resolution = "FHD",
        shareUri = null,
        sessionId = sessionId,
    )

    @Test
    fun `disabled - selects nothing regardless of count`() {
        val items = (1..20).map { item(sessionId = "s$it") }
        val surplus = RecordingRetentionCleaner.surplus(enabled = false, keepLatest = 5, items = items)
        assertTrue("no selection when disabled", surplus.isEmpty())
    }

    @Test
    fun `keep zero - treated as disabled to avoid wiping the library`() {
        val items = (1..3).map { item(sessionId = "s$it") }
        assertTrue(RecordingRetentionCleaner.surplus(enabled = true, keepLatest = 0, items = items).isEmpty())
    }

    @Test
    fun `keep negative - treated as disabled`() {
        val items = (1..3).map { item(sessionId = "s$it") }
        assertTrue(RecordingRetentionCleaner.surplus(enabled = true, keepLatest = -7, items = items).isEmpty())
    }

    @Test
    fun `enabled keep ten with twelve items - selects two oldest`() {
        // Items are passed in newest-first per the History refresh
        // contract; the selector trims the tail.
        val items = (1..12).map { item(sessionId = "s%02d".format(it)) }
        val surplus = RecordingRetentionCleaner.surplus(enabled = true, keepLatest = 10, items = items)
        assertEquals(listOf("s11", "s12"), surplus.map { it.sessionId })
    }

    @Test
    fun `keep count respects newest-first ordering - first ten survive`() {
        val items = (1..15).map { item(sessionId = "s%02d".format(it)) }
        val surplus = RecordingRetentionCleaner.surplus(enabled = true, keepLatest = 10, items = items)
        val selected = surplus.map { it.sessionId }
        items.take(10).forEach { kept ->
            assertTrue("newest survivor ${kept.sessionId} must not be selected", kept.sessionId !in selected)
        }
        assertEquals(5, surplus.size)
    }

    @Test
    fun `library at exactly keepLatest selects nothing`() {
        val items = (1..10).map { item(sessionId = "s%02d".format(it)) }
        assertTrue(RecordingRetentionCleaner.surplus(enabled = true, keepLatest = 10, items = items).isEmpty())
    }

    @Test
    fun `legacy null-session entries are never selected`() {
        val items = (1..15).map { item(sessionId = null, name = "legacy_$it") }
        assertTrue(RecordingRetentionCleaner.surplus(enabled = true, keepLatest = 5, items = items).isEmpty())
    }

    @Test
    fun `mixed legacy and finalized - keep window counted on finalized only`() {
        // Library: 4 finalized + 3 legacy. keepLatest = 2 → surplus is
        // f3, f4. Legacy entries are skipped regardless of position.
        val items = listOf(
            item(sessionId = "f1"),
            item(sessionId = null, name = "legacy_a"),
            item(sessionId = "f2"),
            item(sessionId = null, name = "legacy_b"),
            item(sessionId = "f3"),
            item(sessionId = "f4"),
            item(sessionId = null, name = "legacy_c"),
        )
        val surplus = RecordingRetentionCleaner.surplus(enabled = true, keepLatest = 2, items = items)
        assertEquals(listOf("f3", "f4"), surplus.map { it.sessionId })
    }

    @Test
    fun `empty library selects nothing`() {
        assertTrue(RecordingRetentionCleaner.surplus(enabled = true, keepLatest = 10, items = emptyList()).isEmpty())
    }
}

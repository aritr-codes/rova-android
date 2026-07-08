package com.aritr.rova.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Frozen test matrix T1–T7 (spec `2026-07-08-dualshot-merge-validity-predicate`)
 * for the pure [MergeSegmentFilter]. The real validity predicate is a framework
 * seam (MediaExtractor); every logic test here injects a deterministic fake, so
 * these tests exercise the FILTER contract — not the real decode. The real
 * predicate stays device-verified.
 *
 * Contract under test: each side is filtered INDEPENDENTLY through one injected
 * predicate; no cross-side reconciliation/truncation/padding; symmetry, when it
 * happens, is an OUTCOME; divergence is reported, never enforced away.
 */
class MergeSegmentFilterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun f(name: String) = File(name)

    /** Predicate that rejects the named files, accepts all others. */
    private fun rejecting(vararg invalid: String): (File) -> Boolean {
        val bad = invalid.toSet()
        return { it.name !in bad }
    }

    // T1 — asymmetric fail at the SAME index → each side drops its own; equal
    // counts result as an OUTCOME (not asserted-as-assumption), no divergence.
    @Test
    fun `T1 both sides drop same index - symmetry is an outcome, no divergence`() {
        val p = listOf(f("p1"), f("p2"), f("p3"))
        val l = listOf(f("l1"), f("l2"), f("l3"))
        val pred = rejecting("p1", "l1")

        val pr = MergeSegmentFilter.partition(p, pred)
        val lr = MergeSegmentFilter.partition(l, pred)

        assertEquals(listOf("p2", "p3"), pr.kept.map { it.name })
        assertEquals(listOf("l2", "l3"), lr.kept.map { it.name })
        assertEquals(pr.kept.size, lr.kept.size)
        assertNull(MergeSegmentFilter.divergenceMessage("s", pr.kept.size, lr.kept.size))
    }

    // T2 — only one side fails → counts legitimately differ → divergence logged,
    // BOTH sides still yield their valid sets.
    @Test
    fun `T2 one side only drops - counts differ, divergence reported, both publish`() {
        val p = listOf(f("p1"), f("p2"), f("p3"))
        val l = listOf(f("l1"), f("l2"), f("l3"))
        val pred = rejecting("l2")

        val pr = MergeSegmentFilter.partition(p, pred)
        val lr = MergeSegmentFilter.partition(l, pred)

        assertEquals(3, pr.kept.size)
        assertEquals(listOf("l1", "l3"), lr.kept.map { it.name })
        assertNotNull(MergeSegmentFilter.divergenceMessage("s", pr.kept.size, lr.kept.size))
    }

    // T3 — all of one side invalid → that side filters EMPTY (service then skips
    // its export → no publication); the other side is untouched.
    @Test
    fun `T3 all invalid one side - side empties, other intact, divergence reported`() {
        val p = listOf(f("p1"), f("p2"), f("p3"))
        val l = listOf(f("l1"), f("l2"), f("l3"))
        val pred = rejecting("l1", "l2", "l3")

        val pr = MergeSegmentFilter.partition(p, pred)
        val lr = MergeSegmentFilter.partition(l, pred)

        assertEquals(3, pr.kept.size)
        assertTrue(lr.kept.isEmpty())
        assertEquals(3, lr.dropped.size)
        assertNotNull(MergeSegmentFilter.divergenceMessage("s", pr.kept.size, lr.kept.size))
    }

    // T4 — both sides fully invalid → both empty (service → overall failure, no
    // publication); equal-and-zero → no divergence line.
    @Test
    fun `T4 all invalid both sides - both empty, no divergence`() {
        val p = listOf(f("p1"), f("p2"))
        val l = listOf(f("l1"), f("l2"))
        val pred = rejecting("p1", "p2", "l1", "l2")

        val pr = MergeSegmentFilter.partition(p, pred)
        val lr = MergeSegmentFilter.partition(l, pred)

        assertTrue(pr.kept.isEmpty())
        assertTrue(lr.kept.isEmpty())
        assertNull(MergeSegmentFilter.divergenceMessage("s", pr.kept.size, lr.kept.size))
    }

    // T5 — healthy path: predicate accepts all → identical to input, zero drops,
    // no divergence (regression guard for the single-mode/recovery blast radius).
    @Test
    fun `T5 healthy path unchanged - all kept, no drops, no divergence`() {
        val p = listOf(f("p1"), f("p2"), f("p3"))
        val l = listOf(f("l1"), f("l2"), f("l3"))
        val pred: (File) -> Boolean = { true }

        val pr = MergeSegmentFilter.partition(p, pred)
        val lr = MergeSegmentFilter.partition(l, pred)

        assertEquals(p, pr.kept)
        assertEquals(l, lr.kept)
        assertTrue(pr.dropped.isEmpty())
        assertTrue(lr.dropped.isEmpty())
        assertNull(MergeSegmentFilter.divergenceMessage("s", pr.kept.size, lr.kept.size))
    }

    // T6 — single definition: partition mirrors the injected predicate EXACTLY.
    // Kept == filter(pred), dropped == filterNot(pred). Proves the helper holds
    // no independent/duplicate validity logic — the predicate is the sole gate.
    @Test
    fun `T6 partition mirrors the predicate exactly - no duplicate validity logic`() {
        val segs = (1..10).map { f("seg$it") }
        val pred = rejecting("seg2", "seg5", "seg9")

        val r = MergeSegmentFilter.partition(segs, pred)

        assertEquals(segs.filter(pred), r.kept)
        assertEquals(segs.filterNot(pred), r.dropped.map { it.file })
    }

    // T7 — logging substrate: coarse drop reasons from filesystem facts, and the
    // divergence message content.
    @Test
    fun `T7 drop reasons classify missing empty and invalid-media`() {
        val missing = File(tmp.root, "gone.mp4") // never created
        val empty = tmp.newFile("empty.mp4")     // 0 bytes
        val nonEmpty = tmp.newFile("stub.mp4").apply { writeBytes(ByteArray(850)) }

        assertEquals(MergeSegmentFilter.DropReason.MISSING, MergeSegmentFilter.reasonFor(missing))
        assertEquals(MergeSegmentFilter.DropReason.EMPTY, MergeSegmentFilter.reasonFor(empty))
        // A non-empty file the predicate rejected = the audio-only/frozen stub.
        assertEquals(MergeSegmentFilter.DropReason.INVALID_MEDIA, MergeSegmentFilter.reasonFor(nonEmpty))
    }

    @Test
    fun `T7 divergence message names both counts, null when equal`() {
        assertNull(MergeSegmentFilter.divergenceMessage("s", 2, 2))
        val msg = MergeSegmentFilter.divergenceMessage("sess-1", 3, 2)
        assertNotNull(msg)
        assertTrue(msg!!.contains("portrait=3"))
        assertTrue(msg.contains("landscape=2"))
        assertTrue(msg.contains("sess-1"))
    }
}

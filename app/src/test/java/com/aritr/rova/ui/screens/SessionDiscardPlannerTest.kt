package com.aritr.rova.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-0036 — decision-procedure contract for the deletion transaction's
 * commit step. The planner permits `discardSession(sid)` iff
 *   (a) every batch outcome of `sid` succeeded, AND
 *   (b) the batch covers every currently-listed stableKey of `sid`.
 * Any uncertainty resolves toward retention (I3 fail-toward-visibility).
 *
 * Pure: primitives in, sessionIds out. No filesystem, no Android types,
 * no SessionStore, no coroutines.
 */
class SessionDiscardPlannerTest {

    private fun o(key: String, sid: String?, ok: Boolean) =
        SessionDiscardPlanner.Outcome(stableKey = key, sessionId = sid, deleted = ok)

    // ---- Defect A (DualShot orphan) core cases ----

    @Test
    fun `dualshot both sides succeed and batch covers session - discard`() {
        val eligible = SessionDiscardPlanner.plan(
            outcomes = listOf(o("/p.mp4", "s1", true), o("/l.mp4", "s1", true)),
            listedKeysBySession = mapOf("s1" to setOf("/p.mp4", "/l.mp4")),
        )
        assertEquals(setOf("s1"), eligible)
    }

    @Test
    fun `dualshot one side fails - no discard (I1 no orphan)`() {
        val eligible = SessionDiscardPlanner.plan(
            outcomes = listOf(o("/p.mp4", "s1", true), o("/l.mp4", "s1", false)),
            listedKeysBySession = mapOf("s1" to setOf("/p.mp4", "/l.mp4")),
        )
        assertTrue(eligible.isEmpty())
    }

    @Test
    fun `lone-side batch that covers all listed items - discard`() {
        // A single surviving side (its sibling already gone out-of-band)
        // is the whole session now — deleting it discards the manifest.
        val eligible = SessionDiscardPlanner.plan(
            outcomes = listOf(o("/l.mp4", "s1", true)),
            listedKeysBySession = mapOf("s1" to setOf("/l.mp4")),
        )
        assertEquals(setOf("s1"), eligible)
    }

    @Test
    fun `batch misses a listed sibling - no discard (I2 no collateral)`() {
        // Defect B shape: 1 of 3 kept segments in the batch. Discarding
        // would deleteRecursively the sibling segment files.
        val eligible = SessionDiscardPlanner.plan(
            outcomes = listOf(o("/seg0.mp4", "s1", true)),
            listedKeysBySession = mapOf(
                "s1" to setOf("/seg0.mp4", "/seg1.mp4", "/seg2.mp4"),
            ),
        )
        assertTrue(eligible.isEmpty())
    }

    @Test
    fun `all listed segments in batch and all succeed - discard`() {
        val eligible = SessionDiscardPlanner.plan(
            outcomes = listOf(
                o("/seg0.mp4", "s1", true),
                o("/seg1.mp4", "s1", true),
                o("/seg2.mp4", "s1", true),
            ),
            listedKeysBySession = mapOf(
                "s1" to setOf("/seg0.mp4", "/seg1.mp4", "/seg2.mp4"),
            ),
        )
        assertEquals(setOf("s1"), eligible)
    }

    // ---- Boundary + composition ----

    @Test
    fun `legacy null sessionId outcomes are never eligible`() {
        val eligible = SessionDiscardPlanner.plan(
            outcomes = listOf(o("/legacy.mp4", null, true)),
            listedKeysBySession = emptyMap(),
        )
        assertTrue(eligible.isEmpty())
    }

    @Test
    fun `mixed multi-session batch - verdicts are independent`() {
        val eligible = SessionDiscardPlanner.plan(
            outcomes = listOf(
                o("/a-p.mp4", "sA", true), o("/a-l.mp4", "sA", true),   // full success
                o("/b-p.mp4", "sB", true), o("/b-l.mp4", "sB", false),  // partial fail
                o("/c-seg0.mp4", "sC", true),                            // subset of listed
                o("/legacy.mp4", null, true),
            ),
            listedKeysBySession = mapOf(
                "sA" to setOf("/a-p.mp4", "/a-l.mp4"),
                "sB" to setOf("/b-p.mp4", "/b-l.mp4"),
                "sC" to setOf("/c-seg0.mp4", "/c-seg1.mp4"),
                "sD" to setOf("/d.mp4"),  // listed but not in batch: never eligible
            ),
        )
        assertEquals(setOf("sA"), eligible)
    }

    @Test
    fun `session absent from snapshot - eligible iff all batch outcomes succeeded`() {
        // By construction batch items come from the same listing snapshot,
        // so an absent session means the listing (exists-filter) no longer
        // knows any artifact of it — nothing visible survives to orphan.
        val eligible = SessionDiscardPlanner.plan(
            outcomes = listOf(o("/x.mp4", "s1", true), o("/y.mp4", "s2", false)),
            listedKeysBySession = emptyMap(),
        )
        assertEquals(setOf("s1"), eligible)
    }

    @Test
    fun `empty batch - empty result`() {
        assertTrue(
            SessionDiscardPlanner.plan(emptyList(), emptyMap()).isEmpty()
        )
    }

    @Test
    fun `duplicate outcomes for the same key are tolerated`() {
        val eligible = SessionDiscardPlanner.plan(
            outcomes = listOf(
                o("/p.mp4", "s1", true), o("/p.mp4", "s1", true),
                o("/l.mp4", "s1", true),
            ),
            listedKeysBySession = mapOf("s1" to setOf("/p.mp4", "/l.mp4")),
        )
        assertEquals(setOf("s1"), eligible)
    }
}

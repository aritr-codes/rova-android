package com.aritr.rova.ui.screens

/**
 * ADR-0036 — the decision procedure for the deletion transaction's commit
 * step. Given the per-artifact outcomes of a delete batch and the current
 * session membership of the Library listing, decides which sessionIds are
 * permitted to have their manifest + session directory discarded.
 *
 * A `sessionId` is eligible iff:
 *  1. every batch outcome belonging to it succeeded (I1 — no orphan:
 *     a surviving public artifact must stay reachable, and the manifest
 *     is its only path to visibility), AND
 *  2. the batch covers every currently-listed stableKey of the session
 *     (I2 — no collateral: for MULTI_SEGMENT_KEPT sessions the sibling
 *     segment files live INSIDE the session directory, so a premature
 *     discard destroys recordings the user chose to keep — found in the
 *     2026-07-06 branch analysis; the DualShot variant was flagged by
 *     the codex PR-B last-pass review, 2026-07-03).
 *
 * A session absent from [plan]'s `listedKeysBySession` has no listed
 * artifacts left (batch items are resolved from the same listing
 * snapshot), so rule 2 is vacuously satisfied. Any other snapshot
 * staleness fails toward NOT discarding — a ghost manifest, the
 * acceptable residue under I3 (fail toward visibility).
 *
 * Pure by owner mandate: primitives in, sessionIds out. No filesystem,
 * no Android dependency, no SessionStore, no coroutines. The planner is
 * NOT part of the transaction (ADR-0036 §Transaction structure) — it
 * only decides whether the commit is permitted.
 */
internal object SessionDiscardPlanner {

    /** One artifact-delete outcome from the batch. */
    data class Outcome(
        val stableKey: String,
        val sessionId: String?,
        val deleted: Boolean,
    )

    fun plan(
        outcomes: List<Outcome>,
        listedKeysBySession: Map<String, Set<String>>,
    ): Set<String> {
        val bySession = outcomes
            .filter { it.sessionId != null }
            .groupBy { it.sessionId!! }
        return bySession.filterTo(mutableMapOf()) { (sid, sessionOutcomes) ->
            val allSucceeded = sessionOutcomes.all { it.deleted }
            val batchKeys = sessionOutcomes.mapTo(mutableSetOf()) { it.stableKey }
            allSucceeded && batchKeys.containsAll(listedKeysBySession[sid].orEmpty())
        }.keys
    }
}

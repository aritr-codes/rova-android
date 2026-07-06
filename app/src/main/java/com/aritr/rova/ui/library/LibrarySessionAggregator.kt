package com.aritr.rova.ui.library

import com.aritr.rova.data.CaptureTopology
import com.aritr.rova.service.dualrecord.VideoSide

/**
 * Collapses per-side DualShot rows into one session row (spec §3.4, ADR-0030 amendment §3).
 *
 * Pure post-map step: PR-B calls this on the mapped row list in HistoryViewModel's combine.
 * duration/clipCount use MAX across sides (the sides record CONCURRENTLY — summing would
 * double the session; same invariant as SessionDurations.forRow's side filter). Size sums
 * (two real files). The collapsed row keys on the canonical sessionKey; the original
 * per-side keys survive inside [LibraryRow.sides] for playback/share/delete resolution.
 */
object LibrarySessionAggregator {

    fun aggregate(rows: List<LibraryRow>): List<LibraryRow> {
        // Same-side duplicates (same file surfaced twice) are dropped per side BEFORE the
        // size test, so a same-side "pair" is a kept single, not a collapse.
        val participatingGroups = rows.filter { it.participates() }
            .groupBy { it.sessionKey!! }
            .mapValues { (_, g) -> g.distinctBy { it.side } }
        val bySession = participatingGroups.filterValues { it.size >= 2 }
        val keptSingles = participatingGroups.filterValues { it.size < 2 }
            .values.flatten().mapTo(HashSet()) { it.stableKey }

        if (participatingGroups.isEmpty()) return rows

        val emitted = HashSet<String>()          // collapsed sessions already output
        val emittedSingles = HashSet<String>()   // kept single-side keys already output
        val result = ArrayList<LibraryRow>(rows.size)
        for (r in rows) {
            when {
                !r.participates() -> result.add(r)
                r.sessionKey!! in bySession ->
                    if (emitted.add(r.sessionKey!!)) result.add(collapse(bySession.getValue(r.sessionKey!!)))
                    // else: later member of an already-collapsed group → skip
                r.stableKey in keptSingles ->
                    // Self-sufficient duplicate drop (codex): a kept single emits at most once
                    // even if the same key appears twice in the input.
                    if (emittedSingles.add(r.stableKey)) result.add(r)
                // else: same-side duplicate of a kept single → skip
            }
        }
        return result
    }

    private fun LibraryRow.participates(): Boolean =
        topology == CaptureTopology.DualShot && sessionKey != null && side != null

    private fun collapse(group: List<LibraryRow>): LibraryRow {
        val ordered = group.sortedBy { if (it.side == VideoSide.PORTRAIT) 0 else 1 }
        // Base = latest-dated member: dateMillis, dateLabel, title, badge all come from ONE
        // row so sort/group and search/display can't drift apart (codex). Sides order stays
        // PORTRAIT-first regardless of which side is the base.
        val base = group.maxByOrNull { it.dateMillis }!!
        return base.copy(
            stableKey = base.sessionKey!!,
            sizeBytes = group.sumOf { it.sizeBytes },
            durationMs = group.maxOf { it.durationMs },
            clipCount = group.maxOf { it.clipCount },
            favorite = group.any { it.favorite },
            orientation = null,
            side = null,
            sides = ordered.map { LibrarySessionSide(it.side!!, it.stableKey, it.durationMs, it.clipCount, it.resumePositionMs) },
            // Resume pill reads the side the row tap plays: PORTRAIT-first non-null (spec §3.3/§3.4).
            resumePositionMs = ordered.firstNotNullOfOrNull { it.resumePositionMs },
        )
    }
}

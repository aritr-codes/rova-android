package com.aritr.rova.data

import com.aritr.rova.ui.warnings.TerminalEcho

/**
 * Phase 4 Slice 2 — pure reader. Walks every session manifest under the
 * SessionStore root, returns the most-recently-started terminal session as
 * a [TerminalEcho] (or null if there are none).
 *
 * Sync, blocking (matches `SessionStore.loadManifest` + `listSessionIds`
 * shapes). Cost = one manifest JSON parse per session. Acceptable on
 * cold start; future optimization can cache.
 *
 * Spec: docs/superpowers/specs/2026-05-24-phase-4-slice2-storage-full-autostopped-design.md §4.5
 */
internal fun SessionStore.latestTerminalSession(): TerminalEcho? {
    val ids = listSessionIds()
    var best: Pair<Long, TerminalEcho>? = null
    for (id in ids) {
        val m = loadManifest(id) ?: continue
        if (m.terminated == null) continue
        val candidate = m.startedAt to TerminalEcho(m.sessionId, m.stopReason)
        if (best == null || candidate.first > best!!.first) best = candidate
    }
    return best?.second
}

package com.aritr.rova.ui.recovery

import com.aritr.rova.data.SessionManifest
import com.aritr.rova.service.recovery.RecoveryReport
import com.aritr.rova.service.recovery.SessionClassification

/**
 * Phase 2 Slice 2.1a — pure adapter that converts the Phase 1.5
 * [RecoveryReport] into the Slice 2.0.5
 * [com.aritr.rova.ui.recovery.RecoveryUiState].
 *
 * The adapter takes a `loadManifest` lambda rather than a concrete
 * [com.aritr.rova.data.SessionStore] reference so the mapping logic
 * stays pure and JVM-testable. The 2.1b wiring layer passes
 * `app.sessionStore::loadManifest`; tests pass an in-memory map.
 *
 * Sessions whose `loadManifest` returns `null` (missing manifest /
 * parse failure) are dropped silently — `null` here means "no
 * authoritative terminator value", which is the same hide branch the
 * 2.0.5 mapper already uses for `terminated == null` and is safe to
 * collapse here. The 1.7 export-recovery runner already logs and
 * reports load failures separately; the recovery UI need not
 * double-surface them.
 */
object RecoveryViewSource {

    /**
     * Build a [RecoveryUiState] from the latest scan report and a
     * manifest lookup. `null` report (scan has not run yet, or scan
     * was deferred) yields [RecoveryUiState.Empty].
     *
     * Iteration order follows `report.classifications` map iteration
     * order, which is `LinkedHashMap` insertion order in
     * [com.aritr.rova.service.recovery.RecoveryScanner.classifyAll].
     */
    fun buildUiState(
        report: RecoveryReport?,
        loadManifest: (String) -> SessionManifest?
    ): RecoveryUiState {
        if (report == null) return RecoveryUiState.Empty
        val views = buildViews(report.classifications.values, loadManifest)
        return RecoveryUiStateMapper.map(views)
    }

    /**
     * Lower-level helper exposed for callers that already have a
     * filtered classification subset (e.g. tests or future filtered
     * UI surfaces). Returns the [RecoverySessionView] list ready to
     * feed [RecoveryUiStateMapper.map].
     */
    fun buildViews(
        classifications: Collection<SessionClassification>,
        loadManifest: (String) -> SessionManifest?
    ): List<RecoverySessionView> =
        classifications.mapNotNull { classification ->
            val manifest = loadManifest(classification.sessionId) ?: return@mapNotNull null
            RecoverySessionView(manifest = manifest, classification = classification)
        }
}

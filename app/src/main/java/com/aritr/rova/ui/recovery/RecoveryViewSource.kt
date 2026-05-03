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
 * stays pure and JVM-testable. The wiring layer passes
 * `app.sessionStore::loadManifest`; tests pass an in-memory map.
 *
 * Sessions whose `loadManifest` returns `null` (missing manifest /
 * parse failure) are dropped silently — `null` here means "no
 * authoritative terminator value", which is the same hide branch the
 * 2.0.5 mapper already uses for `terminated == null` and is safe to
 * collapse here.
 *
 * The `dismissedIds` parameter is the in-memory set the
 * [RecoveryViewModel] maintains so a discarded card disappears
 * immediately without waiting for the next disk scan. The set is
 * applied BEFORE manifest lookup so a discarded session id never
 * pays for the load.
 */
object RecoveryViewSource {

    /**
     * Build a [RecoveryUiState] from the latest scan report and a
     * manifest lookup. `null` report (scan has not run yet, or scan
     * was deferred) yields [RecoveryUiState.Empty].
     *
     * The mapper enforces "at most one card" and computes
     * `hiddenCount`; this adapter does not need to clamp.
     */
    fun buildUiState(
        report: RecoveryReport?,
        dismissedIds: Set<String> = emptySet(),
        loadManifest: (String) -> SessionManifest?
    ): RecoveryUiState {
        if (report == null) return RecoveryUiState.Empty
        val classifications = report.classifications.values
            .filter { it.sessionId !in dismissedIds }
        val views = buildViews(classifications, loadManifest)
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

package com.aritr.rova.ui.recovery

import com.aritr.rova.data.SessionManifest
import com.aritr.rova.data.Terminated
import com.aritr.rova.service.recovery.Anomaly
import com.aritr.rova.service.recovery.DiscardEligibility
import com.aritr.rova.service.recovery.SessionClassification

/**
 * Phase 2 Slice 2.0.5 — pure data view of the Phase 1.5 recovery report,
 * shaped for the recovery UI specified in ROADMAP_v3 §"C19 — Recovery UI
 * distinguishes terminator states" and carried unchanged through
 * v4/v5/v6.
 *
 * Internal beta correction (smoke 2026-05-03): the History screen used
 * to render every eligible card stacked in a vertical column, with
 * placeholder Merge / Discard affordances. Beta accumulated red walls
 * of cards. The mapper now emits at most one card — the newest by
 * `manifest.terminatedAt` (fallback `startedAt`) — and exposes a
 * `hiddenCount` so the UI can render a small footer for the rest. The
 * Merge label is removed entirely; the only real action wired for beta
 * is Discard, which is grounded in
 * [com.aritr.rova.data.SessionStore.discardSession].
 *
 * This module is callback-free, Compose-free, and has zero `RovaApp`
 * or `Context` dependencies.
 */

/**
 * Mapper input pair for one session.
 */
data class RecoverySessionView(
    val manifest: SessionManifest,
    val classification: SessionClassification
)

/**
 * Card kind. Maps 1:1 to the renderable [Terminated] values; [Terminated.COMPLETED]
 * has no card and is never represented here.
 */
enum class RecoveryCardKind {
    USER_STOPPED,
    KILLED_BY_SYSTEM,
    KILLED_FORCE_STOP
}

/**
 * Per-session recovery card. Strings are presentation copy; the wiring
 * layer reads them as-is. Beta exposes only the Discard action — the
 * Merge label is removed because no service-side merge API exists yet.
 *
 * `showVendorHelpSlot` is true only for [RecoveryCardKind.KILLED_BY_SYSTEM];
 * the wiring layer renders the vendor-guidance link in that slot.
 */
data class RecoveryCardState(
    val sessionId: String,
    val kind: RecoveryCardKind,
    val title: String,
    val body: String,
    val discardLabel: String,
    val showVendorHelpSlot: Boolean,
    val survivingArtifacts: List<String>
)

/**
 * Aggregate UI state.
 *
 * `cards` is bounded to at most one entry — the newest interrupted
 * session. Older eligible interrupted sessions are counted in
 * [hiddenCount] so the UI can surface a single footer line ("+N older
 * interrupted sessions") instead of stacking unbounded red cards.
 * After the user discards the visible card, the next-newest takes
 * its place on the next state emission.
 */
data class RecoveryUiState(
    val cards: List<RecoveryCardState>,
    val hiddenCount: Int = 0
) {
    companion object {
        val Empty = RecoveryUiState(cards = emptyList(), hiddenCount = 0)
    }
}

/**
 * Pure mapper.
 *
 * Hide rules (no card emitted):
 *  - `manifest.terminated == null` — Phase 1.5 has not classified yet
 *    (BLOCKED). Wait for next scan.
 *  - `manifest.terminated == COMPLETED` — finalize already wrote the
 *    terminal; nothing to recover.
 *  - `eligibility == BLOCKED` — live-owned / age-filtered / pending
 *    export. Wait for next scan.
 *  - `eligibility == AUTO_DISCARD_ELIGIBLE` — Phase 1.7 cleanup
 *    territory; not surfaced as user recovery in 2.0.5 scope.
 *
 * Render rule:
 *  - `eligibility == OFFER_DISCARD` AND
 *    `manifest.terminated ∈ {USER_STOPPED, KILLED_BY_SYSTEM, KILLED_FORCE_STOP}`.
 *
 * Among renderable views the mapper sorts newest-first by
 * `manifest.terminatedAt` (fallback `manifest.startedAt`) and emits
 * the single freshest card; the rest are counted in
 * [RecoveryUiState.hiddenCount].
 */
object RecoveryUiStateMapper {

    fun map(views: List<RecoverySessionView>): RecoveryUiState {
        val eligible = views.filter { isEligible(it) }
        if (eligible.isEmpty()) return RecoveryUiState.Empty
        val sorted = eligible.sortedByDescending { recencyKey(it) }
        val visible = sorted.firstOrNull()?.let { toCard(it) }
            ?: return RecoveryUiState.Empty
        return RecoveryUiState(
            cards = listOf(visible),
            hiddenCount = sorted.size - 1
        )
    }

    private fun isEligible(view: RecoverySessionView): Boolean {
        val terminated = view.manifest.terminated ?: return false
        if (terminated == Terminated.COMPLETED) return false
        if (view.classification.eligibility != DiscardEligibility.OFFER_DISCARD) return false
        return true
    }

    /**
     * Recency anchor for the newest-first sort.
     * `terminatedAt` is the canonical signal — written by `markTerminated`
     * the moment the session ended. `startedAt` is the documented
     * fallback for the rare cross-process race where a manifest exists
     * with a non-null `terminated` but no `terminatedAt` (legacy schema
     * pre-Phase 1.4). Stable enough for ordering; ties resolve via
     * `sortedByDescending` (TimSort) stability.
     */
    private fun recencyKey(view: RecoverySessionView): Long =
        view.manifest.terminatedAt ?: view.manifest.startedAt

    private fun toCard(view: RecoverySessionView): RecoveryCardState? {
        val terminated = view.manifest.terminated ?: return null
        if (terminated == Terminated.COMPLETED) return null
        if (view.classification.eligibility != DiscardEligibility.OFFER_DISCARD) return null

        val kind = when (terminated) {
            Terminated.USER_STOPPED -> RecoveryCardKind.USER_STOPPED
            Terminated.KILLED_BY_SYSTEM -> RecoveryCardKind.KILLED_BY_SYSTEM
            Terminated.KILLED_FORCE_STOP -> RecoveryCardKind.KILLED_FORCE_STOP
            Terminated.COMPLETED -> return null
        }

        return RecoveryCardState(
            sessionId = view.manifest.sessionId,
            kind = kind,
            title = titleFor(kind),
            body = bodyFor(kind),
            discardLabel = DISCARD_LABEL,
            showVendorHelpSlot = (kind == RecoveryCardKind.KILLED_BY_SYSTEM),
            survivingArtifacts = summarize(view.classification)
        )
    }

    private fun titleFor(kind: RecoveryCardKind): String = when (kind) {
        RecoveryCardKind.USER_STOPPED -> "Session stopped"
        RecoveryCardKind.KILLED_BY_SYSTEM -> "Recording stopped by your device"
        RecoveryCardKind.KILLED_FORCE_STOP -> "Recording was force-stopped"
    }

    private fun bodyFor(kind: RecoveryCardKind): String = when (kind) {
        RecoveryCardKind.USER_STOPPED ->
            "Your last session ended before the segments could merge. " +
                "The recovered segments stay on your device until you choose Discard recording. " +
                "This action is permanent."
        RecoveryCardKind.KILLED_BY_SYSTEM ->
            "Your device's battery management stopped the recording before the segments could merge. " +
                "The recovered segments stay on your device until you choose Discard recording. " +
                "This action is permanent."
        RecoveryCardKind.KILLED_FORCE_STOP ->
            "The app was force-stopped before the last session's segments could merge. " +
                "The recovered segments stay on your device until you choose Discard recording. " +
                "This action is permanent."
    }

    private fun summarize(c: SessionClassification): List<String> {
        val out = mutableListOf<String>()
        if (c.appendedSegmentFilenames.isNotEmpty()) {
            out += "Recovered ${c.appendedSegmentFilenames.size} segment(s) from disk"
        }
        c.anomalies.forEach { a ->
            out += when (a) {
                is Anomaly.MissingSegment ->
                    "Missing segment(s) at indices ${a.missingIndices}"
                is Anomaly.InvalidManifestSegment ->
                    "Invalid segment(s) at indices ${a.indices}"
                is Anomaly.OrphanSegment ->
                    "Orphan segment(s) at indices ${a.indices}"
                is Anomaly.InvalidOrphan ->
                    "${a.filenames.size} unreadable orphan file(s)"
                is Anomaly.DuplicateSegment ->
                    "Duplicate segment(s) at indices ${a.indices}"
                is Anomaly.UnknownArtifact ->
                    "${a.filenames.size} unknown file(s) in session dir"
                is Anomaly.MalformedManifestRecord ->
                    "${a.filenames.size} malformed manifest record(s)"
            }
        }
        return out
    }

    // Slice 13C — explicit destructive label. The bare "Discard"
    // verb left users uncertain about scope; "Discard recording"
    // names what the action removes, matching the body copy that
    // also calls out the action by full name.
    private const val DISCARD_LABEL = "Discard recording"
}

package com.aritr.rova.ui.recovery

import com.aritr.rova.data.SessionManifest
import com.aritr.rova.data.Terminated
import com.aritr.rova.service.recovery.Anomaly
import com.aritr.rova.service.recovery.DiscardEligibility
import com.aritr.rova.service.recovery.SessionClassification

/**
 * Phase 2 Slice 2.0.5 — pure data view of the Phase 1.5 recovery report,
 * shaped for the four-state recovery UI specified in ROADMAP_v3
 * §"C19 — Recovery UI distinguishes terminator states" and carried
 * unchanged through v4/v5/v6.
 *
 * This module is callback-free, Compose-free, and has zero `RovaApp`
 * or `Context` dependencies. The 2.1 wiring layer adds the Compose
 * surface and the Merge/Discard handlers. The 2.2 slice fills the
 * `showVendorHelpSlot` rendering with the vendor-intent helper.
 *
 * Why the input is `(SessionManifest, SessionClassification)` and not
 * `RecoveryReport` alone: the scanner-side
 * [com.aritr.rova.service.recovery.TerminalAction] collapses
 * [Terminated.KILLED_BY_SYSTEM] (RovaTickReceiver-owned) into
 * `ALREADY_TERMINAL`, so it cannot drive the four-state branching.
 * The persisted manifest's [Terminated] value is the only authoritative
 * source.
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
 * Per-session recovery card. Strings are presentation copy; the 2.1
 * wiring layer reads them as-is.
 *
 * `showVendorHelpSlot` is true only for [RecoveryCardKind.KILLED_BY_SYSTEM];
 * the wiring layer renders the vendor-guidance link in that slot once
 * Slice 2.2 lands. The flag is exposed here (rather than derived in the
 * UI) so the mapping stays the single source of truth for which cards
 * carry vendor guidance.
 */
data class RecoveryCardState(
    val sessionId: String,
    val kind: RecoveryCardKind,
    val title: String,
    val body: String,
    val mergeLabel: String,
    val discardLabel: String,
    val showVendorHelpSlot: Boolean,
    val survivingArtifacts: List<String>
)

/**
 * Aggregate UI state. Card order matches input order so the wiring
 * layer can pin Compose keys by `sessionId` without reordering.
 */
data class RecoveryUiState(
    val cards: List<RecoveryCardState>
) {
    companion object {
        val Empty = RecoveryUiState(cards = emptyList())
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
 * Render rule (card emitted):
 *  - `eligibility == OFFER_DISCARD` AND
 *    `manifest.terminated ∈ {USER_STOPPED, KILLED_BY_SYSTEM, KILLED_FORCE_STOP}`.
 */
object RecoveryUiStateMapper {

    fun map(views: List<RecoverySessionView>): RecoveryUiState {
        val cards = views.mapNotNull { toCard(it) }
        return RecoveryUiState(cards = cards)
    }

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
            mergeLabel = MERGE_LABEL,
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
            "Your last session ended before merging. " +
                "Choose to merge what was recorded or discard."
        RecoveryCardKind.KILLED_BY_SYSTEM ->
            "Your device's battery management stopped the recording before it could merge. " +
                "Choose to merge what was recorded or discard."
        RecoveryCardKind.KILLED_FORCE_STOP ->
            "The app was force-stopped before the last session could merge. " +
                "Choose to merge what was recorded or discard."
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

    private const val MERGE_LABEL = "Merge what was recorded"
    private const val DISCARD_LABEL = "Discard"
}

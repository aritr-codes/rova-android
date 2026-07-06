package com.aritr.rova.ui.screens

/**
 * Batch orchestrator for the Library delete pipeline (ADR-0036).
 * Pluggable seams so the transaction can be JVM-unit-tested without an
 * `AndroidViewModel`, a real `ContentResolver`, or a real
 * `SessionStore`.
 *
 * Transaction (ADR-0036 §Transaction structure):
 *   1. Delete every artifact in the batch via [deleteArtifact],
 *      recording per-item success. A failure never aborts the pass —
 *      cleanup stays best-effort.
 *   2. Commit manifest deletion — or retain it: [discardSession] runs
 *      once per session that [SessionDiscardPlanner] marks eligible
 *      (every batch outcome of the session succeeded AND the batch
 *      covers all of the session's listed artifacts).
 *
 * Never call [discardSession] per item: the artifact→session relation
 * is N:1 (DualShot sides; MULTI_SEGMENT_KEPT segments whose files live
 * INSIDE the session directory). A per-item discard orphaned the
 * surviving DualShot side on partial failure (codex PR-B last-pass,
 * 2026-07-03) and destroyed sibling kept segments outright (2026-07-06
 * branch analysis).
 *
 * If [discardSession] throws, the exception is logged via
 * [onDiscardError] and swallowed — every artifact of that session is
 * already gone, so the visible operation succeeded; the residue is a
 * ghost manifest, the acceptable failure under ADR-0036 I3. It never
 * marks the batch failed.
 *
 * The helper is `internal` so tests in the same package can access it
 * without exposing it to consumer modules.
 */
internal class HistoryDeleter(
    private val deleteArtifact: (VideoItem) -> Boolean,
    private val discardSession: (String) -> Unit,
    private val onDiscardError: (sessionId: String, t: Throwable) -> Unit = { _, _ -> }
) {
    /**
     * Runs the two-step deletion transaction over [batch].
     * [listedItems] is the current Library listing snapshot the batch
     * was resolved from; it supplies the session-membership input to
     * the eligibility decision. Returns the [VideoItem.stableKey]s
     * whose ARTIFACT delete failed (discard outcomes never affect it).
     */
    fun deleteAll(
        batch: Collection<VideoItem>,
        listedItems: Collection<VideoItem>,
    ): Set<String> {
        val outcomes = batch.map { item ->
            SessionDiscardPlanner.Outcome(
                stableKey = item.stableKey,
                sessionId = item.sessionId,
                deleted = deleteArtifact(item),
            )
        }
        val listedKeysBySession = listedItems
            .filter { it.sessionId != null }
            .groupBy({ it.sessionId!! }, { it.stableKey })
            .mapValues { (_, keys) -> keys.toSet() }
        val eligible = SessionDiscardPlanner.plan(outcomes, listedKeysBySession)
        for (sid in eligible) {
            try {
                discardSession(sid)
            } catch (t: Throwable) {
                onDiscardError(sid, t)
            }
        }
        return outcomes.filterNot { it.deleted }.mapTo(mutableSetOf()) { it.stableKey }
    }

    /**
     * TEMPORARY bridge for the pre-ADR-0036 call sites; removed in the
     * same branch once HistoryViewModel routes through [deleteAll].
     * Treats the single item as covering its whole session — the OLD
     * (defective) semantics, kept only so intermediate commits compile.
     */
    @Deprecated("ADR-0036: route batches through deleteAll")
    fun delete(item: VideoItem): Boolean =
        deleteAll(listOf(item), listedItems = listOf(item)).isEmpty()
}

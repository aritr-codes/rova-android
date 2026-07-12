package com.aritr.rova.ui.recovery

import androidx.annotation.StringRes
import com.aritr.rova.R
import com.aritr.rova.data.ExportState
import com.aritr.rova.data.SessionManifest
import com.aritr.rova.data.StopCategory
import com.aritr.rova.data.StopCategoryClassifier
import com.aritr.rova.data.StopReason
import com.aritr.rova.data.Terminated
import com.aritr.rova.service.recovery.Anomaly
import com.aritr.rova.service.recovery.DiscardEligibility
import com.aritr.rova.service.recovery.SessionClassification
import com.aritr.rova.ui.text.UiText

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
 * The mapper's only source of "now" (M3, APPX-G).
 *
 * A pure seam, not `java.time.Clock`: the app is `minSdk 24` with no core-library desugaring,
 * so `java.time` is unavailable in `app/src/main`. Nothing here reads a system clock — the
 * production instance does, once, at its edge.
 *
 * Deliberately NOT a `Flow`, a ticker, or anything observable. APPX-G freezes recency as a
 * **static label** ("Recency is never a live region … it would make TalkBack chatty"), so the
 * only way a card's label can change is a re-map.
 */
fun interface RecoveryClock {
    fun nowMillis(): Long

    companion object {
        /** The wall clock. The single impure instance; every other caller injects a fake. */
        val System: RecoveryClock = RecoveryClock { java.lang.System.currentTimeMillis() }
    }
}

/**
 * Mapper input pair for one session.
 */
data class RecoverySessionView(
    val manifest: SessionManifest,
    val classification: SessionClassification
)

/**
 * Card kind. Maps to the renderable [Terminated] × [StopReason] combinations;
 * [Terminated.COMPLETED] has no card and is never represented here.
 */
enum class RecoveryCardKind {
    USER_STOPPED,
    SAFETY_STOPPED,
    SCHEDULED_END,
    ERROR_STOPPED,
    KILLED_BY_SYSTEM,
    KILLED_FORCE_STOP,
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
    /** B3 i18n task 8 — fixed copy id; resolved at the composable via stringResource. */
    @StringRes val titleRes: Int,
    /** B3 i18n task 8 — fixed copy id; resolved at the composable via stringResource. */
    @StringRes val bodyRes: Int,
    /** B3 i18n task 8 — fixed copy id; resolved at the composable via stringResource. */
    @StringRes val discardLabelRes: Int,
    val showVendorHelpSlot: Boolean,
    /** B3 i18n task 8 — args/count tokens; resolved at the composable. */
    val survivingArtifacts: List<UiText>,
    /** Phase 4.3 — null when there's nothing to merge (no surviving artifacts). */
    @StringRes val mergeLabelRes: Int? = null,
    /** Phase 4.3 — null when there's nothing to keep. Always co-set with [mergeLabelRes]. */
    @StringRes val keepRawLabelRes: Int? = null,
    /** Phase 4.3 — null=idle; 0..1=merge in progress (strip fills, buttons disabled). */
    val mergeInProgress: Float? = null,
    /**
     * Phase 4.3 — non-null=last merge failed; body+button flip to retry copy.
     * B3 i18n task 8 — stays a raw runtime diagnostic (exception message), not
     * localizable copy. The "Merge failed: " wrapper is externalized at the
     * composable; the reason text itself is passed through verbatim.
     */
    val mergeFailedReason: String? = null,
    /**
     * M9 (Q6, frozen §08) — the owner-locked, localized merge-failure copy id, classified
     * from the TYPED [com.aritr.rova.service.export.ExportResult] chain (never the raw
     * [mergeFailedReason] message). The failbox renders this `@StringRes`; [mergeFailedReason]
     * is retained only for logs/diagnostics. Co-set with [mergeFailedReason] on a merge failure
     * and cleared with it (see [com.aritr.rova.ui.recovery.MergeFailureReason]).
     */
    @StringRes val mergeFailedReasonRes: Int? = null,
    /**
     * M3 (APPX-G) — how long ago this session ended, minted ONCE at map time from the mapper's
     * [RecoveryClock] and immutable thereafter. The primary UI shows only this relative label
     * ("Interrupted · 2 hours ago"); the absolute instant it carries is confined to the
     * contentDescription, the "why" expander, and diagnostics.
     *
     * Anchored on the same `terminatedAt ?: startedAt` key the newest-first sort already used —
     * APPX-G's "already computed to sort, then discarded". No schema change.
     */
    val recency: RelativeTimeLabel,
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
 *  - `manifest.exportState == FINALIZED` — the post-stop hotfix smoke
 *    on 2026-05-08 surfaced sessions where the user stopped during
 *    the inter-clip wait, the service still flushed pending segments,
 *    and the export pipeline ran to completion. The terminator is
 *    `USER_STOPPED` (orthogonal to `ExportState` per
 *    `SessionManifest.kt` §"Terminated") and the disk-side session dir
 *    still has manifest segments, which the recovery scanner sees as
 *    `OFFER_DISCARD`. The finalized recording is already in the
 *    gallery, so a "Discard recording" card is misleading: it does
 *    NOT remove the gallery copy, only the private session residue.
 *    Skip the card entirely once `exportState == FINALIZED`.
 *  - `eligibility == BLOCKED` — live-owned / age-filtered / pending
 *    export. Wait for next scan.
 *  - `eligibility == AUTO_DISCARD_ELIGIBLE` — Phase 1.7 cleanup
 *    territory; not surfaced as user recovery in 2.0.5 scope.
 *
 * Render rule:
 *  - `eligibility == OFFER_DISCARD` AND
 *    `manifest.terminated ∈ {USER_STOPPED, KILLED_BY_SYSTEM, KILLED_FORCE_STOP}`
 *    AND `manifest.exportState != FINALIZED`.
 *
 * Among renderable views the mapper sorts newest-first by
 * `manifest.terminatedAt` (fallback `manifest.startedAt`) and emits
 * the single freshest card; the rest are counted in
 * [RecoveryUiState.hiddenCount].
 */
object RecoveryUiStateMapper {

    /**
     * @param clock read exactly once, and only when there is a card to label, so every card in
     *   one emission agrees about "now". M3 — see [RecoveryClock].
     */
    fun map(views: List<RecoverySessionView>, clock: RecoveryClock): RecoveryUiState {
        val eligible = views.filter { isEligible(it) }
        if (eligible.isEmpty()) return RecoveryUiState.Empty
        val sorted = eligible.sortedByDescending { recencyKey(it) }
        val nowMillis = clock.nowMillis()
        val visible = sorted.firstOrNull()?.let { toCard(it, nowMillis) }
            ?: return RecoveryUiState.Empty
        return RecoveryUiState(
            cards = listOf(visible),
            hiddenCount = sorted.size - 1
        )
    }

    private fun isEligible(view: RecoverySessionView): Boolean {
        val terminated = view.manifest.terminated ?: return false
        if (terminated == Terminated.COMPLETED) return false
        if (terminated == Terminated.MULTI_SEGMENT_KEPT) return false   // Phase 4.3 — kept raw, no recovery card
        if (view.classification.eligibility != DiscardEligibility.OFFER_DISCARD) return false
        // Hotfix 2026-05-08 — finalized exports never surface as
        // recovery cards. See KDoc above.
        if (view.manifest.exportState == ExportState.FINALIZED) return false
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

    private fun toCard(view: RecoverySessionView, nowMillis: Long): RecoveryCardState? {
        val terminated = view.manifest.terminated ?: return null
        if (terminated == Terminated.COMPLETED) return null
        if (view.classification.eligibility != DiscardEligibility.OFFER_DISCARD) return null
        if (view.manifest.exportState == ExportState.FINALIZED) return null

        val stopReason = view.manifest.stopReason
        val kind = when (StopCategoryClassifier.categorize(terminated, stopReason)) {
            StopCategory.SAFETY_STOPPED -> RecoveryCardKind.SAFETY_STOPPED
            StopCategory.SCHEDULED_END -> RecoveryCardKind.SCHEDULED_END
            StopCategory.ERROR_STOPPED -> RecoveryCardKind.ERROR_STOPPED
            StopCategory.USER_STOPPED -> RecoveryCardKind.USER_STOPPED
            StopCategory.INTERRUPTED ->
                if (terminated == Terminated.KILLED_BY_SYSTEM) RecoveryCardKind.KILLED_BY_SYSTEM
                else RecoveryCardKind.KILLED_FORCE_STOP
            StopCategory.RECOVERED, StopCategory.COMPLETED -> return null // isEligible already filters
        }

        return RecoveryCardState(
            sessionId = view.manifest.sessionId,
            kind = kind,
            titleRes = titleResFor(kind, stopReason),
            bodyRes = bodyResFor(kind, stopReason),
            discardLabelRes = R.string.recovery_discard_label,
            showVendorHelpSlot = (kind == RecoveryCardKind.KILLED_BY_SYSTEM),
            survivingArtifacts = summarize(view.classification),
            mergeLabelRes = if (view.classification.appendedSegmentFilenames.isNotEmpty()) R.string.recovery_merge_label else null,
            keepRawLabelRes = if (view.classification.appendedSegmentFilenames.isNotEmpty()) R.string.recovery_keep_raw_label else null,
            recency = RelativeTimeLabels.label(atMillis = recencyKey(view), nowMillis = nowMillis),
        )
    }

    @StringRes
    private fun titleResFor(kind: RecoveryCardKind, stopReason: StopReason): Int = when (kind) {
        RecoveryCardKind.USER_STOPPED -> R.string.recovery_title_user_stopped
        RecoveryCardKind.KILLED_BY_SYSTEM -> R.string.recovery_title_killed_by_system
        RecoveryCardKind.KILLED_FORCE_STOP -> R.string.recovery_title_force_stopped
        RecoveryCardKind.SAFETY_STOPPED ->
            if (stopReason == StopReason.LOW_STORAGE) R.string.recovery_title_safety_storage
            else R.string.recovery_title_safety_thermal
        RecoveryCardKind.SCHEDULED_END -> R.string.recovery_title_scheduled
        RecoveryCardKind.ERROR_STOPPED -> R.string.recovery_title_error
    }

    @StringRes
    private fun bodyResFor(kind: RecoveryCardKind, stopReason: StopReason): Int = when (kind) {
        RecoveryCardKind.USER_STOPPED -> R.string.recovery_body_user_stopped
        RecoveryCardKind.KILLED_BY_SYSTEM -> R.string.recovery_body_killed_by_system
        RecoveryCardKind.KILLED_FORCE_STOP -> R.string.recovery_body_force_stopped
        RecoveryCardKind.SAFETY_STOPPED ->
            if (stopReason == StopReason.LOW_STORAGE) R.string.recovery_body_safety_storage
            else R.string.recovery_body_safety_thermal
        RecoveryCardKind.SCHEDULED_END -> R.string.recovery_body_scheduled
        RecoveryCardKind.ERROR_STOPPED -> R.string.recovery_body_error
    }

    private fun summarize(c: SessionClassification): List<UiText> {
        val out = mutableListOf<UiText>()
        if (c.appendedSegmentFilenames.isNotEmpty()) {
            out += UiText.StrArgs(
                R.string.recovery_artifact_recovered_segments,
                listOf(c.appendedSegmentFilenames.size),
            )
        }
        c.anomalies.forEach { a ->
            out += when (a) {
                is Anomaly.MissingSegment ->
                    UiText.StrArgs(R.string.recovery_artifact_missing_segments, listOf(a.missingIndices.toString()))
                is Anomaly.InvalidManifestSegment ->
                    UiText.StrArgs(R.string.recovery_artifact_invalid_segments, listOf(a.indices.toString()))
                is Anomaly.OrphanSegment ->
                    UiText.StrArgs(R.string.recovery_artifact_orphan_segments, listOf(a.indices.toString()))
                is Anomaly.InvalidOrphan ->
                    UiText.StrArgs(R.string.recovery_artifact_invalid_orphans, listOf(a.filenames.size))
                is Anomaly.DuplicateSegment ->
                    UiText.StrArgs(R.string.recovery_artifact_duplicate_segments, listOf(a.indices.toString()))
                is Anomaly.UnknownArtifact ->
                    UiText.StrArgs(R.string.recovery_artifact_unknown_files, listOf(a.filenames.size))
                is Anomaly.MalformedManifestRecord ->
                    UiText.StrArgs(R.string.recovery_artifact_malformed_records, listOf(a.filenames.size))
            }
        }
        return out
    }
}

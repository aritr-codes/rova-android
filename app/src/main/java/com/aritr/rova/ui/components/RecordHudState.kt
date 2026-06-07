package com.aritr.rova.ui.components

/**
 * Slice 3 — sealed renderable state for the active Record HUD.
 *
 * Mutual exclusion is enforced *at the data layer* rather than via a
 * Compose UI test: the call site collects service flags and asks
 * [RecordHudState.from] which case to render. The sealed hierarchy
 * makes "render two bodies at once" structurally impossible — there
 * is no instance that represents that combination.
 *
 * Phase 2.4 (NEW_UI_BACKEND_REPLAN.md §5 row 2.4) extended this
 * hierarchy with [Merging] so the post-stop merge phase renders as
 * an in-HUD body instead of the legacy `AlertDialog` overlay. The
 * sealed contract still keeps Recording / Waiting / Merging
 * mutually exclusive at compile time.
 *
 * Pure, JVM-testable.
 */
sealed class RecordHudState {

    /** Idle — render the Slice 2 idle dock. */
    data object Idle : RecordHudState()

    /** Periodic session active and a clip is currently recording. */
    data object Recording : RecordHudState()

    /**
     * Periodic session active but the first clip has not started
     * recording yet (startup grace). Distinct from [Waiting] (real
     * inter-clip interval). Discriminated by `segmentCount == 0` —
     * no segment has ever been finalized — so a first-segment retry
     * also shows "Preparing…" rather than the inter-clip "On break".
     * (Bug B — the ~2.6 s pre-record grace wrongly read as Waiting.)
     */
    data object Starting : RecordHudState()

    /** Periodic session active and waiting between clips. */
    data object Waiting : RecordHudState()

    /**
     * Phase 2.4 — post-stop merge in flight.
     *
     * `progress` is the muxer/export progress signal in [0, 1].
     * `currentSegment` is the inferred 1-based index of the segment
     * being merged, derived from `progress * totalSegments`. The
     * derivation is centralized here (instead of in the composable)
     * so the rounding rule is testable.
     *
     * `totalSegments` mirrors `RovaServiceState.segmentCount` at the
     * time the merge began. The service does not flip
     * `isPeriodicActive` and `isMerging` simultaneously, so the
     * count is stable for the duration of the merge — see
     * `RovaRecordingService.performMerge`.
     */
    data class Merging(
        val progress: Float,
        val currentSegment: Int,
        val totalSegments: Int
    ) : RecordHudState()

    companion object {
        /**
         * Resolve the renderable HUD state from the service flags.
         *
         * Resolution priority — `isMerging` wins over the periodic
         * flags so the brief window where the service flips
         * `isPeriodicActive` off before `isMerging` falls cannot
         * flash an "Idle" frame between the last clip and the merge
         * card.
         *
         * Default values for the merge inputs preserve the Slice 3
         * 2-arg signature for any caller that has not yet been
         * migrated to the merging branch.
         */
        fun from(
            isPeriodicActive: Boolean,
            isRecording: Boolean,
            isMerging: Boolean = false,
            mergeProgress: Float = 0f,
            segmentCount: Int = 0,
            // Bug A — the real saved-clip count to surface in the merge
            // band. Defaults to 0 so [segmentCount] remains the fallback
            // total for any caller not yet wired through performMerge.
            mergeClipCount: Int = 0
        ): RecordHudState {
            if (isMerging) {
                // Bug A — on an early user-stop the loop never completed an
                // iteration so segmentCount is still 0; performMerge publishes
                // the real count via mergeClipCount so the band isn't "X of 0".
                val total = (if (mergeClipCount > 0) mergeClipCount else segmentCount)
                    .coerceAtLeast(0)
                val safeProgress = mergeProgress.coerceIn(0f, 1f)
                val current = if (total <= 0) {
                    0
                } else {
                    (safeProgress * total).toInt().coerceIn(0, total)
                }
                return Merging(
                    progress = safeProgress,
                    currentSegment = current,
                    totalSegments = total
                )
            }
            return when {
                !isPeriodicActive -> Idle
                isRecording -> Recording
                // Bug B — before the first clip is ever finalized (startup
                // grace OR a first-segment retry), show Starting, not Waiting.
                segmentCount == 0 -> Starting
                else -> Waiting
            }
        }
    }
}

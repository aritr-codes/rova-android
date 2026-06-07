package com.aritr.rova.ui.components

/**
 * Bug A — resolve the real saved-clip count for the post-merge summary
 * card (`MergeCompleteCard`).
 *
 * `exportedClipCount` is success-owned: the service sets it in
 * `performMerge` / `performMergeDual` on the `ExportResult.Success`
 * path, so it reflects the segments actually finalized + exported —
 * including the single partial clip captured before the first segment
 * boundary on an early user-stop. `segmentCount` is the loop-exhaust
 * fallback for any window where the success count has not been
 * published yet.
 *
 * Fixes "0 clips saved to library" on an early user-stop: the loop
 * never completed an iteration (so `segmentCount` is 0), but the
 * partial clip IS saved, so `exportedClipCount` is 1.
 *
 * Pure, JVM-testable.
 */
object MergeCompleteCount {
    fun resolve(exportedClipCount: Int, segmentCount: Int): Int =
        exportedClipCount.takeIf { it > 0 } ?: segmentCount.coerceAtLeast(0)
}

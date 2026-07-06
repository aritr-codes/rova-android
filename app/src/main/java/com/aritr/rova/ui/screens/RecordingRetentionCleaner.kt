package com.aritr.rova.ui.screens

/**
 * Pure SELECTION policy for the "keep latest N finalized recordings"
 * retention rule wired in Settings (ADR-0036 reshape). This class no
 * longer deletes anything: it names the surplus, and the caller routes
 * it through the same [HistoryDeleter.deleteAll] batch transaction
 * that powers the manual Library delete — so the retention path gets
 * the manifest-discard-last guarantee (I1/I2) instead of the per-item
 * discard that could orphan a KEPT DualShot side whose sibling fell
 * outside the keep window (2026-07-06 branch analysis).
 *
 * Inputs:
 *  * [enabled] — settings toggle. When `false` the selector returns
 *    empty without touching anything.
 *  * [keepLatest] — number of finalized recordings to keep. Values
 *    `<= 0` are treated as "off" too, so a misconfigured persistence
 *    read can never delete the user's entire library.
 *  * [items] — full History list, **already sorted newest-first**.
 *    Only entries with a non-null [VideoItem.sessionId] are eligible;
 *    legacy file-only entries are skipped (their discard path is not
 *    plumbed).
 *
 * The helper is `internal` so tests in the same package can access it
 * without exposing it to consumer modules.
 */
internal object RecordingRetentionCleaner {

    /** Batch outcome summary surfaced via RetentionCleanupNotice. */
    data class Result(val deleted: Int, val failed: Int) {
        companion object {
            val NoOp = Result(deleted = 0, failed = 0)
        }
    }

    /** Names the surplus tail beyond the keep window; deletes nothing. */
    fun surplus(
        enabled: Boolean,
        keepLatest: Int,
        items: List<VideoItem>,
    ): List<VideoItem> {
        if (!enabled) return emptyList()
        if (keepLatest <= 0) return emptyList()
        val finalized = items.filter { it.sessionId != null }
        if (finalized.size <= keepLatest) return emptyList()
        return finalized.drop(keepLatest)
    }
}

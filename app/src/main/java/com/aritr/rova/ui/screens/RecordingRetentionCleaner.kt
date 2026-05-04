package com.aritr.rova.ui.screens

/**
 * Pure cleanup policy for the "keep latest N finalized recordings"
 * retention rule wired in Settings. Pluggable so the
 * delete-and-discard sequence can be swapped for an in-memory fake
 * during JVM unit tests; production wires it to the same
 * [HistoryDeleter]-backed pipeline that powers the manual History
 * delete button so the gallery row + per-session manifest are
 * cleaned up via the same audited code path.
 *
 * Inputs:
 *  * [enabled] — settings toggle. When `false` the cleaner returns
 *    [Result.NoOp] without touching anything.
 *  * [keepLatest] — number of finalized recordings to keep. Values
 *    `<= 0` are treated as "off" too, so a misconfigured persistence
 *    read can never delete the user's entire library.
 *  * [items] — full History list, **already sorted newest-first**.
 *    Only entries with a non-null [VideoItem.sessionId] are eligible
 *    for cleanup; legacy file-only entries are skipped (their
 *    discard path is not yet plumbed in this slice).
 *
 * Order contract: cleanup walks the surplus tail oldest-last and
 * delegates each entry to [deleteItem]. A `false` return from
 * [deleteItem] increments [Result.failed] and DOES NOT abort the
 * batch — we want best-effort cleanup of the rest of the surplus,
 * matching the manual delete path's per-item failure handling.
 *
 * The helper is `internal` so tests in the same package can access
 * it without exposing it to consumer modules.
 */
internal class RecordingRetentionCleaner(
    private val deleteItem: (VideoItem) -> Boolean
) {
    data class Result(val deleted: Int, val failed: Int) {
        companion object {
            val NoOp = Result(deleted = 0, failed = 0)
        }
    }

    fun clean(
        enabled: Boolean,
        keepLatest: Int,
        items: List<VideoItem>
    ): Result {
        if (!enabled) return Result.NoOp
        if (keepLatest <= 0) return Result.NoOp
        // Only finalized manifest-backed entries count toward the
        // keep window. Legacy file-only entries (sessionId == null)
        // are passed through untouched in this slice.
        val finalized = items.filter { it.sessionId != null }
        if (finalized.size <= keepLatest) return Result.NoOp
        val surplus = finalized.drop(keepLatest)
        var deleted = 0
        var failed = 0
        surplus.forEach { item ->
            if (deleteItem(item)) deleted++ else failed++
        }
        return Result(deleted = deleted, failed = failed)
    }
}

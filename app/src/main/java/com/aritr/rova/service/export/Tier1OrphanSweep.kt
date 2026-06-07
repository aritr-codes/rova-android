package com.aritr.rova.service.export

import com.aritr.rova.utils.RovaLog

/**
 * Phase 1.7 commit-5 — Tier 1 startup orphan sweep (ADR 0003 §"Recovery
 * routing", Tier 1 column). Runs on cold launch BEFORE per-session
 * recovery (commit 4 [Tier1Exporter.recover]) hands off, removing
 * pending `MediaStore.Video.Media` rows that no manifest references.
 *
 * Why a sweep is needed:
 * - A Tier 1 export inserts the pending row in step 1, then writes
 *   `pendingUri` into the manifest in step 2 ([Tier1Exporter.export]).
 *   The two writes are not atomic — a process kill between them leaves
 *   a pending row no manifest knows about. Per-session recovery
 *   (commit 4) cannot find these because it walks manifests, not
 *   `MediaStore`. Without the sweep they accumulate as silent quota
 *   pressure on the user's device.
 * - The sweep is the ONLY recovery path that lists `MediaStore` rows.
 *   Per-session recovery uses direct URI ops on the manifest pointer
 *   (no listing, no query) per the commit-4 design.
 *
 * Safety invariants — the sweep MUST NOT delete:
 * - rows referenced by any manifest's `pendingUri` (the per-session
 *   recovery owns these);
 * - rows whose `OWNER_PACKAGE_NAME` doesn't match ours (defensive —
 *   pending rows are app-private at the platform level, but the
 *   filter exists so a runtime `MANAGE_EXTERNAL_STORAGE` expansion
 *   doesn't escalate the sweep into deleting another app's row);
 * - non-pending rows (the production listing seam queries with
 *   `MATCH_ONLY` / `setIncludePending` so they never appear).
 *
 * Idempotent: a second sweep deletes nothing because the orphans from
 * the first sweep are gone.
 *
 * Result-bearing failure: if the listing seam throws, the sweep
 * returns [OrphanSweepResult.QueryFailed] and never attempts deletes.
 * Per-row delete failures count into [OrphanSweepResult.Swept.deleteFailures]
 * without aborting the sweep — one transient failure must not block
 * subsequent orphans from being cleaned.
 *
 * Injectable seams (test hooks; production wiring lives in
 * [Tier1AndroidSweepOps] under `@RequiresApi(Build.VERSION_CODES.Q)`):
 * - [listVisiblePendingRows] — returns every pending row visible to
 *   this app (`MATCH_ONLY` on API 30+, `setIncludePending` on API 29).
 *   May throw; throws become [OrphanSweepResult.QueryFailed].
 * - [deletePendingRow] — best-effort `ContentResolver.delete(uri)`.
 *   Returns `true` if a row was removed; throws are caught and
 *   counted into [OrphanSweepResult.Swept.deleteFailures].
 *
 * NOT in scope for commit 5:
 * - `RovaApp` integration / cold-launch wiring (commit 6).
 * - `performMerge` replacement (commit 7).
 * - Per-session recovery changes (commit 4 owned that).
 * - Phase 1.5 cleanup / session-dir deletion.
 * - Segment deletion.
 */
class Tier1OrphanSweep(
    private val ourPackageName: String,
    private val listVisiblePendingRows: suspend () -> List<PendingRow>,
    private val deletePendingRow: suspend (uri: String) -> Boolean
) {

    /**
     * One pending-row record from the production listing. [uri] is the
     * full content URI as a string ([android.content.ContentUris.withAppendedId]
     * applied to the row's `_ID`). [ownerPackageName] is the value of
     * `MediaStore.MediaColumns.OWNER_PACKAGE_NAME` for the row, or
     * `null` if the column was absent / unset (legacy data; the sweep
     * conservatively treats a `null` owner as "not ours" and retains
     * the row).
     */
    data class PendingRow(val uri: String, val ownerPackageName: String?)

    suspend fun sweepTier1OrphanPendingRows(
        referencedPendingUris: Set<String>
    ): OrphanSweepResult {
        val rows = try {
            listVisiblePendingRows()
        } catch (t: Throwable) {
            RovaLog.w("$TAG: listVisiblePendingRows threw; sweep aborted (defer to next cold launch)", t)
            return OrphanSweepResult.QueryFailed(t)
        }

        var deleted = 0
        var retainedReferenced = 0
        var retainedOtherPackage = 0
        var deleteFailures = 0

        for (row in rows) {
            // Owner filter: defensive against MANAGE_EXTERNAL_STORAGE
            // visibility expansion. Null owner is treated as "not ours"
            // — never escalate into deleting on legacy data.
            if (row.ownerPackageName != ourPackageName) {
                retainedOtherPackage++
                continue
            }
            if (row.uri in referencedPendingUris) {
                retainedReferenced++
                continue
            }
            val ok = try {
                deletePendingRow(row.uri)
            } catch (t: Throwable) {
                RovaLog.w("$TAG: deletePendingRow threw for ${row.uri}", t)
                deleteFailures++
                continue
            }
            if (ok) {
                deleted++
            } else {
                RovaLog.w("$TAG: deletePendingRow returned false for ${row.uri}")
                deleteFailures++
            }
        }

        RovaLog.d {
            "$TAG: sweep complete (deleted=$deleted, retainedReferenced=$retainedReferenced, " +
                "retainedOtherPackage=$retainedOtherPackage, deleteFailures=$deleteFailures)"
        }
        return OrphanSweepResult.Swept(
            deleted = deleted,
            retainedReferenced = retainedReferenced,
            retainedOtherPackage = retainedOtherPackage,
            deleteFailures = deleteFailures
        )
    }

    private companion object {
        const val TAG = "Tier1OrphanSweep"
    }
}

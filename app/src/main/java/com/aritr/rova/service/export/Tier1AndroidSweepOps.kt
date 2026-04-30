package com.aritr.rova.service.export

import android.content.ContentResolver
import android.content.ContentUris
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import com.aritr.rova.utils.RovaLog

/**
 * Phase 1.7 commit-5 — production wiring for [Tier1OrphanSweep]'s
 * listing/delete seams. Annotated `@RequiresApi(Build.VERSION_CODES.Q)`
 * because:
 * - `MediaStore.Video.Media.IS_PENDING` is API 29+,
 * - `MediaStore.MediaColumns.OWNER_PACKAGE_NAME` is API 29+,
 * - `MediaStore.VOLUME_EXTERNAL_PRIMARY` is API 29+.
 *
 * Pending-visibility split (mandatory per the platform):
 *
 * - **API 29 (Q)** — pending rows are visible only when the query URI
 *   was wrapped via `MediaStore.setIncludePending(uri)`. CRUCIALLY,
 *   `setIncludePending` means "include pending rows IN ADDITION to
 *   non-pending owned rows" — NOT "return only pending rows". An
 *   explicit SQL selection on `IS_PENDING = 1` is therefore mandatory;
 *   without it the cursor returns every owned row, including published
 *   videos, and the sweep would delete the user's library.
 * - **API 30+ (R+)** — `setIncludePending` was deprecated in API 30
 *   (no-op in many ROMs) and the supported visibility mechanism is
 *   the `Bundle` query-args overload `query(uri, projection, args,
 *   signal)` with `MediaStore.QUERY_ARG_MATCH_PENDING` set to
 *   `MediaStore.MATCH_ONLY`. `MATCH_ONLY` does mean "pending only" —
 *   but per the commit-5 NO-GO patch's defense-in-depth rule, the
 *   explicit SQL selection on `IS_PENDING = 1` is also applied here
 *   (OEM MediaStore implementations have shipped with non-conformant
 *   visibility-flag handling; never trust the flag alone).
 *
 * Layered defenses against deleting a non-pending row:
 *  1. SDK-branched visibility primitive (`setIncludePending` /
 *     `QUERY_ARG_MATCH_PENDING=MATCH_ONLY`).
 *  2. SQL selection
 *     `OWNER_PACKAGE_NAME = ? AND IS_PENDING = 1` with
 *     `selectionArgs = arrayOf(ourPackageName)` on both branches.
 *  3. Post-cursor pure-Kotlin filter [filterPendingOwned] that re-
 *     verifies `isPending == 1 && ownerPackageName == ourPackageName`
 *     even if the cursor produced a non-conforming row.
 *  4. The sweep itself ([Tier1OrphanSweep]) re-checks owner against
 *     the referenced set.
 *
 * The CI lint bundle for commit 5 enforces this in source:
 *  - `checkExportIsPendingGuarded` — `IS_PENDING` references must be
 *    inside a `@RequiresApi(Q)` file or behind an `SDK_INT >= Q` guard.
 *  - `checkExportSetIncludePendingGuarded` — `setIncludePending`
 *    references must appear within a `Build.VERSION_CODES.R` SDK
 *    branch (forces the `< R` arm).
 *  - `checkExportQueryArgMatchPendingGuarded` — `QUERY_ARG_MATCH_PENDING`
 *    references must likewise be near a `VERSION_CODES.R` branch
 *    (forces the `>= R` arm).
 *  - `checkExportPendingVisibilityOnQuery` — any `resolver.query(`
 *    in `service/export/` must reside in a file that contains BOTH
 *    visibility mechanisms AND an explicit `IS_PENDING = 1` SQL
 *    selection (post-NO-GO patch).
 *
 * Internal to `service/export` per the commit-3 spec ("Any shared
 * helper must stay internal/private to service/export"). Tests use
 * the [Tier1OrphanSweep] seams directly; this object's pure
 * [filterPendingOwned] helper is exercised by the test suite while
 * the platform-bound [listVisiblePendingRows] is verified
 * source-level.
 */
@RequiresApi(Build.VERSION_CODES.Q)
internal object Tier1AndroidSweepOps {

    private const val TAG = "Tier1AndroidSweepOps"

    /**
     * Defense-in-depth SQL selection. Owner-bound + pending-bound;
     * applied on BOTH the API 29 (`setIncludePending` URI) and API
     * 30+ (`QUERY_ARG_MATCH_PENDING=MATCH_ONLY` Bundle) paths so the
     * sweep cannot see published rows even when the visibility flag
     * misbehaves.
     */
    private val SELECTION_PENDING_OWNED =
        "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ? AND " +
            "${MediaStore.Video.Media.IS_PENDING} = 1"

    /**
     * Lists every pending `MediaStore.Video.Media` row owned by
     * [ourPackageName]. SDK-branched per the platform contract above.
     * Both branches apply the SQL selection [SELECTION_PENDING_OWNED]
     * AND the post-cursor [filterPendingOwned] pass — the result is
     * guaranteed to contain only rows where
     * `isPending == 1 && ownerPackageName == ourPackageName`. The
     * cursor is always closed via `use { }`.
     */
    fun listVisiblePendingRows(
        resolver: ContentResolver,
        ourPackageName: String
    ): List<Tier1OrphanSweep.PendingRow> {
        val baseUri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
            MediaStore.Video.Media.IS_PENDING
        )
        val selectionArgs = arrayOf(ourPackageName)
        val raw = mutableListOf<RawCursorRow>()

        val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // API 30+: Bundle args. QUERY_ARG_MATCH_PENDING=MATCH_ONLY
            // is the visibility primitive; the SQL selection is the
            // defense-in-depth filter (OEM MediaStore behavior on the
            // visibility flag varies).
            val bundle = Bundle().apply {
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_ONLY)
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, SELECTION_PENDING_OWNED)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
            }
            resolver.query(baseUri, projection, bundle, null)
        } else {
            // API 29: setIncludePending(uri) makes pending rows VISIBLE
            // — but does not exclude non-pending rows. Without the
            // explicit IS_PENDING = 1 selection below, the cursor would
            // include every owned video (published or pending), and
            // the sweep would delete the user's gallery.
            @Suppress("DEPRECATION")
            resolver.query(
                MediaStore.setIncludePending(baseUri),
                projection,
                SELECTION_PENDING_OWNED,
                selectionArgs,
                null
            )
        }

        cursor?.use { c ->
            val idCol = c.getColumnIndex(MediaStore.Video.Media._ID)
            val ownerCol = c.getColumnIndex(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
            val pendingCol = c.getColumnIndex(MediaStore.Video.Media.IS_PENDING)
            if (idCol < 0 || pendingCol < 0) {
                RovaLog.w("$TAG: cursor missing _ID or IS_PENDING column; treating as empty")
                return emptyList()
            }
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val owner = if (ownerCol >= 0) c.getString(ownerCol) else null
                val isPending = c.getInt(pendingCol)
                raw += RawCursorRow(id = id, ownerPackageName = owner, isPending = isPending)
            }
        }

        return filterPendingOwned(raw, ourPackageName, baseUri.toString())
    }

    /** Best-effort row delete. Returns `true` iff a row was removed. */
    fun deletePendingRow(resolver: ContentResolver, uriString: String): Boolean {
        val uri = android.net.Uri.parse(uriString)
        return resolver.delete(uri, null, null) > 0
    }

    /**
     * Pure (no-platform) post-cursor filter. Defense-in-depth pass
     * after the SQL selection: rejects any row whose `isPending != 1`
     * or whose `ownerPackageName != ourPackageName`. Extracted as
     * `internal` so the JVM unit-test suite can exercise the filter
     * with crafted [RawCursorRow] inputs without standing up an
     * Android platform shim.
     *
     * If the cursor returned a non-conforming row despite the SQL
     * selection (OEM MediaStore drift, race with another process, etc.),
     * this filter is the last line of defense before the sweep would
     * call `delete()` on the URI.
     */
    internal fun filterPendingOwned(
        rows: List<RawCursorRow>,
        ourPackageName: String,
        baseUriString: String
    ): List<Tier1OrphanSweep.PendingRow> = rows.mapNotNull { row ->
        if (row.isPending != 1) {
            RovaLog.w(
                "$TAG.filterPendingOwned: cursor returned a non-pending row " +
                    "(id=${row.id}, isPending=${row.isPending}); skipping"
            )
            return@mapNotNull null
        }
        if (row.ownerPackageName != ourPackageName) {
            RovaLog.w(
                "$TAG.filterPendingOwned: cursor returned a non-owned row " +
                    "(id=${row.id}, owner=${row.ownerPackageName}); skipping"
            )
            return@mapNotNull null
        }
        Tier1OrphanSweep.PendingRow(
            uri = "$baseUriString/${row.id}",
            ownerPackageName = row.ownerPackageName
        )
    }

    /**
     * Raw cursor extraction tuple. Pure data; carries the three
     * columns the filter needs. Internal so tests can construct
     * inputs to [filterPendingOwned] directly.
     */
    internal data class RawCursorRow(
        val id: Long,
        val ownerPackageName: String?,
        val isPending: Int
    )
}

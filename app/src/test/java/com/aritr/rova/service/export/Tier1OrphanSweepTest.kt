package com.aritr.rova.service.export

import com.aritr.rova.service.export.Tier1OrphanSweep.PendingRow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * Phase 1.7 commit-5 — Tier 1 orphan sweep regression suite.
 *
 * Algorithmic tests (filter, idempotency, query/delete failure
 * handling) drive the [Tier1OrphanSweep] class via injected seams.
 * Source-level tests verify the platform-wiring file
 * ([Tier1AndroidSweepOps]) implements both API 29 and API 30+
 * pending-visibility branches — the SDK_INT split cannot be exercised
 * at JVM-test runtime, so the contract is enforced via source-text
 * assertion plus the four CI lints
 * (`checkExportIsPendingGuarded`, `checkExportSetIncludePendingGuarded`,
 * `checkExportQueryArgMatchPendingGuarded`,
 * `checkExportPendingVisibilityOnQuery`).
 */
class Tier1OrphanSweepTest {

    private val ourPkg = "com.aritr.rova"

    private fun newSweep(
        rows: List<PendingRow> = emptyList(),
        listThrows: Throwable? = null,
        deletedRecorder: MutableList<String> = mutableListOf(),
        deleteResult: (String) -> Boolean = { true },
        deleteThrows: (String) -> Throwable? = { null }
    ): Tier1OrphanSweep = Tier1OrphanSweep(
        ourPackageName = ourPkg,
        listVisiblePendingRows = {
            listThrows?.let { throw it }
            rows
        },
        deletePendingRow = { uri ->
            deleteThrows(uri)?.let { throw it }
            deletedRecorder += uri
            deleteResult(uri)
        }
    )

    // ─── Algorithmic tests ──────────────────────────────────────────

    @Test
    fun `unreferenced app-owned pending row is deleted`() {
        val deleted = mutableListOf<String>()
        val sweep = newSweep(
            rows = listOf(PendingRow("content://media/external/video/media/1", ourPkg)),
            deletedRecorder = deleted
        )

        val result = runBlocking { sweep.sweepTier1OrphanPendingRows(emptySet()) }

        assertTrue("expected Swept, got $result", result is OrphanSweepResult.Swept)
        val s = result as OrphanSweepResult.Swept
        assertEquals(1, s.deleted)
        assertEquals(0, s.retainedReferenced)
        assertEquals(0, s.retainedOtherPackage)
        assertEquals(0, s.deleteFailures)
        assertEquals(listOf("content://media/external/video/media/1"), deleted)
    }

    @Test
    fun `referenced pending row is retained`() {
        val deleted = mutableListOf<String>()
        val rowUri = "content://media/external/video/media/2"
        val sweep = newSweep(
            rows = listOf(PendingRow(rowUri, ourPkg)),
            deletedRecorder = deleted
        )

        val result = runBlocking { sweep.sweepTier1OrphanPendingRows(setOf(rowUri)) }

        val s = result as OrphanSweepResult.Swept
        assertEquals(0, s.deleted)
        assertEquals(1, s.retainedReferenced)
        assertEquals(0, s.deleteFailures)
        assertTrue("referenced row must NEVER be deleted", deleted.isEmpty())
    }

    @Test
    fun `other-package pending row is retained even if not referenced`() {
        val deleted = mutableListOf<String>()
        val sweep = newSweep(
            rows = listOf(PendingRow("content://media/external/video/media/3", "com.evil.app")),
            deletedRecorder = deleted
        )

        val result = runBlocking { sweep.sweepTier1OrphanPendingRows(emptySet()) }

        val s = result as OrphanSweepResult.Swept
        assertEquals(0, s.deleted)
        assertEquals(1, s.retainedOtherPackage)
        assertTrue("other-package row must NEVER be deleted by our sweep", deleted.isEmpty())
    }

    @Test
    fun `null owner pending row is retained - conservative on legacy data`() {
        val deleted = mutableListOf<String>()
        val sweep = newSweep(
            rows = listOf(PendingRow("content://media/external/video/media/4", null)),
            deletedRecorder = deleted
        )

        val result = runBlocking { sweep.sweepTier1OrphanPendingRows(emptySet()) }

        val s = result as OrphanSweepResult.Swept
        assertEquals(0, s.deleted)
        assertEquals(1, s.retainedOtherPackage)
        assertTrue(deleted.isEmpty())
    }

    @Test
    fun `non-pending rows never appear in listing - sweep has nothing to delete`() {
        // The production listing seam queries with MATCH_ONLY (API 30+)
        // or via setIncludePending (API 29), both of which return ONLY
        // pending rows. Non-pending rows are never in the list, so the
        // sweep has no path to touch them. Test asserts the empty-list
        // case is benign.
        val deleted = mutableListOf<String>()
        val sweep = newSweep(rows = emptyList(), deletedRecorder = deleted)

        val result = runBlocking { sweep.sweepTier1OrphanPendingRows(emptySet()) }

        val s = result as OrphanSweepResult.Swept
        assertEquals(0, s.deleted)
        assertEquals(0, s.retainedReferenced)
        assertEquals(0, s.retainedOtherPackage)
        assertEquals(0, s.deleteFailures)
        assertTrue(deleted.isEmpty())
    }

    @Test
    fun `multiple rows - mixed buckets all classified correctly`() {
        val deleted = mutableListOf<String>()
        val ourReferenced = "content://media/external/video/media/10"
        val ourOrphan1 = "content://media/external/video/media/11"
        val ourOrphan2 = "content://media/external/video/media/12"
        val otherPkg = "content://media/external/video/media/13"
        val nullOwner = "content://media/external/video/media/14"
        val sweep = newSweep(
            rows = listOf(
                PendingRow(ourReferenced, ourPkg),
                PendingRow(ourOrphan1, ourPkg),
                PendingRow(otherPkg, "com.other.app"),
                PendingRow(ourOrphan2, ourPkg),
                PendingRow(nullOwner, null)
            ),
            deletedRecorder = deleted
        )

        val result = runBlocking {
            sweep.sweepTier1OrphanPendingRows(setOf(ourReferenced))
        }

        val s = result as OrphanSweepResult.Swept
        assertEquals(2, s.deleted)
        assertEquals(1, s.retainedReferenced)
        assertEquals(2, s.retainedOtherPackage) // other-pkg + null-owner
        assertEquals(0, s.deleteFailures)
        assertEquals(listOf(ourOrphan1, ourOrphan2), deleted)
        assertFalse("referenced never touched", deleted.contains(ourReferenced))
        assertFalse("other-pkg never touched", deleted.contains(otherPkg))
        assertFalse("null-owner never touched", deleted.contains(nullOwner))
    }

    // ─── Idempotency ────────────────────────────────────────────────

    @Test
    fun `idempotent - second sweep on cleaned state deletes nothing`() {
        // Simulate two cold launches: first sweep sees an orphan; second
        // sweep sees the empty post-clean state.
        val ourOrphan = "content://media/external/video/media/20"
        var listings = listOf(
            listOf(PendingRow(ourOrphan, ourPkg)),
            emptyList()
        )
        val deleted = mutableListOf<String>()
        var listingIdx = 0
        val sweep = Tier1OrphanSweep(
            ourPackageName = ourPkg,
            listVisiblePendingRows = {
                val r = listings[listingIdx]
                listingIdx++
                r
            },
            deletePendingRow = { uri -> deleted += uri; true }
        )

        val first = runBlocking { sweep.sweepTier1OrphanPendingRows(emptySet()) } as OrphanSweepResult.Swept
        assertEquals("first sweep deletes the orphan", 1, first.deleted)

        val second = runBlocking { sweep.sweepTier1OrphanPendingRows(emptySet()) } as OrphanSweepResult.Swept
        assertEquals("second sweep is a no-op", 0, second.deleted)
        assertEquals(0, second.retainedReferenced)
        assertEquals(0, second.retainedOtherPackage)
        assertEquals(0, second.deleteFailures)
        assertEquals("only one delete across both sweeps", 1, deleted.size)
    }

    // ─── Query failure ──────────────────────────────────────────────

    @Test
    fun `query failure returns QueryFailed without throwing the runner`() {
        val cause = IOException("ContentResolver.query died")
        val sweep = newSweep(listThrows = cause)

        val result = runBlocking { sweep.sweepTier1OrphanPendingRows(emptySet()) }

        assertTrue("expected QueryFailed, got $result", result is OrphanSweepResult.QueryFailed)
        assertEquals(cause, (result as OrphanSweepResult.QueryFailed).cause)
    }

    @Test
    fun `query failure does not invoke deletePendingRow`() {
        var deleteCalled = false
        val sweep = Tier1OrphanSweep(
            ourPackageName = ourPkg,
            listVisiblePendingRows = { throw IllegalStateException("listing died") },
            deletePendingRow = { _ -> deleteCalled = true; true }
        )

        runBlocking { sweep.sweepTier1OrphanPendingRows(emptySet()) }

        assertFalse("delete must not run if listing failed", deleteCalled)
    }

    // ─── Per-row delete failure ─────────────────────────────────────

    @Test
    fun `delete returning false counts as deleteFailure but sweep continues`() {
        val deleted = mutableListOf<String>()
        val ourOrphan1 = "content://media/external/video/media/30"
        val ourOrphan2 = "content://media/external/video/media/31"
        val sweep = Tier1OrphanSweep(
            ourPackageName = ourPkg,
            listVisiblePendingRows = {
                listOf(
                    PendingRow(ourOrphan1, ourPkg),
                    PendingRow(ourOrphan2, ourPkg)
                )
            },
            deletePendingRow = { uri ->
                deleted += uri
                uri == ourOrphan2 // first one fails, second succeeds
            }
        )

        val result = runBlocking { sweep.sweepTier1OrphanPendingRows(emptySet()) }

        val s = result as OrphanSweepResult.Swept
        assertEquals(1, s.deleted)
        assertEquals(1, s.deleteFailures)
        assertEquals("both orphans were attempted", listOf(ourOrphan1, ourOrphan2), deleted)
    }

    @Test
    fun `delete throwing counts as deleteFailure but sweep continues`() {
        val attempts = mutableListOf<String>()
        val ourOrphan1 = "content://media/external/video/media/40"
        val ourOrphan2 = "content://media/external/video/media/41"
        val sweep = Tier1OrphanSweep(
            ourPackageName = ourPkg,
            listVisiblePendingRows = {
                listOf(
                    PendingRow(ourOrphan1, ourPkg),
                    PendingRow(ourOrphan2, ourPkg)
                )
            },
            deletePendingRow = { uri ->
                attempts += uri
                if (uri == ourOrphan1) throw IOException("transient")
                true
            }
        )

        val result = runBlocking { sweep.sweepTier1OrphanPendingRows(emptySet()) }

        val s = result as OrphanSweepResult.Swept
        assertEquals(1, s.deleted)
        assertEquals(1, s.deleteFailures)
        assertEquals(listOf(ourOrphan1, ourOrphan2), attempts)
    }

    // ─── Source-level: SDK branches in the production wiring ────────

    @Test
    fun `Tier1AndroidSweepOps API 29 branch uses setIncludePending AND IS_PENDING SQL filter (NO-GO patch)`() {
        val src = readSweepOpsSource()
        assertTrue(
            "API 29 branch must use MediaStore.setIncludePending",
            src.contains("setIncludePending(")
        )
        assertTrue(
            "must SDK-branch on Build.VERSION_CODES.R",
            src.contains("Build.VERSION_CODES.R") && src.contains("Build.VERSION.SDK_INT")
        )
        // NO-GO patch: setIncludePending on its own includes non-pending
        // rows. An explicit IS_PENDING = 1 SQL selection is mandatory.
        assertTrue(
            "must include explicit IS_PENDING = 1 SQL selection — " +
                "setIncludePending alone returns every owned row, not pending-only",
            Regex("""IS_PENDING\}?\s*=\s*1\b""").containsMatchIn(src)
        )
        // Selection must be parameterised by the package name.
        assertTrue(
            "must include OWNER_PACKAGE_NAME selection bound to the app's package",
            src.contains("OWNER_PACKAGE_NAME") && src.contains("ourPackageName")
        )
    }

    @Test
    fun `Tier1AndroidSweepOps API 30+ branch uses QUERY_ARG_MATCH_PENDING with MATCH_ONLY AND defense-in-depth IS_PENDING filter`() {
        val src = readSweepOpsSource()
        assertTrue(
            "API 30+ branch must use QUERY_ARG_MATCH_PENDING",
            src.contains("QUERY_ARG_MATCH_PENDING")
        )
        assertTrue(
            "must use the MATCH_ONLY value (not MATCH_INCLUDE / MATCH_EXCLUDE)",
            src.contains("MATCH_ONLY")
        )
        // NO-GO patch defense in depth: QUERY_ARG_SQL_SELECTION wires
        // the SQL selection on the Bundle path too — never trust OEM
        // visibility-flag conformance.
        assertTrue(
            "API 30+ Bundle path must also wire QUERY_ARG_SQL_SELECTION",
            src.contains("QUERY_ARG_SQL_SELECTION")
        )
        assertTrue(
            "API 30+ Bundle path must also wire QUERY_ARG_SQL_SELECTION_ARGS",
            src.contains("QUERY_ARG_SQL_SELECTION_ARGS")
        )
    }

    // ─── Defense-in-depth: filterPendingOwned (NO-GO patch) ─────────

    @Test
    fun `filterPendingOwned drops non-pending rows even if cursor returned them`() {
        val rows = listOf(
            // Pending + owned → kept.
            Tier1AndroidSweepOps.RawCursorRow(id = 1, ownerPackageName = ourPkg, isPending = 1),
            // Non-pending + owned → MUST be filtered (the user's published video).
            Tier1AndroidSweepOps.RawCursorRow(id = 2, ownerPackageName = ourPkg, isPending = 0),
            // Pending + other-package → filtered.
            Tier1AndroidSweepOps.RawCursorRow(id = 3, ownerPackageName = "com.other.app", isPending = 1),
            // Pending + null owner → filtered.
            Tier1AndroidSweepOps.RawCursorRow(id = 4, ownerPackageName = null, isPending = 1),
            // Another pending + owned → kept.
            Tier1AndroidSweepOps.RawCursorRow(id = 5, ownerPackageName = ourPkg, isPending = 1)
        )

        val out = Tier1AndroidSweepOps.filterPendingOwned(
            rows = rows,
            ourPackageName = ourPkg,
            baseUriString = "content://media/external_primary/video/media"
        )

        assertEquals(2, out.size)
        assertTrue("id=1 kept", out.any { it.uri.endsWith("/1") })
        assertTrue("id=5 kept", out.any { it.uri.endsWith("/5") })
        assertFalse("non-pending owned (id=2) MUST be dropped — would delete user's gallery", out.any { it.uri.endsWith("/2") })
        assertFalse("other-package (id=3) dropped", out.any { it.uri.endsWith("/3") })
        assertFalse("null-owner (id=4) dropped", out.any { it.uri.endsWith("/4") })
    }

    @Test
    fun `filterPendingOwned with all non-pending rows returns empty list`() {
        // Worst case: cursor returned the user's entire owned-video
        // library because someone forgot the IS_PENDING = 1 selection
        // on API 29. The post-cursor filter is the last line of
        // defense — all rows must be rejected.
        val rows = (1..10).map { id ->
            Tier1AndroidSweepOps.RawCursorRow(
                id = id.toLong(),
                ownerPackageName = ourPkg,
                isPending = 0
            )
        }

        val out = Tier1AndroidSweepOps.filterPendingOwned(
            rows = rows,
            ourPackageName = ourPkg,
            baseUriString = "content://media/external_primary/video/media"
        )

        assertTrue(
            "every non-pending row MUST be filtered out by defense-in-depth pass — " +
                "the sweep must never see a published artifact",
            out.isEmpty()
        )
    }

    @Test
    fun `filterPendingOwned emits PendingRow URI built from baseUri + id`() {
        val rows = listOf(
            Tier1AndroidSweepOps.RawCursorRow(id = 42, ownerPackageName = ourPkg, isPending = 1)
        )
        val out = Tier1AndroidSweepOps.filterPendingOwned(
            rows = rows,
            ourPackageName = ourPkg,
            baseUriString = "content://media/external_primary/video/media"
        )
        assertEquals(1, out.size)
        assertEquals("content://media/external_primary/video/media/42", out[0].uri)
        assertEquals(ourPkg, out[0].ownerPackageName)
    }

    @Test
    fun `app-owned non-pending row never reaches Tier1OrphanSweep`() {
        // End-to-end: simulate the production seam returning the
        // post-filter list (i.e., AFTER filterPendingOwned has rejected
        // any non-pending rows). The sweep then sees only legitimate
        // pending orphans. This is what the layered defenses guarantee.
        val publishedOwnedUri = "content://media/external_primary/video/media/100"
        val pendingOrphanUri = "content://media/external_primary/video/media/101"

        // Production seam runs filterPendingOwned first; we simulate
        // the post-filter list directly.
        val seamReturns = Tier1AndroidSweepOps.filterPendingOwned(
            rows = listOf(
                Tier1AndroidSweepOps.RawCursorRow(id = 100, ownerPackageName = ourPkg, isPending = 0),
                Tier1AndroidSweepOps.RawCursorRow(id = 101, ownerPackageName = ourPkg, isPending = 1)
            ),
            ourPackageName = ourPkg,
            baseUriString = "content://media/external_primary/video/media"
        )

        val deleted = mutableListOf<String>()
        val sweep = Tier1OrphanSweep(
            ourPackageName = ourPkg,
            listVisiblePendingRows = { seamReturns },
            deletePendingRow = { uri -> deleted += uri; true }
        )

        runBlocking { sweep.sweepTier1OrphanPendingRows(emptySet()) }

        assertFalse(
            "user's published video (non-pending) must NEVER reach the sweep's delete path",
            deleted.contains(publishedOwnedUri)
        )
        assertEquals(
            "only the pending orphan is deleted",
            listOf(pendingOrphanUri),
            deleted
        )
    }

    @Test
    fun `Tier1AndroidSweepOps does NOT use setIncludePending in the API 30+ branch`() {
        // Structural check: in the if/else SDK branch, the >= R arm
        // must use QUERY_ARG_MATCH_PENDING and the < R arm must use
        // setIncludePending. We verify the source order in CODE LINES
        // (KDoc references skipped).
        val codeLines = readSweepOpsSource().lines()
            .map { line ->
                val trimmed = line.trimStart()
                if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) ""
                else line
            }
        val matchPendingLine = codeLines.indexOfFirst { it.contains("QUERY_ARG_MATCH_PENDING") }
        val includePendingLine = codeLines.indexOfFirst { it.contains("setIncludePending(") }
        assertTrue("both must exist in code", matchPendingLine >= 0 && includePendingLine >= 0)
        assertTrue(
            "QUERY_ARG_MATCH_PENDING (API 30+ branch) must appear BEFORE setIncludePending " +
                "(API 29 else-branch) — i.e., the if-arm guards the >= R path",
            matchPendingLine < includePendingLine
        )
    }

    private fun readSweepOpsSource(): String {
        // JVM unit tests run with the module root (`app/`) as CWD.
        val file = File("src/main/java/com/aritr/rova/service/export/Tier1AndroidSweepOps.kt")
        assertTrue("Tier1AndroidSweepOps.kt missing at ${file.absolutePath}", file.exists())
        return file.readText()
    }
}

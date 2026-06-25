package com.aritr.rova.gradle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ExportRulesTest {

    private fun src(relPath: String, body: String) =
        SourceFile(relPath, body.split("\n"), body)

    // ─── checkUserStoppedBeforeMerge ─────────────────────────────────────────

    @Test
    fun userStoppedBeforeMerge_passesOnCorrectOrder() {
        // USER_STOPPED before first merge; COMPLETED after last merge — clean.
        // Calls are spaced >3 lines apart so the rule's 3-line markTerminated
        // window does not spill across them (mirrors the real RovaRecordingService.kt
        // structure, where the gate passes).
        val body = """
            sessionStore.markTerminated(sid, Terminated.USER_STOPPED, reason)
            val pad1 = 1
            val pad2 = 2
            val pad3 = 3
            performMerge(segments)
            val pad4 = 4
            val pad5 = 5
            val pad6 = 6
            sessionStore.markTerminated(sid, Terminated.COMPLETED, stopReason)
        """.trimIndent()
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt",
            body
        ))
        assertNull(RovaGateRules.run("checkUserStoppedBeforeMerge", files))
    }

    @Test
    fun userStoppedBeforeMerge_failsOnUserStoppedAfterMerge() {
        // USER_STOPPED appears AFTER the merge call — B3 violation
        val relPath = "app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt"
        val body = """
            performMerge(segments)
            sessionStore.markTerminated(sid, Terminated.USER_STOPPED, reason)
        """.trimIndent()
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkUserStoppedBeforeMerge", files)
        // performMerge( is on line 1; markTerminated( with USER_STOPPED is on line 2
        val expected = "Terminal-write ordering violations in $relPath (ADR 0006 B3 + B7):\n" +
            "  USER_STOPPED markTerminated at line 2 appears AT OR AFTER merge call at line 1 — eager-write rule (B3) violated\n" +
            "Fix: USER_STOPPED writes go BEFORE merge entry; COMPLETED only at merge-success commit point.\n" +
            "Opt-out marker: // terminal-ordering-opt-out: <reason>"
        assertEquals(expected, msg)
    }

    @Test
    fun userStoppedBeforeMerge_passesWithOptOut() {
        // File-level opt-out suppresses all ordering checks
        val body = """
            // terminal-ordering-opt-out: test stub — ordering not applicable here
            performMerge(segments)
            sessionStore.markTerminated(sid, Terminated.USER_STOPPED, reason)
        """.trimIndent()
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt",
            body
        ))
        assertNull(RovaGateRules.run("checkUserStoppedBeforeMerge", files))
    }

    @Test
    fun userStoppedBeforeMerge_passesOnEmptyInput() {
        // Empty list (missing file) — no ordering violations possible
        assertNull(RovaGateRules.run("checkUserStoppedBeforeMerge", emptyList()))
    }

    @Test
    fun userStoppedBeforeMerge_passesWhenNoMergeCall() {
        // No performMerge / mergeSegments in file — nothing to check
        val body = "sessionStore.markTerminated(sid, Terminated.USER_STOPPED, reason)"
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt",
            body
        ))
        assertNull(RovaGateRules.run("checkUserStoppedBeforeMerge", files))
    }

    // ─── checkExportTierReadTolerant ─────────────────────────────────────────

    @Test
    fun exportTierReadTolerant_passesOnClean() {
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/data/SessionManifest.kt",
            "val tier = manifest.optString(\"exportTier\").ifEmpty { currentExportTier() }"
        ))
        assertNull(RovaGateRules.run("checkExportTierReadTolerant", files))
    }

    @Test
    fun exportTierReadTolerant_failsOnGetString() {
        val relPath = "app/src/main/java/com/aritr/rova/data/SessionManifest.kt"
        val body = """val tier = getString("exportTier")"""
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkExportTierReadTolerant", files)
        val expected = "Strict getString(\"exportTier\") is forbidden — schema-2 " +
            "manifests lack the field and would throw JSONException. " +
            "Use the optString + runCatching + currentExportTier() " +
            "fallback pattern (ADR 0003 §FD Mode Amendment partner). " +
            "Offenders:\n" +
            "  $relPath:1: ${body.trim()}"
        assertEquals(expected, msg)
    }

    @Test
    fun exportTierReadTolerant_skipsCommented() {
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/data/SessionManifest.kt",
            """// val tier = getString("exportTier")"""
        ))
        assertNull(RovaGateRules.run("checkExportTierReadTolerant", files))
    }

    @Test
    fun exportTierReadTolerant_passesOnEmptyInput() {
        assertNull(RovaGateRules.run("checkExportTierReadTolerant", emptyList()))
    }

    // ─── checkScanFileBoundedWait ─────────────────────────────────────────────

    @Test
    fun scanFileBoundedWait_passesOnClean() {
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/service/export/Tier2Exporter.kt",
            "MediaScanWaiter.scanAndWait(context, file)"
        ))
        assertNull(RovaGateRules.run("checkScanFileBoundedWait", files))
    }

    @Test
    fun scanFileBoundedWait_failsOnDirectScanFile() {
        val relPath = "app/src/main/java/com/aritr/rova/service/export/Tier2Exporter.kt"
        val body = "MediaScannerConnection.scanFile(context, arrayOf(path), null, null)"
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkScanFileBoundedWait", files)
        val expected = "MediaScannerConnection.scanFile is forbidden outside " +
            "MediaScanWaiter.kt — every call must be bounded by " +
            "MediaScanWaiter.scanAndWait (Phase 1.7 Patch 2; " +
            "naked awaits hang the foreground service if the " +
            "scan callback never fires). Offenders:\n" +
            "  $relPath:1: ${body.trim()}"
        assertEquals(expected, msg)
    }

    @Test
    fun scanFileBoundedWait_passesInsideMediaScanWaiter() {
        // The excluded file — must pass even with the direct scanFile call
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/service/export/MediaScanWaiter.kt",
            "MediaScannerConnection.scanFile(context, arrayOf(path), null, callback)"
        ))
        assertNull(RovaGateRules.run("checkScanFileBoundedWait", files))
    }

    @Test
    fun scanFileBoundedWait_skipsCommented() {
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/service/export/Tier2Exporter.kt",
            "// MediaScannerConnection.scanFile — do not call directly"
        ))
        assertNull(RovaGateRules.run("checkScanFileBoundedWait", files))
    }

    @Test
    fun scanFileBoundedWait_passesOnEmptyInput() {
        assertNull(RovaGateRules.run("checkScanFileBoundedWait", emptyList()))
    }

    // ─── checkPendingFdModeIsRW ───────────────────────────────────────────────

    @Test
    fun pendingFdModeIsRW_passesOnClean() {
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/service/export/Tier1AndroidOps.kt",
            """val pfd = resolver.openFileDescriptor(uri, "rw")"""
        ))
        assertNull(RovaGateRules.run("checkPendingFdModeIsRW", files))
    }

    @Test
    fun pendingFdModeIsRW_failsOnWMode() {
        val relPath = "app/src/main/java/com/aritr/rova/service/export/Tier1Exporter.kt"
        val body = """val pfd = resolver.openFileDescriptor(uri, "w")"""
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkPendingFdModeIsRW", files)
        val expected = "Forbidden openFileDescriptor mode \"w\" in Tier 1 sources " +
            "(ADR 0003 §FD Mode Amendment — \"w\" is non-seekable; " +
            "MediaMuxer.stop() rewrites the moov atom and requires a " +
            "seekable FD). Use \"rw\" instead. Offenders:\n" +
            "  $relPath:1: ${body.trim()}"
        assertEquals(expected, msg)
    }

    @Test
    fun pendingFdModeIsRW_passesWhenNonTier1File() {
        // Non-Tier1 files are out of scope — no check applied
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/service/export/Tier2Exporter.kt",
            """val pfd = resolver.openFileDescriptor(uri, "w")"""
        ))
        assertNull(RovaGateRules.run("checkPendingFdModeIsRW", files))
    }

    @Test
    fun pendingFdModeIsRW_skipsCommented() {
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/service/export/Tier1Exporter.kt",
            """// resolver.openFileDescriptor(uri, "w")"""
        ))
        assertNull(RovaGateRules.run("checkPendingFdModeIsRW", files))
    }

    @Test
    fun pendingFdModeIsRW_passesOnEmptyInput() {
        assertNull(RovaGateRules.run("checkPendingFdModeIsRW", emptyList()))
    }

    // ─── checkExportNoCopyToPublicMovies ──────────────────────────────────────

    @Test
    fun exportNoCopyToPublicMovies_passesOnClean() {
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt",
            "ExportPipeline.export(context, mergedFile)"
        ))
        assertNull(RovaGateRules.run("checkExportNoCopyToPublicMovies", files))
    }

    @Test
    fun exportNoCopyToPublicMovies_failsOnForbiddenSymbol() {
        val relPath = "app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt"
        val body = "copyToPublicMovies(mergedFile)"
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkExportNoCopyToPublicMovies", files)
        val expected = "copyToPublicMovies symbol is forbidden (Phase 1.7 commit-7). Offenders:\n" +
            "  $relPath:1: ${body.trim()}"
        assertEquals(expected, msg)
    }

    @Test
    fun exportNoCopyToPublicMovies_skipsCommented() {
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt",
            "// copyToPublicMovies — deleted in commit-7"
        ))
        assertNull(RovaGateRules.run("checkExportNoCopyToPublicMovies", files))
    }

    @Test
    fun exportNoCopyToPublicMovies_passesOnEmptyInput() {
        assertNull(RovaGateRules.run("checkExportNoCopyToPublicMovies", emptyList()))
    }

    // ─── checkExportCleanupPredicate ─────────────────────────────────────────

    private fun cleanPredicateBody() = """
        if (classification.eligibility != DiscardEligibility.AUTO_DISCARD_ELIGIBLE) return false
        if (manifest.privateTempPath != null) return false
        if (recoveryResult is RecoveryResult.RetryableFailure) return false
        if (recoveryResult is RecoveryResult.ManifestWriteFailed) return false
        if (sweepResult is OrphanSweepResult.QueryFailed) return false
        return true
    """.trimIndent()

    @Test
    fun exportCleanupPredicate_passesOnClean() {
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/service/export/ExportCleanupPredicate.kt",
            cleanPredicateBody()
        ))
        assertNull(RovaGateRules.run("checkExportCleanupPredicate", files))
    }

    @Test
    fun exportCleanupPredicate_failsWhenTokenMissing() {
        // Drop AUTO_DISCARD_ELIGIBLE — gate must fire
        val body = cleanPredicateBody().replace("AUTO_DISCARD_ELIGIBLE", "XXX_REMOVED")
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/service/export/ExportCleanupPredicate.kt",
            body
        ))
        val msg = RovaGateRules.run("checkExportCleanupPredicate", files)
        assertNotNull(msg)
        val expected = "ExportCleanupPredicate cleanup gate violation (ADR 0006 §Ownership table " +
            "+ commit-6 NO-GO patch):\n" +
            "  missing AUTO_DISCARD_ELIGIBLE (Phase 1.5 eligibility gate)\n" +
            "All four gates MUST be referenced in the predicate source. " +
            "Dropping any gate risks deleting a manifest still needed to " +
            "protect a referenced pending row."
        assertEquals(expected, msg)
    }

    @Test
    fun exportCleanupPredicate_failsOnEmptyInput() {
        // Empty list = missing file; REQUIRE gate must return failure, not null
        val msg = RovaGateRules.run("checkExportCleanupPredicate", emptyList())
        assertNotNull(msg)
        // All five tokens are absent — all five problems are reported
        val expected = "ExportCleanupPredicate cleanup gate violation (ADR 0006 §Ownership table " +
            "+ commit-6 NO-GO patch):\n" +
            "  missing AUTO_DISCARD_ELIGIBLE (Phase 1.5 eligibility gate)\n" +
            "  missing privateTempPath check (Tier 2/3 deferred-scan retention gate)\n" +
            "  missing RetryableFailure check (per-session recovery clean gate)\n" +
            "  missing ManifestWriteFailed check (per-session recovery clean gate)\n" +
            "  missing QueryFailed check (sweep clean gate; commit-6 NO-GO patch)\n" +
            "All four gates MUST be referenced in the predicate source. " +
            "Dropping any gate risks deleting a manifest still needed to " +
            "protect a referenced pending row."
        assertEquals(expected, msg)
    }

    @Test
    fun exportCleanupPredicate_doesNotCountCommentOnlyTokens() {
        // Token only appears in a comment line — gate must still fire
        val body = """
            // AUTO_DISCARD_ELIGIBLE is mentioned only in a doc comment
            if (manifest.privateTempPath != null) return false
            if (recoveryResult is RecoveryResult.RetryableFailure) return false
            if (recoveryResult is RecoveryResult.ManifestWriteFailed) return false
            if (sweepResult is OrphanSweepResult.QueryFailed) return false
            return true
        """.trimIndent()
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/service/export/ExportCleanupPredicate.kt",
            body
        ))
        val msg = RovaGateRules.run("checkExportCleanupPredicate", files)
        assertNotNull(msg)
        assert(msg!!.contains("missing AUTO_DISCARD_ELIGIBLE"))
    }
}

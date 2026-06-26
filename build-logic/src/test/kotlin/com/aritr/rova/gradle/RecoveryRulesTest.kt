package com.aritr.rova.gradle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryRulesTest {

    private fun src(relPath: String, body: String) =
        SourceFile(relPath, body.split("\n"), body)

    // ─── checkStopNoGetService ───────────────────────────────────────────────

    @Test
    fun stopNoGetService_passesOnClean() {
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/service/RovaStopReceiver.kt",
            "val pi = PendingIntent.getBroadcast(ctx, 0, intent, 0)"
        ))
        assertNull(RovaGateRules.run("checkStopNoGetService", files))
    }

    @Test
    fun stopNoGetService_failsOnGetService() {
        val relPath = "app/src/main/java/com/aritr/rova/service/SomeReceiver.kt"
        val body = "val pi = PendingIntent.getService(ctx, 0, intent, 0)"
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkStopNoGetService", files)
        val expected = "PendingIntent.getService is forbidden — use PendingIntent.getBroadcast " +
            "with a BroadcastReceiver (Android 12+ disallows FGS starts from " +
            "alarm/notification background contexts). Offenders:\n" +
            "  $relPath:1: ${body.trim()}"
        assertEquals(expected, msg)
    }

    @Test
    fun stopNoGetService_skipsCommented() {
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/service/RovaStopReceiver.kt",
            "// PendingIntent.getService(ctx, 0, intent, 0)"
        ))
        assertNull(RovaGateRules.run("checkStopNoGetService", files))
    }

    // ─── checkScheduleReceiverNoFgsStart ─────────────────────────────────────

    @Test
    fun scheduleReceiverNoFgsStart_passesOnClean() {
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/service/schedule/DailyWindowReceiver.kt",
            "fun onReceive(ctx: Context, intent: Intent) { notifyUser(ctx) }"
        ))
        assertNull(RovaGateRules.run("checkScheduleReceiverNoFgsStart", files))
    }

    @Test
    fun scheduleReceiverNoFgsStart_failsOnStartForegroundService() {
        val relPath = "app/src/main/java/com/aritr/rova/service/schedule/DailyWindowReceiver.kt"
        val body = "ctx.startForegroundService(intent)"
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkScheduleReceiverNoFgsStart", files)
        val expected = "Schedule receivers must not start a foreground service (ADR-0027): the " +
            "camera FGS is started only from MainActivity on the user's notification " +
            "tap. Offenders:\n" +
            "  $relPath:1: ${body.trim()}"
        assertEquals(expected, msg)
    }

    @Test
    fun scheduleReceiverNoFgsStart_skipsCommented() {
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/service/schedule/DailyWindowReceiver.kt",
            "// ctx.startForegroundService(intent)"
        ))
        assertNull(RovaGateRules.run("checkScheduleReceiverNoFgsStart", files))
    }

    @Test
    fun scheduleReceiverNoFgsStart_ignoresJavaFiles() {
        // Old gate filtered extension == "kt" only (not "java")
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/service/schedule/Foo.java",
            "ctx.startForegroundService(intent);"
        ))
        assertNull(RovaGateRules.run("checkScheduleReceiverNoFgsStart", files))
    }

    // ─── checkRecoveryNoDeletion ─────────────────────────────────────────────

    @Test
    fun recoveryNoDeletion_passesOnClean() {
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/service/recovery/RecoveryScanner.kt",
            "val eligible = segments.filter { it.isOrphan }"
        ))
        assertNull(RovaGateRules.run("checkRecoveryNoDeletion", files))
    }

    @Test
    fun recoveryNoDeletion_failsOnDeleteCall() {
        val relPath = "app/src/main/java/com/aritr/rova/service/recovery/RecoveryScanner.kt"
        val body = "segment.delete()"
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkRecoveryNoDeletion", files)
        val expected = "Phase 1.5 sources must not call deletion APIs (ADR 0005 — " +
            "Phase 1.5 emits DiscardEligibility flags only; deletion is " +
            "owned by Phase 1.7 post-export-recovery cleanup or by " +
            "explicit user action). Offenders:\n" +
            "  $relPath:1: ${body.trim()}"
        assertEquals(expected, msg)
    }

    @Test
    fun recoveryNoDeletion_failsOnDeleteRecursively() {
        val relPath = "app/src/main/java/com/aritr/rova/RovaApp.kt"
        val body = "dir.deleteRecursively()"
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkRecoveryNoDeletion", files)
        val expected = "Phase 1.5 sources must not call deletion APIs (ADR 0005 — " +
            "Phase 1.5 emits DiscardEligibility flags only; deletion is " +
            "owned by Phase 1.7 post-export-recovery cleanup or by " +
            "explicit user action). Offenders:\n" +
            "  $relPath:1: ${body.trim()}"
        assertEquals(expected, msg)
    }

    @Test
    fun recoveryNoDeletion_skipsCommented() {
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/service/recovery/RecoveryScanner.kt",
            "// segment.delete("
        ))
        assertNull(RovaGateRules.run("checkRecoveryNoDeletion", files))
    }

    // ─── checkRecoverySegmentRegex ───────────────────────────────────────────

    @Test
    fun recoverySegmentRegex_passesOnClean() {
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/service/recovery/RecoveryScanner.kt",
            """val pattern = Regex("segment_\\d{4}\\.mp4")"""
        ))
        assertNull(RovaGateRules.run("checkRecoverySegmentRegex", files))
    }

    @Test
    fun recoverySegmentRegex_failsOnSegUnderscore() {
        val relPath = "app/src/main/java/com/aritr/rova/service/recovery/RecoveryScanner.kt"
        val body = """val pattern = Regex("seg_\\d{4}\\.mp4")"""
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkRecoverySegmentRegex", files)
        val expected = "`seg_` is forbidden in Phase 1.5 recovery sources — the " +
            "canonical segment filename pattern is `segment_NNNN.mp4` " +
            "(SessionStore.nextSegmentFilename, ADR 0005). " +
            "Offenders:\n" +
            "  $relPath:1: ${body.trim()}"
        assertEquals(expected, msg)
    }

    @Test
    fun recoverySegmentRegex_skipsCommented() {
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/service/recovery/RecoveryScanner.kt",
            """// old pattern was seg_0001.mp4"""
        ))
        assertNull(RovaGateRules.run("checkRecoverySegmentRegex", files))
    }

    // ─── checkScanTriggerSingleSite ──────────────────────────────────────────

    @Test
    fun scanTriggerSingleSite_passesOnClean() {
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/service/recovery/RecoveryScanner.kt",
            "fun classifyAll(): RecoveryReport { return RecoveryReport() }"
        ))
        assertNull(RovaGateRules.run("checkScanTriggerSingleSite", files))
    }

    @Test
    fun scanTriggerSingleSite_passesWhenRovaAppCallsIt() {
        // RovaApp.kt is the ALLOWED site — must pass even when it contains runRecoveryScan
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/RovaApp.kt",
            "private fun runRecoveryScan() { /* ... */ }"
        ))
        assertNull(RovaGateRules.run("checkScanTriggerSingleSite", files))
    }

    @Test
    fun scanTriggerSingleSite_failsOnForbiddenCallSite() {
        val relPath = "app/src/main/java/com/aritr/rova/service/recovery/SomeRunner.kt"
        val body = "runRecoveryScan()"
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkScanTriggerSingleSite", files)
        val expected = "`runRecoveryScan` is owned exclusively by RovaApp; the only " +
            "trigger is RovaApp.triggerRecoveryScanIfNeeded called from " +
            "MainActivity.onCreate (ADR 0005 §Scan Trigger Boundary). " +
            "Offenders:\n" +
            "  $relPath:1: ${body.trim()}"
        assertEquals(expected, msg)
    }

    @Test
    fun scanTriggerSingleSite_skipsCommented() {
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/service/recovery/SomeRunner.kt",
            "// runRecoveryScan() — do NOT call from here"
        ))
        assertNull(RovaGateRules.run("checkScanTriggerSingleSite", files))
    }

    // ─── hole-close: block-comment-close false-pass ──────────────────────────

    @Test
    fun stopNoGetService_failsAfterBlockCommentClose() {
        // `*/ <code>` — raw trimStart begins with `*`, legacy skipped it (false-pass).
        // CommentStripper blanks only the `*/` portion; the token is newly visible.
        val relPath = "app/src/main/java/com/aritr/rova/service/RovaStopReceiver.kt"
        val files = listOf(src(
            relPath,
            "    */ val pi = PendingIntent.getService(ctx, 0, intent, 0)"
        ))
        val msg = RovaGateRules.run("checkStopNoGetService", files)
        assertTrue(msg != null &&
            msg.startsWith("PendingIntent.getService is forbidden"))
    }

    @Test
    fun scheduleReceiverNoFgsStart_failsAfterBlockCommentClose() {
        // `*/ <code>` — raw trimStart begins with `*`, legacy skipped it (false-pass).
        val relPath = "app/src/main/java/com/aritr/rova/service/schedule/DailyWindowReceiver.kt"
        val files = listOf(src(
            relPath,
            "    */ ctx.startService(intent)"
        ))
        val msg = RovaGateRules.run("checkScheduleReceiverNoFgsStart", files)
        assertTrue(msg != null &&
            msg.startsWith("Schedule receivers must not start a foreground service"))
    }

    @Test
    fun recoveryNoDeletion_failsAfterBlockCommentClose() {
        // `*/ <code>` — raw trimStart begins with `*`, legacy skipped it (false-pass).
        val relPath = "app/src/main/java/com/aritr/rova/service/recovery/RecoveryScanner.kt"
        val files = listOf(src(
            relPath,
            "    */ segment.delete()"
        ))
        val msg = RovaGateRules.run("checkRecoveryNoDeletion", files)
        assertTrue(msg != null &&
            msg.startsWith("Phase 1.5 sources must not call deletion APIs"))
    }

    @Test
    fun recoverySegmentRegex_failsAfterBlockCommentClose() {
        // `*/ <code>` — raw trimStart begins with `*`, legacy skipped it (false-pass).
        val relPath = "app/src/main/java/com/aritr/rova/service/recovery/RecoveryScanner.kt"
        val files = listOf(src(
            relPath,
            """    */ val p = Regex("seg_\\d+.mp4")"""
        ))
        val msg = RovaGateRules.run("checkRecoverySegmentRegex", files)
        assertTrue(msg != null &&
            msg.startsWith("`seg_` is forbidden in Phase 1.5 recovery sources"))
    }

    @Test
    fun scanTriggerSingleSite_failsAfterBlockCommentClose() {
        // `*/ <code>` — raw trimStart begins with `*`, legacy skipped it (false-pass).
        val relPath = "app/src/main/java/com/aritr/rova/service/recovery/SomeRunner.kt"
        val files = listOf(src(
            relPath,
            "    */ runRecoveryScan()"
        ))
        val msg = RovaGateRules.run("checkScanTriggerSingleSite", files)
        assertTrue(msg != null &&
            msg.startsWith("`runRecoveryScan` is owned exclusively by RovaApp"))
    }
}

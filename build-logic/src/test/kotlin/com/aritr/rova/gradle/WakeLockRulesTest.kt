package com.aritr.rova.gradle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakeLockRulesTest {

    private fun src(relPath: String, body: String) =
        SourceFile(relPath, body.split("\n"), body)

    // ─── checkWakeLockBoundedAcquire ─────────────────────────────────────────

    @Test
    fun boundedAcquire_passesOnClean() {
        // Only bounded acquire calls (with timeout arg) — must pass.
        val body = """
            wakeLock.acquire(WakeLockPolicy.ACQUIRE_TIMEOUT_MS)
            existing.acquire(WakeLockPolicy.ACQUIRE_TIMEOUT_MS)
        """.trimIndent()
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt", body),
            src("app/src/main/java/com/aritr/rova/service/wakelock/WakeLockPolicy.kt", "// no acquire here")
        )
        assertNull(RovaGateRules.run("checkWakeLockBoundedAcquire", files))
    }

    @Test
    fun boundedAcquire_passesOnEmptyInput() {
        // Forbid gate: no files, no offenders.
        assertNull(RovaGateRules.run("checkWakeLockBoundedAcquire", emptyList()))
    }

    @Test
    fun boundedAcquire_failsOnBareAcquireInService() {
        val relPath = "app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt"
        val body = "        wakeLock.acquire()"
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkWakeLockBoundedAcquire", files)
        val expected = "C17 violation (Phase 1.8 §WakeLock Discipline) — " +
            "WakeLock.acquire() must pass a timeout. Use " +
            "acquire(WakeLockPolicy.ACQUIRE_TIMEOUT_MS):\n" +
            "  $relPath:1: wakeLock.acquire()"
        assertEquals(expected, msg)
    }

    @Test
    fun boundedAcquire_failsOnBareAcquireInPolicy() {
        val relPath = "app/src/main/java/com/aritr/rova/service/wakelock/WakeLockPolicy.kt"
        val body = "        lock.acquire()"
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkWakeLockBoundedAcquire", files)
        val expected = "C17 violation (Phase 1.8 §WakeLock Discipline) — " +
            "WakeLock.acquire() must pass a timeout. Use " +
            "acquire(WakeLockPolicy.ACQUIRE_TIMEOUT_MS):\n" +
            "  $relPath:1: lock.acquire()"
        assertEquals(expected, msg)
    }

    @Test
    fun boundedAcquire_skipsCommentLines() {
        // Lines starting with // or * are ignored.
        val body = """
            // wakeLock.acquire()
            * .acquire() — do not call bare
        """.trimIndent()
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt", body)
        )
        assertNull(RovaGateRules.run("checkWakeLockBoundedAcquire", files))
    }

    @Test
    fun boundedAcquire_matchesAcquireWithWhitespace() {
        // Regex is .acquire\s*\(\s*\) — spaces inside parens must also be caught.
        val relPath = "app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt"
        val body = "wakeLock.acquire(  )"
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkWakeLockBoundedAcquire", files)
        val expected = "C17 violation (Phase 1.8 §WakeLock Discipline) — " +
            "WakeLock.acquire() must pass a timeout. Use " +
            "acquire(WakeLockPolicy.ACQUIRE_TIMEOUT_MS):\n" +
            "  $relPath:1: wakeLock.acquire(  )"
        assertEquals(expected, msg)
    }

    // ─── checkWakeLockHeldRefresh ─────────────────────────────────────────────

    @Test
    fun heldRefresh_passesOnClean() {
        // acquireWakeLock() body contains both required statements.
        // Class-wrapped so members are 4-space indented after trimIndent,
        // matching the real file — the closing-brace detector keys on `    }` exactly.
        val body = """
            class RovaRecordingService {
                private fun acquireWakeLock() {
                    val existing = wakeLock
                    if (existing?.isHeld == true) {
                        existing.acquire(WakeLockPolicy.ACQUIRE_TIMEOUT_MS)
                        return
                    }
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tag")
                }
            }
        """.trimIndent()
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt", body)
        )
        assertNull(RovaGateRules.run("checkWakeLockHeldRefresh", files))
    }

    @Test
    fun heldRefresh_failsOnEmptyInput() {
        // Empty input -> source file missing -> fail-closed (old gate threw on !exists()).
        val msg = RovaGateRules.run("checkWakeLockHeldRefresh", emptyList())
        val expected = "checkWakeLockHeldRefresh: source file missing: " +
            "src/main/java/com/aritr/rova/service/RovaRecordingService.kt"
        assertEquals(expected, msg)
    }

    @Test
    fun heldRefresh_failsWhenFunctionMissing() {
        // File present but contains no acquireWakeLock() declaration.
        val relPath = "app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt"
        val body = "class RovaRecordingService { fun unrelated() {} }"
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkWakeLockHeldRefresh", files)
        val expected = "checkWakeLockHeldRefresh: acquireWakeLock() declaration not found in " +
            relPath
        assertEquals(expected, msg)
    }

    @Test
    fun heldRefresh_failsWhenRefreshMissing() {
        // acquireWakeLock() body has isHeld guard but no refresh acquire call.
        // Class-wrapped so member closing brace is at 4-space indent after trimIndent.
        val relPath = "app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt"
        val body = """
            class RovaRecordingService {
                private fun acquireWakeLock() {
                    val existing = wakeLock
                    if (existing?.isHeld == true) {
                        return
                    }
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tag")
                }
            }
        """.trimIndent()
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkWakeLockHeldRefresh", files)
        val expected = "C17 round-2 violation (Phase 1.8 §WakeLock Discipline) — " +
            "acquireWakeLock() must refresh the bounded timeout on the " +
            "held branch.\n" +
            "Required statements inside the function body:\n" +
            "  - `existing?.isHeld == true` guard: OK\n" +
            "  - `existing.acquire(WakeLockPolicy.ACQUIRE_TIMEOUT_MS)`: MISSING\n" +
            "Fix: in the `if (existing?.isHeld == true)` branch, call " +
            "`existing.acquire(WakeLockPolicy.ACQUIRE_TIMEOUT_MS)` before " +
            "returning so continuous sessions cannot outlive the timeout."
        assertEquals(expected, msg)
    }

    @Test
    fun heldRefresh_failsWhenBothStatementsMissing() {
        // acquireWakeLock() body has neither isHeld guard nor refresh call.
        // Class-wrapped for correct 4-space member indent.
        val relPath = "app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt"
        val body = """
            class RovaRecordingService {
                private fun acquireWakeLock() {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tag")
                }
            }
        """.trimIndent()
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkWakeLockHeldRefresh", files)
        val expected = "C17 round-2 violation (Phase 1.8 §WakeLock Discipline) — " +
            "acquireWakeLock() must refresh the bounded timeout on the " +
            "held branch.\n" +
            "Required statements inside the function body:\n" +
            "  - `existing?.isHeld == true` guard: MISSING\n" +
            "  - `existing.acquire(WakeLockPolicy.ACQUIRE_TIMEOUT_MS)`: MISSING\n" +
            "Fix: in the `if (existing?.isHeld == true)` branch, call " +
            "`existing.acquire(WakeLockPolicy.ACQUIRE_TIMEOUT_MS)` before " +
            "returning so continuous sessions cannot outlive the timeout."
        assertEquals(expected, msg)
    }

    // ─── checkWakeLockZeroGapRefresh ──────────────────────────────────────────

    @Test
    fun zeroGapRefresh_passesOnClean() {
        // if (waitSeconds > 0) block has } else { and acquireWakeLock() within 20 lines.
        val body = """
            if (waitSeconds > 0) {
                waitForNextSegment(waitSeconds)
            } else {
                acquireWakeLock()
            }
        """.trimIndent()
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt", body)
        )
        assertNull(RovaGateRules.run("checkWakeLockZeroGapRefresh", files))
    }

    @Test
    fun zeroGapRefresh_failsOnEmptyInput() {
        // Empty input -> source file missing -> fail-closed (old gate threw on !exists()).
        val msg = RovaGateRules.run("checkWakeLockZeroGapRefresh", emptyList())
        val expected = "checkWakeLockZeroGapRefresh: source file missing: " +
            "src/main/java/com/aritr/rova/service/RovaRecordingService.kt"
        assertEquals(expected, msg)
    }

    @Test
    fun zeroGapRefresh_failsWhenIfLineMissing() {
        // File present but no `if (waitSeconds > 0)` line.
        val relPath = "app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt"
        val body = "waitForNextSegment(waitSeconds)"
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkWakeLockZeroGapRefresh", files)
        val expected = "checkWakeLockZeroGapRefresh: `if (waitSeconds > 0)` not found in " +
            relPath
        assertEquals(expected, msg)
    }

    @Test
    fun zeroGapRefresh_failsWhenElseMissing() {
        // if (waitSeconds > 0) block present but no } else { clause in window.
        val relPath = "app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt"
        val body = """
            if (waitSeconds > 0) {
                waitForNextSegment(waitSeconds)
            }
        """.trimIndent()
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkWakeLockZeroGapRefresh", files)
        val expected = "C17 round-3 violation (Phase 1.8 §WakeLock Discipline) — " +
            "the `if (waitSeconds > 0) { waitForNextSegment(...) }` block must " +
            "have an `else { acquireWakeLock() }` clause so back-to-back " +
            "(interval <= duration) sessions still refresh the bounded " +
            "WakeLock timeout.\n" +
            "  - `} else {` clause: MISSING\n" +
            "  - `acquireWakeLock()` in window: MISSING\n" +
            "Fix: add `} else { acquireWakeLock() }` after the " +
            "`waitForNextSegment` call inside the recording loop."
        assertEquals(expected, msg)
    }

    @Test
    fun zeroGapRefresh_failsWhenElsePresentButNoAcquire() {
        // } else { present but no acquireWakeLock() in the 20-line window.
        val relPath = "app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt"
        val body = """
            if (waitSeconds > 0) {
                waitForNextSegment(waitSeconds)
            } else {
                doSomethingElse()
            }
        """.trimIndent()
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkWakeLockZeroGapRefresh", files)
        val expected = "C17 round-3 violation (Phase 1.8 §WakeLock Discipline) — " +
            "the `if (waitSeconds > 0) { waitForNextSegment(...) }` block must " +
            "have an `else { acquireWakeLock() }` clause so back-to-back " +
            "(interval <= duration) sessions still refresh the bounded " +
            "WakeLock timeout.\n" +
            "  - `} else {` clause: OK\n" +
            "  - `acquireWakeLock()` in window: MISSING\n" +
            "Fix: add `} else { acquireWakeLock() }` after the " +
            "`waitForNextSegment` call inside the recording loop."
        assertEquals(expected, msg)
    }
}

package com.aritr.rova.gradle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceRulesTest {

    private fun src(relPath: String, body: String) =
        SourceFile(relPath, body.split("\n"), body)

    // ─── checkRecoveryReceiverCounter ────────────────────────────────────────

    @Test
    fun receiverCounter_passesOnClean() {
        // File with correct ordering: increment → goAsync → decrement in finally
        val body = """
            activeReceiverWork.incrementAndGet()
            val pending = goAsync()
            scope.launch {
                try { doWork() } finally { activeReceiverWork.decrementAndGet() }
            }
        """.trimIndent()
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/service/RovaTickReceiver.kt",
            body
        ))
        assertNull(RovaGateRules.run("checkRecoveryReceiverCounter", files))
    }

    @Test
    fun receiverCounter_passesWhenNoGoAsync() {
        // No goAsync → rule does not apply
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/service/RovaStopReceiver.kt",
            "fun onReceive(ctx: Context, intent: Intent) { doSomethingSync() }"
        ))
        assertNull(RovaGateRules.run("checkRecoveryReceiverCounter", files))
    }

    @Test
    fun receiverCounter_passesWithOptOut() {
        // goAsync present but opt-out marker suppresses the check
        val body = """
            // guard-b-opt-out: synchronous receiver, no coroutine launched
            val pending = goAsync()
            pending.finish()
        """.trimIndent()
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/service/RovaStopReceiver.kt",
            body
        ))
        assertNull(RovaGateRules.run("checkRecoveryReceiverCounter", files))
    }

    @Test
    fun receiverCounter_failsOnMissingIncrement() {
        // goAsync present but no incrementAndGet → missing increment problem
        val body = """
            val pending = goAsync()
            scope.launch {
                try { doWork() } finally { activeReceiverWork.decrementAndGet() }
            }
        """.trimIndent()
        val relPath = "app/src/main/java/com/aritr/rova/service/RovaTickReceiver.kt"
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkRecoveryReceiverCounter", files)
        val expected = "Guard B violations (ADR 0005 §Concurrency Invariants item 3):\n" +
            "  $relPath: missing activeReceiverWork.incrementAndGet() before goAsync() (line 1)\n" +
            "Add a documented opt-out marker `// guard-b-opt-out: <reason>` " +
            "to the file (with a reason) only if the receiver does no " +
            "asynchronous work and never races the recovery scan."
        assertEquals(expected, msg)
    }

    @Test
    fun receiverCounter_failsOnIncrementAfterGoAsync() {
        // incrementAndGet appears AFTER goAsync — ordering violation
        val body = """
            val pending = goAsync()
            activeReceiverWork.incrementAndGet()
            scope.launch {
                try { doWork() } finally { activeReceiverWork.decrementAndGet() }
            }
        """.trimIndent()
        val relPath = "app/src/main/java/com/aritr/rova/service/RovaTickReceiver.kt"
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkRecoveryReceiverCounter", files)
        // goAsync is line 1 (0-indexed 0 → +1 = 1), increment is line 2 (0-indexed 1 → +1 = 2)
        val expected = "Guard B violations (ADR 0005 §Concurrency Invariants item 3):\n" +
            "  $relPath: activeReceiverWork.incrementAndGet() (line 2) must precede goAsync() (line 1); the gap between goAsync() and the launched coroutine body is the race window\n" +
            "Add a documented opt-out marker `// guard-b-opt-out: <reason>` " +
            "to the file (with a reason) only if the receiver does no " +
            "asynchronous work and never races the recovery scan."
        assertEquals(expected, msg)
    }

    @Test
    fun receiverCounter_failsOnMissingDecrement() {
        // incrementAndGet before goAsync but no decrementAndGet anywhere
        val body = """
            activeReceiverWork.incrementAndGet()
            val pending = goAsync()
            scope.launch { doWork() }
        """.trimIndent()
        val relPath = "app/src/main/java/com/aritr/rova/service/RovaTickReceiver.kt"
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkRecoveryReceiverCounter", files)
        val expected = "Guard B violations (ADR 0005 §Concurrency Invariants item 3):\n" +
            "  $relPath: missing activeReceiverWork.decrementAndGet() — must run in the launched coroutine's finally so every exit path releases the counter\n" +
            "Add a documented opt-out marker `// guard-b-opt-out: <reason>` " +
            "to the file (with a reason) only if the receiver does no " +
            "asynchronous work and never races the recovery scan."
        assertEquals(expected, msg)
    }

    // ─── checkAtomicTerminalWriteForbiddenPair ────────────────────────────────

    @Test
    fun atomicTerminalPair_passesOnClean() {
        // markTerminated with USER_STOPPED but a real StopReason
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt",
            "store.markTerminated(session, Terminated.USER_STOPPED, StopReason.USER)"
        ))
        assertNull(RovaGateRules.run("checkAtomicTerminalWriteForbiddenPair", files))
    }

    @Test
    fun atomicTerminalPair_passesWithOptOut() {
        // Forbidden pair but line-level opt-out suppresses it
        val body = "store.markTerminated(session, Terminated.USER_STOPPED, StopReason.NONE) // terminal-ordering-opt-out: test stub"
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt",
            body
        ))
        assertNull(RovaGateRules.run("checkAtomicTerminalWriteForbiddenPair", files))
    }

    @Test
    fun atomicTerminalPair_passesOnCompletedNone() {
        // COMPLETED with NONE is allowed (merge-success path)
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt",
            "store.markTerminated(session, Terminated.COMPLETED, StopReason.NONE)"
        ))
        assertNull(RovaGateRules.run("checkAtomicTerminalWriteForbiddenPair", files))
    }

    @Test
    fun atomicTerminalPair_failsOnSingleLine() {
        val relPath = "app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt"
        val body = "store.markTerminated(session, Terminated.USER_STOPPED, StopReason.NONE)"
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkAtomicTerminalWriteForbiddenPair", files)
        val expected = "Forbidden pair markTerminated(USER_STOPPED, NONE) detected (ADR 0006 B16):\n" +
            "  $relPath: lines 1\n" +
            "USER_STOPPED writers must pass a real StopReason " +
            "(USER, INIT_FAILED, PERMISSION_REVOKED, LOW_STORAGE). " +
            "Only KILLED_* and merge-success COMPLETED pass NONE."
        assertEquals(expected, msg)
    }

    @Test
    fun atomicTerminalPair_failsOnMultilineWindow() {
        // Multi-line call split across 3 lines — still within the +3 window
        val relPath = "app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt"
        val body = "store.markTerminated(\n    session,\n    Terminated.USER_STOPPED,\n    StopReason.NONE\n)"
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkAtomicTerminalWriteForbiddenPair", files)
        val expected = "Forbidden pair markTerminated(USER_STOPPED, NONE) detected (ADR 0006 B16):\n" +
            "  $relPath: lines 1\n" +
            "USER_STOPPED writers must pass a real StopReason " +
            "(USER, INIT_FAILED, PERMISSION_REVOKED, LOW_STORAGE). " +
            "Only KILLED_* and merge-success COMPLETED pass NONE."
        assertEquals(expected, msg)
    }

    // ─── checkExternalRootShared ──────────────────────────────────────────────

    @Test
    fun externalRootShared_passesOnClean() {
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/service/export/Tier1Exporter.kt",
            "val root = RovaApp.videosRoot"
        ))
        assertNull(RovaGateRules.run("checkExternalRootShared", files))
    }

    @Test
    fun externalRootShared_passesWhenRovaAppCallsIt() {
        // RovaApp.kt is in the allowlist → must pass even with the call
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/RovaApp.kt",
            "val dir = context.getExternalFilesDir(null)"
        ))
        assertNull(RovaGateRules.run("checkExternalRootShared", files))
    }

    @Test
    fun externalRootShared_passesWhenSessionStoreCallsIt() {
        // SessionStore.kt is in the allowlist
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/data/SessionStore.kt",
            "val dir = context.getExternalFilesDir(null)"
        ))
        assertNull(RovaGateRules.run("checkExternalRootShared", files))
    }

    @Test
    fun externalRootShared_failsOnForbiddenFile() {
        val relPath = "app/src/main/java/com/aritr/rova/service/export/Tier1Exporter.kt"
        val body = "val dir = context.getExternalFilesDir(null)"
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkExternalRootShared", files)
        val expected = "External-root drift detected (ADR 0006 B21):\n" +
            "  $relPath: lines 1\n" +
            "Use RovaApp.videosRoot or RovaApp.externalRoot instead. " +
            "Only RovaApp and SessionStore are allowed direct references."
        assertEquals(expected, msg)
    }

    @Test
    fun externalRootShared_skipsCommented() {
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/service/export/Tier1Exporter.kt",
            "// val dir = context.getExternalFilesDir(null)"
        ))
        assertNull(RovaGateRules.run("checkExternalRootShared", files))
    }

    // ─── checkAudioModeFgsTypeMatch ───────────────────────────────────────────

    @Test
    fun audioModeFgsType_passesOnClean() {
        // audioMode reference BEFORE FOREGROUND_SERVICE_TYPE_MICROPHONE
        val body = """
            val fgsType = if (audioMode == AudioMode.VIDEO_AUDIO) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
        """.trimIndent()
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt",
            body
        ))
        assertNull(RovaGateRules.run("checkAudioModeFgsTypeMatch", files))
    }

    @Test
    fun audioModeFgsType_passesWhenNoMicType() {
        // No FOREGROUND_SERVICE_TYPE_MICROPHONE → rule does not apply
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt",
            "startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)"
        ))
        assertNull(RovaGateRules.run("checkAudioModeFgsTypeMatch", files))
    }

    @Test
    fun audioModeFgsType_passesWithOptOut() {
        // Opt-out marker suppresses the check even with violation present
        val body = """
            // audio-mode-opt-out: test harness only
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        """.trimIndent()
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt",
            body
        ))
        assertNull(RovaGateRules.run("checkAudioModeFgsTypeMatch", files))
    }

    @Test
    fun audioModeFgsType_failsWhenNoAudioModeGate() {
        // FOREGROUND_SERVICE_TYPE_MICROPHONE with no preceding audioMode reference
        val relPath = "app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt"
        val body = "startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)"
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkAudioModeFgsTypeMatch", files)
        val expected = "FGS-type / audio-mode coupling violation (ADR 0006 B18 + B20):\n" +
            "  $relPath: line 1 — FGS MIC type without audioMode gate\n" +
            "FOREGROUND_SERVICE_TYPE_MICROPHONE must be gated by an " +
            "audioMode == AudioMode.VIDEO_AUDIO check. Hardcoding the " +
            "type bitfield risks SecurityException on Android 14+."
        assertEquals(expected, msg)
    }

    @Test
    fun audioModeFgsType_passesWhenAudioModeAppearsAfter() {
        // audioMode only appears AFTER the MIC line — must still fail
        val relPath = "app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt"
        val body = """
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            val mode = audioMode
        """.trimIndent()
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkAudioModeFgsTypeMatch", files)
        val expected = "FGS-type / audio-mode coupling violation (ADR 0006 B18 + B20):\n" +
            "  $relPath: line 1 — FGS MIC type without audioMode gate\n" +
            "FOREGROUND_SERVICE_TYPE_MICROPHONE must be gated by an " +
            "audioMode == AudioMode.VIDEO_AUDIO check. Hardcoding the " +
            "type bitfield risks SecurityException on Android 14+."
        assertEquals(expected, msg)
    }

    // ─── checkFGSStartGuarded ─────────────────────────────────────────────────

    @Test
    fun fgsStartGuarded_passesOnClean() {
        // Caller-side: all required guards present within 60 lines
        val body = """
            try {
                ctx.startForegroundService(intent)
            } catch (e: IllegalStateException) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    e is ForegroundServiceStartNotAllowedException) {
                    handleNotAllowed()
                }
            }
        """.trimIndent()
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/MainActivity.kt",
            body
        ))
        assertNull(RovaGateRules.run("checkFGSStartGuarded", files))
    }

    @Test
    fun fgsStartGuarded_passesWhenNoFgsCall() {
        // No startForegroundService or startForeground → skip entirely
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt",
            "fun doWork() { recordSegment() }"
        ))
        assertNull(RovaGateRules.run("checkFGSStartGuarded", files))
    }

    @Test
    fun fgsStartGuarded_passesWithOptOut() {
        // Opt-out marker suppresses entire file
        val body = """
            // fgs-guard-opt-out: test-only stub
            ctx.startForegroundService(intent)
        """.trimIndent()
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/MainActivity.kt",
            body
        ))
        assertNull(RovaGateRules.run("checkFGSStartGuarded", files))
    }

    @Test
    fun fgsStartGuarded_failsWhenMissingIseCatch() {
        // startForegroundService with no catch at all
        val relPath = "app/src/main/java/com/aritr/rova/MainActivity.kt"
        val body = "ctx.startForegroundService(intent)"
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkFGSStartGuarded", files)
        val expected = "FGS guard violations (ADR 0006 B10 + B11 + B20):\n" +
            "  $relPath: line 1: missing catch (e: IllegalStateException)\n" +
            "Caller-side: catch IllegalStateException, SDK-gate the is-check.\n" +
            "Service-side: also catch SecurityException for FGS-type / permission mismatch."
        assertEquals(expected, msg)
    }

    @Test
    fun fgsStartGuarded_failsWhenCatchNotSdkGated() {
        // ISE catch present but no SDK gate
        val relPath = "app/src/main/java/com/aritr/rova/MainActivity.kt"
        val body = """
            try {
                ctx.startForegroundService(intent)
            } catch (e: IllegalStateException) {
                if (e is ForegroundServiceStartNotAllowedException) { handleNotAllowed() }
            }
        """.trimIndent()
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkFGSStartGuarded", files)
        val expected = "FGS guard violations (ADR 0006 B10 + B11 + B20):\n" +
            "  $relPath: line 2: catch is not SDK-gated (Build.VERSION.SDK_INT >= S)\n" +
            "Caller-side: catch IllegalStateException, SDK-gate the is-check.\n" +
            "Service-side: also catch SecurityException for FGS-type / permission mismatch."
        assertEquals(expected, msg)
    }

    @Test
    fun fgsStartGuarded_failsServiceSideWithoutSecurityCatch() {
        // Service-side startForeground: ISE+SDK+is-check OK, but no SecurityException catch
        val relPath = "app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt"
        val body = """
            try {
                startForeground(NOTIF_ID, notification, fgsType)
            } catch (e: IllegalStateException) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    e is ForegroundServiceStartNotAllowedException) { log(e) }
            }
        """.trimIndent()
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkFGSStartGuarded", files)
        val expected = "FGS guard violations (ADR 0006 B10 + B11 + B20):\n" +
            "  $relPath: line 2: service-side startForeground missing catch (e: SecurityException) (B18 + B20)\n" +
            "Caller-side: catch IllegalStateException, SDK-gate the is-check.\n" +
            "Service-side: also catch SecurityException for FGS-type / permission mismatch."
        assertEquals(expected, msg)
    }

    // ─── hole-close golden tests (CommentStripper migration) ─────────────────

    @Test
    fun receiverCounter_detectsGoAsyncAfterBlockCommentClose() {
        // raw trimStart begins with `*` — legacy startsWith("*") skipped it (false-pass).
        // CommentStripper blanks only `*/`, leaving goAsync() visible on stripped.
        val relPath = "app/src/main/java/com/aritr/rova/service/RovaTickReceiver.kt"
        val body = "        */ val pending = goAsync()"
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkRecoveryReceiverCounter", files)
        assertTrue(msg != null && msg.contains("Guard B violations"))
    }

    @Test
    fun atomicTerminalPair_detectsMarkTerminatedAfterBlockCommentClose() {
        // raw trimStart begins with `*` — legacy false-pass; stripped keeps the call.
        val relPath = "app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt"
        val body = "        */ store.markTerminated(session, Terminated.USER_STOPPED, StopReason.NONE)"
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkAtomicTerminalWriteForbiddenPair", files)
        assertTrue(msg != null && msg.contains("Forbidden pair markTerminated(USER_STOPPED, NONE) detected"))
    }

    @Test
    fun externalRootShared_detectsAfterBlockCommentClose() {
        // raw trimStart begins with `*` — legacy false-pass; stripped keeps the call.
        val relPath = "app/src/main/java/com/aritr/rova/service/export/Tier1Exporter.kt"
        val body = "        */ val dir = context.getExternalFilesDir(null)"
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkExternalRootShared", files)
        assertTrue(msg != null && msg.contains("External-root drift detected"))
    }

    @Test
    fun audioModeFgsType_detectsMicAfterBlockCommentClose() {
        // raw trimStart begins with `*` — legacy false-pass; stripped keeps the literal.
        val relPath = "app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt"
        val body = "        */ startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)"
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkAudioModeFgsTypeMatch", files)
        assertTrue(msg != null && msg.contains("FGS-type / audio-mode coupling violation"))
    }

    @Test
    fun fgsStartGuarded_detectsStartForegroundServiceAfterBlockCommentClose() {
        // raw trimStart begins with `*` — legacy false-pass; stripped keeps the call.
        val relPath = "app/src/main/java/com/aritr/rova/MainActivity.kt"
        val body = "        */ ctx.startForegroundService(intent)"
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkFGSStartGuarded", files)
        assertTrue(msg != null && msg.contains("FGS guard violations"))
    }

    // ─── round-4 require-on-stripped golden tests ────────────────────────────

    @Test
    fun audioModeFgsType_failsWhenAudioModeOnlyInComment() {
        // round-4: audioMode mentioned only in a comment BEFORE the MIC literal must
        // NOT satisfy the require (false-PASS on raw lines[i] before the migration).
        val relPath = "app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt"
        val body = """
            // audioMode gate handled elsewhere
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        """.trimIndent()
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkAudioModeFgsTypeMatch", files)
        val expected = "FGS-type / audio-mode coupling violation (ADR 0006 B18 + B20):\n" +
            "  $relPath: line 2 — FGS MIC type without audioMode gate\n" +
            "FOREGROUND_SERVICE_TYPE_MICROPHONE must be gated by an " +
            "audioMode == AudioMode.VIDEO_AUDIO check. Hardcoding the " +
            "type bitfield risks SecurityException on Android 14+."
        assertEquals(expected, msg)
    }

    @Test
    fun fgsStartGuarded_failsWhenIseCatchOnlyInComment() {
        // round-4: the required ISE catch present only in a comment within the
        // 60-line window must NOT satisfy the require (false-PASS on raw window).
        val relPath = "app/src/main/java/com/aritr/rova/MainActivity.kt"
        val body = """
            ctx.startForegroundService(intent)
            // catch (e: IllegalStateException) is done in the caller
        """.trimIndent()
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkFGSStartGuarded", files)
        val expected = "FGS guard violations (ADR 0006 B10 + B11 + B20):\n" +
            "  $relPath: line 1: missing catch (e: IllegalStateException)\n" +
            "Caller-side: catch IllegalStateException, SDK-gate the is-check.\n" +
            "Service-side: also catch SecurityException for FGS-type / permission mismatch."
        assertEquals(expected, msg)
    }
}

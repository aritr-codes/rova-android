package com.aritr.rova.service.recovery

import com.aritr.rova.service.export.ExportResult
import com.aritr.rova.ui.signals.RecoveryMergeOutcomeSignal
import com.aritr.rova.ui.signals.RecoveryMergeOutcomeSignal.RecoveryMergeOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.ArrayDeque

/**
 * Milestone 2 — integration test for [RecoveryMerger]'s retry loop. Six
 * scenarios verify the contract approved during 2026-05-26 brainstorm:
 * Success first try, Transient×3 exhausted, Transient×2 then Success,
 * Permanent first, InsufficientStorage + poll resume, InsufficientStorage
 * + poll timeout. Spec §7.1.
 *
 * Uses kotlinx.coroutines.runBlocking — project convention; NOT runTest
 * (no kotlinx-coroutines-test dependency).
 */
class RecoveryMergerRetryTest {

    /** Fake signal — captures emissions for assertion. */
    private class CapturingSignal : RecoveryMergeOutcomeSignal() {
        val emittedOutcomes = mutableListOf<Pair<String, RecoveryMergeOutcome>>()
        val emittedProgress = mutableListOf<Pair<String, Float>>()
        override fun emitOutcome(sessionId: String, outcome: RecoveryMergeOutcome) {
            emittedOutcomes += sessionId to outcome
        }
        override fun emitInProgress(sessionId: String, progress: Float) {
            emittedProgress += sessionId to progress
        }
    }

    private val FAKE_FILE = File("/fake/segment.mp4")

    /** Builds a RecoveryMerger with programmable per-attempt ExportResults and storage-poll outcomes.
     *
     *  Task 2.1 fold-in (2026-05-26): pollCapMillis / pollIntervalMillis defaults overridden to 1L
     *  so the waitForStorage loop fast-forwards to a single iteration. Spec §7.2 mandates tests
     *  bounded to <1 second total; production defaults (600_000ms cap / 30_000ms interval) would
     *  have made the poll-timeout test run ~10 minutes of wall time.
     *
     *  Loop math (RecoveryMerger.waitForStorage): `while (elapsed < deadline)` with
     *  `elapsed += pollIntervalMillis`. Both must be > 0 for the loop to (a) execute at least
     *  once and (b) terminate. Using 1L / 1L gives exactly one iteration. */
    private fun buildMerger(
        results: List<ExportResult>,
        pollOutcomes: List<Boolean> = emptyList(),   // true == space freed; false == not yet
        backoffOverride: (Int) -> Long = { 0L },     // fast-forward in tests
        pollCapMillis: Long = 1L,                    // fast-forward: single-iteration poll cap
        pollIntervalMillis: Long = 1L,               // fast-forward: 1ms delay between polls
    ): Pair<RecoveryMerger, CapturingSignal> {
        val signal = CapturingSignal()
        val queue = ArrayDeque(results)
        val pollQueue = ArrayDeque(pollOutcomes)
        val merger = RecoveryMerger(
            loadSegments = { listOf(FAKE_FILE) },
            sessionDirOf = { File("/fake/session_dir") },
            exportRecovered = { _, _, _ ->
                queue.pollFirst() ?: error("test ran out of programmed ExportResults")
            },
            signal = signal,
            backoffMillisOverride = backoffOverride,
            pollForStorage = { _, _ -> pollQueue.pollFirst() ?: false },
            pollCapMillis = pollCapMillis,
            pollIntervalMillis = pollIntervalMillis,
        )
        return merger to signal
    }

    @Test
    fun success_first_try_writes_succeeded() = runBlocking {
        val (merger, signal) = buildMerger(
            results = listOf(ExportResult.Success(mediaScanCompleted = true, privateTempRetained = false))
        )
        val outcome = merger.run("session-1")
        assertEquals(RecoveryMergeOutcome.Succeeded, outcome)
        assertEquals(1, signal.emittedOutcomes.size)
        assertEquals("session-1" to RecoveryMergeOutcome.Succeeded, signal.emittedOutcomes[0])
    }

    @Test
    fun three_transient_failures_exhausts_to_mux_failed() = runBlocking {
        val cause = IllegalStateException("flake")
        val (merger, signal) = buildMerger(
            results = listOf(
                ExportResult.MuxFailed(cause),
                ExportResult.MuxFailed(cause),
                ExportResult.MuxFailed(cause),
            )
        )
        val outcome = merger.run("session-2")
        assertTrue("expected MuxFailed terminal, got $outcome", outcome is RecoveryMergeOutcome.MuxFailed)
    }

    @Test
    fun two_transients_then_success_completes() = runBlocking {
        val cause = IllegalStateException("flake")
        val (merger, signal) = buildMerger(
            results = listOf(
                ExportResult.MuxFailed(cause),
                ExportResult.CopyFailed(cause),
                ExportResult.Success(mediaScanCompleted = true, privateTempRetained = false),
            )
        )
        val outcome = merger.run("session-3")
        assertEquals(RecoveryMergeOutcome.Succeeded, outcome)
    }

    @Test
    fun permanent_failure_no_retry() = runBlocking {
        val (merger, signal) = buildMerger(
            results = listOf(ExportResult.UnknownSession(sessionId = "session-4")),
        )
        val outcome = merger.run("session-4")
        assertEquals(RecoveryMergeOutcome.UnknownSession, outcome)
    }

    @Test
    fun insufficient_storage_then_poll_freed_then_success() = runBlocking {
        val (merger, signal) = buildMerger(
            results = listOf(
                ExportResult.InsufficientStorage(requiredBytes = 100L, availableBytes = 50L),
                ExportResult.Success(mediaScanCompleted = true, privateTempRetained = false),
            ),
            pollOutcomes = listOf(true),
        )
        val outcome = merger.run("session-5")
        assertEquals(RecoveryMergeOutcome.Succeeded, outcome)
    }

    @Test
    fun insufficient_storage_then_poll_timeout_writes_insufficient_storage() = runBlocking {
        val (merger, signal) = buildMerger(
            results = listOf(
                ExportResult.InsufficientStorage(requiredBytes = 100L, availableBytes = 50L),
            ),
            pollOutcomes = listOf(false),   // never freed
        )
        val outcome = merger.run("session-6")
        assertTrue(
            "expected InsufficientStorage terminal, got $outcome",
            outcome is RecoveryMergeOutcome.InsufficientStorage
        )
    }
}

package com.aritr.rova.service.recovery

import com.aritr.rova.service.export.ExportResult
import com.aritr.rova.ui.signals.RecoveryMergeOutcomeSignal
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class RecoveryMergerTest {

    private val tempDir = File.createTempFile("recovery-merger-", "").apply {
        delete(); mkdirs(); deleteOnExit()
    }

    private fun mergerOf(
        segments: List<File> = listOf(File(tempDir, "seg_0.mp4").apply { writeBytes(ByteArray(100)) }),
        sessionDir: File = tempDir,
        export: suspend (List<File>, (Float) -> Unit) -> ExportResult,
        progressCollector: (String, Float) -> Unit = { _, _ -> },
    ): Pair<RecoveryMergeOutcomeSignal, RecoveryMerger> {
        val signal = RecoveryMergeOutcomeSignal()
        val merger = RecoveryMerger(
            loadSegments = { _ -> segments },
            sessionDirOf = { _ -> sessionDir },
            exportRecovered = { _, segs, onProgress -> export(segs, onProgress) },
            signal = signal,
            onProgress = { sid, p ->
                progressCollector(sid, p)
                signal.emitInProgress(sid, p)
            },
        )
        return signal to merger
    }

    @Test
    fun `Success maps to Succeeded outcome`() = runBlocking {
        val (signal, merger) = mergerOf(export = { _, _ -> ExportResult.Success(mediaScanCompleted = true, privateTempRetained = false) })
        val outcome = merger.run("sess-1")
        assertEquals(RecoveryMergeOutcomeSignal.RecoveryMergeOutcome.Succeeded, outcome)
        val s = signal.state.value
        assertTrue(s is RecoveryMergeOutcomeSignal.State.Outcome)
        s as RecoveryMergeOutcomeSignal.State.Outcome
        assertEquals("sess-1", s.sessionId)
    }

    @Test
    fun `InsufficientStorage carries bytes`() = runBlocking {
        val (_, merger) = mergerOf(export = { _, _ -> ExportResult.InsufficientStorage(requiredBytes = 200, availableBytes = 50) })
        val outcome = merger.run("sess-2")
        assertTrue(outcome is RecoveryMergeOutcomeSignal.RecoveryMergeOutcome.InsufficientStorage)
        outcome as RecoveryMergeOutcomeSignal.RecoveryMergeOutcome.InsufficientStorage
        assertEquals(200L, outcome.requiredBytes)
        assertEquals(50L, outcome.availableBytes)
    }

    @Test
    fun `MuxFailed wraps cause`() = runBlocking {
        val cause = RuntimeException("boom")
        val (_, merger) = mergerOf(export = { _, _ -> ExportResult.MuxFailed(cause) })
        val outcome = merger.run("sess-3")
        assertTrue(outcome is RecoveryMergeOutcomeSignal.RecoveryMergeOutcome.MuxFailed)
        outcome as RecoveryMergeOutcomeSignal.RecoveryMergeOutcome.MuxFailed
        assertEquals(cause, outcome.cause)
    }

    @Test
    fun `UnknownSession from ExportPipeline maps to UnknownSession outcome`() = runBlocking {
        val (_, merger) = mergerOf(export = { _, _ -> ExportResult.UnknownSession("sess-4") })
        val outcome = merger.run("sess-4")
        assertEquals(RecoveryMergeOutcomeSignal.RecoveryMergeOutcome.UnknownSession, outcome)
    }

    @Test
    fun `empty segments shortcircuits to UnknownSession`() = runBlocking {
        val (_, merger) = mergerOf(
            segments = emptyList(),
            export = { _, _ -> error("should not be called") },
        )
        val outcome = merger.run("sess-empty")
        assertEquals(RecoveryMergeOutcomeSignal.RecoveryMergeOutcome.UnknownSession, outcome)
    }

    @Test
    fun `progress callback fires for each emission with sessionId`() = runBlocking {
        val captures = mutableListOf<Pair<String, Float>>()
        val (_, merger) = mergerOf(
            export = { _, onProgress ->
                onProgress(0.25f); onProgress(0.5f); onProgress(1f)
                ExportResult.Success(mediaScanCompleted = true, privateTempRetained = false)
            },
            progressCollector = { sid, p -> captures += sid to p },
        )
        merger.run("sess-5")
        assertEquals(
            listOf("sess-5" to 0.25f, "sess-5" to 0.5f, "sess-5" to 1f),
            captures,
        )
    }
}

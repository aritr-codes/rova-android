package com.aritr.rova.service.recovery

import com.aritr.rova.service.export.ExportResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Milestone 2 — pure-JVM cover for [classifyMergeFailure]. Pins the
 * 4-transient / 4-permanent / 1-terminal taxonomy approved during the
 * 2026-05-26 brainstorm. Spec
 * `docs/superpowers/specs/2026-05-26-merge-reliability-bundle-design.md` §5 #1.
 */
class MergeFailureClassTest {

    @Test
    fun success_classifies_as_terminal() {
        val result = ExportResult.Success(mediaScanCompleted = true, privateTempRetained = false)
        assertEquals(MergeFailureClass.Terminal, classifyMergeFailure(result))
    }

    @Test
    fun mux_failed_classifies_as_transient() {
        val cause = IllegalStateException("mux")
        val classified = classifyMergeFailure(ExportResult.MuxFailed(cause))
        assertTrue(classified is MergeFailureClass.Transient)
        assertEquals(cause, ((classified as MergeFailureClass.Transient).cause as ExportResult.MuxFailed).cause)
    }

    @Test
    fun copy_failed_classifies_as_transient() {
        val cause = java.io.IOException("copy")
        assertTrue(classifyMergeFailure(ExportResult.CopyFailed(cause)) is MergeFailureClass.Transient)
    }

    @Test
    fun rename_failed_classifies_as_transient() {
        assertTrue(classifyMergeFailure(ExportResult.RenameFailed) is MergeFailureClass.Transient)
    }

    @Test
    fun insufficient_storage_classifies_as_insufficient_storage() {
        val classified = classifyMergeFailure(
            ExportResult.InsufficientStorage(requiredBytes = 100L, availableBytes = 50L)
        )
        assertTrue(classified is MergeFailureClass.InsufficientStorage)
        assertEquals(100L, (classified as MergeFailureClass.InsufficientStorage).requiredBytes)
    }

    @Test
    fun pending_insert_failed_classifies_as_permanent() {
        assertTrue(classifyMergeFailure(ExportResult.PendingInsertFailed(cause = null)) is MergeFailureClass.Permanent)
    }

    @Test
    fun finalize_failed_classifies_as_permanent() {
        assertTrue(classifyMergeFailure(ExportResult.FinalizeFailed(cause = null)) is MergeFailureClass.Permanent)
    }

    @Test
    fun manifest_write_failed_classifies_as_permanent() {
        val classified = classifyMergeFailure(
            ExportResult.ManifestWriteFailed(phase = "test", cause = RuntimeException("boom"))
        )
        assertTrue(classified is MergeFailureClass.Permanent)
    }

    @Test
    fun unknown_session_classifies_as_permanent() {
        assertTrue(classifyMergeFailure(ExportResult.UnknownSession(sessionId = "abc")) is MergeFailureClass.Permanent)
    }
}

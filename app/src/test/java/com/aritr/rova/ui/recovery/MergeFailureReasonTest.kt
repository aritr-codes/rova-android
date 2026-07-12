package com.aritr.rova.ui.recovery

import com.aritr.rova.R
import com.aritr.rova.service.export.ExportResult
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Trust System V1 — M9. Pins the owner-locked, closed merge-failure reason set (Q6, frozen
 * `docs/design/warnings-recovery.html` §08) and its total, message-free classifier over the
 * TYPED [ExportResult] chain. Every variant → exactly one of four causes; UNKNOWN is the
 * fallback; classification never reads a message string.
 */
class MergeFailureReasonTest {

    private val cause = RuntimeException("io boom")

    // ── The closed set → its @StringRes (frozen §08 reason contract) ──────

    @Test
    fun `each reason carries the frozen copy id`() {
        assertEquals(R.string.recovery_fail_reason_storage, MergeFailureReason.STORAGE.messageRes)
        assertEquals(R.string.recovery_fail_reason_unreadable, MergeFailureReason.UNREADABLE.messageRes)
        assertEquals(R.string.recovery_fail_reason_incomplete, MergeFailureReason.INCOMPLETE.messageRes)
        assertEquals(R.string.recovery_fail_reason_unknown, MergeFailureReason.UNKNOWN.messageRes)
    }

    @Test
    fun `the set is exactly four values`() {
        assertEquals(4, MergeFailureReason.values().size)
    }

    // ── Typed classification (every ExportResult variant) ─────────────────

    @Test
    fun `insufficient storage classifies STORAGE`() {
        assertEquals(
            MergeFailureReason.STORAGE,
            MergeFailureReason.classify(ExportResult.InsufficientStorage(requiredBytes = 100, availableBytes = 10)),
        )
    }

    @Test
    fun `saf folder unavailable classifies STORAGE`() {
        assertEquals(
            MergeFailureReason.STORAGE,
            MergeFailureReason.classify(ExportResult.SafFolderUnavailable(cause)),
        )
    }

    @Test
    fun `copy failure classifies UNREADABLE`() {
        assertEquals(
            MergeFailureReason.UNREADABLE,
            MergeFailureReason.classify(ExportResult.CopyFailed(cause)),
        )
    }

    @Test
    fun `mux failure classifies INCOMPLETE`() {
        assertEquals(
            MergeFailureReason.INCOMPLETE,
            MergeFailureReason.classify(ExportResult.MuxFailed(cause)),
        )
    }

    @Test
    fun `rename failure classifies INCOMPLETE`() {
        assertEquals(
            MergeFailureReason.INCOMPLETE,
            MergeFailureReason.classify(ExportResult.RenameFailed),
        )
    }

    @Test
    fun `pending insert failure classifies INCOMPLETE`() {
        assertEquals(
            MergeFailureReason.INCOMPLETE,
            MergeFailureReason.classify(ExportResult.PendingInsertFailed(cause)),
        )
        // cause is nullable on this variant — still typed, still INCOMPLETE.
        assertEquals(
            MergeFailureReason.INCOMPLETE,
            MergeFailureReason.classify(ExportResult.PendingInsertFailed(null)),
        )
    }

    @Test
    fun `finalize failure classifies INCOMPLETE`() {
        assertEquals(
            MergeFailureReason.INCOMPLETE,
            MergeFailureReason.classify(ExportResult.FinalizeFailed(cause)),
        )
    }

    @Test
    fun `manifest write failure classifies INCOMPLETE`() {
        assertEquals(
            MergeFailureReason.INCOMPLETE,
            MergeFailureReason.classify(ExportResult.ManifestWriteFailed("mux", cause)),
        )
    }

    @Test
    fun `unknown session classifies UNKNOWN`() {
        assertEquals(
            MergeFailureReason.UNKNOWN,
            MergeFailureReason.classify(ExportResult.UnknownSession("sess-x")),
        )
    }

    @Test
    fun `success falls back to UNKNOWN`() {
        // Not a failure and never reaches the failbox; classified defensively to the fallback.
        assertEquals(
            MergeFailureReason.UNKNOWN,
            MergeFailureReason.classify(ExportResult.Success(mediaScanCompleted = true, privateTempRetained = false)),
        )
    }

    // ── Stability: same typed input → same cause, message never consulted ─

    @Test
    fun `classification is stable and ignores the exception message`() {
        val a = MergeFailureReason.classify(ExportResult.MuxFailed(RuntimeException("No space left on device")))
        val b = MergeFailureReason.classify(ExportResult.MuxFailed(RuntimeException("totally different text")))
        // Both are MuxFailed → INCOMPLETE regardless of the (never-inspected) message.
        assertEquals(MergeFailureReason.INCOMPLETE, a)
        assertEquals(a, b)
    }
}

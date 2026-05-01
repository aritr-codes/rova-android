package com.aritr.rova.service.export

import com.aritr.rova.data.ExportState
import com.aritr.rova.data.ExportTier
import com.aritr.rova.data.SessionConfig
import com.aritr.rova.data.SessionManifest
import com.aritr.rova.data.Terminated
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 2 Slice 2.3b — pure tests for [PreQExportRetryRunner].
 *
 * No `Context`, no `MediaScannerConnection`, no real `SessionStore`.
 * The exporter recover() lambdas are fakes that record invocation and
 * return canned [RecoveryResult] values, so the eligibility gate and
 * tier dispatch are exercised end-to-end on the JVM.
 */
class PreQExportRetryRunnerTest {

    private fun manifest(
        tier: ExportTier = ExportTier.TIER2_API26_28,
        exportState: ExportState = ExportState.FINALIZED,
        privateTempPath: String? = "/data/private/seg.private",
        publicTargetPath: String? = "/storage/Movies/Rova/Rova_x.mp4",
        mediaScanCompleted: Boolean = false,
        terminated: Terminated? = Terminated.COMPLETED
    ) = SessionManifest(
        sessionId = "s-${tier.name}-${exportState.name}",
        startedAt = 0L,
        config = SessionConfig(30, 5, "FHD", 4),
        segments = emptyList(),
        exportTier = tier,
        privateTempPath = privateTempPath,
        publicTargetPath = publicTargetPath,
        mediaScanCompleted = mediaScanCompleted,
        exportState = exportState,
        terminated = terminated
    )

    private class Recorder(
        private val canned: RecoveryResult = RecoveryResult.Resumed(
            ExportResult.Success(mediaScanCompleted = true, privateTempRetained = false)
        )
    ) {
        val calls = AtomicInteger(0)
        var lastManifest: SessionManifest? = null
        val lambda: suspend (SessionManifest) -> RecoveryResult = { m ->
            calls.incrementAndGet()
            lastManifest = m
            canned
        }
    }

    private fun runner(t2: Recorder = Recorder(), t3: Recorder = Recorder()) =
        PreQExportRetryRunner(recoverTier2 = t2.lambda, recoverTier3 = t3.lambda)

    // ─── eligibility — happy paths ─────────────────────────────────

    @Test
    fun `Tier 2 FINALIZED with retained pointers and scanCompleted false is Eligible`() {
        val m = manifest(tier = ExportTier.TIER2_API26_28)
        assertSame(RetryEligibility.Eligible, runner().eligibility(m))
    }

    @Test
    fun `Tier 3 FINALIZED with retained pointers and scanCompleted false is Eligible`() {
        val m = manifest(tier = ExportTier.TIER3_API24_25)
        assertSame(RetryEligibility.Eligible, runner().eligibility(m))
    }

    // ─── eligibility — tier rejection ──────────────────────────────

    @Test
    fun `Tier 1 with all other fields perfect is WrongTier`() {
        val m = manifest(tier = ExportTier.TIER1_API29_PLUS)
        assertSame(RetryEligibility.WrongTier, runner().eligibility(m))
    }

    // ─── eligibility — state rejection ─────────────────────────────

    @Test
    fun `FAILED is WrongState even with non-null pointers`() {
        // Defensive: in practice setExportFailed clears pointers, but
        // an artisanal corrupt manifest must still be rejected.
        val m = manifest(exportState = ExportState.FAILED)
        val e = runner().eligibility(m)
        assertEquals(RetryEligibility.WrongState(ExportState.FAILED), e)
    }

    @Test
    fun `MUXING is WrongState`() {
        val m = manifest(exportState = ExportState.MUXING)
        assertEquals(RetryEligibility.WrongState(ExportState.MUXING), runner().eligibility(m))
    }

    @Test
    fun `COPYING is WrongState`() {
        val m = manifest(exportState = ExportState.COPYING)
        assertEquals(RetryEligibility.WrongState(ExportState.COPYING), runner().eligibility(m))
    }

    @Test
    fun `NOT_STARTED is WrongState`() {
        val m = manifest(exportState = ExportState.NOT_STARTED)
        assertEquals(RetryEligibility.WrongState(ExportState.NOT_STARTED), runner().eligibility(m))
    }

    // ─── eligibility — pointer / scan rejection ────────────────────

    @Test
    fun `FINALIZED with privateTempPath null is NoPrivateTemp`() {
        val m = manifest(privateTempPath = null)
        assertSame(RetryEligibility.NoPrivateTemp, runner().eligibility(m))
    }

    @Test
    fun `FINALIZED with publicTargetPath null is NoPublicTarget`() {
        val m = manifest(publicTargetPath = null)
        assertSame(RetryEligibility.NoPublicTarget, runner().eligibility(m))
    }

    @Test
    fun `FINALIZED with mediaScanCompleted true is AlreadyScanned`() {
        val m = manifest(mediaScanCompleted = true)
        assertSame(RetryEligibility.AlreadyScanned, runner().eligibility(m))
    }

    // ─── eligibility — terminated independence ─────────────────────

    @Test
    fun `terminated null does NOT block eligibility at substrate layer`() {
        // UI gating is wire-up's job; substrate stays permissive so
        // tests and future callers can drive recover() without first
        // running the late-terminal pass.
        val m = manifest(terminated = null)
        assertSame(RetryEligibility.Eligible, runner().eligibility(m))
    }

    // ─── eligibility — purity ──────────────────────────────────────

    @Test
    fun `eligibility is pure - same manifest yields same verdict and never invokes lambdas`() {
        val t2 = Recorder()
        val t3 = Recorder()
        val r = runner(t2, t3)
        val m = manifest()
        val v1 = r.eligibility(m)
        val v2 = r.eligibility(m)
        assertSame(v1, v2)
        assertEquals(0, t2.calls.get())
        assertEquals(0, t3.calls.get())
    }

    // ─── retry dispatch — eligible ─────────────────────────────────

    @Test
    fun `Tier 2 retry invokes recoverTier2 exactly once and not recoverTier3`() = runBlocking {
        val t2 = Recorder()
        val t3 = Recorder()
        val m = manifest(tier = ExportTier.TIER2_API26_28)
        val out = runner(t2, t3).retry(m)
        assertEquals(1, t2.calls.get())
        assertEquals(0, t3.calls.get())
        assertSame(m, t2.lastManifest)
        assertTrue(out is RetryOutcome.Ran)
    }

    @Test
    fun `Tier 3 retry invokes recoverTier3 exactly once and not recoverTier2`() = runBlocking {
        val t2 = Recorder()
        val t3 = Recorder()
        val m = manifest(tier = ExportTier.TIER3_API24_25)
        val out = runner(t2, t3).retry(m)
        assertEquals(0, t2.calls.get())
        assertEquals(1, t3.calls.get())
        assertSame(m, t3.lastManifest)
        assertTrue(out is RetryOutcome.Ran)
    }

    @Test
    fun `Ran wraps the exporter RecoveryResult verbatim`() = runBlocking {
        val canned = RecoveryResult.Resumed(
            ExportResult.Success(mediaScanCompleted = false, privateTempRetained = true)
        )
        val t2 = Recorder(canned = canned)
        val out = runner(t2 = t2).retry(manifest(tier = ExportTier.TIER2_API26_28))
        out as RetryOutcome.Ran
        assertSame(canned, out.result)
    }

    @Test
    fun `Ran propagates Abandoned untouched`() = runBlocking {
        val t2 = Recorder(canned = RecoveryResult.Abandoned)
        val out = runner(t2 = t2).retry(manifest(tier = ExportTier.TIER2_API26_28))
        out as RetryOutcome.Ran
        assertSame(RecoveryResult.Abandoned, out.result)
    }

    // ─── retry dispatch — ineligible never invokes lambdas ─────────

    @Test
    fun `Ineligible Tier 1 never invokes either lambda`() = runBlocking {
        val t2 = Recorder()
        val t3 = Recorder()
        val out = runner(t2, t3).retry(manifest(tier = ExportTier.TIER1_API29_PLUS))
        assertEquals(0, t2.calls.get())
        assertEquals(0, t3.calls.get())
        out as RetryOutcome.Ineligible
        assertSame(RetryEligibility.WrongTier, out.reason)
    }

    @Test
    fun `Ineligible FAILED never invokes either lambda`() = runBlocking {
        val t2 = Recorder()
        val t3 = Recorder()
        val out = runner(t2, t3).retry(manifest(exportState = ExportState.FAILED))
        assertEquals(0, t2.calls.get())
        assertEquals(0, t3.calls.get())
        out as RetryOutcome.Ineligible
        assertEquals(RetryEligibility.WrongState(ExportState.FAILED), out.reason)
    }

    @Test
    fun `Ineligible AlreadyScanned never invokes either lambda`() = runBlocking {
        val t2 = Recorder()
        val t3 = Recorder()
        val out = runner(t2, t3).retry(manifest(mediaScanCompleted = true))
        assertEquals(0, t2.calls.get())
        assertEquals(0, t3.calls.get())
        out as RetryOutcome.Ineligible
        assertSame(RetryEligibility.AlreadyScanned, out.reason)
    }

    @Test
    fun `Ineligible NoPrivateTemp never invokes either lambda`() = runBlocking {
        val t2 = Recorder()
        val t3 = Recorder()
        val out = runner(t2, t3).retry(manifest(privateTempPath = null))
        assertEquals(0, t2.calls.get())
        assertEquals(0, t3.calls.get())
        out as RetryOutcome.Ineligible
        assertSame(RetryEligibility.NoPrivateTemp, out.reason)
    }

    @Test
    fun `Ineligible NoPublicTarget never invokes either lambda`() = runBlocking {
        val t2 = Recorder()
        val t3 = Recorder()
        val out = runner(t2, t3).retry(manifest(publicTargetPath = null))
        assertEquals(0, t2.calls.get())
        assertEquals(0, t3.calls.get())
        out as RetryOutcome.Ineligible
        assertSame(RetryEligibility.NoPublicTarget, out.reason)
    }

    // ─── regression guards ─────────────────────────────────────────

    @Test
    fun `regression - WrongState carries the actual blocking state for diagnostics`() {
        val states = listOf(
            ExportState.FAILED,
            ExportState.MUXING,
            ExportState.COPYING,
            ExportState.NOT_STARTED
        )
        for (s in states) {
            val e = runner().eligibility(manifest(exportState = s))
            assertEquals("state=$s", RetryEligibility.WrongState(s), e)
        }
    }

    @Test
    fun `regression - eligibility check order Tier before State before Pointers`() {
        // A Tier 1 manifest in FAILED state with cleared pointers and
        // scan-completed must report WrongTier (the most informative
        // rejection — Tier is structurally unsupported, not just this
        // particular manifest's data).
        val m = manifest(
            tier = ExportTier.TIER1_API29_PLUS,
            exportState = ExportState.FAILED,
            privateTempPath = null,
            publicTargetPath = null,
            mediaScanCompleted = true
        )
        assertSame(RetryEligibility.WrongTier, runner().eligibility(m))
    }

    @Test
    fun `regression - lambda return value not pre-cached - second retry calls lambda again`() = runBlocking {
        val t2 = Recorder()
        val r = runner(t2 = t2)
        val m = manifest(tier = ExportTier.TIER2_API26_28)
        r.retry(m)
        r.retry(m)
        assertEquals(2, t2.calls.get())
    }

    @Test
    fun `regression - sealed RetryOutcome is closed - Ineligible carries reason and nothing else`() {
        // Defensive type check — Ineligible.reason is the sole payload
        // and it is the same type returned by eligibility().
        val out: RetryOutcome = RetryOutcome.Ineligible(RetryEligibility.WrongTier)
        assertTrue(out is RetryOutcome.Ineligible)
        assertNull((out as? RetryOutcome.Ran)?.result)
    }
}

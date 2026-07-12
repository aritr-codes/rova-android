package com.aritr.rova.ui.recovery

import com.aritr.rova.R
import com.aritr.rova.data.ExportTier
import com.aritr.rova.data.SessionConfig
import com.aritr.rova.data.SessionManifest
import com.aritr.rova.data.Terminated
import com.aritr.rova.service.recovery.DiscardEligibility
import com.aritr.rova.service.recovery.RecoveryReport
import com.aritr.rova.service.recovery.SessionClassification
import com.aritr.rova.service.recovery.TerminalAction
import com.aritr.rova.ui.signals.RecoveryMergeOutcomeSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2 Slice 2.1b — pure JVM tests for [RecoveryViewModel].
 *
 * Uses [Dispatchers.Unconfined] so the `combine` collector and the
 * `dismiss` coroutine launch run synchronously on the caller thread.
 * No Main dispatcher rule, no `kotlinx-coroutines-test` dependency,
 * no Robolectric. The mapper itself is exhaustively covered by
 * [RecoveryUiStateMapperTest] and [RecoveryViewSourceTest]; this
 * test focuses on the VM's StateFlow lifecycle and the dismiss
 * pipeline.
 */
class RecoveryViewModelTest {

    private fun manifest(
        sessionId: String,
        terminated: Terminated?,
        terminatedAt: Long? = null
    ) = SessionManifest(
        sessionId = sessionId,
        startedAt = 0L,
        config = SessionConfig(30, 5, "FHD", 4),
        segments = emptyList(),
        exportTier = ExportTier.TIER1_API29_PLUS,
        terminated = terminated,
        terminatedAt = terminatedAt
    )

    private fun classification(
        sessionId: String,
        eligibility: DiscardEligibility = DiscardEligibility.OFFER_DISCARD,
    ) = SessionClassification(
        sessionId = sessionId,
        terminalAction = TerminalAction.ALREADY_TERMINAL,
        eligibility = eligibility,
        anomalies = emptyList(),
        appendedSegmentFilenames = emptyList(),
    )

    private fun report(vararg c: SessionClassification): RecoveryReport =
        RecoveryReport(
            classifications = c.associateBy { it.sessionId },
            scanStartMillis = 1L,
            scanCompletedMillis = 2L,
        )

    private fun newVm(
        report: RecoveryReport?,
        discardSession: suspend (String) -> Unit = {},
        loadManifest: (String) -> SessionManifest?,
    ): Pair<MutableStateFlow<RecoveryReport?>, RecoveryViewModel> {
        // `loadManifest` is the LAST function-type parameter so callers
        // can keep using `newVm(report = ...) { id -> ... }` with a
        // trailing lambda. Tests that need a custom `discardSession`
        // pass it via the named argument before the trailing lambda.
        val flow = MutableStateFlow(report)
        val vm = RecoveryViewModel(
            recoveryReport = flow,
            loadManifest = loadManifest,
            discardSession = discardSession,
            ioDispatcher = Dispatchers.Unconfined,
        )
        return flow to vm
    }

    @Test
    fun `null report emits Empty`() {
        val (_, vm) = newVm(report = null) { null }
        assertEquals(RecoveryUiState.Empty, vm.uiState.value)
    }

    @Test
    fun `single OFFER_DISCARD USER_STOPPED emits one card`() {
        val sid = "s1"
        val (_, vm) = newVm(report = report(classification(sid))) { id ->
            if (id == sid) manifest(sid, Terminated.USER_STOPPED, terminatedAt = 100L) else null
        }
        val cards = vm.uiState.value.cards
        assertEquals(1, cards.size)
        assertEquals(sid, cards.single().sessionId)
        assertEquals(RecoveryCardKind.USER_STOPPED, cards.single().kind)
        assertEquals(0, vm.uiState.value.hiddenCount)
    }

    @Test
    fun `multiple eligible sessions cap to one card and hiddenCount counts the rest`() {
        val (_, vm) = newVm(
            report = report(
                classification("s-user"),
                classification("s-sys"),
                classification("s-fs"),
            ),
        ) { id ->
            when (id) {
                "s-user" -> manifest(id, Terminated.USER_STOPPED, terminatedAt = 100L)
                "s-sys" -> manifest(id, Terminated.KILLED_BY_SYSTEM, terminatedAt = 300L)
                "s-fs" -> manifest(id, Terminated.KILLED_FORCE_STOP, terminatedAt = 200L)
                else -> null
            }
        }
        val ui = vm.uiState.value
        assertEquals(listOf("s-sys"), ui.cards.map { it.sessionId })
        assertEquals(2, ui.hiddenCount)
    }

    @Test
    fun `flow update propagates - null to non-null`() {
        val sid = "s1"
        val (flow, vm) = newVm(report = null) { id ->
            if (id == sid) manifest(sid, Terminated.USER_STOPPED, terminatedAt = 100L) else null
        }
        assertTrue(vm.uiState.value.cards.isEmpty())

        flow.value = report(classification(sid))
        val cards = vm.uiState.value.cards
        assertEquals(1, cards.size)
        assertEquals(sid, cards.single().sessionId)
    }

    @Test
    fun `flow update propagates - non-null to null clears cards`() {
        val sid = "s1"
        val (flow, vm) = newVm(report = report(classification(sid))) { id ->
            if (id == sid) manifest(sid, Terminated.USER_STOPPED, terminatedAt = 100L) else null
        }
        assertEquals(1, vm.uiState.value.cards.size)

        flow.value = null
        assertEquals(RecoveryUiState.Empty, vm.uiState.value)
    }

    @Test
    fun `vendor help slot only true for KILLED_BY_SYSTEM visible card`() {
        val (_, vm) = newVm(
            report = report(classification("s-sys")),
        ) { id ->
            if (id == "s-sys") manifest(id, Terminated.KILLED_BY_SYSTEM, terminatedAt = 100L) else null
        }
        val card = vm.uiState.value.cards.single()
        assertEquals(true, card.showVendorHelpSlot)
    }

    // ─── dismiss ───────────────────────────────────────────────────

    @Test
    fun `dismiss hides the visible card immediately`() {
        val (_, vm) = newVm(
            report = report(classification("s-only")),
        ) { id ->
            if (id == "s-only") manifest(id, Terminated.USER_STOPPED, terminatedAt = 100L) else null
        }
        assertEquals(1, vm.uiState.value.cards.size)

        vm.dismiss("s-only")
        assertTrue(vm.uiState.value.cards.isEmpty())
        assertEquals(0, vm.uiState.value.hiddenCount)
    }

    @Test
    fun `dismiss invokes discardSession exactly once`() {
        val discardCalls = mutableListOf<String>()
        val (_, vm) = newVm(
            report = report(classification("s")),
            loadManifest = { id ->
                if (id == "s") manifest(id, Terminated.USER_STOPPED, terminatedAt = 100L) else null
            },
            discardSession = { id -> discardCalls += id },
        )
        vm.dismiss("s")
        assertEquals(listOf("s"), discardCalls)
    }

    @Test
    fun `dismiss surfaces the next-newest card`() {
        // Three eligible cards. Dismissing the visible (newest) one
        // promotes the next-newest into the visible slot and
        // decrements hiddenCount.
        val (_, vm) = newVm(
            report = report(
                classification("oldest"),
                classification("middle"),
                classification("newest"),
            ),
        ) { id ->
            when (id) {
                "oldest" -> manifest(id, Terminated.USER_STOPPED, terminatedAt = 100L)
                "middle" -> manifest(id, Terminated.USER_STOPPED, terminatedAt = 200L)
                "newest" -> manifest(id, Terminated.USER_STOPPED, terminatedAt = 300L)
                else -> null
            }
        }
        assertEquals(listOf("newest"), vm.uiState.value.cards.map { it.sessionId })
        assertEquals(2, vm.uiState.value.hiddenCount)

        vm.dismiss("newest")
        assertEquals(listOf("middle"), vm.uiState.value.cards.map { it.sessionId })
        assertEquals(1, vm.uiState.value.hiddenCount)

        vm.dismiss("middle")
        assertEquals(listOf("oldest"), vm.uiState.value.cards.map { it.sessionId })
        assertEquals(0, vm.uiState.value.hiddenCount)
    }

    @Test
    fun `dismiss swallows discardSession failures without crashing`() {
        // discardSession is best-effort; an exception must not break
        // the VM's StateFlow nor leak past the dismiss call. The
        // dismissed-IDs filter still hides the card from the UI.
        val (_, vm) = newVm(
            report = report(classification("s")),
            loadManifest = { id ->
                if (id == "s") manifest(id, Terminated.USER_STOPPED, terminatedAt = 100L) else null
            },
            discardSession = { error("disk gone") },
        )
        vm.dismiss("s")
        assertTrue(vm.uiState.value.cards.isEmpty())
    }

    // ─── keepRaw / merge / signal combine ──────────────────────────

    @Test
    fun `keepRaw invokes markKeptRaw seam with sessionId`() = runBlocking {
        val seen = mutableListOf<String>()
        val vm = RecoveryViewModel(
            recoveryReport = MutableStateFlow(null),
            loadManifest = { null },
            markKeptRaw = { id -> seen += id },
            startRecoveryMergeFn = { },
            mergeOutcome = MutableStateFlow(RecoveryMergeOutcomeSignal.State.Idle),
            ioDispatcher = Dispatchers.Unconfined,
        )
        vm.keepRaw("sess-1")
        assertEquals(listOf("sess-1"), seen)
    }

    @Test
    fun `merge invokes startRecoveryMergeFn with sessionId`() = runBlocking {
        val seen = mutableListOf<String>()
        val vm = RecoveryViewModel(
            recoveryReport = MutableStateFlow(null),
            loadManifest = { null },
            markKeptRaw = { },
            startRecoveryMergeFn = { id -> seen += id },
            mergeOutcome = MutableStateFlow(RecoveryMergeOutcomeSignal.State.Idle),
            ioDispatcher = Dispatchers.Unconfined,
        )
        vm.merge("sess-2")
        assertEquals(listOf("sess-2"), seen)
    }

    @Test
    fun `InProgress outcome flows into mergeInProgress on the matching card`() = runBlocking {
        val sid = "sess-3"
        val recoveryReport = report(classification(sid))
        val signal = MutableStateFlow<RecoveryMergeOutcomeSignal.State>(
            RecoveryMergeOutcomeSignal.State.InProgress(sid, 0.42f)
        )
        val vm = RecoveryViewModel(
            recoveryReport = MutableStateFlow(recoveryReport),
            loadManifest = { id ->
                if (id == sid) manifest(sid, Terminated.USER_STOPPED, terminatedAt = 100L) else null
            },
            markKeptRaw = { },
            startRecoveryMergeFn = { },
            mergeOutcome = signal,
            ioDispatcher = Dispatchers.Unconfined,
        )
        val card = vm.uiState.value.cards.single()
        assertEquals(0.42f, card.mergeInProgress!!, 0f)
    }

    @Test
    fun `InProgress for a different session does not affect this card`() = runBlocking {
        val sid = "sess-A"
        val recoveryReport = report(classification(sid))
        val signal = MutableStateFlow<RecoveryMergeOutcomeSignal.State>(
            RecoveryMergeOutcomeSignal.State.InProgress("sess-OTHER", 0.7f)
        )
        val vm = RecoveryViewModel(
            recoveryReport = MutableStateFlow(recoveryReport),
            loadManifest = { id ->
                if (id == sid) manifest(sid, Terminated.USER_STOPPED, terminatedAt = 100L) else null
            },
            markKeptRaw = { },
            startRecoveryMergeFn = { },
            mergeOutcome = signal,
            ioDispatcher = Dispatchers.Unconfined,
        )
        assertNull(vm.uiState.value.cards.single().mergeInProgress)
    }

    @Test
    fun `MuxFailed outcome surfaces mergeFailedReason on the matching card`() = runBlocking {
        val sid = "sess-fail"
        val recoveryReport = report(classification(sid))
        val signal = MutableStateFlow<RecoveryMergeOutcomeSignal.State>(
            RecoveryMergeOutcomeSignal.State.Outcome(
                sessionId = sid,
                outcome = RecoveryMergeOutcomeSignal.RecoveryMergeOutcome.MuxFailed(
                    RuntimeException("encoder"), MergeFailureReason.INCOMPLETE,
                ),
            )
        )
        val vm = RecoveryViewModel(
            recoveryReport = MutableStateFlow(recoveryReport),
            loadManifest = { id ->
                if (id == sid) manifest(sid, Terminated.USER_STOPPED, terminatedAt = 100L) else null
            },
            markKeptRaw = { },
            startRecoveryMergeFn = { },
            mergeOutcome = signal,
            ioDispatcher = Dispatchers.Unconfined,
        )
        val card = vm.uiState.value.cards.single()
        assertNotNull(card.mergeFailedReason)
    }

    @Test
    fun `MuxFailed outcome carries the classified reason res onto the card`() = runBlocking {
        // M9 — the outcome's owner-locked MergeFailureReason maps to the failbox @StringRes;
        // the raw message stays only for logs.
        val sid = "sess-fail-res"
        val recoveryReport = report(classification(sid))
        val signal = MutableStateFlow<RecoveryMergeOutcomeSignal.State>(
            RecoveryMergeOutcomeSignal.State.Outcome(
                sessionId = sid,
                outcome = RecoveryMergeOutcomeSignal.RecoveryMergeOutcome.MuxFailed(
                    RuntimeException("java.io.IOException: raw text that must never reach the UI"),
                    MergeFailureReason.UNREADABLE,
                ),
            )
        )
        val vm = RecoveryViewModel(
            recoveryReport = MutableStateFlow(recoveryReport),
            loadManifest = { id ->
                if (id == sid) manifest(sid, Terminated.USER_STOPPED, terminatedAt = 100L) else null
            },
            markKeptRaw = { },
            startRecoveryMergeFn = { },
            mergeOutcome = signal,
            ioDispatcher = Dispatchers.Unconfined,
        )
        val card = vm.uiState.value.cards.single()
        assertEquals(R.string.recovery_fail_reason_unreadable, card.mergeFailedReasonRes)
    }
}

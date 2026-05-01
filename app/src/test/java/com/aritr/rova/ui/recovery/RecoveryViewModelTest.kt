package com.aritr.rova.ui.recovery

import com.aritr.rova.data.ExportTier
import com.aritr.rova.data.SessionConfig
import com.aritr.rova.data.SessionManifest
import com.aritr.rova.data.Terminated
import com.aritr.rova.service.recovery.DiscardEligibility
import com.aritr.rova.service.recovery.RecoveryReport
import com.aritr.rova.service.recovery.SessionClassification
import com.aritr.rova.service.recovery.TerminalAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2 Slice 2.1b — pure JVM tests for [RecoveryViewModel].
 *
 * Uses [Dispatchers.Unconfined] so the `stateIn` collector runs
 * synchronously on the caller thread. No Main dispatcher rule, no
 * `kotlinx-coroutines-test` dependency, no Robolectric. The mapper
 * itself is exhaustively covered by [RecoveryUiStateMapperTest] and
 * [RecoveryViewSourceTest]; this test focuses on the VM's StateFlow
 * lifecycle: construction, initial value, and downstream propagation
 * when the upstream flow emits.
 */
class RecoveryViewModelTest {

    private fun manifest(sessionId: String, terminated: Terminated?) = SessionManifest(
        sessionId = sessionId,
        startedAt = 0L,
        config = SessionConfig(30, 5, "FHD", 4),
        segments = emptyList(),
        exportTier = ExportTier.TIER1_API29_PLUS,
        terminated = terminated,
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
        loadManifest: (String) -> SessionManifest?,
    ): Pair<MutableStateFlow<RecoveryReport?>, RecoveryViewModel> {
        val flow = MutableStateFlow(report)
        val vm = RecoveryViewModel(
            recoveryReport = flow,
            loadManifest = loadManifest,
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
            if (id == sid) manifest(sid, Terminated.USER_STOPPED) else null
        }
        val cards = vm.uiState.value.cards
        assertEquals(1, cards.size)
        assertEquals(sid, cards.single().sessionId)
        assertEquals(RecoveryCardKind.USER_STOPPED, cards.single().kind)
    }

    @Test
    fun `mixed terminators - only USER_STOPPED, KILLED_BY_SYSTEM, KILLED_FORCE_STOP rendered, in input order`() {
        val (_, vm) = newVm(
            report = report(
                classification("s-user"),
                classification("s-sys"),
                classification("s-fs"),
                classification("s-done"),
                classification("s-null"),
                classification("s-auto", DiscardEligibility.AUTO_DISCARD_ELIGIBLE),
                classification("s-blocked", DiscardEligibility.BLOCKED),
            ),
        ) { id ->
            when (id) {
                "s-user" -> manifest(id, Terminated.USER_STOPPED)
                "s-sys" -> manifest(id, Terminated.KILLED_BY_SYSTEM)
                "s-fs" -> manifest(id, Terminated.KILLED_FORCE_STOP)
                "s-done" -> manifest(id, Terminated.COMPLETED)
                "s-null" -> manifest(id, terminated = null)
                "s-auto" -> manifest(id, Terminated.KILLED_BY_SYSTEM)
                "s-blocked" -> manifest(id, Terminated.USER_STOPPED)
                else -> null
            }
        }
        val cards = vm.uiState.value.cards
        assertEquals(listOf("s-user", "s-sys", "s-fs"), cards.map { it.sessionId })
        assertEquals(
            listOf(
                RecoveryCardKind.USER_STOPPED,
                RecoveryCardKind.KILLED_BY_SYSTEM,
                RecoveryCardKind.KILLED_FORCE_STOP,
            ),
            cards.map { it.kind },
        )
    }

    @Test
    fun `flow update propagates - null to non-null`() {
        val sid = "s1"
        val (flow, vm) = newVm(report = null) { id ->
            if (id == sid) manifest(sid, Terminated.USER_STOPPED) else null
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
            if (id == sid) manifest(sid, Terminated.USER_STOPPED) else null
        }
        assertEquals(1, vm.uiState.value.cards.size)

        flow.value = null
        assertEquals(RecoveryUiState.Empty, vm.uiState.value)
    }

    @Test
    fun `vendor help slot only true for KILLED_BY_SYSTEM`() {
        val (_, vm) = newVm(
            report = report(
                classification("s-user"),
                classification("s-sys"),
                classification("s-fs"),
            ),
        ) { id ->
            when (id) {
                "s-user" -> manifest(id, Terminated.USER_STOPPED)
                "s-sys" -> manifest(id, Terminated.KILLED_BY_SYSTEM)
                "s-fs" -> manifest(id, Terminated.KILLED_FORCE_STOP)
                else -> null
            }
        }
        val cards = vm.uiState.value.cards
        assertEquals(3, cards.size)
        assertEquals(false, cards[0].showVendorHelpSlot)
        assertEquals(true, cards[1].showVendorHelpSlot)
        assertEquals(false, cards[2].showVendorHelpSlot)
    }
}

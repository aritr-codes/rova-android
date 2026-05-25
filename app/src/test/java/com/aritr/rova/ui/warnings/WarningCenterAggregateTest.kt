package com.aritr.rova.ui.warnings

import com.aritr.rova.data.StopReason
import com.aritr.rova.ui.signals.CameraSignalState
import com.aritr.rova.ui.signals.PowerState
import com.aritr.rova.ui.signals.RecoveryMergeOutcomeSignal
import com.aritr.rova.ui.signals.ThermalStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests [WarningCenterViewModel.aggregate] — the combine logic — with fake
 * [MutableStateFlow]s and no `viewModelScope`. Same plain-JVM coroutine
 * idiom as the Phase 3 signal tests. 12 source flows after Phase 4.3.
 */
class WarningCenterAggregateTest {

    private fun clearPower() = PowerState(percent = 80, charging = false, powerSaveMode = false)

    private data class TwelveSources(
        val cameraPerm: MutableStateFlow<Boolean>,
        val ea: MutableStateFlow<Boolean>,
        val storage: MutableStateFlow<Boolean>,
        val th: MutableStateFlow<ThermalStatus>,
        val pw: MutableStateFlow<PowerState>,
        val camState: MutableStateFlow<CameraSignalState>,
        val mic: MutableStateFlow<Boolean>,
        val nt: MutableStateFlow<Boolean>,
        val bo: MutableStateFlow<Boolean>,
        val storageLowMidRec: MutableStateFlow<Boolean>,
        val autoStopEcho: MutableStateFlow<TerminalEcho?>,
        val cantMergeActive: MutableStateFlow<Boolean>,           // ← NEW (Phase 4.3)
    )

    private fun sources() = TwelveSources(
        MutableStateFlow(true),
        MutableStateFlow(true),
        MutableStateFlow(false),
        MutableStateFlow(ThermalStatus.NONE),
        MutableStateFlow(clearPower()),
        MutableStateFlow(CameraSignalState.OK),
        MutableStateFlow(true),
        MutableStateFlow(true),
        MutableStateFlow(true),
        MutableStateFlow(false),
        MutableStateFlow<TerminalEcho?>(null),
        MutableStateFlow(false),                                  // ← NEW (Phase 4.3)
    )

    private suspend fun TwelveSources.collectInto(emissions: MutableList<WarningId?>) =
        WarningCenterViewModel.aggregate(
            cameraPerm, ea, storage, th, pw, camState, mic, nt, bo, storageLowMidRec, autoStopEcho,
            cantMergeActive,                                      // ← NEW (Phase 4.3)
        ).collect { emissions += it }

    @Test fun `emits null when all sources are clear`() = runBlocking {
        val s = sources()
        val emissions = mutableListOf<WarningId?>()
        val job = launch(Dispatchers.Unconfined) { s.collectInto(emissions) }
        yield()
        job.cancelAndJoin()
        assertEquals(listOf<WarningId?>(null), emissions)
    }

    @Test fun `re-emits when a source flips and clears`() = runBlocking {
        val s = sources()
        val emissions = mutableListOf<WarningId?>()
        val job = launch(Dispatchers.Unconfined) { s.collectInto(emissions) }
        yield()
        s.ea.value = false   // exact-alarm revoked => #2
        yield()
        s.ea.value = true    // restored => null
        yield()
        job.cancelAndJoin()
        assertEquals(listOf<WarningId?>(null, WarningId.EXACT_ALARM_DENIED, null), emissions)
    }

    @Test fun `higher-priority source wins over a lower one`() = runBlocking {
        val s = sources()
        s.nt.value = false   // notifications off (#16)
        s.bo.value = false   // battery-opt not exempt (#13)
        val emissions = mutableListOf<WarningId?>()
        val job = launch(Dispatchers.Unconfined) { s.collectInto(emissions) }
        yield()
        job.cancelAndJoin()
        // #13 BATTERY_OPTIMIZATION_ON outranks #16 NOTIFICATIONS_DENIED
        assertEquals(listOf<WarningId?>(WarningId.BATTERY_OPTIMIZATION_ON), emissions)
    }

    @Test fun `camera-permission denial raises the top warning and clears`() = runBlocking {
        val s = sources()
        val emissions = mutableListOf<WarningId?>()
        val job = launch(Dispatchers.Unconfined) { s.collectInto(emissions) }
        yield()
        s.cameraPerm.value = false   // #1 — outranks everything
        yield()
        s.cameraPerm.value = true
        yield()
        job.cancelAndJoin()
        assertEquals(listOf<WarningId?>(null, WarningId.CAMERA_PERMISSION_DENIED, null), emissions)
    }

    @Test fun `storage-low-mid-rec flow flip emits id`() = runBlocking {
        val s = sources()
        val emissions = mutableListOf<WarningId?>()
        val job = launch(Dispatchers.Unconfined) { s.collectInto(emissions) }
        yield()
        s.storageLowMidRec.value = true
        yield()
        s.storageLowMidRec.value = false
        yield()
        job.cancelAndJoin()
        assertEquals(listOf<WarningId?>(null, WarningId.STORAGE_LOW_MID_REC, null), emissions)
    }

    @Test fun `battery-low outranks storage-low-mid-rec`() = runBlocking {
        val s = sources()
        s.pw.value = PowerState(percent = 14, charging = false, powerSaveMode = false)   // would fire BATTERY_LOW
        s.storageLowMidRec.value = true                                                  // would also fire — but lower priority
        val emissions = mutableListOf<WarningId?>()
        val job = launch(Dispatchers.Unconfined) { s.collectInto(emissions) }
        yield()
        job.cancelAndJoin()
        assertEquals(listOf<WarningId?>(WarningId.BATTERY_LOW), emissions)
    }

    // ──────────────────────────────────────────────────────────────────
    // v3 — Task A7: expandedWhy + snoozeForever VM-level behaviour
    // ──────────────────────────────────────────────────────────────────

    /**
     * Construct a [WarningCenterViewModel] with `Dispatchers.Unconfined` so
     * `stateIn`-backed flows resolve synchronously on the caller thread —
     * the same posture as `RecoveryViewModelTest`. We inject a custom
     * `CoroutineScope` because `viewModelScope` defaults to
     * `Dispatchers.Main.immediate`, which is unavailable in plain-JVM
     * unit tests (no `kotlinx-coroutines-test` dependency in this module).
     */
    private fun makeVm(
        notificationsGranted: Boolean = true,
        storageInsufficient: Boolean = false,
        microphonePermissionGranted: Boolean = true,
        batteryOptimizationExempt: Boolean = true,
        autoStopEcho: TerminalEcho? = null,
        initialSnoozedIds: Set<WarningId> = emptySet(),
        recoveryMergeOutcomeSignal: MutableStateFlow<RecoveryMergeOutcomeSignal.State> =
            MutableStateFlow(RecoveryMergeOutcomeSignal.State.Idle),
    ): WarningCenterViewModel {
        val s = sources()
        s.nt.value = notificationsGranted
        s.storage.value = storageInsufficient
        s.mic.value = microphonePermissionGranted
        s.bo.value = batteryOptimizationExempt
        s.autoStopEcho.value = autoStopEcho
        return WarningCenterViewModel(
            cameraPermissionGranted = s.cameraPerm,
            exactAlarmGranted = s.ea,
            storageInsufficient = s.storage,
            thermal = s.th,
            power = s.pw,
            camera = s.camState,
            microphonePermissionGranted = s.mic,
            notificationsGranted = s.nt,
            batteryOptimizationExempt = s.bo,
            storageLowMidRec = s.storageLowMidRec,
            autoStopEcho = s.autoStopEcho,
            recoveryMergeOutcomeSignal = recoveryMergeOutcomeSignal,  // ← NEW (Phase 4.3)
            scope = CoroutineScope(Dispatchers.Unconfined),
            initialSnoozedIds = initialSnoozedIds,
        )
    }

    @Test
    fun toggleExpandWhy_adds_and_removes_from_set() {
        val vm = makeVm()
        assertTrue(WarningId.NOTIFICATIONS_DENIED !in vm.expandedWhy.value)
        vm.toggleExpandWhy(WarningId.NOTIFICATIONS_DENIED)
        assertTrue(WarningId.NOTIFICATIONS_DENIED in vm.expandedWhy.value)
        vm.toggleExpandWhy(WarningId.NOTIFICATIONS_DENIED)
        assertTrue(WarningId.NOTIFICATIONS_DENIED !in vm.expandedWhy.value)
    }

    @Test
    fun snoozeForever_hides_id_from_activeWarning() {
        // notificationsGranted = false drives NOTIFICATIONS_DENIED into _resolvedWarning;
        // snoozeForever should then filter it from the public activeWarning.
        val vm = makeVm(notificationsGranted = false)
        assertEquals(WarningId.NOTIFICATIONS_DENIED, vm.activeWarning.value)
        vm.snoozeForever(WarningId.NOTIFICATIONS_DENIED)
        assertNull(vm.activeWarning.value)
    }

    // ──────────────────────────────────────────────────────────────────
    // Phase 4.1c — initialSnoozedIds seed + onSnoozeChanged callback +
    //              clearSnoozes mutator
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun vm_seeded_with_initialSnoozedIds_filters_those_ids_from_activeWarning() {
        // Drive NOTIFICATIONS_DENIED into _resolvedWarning (notificationsGranted = false),
        // and seed snoozedForever with the same id. activeWarning must be null.
        val s = sources()
        s.nt.value = false
        val vm = WarningCenterViewModel(
            cameraPermissionGranted = s.cameraPerm,
            exactAlarmGranted = s.ea,
            storageInsufficient = s.storage,
            thermal = s.th,
            power = s.pw,
            camera = s.camState,
            microphonePermissionGranted = s.mic,
            notificationsGranted = s.nt,
            batteryOptimizationExempt = s.bo,
            storageLowMidRec = s.storageLowMidRec,
            autoStopEcho = s.autoStopEcho,
            scope = CoroutineScope(Dispatchers.Unconfined),
            initialSnoozedIds = setOf(WarningId.NOTIFICATIONS_DENIED),
        )
        assertNull(vm.activeWarning.value)
        assertTrue(WarningId.NOTIFICATIONS_DENIED in vm.snoozedForever.value)
    }

    @Test
    fun clearSnoozes_empties_the_snoozedForever_flow_and_invokes_callback() {
        val s = sources()
        s.nt.value = false
        val received = mutableListOf<Set<WarningId>>()
        val vm = WarningCenterViewModel(
            cameraPermissionGranted = s.cameraPerm,
            exactAlarmGranted = s.ea,
            storageInsufficient = s.storage,
            thermal = s.th,
            power = s.pw,
            camera = s.camState,
            microphonePermissionGranted = s.mic,
            notificationsGranted = s.nt,
            batteryOptimizationExempt = s.bo,
            storageLowMidRec = s.storageLowMidRec,
            autoStopEcho = s.autoStopEcho,
            scope = CoroutineScope(Dispatchers.Unconfined),
            initialSnoozedIds = setOf(WarningId.NOTIFICATIONS_DENIED),
            onSnoozeChanged = { received += it },
        )
        vm.clearSnoozes()
        assertEquals(emptySet<WarningId>(), vm.snoozedForever.value)
        assertEquals(listOf(emptySet<WarningId>()), received)
    }

    @Test
    fun snoozeForever_invokes_callback_with_updated_set() {
        val s = sources()
        val received = mutableListOf<Set<WarningId>>()
        val vm = WarningCenterViewModel(
            cameraPermissionGranted = s.cameraPerm,
            exactAlarmGranted = s.ea,
            storageInsufficient = s.storage,
            thermal = s.th,
            power = s.pw,
            camera = s.camState,
            microphonePermissionGranted = s.mic,
            notificationsGranted = s.nt,
            batteryOptimizationExempt = s.bo,
            storageLowMidRec = s.storageLowMidRec,
            autoStopEcho = s.autoStopEcho,
            scope = CoroutineScope(Dispatchers.Unconfined),
            onSnoozeChanged = { received += it },
        )
        vm.snoozeForever(WarningId.NOTIFICATIONS_DENIED)
        assertEquals(listOf(setOf(WarningId.NOTIFICATIONS_DENIED)), received)
    }

    // ──────────────────────────────────────────────────────────────────
    // Phase 4 Slice 2 — autoStopEcho source + dismissAutoStopEcho mutator
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun autoStopEcho_source_flow_drives_STORAGE_FULL_AUTOSTOPPED_into_activeWarning() {
        val s = sources()
        s.autoStopEcho.value = TerminalEcho("session-a", StopReason.LOW_STORAGE)
        val vm = WarningCenterViewModel(
            cameraPermissionGranted = s.cameraPerm,
            exactAlarmGranted = s.ea,
            storageInsufficient = s.storage,
            thermal = s.th,
            power = s.pw,
            camera = s.camState,
            microphonePermissionGranted = s.mic,
            notificationsGranted = s.nt,
            batteryOptimizationExempt = s.bo,
            storageLowMidRec = s.storageLowMidRec,
            autoStopEcho = s.autoStopEcho,
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        assertEquals(WarningId.STORAGE_FULL_AUTOSTOPPED, vm.activeWarning.value)
    }

    @Test
    fun dismissAutoStopEcho_mutator_invokes_onAutoStopDismissed_callback_with_sessionId() {
        val s = sources()
        val received = mutableListOf<String>()
        val vm = WarningCenterViewModel(
            cameraPermissionGranted = s.cameraPerm,
            exactAlarmGranted = s.ea,
            storageInsufficient = s.storage,
            thermal = s.th,
            power = s.pw,
            camera = s.camState,
            microphonePermissionGranted = s.mic,
            notificationsGranted = s.nt,
            batteryOptimizationExempt = s.bo,
            storageLowMidRec = s.storageLowMidRec,
            autoStopEcho = s.autoStopEcho,
            scope = CoroutineScope(Dispatchers.Unconfined),
            onAutoStopDismissed = { received += it },
        )
        vm.dismissAutoStopEcho("session-xyz")
        assertEquals(listOf("session-xyz"), received)
    }

    // ──────────────────────────────────────────────────────────────────
    // Phase 4 Slice 3 — THERMAL_AUTOSTOPPED aggregate
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun autoStopEcho_source_flow_drives_THERMAL_AUTOSTOPPED_into_activeWarning() {
        val s = sources()
        s.autoStopEcho.value = TerminalEcho("session-t", StopReason.THERMAL)
        val vm = WarningCenterViewModel(
            cameraPermissionGranted = s.cameraPerm,
            exactAlarmGranted = s.ea,
            storageInsufficient = s.storage,
            thermal = s.th,
            power = s.pw,
            camera = s.camState,
            microphonePermissionGranted = s.mic,
            notificationsGranted = s.nt,
            batteryOptimizationExempt = s.bo,
            storageLowMidRec = s.storageLowMidRec,
            autoStopEcho = s.autoStopEcho,
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        assertEquals(WarningId.THERMAL_AUTOSTOPPED, vm.activeWarning.value)
    }

    @Test
    fun autoStopEcho_LOW_STORAGE_still_drives_STORAGE_FULL_AUTOSTOPPED_after_when_rewrite() {
        // Regression for the Slice-2 echo behavior after T4's when-arm rewrite.
        // Distinct test from the Slice-2 one above so a failure points at the
        // rewrite, not the original Slice-2 wiring.
        val s = sources()
        s.autoStopEcho.value = TerminalEcho("session-s2", StopReason.LOW_STORAGE)
        val vm = WarningCenterViewModel(
            cameraPermissionGranted = s.cameraPerm,
            exactAlarmGranted = s.ea,
            storageInsufficient = s.storage,
            thermal = s.th,
            power = s.pw,
            camera = s.camState,
            microphonePermissionGranted = s.mic,
            notificationsGranted = s.nt,
            batteryOptimizationExempt = s.bo,
            storageLowMidRec = s.storageLowMidRec,
            autoStopEcho = s.autoStopEcho,
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        assertEquals(WarningId.STORAGE_FULL_AUTOSTOPPED, vm.activeWarning.value)
    }

    // ──────────────────────────────────────────────────────────────────
    // Phase 4.3 — CANT_MERGE aggregate + pendingCantMergeSessionId
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun recoveryMergeOutcomeSignal_InsufficientStorage_drives_CANT_MERGE_into_activeWarning() {
        val signalFlow = MutableStateFlow<RecoveryMergeOutcomeSignal.State>(
            RecoveryMergeOutcomeSignal.State.Outcome(
                sessionId = "session-cm",
                outcome = RecoveryMergeOutcomeSignal.RecoveryMergeOutcome.InsufficientStorage(
                    requiredBytes = 100L,
                    availableBytes = 10L,
                ),
            )
        )
        val vm = makeVm(recoveryMergeOutcomeSignal = signalFlow)
        assertEquals(WarningId.CANT_MERGE, vm.activeWarning.value)
        assertEquals("session-cm", vm.pendingCantMergeSessionId.value)
    }

    @Test
    fun recoveryMergeOutcomeSignal_Idle_yields_null_pendingCantMergeSessionId() {
        val vm = makeVm()
        assertNull(vm.pendingCantMergeSessionId.value)
    }

    @Test
    fun recoveryMergeOutcomeSignal_Succeeded_does_not_drive_CANT_MERGE() {
        val signalFlow = MutableStateFlow<RecoveryMergeOutcomeSignal.State>(
            RecoveryMergeOutcomeSignal.State.Outcome(
                sessionId = "session-ok",
                outcome = RecoveryMergeOutcomeSignal.RecoveryMergeOutcome.Succeeded,
            )
        )
        val vm = makeVm(recoveryMergeOutcomeSignal = signalFlow)
        assertNull(vm.activeWarning.value)
        assertNull(vm.pendingCantMergeSessionId.value)
    }

    // ──────────────────────────────────────────────────────────────────
    // Phase 4.2 — activeWarningsFor(screen) per-surface filtering
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `activeWarningsFor History returns ordinal-sorted history-allowlist IDs only`() {
        val vm = makeVm(
            storageInsufficient = true,          // history allowlist (#3)
            notificationsGranted = false,         // history allowlist (#20)
            microphonePermissionGranted = false,  // NOT in history allowlist (#16)
        )
        assertEquals(
            listOf(WarningId.STORAGE_INSUFFICIENT, WarningId.NOTIFICATIONS_DENIED),
            vm.activeWarningsFor(WarningScreen.History).value,
        )
    }

    @Test
    fun `activeWarningsFor Settings returns ordinal-sorted settings-allowlist IDs only`() {
        val vm = makeVm(
            microphonePermissionGranted = false,  // settings allowlist (#16)
            batteryOptimizationExempt = false,    // settings allowlist (#17)
            storageInsufficient = true,           // settings allowlist (#3 — overlaps history)
            autoStopEcho = TerminalEcho("s1", StopReason.LOW_STORAGE),  // NOT in settings allowlist (#12)
        )
        assertEquals(
            listOf(
                WarningId.STORAGE_INSUFFICIENT,
                WarningId.MICROPHONE_DENIED,
                WarningId.BATTERY_OPTIMIZATION_ON,
            ),
            vm.activeWarningsFor(WarningScreen.Settings).value,
        )
    }

    @Test
    fun `activeWarningsFor excludes snoozed IDs`() {
        val vm = makeVm(
            notificationsGranted = false,
            initialSnoozedIds = setOf(WarningId.NOTIFICATIONS_DENIED),
        )
        assertTrue(
            vm.activeWarningsFor(WarningScreen.History).value
                .none { it == WarningId.NOTIFICATIONS_DENIED }
        )
    }

    @Test
    fun `activeWarningsFor Record returns empty list — Record uses activeWarning`() {
        val vm = makeVm(storageInsufficient = true)
        assertEquals(
            emptyList<WarningId>(),
            vm.activeWarningsFor(WarningScreen.Record).value,
        )
        // Record still reads the single-active StateFlow:
        assertEquals(WarningId.STORAGE_INSUFFICIENT, vm.activeWarning.value)
    }
}

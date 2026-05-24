package com.aritr.rova.ui.warnings

import com.aritr.rova.ui.signals.CameraSignalState
import com.aritr.rova.ui.signals.PowerState
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
 * idiom as the Phase 3 signal tests. 10 source flows after R2 T5.
 */
class WarningCenterAggregateTest {

    private fun clearPower() = PowerState(percent = 80, charging = false, powerSaveMode = false)

    private data class TenSources(
        val cameraPerm: MutableStateFlow<Boolean>,   // cameraPermissionGranted
        val ea: MutableStateFlow<Boolean>,           // exactAlarmGranted
        val storage: MutableStateFlow<Boolean>,      // storageInsufficient
        val th: MutableStateFlow<ThermalStatus>,     // thermal
        val pw: MutableStateFlow<PowerState>,        // power
        val camState: MutableStateFlow<CameraSignalState>, // camera (state)
        val mic: MutableStateFlow<Boolean>,          // microphonePermissionGranted
        val nt: MutableStateFlow<Boolean>,           // notificationsGranted
        val bo: MutableStateFlow<Boolean>,           // batteryOptimizationExempt
        val storageLowMidRec: MutableStateFlow<Boolean>,  // storageLowMidRec — NEW
    )

    private fun sources() = TenSources(
        MutableStateFlow(true),                       // cameraPerm — granted
        MutableStateFlow(true),                       // ea — exact alarm granted
        MutableStateFlow(false),                      // storage — not insufficient
        MutableStateFlow(ThermalStatus.NONE),         // thermal
        MutableStateFlow(clearPower()),               // power
        MutableStateFlow(CameraSignalState.OK),       // camera state
        MutableStateFlow(true),                       // mic — granted
        MutableStateFlow(true),                       // notificationsGranted
        MutableStateFlow(true),                       // batteryOptimizationExempt
        MutableStateFlow(false),                      // storageLowMidRec — NEW
    )

    private suspend fun TenSources.collectInto(emissions: MutableList<WarningId?>) =
        WarningCenterViewModel.aggregate(
            cameraPerm, ea, storage, th, pw, camState, mic, nt, bo, storageLowMidRec,
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
    private fun makeVm(notificationsGranted: Boolean = true): WarningCenterViewModel {
        val s = sources()
        s.nt.value = notificationsGranted
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
            scope = CoroutineScope(Dispatchers.Unconfined),
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
}

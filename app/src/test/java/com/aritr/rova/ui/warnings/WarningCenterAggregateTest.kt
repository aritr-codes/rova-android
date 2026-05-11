package com.aritr.rova.ui.warnings

import com.aritr.rova.ui.signals.CameraSignalState
import com.aritr.rova.ui.signals.PowerState
import com.aritr.rova.ui.signals.ThermalStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests [WarningCenterViewModel.aggregate] — the combine logic — with fake
 * [MutableStateFlow]s and no `viewModelScope`. Same plain-JVM coroutine
 * idiom as the Phase 3 signal tests. 9 source flows after Phase 4.1b.
 */
class WarningCenterAggregateTest {

    private fun clearPower() = PowerState(percent = 80, charging = false, powerSaveMode = false)

    private data class NineSources(
        val cameraPerm: MutableStateFlow<Boolean>,   // cameraPermissionGranted
        val ea: MutableStateFlow<Boolean>,           // exactAlarmGranted
        val storage: MutableStateFlow<Boolean>,      // storageInsufficient
        val th: MutableStateFlow<ThermalStatus>,     // thermal
        val pw: MutableStateFlow<PowerState>,        // power
        val camState: MutableStateFlow<CameraSignalState>, // camera (state)
        val mic: MutableStateFlow<Boolean>,          // microphonePermissionGranted
        val nt: MutableStateFlow<Boolean>,           // notificationsGranted
        val bo: MutableStateFlow<Boolean>            // batteryOptimizationExempt
    )

    private fun sources() = NineSources(
        MutableStateFlow(true),                       // cameraPerm — granted
        MutableStateFlow(true),                       // ea — exact alarm granted
        MutableStateFlow(false),                      // storage — not insufficient
        MutableStateFlow(ThermalStatus.NONE),         // thermal
        MutableStateFlow(clearPower()),               // power
        MutableStateFlow(CameraSignalState.OK),       // camera state
        MutableStateFlow(true),                       // mic — granted
        MutableStateFlow(true),                       // notificationsGranted
        MutableStateFlow(true)                        // batteryOptimizationExempt
    )

    private suspend fun NineSources.collectInto(emissions: MutableList<WarningId?>) =
        WarningCenterViewModel.aggregate(cameraPerm, ea, storage, th, pw, camState, mic, nt, bo)
            .collect { emissions += it }

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
}

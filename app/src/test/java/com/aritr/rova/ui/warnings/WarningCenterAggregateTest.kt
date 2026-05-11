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
 * Tests [WarningCenterViewModel.aggregate] — the combine logic — with
 * fake [MutableStateFlow]s and no `viewModelScope`. Same plain-JVM
 * coroutine idiom as the Phase 3 signal tests.
 */
class WarningCenterAggregateTest {

    private fun clearPower() = PowerState(percent = 80, charging = false, powerSaveMode = false)

    private fun sources() = SixSources(
        MutableStateFlow(true),                       // exactAlarmGranted
        MutableStateFlow(ThermalStatus.NONE),         // thermal
        MutableStateFlow(clearPower()),               // power
        MutableStateFlow(CameraSignalState.OK),       // camera
        MutableStateFlow(true),                       // notificationsGranted
        MutableStateFlow(true)                        // batteryOptimizationExempt
    )

    private data class SixSources(
        val ea: MutableStateFlow<Boolean>,
        val th: MutableStateFlow<ThermalStatus>,
        val pw: MutableStateFlow<PowerState>,
        val cam: MutableStateFlow<CameraSignalState>,
        val nt: MutableStateFlow<Boolean>,
        val bo: MutableStateFlow<Boolean>
    )

    private suspend fun SixSources.collectInto(emissions: MutableList<WarningId?>) =
        WarningCenterViewModel.aggregate(ea, th, pw, cam, nt, bo).collect { emissions += it }

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
        s.ea.value = false   // exact-alarm revoked → top warning
        yield()
        s.ea.value = true    // restored → null
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
}

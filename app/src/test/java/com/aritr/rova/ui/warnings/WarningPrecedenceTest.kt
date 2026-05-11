package com.aritr.rova.ui.warnings

import com.aritr.rova.ui.signals.CameraSignalState
import com.aritr.rova.ui.signals.PowerState
import com.aritr.rova.ui.signals.ThermalStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JVM tests for [WarningPrecedence.resolve]. One case per wired row,
 * the non-raising states, the battery null/charging guards, and the
 * suppression pairs that pin the precedence order. No Android.
 */
class WarningPrecedenceTest {

    /** All-clear defaults; override one knob per test. */
    private fun resolve(
        exactAlarmGranted: Boolean = true,
        thermal: ThermalStatus = ThermalStatus.NONE,
        percent: Int? = 80,
        charging: Boolean = false,
        powerSaveMode: Boolean = false,
        camera: CameraSignalState = CameraSignalState.OK,
        notificationsGranted: Boolean = true,
        batteryOptimizationExempt: Boolean = true
    ): WarningId? = WarningPrecedence.resolve(
        exactAlarmGranted = exactAlarmGranted,
        thermal = thermal,
        power = PowerState(percent = percent, charging = charging, powerSaveMode = powerSaveMode),
        camera = camera,
        notificationsGranted = notificationsGranted,
        batteryOptimizationExempt = batteryOptimizationExempt
    )

    @Test fun `everything clear returns null`() = assertNull(resolve())

    // --- one wired row at a time ---
    @Test fun `exact alarm denied`() =
        assertEquals(WarningId.EXACT_ALARM_DENIED, resolve(exactAlarmGranted = false))
    @Test fun `thermal shutdown`() =
        assertEquals(WarningId.THERMAL_SHUTDOWN, resolve(thermal = ThermalStatus.SHUTDOWN))
    @Test fun `thermal emergency`() =
        assertEquals(WarningId.THERMAL_EMERGENCY, resolve(thermal = ThermalStatus.EMERGENCY))
    @Test fun `thermal critical`() =
        assertEquals(WarningId.THERMAL_CRITICAL, resolve(thermal = ThermalStatus.CRITICAL))
    @Test fun `battery critical when below 5 and not charging`() =
        assertEquals(WarningId.BATTERY_CRITICAL, resolve(percent = 4, charging = false))
    @Test fun `camera in use`() =
        assertEquals(WarningId.CAMERA_IN_USE, resolve(camera = CameraSignalState.IN_USE))
    @Test fun `camera disabled`() =
        assertEquals(WarningId.CAMERA_DISABLED, resolve(camera = CameraSignalState.DISABLED))
    @Test fun `battery low when below 15 and not charging`() =
        assertEquals(WarningId.BATTERY_LOW, resolve(percent = 12, charging = false))
    @Test fun `thermal severe`() =
        assertEquals(WarningId.THERMAL_SEVERE, resolve(thermal = ThermalStatus.SEVERE))
    @Test fun `battery optimization not exempt`() =
        assertEquals(WarningId.BATTERY_OPTIMIZATION_ON, resolve(batteryOptimizationExempt = false))
    @Test fun `power save mode on`() =
        assertEquals(WarningId.POWER_SAVE_MODE, resolve(powerSaveMode = true))
    @Test fun `thermal moderate`() =
        assertEquals(WarningId.THERMAL_MODERATE, resolve(thermal = ThermalStatus.MODERATE))
    @Test fun `notifications denied`() =
        assertEquals(WarningId.NOTIFICATIONS_DENIED, resolve(notificationsGranted = false))

    // --- non-raising states ---
    @Test fun `thermal light raises nothing`() = assertNull(resolve(thermal = ThermalStatus.LIGHT))
    @Test fun `thermal none raises nothing`() = assertNull(resolve(thermal = ThermalStatus.NONE))
    @Test fun `camera ok raises nothing`() = assertNull(resolve(camera = CameraSignalState.OK))
    @Test fun `camera unknown raises nothing`() = assertNull(resolve(camera = CameraSignalState.UNKNOWN))
    @Test fun `camera other error raises nothing`() = assertNull(resolve(camera = CameraSignalState.OTHER_ERROR))
    @Test fun `unknown battery percent raises no battery warning`() = assertNull(resolve(percent = null, charging = false))
    @Test fun `charging suppresses critical battery`() = assertNull(resolve(percent = 3, charging = true))
    @Test fun `charging suppresses low battery`() = assertNull(resolve(percent = 10, charging = true))
    @Test fun `battery at exactly 15 is not low`() = assertNull(resolve(percent = 15, charging = false))
    @Test fun `battery at exactly 5 is low not critical`() =
        assertEquals(WarningId.BATTERY_LOW, resolve(percent = 5, charging = false))

    // --- suppression / precedence pairs ---
    @Test fun `exact alarm beats thermal shutdown`() =
        assertEquals(WarningId.EXACT_ALARM_DENIED, resolve(exactAlarmGranted = false, thermal = ThermalStatus.SHUTDOWN))
    @Test fun `thermal critical beats battery critical`() =
        assertEquals(WarningId.THERMAL_CRITICAL, resolve(thermal = ThermalStatus.CRITICAL, percent = 3))
    @Test fun `battery critical beats camera in use`() =
        assertEquals(WarningId.BATTERY_CRITICAL, resolve(percent = 3, camera = CameraSignalState.IN_USE))
    @Test fun `camera in use beats battery low`() =
        assertEquals(WarningId.CAMERA_IN_USE, resolve(camera = CameraSignalState.IN_USE, percent = 12))
    @Test fun `battery low beats thermal severe`() =
        assertEquals(WarningId.BATTERY_LOW, resolve(percent = 12, thermal = ThermalStatus.SEVERE))
    @Test fun `thermal severe beats battery optimization`() =
        assertEquals(WarningId.THERMAL_SEVERE, resolve(thermal = ThermalStatus.SEVERE, batteryOptimizationExempt = false))
    @Test fun `battery optimization beats power save`() =
        assertEquals(WarningId.BATTERY_OPTIMIZATION_ON, resolve(batteryOptimizationExempt = false, powerSaveMode = true))
    @Test fun `power save beats thermal moderate`() =
        assertEquals(WarningId.POWER_SAVE_MODE, resolve(powerSaveMode = true, thermal = ThermalStatus.MODERATE))
    @Test fun `thermal moderate beats notifications denied`() =
        assertEquals(WarningId.THERMAL_MODERATE, resolve(thermal = ThermalStatus.MODERATE, notificationsGranted = false))
}

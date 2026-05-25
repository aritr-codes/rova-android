package com.aritr.rova.ui.warnings

import com.aritr.rova.data.StopReason
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
        cameraPermissionGranted: Boolean = true,
        exactAlarmGranted: Boolean = true,
        storageInsufficient: Boolean = false,
        thermal: ThermalStatus = ThermalStatus.NONE,
        percent: Int? = 80,
        charging: Boolean = false,
        powerSaveMode: Boolean = false,
        camera: CameraSignalState = CameraSignalState.OK,
        microphonePermissionGranted: Boolean = true,
        notificationsGranted: Boolean = true,
        batteryOptimizationExempt: Boolean = true,
        storageLowMidRec: Boolean = false,                  // ← NEW
        autoStopEcho: TerminalEcho? = null,                 // ← NEW (Phase 4 Slice 2)
        cantMergeActive: Boolean = false,                   // ← NEW (Phase 4.3)
    ): WarningId? = WarningPrecedence.resolve(
        cameraPermissionGranted = cameraPermissionGranted,
        exactAlarmGranted = exactAlarmGranted,
        storageInsufficient = storageInsufficient,
        thermal = thermal,
        power = PowerState(percent = percent, charging = charging, powerSaveMode = powerSaveMode),
        camera = camera,
        microphonePermissionGranted = microphonePermissionGranted,
        notificationsGranted = notificationsGranted,
        batteryOptimizationExempt = batteryOptimizationExempt,
        storageLowMidRec = storageLowMidRec,                // ← NEW (passed positionally to WarningPrecedence.resolve)
        autoStopEcho = autoStopEcho,                        // ← NEW (Phase 4 Slice 2)
        cantMergeActive = cantMergeActive,                  // ← NEW (Phase 4.3)
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

    // --- new wired rows (Phase 4.1b) ---
    @Test fun `camera permission denied`() =
        assertEquals(WarningId.CAMERA_PERMISSION_DENIED, resolve(cameraPermissionGranted = false))
    @Test fun `storage insufficient`() =
        assertEquals(WarningId.STORAGE_INSUFFICIENT, resolve(storageInsufficient = true))
    @Test fun `microphone denied`() =
        assertEquals(WarningId.MICROPHONE_DENIED, resolve(microphonePermissionGranted = false))

    // --- new suppression / precedence pairs (Phase 4.1b) ---
    @Test fun `camera permission beats exact alarm`() =
        assertEquals(WarningId.CAMERA_PERMISSION_DENIED, resolve(cameraPermissionGranted = false, exactAlarmGranted = false))
    @Test fun `exact alarm beats storage insufficient`() =
        assertEquals(WarningId.EXACT_ALARM_DENIED, resolve(exactAlarmGranted = false, storageInsufficient = true))
    @Test fun `storage insufficient beats thermal shutdown`() =
        assertEquals(WarningId.STORAGE_INSUFFICIENT, resolve(storageInsufficient = true, thermal = ThermalStatus.SHUTDOWN))
    @Test fun `thermal severe beats microphone denied`() =
        assertEquals(WarningId.THERMAL_SEVERE, resolve(thermal = ThermalStatus.SEVERE, microphonePermissionGranted = false))
    @Test fun `microphone denied beats battery optimization`() =
        assertEquals(WarningId.MICROPHONE_DENIED, resolve(microphonePermissionGranted = false, batteryOptimizationExempt = false))
    @Test fun `camera permission outranks every other active warning`() =
        assertEquals(
            WarningId.CAMERA_PERMISSION_DENIED,
            resolve(
                cameraPermissionGranted = false, exactAlarmGranted = false, storageInsufficient = true,
                thermal = ThermalStatus.SHUTDOWN, percent = 1, camera = CameraSignalState.IN_USE,
                microphonePermissionGranted = false, notificationsGranted = false,
                batteryOptimizationExempt = false, powerSaveMode = true
            )
        )

    // ── R2 — STORAGE_LOW_MID_REC (ordinal 10, row #11) ──────────────

    @Test fun `storage low mid-rec alone fires`() =
        assertEquals(WarningId.STORAGE_LOW_MID_REC, resolve(storageLowMidRec = true))

    @Test fun `storage low mid-rec outranks thermal severe`() =
        assertEquals(
            WarningId.STORAGE_LOW_MID_REC,
            resolve(storageLowMidRec = true, thermal = ThermalStatus.SEVERE),
        )

    @Test fun `battery low outranks storage low mid-rec`() =
        assertEquals(
            WarningId.BATTERY_LOW,
            resolve(storageLowMidRec = true, percent = 14, charging = false),
        )

    @Test fun `thermal critical outranks storage low mid-rec`() =
        assertEquals(
            WarningId.THERMAL_CRITICAL,
            resolve(storageLowMidRec = true, thermal = ThermalStatus.CRITICAL),
        )

    @Test fun `storage low mid-rec outranks below-band advisories`() =
        assertEquals(
            WarningId.STORAGE_LOW_MID_REC,
            resolve(
                storageLowMidRec = true,
                microphonePermissionGranted = false,         // would fire #13
                notificationsGranted = false,                // would fire #17
                batteryOptimizationExempt = false,           // would fire #14
                powerSaveMode = true,                        // would fire #15
            ),
        )

    @Test fun `storage low mid-rec false does not fire when otherwise clear`() =
        assertNull(resolve(storageLowMidRec = false))

    // ── Phase 4 Slice 2 — STORAGE_FULL_AUTOSTOPPED (ordinal 11, row #12) ──

    @Test fun `STORAGE_FULL_AUTOSTOPPED fires when autoStopEcho is LOW_STORAGE and no higher-priority signal active`() {
        val resolved = WarningPrecedence.resolve(
            cameraPermissionGranted = true,
            exactAlarmGranted = true,
            storageInsufficient = false,
            thermal = ThermalStatus.NONE,
            power = PowerState(percent = 80, charging = false, powerSaveMode = false),
            camera = CameraSignalState.OK,
            microphonePermissionGranted = true,
            notificationsGranted = true,
            batteryOptimizationExempt = true,
            storageLowMidRec = false,
            autoStopEcho = TerminalEcho("session-a", StopReason.LOW_STORAGE),
        )
        assertEquals(WarningId.STORAGE_FULL_AUTOSTOPPED, resolved)
    }

    @Test fun `STORAGE_LOW_MID_REC outranks STORAGE_FULL_AUTOSTOPPED when both fire`() {
        val resolved = WarningPrecedence.resolve(
            cameraPermissionGranted = true,
            exactAlarmGranted = true,
            storageInsufficient = false,
            thermal = ThermalStatus.NONE,
            power = PowerState(percent = 80, charging = false, powerSaveMode = false),
            camera = CameraSignalState.OK,
            microphonePermissionGranted = true,
            notificationsGranted = true,
            batteryOptimizationExempt = true,
            storageLowMidRec = true,
            autoStopEcho = TerminalEcho("session-a", StopReason.LOW_STORAGE),
        )
        assertEquals(WarningId.STORAGE_LOW_MID_REC, resolved)
    }

    // ── Phase 4 Slice 3 — THERMAL_AUTOSTOPPED (ordinal 12, row #13) ──

    @Test fun `THERMAL_AUTOSTOPPED fires when autoStopEcho is THERMAL and no higher-priority signal active`() {
        val resolved = WarningPrecedence.resolve(
            cameraPermissionGranted = true,
            exactAlarmGranted = true,
            storageInsufficient = false,
            thermal = ThermalStatus.NONE,
            power = PowerState(percent = 80, charging = false, powerSaveMode = false),
            camera = CameraSignalState.OK,
            microphonePermissionGranted = true,
            notificationsGranted = true,
            batteryOptimizationExempt = true,
            storageLowMidRec = false,
            autoStopEcho = TerminalEcho("session-t", StopReason.THERMAL),
        )
        assertEquals(WarningId.THERMAL_AUTOSTOPPED, resolved)
    }

    @Test fun `LOW_STORAGE echo still resolves STORAGE_FULL_AUTOSTOPPED after when-rewrite`() {
        val resolved = WarningPrecedence.resolve(
            cameraPermissionGranted = true,
            exactAlarmGranted = true,
            storageInsufficient = false,
            thermal = ThermalStatus.NONE,
            power = PowerState(percent = 80, charging = false, powerSaveMode = false),
            camera = CameraSignalState.OK,
            microphonePermissionGranted = true,
            notificationsGranted = true,
            batteryOptimizationExempt = true,
            storageLowMidRec = false,
            autoStopEcho = TerminalEcho("session-s", StopReason.LOW_STORAGE),
        )
        assertEquals(WarningId.STORAGE_FULL_AUTOSTOPPED, resolved)
    }

    // ── Phase 4.3 — CANT_MERGE (ordinal 13, row #14) ──────────────────

    @Test fun `CANT_MERGE resolves when cantMergeActive=true and no higher-priority warning`() {
        val resolved = resolve(cantMergeActive = true)
        assertEquals(WarningId.CANT_MERGE, resolved)
    }

    @Test fun `CANT_MERGE loses to THERMAL_AUTOSTOPPED (lower ordinal wins)`() {
        val resolved = resolve(
            cantMergeActive = true,
            autoStopEcho = TerminalEcho("session-t", StopReason.THERMAL),
        )
        assertEquals(WarningId.THERMAL_AUTOSTOPPED, resolved)
    }

    @Test fun `CANT_MERGE wins over THERMAL_SEVERE (higher precedence in advisory band)`() {
        val resolved = resolve(
            cantMergeActive = true,
            thermal = ThermalStatus.SEVERE,
        )
        assertEquals(WarningId.CANT_MERGE, resolved)
    }
}

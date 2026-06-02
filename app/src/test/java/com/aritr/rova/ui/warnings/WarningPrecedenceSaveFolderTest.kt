package com.aritr.rova.ui.warnings

import com.aritr.rova.ui.signals.CameraSignalState
import com.aritr.rova.ui.signals.PowerState
import com.aritr.rova.ui.signals.ThermalStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * B4b (ADR-0024) — precedence tests for [WarningId.SAVE_FOLDER_UNAVAILABLE].
 *
 * Uses the same all-clear helper pattern as [WarningPrecedenceTest] so
 * each test flips only the single knob under examination.
 */
class WarningPrecedenceSaveFolderTest {

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
        storageLowMidRec: Boolean = false,
        autoStopEcho: TerminalEcho? = null,
        cantMergeActive: Boolean = false,
        saveFolderUnavailable: Boolean = false,
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
        storageLowMidRec = storageLowMidRec,
        autoStopEcho = autoStopEcho,
        cantMergeActive = cantMergeActive,
        saveFolderUnavailable = saveFolderUnavailable,
    )

    // ── basic surfacing ──────────────────────────────────────────────────

    @Test fun `saveFolderUnavailable surfaces when nothing higher`() {
        assertEquals(
            WarningId.SAVE_FOLDER_UNAVAILABLE,
            resolve(saveFolderUnavailable = true)
        )
    }

    @Test fun `absent when flag false and all clear`() {
        assertNull(resolve(saveFolderUnavailable = false))
    }

    // ── precedence relative to neighbours ───────────────────────────────

    @Test fun `NOTIFICATIONS_DENIED outranks SAVE_FOLDER_UNAVAILABLE when both active`() {
        // NOTIFICATIONS_DENIED is ordinal 19 (#20), SAVE_FOLDER_UNAVAILABLE is ordinal 20 (#21)
        assertEquals(
            WarningId.NOTIFICATIONS_DENIED,
            resolve(notificationsGranted = false, saveFolderUnavailable = true)
        )
    }

    @Test fun `SAVE_FOLDER_UNAVAILABLE alone resolves over nothing`() {
        assertEquals(
            WarningId.SAVE_FOLDER_UNAVAILABLE,
            resolve(saveFolderUnavailable = true, notificationsGranted = true)
        )
    }

    @Test fun `CAMERA_PERMISSION_DENIED outranks SAVE_FOLDER_UNAVAILABLE`() {
        assertEquals(
            WarningId.CAMERA_PERMISSION_DENIED,
            resolve(cameraPermissionGranted = false, saveFolderUnavailable = true)
        )
    }

    @Test fun `CANT_MERGE outranks SAVE_FOLDER_UNAVAILABLE`() {
        assertEquals(
            WarningId.CANT_MERGE,
            resolve(cantMergeActive = true, saveFolderUnavailable = true)
        )
    }

    // ── allActive includes it when active ────────────────────────────────

    @Test fun `allActive includes SAVE_FOLDER_UNAVAILABLE when flag is true`() {
        val active = WarningPrecedence.allActive(
            cameraPermissionGranted = true,
            exactAlarmGranted = true,
            storageInsufficient = false,
            thermal = ThermalStatus.NONE,
            power = PowerState(percent = 80, charging = false, powerSaveMode = false),
            camera = CameraSignalState.OK,
            microphonePermissionGranted = true,
            notificationsGranted = true,
            batteryOptimizationExempt = true,
            saveFolderUnavailable = true,
        )
        assertEquals(listOf(WarningId.SAVE_FOLDER_UNAVAILABLE), active)
    }

    @Test fun `allActive does not include SAVE_FOLDER_UNAVAILABLE when flag is false`() {
        val active = WarningPrecedence.allActive(
            cameraPermissionGranted = true,
            exactAlarmGranted = true,
            storageInsufficient = false,
            thermal = ThermalStatus.NONE,
            power = PowerState(percent = 80, charging = false, powerSaveMode = false),
            camera = CameraSignalState.OK,
            microphonePermissionGranted = true,
            notificationsGranted = true,
            batteryOptimizationExempt = true,
            saveFolderUnavailable = false,
        )
        assert(WarningId.SAVE_FOLDER_UNAVAILABLE !in active)
    }
}

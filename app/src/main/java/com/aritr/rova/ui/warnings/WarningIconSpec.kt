package com.aritr.rova.ui.warnings

import com.aritr.rova.ui.theme.RovaGlyph
import com.aritr.rova.ui.theme.RovaIcons

/**
 * Pure concept→glyph choice for the Warnings surface (ADR-0031, UI Phase 2 PR-5b-2). One glyph per
 * [WarningId], surface-independent — the sheet and the mid-recording banner agree. Compose/Android-free
 * so it is JVM-unit-testable under `isReturnDefaultValues`, mirroring [com.aritr.rova.ui.library.components.LibraryIconSpec].
 *
 * The glyph is the System-D *identity*; the *tint* stays the warning-severity system (per-tier
 * RovaWarnings + glow) at the render site — the ADR-0031 §4 severity-tint exception.
 */
internal object WarningIconSpec {
    fun glyphFor(id: WarningId): RovaGlyph = when (id) {
        WarningId.CAMERA_PERMISSION_DENIED -> RovaIcons.CameraPermission
        WarningId.CAMERA_IN_USE, WarningId.CAMERA_DISABLED -> RovaIcons.CameraOff
        WarningId.EXACT_ALARM_DENIED -> RovaIcons.AlarmOff
        WarningId.STORAGE_INSUFFICIENT, WarningId.STORAGE_LOW_MID_REC,
        WarningId.STORAGE_FULL_AUTOSTOPPED, WarningId.CANT_MERGE -> RovaIcons.Storage
        WarningId.SAVE_FOLDER_UNAVAILABLE -> RovaIcons.Folder
        WarningId.THERMAL_SHUTDOWN, WarningId.THERMAL_EMERGENCY, WarningId.THERMAL_CRITICAL,
        WarningId.THERMAL_SEVERE, WarningId.THERMAL_MODERATE, WarningId.THERMAL_AUTOSTOPPED -> RovaIcons.Thermal
        WarningId.BATTERY_CRITICAL, WarningId.BATTERY_LOW -> RovaIcons.BatteryLow
        WarningId.MICROPHONE_DENIED -> RovaIcons.MicOff
        WarningId.NOTIFICATIONS_DENIED -> RovaIcons.NotificationsOff
        WarningId.BATTERY_OPTIMIZATION_ON -> RovaIcons.BatterySaver
        WarningId.POWER_SAVE_MODE -> RovaIcons.PowerMode
    }
}

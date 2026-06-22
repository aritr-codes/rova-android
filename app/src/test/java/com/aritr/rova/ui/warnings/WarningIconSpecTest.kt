package com.aritr.rova.ui.warnings

import com.aritr.rova.ui.theme.RovaIcons
import org.junit.Assert.assertSame
import org.junit.Test

class WarningIconSpecTest {

    @Test fun every_warning_id_resolves_without_throwing() {
        WarningId.entries.forEach { WarningIconSpec.glyphFor(it) }
    }

    @Test fun camera_permission_denied_is_the_no_slash_camera() {
        assertSame(RovaIcons.CameraPermission, WarningIconSpec.glyphFor(WarningId.CAMERA_PERMISSION_DENIED))
    }

    @Test fun camera_in_use_and_disabled_are_the_slashed_camera() {
        assertSame(RovaIcons.CameraOff, WarningIconSpec.glyphFor(WarningId.CAMERA_IN_USE))
        assertSame(RovaIcons.CameraOff, WarningIconSpec.glyphFor(WarningId.CAMERA_DISABLED))
    }

    @Test fun all_five_thermal_tiers_and_autostop_share_the_thermal_glyph() {
        listOf(
            WarningId.THERMAL_SHUTDOWN, WarningId.THERMAL_EMERGENCY, WarningId.THERMAL_CRITICAL,
            WarningId.THERMAL_SEVERE, WarningId.THERMAL_MODERATE, WarningId.THERMAL_AUTOSTOPPED,
        ).forEach { assertSame(RovaIcons.Thermal, WarningIconSpec.glyphFor(it)) }
    }

    @Test fun both_battery_tiers_share_the_battery_low_glyph() {
        assertSame(RovaIcons.BatteryLow, WarningIconSpec.glyphFor(WarningId.BATTERY_CRITICAL))
        assertSame(RovaIcons.BatteryLow, WarningIconSpec.glyphFor(WarningId.BATTERY_LOW))
    }

    @Test fun storage_family_is_storage_but_save_folder_is_folder() {
        listOf(
            WarningId.STORAGE_INSUFFICIENT, WarningId.STORAGE_LOW_MID_REC,
            WarningId.STORAGE_FULL_AUTOSTOPPED, WarningId.CANT_MERGE,
        ).forEach { assertSame(RovaIcons.Storage, WarningIconSpec.glyphFor(it)) }
        assertSame(RovaIcons.Folder, WarningIconSpec.glyphFor(WarningId.SAVE_FOLDER_UNAVAILABLE))
    }

    @Test fun permission_and_power_concepts_map_to_their_glyphs() {
        assertSame(RovaIcons.AlarmOff, WarningIconSpec.glyphFor(WarningId.EXACT_ALARM_DENIED))
        assertSame(RovaIcons.MicOff, WarningIconSpec.glyphFor(WarningId.MICROPHONE_DENIED))
        assertSame(RovaIcons.NotificationsOff, WarningIconSpec.glyphFor(WarningId.NOTIFICATIONS_DENIED))
        assertSame(RovaIcons.BatterySaver, WarningIconSpec.glyphFor(WarningId.BATTERY_OPTIMIZATION_ON))
        assertSame(RovaIcons.PowerMode, WarningIconSpec.glyphFor(WarningId.POWER_SAVE_MODE))
    }
}

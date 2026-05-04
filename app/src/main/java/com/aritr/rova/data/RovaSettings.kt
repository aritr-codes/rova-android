package com.aritr.rova.data

import android.content.Context
import androidx.core.content.edit

class RovaSettings(context: Context) {
    private val prefs = context.getSharedPreferences("rova_settings", Context.MODE_PRIVATE)

    var durationSeconds: Int
        get() = prefs.getInt("duration", 10)
        set(value) = prefs.edit { putInt("duration", value) }

    var intervalMinutes: Int
        get() = prefs.getInt("interval", 1)
        set(value) = prefs.edit { putInt("interval", value) }

    var resolution: String
        get() = prefs.getString("resolution", QualityPresets.DEFAULT) ?: QualityPresets.DEFAULT
        set(value) = prefs.edit { putString("resolution", value) }

    var loopCount: Int
        get() = prefs.getInt("loop_count", 10) // -1 for continuous
        set(value) = prefs.edit { putInt("loop_count", value) }

    var enableBeeps: Boolean
        get() = prefs.getBoolean("enable_beeps", true)
        set(value) = prefs.edit { putBoolean("enable_beeps", value) }

    var vibrateAlerts: Boolean
        get() = prefs.getBoolean("vibrate_alerts", true)
        set(value) = prefs.edit { putBoolean("vibrate_alerts", value) }

    var keepScreenOn: Boolean
        get() = prefs.getBoolean("keep_screen_on", false)
        set(value) = prefs.edit { putBoolean("keep_screen_on", value) }

    // Custom Presets (JSON String)
    var customPresetsJson: String
        get() = prefs.getString("custom_presets", "[]") ?: "[]"
        set(value) = prefs.edit { putString("custom_presets", value) }

    // Storage retention — Keep latest N finalized recordings.
    // Default OFF so the existing manual-delete-only behavior is
    // unchanged; the user opts in via Settings.
    var autoDeleteEnabled: Boolean
        get() = prefs.getBoolean("auto_delete_enabled", false)
        set(value) = prefs.edit { putBoolean("auto_delete_enabled", value) }

    var autoDeleteKeepLatest: Int
        get() = prefs.getInt("auto_delete_keep_latest", 10)
        set(value) = prefs.edit { putInt("auto_delete_keep_latest", value) }
}

data class RovaPreset(
    val name: String,
    val duration: Int,
    val interval: Int,
    val loopCount: Int, // -1 for infinite
    val resolution: String
)

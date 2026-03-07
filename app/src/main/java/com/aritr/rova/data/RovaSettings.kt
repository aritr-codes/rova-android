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

    var backgroundMode: Boolean
        get() = prefs.getBoolean("background_mode", true)
        set(value) = prefs.edit { putBoolean("background_mode", value) }

    var resolution: String
        get() = prefs.getString("resolution", "FHD") ?: "FHD"
        set(value) = prefs.edit { putString("resolution", value) }
        
    var preBeepDelay: Int
        get() = prefs.getInt("pre_beep", 1)
        set(value) = prefs.edit { putInt("pre_beep", value) }
        
    var postBeepDelay: Int
        get() = prefs.getInt("post_beep", 1)
        set(value) = prefs.edit { putInt("post_beep", value) }

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
}

data class RovaPreset(
    val name: String,
    val duration: Int,
    val interval: Int,
    val loopCount: Int, // -1 for infinite
    val resolution: String
)

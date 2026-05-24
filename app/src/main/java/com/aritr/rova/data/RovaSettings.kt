package com.aritr.rova.data

import android.content.Context
import androidx.core.content.edit

class RovaSettings(context: Context) {
    private val prefs = context.getSharedPreferences("rova_settings", Context.MODE_PRIVATE)

    // Phase 4 fresh-install fix: `mode` is intentionally NOT backed up. It
    // lives in a separate SharedPreferences file (rova_runtime_prefs) that
    // is excluded from Auto Backup + device-transfer (see backup_rules.xml
    // and data_extraction_rules.xml). Without the split, reinstalling the
    // APK on the same Google account restored `mode=PortraitLandscape` from
    // a prior session and the app opened in DualShot — defeating the
    // documented Portrait default. All OTHER user config (resolution,
    // duration, intervals, presets, etc.) still backs up normally.
    private val runtimePrefs = context.getSharedPreferences(
        RUNTIME_PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    init {
        // One-time migration for installs that pre-date the mode split.
        // If we haven't stamped the migration marker yet, copy any
        // legacy `mode` value from `prefs` into `runtimePrefs`, then
        // delete the legacy key and stamp the marker. The marker
        // travels via Auto Backup along with the rest of `prefs`, so
        // post-backup restores skip this branch and the new install
        // correctly defaults `runtimePrefs.mode` to Portrait.
        if (!prefs.getBoolean(MODE_MIGRATED_V1, false)) {
            val legacyMode = prefs.getString("mode", null)
            if (legacyMode != null) {
                runtimePrefs.edit { putString("mode", legacyMode) }
            }
            prefs.edit {
                remove("mode")
                putBoolean(MODE_MIGRATED_V1, true)
            }
        }
    }

    var durationSeconds: Int
        get() = prefs.getInt("duration", 10)
        set(value) = prefs.edit { putInt("duration", value) }

    var intervalMinutes: Int
        get() = prefs.getInt("interval", 1)
        set(value) = prefs.edit { putInt("interval", value) }

    var resolution: String
        get() = prefs.getString("resolution", QualityPresets.DEFAULT) ?: QualityPresets.DEFAULT
        set(value) = prefs.edit { putString("resolution", value) }

    /** Coerces unknown persisted values to the default — defends against stale/version-mismatched reads. */
    var mode: String
        get() = (runtimePrefs.getString("mode", "Portrait") ?: "Portrait")
            .takeIf { it == "Portrait" || it == "Landscape" || it == "PortraitLandscape" } ?: "Portrait"
        set(value) = runtimePrefs.edit { putString("mode", value) }

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

    // Decorative camera guides (grid + focus brackets + vignette) over the
    // viewfinder. Default ON — the record screen ships matching the mockup.
    var cameraGuidesEnabled: Boolean
        get() = prefs.getBoolean("camera_guides_enabled", true)
        set(value) = prefs.edit { putBoolean("camera_guides_enabled", value) }

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

    // Phase 0 UI-pending keys — persisted only; no UI/service consumer yet.
    // First-run onboarding completion flag.
    var onboardingCompleted: Boolean
        get() = prefs.getBoolean("onboarding_completed", false)
        set(value) = prefs.edit { putBoolean("onboarding_completed", value) }

    // Default ON: preserves existing behavior where finalized recordings
    // publish to MediaStore. Phase 5 will introduce the gating consumer.
    var autoExportEnabled: Boolean
        get() = prefs.getBoolean("auto_export_enabled", true)
        set(value) = prefs.edit { putBoolean("auto_export_enabled", value) }

    // Preferred MediaStore relative folder name; empty = caller default.
    var exportFolderName: String
        get() = prefs.getString("export_folder_name", "") ?: ""
        set(value) = prefs.edit { putString("export_folder_name", value) }

    companion object {
        /** Backup-excluded SharedPreferences file for runtime state that must NOT survive reinstall. */
        const val RUNTIME_PREFS_NAME = "rova_runtime_prefs"
        private const val MODE_MIGRATED_V1 = "mode_migrated_v1"
    }
}

data class RovaPreset(
    val name: String,
    val duration: Int,
    val interval: Int,
    val loopCount: Int, // -1 for infinite
    val resolution: String
)

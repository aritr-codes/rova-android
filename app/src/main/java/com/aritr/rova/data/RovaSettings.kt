package com.aritr.rova.data

import android.content.Context
import androidx.core.content.edit
import com.aritr.rova.ui.locale.AppLocale
import com.aritr.rova.ui.theme.ThemeMigration
import com.aritr.rova.ui.theme.ThemeMode
import com.aritr.rova.ui.theme.ThemeSelection
import com.aritr.rova.ui.theme.themeModeFromStorage

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
        // Legacy-key cleanup. Pre-mode-split installs stored the `mode`
        // value in `prefs` (rova_settings.xml), which IS backed up by
        // Android Auto Backup. After a reinstall, the restored backup
        // brought back the prior `mode=PortraitLandscape` and the app
        // opened in DualShot -- the bug this whole patch chain exists
        // to kill. The new authoritative location is `runtimePrefs`
        // (rova_runtime_prefs.xml), which is excluded from backup, so
        // a reinstall finds it empty and the getter falls through to
        // the documented Portrait default.
        //
        // We DELIBERATELY do not migrate the legacy value forward
        // here. Auto Backup snapshots run on a schedule (~24 h, idle +
        // charging) so a user who installs this patch, sets a mode,
        // and then reinstalls before the next snapshot would have
        // their PRE-PATCH backup restored -- with `mode=PortraitLandscape`
        // intact in `prefs` and no marker. A faithful "copy legacy ->
        // runtime" migration would dutifully preserve that stale P+L
        // value and defeat the fix. Dropping the legacy key without
        // copying it gives the documented Portrait default on every
        // reinstall and on every in-place update from a pre-split
        // build. The one-time cost is a single setting reset on update;
        // worth it for the predictable fresh-install behavior.
        if (prefs.contains("mode")) {
            prefs.edit { remove("mode") }
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

    /**
     * ADR-0029 PR-γ — capture-topology axis ("Single"/"DualShot"/"FrontBack").
     * runtimePrefs (backup-excluded) for the same reason legacy `mode` was:
     * reinstalls must default, not resurrect a backed-up special mode.
     * The legacy "mode" key is migrated once and then left in place, read-only,
     * for one release (ADR-0029 §6).
     */
    var captureTopology: String
        get() {
            migrateLegacyModeIfNeeded()
            return (runtimePrefs.getString("capture_topology", "Single") ?: "Single")
                .takeIf { CaptureTopology.isValidPersisted(it) } ?: "Single"
        }
        set(value) = runtimePrefs.edit { putString("capture_topology", value) }

    /** ADR-0029 PR-γ — orientation-policy axis: "FollowDevice" (default) or "Lock". */
    var orientationPolicy: String
        get() {
            migrateLegacyModeIfNeeded()
            return (runtimePrefs.getString("orientation_policy", "FollowDevice") ?: "FollowDevice")
                .takeIf { it == "FollowDevice" || it == "Lock" } ?: "FollowDevice"
        }
        set(value) = runtimePrefs.edit { putString("orientation_policy", value) }

    /** Surface.ROTATION_* captured at lock time (Ratified-D); -1 when not locked. */
    var orientationLockRotation: Int
        get() {
            migrateLegacyModeIfNeeded()
            return runtimePrefs.getInt("orientation_lock_rotation", -1)
        }
        set(value) = runtimePrefs.edit { putInt("orientation_lock_rotation", value) }

    private fun migrateLegacyModeIfNeeded() {
        if (runtimePrefs.contains("capture_topology")) return
        val legacy = runtimePrefs.getString("mode", null)
        val m = ModeMigration.migrate(legacy)
        runtimePrefs.edit {
            putString("capture_topology", m.topology)
            putString("orientation_policy", m.policy)
            if (m.lockRotation in 0..3) putInt("orientation_lock_rotation", m.lockRotation)
            // legacy "mode" key deliberately left in place for one release (ADR-0029 §6)
        }
    }

    /**
     * Phase 4.1c — persistent "Don't show again" set, keyed by [com.aritr.rova.ui.warnings.WarningId.name].
     * Backed by [RUNTIME_PREFS_NAME] so reinstall resets the choice (same policy
     * `mode` follows; see backup_rules.xml + data_extraction_rules.xml).
     * Spec: docs/superpowers/specs/2026-05-24-phase-4-1c-snooze-persistence-design.md §4.1
     */
    var snoozedWarningIds: Set<String>
        get() = runtimePrefs.getStringSet("snoozed_warning_ids", emptySet()) ?: emptySet()
        set(value) = runtimePrefs.edit { putStringSet("snoozed_warning_ids", value) }

    /**
     * Phase 4 Slice 2 — persistent per-session-id dismissal set for the
     * STORAGE_FULL_AUTOSTOPPED echo banner. Values are session ids
     * ([com.aritr.rova.data.SessionManifest.sessionId]). Backed by
     * [RUNTIME_PREFS_NAME] so reinstall resets (same policy as `mode` +
     * `snoozedWarningIds` — see backup_rules.xml + data_extraction_rules.xml).
     * Spec: docs/superpowers/specs/2026-05-24-phase-4-slice2-storage-full-autostopped-design.md §4.1
     */
    var dismissedAutoStopEchoIds: Set<String>
        get() = runtimePrefs.getStringSet("dismissed_autostop_echo_ids", emptySet()) ?: emptySet()
        set(value) = runtimePrefs.edit { putStringSet("dismissed_autostop_echo_ids", value) }

    /**
     * Battery-optimization card rate-limit — epoch-millis of the last time the
     * card was actually shown. Drives the "show at most once per 24h" gate
     * (see [com.aritr.rova.ui.warnings.shouldSuppressBatteryCard]). Backed by
     * [RUNTIME_PREFS_NAME] so reinstall resets it (same policy as `mode` +
     * `snoozedWarningIds` — see backup_rules.xml + data_extraction_rules.xml).
     */
    var batteryOptCardLastShownAt: Long
        get() = runtimePrefs.getLong("battery_opt_card_last_shown_at", 0L)
        set(value) = runtimePrefs.edit { putLong("battery_opt_card_last_shown_at", value) }

    /**
     * Power-save-mode card rate-limit — epoch-millis of the last time the
     * "power-save mode may throttle recording" card (WarningId.POWER_SAVE_MODE)
     * was actually shown. Mirrors [batteryOptCardLastShownAt] and reuses the
     * same once-per-24h predicate ([com.aritr.rova.ui.warnings.shouldSuppressBatteryCard]).
     * Backed by [RUNTIME_PREFS_NAME] so a reinstall resets it.
     */
    var powerSaveCardLastShownAt: Long
        get() = runtimePrefs.getLong("power_save_card_last_shown_at", 0L)
        set(value) = runtimePrefs.edit { putLong("power_save_card_last_shown_at", value) }

    var loopCount: Int
        get() = prefs.getInt("loop_count", 10) // -1 for continuous
        set(value) = prefs.edit { putInt("loop_count", value) }

    // B2 — app theme. Backed up (a genuine user preference, unlike `mode`).
    // Stored as the ThemeMode name; reads coerce unknown/missing to SYSTEM
    // via the pure themeModeFromStorage helper (see ThemeMode.kt).
    var themeMode: ThemeMode
        get() = themeModeFromStorage(prefs.getString("theme_mode", null))
        set(value) = prefs.edit { putString("theme_mode", value.name) }

    /**
     * Liquid-glass theme selection (ADR-0028 §1.2). Read path is migration-aware:
     * a stored `theme_selection` wins; absent that, the legacy `theme_mode` is
     * migrated (the old key is left intact for one-release rollback safety).
     * Synchronous SharedPreferences — resolved before the theme StateFlow inits so
     * the first frame never flashes the default.
     */
    var themeSelection: ThemeSelection
        get() = ThemeMigration.resolve(
            newRaw = prefs.getString("theme_selection", null),
            oldRaw = prefs.getString("theme_mode", null),
        )
        set(value) = prefs.edit { putString("theme_selection", value.name) }

    // i18n Phase B (ADR-0023) — chosen app language as a BCP-47 tag; null =
    // follow system. Backed up (a genuine user preference, like themeMode).
    // Reads coerce through AppLocale.localeTagFromStorage so an unknown/stale
    // tag (incl. a language not yet shipped) falls back to system. On API 33+
    // LocaleManager is the live applier; this value mirrors it as the single
    // source of truth and feeds the API 24–32 attachBaseContext backport.
    var localeTag: String?
        get() = AppLocale.localeTagFromStorage(prefs.getString("locale_tag", null))
        set(value) = prefs.edit {
            if (value == null) remove("locale_tag") else putString("locale_tag", value)
        }

    // ADR-0024 (B4 SD-card track) — persisted SAF tree URI for the custom
    // save location, plus a human-readable label for the Settings row.
    // Backed up (a genuine user preference). null = no custom folder → the
    // SDK-derived export tier is used. The persisted read+write grant is
    // taken at pick time; export-time code re-checks it via
    // SafAndroidOps.isPersistedPermissionHeld before trusting this value.
    var saveLocationTreeUri: String?
        get() = prefs.getString("save_location_tree_uri", null)
        set(value) = prefs.edit {
            if (value == null) remove("save_location_tree_uri") else putString("save_location_tree_uri", value)
        }

    var saveLocationLabel: String?
        get() = prefs.getString("save_location_label", null)
        set(value) = prefs.edit {
            if (value == null) remove("save_location_label") else putString("save_location_label", value)
        }

    /** ADR-0024 — set true when a frozen SAF export found the folder gone/revoked. Drives WarningId.SAVE_FOLDER_UNAVAILABLE. Runtime (not backed up). */
    var saveFolderUnavailable: Boolean
        get() = runtimePrefs.getBoolean("save_folder_unavailable", false)
        set(value) = runtimePrefs.edit { putBoolean("save_folder_unavailable", value) }

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

    // ADR-0026 — set true once the first-run Standard seed has run (or been
    // skipped for an existing user). Prevents re-seeding on later launches.
    var presetSeeded: Boolean
        get() = prefs.getBoolean("preset_seeded", false)
        set(value) = prefs.edit { putBoolean("preset_seeded", value) }

    // --- Daily recording window (ADR-0027) — off by default. ---
    var scheduleEnabled: Boolean
        get() = prefs.getBoolean("schedule_enabled", false)
        set(value) = prefs.edit { putBoolean("schedule_enabled", value) }

    /** Minutes past local midnight, 0..1439. Default 09:00. */
    var scheduleStartMinuteOfDay: Int
        get() = prefs.getInt("schedule_start_minute", 9 * 60)
        set(value) = prefs.edit { putInt("schedule_start_minute", value) }

    /** Minutes past local midnight, 0..1439. Default 17:00. */
    var scheduleStopMinuteOfDay: Int
        get() = prefs.getInt("schedule_stop_minute", 17 * 60)
        set(value) = prefs.edit { putInt("schedule_stop_minute", value) }

    /** Weekday bitmask: bit0=Mon..bit6=Sun; 0 = every day. Default every day. */
    var scheduleWeekdayMask: Int
        get() = prefs.getInt("schedule_weekday_mask", 0)
        set(value) = prefs.edit { putInt("schedule_weekday_mask", value) }

    /**
     * ADR-0027 — single-use nonce binding the window-open notification's
     * PendingIntent to a legitimate start. MainActivity is exported, so an
     * external app could otherwise forge ACTION_SCHEDULE_AUTO_ARM and trigger
     * camera recording. The receiver writes a fresh nonce when posting the
     * prompt; MainActivity starts only if the tapped intent's nonce matches,
     * then clears it (rotate-on-use). Runtime-backed so a reinstall resets it.
     */
    var scheduleStartNonce: String?
        get() = runtimePrefs.getString("schedule_start_nonce", null)
        set(value) = runtimePrefs.edit {
            if (value == null) remove("schedule_start_nonce") else putString("schedule_start_nonce", value)
        }

    fun scheduleSnapshot(): com.aritr.rova.service.scheduler.ScheduleSettingsSnapshot =
        com.aritr.rova.service.scheduler.ScheduleSettingsSnapshot(
            enabled = scheduleEnabled,
            startMinuteOfDay = scheduleStartMinuteOfDay,
            stopMinuteOfDay = scheduleStopMinuteOfDay,
            weekdayMask = scheduleWeekdayMask,
        )

    /** True if any recording-default pref key has ever been written (existing user). */
    fun hasAnyRecordingPref(): Boolean =
        prefs.contains("duration") || prefs.contains("interval") ||
            prefs.contains("loop_count") || prefs.contains("resolution")

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

    // B5 / ADR-0025 — when ON, new recordings go to the hidden vault
    // (app-private storage, never published). Backed up (a genuine user
    // preference, like themeMode). Default OFF preserves existing behavior.
    var hideInVault: Boolean
        get() = prefs.getBoolean("hide_in_vault", false)
        set(value) = prefs.edit { putBoolean("hide_in_vault", value) }

    /**
     * B6 (front/back camera switch) — persisted lens preference. `true` means
     * the front camera was last selected; `false` (default) means rear. Backed
     * up (a genuine user preference, like `resolution`/`themeMode`), so it lives
     * in `prefs` — NOT the reinstall-reset `runtimePrefs` that `mode` uses.
     * Read once in `RovaRecordingService.onCreate` to seed `currentCameraSelector`
     * and re-written by `flipCamera()`. The P+L snap-to-rear in `setMode` mutates
     * the in-memory selector only and MUST NOT write this pref, so returning to
     * Portrait restores the user's front choice.
     */
    var preferFrontCamera: Boolean
        get() = prefs.getBoolean("prefer_front_camera", false)
        set(value) = prefs.edit { putBoolean("prefer_front_camera", value) }

    companion object {
        /** Backup-excluded SharedPreferences file for runtime state that must NOT survive reinstall. */
        const val RUNTIME_PREFS_NAME = "rova_runtime_prefs"
    }
}

data class RovaPreset(
    val name: String,
    val duration: Int,
    val interval: Int,
    val loopCount: Int, // -1 for infinite
    val resolution: String,
    /** Stable identity. Built-ins use the reserved `builtin.*` prefix; customs use `custom.*`. */
    val id: String = "",
    /** Runtime tag only — never persisted. Customs always deserialize to false. */
    val isBuiltIn: Boolean = false,
)

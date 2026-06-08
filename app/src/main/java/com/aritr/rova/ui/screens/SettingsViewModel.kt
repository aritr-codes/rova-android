package com.aritr.rova.ui.screens

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aritr.rova.data.RovaSettings
import com.aritr.rova.ui.locale.LocaleApplier
import com.aritr.rova.ui.theme.ThemeMode
import com.aritr.rova.ui.theme.ThemeSelection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Single source of truth for all user-facing app settings.
 *
 * Scoped to the Activity (instantiated in MainScreen and passed down) so that
 * RecordScreen and SettingsScreen share the same instance — changes in one are
 * immediately visible in the other without a restart.
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context = application
    private val settings = RovaSettings(context)

    val enableBeeps = MutableStateFlow(settings.enableBeeps)
    val vibrateAlerts = MutableStateFlow(settings.vibrateAlerts)
    val keepScreenOn = MutableStateFlow(settings.keepScreenOn)
    val cameraGuidesEnabled = MutableStateFlow(settings.cameraGuidesEnabled)
    val autoDeleteEnabled = MutableStateFlow(settings.autoDeleteEnabled)
    val autoDeleteKeepLatest = MutableStateFlow(settings.autoDeleteKeepLatest)

    // B1 — recording defaults, surfaced as editable rows in App Settings.
    // These are the SAME persisted prefs the record SettingsSheet edits via
    // RecordViewModel. SharedPreferences is the single source of truth; both
    // ViewModels resume-reseed (see reloadRecordingDefaults + RecordViewModel)
    // so the in-memory copies converge and neither clobbers the other.
    val resolution = MutableStateFlow(settings.resolution)
    val durationSeconds = MutableStateFlow(settings.durationSeconds)
    val intervalMinutes = MutableStateFlow(settings.intervalMinutes)
    val loopCount = MutableStateFlow(settings.loopCount)

    // B2 — app theme mode. Single owner (only this VM writes it); the write-back
    // collector mirrors changes to RovaSettings. The theme root in MainActivity
    // collects this flow above RovaTheme so a change re-themes the whole tree live.
    val themeMode = MutableStateFlow(settings.themeMode)

    // ADR-0028 — liquid-glass theme selection (flat-12 + Follow-System). Single
    // owner; the write-back collector mirrors to RovaSettings. MainActivity
    // collects this above RovaTheme so a change re-themes the tree live. Replaces
    // themeMode as the picker's backing model; themeMode is kept one release for
    // rollback (RovaSettings.themeSelection migrates from it on read).
    val themeSelection = MutableStateFlow(settings.themeSelection)

    // i18n Phase B (ADR-0023) — chosen language tag, null = system. UNLIKE
    // themeMode (which write-backs via a collector), localeTag is persisted
    // SYNCHRONOUSLY in setLocale(): the API 24–32 recreate path reads the value
    // in attachBaseContext, so it must be on disk before apply() recreates.
    val localeTag = MutableStateFlow(settings.localeTag)

    // B4 SAF track (ADR-0024) — human-readable label for the custom save
    // location, or null = no custom folder (uses default internal/MediaStore
    // export tier). Uses explicit setters rather than a collector pair because
    // treeUri + label must be written/cleared atomically; independent collectors
    // would race across the nullable boundary.
    val saveLocationLabel = MutableStateFlow(settings.saveLocationLabel)

    // B5 / ADR-0025 (spec R4) — "hide new recordings in vault" toggle. Uses an
    // EXPLICIT setter ([setHideInVault]) rather than a write-back collector
    // because the ON->OFF transition is auth-gated at the UI layer: the Switch
    // must NOT persist OFF until VaultAuthGate confirms, and a cancelled attempt
    // must snap back to the persisted ON. A collector would persist on every
    // flow assignment, defeating the gate. The flow drives the Switch's checked
    // state so it always reflects the persisted value.
    val hideInVault = MutableStateFlow(settings.hideInVault)

    // ADR-0027 — daily recording window. Explicit setters (not write-back
    // collectors) because every change must re-arm the exact alarms via
    // [com.aritr.rova.service.schedule.ScheduleController.reschedule].
    val scheduleEnabled = MutableStateFlow(settings.scheduleEnabled)
    val scheduleStartMinute = MutableStateFlow(settings.scheduleStartMinuteOfDay)
    val scheduleStopMinute = MutableStateFlow(settings.scheduleStopMinuteOfDay)
    val scheduleWeekdayMask = MutableStateFlow(settings.scheduleWeekdayMask)

    fun setScheduleEnabled(value: Boolean) {
        settings.scheduleEnabled = value
        scheduleEnabled.value = value
        com.aritr.rova.service.schedule.ScheduleController.reschedule(context)
    }

    fun setScheduleStartMinute(value: Int) {
        settings.scheduleStartMinuteOfDay = value
        scheduleStartMinute.value = value
        com.aritr.rova.service.schedule.ScheduleController.reschedule(context)
    }

    fun setScheduleStopMinute(value: Int) {
        settings.scheduleStopMinuteOfDay = value
        scheduleStopMinute.value = value
        com.aritr.rova.service.schedule.ScheduleController.reschedule(context)
    }

    fun setScheduleWeekdayMask(value: Int) {
        settings.scheduleWeekdayMask = value
        scheduleWeekdayMask.value = value
        com.aritr.rova.service.schedule.ScheduleController.reschedule(context)
    }

    init {
        viewModelScope.launch { enableBeeps.collect { settings.enableBeeps = it } }
        viewModelScope.launch { vibrateAlerts.collect { settings.vibrateAlerts = it } }
        viewModelScope.launch { keepScreenOn.collect { settings.keepScreenOn = it } }
        viewModelScope.launch { cameraGuidesEnabled.collect { settings.cameraGuidesEnabled = it } }
        viewModelScope.launch { autoDeleteEnabled.collect { settings.autoDeleteEnabled = it } }
        viewModelScope.launch { autoDeleteKeepLatest.collect { settings.autoDeleteKeepLatest = it } }
        viewModelScope.launch { resolution.collect { settings.resolution = it } }
        viewModelScope.launch { durationSeconds.collect { settings.durationSeconds = it } }
        viewModelScope.launch { intervalMinutes.collect { settings.intervalMinutes = it } }
        viewModelScope.launch { loopCount.collect { settings.loopCount = it } }
        viewModelScope.launch { themeMode.collect { settings.themeMode = it } }
        viewModelScope.launch { themeSelection.collect { settings.themeSelection = it } }
    }

    /**
     * B1 — re-read the recording-default prefs from [RovaSettings] into the
     * flows. Called from SettingsScreen ON_RESUME so values changed by the
     * record sheet while this screen was backgrounded are reflected. When the
     * value is unchanged, MutableStateFlow suppresses the equal assignment
     * (it compares via equals), so the write-back collector does not even
     * re-fire; when it differs, the collector writes the new value to
     * SharedPreferences — exactly the intended update. Either way, harmless.
     */
    fun reloadRecordingDefaults() {
        resolution.value = settings.resolution
        durationSeconds.value = settings.durationSeconds
        intervalMinutes.value = settings.intervalMinutes
        loopCount.value = settings.loopCount
        saveLocationLabel.value = settings.saveLocationLabel
        // B5 — reseed from disk on resume so a value changed elsewhere (or the
        // toggle's persisted state after an auth-gated flip) is reflected. Safe
        // because the setter is the only writer; this is a pure re-read.
        hideInVault.value = settings.hideInVault
    }

    /**
     * B5 / ADR-0025 (spec R4) — persist the vault-hide preference and update the
     * flow. Called directly when turning ON (free) or from the
     * [com.aritr.rova.ui.vault.VaultAuthGate] success / no-lock callbacks when
     * turning OFF (auth-gated at the call site via [toggleRequiresAuth]).
     */
    fun setHideInVault(value: Boolean) {
        settings.hideInVault = value
        hideInVault.value = value
    }

    /**
     * Set the app language. Persists the tag first (the API 24–32 backport reads
     * it in attachBaseContext on recreate), updates the flow, then applies:
     * LocaleManager on API 33+ (framework recreates) or Activity.recreate()
     * on 24–32. [context] must be the Activity.
     */
    fun setLocale(context: Context, tag: String?) {
        settings.localeTag = tag
        localeTag.value = tag
        LocaleApplier.apply(context, tag)
    }

    /**
     * B4 SAF track — persist a validated SAF tree URI + display label as the
     * custom save location. Call AFTER the persistable URI permission has been
     * taken and writeProbe passed. Clears any prior unavailability flag so the
     * fresh pick is treated as healthy.
     */
    fun setSaveLocationFolder(treeUri: String, label: String) {
        settings.saveLocationTreeUri = treeUri
        settings.saveLocationLabel = label
        settings.saveFolderUnavailable = false
        saveLocationLabel.value = label
    }

    /**
     * B4 SAF track — clear the custom save location; subsequent exports use
     * the default internal/MediaStore tier.
     */
    fun clearSaveLocationFolder() {
        settings.saveLocationTreeUri = null
        settings.saveLocationLabel = null
        settings.saveFolderUnavailable = false
        saveLocationLabel.value = null
    }
}

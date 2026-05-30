package com.aritr.rova.ui.screens

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aritr.rova.data.RovaSettings
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
    // Phase 2.1B — UI/persistence-only surface for the Phase 0 key. No
    // export-pipeline consumer reads this yet; Phase 5 will wire the
    // gating consumer. Empty string = use the existing default folder.
    val exportFolderName = MutableStateFlow(settings.exportFolderName)

    // B1 — recording defaults, surfaced as editable rows in App Settings.
    // These are the SAME persisted prefs the record SettingsSheet edits via
    // RecordViewModel. SharedPreferences is the single source of truth; both
    // ViewModels resume-reseed (see reloadRecordingDefaults + RecordViewModel)
    // so the in-memory copies converge and neither clobbers the other.
    val resolution = MutableStateFlow(settings.resolution)
    val durationSeconds = MutableStateFlow(settings.durationSeconds)
    val intervalMinutes = MutableStateFlow(settings.intervalMinutes)
    val loopCount = MutableStateFlow(settings.loopCount)

    init {
        viewModelScope.launch { enableBeeps.collect { settings.enableBeeps = it } }
        viewModelScope.launch { vibrateAlerts.collect { settings.vibrateAlerts = it } }
        viewModelScope.launch { keepScreenOn.collect { settings.keepScreenOn = it } }
        viewModelScope.launch { cameraGuidesEnabled.collect { settings.cameraGuidesEnabled = it } }
        viewModelScope.launch { autoDeleteEnabled.collect { settings.autoDeleteEnabled = it } }
        viewModelScope.launch { autoDeleteKeepLatest.collect { settings.autoDeleteKeepLatest = it } }
        viewModelScope.launch { exportFolderName.collect { settings.exportFolderName = it } }
        viewModelScope.launch { resolution.collect { settings.resolution = it } }
        viewModelScope.launch { durationSeconds.collect { settings.durationSeconds = it } }
        viewModelScope.launch { intervalMinutes.collect { settings.intervalMinutes = it } }
        viewModelScope.launch { loopCount.collect { settings.loopCount = it } }
    }

    /**
     * B1 — re-read the recording-default prefs from [RovaSettings] into the
     * flows. Called from SettingsScreen ON_RESUME so values changed by the
     * record sheet while this screen was backgrounded are reflected. Setting
     * a flow to the just-read value re-fires its write-back collector with an
     * identical value (StateFlow re-emits on equal assignment) — but writing
     * an unchanged value back to SharedPreferences is a no-op, so it is
     * harmless. (Keep collector bodies idempotent for this reason.)
     */
    fun reloadRecordingDefaults() {
        resolution.value = settings.resolution
        durationSeconds.value = settings.durationSeconds
        intervalMinutes.value = settings.intervalMinutes
        loopCount.value = settings.loopCount
    }
}

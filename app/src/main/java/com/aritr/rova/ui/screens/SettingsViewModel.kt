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
    val autoDeleteEnabled = MutableStateFlow(settings.autoDeleteEnabled)
    val autoDeleteKeepLatest = MutableStateFlow(settings.autoDeleteKeepLatest)
    // Phase 2.1B — UI/persistence-only surface for the Phase 0 key. No
    // export-pipeline consumer reads this yet; Phase 5 will wire the
    // gating consumer. Empty string = use the existing default folder.
    val exportFolderName = MutableStateFlow(settings.exportFolderName)

    init {
        viewModelScope.launch { enableBeeps.collect { settings.enableBeeps = it } }
        viewModelScope.launch { vibrateAlerts.collect { settings.vibrateAlerts = it } }
        viewModelScope.launch { keepScreenOn.collect { settings.keepScreenOn = it } }
        viewModelScope.launch { autoDeleteEnabled.collect { settings.autoDeleteEnabled = it } }
        viewModelScope.launch { autoDeleteKeepLatest.collect { settings.autoDeleteKeepLatest = it } }
        viewModelScope.launch { exportFolderName.collect { settings.exportFolderName = it } }
    }
}

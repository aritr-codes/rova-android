package com.aritr.loom.ui.screens

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.camera.core.Preview
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aritr.loom.data.LoomPreset
import com.aritr.loom.data.LoomSettings
import com.aritr.loom.service.LoomRecordingService
import com.aritr.loom.service.LoomServiceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * ViewModel for RecordScreen.
 *
 * A1: Owns all recording settings state (duration, interval, loopCount, resolution, flashMode,
 *     keepScreenOn, enableBeeps, backgroundMode) and persists changes to LoomSettings.
 *
 * A2: Manages the ServiceConnection lifecycle — binds in init, unbinds in onCleared.
 *     Collects state from the service instance (C1) and re-exposes it as a StateFlow.
 */
class RecordViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context = application
    private val settings = LoomSettings(context)

    // --- Service state (C1: pulled from instance, not companion static) ---
    private var serviceBinder: LoomRecordingService.LocalBinder? = null
    private val _serviceState = MutableStateFlow(LoomServiceState())
    val serviceState: StateFlow<LoomServiceState> = _serviceState.asStateFlow()

    // A2: ServiceConnection managed here, not in the Composable
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val localBinder = binder as LoomRecordingService.LocalBinder
            serviceBinder = localBinder
            localBinder.getService().startCameraPreview()
            // Collect from the service instance's StateFlow
            viewModelScope.launch {
                localBinder.getStateFlow().collect { state ->
                    _serviceState.value = state
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            serviceBinder = null
        }
    }

    // --- Recording settings (A1: survive configuration changes) ---
    val duration = MutableStateFlow(settings.durationSeconds)
    val interval = MutableStateFlow(settings.intervalMinutes)
    val loopCount = MutableStateFlow(settings.loopCount)
    val resolution = MutableStateFlow(settings.resolution)
    val flashMode = MutableStateFlow(LoomRecordingService.FLASH_MODE_OFF)

    // --- App settings (Q3: now readable and writable from ViewModel) ---
    val keepScreenOn = MutableStateFlow(settings.keepScreenOn)
    val backgroundMode = MutableStateFlow(settings.backgroundMode)
    val enableBeeps = MutableStateFlow(settings.enableBeeps)

    // --- Presets ---
    private val _customPresets = MutableStateFlow(loadPresetsFromSettings())
    val customPresets: StateFlow<List<LoomPreset>> = _customPresets.asStateFlow()

    init {
        // A2: Bind to the service. BIND_AUTO_CREATE starts the service process if needed
        // but does NOT call onStartCommand — recording only starts when start() is called.
        val intent = Intent(context, LoomRecordingService::class.java)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        // A1: Persist any settings changes back to SharedPreferences
        viewModelScope.launch { duration.collect { settings.durationSeconds = it } }
        viewModelScope.launch { interval.collect { settings.intervalMinutes = it } }
        viewModelScope.launch { loopCount.collect { settings.loopCount = it } }
        viewModelScope.launch { resolution.collect { settings.resolution = it } }
        viewModelScope.launch { keepScreenOn.collect { settings.keepScreenOn = it } }
        viewModelScope.launch { backgroundMode.collect { settings.backgroundMode = it } }
        viewModelScope.launch { enableBeeps.collect { settings.enableBeeps = it } }
    }

    override fun onCleared() {
        super.onCleared()
        try { context.unbindService(serviceConnection) } catch (e: Exception) {}
    }

    // --- Camera / Service actions ---

    fun setSurfaceProvider(surfaceProvider: Preview.SurfaceProvider?) {
        serviceBinder?.getService()?.setSurfaceProvider(surfaceProvider)
    }

    fun flipCamera() {
        serviceBinder?.getService()?.flipCamera()
    }

    fun setFlashMode(mode: Int) {
        flashMode.value = mode
        serviceBinder?.getService()?.setFlashMode(mode)
    }

    // --- Preset management ---

    fun savePreset(name: String) {
        val newPreset = LoomPreset(
            name = name,
            duration = duration.value,
            interval = interval.value,
            loopCount = loopCount.value,
            resolution = resolution.value
        )
        val updated = _customPresets.value + newPreset
        _customPresets.value = updated
        persistPresets(updated)
    }

    fun deletePreset(preset: LoomPreset) {
        val updated = _customPresets.value - preset
        _customPresets.value = updated
        persistPresets(updated)
    }

    private fun loadPresetsFromSettings(): List<LoomPreset> {
        return try {
            val array = JSONArray(settings.customPresetsJson)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                LoomPreset(
                    obj.getString("name"),
                    obj.getInt("duration"),
                    obj.getInt("interval"),
                    obj.getInt("loopCount"),
                    obj.getString("resolution")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun persistPresets(presets: List<LoomPreset>) {
        val array = JSONArray()
        presets.forEach { p ->
            array.put(JSONObject().apply {
                put("name", p.name)
                put("duration", p.duration)
                put("interval", p.interval)
                put("loopCount", p.loopCount)
                put("resolution", p.resolution)
            })
        }
        settings.customPresetsJson = array.toString()
    }
}

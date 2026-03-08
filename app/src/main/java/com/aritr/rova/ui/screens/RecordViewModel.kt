package com.aritr.rova.ui.screens

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.camera.core.Preview
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aritr.rova.data.RovaPreset
import com.aritr.rova.data.RovaSettings
import com.aritr.rova.service.RovaRecordingService
import com.aritr.rova.service.RovaServiceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * ViewModel for RecordScreen.
 *
 * A1: Owns recording settings state (duration, interval, loopCount, resolution, flashMode)
 *     and persists changes to RovaSettings. App-wide settings (keepScreenOn, enableBeeps,
 *     backgroundMode, vibrateAlerts) are owned by SettingsViewModel.
 *
 * A2: Manages the ServiceConnection lifecycle — binds in init, unbinds in onCleared.
 *     Collects state from the service instance (C1) and re-exposes it as a StateFlow.
 */
class RecordViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context = application
    private val settings = RovaSettings(context)

    // --- Service state (C1: pulled from instance, not companion static) ---
    private var serviceBinder: RovaRecordingService.LocalBinder? = null
    private val _serviceState = MutableStateFlow(RovaServiceState())
    val serviceState: StateFlow<RovaServiceState> = _serviceState.asStateFlow()
    private var pendingSurfaceProvider: Preview.SurfaceProvider? = null

    // A2: ServiceConnection managed here, not in the Composable
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val localBinder = binder as RovaRecordingService.LocalBinder
            serviceBinder = localBinder
            // Apply any surface provider that was set before the service connected
            pendingSurfaceProvider?.let { localBinder.getService().setSurfaceProvider(it) }
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
    val flashMode = MutableStateFlow(RovaRecordingService.FLASH_MODE_OFF)

    // --- Presets ---
    private val _customPresets = MutableStateFlow(loadPresetsFromSettings())
    val customPresets: StateFlow<List<RovaPreset>> = _customPresets.asStateFlow()

    init {
        // A2: Bind to the service. BIND_AUTO_CREATE starts the service process if needed
        // but does NOT call onStartCommand — recording only starts when start() is called.
        val intent = Intent(context, RovaRecordingService::class.java)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        // A1: Persist any settings changes back to SharedPreferences
        viewModelScope.launch { duration.collect { settings.durationSeconds = it } }
        viewModelScope.launch { interval.collect { settings.intervalMinutes = it } }
        viewModelScope.launch { loopCount.collect { settings.loopCount = it } }
        viewModelScope.launch { resolution.collect { settings.resolution = it } }
    }

    override fun onCleared() {
        super.onCleared()
        try { context.unbindService(serviceConnection) } catch (e: Exception) {}
    }

    // --- Camera / Service actions ---

    fun setSurfaceProvider(surfaceProvider: Preview.SurfaceProvider?) {
        pendingSurfaceProvider = surfaceProvider
        serviceBinder?.getService()?.setSurfaceProvider(surfaceProvider)
    }

    fun clearRecordingError() {
        serviceBinder?.getService()?.clearRecordingError()
    }

    fun stopCameraPreview() {
        serviceBinder?.getService()?.stopCameraPreview()
    }

    fun startCameraPreview() {
        serviceBinder?.getService()?.startCameraPreview()
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
        val newPreset = RovaPreset(
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

    fun deletePreset(preset: RovaPreset) {
        val updated = _customPresets.value - preset
        _customPresets.value = updated
        persistPresets(updated)
    }

    private fun loadPresetsFromSettings(): List<RovaPreset> {
        return try {
            val array = JSONArray(settings.customPresetsJson)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                RovaPreset(
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

    private fun persistPresets(presets: List<RovaPreset>) {
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

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
import com.aritr.rova.service.ServiceController
import com.aritr.rova.utils.RovaLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.aritr.rova.data.BuiltInPresets
import com.aritr.rova.data.FirstRunSeedPolicy
import com.aritr.rova.data.PresetJson
import com.aritr.rova.data.PresetMatcher
import com.aritr.rova.data.PresetSaveValidator
import com.aritr.rova.data.QualityPresets
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

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
    private var serviceStateJob: Job? = null
    private val _serviceState = MutableStateFlow(RovaServiceState())
    val serviceState: StateFlow<RovaServiceState> = _serviceState.asStateFlow()
    private var pendingSurfaceProvider: Preview.SurfaceProvider? = null

    // Cold-start replay buffer for the P+L dual-preview surfaces. Mirrors
    // [pendingSurfaceProvider]: the DualPreviewZone TextureViews can fire
    // their one-shot onSurfaceTextureAvailable BEFORE the ServiceConnection
    // completes, at which point [attachDualPreview]'s `serviceBinder?` is
    // null and the registration would be lost — the TextureView listener
    // never re-fires, so the EglRouter never receives the preview targets
    // and both zones stay black until a mode-toggle re-mounts the zone.
    // Buffered here and replayed in onServiceConnected. Keyed by side so a
    // re-register (size change) cleanly replaces the prior entry.
    // Main-thread only — same single-threaded assumption as
    // [pendingSurfaceProvider] (TextureView callbacks and onServiceConnected
    // are all delivered on the main thread).
    private val pendingDualPreviews =
        mutableMapOf<com.aritr.rova.service.dualrecord.VideoSide, Triple<android.view.Surface, Int, Int>>()

    // A2: ServiceConnection managed here, not in the Composable
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val localBinder = binder as RovaRecordingService.LocalBinder
            serviceBinder = localBinder
            // Apply any surface provider that was set before the service connected
            pendingSurfaceProvider?.let { localBinder.getService().setSurfaceProvider(it) }
            // Replay any P+L dual-preview surfaces registered before the
            // service bound (cold-start race — see [pendingDualPreviews]).
            // Must precede startCameraPreview() so the surfaces are already
            // in the service's pendingPreviewSurfaces buffer when
            // setupDualCamera builds the recorder and replays them onto it.
            pendingDualPreviews.forEach { (side, t) ->
                localBinder.getService().attachDualPreview(side, t.first, t.second, t.third)
            }
            localBinder.getService().startCameraPreview()
            // Collect from the service instance's StateFlow
            serviceStateJob?.cancel()
            serviceStateJob = viewModelScope.launch {
                localBinder.getStateFlow().collect { state ->
                    _serviceState.value = state
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            serviceStateJob?.cancel()
            serviceStateJob = null
            serviceBinder = null
        }
    }

    // --- Recording settings (A1: survive configuration changes) ---
    val duration = MutableStateFlow(settings.durationSeconds)
    val interval = MutableStateFlow(settings.intervalMinutes)
    val loopCount = MutableStateFlow(settings.loopCount)
    val resolution = MutableStateFlow(settings.resolution)
    val flashMode = MutableStateFlow(RovaRecordingService.FLASH_MODE_OFF)
    val mode = MutableStateFlow(settings.captureTopology)

    // --- Presets ---
    private val _customPresets = MutableStateFlow(loadPresetsFromSettings())
    val customPresets: StateFlow<List<RovaPreset>> = _customPresets.asStateFlow()

    // --- Settings sheet visibility.
    //
    // Opened when the user taps the settings card on the idle layout;
    // drives the SettingsSheet camera-peek panel in RecordScreen.
    private val _combinedSettingsOpen = MutableStateFlow(false)
    val combinedSettingsOpen: StateFlow<Boolean> = _combinedSettingsOpen.asStateFlow()

    fun openSettingsSheet() { _combinedSettingsOpen.value = true }
    fun closeSettingsSheet() { _combinedSettingsOpen.value = false }

    // --- Slice 3: presentation-only timer anchors for the active HUD.
    //
    // The recording service does not publish a "session start time"
    // because the data layer measures time in clip indices and
    // countdown ticks rather than wall-clock duration. The active HUD
    // wants a wall-clock session-elapsed timer, so the VM observes
    // the rising/falling edges of the service flags and stamps an
    // anchor on each transition. The composable derives elapsed
    // seconds from `System.currentTimeMillis() - anchor` while
    // ticking 1 Hz; the VM never holds a per-second timer of its
    // own.
    //
    // Persistence: anchors live in process memory only. Process
    // death mid-session re-anchors at re-attach time — the elapsed
    // timer under-counts by the off-process gap, which is acceptable
    // for a v1 presentation timer and avoids widening the service
    // contract or manifest schema.
    private val _sessionAnchorMillis = MutableStateFlow<Long?>(null)
    val sessionAnchorMillis: StateFlow<Long?> = _sessionAnchorMillis.asStateFlow()

    private val _clipAnchorMillis = MutableStateFlow<Long?>(null)
    val clipAnchorMillis: StateFlow<Long?> = _clipAnchorMillis.asStateFlow()

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

        // Slice 3 — anchor wall-clock timestamps for the HUD timers.
        // Sets on the rising edge of each flag, clears on the falling
        // edge. The first observation also sets the anchor if a
        // session is already live at re-attach (process recreation).
        viewModelScope.launch {
            _serviceState.collect { state ->
                if (state.isPeriodicActive) {
                    if (_sessionAnchorMillis.value == null) {
                        _sessionAnchorMillis.value = System.currentTimeMillis()
                    }
                } else {
                    if (_sessionAnchorMillis.value != null) {
                        _sessionAnchorMillis.value = null
                    }
                }
                if (state.isRecording) {
                    if (_clipAnchorMillis.value == null) {
                        _clipAnchorMillis.value = System.currentTimeMillis()
                    }
                } else {
                    if (_clipAnchorMillis.value != null) {
                        _clipAnchorMillis.value = null
                    }
                }
            }
        }

        // ADR-0026 — one-time first-run seed of the Standard preset. Gated so an
        // existing user's config is never clobbered (FirstRunSeedPolicy).
        if (FirstRunSeedPolicy.shouldSeed(settings.presetSeeded, settings.hasAnyRecordingPref())) {
            val std = BuiltInPresets.default
            settings.durationSeconds = std.duration
            settings.intervalMinutes = std.interval
            settings.loopCount = std.loopCount
            settings.resolution = std.resolution
            duration.value = std.duration
            interval.value = std.interval
            loopCount.value = std.loopCount
            resolution.value = std.resolution
        }
        settings.presetSeeded = true
    }

    /**
     * B1 — re-read the recording-default prefs from [RovaSettings] into the
     * existing flows. Called from RecordScreen ON_RESUME so a change made in
     * App Settings while the record screen was backgrounded is reflected, and
     * a later stepper nudge does not write back a stale value (clobber fix).
     *
     * Excludes `mode` (it has its own setMode() persist path) and `flashMode`
     * (session-volatile, not a RovaSettings pref).
     */
    fun reloadRecordingDefaults() {
        // Don't reseed mid-session: the running session's config is fixed at
        // start; overwriting these flows would desync the HUD + storage-signal
        // recompute from the live session. Same guard precedent as cycleMode().
        val s = _serviceState.value
        if (s.isPeriodicActive || s.isMerging) return
        duration.value = settings.durationSeconds
        interval.value = settings.intervalMinutes
        loopCount.value = settings.loopCount
        resolution.value = settings.resolution
    }

    override fun onCleared() {
        serviceStateJob?.cancel()
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

    /**
     * Phase 6.1c — DualPreviewZone TextureView attached. Buffers the
     * surface in [pendingDualPreviews] AND forwards to the service if it
     * is already bound. The buffer covers the cold-start race where the
     * TextureView becomes available before the ServiceConnection
     * completes; onServiceConnected replays the buffer.
     */
    fun attachDualPreview(
        side: com.aritr.rova.service.dualrecord.VideoSide,
        surface: android.view.Surface,
        width: Int,
        height: Int,
    ) {
        pendingDualPreviews[side] = Triple(surface, width, height)
        serviceBinder?.getService()?.attachDualPreview(side, surface, width, height)
    }

    /**
     * Phase 6.1c — DualPreviewZone TextureView detached. Drops the
     * buffered surface so a later onServiceConnected does not replay a
     * dead Surface, and tells the service to remove its target.
     */
    fun detachDualPreview(side: com.aritr.rova.service.dualrecord.VideoSide) {
        pendingDualPreviews.remove(side)
        serviceBinder?.getService()?.detachDualPreview(side)
    }

    fun setMode(mode: String) {
        settings.captureTopology = mode                        // (1) prefs commit
        this.mode.value = mode                                // (2) StateFlow update
        serviceBinder?.getService()?.setMode(mode)            // (3) service rebind
    }

    /**
     * Slice B — Mode tap-cycle. Reads the current mode + session-lock
     * state and writes the next mode via [setMode]. No-op during an
     * active session (matches the existing sheet behaviour: Mode row
     * hidden / non-interactive when periodic active or merging).
     *
     * Cycle order is delegated to [cycleModeNext] (pure helper) so the
     * VM stays a thin shim around the existing persistence pipeline.
     *
     * Called from [RecordSettingsCard]'s ModeCycleChip via
     * `onCycleMode = { viewModel.cycleMode() }` in [RecordScreen].
     */
    fun cycleMode() {
        val s = _serviceState.value
        if (s.isPeriodicActive || s.isMerging) return
        setMode(cycleModeNext(mode.value))
    }

    fun setFlashMode(mode: Int) {
        flashMode.value = mode
        serviceBinder?.getService()?.setFlashMode(mode)
    }

    /**
     * Phase 1.3 — UI stop entrypoint.
     *
     * Resolves the live sessionId via [ServiceController.current] (the
     * canonical source — avoids racing against a stale ViewModel field
     * after a config change or rebind). Drops if no live controller; the
     * notification cannot show its STOP action in that state either, so
     * the user has no observable affordance to invoke this from a
     * sessionless service. Logged for diagnostic visibility.
     *
     * Sends through [RovaRecordingService.stop] which broadcasts to
     * [com.aritr.rova.service.RovaStopReceiver]. No service start, no
     * bind — required by ROADMAP_v4 §1.3 and Android 12+ FGS-from-
     * background restrictions.
     */
    fun stopRecording() {
        val sid = ServiceController.current()?.sessionId
        if (sid == null) {
            RovaLog.w("RecordViewModel.stopRecording: no live controller; ignoring tap")
            return
        }
        RovaRecordingService.stop(context, sid)
    }

    // --- Preset management ---

    fun savePreset(name: String) {
        // Mid-session guard — same as applyPreset. UI gates this behind `editable`,
        // but a stale dialog, a future caller, or a test could still reach it (codex review).
        val s = _serviceState.value
        if (s.isPeriodicActive || s.isMerging) return
        val trimmed = name.trim()
        val d = duration.value
        val i = interval.value
        val l = loopCount.value
        val r = resolution.value
        // Resolution must be canonicalizable, else the saved custom could never
        // match active (codex review).
        if (QualityPresets.canonicalize(r) == null) return
        val current = _customPresets.value
        // Defensive: the dialog already blocks these; guard the contract.
        if (PresetSaveValidator.validateName(trimmed, current) != PresetSaveValidator.Result.Ok) return
        // Tuple-duplicate guard: unreachable via the conditional + Save chip (only
        // shown when activePresetId == null), but guard the API anyway.
        if (PresetMatcher.matchActive(current, d, i, l, r) != null) return
        val updated = current + RovaPreset(
            name = trimmed, duration = d, interval = i, loopCount = l, resolution = r,
        )
        _customPresets.value = updated
        persistPresets(updated) // encode() stamps a stable custom.* id
    }

    fun deletePreset(preset: RovaPreset) {
        // Same mid-session guard (codex review — VM-side, not only UI).
        val s = _serviceState.value
        if (s.isPeriodicActive || s.isMerging) return
        val updated = _customPresets.value - preset
        _customPresets.value = updated
        persistPresets(updated)
    }

    private fun loadPresetsFromSettings(): List<RovaPreset> =
        PresetJson.decode(settings.customPresetsJson)

    private fun persistPresets(presets: List<RovaPreset>) {
        settings.customPresetsJson = PresetJson.encode(presets)
    }

    /** ADR-0026 — apply a preset's config bundle. Does NOT touch orientation. */
    fun applyPreset(preset: RovaPreset) {
        // Same mid-session guard as reloadRecordingDefaults()/cycleMode().
        val s = _serviceState.value
        if (s.isPeriodicActive || s.isMerging) return
        duration.value = preset.duration
        interval.value = preset.interval
        loopCount.value = preset.loopCount
        resolution.value = preset.resolution
    }

    /** Built-ins first, then user customs. */
    val allPresets: StateFlow<List<RovaPreset>> =
        _customPresets.map { BuiltInPresets.all + it }
            .stateIn(viewModelScope, SharingStarted.Eagerly, BuiltInPresets.all)

    /** The active preset id — built-in value match first, then custom; null = "Custom". */
    val activePresetId: StateFlow<String?> =
        combine(duration, interval, loopCount, resolution, _customPresets) { d, i, l, r, customs ->
            PresetMatcher.matchActive(customs, d, i, l, r)
        }.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            PresetMatcher.matchActive(
                _customPresets.value, duration.value, interval.value, loopCount.value, resolution.value,
            ),
        )
}

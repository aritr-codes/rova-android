package com.aritr.rova.ui.signals

import androidx.camera.core.CameraState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase 3.5 (NEW_UI_BACKEND_REPLAN §5 row 3.5) — runtime camera-health
 * buckets surfaced to the Phase 4 WarningCenter. Mapped from
 * [CameraState.StateError.getCode]; the `CameraState.ERROR_*` ints are
 * compile-time `static final int`, so referencing them here keeps this
 * file JVM-pure (no CameraX runtime touched) — the same posture as
 * [ThermalStatus] referencing `PowerManager.THERMAL_STATUS_*`. Unknown
 * codes map defensively to [OTHER_ERROR] (a future CameraX may extend
 * the range; the signal must not crash).
 */
enum class CameraSignalState {
    /**
     * No active CameraX bind — no recording session in progress.
     * Initial value, and the value after the recording service unbinds
     * (`onCameraUnbound`). The CameraX [CameraState.Type] CLOSED state
     * does NOT drive this — the service's own unbind does.
     */
    UNKNOWN,

    /** Camera bound and healthy ([CameraState] carries no `StateError`). */
    OK,

    /**
     * Another app holds the camera, or the device is at its
     * concurrent-camera limit. Maps to the "Camera in use" banner in
     * Phase 4. `ERROR_CAMERA_IN_USE` | `ERROR_MAX_CAMERAS_IN_USE`.
     */
    IN_USE,

    /**
     * Camera blocked by device policy / Do-Not-Disturb. A distinct
     * Phase 4 banner ("Camera disabled"). `ERROR_CAMERA_DISABLED` |
     * `ERROR_DO_NOT_DISTURB_MODE_ENABLED`.
     */
    DISABLED,

    /**
     * Recoverable / stream-config / fatal CameraX errors, plus any
     * unknown code (forward-compat). These largely overlap the existing
     * INIT_FAILED exception path in the recording service (the fatal
     * bind throw); the signal surfaces them for completeness and Phase 4
     * maps OTHER_ERROR to the generic recovery card, not a new banner.
     * `ERROR_OTHER_RECOVERABLE_ERROR` | `ERROR_STREAM_CONFIG` |
     * `ERROR_CAMERA_FATAL_ERROR` | any unknown int.
     */
    OTHER_ERROR
}

/**
 * Phase 3.5 (NEW_UI_BACKEND_REPLAN §5 row 3.5) — leaf signal exposing
 * runtime camera-health as a [StateFlow]. The Phase 4
 * WarningCenterViewModel is the consumer; this slice ships the SIGNAL
 * ONLY (no banner, no recording-start gate, no Phase 4 mapper).
 *
 * Feed contract: the recording service ([com.aritr.rova.service.RovaRecordingService])
 * observes `Camera.cameraInfo.cameraState` after each `bindToLifecycle`
 * and calls [onCameraState] with `StateError.getCode()` (or `null` when
 * the [CameraState] carries no error). It calls [onCameraUnbound] at
 * each `unbindAll` / teardown site so the signal does not report a
 * stale [CameraSignalState.OK] after a session ends. There is no
 * constructor seam — unlike the SDK-gated signals there is nothing to
 * inject; the signal is purely fed.
 *
 * Relationship to ADR 0006: additive. The fatal-bind exception path
 * (the recording service's `markInitFailedAndStop` + the camera-ready
 * timeout) is unchanged — this signal covers POST-bind runtime errors
 * (e.g. another app steals the camera mid-session), which that path
 * does not.
 *
 * Idempotent — [MutableStateFlow] dedupes equal values, so a repeated
 * same-state feed produces no spurious emission.
 */
class CameraStateSignal {

    private val _state: MutableStateFlow<CameraSignalState> =
        MutableStateFlow(CameraSignalState.UNKNOWN)
    val state: StateFlow<CameraSignalState> = _state.asStateFlow()

    /**
     * Fed by the recording service's `cameraState` observer.
     * [errorCode] is `CameraState.StateError.getCode()`, or `null` when
     * the [CameraState] carries no error (camera healthy).
     */
    fun onCameraState(errorCode: Int?) {
        _state.value = map(errorCode)
    }

    /**
     * The recording service has unbound (`unbindAll`) or is tearing
     * down — there is no live camera to report on.
     */
    fun onCameraUnbound() {
        _state.value = CameraSignalState.UNKNOWN
    }

    private fun map(errorCode: Int?): CameraSignalState = when (errorCode) {
        null -> CameraSignalState.OK
        CameraState.ERROR_CAMERA_IN_USE,
        CameraState.ERROR_MAX_CAMERAS_IN_USE -> CameraSignalState.IN_USE
        CameraState.ERROR_CAMERA_DISABLED,
        CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED -> CameraSignalState.DISABLED
        // ERROR_OTHER_RECOVERABLE_ERROR / ERROR_STREAM_CONFIG /
        // ERROR_CAMERA_FATAL_ERROR + any unknown int (forward-compat).
        else -> CameraSignalState.OTHER_ERROR
    }
}

package com.aritr.rova.ui.signals

import android.content.Context
import android.os.SystemClock
import android.view.OrientationEventListener
import android.view.Surface
import com.aritr.rova.service.orientation.OrientationSnapState
import com.aritr.rova.service.orientation.snapOrientation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn

/**
 * PR-ε (spec §2.2) — UI-side device-orientation seam. Emits the SNAPPED
 * Surface.ROTATION_* (0/1/2/3) for the physical device pose, independent of the
 * window (which is orientation-locked on compact — Display.rotation is frozen
 * there and useless; research §2).
 *
 * Reuses PR-α's [snapOrientation] (dwell ORIENTATION_DWELL_MS + dead-band
 * ORIENTATION_HYSTERESIS_DEG) — ONE snap implementation in the codebase; the
 * service seam and this signal must not drift (spec §2.2).
 *
 * ORIENTATION_UNKNOWN (device flat) is handled inside [snapOrientation]: state
 * unchanged -> hold last value, never spin while flat (research §2).
 *
 * Listener lifecycle follows collection (WhileSubscribed): enabled while the
 * record chrome collects, disabled otherwise — the official enable/disable
 * pattern mapped onto flow subscription.
 */
class DeviceOrientationSignal(
    private val appContext: Context,
    scope: CoroutineScope,
) {
    /**
     * Last dwell-confirmed rotation, retained across flow restarts. The
     * callbackFlow relaunches on every WhileSubscribed resubscription (tab
     * return, screen-off/on); seeding from ROTATION_0 and emitting it would
     * spin the chrome to portrait and back while held landscape (final-review
     * finding #1). Seed from the retained value and emit ONLY from the
     * listener — stateIn supplies the cold-start initial value.
     */
    @Volatile
    private var lastStable: Int = Surface.ROTATION_0

    val snappedRotation: StateFlow<Int> = callbackFlow {
        var state = OrientationSnapState(
            stable = lastStable, candidate = null, candidateSinceMs = null,
        )
        val listener = object : OrientationEventListener(appContext) {
            override fun onOrientationChanged(orientation: Int) {
                state = snapOrientation(
                    degrees = orientation,
                    current = state,
                    nowMs = SystemClock.elapsedRealtime(),
                )
                lastStable = state.stable
                trySend(state.stable)
            }
        }
        if (listener.canDetectOrientation()) listener.enable()
        awaitClose { listener.disable() }
    }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 2_000), Surface.ROTATION_0)
}

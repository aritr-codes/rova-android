package com.aritr.rova.ui.signals

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase 3.4 (NEW_UI_BACKEND_REPLAN §5 row 3.4) — typed wrapper for
 * [PowerManager.getCurrentThermalStatus]. Order mirrors the
 * `PowerManager.THERMAL_STATUS_*` ints `0..6` so [fromRaw] is a
 * direct table lookup. Unknown ints map defensively to [NONE] —
 * a future OS may extend the range, and the signal must not crash.
 */
enum class ThermalStatus {
    NONE, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, SHUTDOWN;

    companion object {
        fun fromRaw(raw: Int): ThermalStatus = when (raw) {
            0 -> NONE
            1 -> LIGHT
            2 -> MODERATE
            3 -> SEVERE
            4 -> CRITICAL
            5 -> EMERGENCY
            6 -> SHUTDOWN
            else -> NONE
        }
    }
}

/**
 * Phase 3.4 (NEW_UI_BACKEND_REPLAN §5 row 3.4) — leaf signal exposing
 * the device's current thermal status as a [StateFlow]. The Phase 4
 * WarningCenterViewModel is the consumer; this slice ships the SIGNAL
 * ONLY (no listener registration, no banner, no recording-start gate).
 *
 * SDK gate: pre-API-29, [PowerManager.getCurrentThermalStatus] does
 * not exist — StateFlow value is constant [ThermalStatus.NONE].
 *
 * Refresh contract: thermal-status changes do NOT fire a system
 * broadcast in this slice (a `OnThermalStatusChangedListener` is
 * Phase 4 territory). Callers re-call [refresh] from the host
 * Activity's [androidx.lifecycle.Lifecycle.Event.ON_RESUME].
 * Idempotent — [MutableStateFlow] dedupes equal values, so repeat
 * calls under the same status produce no spurious emission.
 *
 * Testability: [sdkInt] and [currentStatus] are constructor seams so
 * the unit test never touches a real [Context] / [PowerManager] —
 * same shape as
 * [com.aritr.rova.ui.signals.NotificationPermissionSignal]
 * (`isGranted`),
 * [com.aritr.rova.ui.screens.onboarding.OnboardingViewModel]
 * (`markCompleted`) and
 * [com.aritr.rova.ui.screens.player.PlayerStateEmitter] (`attach`).
 * Production callers use [forContext].
 */
class ThermalStatusSignal(
    private val sdkInt: Int,
    private val currentStatus: () -> Int,
    /**
     * Phase 4 Slice 3 — register a thermal-status push listener.
     * Receives a callback `(rawInt) -> Unit` and returns an opaque token
     * (the actual [PowerManager.OnThermalStatusChangedListener] object) that
     * [removeListener] accepts to unregister. Default no-op (returns [callback]
     * as a pass-through token) preserves pre-Slice-3 call sites (in particular,
     * every existing ThermalStatusSignalTest fixture). Production wiring in
     * [forContext] wraps the callback in a real listener inside an SDK guard and
     * returns that listener as the token.
     */
    private val addListener: ((Int) -> Unit) -> Any = { it },
    /**
     * Phase 4 Slice 3 — unregister the opaque token returned by [addListener].
     * Default no-op symmetric with [addListener].
     */
    private val removeListener: (Any) -> Unit = {},
) {

    private val _state: MutableStateFlow<ThermalStatus> = MutableStateFlow(currentValue())
    val state: StateFlow<ThermalStatus> = _state.asStateFlow()

    /**
     * Re-read the thermal status and publish if changed. Call from
     * `ON_RESUME`. Idempotent on unchanged status.
     */
    fun refresh() {
        _state.value = currentValue()
    }

    /** Opaque listener token held so [stop] can unregister it. */
    private var registeredListenerToken: Any? = null

    /**
     * Phase 4 Slice 3 — begin receiving real-time thermal updates.
     * Idempotent; pre-API-29 no-op.
     *
     * Call from `RovaApp.onCreate` (process-scoped). The OS releases the
     * registration on process death, so no explicit [stop] from app
     * teardown is required (Android does not reliably invoke
     * Application.onTerminate on production devices).
     */
    fun start() {
        if (sdkInt < Build.VERSION_CODES.Q) return
        if (registeredListenerToken != null) return
        val callback: (Int) -> Unit = { raw ->
            _state.value = ThermalStatus.fromRaw(raw)
        }
        registeredListenerToken = addListener(callback)
    }

    /**
     * Phase 4 Slice 3 — unregister the listener captured by [start].
     * Idempotent. Defensive — production never calls this (process death
     * does the cleanup), but tests use it to assert teardown.
     */
    fun stop() {
        val token = registeredListenerToken ?: return
        removeListener(token)
        registeredListenerToken = null
    }

    private fun currentValue(): ThermalStatus =
        if (sdkInt < Build.VERSION_CODES.Q) ThermalStatus.NONE
        else ThermalStatus.fromRaw(currentStatus())

    companion object {
        /**
         * Production constructor. Captures the application context in
         * the seam closure so the closure does not retain an Activity.
         * Reads [Build.VERSION.SDK_INT] once — SDK does not regress
         * for a running process.
         */
        fun forContext(context: Context): ThermalStatusSignal {
            val app = context.applicationContext
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            return ThermalStatusSignal(
                sdkInt = Build.VERSION.SDK_INT,
                // Inner SDK guard mirrors `currentValue()` — logically
                // redundant (the seam is not invoked when sdkInt < Q)
                // but local to the API-29 method reference so lint's
                // flow analysis suppresses NewApi for
                // `PowerManager.getCurrentThermalStatus`. Same shape
                // as NotificationPermissionSignal.forContext.
                currentStatus = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        pm.currentThermalStatus
                    } else {
                        0
                    }
                },
                addListener = { callback ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        registerThermalListener(pm, callback) ?: callback
                    } else {
                        callback
                    }
                },
                removeListener = { token ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        unregisterThermalListener(pm, token)
                    }
                }
            )
        }

        @RequiresApi(Build.VERSION_CODES.Q)
        private fun registerThermalListener(
            pm: PowerManager,
            callback: (Int) -> Unit,
        ): PowerManager.OnThermalStatusChangedListener? {
            val listener = PowerManager.OnThermalStatusChangedListener { raw -> callback(raw) }
            val mainExecutor = java.util.concurrent.Executor { r -> Handler(Looper.getMainLooper()).post(r) }
            return runCatching {
                pm.addThermalStatusListener(mainExecutor, listener)
                listener
            }
                .onFailure {
                    com.aritr.rova.utils.RovaLog.w(
                        "ThermalStatusSignal.addListener threw; falling back to ON_RESUME poll",
                        it
                    )
                }
                .getOrNull()
        }

        @RequiresApi(Build.VERSION_CODES.Q)
        private fun unregisterThermalListener(pm: PowerManager, token: Any) {
            if (token !is PowerManager.OnThermalStatusChangedListener) return
            runCatching { pm.removeThermalStatusListener(token) }
                .onFailure {
                    com.aritr.rova.utils.RovaLog.w(
                        "ThermalStatusSignal.removeListener threw",
                        it
                    )
                }
        }
    }
}

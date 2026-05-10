package com.aritr.rova.ui.signals

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase 3.2 (NEW_UI_BACKEND_REPLAN §5 row 3.2) — leaf signal exposing
 * the current `POST_NOTIFICATIONS` grant state as a [StateFlow]. The
 * Phase 4 WarningCenterViewModel is the consumer; this slice ships
 * the SIGNAL ONLY.
 *
 * SDK gate: pre-API-33, the permission does not exist — StateFlow
 * value is constant `true` (notifications ungated).
 *
 * Refresh contract: permission grants do NOT fire a system broadcast.
 * Callers re-call [refresh] from the host Activity's
 * [androidx.lifecycle.Lifecycle.Event.ON_RESUME]. Idempotent —
 * [MutableStateFlow] dedupes equal values, so repeat calls with the
 * same grant produce no spurious emission.
 *
 * Testability: [sdkInt] and [isGranted] are constructor seams so the
 * unit test never touches a real [Context] — same shape as
 * [com.aritr.rova.ui.screens.onboarding.OnboardingViewModel]
 * (`markCompleted`) and
 * [com.aritr.rova.ui.screens.player.PlayerStateEmitter] (`attach`).
 * Production callers use [forContext].
 */
class NotificationPermissionSignal(
    private val sdkInt: Int,
    private val isGranted: () -> Boolean
) {

    private val _state: MutableStateFlow<Boolean> = MutableStateFlow(currentValue())
    val state: StateFlow<Boolean> = _state.asStateFlow()

    /**
     * Re-read the permission state and publish if changed. Call from
     * `ON_RESUME`. Idempotent on unchanged grant.
     */
    fun refresh() {
        _state.value = currentValue()
    }

    private fun currentValue(): Boolean =
        if (sdkInt < Build.VERSION_CODES.TIRAMISU) true else isGranted()

    companion object {
        /**
         * Production constructor. Captures the application context in
         * the seam closure so the closure does not retain an Activity.
         * Reads [Build.VERSION.SDK_INT] once — SDK does not regress
         * for a running process.
         */
        fun forContext(context: Context): NotificationPermissionSignal {
            val app = context.applicationContext
            return NotificationPermissionSignal(
                sdkInt = Build.VERSION.SDK_INT,
                // Inner SDK guard mirrors `currentValue()` — logically
                // redundant (the seam is not invoked when sdkInt <
                // TIRAMISU) but local to the constant reference so
                // lint's flow analysis suppresses InlinedApi for
                // `Manifest.permission.POST_NOTIFICATIONS` (added in
                // API 33). Same shape as OnboardingScreen's launch site.
                isGranted = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        ContextCompat.checkSelfPermission(
                            app,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) == PackageManager.PERMISSION_GRANTED
                    } else {
                        true
                    }
                }
            )
        }
    }
}

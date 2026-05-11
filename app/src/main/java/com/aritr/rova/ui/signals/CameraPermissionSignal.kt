package com.aritr.rova.ui.signals

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase 4.1b (NEW_UI_BACKEND_REPLAN row 1) — leaf signal: is CAMERA
 * permission currently granted? `true` = granted = good (the WarningCenter
 * raises no camera-permission banner; the Record screen leaves Start
 * enabled). CAMERA is API 1 — no SDK gate (the [NotificationPermissionSignal]
 * shape minus the `sdkInt` parameter). Refresh contract: poll from the host
 * Activity's ON_RESUME and whenever the in-app permission state changes (an
 * in-app grant does not pause the Activity). Idempotent — [MutableStateFlow]
 * dedupes equal values. Ctor seam keeps the unit test off a real Context.
 */
class CameraPermissionSignal(
    private val isGranted: () -> Boolean
) {
    private val _state: MutableStateFlow<Boolean> = MutableStateFlow(isGranted())
    val state: StateFlow<Boolean> = _state.asStateFlow()

    /** Re-read the grant and publish if changed. Call from ON_RESUME / on permission-state change. */
    fun refresh() { _state.value = isGranted() }

    companion object {
        fun forContext(context: Context): CameraPermissionSignal {
            val app = context.applicationContext
            return CameraPermissionSignal(isGranted = {
                ContextCompat.checkSelfPermission(app, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
            })
        }
    }
}

package com.aritr.rova.ui.signals

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase 4.1b (NEW_UI_BACKEND_REPLAN row 12) — leaf signal: is RECORD_AUDIO
 * permission currently granted? `false` drives the `MICROPHONE_DENIED`
 * advisory banner — idle: "clips will be video-only"; mid-session: confirms
 * the session is running in `AudioMode.VIDEO_ONLY` (the recording service
 * picks that mode at start when RECORD_AUDIO is denied, see
 * [com.aritr.rova.data.AudioMode]). RECORD_AUDIO is API 1 — no SDK gate.
 * ADVISORY tier — never gates Start. Refresh contract: poll from the host
 * Activity's ON_RESUME and on permission-state change. Ctor seam keeps the
 * unit test off a real Context.
 */
class MicrophonePermissionSignal(
    private val isGranted: () -> Boolean
) {
    private val _state: MutableStateFlow<Boolean> = MutableStateFlow(isGranted())
    val state: StateFlow<Boolean> = _state.asStateFlow()

    /** Re-read the grant and publish if changed. Call from ON_RESUME / on permission-state change. */
    fun refresh() { _state.value = isGranted() }

    companion object {
        fun forContext(context: Context): MicrophonePermissionSignal {
            val app = context.applicationContext
            return MicrophonePermissionSignal(isGranted = {
                ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
            })
        }
    }
}

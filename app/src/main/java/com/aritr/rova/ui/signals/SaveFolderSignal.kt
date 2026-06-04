package com.aritr.rova.ui.signals

import android.content.Context
import com.aritr.rova.data.RovaSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * B4b (ADR-0024) — leaf signal exposing whether the app's custom SAF save
 * folder has been permanently flagged as unusable (gone or permission revoked).
 * `true` = folder unavailable = the WarningCenter raises [com.aritr.rova.ui.warnings.WarningId.SAVE_FOLDER_UNAVAILABLE].
 *
 * Backed by [RovaSettings.saveFolderUnavailable], which the SAF export result
 * handler sets on a permanent grant-revocation or folder-gone failure.
 *
 * Refresh contract: the flag does not change via a system broadcast.
 * Callers re-call [refresh] from the host Activity's
 * [androidx.lifecycle.Lifecycle.Event.ON_RESUME]. Idempotent —
 * [MutableStateFlow] dedupes equal values.
 *
 * Testability: [isUnavailable] is a constructor seam so unit tests never
 * touch a real [Context] — same shape as [BatteryOptimizationSignal].
 * Production callers use [forContext].
 *
 * R1 (codex review): the seam lambda runs once at construction to seed the
 * initial [MutableStateFlow] value; [refresh] runs it on every ON_RESUME.
 */
class SaveFolderSignal(private val isUnavailable: () -> Boolean) {

    private val _state: MutableStateFlow<Boolean> = MutableStateFlow(isUnavailable())
    val state: StateFlow<Boolean> = _state.asStateFlow()

    /** Re-read the flag and publish if changed. Call from ON_RESUME. */
    fun refresh() {
        _state.value = isUnavailable()
    }

    companion object {
        /**
         * Production constructor. Captures application context in the seam
         * closure so the closure does not retain an Activity reference.
         */
        fun forContext(context: Context): SaveFolderSignal {
            val appCtx = context.applicationContext
            return SaveFolderSignal { RovaSettings(appCtx).saveFolderUnavailable }
        }
    }
}

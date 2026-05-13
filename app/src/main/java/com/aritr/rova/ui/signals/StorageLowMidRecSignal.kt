package com.aritr.rova.ui.signals

import android.content.Context
import android.os.StatFs
import com.aritr.rova.RovaApp
import com.aritr.rova.data.StorageEstimator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * R2 (NEW_UI_BACKEND_REPLAN row 17) - leaf signal: is the device's free space running
 * low MID-RECORDING? Drives the STORAGE_LOW_MID_REC warning, surfaced as the
 * mid-recording top banner (ADR 0007 amendment 2026-05-13).
 *
 * Distinct from [com.aritr.rova.ui.signals.StorageSignal]: that one is the START-time
 * hard-block (STORAGE_INSUFFICIENT), comparing whole-session peak against free bytes.
 * This signal is the running-low advisory - fires when free bytes drop below
 * 3 x bytes-per-clip-at-current-quality while a session is active. Threshold is
 * deliberately conservative: by the time the banner fires the user has roughly three
 * clips of headroom; the per-segment storage gate in the service stays the authoritative
 * backstop once the threshold is crossed.
 *
 * Hysteresis: none. If freeBytes oscillates around the threshold the banner flickers;
 * acknowledged tradeoff - fix is the future 4.1c snooze/hysteresis bundle.
 *
 * The host (RecordScreen, T9) calls [poll] every ~30 s while HUD state is in
 * {Recording, Waiting, Merging} and calls [clear] on the transition back to Idle.
 * No service diff - StatFs and [StorageEstimator.bytesPerSecondForResolution] are
 * read-only from UI.
 */
class StorageLowMidRecSignal(
    private val computeIsLow: (durationSeconds: Int, resolution: String) -> Boolean,
) {
    private val _isLow: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val isLow: StateFlow<Boolean> = _isLow.asStateFlow()

    /** Re-run the estimate for the current clip settings. Idempotent. */
    fun poll(durationSeconds: Int, resolution: String) {
        _isLow.value = computeIsLow(durationSeconds, resolution)
    }

    /** Clear back to false when leaving active states. */
    fun clear() {
        _isLow.value = false
    }

    companion object {
        const val LOW_THRESHOLD_CLIPS: Int = 3

        fun forContext(context: Context): StorageLowMidRecSignal {
            val app = context.applicationContext
            return StorageLowMidRecSignal(computeIsLow = { dur, res ->
                estimateIsLow(app, dur, res)
            })
        }

        /**
         * StatFs read on the calling thread. Acceptable here - the caller is RecordScreen's
         * 30-s poll inside a LaunchedEffect, not the pre-FGS critical path. Null root /
         * StatFs error returns false (don't false-warn - same fail-closed shape as
         * [com.aritr.rova.ui.signals.StorageSignal]).
         */
        private fun estimateIsLow(app: Context, durationSeconds: Int, resolution: String): Boolean {
            return try {
                val rovaApp = app as? RovaApp ?: return false
                val path = rovaApp.externalRoot?.absolutePath ?: return false
                val stat = StatFs(path)
                val available = stat.availableBlocksLong * stat.blockSizeLong
                val bytesPerClip =
                    StorageEstimator.bytesPerSecondForResolution(resolution) * durationSeconds
                available < LOW_THRESHOLD_CLIPS * bytesPerClip
            } catch (_: Exception) {
                false
            }
        }
    }
}

package com.aritr.rova.ui.signals

import android.content.Context
import android.os.StatFs
import com.aritr.rova.RovaApp
import com.aritr.rova.data.StorageEstimator
import com.aritr.rova.data.currentExportTier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase 4.1b (NEW_UI_BACKEND_REPLAN row 3) — leaf signal: is there too
 * little free space to START a session with the current clip settings?
 * `true` drives the `STORAGE_INSUFFICIENT` hard-block banner and disables
 * the Record screen's Start button.
 *
 * The computation mirrors RovaRecordingService.onStartCommand's storage
 * preflight: estimate peak bytes for (durationSeconds, loopCount,
 * resolution) via [StorageEstimator.estimatePeakBytes] (which handles
 * indefinite loops internally), add the same 50 MiB finalize headroom the
 * service uses, and compare against StatFs available bytes on the same
 * external root. Where the service fail-closes (null root / StatFs error
 * to "insufficient", aborting the session), this signal returns `false`
 * (don't false-warn — the service's preflight is the load-bearing backstop
 * at start time; this banner only warns early when we can tell).
 *
 * Shape note: the clip settings are not on this signal — they live in
 * RecordViewModel's StateFlows and are passed to the service at start time;
 * the host screen passes them into [recompute]. Refresh contract: call
 * [recompute] from the host Activity's ON_RESUME and whenever a clip setting
 * (duration / loop count / resolution) changes. Idempotent.
 */
class StorageSignal(
    private val computeInsufficient: (durationSeconds: Int, loopCount: Int, resolution: String) -> Boolean
) {
    private val _insufficientToStart: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val insufficientToStart: StateFlow<Boolean> = _insufficientToStart.asStateFlow()

    /** Re-run the estimate for the given settings and publish if changed. */
    fun recompute(durationSeconds: Int, loopCount: Int, resolution: String) {
        _insufficientToStart.value = computeInsufficient(durationSeconds, loopCount, resolution)
    }

    companion object {
        private const val FINALIZE_HEADROOM_BYTES: Long = 50L * 1024 * 1024

        fun forContext(context: Context): StorageSignal {
            val app = context.applicationContext
            return StorageSignal(computeInsufficient = { durationSeconds, loopCount, resolution ->
                estimateInsufficient(app, durationSeconds, loopCount, resolution)
            })
        }

        /**
         * Mirrors RovaRecordingService.hasEnoughStorage's negation. Reads
         * StatFs synchronously on the calling thread — acceptable here
         * (the caller is a UI settings-change / ON_RESUME hook, not the
         * pre-FGS critical path the service must keep stall-free).
         */
        private fun estimateInsufficient(
            app: Context,
            durationSeconds: Int,
            loopCount: Int,
            resolution: String
        ): Boolean {
            return try {
                val rovaApp = app as? RovaApp ?: return false
                val path = rovaApp.externalRoot?.absolutePath ?: return false
                val stat = StatFs(path)
                val available = stat.availableBlocksLong * stat.blockSizeLong
                val peak = StorageEstimator.estimatePeakBytes(
                    durationSeconds = durationSeconds.toLong(),
                    loopCount = loopCount,
                    resolution = resolution,
                    tier = currentExportTier()
                )
                available < peak + FINALIZE_HEADROOM_BYTES
            } catch (_: Exception) {
                false
            }
        }
    }
}

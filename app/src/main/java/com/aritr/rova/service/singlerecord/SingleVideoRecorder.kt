package com.aritr.rova.service.singlerecord

import android.content.Context
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.util.Consumer
import com.aritr.rova.utils.RovaLog
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single-mode (non-DualShot) recording collaborator. Mirror of
 * `service/dualrecord/DualVideoRecorder`: construction is cheap (config only);
 * it builds & OWNS the CameraX `VideoCapture<Recorder>` use case (incl. its
 * build-time `setTargetRotation` — ADR-0029 §3 boundary; this package is an
 * allowlisted rotation boundary, symmetric with `service/dualrecord/`).
 *
 * The service binds [videoCapture] via `bindToLifecycle` (mirror of
 * `DualVideoRecorder.asCameraEffect()`), then drives one [start] per segment.
 * The per-segment loop, watchdog, finalize-coordination deferreds, persistence,
 * merge, and terminal writes stay in the service (mirror of `recordSegmentDual`
 * / `performMergeDual`). CameraX `VideoRecordEvent` is passed straight through
 * to the service's callback — no event remap.
 *
 * `release()` is idempotent. After release the recorder cannot be reused.
 */
class SingleVideoRecorder(config: SingleVideoRecorderConfig) {

    private val released = AtomicBoolean(false)
    @Volatile private var active: SingleRecording? = null

    /** The owned video use case; the service binds this to the camera lifecycle. */
    val videoCapture: VideoCapture<Recorder>

    init {
        // Strict match against QualityPresets canonical labels: any
        // non-canonical resolutionStr falls through to Quality.FHD exactly as
        // the former inline setupSingleCamera body. (SingleQualitySelector is
        // the pure, tested decision; mapped to the CameraX type here at the edge.)
        val quality = when (SingleQualitySelector.forResolution(config.resolutionStr)) {
            SingleQuality.UHD -> Quality.UHD
            SingleQuality.FHD -> Quality.FHD
            SingleQuality.HD -> Quality.HD
            SingleQuality.SD -> Quality.SD
        }
        val qualitySelector = QualitySelector.fromOrderedList(
            listOf(quality, Quality.FHD, Quality.HD, Quality.SD),
            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
        )
        val recorder = Recorder.Builder()
            .setQualitySelector(qualitySelector)
            .build()
        videoCapture = VideoCapture.Builder(recorder)
            .setTargetRotation(config.buildTimeTargetRotation)
            .build()
    }

    /**
     * Start one segment. Applies the per-segment rotation, prepares + (optionally)
     * audio-enables the recording, and starts it on [executor], delivering events
     * to [callback]. The `withAudioEnabled()` `SecurityException` is propagated
     * unchanged — the caller maps it to the PERMISSION_REVOKED termination path.
     */
    fun start(
        context: Context,
        outputOptions: FileOutputOptions,
        segmentRotation: Int,
        enableAudio: Boolean,
        executor: Executor,
        callback: Consumer<VideoRecordEvent>,
    ): SingleRecording {
        check(!released.get()) { "SingleVideoRecorder is released" }
        // Per-segment boundary rotation (ADR-0029 §3). Property-set form, kept
        // verbatim incl. the defensive try/catch from the former inline body.
        try { videoCapture.targetRotation = segmentRotation } catch (_: Exception) {}
        var pending = videoCapture.output.prepareRecording(context, outputOptions)
        if (enableAudio) {
            // SecurityException intentionally propagated (RECORD_AUDIO revoked
            // between gate and CameraX config) → caller routes to PERMISSION_REVOKED.
            pending = pending.withAudioEnabled()
        }
        val recording = pending.start(executor, callback)
        return SingleRecording(recording).also { active = it }
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        try { active?.stop() } catch (e: Throwable) { RovaLog.w("SingleVideoRecorder.release stop", e) }
        active = null
    }
}

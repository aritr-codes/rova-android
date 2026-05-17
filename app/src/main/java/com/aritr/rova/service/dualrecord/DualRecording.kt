package com.aritr.rova.service.dualrecord

import com.aritr.rova.service.dualrecord.internal.AudioFanOut
import com.aritr.rova.service.dualrecord.internal.DualMuxer
import com.aritr.rova.service.dualrecord.internal.EncoderSurface
import com.aritr.rova.utils.RovaLog
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phase 6.1a — active recording handle returned by `DualVideoRecorder.start()`.
 * One per session segment. Owns the lifecycle of the encoders + muxer +
 * audio for that segment. Idempotent stop().
 *
 * Phase 6.1b smoke-fix: ctor accepts `onStopped` callback so the owning
 * `DualVideoRecorder` can clear its `activeRecording` reference after stop,
 * enabling per-segment reuse without `release()`/reconstruct cycles.
 */
class DualRecording internal constructor(
    private val portraitEncoder: EncoderSurface,
    private val landscapeEncoder: EncoderSurface,
    private val audio: AudioFanOut,
    private val muxer: DualMuxer,
    private val callback: (DualRecordEvent) -> Unit,
    private val callbackExecutor: Executor,
    private val onStopped: () -> Unit = {},
) {

    private val stopped = AtomicBoolean(false)

    /** Idempotent. Subsequent calls are no-ops. Fires `Finalize` exactly once. */
    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        var error: Throwable? = null
        try {
            // Signal EOS to both video encoders + audio.
            portraitEncoder.signalEndOfInputStream()
            landscapeEncoder.signalEndOfInputStream()
            audio.stop()
            portraitEncoder.release()
            landscapeEncoder.release()
        } catch (e: Throwable) {
            RovaLog.w("DualRecording.stop encoder/audio", e); error = e
        }
        val files = try { muxer.stop() } catch (e: Throwable) {
            RovaLog.w("DualRecording.stop muxer", e); error = error ?: e
            mapOf(VideoSide.PORTRAIT to null, VideoSide.LANDSCAPE to null)
        }
        val event = DualRecordEvent.Finalize(
            portraitFile = files[VideoSide.PORTRAIT],
            landscapeFile = files[VideoSide.LANDSCAPE],
            error = error,
        )
        callbackExecutor.execute { callback(event) }
        // Phase 6.1b smoke-fix: unconditionally clear recorder's activeRecording
        // reference so the recorder can be reused for the next loop segment.
        // Wrapped in its own try/catch — must run even if finalize partially failed.
        try { onStopped() } catch (e: Throwable) { RovaLog.w("DualRecording.stop onStopped", e) }
    }
}

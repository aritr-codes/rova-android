package com.aritr.rova.service.singlerecord

import androidx.camera.video.Recording

/**
 * Active-segment handle returned by [SingleVideoRecorder.start]. Mirror of
 * `service/dualrecord/DualRecording`. Wraps the CameraX [Recording] for one
 * segment. [stop] is idempotent and NON-BLOCKING — it delegates to
 * `Recording.stop()` (finalize is delivered asynchronously on the callback
 * executor afterwards; the service performs its bounded finalize-await AFTER
 * calling stop). The CameraX `stop()` exception (if any) is intentionally NOT
 * swallowed here, preserving the service's existing throw-propagation at each
 * stop site.
 */
class SingleRecording internal constructor(private val recording: Recording) {

    private val stopped = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Idempotent. Subsequent calls are no-ops. */
    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        recording.stop()
    }
}

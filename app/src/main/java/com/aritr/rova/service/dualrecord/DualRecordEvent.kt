package com.aritr.rova.service.dualrecord

import java.io.File

/**
 * Phase 6.1a — events delivered to the `DualVideoRecorder.start(...)`
 * callback on the consumer-provided Executor. Mirrors CameraX's
 * `VideoRecordEvent` shape for easier future-maintainer mapping.
 */
sealed class DualRecordEvent {
    /** Both muxers + audio are live; the first frames are being written. */
    data object Start : DualRecordEvent()

    /**
     * Throttled progress update (~250 ms cadence). `recordedBytes` is
     * cumulative bytes written to that side's muxer; `durationMs` is
     * wall-clock since `Start`.
     */
    data class Status(
        val portraitRecordedBytes: Long,
        val landscapeRecordedBytes: Long,
        val durationMs: Long,
    ) : DualRecordEvent()

    /**
     * Terminal event. Delivered exactly once per `DualRecording`. If a
     * side wrote successfully, its `File?` is non-null; if a side
     * failed mid-write, its `File?` is null and `error` carries the
     * cause. If `error == null` and both `File?`s are non-null, both
     * outputs are usable.
     */
    data class Finalize(
        val portraitFile: File?,
        val landscapeFile: File?,
        val error: Throwable?,
    ) : DualRecordEvent()
}

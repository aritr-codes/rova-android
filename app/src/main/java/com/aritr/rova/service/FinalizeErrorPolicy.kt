package com.aritr.rova.service

/**
 * Pure decision for whether a CameraX `VideoRecordEvent.Finalize` failure on a
 * segment should be SUPPRESSED from the user-facing `recordingError` surface.
 *
 * Why this exists: when the user (or a loop-exhaust) stops a periodic session,
 * `stopPeriodicRecordingAndMerge()` cancels the in-flight `recordSegment()`
 * coroutine and calls `currentRecording.stop()`. CameraX then finalizes that
 * aborted final segment with `ERROR_NO_VALID_DATA` (or an empty file with no
 * error) — the EXPECTED outcome of stopping mid-capture, not a capture failure.
 * Surfacing it flashed a false "No video data was captured" snackbar (~4s) while
 * the merge of the valid prior segments succeeded.
 *
 * Suppress ONLY that expected case: a stop is in flight AND the outcome is the
 * no-data/empty result. Genuine mid-recording failures (no stop in flight) and
 * all other finalize error codes (insufficient storage, source inactive, …)
 * still surface, even during a stop.
 *
 * Pure (no Android types) so it is unit-testable under `isReturnDefaultValues`.
 */
object FinalizeErrorPolicy {
    /**
     * @param stopInFlight `stopRequested || userStopRequested` — a stop has been requested.
     * @param hasError the finalize event carried an error (`event.hasError()`).
     * @param isNoValidData the error code is `VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA`.
     */
    fun shouldSuppress(stopInFlight: Boolean, hasError: Boolean, isNoValidData: Boolean): Boolean =
        stopInFlight && (!hasError || isNoValidData)
}

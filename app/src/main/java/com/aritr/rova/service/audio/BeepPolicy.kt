package com.aritr.rova.service.audio

import com.aritr.rova.data.AudioMode

/**
 * Site-aware gate for the start/stop loop beep played through the
 * device speaker by [com.aritr.rova.service.RovaRecordingService].
 *
 * Pure transformer over three inputs so it can be JVM-unit-tested
 * without Robolectric or a real `MediaPlayer`:
 *
 * - [enableBeeps] — user preference toggle.
 * - [audioMode] — `VIDEO_ONLY` or `VIDEO_AUDIO`. Decided once at
 *   session start per ADR 0006 B18 and immutable for the session
 *   lifetime.
 * - [intervalMinutes] — `SessionConfig.intervalMinutes`. `0` means
 *   continuous / back-to-back capture; the recorder opens the
 *   microphone again immediately after each segment finalizes.
 *
 * Rule:
 *
 * 1. If [enableBeeps] is `false`, never beep.
 * 2. Else if [audioMode] is `VIDEO_AUDIO` AND [intervalMinutes] is
 *    `0` (continuous), suppress the beep entirely. There is no
 *    natural gap between segments and adding a synchronous-await
 *    playback delay (~450 ms with tail margin) would punch a hole
 *    in continuous mode. Cleaner to give up the audible cue here
 *    than to compromise the product contract on continuous capture.
 * 3. Otherwise beep. Bleed-safety for non-zero intervals is not
 *    a policy concern — it lives in the call-site sequencing on
 *    `beepStart()` (suspend-await playback completion + tail
 *    margin before the recorder opens the mic).
 */
internal fun shouldPlayBeep(
    enableBeeps: Boolean,
    audioMode: AudioMode,
    intervalMinutes: Int
): Boolean {
    if (!enableBeeps) return false
    if (audioMode == AudioMode.VIDEO_AUDIO && intervalMinutes == 0) return false
    return true
}

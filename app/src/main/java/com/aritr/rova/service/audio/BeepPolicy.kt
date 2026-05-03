package com.aritr.rova.service.audio

import com.aritr.rova.data.AudioMode

/**
 * Pure policy gate for the start/stop loop beep played through the
 * device speaker by [com.aritr.rova.service.RovaRecordingService].
 *
 * Beeps are suppressed when the session captures audio — playing
 * `R.raw.rova_beep` through the speaker while the microphone is hot
 * causes the recorder to re-capture the beep tail into the segment
 * file. The next segment can also pick up the previous iteration's
 * stop-beep when `intervalMinutes == 0` (back-to-back) or when the
 * inter-segment wait is short enough that the speaker is still
 * radiating. Suppressing both ends in [AudioMode.VIDEO_AUDIO]
 * sessions removes that bleed-through path entirely.
 *
 * `VIDEO_ONLY` sessions keep the existing behavior: beeps fire on
 * every start/stop so the user has an audible cue with the screen
 * off. This matches ADR 0006 B18: `audioMode` is decided once at
 * session start and is the right axis to gate on, not a runtime
 * mic-permission check.
 */
internal fun shouldPlayBeep(enableBeeps: Boolean, audioMode: AudioMode): Boolean =
    enableBeeps && audioMode != AudioMode.VIDEO_AUDIO

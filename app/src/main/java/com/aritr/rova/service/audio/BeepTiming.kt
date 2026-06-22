package com.aritr.rova.service.audio

/**
 * Pure timing helper for the start cues played by
 * [com.aritr.rova.service.RovaRecordingService.beepStart].
 *
 * Two-asset cue scheme (2026-06-22): the FIRST segment of a recording plays
 * the full multi-pulse start cue (`R.raw.rova_cue_start`, ~3.5s, 4 pulses —
 * a once-per-recording pre-roll); every later segment start plays the short
 * reminder (`R.raw.rova_beep`, ~1s). `beepStart` awaits the cue's
 * `MediaPlayer` `onCompletion` under a `withTimeoutOrNull` ceiling before
 * opening the mic, so neither cue bleeds into the recording.
 *
 * Cue-bleed bug (2026-06-18, device RZCYA1VBQ2H): the await ceiling used to
 * be a fixed 1500 ms, justified by a stale KDoc claiming the cue was
 * "~300 ms". On-device the long cue is ~3527 ms, so the 1500 ms ceiling
 * always tripped ~2 s before `onCompletion` → the await truncated mid-cue →
 * the mic opened while the cue was still playing → the cue bled in.
 *
 * The ceiling is therefore derived from the *actual* cue duration so the
 * await genuinely reaches `onCompletion`, and it adapts to whichever asset
 * plays — there is no per-asset timing constant. JVM-unit-tested in
 * `BeepTimingTest`; the framework `MediaPlayer`/mic ordering stays a thin
 * seam in the service.
 */

/** Headroom added to the cue's reported duration so the timeout comfortably
 *  outlasts `onCompletion` (which lags the nominal end by the output buffer
 *  drain — measured ~440 ms on RZCYA1VBQ2H; this leaves generous margin). */
internal const val BEEP_CEILING_SLACK_MS = 1_500L

/** Sane upper backstop on the await. If `duration + slack` ever exceeds this,
 *  the cue asset is longer than any plausible cue — the caller MUST log loudly
 *  (see [beepCeilingIsClamped]); silent truncation is the exact failure this
 *  replaces. */
internal const val BEEP_CEILING_MAX_MS = 10_000L

/**
 * Await ceiling (ms) for the start cue: the real cue duration plus
 * [BEEP_CEILING_SLACK_MS], clamped to [BEEP_CEILING_MAX_MS].
 *
 * @param cueDurationMs `MediaPlayer.getDuration()` of the cue. `<= 0` means
 *   the duration is unavailable (e.g. `getDuration()` returned `-1`); fall
 *   back to the safe max ceiling rather than truncating.
 */
internal fun beepPlaybackCeilingMs(cueDurationMs: Long): Long =
    if (cueDurationMs <= 0L) BEEP_CEILING_MAX_MS
    else (cueDurationMs + BEEP_CEILING_SLACK_MS).coerceAtMost(BEEP_CEILING_MAX_MS)

/** True when [beepPlaybackCeilingMs] had to clamp a known duration to the
 *  backstop — i.e. the cue is longer than expected and the await may still
 *  truncate. The caller logs loudly so this can never hide again. */
internal fun beepCeilingIsClamped(cueDurationMs: Long): Boolean =
    cueDurationMs > 0L && cueDurationMs + BEEP_CEILING_SLACK_MS > BEEP_CEILING_MAX_MS

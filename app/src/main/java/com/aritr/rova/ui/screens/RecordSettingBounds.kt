package com.aritr.rova.ui.screens

/**
 * Pure clamp/step math for the inline settings-sheet steppers
 * (`SettingsSheet.kt`). Ranges + `clipStep` are transcribed verbatim from the
 * retired `RecordEditSheets.kt`. `dir` is `-1` (decrement) or `+1` (increment).
 *
 * Repeats has a continuous position ([REPEATS_CONTINUOUS], the app's
 * `loopCount = -1` "until you stop" sentinel) one step below [REPEATS_MIN]:
 * `−` from 1 lands on continuous; `+` from continuous returns to 1.
 */
internal object RecordSettingBounds {

    const val CLIP_MIN = 1
    const val CLIP_MAX = 300
    const val REPEATS_MIN = 1
    const val REPEATS_MAX = 999
    const val REPEATS_CONTINUOUS = -1
    const val WAIT_MIN = 0
    const val WAIT_MAX = 60

    /** 5 s steps below a minute, 15 s at/above — matches the old ClipLengthEditSheet. */
    fun clipStep(seconds: Int): Int = if (seconds < 60) 5 else 15

    fun stepClip(current: Int, dir: Int): Int {
        val c = current.coerceIn(CLIP_MIN, CLIP_MAX)
        return (c + dir * clipStep(c)).coerceIn(CLIP_MIN, CLIP_MAX)
    }

    fun stepRepeats(current: Int, dir: Int): Int = when {
        current == REPEATS_CONTINUOUS -> if (dir > 0) REPEATS_MIN else REPEATS_CONTINUOUS
        current <= REPEATS_MIN && dir < 0 -> REPEATS_CONTINUOUS
        else -> (current + dir).coerceIn(REPEATS_MIN, REPEATS_MAX)
    }

    fun stepWait(current: Int, dir: Int): Int =
        (current.coerceIn(WAIT_MIN, WAIT_MAX) + dir).coerceIn(WAIT_MIN, WAIT_MAX)

    fun clipAtMin(v: Int): Boolean = v.coerceIn(CLIP_MIN, CLIP_MAX) <= CLIP_MIN
    fun clipAtMax(v: Int): Boolean = v.coerceIn(CLIP_MIN, CLIP_MAX) >= CLIP_MAX
    fun repeatsAtMin(v: Int): Boolean = v == REPEATS_CONTINUOUS
    fun repeatsAtMax(v: Int): Boolean = v >= REPEATS_MAX
    fun waitAtMin(v: Int): Boolean = v.coerceIn(WAIT_MIN, WAIT_MAX) <= WAIT_MIN
    fun waitAtMax(v: Int): Boolean = v.coerceIn(WAIT_MIN, WAIT_MAX) >= WAIT_MAX

    // Direct manual-entry clamps (owner 2026-06-17 — tap the value to enter it). A typed value
    // is coerced into the SAME bounds the steppers enforce, so no out-of-range value can be set.
    // Repeats clamps to the finite range; ∞ (continuous) stays a '−'-at-min affordance.
    fun clampClip(v: Int): Int = v.coerceIn(CLIP_MIN, CLIP_MAX)
    fun clampRepeats(v: Int): Int = v.coerceIn(REPEATS_MIN, REPEATS_MAX)
    fun clampWait(v: Int): Int = v.coerceIn(WAIT_MIN, WAIT_MAX)
}

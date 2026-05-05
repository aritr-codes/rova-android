package com.aritr.rova.ui.components

import com.aritr.rova.data.QualityPresets

/**
 * Canonical TalkBack copy for the Slice 2 Record idle screen and edit
 * sheets. Centralised here so cell descriptors, chip descriptors, and
 * Continuous / None variants stay consistent and never leak sentinels
 * (`-1`, `0`, `∞`) into spoken text.
 */
object UiCopy {

    fun clipLengthCellDescription(seconds: Int): String =
        "Clip length: ${formatSecondsForSpeech(seconds)}. Tap to change."

    fun repeatsFixedCellDescription(count: Int): String =
        "Repeats: $count. Tap to change."

    fun repeatsContinuousCellDescription(): String =
        "Repeats: records until you stop. Tap to change."

    fun waitFiniteCellDescription(minutes: Int): String =
        "Wait between clips: ${formatMinutesForSpeech(minutes)}. Tap to change."

    fun waitNoneCellDescription(): String =
        "Wait between clips: no wait between clips. Tap to change."

    fun qualityCellDescription(quality: String): String =
        "Quality: $quality. Tap to change."

    fun continuousRadioDescription(): String = "Records until you stop"

    fun noWaitChipDescription(): String = "No wait between clips"

    /**
     * Base quality chip descriptor — caller appends the state suffix
     * (e.g., `, selected` / `, not selected`). [QuickSetChipRow]
     * already appends the state suffix for every chip, so callers
     * threading through that row should pass this base form to avoid
     * double-suffixing (e.g., the bug `Quality: FHD, selected, selected`).
     *
     * [quality] must be a value from [QualityPresets.PICKER_ORDER]; the
     * helper does not canonicalize so the announce string stays
     * deterministic at the a11y boundary.
     */
    fun qualityChipBaseDescription(quality: String): String = "Quality: $quality"

    /**
     * Full quality chip descriptor: "Quality: FHD, selected" /
     * "Quality: SD, not selected" (and the equivalent for every
     * [QualityPresets.PICKER_ORDER] value). This is the canonical
     * spoken string TalkBack ultimately reads; the chip row composes
     * the same shape by combining [qualityChipBaseDescription] with the
     * state suffix.
     */
    fun qualityChipDescription(quality: String, selected: Boolean): String {
        val state = if (selected) "selected" else "not selected"
        return "${qualityChipBaseDescription(quality)}, $state"
    }

    private fun formatSecondsForSpeech(seconds: Int): String = when {
        seconds <= 0 -> "0 seconds"
        seconds == 1 -> "1 second"
        seconds < 60 -> "$seconds seconds"
        seconds == 60 -> "1 minute"
        seconds % 60 == 0 -> "${seconds / 60} minutes"
        else -> "$seconds seconds"
    }

    private fun formatMinutesForSpeech(minutes: Int): String = when {
        minutes <= 0 -> "no wait between clips"
        minutes == 1 -> "1 minute"
        minutes == 60 -> "1 hour"
        minutes % 60 == 0 -> "${minutes / 60} hours"
        else -> "$minutes minutes"
    }
}

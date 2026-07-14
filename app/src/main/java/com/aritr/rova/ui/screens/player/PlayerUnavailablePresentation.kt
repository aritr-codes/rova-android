package com.aritr.rova.ui.screens.player

import androidx.annotation.StringRes
import com.aritr.rova.R
import com.aritr.rova.ui.text.UiText

/**
 * Pure presentation classifier for the Unavailable triad card
 * (`docs/design/player-states.html` §05). The resolver
 * ([PlayerUriResolver]) and the two runtime failure paths
 * ([PlayerViewModel] `onPlayerError` / [PlayerStateEmitter] attach-throw)
 * carry only a [UiText] reason — **no severity**. This object turns that
 * `@StringRes` reason into the three things the card renders: the action
 * class (Retry vs Back), the neutral glyph archetype, and the supporting
 * detail line.
 *
 * Message-free and exhaustive over the six known reason ids; **unknown →
 * DISMISS** (fail-safe: never offer a Retry the system can't honor).
 * Mirrors the M9 `MergeFailureReason` precedent (closed set, keyed on the
 * typed input) and touches **no backend** — the resolver / ADR-0037 matrix
 * stays read-only. A future typed `reason` on `Unavailable` classified at
 * the resolver seam would be cleaner, but that amends ADR-0037's contract,
 * so V1 uses this string-keyed classifier (§05).
 *
 * Pure Kotlin so it is JVM-unit-testable under `isReturnDefaultValues =
 * true`; the framework-touching part is the `PlayerUnavailableCard`
 * composable that maps [Glyph] → an `ImageVector` and resolves the strings.
 */
object PlayerUnavailablePresentation {

    /** Whether the card offers a way forward (transient) or only Back (permanent). */
    enum class Action { RETRY, DISMISS }

    /**
     * Neutral glyph archetype (never the trust severity red — an unopenable
     * recording is an absence, not a hard-block). One per specimen in §01:
     * transient-entry = a retry mark, permanent = a not-found mark, the
     * runtime flip = a playback-alert mark.
     */
    enum class Glyph { RETRY, NOT_FOUND, PLAYBACK_ALERT }

    data class View(
        val action: Action,
        val glyph: Glyph,
        @StringRes val detailRes: Int,
    )

    /** Classify by the reason's string-resource id (§05 table). */
    fun of(@StringRes reason: Int): View = when (reason) {
        R.string.player_unavailable_not_finished ->
            View(Action.RETRY, Glyph.RETRY, R.string.player_unavailable_not_finished_detail)
        R.string.player_unavailable_playback_failed ->
            View(Action.RETRY, Glyph.PLAYBACK_ALERT, R.string.player_unavailable_playback_failed_detail)
        R.string.player_unavailable_init_failed ->
            View(Action.RETRY, Glyph.RETRY, R.string.player_unavailable_init_failed_detail)
        R.string.player_unavailable_not_available ->
            View(Action.DISMISS, Glyph.NOT_FOUND, R.string.player_unavailable_not_available_detail)
        R.string.player_unavailable_file_not_found ->
            View(Action.DISMISS, Glyph.NOT_FOUND, R.string.player_unavailable_file_not_found_detail)
        R.string.player_unavailable_incomplete ->
            View(Action.DISMISS, Glyph.NOT_FOUND, R.string.player_unavailable_incomplete_detail)
        // Fail-safe: any unmodelled reason gets the permanent (Back-only)
        // treatment so the UI never dangles a Retry the system can't honor.
        else ->
            View(Action.DISMISS, Glyph.NOT_FOUND, R.string.player_unavailable_not_available_detail)
    }

    /**
     * Classify an [PlayerUiState.Unavailable] reason. All six resolver /
     * runtime reasons are [UiText.Str]; the non-Str shapes the resolver
     * never emits fall through to the fail-safe DISMISS above.
     */
    fun of(reason: UiText): View =
        of((reason as? UiText.Str)?.id ?: R.string.player_unavailable_not_available)
}

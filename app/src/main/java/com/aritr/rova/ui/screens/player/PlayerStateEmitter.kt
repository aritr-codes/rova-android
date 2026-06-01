package com.aritr.rova.ui.screens.player

/**
 * Phase 2.5 hardening — pure dispatch from the manifest-resolved
 * [PlayerUiState] to the terminal state the VM publishes. Extracted
 * from [PlayerViewModel.init] so the catch-and-fallback contract is
 * JVM-testable (the real `attachExoPlayer` body needs an Android
 * [android.content.Context] via `ExoPlayer.Builder`, which would force
 * Robolectric for an otherwise pure-Kotlin decision tree).
 *
 * The F#9 ordering invariant is preserved: [emit] invokes [attach]
 * synchronously and returns the [PlayerUiState.Ready] value only after
 * [attach] completes — the caller's single `_uiState.value = emit(...)`
 * write therefore still fires *after* the side effect, matching the
 * pre-extraction behaviour PlayerScreen's `update` block depends on.
 */
internal object PlayerStateEmitter {
    /**
     * @param resolved the state produced by [PlayerUriResolver.resolve].
     * @param attach side effect that prepares the ExoPlayer for the
     *   resolved `mediaUri`. Invoked at most once, only when [resolved]
     *   is [PlayerUiState.Ready].
     *
     * Audit F#R1 — when [attach] throws (ExoPlayer init failure,
     * surface error, malformed `MediaItem`, OOM), the terminal state
     * must be [PlayerUiState.Unavailable], not [PlayerUiState.Ready]
     * and not [PlayerUiState.Loading]. Without the catch, uiState
     * wedges on Loading forever and the spinner never resolves.
     */
    fun emit(
        resolved: PlayerUiState,
        attach: (uri: String) -> Unit
    ): PlayerUiState {
        if (resolved !is PlayerUiState.Ready) return resolved
        return try {
            attach(resolved.mediaUri)
            resolved
        } catch (t: Throwable) {
            // i18n-opt-out: pure-JVM producer; PlayerUiState.Unavailable.reason
            // is a String pinned by exact-equality JVM tests. Externalizing needs
            // a data-contract change (reason -> UiText/@StringRes) + test edits —
            // deferred to a dedicated slice (B3 task 5 is literal->resource only).
            PlayerUiState.Unavailable("Playback initialization failed")
        }
    }
}

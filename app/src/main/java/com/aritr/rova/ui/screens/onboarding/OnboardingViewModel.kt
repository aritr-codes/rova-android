package com.aritr.rova.ui.screens.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.aritr.rova.RovaApp
import com.aritr.rova.data.RovaSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase 2.6 — VM for the first-launch onboarding flow.
 *
 * Holds the current [OnboardingStep] in a [StateFlow] and exposes
 * advance / back / complete transitions. The completion side-effect
 * (`RovaSettings.onboardingCompleted = true`) is injected as a
 * [markCompleted] seam so the VM stays pure-Kotlin and the unit test
 * can pass a counting lambda — same pattern as
 * [com.aritr.rova.ui.screens.player.PlayerViewModel] for testability.
 *
 * SDK gating (POST_NOTIFICATIONS API 33+, SCHEDULE_EXACT_ALARM API 31+)
 * is intentionally NOT modeled here: the VM treats every step as
 * present and the Composable layer auto-advances unsupported steps
 * via [androidx.compose.runtime.LaunchedEffect]. Modeling SDK in the
 * VM would couple it to `Build.VERSION.SDK_INT` and break the JVM
 * test contract.
 *
 * Idempotency contract: [advance] / [complete] / [goBack] are all
 * no-ops once [OnboardingUiState.completed] flips true. Without this
 * guard, a delayed permission-launcher result firing after the user
 * has already exited could re-fire navigation or double-write the
 * settings flag.
 */
class OnboardingViewModel(
    private val markCompleted: () -> Unit
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    /**
     * Move to the next step. On the last step, transitions to
     * [OnboardingUiState.completed] = true and invokes [markCompleted]
     * exactly once. No-op once completed.
     */
    fun advance() {
        val current = _uiState.value
        if (current.completed) return
        val next = current.step.next()
        if (next == null) {
            markCompleted()
            _uiState.value = current.copy(completed = true)
        } else {
            _uiState.value = current.copy(step = next)
        }
    }

    /**
     * Move to the previous step. No-op if already on the first step
     * (back gesture on slide 1 is handled at the Composable layer per
     * UI_NAV_GRAPH §4.1) or if completed.
     */
    fun goBack() {
        val current = _uiState.value
        if (current.completed) return
        val prev = current.step.previous() ?: return
        _uiState.value = current.copy(step = prev)
    }

    /**
     * Skip-to-end. Walkthrough Skip and any "exit early" path lands
     * here. UI_NAV_GRAPH §4.1 + §8 open question 1 — Skip writes
     * `onboardingCompleted = true` (matches the prototype) and the
     * WarningCenter surfaces any still-missing permissions on record.
     * Idempotent: [markCompleted] fires at most once per VM lifetime.
     */
    fun complete() {
        val current = _uiState.value
        if (current.completed) return
        markCompleted()
        _uiState.value = current.copy(completed = true)
    }

    companion object {
        /**
         * Same factory shape as [com.aritr.rova.ui.screens.player.PlayerViewModel.factory] —
         * pulls [RovaSettings] off the [RovaApp] application context so
         * the VM never sees `Context` directly.
         */
        fun factory(app: RovaApp): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val settings = RovaSettings(app)
                    return OnboardingViewModel(
                        markCompleted = { settings.onboardingCompleted = true }
                    ) as T
                }
            }
    }
}

/**
 * Phase 2.6 UI state. [step] is the active step; [completed] flips
 * true exactly once when the user finishes (via [OnboardingViewModel.advance]
 * past the last step or [OnboardingViewModel.complete]).
 */
data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.WALKTHROUGH_1,
    val completed: Boolean = false
)

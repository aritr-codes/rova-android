package com.aritr.rova.ui.screens.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.aritr.rova.RovaApp
import com.aritr.rova.data.RovaSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * M4 (2026-05-27) Onboarding redesign — VM for the device-visible step list first-launch flow.
 *
 * Holds the current [OnboardingStep] in a [StateFlow] and exposes
 * [advance] / [goBack] / [complete] / [skipWalkthroughToFirstPermission] /
 * [setStep] transitions. The completion side-effect
 * (`RovaSettings.onboardingCompleted = true`) is injected as a
 * [markCompleted] seam so the VM stays pure-Kotlin and the unit test
 * passes a counting lambda — same pattern as
 * [com.aritr.rova.ui.screens.player.PlayerViewModel].
 *
 * The visible step list is injected at construction time via [steps],
 * allowing SDK-gated flows (e.g. 4 steps below API 33, 5 steps at API 33+)
 * to share one VM implementation. The factory injects
 * [visibleOnboardingSteps] keyed on [android.os.Build.VERSION.SDK_INT].
 *
 * Idempotency contract: [advance] / [complete] /
 * [skipWalkthroughToFirstPermission] / [goBack] / [setStep] are all no-ops
 * once [OnboardingUiState.completed] flips true. Without this guard,
 * a delayed permission-launcher result OR a HorizontalPager
 * swipe-settle event firing after the user has already exited could
 * re-fire navigation or double-write the settings flag.
 */
class OnboardingViewModel(
    private val steps: List<OnboardingStep>,
    private val markCompleted: () -> Unit
) : ViewModel() {

    /** Visible steps for this device — exposed so OnboardingScreen's pager agrees with the VM. */
    val visibleSteps: List<OnboardingStep> get() = steps

    private val _uiState = MutableStateFlow(OnboardingUiState(step = steps.first()))
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    fun advance() {
        val current = _uiState.value
        if (current.completed) return
        val idx = steps.indexOf(current.step)
        if (idx < 0) return // step not in this device's list — cannot navigate
        if (idx == steps.lastIndex) {
            markCompleted()
            _uiState.value = current.copy(completed = true)
        } else {
            _uiState.value = current.copy(step = steps[idx + 1])
        }
    }

    fun goBack() {
        val current = _uiState.value
        if (current.completed) return
        val idx = steps.indexOf(current.step)
        if (idx <= 0) return // index 0, or -1 (not in list) → no predecessor
        _uiState.value = current.copy(step = steps[idx - 1])
    }

    /**
     * Walkthrough "Skip" jumps to the first permission step (Camera), NOT past it —
     * camera is still enforced at the WarningCenter Start-gate. No-op if already past
     * walkthrough or completed.
     */
    fun skipWalkthroughToFirstPermission() {
        val current = _uiState.value
        if (current.completed) return
        if (!current.step.isWalkthrough) return
        val firstPerm = steps.firstOrNull { it.isPermission } ?: return
        _uiState.value = current.copy(step = firstPerm)
    }

    /**
     * M4 (2026-05-27 owner feedback) — direct step setter used by the
     * [androidx.compose.foundation.pager.HorizontalPager] in
     * [com.aritr.rova.ui.screens.onboarding.OnboardingScreen] to push
     * settled user-swipe page index back into VM state.
     *
     * Idempotent (no-op on equal step) so tap-driven scrolls — where
     * VM is already on target — don't re-emit; and respects the
     * completion guard so a delayed swipe-settle after onCompleted
     * cannot regress state.
     */
    fun setStep(step: OnboardingStep) {
        val current = _uiState.value
        if (current.completed) return
        if (current.step == step) return
        if (step !in steps) return
        _uiState.value = current.copy(step = step)
    }

    /**
     * Early-exit path. NOT called by the normal per-permission skips
     * (those call [advance]); retained as an escape hatch. Idempotent:
     * [markCompleted] fires at most once per VM lifetime.
     */
    fun complete() {
        val current = _uiState.value
        if (current.completed) return
        markCompleted()
        _uiState.value = current.copy(completed = true)
    }

    companion object {
        fun factory(app: RovaApp): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val settings = RovaSettings(app)
                    return OnboardingViewModel(
                        steps = visibleOnboardingSteps(android.os.Build.VERSION.SDK_INT),
                        markCompleted = { settings.onboardingCompleted = true }
                    ) as T
                }
            }
    }
}

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.WALKTHROUGH_1,
    val completed: Boolean = false
)

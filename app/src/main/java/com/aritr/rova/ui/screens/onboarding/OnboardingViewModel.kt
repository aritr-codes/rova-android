package com.aritr.rova.ui.screens.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.aritr.rova.RovaApp
import com.aritr.rova.data.RovaSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * M4 (2026-05-27) Onboarding redesign — VM for the 3-step first-launch flow.
 *
 * Holds the current [OnboardingStep] in a [StateFlow] and exposes
 * [advance] / [goBack] / [complete] / [skipWalkthroughToCamera]
 * transitions. The completion side-effect
 * (`RovaSettings.onboardingCompleted = true`) is injected as a
 * [markCompleted] seam so the VM stays pure-Kotlin and the unit test
 * passes a counting lambda — same pattern as
 * [com.aritr.rova.ui.screens.player.PlayerViewModel].
 *
 * Phase 2.6 had SDK-gating concerns for `PERM_NOTIFS` (API 33+) and
 * `PERM_ALARM` (API 31+). Those steps are GONE in M4 — Camera is
 * unconditionally available API 1+, so no SDK gating is needed.
 *
 * Idempotency contract: [advance] / [complete] /
 * [skipWalkthroughToCamera] / [goBack] are all no-ops once
 * [OnboardingUiState.completed] flips true. Without this guard, a
 * delayed permission-launcher result firing after the user has
 * already exited could re-fire navigation or double-write the
 * settings flag.
 */
class OnboardingViewModel(
    private val markCompleted: () -> Unit
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

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

    fun goBack() {
        val current = _uiState.value
        if (current.completed) return
        val prev = current.step.previous() ?: return
        _uiState.value = current.copy(step = prev)
    }

    /**
     * "Not now" path on the Camera rationale slide AND any future
     * "exit early" path. Idempotent: [markCompleted] fires at most
     * once per VM lifetime.
     */
    fun complete() {
        val current = _uiState.value
        if (current.completed) return
        markCompleted()
        _uiState.value = current.copy(completed = true)
    }

    /**
     * M4 — Walkthrough "Skip" jumps to [OnboardingStep.PERM_CAMERA],
     * NOT past it. Phase 2.6's Skip used [complete], which would
     * bypass the Camera prompt — first-run failure on systems where
     * the user then tapped Start with no Camera permission.
     *
     * No-op if already past walkthrough or already completed.
     */
    fun skipWalkthroughToCamera() {
        val current = _uiState.value
        if (current.completed) return
        if (!current.step.isWalkthrough) return
        _uiState.value = current.copy(step = OnboardingStep.PERM_CAMERA)
    }

    companion object {
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

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.WALKTHROUGH_1,
    val completed: Boolean = false
)

package com.aritr.rova.ui.components

/**
 * Slice 3 — sealed renderable state for the active Record HUD.
 *
 * Mutual exclusion is enforced *at the data layer* rather than via a
 * Compose UI test: the call site collects two service flags and asks
 * [RecordHudState.from] which case to render. The sealed hierarchy
 * makes "render both Recording and Waiting bodies" structurally
 * impossible — there is no instance that represents that combination.
 *
 * Pure, JVM-testable.
 */
sealed class RecordHudState {

    /** Idle — render the Slice 2 idle dock. */
    data object Idle : RecordHudState()

    /** Periodic session active and a clip is currently recording. */
    data object Recording : RecordHudState()

    /** Periodic session active and waiting between clips. */
    data object Waiting : RecordHudState()

    companion object {
        fun from(isPeriodicActive: Boolean, isRecording: Boolean): RecordHudState =
            when {
                !isPeriodicActive -> Idle
                isRecording -> Recording
                else -> Waiting
            }
    }
}

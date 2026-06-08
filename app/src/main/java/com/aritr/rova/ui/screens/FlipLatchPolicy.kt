package com.aritr.rova.ui.screens

/**
 * Pure decision seam for clearing the record-screen front/back "Switching…"
 * overlay latch (`flipInFlight`).
 *
 * House convention (pure-helper extraction): the framework-touching composable
 * stays a thin seam; this object holds the unit-testable decision so it runs
 * under `isReturnDefaultValues = true`.
 *
 * Invariant (camera-flip regression, device-confirmed 2026-06-08): the latch
 * must NOT be cleared off an `isCameraActive` edge. A lens flip pulses
 * `RovaServiceState.isCameraActive` true→false→true inside
 * `forceReconfigureCamera()` + `setupCamera()`; on a warm rebind the `false`
 * window is ~54 ms. `serviceState` is a conflated `StateFlow`, so the Compose
 * main-thread collector misses the brief `false` and — from composition's view —
 * `isCameraActive` never changes, so a `LaunchedEffect(isCameraActive)` keyed
 * clear never re-fires and the opaque overlay latches on forever over a working
 * preview.
 *
 * Fix: gate the clear on `RovaServiceState.cameraConfigGeneration`, a MONOTONIC
 * counter the service increments on every successful (re)bind. Because the END
 * value differs from the value captured at flip-tap (rather than pulsing),
 * conflation cannot hide it: even if every intermediate emission is dropped, the
 * final collected generation is strictly greater than the captured one.
 */
object FlipLatchPolicy {
    /**
     * Whether to clear `flipInFlight`.
     *
     * @param flipInFlight whether a flip is currently latched.
     * @param startGeneration `cameraConfigGeneration` captured at the flip tap.
     * @param currentGeneration the latest observed `cameraConfigGeneration`.
     * @return true once a (re)bind has completed since the flip began.
     */
    fun shouldClear(
        flipInFlight: Boolean,
        startGeneration: Long,
        currentGeneration: Long,
    ): Boolean = flipInFlight && currentGeneration != startGeneration
}

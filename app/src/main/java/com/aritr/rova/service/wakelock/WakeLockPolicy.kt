package com.aritr.rova.service.wakelock

/**
 * Phase 1.8 / C17 — WakeLock discipline contract.
 *
 * ADR 0006 §"WakeLock Ownership" prescribes:
 *  - bounded acquire (timeout-based)
 *  - exception-safe release; Android occasionally throws
 *    `RuntimeException("WakeLock under-locked")` on release.
 *
 * The full `PowerManager.WakeLock` surface is intentionally NOT
 * abstracted — Phase 1.8 stays surgical. Only the two pure helpers used
 * by [com.aritr.rova.service.RovaRecordingService] are exposed, so the
 * JVM unit-test suite can cover the timeout contract and the
 * exception-swallow behavior without a service-wide DI seam.
 */
internal object WakeLockPolicy {
    /**
     * Bound chosen so the wakelock cannot survive a hung session.
     * Existing acquire sites — FGS init, post-relax re-acquire,
     * `requestStop`, `countdownWithWakeLock` — naturally refresh the
     * timeout. 10 minutes comfortably exceeds the longest single
     * segment but is short enough that a process wedge is bounded in
     * battery cost.
     */
    const val ACQUIRE_TIMEOUT_MS: Long = 10L * 60L * 1000L

    /**
     * Invokes [release], swallowing any [RuntimeException] thrown by
     * the underlying WakeLock. Phase 1.8 contract: callers MUST also
     * null their handle regardless of outcome — this helper covers the
     * throw surface only. ADR 0006 §"WakeLock Ownership" line 1219–1221
     * documents the `WakeLock under-locked` race.
     */
    inline fun safeRelease(release: () -> Unit) {
        try {
            release()
        } catch (_: RuntimeException) {
            // Intentionally swallowed — see KDoc.
        }
    }
}

package com.aritr.rova.utils

/**
 * Abstraction over a crash-reporting backend (Firebase Crashlytics, Sentry, etc.).
 *
 * Phase 0 ships a no-op default [NoopCrashReporter]. The Phase 0 DoD acceptance
 * — "release build forced crash visible in dashboard" — remains **open**;
 * production wiring is pending Firebase project setup (`google-services.json`)
 * or equivalent.
 *
 * Call sites use the [RovaCrashReporter] singleton. A future
 * `Application.onCreate` swaps in the live backend via [RovaCrashReporter.setBackend].
 */
interface CrashReporter {

    fun recordException(throwable: Throwable, message: String? = null)

    fun log(message: String)

    fun setCustomKey(key: String, value: String)
}

object NoopCrashReporter : CrashReporter {
    override fun recordException(throwable: Throwable, message: String?) { /* no-op */ }
    override fun log(message: String) { /* no-op */ }
    override fun setCustomKey(key: String, value: String) { /* no-op */ }
}

/**
 * Process-wide crash reporter. Backend is hot-swappable so Phase 4's Firebase
 * wiring is a single [setBackend] call from `Application.onCreate` — no
 * callsite changes anywhere in the codebase.
 *
 * Until then, all calls forward to [NoopCrashReporter] (zero runtime cost,
 * zero behavioral change).
 */
object RovaCrashReporter : CrashReporter {

    @Volatile
    private var backend: CrashReporter = NoopCrashReporter

    /**
     * Swap in a live backend (e.g. Firebase Crashlytics). Intended for one-time
     * call from `Application.onCreate`. Thread-safe via volatile write.
     */
    fun setBackend(reporter: CrashReporter) {
        backend = reporter
    }

    override fun recordException(throwable: Throwable, message: String?) {
        backend.recordException(throwable, message)
    }

    override fun log(message: String) {
        backend.log(message)
    }

    override fun setCustomKey(key: String, value: String) {
        backend.setCustomKey(key, value)
    }
}

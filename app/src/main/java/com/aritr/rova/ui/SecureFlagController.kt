package com.aritr.rova.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * B5 / ADR-0025 — single ref-counted owner of the Activity window's FLAG_SECURE.
 *
 * Multiple screens (vault list, vault player) need the window secured, and they
 * overlap during a Navigation Compose transition (the exiting screen stays
 * composed through the exit animation, then disposes ~300ms later). If each
 * screen calls window.addFlags/clearFlags directly, the late onDispose of the
 * exiting screen wipes the flag the entering screen set — leaving vault playback
 * screenshottable. Ref-counting keeps FLAG_SECURE on while ANY secure screen is
 * active and only clears it when the last one leaves.
 *
 * [onFirstAcquire]/[onLastRelease] are the window side effects; the count logic
 * is pure and JVM-tested. All calls happen on the main thread (Compose effects),
 * so the plain Int counter needs no synchronization.
 */
class SecureFlagController(
    private val onFirstAcquire: () -> Unit,
    private val onLastRelease: () -> Unit,
) {
    private var count = 0

    fun acquire() {
        if (count == 0) onFirstAcquire()
        count++
    }

    fun release() {
        if (count == 0) return
        count--
        if (count == 0) onLastRelease()
    }

    /** Test-only view of the live count. */
    val activeCount: Int get() = count
}

val LocalSecureFlagController = staticCompositionLocalOf<SecureFlagController?> { null }

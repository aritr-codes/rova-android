package com.aritr.rova.ui.signals

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4.1b — pure-JVM tests for [StorageSignal]. The (Int,Int,String,String) seam
 * keeps the test off real disk / settings; the estimator arithmetic itself
 * is already covered by StorageEstimator's own tests, so this file only
 * exercises the StateFlow plumbing.
 */
class StorageSignalTest {

    @Test fun `initial value is false before any recompute`() {
        assertFalse(StorageSignal(computeInsufficient = { _, _, _, _ -> true }).insufficientToStart.value)
    }

    @Test fun `recompute publishes true when the estimate exceeds free space`() {
        val signal = StorageSignal(computeInsufficient = { _, _, _, _ -> true })
        signal.recompute(durationSeconds = 30, loopCount = 10, resolution = "FHD", mode = "Portrait")
        assertTrue(signal.insufficientToStart.value)
    }

    @Test fun `recompute publishes false when there is enough space`() {
        val signal = StorageSignal(computeInsufficient = { _, _, _, _ -> false })
        signal.recompute(durationSeconds = 5, loopCount = 1, resolution = "SD", mode = "Portrait")
        assertFalse(signal.insufficientToStart.value)
    }

    @Test fun `recompute forwards its arguments to the computation`() {
        var seen: Triple<Int, Int, String>? = null
        val signal = StorageSignal(computeInsufficient = { d, l, r, _ -> seen = Triple(d, l, r); false })
        signal.recompute(durationSeconds = 42, loopCount = -1, resolution = "UHD", mode = "Portrait")
        assertEquals(Triple(42, -1, "UHD"), seen)
    }

    @Test fun `idempotent recompute emits exactly once for an unchanged result`() = runBlocking {
        val signal = StorageSignal(computeInsufficient = { _, _, _, _ -> false })
        val emissions = mutableListOf<Boolean>()
        val job = launch(Dispatchers.Unconfined) { signal.insufficientToStart.collect { emissions += it } }
        signal.recompute(1, 1, "HD", "Portrait"); signal.recompute(2, 2, "FHD", "Portrait")
        yield()
        job.cancelAndJoin()
        assertEquals(listOf(false), emissions)
    }

    @Test
    fun `recompute receives mode and forwards to compute seam`() {
        var capturedMode: String? = null
        val sig = StorageSignal(computeInsufficient = { _, _, _, mode ->
            capturedMode = mode
            false
        })
        sig.recompute(durationSeconds = 10, loopCount = 5, resolution = "FHD", mode = "PortraitLandscape")
        assertEquals("PortraitLandscape", capturedMode)
    }

    @Test
    fun `recompute publishes true when seam returns true`() {
        val sig = StorageSignal(computeInsufficient = { _, _, _, _ -> true })
        sig.recompute(10, 5, "FHD", "PortraitLandscape")
        assertTrue(sig.insufficientToStart.value)
    }

    @Test
    fun `recompute initial value is false until first compute`() {
        val sig = StorageSignal(computeInsufficient = { _, _, _, _ -> true })
        assertFalse(sig.insufficientToStart.value)
    }
}

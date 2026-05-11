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
 * Phase 4.1b — pure-JVM tests for [StorageSignal]. The (Int,Int,String) seam
 * keeps the test off real disk / settings; the estimator arithmetic itself
 * is already covered by StorageEstimator's own tests, so this file only
 * exercises the StateFlow plumbing.
 */
class StorageSignalTest {

    @Test fun `initial value is false before any recompute`() {
        assertFalse(StorageSignal(computeInsufficient = { _, _, _ -> true }).insufficientToStart.value)
    }

    @Test fun `recompute publishes true when the estimate exceeds free space`() {
        val signal = StorageSignal(computeInsufficient = { _, _, _ -> true })
        signal.recompute(durationSeconds = 30, loopCount = 10, resolution = "FHD")
        assertTrue(signal.insufficientToStart.value)
    }

    @Test fun `recompute publishes false when there is enough space`() {
        val signal = StorageSignal(computeInsufficient = { _, _, _ -> false })
        signal.recompute(durationSeconds = 5, loopCount = 1, resolution = "SD")
        assertFalse(signal.insufficientToStart.value)
    }

    @Test fun `recompute forwards its arguments to the computation`() {
        var seen: Triple<Int, Int, String>? = null
        val signal = StorageSignal(computeInsufficient = { d, l, r -> seen = Triple(d, l, r); false })
        signal.recompute(durationSeconds = 42, loopCount = -1, resolution = "UHD")
        assertEquals(Triple(42, -1, "UHD"), seen)
    }

    @Test fun `idempotent recompute emits exactly once for an unchanged result`() = runBlocking {
        val signal = StorageSignal(computeInsufficient = { _, _, _ -> false })
        val emissions = mutableListOf<Boolean>()
        val job = launch(Dispatchers.Unconfined) { signal.insufficientToStart.collect { emissions += it } }
        signal.recompute(1, 1, "HD"); signal.recompute(2, 2, "FHD")
        yield()
        job.cancelAndJoin()
        assertEquals(listOf(false), emissions)
    }
}

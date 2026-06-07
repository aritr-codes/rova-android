package com.aritr.rova.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Bug A — pure JVM tests for [MergeCompleteCount.resolve], the post-merge
 * summary-card count resolver.
 *
 * Pinned invariants:
 *  - exportedClipCount wins when > 0 (success-owned, the true saved count).
 *  - segmentCount is the fallback when no success count was published.
 *  - the early-user-stop case (segmentCount 0, exportedClipCount 1) reports 1
 *    instead of the buggy "0 clips saved to library".
 */
class MergeCompleteCountTest {

    @Test
    fun `exported count wins on early stop`() {
        // segmentCount 0 (loop never completed) but the partial clip IS saved.
        assertEquals(1, MergeCompleteCount.resolve(exportedClipCount = 1, segmentCount = 0))
    }

    @Test
    fun `falls back to segment count when no export count`() {
        assertEquals(3, MergeCompleteCount.resolve(exportedClipCount = 0, segmentCount = 3))
    }

    @Test
    fun `zero when neither known`() {
        assertEquals(0, MergeCompleteCount.resolve(exportedClipCount = 0, segmentCount = 0))
    }

    @Test
    fun `exported count wins even when both positive`() {
        assertEquals(2, MergeCompleteCount.resolve(exportedClipCount = 2, segmentCount = 5))
    }

    @Test
    fun `negative segment count coerces to zero`() {
        assertEquals(0, MergeCompleteCount.resolve(exportedClipCount = 0, segmentCount = -3))
    }
}

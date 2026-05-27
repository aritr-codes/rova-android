package com.aritr.rova.service.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * M5 §5 — pure-JVM tests for per-state progress-bar config. Null means
 * no progress bar; indeterminate=true means a spinning bar without a
 * fill percent.
 */
class NotificationProgressTest {

    @Test fun `ClipRecording returns null progress`() {
        val state: NotificationState = NotificationState.ClipRecording(current = 1, etaSecondsRemaining = 18)
        assertNull(state.toProgress())
    }

    @Test fun `GapWaiting with both counts returns determinate countdown`() {
        val state: NotificationState = NotificationState.GapWaiting(
            nextNumber = 2, nextInLabel = "4:42",
            nextStartsInSeconds = 60, gapTotalSeconds = 300
        )
        val progress = state.toProgress()!!
        assertEquals(300, progress.max)
        assertEquals(240, progress.current)
        assertEquals(false, progress.indeterminate)
    }

    @Test fun `GapWaiting missing nextStartsInSeconds returns null`() {
        val state: NotificationState = NotificationState.GapWaiting(
            nextNumber = 2, nextInLabel = "4:42",
            nextStartsInSeconds = null, gapTotalSeconds = 300
        )
        assertNull(state.toProgress())
    }

    @Test fun `GapWaiting missing gapTotalSeconds returns null`() {
        val state: NotificationState = NotificationState.GapWaiting(
            nextNumber = 2, nextInLabel = "4:42",
            nextStartsInSeconds = 60, gapTotalSeconds = null
        )
        assertNull(state.toProgress())
    }

    @Test fun `Merging with percent returns determinate`() {
        val state: NotificationState = NotificationState.Merging(done = 4, total = 6, mergeProgressPercent = 67)
        val progress = state.toProgress()!!
        assertEquals(100, progress.max)
        assertEquals(67, progress.current)
        assertEquals(false, progress.indeterminate)
    }

    @Test fun `Merging without percent returns indeterminate`() {
        val state: NotificationState = NotificationState.Merging(done = 0, total = 6, mergeProgressPercent = null)
        val progress = state.toProgress()!!
        assertEquals(0, progress.max)
        assertEquals(0, progress.current)
        assertEquals(true, progress.indeterminate)
    }

    @Test fun `MergeComplete returns null progress`() {
        val state: NotificationState = NotificationState.MergeComplete(clipCount = 6)
        assertNull(state.toProgress())
    }
}

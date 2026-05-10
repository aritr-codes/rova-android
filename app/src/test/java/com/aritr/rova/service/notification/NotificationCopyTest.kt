package com.aritr.rova.service.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Phase 3.1 — pure-JVM tests for [NotificationState.toCopy].
 *
 * Mockup source-of-truth: `mockups/new_uiux/09-notification-export.html`.
 * Helper is pure data → pure strings — no [android.content.Context],
 * no [android.app.Notification], no resources. Mirrors the
 * extension-fun seam idiom from the leaf-signal trio (3.2/3.3/3.4)
 * but the seam is the function itself, not a constructor.
 */
class NotificationCopyTest {

    @Test fun `ClipRecording with total renders verbatim mockup title`() {
        val copy = NotificationState.ClipRecording(current = 2, total = 6).toCopy()
        assertEquals("Recording · Clip 2 of 6", copy.title)
        assertEquals("Recording in progress", copy.body)
    }

    @Test fun `ClipRecording without total drops the of-N suffix`() {
        val copy = NotificationState.ClipRecording(current = 2, total = null).toCopy()
        assertEquals("Recording · Clip 2", copy.title)
    }

    @Test fun `GapWaiting with total renders verbatim mockup title plus body`() {
        val copy = NotificationState.GapWaiting(
            nextNumber = 3, total = 6, nextInLabel = "4:42"
        ).toCopy()
        assertEquals("Waiting · Clip 3 of 6 next", copy.title)
        assertEquals("Next clip starts in 4:42", copy.body)
    }

    @Test fun `GapWaiting without total drops the of-N suffix`() {
        val copy = NotificationState.GapWaiting(
            nextNumber = 3, total = null, nextInLabel = "30s"
        ).toCopy()
        assertEquals("Waiting · Clip 3 next", copy.title)
        assertEquals("Next clip starts in 30s", copy.body)
    }

    @Test fun `Merging mid-progress renders verbatim mockup title`() {
        val copy = NotificationState.Merging(done = 4, total = 6).toCopy()
        assertEquals("Merging clips · 4 of 6", copy.title)
        assertEquals("Processing — please wait", copy.body)
    }

    @Test fun `Merging at zero done renders 0-of-N`() {
        val copy = NotificationState.Merging(done = 0, total = 3).toCopy()
        assertEquals("Merging clips · 0 of 3", copy.title)
    }

    @Test fun `MergeComplete with multiple clips uses plural body`() {
        val copy = NotificationState.MergeComplete(clipCount = 6).toCopy()
        assertEquals("Merge complete", copy.title)
        assertEquals("6 clips saved to Library", copy.body)
    }

    @Test fun `MergeComplete with single clip uses singular body`() {
        val copy = NotificationState.MergeComplete(clipCount = 1).toCopy()
        assertEquals("Merge complete", copy.title)
        assertEquals("1 clip saved to Library", copy.body)
    }

    @Test fun `MergeComplete with zero clips uses plural body (0 clips)`() {
        val copy = NotificationState.MergeComplete(clipCount = 0).toCopy()
        assertEquals("0 clips saved to Library", copy.body)
    }

    @Test fun `large counts format integrity (no thousands separator)`() {
        val copy = NotificationState.Merging(done = 999, total = 1000).toCopy()
        assertEquals("Merging clips · 999 of 1000", copy.title)
    }

    @Test fun `NotificationCopy equality is structural (data class contract)`() {
        val a = NotificationCopy(title = "T", body = "B")
        val b = NotificationCopy(title = "T", body = "B")
        val c = NotificationCopy(title = "T", body = "X")
        assertEquals(a, b)
        assertNotEquals(a, c)
    }
}

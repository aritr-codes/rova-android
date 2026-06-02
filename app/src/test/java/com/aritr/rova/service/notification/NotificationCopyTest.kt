package com.aritr.rova.service.notification

import com.aritr.rova.R
import com.aritr.rova.ui.text.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Phase 3.1 — pure-JVM tests for [NotificationState.toCopy].
 *
 * Mockup source-of-truth: `mockups/new_uiux/09-notification-export.html`.
 * B3 i18n task 9b: the helper now returns resource-backed [UiText] tokens, so
 * these assert the token (id + args / plural) rather than resolved English. The
 * verbatim English is verified once at the resource layer (strings/plurals.xml).
 */
class NotificationCopyTest {

    @Test fun `ClipRecording with total renders verbatim mockup title`() {
        val copy = NotificationState.ClipRecording(current = 2, total = 6).toCopy()
        assertEquals(UiText.StrArgs(R.string.notification_clip_title_total, listOf(2, 6)), copy.title)
        assertEquals(UiText.Str(R.string.notification_clip_body_static), copy.body)
    }

    @Test fun `ClipRecording without total drops the of-N suffix`() {
        val copy = NotificationState.ClipRecording(current = 2, total = null).toCopy()
        assertEquals(UiText.StrArgs(R.string.notification_clip_title, listOf(2)), copy.title)
    }

    @Test fun `GapWaiting with total renders verbatim mockup title plus body`() {
        val copy = NotificationState.GapWaiting(
            nextNumber = 3, total = 6, nextInLabel = "4:42"
        ).toCopy()
        assertEquals(UiText.StrArgs(R.string.notification_gap_title_total, listOf(3, 6)), copy.title)
        assertEquals(UiText.StrArgs(R.string.notification_gap_body, listOf("4:42")), copy.body)
    }

    @Test fun `GapWaiting without total drops the of-N suffix`() {
        val copy = NotificationState.GapWaiting(
            nextNumber = 3, total = null, nextInLabel = "30s"
        ).toCopy()
        assertEquals(UiText.StrArgs(R.string.notification_gap_title, listOf(3)), copy.title)
        assertEquals(UiText.StrArgs(R.string.notification_gap_body, listOf("30s")), copy.body)
    }

    @Test fun `Merging mid-progress renders verbatim mockup title`() {
        val copy = NotificationState.Merging(done = 4, total = 6).toCopy()
        assertEquals(UiText.StrArgs(R.string.notification_merging_title, listOf(4, 6)), copy.title)
        assertEquals(UiText.Str(R.string.notification_merging_body), copy.body)
    }

    @Test fun `Merging at zero done renders 0-of-N`() {
        val copy = NotificationState.Merging(done = 0, total = 3).toCopy()
        assertEquals(UiText.StrArgs(R.string.notification_merging_title, listOf(0, 3)), copy.title)
    }

    @Test fun `MergeComplete with multiple clips uses plural body`() {
        val copy = NotificationState.MergeComplete(clipCount = 6).toCopy()
        assertEquals(UiText.Str(R.string.notification_complete_title), copy.title)
        assertEquals(UiText.Plural(R.plurals.notification_complete_body_nodur, 6, listOf(6)), copy.body)
    }

    @Test fun `MergeComplete with single clip uses singular body`() {
        val copy = NotificationState.MergeComplete(clipCount = 1).toCopy()
        assertEquals(UiText.Str(R.string.notification_complete_title), copy.title)
        assertEquals(UiText.Plural(R.plurals.notification_complete_body_nodur, 1, listOf(1)), copy.body)
    }

    @Test fun `MergeComplete with zero clips uses plural body (0 clips)`() {
        val copy = NotificationState.MergeComplete(clipCount = 0).toCopy()
        assertEquals(UiText.Plural(R.plurals.notification_complete_body_nodur, 0, listOf(0)), copy.body)
    }

    @Test fun `large counts format integrity (no thousands separator)`() {
        val copy = NotificationState.Merging(done = 999, total = 1000).toCopy()
        assertEquals(UiText.StrArgs(R.string.notification_merging_title, listOf(999, 1000)), copy.title)
    }

    @Test fun `NotificationCopy equality is structural (data class contract)`() {
        val t = UiText.Str(R.string.notification_complete_title)
        val b1 = UiText.Str(R.string.notification_merging_body)
        val b2 = UiText.Str(R.string.notification_clip_body_static)
        val a = NotificationCopy(title = t, body = b1)
        val b = NotificationCopy(title = t, body = b1)
        val c = NotificationCopy(title = t, body = b2)
        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    // M5 extensions — new optional numeric params

    @Test fun `ClipRecording with eta renders MM SS remaining body`() {
        val copy = NotificationState.ClipRecording(
            current = 2, total = 6, etaSecondsRemaining = 18
        ).toCopy()
        assertEquals(UiText.StrArgs(R.string.notification_clip_body_eta, listOf("0:18")), copy.body)
    }

    @Test fun `ClipRecording without eta falls back to static body`() {
        val copy = NotificationState.ClipRecording(
            current = 2, total = 6, etaSecondsRemaining = null
        ).toCopy()
        assertEquals(UiText.Str(R.string.notification_clip_body_static), copy.body)
    }

    @Test fun `ClipRecording with eta over a minute renders M SS`() {
        val copy = NotificationState.ClipRecording(
            current = 1, total = 6, etaSecondsRemaining = 75
        ).toCopy()
        assertEquals(UiText.StrArgs(R.string.notification_clip_body_eta, listOf("1:15")), copy.body)
    }

    @Test fun `ClipRecording with eta zero renders 0 00`() {
        val copy = NotificationState.ClipRecording(
            current = 6, total = 6, etaSecondsRemaining = 0
        ).toCopy()
        assertEquals(UiText.StrArgs(R.string.notification_clip_body_eta, listOf("0:00")), copy.body)
    }

    @Test fun `MergeComplete with duration renders clips dot total dot saved`() {
        val copy = NotificationState.MergeComplete(
            clipCount = 6, totalDurationSeconds = 300
        ).toCopy()
        assertEquals(
            UiText.Plural(R.plurals.notification_complete_body_dur, 6, listOf(6, "5:00")),
            copy.body
        )
    }

    @Test fun `MergeComplete singular with duration uses 1 clip`() {
        val copy = NotificationState.MergeComplete(
            clipCount = 1, totalDurationSeconds = 30
        ).toCopy()
        assertEquals(
            UiText.Plural(R.plurals.notification_complete_body_dur, 1, listOf(1, "0:30")),
            copy.body
        )
    }

    @Test fun `MergeComplete without duration falls back to existing copy`() {
        val copy = NotificationState.MergeComplete(
            clipCount = 6, totalDurationSeconds = null
        ).toCopy()
        assertEquals(UiText.Plural(R.plurals.notification_complete_body_nodur, 6, listOf(6)), copy.body)
    }

    @Test fun `ClipRecording with negative eta clamps to 0 00`() {
        val copy = NotificationState.ClipRecording(
            current = 1, total = 6, etaSecondsRemaining = -3
        ).toCopy()
        assertEquals(UiText.StrArgs(R.string.notification_clip_body_eta, listOf("0:00")), copy.body)
    }
}

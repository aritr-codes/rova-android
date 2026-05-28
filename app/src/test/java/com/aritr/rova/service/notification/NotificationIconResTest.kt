package com.aritr.rova.service.notification

import com.aritr.rova.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * M5 §7 — per-state @DrawableRes lookup. Pure int comparison; no
 * Android resource resolution needed under JVM tests (R fields are
 * compile-time int constants).
 */
class NotificationIconResTest {

    @Test fun `ClipRecording maps to ic_notif_recording`() {
        val state: NotificationState = NotificationState.ClipRecording(current = 1)
        assertEquals(R.drawable.ic_notif_recording, state.toIconRes())
    }

    @Test fun `GapWaiting maps to ic_notif_waiting`() {
        val state: NotificationState = NotificationState.GapWaiting(nextNumber = 2, nextInLabel = "1:00")
        assertEquals(R.drawable.ic_notif_waiting, state.toIconRes())
    }

    @Test fun `Merging maps to ic_notif_merging`() {
        val state: NotificationState = NotificationState.Merging(done = 0, total = 6)
        assertEquals(R.drawable.ic_notif_merging, state.toIconRes())
    }

    @Test fun `MergeComplete maps to ic_notif_complete`() {
        val state: NotificationState = NotificationState.MergeComplete(clipCount = 6)
        assertEquals(R.drawable.ic_notif_complete, state.toIconRes())
    }
}

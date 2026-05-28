package com.aritr.rova.service.notification

import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M5 §4 + §5 — pure-JVM tests for channel routing, accent color, and
 * dismissibility per state. Constants come from android.app, which is
 * resolvable at compile time but returns default ints under
 * isReturnDefaultValues — we assert against the literal int values
 * the framework defines.
 */
class NotificationChannelConfigTest {

    @Test fun `ClipRecording routes to session channel`() {
        val state: NotificationState = NotificationState.ClipRecording(current = 1)
        assertEquals(NotificationChannelConfig.SESSION_CHANNEL_ID, state.toChannelId())
    }

    @Test fun `GapWaiting routes to session channel`() {
        val state: NotificationState = NotificationState.GapWaiting(nextNumber = 1, nextInLabel = "1:00")
        assertEquals(NotificationChannelConfig.SESSION_CHANNEL_ID, state.toChannelId())
    }

    @Test fun `Merging routes to session channel`() {
        val state: NotificationState = NotificationState.Merging(done = 0, total = 6)
        assertEquals(NotificationChannelConfig.SESSION_CHANNEL_ID, state.toChannelId())
    }

    @Test fun `MergeComplete routes to complete channel`() {
        val state: NotificationState = NotificationState.MergeComplete(clipCount = 1)
        assertEquals(NotificationChannelConfig.COMPLETE_CHANNEL_ID, state.toChannelId())
    }

    @Test fun `session channel importance is LOW`() {
        assertEquals(
            NotificationManager.IMPORTANCE_LOW,
            NotificationChannelConfig.importanceFor(NotificationChannelConfig.SESSION_CHANNEL_ID)
        )
    }

    @Test fun `complete channel importance is DEFAULT`() {
        assertEquals(
            NotificationManager.IMPORTANCE_DEFAULT,
            NotificationChannelConfig.importanceFor(NotificationChannelConfig.COMPLETE_CHANNEL_ID)
        )
    }

    @Test fun `accent for recording-session states is brand blue`() {
        val rec: NotificationState = NotificationState.ClipRecording(current = 1)
        val gap: NotificationState = NotificationState.GapWaiting(nextNumber = 1, nextInLabel = "1:00")
        val merge: NotificationState = NotificationState.Merging(done = 0, total = 6)
        assertEquals(NotificationChannelConfig.ACCENT_RECORDING, rec.toAccent())
        assertEquals(NotificationChannelConfig.ACCENT_RECORDING, gap.toAccent())
        assertEquals(NotificationChannelConfig.ACCENT_RECORDING, merge.toAccent())
    }

    @Test fun `accent for MergeComplete is brand green`() {
        val state: NotificationState = NotificationState.MergeComplete(clipCount = 1)
        assertEquals(NotificationChannelConfig.ACCENT_COMPLETE, state.toAccent())
    }

    @Test fun `ACCENT_RECORDING is 0xFF5b7fff`() {
        assertEquals(0xFF5B7FFF.toInt(), NotificationChannelConfig.ACCENT_RECORDING)
    }

    @Test fun `ACCENT_COMPLETE is 0xFF34d399`() {
        assertEquals(0xFF34D399.toInt(), NotificationChannelConfig.ACCENT_COMPLETE)
    }

    @Test fun `only MergeComplete is dismissible`() {
        val rec: NotificationState = NotificationState.ClipRecording(current = 1)
        val gap: NotificationState = NotificationState.GapWaiting(nextNumber = 1, nextInLabel = "1:00")
        val merge: NotificationState = NotificationState.Merging(done = 0, total = 6)
        val complete: NotificationState = NotificationState.MergeComplete(clipCount = 1)
        assertFalse(rec.isDismissible())
        assertFalse(gap.isDismissible())
        assertFalse(merge.isDismissible())
        assertTrue(complete.isDismissible())
    }

    @Test fun `legacy channel id is preserved for back-compat reference`() {
        assertEquals("RovaRecordingChannel", NotificationChannelConfig.LEGACY_CHANNEL_ID)
    }
}

package com.aritr.rova.service.notification

import com.aritr.rova.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M5 §5 — pure-JVM tests for per-state action specs. The service
 * translates a spec into a [androidx.core.app.NotificationCompat.Action]
 * with a real `PendingIntent`; the spec itself is intent metadata only.
 */
class NotificationActionSpecTest {

    @Test fun `ClipRecording produces Stop + Open in order`() {
        val state: NotificationState = NotificationState.ClipRecording(current = 1)
        val actions = state.toActionSpecs()
        assertEquals(2, actions.size)
        assertEquals(NotificationActionKey.STOP, actions[0].key)
        assertEquals(NotificationActionKey.OPEN, actions[1].key)
    }

    @Test fun `GapWaiting produces StopEarly + Open in order`() {
        val state: NotificationState = NotificationState.GapWaiting(nextNumber = 2, nextInLabel = "1:00")
        val actions = state.toActionSpecs()
        assertEquals(2, actions.size)
        assertEquals(NotificationActionKey.STOP_EARLY, actions[0].key)
        assertEquals(NotificationActionKey.OPEN, actions[1].key)
    }

    @Test fun `Merging produces empty action list`() {
        val state: NotificationState = NotificationState.Merging(done = 4, total = 6)
        val actions = state.toActionSpecs()
        assertTrue(actions.isEmpty())
    }

    @Test fun `MergeComplete with sessionId produces View + Share`() {
        val state: NotificationState = NotificationState.MergeComplete(clipCount = 6, sessionId = "abc123")
        val actions = state.toActionSpecs()
        assertEquals(2, actions.size)
        assertEquals(NotificationActionKey.VIEW_IN_LIBRARY, actions[0].key)
        assertEquals("abc123", actions[0].sessionIdExtra)
        assertEquals(NotificationActionKey.SHARE, actions[1].key)
        assertEquals("abc123", actions[1].sessionIdExtra)
    }

    @Test fun `MergeComplete without sessionId produces View without extras`() {
        val state: NotificationState = NotificationState.MergeComplete(clipCount = 6, sessionId = null)
        val actions = state.toActionSpecs()
        assertEquals(2, actions.size)
        assertEquals(NotificationActionKey.VIEW_IN_LIBRARY, actions[0].key)
        assertNull(actions[0].sessionIdExtra)
    }

    @Test fun `every action spec has a non-blank contentDescription resource`() {
        val all = listOf<NotificationState>(
            NotificationState.ClipRecording(current = 1),
            NotificationState.GapWaiting(nextNumber = 1, nextInLabel = "1:00"),
            NotificationState.MergeComplete(clipCount = 1, sessionId = "x")
        )
        all.flatMap { it.toActionSpecs() }.forEach { spec ->
            assertTrue("contentDescription res must be non-zero for ${spec.key}", spec.contentDescriptionRes != 0)
        }
    }

    @Test fun `action labels point to the M5 strings`() {
        val complete: NotificationState = NotificationState.MergeComplete(clipCount = 1)
        val viewAction = complete.toActionSpecs()[0]
        assertEquals(R.string.notification_action_view_in_library, viewAction.labelRes)
        assertEquals(R.string.notification_action_view_in_library_cd, viewAction.contentDescriptionRes)
    }
}

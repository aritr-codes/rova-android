package com.aritr.rova.service.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M5 Phase 3 §7 — pure-JVM tests for [NotificationDotsRow.toDotsPlan].
 * No RemoteViews, no Context — DotsPlan is plain data.
 */
class NotificationDotsRowTest {

    // ----- ClipRecording -----

    @Test fun `ClipRecording with total=6 current=2 yields 6 pills DONE-CURRENT-todo`() {
        val plan = NotificationState.ClipRecording(current = 2, total = 6).toDotsPlan()
        assertTrue(plan.visible)
        assertEquals(6, plan.pills.size)
        assertEquals(DotState.Kind.DONE, plan.pills[0].kind)
        assertEquals(DotState.Kind.CURRENT, plan.pills[1].kind)
        assertEquals(DotState.Kind.TODO, plan.pills[2].kind)
        assertEquals(NotificationChannelConfig.ACCENT_RECORDING, plan.accent)
        assertEquals("Clip 2 of 6", plan.contentDescription)
    }

    @Test fun `ClipRecording with total=null is invisible (unknown total)`() {
        val plan = NotificationState.ClipRecording(current = 1, total = null).toDotsPlan()
        assertFalse(plan.visible)
        assertTrue(plan.pills.isEmpty())
    }

    // ----- GapWaiting -----

    @Test fun `GapWaiting with total=6 nextNumber=3 yields DONE-DONE-todo`() {
        val plan = NotificationState.GapWaiting(
            nextNumber = 3, nextInLabel = "4:42", total = 6
        ).toDotsPlan()
        assertTrue(plan.visible)
        assertEquals(6, plan.pills.size)
        assertEquals(DotState.Kind.DONE, plan.pills[0].kind)
        assertEquals(DotState.Kind.DONE, plan.pills[1].kind)
        assertEquals(DotState.Kind.TODO, plan.pills[2].kind)
        assertEquals(NotificationChannelConfig.ACCENT_RECORDING, plan.accent)
    }

    @Test fun `GapWaiting with total=null is invisible`() {
        val plan = NotificationState.GapWaiting(
            nextNumber = 2, nextInLabel = "1:00", total = null
        ).toDotsPlan()
        assertFalse(plan.visible)
    }

    // ----- Merging -----

    @Test fun `Merging with done=4 total=6 yields done-done-done-done-CURRENT-todo`() {
        val plan = NotificationState.Merging(done = 4, total = 6).toDotsPlan()
        assertTrue(plan.visible)
        assertEquals(6, plan.pills.size)
        assertEquals(DotState.Kind.DONE, plan.pills[3].kind)
        assertEquals(DotState.Kind.CURRENT, plan.pills[4].kind)
        assertEquals(DotState.Kind.TODO, plan.pills[5].kind)
        assertEquals(NotificationChannelConfig.ACCENT_RECORDING, plan.accent)
        assertEquals("Merging, 4 of 6 done", plan.contentDescription)
    }

    @Test fun `Merging with done=6 total=6 yields all DONE`() {
        val plan = NotificationState.Merging(done = 6, total = 6).toDotsPlan()
        assertTrue(plan.pills.all { it.kind == DotState.Kind.DONE })
        assertEquals(6, plan.pills.size)
    }

    // ----- MergeComplete -----

    @Test fun `MergeComplete with clipCount=6 yields 6 DONE green pills`() {
        val plan = NotificationState.MergeComplete(clipCount = 6).toDotsPlan()
        assertTrue(plan.visible)
        assertEquals(6, plan.pills.size)
        assertTrue(plan.pills.all { it.kind == DotState.Kind.DONE })
        assertEquals(NotificationChannelConfig.ACCENT_COMPLETE, plan.accent)
        assertEquals("All 6 clips complete", plan.contentDescription)
    }

    @Test fun `MergeComplete with clipCount=1 yields 1 DONE pill`() {
        val plan = NotificationState.MergeComplete(clipCount = 1).toDotsPlan()
        assertEquals(1, plan.pills.size)
        assertEquals(DotState.Kind.DONE, plan.pills[0].kind)
        assertEquals("All 1 clip complete", plan.contentDescription)
    }

    @Test fun `MergeComplete with clipCount=0 is invisible`() {
        val plan = NotificationState.MergeComplete(clipCount = 0).toDotsPlan()
        assertFalse(plan.visible)
        assertTrue(plan.pills.isEmpty())
    }

    // ----- Large N cap policy (§3.1) -----

    @Test fun `ClipRecording total=10 current=4 yields 7 state pills plus COUNT_PILL +3`() {
        val plan = NotificationState.ClipRecording(current = 4, total = 10).toDotsPlan()
        assertEquals(8, plan.pills.size)
        assertEquals(DotState.Kind.DONE, plan.pills[0].kind)
        assertEquals(DotState.Kind.CURRENT, plan.pills[3].kind)
        assertEquals(DotState.Kind.TODO, plan.pills[4].kind)
        assertEquals(DotState.Kind.COUNT_PILL, plan.pills[7].kind)
        assertEquals("+3", plan.pills[7].countLabel)
    }

    @Test fun `MergeComplete clipCount=50 yields 7 DONE pills plus COUNT_PILL +43`() {
        val plan = NotificationState.MergeComplete(clipCount = 50).toDotsPlan()
        assertEquals(8, plan.pills.size)
        assertTrue(plan.pills.take(7).all { it.kind == DotState.Kind.DONE })
        assertEquals(DotState.Kind.COUNT_PILL, plan.pills[7].kind)
        assertEquals("+43", plan.pills[7].countLabel)
        assertEquals("All 50 clips complete", plan.contentDescription)
    }

    // ----- Boundary: total = 8 — exactly fits, no count pill -----

    @Test fun `ClipRecording total=8 fits exactly without COUNT_PILL`() {
        val plan = NotificationState.ClipRecording(current = 3, total = 8).toDotsPlan()
        assertEquals(8, plan.pills.size)
        assertFalse(plan.pills.any { it.kind == DotState.Kind.COUNT_PILL })
    }
}

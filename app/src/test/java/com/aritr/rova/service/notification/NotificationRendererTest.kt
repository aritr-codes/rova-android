package com.aritr.rova.service.notification

import com.aritr.rova.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M5 Phase 2 + Phase 3 — pure-JVM tests for [NotificationRenderer.toBindPlan].
 * No RemoteViews, no Context, no Android resource resolution beyond
 * compile-time int constants. The plan is plain data; the service
 * consumes it to inflate + bind a real RemoteViews tree.
 */
class NotificationRendererTest {

    // ----- layout-res selection (shared across all 4 states) -----

    @Test fun `all states use the shared collapsed + expanded layouts`() {
        val states: List<NotificationState> = listOf(
            NotificationState.ClipRecording(current = 1),
            NotificationState.GapWaiting(nextNumber = 2, nextInLabel = "1:00"),
            NotificationState.Merging(done = 0, total = 6),
            NotificationState.MergeComplete(clipCount = 1)
        )
        states.forEach { s ->
            val plan = s.toBindPlan()
            assertEquals(R.layout.notif_collapsed, plan.layoutCollapsedRes)
            assertEquals(R.layout.notif_expanded, plan.layoutExpandedRes)
        }
    }

    // ----- accent + icon + isComplete wiring per state -----

    @Test fun `ClipRecording binds blue accent and recording icon`() {
        val plan = NotificationState.ClipRecording(current = 1).toBindPlan()
        assertEquals(NotificationChannelConfig.ACCENT_RECORDING, plan.accent)
        assertEquals(R.drawable.ic_notif_recording, plan.iconRes)
        assertFalse(plan.isComplete)
    }

    @Test fun `GapWaiting binds blue accent and waiting icon`() {
        val plan = NotificationState.GapWaiting(nextNumber = 1, nextInLabel = "1:00").toBindPlan()
        assertEquals(NotificationChannelConfig.ACCENT_RECORDING, plan.accent)
        assertEquals(R.drawable.ic_notif_waiting, plan.iconRes)
        assertFalse(plan.isComplete)
    }

    @Test fun `Merging binds blue accent and merging icon`() {
        val plan = NotificationState.Merging(done = 0, total = 6).toBindPlan()
        assertEquals(NotificationChannelConfig.ACCENT_RECORDING, plan.accent)
        assertEquals(R.drawable.ic_notif_merging, plan.iconRes)
        assertFalse(plan.isComplete)
    }

    @Test fun `MergeComplete binds green accent, complete icon, isComplete true`() {
        val plan = NotificationState.MergeComplete(clipCount = 1).toBindPlan()
        assertEquals(NotificationChannelConfig.ACCENT_COMPLETE, plan.accent)
        assertEquals(R.drawable.ic_notif_complete, plan.iconRes)
        assertTrue(plan.isComplete)
    }

    // ----- title + body delegate to Phase 1 toCopy() -----

    @Test fun `plan title + body match Phase 1 toCopy output`() {
        val state: NotificationState = NotificationState.MergeComplete(
            clipCount = 6, totalDurationSeconds = 300
        )
        val plan = state.toBindPlan()
        val copy = state.toCopy()
        assertEquals(copy.title, plan.title)
        assertEquals(copy.body, plan.body)
    }

    // ----- progress delegates to Phase 1 toProgress() -----

    @Test fun `ClipRecording progress is null (matches Phase 1)`() {
        val plan = NotificationState.ClipRecording(
            current = 1, etaSecondsRemaining = 18
        ).toBindPlan()
        assertNull(plan.progress)
    }

    @Test fun `GapWaiting with full countdown inputs surfaces determinate progress`() {
        val plan = NotificationState.GapWaiting(
            nextNumber = 2, nextInLabel = "4:42",
            nextStartsInSeconds = 60, gapTotalSeconds = 300
        ).toBindPlan()
        val p = plan.progress!!
        assertEquals(300, p.max)
        assertEquals(240, p.current)
        assertFalse(p.indeterminate)
    }

    @Test fun `Merging with percent surfaces determinate progress`() {
        val plan = NotificationState.Merging(
            done = 4, total = 6, mergeProgressPercent = 67
        ).toBindPlan()
        val p = plan.progress!!
        assertEquals(100, p.max)
        assertEquals(67, p.current)
        assertFalse(p.indeterminate)
    }

    @Test fun `Merging without percent surfaces indeterminate progress`() {
        val plan = NotificationState.Merging(
            done = 0, total = 6, mergeProgressPercent = null
        ).toBindPlan()
        val p = plan.progress!!
        assertTrue(p.indeterminate)
    }

    @Test fun `MergeComplete progress is null`() {
        val plan = NotificationState.MergeComplete(clipCount = 1).toBindPlan()
        assertNull(plan.progress)
    }

    // ----- collapsed tail per state -----

    @Test fun `ClipRecording collapsedTail is X SS remaining when eta present`() {
        val plan = NotificationState.ClipRecording(
            current = 2, total = 6, etaSecondsRemaining = 18
        ).toBindPlan()
        assertEquals("0:18 remaining", plan.collapsedTail)
    }

    @Test fun `ClipRecording collapsedTail is null when eta absent`() {
        val plan = NotificationState.ClipRecording(
            current = 2, total = 6, etaSecondsRemaining = null
        ).toBindPlan()
        assertNull(plan.collapsedTail)
    }

    @Test fun `GapWaiting collapsedTail is the nextInLabel`() {
        val plan = NotificationState.GapWaiting(
            nextNumber = 3, nextInLabel = "4:42"
        ).toBindPlan()
        assertEquals("4:42", plan.collapsedTail)
    }

    @Test fun `Merging collapsedTail is NN percent when percent present`() {
        val plan = NotificationState.Merging(
            done = 4, total = 6, mergeProgressPercent = 67
        ).toBindPlan()
        assertEquals("67%", plan.collapsedTail)
    }

    @Test fun `Merging collapsedTail is N of M when percent absent`() {
        val plan = NotificationState.Merging(
            done = 4, total = 6, mergeProgressPercent = null
        ).toBindPlan()
        assertEquals("4 of 6", plan.collapsedTail)
    }

    @Test fun `MergeComplete collapsedTail is N clip(s)`() {
        val one = NotificationState.MergeComplete(clipCount = 1).toBindPlan()
        val many = NotificationState.MergeComplete(clipCount = 6).toBindPlan()
        assertEquals("1 clip", one.collapsedTail)
        assertEquals("6 clips", many.collapsedTail)
    }

    // ----- M5 Phase 3 §7 — new fields -----

    @Test fun `all four states delegate dots to toDotsPlan`() {
        val states: List<NotificationState> = listOf(
            NotificationState.ClipRecording(current = 2, total = 6),
            NotificationState.GapWaiting(nextNumber = 2, nextInLabel = "1:00", total = 6),
            NotificationState.Merging(done = 0, total = 6),
            NotificationState.MergeComplete(clipCount = 6)
        )
        states.forEach { s ->
            val plan = s.toBindPlan()
            val direct = s.toDotsPlan()
            assertEquals(direct.pills.size, plan.dots.pills.size)
            assertEquals(direct.accent, plan.dots.accent)
            assertEquals(direct.visible, plan.dots.visible)
        }
    }

    @Test fun `MergeComplete carries green title color, other states null`() {
        assertNull(NotificationState.ClipRecording(current = 1).toBindPlan().titleColor)
        assertNull(NotificationState.GapWaiting(nextNumber = 1, nextInLabel = "1:00").toBindPlan().titleColor)
        assertNull(NotificationState.Merging(done = 0, total = 6).toBindPlan().titleColor)
        assertEquals(
            NotificationChannelConfig.ACCENT_COMPLETE,
            NotificationState.MergeComplete(clipCount = 1).toBindPlan().titleColor
        )
    }

    @Test fun `MergeComplete uses notif_surface_complete, other states notif_surface`() {
        assertEquals(R.drawable.notif_surface, NotificationState.ClipRecording(current = 1).toBindPlan().surfaceRes)
        assertEquals(R.drawable.notif_surface, NotificationState.GapWaiting(nextNumber = 1, nextInLabel = "1:00").toBindPlan().surfaceRes)
        assertEquals(R.drawable.notif_surface, NotificationState.Merging(done = 0, total = 6).toBindPlan().surfaceRes)
        assertEquals(R.drawable.notif_surface_complete, NotificationState.MergeComplete(clipCount = 1).toBindPlan().surfaceRes)
    }
}

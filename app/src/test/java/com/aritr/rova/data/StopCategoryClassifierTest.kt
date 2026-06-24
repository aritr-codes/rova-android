package com.aritr.rova.data

import org.junit.Assert.assertEquals
import org.junit.Test

class StopCategoryClassifierTest {
    private fun cat(t: Terminated?, r: StopReason) = StopCategoryClassifier.categorize(t, r)

    @Test fun `multi-segment kept is Recovered`() =
        assertEquals(StopCategory.RECOVERED, cat(Terminated.MULTI_SEGMENT_KEPT, StopReason.NONE))

    @Test fun `killed by system is Interrupted`() =
        assertEquals(StopCategory.INTERRUPTED, cat(Terminated.KILLED_BY_SYSTEM, StopReason.NONE))

    @Test fun `killed force stop is Interrupted`() =
        assertEquals(StopCategory.INTERRUPTED, cat(Terminated.KILLED_FORCE_STOP, StopReason.NONE))

    @Test fun `user stop thermal is SafetyStopped`() =
        assertEquals(StopCategory.SAFETY_STOPPED, cat(Terminated.USER_STOPPED, StopReason.THERMAL))

    @Test fun `user stop low storage is SafetyStopped`() =
        assertEquals(StopCategory.SAFETY_STOPPED, cat(Terminated.USER_STOPPED, StopReason.LOW_STORAGE))

    @Test fun `user stop schedule window is ScheduledEnd`() =
        assertEquals(StopCategory.SCHEDULED_END, cat(Terminated.USER_STOPPED, StopReason.SCHEDULE_WINDOW))

    @Test fun `user stop permission revoked is ErrorStopped`() =
        assertEquals(StopCategory.ERROR_STOPPED, cat(Terminated.USER_STOPPED, StopReason.PERMISSION_REVOKED))

    @Test fun `user stop init failed is ErrorStopped`() =
        assertEquals(StopCategory.ERROR_STOPPED, cat(Terminated.USER_STOPPED, StopReason.INIT_FAILED))

    @Test fun `user stop manual is UserStopped`() {
        assertEquals(StopCategory.USER_STOPPED, cat(Terminated.USER_STOPPED, StopReason.USER))
        assertEquals(StopCategory.USER_STOPPED, cat(Terminated.USER_STOPPED, StopReason.NONE))
    }

    @Test fun `completed is Completed`() =
        assertEquals(StopCategory.COMPLETED, cat(Terminated.COMPLETED, StopReason.NONE))

    @Test fun `null terminated is Completed`() =
        assertEquals(StopCategory.COMPLETED, cat(null, StopReason.NONE))
}

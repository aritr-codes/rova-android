package com.aritr.rova.ui.library

import com.aritr.rova.data.ExportState
import com.aritr.rova.data.StopReason
import com.aritr.rova.data.Terminated
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatusBadgePolicyTest {

    @Test fun `completed clean session has no badge`() {
        assertNull(StatusBadgePolicy.badgeFor(Terminated.COMPLETED, StopReason.NONE, ExportState.FINALIZED))
    }

    @Test fun `clean user stop has no badge`() {
        assertNull(StatusBadgePolicy.badgeFor(Terminated.USER_STOPPED, StopReason.USER, ExportState.FINALIZED))
    }

    @Test fun `multi-segment kept is Recovered`() {
        assertEquals(LibraryBadge.RECOVERED, StatusBadgePolicy.badgeFor(Terminated.MULTI_SEGMENT_KEPT, StopReason.NONE, ExportState.FINALIZED))
    }

    @Test fun `system kill is Interrupted`() {
        assertEquals(LibraryBadge.INTERRUPTED, StatusBadgePolicy.badgeFor(Terminated.KILLED_BY_SYSTEM, StopReason.NONE, ExportState.FINALIZED))
        assertEquals(LibraryBadge.INTERRUPTED, StatusBadgePolicy.badgeFor(Terminated.KILLED_FORCE_STOP, StopReason.NONE, ExportState.FINALIZED))
    }

    @Test fun `failed export is Interrupted even if terminal is clean`() {
        assertEquals(LibraryBadge.INTERRUPTED, StatusBadgePolicy.badgeFor(Terminated.COMPLETED, StopReason.NONE, ExportState.FAILED))
    }

    @Test fun `null terminated with finalized export has no badge`() {
        assertNull(StatusBadgePolicy.badgeFor(null, StopReason.NONE, ExportState.FINALIZED))
    }

    @Test fun `thermal auto-stop is AutoStopped`() {
        assertEquals(LibraryBadge.AUTO_STOPPED, StatusBadgePolicy.badgeFor(Terminated.USER_STOPPED, StopReason.THERMAL, ExportState.FINALIZED))
    }

    @Test fun `low storage auto-stop is AutoStopped`() {
        assertEquals(LibraryBadge.AUTO_STOPPED, StatusBadgePolicy.badgeFor(Terminated.USER_STOPPED, StopReason.LOW_STORAGE, ExportState.FINALIZED))
    }

    @Test fun `scheduled end has no badge`() {
        assertNull(StatusBadgePolicy.badgeFor(Terminated.USER_STOPPED, StopReason.SCHEDULE_WINDOW, ExportState.FINALIZED))
    }

    @Test fun `error stop is Interrupted`() {
        assertEquals(LibraryBadge.INTERRUPTED, StatusBadgePolicy.badgeFor(Terminated.USER_STOPPED, StopReason.PERMISSION_REVOKED, ExportState.FINALIZED))
        assertEquals(LibraryBadge.INTERRUPTED, StatusBadgePolicy.badgeFor(Terminated.USER_STOPPED, StopReason.INIT_FAILED, ExportState.FINALIZED))
    }

    @Test fun `safety stop keeps AutoStopped even if export failed`() {
        assertEquals(LibraryBadge.AUTO_STOPPED, StatusBadgePolicy.badgeFor(Terminated.USER_STOPPED, StopReason.THERMAL, ExportState.FAILED))
    }
}

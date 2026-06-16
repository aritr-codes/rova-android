package com.aritr.rova.ui.library

import com.aritr.rova.data.ExportState
import com.aritr.rova.data.Terminated
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatusBadgePolicyTest {

    @Test fun `completed clean session has no badge`() {
        assertNull(StatusBadgePolicy.badgeFor(Terminated.COMPLETED, ExportState.FINALIZED))
    }

    @Test fun `clean user stop has no badge`() {
        assertNull(StatusBadgePolicy.badgeFor(Terminated.USER_STOPPED, ExportState.FINALIZED))
    }

    @Test fun `multi-segment kept is Recovered`() {
        assertEquals(LibraryBadge.RECOVERED, StatusBadgePolicy.badgeFor(Terminated.MULTI_SEGMENT_KEPT, ExportState.FINALIZED))
    }

    @Test fun `system kill is Interrupted`() {
        assertEquals(LibraryBadge.INTERRUPTED, StatusBadgePolicy.badgeFor(Terminated.KILLED_BY_SYSTEM, ExportState.FINALIZED))
        assertEquals(LibraryBadge.INTERRUPTED, StatusBadgePolicy.badgeFor(Terminated.KILLED_FORCE_STOP, ExportState.FINALIZED))
    }

    @Test fun `failed export is Interrupted even if terminal is clean`() {
        assertEquals(LibraryBadge.INTERRUPTED, StatusBadgePolicy.badgeFor(Terminated.COMPLETED, ExportState.FAILED))
    }

    @Test fun `null terminated with finalized export has no badge`() {
        assertNull(StatusBadgePolicy.badgeFor(null, ExportState.FINALIZED))
    }
}

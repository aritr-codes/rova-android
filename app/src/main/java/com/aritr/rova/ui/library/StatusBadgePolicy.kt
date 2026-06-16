package com.aritr.rova.ui.library

import com.aritr.rova.data.ExportState
import com.aritr.rova.data.Terminated

/**
 * ADR-0030 — maps a session's terminal + export state to an exceptional badge,
 * or null for the clean common case (Complete / clean user-stop show nothing).
 * Pure; enum-only inputs.
 */
object StatusBadgePolicy {
    fun badgeFor(terminated: Terminated?, exportState: ExportState): LibraryBadge? = when {
        exportState == ExportState.FAILED -> LibraryBadge.INTERRUPTED
        terminated == Terminated.KILLED_BY_SYSTEM -> LibraryBadge.INTERRUPTED
        terminated == Terminated.KILLED_FORCE_STOP -> LibraryBadge.INTERRUPTED
        terminated == Terminated.MULTI_SEGMENT_KEPT -> LibraryBadge.RECOVERED
        else -> null
    }
}

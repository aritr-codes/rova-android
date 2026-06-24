package com.aritr.rova.ui.library

import com.aritr.rova.data.ExportState
import com.aritr.rova.data.StopCategory
import com.aritr.rova.data.StopCategoryClassifier
import com.aritr.rova.data.StopReason
import com.aritr.rova.data.Terminated

/**
 * ADR-0030 (+ 2026-06-24 amendment) — maps a session's stop taxonomy to an exceptional badge,
 * or null for the clean common case. Delegates the taxonomy to [StopCategoryClassifier] and
 * layers export-`FAILED` on top (Library-local: a failed export on an otherwise badge-less row
 * is Interrupted, but a specific safety/scheduled cause keeps its own badge). Pure; enum-only.
 */
object StatusBadgePolicy {
    fun badgeFor(terminated: Terminated?, stopReason: StopReason, exportState: ExportState): LibraryBadge? =
        when (StopCategoryClassifier.categorize(terminated, stopReason)) {
            StopCategory.RECOVERED -> LibraryBadge.RECOVERED
            StopCategory.SAFETY_STOPPED -> LibraryBadge.AUTO_STOPPED
            StopCategory.INTERRUPTED, StopCategory.ERROR_STOPPED -> LibraryBadge.INTERRUPTED
            StopCategory.SCHEDULED_END, StopCategory.USER_STOPPED, StopCategory.COMPLETED ->
                if (exportState == ExportState.FAILED) LibraryBadge.INTERRUPTED else null
        }
}

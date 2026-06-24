package com.aritr.rova.data

/**
 * Presentation taxonomy for how a recording ended (ADR-0016/0027/0030 presentation clauses,
 * design doc 2026-06-24). Pure / Android-free so it is JVM-unit-testable under
 * `isReturnDefaultValues = true`. The single reason→category seam shared by the Library badge
 * ([com.aritr.rova.ui.library.StatusBadgePolicy]) and the recovery card
 * ([com.aritr.rova.ui.recovery.RecoveryUiStateMapper]).
 *
 * Differentiates at presentation ONLY — terminal classification ([Terminated]) is unchanged.
 * `INTERRUPTED` here means a system/force KILL; export-`FAILED` is layered on by the Library
 * consumer, not folded in here (a clean user-stop whose export failed must stay `USER_STOPPED`,
 * not be mislabeled a kill — see design §3).
 */
enum class StopCategory {
    COMPLETED,
    USER_STOPPED,
    SAFETY_STOPPED,
    SCHEDULED_END,
    ERROR_STOPPED,
    INTERRUPTED,
    RECOVERED,
}

object StopCategoryClassifier {
    /** Pure stop taxonomy. `exportState` is intentionally NOT an input (design §3). */
    fun categorize(terminated: Terminated?, stopReason: StopReason): StopCategory = when {
        terminated == Terminated.MULTI_SEGMENT_KEPT -> StopCategory.RECOVERED
        terminated == Terminated.KILLED_BY_SYSTEM ||
            terminated == Terminated.KILLED_FORCE_STOP -> StopCategory.INTERRUPTED
        terminated == Terminated.USER_STOPPED &&
            (stopReason == StopReason.THERMAL ||
             stopReason == StopReason.LOW_STORAGE) -> StopCategory.SAFETY_STOPPED
        terminated == Terminated.USER_STOPPED &&
            stopReason == StopReason.SCHEDULE_WINDOW -> StopCategory.SCHEDULED_END
        terminated == Terminated.USER_STOPPED &&
            (stopReason == StopReason.PERMISSION_REVOKED ||
             stopReason == StopReason.INIT_FAILED) -> StopCategory.ERROR_STOPPED
        terminated == Terminated.COMPLETED -> StopCategory.COMPLETED
        terminated == Terminated.USER_STOPPED -> StopCategory.USER_STOPPED // USER / NONE
        else -> StopCategory.COMPLETED // terminated == null
    }
}

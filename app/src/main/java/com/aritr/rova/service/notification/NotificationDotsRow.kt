package com.aritr.rova.service.notification

import androidx.annotation.ColorInt

/**
 * M5 Phase 3 §7 — single pill in the clip-progress row.
 * [countLabel] is non-null only when [kind] is COUNT_PILL.
 */
data class DotState(val kind: Kind, val countLabel: String? = null) {
    enum class Kind { DONE, CURRENT, TODO, COUNT_PILL }
}

/**
 * M5 Phase 3 §7 — dots-row bind plan. Pure data; the service consumes
 * it to build the RemoteViews LinearLayout. [visible]=false means the
 * row is hidden entirely (unknown total / empty MergeComplete).
 *
 * §3.1 cap policy: up to 8 entries. For total > 8 the row is 7 state
 * pills + 1 trailing COUNT_PILL("+M") where M = total - 7.
 *
 * Spec: docs/superpowers/specs/2026-05-28-notification-redesign-phase3-design.md
 */
data class DotsPlan(
    val pills: List<DotState>,
    @ColorInt val accent: Int,
    val contentDescription: String,
    val visible: Boolean
) {
    companion object {
        const val MAX_VISIBLE_PILLS = 8
        const val STATE_PILLS_WHEN_CAPPED = 7
    }
}

fun NotificationState.toDotsPlan(): DotsPlan = when (this) {
    is NotificationState.ClipRecording -> buildPlanFromActive(
        accent = NotificationChannelConfig.ACCENT_RECORDING,
        total = total,
        currentIndex = current - 1,
        contentDescription = if (total != null) "Clip $current of $total" else ""
    )
    is NotificationState.GapWaiting -> buildPlanFromActive(
        accent = NotificationChannelConfig.ACCENT_RECORDING,
        total = total,
        currentIndex = nextNumber - 1,
        noCurrentState = true,
        contentDescription = if (total != null) "Waiting, $nextNumber of $total next" else ""
    )
    is NotificationState.Merging -> buildPlanFromActive(
        accent = NotificationChannelConfig.ACCENT_RECORDING,
        total = total,
        currentIndex = done,
        contentDescription = "Merging, $done of $total done"
    )
    is NotificationState.MergeComplete -> buildPlanForComplete(
        accent = NotificationChannelConfig.ACCENT_COMPLETE,
        clipCount = clipCount
    )
}

private fun buildPlanFromActive(
    @ColorInt accent: Int,
    total: Int?,
    currentIndex: Int,
    contentDescription: String,
    noCurrentState: Boolean = false
): DotsPlan {
    if (total == null || total <= 0) {
        return DotsPlan(pills = emptyList(), accent = accent, contentDescription = "", visible = false)
    }
    fun kindFor(i: Int): DotState.Kind = when {
        i < currentIndex -> DotState.Kind.DONE
        i == currentIndex && !noCurrentState -> DotState.Kind.CURRENT
        else -> DotState.Kind.TODO
    }
    val pills = if (total <= DotsPlan.MAX_VISIBLE_PILLS) {
        List(total) { i -> DotState(kind = kindFor(i)) }
    } else {
        val stateSlots = DotsPlan.STATE_PILLS_WHEN_CAPPED
        val statePills = List(stateSlots) { i -> DotState(kind = kindFor(i)) }
        val remainder = total - stateSlots
        statePills + DotState(kind = DotState.Kind.COUNT_PILL, countLabel = "+$remainder")
    }
    return DotsPlan(pills = pills, accent = accent, contentDescription = contentDescription, visible = true)
}

private fun buildPlanForComplete(@ColorInt accent: Int, clipCount: Int): DotsPlan {
    if (clipCount <= 0) {
        return DotsPlan(pills = emptyList(), accent = accent, contentDescription = "", visible = false)
    }
    val cd = if (clipCount == 1) "All 1 clip complete" else "All $clipCount clips complete"
    val pills = if (clipCount <= DotsPlan.MAX_VISIBLE_PILLS) {
        List(clipCount) { DotState(kind = DotState.Kind.DONE) }
    } else {
        val statePills = List(DotsPlan.STATE_PILLS_WHEN_CAPPED) { DotState(kind = DotState.Kind.DONE) }
        val remainder = clipCount - DotsPlan.STATE_PILLS_WHEN_CAPPED
        statePills + DotState(kind = DotState.Kind.COUNT_PILL, countLabel = "+$remainder")
    }
    return DotsPlan(pills = pills, accent = accent, contentDescription = cd, visible = true)
}

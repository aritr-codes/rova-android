package com.aritr.rova.ui.warnings

import androidx.annotation.StringRes
import com.aritr.rova.R
import com.aritr.rova.ui.theme.RovaGlyph

/**
 * Where a given [WarningId] is surfaced on the Record screen (ADR 0007). The
 * [WarningCenterViewModel] still resolves the single highest-priority active
 * warning; this only decides how that warning is drawn.
 *
 * - [HardBlockSheet]   — recording can't start / must abort (camera perm, exact
 *                        alarm, storage). Modal, auto-presents, collapses to a
 *                        WarningChip on "Not now"; FAB goes Disabled, nav dims.
 * - [SoftSheet]        — degraded but recordable (mic denied → video-only). Modal,
 *                        secondary action "Continue without audio" dismisses to a chip.
 * - [AdvisorySheet]    — informational (notifications off, battery-opt on, power-save).
 *                        Modal, secondary "Not now" dismisses to a chip; never blocks Start.
 * - [TopBanner]        — mid-recording risks (thermal, low/critical battery, camera in
 *                        use/disabled). Rendered as a top banner over the active
 *                        viewfinder, not a sheet. R1 wires the idle-reachable surfaces
 *                        (sheets/chips); the top-banner render path lands with R2 unless
 *                        trivially cheap here (spec A6).
 */
enum class WarningSurface { HardBlockSheet, SoftSheet, AdvisorySheet, TopBanner }

/**
 * Phase 4.3 — visual style hint for a [WarningAction]. Defaults to
 * [Primary] so all existing sheets compile without change. [Link]
 * renders as the destructive-text-only treatment (red, transparent
 * background, underlined) used by C2.4 §2.4 "Discard session".
 */
internal enum class WarningActionStyle { Primary, Secondary, Link }

fun warningSurfaceFor(id: WarningId): WarningSurface = when (id) {
    WarningId.CAMERA_PERMISSION_DENIED, WarningId.EXACT_ALARM_DENIED, WarningId.STORAGE_INSUFFICIENT -> WarningSurface.HardBlockSheet
    WarningId.MICROPHONE_DENIED -> WarningSurface.SoftSheet
    WarningId.NOTIFICATIONS_DENIED, WarningId.BATTERY_OPTIMIZATION_ON, WarningId.POWER_SAVE_MODE,
    WarningId.CANT_MERGE,
    WarningId.SAVE_FOLDER_UNAVAILABLE -> WarningSurface.AdvisorySheet
    WarningId.THERMAL_SHUTDOWN, WarningId.THERMAL_EMERGENCY, WarningId.THERMAL_CRITICAL, WarningId.THERMAL_SEVERE, WarningId.THERMAL_MODERATE,
    WarningId.THERMAL_AUTOSTOPPED,
    WarningId.BATTERY_CRITICAL, WarningId.BATTERY_LOW, WarningId.CAMERA_IN_USE, WarningId.CAMERA_DISABLED,
    WarningId.STORAGE_LOW_MID_REC,
    WarningId.STORAGE_FULL_AUTOSTOPPED -> WarningSurface.TopBanner       // ← NEW arm (Phase 4 Slice 2)
}

internal data class WarningAction(
    @StringRes val label: Int,
    val target: ActionTarget,
    val style: WarningActionStyle = WarningActionStyle.Primary,
)

internal enum class ActionTarget {
    EXACT_ALARM_SETTINGS,
    BATTERY_OPTIMIZATION,
    NOTIFICATION_SETTINGS,
    APP_DETAILS_SETTINGS,
    /** VM-only target — routes to [WarningCenterViewModel.snoozeForever]. NOT an Intent. */
    SNOOZE_FOREVER,
    /** Phase 4 Slice 2 — Intent target: opens system storage settings (with APPLICATION_DETAILS fallback). */
    STORAGE_SETTINGS,
    /** Phase 4 Slice 2 — VM-only target: routes to [WarningCenterViewModel.dismissAutoStopEcho]. NOT an Intent. */
    DISMISS_AUTOSTOP_ECHO,
    /** Phase 4 Slice 2 — host-navigation target: opens History (host wires via onNavigateToHistory). NOT an Intent. */
    REVIEW_SESSION,
    /** Phase 4 Slice 3 — VM-only target: routes to RecordScreen's ThermalTipsSheet host (via WarningCenter's onOpenThermalTips param). NOT an Intent. */
    OPEN_THERMAL_TIPS,
    /** Phase 4.3 — VM-only target: routes to RecoveryViewModel.keepRaw(sessionId). NOT an Intent. */
    KEEP_SEGMENTS_ONLY,
    /** Phase 4.3 — VM-only target: routes to RecoveryViewModel.dismiss(sessionId). NOT an Intent. */
    DISCARD_RECOVERY_SESSION,
}

internal data class WarningSheetContent(
    val glyph: RovaGlyph,
    @StringRes val title: Int,
    /** Short supporting line; @StringRes 0 = blank for TopBanner-only defensive arms. Final copy is the dev's call. */
    @StringRes val body: Int,
    /** Primary CTA — always present (e.g. "Open App Settings"). */
    val primary: WarningAction,
    /** Secondary CTA — present for HardBlock/Soft/Advisory sheets ("Not now" / "Continue without audio"); may be null for TopBanner. */
    val secondary: WarningAction?,
    /**
     * Phase 4.3 — optional third CTA, stacked under [secondary]. Used by
     * C2.4 ("Can't merge yet") for the "Discard session" destructive-link.
     * Null = no third row rendered (back-compat for all existing sheets).
     */
    val tertiary: WarningAction? = null,
    /**
     * Overflow ⋯ menu items (top-right of sheet). Empty list = no menu rendered.
     * Each action targets either an Intent (`launchActionTarget`) or
     * [ActionTarget.SNOOZE_FOREVER] (handled by the VM).
     */
    val overflow: List<WarningAction> = emptyList(),
    /**
     * Body text revealed when the "Why this matters" expander is open.
     * Null = expander row is not rendered for this id.
     */
    @StringRes val whyThisMatters: Int? = null,
)

/**
 * The 21-arm sheet-content map (ADR 0007). Copy mirrors `mockups/new_uiux/07-warnings.html`.
 * Glyphs come from [WarningIconSpec] (System-D, ADR-0031). Replaces `bannerContent` now that [WarningSheet] is live.
 * STORAGE_LOW_MID_REC (#11, R2) has a defensive arm — TopBanner-only, never renders as a sheet.
 */
internal fun warningSheetContent(id: WarningId): WarningSheetContent = when (id) {
    WarningId.CAMERA_PERMISSION_DENIED -> WarningSheetContent(
        WarningIconSpec.glyphFor(id), R.string.warning_camera_perm_title,
        R.string.warning_camera_perm_body,
        WarningAction(R.string.warning_camera_perm_primary, ActionTarget.APP_DETAILS_SETTINGS),
        WarningAction(R.string.warning_action_not_now, ActionTarget.APP_DETAILS_SETTINGS),
    )
    WarningId.EXACT_ALARM_DENIED -> WarningSheetContent(
        WarningIconSpec.glyphFor(id), R.string.warning_exact_alarm_title,
        R.string.warning_exact_alarm_body,
        WarningAction(R.string.warning_exact_alarm_primary, ActionTarget.EXACT_ALARM_SETTINGS),
        WarningAction(R.string.warning_action_not_now, ActionTarget.EXACT_ALARM_SETTINGS),
    )
    WarningId.STORAGE_INSUFFICIENT -> WarningSheetContent(
        WarningIconSpec.glyphFor(id), R.string.warning_storage_insufficient_title,
        R.string.warning_storage_insufficient_body,
        WarningAction(R.string.warning_storage_insufficient_primary, ActionTarget.APP_DETAILS_SETTINGS),
        WarningAction(R.string.warning_action_not_now, ActionTarget.APP_DETAILS_SETTINGS),
    )
    WarningId.MICROPHONE_DENIED -> WarningSheetContent(
        WarningIconSpec.glyphFor(id), R.string.warning_mic_title,
        R.string.warning_mic_body,
        WarningAction(R.string.warning_mic_primary, ActionTarget.APP_DETAILS_SETTINGS),
        WarningAction(R.string.warning_mic_secondary, ActionTarget.APP_DETAILS_SETTINGS),
    )
    WarningId.NOTIFICATIONS_DENIED -> WarningSheetContent(
        WarningIconSpec.glyphFor(id), R.string.warning_notifications_title,
        R.string.warning_notifications_body,
        WarningAction(R.string.warning_notifications_primary, ActionTarget.NOTIFICATION_SETTINGS),
        WarningAction(R.string.warning_action_not_now, ActionTarget.NOTIFICATION_SETTINGS),
        overflow = listOf(
            WarningAction(R.string.warning_action_dont_show_again, ActionTarget.SNOOZE_FOREVER),
        ),
        whyThisMatters = R.string.warning_notifications_why,
    )
    WarningId.BATTERY_OPTIMIZATION_ON -> WarningSheetContent(
        WarningIconSpec.glyphFor(id), R.string.warning_battery_opt_title,
        R.string.warning_battery_opt_body,
        WarningAction(R.string.warning_battery_opt_primary, ActionTarget.BATTERY_OPTIMIZATION),
        WarningAction(R.string.warning_action_not_now, ActionTarget.BATTERY_OPTIMIZATION),
        overflow = listOf(
            WarningAction(R.string.warning_action_dont_show_again, ActionTarget.SNOOZE_FOREVER),
        ),
        whyThisMatters = R.string.warning_battery_opt_why,
    )
    WarningId.POWER_SAVE_MODE -> WarningSheetContent(
        WarningIconSpec.glyphFor(id), R.string.warning_power_save_title,
        R.string.warning_power_save_body,
        WarningAction(R.string.warning_power_save_primary, ActionTarget.APP_DETAILS_SETTINGS),
        WarningAction(R.string.warning_action_not_now, ActionTarget.APP_DETAILS_SETTINGS),
        overflow = listOf(
            WarningAction(R.string.warning_action_dont_show_again, ActionTarget.SNOOZE_FOREVER),
        ),
        whyThisMatters = R.string.warning_power_save_why,
    )
    WarningId.THERMAL_SHUTDOWN -> WarningSheetContent(WarningIconSpec.glyphFor(id), R.string.warning_thermal_shutdown_title, 0, WarningAction(R.string.warning_action_ok, ActionTarget.APP_DETAILS_SETTINGS), null)
    WarningId.THERMAL_EMERGENCY -> WarningSheetContent(WarningIconSpec.glyphFor(id), R.string.warning_thermal_emergency_title, 0, WarningAction(R.string.warning_action_ok, ActionTarget.APP_DETAILS_SETTINGS), null)
    WarningId.THERMAL_CRITICAL -> WarningSheetContent(WarningIconSpec.glyphFor(id), R.string.warning_thermal_critical_title, 0, WarningAction(R.string.warning_action_ok, ActionTarget.APP_DETAILS_SETTINGS), null)
    WarningId.THERMAL_SEVERE -> WarningSheetContent(WarningIconSpec.glyphFor(id), R.string.warning_thermal_severe_title, 0, WarningAction(R.string.warning_action_ok, ActionTarget.APP_DETAILS_SETTINGS), null)
    WarningId.THERMAL_MODERATE -> WarningSheetContent(WarningIconSpec.glyphFor(id), R.string.warning_thermal_moderate_title, 0, WarningAction(R.string.warning_action_ok, ActionTarget.APP_DETAILS_SETTINGS), null)
    WarningId.BATTERY_CRITICAL -> WarningSheetContent(WarningIconSpec.glyphFor(id), R.string.warning_battery_critical_title, 0, WarningAction(R.string.warning_action_ok, ActionTarget.APP_DETAILS_SETTINGS), null)
    WarningId.BATTERY_LOW -> WarningSheetContent(WarningIconSpec.glyphFor(id), R.string.warning_battery_low_title, 0, WarningAction(R.string.warning_action_ok, ActionTarget.APP_DETAILS_SETTINGS), null)
    WarningId.STORAGE_LOW_MID_REC -> WarningSheetContent(
        // Defensive — STORAGE_LOW_MID_REC is TopBanner-only (see midRecBannerContent in T6).
        // This arm keeps warningSheetContent exhaustive over WarningId; never renders as a sheet.
        WarningIconSpec.glyphFor(id), R.string.warning_storage_low_mid_rec_title,
        R.string.warning_storage_low_mid_rec_body,
        WarningAction(R.string.warning_action_ok, ActionTarget.APP_DETAILS_SETTINGS),
        null,
    )
    WarningId.STORAGE_FULL_AUTOSTOPPED -> WarningSheetContent(
        // Defensive — STORAGE_FULL_AUTOSTOPPED is TopBanner-only (rendered
        // on Idle, not as a sheet). This arm keeps warningSheetContent
        // exhaustive over WarningId; never renders as a sheet.
        WarningIconSpec.glyphFor(id), R.string.warning_storage_full_autostopped_title,
        R.string.warning_storage_full_autostopped_body,
        WarningAction(R.string.warning_action_ok, ActionTarget.APP_DETAILS_SETTINGS),
        null,
    )
    WarningId.THERMAL_AUTOSTOPPED -> WarningSheetContent(
        // Defensive — THERMAL_AUTOSTOPPED is TopBanner-only (rendered on
        // Idle, not as a sheet). This arm keeps warningSheetContent
        // exhaustive over WarningId; never renders as a sheet.
        WarningIconSpec.glyphFor(id), R.string.warning_thermal_autostopped_title,
        R.string.warning_thermal_autostopped_body,
        WarningAction(R.string.warning_action_ok, ActionTarget.APP_DETAILS_SETTINGS),
        null,
    )
    WarningId.CANT_MERGE -> WarningSheetContent(
        WarningIconSpec.glyphFor(id), R.string.warning_cant_merge_title,
        R.string.warning_cant_merge_body,
        primary = WarningAction(R.string.warning_cant_merge_primary, ActionTarget.STORAGE_SETTINGS),
        secondary = WarningAction(R.string.warning_cant_merge_secondary, ActionTarget.KEEP_SEGMENTS_ONLY, WarningActionStyle.Secondary),
        tertiary = WarningAction(R.string.warning_cant_merge_tertiary, ActionTarget.DISCARD_RECOVERY_SESSION, WarningActionStyle.Link),
    )
    WarningId.CAMERA_IN_USE -> WarningSheetContent(WarningIconSpec.glyphFor(id), R.string.warning_camera_in_use_title, R.string.warning_camera_in_use_body, WarningAction(R.string.warning_action_ok, ActionTarget.APP_DETAILS_SETTINGS), null)
    WarningId.CAMERA_DISABLED -> WarningSheetContent(WarningIconSpec.glyphFor(id), R.string.warning_camera_disabled_title, 0, WarningAction(R.string.warning_action_ok, ActionTarget.APP_DETAILS_SETTINGS), null)
    WarningId.SAVE_FOLDER_UNAVAILABLE -> WarningSheetContent(
        // B4b (ADR-0024) — custom SAF save folder gone or permission revoked.
        // Advisory sheet: user must open Settings > Save location to re-select.
        WarningIconSpec.glyphFor(id), R.string.warning_save_folder_unavailable_title,
        R.string.warning_save_folder_unavailable_body,
        WarningAction(R.string.warning_save_folder_unavailable_primary, ActionTarget.APP_DETAILS_SETTINGS),
        WarningAction(R.string.warning_action_not_now, ActionTarget.APP_DETAILS_SETTINGS),
        overflow = listOf(
            WarningAction(R.string.warning_action_dont_show_again, ActionTarget.SNOOZE_FOREVER),
        ),
    )
}

internal data class TopBannerContent(
    val glyph: RovaGlyph,
    @StringRes val title: Int,
    @StringRes val sub: Int,
    @StringRes val cta: Int,
    /**
     * Optional auto-action countdown — when non-null, the banner renders a
     * countdown ring instead of the trailing CTA pill. Phase 4.4 will wire a
     * real seconds-source; this slice ships a static placeholder.
     */
    val autoAction: AutoAction? = null,
    /**
     * Phase 4 Slice 2 — overflow ⋯ menu items (top-right of banner). Empty
     * list = no overflow icon rendered. Each action targets either an Intent
     * (`launchActionTarget`) or a VM-only target like
     * [ActionTarget.DISMISS_AUTOSTOP_ECHO] (handled by the routing call site).
     */
    val overflow: List<WarningAction> = emptyList(),
)

/**
 * Placeholder countdown payload for the top-banner ring. [secondsRemaining]
 * is static in Phase 4 — a real ticking source lands in Phase 4.4 alongside
 * thermal hysteresis.
 */
internal data class AutoAction(val secondsRemaining: Int, @StringRes val description: Int)

/**
 * R2 — copy for the mid-recording top banner (ADR 0007 amendment 2026-05-13). One arm
 * per WarningId mapped to [WarningSurface.TopBanner] (10 ids total). Pure / JVM-testable.
 * Calling this with a non-TopBanner id is a caller bug — the function throws.
 */
internal fun midRecBannerContent(id: WarningId): TopBannerContent = when (id) {
    WarningId.THERMAL_SHUTDOWN -> TopBannerContent(
        WarningIconSpec.glyphFor(id), R.string.warning_banner_thermal_shutdown_title,
        R.string.warning_banner_thermal_shutdown_sub, R.string.warning_banner_cta_stop,
        autoAction = AutoAction(
            secondsRemaining = 30,
            description = R.string.warning_banner_auto_stop_protect,
        ),
    )
    WarningId.THERMAL_EMERGENCY -> TopBannerContent(
        WarningIconSpec.glyphFor(id), R.string.warning_banner_thermal_emergency_title,
        R.string.warning_banner_thermal_emergency_sub, R.string.warning_banner_cta_stop,
        autoAction = AutoAction(
            secondsRemaining = 30,
            description = R.string.warning_banner_auto_stop_protect,
        ),
    )
    WarningId.THERMAL_CRITICAL -> TopBannerContent(
        WarningIconSpec.glyphFor(id), R.string.warning_banner_thermal_critical_title,
        R.string.warning_banner_thermal_critical_sub, R.string.warning_banner_cta_stop,
    )
    WarningId.THERMAL_SEVERE -> TopBannerContent(
        WarningIconSpec.glyphFor(id), R.string.warning_banner_thermal_severe_title,
        R.string.warning_banner_thermal_severe_sub, R.string.warning_banner_cta_stop,
    )
    WarningId.THERMAL_MODERATE -> TopBannerContent(
        WarningIconSpec.glyphFor(id), R.string.warning_banner_thermal_moderate_title,
        R.string.warning_banner_thermal_moderate_sub, R.string.warning_banner_cta_stop,
    )
    WarningId.BATTERY_CRITICAL -> TopBannerContent(
        WarningIconSpec.glyphFor(id), R.string.warning_banner_battery_critical_title,
        R.string.warning_banner_battery_critical_sub, R.string.warning_banner_cta_stop,
    )
    WarningId.BATTERY_LOW -> TopBannerContent(
        WarningIconSpec.glyphFor(id), R.string.warning_banner_battery_low_title,
        R.string.warning_banner_battery_low_sub, R.string.warning_banner_cta_stop,
    )
    WarningId.CAMERA_IN_USE -> TopBannerContent(
        WarningIconSpec.glyphFor(id), R.string.warning_banner_camera_in_use_title,
        R.string.warning_banner_camera_in_use_sub, R.string.warning_banner_cta_stop,
    )
    WarningId.CAMERA_DISABLED -> TopBannerContent(
        WarningIconSpec.glyphFor(id), R.string.warning_banner_camera_disabled_title,
        R.string.warning_banner_camera_disabled_sub, R.string.warning_banner_cta_stop,
    )
    WarningId.STORAGE_LOW_MID_REC -> TopBannerContent(
        WarningIconSpec.glyphFor(id), R.string.warning_banner_storage_low_title,
        R.string.warning_banner_storage_low_sub, R.string.warning_banner_cta_stop,
    )
    WarningId.STORAGE_FULL_AUTOSTOPPED -> TopBannerContent(
        WarningIconSpec.glyphFor(id), R.string.warning_banner_storage_full_title,
        R.string.warning_banner_storage_full_sub, R.string.warning_banner_storage_full_cta,
        overflow = listOf(
            WarningAction(R.string.warning_action_dont_show_again, ActionTarget.DISMISS_AUTOSTOP_ECHO),
            WarningAction(R.string.warning_action_review_session, ActionTarget.REVIEW_SESSION),
        ),
    )
    WarningId.THERMAL_AUTOSTOPPED -> TopBannerContent(
        WarningIconSpec.glyphFor(id), R.string.warning_banner_thermal_autostopped_title,
        R.string.warning_banner_thermal_autostopped_sub, R.string.warning_banner_thermal_autostopped_cta,
        overflow = listOf(
            WarningAction(R.string.warning_action_dont_show_again, ActionTarget.DISMISS_AUTOSTOP_ECHO),
            WarningAction(R.string.warning_action_review_session, ActionTarget.REVIEW_SESSION),
        ),
    )
    // All other WarningIds are NOT TopBanner-mapped — calling midRecBannerContent on them is a caller bug.
    WarningId.CAMERA_PERMISSION_DENIED,
    WarningId.EXACT_ALARM_DENIED,
    WarningId.STORAGE_INSUFFICIENT,
    WarningId.MICROPHONE_DENIED,
    WarningId.BATTERY_OPTIMIZATION_ON,
    WarningId.POWER_SAVE_MODE,
    WarningId.NOTIFICATIONS_DENIED,
    WarningId.CANT_MERGE,
    WarningId.SAVE_FOLDER_UNAVAILABLE ->
        error("midRecBannerContent called for non-mid-rec id $id — caller bug; gate on warningSurfaceFor(id) == TopBanner")
}

/** True iff [warningSheetContent] for [id] declares an overflow ⋯ menu. */
internal fun hasOverflow(id: WarningId): Boolean =
    warningSheetContent(id).overflow.isNotEmpty()

/** True iff [warningSheetContent] for [id] declares a "Why this matters" expander. */
internal fun shouldShowWhy(id: WarningId): Boolean =
    warningSheetContent(id).whyThisMatters != null

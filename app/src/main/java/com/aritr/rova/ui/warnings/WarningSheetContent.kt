package com.aritr.rova.ui.warnings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlarmOff
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.NoPhotography
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.ui.graphics.vector.ImageVector

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

fun warningSurfaceFor(id: WarningId): WarningSurface = when (id) {
    WarningId.CAMERA_PERMISSION_DENIED, WarningId.EXACT_ALARM_DENIED, WarningId.STORAGE_INSUFFICIENT -> WarningSurface.HardBlockSheet
    WarningId.MICROPHONE_DENIED -> WarningSurface.SoftSheet
    WarningId.NOTIFICATIONS_DENIED, WarningId.BATTERY_OPTIMIZATION_ON, WarningId.POWER_SAVE_MODE -> WarningSurface.AdvisorySheet
    WarningId.THERMAL_SHUTDOWN, WarningId.THERMAL_EMERGENCY, WarningId.THERMAL_CRITICAL, WarningId.THERMAL_SEVERE, WarningId.THERMAL_MODERATE,
    WarningId.BATTERY_CRITICAL, WarningId.BATTERY_LOW, WarningId.CAMERA_IN_USE, WarningId.CAMERA_DISABLED,
    WarningId.STORAGE_LOW_MID_REC -> WarningSurface.TopBanner       // ← NEW arm in the TopBanner cluster
}

internal data class WarningAction(val label: String, val target: ActionTarget)

internal enum class ActionTarget {
    EXACT_ALARM_SETTINGS,
    BATTERY_OPTIMIZATION,
    NOTIFICATION_SETTINGS,
    APP_DETAILS_SETTINGS,
    /** VM-only target — routes to [WarningCenterViewModel.snoozeForever]. NOT an Intent. */
    SNOOZE_FOREVER,
}

internal data class WarningSheetContent(
    val icon: ImageVector,
    val title: String,
    /** Short supporting line; never blank for sheet-rendered warnings. Final copy is the dev's call. */
    val body: String,
    /** Primary CTA — always present (e.g. "Open App Settings"). */
    val primary: WarningAction,
    /** Secondary CTA — present for HardBlock/Soft/Advisory sheets ("Not now" / "Continue without audio"); may be null for TopBanner. */
    val secondary: WarningAction?,
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
    val whyThisMatters: String? = null,
)

/**
 * The 17-arm sheet-content map (ADR 0007). Copy mirrors `mockups/new_uiux/07-warnings.html`.
 * Icons reuse the ones the old banner carried. Replaces `bannerContent` now that [WarningSheet] is live.
 * STORAGE_LOW_MID_REC (#11, R2) has a defensive arm — TopBanner-only, never renders as a sheet.
 */
internal fun warningSheetContent(id: WarningId): WarningSheetContent = when (id) {
    WarningId.CAMERA_PERMISSION_DENIED -> WarningSheetContent(
        Icons.Default.NoPhotography, "Camera access required",
        "Rova can't record without camera access. Grant the permission in App Settings to continue.",
        WarningAction("Open App Settings", ActionTarget.APP_DETAILS_SETTINGS),
        WarningAction("Not now", ActionTarget.APP_DETAILS_SETTINGS),
    )
    WarningId.EXACT_ALARM_DENIED -> WarningSheetContent(
        Icons.Default.AlarmOff, "Alarm permission required",
        "Rova uses exact alarms to time recording segments. Without it, clips won't start or stop on schedule.",
        WarningAction("Allow exact alarms", ActionTarget.EXACT_ALARM_SETTINGS),
        WarningAction("Not now", ActionTarget.EXACT_ALARM_SETTINGS),
    )
    WarningId.STORAGE_INSUFFICIENT -> WarningSheetContent(
        Icons.Default.Storage, "Not enough storage to start",
        "Free up space, then try again.",
        WarningAction("Free up space", ActionTarget.APP_DETAILS_SETTINGS),
        WarningAction("Not now", ActionTarget.APP_DETAILS_SETTINGS),
    )
    WarningId.MICROPHONE_DENIED -> WarningSheetContent(
        Icons.Default.MicOff, "Recording without audio",
        "This session will record video only. You can grant microphone access in Settings and try again.",
        WarningAction("Grant microphone access", ActionTarget.APP_DETAILS_SETTINGS),
        WarningAction("Continue without audio", ActionTarget.APP_DETAILS_SETTINGS),
    )
    WarningId.NOTIFICATIONS_DENIED -> WarningSheetContent(
        Icons.Default.NotificationsOff, "Stay in the loop",
        "Enable notifications to see when recording starts, stops, or finishes merging — even with the screen off.",
        WarningAction("Enable notifications", ActionTarget.NOTIFICATION_SETTINGS),
        WarningAction("Not now", ActionTarget.NOTIFICATION_SETTINGS),
        overflow = listOf(
            WarningAction("Don't show again", ActionTarget.SNOOZE_FOREVER),
        ),
        whyThisMatters = "Notifications are how Rova tells you what's happening while you're not " +
            "in the app — recording started, clip merged, session finished. Without them, you'll " +
            "need to open the app to check progress.",
    )
    WarningId.BATTERY_OPTIMIZATION_ON -> WarningSheetContent(
        Icons.Default.BatterySaver, "Battery optimization may stop recording",
        "Android may kill Rova in the background. Disable battery optimization for reliable long sessions.",
        WarningAction("Disable", ActionTarget.BATTERY_OPTIMIZATION),
        WarningAction("Not now", ActionTarget.BATTERY_OPTIMIZATION),
        overflow = listOf(
            WarningAction("Don't show again", ActionTarget.SNOOZE_FOREVER),
        ),
        whyThisMatters = "Android's battery optimizer can pause background apps to save power. " +
            "If Rova is paused mid-recording, the session may stop early or skip clips. " +
            "Exempting Rova keeps the foreground service alive for the full session.",
    )
    WarningId.POWER_SAVE_MODE -> WarningSheetContent(
        Icons.Default.PowerSettingsNew, "Power-save mode may throttle recording",
        "Turning off battery saver gives Rova full CPU/IO for the session.",
        WarningAction("Settings", ActionTarget.APP_DETAILS_SETTINGS),
        WarningAction("Not now", ActionTarget.APP_DETAILS_SETTINGS),
        overflow = listOf(
            WarningAction("Don't show again", ActionTarget.SNOOZE_FOREVER),
        ),
        whyThisMatters = "Battery saver caps CPU frequency and background I/O. " +
            "Rova may drop frames or fall behind on encoding, which can corrupt clip boundaries " +
            "on long sessions.",
    )
    WarningId.THERMAL_SHUTDOWN -> WarningSheetContent(Icons.Default.Thermostat, "Device overheating — recording stopped", "", WarningAction("OK", ActionTarget.APP_DETAILS_SETTINGS), null)
    WarningId.THERMAL_EMERGENCY -> WarningSheetContent(Icons.Default.Thermostat, "Device critically hot", "", WarningAction("OK", ActionTarget.APP_DETAILS_SETTINGS), null)
    WarningId.THERMAL_CRITICAL -> WarningSheetContent(Icons.Default.Thermostat, "Device very hot — recording may stop", "", WarningAction("OK", ActionTarget.APP_DETAILS_SETTINGS), null)
    WarningId.THERMAL_SEVERE -> WarningSheetContent(Icons.Default.Thermostat, "Device hot — quality may drop", "", WarningAction("OK", ActionTarget.APP_DETAILS_SETTINGS), null)
    WarningId.THERMAL_MODERATE -> WarningSheetContent(Icons.Default.Thermostat, "Device warming up", "", WarningAction("OK", ActionTarget.APP_DETAILS_SETTINGS), null)
    WarningId.BATTERY_CRITICAL -> WarningSheetContent(Icons.Default.BatteryAlert, "Battery critical — recording may stop", "", WarningAction("OK", ActionTarget.APP_DETAILS_SETTINGS), null)
    WarningId.BATTERY_LOW -> WarningSheetContent(Icons.Default.BatteryAlert, "Battery low — consider charging", "", WarningAction("OK", ActionTarget.APP_DETAILS_SETTINGS), null)
    WarningId.STORAGE_LOW_MID_REC -> WarningSheetContent(
        // Defensive — STORAGE_LOW_MID_REC is TopBanner-only (see midRecBannerContent in T6).
        // This arm keeps warningSheetContent exhaustive over WarningId; never renders as a sheet.
        Icons.Default.Storage, "Storage running low",
        "Free space on this device.",
        WarningAction("OK", ActionTarget.APP_DETAILS_SETTINGS),
        null,
    )
    WarningId.CAMERA_IN_USE -> WarningSheetContent(Icons.Default.VideocamOff, "Camera in use by another app", "Close the other camera app.", WarningAction("OK", ActionTarget.APP_DETAILS_SETTINGS), null)
    WarningId.CAMERA_DISABLED -> WarningSheetContent(Icons.Default.VideocamOff, "Camera disabled by device policy", "", WarningAction("OK", ActionTarget.APP_DETAILS_SETTINGS), null)
}

internal data class TopBannerContent(
    val icon: ImageVector,
    val title: String,
    val sub: String,
    val cta: String,            // "Stop" for all R2 ids
    /**
     * Optional auto-action countdown — when non-null, the banner renders a
     * countdown ring instead of the trailing CTA pill. Phase 4.4 will wire a
     * real seconds-source; this slice ships a static placeholder.
     */
    val autoAction: AutoAction? = null,
)

/**
 * Placeholder countdown payload for the top-banner ring. [secondsRemaining]
 * is static in Phase 4 — a real ticking source lands in Phase 4.4 alongside
 * thermal hysteresis.
 */
internal data class AutoAction(val secondsRemaining: Int, val description: String)

/**
 * R2 — copy for the mid-recording top banner (ADR 0007 amendment 2026-05-13). One arm
 * per WarningId mapped to [WarningSurface.TopBanner] (10 ids total). Pure / JVM-testable.
 * Calling this with a non-TopBanner id is a caller bug — the function throws.
 */
internal fun midRecBannerContent(id: WarningId): TopBannerContent = when (id) {
    WarningId.THERMAL_SHUTDOWN -> TopBannerContent(
        Icons.Default.Thermostat, "Device overheating — stopping",
        "Recording will stop automatically.", "Stop",
    )
    WarningId.THERMAL_EMERGENCY -> TopBannerContent(
        Icons.Default.Thermostat, "Device critically hot",
        "Stop now to let it cool.", "Stop",
    )
    WarningId.THERMAL_CRITICAL -> TopBannerContent(
        Icons.Default.Thermostat, "Device very hot",
        "Recording may auto-stop soon.", "Stop",
    )
    WarningId.THERMAL_SEVERE -> TopBannerContent(
        Icons.Default.Thermostat, "Device hot",
        "Quality may drop.", "Stop",
    )
    WarningId.THERMAL_MODERATE -> TopBannerContent(
        Icons.Default.Thermostat, "Device warming up",
        "Watch the temperature.", "Stop",
    )
    WarningId.BATTERY_CRITICAL -> TopBannerContent(
        Icons.Default.BatteryAlert, "Battery critical",
        "Recording may stop soon.", "Stop",
    )
    WarningId.BATTERY_LOW -> TopBannerContent(
        Icons.Default.BatteryAlert, "Battery low",
        "Consider charging.", "Stop",
    )
    WarningId.CAMERA_IN_USE -> TopBannerContent(
        Icons.Default.VideocamOff, "Camera in use",
        "Another app is using the camera.", "Stop",
    )
    WarningId.CAMERA_DISABLED -> TopBannerContent(
        Icons.Default.VideocamOff, "Camera disabled",
        "Disabled by device policy.", "Stop",
    )
    WarningId.STORAGE_LOW_MID_REC -> TopBannerContent(
        Icons.Default.Storage, "Storage running low",
        "Free space on this device.", "Stop",
    )
    // All other WarningIds are NOT TopBanner-mapped — calling midRecBannerContent on them is a caller bug.
    WarningId.CAMERA_PERMISSION_DENIED,
    WarningId.EXACT_ALARM_DENIED,
    WarningId.STORAGE_INSUFFICIENT,
    WarningId.MICROPHONE_DENIED,
    WarningId.BATTERY_OPTIMIZATION_ON,
    WarningId.POWER_SAVE_MODE,
    WarningId.NOTIFICATIONS_DENIED ->
        error("midRecBannerContent called for non-mid-rec id $id — caller bug; gate on warningSurfaceFor(id) == TopBanner")
}

/** True iff [warningSheetContent] for [id] declares an overflow ⋯ menu. */
internal fun hasOverflow(id: WarningId): Boolean =
    warningSheetContent(id).overflow.isNotEmpty()

/** True iff [warningSheetContent] for [id] declares a "Why this matters" expander. */
internal fun shouldShowWhy(id: WarningId): Boolean =
    warningSheetContent(id).whyThisMatters != null

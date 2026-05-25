package com.aritr.rova.ui.warnings

/**
 * Phase 4.2 — screen-routing axis for the WarningCenter. NOT the same as
 * [WarningSurface] (which describes visual chrome). A single warning can
 * route to multiple screens: e.g. `STORAGE_INSUFFICIENT` surfaces on
 * Record (Start gate), History (export visibility), AND Settings
 * (Permissions & status section).
 *
 * Record is the catch-all — Record reads precedence-resolved single
 * `activeWarning` (existing behavior, hard invariant). History and
 * Settings read the per-screen multi-active list via
 * `WarningCenterViewModel.activeWarningsFor(screen)`.
 */
internal enum class WarningScreen { Record, History, Settings }

/**
 * Phase 4.2 — WarningIds routed to the History screen's top warning
 * strip. Allowlist locked by [WarningScreenAllowlistTest]. Add/remove
 * here ONLY in tandem with a contract amendment.
 */
internal val HISTORY_WARNINGS: Set<WarningId> = setOf(
    WarningId.STORAGE_INSUFFICIENT,
    WarningId.STORAGE_FULL_AUTOSTOPPED,
    WarningId.THERMAL_AUTOSTOPPED,
    WarningId.CANT_MERGE,
    WarningId.NOTIFICATIONS_DENIED,
)

/**
 * Phase 4.2 — WarningIds routed to the Settings screen's
 * "Permissions & status" section. Allowlist locked by
 * [WarningScreenAllowlistTest].
 */
internal val SETTINGS_WARNINGS: Set<WarningId> = setOf(
    WarningId.CAMERA_PERMISSION_DENIED,
    WarningId.EXACT_ALARM_DENIED,
    WarningId.STORAGE_INSUFFICIENT,
    WarningId.MICROPHONE_DENIED,
    WarningId.BATTERY_OPTIMIZATION_ON,
    WarningId.POWER_SAVE_MODE,
    WarningId.NOTIFICATIONS_DENIED,
)

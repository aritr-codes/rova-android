package com.aritr.rova.ui.screens.chrome

/**
 * PR-β (ADR-0029 §B) — pure, JVM-testable placement decisions for Record-home
 * chrome. The ONLY decision point for where a slot lives per orientation. No
 * Compose / Android imports — runs under isReturnDefaultValues=true. The call
 * site (RecordScreen via slotModifier) maps [SlotAnchor] -> Compose Alignment
 * and the dp ints -> .dp. Portrait values equal today's literals exactly
 * (regression-pinned by ChromeSlotPlacementTest).
 */

/** Device configuration orientation, as Record-home cares about it. */
enum class ChromeOrientation { PORTRAIT, LANDSCAPE }

/** Re-placeable Record-home regions. */
enum class ChromeSlot {
    STATUS_OVERLAY,   // idle status pill
    CAMERA_CONTROLS,  // flash + flip
    RECOVERY_CHIP,    // idle recovery nudge
    WARNING_CENTER,   // idle warning surface
    ACTIVE_HUD,       // recording HUD wrapper (warning + loop/status/bar)
    PARAMS_CARD,      // settings/params card (holds the mode chip; PR-γ's picker slot)
    NAV_RAIL,         // Library + Settings destinations (landscape only)
    RECORD_ACTION,    // Start/Stop FAB (landscape standalone placement)
    CONFIG_SUMMARY,   // landscape config card docked inboard of the rail (PR-β)
    SETTINGS_SHEET,   // landscape standard (non-modal) settings side panel band (PR-β)
}

/** Semantic anchor; mapped to a Compose Alignment at the call site. */
enum class SlotAnchor {
    TOP_START, TOP_END, TOP_CENTER,
    BOTTOM_CENTER, CENTER_START, CENTER_END,
}

/**
 * Explicit edge insets (dp ints) + optional width cap. maxWidthDp non-null =>
 * constrain width (the anchor's horizontal centering then centers it).
 */
data class SlotPlacement(
    val anchor: SlotAnchor,
    val startDp: Int = 0,
    val topDp: Int = 0,
    val endDp: Int = 0,
    val bottomDp: Int = 0,
    val maxWidthDp: Int? = null,
)

// ── Placement constants (the pure-Int placement contract) ────────────────────
private const val EDGE = 16              // screen edge margin (RecordChromeTokens.screenEdgeMargin)
private const val RECOVERY_TOP_P = 70    // portrait: just below the status pill
private const val WARNING_TOP_P = 110    // portrait: below the recovery chip
private const val RECOVERY_TOP_L = 54    // landscape: shorter viewport, pulled up
private const val WARNING_TOP_L = 92
private const val CAM_INSET_P = 7        // portrait cam-control edge inset (today's literal)
private const val CAM_INSET_L = 12
private const val HUD_TOP_P = 16
private const val HUD_TOP_L = 12
private const val CARD_BOTTOM_P = 120    // = RecordChromeMetrics.bottomNavClearance(90)+settingsCardLift(30); pinned by test
private const val CARD_BOTTOM_L = 12
private const val LAND_MAX_WIDTH = 360   // landscape cap for HUD + params card
private const val NAV_RAIL_INSET = 12
private const val RECORD_ACTION_INSET = 14
private const val RAIL_BAND_INSET = 96   // NAV_RAIL_INSET + rail width + gap; inboard offset for the config card / side sheet
private const val CONFIG_MAX_WIDTH = 200 // landscape config-summary card width cap
private const val SIDE_SHEET_WIDTH = 380 // landscape settings side-panel width cap

/** Pure. The single placement decision per (slot, orientation). */
fun placementFor(slot: ChromeSlot, orientation: ChromeOrientation): SlotPlacement {
    val landscape = orientation == ChromeOrientation.LANDSCAPE
    return when (slot) {
        ChromeSlot.STATUS_OVERLAY ->
            // Status pill stays at EDGE in both orientations; the shorter landscape
            // viewport is absorbed by the pulled-up recovery/warning offsets below.
            SlotPlacement(SlotAnchor.TOP_START, startDp = EDGE, topDp = EDGE)
        ChromeSlot.RECOVERY_CHIP ->
            SlotPlacement(SlotAnchor.TOP_START, startDp = EDGE, topDp = if (landscape) RECOVERY_TOP_L else RECOVERY_TOP_P)
        ChromeSlot.WARNING_CENTER ->
            SlotPlacement(SlotAnchor.TOP_START, startDp = EDGE, topDp = if (landscape) WARNING_TOP_L else WARNING_TOP_P)
        ChromeSlot.CAMERA_CONTROLS ->
            SlotPlacement(SlotAnchor.TOP_END, topDp = if (landscape) CAM_INSET_L else CAM_INSET_P, endDp = if (landscape) CAM_INSET_L else CAM_INSET_P)
        ChromeSlot.ACTIVE_HUD ->
            if (landscape) SlotPlacement(SlotAnchor.TOP_CENTER, topDp = HUD_TOP_L, maxWidthDp = LAND_MAX_WIDTH)
            else SlotPlacement(SlotAnchor.TOP_CENTER, topDp = HUD_TOP_P)
        ChromeSlot.PARAMS_CARD ->
            if (landscape) SlotPlacement(SlotAnchor.BOTTOM_CENTER, bottomDp = CARD_BOTTOM_L, maxWidthDp = LAND_MAX_WIDTH)
            else SlotPlacement(SlotAnchor.BOTTOM_CENTER, startDp = EDGE, endDp = EDGE, bottomDp = CARD_BOTTOM_P)
        ChromeSlot.NAV_RAIL ->
            // Advisory in portrait (portrait renders the intact bottom bar, not this slot).
            SlotPlacement(SlotAnchor.CENTER_START, startDp = NAV_RAIL_INSET)
        ChromeSlot.RECORD_ACTION ->
            // Advisory in portrait (portrait renders the FAB inside the bottom-bar dock, not this slot).
            SlotPlacement(SlotAnchor.CENTER_END, endDp = RECORD_ACTION_INSET)
        ChromeSlot.CONFIG_SUMMARY ->
            // Advisory default (Trailing). Real landscape placement comes from the navEdge overload.
            SlotPlacement(SlotAnchor.CENTER_END, endDp = RAIL_BAND_INSET, maxWidthDp = CONFIG_MAX_WIDTH)
        ChromeSlot.SETTINGS_SHEET ->
            SlotPlacement(SlotAnchor.CENTER_END, endDp = RAIL_BAND_INSET, maxWidthDp = SIDE_SHEET_WIDTH)
    }
}

/**
 * PR-β edge-aware overload. [navEdge] resolves which horizontal edge the
 * landscape rail / config card / side sheet hug (system-nav-bar edge). Portrait
 * is edge-agnostic and delegates to the 2-arg form (byte-identical, pinned).
 * Non-edge landscape slots also delegate to the 2-arg form unchanged.
 */
fun placementFor(slot: ChromeSlot, orientation: ChromeOrientation, navEdge: NavEdge): SlotPlacement {
    if (orientation != ChromeOrientation.LANDSCAPE) return placementFor(slot, orientation)
    val trailing = navEdge == NavEdge.Trailing
    return when (slot) {
        ChromeSlot.NAV_RAIL ->
            if (trailing) SlotPlacement(SlotAnchor.CENTER_END, endDp = NAV_RAIL_INSET)
            else SlotPlacement(SlotAnchor.CENTER_START, startDp = NAV_RAIL_INSET)
        ChromeSlot.RECORD_ACTION ->
            if (trailing) SlotPlacement(SlotAnchor.CENTER_END, endDp = RECORD_ACTION_INSET)
            else SlotPlacement(SlotAnchor.CENTER_START, startDp = RECORD_ACTION_INSET)
        ChromeSlot.CONFIG_SUMMARY ->
            if (trailing) SlotPlacement(SlotAnchor.CENTER_END, endDp = RAIL_BAND_INSET, maxWidthDp = CONFIG_MAX_WIDTH)
            else SlotPlacement(SlotAnchor.CENTER_START, startDp = RAIL_BAND_INSET, maxWidthDp = CONFIG_MAX_WIDTH)
        ChromeSlot.SETTINGS_SHEET ->
            if (trailing) SlotPlacement(SlotAnchor.CENTER_END, endDp = RAIL_BAND_INSET, maxWidthDp = SIDE_SHEET_WIDTH)
            else SlotPlacement(SlotAnchor.CENTER_START, startDp = RAIL_BAND_INSET, maxWidthDp = SIDE_SHEET_WIDTH)
        else -> placementFor(slot, orientation)
    }
}

package com.aritr.rova.ui.warnings

/**
 * The 17 Record-screen warning banners, in PRECEDENCE ORDER (highest
 * first). Declaration order IS the contract — [WarningPrecedence.resolve]
 * returns the first active one, and `WarningIdOrderTest` pins the
 * ordinals so a reorder cannot slip through review.
 *
 * Order mirrors NEW_UI_BACKEND_REPLAN.md the "Phase 4" "Banner precedence"
 * table (owner-signed 2026-05-11), rows 1..19. As of Phase 4 Slice 3 all 17
 * rows are reachable from [WarningPrecedence.resolve]. R2 (2026-05-13)
 * inserts row #11 STORAGE_LOW_MID_REC per ADR 0007 amendment.
 *
 * [gatesStart] = this warning, while active, disables the Record screen's
 * Start button (Phase 4.1b). Only `CAMERA_PERMISSION_DENIED` and
 * `STORAGE_INSUFFICIENT` do — `EXACT_ALARM_DENIED` is `HARD_BLOCK`-tier
 * (chrome) but does NOT gate Start (recording still runs, on inexact
 * alarms). `gatesStart` and `tier` are orthogonal: `tier` is banner
 * chrome, `gatesStart` is behavior. The Record screen does NOT read
 * `gatesStart` off the *resolved* warning (a higher-priority non-gating
 * warning can be showing while a gating one is also active); it reads the
 * underlying signals directly. `gatesStart` is documentation here and a
 * pin in `WarningIdOrderTest`.
 */
enum class WarningId(val tier: WarningTier, val gatesStart: Boolean = false) {
    // Hard block — recording can't start / must abort
    CAMERA_PERMISSION_DENIED(WarningTier.HARD_BLOCK, gatesStart = true),   // #1
    EXACT_ALARM_DENIED(WarningTier.HARD_BLOCK),                            // #2
    STORAGE_INSUFFICIENT(WarningTier.HARD_BLOCK, gatesStart = true),       // #3
    // Critical — active session at risk
    THERMAL_SHUTDOWN(WarningTier.CRITICAL),             // #4
    THERMAL_EMERGENCY(WarningTier.CRITICAL),            // #5
    THERMAL_CRITICAL(WarningTier.CRITICAL),             // #6
    BATTERY_CRITICAL(WarningTier.CRITICAL),             // #7
    CAMERA_IN_USE(WarningTier.CRITICAL),                // #8
    CAMERA_DISABLED(WarningTier.CRITICAL),              // #9
    // Advisory — degraded but functional
    BATTERY_LOW(WarningTier.ADVISORY),                  // #10
    STORAGE_LOW_MID_REC(WarningTier.ADVISORY),          // #11  ← (R2 — ADR 0007 amendment 2026-05-13)
    STORAGE_FULL_AUTOSTOPPED(WarningTier.ADVISORY),     // #12  ← (Phase 4 Slice 2 — echo of past auto-stop)
    THERMAL_AUTOSTOPPED(WarningTier.ADVISORY),          // #13  ← NEW (Phase 4 Slice 3 — echo of thermal auto-stop)
    THERMAL_SEVERE(WarningTier.ADVISORY),               // #14
    MICROPHONE_DENIED(WarningTier.ADVISORY),            // #15
    BATTERY_OPTIMIZATION_ON(WarningTier.ADVISORY),      // #16
    POWER_SAVE_MODE(WarningTier.ADVISORY),              // #17
    THERMAL_MODERATE(WarningTier.ADVISORY),             // #18
    NOTIFICATIONS_DENIED(WarningTier.ADVISORY)          // #19
}

/** Visual tier for the banner chrome. NOT the priority axis — that is [WarningId.ordinal]. */
enum class WarningTier { HARD_BLOCK, CRITICAL, ADVISORY }

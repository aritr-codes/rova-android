package com.aritr.rova.ui.warnings

/**
 * The 16 Record-screen warning banners, in PRECEDENCE ORDER (highest
 * first). Declaration order IS the contract — [WarningPrecedence.resolve]
 * returns the first active one, and `WarningIdOrderTest` pins the
 * ordinals so a reorder cannot slip through review.
 *
 * Order mirrors NEW_UI_BACKEND_REPLAN.md §"Phase 4" "Banner precedence"
 * table (owner-signed 2026-05-11), rows 1..16. Rows 1 (camera-perm),
 * 3 (storage-to-start) and 12 (mic/video-only) are present here but NOT
 * yet reachable from [WarningPrecedence.resolve] — their producers land
 * in Phase 4.1b. They exist now so this enum and `bannerContent` are
 * complete and 4.1b is purely additive.
 */
enum class WarningId(val tier: WarningTier) {
    // Hard block — recording can't start / must abort
    CAMERA_PERMISSION_DENIED(WarningTier.HARD_BLOCK),   // #1  — DEFERRED to 4.1b
    EXACT_ALARM_DENIED(WarningTier.HARD_BLOCK),         // #2
    STORAGE_INSUFFICIENT(WarningTier.HARD_BLOCK),       // #3  — DEFERRED to 4.1b
    // Critical — active session at risk
    THERMAL_SHUTDOWN(WarningTier.CRITICAL),             // #4
    THERMAL_EMERGENCY(WarningTier.CRITICAL),            // #5
    THERMAL_CRITICAL(WarningTier.CRITICAL),             // #6
    BATTERY_CRITICAL(WarningTier.CRITICAL),             // #7
    CAMERA_IN_USE(WarningTier.CRITICAL),                // #8
    CAMERA_DISABLED(WarningTier.CRITICAL),              // #9
    // Advisory — degraded but functional
    BATTERY_LOW(WarningTier.ADVISORY),                  // #10
    THERMAL_SEVERE(WarningTier.ADVISORY),               // #11
    MICROPHONE_DENIED(WarningTier.ADVISORY),            // #12 — DEFERRED to 4.1b
    BATTERY_OPTIMIZATION_ON(WarningTier.ADVISORY),      // #13
    POWER_SAVE_MODE(WarningTier.ADVISORY),              // #14
    THERMAL_MODERATE(WarningTier.ADVISORY),             // #15
    NOTIFICATIONS_DENIED(WarningTier.ADVISORY)          // #16
}

/** Visual tier for the banner chrome. NOT the priority axis — that is [WarningId.ordinal]. */
enum class WarningTier { HARD_BLOCK, CRITICAL, ADVISORY }

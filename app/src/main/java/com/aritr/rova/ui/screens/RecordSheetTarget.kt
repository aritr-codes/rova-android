package com.aritr.rova.ui.screens

/**
 * Slice 2 — which Record idle cell currently has its focused
 * edit sheet mounted. `null` means no sheet open. The Record VM
 * owns this so sheet visibility survives configuration changes.
 *
 * The sheet contents live in `RecordEditSheets.kt` and are routed
 * by a single `when` over this enum inside `RecordScreen.kt`.
 */
enum class SheetTarget {
    ClipLength,
    Repeats,
    Wait,
    Quality
}

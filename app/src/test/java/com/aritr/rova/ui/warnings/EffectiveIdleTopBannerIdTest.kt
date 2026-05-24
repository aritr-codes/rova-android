package com.aritr.rova.ui.warnings

import com.aritr.rova.data.StopReason
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 4 Slice 3 follow-up — pure-JVM tests for [effectiveIdleTopBannerId].
 *
 * Pins the "echo wins on Idle" promotion: when an auto-stop echo is pending
 * AND precedence resolved to a non-echo TopBanner id (active-state thermal /
 * battery / camera — suppressed on Idle), the helper substitutes the echo id
 * so the user actually sees the echo banner instead of nothing.
 *
 * Without this helper, the smoke-time bug surfaces: thermal=CRITICAL via
 * `adb shell cmd thermalservice override-status 4` keeps precedence pinned
 * at THERMAL_CRITICAL (row #6), which is suppressed on Idle, and the
 * THERMAL_AUTOSTOPPED echo (row #13) never gets a chance to render.
 */
class EffectiveIdleTopBannerIdTest {

    @Test fun `THERMAL echo wins over active THERMAL_CRITICAL precedence pick`() {
        val result = effectiveIdleTopBannerId(
            precedenceId = WarningId.THERMAL_CRITICAL,
            autoStopEcho = TerminalEcho("session-thermal", StopReason.THERMAL),
        )
        assertEquals(WarningId.THERMAL_AUTOSTOPPED, result)
    }

    @Test fun `LOW_STORAGE echo wins over active STORAGE_LOW_MID_REC precedence pick`() {
        val result = effectiveIdleTopBannerId(
            precedenceId = WarningId.STORAGE_LOW_MID_REC,
            autoStopEcho = TerminalEcho("session-storage", StopReason.LOW_STORAGE),
        )
        assertEquals(WarningId.STORAGE_FULL_AUTOSTOPPED, result)
    }

    @Test fun `null echo passes precedence id through unchanged`() {
        val result = effectiveIdleTopBannerId(
            precedenceId = WarningId.THERMAL_CRITICAL,
            autoStopEcho = null,
        )
        assertEquals(WarningId.THERMAL_CRITICAL, result)
    }

    @Test fun `non-echo StopReason passes precedence id through unchanged`() {
        // USER / PERMISSION_REVOKED / INIT_FAILED / NONE never yield an echo
        // banner (matches WarningPrecedence when-arm). Defensive coverage:
        // even if such a TerminalEcho leaks through SessionAutoStopEchoSignal,
        // this helper falls through to the precedence pick.
        listOf(
            StopReason.USER,
            StopReason.PERMISSION_REVOKED,
            StopReason.INIT_FAILED,
            StopReason.NONE,
        ).forEach { reason ->
            val result = effectiveIdleTopBannerId(
                precedenceId = WarningId.BATTERY_LOW,
                autoStopEcho = TerminalEcho("session-x", reason),
            )
            assertEquals("expected pass-through for $reason", WarningId.BATTERY_LOW, result)
        }
    }

    @Test fun `THERMAL echo also wins when precedence already picked the echo id`() {
        // Idempotency: if device cooled enough that precedence already
        // resolved to THERMAL_AUTOSTOPPED, the helper returns the same id.
        val result = effectiveIdleTopBannerId(
            precedenceId = WarningId.THERMAL_AUTOSTOPPED,
            autoStopEcho = TerminalEcho("session-t", StopReason.THERMAL),
        )
        assertEquals(WarningId.THERMAL_AUTOSTOPPED, result)
    }
}

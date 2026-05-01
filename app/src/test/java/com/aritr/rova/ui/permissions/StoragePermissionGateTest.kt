package com.aritr.rova.ui.permissions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Phase 2 Slice 2.4 — pure tests for [StoragePermissionGate].
 *
 * Targets [StoragePermissionGate.evaluate] only;
 * [StoragePermissionGate.evaluateForCurrent] reads `Build` (Android
 * boundary, JVM stubs return 0 → would always pick the API-24 branch
 * and produce misleadingly-green assertions) and
 * [StoragePermissionGate.appDetailsSettingsIntent] hits Intent setter
 * stubs (same problem). Both are exercised by `assembleDebug`'s
 * compile pass; runtime behavior belongs to the wire-up slice.
 */
class StoragePermissionGateTest {

    private fun eval(sdkInt: Int, hasWES: Boolean): StorageBannerState =
        StoragePermissionGate.evaluate(sdkInt = sdkInt, hasWriteExternalStorage = hasWES)

    // ─── tier matrix — Shown band (24..28, hasWES = false) ────────

    @Test
    fun `API 24 without WES is Shown`() {
        val r = eval(24, false) as StorageBannerState.Shown
        assertEquals(StoragePermissionGate.BANNER_TITLE, r.title)
        assertEquals(StoragePermissionGate.BANNER_BODY, r.body)
        assertEquals(StoragePermissionGate.BANNER_ACTION_LABEL, r.actionLabel)
    }

    @Test
    fun `API 25 without WES is Shown`() {
        assertShownExact(eval(25, false))
    }

    @Test
    fun `API 26 without WES is Shown`() {
        assertShownExact(eval(26, false))
    }

    @Test
    fun `API 27 without WES is Shown`() {
        assertShownExact(eval(27, false))
    }

    @Test
    fun `API 28 without WES is Shown`() {
        assertShownExact(eval(28, false))
    }

    // ─── tier matrix — hasWES = true on legacy band ───────────────

    @Test
    fun `API 24 with WES is Hidden`() {
        assertSame(StorageBannerState.Hidden, eval(24, true))
    }

    @Test
    fun `API 26 with WES is Hidden`() {
        assertSame(StorageBannerState.Hidden, eval(26, true))
    }

    @Test
    fun `API 28 with WES is Hidden`() {
        assertSame(StorageBannerState.Hidden, eval(28, true))
    }

    // ─── tier matrix — API >= 29 always Hidden ────────────────────

    @Test
    fun `API 29 without WES is Hidden`() {
        assertSame(StorageBannerState.Hidden, eval(29, false))
    }

    @Test
    fun `API 29 with WES is Hidden`() {
        assertSame(StorageBannerState.Hidden, eval(29, true))
    }

    @Test
    fun `API 30 without WES is Hidden`() {
        assertSame(StorageBannerState.Hidden, eval(30, false))
    }

    @Test
    fun `API 33 without WES is Hidden`() {
        assertSame(StorageBannerState.Hidden, eval(33, false))
    }

    @Test
    fun `API 34 without WES is Hidden`() {
        assertSame(StorageBannerState.Hidden, eval(34, false))
    }

    @Test
    fun `API 36 without WES is Hidden`() {
        assertSame(StorageBannerState.Hidden, eval(36, false))
    }

    // ─── exact-string regression ──────────────────────────────────

    @Test
    fun `regression - banner copy strings match the ROADMAP wording exactly`() {
        // Two-line ROADMAP wording: "Gallery export disabled — Grant
        // Storage permission to enable." Split into title + body to
        // match the BatteryOptimizationBanner two-line precedent.
        assertEquals("Gallery export disabled", StoragePermissionGate.BANNER_TITLE)
        assertEquals("Grant Storage permission to enable.", StoragePermissionGate.BANNER_BODY)
        assertEquals("Grant", StoragePermissionGate.BANNER_ACTION_LABEL)
    }

    @Test
    fun `regression - Shown carries those exact constants - no string drift`() {
        val r = eval(28, false) as StorageBannerState.Shown
        assertSame(StoragePermissionGate.BANNER_TITLE, r.title)
        assertSame(StoragePermissionGate.BANNER_BODY, r.body)
        assertSame(StoragePermissionGate.BANNER_ACTION_LABEL, r.actionLabel)
    }

    // ─── purity / determinism ─────────────────────────────────────

    @Test
    fun `Hidden is the same singleton from API gate and from permission gate`() {
        // Both code paths must return the SAME object so consumers
        // can `assertSame(StorageBannerState.Hidden, ...)` without
        // worrying which branch produced it.
        val fromApiGate = eval(29, false)
        val fromPermissionGate = eval(28, true)
        assertSame(fromApiGate, fromPermissionGate)
        assertSame(StorageBannerState.Hidden, fromApiGate)
    }

    @Test
    fun `evaluate is pure - identical inputs yield equal outputs`() {
        val a = eval(28, false)
        val b = eval(28, false)
        assertEquals(a, b)
    }

    @Test
    fun `evaluate produces no side effects across many invocations`() {
        // Defensive: a hidden mutable cache (e.g. a memoization map)
        // would be a regression. Drive 1000 calls and assert each
        // returns a value equal to a fresh first call.
        val first = eval(28, false)
        repeat(1000) {
            assertEquals(first, eval(28, false))
        }
    }

    // ─── boundary precedence ──────────────────────────────────────

    @Test
    fun `regression - API gate wins over permission gate`() {
        // On API 29, the result is Hidden whether or not the caller
        // somehow has WES — encoded so a future refactor that flips
        // the when-branch order doesn't silently change semantics.
        assertSame(StorageBannerState.Hidden, eval(29, false))
        assertSame(StorageBannerState.Hidden, eval(29, true))
    }

    @Test
    fun `regression - the cutover point is exactly Q (29)`() {
        // 28 with no permission is Shown; 29 with no permission is
        // Hidden. Any drift in the threshold (e.g. >= P, > Q) would
        // break this pair.
        assertEquals(StoragePermissionGate.BANNER_TITLE, (eval(28, false) as StorageBannerState.Shown).title)
        assertSame(StorageBannerState.Hidden, eval(29, false))
    }

    // ─── helper ───────────────────────────────────────────────────

    private fun assertShownExact(state: StorageBannerState) {
        val s = state as StorageBannerState.Shown
        assertEquals(StoragePermissionGate.BANNER_TITLE, s.title)
        assertEquals(StoragePermissionGate.BANNER_BODY, s.body)
        assertEquals(StoragePermissionGate.BANNER_ACTION_LABEL, s.actionLabel)
    }
}

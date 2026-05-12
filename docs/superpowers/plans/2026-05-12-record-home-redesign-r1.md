# Record-home redesign — R1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Converge the Record screen onto the definitive `mockups/new_uiux/` design — camera-first idle layout, a swipe-up settings sheet, a new bottom-nav-with-center-FAB chrome, the navigation restructure (Record owns its nav; History/Settings become drill-down screens), and the `07-warnings.html` warning-sheet treatment replacing the inline `WarningCenter()` banner — leaving the active-state restyle (Recording/Waiting/Merging) for R2.

**Architecture:** New small presentational composables (`RecordTopOverlay`, `RecordCameraControls`, `RecordSettingsCard`, `RecordBottomNav`, `RecordRecoveryChip`, `WarningSheet`/`WarningChip`, `SessionSettingsSheet`) + new pure helpers (`recordFabState`, `warningSurfaceFor`, `warningSheetContent`, settings-card cell formatters), then `RecordScreen` re-assembled around them in two steps (chrome first, then idle body) keeping every piece of preserved behaviour verbatim, then `MainScreen` strips its persistent `NavigationBar`, then the old idle-dock files are deleted. The `WarningCenterViewModel` precedence logic and the hard-block Start-gate are unchanged — only the warning *presentation* changes (banner → sheet/chip).

**Tech Stack:** Kotlin 2.2.10, Jetpack Compose (Material 3, `composeBom = 2025.01.01`), CameraX 1.4.2, AGP 9.2.1 / Gradle 9.4.1, `compileSdk`/`targetSdk` 37, JUnit 4 unit tests (no Robolectric / no Compose-UI tests in this slice — UI is owner-verified on device, per the spec).

**Spec:** `docs/superpowers/specs/2026-05-12-record-home-redesign-r1-design.md`. **ADR:** `docs/adr/0007-record-warning-sheets.md` (Accepted). **Reference UX:** `mockups/new_uiux/01-record-home.html`, `02-settings-sheet.html`, `07-warnings.html`, `03-history-library.html`, `06-app-settings.html`; **design tokens contract:** `docs/UI_DESIGN_TOKENS.md` (Phase 2.1 PRs follow it). **Branch:** `feat/record-home-redesign-r1` (already cut from `master` @ `e505c35`; the spec + ADR are committed there — `60ab152`, `c25840a`).

**Baseline (master @ `e505c35`):** `:app:assembleDebug` OK · `:app:testDebugUnitTest` 729 tests / 57 classes / 0-0-0 · `:app:lintDebug` 53 (50 W + 3 H, 0 E) · no `lint-baseline.xml` (don't add one).

---

## Conventions for every task

- **Build command (the `PreToolUse:Bash` hook redirects bare `./gradlew`):** wrap in a subshell with no space after the paren —
  `(./gradlew :app:assembleDebug 2>&1; echo "EXIT=$?") > /tmp/g.log 2>&1; tail -50 /tmp/g.log`
- **Test count (no `python3`/`bc` on the git-bash PATH):** count `<testcase`/`<testsuite`/`<failure`/`<error`/`<skipped` in `app/build/test-results/testDebugUnitTest/*.xml` with the `Grep` tool or `/usr/bin/grep -c` — never awk pipelines.
- **Lint count:** `app/build/reports/lint-results-debug.xml` — `<issue` count + tally `id="…"` / `severity="…"` (each on its own indented line) with the `Grep` tool or `/usr/bin/grep`.
- **`grep` is rewritten to `rtk grep`** (different CLI) — use the `Grep` tool or `/usr/bin/grep`.
- **`gh` CLI:** `/c/Program Files/GitHub CLI/gh.exe` (not on PATH). PowerShell — no bash heredocs in `gh` calls; pass `--body-file` for long bodies.
- **Never stage** `.claude/`, `.github/`, `.mcp.json`, `.superpowers/` (the last is gitignored as of `60ab152`).
- **Commit after every task** (the `- [ ] Commit` step). Conventional-commit subjects, scoped `feat(record):` / `refactor(record):` / `docs(...)`. End each commit message body with `Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>`.
- **Dimensions/colors/elevation:** the mockup CSS (`01-record-home.html` `<style>`) is the visual source; `docs/UI_DESIGN_TOKENS.md` decides which become `MaterialTheme.*` tokens vs. screen-local constants. Where the tokens doc names a token, consume it; where it's screen-local, define a `private val` next to the composable. Glass panels = `Color.Black.copy(alpha = 0.40f)` fill (or the tokens-doc equivalent) with a `1.dp` `Color.White.copy(alpha = 0.07f)` border, rounded corners per the CSS `border-radius`; blur is not available in Compose without `Modifier.blur` on a layer — use the translucent fill alone (the mockup's `backdrop-filter` blur is approximated by the alpha; do NOT add `Modifier.blur` to the camera layer).
- **Package:** new files go in `com.aritr.rova.ui.screens` (Record-screen composables), `com.aritr.rova.ui.warnings` (warning sheet), or `com.aritr.rova.ui.components` only if genuinely cross-screen. Match `docs/naming.md`.

---

## File structure (created / modified / deleted)

**Created:**
- `app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt` — the camera-overlay chrome composables: `RecordTopOverlay`, `RecordCameraControls`, `RecordSettingsCard`, `RecordBottomNav`, `RecordRecoveryChip`, plus `RecordFabState` + `recordFabState(...)`. (One file; ~one screen's worth of overlay UI. If it grows past ~400 lines during implementation, split `RecordBottomNav` + `RecordFabState` into `RecordBottomNav.kt` — implementer's call, note it in the commit.)
- `app/src/main/java/com/aritr/rova/ui/screens/SessionSettingsSheet.kt` — the combined per-session settings `ModalBottomSheet` + the cell-value formatters (`recordSettingsCardClipValue`, `…RepeatsValue`, `…WaitValue`, `…ModeValue`) used by both this sheet and `RecordSettingsCard`.
- `app/src/test/java/com/aritr/rova/ui/screens/RecordFabStateTest.kt`
- `app/src/test/java/com/aritr/rova/ui/screens/SessionSettingsCardFormattersTest.kt`
- `app/src/test/java/com/aritr/rova/ui/warnings/WarningSurfaceTest.kt`
- `app/src/test/java/com/aritr/rova/ui/warnings/WarningSheetContentTest.kt`
- `app/src/test/java/com/aritr/rova/ui/screens/RecordViewModelSettingsSheetTest.kt`

**Modified:**
- `app/src/main/java/com/aritr/rova/ui/warnings/WarningCenter.kt` — add `WarningSurface` + `warningSurfaceFor(...)`; add `WarningSheetContent` + `warningSheetContent(...)` (alongside `bannerContent` at first, then it replaces it); add `WarningSheet` + `WarningChip`; rewrite `WarningCenter()` to render the sheet/chip; delete `WarningBanner`, `BannerContent`, `bannerContent`.
- `app/src/main/java/com/aritr/rova/ui/screens/RecordViewModel.kt` — add `combinedSettingsOpen: StateFlow<Boolean>` + `openSettingsSheet()` / `closeSettingsSheet()`.
- `app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt` — large: remove app-bar `Row`, the big-red-Stop `Button`, the `ModalNavigationDrawer` wrapper, the tutorial overlay + Info button, the "Save preset" `AlertDialog`; add the new chrome (top overlay + cam-controls + bottom nav) across all states with Stop routed through the FAB; replace the navy idle dock with the new idle body (settings card + recovery chip + camera-off placeholder); wire `WarningCenter()` (sheet/chip), `SessionSettingsSheet`, `recordFabState`.
- `app/src/main/java/com/aritr/rova/ui/MainScreen.kt` — remove the `Scaffold` `bottomBar` (`NavigationBar` + items + the `currentRoute`/`sessionLocked`/`TOP_LEVEL_ROUTES` plumbing); un-hoist `recordViewModel` (RecordScreen declares its own); pass `onBack` to the `history` and `settings` composables.
- `app/src/main/java/com/aritr/rova/ui/screens/HistoryScreen.kt` — add `onBack: () -> Unit = {}` param; add a back-arrow `navigationIcon` to the `TopAppBar` (the non-selection state).
- `app/src/main/java/com/aritr/rova/ui/screens/SettingsScreen.kt` — add `onBack: () -> Unit = {}` param; add a back-arrow `navigationIcon` to the `TopAppBar`.
- `NEW_UI_BACKEND_REPLAN.md` — §"Phase 4": "Superseded by ADR 0007" pointer; §6 matrix Record-home re-skin row → points at this R1 spec.
- `docs/WarningCenterContract.md` — §C3.1 (inline banner): "superseded by ADR 0007 / the `WarningSheet` model" pointer.
- `docs/UI_NAV_GRAPH.md` — describe the new nav model (no persistent `NavigationBar`; Record owns its bottom nav; `history`/`settings` are drill-down with a back arrow).

**Deleted:**
- `app/src/main/java/com/aritr/rova/ui/screens/RecordIdleDock.kt`
- `app/src/main/java/com/aritr/rova/ui/components/PlanSummary.kt`
- `app/src/main/java/com/aritr/rova/ui/components/TappablePlanCell.kt`
- `app/src/test/java/com/aritr/rova/ui/components/PlanSummaryTest.kt` (exists) — and any `TappablePlanCellTest.kt` / `RecordIdleDock*Test.kt` (search before deleting).

**Dormant (untouched, kept on disk, no longer surfaced):** `RovaPreset` & `RovaSettings.customPresetsJson` (in `data/RovaSettings.kt`); `RecordViewModel.{savePreset, deletePreset, loadPresetsFromSettings, persistPresets, customPresets}` (no callers after R1 — that's fine; do not delete in this slice).

---

## Phase A — Pure helpers (TDD)

### Task 1: `RecordFabState` + `recordFabState()`

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt` (start the file with just this helper; the composables get added in Phase B tasks)
- Test: `app/src/test/java/com/aritr/rova/ui/screens/RecordFabStateTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.aritr.rova.ui.screens

import com.aritr.rova.ui.components.RecordHudState
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordFabStateTest {

    @Test fun idle_notBlocked_isStart() {
        assertEquals(RecordFabState.Start, recordFabState(RecordHudState.Idle, sessionLocked = false, hardBlockActive = false))
    }

    @Test fun idle_hardBlocked_isDisabled() {
        assertEquals(RecordFabState.Disabled, recordFabState(RecordHudState.Idle, sessionLocked = false, hardBlockActive = true))
    }

    @Test fun recording_isStop_regardlessOfBlocks() {
        assertEquals(RecordFabState.Stop, recordFabState(RecordHudState.Recording, sessionLocked = true, hardBlockActive = false))
        assertEquals(RecordFabState.Stop, recordFabState(RecordHudState.Recording, sessionLocked = true, hardBlockActive = true))
    }

    @Test fun waiting_isStop() {
        assertEquals(RecordFabState.Stop, recordFabState(RecordHudState.Waiting, sessionLocked = true, hardBlockActive = false))
    }

    @Test fun merging_isDisabled() {
        assertEquals(
            RecordFabState.Disabled,
            recordFabState(RecordHudState.Merging(progress = 0.5f, currentSegment = 1, totalSegments = 2), sessionLocked = true, hardBlockActive = false)
        )
    }
}
```

> If `RecordHudState.Merging`'s constructor signature differs, match the actual one (`app/src/main/java/com/aritr/rova/ui/components/RecordHudState.kt`). `RecordHudState` is a sealed class with `Idle` / `Recording` / `Waiting` objects and a `Merging` data class.

- [ ] **Step 2: Run test to verify it fails**

`(./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.RecordFabStateTest" 2>&1; echo "EXIT=$?") > /tmp/g.log 2>&1; tail -40 /tmp/g.log`
Expected: FAIL — `RecordFabState` / `recordFabState` unresolved.

- [ ] **Step 3: Write the implementation** in `RecordChrome.kt`

```kotlin
package com.aritr.rova.ui.screens

import com.aritr.rova.ui.components.RecordHudState

/** What the center button in the Record bottom nav shows / does. */
enum class RecordFabState { Start, Stop, Disabled }

/**
 * Pure derivation of the center-FAB state from the HUD state + gating booleans.
 *
 * - Idle + a hard-block active (camera permission denied OR storage insufficient
 *   to start — see [com.aritr.rova.ui.warnings.WarningCenterViewModel] / RecordScreen's
 *   leaf-signal reads) → [Disabled]; the actionable CTA lives in the auto-presented
 *   warning sheet, not the FAB.
 * - Idle, not blocked → [Start].
 * - Recording or Waiting → [Stop] (always — `sessionLocked`/`hardBlockActive` are
 *   irrelevant once a session is running).
 * - Merging → [Disabled] — the merge runs `NonCancellable` (ADR 0006), so a Stop
 *   affordance would be a lie.
 *
 * `sessionLocked` (= isPeriodicActive || isMerging) is passed for symmetry / future
 * use; the FAB is `Stop` (not `Disabled`) during an active session.
 */
fun recordFabState(
    hudState: RecordHudState,
    sessionLocked: Boolean,
    hardBlockActive: Boolean,
): RecordFabState = when (hudState) {
    RecordHudState.Idle -> if (hardBlockActive) RecordFabState.Disabled else RecordFabState.Start
    RecordHudState.Recording, RecordHudState.Waiting -> RecordFabState.Stop
    is RecordHudState.Merging -> RecordFabState.Disabled
}
```

- [ ] **Step 4: Run test to verify it passes**

`(./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.RecordFabStateTest" 2>&1; echo "EXIT=$?") > /tmp/g.log 2>&1; tail -40 /tmp/g.log`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```
git add app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt app/src/test/java/com/aritr/rova/ui/screens/RecordFabStateTest.kt
git commit -m "feat(record): add recordFabState() helper for the bottom-nav center button"
```

---

### Task 2: `WarningSurface` + `warningSurfaceFor()`

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/warnings/WarningCenter.kt` (add the type + function near the top, after the imports / before `WarningCenter`)
- Test: `app/src/test/java/com/aritr/rova/ui/warnings/WarningSurfaceTest.kt`

> `WarningId` is the enum in `app/src/main/java/com/aritr/rova/ui/warnings/` (one of `WarningId.kt` / inside `WarningCenter.kt`'s package) with 16 entries and a `tier: WarningTier` + `gatesStart: Boolean` per entry — see the existing `bannerContent` `when(id)` arm list in `WarningCenter.kt` for the exact 16 names: `CAMERA_PERMISSION_DENIED`, `EXACT_ALARM_DENIED`, `STORAGE_INSUFFICIENT`, `THERMAL_SHUTDOWN`, `THERMAL_EMERGENCY`, `THERMAL_CRITICAL`, `BATTERY_CRITICAL`, `CAMERA_IN_USE`, `CAMERA_DISABLED`, `BATTERY_LOW`, `THERMAL_SEVERE`, `MICROPHONE_DENIED`, `BATTERY_OPTIMIZATION_ON`, `POWER_SAVE_MODE`, `THERMAL_MODERATE`, `NOTIFICATIONS_DENIED`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.aritr.rova.ui.warnings

import org.junit.Assert.assertEquals
import org.junit.Test

class WarningSurfaceTest {

    @Test fun everyWarningHasASurface() {
        // Exhaustive — one assertion per WarningId; fails to compile if a new id is added without a mapping.
        for (id in WarningId.entries) {
            val s = warningSurfaceFor(id)
            assertEquals("surface for $id", expectedSurface(id), s)
        }
    }

    private fun expectedSurface(id: WarningId): WarningSurface = when (id) {
        WarningId.CAMERA_PERMISSION_DENIED, WarningId.EXACT_ALARM_DENIED, WarningId.STORAGE_INSUFFICIENT -> WarningSurface.HardBlockSheet
        WarningId.MICROPHONE_DENIED -> WarningSurface.SoftSheet
        WarningId.NOTIFICATIONS_DENIED, WarningId.BATTERY_OPTIMIZATION_ON, WarningId.POWER_SAVE_MODE -> WarningSurface.AdvisorySheet
        WarningId.THERMAL_SHUTDOWN, WarningId.THERMAL_EMERGENCY, WarningId.THERMAL_CRITICAL, WarningId.THERMAL_SEVERE, WarningId.THERMAL_MODERATE,
        WarningId.BATTERY_CRITICAL, WarningId.BATTERY_LOW, WarningId.CAMERA_IN_USE, WarningId.CAMERA_DISABLED -> WarningSurface.TopBanner
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

`(./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.warnings.WarningSurfaceTest" 2>&1; echo "EXIT=$?") > /tmp/g.log 2>&1; tail -40 /tmp/g.log`
Expected: FAIL — `WarningSurface` / `warningSurfaceFor` unresolved.

- [ ] **Step 3: Add to `WarningCenter.kt`**

```kotlin
/**
 * Where a given [WarningId] is surfaced on the Record screen (ADR 0007). The
 * [WarningCenterViewModel] still resolves the single highest-priority active
 * warning; this only decides how that warning is drawn.
 *
 * - [HardBlockSheet]   — recording can't start / must abort (camera perm, exact
 *                        alarm, storage). Modal, auto-presents, collapses to a
 *                        [WarningChip] on "Not now"; FAB goes Disabled, nav dims.
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
    WarningId.BATTERY_CRITICAL, WarningId.BATTERY_LOW, WarningId.CAMERA_IN_USE, WarningId.CAMERA_DISABLED -> WarningSurface.TopBanner
}
```

> If `WarningId` has no `.entries` (older Kotlin enum API), use `WarningId.values()` in the test loop. Kotlin 2.2.10 supports `.entries`.

- [ ] **Step 4: Run test to verify it passes**

`(./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.warnings.WarningSurfaceTest" 2>&1; echo "EXIT=$?") > /tmp/g.log 2>&1; tail -40 /tmp/g.log`
Expected: PASS.

- [ ] **Step 5: Commit**

```
git add app/src/main/java/com/aritr/rova/ui/warnings/WarningCenter.kt app/src/test/java/com/aritr/rova/ui/warnings/WarningSurfaceTest.kt
git commit -m "feat(warnings): add WarningSurface + warningSurfaceFor() (ADR 0007)"
```

---

### Task 3: `WarningSheetContent` + `warningSheetContent()`

Adds the sheet content map **alongside** the existing `bannerContent` (which Task 9 deletes once `WarningSheet` replaces `WarningBanner`). Re-uses the existing `ActionTarget` enum + `WarningAction` data class already in `WarningCenter.kt`.

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/warnings/WarningCenter.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/warnings/WarningSheetContentTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.aritr.rova.ui.warnings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WarningSheetContentTest {

    @Test fun everyWarningHasSheetContent() {
        for (id in WarningId.entries) {
            val c = warningSheetContent(id)
            assertTrue("title non-blank for $id", c.title.isNotBlank())
            // primary action is always present; secondary present for sheet-rendered tiers.
            when (warningSurfaceFor(id)) {
                WarningSurface.HardBlockSheet, WarningSurface.SoftSheet, WarningSurface.AdvisorySheet ->
                    assertTrue("secondary action present for sheet-rendered $id", c.secondary != null)
                WarningSurface.TopBanner -> { /* secondary may be null */ }
            }
        }
    }

    @Test fun cameraDeniedRoutesToAppSettings() {
        val c = warningSheetContent(WarningId.CAMERA_PERMISSION_DENIED)
        assertEquals("Camera access required", c.title)
        assertEquals(ActionTarget.APP_DETAILS_SETTINGS, c.primary.target)
    }

    @Test fun exactAlarmRoutesToExactAlarmSettings() {
        assertEquals(ActionTarget.EXACT_ALARM_SETTINGS, warningSheetContent(WarningId.EXACT_ALARM_DENIED).primary.target)
    }

    @Test fun micDeniedSecondaryIsContinueWithoutAudio() {
        val c = warningSheetContent(WarningId.MICROPHONE_DENIED)
        assertTrue(c.secondary!!.label.contains("audio", ignoreCase = true) || c.secondary!!.label.contains("without", ignoreCase = true))
    }
}
```

> If `ActionTarget` / `WarningAction` are `private` in `WarningCenter.kt`, change them to `internal` so the test in the same package... actually the test IS in `com.aritr.rova.ui.warnings`, same package — `private` top-level is file-scoped, so the test can't see them. Make `ActionTarget` and `WarningAction` `internal` (not `private`). This is the same "spec-stub gotcha" noted in earlier phases (a helper must be at least `internal` for a same-package test to call it).

- [ ] **Step 2: Run test to verify it fails**

`(./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.warnings.WarningSheetContentTest" 2>&1; echo "EXIT=$?") > /tmp/g.log 2>&1; tail -40 /tmp/g.log`
Expected: FAIL — `warningSheetContent` / `WarningSheetContent` unresolved (and possibly `ActionTarget` visibility).

- [ ] **Step 3: Add to `WarningCenter.kt`** (and bump `ActionTarget`/`WarningAction` to `internal`)

```kotlin
internal data class WarningSheetContent(
    val icon: ImageVector,
    val title: String,
    /** Short supporting line; never blank for sheet-rendered warnings. Final copy is the dev's call. */
    val body: String,
    /** Primary CTA — always present (e.g. "Open App Settings"). */
    val primary: WarningAction,
    /** Secondary CTA — present for HardBlock/Soft/Advisory sheets ("Not now" / "Continue without audio"); may be null for TopBanner. */
    val secondary: WarningAction?,
)

/**
 * The 16-arm sheet-content map (ADR 0007). Copy mirrors `mockups/new_uiux/07-warnings.html`.
 * Icons reuse the ones the old [bannerContent] carried. Replaces [bannerContent] when
 * [WarningSheet] lands (Task 9).
 */
internal fun warningSheetContent(id: WarningId): WarningSheetContent = when (id) {
    WarningId.CAMERA_PERMISSION_DENIED -> WarningSheetContent(
        Icons.Default.NoPhotography, "Camera access required",
        "Rova can't record without camera access. Grant the permission in App Settings to continue.",
        WarningAction("Open App Settings", ActionTarget.APP_DETAILS_SETTINGS),
        WarningAction("Not now", ActionTarget.APP_DETAILS_SETTINGS), // secondary just dismisses; target unused for dismissal — see WarningSheet
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
    )
    WarningId.BATTERY_OPTIMIZATION_ON -> WarningSheetContent(
        Icons.Default.BatterySaver, "Battery optimization may stop recording",
        "Android may kill Rova in the background. Disable battery optimization for reliable long sessions.",
        WarningAction("Disable", ActionTarget.BATTERY_OPTIMIZATION),
        WarningAction("Not now", ActionTarget.BATTERY_OPTIMIZATION),
    )
    WarningId.POWER_SAVE_MODE -> WarningSheetContent(
        Icons.Default.PowerSettingsNew, "Power-save mode may throttle recording",
        "Turning off battery saver gives Rova full CPU/IO for the session.",
        WarningAction("Settings", ActionTarget.APP_DETAILS_SETTINGS),
        WarningAction("Not now", ActionTarget.APP_DETAILS_SETTINGS),
    )
    // ── TopBanner-tier warnings: title-led, often body-less; secondary may be null.
    WarningId.THERMAL_SHUTDOWN -> WarningSheetContent(Icons.Default.Thermostat, "Device overheating — recording stopped", "", WarningAction("OK", ActionTarget.APP_DETAILS_SETTINGS), null)
    WarningId.THERMAL_EMERGENCY -> WarningSheetContent(Icons.Default.Thermostat, "Device critically hot", "", WarningAction("OK", ActionTarget.APP_DETAILS_SETTINGS), null)
    WarningId.THERMAL_CRITICAL -> WarningSheetContent(Icons.Default.Thermostat, "Device very hot — recording may stop", "", WarningAction("OK", ActionTarget.APP_DETAILS_SETTINGS), null)
    WarningId.THERMAL_SEVERE -> WarningSheetContent(Icons.Default.Thermostat, "Device hot — quality may drop", "", WarningAction("OK", ActionTarget.APP_DETAILS_SETTINGS), null)
    WarningId.THERMAL_MODERATE -> WarningSheetContent(Icons.Default.Thermostat, "Device warming up", "", WarningAction("OK", ActionTarget.APP_DETAILS_SETTINGS), null)
    WarningId.BATTERY_CRITICAL -> WarningSheetContent(Icons.Default.BatteryAlert, "Battery critical — recording may stop", "", WarningAction("OK", ActionTarget.APP_DETAILS_SETTINGS), null)
    WarningId.BATTERY_LOW -> WarningSheetContent(Icons.Default.BatteryAlert, "Battery low — consider charging", "", WarningAction("OK", ActionTarget.APP_DETAILS_SETTINGS), null)
    WarningId.CAMERA_IN_USE -> WarningSheetContent(Icons.Default.VideocamOff, "Camera in use by another app", "Close the other camera app.", WarningAction("OK", ActionTarget.APP_DETAILS_SETTINGS), null)
    WarningId.CAMERA_DISABLED -> WarningSheetContent(Icons.Default.VideocamOff, "Camera disabled by device policy", "", WarningAction("OK", ActionTarget.APP_DETAILS_SETTINGS), null)
}
```

> Imports needed (most already present in `WarningCenter.kt`): `androidx.compose.material.icons.Icons`, the `Icons.Default.*` icons (`NoPhotography`, `AlarmOff`, `Storage`, `MicOff`, `NotificationsOff`, `BatterySaver`, `PowerSettingsNew`, `Thermostat`, `BatteryAlert`, `VideocamOff`), `androidx.compose.ui.graphics.vector.ImageVector`. Keep `bannerContent` untouched for now (still used by `WarningBanner` until Task 9).

- [ ] **Step 4: Run test to verify it passes**

`(./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.warnings.WarningSheetContentTest" 2>&1; echo "EXIT=$?") > /tmp/g.log 2>&1; tail -40 /tmp/g.log`
Expected: PASS.

- [ ] **Step 5: Commit**

```
git add app/src/main/java/com/aritr/rova/ui/warnings/WarningCenter.kt app/src/test/java/com/aritr/rova/ui/warnings/WarningSheetContentTest.kt
git commit -m "feat(warnings): add warningSheetContent() map (ADR 0007); ActionTarget/WarningAction -> internal"
```

---

### Task 4: Settings-card cell-value formatters

Moves the display-string helpers out of (the soon-to-be-deleted) `RecordIdleDock.kt` into the new `SessionSettingsSheet.kt` so both the card and the combined sheet use one source. (`UiCopy` keeps the *accessibility* descriptions; these are the short *display* values.)

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/screens/SessionSettingsSheet.kt` (start with just these `internal fun`s; the composable is added in Task 11)
- Test: `app/src/test/java/com/aritr/rova/ui/screens/SessionSettingsCardFormattersTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.aritr.rova.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionSettingsCardFormattersTest {

    @Test fun clipValues() {
        assertEquals("0 s", recordClipValue(0))
        assertEquals("10 s", recordClipValue(10))
        assertEquals("1 m", recordClipValue(60))
        assertEquals("2 m", recordClipValue(120))
        assertEquals("90 s", recordClipValue(90))
    }

    @Test fun repeatsValues() {
        assertEquals("10", recordRepeatsValue(10))
        assertEquals("1", recordRepeatsValue(1))
        assertEquals("Until you stop", recordRepeatsValue(-1))
    }

    @Test fun waitValues() {
        assertEquals("None", recordWaitValue(0))
        assertEquals("1 m", recordWaitValue(1))
        assertEquals("45 m", recordWaitValue(45))
        assertEquals("1 h", recordWaitValue(60))
        assertEquals("2 h", recordWaitValue(120))
    }

    @Test fun modeValue() {
        assertEquals("Portrait", recordModeValue())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

`(./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.SessionSettingsCardFormattersTest" 2>&1; echo "EXIT=$?") > /tmp/g.log 2>&1; tail -40 /tmp/g.log`
Expected: FAIL — functions unresolved.

- [ ] **Step 3: Write `SessionSettingsSheet.kt`** (formatters only for now)

```kotlin
package com.aritr.rova.ui.screens

// ── Settings-card / settings-sheet display-value formatters (sentinel-blind).
// Accessibility descriptions live in com.aritr.rova.ui.components.UiCopy; these
// are the short cell/row VALUES. Moved here from the old RecordIdleDock.kt.

internal fun recordClipValue(seconds: Int): String = when {
    seconds <= 0 -> "0 s"
    seconds < 60 -> "$seconds s"
    seconds == 60 -> "1 m"
    seconds % 60 == 0 -> "${seconds / 60} m"
    else -> "$seconds s"
}

internal fun recordRepeatsValue(loopCount: Int): String =
    if (loopCount < 0) "Until you stop" else loopCount.toString()

internal fun recordWaitValue(intervalMinutes: Int): String = when {
    intervalMinutes <= 0 -> "None"
    intervalMinutes == 60 -> "1 h"
    intervalMinutes % 60 == 0 -> "${intervalMinutes / 60} h"
    else -> "$intervalMinutes m"
}

/** v1.0.0 is Portrait-only; the Portrait/Landscape picker ships with Phase 6. */
internal fun recordModeValue(): String = "Portrait"
```

- [ ] **Step 4: Run test to verify it passes**

`(./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.SessionSettingsCardFormattersTest" 2>&1; echo "EXIT=$?") > /tmp/g.log 2>&1; tail -40 /tmp/g.log`
Expected: PASS.

- [ ] **Step 5: Commit**

```
git add app/src/main/java/com/aritr/rova/ui/screens/SessionSettingsSheet.kt app/src/test/java/com/aritr/rova/ui/screens/SessionSettingsCardFormattersTest.kt
git commit -m "feat(record): settings-card cell-value formatters (moved from RecordIdleDock)"
```

---

## Phase B — New presentational composables (no unit tests; verify it compiles)

> For every Phase-B task: there is **no** "write failing test" step (the spec rules out Compose-UI tests in this slice). The verification step is `(./gradlew :app:assembleDebug 2>&1; echo "EXIT=$?") > /tmp/g.log 2>&1; tail -50 /tmp/g.log` → expect `BUILD SUCCESSFUL`. New composables are unused until Phase D, so a passing compile is the bar. Use `@Preview` composables freely if helpful (they don't ship).

### Task 5: `RecordTopOverlay` composable

The glass status pill (+ active-state loop pill). Add to `RecordChrome.kt`.

**Files:** Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt`

- [ ] **Step 1: Write the composable**

```kotlin
// ── add to RecordChrome.kt ──
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.aritr.rova.ui.components.RecordHudState

// Screen-local style constants (see mockups/new_uiux/01-record-home.html .status-pill / .loop-pill,
// and docs/UI_DESIGN_TOKENS.md for any of these the tokens doc promotes to MaterialTheme.*).
private val GlassFill = Color.Black.copy(alpha = 0.40f)
private val GlassStroke = Color.White.copy(alpha = 0.07f)
private val StatusPillShape = RoundedCornerShape(20.dp)
private val PillShape = RoundedCornerShape(11.dp)

/**
 * The top-of-viewfinder overlay: a status pill that reads the current mode, plus,
 * in active states, a loop pill ("N/M loops done"). Placed by the caller (RecordScreen)
 * at the top of the camera Box, inset from the status-bar inset. In R1 the active-state
 * text reuses today's elapsed/countdown values; the full restyle of those is R2.
 */
@Composable
fun RecordTopOverlay(
    hudState: RecordHudState,
    statusText: String,            // e.g. "Ready to record" / "Recording" / "On break"
    statusDetail: String?,         // e.g. "0:18 left" / "next in 0:42" / null when idle
    currentLoop: Int,              // for the loop pill in active states
    totalLoops: Int,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (hudState is RecordHudState.Recording || hudState is RecordHudState.Waiting) {
            Row(
                modifier = Modifier.clip(PillShape).background(GlassFill).border(1.dp, GlassStroke, PillShape).padding(horizontal = 13.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "${currentLoop.coerceAtLeast(0)}/${totalLoops.coerceAtLeast(0)}",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White.copy(alpha = 0.93f),
                )
                Text(
                    text = "loops done",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.32f),
                )
            }
        }
        Row(
            modifier = Modifier.clip(StatusPillShape).background(GlassFill).border(1.dp, GlassStroke, StatusPillShape).padding(horizontal = 11.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            StatusDot(hudState)
            Text(statusText, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.65f))
            if (statusDetail != null) {
                Text("· $statusDetail", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.32f))
            }
        }
    }
}

@Composable
private fun StatusDot(hudState: RecordHudState) {
    // 6.dp dot — grey when idle/break, blinking red when recording. (Blink animation is
    // a nice-to-have; a static red dot is acceptable for R1 — owner smoke decides.)
    val color = when (hudState) {
        RecordHudState.Recording -> Color(0xFFEF4444)
        else -> Color.White.copy(alpha = 0.25f)
    }
    androidx.compose.foundation.layout.Box(
        Modifier.size(6.dp).clip(androidx.compose.foundation.shape.CircleShape).background(color)
    )
}
```

> `import androidx.compose.foundation.layout.size` and `androidx.compose.foundation.layout.Box` as needed. Use `MaterialTheme.typography.*` slots — pick the closest existing slot; don't add new typography tokens (the tokens doc says the existing `NumericMonoLarge`/`Medium` and the M3 slots are it).

- [ ] **Step 2: Verify it compiles** — `(./gradlew :app:assembleDebug 2>&1; echo "EXIT=$?") > /tmp/g.log 2>&1; tail -50 /tmp/g.log` → `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```
git add app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt
git commit -m "feat(record): RecordTopOverlay (status pill + loop pill) composable"
```

---

### Task 6: `RecordCameraControls` composable

Two small glass circle buttons (flash cycle, flip camera), top-right. Add to `RecordChrome.kt`.

**Files:** Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt`

- [ ] **Step 1: Write the composable**

```kotlin
// ── add to RecordChrome.kt ──
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlipCameraIos
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import com.aritr.rova.service.RovaRecordingService

private val ControlBtnSize = 30.dp

/**
 * Floating flash + flip controls, top-right of the viewfinder (mockups/new_uiux/01-record-home.html
 * .cam-controls). Replaces the flash/flip IconButtons in the old app-bar Row. Disabled (greyed)
 * while [enabled] is false (i.e. during an active session — same as today).
 */
@Composable
fun RecordCameraControls(
    flashMode: Int,
    onCycleFlash: () -> Unit,
    onFlip: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val tint = if (enabled) Color.White.copy(alpha = 0.75f) else Color.White.copy(alpha = 0.3f)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        GlassCircleButton(onClick = onCycleFlash, enabled = enabled) {
            val (icon, contentTint) = when (flashMode) {
                RovaRecordingService.FLASH_MODE_ON -> Icons.Default.FlashOn to (if (enabled) Color.Yellow else tint)
                RovaRecordingService.FLASH_MODE_AUTO -> Icons.Default.FlashAuto to tint
                else -> Icons.Default.FlashOff to tint
            }
            Icon(icon, contentDescription = "Flash", tint = contentTint)
        }
        GlassCircleButton(onClick = onFlip, enabled = enabled) {
            Icon(Icons.Default.FlipCameraIos, contentDescription = "Flip camera", tint = tint)
        }
    }
}

@Composable
private fun GlassCircleButton(onClick: () -> Unit, enabled: Boolean, content: @Composable () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(ControlBtnSize).clip(CircleShape).background(GlassFill).border(1.dp, GlassStroke, CircleShape),
    ) { content() }
}
```

> `FLASH_MODE_ON` / `FLASH_MODE_AUTO` constants live on `RovaRecordingService` (used today in `RecordScreen`'s app-bar Row). `Icons.Default.FlashOn/FlashAuto/FlashOff/FlipCameraIos` already imported there.

- [ ] **Step 2: Verify it compiles.**
- [ ] **Step 3: Commit** — `git commit -m "feat(record): RecordCameraControls (flash/flip) composable"`

---

### Task 7: `RecordSettingsCard` composable

The glass strip of five display cells + "swipe to edit" hint + chevron; whole-card tap and a swipe-up both fire `onOpenSheet`. Add to `RecordChrome.kt`. Uses the Task-4 formatters.

**Files:** Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt`

- [ ] **Step 1: Write the composable**

```kotlin
// ── add to RecordChrome.kt ──
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign

private val SettingsCardShape = RoundedCornerShape(14.dp)
private val SettingsCardFill = Color.White.copy(alpha = 0.065f)
private val SettingsCardStroke = Color.White.copy(alpha = 0.09f)
private val CellDivider = Color.White.copy(alpha = 0.07f)

/**
 * The idle-state settings strip (mockups/new_uiux/01-record-home.html .settings-wrap +
 * .settings-card). A display-only row of cells — Clip / Repeats / Wait / Quality / Mode
 * (Mode is read-only "Portrait" for v1.0.0) — over a "swipe to edit" hint, with a chevron.
 * The whole card is one tap target AND has a swipe-up gesture; both call [onOpenSheet],
 * which opens SessionSettingsSheet.
 */
@Composable
fun RecordSettingsCard(
    durationSeconds: Int,
    loopCount: Int,
    intervalMinutes: Int,
    quality: String,
    onOpenSheet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        // swipe-to-edit hint
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.padding(bottom = 1.dp)) {
            Box(Modifier.width(30.dp).height(2.dp).clip(RoundedCornerShape(1.dp)).background(Color.White.copy(alpha = 0.22f)))
            Text("SWIPE TO EDIT", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.22f))
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(SettingsCardShape)
                .background(SettingsCardFill)
                .border(1.dp, SettingsCardStroke, SettingsCardShape)
                .clickable { onOpenSheet() }
                .pointerInput(Unit) {
                    detectVerticalDragGestures { _, dragAmount -> if (dragAmount < -8f) onOpenSheet() }
                }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsCell("Clip", recordClipValue(durationSeconds), Modifier.weight(1f), readOnly = false)
            CellSep()
            SettingsCell("Repeats", recordRepeatsValue(loopCount), Modifier.weight(1f), readOnly = false)
            CellSep()
            SettingsCell("Wait", recordWaitValue(intervalMinutes), Modifier.weight(1f), readOnly = false)
            CellSep()
            SettingsCell("Quality", quality, Modifier.weight(1f), readOnly = false)
            CellSep()
            SettingsCell("Mode", recordModeValue(), Modifier.weight(1f), readOnly = true)
            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Edit session settings", tint = Color.White.copy(alpha = 0.18f), modifier = Modifier.padding(start = 6.dp))
        }
    }
}

@Composable
private fun SettingsCell(key: String, value: String, modifier: Modifier, readOnly: Boolean) {
    Column(modifier = modifier.padding(horizontal = 3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.labelLarge, color = if (readOnly) Color.White.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.88f), textAlign = TextAlign.Center, maxLines = 1)
        Text(key.uppercase(), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.28f), textAlign = TextAlign.Center, maxLines = 1)
    }
}

@Composable
private fun CellSep() {
    Box(Modifier.width(1.dp).height(22.dp).background(CellDivider))
}
```

> A real polished swipe-up (open the sheet on a fling-up, with a peek) is R2/polish; the `detectVerticalDragGestures` with a small threshold is enough for R1 (and the whole-card `clickable` is the primary path). Don't over-build the gesture.

- [ ] **Step 2: Verify it compiles.**
- [ ] **Step 3: Commit** — `git commit -m "feat(record): RecordSettingsCard composable (display strip + open-sheet)"`

---

### Task 8: `RecordBottomNav` composable

The glass bottom bar — Library / center FAB (Start/Stop/Disabled) / Settings. Add to `RecordChrome.kt`. Uses `RecordFabState` (Task 1).

**Files:** Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt`

- [ ] **Step 1: Write the composable**

```kotlin
// ── add to RecordChrome.kt ──
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings as SettingsIcon
import androidx.compose.material.icons.filled.Stop
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

private val BottomNavFill = Color.Black.copy(alpha = 0.50f)
private val BottomNavStroke = Color.White.copy(alpha = 0.055f)
private val FabSize = 56.dp

/**
 * The Record screen's own bottom navigation (mockups/new_uiux/01-record-home.html .bottom-nav):
 * Library (left) · center Start/Stop FAB · Settings (right). There is no app-wide NavigationBar
 * any more — this bar lives only on the Record screen; Library/Settings PUSH those screens.
 *
 * Library/Settings dim + disable while [navItemsLocked] (= isPeriodicActive || isMerging). The FAB
 * is [RecordFabState.Stop] during an active session and [RecordFabState.Disabled] when a hard-block
 * is active (idle) or during merge.
 */
@Composable
fun RecordBottomNav(
    fabState: RecordFabState,
    navItemsLocked: Boolean,
    onLibrary: () -> Unit,
    onSettings: () -> Unit,
    onFabClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(BottomNavFill)
            .border(width = 1.dp, color = BottomNavStroke, shape = RoundedCornerShape(0.dp))
            .windowInsetsPadding(WindowInsets.navigationBars)   // clear the gesture-nav bar
            .padding(horizontal = 28.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceAround,
    ) {
        NavItem(icon = Icons.Default.PhotoLibrary, label = "Library", enabled = !navItemsLocked, onClick = onLibrary)
        RecordFab(state = fabState, onClick = onFabClick)
        NavItem(icon = Icons.Default.SettingsIcon, label = "Settings", enabled = !navItemsLocked, onClick = onSettings)
    }
}

@Composable
private fun NavItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val alpha = if (enabled) 0.4f else 0.4f * 0.35f / 0.4f  // ~0.35 of the enabled alpha when locked; tune to taste
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.then(if (enabled) Modifier.clickable { onClick() } else Modifier),
    ) {
        Icon(icon, contentDescription = label, tint = Color.White.copy(alpha = if (enabled) 0.4f else 0.14f), modifier = Modifier.size(34.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = if (enabled) 0.32f else 0.12f))
    }
}

@Composable
private fun RecordFab(state: RecordFabState, onClick: () -> Unit) {
    val (fill, stroke, semantics) = when (state) {
        RecordFabState.Start -> Triple(Color.White.copy(alpha = 0.07f), Color.White.copy(alpha = 0.15f), "Start recording")
        RecordFabState.Stop -> Triple(Color(0xFFEF4444).copy(alpha = 0.13f), Color(0xFFEF4444).copy(alpha = 0.30f), "Stop recording")
        RecordFabState.Disabled -> Triple(Color.White.copy(alpha = 0.04f), Color.White.copy(alpha = 0.08f), "Start recording (unavailable)")
    }
    val enabled = state != RecordFabState.Disabled
    Box(
        modifier = Modifier
            .size(FabSize)
            .clip(CircleShape)
            .background(fill)
            .border(1.5.dp, stroke, CircleShape)
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .semantics { contentDescription = semantics },
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            RecordFabState.Stop -> Box(Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFFEF4444)))
            RecordFabState.Start -> Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White.copy(alpha = 0.78f), modifier = Modifier.size(26.dp))
            RecordFabState.Disabled -> Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White.copy(alpha = 0.25f), modifier = Modifier.size(26.dp))
        }
    }
}
```

> Clean up the `NavItem` alpha expression to a plain `if (enabled) 0.4f else 0.14f` (the ratio comment above is just to show the mockup's `.35` dim factor — don't ship the messy arithmetic). Pick `Icons.Default.PhotoLibrary` (or `VideoLibrary` / `Collections` — match `03-history-library.html`'s nav icon if it has one; `PhotoLibrary` is fine). Don't reach for an M3 `NavigationBar` here — the mockup's glass bar with a protruding-ish FAB isn't the stock component; a `Row` is the honest implementation.

- [ ] **Step 2: Verify it compiles.**
- [ ] **Step 3: Commit** — `git commit -m "feat(record): RecordBottomNav (Library / Start-Stop FAB / Settings) composable"`

---

### Task 9: `WarningSheet` + `WarningChip` + rewrite `WarningCenter()`

Replace the inline `WarningBanner` with a `ModalBottomSheet`-based `WarningSheet` (per-tier styled) + a small collapsed `WarningChip`; rewrite the `WarningCenter()` composable to render the sheet/chip based on `warningSurfaceFor(activeWarning)`. Delete `WarningBanner`, `BannerContent`, `bannerContent` (now superseded by `warningSheetContent`).

**Files:** Modify: `app/src/main/java/com/aritr/rova/ui/warnings/WarningCenter.kt`

- [ ] **Step 1: Rewrite the rendering part of `WarningCenter.kt`**

Keep unchanged: `WarningCenter()`'s `viewModel(factory = …)` wiring (the 9-flow `WarningCenterViewModel` construction), `val active by vm.activeWarning.collectAsStateWithLifecycle()`, `launchActionTarget`, `ActionTarget`, `WarningAction`, `WarningSurface`/`warningSurfaceFor`, `WarningSheetContent`/`warningSheetContent`, the `WarningId`/`WarningTier` types. Delete `WarningBanner`, `BannerContent`, `bannerContent`.

New rendering (replace the body of `WarningCenter` after `val active by …`):

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WarningCenter(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as? RovaApp } ?: return
    val vm: WarningCenterViewModel = viewModel(/* …existing factory, unchanged… */)
    val active by vm.activeWarning.collectAsStateWithLifecycle()
    val id = active ?: return

    val surface = warningSurfaceFor(id)
    // R1: TopBanner-tier warnings are mid-recording (R2 territory) — render nothing for them here
    // unless the cheap top-banner path is added in this slice (spec A6). Default: no-op.
    if (surface == WarningSurface.TopBanner) return

    // Dismiss/collapse state — per active id, so a different warning re-presents fresh.
    var collapsed by rememberSaveable(id) { mutableStateOf(false) }

    if (collapsed) {
        WarningChip(id = id, onExpand = { collapsed = false }, modifier = modifier)
    } else {
        WarningSheet(
            id = id,
            surface = surface,
            onPrimary = { launchActionTarget(context, warningSheetContent(id).primary.target); collapsed = true },
            onSecondary = { collapsed = true },     // "Not now" / "Continue without audio" → collapse to a chip
            onDismissRequest = {
                // Hard blocks are not dismissible by scrim tap (you can't proceed) — keep the sheet up;
                // soft/advisory may collapse on scrim/back.
                if (surface != WarningSurface.HardBlockSheet) collapsed = true
            },
        )
    }
}
```

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WarningSheet(
    id: WarningId,
    surface: WarningSurface,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val c = warningSheetContent(id)
    val accent = when (surface) {
        WarningSurface.HardBlockSheet -> MaterialTheme.colorScheme.error
        WarningSurface.SoftSheet -> Color(0xFFFBBF24)            // amber — or a tokens-doc token if defined
        WarningSurface.AdvisorySheet -> MaterialTheme.colorScheme.primary
        WarningSurface.TopBanner -> MaterialTheme.colorScheme.primary // unreachable here
    }
    val sheetState = rememberModalBottomSheetState(
        // Hard blocks: not dismissible by drag-down; soft/advisory: dismissible.
        skipPartiallyExpanded = true,
        confirmValueChange = { surface != WarningSurface.HardBlockSheet || it != SheetValue.Hidden },
    )
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(accent.copy(alpha = 0.13f)),
                contentAlignment = Alignment.Center,
            ) { Icon(c.icon, contentDescription = null, tint = accent) }
            Text(c.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            if (c.body.isNotBlank()) {
                Text(c.body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(4.dp))
            Button(onClick = onPrimary, modifier = Modifier.fillMaxWidth()) { Text(c.primary.label) }
            c.secondary?.let { sec ->
                TextButton(onClick = onSecondary, modifier = Modifier.fillMaxWidth()) { Text(sec.label) }
            }
        }
    }
}

@Composable
private fun WarningChip(id: WarningId, onExpand: () -> Unit, modifier: Modifier = Modifier) {
    val c = warningSheetContent(id)
    Surface(
        modifier = modifier.clickable { onExpand() },
        shape = RoundedCornerShape(20.dp),
        color = Color.Black.copy(alpha = 0.40f),
        contentColor = Color.White,
    ) {
        Row(Modifier.padding(horizontal = 11.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(c.icon, contentDescription = null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(14.dp))
            Text(c.title, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f), maxLines = 1)
        }
    }
}
```

> Imports to add: `androidx.compose.material3.{ModalBottomSheet, rememberModalBottomSheetState, SheetValue, BottomSheetDefaults, ExperimentalMaterial3Api, Button, TextButton, Surface}`, `androidx.compose.runtime.{rememberSaveable, mutableStateOf, getValue, setValue}` (rememberSaveable is in `androidx.compose.runtime.saveable`), `androidx.compose.foundation.layout.{Spacer, height, size, fillMaxWidth, padding}`, `androidx.compose.ui.text.font.FontWeight`, `androidx.compose.ui.text.style.TextAlign`, `androidx.compose.foundation.clickable`, `androidx.compose.foundation.shape.RoundedCornerShape`, `androidx.compose.foundation.background`, `androidx.compose.ui.draw.clip`, `androidx.compose.ui.graphics.Color`. The `WarningCenter()` function keeps its existing `viewModel(factory = viewModelFactory { initializer { WarningCenterViewModel(...9 flows...) } })` block verbatim. Delete the now-unused `WarningTier` import only if nothing else uses it (the `tier` field on `WarningId` may still be referenced by `WarningPrecedence` — leave the type).

- [ ] **Step 2: Verify it compiles** — `(./gradlew :app:assembleDebug 2>&1; echo "EXIT=$?") > /tmp/g.log 2>&1; tail -50 /tmp/g.log` → `BUILD SUCCESSFUL`. (`RecordScreen` still calls `WarningCenter()` at its old call site — that's fine; a `ModalBottomSheet` positions itself regardless of where in the tree it's invoked. The collapsed `WarningChip` will render at the call site, which Task 14 relocates.)

- [ ] **Step 3: Run the warnings unit tests** — `(./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.warnings.*" 2>&1; echo "EXIT=$?") > /tmp/g.log 2>&1; tail -40 /tmp/g.log` → all PASS (`WarningPrecedenceTest`, `WarningCenterAggregateTest`, `WarningIdOrderTest`, `WarningSurfaceTest`, `WarningSheetContentTest`, the `*PermissionSignalTest`s, `StorageSignalTest`).

- [ ] **Step 4: Commit**

```
git add app/src/main/java/com/aritr/rova/ui/warnings/WarningCenter.kt
git commit -m "feat(warnings): WarningSheet/WarningChip replace the inline WarningBanner (ADR 0007)"
```

---

### Task 10: `RecordRecoveryChip` composable

A small glass chip — "N recording(s) interrupted — Review" → History. Replaces the visual of `RecoveryEchoBanner` (the eligibility/count logic stays in `RecordScreen`). Add to `RecordChrome.kt`.

**Files:** Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt`

- [ ] **Step 1: Write the composable**

```kotlin
// ── add to RecordChrome.kt ──
import androidx.compose.material.icons.filled.History as HistoryIcon
import androidx.compose.material3.Surface

/**
 * Low-key recovery nudge on the Record idle screen (a chip near the status pill). Recovery
 * *cards* live on the Library (the replan / ADR 0005); this is just the landing-screen ping.
 * Replaces the visual of the old RecoveryEchoBanner; RecordScreen still computes [count] from
 * RovaApp.recoveryReport via RecoveryViewSource.eligibleSessionCount.
 */
@Composable
fun RecordRecoveryChip(count: Int, onReview: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.clickable { onReview() },
        shape = RoundedCornerShape(20.dp),
        color = Color.Black.copy(alpha = 0.40f),
        contentColor = Color.White,
    ) {
        Row(Modifier.padding(horizontal = 11.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(Icons.Default.HistoryIcon, contentDescription = null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(14.dp))
            val word = if (count == 1) "recording" else "recordings"
            Text("$count $word interrupted · Review", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f), maxLines = 1)
        }
    }
}
```

- [ ] **Step 2: Verify it compiles.**
- [ ] **Step 3: Commit** — `git commit -m "feat(record): RecordRecoveryChip composable"`

---

### Task 11: `SessionSettingsSheet` composable + row→picker callback

The combined per-session settings `ModalBottomSheet`: a non-interactive "Portrait" mode row + the four setting rows + a "Done" button; row taps fire `onPickRow(SheetTarget)`. Add to `SessionSettingsSheet.kt` (which already has the formatters from Task 4).

**Files:** Modify: `app/src/main/java/com/aritr/rova/ui/screens/SessionSettingsSheet.kt`

- [ ] **Step 1: Write the composable**

```kotlin
// ── add to SessionSettingsSheet.kt ──
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The swipe-up combined per-session settings sheet (mockups/new_uiux/02-settings-sheet.html).
 * "Recording mode" is a single non-interactive "Portrait" row for v1.0.0 (the picker ships with
 * Phase 6). Each setting row → [onPickRow] with the matching [SheetTarget]; the caller (RecordScreen)
 * opens that param's existing edit sheet via RecordViewModel.openSheet — which renders ON TOP of this
 * sheet (a second ModalBottomSheet); closing it returns here. "Done" → [onDismiss].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionSettingsSheet(
    durationSeconds: Int,
    loopCount: Int,
    intervalMinutes: Int,
    quality: String,
    onPickRow: (SheetTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, dragHandle = { BottomSheetDefaults.DragHandle() }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionLabel("Recording mode")
            // non-interactive Portrait chip
            Row(
                Modifier.padding(vertical = 8.dp, horizontal = 4.dp).clip(RoundedCornerShape(9.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.30f), RoundedCornerShape(9.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(recordModeValue(), style = MaterialTheme.typography.bodyMedium)
                Text("· landscape coming soon", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer8()
            SectionLabel("Session settings")
            SettingRow("Clip duration", recordClipValue(durationSeconds)) { onPickRow(SheetTarget.ClipLength) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            SettingRow("Repeats", recordRepeatsValue(loopCount)) { onPickRow(SheetTarget.Repeats) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            SettingRow("Wait between", recordWaitValue(intervalMinutes)) { onPickRow(SheetTarget.Wait) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            SettingRow("Quality", quality) { onPickRow(SheetTarget.Quality) }
            Spacer8()
            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Done") }
        }
    }
}

@Composable private fun SectionLabel(text: String) =
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp))

@Composable private fun SettingRow(label: String, value: String, onClick: () -> Unit) =
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 12.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
    }

@Composable private fun Spacer8() = androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 8.dp))
```

> `SheetTarget` is the existing enum in `RecordViewModel.kt` (`ClipLength`, `Repeats`, `Wait`, `Quality`) — same package (`com.aritr.rova.ui.screens`), so it's directly usable. Fix the cosmetic `Spacer8`/`clip` import shortcuts (add `androidx.compose.ui.draw.clip`, `androidx.compose.foundation.layout.Spacer`, `androidx.compose.foundation.layout.height`) — use `Spacer(Modifier.height(8.dp))` not the `padding` hack.

- [ ] **Step 2: Verify it compiles.**
- [ ] **Step 3: Commit** — `git commit -m "feat(record): SessionSettingsSheet (combined per-session settings) composable"`

---

## Phase C — RecordViewModel state

### Task 12: `combinedSettingsOpen` on `RecordViewModel`

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordViewModel.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/screens/RecordViewModelSettingsSheetTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.aritr.rova.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordViewModelSettingsSheetTest {
    // Pure state-holder behaviour — no Android, no service bind. If the VM's ctor needs a
    // Context/Application, this test instead exercises a thin extracted holder; but RecordViewModel's
    // openSheet/closeSheet (existing) are already unit-exercised elsewhere via plain MutableStateFlow,
    // so the same pattern applies. If the VM truly cannot be constructed in a unit test, SKIP this
    // task's test and instead assert the StateFlow defaults via a tiny `internal` holder class.
    //
    // Practical form: RecordViewModel exposes `combinedSettingsOpen: StateFlow<Boolean>` (initial false),
    // `openSettingsSheet()`, `closeSettingsSheet()`. Assert: initial false; after open → true; after close → false.

    @Test fun documentsContract() {
        // Placeholder for the contract; the real assertions go here once the VM is constructible
        // in test or the holder is extracted. Keep this test compiling — it's the spec stub.
        assertTrue(true)
    }
}
```

> **Implementer note:** `RecordViewModel` binds a service in `init` (via `Context`), so it's likely *not* unit-constructible. If so: skip the failing-test dance for this task — just add the three members to the VM, confirm `:app:assembleDebug`, and delete this stub test (or keep it trivial). Do **not** invent a Robolectric test. The `editingField`/`openSheet`/`closeSheet` members already follow exactly this pattern with no unit test of their own.

- [ ] **Step 2: Add to `RecordViewModel.kt`**

```kotlin
// alongside the existing _editingField / editingField:
private val _combinedSettingsOpen = MutableStateFlow(false)
val combinedSettingsOpen: StateFlow<Boolean> = _combinedSettingsOpen.asStateFlow()

fun openSettingsSheet() { _combinedSettingsOpen.value = true }
fun closeSettingsSheet() { _combinedSettingsOpen.value = false }
```

> `MutableStateFlow` / `asStateFlow` / `StateFlow` are already imported in `RecordViewModel.kt`.

- [ ] **Step 3: Verify it compiles** — `(./gradlew :app:assembleDebug 2>&1; echo "EXIT=$?") > /tmp/g.log 2>&1; tail -50 /tmp/g.log` → `BUILD SUCCESSFUL`. Run `:app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.RecordViewModelSettingsSheetTest"` → PASS (or, if the stub was deleted, skip).

- [ ] **Step 4: Commit**

```
git add app/src/main/java/com/aritr/rova/ui/screens/RecordViewModel.kt app/src/test/java/com/aritr/rova/ui/screens/RecordViewModelSettingsSheetTest.kt
git commit -m "feat(record): RecordViewModel.combinedSettingsOpen state for the settings sheet"
```

---

## Phase D — RecordScreen re-assembly

> `RecordScreen.kt` today (≈940 lines) is densely commented. **Preserve every behaviour verbatim** unless this plan says delete it: the `onStart` handler + `StartResult.Blocked` snackbar paths, `viewModel.stopRecording()`, the `produceState` session/clip elapsed timers + the `displayedCountdownSeconds` local-tick, the `cameraWarmingUp` 1500 ms warm-up overlay, the "Initializing Camera…" loading overlay, the `produceState` `interruptedSessionCount` + the recovery-echo eligibility filter, the `DisposableEffect(keepScreenOn)`, the `DisposableEffect(previewView)` surface wiring, the `rememberMultiplePermissionsState` + `hasCapturePermissions`, the `LaunchedEffect(serviceState.recordingError)` snackbar, the `LaunchedEffect(permissionsState…)` + `LaunchedEffect(duration,loopCount,resolution)` signal-refreshes, the `DisposableEffect(lifecycleOwner)` ON_STOP/ON_START/ON_RESUME observer, the `cameraPermissionFlow`/`storageInsufficientFlow` reads → `startBlocked`, the `RecordHudState.from(...)`, the `wasMerging`/`showCompleteCard`/`lastCompleteClipCount` merge-grace `LaunchedEffect` + `MergeCompleteCard`, the camera-disconnect `AlertDialog`, the `editingField` `when` router with `ClipLengthEditSheet`/`RepeatsEditSheet`/`WaitEditSheet`/`QualityEditSheet`. Line ranges below refer to the file as of `master` @ `e505c35`.

### Task 13: RecordScreen — add the new chrome; remove the app bar + the big-red-Stop button

Keep the navy `RecordIdleDock` for now (place it above the new bottom nav so they don't collide — a transitional state). After this task: the screen has the new top overlay + cam-controls + bottom nav (FAB does Start/Stop), the app-bar `Row` is gone, the big-red-Stop `Button` is gone, but the idle body is still the old dock. It builds and runs.

**Files:** Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt`

- [ ] **Step 1: Compute the new chrome inputs.** Just above the `ModalNavigationDrawer { … }` block (after `val onStart: () -> Unit = …`), add:

```kotlin
val hardBlockActive = startBlocked   // = !cameraPermissionGranted || storageInsufficient (already computed)
val sessionLocked = serviceState.isPeriodicActive || serviceState.isMerging
val fabState = recordFabState(hudState, sessionLocked = sessionLocked, hardBlockActive = hardBlockActive)
val onFabClick: () -> Unit = {
    when (fabState) {
        RecordFabState.Start -> onStart()
        RecordFabState.Stop -> viewModel.stopRecording()
        RecordFabState.Disabled -> { /* no-op — the warning sheet carries the CTA */ }
    }
}
// status pill text
val statusText: String; val statusDetail: String?
when (hudState) {
    RecordHudState.Recording -> { statusText = "Recording"; statusDetail = "${clipElapsedSeconds}s of ${duration}s" } // reuse R2-bound formatting later
    RecordHudState.Waiting   -> { statusText = "On break"; statusDetail = "next in ${displayedCountdownSeconds}s" }
    is RecordHudState.Merging -> { statusText = "Merging"; statusDetail = null }
    RecordHudState.Idle -> { statusText = "Ready to record"; statusDetail = null }
}
```

> The exact active-state copy is R2's concern — keep it functional (don't agonise over it now). `clipElapsedSeconds` / `displayedCountdownSeconds` / `duration` are already in scope.

- [ ] **Step 2: Inside the `Scaffold { innerPadding -> Box(...) { … } }`** — delete the entire **app-bar `Row`** (the `Row` with the hamburger `IconButton`, the centered "Rova"/"Hands-free loop recorder" `Column` / "INITIALIZING…" / `Spacer`, and the flash/flip/help `IconButton`s — currently the first child of the `Column(modifier = Modifier.fillMaxWidth())` inside the Box, ≈ lines 518–589). The flash/flip behaviour moves into `RecordCameraControls`; the hamburger (drawer) is being deleted in Task 14; "Rova"/branding and "INITIALIZING…" are absorbed by the status pill / loading overlay.

  Then, **still inside the Box**, after the existing `Column { WarningCenter(); RecoveryEchoBanner?; RecordStatusStrip? }` block and the bottom-area `when`, add the new chrome as the **last** Box children (so they paint on top):

```kotlin
// ── new chrome — top overlay + cam-controls (top), bottom nav (bottom) ──
RecordTopOverlay(
    hudState = hudState,
    statusText = statusText,
    statusDetail = statusDetail,
    currentLoop = serviceState.currentLoop,
    totalLoops = serviceState.totalLoops,
    modifier = Modifier
        .align(Alignment.TopStart)
        .windowInsetsPadding(WindowInsets.statusBars)
        .padding(start = 16.dp, top = 16.dp),
)
RecordCameraControls(
    flashMode = flashMode,
    onCycleFlash = { if (!isUiLocked) viewModel.setFlashMode((flashMode + 1) % 3) },
    onFlip = { if (!isUiLocked) viewModel.flipCamera() },
    enabled = !isUiLocked,
    modifier = Modifier
        .align(Alignment.TopEnd)
        .windowInsetsPadding(WindowInsets.statusBars)
        .padding(end = 16.dp, top = 16.dp),
)
RecordBottomNav(
    fabState = fabState,
    navItemsLocked = sessionLocked,
    onLibrary = onNavigateToHistory,
    onSettings = { /* wired in Task 15 / via a new onNavigateToSettings callback — see note */ },
    onFabClick = onFabClick,
    modifier = Modifier.align(Alignment.BottomCenter),
)
```

> **`onSettings`:** `RecordScreen` currently has no "navigate to Settings" callback. Add a new param `onNavigateToSettings: () -> Unit = {}` to `RecordScreen` (and wire it from `MainScreen` in Task 15). For now stub it `{}` so this task compiles; Task 15 fills it. — Add `import androidx.compose.foundation.layout.{WindowInsets, statusBars, windowInsetsPadding}`.

- [ ] **Step 3: Stop the navy dock colliding with the new bottom nav** — the `RecordIdleDock(...)` call (the `else if (!showCompleteCard)` branch, ≈ lines 717–744) currently has `modifier = Modifier.align(Alignment.BottomCenter)`. Change it to `Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp)` (rough nav height) so the old dock sits above the new bottom nav during this transitional task. Likewise the active-HUD `Column(Modifier.align(Alignment.BottomCenter)…)` and the merging `Column(Modifier.align(Alignment.BottomCenter)…)` — add `.padding(bottom = 96.dp)` so the existing Stop band / merge band clear the new nav. **Delete the big-red "Stop recording" `Button`** inside the active-HUD `Column` (≈ lines 674–697) — Stop is the FAB now; the `ClipProgressBand` / `WaitingCountdown` stay.

- [ ] **Step 4: Verify it compiles + runs** — `(./gradlew :app:assembleDebug 2>&1; echo "EXIT=$?") > /tmp/g.log 2>&1; tail -50 /tmp/g.log` → `BUILD SUCCESSFUL`. (Don't run the app here — owner smoke later.) Run `:app:testDebugUnitTest` → expect still green (no test touches these UI lines except `RecordHudState*`/`RecordHudFormatters*`, untouched).

- [ ] **Step 5: Commit**

```
git add app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt
git commit -m "feat(record): add new chrome (top overlay, cam-controls, bottom nav + FAB); drop app bar + big Stop button"
```

---

### Task 14: RecordScreen — swap the navy dock for the new idle body; remove drawer + tutorial + save-preset dialog; wire WarningCenter-sheet + SessionSettingsSheet

After this task: the idle screen is the camera-first design (settings card + recovery chip + camera-off placeholder); the `ModalNavigationDrawer` wrapper, the tutorial overlay, the Info button, and the "Save preset" dialog are gone; `WarningCenter()` (now the sheet/chip) and `SessionSettingsSheet` are wired.

**Files:** Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt`

- [ ] **Step 1: Remove the `ModalNavigationDrawer` wrapper** — the whole `ModalNavigationDrawer(drawerState = …, gesturesEnabled = …, drawerContent = { ModalDrawerSheet { Column { … "Quick settings" card, SwitchRow×2 … } } }) { Scaffold(...) { … } }` (≈ lines 431–828) becomes just `Scaffold(...) { … }`. Delete: `val drawerState = rememberDrawerState(...)`, the `drawerContent` lambda, the `scope.launch { drawerState.open() }` callers (the hamburger is gone anyway), and the now-unused imports (`ModalNavigationDrawer`, `ModalDrawerSheet`, `rememberDrawerState`, `DrawerValue`, `SwitchRow`). `settingsViewModel` is still used (for `keepScreenOn` in the `DisposableEffect`) — keep that param and that effect.

- [ ] **Step 2: Delete the tutorial overlay + the "Save preset" dialog** — remove the `if (showTutorial) { Box(...) { … 3-step walkthrough … } }` block (≈ lines 764–825) and `var showTutorial`/`var tutorialStep`; remove the `if (showSavePresetDialog) { AlertDialog(...) }` block (≈ lines 903–929) and `var showSavePresetDialog`/`var presetNameInput`. (The Info button was already removed with the app-bar Row in Task 13. The tutorial is Onboarding's job now — spec A2.)

- [ ] **Step 3: Replace the navy idle dock with the new idle body** — the `else if (!showCompleteCard) { RecordIdleDock(...) }` branch (now with the temp `.padding(bottom = 96.dp)`) becomes:

```kotlin
} else if (!showCompleteCard) {
    // Idle: camera-first body. The settings card sits above the bottom nav; the recovery
    // chip (if any) sits under the status pill. The viewfinder is the camera preview behind.
    RecordSettingsCard(
        durationSeconds = duration,
        loopCount = loopCount,
        intervalMinutes = interval,
        quality = resolution,
        onOpenSheet = { viewModel.openSettingsSheet() },
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(bottom = 90.dp, start = 16.dp, end = 16.dp),   // clear the bottom nav; tune to RecordBottomNav's height
    )
}
```

> Drop the `onCellTap`/`onPresetSelected`/`onPresetDeleted`/`onSavePreset`/`onStart`/`startEnabled` plumbing that fed `RecordIdleDock` — the settings card has only `onOpenSheet`, and the FAB owns Start. The `customPresets`/`viewModel.deletePreset`/`viewModel.savePreset` references in that branch go away (the VM methods stay, just unused — spec dormancy).

  And the **recovery echo** block (currently `if (hudState == RecordHudState.Idle && interruptedSessionCount > 0) { Box(...) { RecoveryEchoBanner(interruptedCount = …, onReviewInHistory = onNavigateToHistory) } }` inside the top `Column`) → render `RecordRecoveryChip` instead, and move it out of the `Column` into the Box, aligned under the status pill:

```kotlin
if (hudState == RecordHudState.Idle && interruptedSessionCount > 0) {
    RecordRecoveryChip(
        count = interruptedSessionCount,
        onReview = onNavigateToHistory,
        modifier = Modifier
            .align(Alignment.TopStart)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 16.dp, top = 70.dp),   // sits just below the status pill (~16 + pill height)
    )
}
```

  The top `Column(modifier = Modifier.fillMaxWidth())` that used to hold the app bar + `WarningCenter()` + `RecoveryEchoBanner` + `RecordStatusStrip` — now it holds only `RecordStatusStrip` (for Recording/Waiting). Either keep that slimmed `Column` (just the `if (hudState == Recording || Waiting) { Spacer(8.dp); RecordStatusStrip(...) }`) positioned below the top overlay, or fold `RecordStatusStrip` into `RecordTopOverlay`'s active branch and delete the `Column` entirely. **Recommended: keep the slim `Column` for R1** (it holds `RecordStatusStrip`, an existing component R2 will replace) — just give it `Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.statusBars).padding(top = 100.dp)` so it sits below the top overlay. `WarningCenter()` is no longer called inside that Column — call it as a standalone Box child (its `ModalBottomSheet`/chip positions itself):

```kotlin
WarningCenter()   // ADR 0007 — renders the warning sheet (modal) or, if collapsed, a chip at this position
```

  > For the collapsed `WarningChip` to land under the status pill (not at the top-left origin), give `WarningCenter` a `modifier` and have it pass it to `WarningChip` — or, simplest for R1, accept the chip rendering wherever `WarningCenter()` is invoked and place that call inside a `Box(Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.statusBars).padding(start = 16.dp, top = 110.dp)) { WarningCenter() }`. The modal sheet ignores that wrapper's position anyway.

- [ ] **Step 4: Wire `SessionSettingsSheet`** — outside the `Box`, near the existing `editingField` `when` router, add:

```kotlin
val combinedOpen by viewModel.combinedSettingsOpen.collectAsStateWithLifecycle()
if (combinedOpen) {
    SessionSettingsSheet(
        durationSeconds = duration,
        loopCount = loopCount,
        intervalMinutes = interval,
        quality = resolution,
        onPickRow = { target -> viewModel.openSheet(target) },   // opens the existing per-param sheet ON TOP
        onDismiss = { viewModel.closeSettingsSheet() },
    )
}
// (the existing `when (editingField) { SheetTarget.ClipLength -> ClipLengthEditSheet(...) ; ... }` block stays
//  exactly as-is below — those per-param sheets now stack over SessionSettingsSheet when invoked from a row)
```

- [ ] **Step 5: Remove the temp `.padding(bottom = 96.dp)`** band-aids added in Task 13 to the active-HUD `Column` and the merging `Column`, replacing with `.windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 90.dp)` (clear the real bottom nav). Make sure the `SessionTimer` `align(Center)` block for Recording/Waiting still sits sensibly (it had `.padding(top = 96.dp)` — leave it; R2 restyles).

- [ ] **Step 6: Tidy imports** — remove `ModalNavigationDrawer`/`ModalDrawerSheet`/`rememberDrawerState`/`DrawerValue`/`SwitchRow`/`RecordIdleDock`/`RecoveryEchoBanner` (if `RecoveryEchoBanner` is now unused — it is, replaced by `RecordRecoveryChip`); add `RecordSettingsCard`/`RecordRecoveryChip`/`SessionSettingsSheet`/`recordFabState`/`RecordFabState`/`RecordTopOverlay`/`RecordCameraControls`/`RecordBottomNav` (same package — likely no import needed, all in `com.aritr.rova.ui.screens`) and the `WindowInsets` ones. `tutorial`/`save-preset`-only imports (`Icons.Default.Tune`/`Videocam`/`Save` if only the tutorial used them — check; `Videocam` may still be used elsewhere) — remove the now-orphaned ones. Let the compiler tell you.

- [ ] **Step 7: Verify it compiles + tests green** — `(./gradlew :app:assembleDebug 2>&1; echo "EXIT=$?") > /tmp/g.log 2>&1; tail -50 /tmp/g.log` → `BUILD SUCCESSFUL`. `(./gradlew :app:testDebugUnitTest 2>&1; echo "EXIT=$?") > /tmp/g.log 2>&1; tail -40 /tmp/g.log` → all green (count = 729 − any deleted-component tests not yet deleted; `PlanSummaryTest` still exists at this point → still 729-ish; the `RecordIdleDock` private formatters had no test of their own — confirm via search).

- [ ] **Step 8: Commit**

```
git add app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt
git commit -m "feat(record): camera-first idle body (settings card + recovery chip), warning sheet, settings sheet; drop drawer/tutorial/save-preset"
```

---

## Phase E — Navigation restructure

### Task 15: `MainScreen.kt` — drop the persistent NavigationBar; drill-down History/Settings; un-hoist RecordViewModel

**Files:** Modify: `app/src/main/java/com/aritr/rova/ui/MainScreen.kt`; touch `app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt` (its `viewModel` param + the new `onNavigateToSettings`).

- [ ] **Step 1: Edit `MainScreen.kt`** —
  - Remove the `Scaffold`'s `bottomBar = { … }` lambda entirely (the `NavigationBar`, the `NavigationBarItem`s, the `currentRoute`/`currentDestination` derivation that fed `selected`, the `sessionLocked`-driven `enabled = !sessionLocked` + the "Locked during recording" pill, and the `TOP_LEVEL_ROUTES` collapse guard). The `Scaffold` keeps only its `content = { innerPadding -> NavHost(...) }` (and `containerColor` if set).
  - Remove `val recordViewModel: RecordViewModel = viewModel()` (the hoisted one) and the `serviceState`/`sessionLocked` reads in `MainScreen`. `RecordScreen` will own its VM (Step 2). Keep `val settingsViewModel: SettingsViewModel = viewModel()` (still shared by `RecordScreen` + `SettingsScreen`).
  - The `composable("record") { RecordScreen(onMergeFinished = toHistory, onNavigateToHistory = toHistory, viewModel = recordViewModel, settingsViewModel = settingsViewModel) }` → `RecordScreen(onMergeFinished = toHistory, onNavigateToHistory = toHistory, onNavigateToSettings = { navController.navigate("settings") { launchSingleTop = true } }, settingsViewModel = settingsViewModel)` — drop the `viewModel = recordViewModel` arg (RecordScreen defaults to `viewModel()`).
  - The `composable("history") { HistoryScreen(onNavigateToRecord = …, onOpenPlayer = …) }` → add `onBack = { navController.popBackStack() }`.
  - The `composable("settings") { SettingsScreen(settingsViewModel = settingsViewModel) }` → add `onBack = { navController.popBackStack() }`.
  - Remove now-unused imports (`NavigationBar`, `NavigationBarItem`, `NavigationBarItemDefaults`, `RecordViewModel` if no longer referenced, the `TOP_LEVEL_ROUTES` set + whatever it used). `MainActivity` is **unchanged** (still calls `MainScreen()` + triggers the recovery scan).

- [ ] **Step 2: Edit `RecordScreen.kt`** —
  - Change the signature: `viewModel: RecordViewModel = viewModel()` (it currently may already be `= viewModel()` with `MainScreen` passing one explicitly — either way it now defaults; just ensure no caller passes it). Add `onNavigateToSettings: () -> Unit = {}` to the param list (used by `RecordBottomNav`'s `onSettings`).
  - In Task 13's `RecordBottomNav(...)` call, set `onSettings = onNavigateToSettings`.

- [ ] **Step 3: Verify it compiles + runs** — `(./gradlew :app:assembleDebug 2>&1; echo "EXIT=$?") > /tmp/g.log 2>&1; tail -50 /tmp/g.log` → `BUILD SUCCESSFUL`. (At this point: no persistent nav bar; Record has its own; Library/Settings reachable from Record; History/Settings still lack a back arrow until Tasks 16/17 — but `popBackStack()` works via system back / predictive back already.)

- [ ] **Step 4: Commit**

```
git add app/src/main/java/com/aritr/rova/ui/MainScreen.kt app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt
git commit -m "feat(nav): Record owns its bottom nav; History/Settings become drill-down; drop the persistent NavigationBar"
```

---

### Task 16: `HistoryScreen.kt` — back arrow

**Files:** Modify: `app/src/main/java/com/aritr/rova/ui/screens/HistoryScreen.kt`

- [ ] **Step 1:** Add `onBack: () -> Unit = {}` to the `HistoryScreen` param list. In its `TopAppBar` (the non-selection state — the one with the "Library" title + summary counts + retention pill), add `navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }`. (Leave the selection-mode bar's existing close/back affordance as-is.) Add the import `androidx.compose.material.icons.automirrored.filled.ArrowBack` + `androidx.compose.material3.IconButton` / `Icon` if not present.

- [ ] **Step 2:** Verify it compiles. `git commit -m "feat(nav): HistoryScreen back arrow"`

---

### Task 17: `SettingsScreen.kt` — back arrow

**Files:** Modify: `app/src/main/java/com/aritr/rova/ui/screens/SettingsScreen.kt`

- [ ] **Step 1:** Add `onBack: () -> Unit = {}` to the `SettingsScreen` param list. In its `TopAppBar` ("Settings" title), add `navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }`. Imports as in Task 16.

- [ ] **Step 2:** Verify it compiles. `git commit -m "feat(nav): SettingsScreen back arrow"`

---

## Phase F — Deletions

### Task 18: Delete `RecordIdleDock.kt`, `PlanSummary.kt`, `TappablePlanCell.kt` (+ their tests)

**Files:** Delete: `app/src/main/java/com/aritr/rova/ui/screens/RecordIdleDock.kt`, `app/src/main/java/com/aritr/rova/ui/components/PlanSummary.kt`, `app/src/main/java/com/aritr/rova/ui/components/TappablePlanCell.kt`, `app/src/test/java/com/aritr/rova/ui/components/PlanSummaryTest.kt`, and any `TappablePlanCellTest.kt` / `RecordIdleDock*Test.kt` if present.

- [ ] **Step 1: Search for remaining references** — `Grep` for `RecordIdleDock`, `PlanSummary`, `TappablePlanCell` across `app/src/`. Expected: only the files being deleted + the deleted test(s). If anything else references them, fix it (it shouldn't — Task 14 removed the `RecordScreen` usage). Also `Grep` for `TappablePlanCellTest` / `RecordIdleDockTest` under `app/src/test/`.

- [ ] **Step 2: Delete the files** — `git rm app/src/main/java/com/aritr/rova/ui/screens/RecordIdleDock.kt app/src/main/java/com/aritr/rova/ui/components/PlanSummary.kt app/src/main/java/com/aritr/rova/ui/components/TappablePlanCell.kt app/src/test/java/com/aritr/rova/ui/components/PlanSummaryTest.kt` (+ any others found in Step 1).

- [ ] **Step 3: Verify build + tests** — `(./gradlew :app:assembleDebug 2>&1; echo "EXIT=$?") > /tmp/g.log 2>&1; tail -50 /tmp/g.log` → `BUILD SUCCESSFUL`. `(./gradlew :app:testDebugUnitTest 2>&1; echo "EXIT=$?") > /tmp/g.log 2>&1; tail -40 /tmp/g.log` → all green; record the new count (= 729 − deleted test methods; `PlanSummaryTest` had N methods — note N in the commit body).

- [ ] **Step 4: Commit**

```
git add -A
git commit -m "refactor(record): delete RecordIdleDock / PlanSummary / TappablePlanCell (superseded by the new chrome)"
```

---

## Phase G — Docs

### Task 19: Amend `NEW_UI_BACKEND_REPLAN.md`, `docs/WarningCenterContract.md`, `docs/UI_NAV_GRAPH.md`

**Files:** Modify: `NEW_UI_BACKEND_REPLAN.md`, `docs/WarningCenterContract.md`, `docs/UI_NAV_GRAPH.md`.

- [ ] **Step 1:** In `NEW_UI_BACKEND_REPLAN.md` §"Phase 4" — under the "Banner precedence (input spec for 4.1)" paragraph and/or the precedence table, add a blockquote: *"**Superseded for the Record-screen surface by ADR 0007 (2026-05-12, R1 redesign):** the WarningCenter precedence VM is retained, but the resolved warning is now rendered as a per-tier modal **sheet / chip** (`07-warnings.html`) — not a single inline banner. The hard-block Start-gate is unchanged. See `docs/superpowers/specs/2026-05-12-record-home-redesign-r1-design.md`."* In §6 (Dependency Matrix), the "Record idle home …" rows: append *"— Record-home re-skin tracked as the **Record-home redesign R1** (`docs/superpowers/specs/2026-05-12-record-home-redesign-r1-design.md`); not the same as the Phase-2.1 'App Settings re-skin' slice."*

- [ ] **Step 2:** In `docs/WarningCenterContract.md` — at §C3.1 (the inline-banner description) and anywhere it says "single Record-screen banner", add: *"**Superseded by ADR 0007** — the Record-screen presentation is the `WarningSheet`/`WarningChip` model (per-tier modal sheet, collapse-to-chip), not an inline `Surface` strip. The precedence model in this contract is otherwise unchanged."*

- [ ] **Step 3:** In `docs/UI_NAV_GRAPH.md` — update the navigation description: there is no persistent app-wide `NavigationBar`; the Record screen is the home and carries its own bottom nav (Library / center Start-Stop FAB / Settings); `history` and `settings` are drill-down routes (pushed; back arrow; `popBackStack()`); the old `TOP_LEVEL_ROUTES`-collapse mechanism is removed. Keep the route names (`onboarding`, `record`, `history`, `player/{sessionId}`, `settings`).

- [ ] **Step 4: Commit** — `git add NEW_UI_BACKEND_REPLAN.md docs/WarningCenterContract.md docs/UI_NAV_GRAPH.md && git commit -m "docs: amend replan/WarningCenterContract/UI_NAV_GRAPH for ADR 0007 + the nav restructure"`

---

## Phase H — Gates + PR

### Task 20: Run the three gates; record results; fix or flag new lint

**Files:** none (verification).

- [ ] **Step 1: assembleDebug** — `(./gradlew :app:assembleDebug 2>&1; echo "EXIT=$?") > /tmp/g.log 2>&1; tail -60 /tmp/g.log` → `BUILD SUCCESSFUL`, `EXIT=0`, no "Failed to resolve". Note any deprecation warnings (baseline = 1: `excludeLibraryComponentsFromConstraints`).

- [ ] **Step 2: testDebugUnitTest (unfiltered)** — `(./gradlew :app:testDebugUnitTest 2>&1; echo "EXIT=$?") > /tmp/g.log 2>&1; tail -40 /tmp/g.log` → `BUILD SUCCESSFUL`. Count from `app/build/test-results/testDebugUnitTest/*.xml`: `<testsuite` count (classes), `<testcase` count (tests), `<failure`/`<error`/`<skipped` counts (must be 0/0/0). Expected ≈ **729 − PlanSummaryTest methods + 4 new helper-test classes' methods** (`RecordFabStateTest` 5, `WarningSurfaceTest` 1, `WarningSheetContentTest` ~4, `SessionSettingsCardFormattersTest` 4, `RecordViewModelSettingsSheetTest` 1-or-deleted). Record the exact numbers.

- [ ] **Step 3: lintDebug** — `(./gradlew :app:lintDebug 2>&1; echo "EXIT=$?") > /tmp/g.log 2>&1; tail -40 /tmp/g.log` → `BUILD SUCCESSFUL`. From `app/build/reports/lint-results-debug.xml`: count `<issue` (baseline 53); tally `id="…"` and `severity="…"`. **Report every new id vs the baseline** (baseline ids: `GradleDependency` 14, `UseKtx` 14, `UnusedResources` 7, `UseTomlInstead` 5, `NewerVersionAvailable` 3, `AutoboxingStateCreation` 3, `StaticFieldLeak` 2, `DefaultLocale` 2, `BatteryLife` 1, `ModifierParameter` 1, `DiscouragedApi` 1). Deletions may *reduce* some (e.g. `UseKtx`/`AutoboxingStateCreation` if the deleted files had them); the new composables may *add* some (likely `UseKtx` for any `clip`/`background` ordering, or `ModifierParameter` if a `modifier` param isn't first/defaulted — keep `modifier: Modifier = Modifier` last with a default to avoid that). **If a new finding is real and actionable, fix it in this task** (e.g. reorder a modifier param); if it's a nag, leave it and report it. **Do not add `lint-baseline.xml`.** If you fix anything, re-run lint, then `git add -A && git commit -m "fix(record): address lint findings from the redesign"`.

- [ ] **Step 4:** Record the three results in a scratch note for the done-report (Task 21).

---

### Task 21: Push the branch, open the PR, post the done-report

**Files:** none.

- [ ] **Step 1: Push** — `git push -u origin feat/record-home-redesign-r1` (the branch already has the spec/ADR commits + all the task commits).

- [ ] **Step 2: Open the PR** (PowerShell — `gh` at `/c/Program Files/GitHub CLI/gh.exe`, `--base master`):

```
"/c/Program Files/GitHub CLI/gh.exe" pr create --base master --head feat/record-home-redesign-r1 --title "Record-home redesign R1 — idle layout + chrome + warning sheets" --body-file /tmp/pr_body.md
```
where `/tmp/pr_body.md` is a short body: one-line summary, link to the spec + ADR-0007, "first slice of the Record-screen convergence onto `mockups/new_uiux/`; R2 = active-HUD restyle (separate)", and "**Do not squash-merge — hold for the review-gate (NO-GO/GO).**"

- [ ] **Step 3: Post the done-report as the PR's first comment** — `"/c/Program Files/GitHub CLI/gh.exe" pr comment <PR#> --body-file /tmp/done_report.md`. The done-report contains:
  - Branch + PR# + base; `git diff master --stat`.
  - What shipped: nav restructure (no persistent NavigationBar; Record owns its bottom nav; History/Settings drill-down + back arrows); camera-first idle layout (status pill / cam-controls / settings card → combined sheet → per-row pickers / recovery chip); bottom nav with center Start-Stop FAB; `07-warnings.html` warning sheets/chips replacing the inline banner (precedence VM + Start-gate unchanged); ADR-0007 + replan/Contract/NavGraph amendments; deleted `RecordIdleDock`/`PlanSummary`/`TappablePlanCell`; presets dormant; drawer + tutorial deleted.
  - What's deferred: R2 (active-state restyle — R1 only re-chromed those states + rerouted Stop to the FAB); the mid-recording thermal/storage **top-banner** surface (spec A6); the Portrait/Landscape **Mode** picker (Phase 6 — currently read-only "Portrait").
  - Gate results: `assembleDebug` OK (deprecation warnings: N); `testDebugUnitTest` X tests / Y classes / 0-0-0 (was 729/57; explain the delta — PlanSummaryTest removed, M helper tests added); `lintDebug` Z issues (was 53; list every new/removed id; note any fixed-here).
  - On-device smoke: **DEFERRED-to-owner** — paste the spec §4 smoke checklist verbatim (idle layout + flash/flip + settings-card→sheet→per-row-picker→persist; nav push/back; FAB Start incl. perm-prompt path; recording→Stop-FAB, Library/Settings dim, merging→FAB disabled; warnings — camera/mic/notifications sheets, "Not now"→chip→re-open, primary CTA→system page; recovery chip; edge-to-edge/insets at font scale 100/130/150/200, light+dark; predictive back from sheets/Library/Settings).
  - "**Held for review-gate — not squash-merged.**"

- [ ] **Step 4:** Stop here. **Do not merge.** Report the PR URL back to the owner for the NO-GO/GO review-gate.

---

## Self-review (run after writing; fix inline)

**Spec coverage** — every spec section maps to ≥1 task:
- §2.1 Nav restructure → Tasks 15, 16, 17 ✓ · §2.2 Record structure / chrome → Tasks 5, 6, 7, 8, 13, 14 ✓ · §2.3 Combined settings sheet + per-row picker → Tasks 4, 11, 14 ✓ · §2.5 Warning sheets / `warningSurfaceFor` / `warningSheetContent` / `WarningSheet`/`WarningChip` / Start-gate / recovery chip / ADR + replan amendment → Tasks 2, 3, 9, 10, 14, 19 ✓ · FAB-state helper → Task 1 ✓ · §3 Deletions / dormancy → Tasks 14 (in-file deletions), 18 (file deletions), and dormancy is "do nothing" ✓ · §4 Testing & gates → Tasks 1–4 + 12 (new helper tests), 9 (warnings tests stay green), 20 (gates) ✓ · §5 Risks → reflected in task ordering (per-task commits, R1 = chrome-only for active states, hard-block sheet behaviour spelled out in Task 9) ✓ · §6 Out of scope → nothing in the plan touches `service/`/`data/SessionManifest`/recovery/merger; presets not resurrected; no draft/Save model ✓.

**Placeholder scan** — the `RecordViewModelSettingsSheetTest` "documentsContract" stub is intentionally trivial *with an implementer note* explaining why (the VM isn't unit-constructible); not a hidden TODO. All code steps have full code. No "add error handling"/"similar to Task N"/"TBD". The `onSettings = { /* wired in Task 15 */ }` in Task 13 is a deliberate two-step (stub → fill) with the fill task named explicitly. ✓

**Type consistency** — `RecordFabState` {Start, Stop, Disabled} used identically in Tasks 1, 8, 13. `recordFabState(hudState, sessionLocked, hardBlockActive)` — same signature in Tasks 1 and 13. `WarningSurface` {HardBlockSheet, SoftSheet, AdvisorySheet, TopBanner} — same in Tasks 2, 3, 9. `WarningSheetContent(icon, title, body, primary, secondary)` — same in Tasks 3, 9. `warningSheetContent(id)` returns it — Tasks 3, 9. `recordClipValue`/`recordRepeatsValue`/`recordWaitValue`/`recordModeValue` — defined Task 4, used Tasks 7, 11. `SheetTarget` {ClipLength, Repeats, Wait, Quality} — existing enum, used Tasks 11, 14. `combinedSettingsOpen`/`openSettingsSheet`/`closeSettingsSheet` — defined Task 12, used Task 14. `onNavigateToSettings` param on `RecordScreen` — introduced Task 13 (stub), wired Task 15. ✓

**Known soft spots flagged for the implementer** (not gaps — judgment calls left open per the spec): exact `dp`/color values for the glass panels (consume `docs/UI_DESIGN_TOKENS.md`); whether `RecordChrome.kt` stays one file or splits; whether the collapsed `WarningChip` gets a positioning `modifier` threaded through or is placed via a wrapper `Box`; the precise bottom-nav height constant the idle settings card pads against (measure `RecordBottomNav`); whether `RecordViewModelSettingsSheetTest` survives as a real test or is dropped (depends on VM constructibility).

---

## Execution handoff — see below.

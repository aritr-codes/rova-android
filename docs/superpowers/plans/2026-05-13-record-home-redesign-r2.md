# Record-home redesign — R2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Re-skin the Record screen's three active states (Recording / Waiting / Merging) to the `mockups/new_uiux/01-record-home.html` HUD (loop-pill + status-pill + Stop-FAB), retire the five legacy active-state components (`SessionTimer`, `ClipProgressBand`, `WaitingCountdown`, `MergingProgressBand`, `RecordStatusStrip`), and ship the deferred-from-ADR-0007 mid-recording top-banner surface. Adds one row to the warning precedence enum (`STORAGE_LOW_MID_REC` at ordinal `10`) + its leaf signal `StorageLowMidRecSignal`.

**Architecture:** Pure helpers first (`loopPillContent`, `hudStatusPillContent`, `midRecBannerContent`) and the new leaf signal (`StorageLowMidRecSignal`); then the enum extension and the precedence-VM expansion (the coupled rippling change); then the top-banner composable + `WarningCenter` signature change (gains a `hudState` parameter — dual-branch routing); then the active-HUD composables in `RecordChrome.kt`; then `RecordScreen` integration; then deletion of the five retired components; then doc amendments; then PR. UI-only — no `service/**` or `data/**` diff. The 16 existing `WarningId` rows stay byte-identical in ordinal position; the new row is inserted at ordinal 10 (1-indexed: row 11). `WarningCenterViewModel` precedence semantics and the Start-gate are preserved; only one source flow + one resolve parameter are added at the END of their respective signatures.

**Tech Stack:** Kotlin 2.2.10, Jetpack Compose (Material 3, `composeBom = 2025.01.01`), CameraX 1.4.2, AGP 9.2.1 / Gradle 9.4.1, `compileSdk`/`targetSdk` 37, JUnit 4 unit tests (no Robolectric / no Compose-UI tests in this slice — UI is owner-verified on device, per the spec).

**Spec:** `docs/superpowers/specs/2026-05-13-record-home-redesign-r2-design.md` (committed @ `4c77f6e`). **ADR amended:** `docs/adr/0007-record-warning-sheets.md` (Task 11 appends the 2026-05-13 amendment). **Reference UX:** `mockups/new_uiux/01-record-home.html` (active-state HUD), `mockups/new_uiux/07-warnings.html` row 6 (mid-rec banner). **Branch:** `feat/record-home-redesign-r2` (already cut from `master` @ `07fec76`; the R2 spec is committed there @ `4c77f6e`).

**Baseline (master @ `07fec76`, also branch tip pre-implementation):** `:app:assembleDebug` OK · `:app:testDebugUnitTest` 735 tests / 60 classes / 0-0-0 · `:app:lintDebug` 53 (50 W + 3 H, 0 E) · no `lint-baseline.xml` (don't add one).

---

## Conventions for every task

- **Build command (the `PreToolUse:Bash` hook redirects bare `./gradlew`):** wrap in a subshell with no space after the paren —
  `(./gradlew :app:assembleDebug 2>&1; echo "EXIT=$?") > /tmp/g.log 2>&1; tail -50 /tmp/g.log`
- **Test count (no `python3`/`bc` on the git-bash PATH):** count `<testcase`/`<testsuite`/`<failure`/`<error`/`<skipped` in `app/build/test-results/testDebugUnitTest/*.xml` with the `Grep` tool or `/usr/bin/grep -c` — never awk pipelines.
- **Lint count:** `app/build/reports/lint-results-debug.xml` — `<issue` count + tally `id="…"` / `severity="…"` (each on its own indented line) with the `Grep` tool or `/usr/bin/grep`.
- **`grep` is rewritten to `rtk grep`** (different CLI) — use the `Grep` tool or `/usr/bin/grep`.
- **`gh` CLI:** `/c/Program Files/GitHub CLI/gh.exe` (not on PATH). PowerShell — no bash heredocs in `gh` calls; pass `--body-file` for long bodies.
- **Never stage** `.claude/`, `.github/`, `.mcp.json`, `.superpowers/`, the QA-handoff doc in `docs/superpowers/2026-05-12-record-home-redesign-r1-qa-handoff.md`.
- **Commit after every task** (the `- [ ] Commit` step). Conventional-commit subjects, scoped `feat(record):` / `refactor(record):` / `docs(...)`. End each commit message body with `Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>`.
- **No `service/**` or `data/**` diff** in any task except read-only references to `StorageEstimator.bytesPerSecondForResolution` (consumed but not modified). If a task wants to touch one of those trees, stop and escalate to the controller.
- **No `app/build.gradle.kts` diff** in any task. If a new dep is needed, stop and escalate.
- **Package paths:** new files go in `com.aritr.rova.ui.screens` (Record-screen composables), `com.aritr.rova.ui.warnings` (warning surfaces), `com.aritr.rova.ui.signals` (leaf signals). Tests mirror the same package path under `app/src/test/java/…`.
- **Glass tokens:** the active-HUD pills + the top banner reuse `Color.Black.copy(alpha = 0.40f)` fill + `1.dp` `Color.White.copy(alpha = 0.07f)` border (R1 convention); new dot colors are file-local `val`s in `RecordChrome.kt` (`RecordingDotColor`, `BreakDotColor`, `MergingDotColor` — exact hex in Task 8).
- **Invariant audit before each commit:** `WarningId.kt`'s 16 carry-over rows stay in their original ordinal positions; `WarningPrecedence.resolve(...)` arms for those 16 rows return the same `WarningId` for the same inputs; `WarningCenterViewModel.aggregate(...)` re-uses the same 9 source flows in the same order; the Start-gate (`startBlocked = !cameraPermissionGranted || storageInsufficient`) in `RecordScreen.kt` is byte-identical. If any of these change as a side effect of a task, stop and escalate.

---

## File structure (created / modified / deleted)

**Created:**
- `app/src/main/java/com/aritr/rova/ui/signals/StorageLowMidRecSignal.kt` — new leaf signal: polls free bytes vs. 3 × bytes-per-clip headroom while recording.
- `app/src/test/java/com/aritr/rova/ui/signals/StorageLowMidRecSignalTest.kt`
- `app/src/test/java/com/aritr/rova/ui/screens/RecordActiveHudFormattersTest.kt` — `loopPillContent` + `hudStatusPillContent` JVM tests.
- `app/src/test/java/com/aritr/rova/ui/warnings/MidRecBannerContentTest.kt` — `midRecBannerContent(id)` per-arm tests.

**Modified:**
- `app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt` — add `RecordActiveHud(...)`, file-private `LoopPill` / `StatusPill` / `StatusDot`, the three dot-color `val`s, helpers `loopPillContent(...)` + `hudStatusPillContent(...)`, internal data class `StatusPillContent` + internal enum `StatusDotColor`.
- `app/src/main/java/com/aritr/rova/ui/warnings/WarningId.kt` — insert `STORAGE_LOW_MID_REC(WarningTier.ADVISORY)` at ordinal 10 (between `BATTERY_LOW` and `THERMAL_SEVERE`). Update KDoc row count (16 → 17).
- `app/src/main/java/com/aritr/rova/ui/warnings/WarningPrecedence.kt` — `resolve(...)` gains `storageLowMidRec: Boolean` (last param); add the new branch between `BATTERY_LOW` and `THERMAL_SEVERE`.
- `app/src/main/java/com/aritr/rova/ui/warnings/WarningCenterViewModel.kt` — `aggregate(...)` gains a 10th source flow `storageLowMidRec: StateFlow<Boolean>` (last param); rename `Bools5` → `Bools6` adding the new boolean.
- `app/src/main/java/com/aritr/rova/ui/warnings/WarningCenter.kt` — `WarningCenter` gains `hudState: RecordHudState` + `onStopRecording: () -> Unit` parameters; idle vs. active rendering branch; new `WarningTopBanner` composable + `TopBannerContent` data class + `midRecBannerContent(id)` helper; the existing `warningSurfaceFor(id)` gains one arm (`STORAGE_LOW_MID_REC → TopBanner`); `warningSheetContent(id)` gains one defensive arm (unreachable — see Task 4); VM-factory wiring inside `WarningCenter` adds the `app.storageLowMidRecSignal.isLow` 10th argument.
- `app/src/main/java/com/aritr/rova/RovaApp.kt` — instantiate `StorageLowMidRecSignal.forContext(this)` in `onCreate()`; expose as `val storageLowMidRecSignal`.
- `app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt` — replace the legacy active-state body (`SessionTimer` / `ClipProgressBand` / `WaitingCountdown` / `MergingProgressBand` / `RecordStatusStrip`) with the new active-when-arm (`Column { WarningCenter(hudState, onStopRecording); RecordActiveHud(...) }`); add a new `LaunchedEffect(hudState, duration, resolution)` that calls `storageLowMidRecSignal.poll(...)` every 30 s while active and `clear()` on Idle; add the idle-side `WarningCenter(hudState = Idle, onStopRecording = {})` mount; thread `viewModel.stopRecording` (or the equivalent stop callback already used by the bottom-nav Stop-FAB) into both mounts.
- `app/src/test/java/com/aritr/rova/ui/warnings/WarningIdOrderTest.kt` — update to pin the 17-row order; the new row pinned at ordinal `10`.
- `app/src/test/java/com/aritr/rova/ui/warnings/WarningPrecedenceTest.kt` — add 6 new tests (Task 5).
- `app/src/test/java/com/aritr/rova/ui/warnings/WarningCenterAggregateTest.kt` — add 2 new tests (Task 5).
- `app/src/test/java/com/aritr/rova/ui/warnings/WarningSurfaceTest.kt` — add 1 new arm in the `expectedSurface` `when` (Task 6).
- `app/src/test/java/com/aritr/rova/ui/warnings/WarningSheetContentTest.kt` — add 1 defensive test for the new unreachable arm (Task 4).
- `docs/adr/0007-record-warning-sheets.md` — append `## Amendment 2026-05-13 — R2: mid-rec top-banner live, +1 row (STORAGE_LOW_MID_REC)`.
- `docs/WarningCenterContract.md` — 16-row table → 17-row table inside the existing Phase-1.D revision block; §7 producer notes append one line for `StorageLowMidRecSignal`.
- `NEW_UI_BACKEND_REPLAN.md` — §2.1 + row-count refs in §6 update from 16 to 17 rows; new pointer line for the R2 spec.
- `docs/architecture.md` — file-tree update.

**Deleted:**
- `app/src/main/java/com/aritr/rova/ui/components/SessionTimer.kt`
- `app/src/main/java/com/aritr/rova/ui/components/ClipProgressBand.kt`
- `app/src/main/java/com/aritr/rova/ui/components/WaitingCountdown.kt`
- `app/src/main/java/com/aritr/rova/ui/components/MergingProgressBand.kt`
- `app/src/main/java/com/aritr/rova/ui/components/RecordStatusStrip.kt`
- *(None of these have a `*Test.kt` companion on master — verified at plan-write time; if any test file shows up against `app/src/test/java/com/aritr/rova/ui/components/` named after one of the five during implementation, delete it too.)*

**Untouched (audit):** `RecordHudState.kt`, `RecordHudFormatters.kt`, `MergeCompleteCard.kt`, all R1 idle composables in `RecordChrome.kt`, the R1 "preserve verbatim" list in `RecordScreen.kt` (per spec §4.4), all `service/**` and `data/**`, `app/build.gradle.kts`.

---

## Phase A — Pure helpers (TDD)

### Task 1: `loopPillContent()` — loop-pill text rule

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt` (append the helper near the existing R1 helpers; do not yet add `LoopPill`/`StatusPill` composables — those land in Task 8)
- Create: `app/src/test/java/com/aritr/rova/ui/screens/RecordActiveHudFormattersTest.kt` (this test file will also gain `hudStatusPillContent` cases in Task 2 — leave room)

- [ ] **Step 1: Write the failing test**

```kotlin
package com.aritr.rova.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordActiveHudFormattersTest {

    // ── loopPillContent ─────────────────────────────────────────────

    @Test fun loop_singleClip_isHidden() {
        assertNull(loopPillContent(loopIndex = 0, loopTotal = 1))
    }

    @Test fun loop_indefinite_omitsTotal() {
        assertEquals("3 loops done", loopPillContent(loopIndex = 3, loopTotal = -1))
    }

    @Test fun loop_indefinite_clampsNegativeIndex() {
        assertEquals("0 loops done", loopPillContent(loopIndex = -2, loopTotal = -1))
    }

    @Test fun loop_finite_formatsSlash() {
        assertEquals("4/10 loops done", loopPillContent(loopIndex = 4, loopTotal = 10))
    }

    @Test fun loop_finite_clampsOverflowIndex() {
        assertEquals("10/10 loops done", loopPillContent(loopIndex = 12, loopTotal = 10))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

`(./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.RecordActiveHudFormattersTest" 2>&1; echo "EXIT=$?") > /tmp/g.log 2>&1; tail -40 /tmp/g.log`
Expected: FAIL — `loopPillContent` unresolved.

- [ ] **Step 3: Write the implementation**

Append to `app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt`:

```kotlin
// ── R2 active-HUD helpers (Phase A). Composables that consume these land in Task 8.

/**
 * R2 — loop-pill text. Returns `null` when there's only one clip (single-clip sessions hide
 * the pill entirely; the status-pill alone carries the state). Indefinite sessions
 * (`loopTotal < 0`) render the index without a total. The index is clamped on both ends.
 */
internal fun loopPillContent(loopIndex: Int, loopTotal: Int): String? = when {
    loopTotal == 1 -> null
    loopTotal < 0  -> "${loopIndex.coerceAtLeast(0)} loops done"
    else           -> "${loopIndex.coerceIn(0, loopTotal)}/$loopTotal loops done"
}
```

- [ ] **Step 4: Run test to verify it passes**

`(./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.RecordActiveHudFormattersTest" 2>&1; echo "EXIT=$?") > /tmp/g.log 2>&1; tail -40 /tmp/g.log`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```
git add app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt \
        app/src/test/java/com/aritr/rova/ui/screens/RecordActiveHudFormattersTest.kt
git commit -m "feat(record): add loopPillContent() helper for the R2 active-HUD loop pill

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: `hudStatusPillContent()` — status-pill content per HUD state

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt` (append after `loopPillContent`)
- Modify: `app/src/test/java/com/aritr/rova/ui/screens/RecordActiveHudFormattersTest.kt` (append cases)

- [ ] **Step 1: Append the failing tests**

Append the section below to the existing test class (after the loop-pill tests):

```kotlin
    // ── hudStatusPillContent ────────────────────────────────────────

    @Test fun status_recording_red_with_clip_countdown() {
        val c = hudStatusPillContent(
            state = com.aritr.rova.ui.components.RecordHudState.Recording,
            clipSecondsLeft = 18, waitSecondsLeft = 0,
        )
        assertEquals(StatusDotColor.RECORDING, c.dot)
        assertEquals("Recording", c.main)
        assertEquals("· 0:18 left", c.time)
    }

    @Test fun status_waiting_amber_with_next_in_countdown() {
        val c = hudStatusPillContent(
            state = com.aritr.rova.ui.components.RecordHudState.Waiting,
            clipSecondsLeft = 0, waitSecondsLeft = 42,
        )
        assertEquals(StatusDotColor.BREAK, c.dot)
        assertEquals("On break", c.main)
        assertEquals("· next in 0:42", c.time)
    }

    @Test fun status_merging_blue_with_percent() {
        val c = hudStatusPillContent(
            state = com.aritr.rova.ui.components.RecordHudState.Merging(
                progress = 0.534f, currentSegment = 3, totalSegments = 6,
            ),
            clipSecondsLeft = 0, waitSecondsLeft = 0,
        )
        assertEquals(StatusDotColor.MERGING, c.dot)
        assertEquals("Merging…", c.main)
        assertEquals("· 53%", c.time)
    }

    @Test fun status_merging_zeroProgress_is_0_percent() {
        val c = hudStatusPillContent(
            state = com.aritr.rova.ui.components.RecordHudState.Merging(
                progress = 0f, currentSegment = 0, totalSegments = 0,
            ),
            clipSecondsLeft = 0, waitSecondsLeft = 0,
        )
        assertEquals("· 0%", c.time)
    }

    @Test fun status_merging_clamps_to_100() {
        // progress > 1 shouldn't happen but the helper should clamp defensively.
        val c = hudStatusPillContent(
            state = com.aritr.rova.ui.components.RecordHudState.Merging(
                progress = 1.7f, currentSegment = 0, totalSegments = 0,
            ),
            clipSecondsLeft = 0, waitSecondsLeft = 0,
        )
        assertEquals("· 100%", c.time)
    }

    @Test(expected = IllegalStateException::class) fun status_idle_throws_caller_bug() {
        hudStatusPillContent(
            state = com.aritr.rova.ui.components.RecordHudState.Idle,
            clipSecondsLeft = 0, waitSecondsLeft = 0,
        )
    }
```

- [ ] **Step 2: Run tests to verify they fail**

`(./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.RecordActiveHudFormattersTest" 2>&1; echo "EXIT=$?") > /tmp/g.log 2>&1; tail -40 /tmp/g.log`
Expected: FAIL — `hudStatusPillContent` / `StatusDotColor` / `StatusPillContent` unresolved.

- [ ] **Step 3: Write the implementation**

Append to `app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt` (importing `RecordHudState` from `com.aritr.rova.ui.components` and `mmss` from `com.aritr.rova.ui.components` if not already imported):

```kotlin
import com.aritr.rova.ui.components.RecordHudState
import com.aritr.rova.ui.components.mmss        // reuse the R1 mm:ss helper

internal enum class StatusDotColor { RECORDING, BREAK, MERGING }

internal data class StatusPillContent(
    val dot: StatusDotColor,
    val main: String,
    val time: String,
)

/**
 * R2 — status-pill content per HUD state. Pure. `clipSecondsLeft` / `waitSecondsLeft`
 * come from RecordScreen's existing `produceState` timers (R1 preserve list); the
 * helper takes them as ints rather than reading off `RecordHudState`, which holds no
 * countdown fields. Idle is a caller bug — the active HUD must not be mounted at idle.
 */
internal fun hudStatusPillContent(
    state: RecordHudState,
    clipSecondsLeft: Int,
    waitSecondsLeft: Int,
): StatusPillContent = when (state) {
    RecordHudState.Recording -> StatusPillContent(
        dot = StatusDotColor.RECORDING,
        main = "Recording",
        time = "· ${mmss(clipSecondsLeft)} left",
    )
    RecordHudState.Waiting -> StatusPillContent(
        dot = StatusDotColor.BREAK,
        main = "On break",
        time = "· next in ${mmss(waitSecondsLeft)}",
    )
    is RecordHudState.Merging -> StatusPillContent(
        dot = StatusDotColor.MERGING,
        main = "Merging…",
        time = "· ${(state.progress * 100).toInt().coerceIn(0, 100)}%",
    )
    RecordHudState.Idle ->
        error("hudStatusPillContent called with Idle — caller bug; gate on hudState != Idle")
}
```

> If the `mmss` helper isn't named exactly `mmss` in `RecordHudFormatters.kt`, match the real symbol. The R1 plan added an `mmss(seconds: Int): String` helper there; verify via `Grep -n "^internal fun mmss" app/src/main/java/com/aritr/rova/ui/components/RecordHudFormatters.kt`. If the existing helper has a different name, adapt the import + call sites; do NOT add a duplicate helper.

- [ ] **Step 4: Run tests to verify they pass**

`(./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.RecordActiveHudFormattersTest" 2>&1; echo "EXIT=$?") > /tmp/g.log 2>&1; tail -40 /tmp/g.log`
Expected: PASS (11 tests in the class).

- [ ] **Step 5: Commit**

```
git add app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt \
        app/src/test/java/com/aritr/rova/ui/screens/RecordActiveHudFormattersTest.kt
git commit -m "feat(record): add hudStatusPillContent() helper for the R2 active-HUD status pill

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Phase B — New leaf signal (TDD)

### Task 3: `StorageLowMidRecSignal` + RovaApp wiring

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/signals/StorageLowMidRecSignal.kt`
- Create: `app/src/test/java/com/aritr/rova/ui/signals/StorageLowMidRecSignalTest.kt`
- Modify: `app/src/main/java/com/aritr/rova/RovaApp.kt` — instantiate + expose the signal (one new line in `onCreate()`, one new `val`).

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/aritr/rova/ui/signals/StorageLowMidRecSignalTest.kt`:

```kotlin
package com.aritr.rova.ui.signals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageLowMidRecSignalTest {

    @Test fun pollAboveThreshold_isFalse() {
        val s = StorageLowMidRecSignal(computeIsLow = { _, _ -> false })
        s.poll(durationSeconds = 30, resolution = "1080p")
        assertFalse(s.isLow.value)
    }

    @Test fun pollBelowThreshold_isTrue() {
        val s = StorageLowMidRecSignal(computeIsLow = { _, _ -> true })
        s.poll(durationSeconds = 30, resolution = "1080p")
        assertTrue(s.isLow.value)
    }

    @Test fun computeLambda_receivesCallerSettings() {
        var lastDur = -1
        var lastRes = ""
        val s = StorageLowMidRecSignal(computeIsLow = { d, r ->
            lastDur = d; lastRes = r; false
        })
        s.poll(durationSeconds = 45, resolution = "4K")
        assertEquals(45, lastDur)
        assertEquals("4K", lastRes)
    }

    @Test fun clear_resetsToFalse() {
        val s = StorageLowMidRecSignal(computeIsLow = { _, _ -> true })
        s.poll(durationSeconds = 30, resolution = "1080p")
        assertTrue(s.isLow.value)
        s.clear()
        assertFalse(s.isLow.value)
    }

    @Test fun pollTransitions_bothDirections() {
        var low = true
        val s = StorageLowMidRecSignal(computeIsLow = { _, _ -> low })
        s.poll(30, "1080p"); assertTrue(s.isLow.value)
        low = false
        s.poll(30, "1080p"); assertFalse(s.isLow.value)
        low = true
        s.poll(30, "1080p"); assertTrue(s.isLow.value)
    }

    @Test fun initialState_isFalse() {
        val s = StorageLowMidRecSignal(computeIsLow = { _, _ -> true })
        // No poll() yet — the StateFlow seed is always false (the recording isn't running).
        assertFalse(s.isLow.value)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

`(./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.signals.StorageLowMidRecSignalTest" 2>&1; echo "EXIT=$?") > /tmp/g.log 2>&1; tail -40 /tmp/g.log`
Expected: FAIL — `StorageLowMidRecSignal` unresolved.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/aritr/rova/ui/signals/StorageLowMidRecSignal.kt`:

```kotlin
package com.aritr.rova.ui.signals

import android.content.Context
import android.os.StatFs
import com.aritr.rova.RovaApp
import com.aritr.rova.data.StorageEstimator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * R2 (NEW_UI_BACKEND_REPLAN row 17) — leaf signal: is the device's free space running
 * low MID-RECORDING? Drives the `STORAGE_LOW_MID_REC` warning, surfaced as the
 * mid-recording top banner (ADR 0007 amendment 2026-05-13).
 *
 * Distinct from [StorageSignal]: that one is the START-time hard-block (`STORAGE_INSUFFICIENT`),
 * comparing whole-session peak against free bytes. This signal is the running-low
 * advisory — fires when free bytes drop below 3 × bytes-per-clip-at-current-quality
 * while a session is active. Threshold is deliberately conservative: by the time the
 * banner fires the user has roughly three clips of headroom, the per-segment storage
 * gate in the service stays the authoritative backstop once the threshold is crossed.
 *
 * Hysteresis: none. If `freeBytes` oscillates around the threshold the banner flickers;
 * acknowledged tradeoff — fix is the future 4.1c snooze/hysteresis bundle.
 *
 * The host (RecordScreen) calls [poll] every ~30 s while HUD state ∈ {Recording, Waiting,
 * Merging} and calls [clear] on the transition back to Idle. No `service/**` diff —
 * StatFs and [StorageEstimator.bytesPerSecondForResolution] are read-only from UI.
 */
class StorageLowMidRecSignal(
    private val computeIsLow: (durationSeconds: Int, resolution: String) -> Boolean,
) {
    private val _isLow: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val isLow: StateFlow<Boolean> = _isLow.asStateFlow()

    /** Re-run the estimate for the current clip settings. Idempotent. */
    fun poll(durationSeconds: Int, resolution: String) {
        _isLow.value = computeIsLow(durationSeconds, resolution)
    }

    /** Clear back to `false` when leaving active states. */
    fun clear() {
        _isLow.value = false
    }

    companion object {
        const val LOW_THRESHOLD_CLIPS: Int = 3

        fun forContext(context: Context): StorageLowMidRecSignal {
            val app = context.applicationContext
            return StorageLowMidRecSignal(computeIsLow = { dur, res ->
                estimateIsLow(app, dur, res)
            })
        }

        /**
         * StatFs read on the calling thread. Acceptable here — the caller is RecordScreen's
         * 30-s poll inside a `LaunchedEffect`, not the pre-FGS critical path. Null root /
         * StatFs error → returns `false` (don't false-warn — same fail-closed shape as
         * [StorageSignal]).
         */
        private fun estimateIsLow(app: Context, durationSeconds: Int, resolution: String): Boolean {
            return try {
                val rovaApp = app as? RovaApp ?: return false
                val path = rovaApp.externalRoot?.absolutePath ?: return false
                val stat = StatFs(path)
                val available = stat.availableBlocksLong * stat.blockSizeLong
                val bytesPerClip =
                    StorageEstimator.bytesPerSecondForResolution(resolution) * durationSeconds
                available < LOW_THRESHOLD_CLIPS * bytesPerClip
            } catch (_: Exception) {
                false
            }
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

`(./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.signals.StorageLowMidRecSignalTest" 2>&1; echo "EXIT=$?") > /tmp/g.log 2>&1; tail -40 /tmp/g.log`
Expected: PASS (6 tests).

- [ ] **Step 5: Wire the signal on `RovaApp`**

Open `app/src/main/java/com/aritr/rova/RovaApp.kt`. Locate the existing signal `val`s (one named `storageSignal`, `cameraPermissionSignal`, etc.) and the `onCreate()` where they're instantiated. Mirror the existing pattern.

Add to the property block (next to `lateinit var storageSignal: StorageSignal` or the equivalent):

```kotlin
lateinit var storageLowMidRecSignal: StorageLowMidRecSignal
    private set
```

Add to `onCreate()` (next to `storageSignal = StorageSignal.forContext(this)` or wherever R1's storage signal is bootstrapped):

```kotlin
storageLowMidRecSignal = StorageLowMidRecSignal.forContext(this)
```

(If the existing signals use a different idiom — e.g. eager `val storageSignal = StorageSignal.forContext(this)` resolved at class init — match that idiom. Read 10 lines around the existing storage signal instantiation before writing this line.)

- [ ] **Step 6: Verify the app still builds**

`(./gradlew :app:assembleDebug 2>&1; echo "EXIT=$?") > /tmp/g.log 2>&1; tail -40 /tmp/g.log`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```
git add app/src/main/java/com/aritr/rova/ui/signals/StorageLowMidRecSignal.kt \
        app/src/test/java/com/aritr/rova/ui/signals/StorageLowMidRecSignalTest.kt \
        app/src/main/java/com/aritr/rova/RovaApp.kt
git commit -m "feat(signals): add StorageLowMidRecSignal — mid-recording free-bytes advisory

Polls StatFs vs. 3 × bytes-per-clip while the host is in an active HUD state.
RovaApp exposes the signal; RecordScreen drives poll()/clear() in a follow-up.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Phase C — Enum + precedence-VM expansion (coupled rippling change)

### Task 4: Add `STORAGE_LOW_MID_REC` enum row + `WarningIdOrderTest` pin + defensive `warningSheetContent` arm

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/warnings/WarningId.kt`
- Modify: `app/src/test/java/com/aritr/rova/ui/warnings/WarningIdOrderTest.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/warnings/WarningCenter.kt` — `warningSheetContent(id)` is exhaustive over the enum (Kotlin `when` with no `else`); adding a row REQUIRES adding the new arm. The new arm is defensive (unreachable — TopBanner-only) but must compile.
- Modify: `app/src/test/java/com/aritr/rova/ui/warnings/WarningSheetContentTest.kt` — add 1 defensive test for the new arm.

> This task lands ONLY the enum row, the WarningIdOrderTest pin, the defensive warningSheetContent arm, and its test. Building/testing must still pass at this commit. The precedence-VM and `aggregate` changes that consume the new row are Task 5; the `warningSurfaceFor` arm and the top-banner content map are Task 6. Keeping these split simplifies the per-task review.

- [ ] **Step 1: Read the current `WarningIdOrderTest` to learn the assertion style**

`(./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.warnings.WarningIdOrderTest" 2>&1; echo "EXIT=$?") > /tmp/g.log 2>&1; tail -40 /tmp/g.log`

Use the `Read` tool on `app/src/test/java/com/aritr/rova/ui/warnings/WarningIdOrderTest.kt` to capture the existing 16-row assertion shape. It likely pins each id by ordinal (e.g. `assertEquals(0, WarningId.CAMERA_PERMISSION_DENIED.ordinal)`) and a 16-row `assertEquals(16, WarningId.entries.size)` line.

- [ ] **Step 2: Update the order test to expect 17 rows with the new row at ordinal 10**

Modify `WarningIdOrderTest.kt`. The 16 carry-over rows' ordinals 0..9 stay; the 6 carry-over rows previously at ordinals 10..15 now shift to 11..16; `STORAGE_LOW_MID_REC` pins at ordinal 10:

```kotlin
@Test fun ordering_matches_replan_table() {
    // 17 rows total (R2 amendment 2026-05-13 added STORAGE_LOW_MID_REC at ordinal 10).
    assertEquals(17, WarningId.entries.size)

    assertEquals(0,  WarningId.CAMERA_PERMISSION_DENIED.ordinal)
    assertEquals(1,  WarningId.EXACT_ALARM_DENIED.ordinal)
    assertEquals(2,  WarningId.STORAGE_INSUFFICIENT.ordinal)
    assertEquals(3,  WarningId.THERMAL_SHUTDOWN.ordinal)
    assertEquals(4,  WarningId.THERMAL_EMERGENCY.ordinal)
    assertEquals(5,  WarningId.THERMAL_CRITICAL.ordinal)
    assertEquals(6,  WarningId.BATTERY_CRITICAL.ordinal)
    assertEquals(7,  WarningId.CAMERA_IN_USE.ordinal)
    assertEquals(8,  WarningId.CAMERA_DISABLED.ordinal)
    assertEquals(9,  WarningId.BATTERY_LOW.ordinal)
    assertEquals(10, WarningId.STORAGE_LOW_MID_REC.ordinal)   // NEW — R2
    assertEquals(11, WarningId.THERMAL_SEVERE.ordinal)         // was 10
    assertEquals(12, WarningId.MICROPHONE_DENIED.ordinal)      // was 11
    assertEquals(13, WarningId.BATTERY_OPTIMIZATION_ON.ordinal)// was 12
    assertEquals(14, WarningId.POWER_SAVE_MODE.ordinal)        // was 13
    assertEquals(15, WarningId.THERMAL_MODERATE.ordinal)       // was 14
    assertEquals(16, WarningId.NOTIFICATIONS_DENIED.ordinal)   // was 15
}
```

> If the existing test file groups assertions in a different shape (e.g. one test per row, or a `for ((id, expected) in zip)` walk), match the existing pattern verbatim and just slot the new row + bump the count. The goal is "17 rows, new one at 10".

- [ ] **Step 3: Run the order test — verify it fails (compile error or wrong-ordinal)**

`(./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.warnings.WarningIdOrderTest" 2>&1; echo "EXIT=$?") > /tmp/g.log 2>&1; tail -40 /tmp/g.log`
Expected: FAIL — `STORAGE_LOW_MID_REC` unresolved (or, if the compiler tolerates the missing enum value somehow, an ordinal mismatch).

- [ ] **Step 4: Add the enum row**

Modify `app/src/main/java/com/aritr/rova/ui/warnings/WarningId.kt`. Insert `STORAGE_LOW_MID_REC` between `BATTERY_LOW` (current #10) and `THERMAL_SEVERE` (current #11). Update the KDoc row count and the `Phase 4.1b` line.

```kotlin
package com.aritr.rova.ui.warnings

/**
 * The 17 Record-screen warning rows, in PRECEDENCE ORDER (highest first). Declaration
 * order IS the contract — [WarningPrecedence.resolve] returns the first active one, and
 * `WarningIdOrderTest` pins the ordinals so a reorder cannot slip through review.
 *
 * Order mirrors NEW_UI_BACKEND_REPLAN.md's "Banner precedence" table (owner-signed
 * 2026-05-11, amended 2026-05-13 with row 11 STORAGE_LOW_MID_REC — ADR 0007 amendment).
 * As of R2 all 17 rows are reachable from [WarningPrecedence.resolve].
 *
 * [gatesStart] = this warning, while active, disables the Record screen's Start button
 * (Phase 4.1b). Only `CAMERA_PERMISSION_DENIED` and `STORAGE_INSUFFICIENT` do —
 * `EXACT_ALARM_DENIED` is `HARD_BLOCK`-tier (chrome) but does NOT gate Start (recording
 * still runs, on inexact alarms). `gatesStart` and `tier` are orthogonal: `tier` is
 * banner chrome, `gatesStart` is behavior. The Record screen does NOT read `gatesStart`
 * off the *resolved* warning (a higher-priority non-gating warning can be showing while
 * a gating one is also active); it reads the underlying signals directly. `gatesStart`
 * is documentation here and a pin in `WarningIdOrderTest`.
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
    STORAGE_LOW_MID_REC(WarningTier.ADVISORY),          // #11  ← NEW (R2 — ADR 0007 amendment 2026-05-13)
    THERMAL_SEVERE(WarningTier.ADVISORY),               // #12
    MICROPHONE_DENIED(WarningTier.ADVISORY),            // #13
    BATTERY_OPTIMIZATION_ON(WarningTier.ADVISORY),      // #14
    POWER_SAVE_MODE(WarningTier.ADVISORY),              // #15
    THERMAL_MODERATE(WarningTier.ADVISORY),             // #16
    NOTIFICATIONS_DENIED(WarningTier.ADVISORY)          // #17
}

/** Visual tier for the banner chrome. NOT the priority axis — that is [WarningId.ordinal]. */
enum class WarningTier { HARD_BLOCK, CRITICAL, ADVISORY }
```

- [ ] **Step 5: Build → reveals the missing exhaustive arms**

`(./gradlew :app:assembleDebug 2>&1; echo "EXIT=$?") > /tmp/g.log 2>&1; tail -60 /tmp/g.log`
Expected: BUILD FAILED with `e: ... when expression must be exhaustive, add necessary 'STORAGE_LOW_MID_REC' branch or 'else' branch instead`. Two sites are affected by the enum addition:
- `app/src/main/java/com/aritr/rova/ui/warnings/WarningCenter.kt` — `warningSheetContent(id)` (covered in Step 6 below).
- `app/src/test/java/com/aritr/rova/ui/warnings/WarningSurfaceTest.kt` — `expectedSurface(id)` (covered in Step 6a below).

`WarningPrecedence.resolve(...)` does NOT `when` on `WarningId`, so it isn't affected here.

- [ ] **Step 6: Add the defensive `warningSheetContent` arm**

In `app/src/main/java/com/aritr/rova/ui/warnings/WarningCenter.kt`, locate `internal fun warningSheetContent(id: WarningId): WarningSheetContent = when (id) { ... }`. Insert the new arm (anywhere — convention is to keep arms in enum order, so place it after `WarningId.BATTERY_LOW`):

```kotlin
    WarningId.STORAGE_LOW_MID_REC -> WarningSheetContent(
        // Defensive — STORAGE_LOW_MID_REC is TopBanner-only (see midRecBannerContent in Task 6).
        // This arm exists to keep warningSheetContent exhaustive; it should never render as a sheet.
        Icons.Default.Storage, "Storage running low",
        "Free space on this device.",
        WarningAction("OK", ActionTarget.APP_DETAILS_SETTINGS),
        null,
    )
```

> `Icons.Default.Storage` is already imported at the top of `WarningCenter.kt` (used by the existing `STORAGE_INSUFFICIENT` arm); no new import.

- [ ] **Step 6a: Update `WarningSurfaceTest`'s `expectedSurface` `when` to include the new row**

The test-side exhaustive `when` over `WarningId` is broken by the enum addition. Update the existing `expectedSurface` private helper in `app/src/test/java/com/aritr/rova/ui/warnings/WarningSurfaceTest.kt` to add the new arm to the `TopBanner` cluster:

```kotlin
    private fun expectedSurface(id: WarningId): WarningSurface = when (id) {
        WarningId.CAMERA_PERMISSION_DENIED, WarningId.EXACT_ALARM_DENIED, WarningId.STORAGE_INSUFFICIENT -> WarningSurface.HardBlockSheet
        WarningId.MICROPHONE_DENIED -> WarningSurface.SoftSheet
        WarningId.NOTIFICATIONS_DENIED, WarningId.BATTERY_OPTIMIZATION_ON, WarningId.POWER_SAVE_MODE -> WarningSurface.AdvisorySheet
        WarningId.THERMAL_SHUTDOWN, WarningId.THERMAL_EMERGENCY, WarningId.THERMAL_CRITICAL, WarningId.THERMAL_SEVERE, WarningId.THERMAL_MODERATE,
        WarningId.BATTERY_CRITICAL, WarningId.BATTERY_LOW, WarningId.CAMERA_IN_USE, WarningId.CAMERA_DISABLED,
        WarningId.STORAGE_LOW_MID_REC -> WarningSurface.TopBanner     // ← NEW arm (mirrors prod warningSurfaceFor in Task 6)
    }
```

> **Important.** The production `warningSurfaceFor(id)` in `WarningCenter.kt` is NOT updated in this task (Task 4) — it's deferred to Task 6. The test's `expectedSurface` arm being added here will then FAIL the test (because the prod function still returns the wrong value or fails to compile if you add the arm in the test ahead of prod). To keep this task's commit green:
>
> EITHER
> (a) Also add the production `warningSurfaceFor` arm in this task (Task 4) Step 6 — keep the helper change atomic with the enum change. Then Task 6 only adds `midRecBannerContent` + `MidRecBannerContentTest` + the WarningSurfaceTest `+1` test method that asserts `STORAGE_LOW_MID_REC → TopBanner`. **(Recommended — cleaner per-commit story.)**
> OR
> (b) Skip Step 6a here; let the build fail at Task 4 Step 9 and pick up the WarningSurfaceTest arm in Task 6.
>
> Going with (a) — append this to Step 6 below in the same commit:
>
> ```kotlin
> // Production warningSurfaceFor(id) in WarningCenter.kt:
> fun warningSurfaceFor(id: WarningId): WarningSurface = when (id) {
>     WarningId.CAMERA_PERMISSION_DENIED, WarningId.EXACT_ALARM_DENIED, WarningId.STORAGE_INSUFFICIENT -> WarningSurface.HardBlockSheet
>     WarningId.MICROPHONE_DENIED -> WarningSurface.SoftSheet
>     WarningId.NOTIFICATIONS_DENIED, WarningId.BATTERY_OPTIMIZATION_ON, WarningId.POWER_SAVE_MODE -> WarningSurface.AdvisorySheet
>     WarningId.THERMAL_SHUTDOWN, WarningId.THERMAL_EMERGENCY, WarningId.THERMAL_CRITICAL, WarningId.THERMAL_SEVERE, WarningId.THERMAL_MODERATE,
>     WarningId.BATTERY_CRITICAL, WarningId.BATTERY_LOW, WarningId.CAMERA_IN_USE, WarningId.CAMERA_DISABLED,
>     WarningId.STORAGE_LOW_MID_REC -> WarningSurface.TopBanner       // ← NEW (production)
> }
> ```
>
> If you take (a), Task 6's Step 1 (the test-side WarningSurfaceTest arm) is already done — skip it.

- [ ] **Step 7: Add a defensive test for the new arm**

Modify `app/src/test/java/com/aritr/rova/ui/warnings/WarningSheetContentTest.kt`. Locate the existing test class and append:

```kotlin
    @Test fun storage_low_mid_rec_arm_returns_nonblank_defensive_content() {
        // Defensive — STORAGE_LOW_MID_REC is TopBanner-only; this exists to keep
        // warningSheetContent total over WarningId. The test checks that the arm
        // returns a non-blank, callable sheet content (never rendered as a sheet).
        val c = warningSheetContent(WarningId.STORAGE_LOW_MID_REC)
        assertFalse("title", c.title.isBlank())
        assertNotNull("icon", c.icon)
        assertEquals("OK", c.primary.label)
        assertNull("secondary should be null on TopBanner-only arm", c.secondary)
    }
```

Add the imports for `assertNull` / `assertNotNull` if they're not already present (`org.junit.Assert.assertNull`, `org.junit.Assert.assertNotNull`).

- [ ] **Step 8: Run the test classes**

`(./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.warnings.WarningIdOrderTest" --tests "com.aritr.rova.ui.warnings.WarningSheetContentTest" 2>&1; echo "EXIT=$?") > /tmp/g.log 2>&1; tail -60 /tmp/g.log`
Expected: both PASS. `WarningIdOrderTest` should be 1 test, `WarningSheetContentTest` should be (existing count + 1).

The PRECEDENCE-VM and AGGREGATE tests will still pass because they reference only the 9 source flows / 9 resolve params Step 4–7 didn't touch.

> Note: `WarningPrecedenceTest` calls `resolve(...)` — adding the new enum row alone doesn't break `resolve`'s signature, so those tests stay green. The `resolve` signature change is the explicit subject of Task 5.

- [ ] **Step 9: Full test gate — confirm everything still passes**

`(./gradlew :app:testDebugUnitTest 2>&1; echo "EXIT=$?") > /tmp/g.log 2>&1; tail -40 /tmp/g.log`
Expected: BUILD SUCCESSFUL, 735 + 1 = **736 tests / 60 classes / 0-0-0** (or 735 + 1 + however many you added to `WarningIdOrderTest` if you split the single-`assertEquals(17, …)` test into multiple).

- [ ] **Step 10: Commit**

```
git add app/src/main/java/com/aritr/rova/ui/warnings/WarningId.kt \
        app/src/main/java/com/aritr/rova/ui/warnings/WarningCenter.kt \
        app/src/test/java/com/aritr/rova/ui/warnings/WarningIdOrderTest.kt \
        app/src/test/java/com/aritr/rova/ui/warnings/WarningSheetContentTest.kt \
        app/src/test/java/com/aritr/rova/ui/warnings/WarningSurfaceTest.kt
git commit -m "feat(warnings): add STORAGE_LOW_MID_REC at ordinal 10 (R2)

Inserts the new advisory-tier row between BATTERY_LOW and THERMAL_SEVERE.
WarningIdOrderTest pins the 17-row order; warningSheetContent gains a
defensive arm (TopBanner-only — never renders as a sheet) to keep the
exhaustive when callable, with a guard test; warningSurfaceFor adds the
STORAGE_LOW_MID_REC -> TopBanner arm to its existing cluster, mirrored
in WarningSurfaceTest's expectedSurface helper. Precedence-VM
consumption + midRecBannerContent land in the next commits.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Extend `WarningPrecedence.resolve(...)` + `WarningCenterViewModel.aggregate(...)` + WarningCenter VM-factory wiring

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/warnings/WarningPrecedence.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/warnings/WarningCenterViewModel.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/warnings/WarningCenter.kt` — the file-local `viewModelFactory { initializer { WarningCenterViewModel(...) } }` block grows one argument.
- Modify: `app/src/test/java/com/aritr/rova/ui/warnings/WarningPrecedenceTest.kt` — +6 new tests.
- Modify: `app/src/test/java/com/aritr/rova/ui/warnings/WarningCenterAggregateTest.kt` — +2 new tests.

> This is the coupled rippling change. The resolve signature + every existing call site must move together so the build never breaks at any commit step.

- [ ] **Step 1: Append the failing precedence tests**

Append to `app/src/test/java/com/aritr/rova/ui/warnings/WarningPrecedenceTest.kt` (read it first to learn the existing helper / `WarningPrecedence.resolve(all-good-state).copy(...)` style and reuse it; if the existing tests pass arguments positionally, the new ones do the same — the new param is the LAST positional argument):

```kotlin
    // ── R2 — STORAGE_LOW_MID_REC (ordinal 10) ────────────────────────

    @Test fun storage_low_mid_rec_alone_fires() {
        // All other signals at their "OK" values; storageLowMidRec=true → returns STORAGE_LOW_MID_REC.
        val id = WarningPrecedence.resolve(
            cameraPermissionGranted = true,
            exactAlarmGranted = true,
            storageInsufficient = false,
            thermal = com.aritr.rova.ui.signals.ThermalStatus.NONE,
            power = okPower(),
            camera = com.aritr.rova.ui.signals.CameraSignalState.OK,
            microphonePermissionGranted = true,
            notificationsGranted = true,
            batteryOptimizationExempt = true,
            storageLowMidRec = true,
        )
        assertEquals(WarningId.STORAGE_LOW_MID_REC, id)
    }

    @Test fun storage_low_mid_rec_outranks_thermal_severe() {
        val id = WarningPrecedence.resolve(
            cameraPermissionGranted = true,
            exactAlarmGranted = true,
            storageInsufficient = false,
            thermal = com.aritr.rova.ui.signals.ThermalStatus.SEVERE,
            power = okPower(),
            camera = com.aritr.rova.ui.signals.CameraSignalState.OK,
            microphonePermissionGranted = true,
            notificationsGranted = true,
            batteryOptimizationExempt = true,
            storageLowMidRec = true,
        )
        assertEquals(WarningId.STORAGE_LOW_MID_REC, id)
    }

    @Test fun battery_low_outranks_storage_low_mid_rec() {
        // BATTERY_LOW is #10, STORAGE_LOW_MID_REC is #11; battery wins.
        val id = WarningPrecedence.resolve(
            cameraPermissionGranted = true,
            exactAlarmGranted = true,
            storageInsufficient = false,
            thermal = com.aritr.rova.ui.signals.ThermalStatus.NONE,
            power = com.aritr.rova.ui.signals.PowerState(percent = 14, charging = false, powerSaveMode = false),
            camera = com.aritr.rova.ui.signals.CameraSignalState.OK,
            microphonePermissionGranted = true,
            notificationsGranted = true,
            batteryOptimizationExempt = true,
            storageLowMidRec = true,
        )
        assertEquals(WarningId.BATTERY_LOW, id)
    }

    @Test fun thermal_critical_outranks_storage_low_mid_rec() {
        val id = WarningPrecedence.resolve(
            cameraPermissionGranted = true,
            exactAlarmGranted = true,
            storageInsufficient = false,
            thermal = com.aritr.rova.ui.signals.ThermalStatus.CRITICAL,
            power = okPower(),
            camera = com.aritr.rova.ui.signals.CameraSignalState.OK,
            microphonePermissionGranted = true,
            notificationsGranted = true,
            batteryOptimizationExempt = true,
            storageLowMidRec = true,
        )
        assertEquals(WarningId.THERMAL_CRITICAL, id)
    }

    @Test fun storage_low_mid_rec_outranks_mic_and_below_advisories() {
        val id = WarningPrecedence.resolve(
            cameraPermissionGranted = true,
            exactAlarmGranted = true,
            storageInsufficient = false,
            thermal = com.aritr.rova.ui.signals.ThermalStatus.NONE,
            power = com.aritr.rova.ui.signals.PowerState(percent = 80, charging = false, powerSaveMode = true),
            camera = com.aritr.rova.ui.signals.CameraSignalState.OK,
            microphonePermissionGranted = false,         // would fire #13
            notificationsGranted = false,                // would fire #17
            batteryOptimizationExempt = false,           // would fire #14
            storageLowMidRec = true,
        )
        assertEquals(WarningId.STORAGE_LOW_MID_REC, id)
    }

    @Test fun storage_low_mid_rec_false_does_not_fire_when_alone() {
        val id = WarningPrecedence.resolve(
            cameraPermissionGranted = true,
            exactAlarmGranted = true,
            storageInsufficient = false,
            thermal = com.aritr.rova.ui.signals.ThermalStatus.NONE,
            power = okPower(),
            camera = com.aritr.rova.ui.signals.CameraSignalState.OK,
            microphonePermissionGranted = true,
            notificationsGranted = true,
            batteryOptimizationExempt = true,
            storageLowMidRec = false,
        )
        assertNull(id)
    }

    // Helper — adjust to match the existing test file's "all-OK PowerState" if it has one already.
    private fun okPower() = com.aritr.rova.ui.signals.PowerState(percent = 80, charging = false, powerSaveMode = false)
```

> If the existing tests already define `okPower()`/equivalent in a top-level `private fun` or a companion, reuse it instead of redeclaring. If existing tests pass `power` as a named argument, do the same. If the `ThermalStatus` enum values aren't `NONE/SEVERE/CRITICAL` verbatim, match the real values — read `app/src/main/java/com/aritr/rova/ui/signals/ThermalStatusSignal.kt`.

- [ ] **Step 2: Append the failing aggregate tests**

Append to `app/src/test/java/com/aritr/rova/ui/warnings/WarningCenterAggregateTest.kt` (read it first to learn the existing flow-stub style — likely `MutableStateFlow` per source flow + `runTest { vm.activeWarning.first { it == ... }; ... }`):

```kotlin
    @Test fun storage_low_mid_rec_flow_flip_emits_id() = runTest {
        val storageLowMidRec = MutableStateFlow(false)
        val vm = WarningCenterViewModel.aggregate(
            cameraPermissionGranted = MutableStateFlow(true),
            exactAlarmGranted = MutableStateFlow(true),
            storageInsufficient = MutableStateFlow(false),
            thermal = MutableStateFlow(com.aritr.rova.ui.signals.ThermalStatus.NONE),
            power = MutableStateFlow(okPower()),
            camera = MutableStateFlow(com.aritr.rova.ui.signals.CameraSignalState.OK),
            microphonePermissionGranted = MutableStateFlow(true),
            notificationsGranted = MutableStateFlow(true),
            batteryOptimizationExempt = MutableStateFlow(true),
            storageLowMidRec = storageLowMidRec,
        )
        // Initial: no warning.
        assertNull(vm.activeWarning.first())
        storageLowMidRec.value = true
        assertEquals(WarningId.STORAGE_LOW_MID_REC, vm.activeWarning.first { it == WarningId.STORAGE_LOW_MID_REC })
        storageLowMidRec.value = false
        // Clearing returns to null when nothing else is active.
        assertNull(vm.activeWarning.first { it == null })
    }

    @Test fun aggregate_uses_storage_low_mid_rec_in_position() = runTest {
        // Battery low + storage low at the same time — battery wins (precedence #10 > #11).
        val vm = WarningCenterViewModel.aggregate(
            cameraPermissionGranted = MutableStateFlow(true),
            exactAlarmGranted = MutableStateFlow(true),
            storageInsufficient = MutableStateFlow(false),
            thermal = MutableStateFlow(com.aritr.rova.ui.signals.ThermalStatus.NONE),
            power = MutableStateFlow(com.aritr.rova.ui.signals.PowerState(percent = 14, charging = false, powerSaveMode = false)),
            camera = MutableStateFlow(com.aritr.rova.ui.signals.CameraSignalState.OK),
            microphonePermissionGranted = MutableStateFlow(true),
            notificationsGranted = MutableStateFlow(true),
            batteryOptimizationExempt = MutableStateFlow(true),
            storageLowMidRec = MutableStateFlow(true),
        )
        assertEquals(WarningId.BATTERY_LOW, vm.activeWarning.first { it != null })
    }
```

> Imports: `kotlinx.coroutines.flow.MutableStateFlow`, `kotlinx.coroutines.flow.first`, `kotlinx.coroutines.test.runTest`, `org.junit.Assert.assertEquals`, `org.junit.Assert.assertNull`. Match whatever the existing tests use.

- [ ] **Step 3: Run the tests — expect compile/resolve failures**

`(./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.warnings.WarningPrecedenceTest" --tests "com.aritr.rova.ui.warnings.WarningCenterAggregateTest" 2>&1; echo "EXIT=$?") > /tmp/g.log 2>&1; tail -60 /tmp/g.log`
Expected: FAIL — `resolve(...)` has no `storageLowMidRec` parameter; `aggregate(...)` has no `storageLowMidRec` parameter.

- [ ] **Step 4: Extend `WarningPrecedence.resolve(...)` with the new last param (default `= false`)**

Modify `app/src/main/java/com/aritr/rova/ui/warnings/WarningPrecedence.kt`. Add `storageLowMidRec: Boolean = false` as the LAST parameter (the `= false` default keeps every existing positional call site in `WarningPrecedenceTest` — and the prod aggregate call site, which is changing in Step 5 anyway — compiling unchanged); insert the new branch between `BATTERY_LOW` and `THERMAL_SEVERE`:

```kotlin
internal object WarningPrecedence {
    fun resolve(
        cameraPermissionGranted: Boolean,
        exactAlarmGranted: Boolean,
        storageInsufficient: Boolean,
        thermal: ThermalStatus,
        power: PowerState,
        camera: CameraSignalState,
        microphonePermissionGranted: Boolean,
        notificationsGranted: Boolean,
        batteryOptimizationExempt: Boolean,
        storageLowMidRec: Boolean = false,   // ← NEW (last param, additive, default = false)
    ): WarningId? {
        if (!cameraPermissionGranted) return WarningId.CAMERA_PERMISSION_DENIED            // #1
        if (!exactAlarmGranted) return WarningId.EXACT_ALARM_DENIED                         // #2
        if (storageInsufficient) return WarningId.STORAGE_INSUFFICIENT                      // #3
        when (thermal) {                                                                   // #4 / #5 / #6
            ThermalStatus.SHUTDOWN -> return WarningId.THERMAL_SHUTDOWN
            ThermalStatus.EMERGENCY -> return WarningId.THERMAL_EMERGENCY
            ThermalStatus.CRITICAL -> return WarningId.THERMAL_CRITICAL
            else -> Unit
        }
        val pct = power.percent
        if (pct != null && pct < 5 && !power.charging) return WarningId.BATTERY_CRITICAL   // #7
        when (camera) {                                                                    // #8 / #9
            CameraSignalState.IN_USE -> return WarningId.CAMERA_IN_USE
            CameraSignalState.DISABLED -> return WarningId.CAMERA_DISABLED
            else -> Unit
        }
        if (pct != null && pct < 15 && !power.charging) return WarningId.BATTERY_LOW        // #10
        if (storageLowMidRec) return WarningId.STORAGE_LOW_MID_REC                          // #11  ← NEW
        if (thermal == ThermalStatus.SEVERE) return WarningId.THERMAL_SEVERE                // #12
        if (!microphonePermissionGranted) return WarningId.MICROPHONE_DENIED               // #13
        if (!batteryOptimizationExempt) return WarningId.BATTERY_OPTIMIZATION_ON           // #14
        if (power.powerSaveMode) return WarningId.POWER_SAVE_MODE                           // #15
        if (thermal == ThermalStatus.MODERATE) return WarningId.THERMAL_MODERATE           // #16
        if (!notificationsGranted) return WarningId.NOTIFICATIONS_DENIED                   // #17
        return null
    }
}
```

> Update the KDoc above `resolve` to mention 17 rows (was 16). The KDoc paragraph "As of Phase 4.1b all 16 rows are reachable" becomes "As of R2 (2026-05-13) all 17 rows are reachable, including STORAGE_LOW_MID_REC (#11)".

- [ ] **Step 5: Extend `WarningCenterViewModel.aggregate(...)` with the new last source flow + `Bools6`**

Unlike `resolve`, `aggregate` takes flow parameters — a sentinel-default like `MutableStateFlow(false)` would create a per-call instance and is fragile. Instead, update every existing `aggregate(...)` call site to pass `storageLowMidRec = MutableStateFlow(false)` (no behavior change — defaults to "never fires"). The aggregate call sites are: the WarningCenter VM-factory (covered in Step 6 below) and every existing test in `WarningCenterAggregateTest.kt` (count them via `Grep -n "WarningCenterViewModel.aggregate" app/src/test/java`; mechanically add the new named arg to each). The 2 new tests written in Step 2 pass it explicitly; carry-over tests get `storageLowMidRec = MutableStateFlow(false)` added.


Open `app/src/main/java/com/aritr/rova/ui/warnings/WarningCenterViewModel.kt`. The R1 build folded the booleans into a `Bools5`-style private class then `combine(bools5, batteryOpt, thermal, power, cameraState)` (verified by the carry-forward memo). R2 grows it to `Bools6` by appending `storageLowMidRec`. The `combine(...)` 5-arg call stays 5-arg (bools6 + 4 non-bool flows).

Concretely (illustrative — match the existing exact shape):

```kotlin
private data class Bools6(
    val cameraPerm: Boolean,
    val exactAlarm: Boolean,
    val storageInsufficient: Boolean,
    val microphone: Boolean,
    val notifications: Boolean,
    val storageLowMidRec: Boolean,         // ← NEW
)

class WarningCenterViewModel internal constructor(
    val activeWarning: StateFlow<WarningId?>,
) : ViewModel() {

    companion object {
        fun aggregate(
            cameraPermissionGranted: StateFlow<Boolean>,
            exactAlarmGranted: StateFlow<Boolean>,
            storageInsufficient: StateFlow<Boolean>,
            thermal: StateFlow<ThermalStatus>,
            power: StateFlow<PowerState>,
            camera: StateFlow<CameraSignalState>,
            microphonePermissionGranted: StateFlow<Boolean>,
            notificationsGranted: StateFlow<Boolean>,
            batteryOptimizationExempt: StateFlow<Boolean>,
            storageLowMidRec: StateFlow<Boolean>,     // ← NEW
        ): WarningCenterViewModel {
            val bools6: Flow<Bools6> = combine(
                cameraPermissionGranted,
                exactAlarmGranted,
                storageInsufficient,
                microphonePermissionGranted,
                notificationsGranted,
                storageLowMidRec,                       // ← NEW (6th)
            ) { camPerm, exact, storage, mic, notif, storLow ->
                Bools6(camPerm, exact, storage, mic, notif, storLow)
            }
            val active: StateFlow<WarningId?> = combine(
                bools6, /* StateFlow<Boolean> */ /* batteryOptExempt? if it's its own flow */, thermal, power, camera,
            ) { b, batteryOpt, t, p, c ->
                WarningPrecedence.resolve(
                    cameraPermissionGranted = b.cameraPerm,
                    exactAlarmGranted = b.exactAlarm,
                    storageInsufficient = b.storageInsufficient,
                    thermal = t,
                    power = p,
                    camera = c,
                    microphonePermissionGranted = b.microphone,
                    notificationsGranted = b.notifications,
                    batteryOptimizationExempt = batteryOpt,
                    storageLowMidRec = b.storageLowMidRec,
                )
            }.stateIn(/* scope */, /* started */, /* initial */)
            return WarningCenterViewModel(activeWarning = active)
        }
    }
}
```

> Read the existing file before writing — pay attention to whether `combine(...)` with 6 booleans uses `Flow.combine(vararg)` (the array-typed variant, which IS available for >5 sources) or whether R1 chose to fold to a smaller pack first. The existing memo says R1 used a `Bools5` class to keep the typed-5 combine; for 6 booleans the typed-`combine` overload tops out at 5 args, so a vararg-`combine` is needed (Kotlin's vararg `combine` takes up to N flows and gives an `Array<*>` to unpack). Inspect the actual `combine` import and use the matching overload — `kotlinx.coroutines.flow.combine` exposes both typed (1..5) and vararg-array overloads. If 6 booleans are needed in one `combine`, use the vararg form and unpack the `Array<Any?>` positionally — keep this private to `aggregate`.

- [ ] **Step 6: Extend the WarningCenter VM-factory wiring with the new source flow**

In `app/src/main/java/com/aritr/rova/ui/warnings/WarningCenter.kt`, locate the `viewModelFactory { initializer { WarningCenterViewModel(...) } }` block (currently at ~line 108). The R1 build calls `WarningCenterViewModel(...)` directly — but `aggregate(...)` is the companion factory. Reconcile: if the R1 wiring uses a direct `WarningCenterViewModel(cameraPermissionGranted = …, …)` constructor with 9 named StateFlow params, that constructor must also gain the 10th — OR the wiring switches to call `aggregate(...)`. Match the existing pattern.

Concretely (replace the existing 9-arg call with the 10-arg form):

```kotlin
val vm: WarningCenterViewModel = viewModel(
    factory = viewModelFactory {
        initializer {
            WarningCenterViewModel(
                cameraPermissionGranted = app.cameraPermissionSignal.state,
                exactAlarmGranted = app.exactAlarmSignal.state,
                storageInsufficient = app.storageSignal.insufficientToStart,
                thermal = app.thermalStatusSignal.state,
                power = app.powerSignal.state,
                camera = app.cameraStateSignal.state,
                microphonePermissionGranted = app.microphonePermissionSignal.state,
                notificationsGranted = app.notificationPermissionSignal.state,
                batteryOptimizationExempt = app.batteryOptimizationSignal.isExempt,
                storageLowMidRec = app.storageLowMidRecSignal.isLow,          // ← NEW
            )
        }
    }
)
```

> If R1's `WarningCenterViewModel` constructor doesn't take 9 named StateFlows directly but delegates to `aggregate(...)`, update the `aggregate(...)` call here instead — same logical shape.

- [ ] **Step 7: Run both extended test classes + full build**

`(./gradlew :app:testDebugUnitTest 2>&1; echo "EXIT=$?") > /tmp/g.log 2>&1; tail -40 /tmp/g.log`
Expected: BUILD SUCCESSFUL, **738 tests / 60 classes / 0-0-0** (previous 736 + 6 precedence + 2 aggregate = wait, that's +8; sanity: 735 baseline → Task 4 added 1 to WarningIdOrderTest if it was a single test before, or could have stayed +1 → ≈ 736 → +6 + +2 = 744). The exact number depends on the WarningIdOrderTest assertion count; treat the count as "baseline + Δ" and update the spec's predicted count in Task 12.

- [ ] **Step 8: Commit**

```
git add app/src/main/java/com/aritr/rova/ui/warnings/WarningPrecedence.kt \
        app/src/main/java/com/aritr/rova/ui/warnings/WarningCenterViewModel.kt \
        app/src/main/java/com/aritr/rova/ui/warnings/WarningCenter.kt \
        app/src/test/java/com/aritr/rova/ui/warnings/WarningPrecedenceTest.kt \
        app/src/test/java/com/aritr/rova/ui/warnings/WarningCenterAggregateTest.kt
git commit -m "feat(warnings): wire STORAGE_LOW_MID_REC through resolve + aggregate + VM factory

- WarningPrecedence.resolve gains storageLowMidRec (last param); new branch
  fires between BATTERY_LOW (#10) and THERMAL_SEVERE (#12).
- WarningCenterViewModel.aggregate gains a 10th StateFlow<Boolean> source.
- WarningCenter VM-factory wires app.storageLowMidRecSignal.isLow as the
  10th argument.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Phase D — Top-banner composable + WarningCenter signature

### Task 6: `midRecBannerContent()` + `WarningSurfaceFor` arm + `MidRecBannerContentTest` + `WarningSurfaceTest` +1

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/warnings/WarningCenter.kt` — add `TopBannerContent` data class, `midRecBannerContent(id)` helper, and the new `STORAGE_LOW_MID_REC → TopBanner` arm in `warningSurfaceFor(id)`.
- Create: `app/src/test/java/com/aritr/rova/ui/warnings/MidRecBannerContentTest.kt`
- Modify: `app/src/test/java/com/aritr/rova/ui/warnings/WarningSurfaceTest.kt` — append one arm to the `expectedSurface` `when`.

- [ ] **Step 1: Append an explicit `STORAGE_LOW_MID_REC → TopBanner` test method to `WarningSurfaceTest`**

Task 4's recommended path (option a) already added the prod `warningSurfaceFor` arm + the test's `expectedSurface` helper arm in the same commit, so the existing `everyWarningHasASurface` walk already covers the new id. Add ONE additional explicit test method to `WarningSurfaceTest` for surface-area / discoverability (mirrors the spec §5.1 prediction "WarningSurfaceTest +1"):

```kotlin
    @Test fun storage_low_mid_rec_resolves_to_top_banner() {
        assertEquals(WarningSurface.TopBanner, warningSurfaceFor(WarningId.STORAGE_LOW_MID_REC))
    }
```

- [ ] **Step 2: Write the failing `MidRecBannerContentTest`**

Create `app/src/test/java/com/aritr/rova/ui/warnings/MidRecBannerContentTest.kt`:

```kotlin
package com.aritr.rova.ui.warnings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class MidRecBannerContentTest {

    private val ids = listOf(
        WarningId.THERMAL_SHUTDOWN,
        WarningId.THERMAL_EMERGENCY,
        WarningId.THERMAL_CRITICAL,
        WarningId.THERMAL_SEVERE,
        WarningId.THERMAL_MODERATE,
        WarningId.BATTERY_CRITICAL,
        WarningId.BATTERY_LOW,
        WarningId.CAMERA_IN_USE,
        WarningId.CAMERA_DISABLED,
        WarningId.STORAGE_LOW_MID_REC,
    )

    @Test fun every_mid_rec_id_returns_nonblank_content() {
        for (id in ids) {
            val c = midRecBannerContent(id)
            assertNotNull("icon for $id", c.icon)
            assertFalse("title for $id", c.title.isBlank())
            assertFalse("sub for $id", c.sub.isBlank())
            assertEquals("cta for $id", "Stop", c.cta)
        }
    }

    @Test fun thermal_shutdown_copy() {
        assertEquals("Device overheating — stopping", midRecBannerContent(WarningId.THERMAL_SHUTDOWN).title)
    }

    @Test fun battery_low_copy() {
        assertEquals("Battery low", midRecBannerContent(WarningId.BATTERY_LOW).title)
    }

    @Test fun camera_in_use_copy() {
        assertEquals("Camera in use", midRecBannerContent(WarningId.CAMERA_IN_USE).title)
    }

    @Test fun storage_low_mid_rec_copy() {
        val c = midRecBannerContent(WarningId.STORAGE_LOW_MID_REC)
        assertEquals("Storage running low", c.title)
        assertEquals("Free space on this device.", c.sub)
    }
}
```

- [ ] **Step 3: Run tests — verify they fail**

`(./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.warnings.MidRecBannerContentTest" --tests "com.aritr.rova.ui.warnings.WarningSurfaceTest" 2>&1; echo "EXIT=$?") > /tmp/g.log 2>&1; tail -40 /tmp/g.log`
Expected: FAIL — `midRecBannerContent` / `TopBannerContent` unresolved; possibly `WarningSurfaceTest` failing because `warningSurfaceFor(STORAGE_LOW_MID_REC)` returns wrong value (or fails to compile if the production `when` isn't exhaustive yet).

- [ ] **Step 4: Add the production code**

Modify `app/src/main/java/com/aritr/rova/ui/warnings/WarningCenter.kt`. After the existing `warningSheetContent` block, add:

```kotlin
internal data class TopBannerContent(
    val icon: ImageVector,
    val title: String,
    val sub: String,
    val cta: String,            // "Stop" for all R2 ids
)

/**
 * R2 — copy for the mid-recording top banner (ADR 0007 amendment 2026-05-13). One arm
 * per WarningId mapped to [WarningSurface.TopBanner]. Pure / JVM-testable.
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
```

> `warningSurfaceFor(id)` was already updated in Task 4 Step 6 (option-a path); skip touching it again here. If — for any reason — that arm wasn't added in Task 4, add it now (the existing function currently has 9 ids mapped to TopBanner; the new arm puts `STORAGE_LOW_MID_REC` into the same cluster).

- [ ] **Step 5: Run tests — verify they pass**

`(./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.warnings.MidRecBannerContentTest" --tests "com.aritr.rova.ui.warnings.WarningSurfaceTest" 2>&1; echo "EXIT=$?") > /tmp/g.log 2>&1; tail -40 /tmp/g.log`
Expected: PASS.

- [ ] **Step 6: Commit**

```
git add app/src/main/java/com/aritr/rova/ui/warnings/WarningCenter.kt \
        app/src/test/java/com/aritr/rova/ui/warnings/MidRecBannerContentTest.kt \
        app/src/test/java/com/aritr/rova/ui/warnings/WarningSurfaceTest.kt
git commit -m "feat(warnings): add midRecBannerContent() + STORAGE_LOW_MID_REC -> TopBanner

Production: TopBannerContent data class + midRecBannerContent(id) helper
covering the 10 TopBanner-mapped ids; warningSurfaceFor gains the
STORAGE_LOW_MID_REC arm.

Tests: MidRecBannerContentTest (14 assertions) + WarningSurfaceTest +1.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: `WarningCenter(hudState, onStopRecording)` signature + `WarningTopBanner` composable + dual-branch routing

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/warnings/WarningCenter.kt`

> No new test class for the composable itself (UI-only — owner-verified). The helpers it consumes are already covered (Task 6).

- [ ] **Step 1: Add the `WarningTopBanner` composable**

In `app/src/main/java/com/aritr/rova/ui/warnings/WarningCenter.kt`, after the existing `WarningChip` private composable, add:

```kotlin
@Composable
private fun WarningTopBanner(
    content: TopBannerContent,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Visual: mockups/new_uiux/07-warnings.html row 6 — amber non-blocking top banner.
    // Rounded glass surface, leading icon + two-line text block (title + sub), trailing CTA pill.
    val amber = Color(0xFFFBBF24)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color.Black.copy(alpha = 0.55f),
        contentColor = Color.White,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                content.icon, contentDescription = null,
                tint = amber, modifier = Modifier.size(18.dp),
            )
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    content.title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
                Text(
                    content.sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.75f),
                )
            }
            Surface(
                modifier = Modifier.clickable { onAction() },
                shape = RoundedCornerShape(10.dp),
                color = amber.copy(alpha = 0.20f),
                contentColor = amber,
            ) {
                Text(
                    content.cta,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }
    }
}
```

> Imports: `androidx.compose.foundation.layout.Column` (likely already imported); confirm. `Color(0xFFFBBF24)` matches the existing SoftSheet accent — kept file-local rather than introducing a `RovaTokens` entry (per spec out-of-scope §7).

- [ ] **Step 2: Replace the `WarningCenter` composable signature + body**

In the same file, replace the existing `WarningCenter` composable. Add two parameters: `hudState: RecordHudState` (required, no default — every call site must declare an intent) and `onStopRecording: () -> Unit` (default `{}`, used by the TopBanner CTA).

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WarningCenter(
    hudState: com.aritr.rova.ui.components.RecordHudState,
    onStopRecording: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as? RovaApp } ?: return
    val vm: WarningCenterViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                WarningCenterViewModel(
                    cameraPermissionGranted = app.cameraPermissionSignal.state,
                    exactAlarmGranted = app.exactAlarmSignal.state,
                    storageInsufficient = app.storageSignal.insufficientToStart,
                    thermal = app.thermalStatusSignal.state,
                    power = app.powerSignal.state,
                    camera = app.cameraStateSignal.state,
                    microphonePermissionGranted = app.microphonePermissionSignal.state,
                    notificationsGranted = app.notificationPermissionSignal.state,
                    batteryOptimizationExempt = app.batteryOptimizationSignal.isExempt,
                    storageLowMidRec = app.storageLowMidRecSignal.isLow,
                )
            }
        }
    )
    val active by vm.activeWarning.collectAsStateWithLifecycle()
    val id = active ?: return

    val surface = warningSurfaceFor(id)

    if (hudState is com.aritr.rova.ui.components.RecordHudState.Idle) {
        // ── Idle branch: sheet / chip path (R1). TopBanner-mapped ids no-op (their surface
        //    isn't reachable from idle by design — see ADR 0007 amendment).
        if (surface == WarningSurface.TopBanner) return

        var collapsed by rememberSaveable(id) { mutableStateOf(false) }

        if (collapsed) {
            WarningChip(id = id, onExpand = { collapsed = false }, modifier = modifier)
        } else {
            WarningSheet(
                id = id,
                surface = surface,
                onPrimary = { launchActionTarget(context, warningSheetContent(id).primary.target); collapsed = true },
                onSecondary = { collapsed = true },
                onDismissRequest = {
                    if (surface != WarningSurface.HardBlockSheet) collapsed = true
                },
            )
        }
    } else {
        // ── Active branch (Recording / Waiting / Merging): TopBanner only; sheets / chips
        //    suppressed mid-rec (per spec §3.5 D6).
        if (surface != WarningSurface.TopBanner) return
        WarningTopBanner(
            content = midRecBannerContent(id),
            onAction = onStopRecording,
            modifier = modifier,
        )
    }
}
```

> The composable signature change is a breaking API change. Every call site needs updating — exactly one in master right now: inside `RecordScreen.kt` (the R1 mount). Task 8 covers the active-side mount; the existing idle-side mount is updated in this Task 7 Step 3 to pass `hudState = RecordHudState.Idle`, `onStopRecording = {}`, so the build continues to compile.

- [ ] **Step 3: Update the existing call site in `RecordScreen.kt` to pass `hudState = RecordHudState.Idle` (interim)**

The existing R1 `WarningCenter()` call in `RecordScreen.kt` is somewhere inside `RecordTopOverlay` or directly in the screen layout. Use the `Grep` tool: `Grep -n "WarningCenter\(" app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt`. The call probably looks like `WarningCenter()` or `WarningCenter(modifier = …)`.

Update it (interim — the full RecordScreen wiring lands in Task 9):

```kotlin
WarningCenter(
    hudState = com.aritr.rova.ui.components.RecordHudState.Idle,
    onStopRecording = {},
    modifier = …,            // keep whatever modifier was there
)
```

> This passes Idle unconditionally — that's wrong for active states, but it preserves the R1 visual at idle (the only state R1 ever rendered this mount in) and unblocks the build. Task 9 fixes this with the real `hudState` wiring + the active-branch mount.

- [ ] **Step 4: Build + run full test suite — verify it compiles + passes**

```
(./gradlew :app:assembleDebug 2>&1; echo "EXIT=$?") > /tmp/g.log 2>&1; tail -40 /tmp/g.log
(./gradlew :app:testDebugUnitTest 2>&1; echo "EXIT=$?") > /tmp/g.log 2>&1; tail -40 /tmp/g.log
```

Expected: both BUILD SUCCESSFUL; full test count unchanged from Task 6 (no new tests in Task 7).

- [ ] **Step 5: Commit**

```
git add app/src/main/java/com/aritr/rova/ui/warnings/WarningCenter.kt \
        app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt
git commit -m "feat(warnings): WarningCenter(hudState) — dual-branch routing + WarningTopBanner

- WarningCenter gains hudState + onStopRecording params.
- Idle branch: existing R1 sheet/chip path (unchanged).
- Active branch: renders WarningTopBanner; sheets/chips suppressed.
- Existing R1 call site in RecordScreen wired with hudState=Idle (interim;
  the active-side mount lands in Task 9).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Phase E — Active-HUD composables

### Task 8: `RecordActiveHud` + `LoopPill` + `StatusPill` + `StatusDot` in `RecordChrome.kt`

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt` — add the composables + the three dot-color file-local `val`s.

> No new test class — the composables consume helpers already tested (Tasks 1 + 2). Layout / colors are owner-verified on device.

- [ ] **Step 1: Add the three dot-color `val`s at the top of `RecordChrome.kt` (after the existing R1 file-local constants like `GlassFill` / `GlassStroke`)**

```kotlin
private val RecordingDotColor = androidx.compose.ui.graphics.Color(0xFFEF4444)   // red
private val BreakDotColor     = androidx.compose.ui.graphics.Color(0xFFFBBF24)   // amber (matches SoftSheet accent)
private val MergingDotColor   = androidx.compose.ui.graphics.Color(0xFF60A5FA)   // blue
```

- [ ] **Step 2: Add the `StatusDot` composable (file-private)**

```kotlin
@Composable
private fun StatusDot(dot: StatusDotColor, modifier: Modifier = Modifier) {
    val color = when (dot) {
        StatusDotColor.RECORDING -> RecordingDotColor
        StatusDotColor.BREAK -> BreakDotColor
        StatusDotColor.MERGING -> MergingDotColor
    }
    androidx.compose.foundation.layout.Box(
        modifier
            .size(8.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(color),
    )
}
```

- [ ] **Step 3: Add the `LoopPill` composable (file-private)**

```kotlin
@Composable
private fun LoopPill(loopIndex: Int, loopTotal: Int, modifier: Modifier = Modifier) {
    val text = loopPillContent(loopIndex, loopTotal) ?: return       // hide pill on single-clip
    androidx.compose.material3.Surface(
        modifier = modifier,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        color = GlassFill,                                  // R1 file-local — reuse
        contentColor = Color.White,
        border = androidx.compose.foundation.BorderStroke(1.dp, GlassStroke),
    ) {
        androidx.compose.material3.Text(
            text,
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}
```

> If `GlassFill` / `GlassStroke` have different names in the actual `RecordChrome.kt`, match the real names. The R1 plan used `Color.Black.copy(alpha = 0.40f)` for the fill and `Color.White.copy(alpha = 0.07f)` for the stroke — inline those if there are no named constants.

- [ ] **Step 4: Add the `StatusPill` composable (file-private)**

```kotlin
@Composable
private fun StatusPill(content: StatusPillContent, modifier: Modifier = Modifier) {
    androidx.compose.material3.Surface(
        modifier = modifier,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
        color = GlassFill,
        contentColor = Color.White,
        border = androidx.compose.foundation.BorderStroke(1.dp, GlassStroke),
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        ) {
            StatusDot(content.dot)
            androidx.compose.material3.Text(
                content.main,
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                color = Color.White,
            )
            androidx.compose.material3.Text(
                content.time,
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.75f),
            )
        }
    }
}
```

- [ ] **Step 5: Add the `RecordActiveHud` public-`internal` composable**

```kotlin
/**
 * R2 — top-anchored active-state HUD. Stacks the loop pill (when applicable) above the
 * status pill. MUST NOT be mounted at Idle (the helpers throw on Idle as a caller-bug
 * guard). Each pill is glass-on-camera, compact, centered on the top safe-area row.
 */
@Composable
internal fun RecordActiveHud(
    state: com.aritr.rova.ui.components.RecordHudState,
    loopIndex: Int,
    loopTotal: Int,
    clipSecondsLeft: Int,
    waitSecondsLeft: Int,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.Column(
        modifier = modifier,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        LoopPill(loopIndex = loopIndex, loopTotal = loopTotal)
        StatusPill(content = hudStatusPillContent(state, clipSecondsLeft, waitSecondsLeft))
    }
}
```

- [ ] **Step 6: Build — verify it compiles**

`(./gradlew :app:assembleDebug 2>&1; echo "EXIT=$?") > /tmp/g.log 2>&1; tail -40 /tmp/g.log`
Expected: BUILD SUCCESSFUL. (No new tests; the composables are visual.)

- [ ] **Step 7: Commit**

```
git add app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt
git commit -m "feat(record): add RecordActiveHud + LoopPill + StatusPill + StatusDot

R2 active-state HUD composables. Glass-on-camera tokens; dot colors are
file-local vals (RecordingDotColor / BreakDotColor / MergingDotColor).
RecordScreen wires them in the next task.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Phase F — RecordScreen integration

### Task 9: Wire `RecordActiveHud` + active-side `WarningCenter` + the storage-low poll LaunchedEffect in `RecordScreen`

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt` — replace the legacy active-state body (`SessionTimer` / `ClipProgressBand` / `WaitingCountdown` / `MergingProgressBand` / `RecordStatusStrip`) with the new active-when branch; add the active-side `WarningCenter` mount; add the storage-low poll LaunchedEffect; thread `viewModel.stopRecording` (or the existing equivalent) into both warning mounts.

> No new tests in this task. The composables it mounts are covered (Phases A–E). The wiring is owner-verified on device.

- [ ] **Step 1: Capture the existing active-state layout shape**

Use `Grep -n "SessionTimer\\|ClipProgressBand\\|WaitingCountdown\\|MergingProgressBand\\|RecordStatusStrip" app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt` to list every use site. Read 20 lines around each to understand the composition. The five legacy composables together render the active-state HUD inside whichever Scaffold/Column structure RecordScreen uses. They will be removed in Task 10 — for this task, locate the `when (hudState) { ... }` (or equivalent state branch) and prepare to substitute the new R2 mount.

- [ ] **Step 2: Determine the call-site for `viewModel.stopRecording`**

The existing R1 `RecordBottomNav(fabState = recordFabState(...), onFabClick = ...)` should already route the FAB's Stop click to `viewModel.stopRecording()` (or equivalent — `onStop`, `onStopClicked`, etc.). Find it via Grep on `RecordScreen.kt`. The same lambda passes to `WarningCenter`'s new `onStopRecording`.

- [ ] **Step 3: Replace the active-state body with the new R2 active-when arm**

Locate the active branch(es). It might be a single `when (hudState)` with a multi-arm body, or three separate `if`s. Replace the active surface mounting code with this shape:

```kotlin
when (hudState) {
    RecordHudState.Idle -> {
        // R1 idle path — unchanged.
        RecordTopOverlay(...)
        WarningCenter(
            hudState = RecordHudState.Idle,
            onStopRecording = {},
            modifier = Modifier.align(Alignment.TopCenter),
        )
        RecordCameraControls(...)
        RecordSettingsCard(...)
    }
    RecordHudState.Recording, RecordHudState.Waiting, is RecordHudState.Merging -> {
        // R2 active-state chrome.
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            WarningCenter(
                hudState = hudState,
                onStopRecording = { viewModel.stopRecording() },     // adapt name to actual VM API
            )
            RecordActiveHud(
                state = hudState,
                loopIndex = currentLoopIndex,                         // adapt to actual VM/service flow
                loopTotal = configuredLoopCount,                      // adapt to actual VM state
                clipSecondsLeft = displayedCountdownSeconds,          // existing R1 local
                waitSecondsLeft = displayedWaitSeconds,               // existing R1 local
            )
        }
    }
}
```

> `currentLoopIndex` and `configuredLoopCount` aren't names you can copy verbatim — find their real source. The R1 build's idle dock displayed an "X of N" loops count (via `PlanSummary` — now deleted). Look in `RecordViewModel.kt` for a `currentLoopIndex: StateFlow<Int>` / `loopCount: StateFlow<Int>` (or whatever the names actually are). Read 30 lines around the VM's loop-state exposure. If only `configuredLoopCount` exists but not `currentLoopIndex`, look in `RovaServiceState` (the service status flow RecordScreen subscribes to) — the service has a notion of the running loop index for the FGS notification.

> `displayedCountdownSeconds` and `displayedWaitSeconds` are R1's "preserve verbatim" local-tick values for the per-clip / per-wait countdowns. They exist (R1 spec preserve list). Re-use the existing locals — do NOT introduce new produceStates.

- [ ] **Step 4: Add the storage-low-mid-rec poll LaunchedEffect**

Find an unrelated, scope-clean spot in RecordScreen (next to the R1 storage signal `recompute` LaunchedEffect is ideal — that one's keyed on `(duration, loopCount, resolution)`). Add a new LaunchedEffect:

```kotlin
LaunchedEffect(hudState, duration, resolution) {
    val app = (LocalContext.current.applicationContext as? RovaApp)
    if (hudState is RecordHudState.Idle) {
        app?.storageLowMidRecSignal?.clear()
        return@LaunchedEffect
    }
    while (true) {
        app?.storageLowMidRecSignal?.poll(duration, resolution)
        delay(30_000L)
    }
}
```

> `LocalContext.current` can't be called inside a `LaunchedEffect` block (it's a `@Composable` reader). Capture the context BEFORE the LaunchedEffect:
>
> ```kotlin
> val context = LocalContext.current
> val app = remember(context) { context.applicationContext as? RovaApp }
> LaunchedEffect(hudState, duration, resolution) {
>     if (hudState is RecordHudState.Idle) {
>         app?.storageLowMidRecSignal?.clear()
>         return@LaunchedEffect
>     }
>     while (true) {
>         app?.storageLowMidRecSignal?.poll(duration, resolution)
>         delay(30_000L)
>     }
> }
> ```
>
> `duration` and `resolution` are likely already collected as Compose state via `by viewModel.duration.collectAsStateWithLifecycle()` and `by viewModel.resolution.collectAsStateWithLifecycle()` — R1 wired both. If not, collect them. Match the existing collection style.

> **Concurrency note:** `while (true) { … delay(N) }` inside a `LaunchedEffect` auto-cancels when the key list changes or when the composable leaves composition. That's the desired semantics — a transition to Idle re-runs the effect (key change), enters the early-return clear branch, and a fresh entry into an active state starts a fresh poll loop.

- [ ] **Step 5: Build + run full test suite**

```
(./gradlew :app:assembleDebug 2>&1; echo "EXIT=$?") > /tmp/g.log 2>&1; tail -40 /tmp/g.log
(./gradlew :app:testDebugUnitTest 2>&1; echo "EXIT=$?") > /tmp/g.log 2>&1; tail -40 /tmp/g.log
```

Expected: BUILD SUCCESSFUL on both. No test count change (this task adds no tests). The five legacy components are still in the source tree but no longer rendered — they'll come out in Task 10.

- [ ] **Step 6: Commit**

```
git add app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt
git commit -m "feat(record): wire R2 active-state HUD + mid-rec banner + storage-low poll

- Active-state body replaced with Column { WarningCenter; RecordActiveHud }.
- Idle WarningCenter mount keeps the R1 visual.
- New LaunchedEffect drives StorageLowMidRecSignal.poll every 30 s while
  HUD state is active; clears on Idle transitions.
- The five legacy components (SessionTimer / ClipProgressBand /
  WaitingCountdown / MergingProgressBand / RecordStatusStrip) are no
  longer referenced — files removed in the next commit.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Phase G — Cleanup

### Task 10: Delete the 5 retired components + verify no stragglers

**Files (delete):**
- `app/src/main/java/com/aritr/rova/ui/components/SessionTimer.kt`
- `app/src/main/java/com/aritr/rova/ui/components/ClipProgressBand.kt`
- `app/src/main/java/com/aritr/rova/ui/components/WaitingCountdown.kt`
- `app/src/main/java/com/aritr/rova/ui/components/MergingProgressBand.kt`
- `app/src/main/java/com/aritr/rova/ui/components/RecordStatusStrip.kt`

> Verified at plan-write time (2026-05-13): NONE of these have a `*Test.kt` companion in `app/src/test/java/com/aritr/rova/ui/components/`. The four `*Test.kt` files there are `RecordHudFormattersTest`, `RecordHudStateTest`, `RecordHudMutualExclusionTest`, `UiCopyTest` — all untouched. If implementation surfaces a hidden test file, delete it alongside.

- [ ] **Step 1: Confirm nothing else references these symbols**

```
Grep -rn "SessionTimer\\|ClipProgressBand\\|WaitingCountdown\\|MergingProgressBand\\|RecordStatusStrip" app/src/main app/src/test app/src/androidTest
```

Expected: matches only inside the five files themselves (their own `package` / `class` / `fun` definitions). If a match shows up elsewhere, RecordScreen Task 9 missed a reference — fix THAT first, then return to this task.

- [ ] **Step 2: Delete the five files**

```
git rm app/src/main/java/com/aritr/rova/ui/components/SessionTimer.kt \
       app/src/main/java/com/aritr/rova/ui/components/ClipProgressBand.kt \
       app/src/main/java/com/aritr/rova/ui/components/WaitingCountdown.kt \
       app/src/main/java/com/aritr/rova/ui/components/MergingProgressBand.kt \
       app/src/main/java/com/aritr/rova/ui/components/RecordStatusStrip.kt
```

- [ ] **Step 3: Build + run full test suite**

```
(./gradlew :app:assembleDebug 2>&1; echo "EXIT=$?") > /tmp/g.log 2>&1; tail -40 /tmp/g.log
(./gradlew :app:testDebugUnitTest 2>&1; echo "EXIT=$?") > /tmp/g.log 2>&1; tail -40 /tmp/g.log
```

Expected: both BUILD SUCCESSFUL; test count unchanged from Task 9.

- [ ] **Step 4: Lint gate — confirm ≤ 53, 0 Error**

`(./gradlew :app:lintDebug 2>&1; echo "EXIT=$?") > /tmp/g.log 2>&1; tail -40 /tmp/g.log`

Parse `app/build/reports/lint-results-debug.xml`:

```
Grep -c "<issue " app/build/reports/lint-results-debug.xml
```

Expected: ≤ 53. If above 53, identify which finding regressed and fix it before committing. No `lint-baseline.xml` may exist.

- [ ] **Step 5: Commit**

```
git commit -m "refactor(record): delete the 5 retired active-state components

R2 retires SessionTimer / ClipProgressBand / WaitingCountdown /
MergingProgressBand / RecordStatusStrip — replaced by RecordActiveHud
(loop-pill + status-pill in RecordChrome.kt) per the redesign.

No tests retired (none of the five had *Test.kt companions on master).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Phase H — Docs

### Task 11: Doc amendments — ADR-0007 amendment, WarningCenterContract, NEW_UI_BACKEND_REPLAN, architecture.md

**Files:**
- Modify: `docs/adr/0007-record-warning-sheets.md` — append amendment.
- Modify: `docs/WarningCenterContract.md` — table 16 → 17 rows; §7 producer notes.
- Modify: `NEW_UI_BACKEND_REPLAN.md` — row-count refs; pointer to R2 spec.
- Modify: `docs/architecture.md` — file-tree update.

- [ ] **Step 1: Append the ADR-0007 amendment**

Append to `docs/adr/0007-record-warning-sheets.md` (after the existing "Status / sign-off" section):

```markdown
---

## Amendment 2026-05-13 — R2: mid-rec top-banner live, +1 row (STORAGE_LOW_MID_REC)

R2 (`docs/superpowers/specs/2026-05-13-record-home-redesign-r2-design.md`) lands the deferred top-banner render path from §1 last sub-bullet. Concretely:

1. **Mid-rec top-banner is live.** `WarningCenter(hudState: RecordHudState)` now renders `WarningTopBanner` when the active warning's surface is `TopBanner` AND `hudState != Idle`. At idle, TopBanner-mapped warnings continue to render nothing (the deliberate "no idle surface for mid-rec ids" choice).
2. **+1 row: `STORAGE_LOW_MID_REC`** (ADVISORY-tier, `gatesStart = false`). Placed at ordinal 10 in `WarningId.kt` — between `BATTERY_LOW` (#10 → 1-indexed) and `THERMAL_SEVERE` (was #11, now #12). Rationale: it's more urgent than the configuration-style advisories (mic / notif / battery-opt / power-save) because storage exhaustion will autonomously halt the session via the per-segment storage gate in `RovaRecordingService`, but it ranks below `BATTERY_LOW` because a 14 %-battery session has less remaining runtime than a session with 3 clips of storage headroom in the common case.
3. **`StorageLowMidRecSignal`** is a new UI-side leaf signal on `RovaApp`, polled by RecordScreen every ~30 s while HUD state ∈ {Recording, Waiting, Merging}. Fires when `freeBytes < 3 × bytesPerSecondForResolution(resolution) × durationSeconds`. No `service/**` diff.
4. **`WarningPrecedence.resolve(...)`** gains `storageLowMidRec: Boolean` (last param). **`WarningCenterViewModel.aggregate(...)`** gains a 10th source flow. **`WarningCenter` composable signature** gains `hudState: RecordHudState` and `onStopRecording: () -> Unit`. **`WarningCenterContract.md`** is the 17-row contract document; the Phase-1.D revision block's 16-row table is updated.
5. **Retired:** the five legacy active-state composables (`SessionTimer`, `ClipProgressBand`, `WaitingCountdown`, `MergingProgressBand`, `RecordStatusStrip`) are deleted and replaced by `RecordActiveHud` + `LoopPill` + `StatusPill` in `RecordChrome.kt`.
6. **Out of scope / deferred:** the snooze / dismiss model + hysteresis on the top banner (future 4.1c bundle); service-side cancel for in-progress merges; the dynamic "~N min remaining" estimate in the storage-low banner sub-copy.
7. **Invariants preserved:** the 16 existing rows of `WarningId` keep their original ordinal positions; their tier values and `gatesStart` flags are unchanged. The Start-gate in `RecordScreen` (`startBlocked = !cameraPermissionGranted || storageInsufficient`) is byte-identical.

Owner sign-off recorded 2026-05-13 in the R2 spec review.
```

- [ ] **Step 2: Update `docs/WarningCenterContract.md`**

Locate the Phase-1.D revision block's 16-row table. Insert the new row between `BATTERY_LOW` (#10) and `THERMAL_SEVERE` (was #11, now #12). Update the section title's row count (16 → 17). Bump §7 producer notes with one line:

```markdown
| 11 | STORAGE_LOW_MID_REC | ADVISORY | false | Mid-rec only; top-banner surface (R2 — ADR 0007 amendment 2026-05-13). |
```

Append to §7 producer notes:

```markdown
- **`StorageLowMidRecSignal`** (R2, 2026-05-13) — UI-side leaf signal. Polled by RecordScreen every ~30 s while HUD state ∈ {Recording, Waiting, Merging}. Fires when `freeBytes < 3 × bytesPerSecondForResolution(resolution) × durationSeconds`. Cleared on Idle transitions. No service or data-layer involvement.
```

- [ ] **Step 3: Update `NEW_UI_BACKEND_REPLAN.md`**

Find and update every literal occurrence of "16 rows" / "16-row" → "17 rows" / "17-row" inside §2.1 and §6. Find the row-count assertion in §6 (likely "16 rows in `WarningId`") → 17.

Append the R2 spec pointer to whichever section currently lists Phase 4 + R1 sources:

```markdown
- R2 spec: `docs/superpowers/specs/2026-05-13-record-home-redesign-r2-design.md` — active-state HUD + mid-rec banner surface (ADR 0007 amendment 2026-05-13).
```

- [ ] **Step 4: Update `docs/architecture.md`**

Locate the file-tree section's `ui/components/` listing and remove these five lines (or whatever shape the tree uses):

```
- SessionTimer.kt
- ClipProgressBand.kt
- WaitingCountdown.kt
- MergingProgressBand.kt
- RecordStatusStrip.kt
```

Locate the `ui/signals/` listing and add:

```
- StorageLowMidRecSignal.kt
```

Locate the `ui/warnings/WarningCenter.kt` annotation and append: `(+ WarningTopBanner + midRecBannerContent — R2)`. Locate `ui/screens/RecordChrome.kt` and append `(+ RecordActiveHud + LoopPill + StatusPill — R2)`.

- [ ] **Step 5: Verify the docs build (no Gradle for these — just grep for stale row counts)**

```
Grep -rn "16-row\\|16 rows\\|16 row" docs NEW_UI_BACKEND_REPLAN.md
```

Expected: zero hits (or only the historical "Phase 4.1b: all 16 rows are reachable" inside an "as of YYYY-MM-DD" sentence that's clearly historical — owner's call whether to amend in place or leave as a dated record).

```
Grep -rn "SessionTimer\\|ClipProgressBand\\|WaitingCountdown\\|MergingProgressBand\\|RecordStatusStrip" docs
```

Expected: zero hits (or only inside historical PR/commit-style notes, which the owner can keep).

- [ ] **Step 6: Commit**

```
git add docs/adr/0007-record-warning-sheets.md \
        docs/WarningCenterContract.md \
        NEW_UI_BACKEND_REPLAN.md \
        docs/architecture.md
git commit -m "docs: R2 amendments — ADR-0007, WarningCenterContract, replan, architecture

- ADR 0007 gains 'Amendment 2026-05-13 — R2' section.
- WarningCenterContract.md: 16-row table -> 17-row table (Phase-1.D
  revision block); §7 producer notes append StorageLowMidRecSignal.
- NEW_UI_BACKEND_REPLAN.md: row-count refs updated; pointer to R2 spec.
- architecture.md: file-tree updated for the new + retired files.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Phase I — Branch + PR

### Task 12: Final whole-branch gates rerun + PR + done-report comment

**Files:** none (gates + PR open).

- [ ] **Step 1: Final clean build**

```
(./gradlew :app:clean 2>&1; echo "EXIT=$?") > /tmp/g.log 2>&1; tail -10 /tmp/g.log
(./gradlew :app:assembleDebug 2>&1; echo "EXIT=$?") > /tmp/g.log 2>&1; tail -40 /tmp/g.log
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Final test gate**

```
(./gradlew :app:testDebugUnitTest 2>&1; echo "EXIT=$?") > /tmp/g.log 2>&1; tail -40 /tmp/g.log
```

Parse `app/build/test-results/testDebugUnitTest/*.xml`:

```
Grep -c "<testcase " app/build/test-results/testDebugUnitTest
Grep -c "<testsuite " app/build/test-results/testDebugUnitTest
Grep -c "<failure" app/build/test-results/testDebugUnitTest
Grep -c "<error" app/build/test-results/testDebugUnitTest
Grep -c "<skipped" app/build/test-results/testDebugUnitTest
```

Expected: 0 failures, 0 errors, 0 skipped. Test count = baseline (735) + Δ from R2 (~+34, the spec's predicted method delta). Write the exact final count into the PR done-report comment in Step 5.

- [ ] **Step 3: Final lint gate**

```
(./gradlew :app:lintDebug 2>&1; echo "EXIT=$?") > /tmp/g.log 2>&1; tail -40 /tmp/g.log
Grep -c "<issue " app/build/reports/lint-results-debug.xml
```

Expected: ≤ 53. If above, find the new finding(s) and fix before opening PR. If below 53 (because the retired components were responsible for one or more warnings), record the new baseline for the PR comment.

- [ ] **Step 4: Push the branch**

```
git push -u origin feat/record-home-redesign-r2
```

- [ ] **Step 5: Open the PR**

Compose a PR body file `/tmp/pr-r2-body.md` with:

```markdown
## R2 — active-state HUD restyle + mid-rec top-banner surface

Implements `docs/superpowers/specs/2026-05-13-record-home-redesign-r2-design.md` (spec @ `4c77f6e`) per the locked decisions D1–D11 / assumptions A1–A5.

### Highlights
- **Active-state HUD (Recording / Waiting / Merging)** re-skinned to `01-record-home.html`: loop-pill + status-pill + Stop-FAB. Five legacy components retired (SessionTimer, ClipProgressBand, WaitingCountdown, MergingProgressBand, RecordStatusStrip).
- **Mid-recording top-banner** shipped (ADR 0007 deferred path). Routes the 9 ADR-listed WarningIds + the new `STORAGE_LOW_MID_REC` (10 total) through `WarningSurface.TopBanner`.
- **+1 row in the precedence enum:** `STORAGE_LOW_MID_REC` at ordinal 10 (between BATTERY_LOW and THERMAL_SEVERE). New leaf signal `StorageLowMidRecSignal` polls free bytes vs. 3 × bytes-per-clip every 30 s while active.
- **Invariants preserved byte-for-byte:** 16 carry-over WarningId rows keep their tier + gatesStart + previous ordinal-relative position; Start-gate logic unchanged; service/data/build-gradle untouched.

### Per-task commits

`(./scripts/list-commits)` or manually:

```
<commit list — fill in after final commits>
```

### Gates @ `<final commit SHA>` (re-run after `:app:clean`)

- `:app:assembleDebug` — BUILD SUCCESSFUL
- `:app:testDebugUnitTest` — `<NNN tests / NN classes / 0-0-0>`
- `:app:lintDebug` — `<NN>` (`<W>` W + `<H>` H, 0 E), no `lint-baseline.xml`

### On-device smoke checklist (DEFERRED-to-owner — no emulator in build env)

Per spec §5.3:
1. Happy path — 4 × 30 s / 2 m wait session — loop-pill counts, status-pill cycles, Merging % shown, MergeCompleteCard fires.
2. Single-clip session (`Repeats = 1`) — loop-pill hidden.
3. Indefinite session — loop-pill shows `"N loops done"` (no slash).
4. Thermal banner — appears + auto-clears.
5. Storage-low banner — fires within ~30 s of disk near-full, "Stop" CTA works.
6. Camera-busy banner — fires when system camera app opens; auto-stops.
7. Camera-permission revoked mid-record — banner + AlertDialog.
8. Banner z-order with status pill — pills sit below banner; both readable.
9. Stop-FAB during merge — visually Disabled + tapping is a no-op.
10. Font scale 100 → 200 % — no clipping, no overlap.
11. Predictive back (API 34+) — no crash, no premature stop.
12. Light / dark theme — chrome legible in both.

### Reviewer notes

- The composable layout (RecordActiveHud, WarningTopBanner) has no unit tests — Compose layout is owner-verified per the spec / R1 precedent.
- Possible flicker on `STORAGE_LOW_MID_REC` if `freeBytes` oscillates near the 3-clip threshold — acknowledged tradeoff; hysteresis is the future 4.1c bundle.
- The dynamic "~N min remaining" estimate from `07-warnings.html` row 6 is not in this PR — deferred per spec §7.

### Spec
- `docs/superpowers/specs/2026-05-13-record-home-redesign-r2-design.md`
- ADR amendment: `docs/adr/0007-record-warning-sheets.md` → "Amendment 2026-05-13"
- Plan: `docs/superpowers/plans/2026-05-13-record-home-redesign-r2.md`
```

Then:

```powershell
& "C:\Program Files\GitHub CLI\gh.exe" pr create --base master --head feat/record-home-redesign-r2 `
    --title "R2 — active-state HUD restyle + mid-rec top-banner surface" `
    --body-file /tmp/pr-r2-body.md
```

> Or the equivalent under bash if the controller is using git-bash. The plan deliberately leaves the PR creation command flexible — match the shell the executing subagent is running in.

- [ ] **Step 6: Post the done-report as the first PR comment**

After PR creation, also post the same content (or a condensed version) as the first PR comment so it's discoverable from the `gh pr view --comments` flow. The owner reads this before deciding GO.

- [ ] **Step 7: STOP**

**Do not squash-merge. Do not `gh pr merge`.** Owner reviews + GO + merges. Report back the PR URL + final gate numbers.

---

## Self-review

After all 12 tasks complete, the executing controller / final whole-branch reviewer subagent should validate:

1. **Spec coverage** — every D1–D11 decision and A1–A5 assumption is realised somewhere. D2 (Merging status-pill) → Task 2's `hudStatusPillContent` + Task 8's `StatusPill`. D3 (10-id mid-rec set) → Task 6's `midRecBannerContent` + warningSurfaceFor arm. D4 (StorageLowMidRecSignal contract) → Task 3. D5 (RecordChrome.kt extension) → Tasks 1 + 2 + 8. D6 (WarningCenter signature + dual-branch routing) → Task 7. D7 (cam controls hidden during active) → Task 9 (the when-arm doesn't mount `RecordCameraControls`). D8 (recovery chip + WarningChip hidden during active) → Task 9 (the when-arm doesn't mount `RecordTopOverlay`'s chips OR the chip path is suppressed via the WarningCenter active branch). D9 (Stop-FAB enabled/disabled) → no code change (the R1 `recordFabState` already implements D9). D10 (banner pushes pills down) → Task 9's Column structure. D11 (test policy) → all tasks (no Compose-UI/Robolectric).
2. **Invariant audit** — at no commit does any of (a) the 16 carry-over WarningId rows shift ordinal, (b) the Start-gate code in RecordScreen change, (c) `service/**` or `data/**` see a diff, (d) `app/build.gradle.kts` change. Reviewer runs `git log --stat master..HEAD` and confirms.
3. **Test count vs. spec prediction** — spec §5.1 predicted +34 new methods; reviewer reports the actual delta and flags any >2 mismatch.
4. **Lint stays ≤ 53, no Error, no baseline** — reviewer confirms.
5. **All five retired files actually deleted** — `git log --stat` shows the five `delete mode` entries.
6. **Owner smoke is DEFERRED, not silently skipped** — the PR's first comment explicitly lists the 12-item checklist with "owner to verify on device" framing.

---

**End of plan.**

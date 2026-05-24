# Phase 4 Slice 2 — `STORAGE_FULL_AUTOSTOPPED` Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Surface a new `STORAGE_FULL_AUTOSTOPPED` echo banner on Record-screen Idle when the most-recent terminal session was auto-stopped for `LOW_STORAGE`; persistent per-session-id dismissal; "Free up space" CTA; overflow with "Don't show again" + "Review session".

**Architecture:** New `SessionAutoStopEchoSignal` reads the latest terminal manifest via a `terminalEchoSource: () -> TerminalEcho?` seam (factored as `latestTerminalSession()` on `SessionStore`). Filtered against `RovaSettings.dismissedAutoStopEchoIds` (new key on `rova_runtime_prefs`). `WarningPrecedence.resolve` gains an 11th param `autoStopEcho: TerminalEcho?` and a branch returning the new `WarningId.STORAGE_FULL_AUTOSTOPPED` (precedence slot 12) when `stopReason == LOW_STORAGE`. `WarningCenterViewModel` ctor gains the source flow + `dismissAutoStopEcho` mutator + `onAutoStopDismissed` callback. `WarningTopBannerV3` gains an optional overflow slot. `WarningCenter` special-cases the new id on Idle to render `WarningTopBannerV3`.

**Tech Stack:** Kotlin 2.2.10 · Jetpack Compose · `androidx.lifecycle.viewmodel.compose.viewModel` · `SharedPreferences` (rova_runtime_prefs) · JUnit4 pure-JVM (no Robolectric / coroutines-test).

**Spec:** [`docs/superpowers/specs/2026-05-24-phase-4-slice2-storage-full-autostopped-design.md`](../specs/2026-05-24-phase-4-slice2-storage-full-autostopped-design.md)

**Branch:** `phase4-slice2-storage-full-autostopped` (already cut from master `3a490bd`; spec already committed).

---

## File Structure

| File | Responsibility | Change |
|---|---|---|
| `app/src/main/java/com/aritr/rova/data/RovaSettings.kt` | Persistent settings. | + `dismissedAutoStopEchoIds: Set<String>` on `runtimePrefs`. |
| `app/src/test/java/com/aritr/rova/data/RovaSettingsTest.kt` | Pure-JVM round-trip coverage. | + 3 tests. |
| `app/src/main/java/com/aritr/rova/ui/warnings/TerminalEcho.kt` | NEW. Value type carried by the signal. | New file (file-internal data class). |
| `app/src/main/java/com/aritr/rova/data/LatestTerminalSession.kt` | NEW. Pure SessionStore-reader extension. | New file — `internal fun SessionStore.latestTerminalSession()`. |
| `app/src/main/java/com/aritr/rova/ui/signals/SessionAutoStopEchoSignal.kt` | NEW. Leaf signal class. | New file — exposes `state: StateFlow<TerminalEcho?>` + `refresh()` + `markDismissed(id)`. |
| `app/src/test/java/com/aritr/rova/ui/signals/SessionAutoStopEchoSignalTest.kt` | NEW. Pure-JVM signal tests. | New file — 6 tests. |
| `app/src/main/java/com/aritr/rova/ui/warnings/WarningId.kt` | Enum. | + `STORAGE_FULL_AUTOSTOPPED` at ordinal 11 (between `STORAGE_LOW_MID_REC` and `THERMAL_SEVERE`). |
| `app/src/main/java/com/aritr/rova/ui/warnings/WarningPrecedence.kt` | Pure resolver. | + 11th param `autoStopEcho` + branch. |
| `app/src/test/java/com/aritr/rova/ui/warnings/WarningPrecedenceTest.kt` | If file exists, add 2 tests. If missing, plan reuses `WarningCenterAggregateTest` for these scenarios — see Task 3. | + 2 tests. |
| `app/src/main/java/com/aritr/rova/ui/warnings/WarningCenterViewModel.kt` | VM. | + 11th source ctor param `autoStopEcho` + `dismissAutoStopEcho(id)` mutator + `onAutoStopDismissed` callback + extended `aggregate()` combinator. |
| `app/src/test/java/com/aritr/rova/ui/warnings/WarningCenterAggregateTest.kt` | VM tests. | + 2 tests; `makeVm()` helper extended with default autoStopEcho. |
| `app/src/main/java/com/aritr/rova/ui/warnings/WarningSheetContent.kt` | Surface map + ActionTarget + content helpers. | + `STORAGE_FULL_AUTOSTOPPED → TopBanner` in `warningSurfaceFor`; defensive arm in `warningSheetContent`; arm in `midRecBannerContent`; + `ActionTarget.STORAGE_SETTINGS` / `DISMISS_AUTOSTOP_ECHO` / `REVIEW_SESSION`; + `overflow: List<WarningAction> = emptyList()` field on `TopBannerContent`. |
| `app/src/main/java/com/aritr/rova/ui/warnings/WarningTopBannerV3.kt` | Banner composable. | + optional `onOverflow: ((ActionTarget) -> Unit)? = null` param + overflow ⋯ icon render when `content.overflow.isNotEmpty()`. |
| `app/src/main/java/com/aritr/rova/ui/warnings/WarningCenter.kt` | Routing. | Idle-branch special-case for `STORAGE_FULL_AUTOSTOPPED → WarningTopBannerV3`; update `launchActionTarget` for new ActionTarget values; update `buildWarningCenterViewModel` to thread the new params. |
| `app/src/main/java/com/aritr/rova/RovaApp.kt` | App singleton. | + lazy `autoStopEchoSignal` property. |
| `app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt` | RecordScreen mount. | + `LifecycleEventEffect(ON_RESUME)` calls `app.autoStopEchoSignal.refresh()`; + navigation callback wiring for "Review session" passthrough. |
| `docs/adr/0015-storage-full-autostopped-echo.md` | NEW. | ADR for the echo canon. |
| `docs/WarningCenterContract.md` | Contract doc. | Amend §4.2 row C2.3 "read-only echo on record" status + cite ADR-0015. |

Total: 8 src files modified or created, 3 test files modified or created, 2 docs touched.

---

## Pre-flight (one time)

- [ ] **Step P1: Verify branch + clean tree**

Run:
```bash
git status
git rev-parse --abbrev-ref HEAD
```

Expected: branch `phase4-slice2-storage-full-autostopped`; spec already committed; otherwise clean.

- [ ] **Step P2: Confirm baseline tests pass**

Dispatch the gradle subagent (per project convention `gradle subagent-routed`) with:
> "Run `gradlew.bat :app:testDebugUnitTest --tests 'com.aritr.rova.data.RovaSettingsTest' --tests 'com.aritr.rova.ui.warnings.WarningCenterAggregateTest'`. Report total + failure count."

Expected: all green. Baseline: `RovaSettingsTest` 39 tests, `WarningCenterAggregateTest` 11 tests.

- [ ] **Step P3: Confirm a `WarningPrecedenceTest.kt` location**

Run:
```bash
git ls-files | grep WarningPrecedenceTest
```

If the file exists → tests in T3 go there. If it does NOT exist → T3 creates `app/src/test/java/com/aritr/rova/ui/warnings/WarningPrecedenceTest.kt`. (Plan steps below assume file may or may not exist; create-if-missing.)

---

## Task 1: `RovaSettings.dismissedAutoStopEchoIds`

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/data/RovaSettings.kt`
- Test: `app/src/test/java/com/aritr/rova/data/RovaSettingsTest.kt`

Mirror the ADR-0014 pattern (4.1c added `snoozedWarningIds` the same way).

- [ ] **Step 1.1: Write failing tests (3)**

In `RovaSettingsTest.kt`, find the `// ─── snoozedWarningIds (Phase 4.1c) ───────────────────────────` section (added by 4.1c). Immediately after the 3 snooze tests but BEFORE the `// ─── Helpers ──────` divider, insert:

```kotlin
    // ─── dismissedAutoStopEchoIds (Phase 4 Slice 2) ─────────────────

    @Test fun `dismissedAutoStopEchoIds default is empty set`() {
        assertEquals(emptySet<String>(), settings().dismissedAutoStopEchoIds)
    }

    @Test fun `dismissedAutoStopEchoIds round-trips a 2-id set`() {
        val s = settings()
        s.dismissedAutoStopEchoIds = setOf("session-a", "session-b")
        assertEquals(setOf("session-a", "session-b"), s.dismissedAutoStopEchoIds)
    }

    @Test fun `dismissedAutoStopEchoIds setter replaces, does not merge`() {
        val s = settings()
        s.dismissedAutoStopEchoIds = setOf("a", "b")
        s.dismissedAutoStopEchoIds = setOf("c")
        assertEquals(setOf("c"), s.dismissedAutoStopEchoIds)
    }
```

- [ ] **Step 1.2: Verify tests fail**

```
gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.data.RovaSettingsTest.dismissedAutoStopEchoIds*"
```

Expect 3 compile-time failures referencing `dismissedAutoStopEchoIds` unresolved.

- [ ] **Step 1.3: Add the property to `RovaSettings.kt`**

Find the `snoozedWarningIds` property block (added in 4.1c, immediately after `mode`). Insert directly AFTER it and BEFORE `loopCount`:

```kotlin

    /**
     * Phase 4 Slice 2 — persistent per-session-id dismissal set for the
     * STORAGE_FULL_AUTOSTOPPED echo banner. Values are session ids
     * ([com.aritr.rova.data.SessionManifest.sessionId]). Backed by
     * [RUNTIME_PREFS_NAME] so reinstall resets (same policy as `mode` +
     * `snoozedWarningIds` — see backup_rules.xml + data_extraction_rules.xml).
     * Spec: docs/superpowers/specs/2026-05-24-phase-4-slice2-storage-full-autostopped-design.md §4.1
     */
    var dismissedAutoStopEchoIds: Set<String>
        get() = runtimePrefs.getStringSet("dismissed_autostop_echo_ids", emptySet()) ?: emptySet()
        set(value) = runtimePrefs.edit { putStringSet("dismissed_autostop_echo_ids", value) }
```

- [ ] **Step 1.4: Verify tests pass**

```
gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.data.RovaSettingsTest"
```

Expect all RovaSettingsTest tests pass (39 baseline + 3 new = 42).

- [ ] **Step 1.5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/data/RovaSettings.kt \
        app/src/test/java/com/aritr/rova/data/RovaSettingsTest.kt
git commit -m "feat(settings): persist dismissedAutoStopEchoIds (slice2 T1)

Phase 4 Slice 2 T1. Adds Set<String> getter/setter backed by
rova_runtime_prefs (same backup-excluded file as mode +
snoozedWarningIds). Values are session ids. Spec §4.1 / §4.2."
```

---

## Task 2: `TerminalEcho` + `SessionAutoStopEchoSignal` + `latestTerminalSession()`

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/warnings/TerminalEcho.kt`
- Create: `app/src/main/java/com/aritr/rova/data/LatestTerminalSession.kt`
- Create: `app/src/main/java/com/aritr/rova/ui/signals/SessionAutoStopEchoSignal.kt`
- Create: `app/src/test/java/com/aritr/rova/ui/signals/SessionAutoStopEchoSignalTest.kt`

The signal is reason-agnostic — surfaces ALL terminal echoes filtered only against `dismissed`. `WarningPrecedence` (T3) does the `stopReason == LOW_STORAGE` filtering. Decoupling keeps the signal simple and reusable for future reason-specific echoes.

- [ ] **Step 2.1: Create `TerminalEcho.kt`**

Create `app/src/main/java/com/aritr/rova/ui/warnings/TerminalEcho.kt`:

```kotlin
package com.aritr.rova.ui.warnings

import com.aritr.rova.data.StopReason

/**
 * Phase 4 Slice 2 — value type for the auto-stop echo signal. Tiny immutable
 * carrier; passed through `SessionAutoStopEchoSignal.state` and consumed by
 * `WarningPrecedence.resolve` (which filters to `stopReason == LOW_STORAGE`).
 * Spec: docs/superpowers/specs/2026-05-24-phase-4-slice2-storage-full-autostopped-design.md §4.3
 */
data class TerminalEcho(
    val sessionId: String,
    val stopReason: StopReason,
)
```

- [ ] **Step 2.2: Create `LatestTerminalSession.kt`**

Create `app/src/main/java/com/aritr/rova/data/LatestTerminalSession.kt`:

```kotlin
package com.aritr.rova.data

import com.aritr.rova.ui.warnings.TerminalEcho

/**
 * Phase 4 Slice 2 — pure reader. Walks every session manifest under the
 * SessionStore root, returns the most-recently-started terminal session as
 * a [TerminalEcho] (or null if there are none).
 *
 * Sync, blocking (matches `SessionStore.loadManifest` + `listSessionIds`
 * shapes). Cost = one manifest JSON parse per session. Acceptable on
 * cold start; future optimization can cache.
 *
 * Spec: docs/superpowers/specs/2026-05-24-phase-4-slice2-storage-full-autostopped-design.md §4.5
 */
internal fun SessionStore.latestTerminalSession(): TerminalEcho? {
    val ids = listSessionIds()
    var best: Pair<Long, TerminalEcho>? = null
    for (id in ids) {
        val m = loadManifest(id) ?: continue
        if (m.terminated == null) continue
        val candidate = m.startedAt to TerminalEcho(m.sessionId, m.stopReason)
        if (best == null || candidate.first > best!!.first) best = candidate
    }
    return best?.second
}
```

- [ ] **Step 2.3: Write failing tests for `SessionAutoStopEchoSignal` (6)**

Create `app/src/test/java/com/aritr/rova/ui/signals/SessionAutoStopEchoSignalTest.kt`:

```kotlin
package com.aritr.rova.ui.signals

import com.aritr.rova.data.StopReason
import com.aritr.rova.ui.warnings.TerminalEcho
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionAutoStopEchoSignalTest {

    @Test fun `state is null when source returns null`() {
        val signal = SessionAutoStopEchoSignal(terminalEchoSource = { null })
        assertNull(signal.state.value)
    }

    @Test fun `state emits TerminalEcho when source returns a terminal session`() {
        val echo = TerminalEcho("session-a", StopReason.LOW_STORAGE)
        val signal = SessionAutoStopEchoSignal(terminalEchoSource = { echo })
        assertEquals(echo, signal.state.value)
    }

    @Test fun `state is null when sessionId in initialDismissedIds seed`() {
        val echo = TerminalEcho("session-a", StopReason.LOW_STORAGE)
        val signal = SessionAutoStopEchoSignal(
            terminalEchoSource = { echo },
            initialDismissedIds = setOf("session-a"),
        )
        assertNull(signal.state.value)
    }

    @Test fun `markDismissed flips state to null when current echo matches`() {
        val echo = TerminalEcho("session-a", StopReason.LOW_STORAGE)
        val signal = SessionAutoStopEchoSignal(terminalEchoSource = { echo })
        assertEquals(echo, signal.state.value)
        signal.markDismissed("session-a")
        assertNull(signal.state.value)
    }

    @Test fun `markDismissed does nothing when current echo is a different sessionId`() {
        val echo = TerminalEcho("session-a", StopReason.LOW_STORAGE)
        val signal = SessionAutoStopEchoSignal(terminalEchoSource = { echo })
        signal.markDismissed("session-b")
        assertEquals(echo, signal.state.value)
    }

    @Test fun `refresh re-reads source and emits new TerminalEcho when latest changes`() {
        var current: TerminalEcho? = null
        val signal = SessionAutoStopEchoSignal(terminalEchoSource = { current })
        assertNull(signal.state.value)
        val newEcho = TerminalEcho("session-b", StopReason.USER)
        current = newEcho
        signal.refresh()
        assertEquals(newEcho, signal.state.value)
    }
}
```

- [ ] **Step 2.4: Verify tests fail**

```
gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.signals.SessionAutoStopEchoSignalTest"
```

Expect 6 compile failures (`SessionAutoStopEchoSignal` unresolved).

- [ ] **Step 2.5: Create `SessionAutoStopEchoSignal.kt`**

Create `app/src/main/java/com/aritr/rova/ui/signals/SessionAutoStopEchoSignal.kt`:

```kotlin
package com.aritr.rova.ui.signals

import com.aritr.rova.ui.warnings.TerminalEcho
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase 4 Slice 2 — leaf signal. Surfaces the most-recent terminal session
 * as a [TerminalEcho], filtered against an in-memory dismissed-id set. The
 * I/O lives behind the [terminalEchoSource] seam (production wires
 * `SessionStore.latestTerminalSession()`); tests inject a pure `() -> TerminalEcho?`.
 *
 * This signal is REASON-AGNOSTIC: it surfaces ALL terminal echoes regardless
 * of stop reason. The `stopReason == LOW_STORAGE` filter is in
 * [com.aritr.rova.ui.warnings.WarningPrecedence.resolve].
 *
 * Dismissal: in-memory only. The factory in `WarningCenter.kt` wires the
 * persistent set on `RovaSettings.dismissedAutoStopEchoIds` and seeds
 * [initialDismissedIds] from it at construction. `markDismissed(id)` calls
 * are paired with a `RovaSettings` write in the factory callback.
 *
 * Refresh triggers: `RecordScreen` `ON_RESUME` lifecycle event, and any
 * out-of-band terminal-transition observer wired in `RovaApp` (see T6).
 *
 * Spec: docs/superpowers/specs/2026-05-24-phase-4-slice2-storage-full-autostopped-design.md §4.4
 */
class SessionAutoStopEchoSignal(
    private val terminalEchoSource: () -> TerminalEcho?,
    initialDismissedIds: Set<String> = emptySet(),
) {
    private val _state = MutableStateFlow<TerminalEcho?>(null)
    val state: StateFlow<TerminalEcho?> = _state.asStateFlow()

    private var dismissed: Set<String> = initialDismissedIds

    init { recompute() }

    /** Re-poll source. Called on RecordScreen ON_RESUME + on service terminal transitions. */
    fun refresh() { recompute() }

    /** Add [sessionId] to the dismissed set and re-filter the flow. */
    fun markDismissed(sessionId: String) {
        dismissed = dismissed + sessionId
        recompute()
    }

    private fun recompute() {
        val latest = terminalEchoSource()
        _state.value = latest?.takeIf { it.sessionId !in dismissed }
    }
}
```

- [ ] **Step 2.6: Verify tests pass**

```
gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.signals.SessionAutoStopEchoSignalTest"
```

Expect 6/0/0.

- [ ] **Step 2.7: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/warnings/TerminalEcho.kt \
        app/src/main/java/com/aritr/rova/data/LatestTerminalSession.kt \
        app/src/main/java/com/aritr/rova/ui/signals/SessionAutoStopEchoSignal.kt \
        app/src/test/java/com/aritr/rova/ui/signals/SessionAutoStopEchoSignalTest.kt
git commit -m "feat(signal): SessionAutoStopEchoSignal + latestTerminalSession (slice2 T2)

Phase 4 Slice 2 T2. New leaf signal exposes the most-recent terminal
session as TerminalEcho?, filtered against an in-memory dismissed set
(seeded by factory from RovaSettings). Reason-agnostic — precedence
filter on LOW_STORAGE lives in WarningPrecedence. 6 pure-JVM tests.
Spec §4.3 / §4.4 / §4.5."
```

---

## Task 3: `WarningId.STORAGE_FULL_AUTOSTOPPED` + `WarningPrecedence` branch

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/warnings/WarningId.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/warnings/WarningPrecedence.kt`
- Modify or create: `app/src/test/java/com/aritr/rova/ui/warnings/WarningPrecedenceTest.kt`

- [ ] **Step 3.1: Add enum value to `WarningId.kt`**

Insert `STORAGE_FULL_AUTOSTOPPED` at ordinal 11 (between `STORAGE_LOW_MID_REC` and `THERMAL_SEVERE`). Replace the current rows 10-17 (lines 38-45 of the file at branch tip):

```kotlin
    // Advisory — degraded but functional
    BATTERY_LOW(WarningTier.ADVISORY),                  // #10
    STORAGE_LOW_MID_REC(WarningTier.ADVISORY),          // #11  ← (R2 — ADR 0007 amendment 2026-05-13)
    STORAGE_FULL_AUTOSTOPPED(WarningTier.ADVISORY),     // #12  ← NEW (Phase 4 Slice 2 — echo of past auto-stop)
    THERMAL_SEVERE(WarningTier.ADVISORY),               // #13
    MICROPHONE_DENIED(WarningTier.ADVISORY),            // #14
    BATTERY_OPTIMIZATION_ON(WarningTier.ADVISORY),      // #15
    POWER_SAVE_MODE(WarningTier.ADVISORY),              // #16
    THERMAL_MODERATE(WarningTier.ADVISORY),             // #17
    NOTIFICATIONS_DENIED(WarningTier.ADVISORY)          // #18
```

Also update the file-level KDoc — the line `the "Phase 4" "Banner precedence" table (owner-signed 2026-05-11), rows 1..17` becomes `rows 1..18`. And `As of Phase 4.1b all 16 rows are reachable` keep as-is (historical context).

- [ ] **Step 3.2: Verify it compiles (downstream files will fail until T3.3-3.5 + T4)**

```
gradlew.bat :app:compileDebugKotlin
```

Expect compile errors in `WarningSheetContent.kt` (exhaustive `when` over `WarningId` in `warningSheetContent`, `warningSurfaceFor`, `midRecBannerContent`). These will be fixed in T5; for now T3 sets up the enum + precedence only.

Skip-ahead: to keep the build green for T3-T4 work, add stub arms to `WarningSheetContent.kt` now:

In `warningSurfaceFor` (currently lines 36-43), the TopBanner cluster `when` arm ends with `WarningId.STORAGE_LOW_MID_REC -> WarningSurface.TopBanner`. Change the cluster's last entry to include the new id:

```kotlin
    WarningId.THERMAL_SHUTDOWN, WarningId.THERMAL_EMERGENCY, WarningId.THERMAL_CRITICAL, WarningId.THERMAL_SEVERE, WarningId.THERMAL_MODERATE,
    WarningId.BATTERY_CRITICAL, WarningId.BATTERY_LOW, WarningId.CAMERA_IN_USE, WarningId.CAMERA_DISABLED,
    WarningId.STORAGE_LOW_MID_REC,
    WarningId.STORAGE_FULL_AUTOSTOPPED -> WarningSurface.TopBanner       // ← NEW arm (Phase 4 Slice 2)
```

In `warningSheetContent(id)`, add a defensive arm BEFORE the closing brace (mirrors the `STORAGE_LOW_MID_REC` defensive arm at lines 151-158):

```kotlin
    WarningId.STORAGE_FULL_AUTOSTOPPED -> WarningSheetContent(
        // Defensive — STORAGE_FULL_AUTOSTOPPED is TopBanner-only (rendered
        // on Idle, not as a sheet). This arm keeps warningSheetContent
        // exhaustive over WarningId; never renders as a sheet.
        Icons.Default.Storage, "Recording stopped",
        "Storage filled up.",
        WarningAction("OK", ActionTarget.APP_DETAILS_SETTINGS),
        null,
    )
```

In `midRecBannerContent`, currently the `else` defensive arm is a `when` group naming 7 ids. STORAGE_FULL_AUTOSTOPPED is a TopBanner id, so it MUST get a real arm (it goes through this function). Add (T5 will refine the copy + add overflow; here just stub):

```kotlin
    WarningId.STORAGE_FULL_AUTOSTOPPED -> TopBannerContent(
        Icons.Default.Storage, "Recording stopped",
        "Storage filled up.", "Free up space",
    )
```

These stubs let T3-T4 compile. T5 replaces the `midRecBannerContent` arm with the full version including overflow + extracts to `idleEchoBannerContent` if needed.

- [ ] **Step 3.3: Extend `WarningPrecedence.resolve` signature + branch**

Replace the `resolve(...)` function body in `app/src/main/java/com/aritr/rova/ui/warnings/WarningPrecedence.kt` (currently lines 47-84) with:

```kotlin
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
        storageLowMidRec: Boolean = false,
        autoStopEcho: TerminalEcho? = null,             // ← NEW (Phase 4 Slice 2; trailing optional)
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
        if (storageLowMidRec) return WarningId.STORAGE_LOW_MID_REC                         // #11
        // #12 — STORAGE_FULL_AUTOSTOPPED (Phase 4 Slice 2; LOW_STORAGE-filtered echo of past auto-stop)
        autoStopEcho?.takeIf { it.stopReason == StopReason.LOW_STORAGE }
            ?.let { return WarningId.STORAGE_FULL_AUTOSTOPPED }
        if (thermal == ThermalStatus.SEVERE) return WarningId.THERMAL_SEVERE                // #13
        if (!microphonePermissionGranted) return WarningId.MICROPHONE_DENIED               // #14
        if (!batteryOptimizationExempt) return WarningId.BATTERY_OPTIMIZATION_ON           // #15
        if (power.powerSaveMode) return WarningId.POWER_SAVE_MODE                           // #16
        if (thermal == ThermalStatus.MODERATE) return WarningId.THERMAL_MODERATE           // #17
        if (!notificationsGranted) return WarningId.NOTIFICATIONS_DENIED                   // #18
        return null
    }
```

Also add to the imports block at top of file:

```kotlin
import com.aritr.rova.data.StopReason
```

- [ ] **Step 3.4: Write failing precedence tests (2)**

Check whether `app/src/test/java/com/aritr/rova/ui/warnings/WarningPrecedenceTest.kt` exists.

**If the file exists:** append the 2 new tests inside the class (before its closing brace):

```kotlin
    @Test fun `STORAGE_FULL_AUTOSTOPPED fires when autoStopEcho is LOW_STORAGE and no higher-priority signal active`() {
        val resolved = WarningPrecedence.resolve(
            cameraPermissionGranted = true,
            exactAlarmGranted = true,
            storageInsufficient = false,
            thermal = ThermalStatus.NONE,
            power = PowerState(percent = 80, charging = false, powerSaveMode = false),
            camera = CameraSignalState.OK,
            microphonePermissionGranted = true,
            notificationsGranted = true,
            batteryOptimizationExempt = true,
            storageLowMidRec = false,
            autoStopEcho = TerminalEcho("session-a", StopReason.LOW_STORAGE),
        )
        assertEquals(WarningId.STORAGE_FULL_AUTOSTOPPED, resolved)
    }

    @Test fun `STORAGE_LOW_MID_REC outranks STORAGE_FULL_AUTOSTOPPED when both fire`() {
        val resolved = WarningPrecedence.resolve(
            cameraPermissionGranted = true,
            exactAlarmGranted = true,
            storageInsufficient = false,
            thermal = ThermalStatus.NONE,
            power = PowerState(percent = 80, charging = false, powerSaveMode = false),
            camera = CameraSignalState.OK,
            microphonePermissionGranted = true,
            notificationsGranted = true,
            batteryOptimizationExempt = true,
            storageLowMidRec = true,                                       // both active
            autoStopEcho = TerminalEcho("session-a", StopReason.LOW_STORAGE),
        )
        assertEquals(WarningId.STORAGE_LOW_MID_REC, resolved)
    }
```

Ensure the file's imports include `import com.aritr.rova.data.StopReason` and `import com.aritr.rova.ui.warnings.TerminalEcho`.

**If the file does NOT exist:** create it with this full content:

```kotlin
package com.aritr.rova.ui.warnings

import com.aritr.rova.data.StopReason
import com.aritr.rova.ui.signals.CameraSignalState
import com.aritr.rova.ui.signals.PowerState
import com.aritr.rova.ui.signals.ThermalStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class WarningPrecedenceTest {

    @Test fun `STORAGE_FULL_AUTOSTOPPED fires when autoStopEcho is LOW_STORAGE and no higher-priority signal active`() {
        val resolved = WarningPrecedence.resolve(
            cameraPermissionGranted = true,
            exactAlarmGranted = true,
            storageInsufficient = false,
            thermal = ThermalStatus.NONE,
            power = PowerState(percent = 80, charging = false, powerSaveMode = false),
            camera = CameraSignalState.OK,
            microphonePermissionGranted = true,
            notificationsGranted = true,
            batteryOptimizationExempt = true,
            storageLowMidRec = false,
            autoStopEcho = TerminalEcho("session-a", StopReason.LOW_STORAGE),
        )
        assertEquals(WarningId.STORAGE_FULL_AUTOSTOPPED, resolved)
    }

    @Test fun `STORAGE_LOW_MID_REC outranks STORAGE_FULL_AUTOSTOPPED when both fire`() {
        val resolved = WarningPrecedence.resolve(
            cameraPermissionGranted = true,
            exactAlarmGranted = true,
            storageInsufficient = false,
            thermal = ThermalStatus.NONE,
            power = PowerState(percent = 80, charging = false, powerSaveMode = false),
            camera = CameraSignalState.OK,
            microphonePermissionGranted = true,
            notificationsGranted = true,
            batteryOptimizationExempt = true,
            storageLowMidRec = true,
            autoStopEcho = TerminalEcho("session-a", StopReason.LOW_STORAGE),
        )
        assertEquals(WarningId.STORAGE_LOW_MID_REC, resolved)
    }
}
```

- [ ] **Step 3.5: Verify all tests pass**

```
gradlew.bat :app:testDebugUnitTest
```

Expect all green. `WarningCenterAggregateTest` 11/0/0 must remain (its existing tests do not exercise the new param — autoStopEcho defaults to null in `resolve`, so existing aggregate tests are unaffected).

- [ ] **Step 3.6: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/warnings/WarningId.kt \
        app/src/main/java/com/aritr/rova/ui/warnings/WarningPrecedence.kt \
        app/src/main/java/com/aritr/rova/ui/warnings/WarningSheetContent.kt \
        app/src/test/java/com/aritr/rova/ui/warnings/WarningPrecedenceTest.kt
git commit -m "feat(warnings): STORAGE_FULL_AUTOSTOPPED enum + precedence branch (slice2 T3)

Phase 4 Slice 2 T3. Adds enum value at ordinal 11 (between
STORAGE_LOW_MID_REC and THERMAL_SEVERE). WarningPrecedence gains
11th param autoStopEcho with LOW_STORAGE filter. 2 new precedence
tests. Defensive arms added to WarningSheetContent to keep build
green; T5 refines copy + overflow. Spec §4.6 / §4.7."
```

---

## Task 4: `WarningCenterViewModel` ctor + `dismissAutoStopEcho` + aggregate combinator

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/warnings/WarningCenterViewModel.kt`
- Modify: `app/src/test/java/com/aritr/rova/ui/warnings/WarningCenterAggregateTest.kt`

`WarningCenterViewModel` ctor gains an 11th source-flow param `autoStopEcho: StateFlow<TerminalEcho?>` (positional, after the 10 source flows but before the 4.1c trailing optionals). Also a new trailing optional callback `onAutoStopDismissed: ((String) -> Unit)? = null`. New `dismissAutoStopEcho(sessionId)` mutator. The `aggregate()` combinator gets restructured to pack the 4 non-Bool flows into one inner combine.

- [ ] **Step 4.1: Write failing tests (2)**

In `WarningCenterAggregateTest.kt`, find the end of the class. The existing structure has:
- `sources()` factory at top
- `collectInto` helper
- A `makeVm()` factory (for the 4.1c-era VM tests)
- 11 existing tests

`makeVm()` currently constructs the VM with 10 source flows. We need to extend `sources()` to include `autoStopEcho`, extend `makeVm()` to pass it, and extend `collectInto` to pass it through `aggregate()`. Then add 2 new tests.

First, update `sources()`. Find `private data class TenSources(...)` (around line 27) and the matching `private fun sources()` (around line 40). Extend:

```kotlin
    private data class ElevenSources(
        val cameraPerm: MutableStateFlow<Boolean>,
        val ea: MutableStateFlow<Boolean>,
        val storage: MutableStateFlow<Boolean>,
        val th: MutableStateFlow<ThermalStatus>,
        val pw: MutableStateFlow<PowerState>,
        val camState: MutableStateFlow<CameraSignalState>,
        val mic: MutableStateFlow<Boolean>,
        val nt: MutableStateFlow<Boolean>,
        val bo: MutableStateFlow<Boolean>,
        val storageLowMidRec: MutableStateFlow<Boolean>,
        val autoStopEcho: MutableStateFlow<TerminalEcho?>,        // ← NEW
    )

    private fun sources() = ElevenSources(
        MutableStateFlow(true),
        MutableStateFlow(true),
        MutableStateFlow(false),
        MutableStateFlow(ThermalStatus.NONE),
        MutableStateFlow(clearPower()),
        MutableStateFlow(CameraSignalState.OK),
        MutableStateFlow(true),
        MutableStateFlow(true),
        MutableStateFlow(true),
        MutableStateFlow(false),
        MutableStateFlow<TerminalEcho?>(null),                    // ← NEW
    )
```

Rename `TenSources` to `ElevenSources` everywhere it appears in the file (existing tests use `TenSources` typename via the helper; with destructuring or named access there may be no other references — verify).

Update `collectInto`:

```kotlin
    private suspend fun ElevenSources.collectInto(emissions: MutableList<WarningId?>) =
        WarningCenterViewModel.aggregate(
            cameraPerm, ea, storage, th, pw, camState, mic, nt, bo, storageLowMidRec, autoStopEcho,
        ).collect { emissions += it }
```

Update `makeVm()`:

```kotlin
    private fun makeVm(notificationsGranted: Boolean = true): WarningCenterViewModel {
        val s = sources()
        s.nt.value = notificationsGranted
        return WarningCenterViewModel(
            cameraPermissionGranted = s.cameraPerm,
            exactAlarmGranted = s.ea,
            storageInsufficient = s.storage,
            thermal = s.th,
            power = s.pw,
            camera = s.camState,
            microphonePermissionGranted = s.mic,
            notificationsGranted = s.nt,
            batteryOptimizationExempt = s.bo,
            storageLowMidRec = s.storageLowMidRec,
            autoStopEcho = s.autoStopEcho,                        // ← NEW (positional 11th source)
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
    }
```

Now add the 2 new tests at the END of the class (after the existing `snoozeForever_invokes_callback_with_updated_set` test from 4.1c):

```kotlin

    // ──────────────────────────────────────────────────────────────────
    // Phase 4 Slice 2 — autoStopEcho source + dismissAutoStopEcho mutator
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun autoStopEcho_source_flow_drives_STORAGE_FULL_AUTOSTOPPED_into_activeWarning() {
        val s = sources()
        s.autoStopEcho.value = TerminalEcho("session-a", StopReason.LOW_STORAGE)
        val vm = WarningCenterViewModel(
            cameraPermissionGranted = s.cameraPerm,
            exactAlarmGranted = s.ea,
            storageInsufficient = s.storage,
            thermal = s.th,
            power = s.pw,
            camera = s.camState,
            microphonePermissionGranted = s.mic,
            notificationsGranted = s.nt,
            batteryOptimizationExempt = s.bo,
            storageLowMidRec = s.storageLowMidRec,
            autoStopEcho = s.autoStopEcho,
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        assertEquals(WarningId.STORAGE_FULL_AUTOSTOPPED, vm.activeWarning.value)
    }

    @Test
    fun dismissAutoStopEcho_mutator_invokes_onAutoStopDismissed_callback_with_sessionId() {
        val s = sources()
        val received = mutableListOf<String>()
        val vm = WarningCenterViewModel(
            cameraPermissionGranted = s.cameraPerm,
            exactAlarmGranted = s.ea,
            storageInsufficient = s.storage,
            thermal = s.th,
            power = s.pw,
            camera = s.camState,
            microphonePermissionGranted = s.mic,
            notificationsGranted = s.nt,
            batteryOptimizationExempt = s.bo,
            storageLowMidRec = s.storageLowMidRec,
            autoStopEcho = s.autoStopEcho,
            scope = CoroutineScope(Dispatchers.Unconfined),
            onAutoStopDismissed = { received += it },
        )
        vm.dismissAutoStopEcho("session-xyz")
        assertEquals(listOf("session-xyz"), received)
    }
```

Add to the imports at top of the test file:

```kotlin
import com.aritr.rova.data.StopReason
```

(`TerminalEcho` is in the same `com.aritr.rova.ui.warnings` package — no import needed.)

- [ ] **Step 4.2: Verify tests fail**

```
gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.warnings.WarningCenterAggregateTest"
```

Expect compile failures on:
- `WarningCenterViewModel(...) autoStopEcho = ...` (param doesn't exist)
- `WarningCenterViewModel(...) onAutoStopDismissed = ...` (param doesn't exist)
- `vm.dismissAutoStopEcho(...)` (method doesn't exist)
- `aggregate(...)` 11-arg overload (only 10-arg exists)

- [ ] **Step 4.3: Update `WarningCenterViewModel` ctor signature**

In `WarningCenterViewModel.kt`, the current class declaration ends with:

```kotlin
class WarningCenterViewModel(
    cameraPermissionGranted: StateFlow<Boolean>,
    exactAlarmGranted: StateFlow<Boolean>,
    storageInsufficient: StateFlow<Boolean>,
    thermal: StateFlow<ThermalStatus>,
    power: StateFlow<PowerState>,
    camera: StateFlow<CameraSignalState>,
    microphonePermissionGranted: StateFlow<Boolean>,
    notificationsGranted: StateFlow<Boolean>,
    batteryOptimizationExempt: StateFlow<Boolean>,
    storageLowMidRec: StateFlow<Boolean>,
    private val scope: CoroutineScope? = null,
    initialSnoozedIds: Set<WarningId> = emptySet(),
    private val onSnoozeChanged: ((Set<WarningId>) -> Unit)? = null,
) : ViewModel() {
```

Replace with:

```kotlin
class WarningCenterViewModel(
    cameraPermissionGranted: StateFlow<Boolean>,
    exactAlarmGranted: StateFlow<Boolean>,
    storageInsufficient: StateFlow<Boolean>,
    thermal: StateFlow<ThermalStatus>,
    power: StateFlow<PowerState>,
    camera: StateFlow<CameraSignalState>,
    microphonePermissionGranted: StateFlow<Boolean>,
    notificationsGranted: StateFlow<Boolean>,
    batteryOptimizationExempt: StateFlow<Boolean>,
    storageLowMidRec: StateFlow<Boolean>,
    autoStopEcho: StateFlow<TerminalEcho?>,                       // ← NEW (Phase 4 Slice 2 — 11th source)
    private val scope: CoroutineScope? = null,
    initialSnoozedIds: Set<WarningId> = emptySet(),
    private val onSnoozeChanged: ((Set<WarningId>) -> Unit)? = null,
    private val onAutoStopDismissed: ((String) -> Unit)? = null,  // ← NEW (Phase 4 Slice 2 callback)
) : ViewModel() {
```

- [ ] **Step 4.4: Add `dismissAutoStopEcho` mutator**

In `WarningCenterViewModel.kt`, after the `clearSnoozes` function (added in 4.1c), add:

```kotlin

    /**
     * Phase 4 Slice 2 — invoked from the WarningCenter Idle-branch overflow
     * router when the user taps "Don't show again" on the auto-stop echo
     * banner. Routes to the factory-wired callback which persists the
     * session id to `RovaSettings.dismissedAutoStopEchoIds` AND calls
     * `app.autoStopEchoSignal.markDismissed(sessionId)` to clear the
     * banner immediately.
     */
    fun dismissAutoStopEcho(sessionId: String) {
        onAutoStopDismissed?.invoke(sessionId)
    }
```

- [ ] **Step 4.5: Extend `aggregate()` combinator**

In `WarningCenterViewModel.kt`, find the `companion object { fun aggregate(...) ... }` block. Replace the existing `aggregate()` function (currently lines 136-177) with:

```kotlin
        /**
         * Combine the eleven source flows => highest-priority active
         * [WarningId] via [WarningPrecedence.resolve]. WarningCenterContract
         * NO-GO #6: a throw inside the combine logic logs and degrades to
         * `null` — a failure to compute a banner must not itself become a
         * banner.
         *
         * kotlinx-coroutines has typed `combine` overloads only up to five
         * flows. The six plain booleans are folded into a single upstream
         * `Bools6` combine (vararg). The four non-boolean flows
         * (thermal, power, camera, autoStopEcho — Phase 4 Slice 2 added
         * the last) are folded into a single `NonBools4` combine. Outer
         * 3-arg combine then resolves.
         */
        fun aggregate(
            cameraPermissionGranted: Flow<Boolean>,
            exactAlarmGranted: Flow<Boolean>,
            storageInsufficient: Flow<Boolean>,
            thermal: Flow<ThermalStatus>,
            power: Flow<PowerState>,
            camera: Flow<CameraSignalState>,
            microphonePermissionGranted: Flow<Boolean>,
            notificationsGranted: Flow<Boolean>,
            batteryOptimizationExempt: Flow<Boolean>,
            storageLowMidRec: Flow<Boolean>,
            autoStopEcho: Flow<TerminalEcho?>,                  // ← NEW (Phase 4 Slice 2; last)
        ): Flow<WarningId?> {
            val bools6: Flow<Bools6> = combine(
                cameraPermissionGranted,
                exactAlarmGranted,
                storageInsufficient,
                microphonePermissionGranted,
                notificationsGranted,
                storageLowMidRec,
            ) { arr: Array<Boolean> ->
                Bools6(arr[0], arr[1], arr[2], arr[3], arr[4], arr[5])
            }
            val nonBools4: Flow<NonBools4> = combine(
                thermal, power, camera, autoStopEcho,
            ) { th, pw, cm, ae ->
                NonBools4(th, pw, cm, ae)
            }
            return combine(bools6, batteryOptimizationExempt, nonBools4) { b, bo, n4 ->
                runCatching {
                    WarningPrecedence.resolve(
                        cameraPermissionGranted = b.cameraPermissionGranted,
                        exactAlarmGranted = b.exactAlarmGranted,
                        storageInsufficient = b.storageInsufficient,
                        thermal = n4.thermal,
                        power = n4.power,
                        camera = n4.camera,
                        microphonePermissionGranted = b.microphonePermissionGranted,
                        notificationsGranted = b.notificationsGranted,
                        batteryOptimizationExempt = bo,
                        storageLowMidRec = b.storageLowMidRec,
                        autoStopEcho = n4.autoStopEcho,
                    )
                }.getOrElse { e ->
                    Log.w("WarningCenter", "warning resolution failed", e)
                    null
                }
            }
        }
```

Add a new private class at file scope (below `Bools6`):

```kotlin
/** Phase 4 Slice 2 — packs 4 non-Boolean source flows so the outer combine stays at 3 typed args. */
private class NonBools4(
    val thermal: ThermalStatus,
    val power: PowerState,
    val camera: CameraSignalState,
    val autoStopEcho: TerminalEcho?,
)
```

Update the `_resolvedWarning` block to pass the new flow. Find the existing block:

```kotlin
    private val _resolvedWarning: StateFlow<WarningId?> =
        aggregate(
            cameraPermissionGranted, exactAlarmGranted, storageInsufficient,
            thermal, power, camera,
            microphonePermissionGranted, notificationsGranted, batteryOptimizationExempt,
            storageLowMidRec,
        ).stateIn(activeScope, SharingStarted.WhileSubscribed(5_000L), null)
```

Replace with:

```kotlin
    private val _resolvedWarning: StateFlow<WarningId?> =
        aggregate(
            cameraPermissionGranted, exactAlarmGranted, storageInsufficient,
            thermal, power, camera,
            microphonePermissionGranted, notificationsGranted, batteryOptimizationExempt,
            storageLowMidRec, autoStopEcho,                  // ← NEW (Phase 4 Slice 2; last)
        ).stateIn(activeScope, SharingStarted.WhileSubscribed(5_000L), null)
```

- [ ] **Step 4.6: Verify tests pass**

```
gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.warnings.WarningCenterAggregateTest"
```

Expect 13/0/0 (11 baseline + 2 new).

- [ ] **Step 4.7: Verify full test suite**

```
gradlew.bat :app:testDebugUnitTest
```

Expect total ≥ baseline + 11 (T1 3 + T2 6 + T3 2 + T4 2 = 13 new tests across all task files).

- [ ] **Step 4.8: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/warnings/WarningCenterViewModel.kt \
        app/src/test/java/com/aritr/rova/ui/warnings/WarningCenterAggregateTest.kt
git commit -m "feat(warnings): VM autoStopEcho source + dismissAutoStopEcho mutator (slice2 T4)

Phase 4 Slice 2 T4. WarningCenterViewModel gains 11th positional source
ctor param autoStopEcho + trailing optional onAutoStopDismissed
callback + dismissAutoStopEcho(sessionId) mutator. aggregate()
combinator restructured to pack 4 non-Bool flows (NonBools4) so the
outer combine stays at 3 typed args. 2 new aggregate tests; existing
11 stay green. Spec §4.8 / §4.9."
```

---

## Task 5: `WarningTopBannerV3` overflow slot + `WarningSheetContent` real arms + `ActionTarget` values

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/warnings/WarningTopBannerV3.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/warnings/WarningSheetContent.kt`

T3 added stub arms in WarningSheetContent + a stub `STORAGE_FULL_AUTOSTOPPED` arm in `midRecBannerContent`. T5 replaces them with the real copy + overflow menu + adds the new ActionTarget values + extends WarningTopBannerV3 with an overflow render slot.

- [ ] **Step 5.1: Extend `ActionTarget` enum**

In `WarningSheetContent.kt`, find:

```kotlin
internal enum class ActionTarget {
    EXACT_ALARM_SETTINGS,
    BATTERY_OPTIMIZATION,
    NOTIFICATION_SETTINGS,
    APP_DETAILS_SETTINGS,
    /** VM-only target — routes to [WarningCenterViewModel.snoozeForever]. NOT an Intent. */
    SNOOZE_FOREVER,
}
```

Replace with:

```kotlin
internal enum class ActionTarget {
    EXACT_ALARM_SETTINGS,
    BATTERY_OPTIMIZATION,
    NOTIFICATION_SETTINGS,
    APP_DETAILS_SETTINGS,
    /** VM-only target — routes to [WarningCenterViewModel.snoozeForever]. NOT an Intent. */
    SNOOZE_FOREVER,
    /** Phase 4 Slice 2 — Intent target: opens system storage settings (with APPLICATION_DETAILS fallback). */
    STORAGE_SETTINGS,
    /** Phase 4 Slice 2 — VM-only target: routes to [WarningCenterViewModel.dismissAutoStopEcho]. NOT an Intent. */
    DISMISS_AUTOSTOP_ECHO,
    /** Phase 4 Slice 2 — host-navigation target: opens History (host wires via onNavigateToHistory). NOT an Intent. */
    REVIEW_SESSION,
}
```

- [ ] **Step 5.2: Add `overflow` field to `TopBannerContent`**

In `WarningSheetContent.kt`, find:

```kotlin
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
```

Replace with:

```kotlin
internal data class TopBannerContent(
    val icon: ImageVector,
    val title: String,
    val sub: String,
    val cta: String,
    /**
     * Optional auto-action countdown — when non-null, the banner renders a
     * countdown ring instead of the trailing CTA pill. Phase 4.4 will wire a
     * real seconds-source; this slice ships a static placeholder.
     */
    val autoAction: AutoAction? = null,
    /**
     * Phase 4 Slice 2 — overflow ⋯ menu items (top-right of banner). Empty
     * list = no overflow icon rendered. Each action targets either an Intent
     * (`launchActionTarget`) or a VM-only target like
     * [ActionTarget.DISMISS_AUTOSTOP_ECHO] (handled by the routing call site).
     */
    val overflow: List<WarningAction> = emptyList(),
)
```

- [ ] **Step 5.3: Replace stub `STORAGE_FULL_AUTOSTOPPED` arm in `midRecBannerContent`**

T3 added a stub. Replace the stub arm with the real version (still inside `midRecBannerContent`):

```kotlin
    WarningId.STORAGE_FULL_AUTOSTOPPED -> TopBannerContent(
        Icons.Default.Storage, "Recording stopped",
        "Storage filled up.", "Free up space",
        overflow = listOf(
            WarningAction("Don't show again", ActionTarget.DISMISS_AUTOSTOP_ECHO),
            WarningAction("Review session", ActionTarget.REVIEW_SESSION),
        ),
    )
```

- [ ] **Step 5.4: Extend `WarningTopBannerV3` with overflow slot**

Replace the existing `WarningTopBannerV3` composable signature (currently lines 43-49) with:

```kotlin
@Composable
internal fun WarningTopBannerV3(
    content: TopBannerContent,
    severityColor: Color,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    /** Phase 4 Slice 2 — called when user taps an overflow menu item. Null = overflow ⋯ icon hidden. */
    onOverflow: ((ActionTarget) -> Unit)? = null,
) {
```

Inside the Row layout, after the CTA pill / countdown ring block (currently the trailing `if (autoAction != null) ... else { Row { ... CTA text ... } }`), add the overflow icon. Replace the entire `if (autoAction != null) { CountdownRing... } else { Row { ... CTA ... } }` block with:

```kotlin
        val autoAction = content.autoAction
        if (autoAction != null) {
            CountdownRing(
                secondsRemaining = autoAction.secondsRemaining,
                totalSeconds = 30,
                severityColor = severityColor,
            )
        } else {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(severityColor.copy(alpha = 0.20f))
                    .clickable { onAction() }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = content.cta.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = severityColor.copy(alpha = 0.95f),
                )
            }
        }

        // Phase 4 Slice 2 — overflow ⋯ icon rendered when content.overflow
        // is non-empty AND a handler is wired. Tapping opens a DropdownMenu
        // listing each WarningAction; selecting an item dispatches via
        // onOverflow with the action's ActionTarget.
        if (content.overflow.isNotEmpty() && onOverflow != null) {
            Box {
                var expanded by remember { mutableStateOf(false) }
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "More options",
                    tint = Color.White.copy(alpha = 0.55f),
                    modifier = Modifier
                        .size(20.dp)
                        .clickable { expanded = true },
                )
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    content.overflow.forEach { action ->
                        DropdownMenuItem(
                            text = { Text(action.label) },
                            onClick = {
                                expanded = false
                                onOverflow(action.target)
                            },
                        )
                    }
                }
            }
        }
```

Add to the imports block at top of `WarningTopBannerV3.kt`:

```kotlin
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
```

- [ ] **Step 5.5: Verify it compiles**

```
gradlew.bat :app:compileDebugKotlin
```

Expect compile errors in `WarningCenter.kt` `launchActionTarget` (the `when` over `ActionTarget` is exhaustive — 3 new values need arms). T6 fixes this; for now, run the test suite to confirm no regressions:

- [ ] **Step 5.6: Verify tests pass (where compilable)**

```
gradlew.bat :app:testDebugUnitTest
```

If gradle reports unrelated tests failing due to the WarningCenter compile error, that's expected — T6 fixes it. The pure-JVM tests in T1-T4 (which don't touch WarningCenter.kt) should be parseable. If the compile failure cascades and blocks the test task, defer test verification to after T6.

Pragmatically, do a quick local fix to launchActionTarget to keep T5 commit standalone-buildable: add the 3 new arms as `return` no-ops (T6 replaces with real impls):

In `WarningCenter.kt` `launchActionTarget`, change the `when (target) { ... }` to include:

```kotlin
        ActionTarget.SNOOZE_FOREVER -> return    // unreachable (guarded above) — for `when` exhaustiveness
        ActionTarget.STORAGE_SETTINGS,
        ActionTarget.DISMISS_AUTOSTOP_ECHO,
        ActionTarget.REVIEW_SESSION -> return    // Phase 4 Slice 2 — handled by overflow router in T6; placeholder here
```

After this, the build is green. T6 replaces the placeholder with the real implementations.

```
gradlew.bat :app:assembleDebug
```

Expect BUILD SUCCESSFUL.

- [ ] **Step 5.7: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/warnings/WarningTopBannerV3.kt \
        app/src/main/java/com/aritr/rova/ui/warnings/WarningSheetContent.kt \
        app/src/main/java/com/aritr/rova/ui/warnings/WarningCenter.kt
git commit -m "feat(warnings): banner overflow slot + real STORAGE_FULL_AUTOSTOPPED copy (slice2 T5)

Phase 4 Slice 2 T5. WarningTopBannerV3 gains optional onOverflow slot
+ DropdownMenu render when content.overflow is non-empty.
TopBannerContent gains overflow: List<WarningAction>. ActionTarget
gains STORAGE_SETTINGS + DISMISS_AUTOSTOP_ECHO + REVIEW_SESSION.
midRecBannerContent's STORAGE_FULL_AUTOSTOPPED arm replaced with real
copy (\"Recording stopped\" / \"Free up space\") + 2-item overflow
menu. launchActionTarget gets placeholder arms (T6 wires real impls).
Spec §4.11 / §4.12 / §4.13."
```

---

## Task 6: `WarningCenter` Idle routing + `RovaApp.autoStopEchoSignal` + `RecordScreen` lifecycle refresh + factory wiring

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/warnings/WarningCenter.kt`
- Modify: `app/src/main/java/com/aritr/rova/RovaApp.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt`

T6 wires the production paths: RovaApp lazy-inits the signal, RecordScreen refreshes it on resume, WarningCenter routes the new id to `WarningTopBannerV3` on Idle, factory threads the new params.

- [ ] **Step 6.1: Add `autoStopEchoSignal` to `RovaApp.kt`**

Find the existing lazy signal block in `app/src/main/java/com/aritr/rova/RovaApp.kt` (around lines 129-243). Add this new lazy property at the end of the signal cluster (after `storageLowMidRecSignal` at line 241):

```kotlin
    /**
     * Phase 4 Slice 2 — auto-stop echo signal. Reads the most-recent terminal
     * session manifest at lazy-init and surfaces it as a [TerminalEcho?],
     * filtered against the persistent dismissed-id set from [RovaSettings].
     * Refresh triggers: RecordScreen ON_RESUME (see RecordScreen.kt T6),
     * + future service terminal-transition observers.
     *
     * Spec: docs/superpowers/specs/2026-05-24-phase-4-slice2-storage-full-autostopped-design.md §4.5
     */
    val autoStopEchoSignal: SessionAutoStopEchoSignal by lazy {
        val settings = RovaSettings(this)
        SessionAutoStopEchoSignal(
            terminalEchoSource = { sessionStore.latestTerminalSession() },
            initialDismissedIds = settings.dismissedAutoStopEchoIds,
        )
    }
```

Imports added (alphabetical order):

```kotlin
import com.aritr.rova.data.RovaSettings
import com.aritr.rova.data.latestTerminalSession
import com.aritr.rova.ui.signals.SessionAutoStopEchoSignal
```

(`RovaSettings` may already be imported; verify before adding.) Note `sessionStore` is expected to already exist on `RovaApp` — verify by grep `val sessionStore` in `RovaApp.kt`. If named differently (e.g. `val sessions: SessionStore`), use that name. If a `SessionStore` is not yet on `RovaApp`, add one:

```kotlin
val sessionStore: SessionStore by lazy { SessionStore(externalRoot ?: filesDir) }
```

(Add `import com.aritr.rova.data.SessionStore` if needed. `externalRoot` is the existing per-app external dir reference seen in `StorageLowMidRecSignal.kt:67`.)

- [ ] **Step 6.2: Update `launchActionTarget` to handle `STORAGE_SETTINGS`**

In `WarningCenter.kt`, replace the `STORAGE_SETTINGS` placeholder arm (added in T5.6) with a real implementation. Find:

```kotlin
        ActionTarget.SNOOZE_FOREVER -> return
        ActionTarget.STORAGE_SETTINGS,
        ActionTarget.DISMISS_AUTOSTOP_ECHO,
        ActionTarget.REVIEW_SESSION -> return
```

Replace with:

```kotlin
        ActionTarget.SNOOZE_FOREVER -> return
        ActionTarget.STORAGE_SETTINGS ->
            Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS).let { primary ->
                if (primary.resolveActivity(context.packageManager) != null) primary
                else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
            }
        ActionTarget.DISMISS_AUTOSTOP_ECHO -> return    // VM-only target; routed by overflow handler in WarningCenter
        ActionTarget.REVIEW_SESSION -> return           // host-navigation target; routed at call site
```

Wait — the existing structure assigns the chosen `Intent` to `intent: Intent` then `context.startActivity(intent)`. The `STORAGE_SETTINGS` arm above is the only one returning a value mid-branch. Restructure the arm to fit the existing pattern. The clean shape is to compute the intent for STORAGE_SETTINGS and let it fall through to `startActivity`. Final replacement for the whole `when` block:

```kotlin
    val intent: Intent = when (target) {
        ActionTarget.EXACT_ALARM_SETTINGS ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri)
            else
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
        ActionTarget.BATTERY_OPTIMIZATION ->
            BatteryOptimizationHelper.buildRequestIntent(context.packageName)
        ActionTarget.NOTIFICATION_SETTINGS ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            else
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
        ActionTarget.APP_DETAILS_SETTINGS ->
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
        ActionTarget.STORAGE_SETTINGS -> {
            val primary = Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
            if (primary.resolveActivity(context.packageManager) != null) primary
            else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
        }
        ActionTarget.SNOOZE_FOREVER -> return                    // VM-only; guarded above
        ActionTarget.DISMISS_AUTOSTOP_ECHO -> return             // VM-only; routed by overflow handler
        ActionTarget.REVIEW_SESSION -> return                    // host-nav; routed at call site
    }
    try { context.startActivity(intent) } catch (_: ActivityNotFoundException) {}
```

Also update the early-return guard at the top of `launchActionTarget`. Current:

```kotlin
    if (target == ActionTarget.SNOOZE_FOREVER) return
```

Replace with:

```kotlin
    if (target == ActionTarget.SNOOZE_FOREVER) return
    if (target == ActionTarget.DISMISS_AUTOSTOP_ECHO) return
    if (target == ActionTarget.REVIEW_SESSION) return
```

- [ ] **Step 6.3: Wire the Idle-branch routing for STORAGE_FULL_AUTOSTOPPED**

In `WarningCenter.kt`, the current Idle branch is (around lines 58-93):

```kotlin
    if (hudState is RecordHudState.Idle) {
        // Idle branch — sheet / chip. TopBanner ids no-op here.
        if (surface == WarningSurface.TopBanner) return

        val dismissed by resolvedVm.dismissedWarnings.collectAsStateWithLifecycle()
        // ... rest of idle path ...
```

Replace the early `return` with the special-case for `STORAGE_FULL_AUTOSTOPPED`. Update the function signature first to accept a navigation callback for "Review session":

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WarningCenter(
    hudState: RecordHudState,
    onStopRecording: () -> Unit,
    modifier: Modifier = Modifier,
    vm: WarningCenterViewModel? = null,
    /** Phase 4 Slice 2 — host-wired callback for the echo banner's "Review session" overflow item. Null = item still rendered but tap is a no-op (the underlying ActionTarget.REVIEW_SESSION is a host-nav target). */
    onNavigateToHistory: (() -> Unit)? = null,
) {
```

Then update the Idle branch:

```kotlin
    if (hudState is RecordHudState.Idle) {
        // Idle branch — sheet / chip / echo-banner.
        if (surface == WarningSurface.TopBanner) {
            // Phase 4 Slice 2 — STORAGE_FULL_AUTOSTOPPED is the one TopBanner
            // id that renders on Idle (echo of past auto-stop). All other
            // TopBanner ids are active-HUD only and suppress here.
            if (id == WarningId.STORAGE_FULL_AUTOSTOPPED) {
                val autoStopState by resolvedVm.activeWarning.collectAsStateWithLifecycle()
                // The session id we'll dismiss is carried by autoStopEchoSignal.state
                // which the VM doesn't directly expose. Read it from the same
                // source the VM consumed by calling vm.activeAutoStopSessionId()
                // — but that method doesn't exist. Instead, route the dismiss
                // through a context-aware overflow lambda that calls
                // resolvedVm.dismissAutoStopEcho(sessionId) — we capture
                // sessionId from the signal directly via the app singleton.
                val autoStopEcho by app.autoStopEchoSignal.state.collectAsStateWithLifecycle()
                WarningTopBannerV3(
                    content = midRecBannerContent(id),
                    severityColor = RovaWarnings.advisory,
                    onAction = { launchActionTarget(context, ActionTarget.STORAGE_SETTINGS) },
                    onOverflow = { target ->
                        when (target) {
                            ActionTarget.DISMISS_AUTOSTOP_ECHO -> {
                                val sid = autoStopEcho?.sessionId ?: return@WarningTopBannerV3
                                resolvedVm.dismissAutoStopEcho(sid)
                            }
                            ActionTarget.REVIEW_SESSION -> onNavigateToHistory?.invoke()
                            else -> launchActionTarget(context, target)
                        }
                    },
                    modifier = modifier,
                )
                return
            }
            return
        }

        val dismissed by resolvedVm.dismissedWarnings.collectAsStateWithLifecycle()
        // ... rest of idle path UNCHANGED ...
```

Note the `RovaWarnings.advisory` color (vs `RovaWarnings.escalating` used by mid-rec banners) — the echo is ADVISORY-tier.

Also note: `autoStopState` is unused — remove that line (was a stub during plan drafting). Final form uses only `autoStopEcho` for sessionId capture.

Import additions to `WarningCenter.kt`:

```kotlin
// (RovaWarnings already imported)
```

- [ ] **Step 6.4: Update `buildWarningCenterViewModel` factory**

In `WarningCenter.kt`, replace the existing `buildWarningCenterViewModel` function (currently lines 143-164) with:

```kotlin
internal fun buildWarningCenterViewModel(app: RovaApp): WarningCenterViewModel {
    val settings = RovaSettings(app)
    val initialSnoozed: Set<WarningId> = settings.snoozedWarningIds
        .mapNotNull { runCatching { WarningId.valueOf(it) }.getOrNull() }
        .toSet()
    return WarningCenterViewModel(
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
        autoStopEcho = app.autoStopEchoSignal.state,                  // ← NEW (Phase 4 Slice 2)
        initialSnoozedIds = initialSnoozed,
        onSnoozeChanged = { set ->
            settings.snoozedWarningIds = set.map(WarningId::name).toSet()
        },
        onAutoStopDismissed = { sessionId ->                          // ← NEW (Phase 4 Slice 2)
            settings.dismissedAutoStopEchoIds = settings.dismissedAutoStopEchoIds + sessionId
            app.autoStopEchoSignal.markDismissed(sessionId)
        },
    )
}
```

- [ ] **Step 6.5: Wire ON_RESUME refresh in `RecordScreen.kt`**

In `app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt`, find where the `warningVm` hoist lives (added by 4.1c T4, around line 68-80). Immediately after the hoist block, insert:

```kotlin
        // Phase 4 Slice 2 — refresh the auto-stop echo signal on ON_RESUME
        // so a session that auto-stopped while the user was backgrounded
        // surfaces as soon as they return. The signal is reason-agnostic;
        // WarningPrecedence filters to LOW_STORAGE.
        val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                    warningCenterApp.autoStopEchoSignal.refresh()
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
```

Add imports (alphabetical order; if any already present, skip):

```kotlin
import androidx.compose.runtime.DisposableEffect
```

(`androidx.lifecycle.compose.LocalLifecycleOwner` and the LifecycleEventObserver / Lifecycle.Event imports are referenced via fully-qualified names above to avoid import churn in this large file. If the file already uses these classes via short names, prefer those.)

Find the two existing `WarningCenter(...)` call sites in RecordScreen (added by 4.1c T4 at lines ~563 + ~606) and pass the new `onNavigateToHistory` callback:

Idle branch:

```kotlin
                        WarningCenter(
                            hudState = RecordHudState.Idle,
                            onStopRecording = {},
                            vm = warningVm,
                            onNavigateToHistory = onNavigateToHistory,
                        )
```

(Note: `onNavigateToHistory` is an existing param on the surrounding `RecordScreen` composable used by `RecordRecoveryChip`. Verify it's in scope at this call site — if it is named differently, use that name.)

Active branch:

```kotlin
                            WarningCenter(
                                hudState = hudState,
                                onStopRecording = { viewModel.stopRecording() },
                                vm = warningVm,
                                onNavigateToHistory = onNavigateToHistory,
                            )
```

- [ ] **Step 6.6: Verify it compiles**

```
gradlew.bat :app:assembleDebug
```

Expect BUILD SUCCESSFUL.

- [ ] **Step 6.7: Verify no test regressions**

```
gradlew.bat :app:testDebugUnitTest
```

Expect total ≥ baseline + 13. Zero failures.

- [ ] **Step 6.8: Owner device smoke (post-commit)**

Hand to owner with the smoke script:

```
SMOKE — Phase 4 Slice 2 STORAGE_FULL_AUTOSTOPPED

PRECONDITION
1. Install fresh APK (uninstall first to reset runtime_prefs).
2. Use a device with limited storage OR a debug build that allows simulating storage-full mid-rec.

A. Echo banner appears after auto-stop
1. Fill storage close to empty (within 3 clips' headroom).
2. Start a 60s loop recording.
3. Service auto-stops mid-rec when storage falls below the segment-gate threshold.
4. HUD reverts to Idle.
5. STORAGE_FULL_AUTOSTOPPED banner appears at top of Record screen.

B. Primary CTA opens storage settings
1. Tap "FREE UP SPACE" on the banner.
2. System Storage settings (or APPLICATION_DETAILS fallback) opens.
3. Back to Rova → banner still present (no auto-dismiss).

C. Overflow → Don't show again → persistent dismiss
1. Tap the ⋯ overflow icon.
2. Tap "Don't show again".
3. Banner clears immediately.
4. Force-stop Rova → relaunch → banner does NOT reappear.

D. Overflow → Review session navigates to History
1. Provoke another auto-stop (new sessionId).
2. Banner reappears on Record Idle.
3. Tap ⋯ → "Review session".
4. History screen opens; the relevant recovery card is visible.

E. New auto-stop after dismissal
1. After dismissing in C, force another auto-stop (new sessionId).
2. Banner reappears for the new sessionId.

EXPECTED RESULT: all 5 paths pass.
```

Owner reports back. If smoke fails, return to relevant step.

- [ ] **Step 6.9: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/warnings/WarningCenter.kt \
        app/src/main/java/com/aritr/rova/RovaApp.kt \
        app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt
git commit -m "feat(record): wire STORAGE_FULL_AUTOSTOPPED echo end-to-end (slice2 T6)

Phase 4 Slice 2 T6. RovaApp gains lazy autoStopEchoSignal sourced
from SessionStore.latestTerminalSession(). WarningCenter Idle-branch
special-cases the new id to render WarningTopBannerV3 with the
'Free up space' CTA + 'Don't show again' / 'Review session' overflow.
launchActionTarget handles STORAGE_SETTINGS with INTERNAL_STORAGE
+ APPLICATION_DETAILS fallback. Factory threads onAutoStopDismissed
callback that persists to RovaSettings + calls signal.markDismissed.
RecordScreen ON_RESUME refreshes the signal. Spec §4.5 / §4.10 / §4.11 / §4.14."
```

---

## Task 7: ADR-0015 + `WarningCenterContract.md` §4.2 amendment + push + PR

**Files:**
- Create: `docs/adr/0015-storage-full-autostopped-echo.md`
- Modify: `docs/WarningCenterContract.md`

- [ ] **Step 7.1: Write ADR-0015**

Create `docs/adr/0015-storage-full-autostopped-echo.md`:

```markdown
# ADR-0015 — `STORAGE_FULL_AUTOSTOPPED` echo banner (Phase 4 Slice 2)

**Status:** Accepted (2026-05-24)
**Phase:** 4 (post-4.1c)
**Supersedes:** none (additive — fills `WarningCenterContract.md` §4.2 row C2.3 "read-only echo on record" surface that was specified but unwired).
**Related ADRs:** ADR-0006 (terminal manifest reasons), ADR-0007 (warning sheet model), ADR-0013 (warning re-skin v3 chrome canon), ADR-0014 (snooze persistence).

## Context

The service auto-stops mid-recording when `SegmentGate.compute()` detects insufficient storage for the next segment (ADR-0006 row 9). It writes `Terminated.KILLED_BY_SYSTEM` + `StopReason.LOW_STORAGE` to the session manifest. A recovery card appears on Library next launch.

But the user is left on Record-Idle without explanation. The HUD reverts from Recording to Idle silently. `WarningCenterContract.md` §4.2 row C2.3 calls for a "read-only echo on `record`" — that echo was not yet wired.

## Decision

Add a new `WarningId.STORAGE_FULL_AUTOSTOPPED` (ADVISORY tier, ordinal 12 — between `STORAGE_LOW_MID_REC` and `THERMAL_SEVERE`). New leaf signal `SessionAutoStopEchoSignal` exposes the most-recent terminal session as `TerminalEcho?`, filtered against a persistent dismissed-id set on `RovaSettings.dismissedAutoStopEchoIds`. `WarningPrecedence.resolve` adds a branch that returns the new id when `stopReason == LOW_STORAGE`. `WarningCenter` Idle-branch special-cases the id to render `WarningTopBannerV3` (the same chrome used by the active-HUD top banner) with the "Free up space" primary CTA and an overflow menu offering "Don't show again" (per-session-id persistent dismiss) + "Review session" (host-wired navigation to History).

## Survives / does NOT survive

Echo SURVIVES:
- cold start
- system reclaim (`onTrimMemory`)
- force-stop via Settings → Apps
- device reboot
- in-place APK update

Echo does NOT survive:
- explicit "Don't show again" tap (persistent per-session-id dismissal in `rova_runtime_prefs.dismissed_autostop_echo_ids`)
- uninstall + reinstall (backup-excluded; same policy as `mode` + `snoozedWarningIds`)
- a more-recent terminal session that is NOT `LOW_STORAGE` (the signal surfaces only the most-recent terminal; precedence filters to LOW_STORAGE)

## Implementation

- **Storage:** `RovaSettings.dismissedAutoStopEchoIds: Set<String>` on `rova_runtime_prefs` under key `dismissed_autostop_echo_ids`. Values are session ids.
- **Signal:** `SessionAutoStopEchoSignal(terminalEchoSource: () -> TerminalEcho?, initialDismissedIds: Set<String>)` exposes `state: StateFlow<TerminalEcho?>`. `markDismissed(id)` flips the flow when the current echo matches. Reason-agnostic — `WarningPrecedence` filters to `LOW_STORAGE`.
- **Source reader:** `SessionStore.latestTerminalSession(): TerminalEcho?` (extension function, sync) walks manifests, returns most-recent terminal.
- **Precedence:** `WarningPrecedence.resolve` gains 11th param `autoStopEcho: TerminalEcho?` with trailing-default `= null`. Branch at slot 12 returns the new id when `stopReason == LOW_STORAGE`.
- **VM:** `WarningCenterViewModel` ctor gains 11th source param `autoStopEcho` + trailing optional `onAutoStopDismissed: ((String) -> Unit)?`. New `dismissAutoStopEcho(sessionId)` mutator routes to the callback. `aggregate()` combinator restructured: 4 non-Bool flows (`thermal`, `power`, `camera`, `autoStopEcho`) packed into `NonBools4`; outer combine stays at 3 typed args.
- **Surface:** `WarningTopBannerV3` extended with optional `onOverflow: ((ActionTarget) -> Unit)?` slot and `content.overflow: List<WarningAction>` field. `WarningCenter` Idle-branch special-cases the new id to render this composable with `RovaWarnings.advisory` severity color (NOT `escalating` — echo is informational).
- **Refresh triggers:** `RecordScreen` `ON_RESUME` lifecycle event calls `app.autoStopEchoSignal.refresh()`. Future slices can add a service terminal-transition observer for same-process auto-stops.
- **Factory:** `buildWarningCenterViewModel(app)` threads `autoStopEcho = app.autoStopEchoSignal.state` + `onAutoStopDismissed = { id -> settings.dismissedAutoStopEchoIds += id; app.autoStopEchoSignal.markDismissed(id) }`.

## Consequences

- **Behavioural:** Users now see why the session stopped, on the same screen they're looking at. Removes a real source of confusion (silent revert to Idle).
- **No new schema:** persisted format is `Set<String>` keyed by session id. No new manifest fields.
- **`WarningId` ordinal shift:** rows 12-17 shift +1. `WarningPrecedence.resolve` uses no ordinal arithmetic. `WarningId.gatesStart` is by-name. `snoozedWarningIds` (ADR-0014) is by-name. No breakage.
- **Set growth:** `dismissedAutoStopEchoIds` grows by 1 per auto-stop event. Acceptable for v1.0 (rare event). Garbage collection deferred.

## Rejected alternatives

- **Bundle with SD_CARD_EJECTED** — rejected; `WarningCenterContract.md` §4.6 already demoted SD_CARD as out-of-scope for v1.0. Honor the contract.
- **At-stop flash on active HUD** — rejected; HUD reverts to Idle quickly (~1s) and a flash would barely register. The Idle echo gives the user time to read and act.
- **Auto-clear on next successful recording** — rejected for v1.0; explicit dismiss is clearer UX. A future slice can add auto-clear if telemetry shows users ignoring the banner.
- **Extending `RecordRecoveryChip`** — rejected; the chip is generic "N interrupted sessions" + Review CTA. Specializing it for LOW_STORAGE would bypass `WarningCenter` and split the routing model.
- **Per-id confirmation dialog on dismiss** — rejected; low-stakes single-tap, no destructive consequence.

## References

- Spec: `docs/superpowers/specs/2026-05-24-phase-4-slice2-storage-full-autostopped-design.md`
- Plan: `docs/superpowers/plans/2026-05-24-phase-4-slice2-storage-full-autostopped.md`
- ADR-0006 (terminal manifest reasons)
- ADR-0007 (warning sheet model)
- ADR-0013 (warning re-skin v3 chrome canon)
- ADR-0014 (snooze persistence)
- `docs/WarningCenterContract.md` §4.2 row C2.3
```

- [ ] **Step 7.2: Amend `WarningCenterContract.md` §4.2 row C2.3**

In `docs/WarningCenterContract.md`, find the §4.2 C2 table. The row C2.3 currently reads:

```
| C2.3 Storage full / aborted | "Recording Stopped" (storage-full variant) | `Hard` | shipped | `StopReason.LOW_STORAGE` per ADR 0006 row 9 — service writes terminal manifest with reason | Recovery card on `history` (matches Killed-by-system layout but with storage-full body); read-only echo on `record` | until user discards card | dismissible (the card; the underlying state is terminal) | n/a |
```

Replace the "Surface" column text and add an ADR citation. The new row:

```
| C2.3 Storage full / aborted | "Recording Stopped" (storage-full variant) | `Hard` (recovery card) / `Advisory` (echo) | shipped | `StopReason.LOW_STORAGE` per ADR 0006 row 9 — service writes terminal manifest with reason | Recovery card on `history` (matches Killed-by-system layout but with storage-full body); read-only echo on `record` via `WarningId.STORAGE_FULL_AUTOSTOPPED` (ADR-0015, Phase 4 Slice 2) | card until user discards; echo until "Don't show again" tap (per-session-id persistent dismiss) | dismissible (both) | n/a |
```

- [ ] **Step 7.3: Commit**

```bash
git add docs/adr/0015-storage-full-autostopped-echo.md docs/WarningCenterContract.md
git commit -m "docs(adr): ADR-0015 + WarningCenterContract §4.2 C2.3 amendment (slice2 T7)

Phase 4 Slice 2 T7. ADR-0015 documents the STORAGE_FULL_AUTOSTOPPED
echo canon (signal + precedence + surface + dismissal). Contract
§4.2 row C2.3 amended to reflect that the 'read-only echo on record'
surface is now shipped (was specified but unwired since Phase 1.D)."
```

- [ ] **Step 7.4: Push the branch**

```bash
git push -u origin phase4-slice2-storage-full-autostopped
```

- [ ] **Step 7.5: Open the PR**

```bash
gh pr create --title "Phase 4 Slice 2 — STORAGE_FULL_AUTOSTOPPED echo banner" --body "$(cat <<'EOF'
## Summary
- New `WarningId.STORAGE_FULL_AUTOSTOPPED` (ADVISORY tier, ordinal 12). Surfaces on Record-screen Idle when the most-recent terminal session was auto-stopped for `LOW_STORAGE`.
- `RovaSettings.dismissedAutoStopEchoIds` on backup-excluded `rova_runtime_prefs` tracks per-session-id persistent dismissals.
- `SessionAutoStopEchoSignal` exposes `StateFlow<TerminalEcho?>` filtered against the dismissed set. Reason-agnostic; `WarningPrecedence` filters to `LOW_STORAGE`.
- `WarningCenterViewModel` ctor gains 11th source `autoStopEcho` + trailing optional `onAutoStopDismissed` callback. `aggregate()` combinator restructured to pack 4 non-Bool flows.
- `WarningTopBannerV3` extended with optional `onOverflow` slot + `content.overflow` field. Renders ⋯ dropdown when both are present.
- `WarningCenter` Idle-branch special-cases the new id to render `WarningTopBannerV3` with `RovaWarnings.advisory` color + "Free up space" CTA + overflow "Don't show again" / "Review session".
- `RecordScreen` `ON_RESUME` refreshes the signal.
- ADR-0015 documents the canon; `WarningCenterContract.md` §4.2 row C2.3 amended.

## Test plan
- [x] `RovaSettingsTest` — 3 new tests (default empty / round-trip / setter replaces).
- [x] `SessionAutoStopEchoSignalTest` — 6 new tests (source-null / source-non-null / seed-dismissed / markDismissed-match / markDismissed-mismatch / refresh).
- [x] `WarningPrecedenceTest` — 2 new tests (fires on LOW_STORAGE / outranked by STORAGE_LOW_MID_REC).
- [x] `WarningCenterAggregateTest` — 2 new tests (source-flow drives id / dismiss callback receives sessionId).
- [x] Full `:app:testDebugUnitTest` green; `:app:assembleDebug` clean.
- [ ] Owner device smoke per [docs/superpowers/plans/2026-05-24-phase-4-slice2-storage-full-autostopped.md](docs/superpowers/plans/2026-05-24-phase-4-slice2-storage-full-autostopped.md) Task 6.8 (paths A-E).

## Notes
- Honors `WarningCenterContract.md` §4.6 — SD_CARD_EJECTED remains out of scope.
- Backup-excluded: dismissals reset on uninstall + reinstall (matches `mode` + `snoozedWarningIds` policy).
- No new `StopReason` / `Terminated` enum values — reuses `LOW_STORAGE` + `KILLED_BY_SYSTEM`.

## Spec / Plan / ADR
- Spec: \`docs/superpowers/specs/2026-05-24-phase-4-slice2-storage-full-autostopped-design.md\`
- Plan: \`docs/superpowers/plans/2026-05-24-phase-4-slice2-storage-full-autostopped.md\`
- ADR-0015: \`docs/adr/0015-storage-full-autostopped-echo.md\`

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 7.6: Return PR URL to owner**

Capture the PR URL. Hand to owner; do NOT merge (`gh pr merge owner-only`).

---

## Rollback

If a regression cannot be fixed forward in the same PR:

1. Owner closes the PR.
2. Local: `git checkout master && git branch -D phase4-slice2-storage-full-autostopped`.
3. No production data is touched until T6 (the VM wiring). T1-T5 are additive surface-only — reverting only those is a clean op.
4. The persisted `dismissedAutoStopEchoIds` key, once written, is ignored by pre-Slice-2 code paths. Rollback after T6 ships leaves the key on disk; uninstall reclaims.

---

## Self-review

### Spec coverage

| Spec section | Covered by |
|---|---|
| §4.1 Storage (RovaSettings.dismissedAutoStopEchoIds) | Task 1 (property + 3 tests) |
| §4.2 RovaSettings surface | Task 1 |
| §4.3 TerminalEcho value type | Task 2 (T2.1) |
| §4.4 SessionAutoStopEchoSignal | Task 2 (T2.5 + 6 tests at T2.3) |
| §4.5 RovaApp wiring | Task 6 (T6.1) + `latestTerminalSession()` reader at Task 2 (T2.2) |
| §4.6 WarningId precedence slot | Task 3 (T3.1) |
| §4.7 WarningPrecedence.resolve extension | Task 3 (T3.3) + 2 tests (T3.4) |
| §4.8 VM ctor + mutator | Task 4 (T4.3 + T4.4) |
| §4.9 aggregate() combinator extension | Task 4 (T4.5) |
| §4.10 Factory wiring | Task 6 (T6.4) |
| §4.11 Surface routing (Idle special-case) | Task 6 (T6.3) |
| §4.12 idleEchoBannerContent helper — covered by T5 reusing `midRecBannerContent` arm | Task 5 (T5.3) |
| §4.13 ActionTarget extensions | Task 5 (T5.1) + launchActionTarget impl Task 6 (T6.2) |
| §4.14 Refresh triggers | Task 6 (T6.5) |
| §5 Data flow | Verified by Task 6.8 device smoke |
| §6 Error handling | Task 1 (?: emptySet), Task 2 (recompute filters null), Task 3 (LOW_STORAGE filter), Task 6 (Intent fallback) |
| §7 Testing — RovaSettings 3 tests | Task 1.1 |
| §7 Testing — SessionAutoStopEchoSignal 6 tests | Task 2.3 |
| §7 Testing — WarningPrecedence 2 tests | Task 3.4 |
| §7 Testing — WarningCenterAggregate 2 tests | Task 4.1 |
| §8 Slice plan T1-T7 | Tasks 1-7 (1-to-1) |
| §9 Migration risk | Rollback section above + ctor compatibility verified in T4.3 |

### Placeholder scan

Searched for: "TBD", "TODO", "implement later", "fill in details", "add appropriate", "similar to Task". None present in implementation steps. The only `TODO`-like phrasing is in step 4.5's combinator restructure where the plan explicitly lays out both `Bools6` and `NonBools4` — no deferral.

The §4.12 spec mentions a separate `idleEchoBannerContent` helper. Plan opts to reuse `midRecBannerContent` instead — same shape, single helper, less duplication. Documented in §8 self-review row above.

### Type consistency

- `Set<String>` (RovaSettings) ↔ `Set<String>` (signal initialDismissedIds + markDismissed param) ↔ `String` (VM dismissAutoStopEcho param + onAutoStopDismissed callback param). All by-session-id.
- `TerminalEcho` package `com.aritr.rova.ui.warnings` (T2.1) ↔ imports in `WarningPrecedence` (T3.3 via local import), `SessionAutoStopEchoSignal` (T2.5), `LatestTerminalSession` (T2.2), `WarningCenterViewModel` (T4.5), test files.
- `autoStopEcho` is the consistent param name across `WarningPrecedence.resolve`, `WarningCenterViewModel` ctor, `aggregate()`, `NonBools4`, and factory call site. No drift.
- `dismissAutoStopEcho(sessionId: String)` ↔ `onAutoStopDismissed: ((String) -> Unit)?` ↔ `markDismissed(sessionId: String)` — all string-keyed, no enum shift.
- `ActionTarget.STORAGE_SETTINGS` / `DISMISS_AUTOSTOP_ECHO` / `REVIEW_SESSION` introduced in T5.1; consumed by `launchActionTarget` in T6.2 + overflow router in T6.3. All consistent.
- `latestTerminalSession()` defined in T2.2 (as extension on `SessionStore`); consumed in T6.1 (`sessionStore.latestTerminalSession()`). Plan adds `import com.aritr.rova.data.latestTerminalSession` in T6.1.

---

## Execution handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-24-phase-4-slice2-storage-full-autostopped.md`. Two execution options:

1. **Subagent-Driven (recommended)** — fresh subagent per task, review between tasks, fast iteration.
2. **Inline Execution** — execute tasks in this session using executing-plans, batch with checkpoints.

Which approach?

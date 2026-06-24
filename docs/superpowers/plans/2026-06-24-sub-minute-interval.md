# Sub-minute recording interval (30 s) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the recording-interval picker offer a **30 s** option below the current 1-minute floor, by migrating the interval unit from whole minutes to seconds end-to-end.

**Architecture:** The interval is currently modeled as `intervalMinutes: Int` across the manifest, prefs, presets, intent boundary, scheduler, and UI. We rename the source-of-truth to `intervalSeconds: Int` (the scheduler already works in seconds) and replace the wait stepper's minute-arithmetic with an ordered **allowed-values list** `[0, 30, 60, 120, …, 3600]`. The unit flip is inherently atomic across the data + service + ViewModel seam (the service constructs `SessionConfig` from the intent value, and `RecordViewModel` bridges prefs↔preset↔`start()`), so the pure leaf helpers (stepper, formatters) land first as dormant, independently-tested units, then one task flips the live currency. A `*60` bridge at the manifest task keeps every intermediate commit green **and** correct.

**Tech Stack:** Kotlin, Android, `org.json` persistence, JVM unit tests (`isReturnDefaultValues = true`), 46 custom `check*` Gradle gates.

## Global Constraints

- **All 46 static gates + full `:app:testDebugUnitTest` GREEN at EVERY commit.** Verify via `:app:assembleDebug` (fires the gates on `preBuild`), **NOT** `:app:lintDebug` (pre-existing VaultAndroidOps:267 NewApi lint failure is unrelated and RED).
- **Never edit a `check*` task to pass** — none asserts the interval unit; no gate edits are needed.
- **Schema bump = version + migration + tests; OLD manifests must still load.** Manifest `SCHEMA_VERSION` goes **12 → 13** (PR-6b set 12). `fromJson` reads the legacy `intervalMinutes` key × 60 when `intervalSeconds` is absent.
- **ADR-amend-first:** ADR-0033 lands before any code (Task 1).
- **Pure-helper extraction** for framework-touching logic; new feature lands its JVM tests in the same task.
- **Stacked on PR-6b** (`feat/player-wall-clock-playhead`, tip `9ba34a7`, schema 12). Branch: `feat/sub-minute-interval`. Merge train: PR-6b → intervals. Push/PR/merge **only on explicit owner GO**.
- **Subagents EDIT-ONLY**; the controller runs all gradle/tests/commits. Build WARM (no cache wipe).
- **Allowed wait values:** `listOf(0, 30) + (1..60).map { it * 60 }` → `[0, 30, 60, 120, …, 3600]`. `0` = None/Continuous. Max 3600 s (60 min) — same ceiling as today's `WAIT_MAX = 60` minutes.
- **30 s minimum** non-zero interval (15 s dropped — cues need the gap). **No cue-timing changes** in this PR.
- **Interval is end-to-start idle wait**, not a start-to-start period → **no interval-vs-duration guard**.
- **Formatter copy stays literal** (deliberate deviation from spec §4.2 — see Task 3 note): the existing `recordWaitValue`/`formatWait` siblings already return unlocalized literals (`"None"`, `"m"`, `"h"`) and are outside `checkNoHardcodedUiStrings` scope (it only matches `Text("`/`contentDescription="`). New string **resources** are added only where the value reaches a real `stringResource`/`pluralStringResource` call site (the preset screen-reader phrase, Task 5) — those require `en` + `es`.

---

### Task 1: ADR-0033 — interval unit minutes → seconds

**Files:**
- Create: `docs/adr/0033-interval-seconds-unit.md`

No code; documentation only. This is the amend-first record the other tasks implement.

- [ ] **Step 1: Write the ADR**

Create `docs/adr/0033-interval-seconds-unit.md`:

```markdown
# ADR-0033: Recording interval unit — minutes → seconds (30 s minimum)

**Status:** Accepted (2026-06-24)

## Context

The recording interval (idle WAIT between clips) was modeled as
`intervalMinutes: Int` — whole minutes only, so the smallest non-zero wait was
1 minute. Product wants a sub-minute option. `Int` minutes cannot express it,
and fractional-minute `Double` truncates on the `M_MINUTES.toLong()` intent path
and reads poorly. The scheduler already converts to seconds (`mMinutes * 60`),
so seconds is the natural canonical unit.

## Decision

1. The interval source-of-truth becomes **`intervalSeconds: Int`** everywhere it
   is persisted or crosses a process boundary: `SessionManifest.SessionConfig`,
   `RovaSettings`, `RovaPreset`/`BuiltInPresets`/`PresetJson`, the service intent
   extra, and the scheduler.
2. The smallest non-zero interval is **30 s**. 15 s is rejected: the start cue
   (`rova_cue_start`, ~3.5 s) plus reminder would consume too much of a 15 s gap.
   30 s accommodates the existing cues unchanged.
3. The wait picker is a **stepper over an ordered allowed-values list**
   `[0, 30, 60, 120, …, 3600]` (`0` = None/Continuous, then 30 s, then whole
   minutes to 60 min). It steps by index; direct/typed sets snap to the nearest
   allowed value.
4. Manifest schema **12 → 13**. `fromJson` reads the legacy `intervalMinutes`
   key × 60 when `intervalSeconds` is absent — old manifests load losslessly.
5. Prefs migrate to a **new** key `interval_seconds` (the old `interval` minutes
   key is NOT reinterpreted — `5` minutes must not become 5 seconds). Custom
   presets migrate `PresetJson` v2 → v3 (× 60 on read); built-in preset interval
   values are × 60. Custom-preset ids are preserved (the `custom.` short-circuit
   never re-hashes a migrated value).

## Consequences

- Old manifests, prefs, and presets migrate losslessly; display of historical
  recordings is unchanged (5 min → 300 s → "5 m").
- No interval-vs-duration guard: the interval is end-to-start idle wait.
- Cue behavior is unchanged at the 30 s floor; cue-timing rework is deferred.
- No new static gate (the unit is not an enforced invariant).
```

- [ ] **Step 2: Commit**

```bash
git add docs/adr/0033-interval-seconds-unit.md
git commit -m "docs(adr): ADR-0033 interval unit minutes->seconds, 30s minimum"
```

---

### Task 2: Wait stepper — allowed-values list (`RecordSettingBounds`)

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordSettingBounds.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/screens/RecordSettingBoundsWaitTest.kt` (create; if a `RecordSettingBoundsTest.kt` already exists, add the cases there instead — check first)

**Interfaces:**
- Consumes: nothing new.
- Produces: `WAIT_ALLOWED: List<Int>`, `nearestAllowedWait(value: Int): Int`. Existing wait signatures are **unchanged** so callers don't break: `stepWait(current: Int, dir: Int): Int`, `clampWait(v: Int): Int`, `waitAtMin(v: Int): Boolean`, `waitAtMax(v: Int): Boolean`. Their semantics change from minute-arithmetic to seconds-over-the-allowed-list. This task is dormant (values fed by callers are still minutes) until Task 5 flips the live currency; its unit tests exercise it with seconds directly.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/aritr/rova/ui/screens/RecordSettingBoundsWaitTest.kt`:

```kotlin
package com.aritr.rova.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordSettingBoundsWaitTest {

    @Test fun allowedList_shape() {
        val a = RecordSettingBounds.WAIT_ALLOWED
        assertEquals(0, a.first())
        assertEquals(30, a[1])
        assertEquals(60, a[2])
        assertEquals(120, a[3])
        assertEquals(3600, a.last())
        // strictly increasing, no duplicates
        assertTrue(a.zipWithNext().all { (x, y) -> y > x })
    }

    @Test fun stepWait_up_crossesSecondsToMinuteBoundary() {
        assertEquals(30, RecordSettingBounds.stepWait(0, +1))
        assertEquals(60, RecordSettingBounds.stepWait(30, +1))
        assertEquals(120, RecordSettingBounds.stepWait(60, +1))
    }

    @Test fun stepWait_down_crossesMinuteToSecondsBoundary() {
        assertEquals(60, RecordSettingBounds.stepWait(120, -1))
        assertEquals(30, RecordSettingBounds.stepWait(60, -1))
        assertEquals(0, RecordSettingBounds.stepWait(30, -1))
    }

    @Test fun stepWait_clampsAtEnds() {
        assertEquals(0, RecordSettingBounds.stepWait(0, -1))
        assertEquals(3600, RecordSettingBounds.stepWait(3600, +1))
    }

    @Test fun stepWait_fromOffGridValue_snapsThenSteps() {
        // 45 snaps to 30, then +1 -> 60
        assertEquals(60, RecordSettingBounds.stepWait(45, +1))
        // 50 snaps to 60, then -1 -> 30
        assertEquals(30, RecordSettingBounds.stepWait(50, -1))
    }

    @Test fun nearestAllowedWait_snapsToClosest_tieRoundsDown() {
        assertEquals(30, RecordSettingBounds.nearestAllowedWait(45)) // 45 -> 30 (tie: 15 vs 15, rounds down)
        assertEquals(60, RecordSettingBounds.nearestAllowedWait(50))
        assertEquals(0, RecordSettingBounds.nearestAllowedWait(10))  // 10 -> 0 (10<15)
        assertEquals(30, RecordSettingBounds.nearestAllowedWait(20)) // 20 -> 30 (20>15)
        assertEquals(0, RecordSettingBounds.nearestAllowedWait(-5))  // below floor
        assertEquals(3600, RecordSettingBounds.nearestAllowedWait(9999)) // above ceiling
    }

    @Test fun clampWait_snapsToAllowed() {
        assertEquals(30, RecordSettingBounds.clampWait(45))
        assertEquals(0, RecordSettingBounds.clampWait(0))
        assertEquals(3600, RecordSettingBounds.clampWait(99999))
    }

    @Test fun waitAtMin_atMax() {
        assertTrue(RecordSettingBounds.waitAtMin(0))
        assertFalse(RecordSettingBounds.waitAtMin(30))
        assertTrue(RecordSettingBounds.waitAtMax(3600))
        assertFalse(RecordSettingBounds.waitAtMax(60))
    }
}
```

- [ ] **Step 2: Run it to confirm failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.RecordSettingBoundsWaitTest"`
Expected: FAIL — `WAIT_ALLOWED` / `nearestAllowedWait` unresolved.

- [ ] **Step 3: Replace the wait block in `RecordSettingBounds.kt`**

Replace the `WAIT_MIN`/`WAIT_MAX` constants and the four wait functions (`stepWait`, `waitAtMin`, `waitAtMax`, `clampWait`) with the allowed-list implementation. Leave `CLIP_*`, `REPEATS_*`, `clipStep`, `stepClip`, `stepRepeats`, `clipAtMin/Max`, `repeatsAtMin/Max`, `clampClip`, `clampRepeats` untouched.

Remove:
```kotlin
    const val WAIT_MIN = 0
    const val WAIT_MAX = 60
```
and:
```kotlin
    fun stepWait(current: Int, dir: Int): Int =
        (current.coerceIn(WAIT_MIN, WAIT_MAX) + dir).coerceIn(WAIT_MIN, WAIT_MAX)
```
and:
```kotlin
    fun waitAtMin(v: Int): Boolean = v.coerceIn(WAIT_MIN, WAIT_MAX) <= WAIT_MIN
    fun waitAtMax(v: Int): Boolean = v.coerceIn(WAIT_MIN, WAIT_MAX) >= WAIT_MAX
```
and:
```kotlin
    fun clampWait(v: Int): Int = v.coerceIn(WAIT_MIN, WAIT_MAX)
```

Add (place the constant near the other `const`s, the functions where the wait functions were):
```kotlin
    /**
     * ADR-0033 — the wait/interval picker's ordered allowed values, in SECONDS.
     * `0` = None/Continuous, then the 30 s sub-minute step, then whole minutes to
     * 60 min. Steps move by index; off-grid values snap via [nearestAllowedWait].
     */
    val WAIT_ALLOWED: List<Int> = listOf(0, 30) + (1..60).map { it * 60 }

    /** The allowed wait value closest to [value]; ties round DOWN. Clamps to list bounds. */
    fun nearestAllowedWait(value: Int): Int {
        if (value <= WAIT_ALLOWED.first()) return WAIT_ALLOWED.first()
        if (value >= WAIT_ALLOWED.last()) return WAIT_ALLOWED.last()
        // minByOrNull keeps the FIRST minimal on ties -> the lower neighbor (round down).
        return WAIT_ALLOWED.minByOrNull { kotlin.math.abs(it - value) }!!
    }

    fun stepWait(current: Int, dir: Int): Int {
        val idx = WAIT_ALLOWED.indexOf(nearestAllowedWait(current))
        return WAIT_ALLOWED[(idx + dir).coerceIn(0, WAIT_ALLOWED.lastIndex)]
    }

    fun waitAtMin(v: Int): Boolean = nearestAllowedWait(v) <= WAIT_ALLOWED.first()
    fun waitAtMax(v: Int): Boolean = nearestAllowedWait(v) >= WAIT_ALLOWED.last()

    // Direct manual-entry / typed value snaps to the nearest allowed wait (ADR-0033).
    fun clampWait(v: Int): Int = nearestAllowedWait(v)
```

> Note on the tie rule: for `value = 45`, neighbors 30 and 60 are equidistant (15 each); `minByOrNull` returns the first minimal element it encounters, which is the lower (30). The test pins this.

- [ ] **Step 4: Run the test to confirm pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.RecordSettingBoundsWaitTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/RecordSettingBounds.kt app/src/test/java/com/aritr/rova/ui/screens/RecordSettingBoundsWaitTest.kt
git commit -m "feat(record): wait stepper as allowed-values list in seconds (ADR-0033)"
```

---

### Task 3: Seconds-aware wait formatters

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordSettingsFormat.kt` (`recordWaitValue`)
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/LibrarySessionConfigFormatters.kt` (`formatWait`)
- Test: `app/src/test/java/com/aritr/rova/ui/screens/SessionSettingsCardFormattersTest.kt` (add cases — verify file name; it's the existing home of `recordWaitValue` tests) and `app/src/test/java/com/aritr/rova/ui/screens/LibrarySessionConfigFormattersTest.kt` (add cases)

**Interfaces:**
- Both functions keep signature `(Int): String`; the parameter is now SECONDS. Dormant until Task 5 feeds them seconds.

**Note (deliberate spec deviation):** Spec §4.2 proposed an `record_wait_seconds` string resource. These two helpers already return unlocalized literals (`"None"`, `"$x m"`, `"1 h"`) and sit outside `checkNoHardcodedUiStrings` (which only matches `Text("`/`contentDescription="`). Adding a localized resource to one branch while the siblings stay literal is inconsistent and would not match existing behavior. We keep literals here for parity; the preset screen-reader phrase (a real `pluralStringResource` site) DOES get localized resources in Task 5. This deviation is surfaced to the owner at execution handoff.

- [ ] **Step 1: Write the failing tests**

Add to `SessionSettingsCardFormattersTest.kt`:
```kotlin
    @Test fun recordWaitValue_secondsUnit() {
        assertEquals("None", recordWaitValue(0))
        assertEquals("30 s", recordWaitValue(30))
        assertEquals("1 m", recordWaitValue(60))
        assertEquals("2 m", recordWaitValue(120))
        assertEquals("1 h", recordWaitValue(3600))
    }
```

Add to `LibrarySessionConfigFormattersTest.kt`:
```kotlin
    @Test fun formatWait_secondsUnit() {
        assertEquals("None", LibrarySessionConfigFormatters.formatWait(0))
        assertEquals("30 s", LibrarySessionConfigFormatters.formatWait(30))
        assertEquals("1 min", LibrarySessionConfigFormatters.formatWait(60))
        assertEquals("2 min", LibrarySessionConfigFormatters.formatWait(120))
        assertEquals("1 h", LibrarySessionConfigFormatters.formatWait(3600))
    }
```

> The existing `recordWaitValue`/`formatWait` tests in these files assume minutes — DELETE or rewrite those stale cases (e.g. an old `recordWaitValue(60) == "1 h"`). Read each test file and reconcile so no minutes-era assertion remains.

- [ ] **Step 2: Run to confirm failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.SessionSettingsCardFormattersTest" --tests "com.aritr.rova.ui.screens.LibrarySessionConfigFormattersTest"`
Expected: FAIL (new seconds cases; `recordWaitValue(60)` now expected "1 m" not "1 h").

- [ ] **Step 3: Rewrite `recordWaitValue`**

In `RecordSettingsFormat.kt`, replace:
```kotlin
internal fun recordWaitValue(intervalMinutes: Int): String = when {
    intervalMinutes <= 0 -> "None"
    intervalMinutes == 60 -> "1 h"
    intervalMinutes % 60 == 0 -> "${intervalMinutes / 60} h"
    else -> "$intervalMinutes m"
}
```
with:
```kotlin
internal fun recordWaitValue(intervalSeconds: Int): String = when {
    intervalSeconds <= 0 -> "None"
    intervalSeconds < 60 -> "$intervalSeconds s"
    intervalSeconds == 60 -> "1 m"
    intervalSeconds % 3600 == 0 -> "${intervalSeconds / 3600} h"
    intervalSeconds % 60 == 0 -> "${intervalSeconds / 60} m"
    else -> "$intervalSeconds s" // off-grid defensive; allowed list never yields this
}
```

- [ ] **Step 4: Rewrite `formatWait`**

In `LibrarySessionConfigFormatters.kt`, replace:
```kotlin
    fun formatWait(intervalMinutes: Int): String {
        val m = intervalMinutes.coerceAtLeast(0)
        if (m == 0) return "None"
        if (m < 60) return "$m min"
        val hours = m / 60
        val mins = m % 60
        return if (mins == 0) "$hours h" else "$hours h $mins min"
    }
```
with:
```kotlin
    fun formatWait(intervalSeconds: Int): String {
        val s = intervalSeconds.coerceAtLeast(0)
        if (s == 0) return "None"
        if (s < 60) return "$s s"
        if (s % 3600 == 0) return "${s / 3600} h"
        val minutes = s / 60
        val rem = s % 60
        return if (rem == 0) "$minutes min" else "$minutes min $rem s"
    }
```

Also update the KDoc lines in `LibrarySessionConfigFormatters.kt` that say "**Wait** is `SessionConfig.intervalMinutes`" → "`SessionConfig.intervalSeconds`".

- [ ] **Step 5: Run to confirm pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.SessionSettingsCardFormattersTest" --tests "com.aritr.rova.ui.screens.LibrarySessionConfigFormattersTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/RecordSettingsFormat.kt app/src/main/java/com/aritr/rova/ui/screens/LibrarySessionConfigFormatters.kt app/src/test/java/com/aritr/rova/ui/screens/SessionSettingsCardFormattersTest.kt app/src/test/java/com/aritr/rova/ui/screens/LibrarySessionConfigFormattersTest.kt
git commit -m "feat(format): seconds-aware wait formatters (ADR-0033)"
```

---

### Task 4: Manifest schema 12 → 13 — `SessionConfig.intervalSeconds`

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/data/SessionManifest.kt` (`SessionConfig` field + `toJson` + `fromJson` + `SCHEMA_VERSION`)
- Modify: `app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt:1288` (the only `SessionConfig(...)` construction — **bridge** write)
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/LibrarySessionConfigDialog.kt:70` (reader)
- Modify version-pin tests: `app/src/test/java/com/aritr/rova/data/SessionManifestModeMigrationTest.kt` (`SCHEMA_VERSION is 12` → 13), `app/src/test/java/com/aritr/rova/data/SessionManifestVaultTest.kt` (`schemaVersion_isTwelve` → thirteen). Check `SessionManifestSchemaCompatTest.kt` and `SessionConfigModeTest.kt` for any `intervalMinutes` / schema-12 assertions and reconcile.
- Test: `app/src/test/java/com/aritr/rova/data/SessionConfigIntervalSecondsTest.kt` (create)

**Interfaces:**
- Produces: `SessionConfig.intervalSeconds: Int` (replaces `intervalMinutes`). After this task the manifest persists seconds; the live in-app currency is still minutes (Task 5 flips it). The service bridges via `* 60` so the persisted value is correct seconds.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/aritr/rova/data/SessionConfigIntervalSecondsTest.kt`:
```kotlin
package com.aritr.rova.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionConfigIntervalSecondsTest {

    private fun cfg(intervalSeconds: Int) =
        SessionConfig(durationSeconds = 10, intervalSeconds = intervalSeconds, resolution = "HD", loopCount = 10)

    @Test fun roundTrip_writesIntervalSeconds() {
        val json = cfg(30).toJson()
        assertEquals(30, json.getInt("intervalSeconds"))
        assertEquals(30, SessionConfig.fromJson(json).intervalSeconds)
    }

    @Test fun legacyManifest_readsMinutesTimes60() {
        // schema<=12 manifest: only intervalMinutes present.
        val json = JSONObject().apply {
            put("durationSeconds", 10)
            put("intervalMinutes", 5)
            put("resolution", "HD")
            put("loopCount", 10)
        }
        assertEquals(300, SessionConfig.fromJson(json).intervalSeconds)
    }

    @Test fun newKeyWins_whenBothPresent() {
        val json = JSONObject().apply {
            put("durationSeconds", 10)
            put("intervalMinutes", 5)   // legacy, must be ignored
            put("intervalSeconds", 30)  // canonical
            put("resolution", "HD")
            put("loopCount", 10)
        }
        assertEquals(30, SessionConfig.fromJson(json).intervalSeconds)
    }

    @Test fun schemaVersion_isThirteen() {
        assertEquals(13, SessionManifest.SCHEMA_VERSION)
    }
}
```

- [ ] **Step 2: Run to confirm failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.data.SessionConfigIntervalSecondsTest"`
Expected: FAIL — `intervalSeconds` param unresolved.

- [ ] **Step 3: Rename the field + JSON**

In `SessionManifest.kt`, `SessionConfig`:
- data-class param `val intervalMinutes: Int,` → `val intervalSeconds: Int,`
- `toJson`: `put("intervalMinutes", intervalMinutes)` → `put("intervalSeconds", intervalSeconds)`
- `fromJson`: `intervalMinutes = json.getInt("intervalMinutes"),` → `intervalSeconds = if (json.has("intervalSeconds")) json.getInt("intervalSeconds") else json.getInt("intervalMinutes") * 60,`

- [ ] **Step 4: Bump `SCHEMA_VERSION` to 13**

Find `const val SCHEMA_VERSION = 12` and change to `13`. Add a history comment `// 12 -> 13: ADR-0033 interval unit minutes -> seconds (intervalSeconds).` next to it.

- [ ] **Step 5: Bridge the service write**

In `RovaRecordingService.kt:1288`, change:
```kotlin
                    intervalMinutes = mMinutes.toInt(),
```
to:
```kotlin
                    // ADR-0033 bridge: mMinutes is still minutes here (flipped to
                    // mSeconds in the interval-unit task); persist canonical seconds.
                    intervalSeconds = mMinutes.toInt() * 60,
```

- [ ] **Step 6: Fix the dialog reader**

In `LibrarySessionConfigDialog.kt:70`, change `config.intervalMinutes` → `config.intervalSeconds`.

- [ ] **Step 7: Update version-pin + reconcile tests**

- `SessionManifestVaultTest.kt`: rename `schemaVersion_isTwelve` → `schemaVersion_isThirteen`, body `assertEquals(13, SessionManifest.SCHEMA_VERSION)`, update the `// 11 -> 12` comment to `// 12 -> 13: ADR-0033 intervalSeconds.`. The `baseManifest()` uses positional `SessionConfig(10, 1, "HD", 10)` — the 2nd arg is now `intervalSeconds`; `1` is a harmless value, leave it.
- `SessionManifestModeMigrationTest.kt`: `SCHEMA_VERSION is 12` test → `assertEquals(13, …)` and update the comment chain to prepend `12 -> 13: ADR-0033 intervalSeconds`. The `v3ConfigJson()` helper writes `put("intervalMinutes", 1)` — that's a deliberate **legacy** v3-manifest fixture, so KEEP `intervalMinutes` there (it exercises the legacy read path), do not rename it.
- Read `SessionManifestSchemaCompatTest.kt` and `SessionConfigModeTest.kt`; update any `intervalMinutes =` named-arg constructions to `intervalSeconds =` (values can stay; semantics shift is fine for these structural tests) and any schema-version assertion to 13.

- [ ] **Step 8: Run the targeted tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.data.SessionConfigIntervalSecondsTest" --tests "com.aritr.rova.data.SessionManifest*" --tests "com.aritr.rova.data.SessionConfigModeTest"`
Expected: PASS. (Controller runs the FULL suite + `assembleDebug` before marking the task complete.)

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/aritr/rova/data/SessionManifest.kt app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt app/src/main/java/com/aritr/rova/ui/screens/LibrarySessionConfigDialog.kt app/src/test/java/com/aritr/rova/data/
git commit -m "feat(data): SessionConfig.intervalSeconds, schema 12->13 legacy x60 read (ADR-0033)"
```

---

### Task 5: Live-currency flip — prefs, presets, intent, scheduler, ViewModels

This is the atomic semantic switch: everything that operates in the live "interval value" currency moves from minutes to seconds in one commit. It removes the Task-4 `*60` bridge.

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/data/RovaSettings.kt` — `intervalMinutes` → `intervalSeconds` (new key + one-shot migration), `hasAnyRecordingPref`
- Modify: `app/src/main/java/com/aritr/rova/data/RovaPreset` (in `RovaSettings.kt`) — `interval` → `intervalSeconds`
- Modify: `app/src/main/java/com/aritr/rova/data/BuiltInPresets.kt` — interval values × 60
- Modify: `app/src/main/java/com/aritr/rova/data/PresetJson.kt` — VERSION 2 → 3, version-aware × 60 decode, encode `intervalSeconds`
- Modify: `app/src/main/java/com/aritr/rova/data/PresetMatcher.kt` — param `interval` → `intervalSeconds`
- Modify: `app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt` — `mMinutes` → `mSeconds`, intent `M_SECONDS` (+ `M_MINUTES` fallback), `start()` signature, scheduler `*60` drop (1573), `beepStart` param, manifest write (drop bridge)
- Modify: `app/src/main/java/com/aritr/rova/service/audio/BeepPolicy.kt` — param `intervalMinutes` → `intervalSeconds`
- Modify: `app/src/main/java/com/aritr/rova/RovaApp.kt:934-937` — pass `intervalSeconds`
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt:669-672` — `start()` call
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordViewModel.kt` — `settings.intervalSeconds`, `preset.intervalSeconds`, save/seed/apply
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/SettingsViewModel.kt` — `settings.intervalSeconds`
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/SettingsSheet.kt:1524` — preset-CD seconds-aware (new plural)
- Modify: `app/src/main/res/values/strings.xml` + `app/src/main/res/values-es/strings.xml` — new `preset_cd_every_seconds` plural
- Modify tests: `BuiltInPresetsTest.kt`, `PresetMatcherTest.kt`, `BeepPolicyTest.kt`, `RovaSettingsTest.kt`
- Test (create): `app/src/test/java/com/aritr/rova/data/PresetJsonMigrationTest.kt`

> The UI composable parameter named `intervalMinutes` in `RecordChrome.kt`, `SettingsSheet.kt`, `FloatingSettingsPanel.kt`, `RecordScreen.kt`, and `SettingsViewModel`'s `MutableStateFlow` named `intervalMinutes` may be left named as-is (they're internal labels; the compiler doesn't care) OR renamed for clarity. **Rename them to `intervalSeconds` where touched** to avoid a misleading name holding seconds — but renaming the `SettingsViewModel.intervalMinutes` StateFlow is a public-ish VM property; check its collectors in the Settings UI and rename consistently. Do NOT rename if a collector is outside this task's file set and would break — leave a `// holds seconds (ADR-0033)` comment instead.

**Interfaces:**
- Consumes: `WAIT_ALLOWED`/`nearestAllowedWait` (Task 2), seconds formatters (Task 3), `SessionConfig.intervalSeconds` (Task 4).
- Produces: `RovaSettings.intervalSeconds`, `RovaPreset.intervalSeconds`, `RovaRecordingService.start(intervalSeconds: Int, …)`, intent extra `M_SECONDS`.

- [ ] **Step 1: Write failing tests (prefs migration)**

Read `RovaSettingsTest.kt` first to match its fixture style (how it builds a `Context`/prefs — likely Robolectric? NO — JVM. Check whether it uses a fake. If the existing tests construct `RovaSettings(context)` they must already have a Context seam; mirror it). Add:
```kotlin
    @Test fun intervalSeconds_migratesLegacyMinutesOnce() {
        // old install: "interval"=5 (minutes) in rova_settings, no "interval_seconds"
        legacyPrefs().edit().putInt("interval", 5).apply()
        val s = RovaSettings(context)
        assertEquals(300, s.intervalSeconds)            // 5 min -> 300 s
        // migration persisted to the new key
        assertEquals(300, rovaPrefs().getInt("interval_seconds", -1))
    }

    @Test fun intervalSeconds_defaultsTo60_whenNoKeys() {
        assertEquals(60, RovaSettings(context).intervalSeconds)
    }

    @Test fun intervalSeconds_newKeyWins_overLegacy() {
        legacyPrefs().edit().putInt("interval", 5).apply()
        rovaPrefs().edit().putInt("interval_seconds", 30).apply()
        assertEquals(30, RovaSettings(context).intervalSeconds)
    }
```
> Adapt `legacyPrefs()`/`rovaPrefs()`/`context` to whatever helpers the existing `RovaSettingsTest` already uses for `rova_settings`. If the file doesn't exist or doesn't have a Context, note it in your report and pin the migration via a smaller pure helper instead (extract `migrateIntervalSeconds(hasNew, newVal, legacyVal): Int` and unit-test that pure function).

- [ ] **Step 2: Write failing tests (preset JSON v2 → v3 migration + id stability)**

Create `app/src/test/java/com/aritr/rova/data/PresetJsonMigrationTest.kt`:
```kotlin
package com.aritr.rova.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetJsonMigrationTest {

    @Test fun v3RoundTrip_secondsPreserved() {
        val p = RovaPreset(name = "Half", duration = 10, intervalSeconds = 30, loopCount = 5,
            resolution = "HD", id = "custom.abc", isBuiltIn = false)
        val back = PresetJson.decode(PresetJson.encode(listOf(p)))
        assertEquals(1, back.size)
        assertEquals(30, back[0].intervalSeconds)
        assertEquals("custom.abc", back[0].id) // id preserved
    }

    @Test fun legacyV2Envelope_intervalMinutesScaledToSeconds() {
        // v2 writer stored interval in MINUTES under presetSchemaVersion=2.
        val v2 = JSONObject().apply {
            put("presetSchemaVersion", 2)
            put("presets", org.json.JSONArray().put(JSONObject().apply {
                put("id", "custom.deadbeef")
                put("name", "Two")
                put("duration", 10)
                put("interval", 2)        // 2 MINUTES
                put("loopCount", 5)
                put("resolution", "HD")
            }))
        }.toString()
        val back = PresetJson.decode(v2)
        assertEquals(120, back[0].intervalSeconds)        // 2 min -> 120 s
        assertEquals("custom.deadbeef", back[0].id)       // explicit custom id NOT re-hashed
    }

    @Test fun legacyArray_noVersion_treatedAsMinutes() {
        val arr = "[{\"id\":\"custom.x\",\"name\":\"Min\",\"duration\":10,\"interval\":1,\"loopCount\":5,\"resolution\":\"HD\"}]"
        assertEquals(60, PresetJson.decode(arr)[0].intervalSeconds) // 1 min -> 60 s
    }

    @Test fun blankId_derivesStableCustomId() {
        val arr = "[{\"name\":\"NoId\",\"duration\":10,\"interval\":1,\"loopCount\":5,\"resolution\":\"HD\"}]"
        val id = PresetJson.decode(arr)[0].id
        assertTrue(id.startsWith("custom."))
    }
}
```

- [ ] **Step 3: Write failing tests (builtins, matcher, beep)**

- `BuiltInPresetsTest.kt`: assert the migrated seconds — Quick Sample `intervalSeconds == 60`, Standard `== 120`, Long Session `== 300`, Continuous `== 0`. Update any existing `interval == 1/2/5` assertions.
- `PresetMatcherTest.kt`: update calls — the `interval` arg is now seconds; a config of `(duration, intervalSeconds=120, loopCount=20, "FHD")` matches Standard. Rename the named arg if used.
- `BeepPolicyTest.kt`: rename `intervalMinutes =` → `intervalSeconds =`; the `== 0` suppression case is unchanged (continuous), add a `intervalSeconds = 30` → beeps case.

- [ ] **Step 4: Run all new/changed tests to confirm failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.data.PresetJsonMigrationTest" --tests "com.aritr.rova.data.BuiltInPresetsTest" --tests "com.aritr.rova.data.PresetMatcherTest" --tests "com.aritr.rova.service.audio.BeepPolicyTest" --tests "com.aritr.rova.data.RovaSettingsTest"`
Expected: FAIL (unresolved `intervalSeconds` / wrong values).

- [ ] **Step 5: `RovaPreset` field rename**

In `RovaSettings.kt`, `data class RovaPreset`: `val interval: Int,` → `val intervalSeconds: Int,`. Keep the KDoc accurate.

- [ ] **Step 6: `RovaSettings` — new key + migration + `hasAnyRecordingPref`**

Replace:
```kotlin
    var intervalMinutes: Int
        get() = prefs.getInt("interval", 1)
        set(value) = prefs.edit { putInt("interval", value) }
```
with:
```kotlin
    /**
     * ADR-0033 — recording interval in SECONDS. The legacy `"interval"` key
     * stored MINUTES and must NOT be reinterpreted (a stored `5` is 5 minutes,
     * not 5 seconds), so seconds live under a NEW key `"interval_seconds"`. On
     * first read after the update, the legacy minutes value is migrated once
     * (× 60) into the new key. Default 60 s (the old 1-minute default).
     */
    var intervalSeconds: Int
        get() {
            if (prefs.contains("interval_seconds")) return prefs.getInt("interval_seconds", 60)
            val migrated = prefs.getInt("interval", 1) * 60
            prefs.edit { putInt("interval_seconds", migrated) }
            return migrated
        }
        set(value) = prefs.edit { putInt("interval_seconds", value) }
```
And update `hasAnyRecordingPref()`:
```kotlin
    fun hasAnyRecordingPref(): Boolean =
        prefs.contains("duration") || prefs.contains("interval") || prefs.contains("interval_seconds") ||
            prefs.contains("loop_count") || prefs.contains("resolution")
```
> If `RovaSettingsTest` proved a pure helper is cleaner (Step 1 fallback), extract `internal fun migrateIntervalSeconds(...)` and call it from the getter.

- [ ] **Step 7: `BuiltInPresets` — × 60**

```kotlin
        RovaPreset(name = "Quick Sample", duration = 10, intervalSeconds = 60, loopCount = 10,
            resolution = QualityPresets.FHD, id = "builtin.quick_sample", isBuiltIn = true),
        RovaPreset(name = "Standard", duration = 30, intervalSeconds = 120, loopCount = 20,
            resolution = QualityPresets.FHD, id = DEFAULT_ID, isBuiltIn = true),
        RovaPreset(name = "Long Session", duration = 60, intervalSeconds = 300, loopCount = 50,
            resolution = QualityPresets.HD, id = "builtin.long_session", isBuiltIn = true),
        RovaPreset(name = "Continuous", duration = 60, intervalSeconds = 0,
            loopCount = RecordSettingBounds.REPEATS_CONTINUOUS,
            resolution = QualityPresets.HD, id = "builtin.continuous", isBuiltIn = true),
```

- [ ] **Step 8: `PresetJson` — VERSION 3, version-aware decode, encode `intervalSeconds`**

- `private const val VERSION = 2` → `3`.
- `encode`: `put("interval", p.interval)` → `put("interval", p.intervalSeconds)` (keep the JSON key name `"interval"`; its meaning is now seconds, disambiguated by `presetSchemaVersion=3`).
- `decode`: determine the source version and scale:
```kotlin
    fun decode(raw: String): List<RovaPreset> {
        val trimmed = raw.trim()
        // Source unit: arrays (pre-envelope) and envelope v<3 stored MINUTES; v>=3 stores SECONDS.
        val version: Int
        val array: JSONArray = try {
            when {
                trimmed.startsWith("[") -> { version = 1; JSONArray(trimmed) }
                trimmed.startsWith("{") -> {
                    val root = JSONObject(trimmed)
                    version = root.optInt("presetSchemaVersion", 1)
                    root.optJSONArray("presets") ?: JSONArray()
                }
                else -> return emptyList()
            }
        } catch (e: Exception) {
            return emptyList()
        }
        val intervalToSeconds: (Int) -> Int = if (version >= 3) { v -> v } else { v -> v * 60 }
        val out = ArrayList<RovaPreset>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val preset = try {
                RovaPreset(
                    name = obj.getString("name"),
                    duration = obj.getInt("duration"),
                    intervalSeconds = intervalToSeconds(obj.getInt("interval")),
                    loopCount = obj.getInt("loopCount"),
                    resolution = obj.getString("resolution"),
                    id = "",
                    isBuiltIn = false,
                )
            } catch (e: Exception) {
                continue
            }
            out.add(preset.copy(id = ensureCustomId(obj.optString("id", ""), preset)))
        }
        return out
    }
```
- `ensureCustomId`: update the hash key field name `${p.interval}` → `${p.intervalSeconds}`. **Critical:** the `if (rawId.startsWith("custom.")) return rawId` short-circuit is what preserves legacy ids across the migration — do NOT remove it. (A stored v2 custom always has a `custom.<hash>` id, so it is returned verbatim and never re-hashed.)

- [ ] **Step 9: `PresetMatcher` — param rename**

Rename the `interval: Int` parameter to `intervalSeconds: Int` in both `match` overloads and `matchActive`, and the comparison `p.interval == interval` → `p.intervalSeconds == intervalSeconds`. Pure value compare — callers must pass seconds (they will, post-flip).

- [ ] **Step 10: `BeepPolicy` — param rename**

`intervalMinutes: Int` → `intervalSeconds: Int`; `intervalMinutes == 0` → `intervalSeconds == 0`. Update the KDoc (`SessionConfig.intervalMinutes` → `intervalSeconds`; "0 means continuous" still holds).

- [ ] **Step 11: `RovaRecordingService` — `mSeconds`, intent, scheduler, beep, manifest**

- Field `private var mMinutes = 10L` → `private var mSeconds = 60L`.
- `start()` companion: param `mMinutes: Float` → `intervalSeconds: Int`; `putExtra("M_MINUTES", mMinutes)` → `putExtra("M_SECONDS", intervalSeconds)`.
- `onStartCommand` read (1091): replace
  `mMinutes = intent.getFloatExtra("M_MINUTES", 10f).toLong()`
  with
```kotlin
        mSeconds = if (intent.hasExtra("M_SECONDS")) {
            intent.getIntExtra("M_SECONDS", 60).toLong()
        } else {
            // ADR-0033 fallback: tolerate an in-flight pre-update M_MINUTES start.
            (intent.getFloatExtra("M_MINUTES", 1f).toLong()) * 60
        }
```
- Manifest write (1288): drop the Task-4 bridge — `intervalSeconds = mMinutes.toInt() * 60,` → `intervalSeconds = mSeconds.toInt(),`.
- `beepStart` call (1473): `beepStart(mMinutes.toInt(), …)` → `beepStart(mSeconds.toInt(), …)`.
- `beepStart` def (4377): param `intervalMinutes: Int` → `intervalSeconds: Int`; the `shouldPlayBeep(intervalMinutes = intervalMinutes)` arg → `intervalSeconds = intervalSeconds`.
- Scheduler (1573): `val waitSeconds = (mMinutes * 60).toInt().coerceAtLeast(0)` → `val waitSeconds = mSeconds.toInt().coerceAtLeast(0)`. Update the nearby comment that says "a 1 m interval … waits a full 60 s" only if it now reads wrong; the end-to-start contract paragraph stays valid.

- [ ] **Step 12: `RovaApp` + `RecordScreen` — `start()` call sites**

- `RovaApp.kt:937`: `mMinutes = s.intervalMinutes.toFloat(),` → `intervalSeconds = s.intervalSeconds,`.
- `RecordScreen.kt:672`: the positional `viewModel.interval.value.toFloat()` arg → `viewModel.interval.value` (now Int seconds; match the new `start()` signature — if `start()` uses named params at this call site, use `intervalSeconds = viewModel.interval.value`). Read 669-680 to get the exact call shape before editing.

- [ ] **Step 13: `RecordViewModel` + `SettingsViewModel`**

- `RecordViewModel.kt`: `MutableStateFlow(settings.intervalMinutes)` → `settings.intervalSeconds` (L117); `interval.collect { settings.intervalMinutes = it }` → `settings.intervalSeconds = it` (L180); `settings.intervalMinutes = std.interval` → `settings.intervalSeconds = std.intervalSeconds` (L216); `interval.value = std.interval` → `std.intervalSeconds` (L220); `interval.value = settings.intervalMinutes` → `settings.intervalSeconds` (L243); save-custom `interval = i` → `intervalSeconds = i` (L379); `interval.value = preset.interval` → `preset.intervalSeconds` (L407). The `matchActive(..., interval.value, ...)` (L425) needs no rename if the param is positional; if named, update.
- `SettingsViewModel.kt`: L46/125/143 `settings.intervalMinutes` → `settings.intervalSeconds`. If the StateFlow stays named `intervalMinutes`, add `// holds seconds (ADR-0033)`; preferred is rename to `intervalSeconds` + update its collectors in `SettingsScreen`/`SettingsSheet` wiring (grep `viewModel.intervalMinutes` / the param threading).

- [ ] **Step 14: Preset screen-reader phrase — seconds-aware (`SettingsSheet:1521`)**

Add the new plural. In `app/src/main/res/values/strings.xml`, beside `preset_cd_every_minutes`:
```xml
    <plurals name="preset_cd_every_seconds">
        <item quantity="one">every %1$d second</item>
        <item quantity="other">every %1$d seconds</item>
    </plurals>
```
In `app/src/main/res/values-es/strings.xml`, beside the es `preset_cd_every_minutes`:
```xml
    <plurals name="preset_cd_every_seconds">
        <item quantity="one">cada %1$d segundo</item>
        <item quantity="other">cada %1$d segundos</item>
    </plurals>
```
Then in `SettingsSheet.kt`, replace the `waitPhrase` block:
```kotlin
    val waitPhrase = if (p.interval <= 0) {
        stringResource(R.string.preset_cd_no_gap)
    } else {
        pluralStringResource(R.plurals.preset_cd_every_minutes, p.interval, p.interval)
    }
```
with:
```kotlin
    val waitPhrase = when {
        p.intervalSeconds <= 0 -> stringResource(R.string.preset_cd_no_gap)
        p.intervalSeconds < 60 ->
            pluralStringResource(R.plurals.preset_cd_every_seconds, p.intervalSeconds, p.intervalSeconds)
        else -> {
            val mins = p.intervalSeconds / 60
            pluralStringResource(R.plurals.preset_cd_every_minutes, mins, mins)
        }
    }
```
Also the `SettingsSummary(intervalMinutes: Int, …)` param (L1192) holds seconds now — rename to `intervalSeconds` and update its one call site + the `if (intervalSeconds > 0)` guard (the value flows to `recordWaitValue`, already seconds-aware from Task 3).

- [ ] **Step 15: Sweep remaining `intervalMinutes` UI params**

Compile will flag the rest. For each composable param still named `intervalMinutes` that now carries seconds (`RecordChrome.kt:346`, `SettingsSheet.kt:132/237/390/606`, `FloatingSettingsPanel.kt:131`, `RecordScreen.kt:1087/1220/1263`), rename to `intervalSeconds` and update the threading. These pass straight into `recordWaitValue(...)` / `RecordSettingBounds.*Wait(...)`, all already seconds-ready. Do NOT change behavior — pure rename.

- [ ] **Step 16: Build + full suite (controller)**

The controller runs:
```
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL (all 46 gates) + full suite GREEN (no minutes-era assertion left). Fix any straggler call site the sweep missed.

- [ ] **Step 17: Commit**

```bash
git add -A
git commit -m "feat(record): flip interval unit to seconds end-to-end; 30s minimum (ADR-0033)"
```

---

## Self-Review (controller, before dispatch)

- **Spec coverage:** D1 representation → Tasks 4/5. D2 30 s min → WAIT_ALLOWED (T2). D3 stepper allowed list + snap → T2. D4 schema 13 stacked → T4. D5 ADR → T1. Migrations §2.1 manifest → T4; §2.2 prefs new key → T5/6; §2.3 presets v3 + id stability → T5/8; §2.4 intent M_SECONDS + fallback → T5/11. Scheduler §3 → T5/11. Picker/formatters §4 → T2/T3. Tests §6 → each task's test step.
- **Deliberate deviation (flag to owner):** spec §4.2's `record_wait_seconds` resource is NOT added; the pure formatters keep literals for sibling consistency (they're outside `checkNoHardcodedUiStrings`). Localized resources are added only at the real `pluralStringResource` site (preset CD, T5/14).
- **Type consistency:** `intervalSeconds: Int` used identically in `SessionConfig`, `RovaSettings`, `RovaPreset`, `PresetMatcher`, `BeepPolicy`; intent key `M_SECONDS` (Int); `start(intervalSeconds: Int)`; service field `mSeconds: Long`.
- **Green-each-commit:** T2/T3 are pure + signature-stable (dormant). T4 bridges the service write `*60` so the manifest is correct seconds while live currency is still minutes. T5 removes the bridge and flips everything atomically. No intermediate non-compiling tree.

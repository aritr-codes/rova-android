# Sub-minute recording interval (30 s) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the recording-interval picker offer a **30 s** option below the current 1-minute floor, by migrating the interval unit from whole minutes to seconds end-to-end.

**Architecture:** The interval is modeled as `intervalMinutes: Int` across the manifest, prefs, presets, intent boundary, scheduler, and UI. We rename the source-of-truth to `intervalSeconds: Int` (the scheduler already works in seconds) and replace the wait stepper's minute-arithmetic with an ordered **allowed-values list** `[0, 30, 60, 120, …, 3600]`. The interval is a single global *currency* touching live input + live display + persistence simultaneously, so it cannot be flipped in leaf-first slices (a stepper or formatter that flips ahead of the data model would operate on the still-minutes live value and ship wrong values — confirmed by codex review). It decomposes into exactly two independent clusters: the **manifest / history-display cluster** (`SessionConfig` + `LibrarySessionConfigFormatters.formatWait` + the read-only history dialog) and the **live record-settings cluster** (`RovaSettings` + `RovaPreset` + the stepper + `recordWaitValue` + scheduler + intent + ViewModels). They touch *different* functions on *different* values, so the manifest cluster lands first (self-consistent, with a `*60` bridge at the sole service constructor), then the live cluster flips atomically and removes the bridge.

**Tech Stack:** Kotlin, Android, `org.json` persistence, JVM unit tests (`isReturnDefaultValues = true`), 46 custom `check*` Gradle gates.

## Global Constraints

- **All 46 static gates + full `:app:testDebugUnitTest` GREEN at EVERY commit; each commit also behaviorally correct** (no green-but-wrong intermediate). Verify via `:app:assembleDebug` (fires the gates on `preBuild`), **NOT** `:app:lintDebug` (pre-existing VaultAndroidOps:267 NewApi lint failure is unrelated and RED).
- **Never edit a `check*` task to pass** — none asserts the interval unit; no gate edits are needed.
- **Schema bump = version + migration + tests; OLD manifests must still load.** Manifest `SCHEMA_VERSION` goes **12 → 13** (PR-6b set 12). `fromJson` reads the legacy `intervalMinutes` key × 60 when `intervalSeconds` is absent.
- **ADR-amend-first:** ADR-0033 lands before any code (Task 1).
- **Pure-helper extraction** for framework-touching logic; new feature lands its JVM tests in the same task.
- **Stacked on PR-6b** (`feat/player-wall-clock-playhead`, tip `9ba34a7`, schema 12). Branch: `feat/sub-minute-interval`. Merge train: PR-6b → intervals. Push/PR/merge **only on explicit owner GO**.
- **Subagents EDIT-ONLY**; the controller runs all gradle/tests/commits. Build WARM (no cache wipe).
- **Allowed wait values:** `listOf(0, 30) + (1..60).map { it * 60 }` → `[0, 30, 60, 120, …, 3600]`. `0` = None/Continuous. Max 3600 s (60 min) — same ceiling as today's `WAIT_MAX = 60` minutes.
- **30 s minimum** non-zero interval (15 s dropped — cues need the gap). **No cue-timing changes** in this PR.
- **Interval is end-to-start idle wait**, not a start-to-start period → **no interval-vs-duration guard**.
- **Prefs migration is atomic at construction**, not a read-that-writes: a one-shot migration runs in the `RovaSettings` `init {}` block (joining the existing legacy-`mode` cleanup), guarded by a presence check, so getters are pure reads (codex finding 5).
- **Formatter copy stays literal** (deliberate deviation from spec §4.2): `recordWaitValue`/`formatWait` already return unlocalized literals (`"None"`, `"m"`, `"h"`) and are outside `checkNoHardcodedUiStrings` (which only matches `Text("`/`contentDescription="`). New string **resources** are added only where the value reaches a real `pluralStringResource` call site (the preset screen-reader phrase, Task 3) — those require `en` + `es`.

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
   key is NOT reinterpreted — `5` minutes must not become 5 seconds), via a
   one-shot guarded migration at `RovaSettings` construction. Custom presets
   migrate `PresetJson` v2 → v3 (× 60 on read); built-in preset interval values
   are × 60. Custom-preset ids are preserved (the `custom.` short-circuit never
   re-hashes a migrated value); a stored custom that lacks a valid `custom.*` id
   has no identity guarantee and may be re-derived (acceptable).

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

### Task 2: Manifest + history-display cluster — `SessionConfig.intervalSeconds`

This cluster is self-contained: the persisted manifest field and the **read-only History "View Settings" dialog** that displays it. It uses `LibrarySessionConfigFormatters.formatWait` — a *different* function from the live-settings `recordWaitValue`, on a *different* value — so flipping it here is correct while the live record-settings path stays entirely on minutes until Task 3. The service (the sole `SessionConfig` constructor) **bridges**: `mMinutes` is still minutes, so it writes `intervalSeconds = mMinutes.toInt() * 60`, persisting correct seconds.

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/data/SessionManifest.kt` (`SessionConfig` field + `toJson` + `fromJson` + `SCHEMA_VERSION`)
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/LibrarySessionConfigFormatters.kt` (`formatWait` → seconds-aware + KDoc)
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/LibrarySessionConfigDialog.kt:70` (reader → `config.intervalSeconds`)
- Modify: `app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt:1288` (the only `SessionConfig(...)` construction — **bridge** write `mMinutes.toInt() * 60`)
- Modify version-pin tests: `app/src/test/java/com/aritr/rova/data/SessionManifestModeMigrationTest.kt` (`SCHEMA_VERSION is 12` → 13), `app/src/test/java/com/aritr/rova/data/SessionManifestVaultTest.kt` (`schemaVersion_isTwelve` → thirteen). Check `SessionManifestSchemaCompatTest.kt` and `SessionConfigModeTest.kt` and reconcile any schema-12 / `intervalMinutes` assertions.
- Modify test: `app/src/test/java/com/aritr/rova/ui/screens/LibrarySessionConfigFormattersTest.kt` (seconds cases; delete stale minutes cases)
- Test (create): `app/src/test/java/com/aritr/rova/data/SessionConfigIntervalSecondsTest.kt`

**Interfaces:**
- Produces: `SessionConfig.intervalSeconds: Int` (replaces `intervalMinutes`); manifest persists seconds; `formatWait(Int /* seconds */)`.
- Does NOT touch: `RovaSettings`, `RovaPreset`, `RecordSettingBounds`, `recordWaitValue`, the scheduler, the intent, or the ViewModels (all stay minutes until Task 3).

- [ ] **Step 1: Write the failing manifest test**

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
        val json = JSONObject().apply {
            put("durationSeconds", 10)
            put("intervalMinutes", 5)   // schema<=12: only the legacy key
            put("resolution", "HD")
            put("loopCount", 10)
        }
        assertEquals(300, SessionConfig.fromJson(json).intervalSeconds)
    }

    @Test fun newKeyWins_whenBothPresent() {
        val json = JSONObject().apply {
            put("durationSeconds", 10)
            put("intervalMinutes", 5)
            put("intervalSeconds", 30)
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

- [ ] **Step 2: Write the failing formatter test**

In `app/src/test/java/com/aritr/rova/ui/screens/LibrarySessionConfigFormattersTest.kt`, add (and delete any stale minutes-era `formatWait` assertion, e.g. `formatWait(60) == "1 h"`):
```kotlin
    @Test fun formatWait_secondsUnit() {
        assertEquals("None", LibrarySessionConfigFormatters.formatWait(0))
        assertEquals("30 s", LibrarySessionConfigFormatters.formatWait(30))
        assertEquals("1 min", LibrarySessionConfigFormatters.formatWait(60))
        assertEquals("2 min", LibrarySessionConfigFormatters.formatWait(120))
        assertEquals("1 h", LibrarySessionConfigFormatters.formatWait(3600))
    }
```

- [ ] **Step 3: Run to confirm failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.data.SessionConfigIntervalSecondsTest" --tests "com.aritr.rova.ui.screens.LibrarySessionConfigFormattersTest"`
Expected: FAIL — `intervalSeconds` unresolved; seconds formatter cases wrong.

- [ ] **Step 4: Rename the `SessionConfig` field + JSON + schema**

In `SessionManifest.kt`, `SessionConfig`:
- param `val intervalMinutes: Int,` → `val intervalSeconds: Int,`
- `toJson`: `put("intervalMinutes", intervalMinutes)` → `put("intervalSeconds", intervalSeconds)`
- `fromJson`: `intervalMinutes = json.getInt("intervalMinutes"),` → `intervalSeconds = if (json.has("intervalSeconds")) json.getInt("intervalSeconds") else json.getInt("intervalMinutes") * 60,`
- `const val SCHEMA_VERSION = 12` → `13`, with comment `// 12 -> 13: ADR-0033 interval unit minutes -> seconds (intervalSeconds).`

- [ ] **Step 5: Make `formatWait` seconds-aware**

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
Update the KDoc line "**Wait** is `SessionConfig.intervalMinutes`" → "`SessionConfig.intervalSeconds`".

- [ ] **Step 6: Fix the dialog reader**

`LibrarySessionConfigDialog.kt:70`: `config.intervalMinutes` → `config.intervalSeconds`.

- [ ] **Step 7: Bridge the service write**

`RovaRecordingService.kt:1288`, change:
```kotlin
                    intervalMinutes = mMinutes.toInt(),
```
to:
```kotlin
                    // ADR-0033 bridge: mMinutes is still minutes here (flipped to
                    // mSeconds in the live-currency task); persist canonical seconds.
                    intervalSeconds = mMinutes.toInt() * 60,
```

- [ ] **Step 8: Update version-pin + reconcile tests**

- `SessionManifestVaultTest.kt`: rename `schemaVersion_isTwelve` → `schemaVersion_isThirteen`, body `assertEquals(13, …)`, update the `// 11 -> 12` comment to `// 12 -> 13: ADR-0033 intervalSeconds.`. `baseManifest()` uses positional `SessionConfig(10, 1, "HD", 10)` — the 2nd arg is now `intervalSeconds`; `1` is harmless, leave it.
- `SessionManifestModeMigrationTest.kt`: `SCHEMA_VERSION is 12` → `assertEquals(13, …)`, prepend `12 -> 13: ADR-0033 intervalSeconds` to the comment chain. The `v3ConfigJson()` helper writes `put("intervalMinutes", 1)` — that is a deliberate **legacy** fixture exercising the legacy read path, so KEEP it.
- Read `SessionManifestSchemaCompatTest.kt` and `SessionConfigModeTest.kt`; update any `intervalMinutes =` named-arg to `intervalSeconds =` and any schema assertion to 13.

- [ ] **Step 9: Run targeted tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.data.SessionConfig*" --tests "com.aritr.rova.data.SessionManifest*" --tests "com.aritr.rova.ui.screens.LibrarySessionConfigFormattersTest"`
Expected: PASS. (Controller runs the FULL suite + `assembleDebug` before marking complete.)

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/aritr/rova/data/SessionManifest.kt app/src/main/java/com/aritr/rova/ui/screens/LibrarySessionConfigFormatters.kt app/src/main/java/com/aritr/rova/ui/screens/LibrarySessionConfigDialog.kt app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt app/src/test/java/com/aritr/rova/data/ app/src/test/java/com/aritr/rova/ui/screens/LibrarySessionConfigFormattersTest.kt
git commit -m "feat(data): SessionConfig.intervalSeconds, schema 12->13 legacy x60 read; history formatter (ADR-0033)"
```

---

### Task 3: Live-currency flip — stepper, prefs, presets, intent, scheduler, ViewModels

The atomic semantic switch for the live record-settings cluster. Everything that operates on the live "interval value" — the stepper, `recordWaitValue`, `RovaSettings`, presets, the intent, the scheduler, and the ViewModels — moves from minutes to seconds in one commit, and the Task-2 service `*60` bridge is removed. These are mutually coupled through `RecordViewModel` (which bridges prefs↔preset↔`start()`) and through the live value feeding both the stepper and `recordWaitValue`, so they cannot be split without shipping a green-but-incorrect intermediate (codex-confirmed).

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordSettingBounds.kt` — wait stepper → allowed-values list
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordSettingsFormat.kt` — `recordWaitValue` → seconds-aware
- Modify: `app/src/main/java/com/aritr/rova/data/RovaSettings.kt` — `intervalMinutes` → `intervalSeconds` (new key + init migration), `RovaPreset.interval` → `intervalSeconds`, `hasAnyRecordingPref`
- Modify: `app/src/main/java/com/aritr/rova/data/BuiltInPresets.kt` — interval values × 60
- Modify: `app/src/main/java/com/aritr/rova/data/PresetJson.kt` — VERSION 2 → 3, version-aware × 60 decode, encode seconds
- Modify: `app/src/main/java/com/aritr/rova/data/PresetMatcher.kt` — param `interval` → `intervalSeconds`
- Modify: `app/src/main/java/com/aritr/rova/service/audio/BeepPolicy.kt` — param `intervalMinutes` → `intervalSeconds`
- Modify: `app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt` — `mMinutes` → `mSeconds`, intent `M_SECONDS` (+ `M_MINUTES` fallback), `start()` signature, scheduler `*60` drop (1573), `beepStart` (1473 + 4377), manifest write (drop Task-2 bridge → `mSeconds.toInt()`)
- Modify: `app/src/main/java/com/aritr/rova/RovaApp.kt:934-937` — `start()` call
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt:669-672` — `start()` call
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordViewModel.kt` — `settings.intervalSeconds`, `preset.intervalSeconds`, save/seed/apply
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/SettingsViewModel.kt` — `settings.intervalSeconds`
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/SettingsSheet.kt` — `SettingsSummary` param + preset-CD seconds-aware (new plural)
- Modify (rename pass): `RecordChrome.kt`, `FloatingSettingsPanel.kt` (composable `intervalMinutes` params that now hold seconds)
- Modify: `app/src/main/res/values/strings.xml` + `app/src/main/res/values-es/strings.xml` — new `preset_cd_every_seconds` plural
- Modify tests: `BuiltInPresetsTest.kt`, `PresetMatcherTest.kt`, `BeepPolicyTest.kt`, `RovaSettingsTest.kt`, `SessionSettingsCardFormattersTest.kt`
- Test (create): `app/src/test/java/com/aritr/rova/ui/screens/RecordSettingBoundsWaitTest.kt`, `app/src/test/java/com/aritr/rova/data/PresetJsonMigrationTest.kt`

**Interfaces:**
- Consumes: `SessionConfig.intervalSeconds` + seconds `formatWait` (Task 2).
- Produces: `RecordSettingBounds.WAIT_ALLOWED`/`nearestAllowedWait`, `RovaSettings.intervalSeconds`, `RovaPreset.intervalSeconds`, `recordWaitValue(Int /* seconds */)`, `RovaRecordingService.start(intervalSeconds: Int, …)`, intent extra `M_SECONDS`.

- [ ] **Step 1: Write failing stepper tests**

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
        assertEquals(0, a.first()); assertEquals(30, a[1]); assertEquals(60, a[2])
        assertEquals(120, a[3]); assertEquals(3600, a.last())
        assertTrue(a.zipWithNext().all { (x, y) -> y > x })
    }

    @Test fun stepWait_up() {
        assertEquals(30, RecordSettingBounds.stepWait(0, +1))
        assertEquals(60, RecordSettingBounds.stepWait(30, +1))
        assertEquals(120, RecordSettingBounds.stepWait(60, +1))
    }

    @Test fun stepWait_down() {
        assertEquals(60, RecordSettingBounds.stepWait(120, -1))
        assertEquals(30, RecordSettingBounds.stepWait(60, -1))
        assertEquals(0, RecordSettingBounds.stepWait(30, -1))
    }

    @Test fun stepWait_clampsAtEnds() {
        assertEquals(0, RecordSettingBounds.stepWait(0, -1))
        assertEquals(3600, RecordSettingBounds.stepWait(3600, +1))
    }

    @Test fun stepWait_fromOffGrid_snapsThenSteps() {
        assertEquals(60, RecordSettingBounds.stepWait(45, +1)) // 45->30, +1->60
        assertEquals(30, RecordSettingBounds.stepWait(50, -1)) // 50->60, -1->30
    }

    @Test fun nearestAllowedWait_tieRoundsDown_andClamps() {
        assertEquals(30, RecordSettingBounds.nearestAllowedWait(45)) // tie -> lower
        assertEquals(60, RecordSettingBounds.nearestAllowedWait(50))
        assertEquals(0, RecordSettingBounds.nearestAllowedWait(10))
        assertEquals(30, RecordSettingBounds.nearestAllowedWait(20))
        assertEquals(0, RecordSettingBounds.nearestAllowedWait(-5))
        assertEquals(3600, RecordSettingBounds.nearestAllowedWait(9999))
    }

    @Test fun clampWait_snaps() {
        assertEquals(30, RecordSettingBounds.clampWait(45))
        assertEquals(3600, RecordSettingBounds.clampWait(99999))
    }

    @Test fun waitAtMin_atMax() {
        assertTrue(RecordSettingBounds.waitAtMin(0)); assertFalse(RecordSettingBounds.waitAtMin(30))
        assertTrue(RecordSettingBounds.waitAtMax(3600)); assertFalse(RecordSettingBounds.waitAtMax(60))
    }
}
```

- [ ] **Step 2: Write failing formatter test**

In `app/src/test/java/com/aritr/rova/ui/screens/SessionSettingsCardFormattersTest.kt`, add (delete stale minutes `recordWaitValue` cases, e.g. `recordWaitValue(60) == "1 h"`):
```kotlin
    @Test fun recordWaitValue_secondsUnit() {
        assertEquals("None", recordWaitValue(0))
        assertEquals("30 s", recordWaitValue(30))
        assertEquals("1 m", recordWaitValue(60))
        assertEquals("2 m", recordWaitValue(120))
        assertEquals("1 h", recordWaitValue(3600))
    }
```

- [ ] **Step 3: Write failing prefs-migration tests**

Read `RovaSettingsTest.kt` first to match its `Context`/prefs fixture style. Add:
```kotlin
    @Test fun intervalSeconds_migratesLegacyMinutesOnce() {
        legacyIntervalPref(5)                         // old "interval"=5 minutes
        assertEquals(300, RovaSettings(context).intervalSeconds)
        assertEquals(300, rovaPrefs().getInt("interval_seconds", -1))
    }

    @Test fun intervalSeconds_defaultsTo60_whenNoKeys() {
        assertEquals(60, RovaSettings(context).intervalSeconds)
    }

    @Test fun intervalSeconds_newKeyWins_overLegacy() {
        legacyIntervalPref(5)
        rovaPrefs().edit().putInt("interval_seconds", 30).apply()
        assertEquals(30, RovaSettings(context).intervalSeconds)
    }
```
> Adapt `legacyIntervalPref`/`rovaPrefs`/`context` to the existing test's helpers for the `rova_settings` file. If `RovaSettingsTest` has no Context seam, instead extract a pure `internal fun migrateIntervalSeconds(hasNew: Boolean, newVal: Int, legacyVal: Int): Int` and unit-test that; note the choice in your report.

- [ ] **Step 4: Write failing preset-migration tests**

Create `app/src/test/java/com/aritr/rova/data/PresetJsonMigrationTest.kt`:
```kotlin
package com.aritr.rova.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetJsonMigrationTest {

    @Test fun v3RoundTrip_secondsAndIdPreserved() {
        val p = RovaPreset(name = "Half", duration = 10, intervalSeconds = 30, loopCount = 5,
            resolution = "HD", id = "custom.abc", isBuiltIn = false)
        val back = PresetJson.decode(PresetJson.encode(listOf(p)))
        assertEquals(30, back[0].intervalSeconds)
        assertEquals("custom.abc", back[0].id)
    }

    @Test fun legacyV2Envelope_minutesScaledAndIdKept() {
        val v2 = JSONObject().apply {
            put("presetSchemaVersion", 2)
            put("presets", JSONArray().put(JSONObject().apply {
                put("id", "custom.deadbeef"); put("name", "Two"); put("duration", 10)
                put("interval", 2); put("loopCount", 5); put("resolution", "HD")
            }))
        }.toString()
        val back = PresetJson.decode(v2)
        assertEquals(120, back[0].intervalSeconds)
        assertEquals("custom.deadbeef", back[0].id)
    }

    @Test fun legacyArray_noVersion_treatedAsMinutes() {
        val arr = "[{\"id\":\"custom.x\",\"name\":\"Min\",\"duration\":10,\"interval\":1,\"loopCount\":5,\"resolution\":\"HD\"}]"
        assertEquals(60, PresetJson.decode(arr)[0].intervalSeconds)
    }

    @Test fun blankId_derivesStableCustomId() {
        val arr = "[{\"name\":\"NoId\",\"duration\":10,\"interval\":1,\"loopCount\":5,\"resolution\":\"HD\"}]"
        assertTrue(PresetJson.decode(arr)[0].id.startsWith("custom."))
    }
}
```

- [ ] **Step 5: Update builtins / matcher / beep tests**

- `BuiltInPresetsTest.kt`: Quick Sample `intervalSeconds == 60`, Standard `== 120`, Long Session `== 300`, Continuous `== 0` (replace `interval == 1/2/5/0`).
- `PresetMatcherTest.kt`: the interval arg is seconds — a config `(10, 120, 20, "FHD")` matches Standard. Rename named args.
- `BeepPolicyTest.kt`: `intervalMinutes =` → `intervalSeconds =`; keep the `== 0` continuous-suppression case; add an `intervalSeconds = 30` → beeps case.

- [ ] **Step 6: Run all new/changed tests to confirm failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.RecordSettingBoundsWaitTest" --tests "com.aritr.rova.ui.screens.SessionSettingsCardFormattersTest" --tests "com.aritr.rova.data.PresetJsonMigrationTest" --tests "com.aritr.rova.data.BuiltInPresetsTest" --tests "com.aritr.rova.data.PresetMatcherTest" --tests "com.aritr.rova.service.audio.BeepPolicyTest" --tests "com.aritr.rova.data.RovaSettingsTest"`
Expected: FAIL.

- [ ] **Step 7: `RecordSettingBounds` — allowed-values wait stepper**

Remove the wait constants/functions:
```kotlin
    const val WAIT_MIN = 0
    const val WAIT_MAX = 60
```
```kotlin
    fun stepWait(current: Int, dir: Int): Int =
        (current.coerceIn(WAIT_MIN, WAIT_MAX) + dir).coerceIn(WAIT_MIN, WAIT_MAX)
```
```kotlin
    fun waitAtMin(v: Int): Boolean = v.coerceIn(WAIT_MIN, WAIT_MAX) <= WAIT_MIN
    fun waitAtMax(v: Int): Boolean = v.coerceIn(WAIT_MIN, WAIT_MAX) >= WAIT_MAX
```
```kotlin
    fun clampWait(v: Int): Int = v.coerceIn(WAIT_MIN, WAIT_MAX)
```
Add:
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
        return WAIT_ALLOWED.minByOrNull { kotlin.math.abs(it - value) }!! // first-minimal = lower neighbor on ties
    }

    fun stepWait(current: Int, dir: Int): Int {
        val idx = WAIT_ALLOWED.indexOf(nearestAllowedWait(current))
        return WAIT_ALLOWED[(idx + dir).coerceIn(0, WAIT_ALLOWED.lastIndex)]
    }

    fun waitAtMin(v: Int): Boolean = nearestAllowedWait(v) <= WAIT_ALLOWED.first()
    fun waitAtMax(v: Int): Boolean = nearestAllowedWait(v) >= WAIT_ALLOWED.last()

    fun clampWait(v: Int): Int = nearestAllowedWait(v) // typed value snaps to allowed (ADR-0033)
```
Leave all `CLIP_*` / `REPEATS_*` members untouched.

- [ ] **Step 8: `recordWaitValue` — seconds-aware**

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
    else -> "$intervalSeconds s"
}
```

- [ ] **Step 9: `RovaPreset` + `RovaSettings` (init migration) + `hasAnyRecordingPref`**

In `RovaSettings.kt`:
- `data class RovaPreset`: `val interval: Int,` → `val intervalSeconds: Int,`.
- Add the migration to the existing `init {}` block (after the `mode` cleanup), so getters stay pure reads:
```kotlin
        // ADR-0033 — one-shot interval unit migration. The legacy "interval" key
        // stored MINUTES; seconds live under the NEW "interval_seconds" key so a
        // stored `5` is never reinterpreted as 5 seconds. Guarded => idempotent;
        // runs at construction (not in the getter) so reads never write.
        if (!prefs.contains("interval_seconds") && prefs.contains("interval")) {
            prefs.edit { putInt("interval_seconds", prefs.getInt("interval", 1) * 60) }
        }
```
- Replace the property:
```kotlin
    var intervalMinutes: Int
        get() = prefs.getInt("interval", 1)
        set(value) = prefs.edit { putInt("interval", value) }
```
with:
```kotlin
    /** ADR-0033 — recording interval in SECONDS (migrated from legacy minutes in init). Default 60 s. */
    var intervalSeconds: Int
        get() = prefs.getInt("interval_seconds", 60)
        set(value) = prefs.edit { putInt("interval_seconds", value) }
```
- `hasAnyRecordingPref()`: add `|| prefs.contains("interval_seconds")` alongside `prefs.contains("interval")`.

- [ ] **Step 10: `BuiltInPresets` — × 60**

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

- [ ] **Step 11: `PresetJson` — v3 + version-aware decode**

- `private const val VERSION = 2` → `3`.
- `encode`: `put("interval", p.interval)` → `put("interval", p.intervalSeconds)` (key name `"interval"` kept; meaning now seconds, disambiguated by `presetSchemaVersion=3`).
- `decode`: scale by source version:
```kotlin
    fun decode(raw: String): List<RovaPreset> {
        val trimmed = raw.trim()
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
        val toSeconds: (Int) -> Int = if (version >= 3) { v -> v } else { v -> v * 60 } // v<3 stored minutes
        val out = ArrayList<RovaPreset>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val preset = try {
                RovaPreset(
                    name = obj.getString("name"),
                    duration = obj.getInt("duration"),
                    intervalSeconds = toSeconds(obj.getInt("interval")),
                    loopCount = obj.getInt("loopCount"),
                    resolution = obj.getString("resolution"),
                    id = "",
                    isBuiltIn = false,
                )
            } catch (e: Exception) { continue }
            out.add(preset.copy(id = ensureCustomId(obj.optString("id", ""), preset)))
        }
        return out
    }
```
- `ensureCustomId`: hash key `${p.interval}` → `${p.intervalSeconds}`. **Keep** the `if (rawId.startsWith("custom.")) return rawId` short-circuit — it preserves legacy custom ids across the migration (a stored v2 custom has a `custom.<hash>` id, returned verbatim, never re-hashed).

- [ ] **Step 12: `PresetMatcher` + `BeepPolicy` param rename**

- `PresetMatcher`: rename `interval: Int` → `intervalSeconds: Int` in both `match` overloads and `matchActive`; `p.interval == interval` → `p.intervalSeconds == intervalSeconds`.
- `BeepPolicy.shouldPlayBeep`: param `intervalMinutes: Int` → `intervalSeconds: Int`; `intervalMinutes == 0` → `intervalSeconds == 0`; update KDoc.

- [ ] **Step 13: `RovaRecordingService` — `mSeconds`, intent, scheduler, beep, manifest**

- `private var mMinutes = 10L` → `private var mSeconds = 60L`.
- `start()` companion: param `mMinutes: Float` → `intervalSeconds: Int`; `putExtra("M_MINUTES", mMinutes)` → `putExtra("M_SECONDS", intervalSeconds)`.
- read (1091): replace `mMinutes = intent.getFloatExtra("M_MINUTES", 10f).toLong()` with:
```kotlin
        mSeconds = if (intent.hasExtra("M_SECONDS")) {
            intent.getIntExtra("M_SECONDS", 60).toLong()
        } else {
            (intent.getFloatExtra("M_MINUTES", 1f).toLong()) * 60 // ADR-0033 fallback: in-flight pre-update start
        }
```
- manifest write (1288): drop the Task-2 bridge → `intervalSeconds = mSeconds.toInt(),`.
- beep call (1473): `beepStart(mMinutes.toInt(), …)` → `beepStart(mSeconds.toInt(), …)`.
- beep def (4377): param `intervalMinutes: Int` → `intervalSeconds: Int`; `shouldPlayBeep(intervalMinutes = intervalMinutes)` → `intervalSeconds = intervalSeconds`.
- scheduler (1573): `val waitSeconds = (mMinutes * 60).toInt().coerceAtLeast(0)` → `val waitSeconds = mSeconds.toInt().coerceAtLeast(0)`.

- [ ] **Step 14: `start()` call sites**

- `RovaApp.kt:937`: `mMinutes = s.intervalMinutes.toFloat(),` → `intervalSeconds = s.intervalSeconds,`.
- `RecordScreen.kt:669-672`: read the call shape, then the interval arg `viewModel.interval.value.toFloat()` → `viewModel.interval.value` (Int seconds), matching the new `start()` param (`intervalSeconds = …` if named).

- [ ] **Step 15: ViewModels**

- `RecordViewModel.kt`: L117 `MutableStateFlow(settings.intervalSeconds)`; L180 `settings.intervalSeconds = it`; L216 `settings.intervalSeconds = std.intervalSeconds`; L220 `interval.value = std.intervalSeconds`; L243 `interval.value = settings.intervalSeconds`; L379 `intervalSeconds = i`; L407 `interval.value = preset.intervalSeconds`. (`matchActive(..., interval.value, ...)` needs no change if positional.)
- `SettingsViewModel.kt`: L46/125/143 `settings.intervalSeconds`. If the StateFlow stays named `intervalMinutes`, prefer renaming it to `intervalSeconds` and updating its collectors in the Settings UI; else add `// holds seconds (ADR-0033)`.

- [ ] **Step 16: Preset screen-reader phrase + summary (`SettingsSheet.kt`)**

Add the plural — `values/strings.xml` beside `preset_cd_every_minutes`:
```xml
    <plurals name="preset_cd_every_seconds">
        <item quantity="one">every %1$d second</item>
        <item quantity="other">every %1$d seconds</item>
    </plurals>
```
`values-es/strings.xml` beside the es `preset_cd_every_minutes`:
```xml
    <plurals name="preset_cd_every_seconds">
        <item quantity="one">cada %1$d segundo</item>
        <item quantity="other">cada %1$d segundos</item>
    </plurals>
```
Replace the `waitPhrase` block (≈L1521):
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
`SettingsSummary` (≈L1192): rename param `intervalMinutes: Int` → `intervalSeconds: Int`, update the `if (… > 0)` guard + its single call site (the value flows into `recordWaitValue`, already seconds-aware).

- [ ] **Step 17: Sweep remaining `intervalMinutes` UI params**

Compile flags the rest. Rename each composable param still named `intervalMinutes` that now carries seconds (`RecordChrome.kt:346`, `SettingsSheet.kt:132/237/390/606`, `FloatingSettingsPanel.kt:131`, `RecordScreen.kt:1087/1220/1263`) to `intervalSeconds` and fix the threading. Pure rename — they pass into `recordWaitValue`/`RecordSettingBounds.*Wait`, all seconds-ready. No behavior change.

- [ ] **Step 18: Build + full suite (controller)**

```
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL (all 46 gates) + full suite GREEN, no minutes-era assertion remaining. Fix any straggler the sweep missed.

- [ ] **Step 19: Commit**

```bash
git add -A
git commit -m "feat(record): flip interval unit to seconds end-to-end; 30s minimum (ADR-0033)"
```

---

## Self-Review (controller, before dispatch)

- **Spec coverage:** D1 representation → Tasks 2/3. D2 30 s min → `WAIT_ALLOWED` (T3). D3 stepper allowed list + snap → T3. D4 schema 13 stacked → T2. D5 ADR → T1. Migrations §2.1 manifest → T2; §2.2 prefs new key (init migration) → T3/9; §2.3 presets v3 + id stability → T3/11; §2.4 intent M_SECONDS + fallback → T3/13. Scheduler §3 → T3/13. Picker/formatters §4 → T3 (stepper, `recordWaitValue`) + T2 (`formatWait`).
- **Codex review folded (decomposition):** the original leaf-first split (stepper/formatters before the data flip) shipped green-but-incorrect commits because those helpers are LIVE on the still-minutes value. Corrected to manifest-cluster (T2, distinct functions/values) then atomic live-currency flip (T3). Prefs migration moved to a guarded `init {}` block (pure-read getters; no read-that-writes race). Custom-preset id continuity holds for `custom.*` ids via the short-circuit; non-`custom.` legacy ids may re-derive (documented-acceptable).
- **Deliberate deviation (flag to owner):** spec §4.2's `record_wait_seconds` literal resource is NOT added; `recordWaitValue`/`formatWait` keep literals (sibling-consistent, outside `checkNoHardcodedUiStrings`). Localized resources are added only at the real `pluralStringResource` site (preset CD, T3/16).
- **Type consistency:** `intervalSeconds: Int` identical across `SessionConfig`, `RovaSettings`, `RovaPreset`, `PresetMatcher`, `BeepPolicy`; intent key `M_SECONDS` (Int); `start(intervalSeconds: Int)`; service field `mSeconds: Long`.
- **Green-AND-correct each commit:** T1 doc-only. T2 manifest cluster is internally consistent (the only reader, the history dialog, uses the seconds-aware `formatWait`; the live record-settings path is wholly untouched). T3 flips the live cluster atomically and removes the T2 bridge.

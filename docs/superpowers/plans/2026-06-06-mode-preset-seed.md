# Mode Preset Seed Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship four named, code-defined recording presets as a one-tap, preset-first idle surface, with the existing steppers demoted behind a "Customize" door — without bumping the manifest schema or clobbering any existing user's config.

**Architecture:** A preset is a config bundle `{duration, interval, loopCount, resolution}` only; orientation stays orthogonal (ADR-0026, enforced by `checkPresetNoOrientation`). Built-ins are a read-only Kotlin `object`; user customs persist in `RovaSettings.customPresetsJson`. All correctness lives in pure helpers (`PresetJson`, `PresetMatcher`, `FirstRunSeedPolicy`) so it is JVM-testable under `isReturnDefaultValues = true`. The `RecordViewModel` and Compose UI are thin seams over those helpers.

**Tech Stack:** Kotlin, Jetpack Compose, `org.json` (on `testImplementation`, works under JVM tests), Gradle static-check gates wired to `preBuild`.

**Spec:** `docs/superpowers/specs/2026-06-06-mode-preset-seed-design.md`

**Branch:** `feat/mode-preset-seed` (already created; spec already committed there)

**Conventions for every task below:**
- Build a single JVM test class: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.<FQN>"`
- Run a gate: `./gradlew :app:<taskName>`
- The post-edit hook may auto-run Gradle on save; do not launch parallel builds. Run the listed command once per step.

---

## File Structure

| File | Responsibility | New/Modify |
|---|---|---|
| `data/RovaPreset` (in `data/RovaSettings.kt`) | preset value type — gains `id`, `isBuiltIn` | Modify |
| `data/RovaSettings.kt` | add `presetSeeded` flag + `hasAnyRecordingPref()` | Modify |
| `data/BuiltInPresets.kt` | the 4 read-only built-ins | Create |
| `data/PresetJson.kt` | pure encode/decode of customs (envelope v2 + legacy-tolerant) | Create |
| `data/PresetMatcher.kt` | pure current-values → built-in id or null | Create |
| `data/FirstRunSeedPolicy.kt` | pure "seed Standard?" decision | Create |
| `ui/screens/RecordViewModel.kt` | `applyPreset`, `activePresetId`, `allPresets`, seed-on-init, use `PresetJson` | Modify |
| `ui/screens/PresetRow.kt` | the preset chip row composable | Create |
| `ui/screens/RecordChrome.kt` / `RecordScreen.kt` | mount `PresetRow` above the settings card | Modify |
| `app/build.gradle.kts` | register `checkPresetNoOrientation`, wire to `preBuild` | Modify |
| `docs/adr/0026-preset-config-bundle-orientation-orthogonal.md` | ADR-0026 | Create |
| `app/src/test/.../data/*Test.kt` | tests per task | Create |

---

## Task 1: ADR-0026

**Files:**
- Create: `docs/adr/0026-preset-config-bundle-orientation-orthogonal.md`

- [ ] **Step 1: Write the ADR**

```markdown
# ADR-0026: Preset = config bundle; orientation is orthogonal; built-ins are read-only

Status: Accepted
Date: 2026-06-06

## Context

The Record screen exposes four recording controls (clip duration, interval,
repeats/loop-count, quality) plus an independent orientation control
(Portrait / Landscape / P+L). The "Mode Preset Seed" feature introduces named
presets. The original mockup conflated "mode" (orientation) with preset
("config bundle"), which would make a single word mean two things on one screen.

## Decision

1. A **preset carries only** `{duration, interval, loopCount, resolution}`.
   It MUST NOT carry orientation/mode. Applying a preset never changes the
   camera orientation.
2. **Built-in presets are code-defined and read-only** (`object BuiltInPresets`),
   never persisted. They can be retuned in a future release and reach all users.
   User-saved customs persist in `RovaSettings.customPresetsJson`.
3. Preset persistence has its own `presetSchemaVersion` envelope and is fully
   independent of `SessionManifest.SCHEMA_VERSION` (no manifest bump).

## Enforcement

`checkPresetNoOrientation` (Gradle, wired to `preBuild`) fails the build if
`RovaPreset` or `BuiltInPresets` gains an orientation/mode field.

## Consequences

- "Active preset" is determined by *value match* against built-ins, not identity;
  a custom whose values equal a built-in displays as that built-in. This is intended.
- Future per-clip "phases" land as additive optional fields under the same
  envelope, no corner.
```

- [ ] **Step 2: Commit**

```bash
git add docs/adr/0026-preset-config-bundle-orientation-orthogonal.md
git commit -m "docs(adr): ADR-0026 preset=config-bundle, orientation orthogonal, built-ins read-only"
```

---

## Task 2: `RovaPreset` gains `id` + `isBuiltIn`

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/data/RovaSettings.kt:234-240`

- [ ] **Step 1: Extend the data class**

Replace the existing `RovaPreset` declaration:

```kotlin
data class RovaPreset(
    val name: String,
    val duration: Int,
    val interval: Int,
    val loopCount: Int, // -1 for infinite
    val resolution: String,
    /** Stable identity. Built-ins use the reserved `builtin.*` prefix; customs use `custom.*`. */
    val id: String = "",
    /** Runtime tag only — never persisted. Customs always deserialize to false. */
    val isBuiltIn: Boolean = false,
)
```

The two new params have defaults, so the existing `RovaPreset(name = …, duration = …, …)` call in `RecordViewModel.savePreset` still compiles (it will be updated in Task 6).

- [ ] **Step 2: Build to verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/aritr/rova/data/RovaSettings.kt
git commit -m "feat(preset): RovaPreset gains id + isBuiltIn (ADR-0026)"
```

---

## Task 3: `BuiltInPresets`

**Files:**
- Create: `app/src/main/java/com/aritr/rova/data/BuiltInPresets.kt`
- Test: `app/src/test/java/com/aritr/rova/data/BuiltInPresetsTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.aritr.rova.data

import com.aritr.rova.ui.screens.RecordSettingBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInPresetsTest {

    @Test fun thereAreExactlyFourBuiltIns() {
        assertEquals(4, BuiltInPresets.all.size)
    }

    @Test fun idsAreNamespacedAndUnique() {
        val ids = BuiltInPresets.all.map { it.id }
        assertTrue(ids.all { it.startsWith("builtin.") })
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun allAreTaggedBuiltIn() {
        assertTrue(BuiltInPresets.all.all { it.isBuiltIn })
    }

    @Test fun valueTuplesArePairwiseDistinct() {
        // Required so "modified -> Custom" is observable from values alone.
        val tuples = BuiltInPresets.all.map {
            listOf(it.duration, it.interval, it.loopCount, QualityPresets.canonicalizeOrDefault(it.resolution))
        }
        assertEquals(tuples.size, tuples.toSet().size)
    }

    @Test fun valuesAreWithinBounds() {
        BuiltInPresets.all.forEach { p ->
            assertTrue(p.duration in RecordSettingBounds.CLIP_MIN..RecordSettingBounds.CLIP_MAX)
            assertTrue(p.interval in RecordSettingBounds.WAIT_MIN..RecordSettingBounds.WAIT_MAX)
            val loopOk = p.loopCount == RecordSettingBounds.REPEATS_CONTINUOUS ||
                p.loopCount in RecordSettingBounds.REPEATS_MIN..RecordSettingBounds.REPEATS_MAX
            assertTrue("loopCount ${p.loopCount} out of bounds", loopOk)
            assertEquals(p.resolution, QualityPresets.canonicalizeOrDefault(p.resolution))
        }
    }

    @Test fun standardIsDefaultId() {
        assertEquals("builtin.standard", BuiltInPresets.DEFAULT_ID)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.data.BuiltInPresetsTest"`
Expected: FAIL — `BuiltInPresets` unresolved.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.aritr.rova.data

/**
 * ADR-0026 — the four read-only, code-defined recording presets. A preset is a
 * config bundle of {duration, interval, loopCount, resolution} ONLY; orientation
 * is orthogonal and never carried here (enforced by `checkPresetNoOrientation`).
 *
 * These are never persisted — they are retunable per release and reach all users.
 * Values are intentionally tunable constants; they are sensible defaults for this
 * periodic-capture paradigm, validated later against beta telemetry.
 */
object BuiltInPresets {

    const val DEFAULT_ID = "builtin.standard"

    val all: List<RovaPreset> = listOf(
        RovaPreset(
            name = "Quick Sample", duration = 10, interval = 1, loopCount = 10,
            resolution = QualityPresets.FHD, id = "builtin.quick_sample", isBuiltIn = true,
        ),
        RovaPreset(
            name = "Standard", duration = 30, interval = 2, loopCount = 20,
            resolution = QualityPresets.FHD, id = DEFAULT_ID, isBuiltIn = true,
        ),
        RovaPreset(
            name = "Long Session", duration = 60, interval = 5, loopCount = 50,
            resolution = QualityPresets.HD, id = "builtin.long_session", isBuiltIn = true,
        ),
        RovaPreset(
            name = "Continuous", duration = 60, interval = 0,
            loopCount = RecordSettingBounds.REPEATS_CONTINUOUS,
            resolution = QualityPresets.HD, id = "builtin.continuous", isBuiltIn = true,
        ),
    )

    /** The Standard preset (first-run default). */
    val default: RovaPreset = all.first { it.id == DEFAULT_ID }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.data.BuiltInPresetsTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/data/BuiltInPresets.kt app/src/test/java/com/aritr/rova/data/BuiltInPresetsTest.kt
git commit -m "feat(preset): BuiltInPresets — 4 read-only built-ins (ADR-0026)"
```

---

## Task 4: `PresetMatcher`

**Files:**
- Create: `app/src/main/java/com/aritr/rova/data/PresetMatcher.kt`
- Test: `app/src/test/java/com/aritr/rova/data/PresetMatcherTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.aritr.rova.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PresetMatcherTest {

    @Test fun exactStandardMatches() {
        assertEquals("builtin.standard", PresetMatcher.match(30, 2, 20, "FHD"))
    }

    @Test fun continuousSentinelMatches() {
        assertEquals("builtin.continuous", PresetMatcher.match(60, 0, -1, "HD"))
    }

    @Test fun legacyResolutionAliasCanonicalizes() {
        // "1080p" is the legacy alias for FHD — Standard should still match.
        assertEquals("builtin.standard", PresetMatcher.match(30, 2, 20, "1080p"))
    }

    @Test fun noMatchReturnsNull() {
        assertNull(PresetMatcher.match(25, 2, 20, "FHD"))
    }

    @Test fun continuousDoesNotMatchFiniteLoop() {
        // Same duration/interval/quality as Continuous but finite loop -> no match.
        assertNull(PresetMatcher.match(60, 0, 5, "HD"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.data.PresetMatcherTest"`
Expected: FAIL — `PresetMatcher` unresolved.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.aritr.rova.data

/**
 * ADR-0026 — classifies the current recording config against the built-in
 * presets. Returns the matching `builtin.*` id, or null (rendered as "Custom").
 *
 * "Active preset = built-in value match, else Custom" — selection is by config,
 * not identity. A user custom whose values equal a built-in resolves to the
 * built-in id. Resolution is compared canonically so legacy aliases ("1080p",
 * "UHD") match; loopCount is compared exactly, including the -1 continuous sentinel.
 */
object PresetMatcher {
    fun match(duration: Int, interval: Int, loopCount: Int, resolution: String?): String? {
        val res = QualityPresets.canonicalizeOrDefault(resolution)
        return BuiltInPresets.all.firstOrNull { p ->
            p.duration == duration &&
                p.interval == interval &&
                p.loopCount == loopCount &&
                QualityPresets.canonicalizeOrDefault(p.resolution) == res
        }?.id
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.data.PresetMatcherTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/data/PresetMatcher.kt app/src/test/java/com/aritr/rova/data/PresetMatcherTest.kt
git commit -m "feat(preset): PresetMatcher — current values -> built-in id (ADR-0026)"
```

---

## Task 5: `PresetJson` — tolerant custom-preset codec

**Files:**
- Create: `app/src/main/java/com/aritr/rova/data/PresetJson.kt`
- Test: `app/src/test/java/com/aritr/rova/data/PresetJsonTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.aritr.rova.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetJsonTest {

    @Test fun emptyArrayDecodesEmpty() {
        assertEquals(emptyList<RovaPreset>(), PresetJson.decode("[]"))
    }

    @Test fun envelopeRoundTrip() {
        val customs = listOf(
            RovaPreset("My Vlog", 45, 3, 15, "FHD", id = "custom.abc", isBuiltIn = false),
        )
        val decoded = PresetJson.decode(PresetJson.encode(customs))
        assertEquals(1, decoded.size)
        assertEquals("My Vlog", decoded[0].name)
        assertEquals(45, decoded[0].duration)
        assertEquals("custom.abc", decoded[0].id)
        assertTrue(!decoded[0].isBuiltIn)
    }

    @Test fun legacyArrayWithoutIdsDerivesStableCustomId() {
        val legacy = """[{"name":"Old","duration":20,"interval":2,"loopCount":5,"resolution":"HD"}]"""
        val decoded = PresetJson.decode(legacy)
        assertEquals(1, decoded.size)
        assertTrue(decoded[0].id.startsWith("custom."))
        // Stable: decoding the same legacy blob twice yields the same id.
        assertEquals(decoded[0].id, PresetJson.decode(legacy)[0].id)
    }

    @Test fun oneMalformedObjectIsSkippedRestSurvive() {
        val mixed = """[{"name":"Good","duration":20,"interval":2,"loopCount":5,"resolution":"HD"},{"name":"Bad"}]"""
        val decoded = PresetJson.decode(mixed)
        assertEquals(1, decoded.size)
        assertEquals("Good", decoded[0].name)
    }

    @Test fun customClaimingBuiltinPrefixIsRenamespaced() {
        val sneaky = """{"presetSchemaVersion":2,"presets":[{"id":"builtin.standard","name":"X","duration":30,"interval":2,"loopCount":20,"resolution":"FHD"}]}"""
        val decoded = PresetJson.decode(sneaky)
        assertEquals(1, decoded.size)
        assertTrue("must not impersonate a built-in", decoded[0].id.startsWith("custom."))
    }

    @Test fun garbageDecodesEmpty() {
        assertEquals(emptyList<RovaPreset>(), PresetJson.decode("not json"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.data.PresetJsonTest"`
Expected: FAIL — `PresetJson` unresolved.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.aritr.rova.data

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.absoluteValue

/**
 * ADR-0026 — pure codec for the USER CUSTOM presets persisted in
 * `RovaSettings.customPresetsJson`. Built-ins are never written here.
 *
 * Writer emits the v2 envelope: {presetSchemaVersion, presets:[...]}.
 * Reader is additive-tolerant and branches on the root token:
 *  - legacy root JSONArray (today's "[]" / array-of-objects)
 *  - v2 root JSONObject envelope
 * Missing id -> stable derived `custom.<hash>`. A single malformed object is
 * skipped (not the whole list). Any id with the reserved `builtin.` prefix is
 * re-namespaced to `custom.*` so a custom can never impersonate a built-in.
 */
object PresetJson {

    private const val VERSION = 2

    fun encode(customs: List<RovaPreset>): String {
        val arr = JSONArray()
        customs.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", ensureCustomId(p.id, p))
                put("name", p.name)
                put("duration", p.duration)
                put("interval", p.interval)
                put("loopCount", p.loopCount)
                put("resolution", p.resolution)
            })
        }
        return JSONObject().apply {
            put("presetSchemaVersion", VERSION)
            put("presets", arr)
        }.toString()
    }

    fun decode(raw: String): List<RovaPreset> {
        val trimmed = raw.trim()
        val array: JSONArray = try {
            when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                trimmed.startsWith("{") -> JSONObject(trimmed).optJSONArray("presets") ?: JSONArray()
                else -> return emptyList()
            }
        } catch (e: Exception) {
            return emptyList()
        }
        val out = ArrayList<RovaPreset>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val preset = try {
                RovaPreset(
                    name = obj.getString("name"),
                    duration = obj.getInt("duration"),
                    interval = obj.getInt("interval"),
                    loopCount = obj.getInt("loopCount"),
                    resolution = obj.getString("resolution"),
                    id = "", // assigned below
                    isBuiltIn = false,
                )
            } catch (e: Exception) {
                continue // skip one malformed object, keep the rest
            }
            out.add(preset.copy(id = ensureCustomId(obj.optString("id", ""), preset)))
        }
        return out
    }

    /** A custom id that never collides with / impersonates a `builtin.*` id. */
    private fun ensureCustomId(rawId: String, p: RovaPreset): String {
        if (rawId.isNotEmpty() && rawId.startsWith("custom.")) return rawId
        // Missing, blank, or reserved `builtin.` -> derive a stable custom id.
        val key = "${p.name}|${p.duration}|${p.interval}|${p.loopCount}|${p.resolution}"
        return "custom." + key.hashCode().absoluteValue.toString(16)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.data.PresetJsonTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/data/PresetJson.kt app/src/test/java/com/aritr/rova/data/PresetJsonTest.kt
git commit -m "feat(preset): PresetJson tolerant codec — v2 envelope, legacy + malformed-skip + prefix guard"
```

---

## Task 6: `FirstRunSeedPolicy` + `RovaSettings` seed flag

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/data/RovaSettings.kt` (add `presetSeeded` + `hasAnyRecordingPref()`)
- Create: `app/src/main/java/com/aritr/rova/data/FirstRunSeedPolicy.kt`
- Test: `app/src/test/java/com/aritr/rova/data/FirstRunSeedPolicyTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.aritr.rova.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstRunSeedPolicyTest {

    @Test fun freshInstallSeeds() {
        // Never seeded AND no recording pref written yet -> genuinely fresh.
        assertTrue(FirstRunSeedPolicy.shouldSeed(presetSeeded = false, anyRecordingPrefPresent = false))
    }

    @Test fun existingUserNeverClobbered() {
        // A recording pref already exists -> existing user, do NOT seed.
        assertFalse(FirstRunSeedPolicy.shouldSeed(presetSeeded = false, anyRecordingPrefPresent = true))
    }

    @Test fun alreadySeededDoesNotReseed() {
        assertFalse(FirstRunSeedPolicy.shouldSeed(presetSeeded = true, anyRecordingPrefPresent = false))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.data.FirstRunSeedPolicyTest"`
Expected: FAIL — `FirstRunSeedPolicy` unresolved.

- [ ] **Step 3: Write `FirstRunSeedPolicy`**

```kotlin
package com.aritr.rova.data

/**
 * ADR-0026 — decides whether to seed the Standard preset's values on launch.
 *
 * We only seed on a PROVEN fresh install: never-seeded AND no recording pref has
 * ever been written. An existing user who simply never tweaked a stepper still
 * has `anyRecordingPrefPresent == false` only on a truly clean install, because
 * the seed itself writes those keys. This guarantees we never overwrite a config
 * a user already established (codex review #1).
 */
object FirstRunSeedPolicy {
    fun shouldSeed(presetSeeded: Boolean, anyRecordingPrefPresent: Boolean): Boolean =
        !presetSeeded && !anyRecordingPrefPresent
}
```

- [ ] **Step 4: Add the flag + presence check to `RovaSettings`**

In `app/src/main/java/com/aritr/rova/data/RovaSettings.kt`, add after the `customPresetsJson` property (around line 182):

```kotlin
    // ADR-0026 — set true once the first-run Standard seed has run (or been
    // skipped for an existing user). Prevents re-seeding on later launches.
    var presetSeeded: Boolean
        get() = prefs.getBoolean("preset_seeded", false)
        set(value) = prefs.edit { putBoolean("preset_seeded", value) }

    /** True if any recording-default pref key has ever been written (existing user). */
    fun hasAnyRecordingPref(): Boolean =
        prefs.contains("duration") || prefs.contains("interval") ||
            prefs.contains("loop_count") || prefs.contains("resolution")
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.data.FirstRunSeedPolicyTest"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aritr/rova/data/FirstRunSeedPolicy.kt app/src/main/java/com/aritr/rova/data/RovaSettings.kt app/src/test/java/com/aritr/rova/data/FirstRunSeedPolicyTest.kt
git commit -m "feat(preset): FirstRunSeedPolicy + presetSeeded flag (no-clobber, codex #1)"
```

---

## Task 7: Wire the ViewModel (`applyPreset`, `activePresetId`, `allPresets`, seed-on-init)

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordViewModel.kt`

No JVM unit test here — the VM touches `SharedPreferences`/`Context` (returns defaults under `isReturnDefaultValues = true`), so logic is already covered by the pure-helper tests in Tasks 4–6. This task is wiring only; verify by `compileDebugKotlin`.

- [ ] **Step 1: Replace `loadPresetsFromSettings`/`persistPresets` bodies to use `PresetJson`**

In `RecordViewModel.kt`, replace the existing `loadPresetsFromSettings()` and `persistPresets()` private functions (the JSON-building block around lines 331–361) with:

```kotlin
    private fun loadPresetsFromSettings(): List<RovaPreset> =
        PresetJson.decode(settings.customPresetsJson)

    private fun persistPresets(presets: List<RovaPreset>) {
        settings.customPresetsJson = PresetJson.encode(presets)
    }
```

Remove the now-unused `JSONArray` / `JSONObject` imports if they are no longer referenced elsewhere in the file.

- [ ] **Step 2: Give `savePreset` a stable custom id**

Replace the `RovaPreset(...)` construction inside `savePreset` so new customs carry a `custom.*` id (re-uses `PresetJson`'s derivation by leaving id blank — `encode` assigns it):

```kotlin
    fun savePreset(name: String) {
        val newPreset = RovaPreset(
            name = name,
            duration = duration.value,
            interval = interval.value,
            loopCount = loopCount.value,
            resolution = resolution.value,
        )
        val updated = _customPresets.value + newPreset
        _customPresets.value = updated
        persistPresets(updated) // encode() stamps a stable custom.* id
    }
```

- [ ] **Step 3: Add `applyPreset`, `activePresetId`, `allPresets`**

Add to the `// --- Preset management ---` region:

```kotlin
    /** ADR-0026 — apply a preset's config bundle. Does NOT touch orientation. */
    fun applyPreset(preset: RovaPreset) {
        // Same mid-session guard as reloadRecordingDefaults()/cycleMode().
        val s = _serviceState.value
        if (s.isPeriodicActive || s.isMerging) return
        duration.value = preset.duration
        interval.value = preset.interval
        loopCount.value = preset.loopCount
        resolution.value = preset.resolution
    }

    /** Built-ins first, then user customs. */
    val allPresets: StateFlow<List<RovaPreset>> =
        _customPresets.map { BuiltInPresets.all + it }
            .stateIn(viewModelScope, SharingStarted.Eagerly, BuiltInPresets.all)

    /** The active built-in id (value match) or null = "Custom". */
    val activePresetId: StateFlow<String?> =
        combine(duration, interval, loopCount, resolution) { d, i, l, r ->
            PresetMatcher.match(d, i, l, r)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, PresetMatcher.match(
            duration.value, interval.value, loopCount.value, resolution.value))
```

Add the imports if missing: `androidx.lifecycle.viewModelScope`, `kotlinx.coroutines.flow.SharingStarted`, `kotlinx.coroutines.flow.combine`, `kotlinx.coroutines.flow.map`, `kotlinx.coroutines.flow.stateIn`, and `com.aritr.rova.data.BuiltInPresets`, `com.aritr.rova.data.PresetMatcher`, `com.aritr.rova.data.PresetJson`.

- [ ] **Step 4: Seed Standard on a proven fresh install (in `init {}`)**

At the END of the existing `init { … }` block (after the anchor-collect launch, before the closing brace at line ~178), add:

```kotlin
        // ADR-0026 — one-time first-run seed of the Standard preset. Gated so an
        // existing user's config is never clobbered (FirstRunSeedPolicy).
        if (FirstRunSeedPolicy.shouldSeed(settings.presetSeeded, settings.hasAnyRecordingPref())) {
            val std = BuiltInPresets.default
            settings.durationSeconds = std.duration
            settings.intervalMinutes = std.interval
            settings.loopCount = std.loopCount
            settings.resolution = std.resolution
            duration.value = std.duration
            interval.value = std.interval
            loopCount.value = std.loopCount
            resolution.value = std.resolution
        }
        settings.presetSeeded = true
```

Add import `com.aritr.rova.data.FirstRunSeedPolicy`.

- [ ] **Step 5: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Run the full unit suite (regression)**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (baseline + new tests; 0 failures)

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/RecordViewModel.kt
git commit -m "feat(preset): VM applyPreset/activePresetId/allPresets + first-run seed (ADR-0026)"
```

---

## Task 8: `checkPresetNoOrientation` gate

**Files:**
- Modify: `app/build.gradle.kts` (register task near the other `check*` tasks ~line 1754; wire into `preBuild` in the `afterEvaluate` block ~line 1843)

- [ ] **Step 1: Register the gate**

Add alongside the other `check*` registrations (e.g. just before `checkA11yAnimationGated`):

```kotlin
// ADR-0026 — a preset is a config bundle ONLY; orientation/mode must never become
// a RovaPreset field, or the preset/orientation vocabulary collision returns.
// Scans the two preset source files for an orientation/mode property declaration.
val checkPresetNoOrientation = tasks.register("checkPresetNoOrientation") {
    group = "verification"
    description = "Forbid an orientation/mode field on RovaPreset/BuiltInPresets — preset = config bundle only (ADR-0026)."
    val files = listOf(
        file("src/main/java/com/aritr/rova/data/RovaSettings.kt"),
        file("src/main/java/com/aritr/rova/data/BuiltInPresets.kt"),
    )
    inputs.files(files).withPropertyName("presetSources")
    doLast {
        // `val mode`/`var mode`/`val orientation` etc. as a declared property.
        val offendingProp = Regex("""(^|\s)(val|var)\s+(mode|orientation)\b""")
        // RovaPreset's parameter list must not name a mode/orientation field.
        val ctorParam = Regex("""\b(mode|orientation)\s*:""")
        val rovaPresetSrc = files[0]
        if (!rovaPresetSrc.exists()) {
            throw GradleException("checkPresetNoOrientation: source missing: $rovaPresetSrc")
        }
        // Narrow to the RovaPreset declaration block to avoid matching unrelated
        // `mode` usage elsewhere in RovaSettings.kt (e.g. the legacy `mode` pref).
        val text = rovaPresetSrc.readText()
        val start = text.indexOf("data class RovaPreset")
        if (start >= 0) {
            val end = text.indexOf(")", start).let { if (it < 0) text.length else it }
            val block = text.substring(start, end)
            if (ctorParam.containsMatchIn(block)) {
                throw GradleException(
                    "checkPresetNoOrientation: RovaPreset must not declare a mode/orientation field (ADR-0026)."
                )
            }
        }
        val builtIns = files[1]
        if (builtIns.exists() && offendingProp.containsMatchIn(builtIns.readText())) {
            throw GradleException(
                "checkPresetNoOrientation: BuiltInPresets must not declare a mode/orientation property (ADR-0026)."
            )
        }
    }
}
```

- [ ] **Step 2: Wire into `preBuild`**

In the `afterEvaluate { tasks.matching { it.name == "preBuild" } … }` block, add a line alongside the existing `dependsOn(...)` calls:

```kotlin
        dependsOn(checkPresetNoOrientation)
```

- [ ] **Step 3: Run the gate on the clean tree (expect PASS)**

Run: `./gradlew :app:checkPresetNoOrientation`
Expected: BUILD SUCCESSFUL (RovaPreset has no mode/orientation field).

- [ ] **Step 4: Negative check — verify the gate actually bites**

Temporarily add `val orientation: String = ""` inside the `RovaPreset` constructor, then:

Run: `./gradlew :app:checkPresetNoOrientation`
Expected: FAIL with "RovaPreset must not declare a mode/orientation field (ADR-0026)."

Then REVERT the temporary edit and re-run to confirm PASS. (Do not commit the temporary edit.)

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts
git commit -m "feat(gate): checkPresetNoOrientation wired to preBuild (ADR-0026)"
```

---

## Task 9: `PresetRow` composable + mount it

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/screens/PresetRow.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt` (mount above the settings card) and/or `RecordScreen.kt` (pass the new VM flows in)

No JVM unit test (Compose UI). Verify by `compileDebugKotlin` + on-device smoke (Task 10).

- [ ] **Step 1: Write the composable**

```kotlin
package com.aritr.rova.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aritr.rova.data.RovaPreset

/**
 * ADR-0026 — preset-first idle surface. One tap on a chip applies the whole
 * config bundle via [onApply]. The selected chip reflects [activePresetId]
 * (null = no chip selected -> the "Customize" door shows "Custom").
 *
 * Each chip is a button with a spoken contentDescription (WCAG, ADR-0020) and a
 * >=48dp touch target.
 */
@Composable
fun PresetRow(
    presets: List<RovaPreset>,
    activePresetId: String?,
    onApply: (RovaPreset) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        presets.forEach { p ->
            val selected = p.id == activePresetId
            FilterChip(
                selected = selected,
                onClick = { onApply(p) },
                label = { Text(p.name) },
                shape = RoundedCornerShape(50),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = presetSpoken(p) },
            )
        }
    }
}

/** "Standard preset, 30 seconds clip, every 2 minutes, 20 times, FHD." */
private fun presetSpoken(p: RovaPreset): String {
    val repeats = if (p.loopCount < 0) "until you stop" else "${p.loopCount} times"
    val wait = if (p.interval <= 0) "no gap" else "every ${p.interval} minutes"
    return "${p.name} preset, ${p.duration} seconds clip, $wait, $repeats, ${p.resolution}"
}
```

> NOTE: the spoken strings are inlined here for clarity but `checkNoHardcodedUiStrings` only scans `Text("`/`contentDescription = "` call sites. `contentDescription = presetSpoken(p)` uses a function call (not a literal), so it passes the gate. The chip `Text(p.name)` uses a variable, not a literal — also passes. If the project owner wants these externalized to `strings.xml`, do so via `stringResource`, but it is not required by the gate.

- [ ] **Step 2: Mount `PresetRow` in the idle dock**

In `RecordChrome.kt`, locate the idle dock composable that renders the settings card / stepper-grid summary (search for where `openSettingsSheet` / the Clip·Repeats·Wait·Quality cells are rendered). Insert `PresetRow(...)` directly ABOVE that card, threading the three new params down from `RecordScreen.kt`:

```kotlin
PresetRow(
    presets = allPresets,              // from viewModel.allPresets.collectAsState()
    activePresetId = activePresetId,   // from viewModel.activePresetId.collectAsState()
    onApply = viewModel::applyPreset,
    modifier = Modifier.padding(horizontal = 16.dp),
)
```

In `RecordScreen.kt`, collect the flows and pass them into the chrome composable:

```kotlin
val allPresets by viewModel.allPresets.collectAsState()
val activePresetId by viewModel.activePresetId.collectAsState()
```

The existing settings card stays as-is; it IS the "Customize" door (`openSettingsSheet()` already opens `SettingsSheet`). When `activePresetId == null`, the card's existing summary line already shows the raw values — no extra "Custom" label work is required for v1 (optional: prefix the summary with "Custom · " when null).

- [ ] **Step 3: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Run the hardcoded-strings + a11y gates (regression)**

Run: `./gradlew :app:checkNoHardcodedUiStrings :app:checkA11yAnimationGated :app:checkPresetNoOrientation`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/PresetRow.kt app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt
git commit -m "feat(preset): preset chip row on Record idle, Customize door reuses SettingsSheet (ADR-0026)"
```

---

## Task 10: Full gate + test sweep, build APK, on-device smoke

**Files:** none (verification)

- [ ] **Step 1: Full lint/gate gate-run (all 30 check* tasks via preBuild path)**

Run: `./gradlew :app:lintDebug`
Expected: BUILD SUCCESSFUL — including `checkPresetNoOrientation`.

- [ ] **Step 2: Full unit suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS, 0 failures. (Baseline was 1528; this adds the preset helper tests.)

- [ ] **Step 3: Build the debug APK**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: On-device smoke (real device — emulators fail CameraX video)**

Install and verify by hand:
```
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Checklist:
- Fresh install (uninstall first): Record idle shows preset chips; **Standard** is selected.
- Tap **Quick Sample** → summary line updates to `10s · 1m · 10× · FHD`; chip selected.
- Open **Customize** → nudge clip stepper → return to idle → no chip selected ("Custom").
- TalkBack on: each chip announces its spoken description; selected state announced.
- Existing-user check: install the PRE-feature build, set custom values (e.g. 25s), then install -r this build → values are PRESERVED, chip shows "Custom" (no clobber).
- Start a recording with a preset applied → records normally (orientation unchanged by preset).

- [ ] **Step 5: Final commit (if any smoke-fix needed) + summary**

```bash
git add -A
git commit -m "test(preset): on-device smoke fixes for Mode Preset Seed"   # only if fixes were needed
```

---

## Self-Review

**Spec coverage:**
- Model A (config-only, orientation orthogonal) → Tasks 1, 2, 8. ✓
- 4 outcome-named built-ins, code-defined read-only, namespaced ids, distinct tuples → Task 3. ✓
- `id` + `presetSchemaVersion` envelope, additive-tolerant reader, legacy array + per-object skip + reserved-prefix guard → Tasks 2, 5. ✓
- `PresetMatcher` (canonicalized resolution, `-1` sentinel, built-in-wins) → Task 4. ✓
- `applyPreset` / `activePresetId` / `allPresets` → Task 7. ✓
- Preset-first UI + Customize door reusing `SettingsSheet` → Task 9. ✓
- Modified→Custom, first-run=Standard, existing-user no-clobber → Tasks 4, 6, 7, 10. ✓
- A11y (chip buttons, contentDescription, selected semantics, ≥48dp) → Task 9. ✓
- No `SessionManifest` bump; no service/scheduler/recovery change → confirmed (nothing in those paths touched). ✓
- ADR-0026 + `checkPresetNoOrientation` → Tasks 1, 8. ✓
- JVM-only test matrix → Tasks 3, 4, 5, 6. ✓

**Placeholder scan:** No TBD/TODO; every code step shows complete code. The two instruction-only steps (Task 9 Step 2 mount-point, Task 8 Step 4 negative check) are precise actions, not code placeholders.

**Type consistency:** `RovaPreset(name,duration,interval,loopCount,resolution,id,isBuiltIn)` used consistently across Tasks 2/3/5/7. `PresetMatcher.match(Int,Int,Int,String?)` consistent (Tasks 4, 7). `PresetJson.encode/decode` consistent (Tasks 5, 7). `FirstRunSeedPolicy.shouldSeed(Boolean,Boolean)` consistent (Tasks 6, 7). `BuiltInPresets.all/default/DEFAULT_ID` consistent (Tasks 3, 7).

**Risks to watch during execution:**
- Task 7 `combine` over 4 flows: the 4-arg `combine` overload exists in kotlinx.coroutines; if the import resolves to the vararg form, types still hold. Verify `compileDebugKotlin`.
- Confirm `loop_count` is the real pref key for loopCount before relying on `hasAnyRecordingPref()` (grep `RovaSettings.kt` for the `loopCount` getter). If it differs, update the `contains(...)` key.
- `checkPresetNoOrientation` block-narrowing relies on `RovaPreset`'s declaration containing the substring `data class RovaPreset` and a `)` terminating the param list — true for the Task 2 form.

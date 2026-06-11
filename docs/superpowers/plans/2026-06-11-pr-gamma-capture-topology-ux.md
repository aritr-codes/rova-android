# PR-γ — Capture Topology + Orientation Policy + UX Layer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Collapse the legacy orientation-carrying `mode` string into `CaptureTopology` + `OrientationPolicy` (ADR-0029 PR-γ) and ship the approved UX layer: Auto/DualShot/DualSight mode picker, Follow Device orientation row, LOCKED config-card cell, accent-iff-non-Auto rule, clip/session terminology (spec `docs/superpowers/specs/2026-06-11-capture-mode-orientation-ux-design.md`).

**Architecture:** Pure-helper pattern throughout — `CaptureTopology` (enum + persisted strings), `ModeMigration` (legacy string → axes), `OrientationPolicyResolver` (policy → effective rotation) are pure JVM-tested objects; `RovaSettings`/`SessionConfig`/service stay thin seams. Persisted topology/policy are validated strings (house style, matches the old `mode` pattern). SessionManifest schema 10→11 with legacy-`mode` read-tolerance for one release (ADR-0029 §6). Four new `check*` preBuild gates.

**Tech Stack:** Kotlin, Compose, SharedPreferences, org.json (JVM tests via `org.json:json` testImplementation), Gradle Kotlin DSL regex gates.

**Base branch:** Start from master AFTER `feat/liquid-glass-landscape-chrome` merges (this plan edits `RecordChrome.kt` / `SettingsSheet.kt` / `RecordScreen.kt`, which that branch reshapes). Branch name: `feat/pr-gamma-capture-topology`.

**Build-env constraints (load-bearing):**
- Subagents are EDIT-ONLY. Never run gradle from a subagent (broken kotlin-postedit hook corrupts the `built_in_kotlinc` cache). The controller runs one build per batch after `./gradlew --stop` + killing stray `java` processes.
- Where a step says "Run: `./gradlew …`", the CONTROLLER runs it, not the implementer subagent. Implementers still write tests first (TDD); verification is batched.
- JVM unit tests only (`isReturnDefaultValues = true`). `Surface.ROTATION_*` are plain ints 0–3 — safe in pure helpers.

**Reference docs:** `docs/adr/0029-capture-topology-orientation-policy.md` (model truth — esp. Decision 1/2/4/6 and gate sketches), `docs/superpowers/specs/2026-06-11-capture-mode-orientation-ux-design.md` (UX truth — §3 modes, §4 orientation, §5 card, §6 sheet, §7 terminology, §8 migration, §10 acceptance).

---

## Persisted-value contract (used by every task)

| Concept | Persisted string | Notes |
|---|---|---|
| Topology | `"Single"` / `"DualShot"` / `"FrontBack"` | prefs key `capture_topology` (runtimePrefs), JSON key `captureTopology` |
| Policy | `"FollowDevice"` / `"Lock"` | prefs key `orientation_policy` (runtimePrefs), JSON key `orientationPolicy` |
| Lock rotation | `0/1/2/3` (`Surface.ROTATION_*`), `-1` = unset | prefs key `orientation_lock_rotation`, JSON key `orientationLockRotation` |
| Legacy mode | `"Portrait"` / `"Landscape"` / `"PortraitLandscape"` | prefs key `mode` (left in place, read-only), JSON key `mode` (read + round-tripped, never written for new sessions) |

Migration mapping (ADR-0029 §6, Ratified-A):
`Portrait` → `Single` + `Lock(0)` · `Landscape` → `Single` + `Lock(1)` · `PortraitLandscape` → `DualShot` + `FollowDevice` · null/garbage → `Single` + `FollowDevice`.

---

### Task 1: Pure model — `CaptureTopology`, `ModeMigration`, `OrientationPolicyResolver`

**Files:**
- Create: `app/src/main/java/com/aritr/rova/data/CaptureTopology.kt`
- Create: `app/src/main/java/com/aritr/rova/data/ModeMigration.kt`
- Create: `app/src/main/java/com/aritr/rova/service/orientation/OrientationPolicyResolver.kt`
- Test: `app/src/test/java/com/aritr/rova/data/ModeMigrationTest.kt`
- Test: `app/src/test/java/com/aritr/rova/service/orientation/OrientationPolicyResolverTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
// app/src/test/java/com/aritr/rova/data/ModeMigrationTest.kt
package com.aritr.rova.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ModeMigrationTest {

    @Test
    fun portrait_migratesTo_singlePlusPortraitLock_flaggedLegacy() {
        val m = ModeMigration.migrate("Portrait")
        assertEquals("Single", m.topology)
        assertEquals("Lock", m.policy)
        assertEquals(0, m.lockRotation) // Surface.ROTATION_0
        assertEquals(true, m.legacyMigrated)
    }

    @Test
    fun landscape_migratesTo_singlePlusLandscapeLock_flaggedLegacy() {
        val m = ModeMigration.migrate("Landscape")
        assertEquals("Single", m.topology)
        assertEquals("Lock", m.policy)
        assertEquals(1, m.lockRotation) // Surface.ROTATION_90
        assertEquals(true, m.legacyMigrated)
    }

    @Test
    fun portraitLandscape_migratesTo_dualShot_followDevice() {
        val m = ModeMigration.migrate("PortraitLandscape")
        assertEquals("DualShot", m.topology)
        assertEquals("FollowDevice", m.policy)
        assertEquals(-1, m.lockRotation)
        assertEquals(false, m.legacyMigrated)
    }

    @Test
    fun nullOrGarbage_migratesTo_singleFollowDevice() {
        for (input in listOf(null, "", "P + L", "single")) {
            val m = ModeMigration.migrate(input)
            assertEquals("Single", m.topology)
            assertEquals("FollowDevice", m.policy)
            assertEquals(false, m.legacyMigrated)
        }
    }

    @Test
    fun captureTopology_validPersistedValues() {
        assertEquals(true, CaptureTopology.isValidPersisted("Single"))
        assertEquals(true, CaptureTopology.isValidPersisted("DualShot"))
        assertEquals(true, CaptureTopology.isValidPersisted("FrontBack"))
        assertEquals(false, CaptureTopology.isValidPersisted("Portrait"))
        assertEquals(false, CaptureTopology.isValidPersisted("PortraitLandscape"))
        assertEquals(false, CaptureTopology.isValidPersisted(""))
    }
}
```

```kotlin
// app/src/test/java/com/aritr/rova/service/orientation/OrientationPolicyResolverTest.kt
package com.aritr.rova.service.orientation

import org.junit.Assert.assertEquals
import org.junit.Test

class OrientationPolicyResolverTest {

    @Test
    fun followDevice_passesSnappedRotationThrough() {
        for (snapped in 0..3) {
            assertEquals(snapped, OrientationPolicyResolver.resolve("FollowDevice", -1, snapped))
        }
    }

    @Test
    fun lock_pinsToStoredRotation_ignoringSnapped() {
        assertEquals(0, OrientationPolicyResolver.resolve("Lock", 0, 3))
        assertEquals(1, OrientationPolicyResolver.resolve("Lock", 1, 0))
        assertEquals(3, OrientationPolicyResolver.resolve("Lock", 3, 1))
    }

    @Test
    fun lockWithInvalidStoredRotation_fallsBackToSnapped() {
        // Defensive: a "Lock" policy with rotation outside 0..3 must not pin garbage.
        assertEquals(2, OrientationPolicyResolver.resolve("Lock", -1, 2))
        assertEquals(2, OrientationPolicyResolver.resolve("Lock", 7, 2))
    }

    @Test
    fun unknownPolicyString_behavesAsFollowDevice() {
        assertEquals(1, OrientationPolicyResolver.resolve("Auto", -1, 1))
        assertEquals(1, OrientationPolicyResolver.resolve("", -1, 1))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Controller runs: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.data.ModeMigrationTest" --tests "com.aritr.rova.service.orientation.OrientationPolicyResolverTest"`
Expected: FAIL — unresolved references `ModeMigration`, `CaptureTopology`, `OrientationPolicyResolver`.

- [ ] **Step 3: Implement the three pure units**

```kotlin
// app/src/main/java/com/aritr/rova/data/CaptureTopology.kt
package com.aritr.rova.data

/**
 * ADR-0029 Decision 1 — capture-topology axis. `Single` replaces BOTH legacy
 * "Portrait" and "Landscape" (they differed only in orientation, never in
 * topology). Persisted form is the enum's [persisted] string; orientation is
 * the separate OrientationPolicy axis (never conflated here).
 */
enum class CaptureTopology(val persisted: String) {
    Single("Single"),
    DualShot("DualShot"),
    FrontBack("FrontBack");

    companion object {
        fun isValidPersisted(value: String): Boolean = entries.any { it.persisted == value }
        fun fromPersisted(value: String?): CaptureTopology =
            entries.firstOrNull { it.persisted == value } ?: Single
    }
}
```

```kotlin
// app/src/main/java/com/aritr/rova/data/ModeMigration.kt
package com.aritr.rova.data

/**
 * ADR-0029 §6 [Ratified A] — pure legacy-mode mapper. Legacy "Portrait" never
 * meant "portrait lock", only rotation-at-bind; Lock(ROTATION_0) preserves the
 * TYPICAL-case output (documented lossy assumption — no per-clip rotation
 * history was ever persisted). Migrated users keep least-surprise; NEW users
 * default to FollowDevice via RovaSettings defaults, not via this mapper.
 */
object ModeMigration {

    data class Migrated(
        val topology: String,
        val policy: String,
        val lockRotation: Int,
        val legacyMigrated: Boolean,
    )

    fun migrate(legacyMode: String?): Migrated = when (legacyMode) {
        "Portrait" -> Migrated("Single", "Lock", 0, legacyMigrated = true)
        "Landscape" -> Migrated("Single", "Lock", 1, legacyMigrated = true)
        "PortraitLandscape" -> Migrated("DualShot", "FollowDevice", -1, legacyMigrated = false)
        else -> Migrated("Single", "FollowDevice", -1, legacyMigrated = false)
    }
}
```

```kotlin
// app/src/main/java/com/aritr/rova/service/orientation/OrientationPolicyResolver.kt
package com.aritr.rova.service.orientation

/**
 * ADR-0029 [Ratified D] — (policy, snapped device rotation) -> effectiveTargetRotation.
 * FollowDevice passes the snapped rotation through; a lock resolves to the
 * EXPLICIT Surface.ROTATION_* captured at lock time (four-rotation model, so
 * reverse/180 locks later are additive with no prefs migration). A Lock with
 * an out-of-range stored rotation degrades to FollowDevice rather than
 * pinning garbage.
 */
object OrientationPolicyResolver {
    fun resolve(policy: String, lockRotation: Int, snappedRotation: Int): Int =
        if (policy == "Lock" && lockRotation in 0..3) lockRotation else snappedRotation
}
```

- [ ] **Step 4: Run tests to verify they pass**

Controller runs the Step 2 command. Expected: PASS (9 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/data/CaptureTopology.kt app/src/main/java/com/aritr/rova/data/ModeMigration.kt app/src/main/java/com/aritr/rova/service/orientation/OrientationPolicyResolver.kt app/src/test/java/com/aritr/rova/data/ModeMigrationTest.kt app/src/test/java/com/aritr/rova/service/orientation/OrientationPolicyResolverTest.kt
git commit -m "feat(gamma): CaptureTopology + ModeMigration + OrientationPolicyResolver pure model (ADR-0029 D1/D6/RatD)"
```

---

### Task 2: `RovaSettings` — new axes + one-shot lazy migration

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/data/RovaSettings.kt` (mode property at lines 68–71)

No new JVM test file — SharedPreferences is framework; the mapping logic is already pinned by `ModeMigrationTest`. The seam stays thin.

- [ ] **Step 1: Replace the `mode` property with the two axes + migration**

Delete the `var mode: String` property (lines 68–71) and add in its place:

```kotlin
/**
 * ADR-0029 PR-γ — capture-topology axis ("Single"/"DualShot"/"FrontBack").
 * runtimePrefs (backup-excluded) for the same reason legacy `mode` was:
 * reinstalls must default, not resurrect a backed-up special mode.
 * The legacy "mode" key is migrated once and then left in place, read-only,
 * for one release (ADR-0029 §6).
 */
var captureTopology: String
    get() {
        migrateLegacyModeIfNeeded()
        return (runtimePrefs.getString("capture_topology", "Single") ?: "Single")
            .takeIf { CaptureTopology.isValidPersisted(it) } ?: "Single"
    }
    set(value) = runtimePrefs.edit { putString("capture_topology", value) }

/** ADR-0029 PR-γ — orientation-policy axis: "FollowDevice" (default) or "Lock". */
var orientationPolicy: String
    get() {
        migrateLegacyModeIfNeeded()
        return (runtimePrefs.getString("orientation_policy", "FollowDevice") ?: "FollowDevice")
            .takeIf { it == "FollowDevice" || it == "Lock" } ?: "FollowDevice"
    }
    set(value) = runtimePrefs.edit { putString("orientation_policy", value) }

/** Surface.ROTATION_* captured at lock time (Ratified-D); -1 when not locked. */
var orientationLockRotation: Int
    get() {
        migrateLegacyModeIfNeeded()
        return runtimePrefs.getInt("orientation_lock_rotation", -1)
    }
    set(value) = runtimePrefs.edit { putInt("orientation_lock_rotation", value) }

private fun migrateLegacyModeIfNeeded() {
    if (runtimePrefs.contains("capture_topology")) return
    val legacy = runtimePrefs.getString("mode", null)
    val m = ModeMigration.migrate(legacy)
    runtimePrefs.edit {
        putString("capture_topology", m.topology)
        putString("orientation_policy", m.policy)
        if (m.lockRotation in 0..3) putInt("orientation_lock_rotation", m.lockRotation)
        // legacy "mode" key deliberately left in place for one release (ADR-0029 §6)
    }
}
```

Note: a fresh install has no `"mode"` key → `migrate(null)` → Single/FollowDevice → new-user `Auto` default (Ratified-A; the condition is satisfied because Task 7 puts the ORIENTATION row on the pre-record sheet). The migration also runs (idempotently, guarded by the `contains` check) from whichever axis getter is touched first.

- [ ] **Step 2: Fix compile breakage from removed `RovaSettings.mode`**

Compile will fail at every `settings.mode` consumer. Do NOT fix consumers here beyond making them compile against the new axis — semantic switches happen in Tasks 4–6. In this task only mechanical renames where the meaning is identical:
- `RecordViewModel.kt` line ~280: `settings.mode = mode` → `settings.captureTopology = mode` and the StateFlow init `MutableStateFlow(settings.mode)` → `MutableStateFlow(settings.captureTopology)`. (The values flowing through become topology strings in Task 5; at this commit the ViewModel still passes whatever the UI sends — that's fine, the branch is not shippable mid-plan.)
- Any other `settings.mode` read found by `grep -rn "settings.mode\|\.mode =" app/src/main/java` → switch to `captureTopology`.

- [ ] **Step 3: Controller compile check**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (warnings OK).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/aritr/rova/data/RovaSettings.kt app/src/main/java/com/aritr/rova/ui/screens/RecordViewModel.kt
git commit -m "feat(gamma): RovaSettings capture_topology/orientation_policy axes + one-shot legacy-mode migration (ADR-0029 S6)"
```

---

### Task 3: `SessionConfig` schema 11 — topology/policy fields + legacy read-tolerance

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/data/SessionManifest.kt` (SCHEMA_VERSION line ~185; SessionConfig lines 276–301)
- Test: `app/src/test/java/com/aritr/rova/data/SessionConfigModeTest.kt` (rewrite)

- [ ] **Step 1: Rewrite the test file as failing topology tests**

Replace the body of `SessionConfigModeTest.kt` (keep package/imports plus `org.json.JSONObject`):

```kotlin
package com.aritr.rova.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionConfigModeTest {

    private fun base(topology: String = "Single") = SessionConfig(
        durationSeconds = 5, intervalMinutes = 0, resolution = "FHD", loopCount = 2,
        captureTopology = topology,
    )

    @Test
    fun defaults_areSingle_followDevice_noLock_noLegacy() {
        val c = SessionConfig(durationSeconds = 5, intervalMinutes = 0, resolution = "FHD", loopCount = 2)
        assertEquals("Single", c.captureTopology)
        assertEquals("FollowDevice", c.orientationPolicy)
        assertEquals(-1, c.orientationLockRotation)
        assertEquals(null, c.legacyMode)
    }

    @Test
    fun toJson_writesTopologyAndPolicy_omitsLockAndLegacyWhenUnset() {
        val json = base().toJson()
        assertEquals("Single", json.getString("captureTopology"))
        assertEquals("FollowDevice", json.getString("orientationPolicy"))
        assertFalse(json.has("orientationLockRotation"))
        assertFalse(json.has("mode")) // new sessions never write the legacy key
    }

    @Test
    fun lockedConfig_roundTrips() {
        val c = base().copy(orientationPolicy = "Lock", orientationLockRotation = 1)
        val back = SessionConfig.fromJson(c.toJson())
        assertEquals("Lock", back.orientationPolicy)
        assertEquals(1, back.orientationLockRotation)
    }

    @Test
    fun dualShot_roundTrips() {
        val back = SessionConfig.fromJson(base("DualShot").toJson())
        assertEquals("DualShot", back.captureTopology)
    }

    @Test
    fun legacyManifest_portraitLandscape_derivesDualShot_andPreservesLegacyMode() {
        // A schema<=10 manifest: has "mode", no "captureTopology" (ADR-0029 S6 read-compat).
        val legacy = JSONObject().apply {
            put("durationSeconds", 5); put("intervalMinutes", 0)
            put("resolution", "FHD"); put("loopCount", 2)
            put("mode", "PortraitLandscape")
        }
        val c = SessionConfig.fromJson(legacy)
        assertEquals("DualShot", c.captureTopology)
        assertEquals("PortraitLandscape", c.legacyMode)
        // Round-trip must NOT lose the historical label (recovery rewrites manifests).
        assertEquals("PortraitLandscape", c.toJson().getString("mode"))
    }

    @Test
    fun legacyManifest_portraitAndLandscape_deriveSingle() {
        for (legacyMode in listOf("Portrait", "Landscape")) {
            val legacy = JSONObject().apply {
                put("durationSeconds", 5); put("intervalMinutes", 0)
                put("resolution", "FHD"); put("loopCount", 2)
                put("mode", legacyMode)
            }
            val c = SessionConfig.fromJson(legacy)
            assertEquals("Single", c.captureTopology)
            assertEquals(legacyMode, c.legacyMode)
        }
    }

    @Test
    fun garbageTopology_coercesToSingle() {
        val json = base().toJson().put("captureTopology", "P + L")
        assertEquals("Single", SessionConfig.fromJson(json).captureTopology)
    }

    @Test
    fun garbagePolicy_coercesToFollowDevice() {
        val json = base().toJson().put("orientationPolicy", "Auto")
        assertEquals("FollowDevice", SessionConfig.fromJson(json).orientationPolicy)
    }
}
```

- [ ] **Step 2: Controller verifies FAIL** (`--tests "com.aritr.rova.data.SessionConfigModeTest"` — unresolved `captureTopology` etc.)

- [ ] **Step 3: Implement the new SessionConfig**

Replace the `SessionConfig` data class (SessionManifest.kt lines 276–301) with:

```kotlin
data class SessionConfig(
    val durationSeconds: Int,
    val intervalMinutes: Int,
    val resolution: String,
    val loopCount: Int,
    /** ADR-0029 PR-γ axes (schema 11). */
    val captureTopology: String = "Single",
    val orientationPolicy: String = "FollowDevice",
    val orientationLockRotation: Int = -1,
    /**
     * ADR-0029 §6 — the legacy schema<=10 "mode" label, preserved verbatim so
     * recovery rewrites of old manifests never lose the historical capture
     * meaning. Read-only; never set for new sessions.
     */
    val legacyMode: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("durationSeconds", durationSeconds)
        put("intervalMinutes", intervalMinutes)
        put("resolution", resolution)
        put("loopCount", loopCount)
        put("captureTopology", captureTopology)
        put("orientationPolicy", orientationPolicy)
        if (orientationLockRotation in 0..3) put("orientationLockRotation", orientationLockRotation)
        legacyMode?.let { put("mode", it) }
    }

    companion object {
        fun fromJson(json: JSONObject): SessionConfig {
            val legacy = json.optString("mode", "").ifEmpty { null }
                ?.takeIf { it == "Portrait" || it == "Landscape" || it == "PortraitLandscape" }
            val topology = json.optString("captureTopology", "").ifEmpty { null }
                ?.takeIf { CaptureTopology.isValidPersisted(it) }
                ?: ModeMigration.migrate(legacy).topology
            return SessionConfig(
                durationSeconds = json.getInt("durationSeconds"),
                intervalMinutes = json.getInt("intervalMinutes"),
                resolution = json.getString("resolution"),
                loopCount = json.getInt("loopCount"),
                captureTopology = topology,
                orientationPolicy = json.optString("orientationPolicy", "")
                    .takeIf { it == "FollowDevice" || it == "Lock" } ?: "FollowDevice",
                orientationLockRotation = json.optInt("orientationLockRotation", -1),
                legacyMode = legacy,
            )
        }
    }
}
```

Bump the schema constant (line ~185):

```kotlin
const val SCHEMA_VERSION = 11   // 10->11: captureTopology/orientationPolicy axes, legacy mode read-only (ADR-0029 PR-γ §6)
```

- [ ] **Step 4: Fix `config.mode` consumers (mechanical, same meaning)**

Every `manifest.config.mode == "PortraitLandscape"` becomes `manifest.config.captureTopology == "DualShot"` (old manifests derive DualShot in `fromJson`, so behavior is identical):
- `app/src/main/java/com/aritr/rova/service/export/VaultMoverBuilder.kt:43` → `manifest.config.captureTopology != "DualShot"`
- `app/src/main/java/com/aritr/rova/service/recovery/RecoveryScanner.kt:242` → `manifest.config.captureTopology == "DualShot"`
- `StorageEstimator.kt:86` → `val sides = if (captureTopology == "DualShot") 2L else 1L` (rename the local/param it reads from; trace its caller, which now passes topology)
- `SessionConfig(...)` constructor call sites (grep `SessionConfig(`): the session-start path (RecordViewModel/service) passes `captureTopology = settings.captureTopology, orientationPolicy = settings.orientationPolicy, orientationLockRotation = settings.orientationLockRotation` instead of `mode = ...`; `ScheduleController`'s config construction gets the same treatment. Check `SessionManifestModeMigrationTest.kt` and update its expectations to the new derivation (legacy fixtures keep passing through `legacyMode`).

- [ ] **Step 5: Controller runs the data tests + compile** — `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --tests "com.aritr.rova.data.*"`. Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add -A app/src/main/java app/src/test/java
git commit -m "feat(gamma): SessionConfig schema 11 — captureTopology/orientationPolicy + legacy mode read-tolerance (ADR-0029 S6)"
```

---

### Task 4: Service — topology branch + policy-resolved rotation

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt` (`currentMode` field; `setupCamera()` ~line 1625; `setMode()` ~line 2321; PR-α effective-rotation sample site)
- Modify: `app/src/main/java/com/aritr/rova/service/orientation/OrientationSnap.kt` (retire `DEFAULT_ORIENTATION_POLICY_IS_AUTO`)

- [ ] **Step 1: Rename the mode field + branch on topology**

- `private var currentMode: String` → `private var currentTopology: String` (initialize from `RovaSettings(this).captureTopology`).
- `setupCamera()`:

```kotlin
private suspend fun setupCamera() {
    if (currentTopology == "DualShot") {
        setupDualCamera()
    } else {
        setupSingleCamera()
    }
}
```

- `fun setMode(mode: String)` → `fun setTopology(topology: String)`; inside, replace `mode == "PortraitLandscape"` with `topology == "DualShot"` (rear-snap logic and `LensFlipPolicy` restore branch otherwise unchanged — DualShot stays rear-only per ADR-0009/B6). `ModeReconfigurePolicy.shouldSkipReconfigure(requestedMode = topology, currentMode = currentTopology, ...)` — parameter names unchanged (pure helper, string-agnostic).
- The P+L branch at ~line 2347 follows the same substitution.
- Binder/ViewModel call `serviceBinder?.getService()?.setMode(mode)` → `setTopology(topology)` (RecordViewModel updated in Task 5; make it compile here with the rename).

- [ ] **Step 2: Replace the Auto-const gate with the resolver**

Find the PR-α segment-start sample site (grep `effectiveTargetRotation` and `DEFAULT_ORIENTATION_POLICY_IS_AUTO` in `RovaRecordingService.kt`). Today it is gated by the boolean const. Replace with:

```kotlin
val policy = sessionConfig.orientationPolicy          // session-frozen at start (schema 11)
val lockRotation = sessionConfig.orientationLockRotation
val effective = OrientationPolicyResolver.resolve(policy, lockRotation, snappedRotation)
```

where `snappedRotation` is the existing snapper output (with its existing first-sample fallback). The `OrientationEventListener` stays enabled for Single regardless of lock (the HUD pending-orientation state still consumes it; the resolver pins the encoded output). DualShot path untouched (ADR-0029 §4 — owns its rotation; never enables the listener).

In `OrientationSnap.kt` delete `DEFAULT_ORIENTATION_POLICY_IS_AUTO` (line 31) and its consumers — the default now lives in `RovaSettings.orientationPolicy = "FollowDevice"`. Update the line-24 comment to point at `OrientationPolicyResolver`.

- [ ] **Step 3: Controller compile + full data/service tests** — `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --tests "com.aritr.rova.service.*" --tests "com.aritr.rova.data.*"`. Expected: PASS (OrientationSnapTest unaffected — it pins snap/hysteresis, not the deleted const; if it references the const, update the test to drop that pin).

- [ ] **Step 4: Commit**

```bash
git add -A app/src/main/java app/src/test/java
git commit -m "feat(gamma): service branches on CaptureTopology; effective rotation via OrientationPolicyResolver (ADR-0029 D2/D4)"
```

---

### Task 5: Mode registry + ViewModel axes

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/screens/CaptureModes.kt`
- Delete: `app/src/main/java/com/aritr/rova/ui/screens/RecordModeCycle.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordViewModel.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/screens/CaptureModesTest.kt` (replaces `RecordModeCycleTest.kt` — delete it)

- [ ] **Step 1: Write the failing registry test**

```kotlin
// app/src/test/java/com/aritr/rova/ui/screens/CaptureModesTest.kt
package com.aritr.rova.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureModesTest {

    @Test
    fun visible_isAutoAndDualShot_whileDualSightDisabled() {
        // PR-γ ships DualSight as a registry entry only (spec §3); flips in PR-δ.
        assertEquals(listOf(CaptureMode.Auto, CaptureMode.DualShot), CaptureMode.visible())
    }

    @Test
    fun forTopology_mapsPersistedStrings_andDefaultsToAuto() {
        assertEquals(CaptureMode.Auto, CaptureMode.forTopology("Single"))
        assertEquals(CaptureMode.DualShot, CaptureMode.forTopology("DualShot"))
        assertEquals(CaptureMode.DualSight, CaptureMode.forTopology("FrontBack"))
        assertEquals(CaptureMode.Auto, CaptureMode.forTopology("Portrait")) // legacy/garbage
    }

    @Test
    fun isAccented_onlyForNonDefaultModes() {
        // Spec §5 Variant A (owner-ratified): accent iff mode != Auto.
        assertEquals(false, CaptureMode.isAccented("Single"))
        assertEquals(true, CaptureMode.isAccented("DualShot"))
        assertEquals(true, CaptureMode.isAccented("FrontBack"))
    }

    @Test
    fun cycleNext_loopsThroughVisibleModesOnly() {
        assertEquals("DualShot", CaptureMode.cycleNext("Single"))
        assertEquals("Single", CaptureMode.cycleNext("DualShot"))
        assertEquals("Single", CaptureMode.cycleNext("FrontBack")) // hidden -> snaps into visible ring
        assertEquals("DualShot", CaptureMode.cycleNext("garbage"))  // forTopology -> Auto -> next
    }

    @Test
    fun lockRotationForLandscapePick_usesCurrentRotationWhenLandscape_else90() {
        assertEquals(1, lockRotationForLandscapePick(1))
        assertEquals(3, lockRotationForLandscapePick(3))
        assertEquals(1, lockRotationForLandscapePick(0))
        assertEquals(1, lockRotationForLandscapePick(2))
        assertEquals(1, lockRotationForLandscapePick(null))
    }
}
```

- [ ] **Step 2: Controller verifies FAIL** (unresolved `CaptureMode`).

- [ ] **Step 3: Implement the registry**

```kotlin
// app/src/main/java/com/aritr/rova/ui/screens/CaptureModes.kt
package com.aritr.rova.ui.screens

import androidx.annotation.StringRes
import com.aritr.rova.R

/**
 * Spec 2026-06-11 §3 — the user-facing capture-mode registry. Mode = capture
 * strategy (CaptureTopology), NEVER orientation. Picker tabs, the record-home
 * cycle chip, and captions all render from this single list, so a future mode
 * is one entry here, no layout work.
 */
enum class CaptureMode(
    val topology: String,
    @StringRes val labelRes: Int,
    @StringRes val captionRes: Int,
) {
    Auto("Single", R.string.capture_mode_auto, R.string.capture_mode_auto_caption),
    DualShot("DualShot", R.string.capture_mode_dualshot, R.string.capture_mode_dualshot_caption),
    DualSight("FrontBack", R.string.capture_mode_dualsight, R.string.capture_mode_dualsight_caption);

    companion object {
        /**
         * PR-δ flips this behind the concurrent-camera capability gate
         * (ADR-0029 §5 — gate on getConcurrentCameraIds combinations, never
         * API level). Until then DualSight is registry-only.
         */
        const val DUALSIGHT_ENABLED: Boolean = false

        fun visible(): List<CaptureMode> = entries.filter { it != DualSight || DUALSIGHT_ENABLED }

        fun forTopology(topology: String): CaptureMode =
            entries.firstOrNull { it.topology == topology } ?: Auto

        /** Spec §5 Variant A (owner-ratified): accent gradient iff mode != Auto. */
        fun isAccented(topology: String): Boolean = forTopology(topology) != Auto

        fun cycleNext(topology: String): String {
            val ring = visible()
            val i = ring.indexOf(forTopology(topology)).coerceAtLeast(0)
            return ring[(i + 1) % ring.size].topology
        }
    }
}

/**
 * Ratified-D "lock whatever I'm aimed at now": Lock Landscape captures the
 * CURRENTLY-snapped landscape rotation when the device is in one (90 or 270),
 * else defaults to ROTATION_90. Lock Portrait is always ROTATION_0.
 */
internal fun lockRotationForLandscapePick(currentDeviceRotation: Int?): Int =
    if (currentDeviceRotation == 1 || currentDeviceRotation == 3) currentDeviceRotation else 1
```

Delete `RecordModeCycle.kt` + `RecordModeCycleTest.kt`; the cycle call site (RecordScreen/Chrome `onCycleMode`) switches to `CaptureMode.cycleNext(...)`.

- [ ] **Step 4: ViewModel axes**

In `RecordViewModel.kt`:
- Rename the flow: `val mode = MutableStateFlow(settings.captureTopology)` → `val topology = MutableStateFlow(settings.captureTopology)` (update collectors; if churn is wide, keep the property name `mode` and just note it carries topology strings — implementer judgment, prefer the rename if ≤ ~15 sites).
- `setMode(mode)` → `setTopology(topology)`:

```kotlin
fun setTopology(topology: String) {
    settings.captureTopology = topology                       // (1) prefs commit
    this.topology.value = topology                            // (2) StateFlow update
    serviceBinder?.getService()?.setTopology(topology)        // (3) service reconfigure
}
```

- Add orientation axes (same 3-step shape; resume-reseed both VMs pattern applies — see B1 memory):

```kotlin
val orientationPolicy = MutableStateFlow(settings.orientationPolicy)
val orientationLockRotation = MutableStateFlow(settings.orientationLockRotation)

fun setOrientationPolicy(policy: String, lockRotation: Int) {
    settings.orientationPolicy = policy
    settings.orientationLockRotation = lockRotation
    orientationPolicy.value = policy
    orientationLockRotation.value = lockRotation
}
```

- [ ] **Step 5: Controller runs** `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.CaptureModesTest"`. Expected: PASS. (Full compile will still fail until Task 6 strings exist — if so, controller runs Task 5+6 builds together; implementers commit independently.)

- [ ] **Step 6: Commit**

```bash
git add -A app/src/main/java app/src/test/java
git commit -m "feat(gamma): CaptureMode registry (Auto/DualShot/DualSight) + ViewModel topology/orientation axes (spec S3/S4)"
```

---

### Task 6: Strings — new mode/orientation strings + terminology renames (en + es)

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-es/strings.xml`

Pure resource edits. Spec §7 is the contract.

- [ ] **Step 1: Add new strings (en)**

```xml
<!-- Capture modes (spec 2026-06-11 §3). DualShot/DualSight are brand names, both locales. -->
<string name="capture_mode_auto">Auto</string>
<string name="capture_mode_dualshot" translatable="false">DualShot</string>
<string name="capture_mode_dualsight" translatable="false">DualSight</string>
<string name="capture_mode_auto_caption">Records clips, rotates with your device</string>
<string name="capture_mode_dualshot_caption">Every clip saved as portrait + landscape (2× storage)</string>
<string name="capture_mode_dualsight_caption">Front + rear cameras in one video</string>
<!-- Orientation policy row (spec §4). "Auto" is reserved for the mode; policy says Follow Device. -->
<string name="settings_sheet_orientation">Orientation</string>
<string name="orientation_follow_device">Follow Device</string>
<string name="orientation_lock_portrait">Lock Portrait</string>
<string name="orientation_lock_landscape">Lock Landscape</string>
<!-- Locked-state config cell (spec §5) -->
<string name="record_cell_locked">Locked</string>
<string name="record_locked_portrait">Portrait</string>
<string name="record_locked_landscape">Landscape</string>
```

es twins (values-es/strings.xml): captions/labels translated — `capture_mode_auto` = "Auto", caption = "Graba clips y rota con tu dispositivo"; DualShot caption = "Cada clip se guarda en vertical + horizontal (2× almacenamiento)"; DualSight caption = "Cámaras frontal y trasera en un solo video"; `settings_sheet_orientation` = "Orientación"; `orientation_follow_device` = "Seguir dispositivo"; `orientation_lock_portrait` = "Bloquear vertical"; `orientation_lock_landscape` = "Bloquear horizontal"; `record_cell_locked` = "Bloqueado"; `record_locked_portrait` = "Vertical"; `record_locked_landscape` = "Horizontal".

- [ ] **Step 2: Apply the §7 rename table (en + es values; resource NAMES unchanged unless deleted)**

en changes:
- `record_loops_done_caption`: `LOOPS DONE` → `CLIPS DONE`
- `record_hud_loops_done`: `%1$d/%2$d loops done` → `%1$d/%2$d clips done`
- `record_hud_loops_done_indefinite`: `%1$d loops done` → `%1$d clips done`
- `record_hud_loops_remaining`: `%1$d of %2$d loops remaining` → `%1$d of %2$d clips remaining`
- `record_cell_clip`: `Clip` → `Length`
- `record_cell_repeats`: `Repeats` → `Clips`
- `settings_sheet_clip_duration`: `Clip Duration` → `Clip length`
- `settings_sheet_repeats`: `Repeats` → `Clips`
- `settings_clip_duration_label`: `Clip duration` → `Clip length`
- `settings_loops_label`: `Number of loops` → `Number of clips`
- `history_config_repeats`: `Repeats` → `Clips`
- `notification_segment_saved`: `Segment Saved: %1$d KB` → `Clip saved (%1$d KB)`
- `notification_empty_segment`: `Recording produced an empty segment` → `Recording produced an empty clip`
- DELETE: `settings_sheet_mode_portrait`, `settings_sheet_mode_landscape`, `settings_sheet_mode_pl`, `record_mode_pl_label` (consumers replaced in Tasks 5/7; deleting here is safe only after Task 7 lands — if compiling between tasks, delete in the same controller batch as Task 7).

es: apply the same renames to the es values (e.g. `record_loops_done_caption` `CICLOS HECHOS` → `CLIPS HECHOS`; "Repeticiones" → "Clips"; segment strings → "clip"). Also update line-712-area comments if they reference loops (comments are non-user-facing but keep them honest).

- [ ] **Step 3: Sweep for stragglers**

Run (implementer, read-only): grep both strings.xml for `\b[Ll]oops?\b|\b[Rr]epeats?\b|\b[Ss]egments?\b|[Cc]iclos?|[Ss]egmentos?|[Rr]epeticion` in VALUES. Every hit either gets renamed per the spec vocabulary or justified in the commit message (resource *names* containing `loops`/`repeats` are internal and exempt).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/values/strings.xml app/src/main/res/values-es/strings.xml
git commit -m "feat(gamma): clip/session terminology + capture-mode/orientation strings en+es (spec S7)"
```

---

### Task 7: UI — picker tabs, cycle chip accent rule, LOCKED cell, ORIENTATION row

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/SettingsSheet.kt` (SheetModeTab ~722, ModeTabs ~728, SettingsContent)
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt` (ModeCycleChip ~419, RecordSettingsCard ~293)
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt` (modeLabel ~917, plumbing for new params)

- [ ] **Step 1: Replace `SheetModeTab` with registry-driven tabs + caption**

Delete the `SheetModeTab` enum. Rewrite `ModeTabs`:

```kotlin
@Composable
private fun ModeTabs(currentTopology: String, enabled: Boolean, onPick: (String) -> Unit) {
    // Active tab paints the liquid-glass accent gradient — the documented
    // record-chrome contrast exception (ADR-0020). Tabs render from the
    // CaptureMode registry (spec 2026-06-11 §3): a future mode is one entry.
    val palette = LocalGlassEnvironment.current.palette
    val activeBrush = remember(palette) { Brush.linearGradient(listOf(palette.accent, palette.accent2)) }
    val modes = CaptureMode.visible()
    val current = CaptureMode.forTopology(currentTopology)
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(SettingsSheetTokens.modeTabsRadius))
                .background(SettingsSheetTokens.modeTabsTrackFill)
                .padding(SettingsSheetTokens.modeTabsPadding),
            horizontalArrangement = Arrangement.spacedBy(SettingsSheetTokens.modeTabsGap),
        ) {
            modes.forEach { mode ->
                val isActive = current == mode
                // ...keep the existing tabShape/tabModifier/shadow/focusHighlight body verbatim,
                // with label Text(stringResource(mode.labelRes)) and onPick(mode.topology)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(current.captionRes),
            style = RovaTokens.sheetSupporting,   // match the existing supporting-text style used by stepper rows
            color = SettingsSheetTokens.sectionLabelColor,
        )
    }
}
```

(Implementer: keep the existing per-tab modifier chain byte-for-byte — only the data source, label, and pick value change. If `RovaTokens.sheetSupporting` doesn't exist, reuse whatever style `settings_clip_duration_supporting` text uses.)

- [ ] **Step 2: ORIENTATION row in `SettingsContent`**

After the existing Quality section (locate `SheetSectionLabel` usages for order), add:

```kotlin
SheetSectionLabel(stringResource(R.string.settings_sheet_orientation))
OrientationRow(
    policy = orientationPolicy,
    lockRotation = orientationLockRotation,
    // DualShot owns its rotation (ADR-0029 §4 + DualShotPortraitGate) — the row
    // is inert while DualShot is the active topology.
    enabled = editable && currentTopology != "DualShot",
    onPick = onOrientationPick,
)
```

```kotlin
@Composable
private fun OrientationRow(
    policy: String,
    lockRotation: Int,
    enabled: Boolean,
    onPick: (policy: String, lockRotation: Int) -> Unit,
) {
    // Deliberately QUIET (spec §4): neutral active fill, no accent gradient —
    // orientation is a setting, not a capture strategy.
    data class Option(val label: Int, val policy: String, val lock: Int)
    val currentRotation = LocalConfiguration.current.let { null as Int? } // see note below
    val options = listOf(
        Option(R.string.orientation_follow_device, "FollowDevice", -1),
        Option(R.string.orientation_lock_portrait, "Lock", 0),
        Option(R.string.orientation_lock_landscape, "Lock", lockRotationForLandscapePick(currentRotation)),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(SettingsSheetTokens.modeTabsRadius))
            .background(SettingsSheetTokens.modeTabsTrackFill)
            .padding(SettingsSheetTokens.modeTabsPadding),
        horizontalArrangement = Arrangement.spacedBy(SettingsSheetTokens.modeTabsGap),
    ) {
        options.forEach { opt ->
            val isActive = when {
                opt.policy == "FollowDevice" -> policy == "FollowDevice"
                else -> policy == "Lock" && ((opt.lock == 0 && lockRotation in listOf(0, 2)) ||
                        (opt.lock != 0 && lockRotation in listOf(1, 3)))
            }
            // same tab chrome as ModeTabs but active fill = Color.White.copy(alpha = 0.12f)
            // (NOT the accent brush); alpha-dim the whole row when !enabled.
            // onClick: onPick(opt.policy, opt.lock)
        }
    }
}
```

Note on `currentRotation`: pass the actual snapped rotation if `RecordScreen` already exposes the current sense (it does — the landscape-chrome branch computes `currentSense`); plumb it down as a parameter `currentDeviceRotation: Int?` instead of the placeholder shown above. `lockRotationForLandscapePick` (Task 5) handles null.

Plumb `orientationPolicy`/`orientationLockRotation`/`onOrientationPick`/`currentTopology` through `SettingsContent`'s callers (`SettingsBottomSheet`, `SettingsSidePanel`) from `RecordViewModel` (`collectAsState` in `RecordScreen`, same pattern as `mode` today). Wire `onOrientationPick = viewModel::setOrientationPolicy`.

- [ ] **Step 3: `ModeCycleChip` — registry label + accent-iff-non-Auto**

In `ModeCycleChip` (RecordChrome.kt ~419):

```kotlin
val accented = CaptureMode.isAccented(mode)
val palette = LocalGlassEnvironment.current.palette
val selectedBrush = Brush.linearGradient(listOf(palette.accent, palette.accent2))
// background: if (accented) .background(selectedBrush, chipShape)
//             else the same fill/text tokens SettingsCell uses (quiet cell)
// label Text: stringResource(CaptureMode.forTopology(mode).labelRes)
// text colors: white-on-gradient when accented (ADR-0020 exemption);
//              RecordChromeTokens.cellValueText/cellKeyText when quiet.
```

And `RecordScreen.kt:917`: `val modeLabel = stringResource(CaptureMode.forTopology(mode).labelRes)` (drop the `record_mode_pl_label` conditional). The chip's `onCycleMode` call site switches to `viewModel.setTopology(CaptureMode.cycleNext(currentTopology))`.

- [ ] **Step 4: LOCKED cell in `RecordSettingsCard`**

Add params `orientationPolicy: String` and `orientationLockRotation: Int` to `RecordSettingsCard`; after the `ModeCycleChip` cell:

```kotlin
if (orientationPolicy == "Lock" && CaptureMode.forTopology(mode) == CaptureMode.Auto) {
    SettingsCell(
        stringResource(R.string.record_cell_locked),
        stringResource(
            if (orientationLockRotation in listOf(1, 3)) R.string.record_locked_landscape
            else R.string.record_locked_portrait
        ),
        compact = compact,
    )
}
```

(Match `SettingsCell`'s actual parameter order — key/value vs value/key — to the existing five cells. The cell is quiet by construction; spec §5 acceptance #3.)

Plumb the two params from `RecordScreen` (collected from the ViewModel flows added in Task 5).

- [ ] **Step 5: Controller batch build** — `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`. Expected: full suite GREEN. (This is the batch where Task 6's string deletes become safe.)

- [ ] **Step 6: Commit**

```bash
git add -A app/src/main/java
git commit -m "feat(gamma): registry-driven mode picker + quiet-Auto accent rule + LOCKED cell + ORIENTATION row (spec S4/S5/S6)"
```

---### Task 8: Four `check*` gates

**Files:**
- Modify: `app/build.gradle.kts` (append after `checkPresetNoOrientation` ~line 1817; wire into the existing `preBuild` dependsOn block)

- [ ] **Step 1: Add the gate tasks**

```kotlin
// ADR-0029 PR-γ gate 1 — live capture paths must not branch on the legacy
// orientation-carrying mode strings; read-compat sites are allowlisted (§6).
val checkNoLegacyModeStrings = tasks.register("checkNoLegacyModeStrings") {
    group = "verification"
    description = "Forbid \"Portrait\"/\"Landscape\"/\"PortraitLandscape\" string literals outside legacy read-compat (ADR-0029 PR-γ §6)."
    val allow = setOf(
        "data/SessionManifest.kt",   // legacy "mode" JSON read-tolerance
        "data/ModeMigration.kt",     // the migration mapper itself
        "data/RovaSettings.kt",      // one-shot prefs migration
    )
    doLast {
        val offenders = mutableListOf<String>()
        fileTree("src/main/java") { include("**/*.kt") }.forEach { f ->
            val rel = f.path.replace('\\', '/').substringAfter("com/aritr/rova/")
            if (allow.any { rel.endsWith(it) }) return@forEach
            f.readLines().forEachIndexed { i, line ->
                if (Regex("\"(Portrait|Landscape|PortraitLandscape)\"").containsMatchIn(line)) {
                    offenders += "$rel:${i + 1}: $line"
                }
            }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "ADR-0029 PR-γ: legacy mode strings in live paths (use CaptureTopology):\n" +
                    offenders.joinToString("\n")
            )
        }
    }
}

// ADR-0029 PR-γ gate 2 — rotation applies only at segment boundaries (§3):
// setTargetRotation is reachable only from the allowlisted capture files.
val checkSetTargetRotationBoundaryOnly = tasks.register("checkSetTargetRotationBoundaryOnly") {
    group = "verification"
    description = "setTargetRotation only in RovaRecordingService/dualrecord (ADR-0029 §3 segment-boundary rule)."
    doLast {
        val offenders = mutableListOf<String>()
        fileTree("src/main/java") { include("**/*.kt") }.forEach { f ->
            val rel = f.path.replace('\\', '/').substringAfter("com/aritr/rova/")
            val allowed = rel.endsWith("service/RovaRecordingService.kt") || rel.contains("service/dualrecord/")
            if (allowed) return@forEach
            f.readLines().forEachIndexed { i, line ->
                if (line.contains("setTargetRotation(")) offenders += "$rel:${i + 1}"
            }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException("ADR-0029 §3: setTargetRotation outside boundary-owning files:\n" + offenders.joinToString("\n"))
        }
    }
}

// ADR-0029 PR-γ gate 3 — FrontBack construction is capability-gated (§5):
// the topology may be referenced only by its declaration and the registry
// that owns the capability gate. Future PR-δ extends the allowlist with the
// concurrent-camera module it builds.
val checkFrontBackCapabilityGated = tasks.register("checkFrontBackCapabilityGated") {
    group = "verification"
    description = "\"FrontBack\" referenced only in CaptureTopology/CaptureModes (capability gate site) (ADR-0029 §5)."
    val allow = setOf("data/CaptureTopology.kt", "ui/screens/CaptureModes.kt")
    doLast {
        val offenders = mutableListOf<String>()
        fileTree("src/main/java") { include("**/*.kt") }.forEach { f ->
            val rel = f.path.replace('\\', '/').substringAfter("com/aritr/rova/")
            if (allow.any { rel.endsWith(it) }) return@forEach
            f.readLines().forEachIndexed { i, line ->
                if (line.contains("FrontBack")) offenders += "$rel:${i + 1}"
            }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException("ADR-0029 §5: FrontBack outside the capability-gated registry:\n" + offenders.joinToString("\n"))
        }
    }
}

// Spec 2026-06-11 §7 / ADR-0029 §C — user-facing copy speaks clip/session only.
val checkUserCopyVocabulary = tasks.register("checkUserCopyVocabulary") {
    group = "verification"
    description = "No loop/repeat/segment vocabulary in user-visible string VALUES, en+es (ADR-0029 §C terminology)."
    val banned = Regex("(?i)\\b(loops?|repeats?|segments?|ciclos?|segmentos?|repeticion(es)?)\\b")
    // Allowlist by resource NAME for justified exceptions (none expected at γ).
    val allowNames = setOf<String>()
    doLast {
        val offenders = mutableListOf<String>()
        listOf("src/main/res/values/strings.xml", "src/main/res/values-es/strings.xml").forEach { p ->
            val nameRe = Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""")
            nameRe.findAll(file(p).readText()).forEach { m ->
                val (name, value) = m.destructured
                if (name in allowNames) return@forEach
                if (banned.containsMatchIn(value)) offenders += "$p: $name = $value"
            }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException("ADR-0029 §C: banned vocabulary in user copy (use clip/session):\n" + offenders.joinToString("\n"))
        }
    }
}
```

Wire all four into the existing preBuild block (find `tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(` and append the four registrations to its list).

Note: gate 4's single-line `<string>` regex matches the file's existing one-string-per-line style; if a multiline string value exists, the implementer extends the regex with `RegexOption.DOT_MATCHES_ALL` on a per-element scan — verify by counting `<string ` occurrences vs matches and fail the gate build if they differ:

```kotlin
val declared = Regex("<string ").findAll(file(p).readText()).count()
val matched = nameRe.findAll(file(p).readText()).count()
if (declared != matched) throw GradleException("checkUserCopyVocabulary: parser missed ${declared - matched} strings in $p — fix the regex")
```

- [ ] **Step 2: Controller runs the gates** — `./gradlew :app:checkNoLegacyModeStrings :app:checkSetTargetRotationBoundaryOnly :app:checkFrontBackCapabilityGated :app:checkUserCopyVocabulary`
Expected: all GREEN. If gate 1 flags stragglers (e.g. a missed `"PortraitLandscape"` literal), fix the source per Task 4's substitution — never widen the allowlist for a live path.

- [ ] **Step 3: Commit**

```bash
git add app/build.gradle.kts
git commit -m "feat(gamma): 4 preBuild gates — legacy-mode strings, rotation boundary, FrontBack capability, user-copy vocabulary (ADR-0029)"
```

---

### Task 9: Docs — ADR-0029 promotion + §C terminology clause

**Files:**
- Modify: `docs/adr/0029-capture-topology-orientation-policy.md`

- [ ] **Step 1: Promote + amend**

- Header status: **Proposed** → **Accepted** (the ADR itself says "promoted to Accepted when PR-γ lands").
- In the gates section (~line 309), convert the three "candidate" sketches to "**Built (PR-γ):** `checkNoLegacyModeStrings`, `checkSetTargetRotationBoundaryOnly`, `checkFrontBackCapabilityGated`".
- Append a new section:

```markdown
## §C — User-copy vocabulary (2026-06-11, owner-ratified; spec 2026-06-11-capture-mode-orientation-ux-design.md)

User-facing copy speaks exactly two nouns: **session** (one Start→Stop run) and
**clip** (one video within it). "Loop", "repeat", "segment" are banned from
string VALUES in `values*/strings.xml` (internal code vocabulary unaffected —
`loopCount`, `Segment*` classes, segment file naming, and the gates that pin
them are exempt by construction). Mode names are capture strategies
(Auto / DualShot / DualSight), never orientations; the orientation policy
displays "Follow Device", never "Auto". Enforced by `checkUserCopyVocabulary`.
```

- [ ] **Step 2: Commit**

```bash
git add docs/adr/0029-capture-topology-orientation-policy.md
git commit -m "docs(adr-0029): promote to Accepted; record built PR-gamma gates + SC vocabulary clause"
```

---

### Task 10: Full verification + device smoke (controller-run)

**Files:** none (verification).

- [ ] **Step 1: Clean build environment** (broken-hook recovery ritual)

```powershell
./gradlew --stop
Get-Process java -ErrorAction SilentlyContinue | Stop-Process -Force
```

- [ ] **Step 2: Full suite + APK**

```powershell
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Expected: all tests GREEN (baseline grows by the new test files; `RecordModeCycleTest` deleted), all 35 gates GREEN, APK built. `lintDebug` optional (known pre-existing B5 `VaultAndroidOps` NewApi RED — use assembleDebug as the gate runner).

- [ ] **Step 3: Owner device-smoke matrix** (install `app-debug.apk` on RZCYA1VBQ2H)

Migration cases (set the legacy state BEFORE installing the new build, via the old APK):
1. Old build in **Portrait** mode → upgrade → expect: chip "Auto" (quiet), `LOCKED · Portrait` cell visible, recording produces portrait MP4 regardless of device rotation; Settings → Orientation shows Lock Portrait active.
2. Old build in **Landscape** mode → upgrade → `LOCKED · Landscape`, landscape MP4.
3. Old build in **P + L** → upgrade → chip "DualShot" (accented), dual outputs as before.
4. Fresh install → chip "Auto" (quiet), no LOCKED cell, rotation-follows-device MP4s (both senses).

Behavior cases:
5. Cycle chip: Auto ↔ DualShot only (no DualSight on A17 — unsupported hardware).
6. Sheet: CAPTURE MODE tabs Auto|DualShot + caption line switches per tab; ORIENTATION row picks persist across app restart; row inert while DualShot active.
7. Lock Landscape picked while holding the device in 270° landscape → MP4 plays back in that landscape sense (Ratified-D capture-current behavior).
8. Terminology sweep: HUD says "CLIPS DONE", config cell "CLIPS", notification "Clip saved", History "Clips"; Spanish locale spot-check ("CLIPS HECHOS").
9. Landscape chrome regression: rotated cluster + side sheet still correct in both senses (no regression from the merged β branch).

- [ ] **Step 4: Final review + finish**

Dispatch final code-reviewer subagent over the whole branch diff, then use superpowers:finishing-a-development-branch (expect option 2: push + PR titled "PR-γ: capture topology + orientation policy + clip/session UX (ADR-0029)").

---

## Self-review notes (done at write time)

- **Spec coverage:** §3 modes → T5/T7; §4 orientation → T1/T2/T4/T7; §5 card → T7; §6 sheet → T7; §7 terminology → T6 + gate 4 (T8); §8 migration → T1/T2/T3 + smoke 1–4; §10 acceptance 1–8 → smoke matrix + gates. DualSight = registry-only (spec out-of-scope fence) → `DUALSIGHT_ENABLED = false` + gate 3.
- **Type consistency:** persisted strings table at top is the single contract; `CaptureMode.topology : String` everywhere in UI; enum `CaptureTopology` used for validation only (`isValidPersisted`/`fromPersisted`) — no enum plumbed through binder/flows.
- **Known judgment points for implementers:** exact PR-α sample-site shape (T4 S2), `SettingsCell` param order (T7 S4), `mode`→`topology` flow rename breadth (T5 S4) — all marked inline.

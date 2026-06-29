# DualShot AE frame-rate floor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Lift the DualShot auto-exposure frame-rate ceiling by setting a capability-gated `CONTROL_AE_TARGET_FPS_RANGE` floor of `[24,30]`, so dim-light camera cadence stops collapsing toward ~15fps.

**Architecture:** A pure `AeFpsRangePolicy` helper selects the best supported range from the device's discrete `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES`; `RovaRecordingService.setupDualCamera` reads the back-camera characteristics (CameraX-native, pre-bind), runs the helper, and applies the chosen range frame-0 via `Camera2Interop.Extender` on the `Preview.Builder` — all best-effort so the camera always opens. A new 47th static-check gate (`checkAeFpsRangeCapabilityGated`, ADR-0034) forbids a hard-coded range literal.

**Tech Stack:** Kotlin, CameraX 1.4.2 (`Camera2Interop`, `Camera2CameraInfo`), `android.hardware.camera2` (`CameraCharacteristics`/`CaptureRequest`), JUnit4 (`org.junit.Assert`), the `build-logic` `SourceCheckTask`/`RovaGateRules` gate framework.

## Global Constraints

- DualShot binding only (`setupDualCamera`). Single-camera and FrontBack paths untouched.
- **Capability-gated:** never request a range not in the device's `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES`. Helper returns `null` → set nothing (keep device default).
- **Fail-open:** the read+choose+apply block is wrapped in try/catch; on any failure or `null`, the binding proceeds unchanged. The camera must always open.
- Floor = `24`, ceiling = `30` (the asymmetric `[24,30]` policy).
- **Camera-identity guard:** apply only if `currentCameraSelector.filter(provider.availableCameraInfos)` resolves to **exactly one** `CameraInfo`; else skip.
- Pure helper (`AeFpsRangePolicy`) is framework-free (Int `Pair`s; `android.util.Range` wrapped only at the service edge — D-deviation) and JVM-unit-tested. Baseline 1241 tests / 0-0-0; new tests land in the same PR.
- `@OptIn(ExperimentalCamera2Interop)` on the new interop call sites (consistent with the existing `attachAeMetadataProbe`).
- New 47th gate `checkAeFpsRangeCapabilityGated` enforcing ADR-0034; gate count 46 → 47. Do not edit/remove any existing gate.
- ADR-0009 crop geometry, the #25–#35 fence-sync stability stack, the DualMuxer, and all 46 existing gates untouched.
- Verify via `:app:testDebugUnitTest` + `:app:assembleDebug` (fires all 47 gates on preBuild), **not** `:app:lintDebug` (pre-existing `VaultAndroidOps:267` NewApi RED is unrelated). Build WARM.
- Success criterion is **camera-side** cadence (effective AE range + `cameraHW` fps via the DEBUG probe), not merged-file output fps (still encoder-gated ~22fps until Limiter 2).
- Device: RZCYA1VBQ2H. No PR/merge without explicit owner GO; never push master directly.

---

### Task 1: `AeFpsRangePolicy` pure helper

**Files:**
- Create: `app/src/main/java/com/aritr/rova/service/dualrecord/internal/AeFpsRangePolicy.kt`
- Test: `app/src/test/java/com/aritr/rova/service/dualrecord/internal/AeFpsRangePolicyTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `AeFpsRangePolicy.choose(available: List<Pair<Int, Int>>, floor: Int, ceiling: Int): Pair<Int, Int>?` — each `Pair` is `(lower, upper)`; returns the chosen `(lower, upper)` or `null` (don't set).

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.aritr.rova.service.dualrecord.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AeFpsRangePolicyTest {

    private fun choose(vararg ranges: Pair<Int, Int>) =
        AeFpsRangePolicy.choose(ranges.toList(), floor = 24, ceiling = 30)

    @Test fun prefers24to30_lowestLowerWithCeilingUpper() {
        // pass 1: upper==30 && lower>=24 → {[24,30],[30,30]} → lowest lower
        assertEquals(24 to 30, choose(15 to 15, 15 to 30, 24 to 30, 30 to 30))
    }

    @Test fun fallsBackTo30to30_whenNo24to30() {
        // [15,30] excluded (lower 15 < 24); only [30,30] qualifies in pass 1
        assertEquals(30 to 30, choose(15 to 30, 30 to 30))
    }

    @Test fun pass2_picksHighestUpperThenLowestLower_whenNoCeilingUpper() {
        // no upper==30 → pass 2: lower>=24 && upper<=30 → [24,24]
        assertEquals(24 to 24, choose(15 to 24, 24 to 24))
    }

    @Test fun nullWhenNoFloorRange() {
        // no lower>=24 anywhere → don't set
        assertNull(choose(7 to 30, 15 to 30))
    }

    @Test fun nullWhenEmpty() {
        assertNull(choose())
    }

    @Test fun nullWhenOnlyHighCeilings() {
        // no upper<=30 with lower>=24 ([30,60],[60,60] exceed ceiling; [15,15] below floor)
        assertNull(choose(15 to 15, 30 to 60, 60 to 60))
    }

    @Test fun duplicatesAreStable() {
        assertEquals(24 to 30, choose(24 to 30, 24 to 30))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.service.dualrecord.internal.AeFpsRangePolicyTest"`
Expected: FAIL — `AeFpsRangePolicy` unresolved reference.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.aritr.rova.service.dualrecord.internal

/**
 * DualShot AE frame-rate floor (2026-06-29, ADR-0034) — pure selection over the
 * device's discrete CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES. Capability-gated:
 * returns a SUPPORTED range to request, or null to leave the AE default in place
 * (never request an unlisted range — that can fail camera-open). Framework-free
 * (Int Pairs; android.util.Range is wrapped only at the RovaRecordingService
 * edge — D-deviation) so it is unit-tested directly (AeFpsRangePolicyTest). See
 * the 2026-06-29 AE-floor design spec §4.
 */
internal object AeFpsRangePolicy {

    /**
     * Choose the AE target fps range from [available] (each `(lower, upper)`),
     * honoring [floor] (min acceptable lower) and [ceiling] (preferred upper).
     *  - Pass 1: `upper == ceiling && lower >= floor` → lowest `lower` (max
     *    low-light headroom; prefers [24,30] over [30,30]).
     *  - Pass 2 (pass 1 empty): `lower >= floor && upper <= ceiling` → highest
     *    `upper`, then lowest `lower` (e.g. [24,24]).
     *  - else: null — do not set the option; keep the device default.
     */
    fun choose(available: List<Pair<Int, Int>>, floor: Int, ceiling: Int): Pair<Int, Int>? {
        available.filter { it.second == ceiling && it.first >= floor }
            .minByOrNull { it.first }
            ?.let { return it }
        return available.filter { it.first >= floor && it.second <= ceiling }
            .sortedWith(compareByDescending<Pair<Int, Int>> { it.second }.thenBy { it.first })
            .firstOrNull()
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.service.dualrecord.internal.AeFpsRangePolicyTest"`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/service/dualrecord/internal/AeFpsRangePolicy.kt app/src/test/java/com/aritr/rova/service/dualrecord/internal/AeFpsRangePolicyTest.kt
git commit -m "feat(dualshot-ae): AeFpsRangePolicy capability-gated selection + tests"
```

---

### Task 2: Wire the capability-gated AE floor into `setupDualCamera`

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt` (the DualShot preview-build block — locate by content; see anchors below)

Framework layer — no unit test (the pure logic is Task 1; this is the thin CameraX seam). Verified by `:app:assembleDebug` + on-device.

**Interfaces:**
- Consumes: `AeFpsRangePolicy.choose` (Task 1); `ProcessCameraProvider`, `currentCameraSelector` (existing field), `RovaLog` (existing).
- Produces: a private `applyAeFpsFloor(provider, builder)` method; a call to it in `setupDualCamera`. No new public surface.

- [ ] **Step 1: Locate the anchors**

In `setupDualCamera`, find the DualShot preview-build block. It currently reads (the `attachAeMetadataProbe` block was added by the diagnosis branch):

```kotlin
            val previewBuilder = Preview.Builder()
                .setResolutionSelector(resolutionSelector)
                .setTargetRotation(android.view.Surface.ROTATION_0)
            // fps-cadence diagnosis (2026-06-29) — best-effort AE metadata. ...
            if (BuildConfig.DEBUG) {
                try {
                    attachAeMetadataProbe(previewBuilder)
                } catch (t: Throwable) {
                    RovaLog.w("DualShot AE probe attach failed (non-fatal)", t)
                }
            }
            preview = previewBuilder.build()
```

`provider` is the local `ProcessCameraProvider` already in scope in `setupDualCamera` (`val provider = cameraProvider ?: return@withLock`). `currentCameraSelector` is the existing service field (`DEFAULT_BACK_CAMERA`).

- [ ] **Step 2: Insert the AE-floor call**

Insert immediately **after** the `previewBuilder` is created (after the `.setTargetRotation(android.view.Surface.ROTATION_0)` line) and **before** the `if (BuildConfig.DEBUG)` probe block, so the probe reads the range we just set:

```kotlin
            // ADR-0034 — capability-gated AE fps floor [24,30]. Lifts the
            // auto-exposure ceiling so dim-light capture cadence stops
            // collapsing toward 15fps. Best-effort; never blocks the binding.
            applyAeFpsFloor(provider, previewBuilder)
```

- [ ] **Step 3: Add the private method**

Add near `attachAeMetadataProbe` (private helpers around `setupDualCamera`):

```kotlin
    /**
     * DualShot AE frame-rate floor (2026-06-29, ADR-0034) — read the bound
     * back-camera's CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES, pick the best
     * supported range >=24fps via [AeFpsRangePolicy], and request it frame-0 on
     * the DualShot Preview. Capability-gated + fail-open: an unlisted range, a
     * multi-camera selector match, a null pick, or any exception leaves the AE
     * default in place — the camera must always open. The range is built on its
     * own line and passed by reference (checkAeFpsRangeCapabilityGated forbids a
     * hard-coded Range literal at the setCaptureRequestOption call).
     */
    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun applyAeFpsFloor(
        provider: androidx.camera.lifecycle.ProcessCameraProvider,
        builder: androidx.camera.core.Preview.Builder,
    ) {
        try {
            val matches = currentCameraSelector.filter(provider.availableCameraInfos)
            if (matches.size != 1) {
                RovaLog.d { "AE floor: selector resolved ${matches.size} cameras (need 1), skipping" }
                return
            }
            val available = androidx.camera.camera2.interop.Camera2CameraInfo.from(matches.first())
                .getCameraCharacteristic(
                    android.hardware.camera2.CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
                )
                ?.map { it.lower to it.upper }
                ?: emptyList()
            val chosen = AeFpsRangePolicy.choose(available, floor = 24, ceiling = 30)
            if (chosen == null) {
                RovaLog.d { "AE floor: no supported range >=24fps, keeping device default" }
                return
            }
            val aeRange = android.util.Range(chosen.first, chosen.second)
            androidx.camera.camera2.interop.Camera2Interop.Extender(builder)
                .setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    aeRange,
                )
            RovaLog.d { "AE floor: requested CONTROL_AE_TARGET_FPS_RANGE=$aeRange" }
        } catch (t: Throwable) {
            RovaLog.w("AE floor: apply failed (non-fatal), keeping device default", t)
        }
    }
```

Note: if `androidx.camera.lifecycle.ProcessCameraProvider` is already imported in the file, the parameter type may be written as `ProcessCameraProvider` to match local style; the FQN form above is safe regardless.

- [ ] **Step 4: Build to verify it compiles + existing gates pass**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL; the 46 existing gates pass on preBuild (notably `checkSetTargetRotationBoundaryOnly` — no new `setTargetRotation`; `checkPresetNoOrientation` — unaffected). `androidx.camera.camera2.interop` resolves (already used by `attachAeMetadataProbe`).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt
git commit -m "feat(dualshot-ae): wire capability-gated AE fps floor into setupDualCamera"
```

---

### Task 3: ADR-0034 + `checkAeFpsRangeCapabilityGated` gate (47th)

**Files:**
- Create: `docs/adr/0034-dualshot-ae-fps-floor.md`
- Modify: `build-logic/src/main/kotlin/com/aritr/rova/gradle/RovaGateRules_ModeRotation.kt` (add rule)
- Modify: `build-logic/src/main/kotlin/com/aritr/rova/gradle/RovaGateRules.kt` (register rule)
- Modify: `build-logic/src/test/kotlin/com/aritr/rova/gradle/ModeRotationRulesTest.kt` (golden test)
- Modify: `build-logic/src/test/kotlin/com/aritr/rova/gradle/RegistryTest.kt` (EXPECTED_IDS 46 → 47)
- Modify: `app/build.gradle.kts` (register `SourceCheckTask` + `preBuild` dependsOn)
- Modify: `CLAUDE.md` (gate count 46 → 47, ADR count 33 → 34)

**Interfaces:**
- Consumes: the `SourceFile`/`RovaGateRules`/`SourceCheckTask` framework.
- Produces: gate id `checkAeFpsRangeCapabilityGated` enforcing that, in `RovaRecordingService.kt`, no single line both references `CONTROL_AE_TARGET_FPS_RANGE` and constructs a `Range(` literal.

- [ ] **Step 1: Write the ADR**

Create `docs/adr/0034-dualshot-ae-fps-floor.md`:

```markdown
# ADR-0034: DualShot AE frame-rate floor (capability-gated)

**Status:** Accepted (2026-06-29)

## Context

The fps-cadence diagnosis (PR #154) proved the DualShot ~18–20fps plateau is
compound. Limiter 1 is auto-exposure: the DualShot binding sets no
`CONTROL_AE_TARGET_FPS_RANGE`, so in dim light AE stretches exposure to ~60ms
and sensor cadence collapses to ~16.7fps. A bright-light intervention dropped
exposure to 3.27ms and lifted cadence to 30fps, confirming the cause.

## Decision

The DualShot binding requests a capability-gated AE target fps **floor of
`[24,30]`** (asymmetric — forbids the ~16fps worst case while letting AE relax
to 24fps in dim light for brightness). The range is selected from the device's
`CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES` by the pure `AeFpsRangePolicy`; if no
range with a floor ≥24 is supported, the option is **not** set (the device
default stays). The camera must always open — the apply path is fail-open.

This addresses Limiter 1 only. The encoder ceiling (Limiter 2, ~22fps output)
is a separate later cycle.

## Clause (enforced by `checkAeFpsRangeCapabilityGated`)

The AE target fps range MUST be capability-gated — chosen from the device's
available ranges via `AeFpsRangePolicy`, never a hard-coded literal. An
unconditional `setCaptureRequestOption(CONTROL_AE_TARGET_FPS_RANGE, Range(x,y))`
is a camera-open footgun on devices that do not list `[x,y]`. The gate forbids a
`Range(` literal on the same line as `CONTROL_AE_TARGET_FPS_RANGE` in
`RovaRecordingService.kt`.

## Scope

DualShot binding only. Single-camera and FrontBack paths are out of scope.
```

- [ ] **Step 2: Write the failing golden test** (append to `ModeRotationRulesTest.kt`)

```kotlin
    // ── checkAeFpsRangeCapabilityGated (ADR-0034) ──────────────────────────

    private fun svc(text: String) = listOf(
        SourceFile("app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt",
            text.split("\n"), text)
    )

    @Test fun aeFpsRange_passesWhenRangeBuiltOnSeparateLineAndPassedByRef() {
        val ok = """
            val aeRange = android.util.Range(chosen.first, chosen.second)
            ext.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                aeRange,
            )
        """.trimIndent()
        assertNull(ruleAeFpsRangeCapabilityGated(svc(ok)))
    }

    @Test fun aeFpsRange_firesOnInlineRangeLiteral() {
        val bad = "ext.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(24, 30))"
        val msg = ruleAeFpsRangeCapabilityGated(svc(bad))
        assertNotNull(msg)
        assertTrue(msg!!.contains("ADR-0034"))
    }

    @Test fun aeFpsRange_ignoresAvailableRangesCharacteristicLine() {
        // the AVAILABLE list key must NOT trigger (different token), even though
        // the .map { it.lower to it.upper } line is nearby.
        val ok = "val a = info.getCameraCharacteristic(CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)"
        assertNull(ruleAeFpsRangeCapabilityGated(svc(ok)))
    }

    @Test fun aeFpsRange_nullWhenServiceFileAbsent() {
        assertNull(ruleAeFpsRangeCapabilityGated(emptyList()))
    }
```

Ensure the test file imports `assertNotNull` / `assertTrue` (`import org.junit.Assert.assertNotNull`, `import org.junit.Assert.assertTrue`) — add if missing.

- [ ] **Step 3: Run the golden test to verify it fails**

Run: `./gradlew :build-logic:test --tests "com.aritr.rova.gradle.ModeRotationRulesTest"`
Expected: FAIL — `ruleAeFpsRangeCapabilityGated` unresolved reference.

- [ ] **Step 4: Add the rule** (append to `RovaGateRules_ModeRotation.kt`)

```kotlin
// ─── checkAeFpsRangeCapabilityGated ───────────────────────────────────────────

/**
 * ADR-0034 — the DualShot AE target fps range must be capability-gated via
 * AeFpsRangePolicy, never a hard-coded literal. Scope: RovaRecordingService.kt.
 * Forbid any single line that both references the request key
 * `CONTROL_AE_TARGET_FPS_RANGE` AND constructs a `Range(` literal — that is the
 * camera-open footgun. The legitimate form builds `android.util.Range(...)` on
 * its own line and passes it by reference to setCaptureRequestOption.
 * Detection on strippedLines (comment-aware); report from the raw line.
 * The AVAILABLE-list key (CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) does not
 * contain the substring `CONTROL_AE_TARGET_FPS_RANGE`, so it never triggers.
 * Empty input / file absent: null (forbid gate, nothing to forbid).
 */
internal fun ruleAeFpsRangeCapabilityGated(files: List<SourceFile>): String? {
    val suffix = "service/RovaRecordingService.kt"
    val src = files.firstOrNull { it.relPath.replace('\\', '/').endsWith(suffix) } ?: return null
    val offenders = mutableListOf<String>()
    src.lines.forEachIndexed { i, line ->
        val s = src.strippedLine(i)
        if (s.contains("CONTROL_AE_TARGET_FPS_RANGE") && s.contains("Range(")) {
            offenders += "$suffix:${i + 1}: ${line.trim()}"
        }
    }
    if (offenders.isNotEmpty()) {
        return "ADR-0034: AE target fps range must be capability-gated via AeFpsRangePolicy, " +
            "not a hard-coded Range(...) literal:\n" + offenders.joinToString("\n")
    }
    return null
}
```

- [ ] **Step 5: Register the rule** (in `RovaGateRules.kt`, inside the `buildMap`, after `put("checkFrontBackCapabilityGated", ::ruleFrontBackCapabilityGated)`)

```kotlin
        put("checkAeFpsRangeCapabilityGated", ::ruleAeFpsRangeCapabilityGated)
```

- [ ] **Step 6: Run the golden test to verify it passes**

Run: `./gradlew :build-logic:test --tests "com.aritr.rova.gradle.ModeRotationRulesTest"`
Expected: PASS (existing cases + 4 new). The `firesOnInlineRangeLiteral` case proves the gate RED-fires.

- [ ] **Step 7: Add the id to `RegistryTest` EXPECTED_IDS**

In `build-logic/src/test/kotlin/com/aritr/rova/gradle/RegistryTest.kt`: change the two comments `46` → `47` (the KDoc "EXACTLY the 46 gate ids" and the `// All 46 gate ids` line), and insert into the `sortedSetOf(...)` (alphabetical position, before `checkAtomicTerminalWriteForbiddenPair`):

```kotlin
            "checkAeFpsRangeCapabilityGated",
```

- [ ] **Step 8: Run the registry test**

Run: `./gradlew :build-logic:test --tests "com.aritr.rova.gradle.RegistryTest"`
Expected: PASS (registry now holds exactly 47 ids).

- [ ] **Step 9: Register the gradle task + preBuild wiring** (in `app/build.gradle.kts`)

Add the task registration next to the other Mode/Rotation gates (e.g. after the `checkFrontBackCapabilityGated` registration block):

```kotlin
val checkAeFpsRangeCapabilityGated = tasks.register<com.aritr.rova.gradle.SourceCheckTask>("checkAeFpsRangeCapabilityGated") {
    group = "verification"
    description = "DualShot AE target fps range must be capability-gated via AeFpsRangePolicy, never a hard-coded Range literal (ADR-0034)."
    sources.from(
        layout.projectDirectory.file("src/main/java/com/aritr/rova/service/RovaRecordingService.kt")
    )
    checkId.set("checkAeFpsRangeCapabilityGated")
    reportBaseDir.set(rootProject.layout.projectDirectory)
    sentinel.set(layout.buildDirectory.file("reports/rova-checks/checkAeFpsRangeCapabilityGated.ok"))
}
```

Add to the `pluginManager.withPlugin("com.android.application")` `preBuild` block (next to `dependsOn(checkFrontBackCapabilityGated)` if present, else anywhere in the block):

```kotlin
        dependsOn(checkAeFpsRangeCapabilityGated)
```

- [ ] **Step 10: Update `CLAUDE.md` doc counts**

In `CLAUDE.md`: change "**46 custom `check*` tasks**" → "**47 custom `check*` tasks**", append `checkAeFpsRangeCapabilityGated` to the inline gate-name list, and update the ADR count to reflect ADR-0034 (the "**31 ADRs**"/"0001–0031" phrasing is already stale vs 0032/0033 — extend the range note to 0034 where the doc enumerates ADRs). Do not alter gate mechanics prose.

- [ ] **Step 11: Build to verify the new gate runs GREEN on real code**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL; **47** `check*` tasks run on preBuild; `checkAeFpsRangeCapabilityGated` passes against the Task-2 code (the range is built on its own line and passed by reference, so no co-presence).

- [ ] **Step 12: Commit**

```bash
git add docs/adr/0034-dualshot-ae-fps-floor.md build-logic/src/main/kotlin/com/aritr/rova/gradle/RovaGateRules_ModeRotation.kt build-logic/src/main/kotlin/com/aritr/rova/gradle/RovaGateRules.kt build-logic/src/test/kotlin/com/aritr/rova/gradle/ModeRotationRulesTest.kt build-logic/src/test/kotlin/com/aritr/rova/gradle/RegistryTest.kt app/build.gradle.kts CLAUDE.md
git commit -m "feat(dualshot-ae): ADR-0034 + checkAeFpsRangeCapabilityGated gate (47th)"
```

---

### Task 4: On-device verification (RZCYA1VBQ2H)

Not a code task — the slice's behavioral proof. No commit unless the findings doc is updated.

- [ ] **Step 1: Build + install**

Run: `./gradlew :app:assembleDebug` then `adb install -r app/build/outputs/apk/debug/app-debug.apk`
Expected: BUILD SUCCESSFUL; `Success`.

- [ ] **Step 2: Dim DualShot run**

Record a DualShot session (SD, 2×10s) in a dim/indoor scene. Capture logcat:

```powershell
adb logcat -d -v time | Select-String "DualShot AE","EglRouter cadence"
```

Expected: `DualShot AE` shows effective `aeTargetFpsRange=[24, 30]` (or the device's best supported range from the policy) — **not** `[15, 30]`; `EglRouter cadence` `cameraHW` median ≈ ≤41.7ms (~24fps) vs the pre-fix 60ms (~16.7fps). Camera opened cleanly (no session-config error in logcat; preview visible).

- [ ] **Step 3: Bright run (regression)**

Record a bright-scene session. Expected: still ~30fps (the floor does not lower the ceiling).

- [ ] **Step 4: Record results**

Append a short "AE floor — device verification" section to `docs/superpowers/specs/2026-06-29-dualshot-fps-cadence-findings.md` (effective range, dim `cameraHW` fps before/after, bright unchanged, clean camera-open). Restore prefs via the UI if changed.

```bash
git add docs/superpowers/specs/2026-06-29-dualshot-fps-cadence-findings.md
git commit -m "docs(dualshot-ae): on-device verification of the AE fps floor"
```

---

## Self-review notes

- **Spec coverage:** §3 wiring → Task 2; §3 identity guard → Task 2 Step 3 (`matches.size != 1`); §3.1 single-stream + probe confirmation → Task 4 Step 2 (effective range read by the existing probe); §4 helper + §4.1 matrix → Task 1; §5 tradeoff/success-criterion → Task 4; §6 gate → Task 3; §6 ADR-0034 → Task 3 Step 1; §7 fail-open → Task 2 try/catch + null path; §8 verification → Task 4; §9 out-of-scope → no encoder/adaptive/single-camera task exists (correct).
- **Type consistency:** `AeFpsRangePolicy.choose(List<Pair<Int,Int>>, Int, Int): Pair<Int,Int>?` defined Task 1, consumed Task 2 (`.map { it.lower to it.upper }` → `Pair`s; result `.first`/`.second` → `android.util.Range`). Gate id `checkAeFpsRangeCapabilityGated` identical across RovaGateRules / RegistryTest / app/build.gradle.kts / CLAUDE.md.
- **Gate RED-proof:** Task 3 Step 2/3 write the golden test FIRST (fails to compile → then RED on the literal case), Step 6 GREEN — proves the gate fires on a real violation before it guards real code.
- **No placeholders:** every code/edit step shows full code; every run step has an exact command + expected result.

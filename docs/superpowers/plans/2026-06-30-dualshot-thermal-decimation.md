# DualShot Thermal-Adaptive Encode Decimation — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When the device is hot (`ThermalStatus.SEVERE`), encode only every 2nd DualShot frame so sessions last longer before the `CRITICAL` thermal autostop — preview stays full-rate, resolution unchanged.

**Architecture:** A pure policy (`ThermalDecimationPolicy`) maps the already-hysteresis'd thermal status to a decimation factor and decides per-frame submission. `EglRouter` reads a `@Volatile` factor and skips the encoder-feed block on decimated frames (preview render is untouched). `RovaRecordingService.checkSegmentGates` — which already reads the stable thermal status each segment boundary — sets the factor through a `DualVideoRecorder` → `DualSurfaceProcessor` → `EglRouter` passthrough.

**Tech Stack:** Kotlin, CameraX 1.5.3, EGL14/GLES30, JUnit (JVM unit tests).

## Global Constraints

- Verify with `./gradlew.bat :app:assembleDebug` (compiles + fires all custom `check*` gates on preBuild) and `./gradlew.bat :app:testDebugUnitTest`. **NOT** `:app:lintDebug` (pre-existing unrelated `VaultAndroidOps` NewApi RED).
- Windows: subagents EDIT-ONLY; the controller runs all gradle/git.
- `ThermalStatus` ordinals: `NONE=0 LIGHT=1 MODERATE=2 SEVERE=3 CRITICAL=4 EMERGENCY=5 SHUTDOWN=6`.
- `factor == 1` MUST be byte-identical to current behavior (every frame encoded); default `1`; only DualShot ever sets it.
- Decimation applies ONLY to the encoder-feed path. **Preview render stays full-rate** (load-bearing invariant).
- No new UI strings/glyphs (rides under the existing `SEVERE` HUD banner).
- Branch: `perf/dualshot-thermal-decimation` (already created, stacked on δ0 / CameraX 1.5.3).

---

### Task 1: ThermalDecimationPolicy (pure helper + tests)

**Files:**
- Create: `app/src/main/java/com/aritr/rova/service/ThermalDecimationPolicy.kt`
- Test: `app/src/test/java/com/aritr/rova/service/ThermalDecimationPolicyTest.kt`

**Interfaces:**
- Consumes: `com.aritr.rova.ui.signals.ThermalStatus`
- Produces:
  - `ThermalDecimationPolicy.decimationFactor(status: ThermalStatus): Int` — `2` when `status.ordinal >= SEVERE.ordinal`, else `1`.
  - `ThermalDecimationPolicy.shouldSubmit(frameCounter: Int, factor: Int): Boolean` — `true` when `factor <= 1 || frameCounter % factor == 0`.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/aritr/rova/service/ThermalDecimationPolicyTest.kt`:
```kotlin
package com.aritr.rova.service

import com.aritr.rova.ui.signals.ThermalStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalDecimationPolicyTest {
    @Test fun factor_isOne_belowSevere() {
        for (s in listOf(ThermalStatus.NONE, ThermalStatus.LIGHT, ThermalStatus.MODERATE)) {
            assertEquals("$s should not decimate", 1, ThermalDecimationPolicy.decimationFactor(s))
        }
    }

    @Test fun factor_isTwo_atSevereAndAbove() {
        for (s in listOf(ThermalStatus.SEVERE, ThermalStatus.CRITICAL, ThermalStatus.EMERGENCY, ThermalStatus.SHUTDOWN)) {
            assertEquals("$s should decimate", 2, ThermalDecimationPolicy.decimationFactor(s))
        }
    }

    @Test fun shouldSubmit_factorOne_passesEveryFrame() {
        for (c in 0..5) assertTrue(ThermalDecimationPolicy.shouldSubmit(c, 1))
    }

    @Test fun shouldSubmit_factorTwo_passesEveryOtherFrame() {
        assertTrue(ThermalDecimationPolicy.shouldSubmit(0, 2))
        assertFalse(ThermalDecimationPolicy.shouldSubmit(1, 2))
        assertTrue(ThermalDecimationPolicy.shouldSubmit(2, 2))
        assertFalse(ThermalDecimationPolicy.shouldSubmit(3, 2))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.service.ThermalDecimationPolicyTest"`
Expected: FAIL — `ThermalDecimationPolicy` unresolved.

- [ ] **Step 3: Write minimal implementation**

`app/src/main/java/com/aritr/rova/service/ThermalDecimationPolicy.kt`:
```kotlin
package com.aritr.rova.service

import com.aritr.rova.ui.signals.ThermalStatus

/**
 * ADR-0035 — thermal-adaptive encode decimation. Extends ADR-0016's thermal
 * ladder: at SEVERE (one rung below the CRITICAL autostop) DualShot encodes
 * only every 2nd frame, halving encoder duty so the device climbs to CRITICAL
 * slower — sessions last longer before the hard stop. Preview is never
 * decimated (see EglRouter). Mirrors [SegmentGateThermal]: reads the
 * already-hysteresis'd `thermalStatusSignal.state.value` (ADR-0019), so it
 * needs no hysteresis of its own. Pure — JVM-testable (ADR-0007).
 */
internal object ThermalDecimationPolicy {
    /** 1 = encode every frame; 2 = encode every 2nd (~half fps). */
    fun decimationFactor(status: ThermalStatus): Int =
        if (status.ordinal >= ThermalStatus.SEVERE.ordinal) 2 else 1

    /** True when this frame should be fed to the encoders. factor<=1 => all. */
    fun shouldSubmit(frameCounter: Int, factor: Int): Boolean =
        factor <= 1 || frameCounter % factor == 0
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.service.ThermalDecimationPolicyTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/service/ThermalDecimationPolicy.kt app/src/test/java/com/aritr/rova/service/ThermalDecimationPolicyTest.kt
git commit -m "feat(perf): ThermalDecimationPolicy — SEVERE->factor 2, per-frame submit predicate"
```

---

### Task 2: EglRouter decimation gate

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/service/dualrecord/internal/EglRouter.kt` (field near other `@Volatile` body fields ~262; gate the encoder-feed block at ~589; new counter)

**Interfaces:**
- Consumes: `ThermalDecimationPolicy.shouldSubmit` (Task 1).
- Produces: `EglRouter.encodeDecimationFactor: Int` (`@Volatile var`, default `1`); the encoder-feed block runs only when `ThermalDecimationPolicy.shouldSubmit(encodeFrameCounter++, encodeDecimationFactor)`.

Not JVM-testable (EGL). Correctness of the predicate is covered by Task 1; this task is verified by `assembleDebug` + Task 5 device-verify.

- [ ] **Step 1: Add the volatile factor + counter**

In `EglRouter.kt`, add two fields to the class body (place beside the existing render-state fields, e.g. just after the class-level `@Volatile`/counter declarations near the top of the class body):
```kotlin
    // ADR-0035 thermal-adaptive encode decimation. Set from the service
    // thread (single writer) via the DualSurfaceProcessor passthrough; read
    // on the callback (renderFrame) thread. 1 = encode every frame (default,
    // identical to pre-ADR-0035 behavior). Preview render is NEVER gated on
    // this — decimation is encoder-feed only.
    @Volatile
    var encodeDecimationFactor: Int = 1
    // Callback-thread only (renderFrame is single-threaded) — no sync needed.
    private var encodeFrameCounter: Int = 0
```

- [ ] **Step 2: Gate the encoder-feed block**

In `renderFrame()`, change the encoder-feed guard (currently `if (liveEncoders.isNotEmpty() && ring != null) {` at ~line 589) to also require the decimation predicate. The counter increments once per call so cadence is stable regardless of `liveEncoders` emptiness:
```kotlin
        val ring = fboRing
        val submitThisFrame = com.aritr.rova.service.ThermalDecimationPolicy
            .shouldSubmit(encodeFrameCounter++, encodeDecimationFactor)
        if (submitThisFrame && liveEncoders.isNotEmpty() && ring != null) {
```
Leave the entire block body (blit, fence, `recordFence`, `liveEncoders.forEach { it.submit(frame) }`) unchanged. The preview-render section further down in `renderFrame()` stays outside this guard — do not touch it. When `submitThisFrame` is false, the encoders simply get no new frame this tick (their mailbox `take()` blocks until the next submit) — half-rate encode, preview unaffected.

- [ ] **Step 3: Verify it compiles + gates pass**

Run: `./gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL (compiles; all 47 gates pass — factor default 1 = no behavior change).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/aritr/rova/service/dualrecord/internal/EglRouter.kt
git commit -m "feat(perf): EglRouter encoder-feed decimation gate (preview untouched)"
```

---

### Task 3: Passthrough + service wiring

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/service/dualrecord/internal/DualSurfaceProcessor.kt` (add setter delegating to `router`)
- Modify: `app/src/main/java/com/aritr/rova/service/dualrecord/DualVideoRecorder.kt` (add setter delegating to `processor`)
- Modify: `app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt` (`checkSegmentGates`, ~line 2725-2732)

**Interfaces:**
- Consumes: `ThermalDecimationPolicy.decimationFactor` (Task 1); `EglRouter.encodeDecimationFactor` (Task 2); `currentDualRecorder: DualVideoRecorder?` (`RovaRecordingService.kt:292`).
- Produces: `DualVideoRecorder.setEncodeDecimationFactor(factor: Int)`; `DualSurfaceProcessor.setEncodeDecimationFactor(factor: Int)`.

Plumbing only — no JVM test (the decision logic is `ThermalDecimationPolicy`, tested in Task 1). Verified by `assembleDebug` + Task 5.

- [ ] **Step 1: DualSurfaceProcessor passthrough**

In `DualSurfaceProcessor.kt` (the `router` field is at line 42), add a public method on the class:
```kotlin
    /** ADR-0035 — forward the thermal decimation factor to the router. */
    fun setEncodeDecimationFactor(factor: Int) {
        router.encodeDecimationFactor = factor
    }
```

- [ ] **Step 2: DualVideoRecorder passthrough**

In `DualVideoRecorder.kt` (the `processor` lazy field is at line 37), add:
```kotlin
    /** ADR-0035 — forward the thermal decimation factor to the EGL router. */
    fun setEncodeDecimationFactor(factor: Int) {
        processor.setEncodeDecimationFactor(factor)
    }
```

- [ ] **Step 3: Wire into checkSegmentGates**

In `RovaRecordingService.kt`, `checkSegmentGates` already reads the stable thermal status (~line 2729) and calls `SegmentGateThermal.shouldTerminate` (~line 2731). Immediately AFTER the existing thermal read (and before/independent of the terminate check), set the factor on the active dual recorder so it applies to the next segment:
```kotlin
        // ADR-0035 — thermal-adaptive encode decimation. Same stable-status
        // read as the terminate gate below; per-segment cadence is ample for
        // a minutes-long heat soak. No-op for single mode (currentDualRecorder
        // null). Factor 1 below SEVERE restores full-rate on cooldown.
        currentDualRecorder?.setEncodeDecimationFactor(
            com.aritr.rova.service.ThermalDecimationPolicy.decimationFactor(thermal),
        )
```
(Use the existing local holding `app?.thermalStatusSignal?.state?.value` — named `thermal` at line ~2729. If it is nullable, default to `ThermalStatus.NONE` for this call as the existing gate does.)

- [ ] **Step 4: Verify compile + gates + unit tests**

Run: `./gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL.
Run: `./gradlew.bat :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL (baseline + 4 new Task-1 tests, all green).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/service/dualrecord/internal/DualSurfaceProcessor.kt app/src/main/java/com/aritr/rova/service/dualrecord/DualVideoRecorder.kt app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt
git commit -m "feat(perf): wire thermal decimation factor into checkSegmentGates (DualShot)"
```

---

### Task 4: ADR-0035 + candidate gate (owner sign-off)

**Files:**
- Create: `docs/adr/0035-thermal-adaptive-encode-decimation.md`
- (Owner-gated) Modify: `build-logic/src/main/kotlin/com/aritr/rova/gradle/RovaGateRules_*.kt`, `app/build.gradle.kts`, `build-logic/.../RegistryTest.kt` for a 48th gate `checkDecimationEncoderOnly`.

- [ ] **Step 1: Write ADR-0035**

Create `docs/adr/0035-thermal-adaptive-encode-decimation.md` (status **Accepted** pending owner sign-off) documenting: the encoder-bound thermal diagnosis (link `docs/superpowers/specs/2026-06-30-dualshot-jitter-thermal-findings.md`); the decision (single-step factor-2 decimation at `SEVERE`, encoder-feed only, preview full-rate, merge-safe, AE-floor-untouched); the ladder position (`SEVERE` lighten → `CRITICAL` hard-stop backstop); rejected alternatives (resolution/new-session, whole-session res); and the invariant "decimation is read only in the encoder-feed path, never preview."

- [ ] **Step 2: Commit the ADR**

```bash
git add docs/adr/0035-thermal-adaptive-encode-decimation.md
git commit -m "docs(adr): ADR-0035 thermal-adaptive encode decimation"
```

- [ ] **Step 3: Gate decision (STOP — owner sign-off)**

Ask the owner: add the 48th static gate `checkDecimationEncoderOnly` (assert `encodeDecimationFactor` / `shouldSubmit` appears only in the encoder-feed region of `EglRouter`, not the preview region), or rely on the ADR prose + Task-1 tests? Per house convention a new invariant gets a gate; but the owner explicitly reserved this call for ADR review. If yes: add the rule to `RovaGateRules`, register the `SourceCheckTask` in `app/build.gradle.kts`, add it to the `preBuild` `dependsOn` block, add its id to `RegistryTest.EXPECTED_IDS`, and prove it RED-fires on a violation before GREEN. Do not proceed without the owner's answer.

---

### Task 5: Device verification (RZCYA1VBQ2H)

**Files:** none (verification only).

- [ ] **Step 1: Build + install**

```bash
./gradlew.bat :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 2: Soak A/B — confirm relief**

Record DualShot (SD, many clips) and force the device hot (or run long enough to reach `SEVERE`). Confirm via `adb logcat` / `dumpsys thermalservice`:
- Recorded per-side fps ~halves once stable thermal ≥ `SEVERE`, and restores to full when it falls back below `SEVERE` (hysteresis handles the fall-dwell).
- Time-to-`CRITICAL` autostop is materially later than a factor-1 run under the same forcing.
- `EglRouter.recordRenderPerf` `interval`/`prevSwapMax` stay flat (preview smooth) throughout — decimation must NOT perturb preview cadence.

- [ ] **Step 3: Merge-safety + regression**

- Let a session cross a decimation transition, complete, and merge. `ffprobe` the merged per-side files: play cleanly, constant dimensions, monotonic PTS across the fps change.
- Cool-case regression: a fully-cool DualShot session must be byte-behavior identical to pre-change (factor stays 1) — same fps as the δ0 baseline.

- [ ] **Step 4: codex-review the diagnosis→fix→device result, then request owner GO.**

---

## Self-Review

- **Spec coverage:** policy (T1), EGL gate + preview-untouched invariant (T2), thermal wiring (T3), ADR + candidate gate (T4), device soak/merge/regression verify (T5). Hysteresis component from the spec is intentionally dropped — `ThermalStatusSignal.state.value` is already hysteresis-applied upstream (ADR-0019); noted in `ThermalDecimationPolicy` KDoc. ✓
- **Placeholders:** none — all code shown; the one soft spot (exact local name/nullability of the thermal read in `checkSegmentGates`) is pinned to line ~2729 with a fallback instruction. ✓
- **Type consistency:** `decimationFactor(ThermalStatus):Int`, `shouldSubmit(Int,Int):Boolean`, `setEncodeDecimationFactor(Int)`, `encodeDecimationFactor:Int` used identically across T1-T3. ✓

# DualShot AE frame-rate floor — Design Spec

**Date:** 2026-06-29
**Status:** Approved (brainstorm), pending implementation plan
**Type:** Fix slice (Limiter 1 of the compound fps verdict)
**Gated on:** `docs/superpowers/specs/2026-06-29-dualshot-fps-cadence-findings.md` (diagnosis verdict, PR #154)
**Builds on / must not regress:** `memory/project_dualshot_stability_stack.md` (#25–#35 fence-sync), ADR-0009 (4:3 source + 27/64 side-crop)

---

## 1. Problem

The fps-cadence diagnosis (PR #154) proved the DualShot ~18–20fps plateau is **compound**, two stacked limiters:

- **Limiter 1 — auto-exposure (this slice).** The DualShot CameraX binding sets **no** `CONTROL_AE_TARGET_FPS_RANGE`. The device default `[15,30]` lets AE stretch exposure in dim light (measured 60ms exposure → 16.7fps camera cadence). A bright-light intervention dropped exposure to 3.27ms and lifted cadence to 30fps — proving capture cadence is exposure-bound, not a fixed pipeline limit.
- **Limiter 2 — encoder service time (NOT this slice).** Once the camera feeds 30fps, the encoder's ~45ms per-frame GPU service (`drawFrame` + the FBO-slot read-safety `glFinish`) caps output at ~22fps with 25–33 mailbox drops/side. Deferred to a separate later cycle that begins with its own micro-diagnosis (over-synchronization vs GPU saturation vs thermal); it touches the load-bearing fence-sync stability stack and is not yet root-caused to a specific fix.

This slice raises the AE floor so capture cadence in normal/dim light stops collapsing to ~16fps. It does **not** reach a uniform 30fps — output remains encoder-gated at ~22fps until Limiter 2 is addressed — but it is a proven, low-risk, standalone improvement that restores smoother, more consistent capture.

## 2. Decision summary (owner, 2026-06-29)

- **Scope:** AE floor only this cycle. Encoder = separate later cycle.
- **Policy:** asymmetric floor **`[24,30]`** (not a hard `[30,30]` lock) — forbids the ~16fps worst case while still letting AE relax to 24fps in dim light for more brightness.
- **Seam:** hybrid — CameraX-native capability read (`Camera2CameraInfo`, pre-bind) + frame-0 application (`Camera2Interop.Extender` on the `Preview.Builder`). No platform `CameraManager`; no post-bind `Camera2CameraControl` re-assert (reserved for the future adaptive/encoder cycle).
- **Gate:** include a new 47th static-check gate `checkAeFpsRangeCapabilityGated`.

## 3. Architecture / data flow

All changes in `RovaRecordingService.setupDualCamera` (~lines 2095–2254) plus one new pure helper and one new gate.

In `setupDualCamera`, after `provider` is resolved (~line 2109) and before the preview is built (~line 2238):

1. Resolve the bound camera's `CameraInfo`: `currentCameraSelector.filter(provider.getAvailableCameraInfos())` → first element (the back camera, `DEFAULT_BACK_CAMERA`).
2. Read its supported ranges: `Camera2CameraInfo.from(cameraInfo).getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)` → `Array<Range<Int>>?`.
3. Map to primitive `Int` pairs and call the pure helper `AeFpsRangePolicy.choose(available, floor = 24, ceiling = 30)` → a chosen `(lower, upper)` or **null**.
4. If non-null, wrap as `android.util.Range(lower, upper)` at the edge and apply via `Camera2Interop.Extender(previewBuilder).setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, chosen)`. If null, set nothing — today's `[15,30]` default behavior is preserved.
5. The existing DEBUG `attachAeMetadataProbe` (from PR #154) stays. It logs the *effective* `CONTROL_AE_TARGET_FPS_RANGE` from each capture result, so device runs confirm the floor took.

The whole read+choose+apply block is wrapped so any exception (characteristics unavailable, interop quirk) is caught and logged, and the binding proceeds unchanged — the camera must always open. Best-effort, never blocking, mirroring the probe's contract.

## 4. Pure helper — `AeFpsRangePolicy`

`app/src/main/java/com/aritr/rova/service/dualrecord/internal/AeFpsRangePolicy.kt`. Framework-free (D-deviation: operates on primitive `Int` pairs; `android.util.Range` is wrapped only at the call site in the service). JVM-unit-tested.

**Signature:** `choose(available: List<Pair<Int, Int>>, floor: Int, ceiling: Int): Pair<Int, Int>?` — each `Pair` is `(lower, upper)`; returns the chosen `(lower, upper)` or `null` meaning "do not set the option." (`android.util.Range` ⇄ `Pair` conversion happens only at the service edge, never inside the helper.)

**Selection algorithm (discrete list — must pick a listed range, cannot request an arbitrary one):**

- **Pass 1** — candidates with `upper == ceiling` (30) and `lower >= floor` (24). Pick the one with the **lowest `lower`** (maximum low-light headroom). This prefers `[24,30]` over `[30,30]`.
- **Pass 2** (Pass 1 empty) — candidates with `lower >= floor` and `upper <= ceiling`. Pick highest `upper`, then lowest `lower`. Yields e.g. `[24,24]` when no `*,30` floor range exists.
- **Pass 3** (none) — return `null`. The option is not set; the device default `[15,30]` (or whatever it is) stays. Never breaks camera-open.

**Determinism:** equal candidates resolve by the stated tie-breaks (lowest `lower`, then as-listed order) so the result is stable.

### 4.1 Test matrix (`AeFpsRangePolicyTest`, all JVM)

| Available ranges | Expected | Why |
|---|---|---|
| `[15,15],[15,30],[24,30],[30,30]` | `[24,30]` | Pass 1, lowest lower |
| `[15,30],[30,30]` (no `[24,30]`) | `[30,30]` | Pass 1, only ≥24 floor with upper 30 |
| `[15,24],[24,24]` (no upper-30) | `[24,24]` | Pass 2 |
| `[7,30],[15,30]` (no floor ≥24) | `null` | no qualifying range → don't set |
| `[]` (empty/unknown) | `null` | unknown caps → don't set |
| `[15,15],[30,60],[60,60]` | `null` | no upper ≤30 with floor ≥24 (won't accept a 60 ceiling) |
| `[24,30],[24,30]` (dup) | `[24,30]` | dedup-stable |

Baseline 1241 tests / 0-0-0; new tests land in the same PR.

## 5. Behavior / the quantified tradeoff

| Scene | Today (`[15,30]`) | After `[24,30]` floor |
|---|---|---|
| Dim | AE → 16.7fps, exposure 60ms | AE held ≥24fps, exposure ≤ ~42ms → **camera 24fps** |
| Bright | 30fps, exposure 3.3ms | 30fps, exposure 3.3ms (unchanged) |

Dim-scene exposure settles at ~42ms — **between** today's 60ms and a hard-30 lock's 33ms: smoother capture, modestly dimmer than today, brighter than locking 30.

**Honest limit:** real-world *output* is still encoder-gated at ~22fps (Limiter 2). Dim output rises ~16.7 → ~22fps (≈ +30%) and capture is smoother/more consistent; it does **not** reach 30. The encoder cycle lifts the rest. This slice's success criterion is the **camera-side** cadence (the AE floor taking effect), measured on `cameraHW` / `DualShot AE`, not the merged-file output fps.

## 6. The 47th gate — `checkAeFpsRangeCapabilityGated`

**Invariant (new ADR-0034 clause):** the DualShot AE target fps range must be **capability-gated** — chosen from the device's `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES` via `AeFpsRangePolicy`, never a hard-coded literal. An unconditional `setCaptureRequestOption(CONTROL_AE_TARGET_FPS_RANGE, Range(x,y))` is a camera-open footgun on devices that don't list `[x,y]`.

**Rule (in `RovaGateRules`, build-logic):** scope = `RovaRecordingService.kt`. On comment-stripped source, if a statement references `CONTROL_AE_TARGET_FPS_RANGE`, it must **not** also construct an inline `Range(` / `android.util.Range(` literal — the value argument must be a reference (the helper's output). The legitimate `android.util.Range` construction lives in the service edge fed by the helper result; a hard-coded literal range passed straight to `setCaptureRequestOption` fails the gate. Detection on `strippedLines` (comment-aware) per the project's comment-strip convention; failure message cites the ADR-0034 clause.

**Wiring (per CLAUDE.md gate recipe):** ADR-0034 clause → rule + golden test in `build-logic/src/{main,test}/kotlin/.../RovaGateRules_*.kt` (RED-fires on a literal-range violation, GREEN on the helper-reference form) → `tasks.register<SourceCheckTask>("checkAeFpsRangeCapabilityGated")` in `app/build.gradle.kts` → add to the `preBuild` `dependsOn` block → add `"AeFpsRangeCapabilityGated"` to `RegistryTest` `EXPECTED_IDS`. Gate count 46 → **47**.

## 7. Error handling / non-regression

- Read+choose+apply wrapped in try/catch; on any failure the binding proceeds with no AE option set (today's behavior). The camera must always open.
- `null` from the helper is a normal outcome, not an error — log at DEBUG, set nothing.
- DualShot binding only. Single-camera and FrontBack paths untouched (not diagnosed; out of scope).
- ADR-0009 crop geometry, the #25–#35 fence-sync stability stack, the DualMuxer, and all 46 existing gates untouched.
- `@OptIn(ExperimentalCamera2Interop)` on the new interop call sites (consistent with the existing probe).

## 8. Testing + verification

- **JVM:** `AeFpsRangePolicyTest` (§4.1) + the new gate's golden test. `:app:testDebugUnitTest`.
- **Build/gates:** `:app:assembleDebug` (fires all **47** gates on preBuild), not `:app:lintDebug` (pre-existing `VaultAndroidOps:267` NewApi RED unrelated).
- **Device (RZCYA1VBQ2H):** dim DualShot run → `DualShot AE` log shows effective range `[24,30]` (or the device's best supported) and `cameraHW ≥ 24fps` vs 16.7 today; bright run still 30fps; **camera opens cleanly** (no session-config failure); capture the encoder drops/output for the record. Restore prefs after.

## 9. Out of scope

- Encoder service-time / Limiter 2 (separate cycle, starts with its own micro-diagnosis).
- Adaptive/brightness-aware AE policy and any user-facing smoothness-vs-low-light preference (owner chose a fixed `[24,30]`).
- Post-bind `Camera2CameraControl` runtime AE changes (reserved for the future adaptive/encoder cycle).
- Single-camera / FrontBack AE tuning.
- Removing the DEBUG cadence probe (kept through the fix cycle to measure each step; removed before the encoder fix PR per the diagnosis spec).

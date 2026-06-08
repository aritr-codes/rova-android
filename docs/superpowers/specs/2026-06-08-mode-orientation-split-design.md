# Capture-Topology / Orientation-Policy Split — Design Spec

> **Status:** Draft for owner review (2026-06-08). Companion to **ADR-0029** (`docs/adr/0029-capture-topology-orientation-policy.md`, Status: Proposed).
> **Type:** Program (multi-PR). PR-α is a standalone reliability fix and ships first; PR-γ is the model change; PR-β (chrome) and PR-δ (FrontBack) are independently sequenceable.
> **Supersedes scope of:** the conflated `mode` string (`Portrait | Landscape | PortraitLandscape`) — split into two orthogonal axes.

## Goal

Stop conflating **what** Rova captures (one sensor / two side-cropped views / two physical sensors) with **how** the frame is oriented (auto-rotate / locked portrait / locked landscape). Today both live in one string `mode` and the app never reacts to device rotation after a segment binds, so a phone picked up mid-session records sideways forever. This program (a) lands a device-driven auto-rotate fix that is correct under the existing model, then (b) replaces `mode` with two first-class axes — `CaptureTopology` × `OrientationPolicy` — migrating prefs, manifest, picker, service, recovery and export without reinterpreting old sessions, and (c) sketches the future two-sensor `FrontBack` topology.

## Context — verified current state (the starting point)

These are the facts the design works against; cite them, do not assume the reader re-derived them.

- **`mode` is a single string** `"Portrait" | "Landscape" | "PortraitLandscape"`, default `"Portrait"`, persisted in three places:
  - `RovaSettings.mode` — key `"mode"` in **runtime prefs** (`getString("mode", "Portrait")`; reset on reinstall by design — `RovaSettings` even actively strips a restored-from-backup `"mode"` key on launch).
  - `SessionConfig.mode` — default `"Portrait"`, serialized in `SessionConfig.toJson()`.
  - `SessionManifest.config.mode` — persisted under `config`; current `SessionManifest.SCHEMA_VERSION = 9`.
- **Picker** is a 3-tab segmented control whose single tap-cycle chip advances via the pure helper `cycleModeNext(current: String)` in `ui/screens/RecordModeCycle.kt` (`Portrait→Landscape→PortraitLandscape→Portrait`, defensive `else -> "Portrait"`).
- **ViewModel path:** `RecordViewModel.cycleMode()` reads `mode.value`, delegates order to `cycleModeNext`, calls `setMode(mode: String)` which does (1) prefs commit + (2) `_mode` StateFlow emit + (3) `serviceBinder?.getService()?.setMode(mode)` rebind. `cycleMode()` / `setMode()` are **guarded no-ops during an active session** (same mid-session guard as `reloadRecordingDefaults()`).
- **Capture orientation** is computed by the file-level `internal fun computeTargetRotation(displayRotation: Int, mode: String): Int` in `RovaRecordingService.kt`:
  - `base = when(displayRotation) { ROTATION_270 -> displayRotation; … }` → Portrait = `base`, Landscape = `(base+1)%4`, P+L = `base`.
  - Applied in `setupSingleCamera()` as `VideoCapture.Builder(recorder).setTargetRotation(targetRot)` + `Preview.Builder().setTargetRotation(targetRot)`.
  - `displayRotation` is read **ONCE at bind** from `(getSystemService(DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY).rotation`. **There is no `OrientationEventListener`.** Rotation is locked at bind and only re-read when `forceReconfigureCamera()` rebinds — which today is triggered **only** by `setMode` / `flipCamera`, never by the device physically rotating.
- **P+L path:** `setupDualCamera()` (when `mode == "PortraitLandscape"`) builds a `Preview` with `setTargetRotation(Surface.ROTATION_0)` **hardcoded** — EGL / `AspectFitMath` owns rotation; rear-only; CameraEffect + EGL14/GLES20 fan-out to two `MediaMuxer`s.
- **AndroidManifest** `MainActivity`: `screenOrientation="unspecified"` + `configChanges="orientation|screenSize|screenLayout|keyboardHidden"` — the Activity rotates without recreate, but the **capture pipeline does not react** to that config change at all.
- **ADR-0026** (presets): `RovaPreset` has **no `mode`/orientation field** (orientation-orthogonal); `checkPresetNoOrientation` enforces it. The split must preserve this — presets stay orthogonal to both new axes.

## Architecture (one sentence each)

- **Two orthogonal axes replace one string:** `CaptureTopology ∈ {Single, DualShot, FrontBack}` answers *what to capture*; `OrientationPolicy ∈ {Auto, PortraitLock, LandscapeLock}` answers *how to orient it*. `Single`+`Auto`/`PortraitLock`/`LandscapeLock` covers everything the old `Portrait`/`Landscape` did and adds true device-driven rotation; `DualShot` is today's P+L; `FrontBack` is future.
- **Orientation is sampled, snapped, and applied at segment boundaries** — never mid-clip — by a pure pipeline `OrientationEventListener → OrientationSnap → OrientationPolicyResolver → effectiveTargetRotation`, persisted per clip in the manifest.
- **Migration is pure + non-destructive:** a deterministic mapper translates the legacy `mode` string into a `(CaptureTopology, OrientationPolicy)` pair on read; the old `"mode"` key and old manifests stay readable and are **never reinterpreted** (an old `Landscape` session keeps its baked-in rotation).

This document references ADR-0029 by clause throughout and restates each clause it depends on. The landscape-chrome mockup lives in `rova_design_system_round3.html` (`.lrec` panel — gitignored visual reference; measurements that matter are inlined in PR-β).

---

## 1. The two axes (ADR-0029 §Decision 1)

```kotlin
/** What the capture graph produces. Orthogonal to OrientationPolicy. */
enum class CaptureTopology {
    Single,     // one sensor → one stream. Replaces legacy Portrait + Landscape.
    DualShot,   // today's P+L: one rear sensor → EGL fan-out → two muxes (ADR-0009).
    FrontBack,  // FUTURE: two concurrent physical sensors → two muxes (PR-δ).
}

/** How the captured frame is oriented. Orthogonal to CaptureTopology. */
enum class OrientationPolicy {
    Auto,          // default — device-driven via OrientationEventListener + hysteresis.
    PortraitLock,  // first-class mounted/tripod use; Auto can't fix a non-90° tilt.
    LandscapeLock, // first-class; "which landscape" resolved per §Open-Q.
}
```

- **Orthogonal:** every `(topology, policy)` pair is valid in principle. `DualShot` consumes orientation through the EGL transform / muxer metadata path, **not** `VideoCapture.targetRotation` (§4). `FrontBack` is gated by device capability (§5).
- **Lock is first-class, not a degraded Auto.** Mounted/tripod recording wants a fixed orientation even while the device sits at a non-90° angle that `Auto` would refuse to snap. ADR-0029 §Decision 2.
- **Default `OrientationPolicy = Auto`** for new users (**[Ratified A]** — conditional on a visible session-setup orientation control, else `PortraitLock`); **migrated** legacy `Portrait`→`PortraitLock`, not `Auto`. See §8 (resolved) and ADR-0029 §2/§6.

---

## 2. New pure helpers (house pure-helper pattern, JVM-testable)

All three are framework-free so they run under `testOptions.unitTests.isReturnDefaultValues = true`. They follow the precedent of `ThermalHysteresis`, `AspectFitMath`, `RecordModeCycle`, `ContrastMath`.

### 2.1 `OrientationSnap` — degrees → `Surface.ROTATION_*` with hysteresis

The `OrientationEventListener` callback delivers `0..359` (or `ORIENTATION_UNKNOWN = -1`). Snapping the raw degree to one of four buckets naively chatters at the 45°/135°/225°/315° boundaries. Reuse the **asymmetric-hysteresis shape** proven in `applyThermalHysteresis` (a pure `(raw, state, now) → state` step function with a dwell timer): a new candidate rotation must persist for a dwell before it becomes `stable`.

```kotlin
package com.aritr.rova.service.orientation

/** Opaque hysteresis state; mirrors ThermalHysteresis.HysteresisState. */
internal data class OrientationSnapState(
    val stable: Int,                 // a Surface.ROTATION_* value (0/1/2/3)
    val candidate: Int?,             // pending rotation under dwell, or null
    val candidateSinceMs: Long?,     // non-null iff candidate != null
)

internal const val ORIENTATION_DWELL_MS: Long = 350L   // tune on device (§Risks)
internal const val ORIENTATION_HYSTERESIS_DEG: Int = 12 // dead-band each side of a bucket edge

/**
 * Pure. degrees ∈ [0,359] or ORIENTATION_UNKNOWN(-1). UNKNOWN is IGNORED
 * (returns state unchanged). A degree within ORIENTATION_HYSTERESIS_DEG of a
 * bucket boundary keeps the current stable rotation (dead-band). A degree
 * cleanly inside a NEW bucket starts/continues a dwell; only after
 * ORIENTATION_DWELL_MS does `stable` flip. Same dwell semantics as
 * applyThermalHysteresis (multi-event during dwell does NOT restart the timer).
 */
internal fun snapOrientation(
    degrees: Int,
    current: OrientationSnapState,
    nowMs: Long,
    dwellMs: Long = ORIENTATION_DWELL_MS,
    deadBandDeg: Int = ORIENTATION_HYSTERESIS_DEG,
): OrientationSnapState
```

Mapping (matches Android's convention; device-natural-portrait assumed, validated on device): `[315..360)∪[0..45) → ROTATION_0`, `[45..135) → ROTATION_270`, `[135..225) → ROTATION_180`, `[225..315) → ROTATION_90` (sensor-vs-display sign is verified empirically — see Risks; the math seam keeps it testable regardless of sign).

**Unit-test cases:**
- `ORIENTATION_UNKNOWN(-1)` → state unchanged (no candidate started).
- raw squarely in current bucket → unchanged, any in-flight candidate cleared.
- raw in a new bucket but `< dwellMs` elapsed → `stable` unchanged, `candidate` set.
- raw in a new bucket, `>= dwellMs` elapsed → `stable` flips to candidate, candidate cleared.
- raw oscillating across a boundary within the dead-band → never flips (dead-band absorbs it).
- multi-event lower/other-bucket during dwell does **not** restart the timer (mirror the thermal multi-event test).
- exact boundary degree (e.g. 45) is deterministic (document which side it falls).

### 2.2 `OrientationPolicyResolver` — policy + snapped rotation → `effectiveTargetRotation`

```kotlin
package com.aritr.rova.service.orientation

/**
 * Pure. Resolves the rotation a NEW segment will be encoded with.
 *  - Auto          → the hysteresis-snapped device rotation.
 *  - PortraitLock  → fixed natural-portrait rotation (ROTATION_0) regardless of device.
 *  - LandscapeLock → fixed landscape rotation ("which landscape" = §Open-Q;
 *                    default ROTATION_90, owner-confirmable).
 * Independent of CaptureTopology — DualShot feeds this result into the EGL path,
 * Single into VideoCapture.targetRotation (§4).
 */
internal fun resolveEffectiveTargetRotation(
    policy: OrientationPolicy,
    snappedRotation: Int,        // a Surface.ROTATION_* from OrientationSnap
    naturalPortraitRotation: Int = Surface.ROTATION_0,
    landscapeRotation: Int = Surface.ROTATION_90,
): Int
```

**Unit-test cases:** `Auto` echoes `snappedRotation` for all four values; `PortraitLock` returns `naturalPortraitRotation` for every input; `LandscapeLock` returns `landscapeRotation` for every input; result is always a valid `ROTATION_*`.

### 2.3 `CaptureModeMigration` — legacy `mode` string → `(CaptureTopology, OrientationPolicy)`

```kotlin
package com.aritr.rova.data

@Immutable data class CaptureMode(
    val topology: CaptureTopology,
    val policy: OrientationPolicy,
    val migratedFromLegacy: Boolean = false,  // true → "legacy landscape" flag (see Risks)
)

/**
 * Pure, deterministic. The ONLY translation from the old single string.
 *  "Portrait"          → Single + Auto
 *  "Landscape"         → Single + LandscapeLock  (migratedFromLegacy = true)
 *  "PortraitLandscape" → DualShot + Auto
 *  unknown / null      → Single + Auto           (matches cycleModeNext's defensive else)
 * Does NOT reinterpret an old SESSION's baked rotation — see ADR-0029 §Decision 6.
 */
internal fun migrateLegacyMode(mode: String?): CaptureMode
```

`Portrait → Single+Auto` is deliberate: the old "Portrait" never rotated, but the new default behaviour IS auto-rotate, and PR-α already made auto-rotate the live behaviour under the old model, so the migrated default matches shipped behaviour after PR-α. `Landscape → Single+LandscapeLock` (not Auto) because a user who explicitly chose Landscape wants it locked; the `migratedFromLegacy` flag lets the UI surface a one-time "your Landscape mode is now a lock — switch to Auto?" nudge without changing behaviour silently.

**Unit-test cases:** each of the three known strings maps exactly; `null` and an arbitrary unknown both map to `Single+Auto`; `Landscape` sets `migratedFromLegacy=true` and the other two leave it `false`; round-trip with §3 serialization is stable.

---

## 3. Persistence & serialization

- **`RovaSettings`:** add `captureTopology: CaptureTopology` and `orientationPolicy: OrientationPolicy`, persisted as their enum `.name` strings under new runtime-prefs keys `"capture_topology"` / `"orientation_policy"` (same runtime-prefs store as `"mode"`, so reinstall-reset semantics are preserved). On read, if the new keys are **absent** but `"mode"` is present, derive via `migrateLegacyMode` and write the new keys (one-time); leave `"mode"` untouched for one release (rollback safety, mirroring the `theme_mode → theme_selection` migration precedent in `RovaSettings`).
- **`SessionConfig`:** add `captureTopology`, `orientationPolicy`, and `effectiveTargetRotation: Int?` fields; keep the legacy `mode: String` field **readable** (don't delete it — old manifests carry it). `toJson()` writes all of them; `fromJson()` reads new fields when present, else derives topology/policy from legacy `mode` via `migrateLegacyMode` **but leaves `effectiveTargetRotation` null** for legacy sessions (no rotation re-interpretation).
- **`SessionManifest`:** bump `SCHEMA_VERSION 9 → 10`. Per-clip orientation is the new load-bearing field: `effectiveTargetRotation` is sampled at **segment start** and persisted with each segment (or in `SessionConfig` if the session is single-rotation; per-segment is preferred so a mid-session rotation under `Auto` is recorded accurately). `fromJson` for schema < 10 reads legacy `config.mode` only and never fabricates an `effectiveTargetRotation`.

ADR-0029 §Decision 6 (restated, **[Ratified A]**): *add new fields, keep legacy `mode` readable, never reinterpret an old `Landscape` session's baked rotation; prefs map `Portrait→Single+PortraitLock(flag legacy)`, `Landscape→Single+LandscapeLock(flag legacy)`, `PortraitLandscape→DualShot`.* (New-user default is `Auto`; **migrated** Portrait is `PortraitLock` — distinct, see §1/§8.)

---

## 4. Where each axis is consumed (ADR-0029 §Decision 3, 4)

- **`Single` topology** consumes `effectiveTargetRotation` via `VideoCapture.setTargetRotation(rot)` on the already-bound use case (live update, **no rebind**) — but the in-progress `Recorder` ignores rotation changes, so the change only takes effect on the **next segment**. Preview can update live for the operator's sake.
- **`DualShot` topology** consumes `effectiveTargetRotation` through the **EGL transform / muxer-metadata** path (`service/dualrecord/`, `AspectFitMath`), **not** `VideoCapture.targetRotation`. The hardcoded `setTargetRotation(ROTATION_0)` on the dual `Preview` stays; orientation rides the matrix + muxer rotation metadata.
- **Segment-boundary application (ADR-0029 §Decision 3, restated):** rotation is **sampled at segment start** and held for that whole segment. Live `setTargetRotation` is permitted for preview, but the recorded rotation for a clip is frozen at its start. After a rotation change, **refresh `ResolutionInfo`** (the resolution can swap between portrait/landscape aspect). The HUD shows **current vs pending-next** orientation so the operator understands the next segment will rotate; the pending indicator is **debounced** so a brief tilt doesn't flicker the HUD.

---

## 5. `FrontBack` topology (future — high-level only, PR-δ)

- **Capability detection:** API 30+ `CameraManager.getConcurrentCameraIds()`, gated on the **actual supported combo** for the device — not on API level. Devices that report API 30 but expose no usable front+back concurrent combo must **hide/disable** the FrontBack option. Prefer CameraX's concurrent-camera API (`LifecycleCameraProvider.bindToLifecycle` with a 2-camera `ConcurrentCamera` config; CameraX enforces the 2-camera limit and lower res/fps).
- **Two CameraX bindings** (front + back) → two encoders → two `MediaMuxer`s. Parallels `service/dualrecord/` structurally, but with two real sensors instead of one sensor fanned out in EGL.
- **Composition** (PiP vs side-by-side split) is an **open question** — flagged, not designed here.
- **Expected constraints:** lower resolution/fps than single-sensor; thermal pressure higher; orientation still flows through `OrientationPolicyResolver` per stream.

**This whole section is deferred.** PR-δ lands only after the model (PR-γ) ships and a device-capability survey exists.

---

## 6. Per-PR breakdown (the spine)

### PR-α — Device-driven Auto-rotate (standalone disorientation fix, model UNTOUCHED)

**Why first:** this is a real reliability bug (phone picked up mid-session records sideways forever) that is fixable **without** the model change. Modes stay `Portrait | Landscape | PortraitLandscape`; `OrientationPolicy`/`CaptureTopology` enums do **not** exist yet. This PR teaches the existing `Single` path to re-read orientation at segment boundaries.

**New files:**
- `app/src/main/java/com/aritr/rova/service/orientation/OrientationSnap.kt` — `snapOrientation` + `OrientationSnapState` + dwell/dead-band consts (§2.1).
- `app/src/test/java/com/aritr/rova/service/orientation/OrientationSnapTest.kt` — the §2.1 case list.
- (PR-α uses `OrientationSnap` directly; `OrientationPolicyResolver` lands in PR-γ. For PR-α, "Auto" is the only behaviour and equals the snapped rotation.)

**Modified:**
- `app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt`:
  - Add an `OrientationEventListener` field. **Lifecycle:** `enable()` on successful camera bind (end of `setupSingleCamera()`; **not** for `setupDualCamera()` in PR-α — P+L rotation is EGL-owned and out of scope here), `disable()` on every teardown/unbind path (mirror the existing bind-success/teardown bookkeeping already present for camera generation/flip state).
  - In the listener callback, feed `orientation` through `snapOrientation` (holding `OrientationSnapState` as a service field); on a `stable` change, call `videoCapture.setTargetRotation(newRot)` + `preview.setTargetRotation(newRot)` **live (no rebind)**, and refresh `ResolutionInfo`. The recorded rotation only takes effect at the next segment (Recorder ignores mid-clip), which is the desired contract.
  - At **segment start** (the existing segment-boundary code), sample the current `stable` rotation into the new per-segment manifest field.
  - Keep `computeTargetRotation(displayRotation, mode)` as the **bind-time seed** (first segment) — the listener takes over after bind.
- `app/src/main/java/com/aritr/rova/data/SessionManifest.kt` — **schema 9 → 10**: persist `effectiveTargetRotation` per segment (sampled at segment start). `fromJson` schema-<10 path never fabricates it.
- `app/src/main/java/com/aritr/rova/data/SessionConfig.kt` — add nullable `effectiveTargetRotation` only if a session-level fallback is needed (per-segment preferred).

**Tests:** `OrientationSnapTest` (pure). Manifest round-trip test for the new per-segment field (uses the real `org.json` on `testImplementation` per test policy). No new static gate required (no new ADR invariant beyond what PR-γ will add); **PR-α is correct under the existing `mode` model**.

**Out of scope for PR-α:** the enum split, the picker, P+L auto-rotate, locks. This PR is purely "Single-path Auto-rotate now works."

### PR-β — Landscape Record-home chrome re-layout (presentation-only; the deferred liquid-glass PR2b)

**Why separable:** this is pure Compose layout — it does not touch capture, the model, or persistence. It can ship **before or after** PR-γ (§Open Questions). It themes/re-lays-out the existing Record home for landscape; the liquid-glass PR2 plan explicitly deferred this as "PR2b."

**Driver:** orientation-conditional chrome keyed on `LocalConfiguration.current.orientation` (`Configuration.ORIENTATION_LANDSCAPE`). Edge-hugging arrangement per the `.lrec` mockup in `rova_design_system_round3.html` — "controls hug the short edges":
- **FAB → right-center** (vertically centered on the right short edge) instead of bottom-center.
- **Nav → vertical rail** on a short edge instead of the bottom bar.
- **Settings card → left**, **mode/status → top**.

**Modified:**
- `app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt` — branch on `LocalConfiguration.current.orientation`; choose portrait (today's) vs landscape arrangement. No behavioural change to start/stop/cycle wiring.
- `app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt` — add a **`RecordSideRail`** composable (vertical nav rail) and a landscape arrangement that **reuses the existing `RecordFab` and `NavItem`** primitives (already present — confirmed at `RecordChrome.kt`). Do **not** fork their internals; only re-place them.
  - **`bottomNavBrush` scrim caveat:** `RecordChromeTokens.bottomNavBrush` is a **bottom-anchored vertical gradient**. A right-edge vertical rail needs a *horizontal* (right-anchored) scrim, or no scrim. Reusing `bottomNavBrush` on a right rail paints the gradient in the wrong direction — introduce a `sideRailBrush` token (or render the rail without the dock scrim) rather than misapplying the bottom one. Note: this is the one new token this PR adds; it inlines the mockup measurement (gitignored asset).
- Active-state HUD (status/loop pills, Stop FAB) re-place to the landscape arrangement using the same composables.

**Tests:** any pure layout-decision helper (e.g. an `isLandscape → arrangement` chooser) gets a JVM test; Compose layout itself is device-verified. No model/persistence test.

**Note:** **presentation-only.** Zero capture/model/manifest impact. Holds existing gates (no `Modifier.blur` on record surface; `checkNoHardcodedUiStrings` for any new string — none expected).

### PR-γ — Collapse modes into `CaptureTopology` × `OrientationPolicy` (the model change)

**This is the structural PR.** It introduces the enums, migrates `mode`, splits the picker, adds the orientation control, and threads both axes through every subsystem that read `mode`.

**New files:**
- `app/src/main/java/com/aritr/rova/data/CaptureTopology.kt` + `OrientationPolicy.kt` (or one file) — the §1 enums.
- `app/src/main/java/com/aritr/rova/service/orientation/OrientationPolicyResolver.kt` — `resolveEffectiveTargetRotation` (§2.2) + test.
- `app/src/main/java/com/aritr/rova/data/CaptureModeMigration.kt` — `migrateLegacyMode` + `CaptureMode` (§2.3) + test.
- Picker UI for the orientation control (`Auto / Portrait / Landscape`) — co-located with the existing mode chip in `RecordChrome.kt` / `RecordSettingsCard`.

**Touched subsystems (call out every one):**
- **Picker:** `cycleModeNext` in `RecordModeCycle.kt` becomes a **topology** cycle `Single → DualShot → Single` (FrontBack hidden until PR-δ + capability), renamed `cycleTopologyNext`. A **new** orientation control (segmented `Auto/PortraitLock/LandscapeLock`) is added — separate affordance, not folded into the topology chip. Keep both pure/JVM-tested.
- **`RecordViewModel`:** `setMode(String)` splits into `setTopology(CaptureTopology)` + `setOrientationPolicy(OrientationPolicy)`. Each: prefs commit + StateFlow emit + `serviceBinder?.getService()?.…` rebind, with the **same mid-session guard** as today's `cycleMode`/`setMode` (no-op during an active session). Topology change still rebinds (Single↔DualShot is a real camera-graph change); orientation-policy change rebinds only if it must (Auto↔Lock can be a live `setTargetRotation` on `Single`, but a DualShot policy change touches the EGL path — rebind to be safe in v1).
- **`SessionConfig`:** add `captureTopology`/`orientationPolicy`; keep `mode` readable; `fromJson` derives via `migrateLegacyMode` when new fields absent (§3).
- **`SessionManifest`:** the schema-10 fields from PR-α are already present; PR-γ adds `captureTopology`/`orientationPolicy` to the persisted `config` (no second schema bump if PR-α and PR-γ land together as 9→10; if PR-α ships first as 10, PR-γ is 10→11). Sequencing note: **prefer PR-α as 9→10 (rotation field) and PR-γ as 10→11 (axes fields)** to keep each bump's meaning crisp.
- **Service:** `setupSingleCamera()` / `setupDualCamera()` branch on `captureTopology` instead of `mode == "PortraitLandscape"`. `computeTargetRotation(displayRotation, mode)` is **retired** in favour of `resolveEffectiveTargetRotation(policy, snappedRotation)` for the seed + the PR-α listener for subsequent segments. `FrontBack` branch is a stub that throws/falls back until PR-δ.
- **Recovery:** `RecoveryScanner.classifyAll` and the segment-regex path are **agnostic to orientation** but must read schema-10/11 manifests without choking; **per-clip `effectiveTargetRotation` is informational for recovery, never a deletion input** (`checkRecoveryNoDeletion` unaffected). Verify the recovery segment regex still matches segment filenames (orientation does not change the filename scheme).
- **Export:** `Tier1/2/3` exporters and `VideoMerger` must concatenate segments that may have **different `effectiveTargetRotation`** under `Auto` (a session where the user rotated mid-way). v1 policy: the merged output adopts the **first segment's** rotation (or the session-config rotation); document that mixed-rotation `Auto` sessions merge to a single rotation (the muxer concat path can't re-rotate per-segment). This is an export-correctness call worth an ADR clause.

**ADR + gates:**
- **Promote ADR-0029 Proposed → Accepted.**
- **Candidate gates** (invariant → `check*` → `preBuild`, per house convention):
  - `checkNoLegacyModeStringWrites` — only `CaptureModeMigration` may read/branch on the legacy `"mode"` string; new code must use the enums. (Prevents the split from silently regrowing the conflation.)
  - `checkOrientationSampledAtSegmentStart` — `effectiveTargetRotation` is written only at the segment-boundary site (mirrors `checkCompletedWriteOnlyFromPerformMerge`'s single-writer shape).
  - `checkPresetNoOrientation` (existing) — re-verify presets stay orthogonal to **both** new axes (ADR-0026 still holds; presets carry neither topology nor policy unless an owner decision changes that).

**Tests:** `OrientationPolicyResolverTest`, `CaptureModeMigrationTest`, `SessionConfig`/`SessionManifest` round-trip with new fields + legacy-read, picker cycle tests. Full suite stays green (baseline 1241/0-0-0 + new).

### PR-δ — `FrontBack` topology (later, deferred)

High-level only (see §5). Lands after PR-γ + a device-capability survey:
- Capability detection (`getConcurrentCameraIds` + per-combo gating; prefer CameraX concurrent-camera).
- Concurrent two-binding camera session → two encoders → two muxers.
- Composition (PiP vs split) — **open question, designed in PR-δ's own spec.**
- Gating UI: hide/disable FrontBack on unsupported devices; surface lower res/fps expectation.
- Orientation flows per-stream through `OrientationPolicyResolver`.

**Marked deferred.** Not in the initial program's critical path.

---

## 7. Risks (codex-surfaced)

- **Recorder ignores mid-clip rotation (UX).** The biggest surprise: a user rotates the phone, the **preview** rotates, but the **current segment keeps recording the old rotation** — the new orientation only lands on the next segment. The HUD's "current vs pending-next" indicator (§4) is the mitigation; if it's unclear, users will think rotation is broken. Worth on-device validation of the messaging.
- **Hysteresis tuning.** `ORIENTATION_DWELL_MS` (350ms) and `ORIENTATION_HYSTERESIS_DEG` (12°) are first guesses. Too tight → chatter at boundaries; too loose → sluggish. Must be tuned on a real device (emulators are unreliable for sensor rotation). The pure seam makes re-tuning a one-constant change with test coverage intact.
- **"Which landscape" ambiguity.** `LandscapeLock` and the `Auto` landscape buckets have two physical orientations (camera-left vs camera-right / ROTATION_90 vs ROTATION_270). v1 picks one default (`ROTATION_90`); reverse-landscape is an open question. `LandscapeLock` won't auto-pick the "natural" landscape for a device that prefers the other.
- **Non-90° mounts.** `Auto` cannot fix a device sitting at, say, 30° on a desk — it snaps to the nearest bucket or refuses. This is exactly why **Lock is first-class**; document it so it's not filed as an Auto bug.
- **Concurrent-camera device fragmentation (PR-δ).** `getConcurrentCameraIds` returning a combo ≠ the combo actually working; OEM quirks; res/fps ceilings vary wildly. Gate on actual combos and expect to hide FrontBack on many devices.
- **Migration meaning-drift.** `Portrait → Single+Auto` changes the *behaviour* of the word "Portrait" (old Portrait never rotated; new default does). PR-α makes auto-rotate the shipped behaviour first. **[Ratified A+B]** Resolved: migrated legacy `Portrait`→`PortraitLock` (not `Auto`), so migration never silently turns on rotation for existing users; new users get `Auto`. Sequencing ratified `PR-α → PR-β → PR-γ`.
- **Manifest schema / recovery compatibility.** Two schema bumps (10 for rotation, 11 for axes) must each keep `fromJson` reading all older schemas without fabricating new fields. Recovery and export must tolerate per-clip rotation differences (mixed-rotation `Auto` sessions). A schema regression here corrupts recovery classification — covered by manifest round-trip tests across schema versions.

---

## 8. Open questions (owner) — **RESOLVED 2026-06-08 (codex-reviewed, owner-ratified)**

All four are decided; see the ADR-0029 **[Ratified A/B/C/D]** amendments for the authoritative clauses. Inline draft text elsewhere in this spec that predates these resolutions (e.g. the §2.2/§2.3 symbolic-lock samples and the `Portrait→Auto` migration note) is **superseded by the answers below** and is rewritten when PR-γ is planned.

1. **[D] Advanced locks 0/90/180/270 — DEFER the UI, build the four-rotation model now.** Picker shows three (`Auto` / Lock portrait / Lock landscape); the *internal* model is the four fixed rotations and **a lock captures+persists the explicit snapped `Surface.ROTATION_*` at lock time** — NOT a symbolic Portrait/Landscape token pinned to a constant (`ROTATION_0`≠portrait on tablets; "landscape" is two-valued 90/270). Reverse/180 becomes a later UI-only add with no prefs migration. γ acceptance criterion.
2. **[C] FrontBack composition — single-file PiP** (rear full + front inset), opinionated default, via CameraX-native `CompositionSettings` (not custom EGL, not two unjoined files as the artifact). Layout persisted as session metadata → split/side-by-side are export-time re-renders. Gate on two-encoder availability. δ/later.
3. **[A] Default `OrientationPolicy` — `Auto`** for new users, *conditional* on the orientation control being visible at session-setup (else fall back `PortraitLock`); **migrated** legacy `Portrait`→`PortraitLock` (legacy-flagged), `Landscape`→`LandscapeLock`. `Auto` must define a deterministic first-sample fallback (last-effective→snapped-display→portrait), a PR-α deliverable. The migration table below is corrected accordingly.
4. **[B] Sequencing — `PR-α → PR-β → PR-γ → PR-δ`** (β before γ). β must be **slot-based** (re-place landscape *regions*, not hardcode one mode chip) so γ's orientation picker drops into a slot; γ must prove picker placement in **both** orientations on device + ship the mixed-rotation merge contract (merged output adopts the first segment's rotation).

---

## 9. Out of scope / non-goals

- Pause/resume across the orientation change (DualShot pause/resume is already deferred per ADR-0009).
- Re-rotating already-recorded segments at merge time (the muxer concat path can't; mixed-rotation `Auto` sessions adopt one rotation — §6 PR-γ export).
- Per-segment **different topology** within one session (topology is session-fixed; only orientation can vary mid-session under `Auto`).
- Presets gaining a topology/orientation field — ADR-0026 orthogonality is preserved; revisit only with explicit owner sign-off.

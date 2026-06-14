# DualSight (FrontBack PiP) — Design Spec

> PR-δ — the last unbuilt phase of ADR-0029 (capture-topology × orientation-policy).
> Concurrent **front + back** camera capture composited into a **single-file
> picture-in-picture** clip. Surfaced as the **DualSight** capture mode (third tab
> alongside Auto/Single and DualShot).
>
> **Status:** Design approved by owner 2026-06-14. Next: implementation plan
> (`writing-plans`). **No code until the plan is approved.**
> **Date:** 2026-06-14. **Base:** master `bd6aa0c` (#114), zero open PRs.

---

## 1. Context & problem

`CaptureTopology.FrontBack` exists today only as an enum value
(`data/CaptureTopology.kt:12`), a hidden mode tab
(`ui/screens/CaptureModes.kt` — `CaptureMode.DualSight`, gated off by
`DUALSIGHT_ENABLED = false`), a `RovaSettings.captureTopology` axis, and the
`checkFrontBackCapabilityGated` build gate (`app/build.gradle.kts` ~L1878). There
is **no concurrent-camera implementation** under `service/`.

DualSight is distinct from **DualShot** (`service/dualrecord/`, ADR-0009): DualShot
is **one** sensor split into two 27/64 crops via an EGL `CameraEffect` fan-out.
DualSight is **two physical sensors** bound concurrently and composited into one
PiP frame — a fundamentally different seam (two CameraX bindings, not one effect).

ADR-0029 §5 + Ratified-C already fix the shape: single-file PiP (rear full-frame +
front inset) via the **CameraX-native concurrent-camera composition**
(`CompositionSettings`), **not** a hand-rolled EGL blend, **not** two files;
hardware-capability-gated; a **single composed encoder** (avoids the two-encoder
denial race); layout persisted as session metadata.

## 2. Feasibility verdict (research 2026-06-14, codex-confirmed)

**Yes-with-caveats — blocked on a CameraX dependency bump.**

- `CompositionSettings` and the composition `SingleCameraConfig(selector,
  compositionSettings, useCaseGroup, lifecycleOwner)` overload were added in
  **CameraX 1.5.0**; robust concurrent `VideoCapture` + composition-mode
  `CameraEffect` landed in **1.5.1**. The project is pinned at **1.4.2**, which has
  only the 1.3-era concurrent API (dual *Preview*, ≤2 use cases, **no
  composition**). **1.4.2 cannot produce the composited single-file PiP.** →
  PR-δ requires a CameraX bump first (no standing CameraX pin, unlike Media3; still
  a toolchain change touching live Single + DualShot capture). **Target the latest
  stable, not 1.5.1 specifically** — as of 2026-06 that is **1.5.3** (1.5.2 fixed a
  dynamic-range crash on Android 17+, relevant at targetSdk 37); δ0 evaluates the
  current stable with the same regression smoke. "1.5.x" below means ≥1.5.1, prefer
  latest stable.
- Reference implementation: `google/jetpack-camera-app`'s `ConcurrentCameraSession.kt`.
- **Capability gate (the real arbiter):** `FEATURE_CAMERA_CONCURRENT` is necessary
  but **not sufficient**; you must also find a FRONT+BACK pair in
  `ProcessCameraProvider.availableConcurrentCameraInfos` (empty if no combo is
  bindable). Camera2 `CameraManager.getConcurrentCameraIds()` is the platform-truth
  cross-check. Never a static device allowlist.
- **PiP inset position is bind-time only.** `CompositionSettings` is immutable
  (Builder→build), consumed only by `SingleCameraConfig` at `bindToLifecycle`.
  No live setter on `Camera`/`CameraControl`/`ConcurrentCamera`/`VideoCapture`.
  Reposition ⇒ rebind ⇒ stop/restart recording. Offset/scale/alpha are normalized
  device coords (center origin, offset applied **after** scale): `setAlpha(0..1)`,
  `setOffset(-1..1,-1..1)`, `setScale(sx,sy)`.
- **Device reality:** ~20–30% of install base, flagship/recent-tier concentrated
  (Samsung S21+ "Director's View"/"Dual Recording"; Pixel 6/7/8/9 HW-capable).
  Budget tier (Galaxy A15/A17) lacks it (single-pipeline ISP). The owner's smoke
  device **RZCYA1VBQ2H is A17-class** → expected to fail the concurrent-video probe.
  Logical multi-camera (API 28) fuses **same-direction** sensors only and throws
  `ERROR_MAX_CAMERAS_IN_USE` for front+back; concurrent streaming (API 30+,
  `getConcurrentCameraIds`) is the **only** front+back path.
- **Hard constraints:** concurrent streams guaranteed only to **s720p**; FPS floor
  30; Samsung caps dual at FHD30. Encoder denial (`ERROR_INSUFFICIENT_RESOURCE` /
  `ERROR_RECLAIMED`) is expected, not exceptional — the **single composited
  encoder** sidesteps the second-HW-instance race entirely. Sustained dual capture
  is worst-case thermal load.

Sources: developer.android.com/reference/androidx/camera/core/CompositionSettings ·
android-developers.googleblog.com/2024/10/camerax-update-makes-dual-concurrent-camera-easier.html ·
github.com/google/jetpack-camera-app (ConcurrentCameraSession.kt) ·
source.android.com/docs/core/camera/concurrent-streaming ·
strv.com/blog/can-we-use-the-front-back-cameras-at-the-same-time-on-android-engineering

**BeReal is not evidence for our case:** it captures *photos* via fast **sequential**
capture, not two simultaneous video streams. It proves quick lens switching, not
concurrent front+back **video** (the ISP-bandwidth-bound thing DualSight needs).
Capability is determined **empirically** by the δ-probe, not assumed.

## 3. Approach — three independently shippable phases

### Phase δ-probe (throwaway branch) — empirical capability investigation

**Goal:** prove or disprove concurrent front+back **video recording** on real
hardware before any implementation assumptions. Preview success ≠ recording
success, so the probe attempts the **actual recording workload**, not just queries.

The probe branch may bump CameraX to 1.5.1 **locally** (throwaway — does not couple
to the mergeable δ0 bump). It runs and logs:

1. **Capability queries** — `FEATURE_CAMERA_CONCURRENT`; CameraX
   `availableConcurrentCameraInfos` front+back pair; Camera2
   `getConcurrentCameraIds()` / `concurrentCameraAccessMap` (platform truth, to
   distinguish "CameraX is conservative" from "hardware can't").
2. **Real-workload attempt A (CameraX latest-stable, intended pipeline):** bind
   front+back concurrently with the **exact intended use cases** — one shared
   `UseCaseGroup` (Preview + one `VideoCapture` + `CompositionSettings` PiP), the
   real quality selector, audio enabled — and **record ~5 s**; verify the MP4 is
   valid and visibly composited (rear full + front inset). **Capability queries +
   bindability are not enough — the exact Preview+VideoCapture composition workload
   must run** (a combo existing ≠ this pipeline surviving).
   **Also probe mirroring explicitly:** point the front lens at asymmetric/text
   content and confirm the inset is mirrored correctly (front-as-secondary under
   composition; CameraX docs warn mirror/rotation apply *post-composition* and
   `VideoCapture.mirrorMode` may be **ignored when a CameraEffect is set** — so the
   `MIRROR_MODE_ON_FRONT_ONLY` assumption must be visually verified, not assumed).
   **Repeated-rebind torture:** bind→record→unbind→rebind in a loop (mimicking the
   segment boundary) many times over several minutes to surface mid-session
   concurrent-bind failures under accumulated heat/load.
3. **Real-workload attempt B (raw Camera2, version-independent):** open a concurrent
   session from `getConcurrentCameraIds` with two recording-capable surfaces and
   **record ~5 s** — hardware-truth, independent of CameraX's conservatism.
4. **Diagnostics:** per-camera stream-config / `SCALER` dump; `camera service`
   logcat during the attempt; the exact failure code on any failure
   (`ERROR_MAX_CAMERAS_IN_USE`, `ERROR_INSUFFICIENT_RESOURCE`, `openCamera` fail).
5. **Verdict line** via `RovaLog`. Run on RZCYA1VBQ2H:
   `adb -s RZCYA1VBQ2H logcat -s Rova:I` (filter `DualSightProbe`).

**Decision from evidence:**
- **PASS** (a real-workload recording succeeds on either path) → DualSight is
  device-smokeable locally; proceed.
- **FAIL** on both paths after exhausting Camera2 → the limitation is real on this
  handset; gated-disabled UX is honest, and DualSight-capture device-smoke moves to
  borrowed/capable hardware (S21+/Pixel 7+) before merge.

### Phase δ0 (separate PR) — CameraX 1.4.2 → latest-stable (1.5.x) bump

Update `gradle/libs.versions.toml`, fix API breaks across Single + DualShot capture,
**full regression device-smoke** of existing capture on RZCYA1VBQ2H (which can test
those). Merge before any DualSight feature code lands. The 1.5.x line touches
`VideoCapture`, resolution selection, mirror mode, target rotation, selected-quality
reporting, effect/StreamSharing and concurrent paths — so the smoke is **behavioral,
not just compile-clean**. Explicit regression checklist:

- Single rear: actual file record + playback; orientation metadata correct in all
  rotations; selected quality at low **and** high; segment finalization + merge.
- Front (LensFlip): front mirror still as expected.
- DualShot (P+L): still records both crops correctly; rotation contract (§4) intact.
- No change to existing manifest round-trips or recovery classification.

### Phase δ (PR-δ) — DualSight build

On the stable 1.5.1 base. Device-smoke on whatever hardware δ-probe proved capable.

## 4. Design — DualSight (PR-δ)

### 4.1 Module & components — new `service/dualsight/`

Parallels `service/dualrecord/` but with **two bindings, one encoder** (not one
binding split into two crops).

- **`ConcurrentCameraCapability`** — capability seam. Pure-Kotlin core takes the
  `availableConcurrentCameraInfos` combo list (+ feature flag) and answers "is a
  front+back pair bindable?"; thin wrapper reads the provider/`PackageManager`.
  JVM-tested over fake combo lists.
- **`DualSightSession`** — builds two `ConcurrentCamera.SingleCameraConfig` (rear
  primary + front secondary), each with a `CompositionSettings` (rear full-frame;
  front inset offset/scale/alpha from `PipInsetPlacement`), sharing **one**
  `UseCaseGroup` (Preview + one `VideoCapture`) → `bindToLifecycle(listOf(primary,
  secondary))`. One encoder, one MP4. Front uses `MIRROR_MODE_ON_FRONT_ONLY`.
- **`PipInsetPlacement`** — **pure helper (JVM-tested):** maps a screen position to
  a clamped normalized `CompositionSettings` offset (center origin, −1..1, applied
  after scale); owns the default corner and the bounds clamp (inset never leaves
  frame). Holds the inset scale (fraction of frame).
- **`RovaRecordingService`** stays the **sole** CameraX owner; DualSight is bound
  through it like DualShot, honoring the STOP-path / `performMerge` invariants and
  the gates that pin them (`checkStopNoGetService`, `checkUserStoppedBeforeMerge`,
  `checkCompletedWriteOnlyFromPerformMerge`, `checkSetTargetRotationBoundaryOnly`).

### 4.2 Rotation & orientation

DualSight is **one** `VideoCapture`, so it uses ADR-0029 **§3** (single-VideoCapture
`setTargetRotation` applied at segment boundary) and composes with `OrientationPolicy`
exactly like `Single` — **not** DualShot's §4 EGL/metadata path. One rotation for the
whole composited frame. Reuses the PR-α/γ `OrientationSnap` / `OrientationPolicyResolver`
/ `effectiveTargetRotation` seam unchanged.

**Segment-concat invariant (codex [HIGH]).** Rebind at a boundary can change selected
quality / resolution / rotation metadata, and `MediaMuxer` concat assumes an
**identical track format** across segments. DualSight must **lock** the concurrent
quality/resolution at session start and **verify** each new segment's track format
(codec, dimensions, fps, rotation, audio format, color/profile) matches before
appending. A mismatch is fail-closed: finalize what exists, do not silently append a
divergent segment. (Single/DualShot rely on stable single-camera selection; DualSight's
concurrent selection is more volatile, so this check is mandatory here.)

**Per-segment rebind risk (codex [HIGH]).** Concurrent bind reopens **two** cameras +
shared `VideoCapture` every boundary; resource arbitration can fail after minutes of
heat even when the first bind succeeded. Mitigations: (a) the δ-probe repeated-rebind
torture test must pass before δ proceeds; (b) **fail closed** on a mid-session bind
failure (see §4.4), never silently degrade; (c) if torture testing shows churn is the
problem, revisit whether DualSight can hold the concurrent binding across boundaries
(only rebinding for a real rotation change) — a plan-time decision gated on probe data.

### 4.3 PiP inset & draggable UI (v1 simplified)

- On-screen preview: rear `Preview` `fillMaxSize` + front `Preview` in a draggable
  Compose Box (`pointerInput`/`detectDragGestures`), clamped by `PipInsetPlacement`.
- **v1 interaction:** the inset is draggable **only while idle (before Start)**.
  Once recording starts, position is **fixed for the entire session**. (Rationale:
  reposition requires a rebind = stop/restart; segment-boundary reposition is
  deferred until DualSight is proven stable. Per-clip position metadata avoided.)
- The chosen inset (corner/offset + scale) bakes into `CompositionSettings` at the
  first bind and is **persisted session-level** in the manifest (schema 11→12, one
  `pipInset` field) for crash-resume and future export-time re-render (split /
  side-by-side become later export/playback re-renders per ADR Ratified-C).
- **Mirroring caveat (codex [HIGH]):** `MIRROR_MODE_ON_FRONT_ONLY` is the intent for
  the front inset, but CameraX applies mirror/rotation **post-composition** and may
  **ignore `VideoCapture.mirrorMode` when a CameraEffect is set** under concurrent
  composition. The δ-probe must visually confirm correct front-inset mirroring; if
  the no-effect composition path doesn't honor it, the plan picks an alternative
  (e.g. composition-level mirror or accept-unmirrored) — do not assume it works.

### 4.4 Error handling & fallback

- **Pre-record** bind failure (`ERROR_INSUFFICIENT_RESOURCE` / `ERROR_RECLAIMED` /
  `openCamera` fail) → fall back to **Single rear** (reuse `LensFlipPolicy` bind-fail
  precedent) + explainer. This is the only place silent fallback is allowed.
- **Mid-session** DualSight bind/start failure (codex [MED]) → do **NOT** silently
  continue as Single in the same file (that yields a mixed-topology output). Instead
  **finalize/stop** the current session with recovery messaging; the manifest records
  the DualSight topology truthfully. Topology never changes within one final file.
- Unsupported device (no front+back pair / feature absent) → DualSight tab
  **disabled + explainer** (replace the static `DUALSIGHT_ENABLED` flag with the
  capability-driven check from `ConcurrentCameraCapability`).
- Recovery scan treats a DualSight session like any other (single output file).

### 4.5 Thermal & telemetry

Reuse the existing ADR-0016 thermal autostop + `ThermalHysteresis` / `SegmentGateThermal`
for the **first** DualSight cut — **no DualSight-specific threshold** yet — and add
**DualSight telemetry** (`RovaLog` lines: thermal level, encoder/codec errors,
camera-resource events) + sustained-use validation to gather data before tightening.
**Codex [MED]: reuse is acceptable for δ, not launch-final** — concurrent dual capture
is a hotter envelope; single-camera thresholds may fire too late (camera reclaim /
corrupted boundary / OS shutdown before graceful autostop). The plan should include a
**conservative guard** (telemetry now + a candidate shorter max-duration or earlier
soft-warning for DualSight) and a follow-up to set data-driven thresholds before
treating DualSight as launch-quality. Treat `ERROR_RECLAIMED` /
`ERROR_INSUFFICIENT_RESOURCE` as recoverable, never crashes.

### 4.6 Audio & deferrals

- **Audio:** one track from the default mic (single file, single encoder).
- **Pause/resume:** deferred (DualShot precedent).
- **Quality:** 720p composited floor / FHD30 cap — DualSight does not promise
  single-camera quality; the quality picker reflects the concurrent ceiling.

### 4.7 Manifest schema

`SessionManifest` 11 → 12: add `pipInset` (session-level inset position + scale;
default corner). `toJson`/`fromJson` updated with fallbacks; legacy read-compat
preserved. `captureTopology="FrontBack"` already round-trips (PR-γ).
**Codex [LOW]:** the integer bump is low-risk *iff* additive — schema-11 manifests
**without** `pipInset` MUST parse with a default (never crash); and a partial/crashed
DualSight manifest must recover **as DualSight topology**, not be misread as
Single/DualShot. Both are explicit schema-12 round-trip test cases.

## 5. Gates & ADR

- **Extend** `checkFrontBackCapabilityGated` allow-set with `service/dualsight/`
  (sanctioned by the gate's own KDoc — "PR-δ extends the allowlist with the
  concurrent-camera module it builds"). **Pair with an ADR-0029 §5 amendment**
  recording the new module + the 1.5.1 dependency + the §6 capture clauses (today
  §5 only fences `FrontBack`).
- **Candidate new gate (decide in plan):** pin DualSight to the single-encoder /
  `CompositionSettings` path (forbid a second `MediaCodec`/`VideoCapture` in
  `service/dualsight/`), so the encoder-denial-avoidance invariant survives.
- Existing invariants unchanged: `checkSetTargetRotationBoundaryOnly` already allows
  `RovaRecordingService.kt` / `service/dualrecord/`; confirm whether `service/dualsight/`
  needs adding to its allowlist (it drives `setTargetRotation` via §3).
- `checkPresetNoOrientation` unaffected (DualSight is a topology, never a preset field).

## 6. Testing (JVM-only, lands in PR-δ)

- `PipInsetPlacementTest` — clamp, normalized-offset math, default corner, scale.
  **Explicitly pin the CameraX NDC conversion (codex [LOW]):** composition offset is
  **center-origin, Y-up, offset applied after scale**; UI drag coords are top-left,
  Y-down — easy to invert. Test the round-trip for each corner + out-of-bounds clamp.
- Segment-concat format-lock guard — matching segment appends, mismatched segment
  fails closed (verify codec/dims/fps/rotation/audio comparison logic).
- `ConcurrentCameraCapabilityTest` — front+back pair detection over fake combo
  lists; feature-flag false; no-pair; front-only / back-only.
- `SessionManifest` schema-12 round-trip (`pipInset` persist/restore + legacy
  read-compat).
- `DualSightDragGatePolicy` — drag allowed only while not recording (v1).
- Framework-touching binders (`DualSightSession`, provider wrappers) stay thin
  seams; pure logic extracted per the house pure-helper pattern.

## 7. Out of scope (v1)

- Segment-boundary / mid-session inset reposition (deferred; v1 fixes position at
  Start).
- Split / side-by-side capture-time layouts (export/playback re-render per
  Ratified-C; corner *position* before Start is the only capture-time layout choice).
- Two-file (unjoined) output.
- Pause/resume.
- DualShot rotation contract changes (§4 untouched).
- Advanced-lock UI (ADR-0029 decision D, separate P3 item).

## 8. Open items for the plan

- Exact `pipInset` serialization shape (corner enum + scale, vs raw normalized
  offset) — settle in plan after `PipInsetPlacement` math is fixed.
- Whether `service/dualsight/` joins the `checkSetTargetRotationBoundaryOnly`
  allowlist (depends on where the §3 call lands).
- Confirm whether `service/dualrecord/` drives one or two `MediaCodec` encoders
  (audit note from research) — informs the single-encoder gate wording.
- Quality-picker copy/values for the concurrent 720p/FHD30 ceiling.
- δ-probe result (PASS/FAIL on RZCYA1VBQ2H) determines the device-smoke venue.
- **Hold-binding-vs-rebind across segment boundaries** — gated on δ-probe torture
  data. If per-segment concurrent rebind is flaky, decide whether DualSight holds
  the concurrent binding across boundaries (rebinding only on a real rotation change).
- **DualSight thermal guard shape** — telemetry-only for δ vs a conservative
  shorter-max-duration / earlier-warning; data-driven thresholds are a follow-up.
- Mirroring path resolution if `MIRROR_MODE_ON_FRONT_ONLY` isn't honored under
  composition (composition-level mirror vs accept-unmirrored).

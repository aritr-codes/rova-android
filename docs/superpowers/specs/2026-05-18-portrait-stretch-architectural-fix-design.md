# PORTRAIT-stretch architectural fix — DualShot 4:3-source design

**Date**: 2026-05-18
**Status**: Brainstorm-approved, awaiting implementation plan
**Branch (proposed)**: `feat/dualshot-4-3-source-aspect`
**Base**: `master @ ba252c3` (Phase 6.1c merge tip)
**Predecessor**: Phase 6.1c (PR #24, squash-merged @ `ba252c3`) — carried over the PORTRAIT-stretch architectural ticket
**Reference architecture**: iOS DualShot Recorder (March 2026) + Android `com.dual.shot` (May 2026)

---

## §0 — Context

### What's wrong (carried from 6.1c)

Phase 6.1c shipped true per-side rendering for the Portrait+Landscape (P+L) capture mode. Three smoke-fix rounds resolved orientation correctness on both sides. One architectural defect was knowingly parked: the **PORTRAIT zone is stretched 1.78× vertically.**

Root cause:

- `RovaRecordingService.setupDualCamera()` forces `ResolutionSelector` to **`Size(1920, 1080)` (16:9)** to bypass CameraX's default ~44% center-crop FOV loss.
- `AspectFitMath.buildSideAspectCrop(PORTRAIT) = pivot-scale(9/16, 1, 1)` samples a centered 9/16-width × full-height region from the 16:9 source = **1080×1080 (1:1 square)**.
- That square gets packed into the 1080×1920 (9:16) PORTRAIT encoder = **1.78× vertical stretch**.

The bug is not in the matrix — `pivot-scale(9/16, 1, 1)` does exactly what it claims. The bug is in the **choice of source aspect**: forcing 16:9 capture amputates the vertical FOV before per-side crop math runs, leaving no pixels left to crop into a 9:16 strip.

### Why now

- 6.1c left two candidate fixes on the table: (a) `EglRouter` letterbox via `contentAspect=1f`, or (b) `sideAspectCrop(PORTRAIT)` redesign. Brainstorm round (2026-05-18) surfaced a third path that supersedes both: **change the source aspect to match the iOS DualShot reference architecture.**
- Owner provided a competitive-architecture brief covering iOS DualShot Recorder and the new `com.dual.shot` Android app. The brief's "Fun Fact" section spells out the iOS pattern: capture at native 4:3 (e.g., 4000×3000), then per-side crop on the long edge of each output ratio. Both sides full-FOV. Neither side stretched. No bars.
- The 6.1c plumbing (single CameraX session → `DualSurfaceProcessor` → `EglRouter` 4-target fan-out) is source-aspect-agnostic by design. Source aspect lives in exactly one place: the matrix constants inside `AspectFitMath.buildSideAspectCrop`. So adopting the iOS pattern is a **localized swap** on top of proven plumbing, not a rewrite.

### Owner-confirmed design gates (2026-05-18 brainstorm)

| Gate | Choice |
| --- | --- |
| Direction | **4:3 native sensor source + per-side crop** (iOS DualShot pattern) |
| HW fallback policy | **Quality fallback** — pick largest available 4:3 mode even if < 1920×1440; PORTRAIT upscales softly |
| Test policy | **Re-derive 16 existing `AspectFitMathTest` matrices in place** (same count, new constants) |
| Performance check | **Smoke-only on Samsung SM-A176B (A17)** — frame-drop / thermal-throttle contingency pre-flagged |

---

## §1 — Architecture

### Current (master @ `ba252c3`)

```
CameraX session (setupDualCamera)
  └── Preview.Builder
       └── ResolutionSelector(Size(1920, 1080), 16:9 FIXED)
            │
            ▼
       Source SurfaceTexture (16:9, 1920×1080)
            │
            ▼
       DualSurfaceProcessor → EglRouter (4 targets)
            │
            ▼
       AspectFitMath.buildSideAspectCrop
         PORTRAIT  = pivot-scale(9/16, 1, 1)   ← samples 1080×1080 square (stretched 1.78× into 9:16 encoder)
         LANDSCAPE = identity                  ← samples 1920×1080 (pixel-perfect into 1920×1080 encoder)
```

### New (proposed)

```
CameraX session (setupDualCamera)
  └── Preview.Builder
       └── ResolutionSelector(targetSize = resolveDualCameraSize(streamConfigMap))
            ├── 1st: largest available 4:3 ≥ 1920×1440           ← e.g. A17 → 2560×1920 or 4032×3024
            ├── 2nd: largest available 4:3 (any size)            ← weak-device fallback (PORTRAIT upscales)
            └── 3rd: Size(1920, 1440) hint                       ← no-4:3 last resort
                 │
                 ▼
            Source SurfaceTexture (4:3, e.g. 2560×1920)
                 │
                 ▼
       DualSurfaceProcessor → EglRouter (4 targets)              ← UNCHANGED, aspect-agnostic
                 │
                 ▼
       AspectFitMath.buildSideAspectCrop                         ← CHANGE (constants only)
         PORTRAIT  = pivot-scale(27/64, 1, 1)                    ← samples ~42% of width, full height → 9:16 strip, no stretch
         LANDSCAPE = pivot-scale(1, 3/4, 1)                      ← samples full width, top+bottom crop → 16:9 strip
```

### Matrix derivation

For a source aspect `S = sourceWidth / sourceHeight = 4/3` and a target aspect `T = targetWidth / targetHeight`:

- **PORTRAIT** (`T = 9/16`, narrower than source): crop horizontally → `pivot-scale(T / S, 1, 1) = pivot-scale((9/16) / (4/3), 1, 1) = pivot-scale(27/64, 1, 1)`.
- **LANDSCAPE** (`T = 16/9`, wider than source): crop vertically → `pivot-scale(1, S / T, 1) = pivot-scale(1, (4/3) / (16/9), 1) = pivot-scale(1, 3/4, 1)`.

Both crops anchor on the source center and on the long edge of their respective output ratio. Both fill their encoders edge-to-edge.

### Tradeoff accepted

LANDSCAPE side is no longer source-pixel-perfect:

- Source 2560×1920 → LANDSCAPE crop = 2560×1440 → 1920×1080 encoder = **1.33× downscale**.
- 1.33× is mild and matches what the iOS DualShot pattern accepts. The alternative (keep LANDSCAPE 16:9 source AND add a separate 4:3 PORTRAIT session) would break the 6.1c single-source GL fan-out, double encoder/HAL load, and may exceed the device's max simultaneous stream count (the brief flags 2–3 streams as a common cap and Samsung A17 specifically as "mid-range, partial/limited multi-camera"). **Rejected.**

### Hard invariants preserved (full master → branch HEAD)

Byte-identical to `master @ ba252c3`:

- `setupSingleCamera` (Portrait standalone, Landscape standalone) — uses its own CameraX defaults; never touches `AspectFitMath`.
- `DualSurfaceProcessor` / `EglRouter` / `DualVideoRecorder` / `DualPreviewZone` / `RecordScreen` — GL plumbing is source-aspect-agnostic by 6.1c design.
- `WarningId` / `WarningPrecedence` / `WarningCenterViewModel` / Start-gate at `RecordScreen.kt:107-122` / `flipCamera()` / `setMode()` / `VideoMerger.kt` / single-mode encoder paths / build files / manifest schema / `RotationCalculator.tag()`.
- `AspectFitMath.buildDisplayRotationCorrection` and `buildCropMatrix` composition order — orientation math is independent of source aspect.

---

## §2 — Components

### File-by-file change manifest

| File | Change | Why |
| --- | --- | --- |
| `app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt` — `setupDualCamera()` `ResolutionSelector` block | Swap forced `Size(1920, 1080)` (16:9) for `resolveDualCameraSize(streamConfigMap)` call. Pass `Surface.ROTATION_0` unchanged. Update inline KDoc to point at ADR-0009. | Single source-aspect change point; `setupSingleCamera` untouched. |
| `app/src/main/java/com/aritr/rova/service/dualrecord/internal/DualCameraSizeResolver.kt` (new) | Top-level `internal object` (or `internal fun` if cleaner) with `resolveDualCameraSizeFrom(sizes: List<Size>): Size` (pure, JVM-testable) + a thin wrapper `resolveDualCameraSize(map: StreamConfigurationMap): Size` that extracts sizes from the map and delegates. Add `private const val PIXEL_PERFECT_SHORT_EDGE = 1920` + `private const val FALLBACK_HINT = Size(1920, 1440)`. | Pure-helper seam — same pattern as the 4.1b `StorageSignal.estimateInsufficient` extract; JVM tests exercise the filter logic without `StreamConfigurationMap` (which requires Android runtime). |
| `app/src/main/java/com/aritr/rova/service/dualrecord/internal/AspectFitMath.kt` — `buildSideAspectCrop(side, out)` | Re-derive constants: PORTRAIT → `pivot-scale(27/64, 1, 1)`; LANDSCAPE → `pivot-scale(1, 3/4, 1)`. Add file-level `internal const val SOURCE_ASPECT_W = 4f` + `internal const val SOURCE_ASPECT_H = 3f` so the matrix derivation is named once. Revise KDoc: "Assumes 4:3 source aspect (per ADR-0009). If source aspect changes, both matrices must be re-derived." | The math seam. Constants only — no signature change, no new function. |
| `app/src/main/java/com/aritr/rova/service/dualrecord/internal/AspectFitMath.kt` — `buildCropMatrix(displayRotation, side, out)` | KDoc augment: "source aspect = 4:3, pinned by `setupDualCamera`'s `ResolutionSelector`." No code change. | Composition logic unchanged; doc-discoverability only. |
| `app/src/test/java/com/aritr/rova/service/dualrecord/internal/AspectFitMathTest.kt` — all 16 tests | Re-derive expected matrix values for 4:3 source. Mirror impl constants at file top (`private const val EXPECTED_SOURCE_ASPECT_W = 4f` / `…_H = 3f`). Replace one trivial PORTRAIT test + one trivial LANDSCAPE test with **regression-locks** that assert the exact matrix values (`pivot-scale(27/64, 1, 1)` and `pivot-scale(1, 3/4, 1)` respectively). | Test count unchanged at 16; pinned constants form a "deviation tripwire" against axis-swap or wrong-source-aspect drift. |
| `app/src/test/java/com/aritr/rova/service/dualrecord/internal/DualCameraSizeResolverTest.kt` (new) | 6 JVM tests for `resolveDualCameraSizeFrom(sizes: List<Size>): Size` — see §5 test matrix. No Robolectric. | Pure-helper coverage; protects the fallback chain from regression. |
| `docs/adr/0009-dualshot-4-3-source-aspect.md` (new) | **Accepted**-status ADR. Context (6.1c PORTRAIT-stretch + iOS DualShot reference), Decision (4:3 source + per-side crop), Consequences (LANDSCAPE 1.33× downscale, HW fallback chain, GPU pixel throughput ~2.4× baseline), Alternatives rejected (letterbox / rotated landscape / dual-capture-session). | Captures the architectural decision so it's not buried in a smoke-fix commit body. |
| `docs/architecture.md` | If the file currently documents 6.1c's forced 1920×1080 ResolutionSelector choice, amend to reference ADR-0009. Otherwise no-op. | Doc hygiene — same precedent as the BatteryOptimizationBanner cleanup in 4.1b. |

### Footprint summary

- **2 src files modified** (`RovaRecordingService.kt`, `AspectFitMath.kt`)
- **1 src file new** (`DualCameraSizeResolver.kt`)
- **1 test file modified** (`AspectFitMathTest.kt`)
- **1 test file new** (`DualCameraSizeResolverTest.kt`)
- **1 new ADR** (`0009-dualshot-4-3-source-aspect.md`)
- **1 doc-hygiene amendment** (`docs/architecture.md`, conditional)
- Total: **~5–6 files.**

`EglRouter`, `DualSurfaceProcessor`, `DualVideoRecorder`, `DualPreviewZone`, `RecordScreen`, `RecordViewModel`, all of `ui/warnings/**`, all single-mode pipelines, all build files, all manifest schemas = **zero diff**.

---

## §3 — Data flow

```
sensor → CameraX session (4:3 mode, e.g. 2560×1920)
  ↓
SurfaceTexture (4:3 source)
  ↓
DualSurfaceProcessor.onInputSurface(surfaceRequest)            ← unchanged
  ↓
EglRouter.renderFrame (4 targets, per-frame iteration)         ← unchanged plumbing
  ├── target = PORTRAIT preview  → glViewport(zone), draw with cropMatrix(dispRot, PORTRAIT)
  ├── target = LANDSCAPE preview → glViewport(zone), draw with cropMatrix(dispRot, LANDSCAPE)
  ├── target = PORTRAIT encoder  → glViewport(1080×1920), draw with cropMatrix(dispRot, PORTRAIT)
  └── target = LANDSCAPE encoder → glViewport(1920×1080), draw with cropMatrix(dispRot, LANDSCAPE)
                          ↓
                  AspectFitMath.buildCropMatrix
                    = buildSideAspectCrop(side) × buildDisplayRotationCorrection(dispRot)
                                ↓
                  buildSideAspectCrop                          ← CHANGE (constants)
                    PORTRAIT  → pivot-scale(27/64, 1, 1)
                    LANDSCAPE → pivot-scale(1, 3/4, 1)
```

### Frame-by-frame contract

1. Sensor delivers a 4:3 frame at the chosen size (e.g., 2560×1920) into the source SurfaceTexture.
2. `EglRouter.renderFrame` iterates the 4 attached targets; for each, calls `glViewport` to its target size and draws with the target's pre-computed crop matrix as `uTexMatrix` (composed at attach-time with the camera-supplied `texMatrix` and `mirrorMatrix`).
3. PORTRAIT-side targets sample a `(27/64 × sourceWidth) × sourceHeight` vertical strip from the source center → fills the 1080×1920 encoder & 9:16 preview slot edge-to-edge with no stretch and no bars.
4. LANDSCAPE-side targets sample a `sourceWidth × (3/4 × sourceHeight)` horizontal strip from the source center → downscales to 1920×1080 encoder & fills the 16:9 preview slot edge-to-edge.
5. `DualPreviewZone`'s two `TextureView` surfaces render in real time — on-device smoke shows the corrected aspect immediately (same feedback loop as 6.1c smoke-fix rounds).

### Threading note

The 4 crop matrices are computed **once at target-attach time** inside `EglRouter.addTarget`, not per-frame. The matrix-constant change has **zero per-frame CPU cost** vs. baseline. The existing `synchronized(targets)` and `synchronized(pendingPreviewSurfaces)` thread-safety wrappers (added in 6.1c review-fix rounds) are unchanged — the source-aspect swap introduces no new race surface.

---

## §4 — Error handling

### HW capability fallback chain (inside `resolveDualCameraSizeFrom`)

```
input: List<Size> (from StreamConfigurationMap.getOutputSizes(SurfaceTexture::class.java))
  ↓
filter to aspect-4:3 modes (size.width * 3 == size.height * 4, ± 1 px rounding tolerance)
  ↓
  ├── any with shortEdge ≥ 1920?
  │     ↓ yes
  │   pick MAX by total pixel count (e.g., 4032×3024 wins over 2560×1920)
  │     ↓
  │   RovaLog.d("dual src = 4:3 ${w}×${h} (pixel-perfect PORTRAIT)")
  │
  ├── any 4:3 mode at all?
  │     ↓ yes (all below 1920 short edge)
  │   pick MAX
  │     ↓
  │   RovaLog.w("dual src = 4:3 ${w}×${h} (sub-1920 short edge; PORTRAIT upscales)")
  │
  └── no 4:3 modes at all (rare; ≤ ~1% of devices)
        ↓
      return Size(1920, 1440)   // hint to CameraX; let ResolutionSelector pick closest
        ↓
      RovaLog.w("dual src = no 4:3 mode; hinting 1920×1440")
```

### Failure modes & responses

| Failure | Detection | Response |
| --- | --- | --- |
| Device exposes no 4:3 modes at all | `resolveDualCameraSizeFrom` finds zero matches | Hint `Size(1920, 1440)` to CameraX; let `ResolutionSelector` pick its closest. CameraX may land non-4:3, which means PORTRAIT crop will subtly under-fill or over-crop. **Log at WARN.** Smoke-fix follow-up if it surfaces on a real device. Acceptable risk given the ≤ 1% device share. |
| GL pipeline can't keep up with 2.4× pixel throughput (mid-range thermal throttle) | Owner smoke on A17 — visible frame drops, encoder dropped-frame stats in logcat | **Pre-flagged round-2 contingency**: lower the helper's `PIXEL_PERFECT_SHORT_EDGE` from `1920` to `1440` (or replace the whole chain with `Size(1920, 1440)` fixed target). PORTRAIT upscales 1.33×; GL pixel throughput returns to ~6.1c baseline. |
| CameraX session-creation throws (e.g., requested size > sensor max) | `CameraSelector.Builder` throws / session callback errors | Existing `RovaRecordingService` camera-error path handles this and surfaces via `cameraStateSignal`. No new code; the existing `CAMERA_BIND_ERROR` warning row covers it. |
| Matrix re-derivation has an off-by-one or axis-swap | Pinned `AspectFitMathTest` constant-assertions (see §5) | Compile/test gate catches before smoke. |

### Owner re-smoke verification (6.1c-style)

| Zone | Expected | Indicator if wrong |
| --- | --- | --- |
| PORTRAIT | Fills 9:16 zone edge-to-edge; mouse/text/text-rows natural aspect ratio; vertical FOV ≈ full sensor height | Black bars top+bottom (letterbox crept in) · still stretched (constant didn't apply) · narrower-than-expected (4:3 source rejected, fell back to 16:9) |
| LANDSCAPE | Fills 16:9 zone edge-to-edge; natural aspect; horizontal FOV ≈ full sensor width | Black bars left+right (over-cropped) · vertically squeezed (constant inverted) · perceptibly softer than 6.1c smoke-3 (expected/acceptable — 1.33× downscale artifact) |

### What's intentionally NOT a feature flag

The whole helper is ~15 LOC + 6 tests. Gating the 4:3-source-vs-16:9 toggle behind a debug flag would just add cleanup debt later (same calculus as 4.1b's `StorageSignal` seam). If smoke fails, we iterate the helper threshold inside the same PR; we don't ship a permanent escape hatch.

---

## §5 — Testing

### Unit tests (pure JVM, no Robolectric)

#### 1. `AspectFitMathTest.kt` — re-derive existing 16 tests in place

- Add file-level `private const val EXPECTED_SOURCE_ASPECT_W = 4f` + `…_H = 3f` mirroring impl constants. Use these inside any helper that derives expected matrices.
- All `buildSideAspectCrop` / `buildCropMatrix` expected-matrix values re-computed for 4:3 source.
- **Replace two trivial existing tests with pinned regression-locks**:
  - `buildSideAspectCrop_PORTRAIT_matches_4_3_to_9_16_constants` → asserts the matrix equals `pivot-scale(27/64, 1, 1)` exactly via JUnit 4's `assertArrayEquals(expected: FloatArray, actual: FloatArray, delta: Float)` (column-major mat4, 16 floats; tolerance per the project's existing convention in `AspectFitMathTest.kt`).
  - `buildSideAspectCrop_LANDSCAPE_matches_4_3_to_16_9_constants` → asserts the matrix equals `pivot-scale(1, 3/4, 1)` exactly via the same mechanism.
- **Test count stays at 16.**

#### 2. `DualCameraSizeResolverTest.kt` — new, 6 tests

Pure JVM; takes `List<Size>` not `StreamConfigurationMap`:

| # | Test | Input | Expected |
| --- | --- | --- | --- |
| 1 | `picks_largest_4_3_above_threshold` | `[2560×1920, 4032×3024, 1920×1080, 1280×720]` | `Size(4032, 3024)` |
| 2 | `picks_threshold_boundary_size` | `[1280×960, 1920×1440, 1024×768]` | `Size(1920, 1440)` |
| 3 | `falls_back_to_largest_4_3_when_all_below_threshold` | `[1280×960, 640×480]` | `Size(1280, 960)` |
| 4 | `falls_back_to_hint_when_no_4_3_modes` | `[1920×1080, 1280×720]` | `Size(1920, 1440)` |
| 5 | `tolerates_1px_aspect_rounding` | `[2561×1920]` | `Size(2561, 1920)` (counted as 4:3) |
| 6 | `ignores_portrait_orientation_modes` | `[1920×2560]` | filtered out — session is landscape-oriented (`Surface.ROTATION_0`) |

#### 3. No service-layer integration tests

`RovaRecordingService.setupDualCamera` is on-device only — matches 6.1c precedent. No Robolectric in the project. Service-layer correctness is verified by owner smoke.

### Gates (predicted vs. master baseline)

| Gate | Master baseline | Predicted post |
| --- | --- | --- |
| `:app:testDebugUnitTest` | 966 / 76 / 0-0-0 | **972 / 77 / 0-0-0** (+6 new `DualCameraSizeResolverTest`, +1 class; `AspectFitMathTest` count unchanged at 16, values re-derived) |
| `:app:lintDebug` | 53 (50 W + 3 H + 0 E) | **53 unchanged** (no new SDK-gated APIs; `StreamConfigurationMap` / `SurfaceTexture` / `Size` all API 21+ and already in use) |
| `:app:assembleDebug` | OK | OK |
| deprecation warnings | 1 (baseline carry) | 1 unchanged |

### Owner on-device smoke (Samsung SM-A176B, A17, Android 16)

- Same protocol as 6.1c rounds: open P+L mode → eyeball both zones → screenshot → compare against §4 expected table → post observed-vs-predicted as a PR comment.
- **Round 1 expectation**: PORTRAIT pixel-perfect (A17 modern sensor exposes multiple 4:3 modes; single-camera 4:3 is universal even on devices with limited multi-camera support). LANDSCAPE perceptibly softer than 6.1c smoke-3 (1.33× downscale artifact — expected and acceptable).
- **Round-2 contingency (pre-flagged in spec)**: if A17 thermal-throttles or drops frames on 2.4× pixel throughput → drop the helper threshold from "largest 4:3 ≥ 1920×1440" to "exactly Size(1920, 1440)"; PORTRAIT upscales 1.33×, GL pixel throughput returns to 6.1c baseline.

### Invariant preservation gate

`git diff master..HEAD --name-only` must contain **only** the files in §2's file-by-file table (plus spec + plan files under `docs/superpowers/`). Any file outside that allowlist appearing in the diff = scope creep, NO-GO.

---

## §6 — Out of scope (explicit non-goals)

- Touching `setupSingleCamera` (Portrait standalone, Landscape standalone) source-aspect choice. Single-mode pipelines remain byte-identical.
- Refactoring `EglRouter` / `DualSurfaceProcessor` / `DualVideoRecorder` / `DualPreviewZone` / `RecordScreen`. The 6.1c plumbing is correct as-is.
- A dual-capture-session architecture (PORTRAIT gets its own CameraX `VideoCapture` pinned to 4:3 while LANDSCAPE keeps current 16:9). Rejected — see §1 Tradeoff.
- Letterbox fallback path (PORTRAIT shows black bars top+bottom). Rejected — contradicts the no-bars iOS-reference framing.
- Rotated-landscape PORTRAIT encoding (encoder file is landscape content rotated 90°). Rejected — no competing app does this; awkward playback UX.
- A debug feature flag toggling 4:3-vs-16:9 source. Rejected — see §4 "What's intentionally NOT a feature flag."
- Backlog items 4.1c / 4.2 / 4.3 — owner-dispatched separately when this lands.

---

## §7 — References

- [ADR-0008 — Dual-recording architecture](../../adr/0008-dual-recording-architecture.md) — 6.1a foundation.
- [Phase 6.1c spec — DualShot render](2026-05-17-phase-6.1c-dualshot-render-design.md) — predecessor; ships the math seam this fix refines.
- [PR #24 — Phase 6.1c smoke-fix series](https://github.com/aritr-codes/rova-android/pull/24) — owner re-smoke at tip `5bbf543` confirmed PORTRAIT-stretch as the carry-over architectural defect; squash-merged @ `ba252c3`.
- Owner brief (2026-05-18 brainstorm) — iOS DualShot Recorder pattern + `com.dual.shot` Android architecture; introduced the 4:3-source insight.
- [docs/architecture.md](../../architecture.md) — pending amendment per §2.
- [docs/adr/0009-dualshot-4-3-source-aspect.md](../../adr/0009-dualshot-4-3-source-aspect.md) — to be written alongside the implementation.

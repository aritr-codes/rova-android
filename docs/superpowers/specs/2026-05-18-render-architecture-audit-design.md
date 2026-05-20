# Render Architecture Audit + First-Principles UV Pipeline — Design

**Date:** 2026-05-18
**Author:** Owner + assistant brainstorm
**Status:** Approved (Sections §1–§5 reviewed)
**Sub-project:** 1 of 3 (queued: §2 debug overlay + multi-config smoke; §3 Record-home UI re-skin)
**Base branch:** `feat/dualshot-4-3-source-aspect` @ `84c1d16` (PR #25 tip; PR #25 held pending stabilization)
**Target PR:** Decision deferred to writing-plans phase — either extends PR #25 in place or stacks as PR #26 on top of it. Either path is compatible with this spec; the architecture work is independent of PR strategy.

---

## §0 Context

Phase 6.1c (PR #24, merged @ `ba252c3`) shipped the DualShot render with a 3-round on-device smoke-fix series that empirically tuned a `+270°` per-side UV correction. PR #25 (`feat/dualshot-4-3-source-aspect` @ `84c1d16`) then fixed the source aspect (forced-16:9 → native-4:3 per ADR-0009), resolving the PORTRAIT-zone 1.78× vertical stretch.

The owner's 2026-05-18 review challenged the **architectural correctness** of the render pipeline, hypothesizing that one render path was implicitly derived from another's transformed output (Sensor → Portrait Transform → Rotate/Resize → Landscape Output).

### Audit findings (verified against branch tip `84c1d16`)

**The renderer is already structurally independent per target**, but hidden orientation normalization coupling and empirical transform composition created artifacts resembling derived-orientation behavior. Concretely:

- ✅ Single shared OES texture (`EglRouter.inputTextureId`, created once at `setup()`)
- ✅ Both render paths bind the SAME OES texture (`glBindTexture(GL_TEXTURE_EXTERNAL_OES, inputTextureId)` per target in `renderFrame.forEach`)
- ✅ Independent per-target uvTransform built at `addTarget()` time — no cross-side derivation in the data flow
- ✅ Independent per-target `glViewport` (encoder targets full surface; preview targets letterbox)
- ✅ Independent per-target encoder `eglSurface` via own `eglCreateWindowSurface`

**The earlier visual artifacts came from wrong math (16:9 source center-crop into 9:16 encoder = 1.78× stretch) + empirical `+270°` sideCorrection masking an upstream orientation-normalization gap — NOT from a structural Portrait→Landscape derivation chain.** PR #25's ADR-0009 4:3-source fix resolves the source-aspect math; this sub-project resolves the upstream normalization gap by re-deriving the UV pipeline from first principles.

### What the audit DID surface (this sub-project's scope)

⚠️ **Math composition is rotate-then-crop in UV space.** `AspectFitMath.buildCropMatrix` composes `out = (crop × rot) × sideCorrection`. Applied right-to-left to UV = `sideCorrection → rot → crop`. Currently equivalent because all ops pivot around (0.5, 0.5), but violates owner's rule: "Aspect crop should be defined in the canonical UV frame, terminal in the chain."

⚠️ **Hidden coupling.** `buildCropMatrix` applies identical `+270°` sideCorrection to BOTH sides. The KDoc itself says "Both sides happen to land on the same correction (+270°) after round 3." Identical correction across two unrelated sides = compensating for an upstream issue (OES sensor's native orientation) that should be a single pre-normalization step, not a per-side hack.

⚠️ **Smoke-fix sediment.** Math was empirically tuned across 3 rounds, not first-principles derived. Comments say "if a future device shows the opposite over/under-rotation, flip the constants." Translation: the next sensor/device may surface a new failure mode.

⚠️ **No validation harness.** No unit test asserts cross-side independence. No coverage for front camera, fallback HW path, or rotated device orientation.

### Goal

Re-derive the DualShot UV-transform pipeline from first principles, factored as a clean chain of independent matrices with crop-as-terminal-transform ordering, sourced from documented camera properties (`CameraCharacteristics.SENSOR_ORIENTATION`). Land as **parallel dead-code under a debug flag** (hybrid pattern) so on-device validation precedes migration.

This is sub-project 1 of 3:
- **§1**: render-architecture audit + first-principles math + validation harness (this spec)
- **§2** (queued): debug overlay + multi-config smoke harness
- **§3** (queued): Record-home UI re-skin to `01-record-home.html` mockup

---

## §1 Architecture

### §1.1 Canonical UV frame (the foundational invariant)

The new pipeline introduces — and `textureNormalization` GUARANTEES — a **canonical UV frame** that all downstream transforms operate in:

- `+U = screen-right`
- `+V = screen-down`
- origin = top-left
- rear camera unmirrored
- device-natural orientation aligned (portrait-up for typical phones)

All transforms after `textureNormalization` operate in this frame. Anything before it operates in the OES/SurfaceTexture-native frame. This is the architectural invariant that replaces today's accumulated empirical behavior.

### §1.2 Proposed pipeline (UV-space, right-to-left = applied first to last)

```
uTexMatrix = sideAspectCrop × displayRotationCorrection × textureNormalization × texMatrix × mirrorMatrix
             \______________/  \_____________________/  \______________________/  \________/  \____________/
              CROP (terminal     device-tilt              OES → canonical          intrinsic   user-facing
              transform in      compensation             UV alignment              OES         semantic
              canonical UV)                                                        correction  correction
                                                                                   (opaque)    (front-cam
                                                                                               selfie)
```

**Aspect crop is the terminal transform applied in canonical UV space** — NOT "crop-before-rotation." All orientation normalization is resolved first into the canonical UV frame; crop then operates on a fully-oriented frame. This satisfies the owner's intent (no rotation modifies crop semantics) via a stronger guarantee (crop is defined relative to a fixed canonical frame).

### §1.3 Mirror semantics — preview ≠ encoder

`mirrorMatrix` is **NOT** orientation normalization. It is **user-facing semantic correction** isolated from canonical-frame reasoning:

- **PREVIEW path**: front-camera selfie IS mirrored in UV space for UX familiarity (user's left hand appears on the preview's left, as in a mirror).
- **ENCODER path**: front-camera selfie is NOT mirrored. Recorded files preserve real-world handedness so shared video plays with correct orientation.

This is industry-standard (iOS, Snapchat, Instagram, TikTok). `mirrorMatrix` lives at the OES end of the UV chain, participates in NEITHER orientation normalization NOR aspect crop.

### §1.4 textureNormalization derivation

Derives canonical orientation from `CameraCharacteristics.SENSOR_ORIENTATION` **in conjunction with** the incoming `SurfaceTexture.getTransformMatrix()` convention. SENSOR_ORIENTATION is **necessary but not sufficient** — the effective UV basis also depends on OEM OES handling + SurfaceTexture's intrinsic correction. The helper takes `sensorOrientation` as input; on-device smoke validates the composition against actual `texMatrix` values.

**Current implementation reduces to** a pivot-rotate around (0.5, 0.5) by `sensorOrientation` degrees CCW. This is an **implementation detail, NOT a contract.** Future revisions may incorporate texMatrix-convention adjustments or per-OEM compensations without API breakage as long as the canonical-UV-frame guarantee holds.

### §1.5 Architectural non-goals

- **NO device-specific calibration tables or empirical correction constants** unless future OEM behavior proves mathematically irreducible. If sub-project 2's multi-config smoke exposes a divergence on Xiaomi/OnePlus/etc, the response is to refine the derivation — not to add per-device constants.
- **NO live mid-session rotation handling.** `displayRotation` and `sensorOrientation` pinned at session-start. Mid-session rotation continues with original orientation. (See §3.4.)
- **NO FBO indirection.** Renders directly to encoder/preview surfaces (existing pattern, unchanged).
- **NO new locks.** Reuses existing `synchronized(targets)` in EglRouter.

### §1.6 Hybrid coexistence model

New chain compiled in but **inert by default**. Flag-gated at `EglRouter.addTarget` time via two SharedPreferences keys:

- `pref.dev.useFirstPrinciplesRender` (default `false`): switches `addTarget` from legacy `buildCropMatrix` to new `buildUvTransformV2`
- `pref.dev.enableMatrixSnapshots` (default `false`): gates debug snapshot writes in `renderFrame` hot path

**Both flags wrapped in `BuildConfig.DEBUG` short-circuit** so release builds CANNOT enable them.

### §1.7 Bridge test policy

Hybrid invariant gated by `AspectFitMathBridgeTest`:

```
assertArrayEquals(legacy.buildCropMatrix(...), v2.buildUvTransformV2(...), 1e-5f)
```

Epsilon tolerance (not byte-equality) — floating-point accumulation from different factor orders is expected and acceptable; visual output is identical at perceptual standard (see §5).

**Bridge tests are migration-phase only.** NOT canonical rendering truth. Retirement plan in §5.5.

### §1.8 File scope (12 files total)

**Modified (5):**
- `app/src/main/java/com/aritr/rova/service/dualrecord/internal/AspectFitMath.kt`
- `app/src/main/java/com/aritr/rova/service/dualrecord/internal/EglRouter.kt`
- `app/src/main/java/com/aritr/rova/service/dualrecord/internal/DualSurfaceProcessor.kt`
- `app/src/main/java/com/aritr/rova/service/dualrecord/DualVideoRecorderConfig.kt`
- `app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt`

**New source (1):**
- (none — all new helpers added to existing `AspectFitMath.kt`)

**New tests (4):**
- `app/src/test/java/com/aritr/rova/service/dualrecord/internal/AspectFitMathV2Test.kt`
- `app/src/test/java/com/aritr/rova/service/dualrecord/internal/AspectFitMathBridgeTest.kt`
- `app/src/test/java/com/aritr/rova/service/dualrecord/internal/EglRouterDebugSnapshotTest.kt`
- `app/src/test/java/com/aritr/rova/service/RovaRecordingServiceSensorOrientationSanitizerTest.kt`

**New docs (3):**
- `docs/superpowers/specs/2026-05-18-render-architecture-audit-design.md` (this spec)
- `docs/superpowers/plans/2026-05-18-render-architecture-audit.md` (created post-brainstorm via writing-plans)
- `docs/adr/0010-canonical-uv-frame-and-first-principles-render.md` (captures canonical-UV-frame invariant + hybrid migration policy + bridge retirement plan)

---

## §2 Components

### §2.1 `AspectFitMath.buildTextureNormalization`

**Responsibility:** Produce the UV-space transform that maps the effective OES UV basis into the canonical UV frame, given the physical sensor mount rotation.

**Signature:**
```kotlin
fun buildTextureNormalization(sensorOrientation: Int, out: FloatArray)
```

**Contract:**
- `sensorOrientation ∈ {0, 90, 180, 270}` (per Camera2 spec, `CameraCharacteristics.SENSOR_ORIENTATION` returns multiples of 90)
- `out` must be length 16; contents overwritten
- **Output produces the canonical UV alignment transform derived from SENSOR_ORIENTATION relative to the current OES texture basis.** The current implementation reduces to a pivot-rotate around (0.5, 0.5) by `sensorOrientation` degrees CCW; this is an implementation detail, not a permanent contract
- Throws `IllegalArgumentException` if sensorOrientation not in {0, 90, 180, 270} or out.size != 16

**Why this signature:** Mirrors `buildDisplayRotationCorrection`'s contract (degrees + out buffer). Frontal/rear sensors differ in SENSOR_ORIENTATION value (typically rear=90, front=270) which automatically handles the difference without per-lens branching.

**Depends on:** Nothing. Pure-JVM. Same column-major mat4 conventions as `buildDisplayRotationCorrection`. NO `android.opengl.Matrix.*` references (silently no-ops under `testOptions.unitTests.isReturnDefaultValues = true`).

### §2.2 `AspectFitMath.buildUvTransformV2`

**Responsibility:** Compose the full UV pipeline from canonical components.

**Signature:**
```kotlin
fun buildUvTransformV2(
    displayRotation: Int,
    sensorOrientation: Int,
    side: VideoSide,
    out: FloatArray,
    scratchA: FloatArray,  // length 16, caller-owned, sideAspectCrop accumulator
    scratchB: FloatArray,  // length 16, caller-owned, displayRotationCorrection accumulator
    scratchC: FloatArray,  // length 16, caller-owned, textureNormalization accumulator
    scratchD: FloatArray,  // length 16, caller-owned, rotTimesNorm accumulator
)
```

**Contract:**
- `displayRotation ∈ 0..3`, `sensorOrientation ∈ {0, 90, 180, 270}`
- `out`, `scratchA`–`scratchD` each length 16
- `out`, `scratchA`, `scratchB`, `scratchC`, `scratchD` must be PAIRWISE-DISTINCT array instances (aliasing throws — required by `multiplyMat4`'s no-alias contract; see §2.4)
- Composition: `out = sideAspectCrop × displayRotationCorrection × textureNormalization`
- NO sideCorrection. NO empirical constants.

**Reference implementation:**
```kotlin
buildSideAspectCrop(side, scratchA)                       // scratchA = sideAspectCrop
buildDisplayRotationCorrection(displayRotation, scratchB) // scratchB = displayRotationCorrection
buildTextureNormalization(sensorOrientation, scratchC)    // scratchC = textureNormalization
multiplyMat4(scratchD, scratchB, scratchC)                // scratchD = rotTimesNorm
multiplyMat4(out,      scratchA, scratchD)                // out = sideAspectCrop × rotTimesNorm
```

**Caller (EglRouter) owns scratch buffers as instance fields**, allocated ONCE at ctor:
```kotlin
private val scratchA = FloatArray(16)
private val scratchB = FloatArray(16)
private val scratchC = FloatArray(16)
private val scratchD = FloatArray(16)
```

`addTarget` runs at session-start/TextureView-attach (not per-frame); no-allocation hygiene established preemptively so the pattern is safe if it ever moves into a hot path.

**Name rationale:** NOT `buildCropMatrixV2`. The composed output contains crop + rotation + normalization — calling it "crop" carries legacy conceptual baggage and invites future engineers to assume crop-only semantics. `buildUvTransformV2` reflects what it actually is.

**Reuses legacy `buildSideAspectCrop` unmodified** — it's already canonical (operates in the canonical UV frame on a 4:3 source per ADR-0009). The "smell" was never in the side crop; it was in the upstream sideCorrection that didn't belong per-side.

### §2.3 `DualShotMatrixDebugInfo`

**Responsibility:** Carry one render-target's complete matrix state for debug instrumentation.

**Location:** `AspectFitMath.kt` (alongside helpers; same internal package).

```kotlin
internal data class DualShotMatrixDebugInfo(
    val side: VideoSide,
    val sensorOrientation: Int,
    val displayRotation: Int,
    val lensFacing: LensFacing,
    val texMatrix: FloatArray,                // last frame's SurfaceTexture.getTransformMatrix
    val normalizationMatrix: FloatArray,      // buildTextureNormalization output (component)
    val displayRotationMatrix: FloatArray,    // buildDisplayRotationCorrection output (component)
    val sideAspectCropMatrix: FloatArray,     // buildSideAspectCrop output (component)
    val uvTransform: FloatArray,              // composed: sideAspectCrop × displayRotation × normalization (target.uvTransform)
    val finalMatrix: FloatArray,              // composed uTexMatrix from last frame: uvTransform × texMatrix × mirror
    val viewport: IntArray,                   // [x, y, w, h]
    val encoderSize: Pair<Int, Int>,
    val timestampNs: Long,                    // System.nanoTime() at snapshot write
)
```

**Field semantics — three component matrices + two composed matrices:**
- Components (raw helper outputs, useful for debugging which factor went wrong): `normalizationMatrix`, `displayRotationMatrix`, `sideAspectCropMatrix`
- Composed: `uvTransform` (the pinned `target.uvTransform` — the result of `buildUvTransformV2`); `finalMatrix` (the per-frame composed `uTexMatrix` actually uploaded to the shader)
- The legacy field name `cropMatrix` is GONE from this struct — it carried muddled semantics (component or composed?). Replaced by the explicit pair.

**Consumers:** sub-project 2's debug overlay, logcat dump, screenshot metadata. **This sub-project ships the plumbing inert; sub-project 2 enables it.**

**Concurrency:** `EglRouter` keeps per-side `MutableMap<VideoSide, DualShotMatrixDebugInfo>`. `renderFrame` writes the snapshot under the existing `synchronized(targets)` lock, but ONLY when `enableMatrixSnapshots = true`. Reader `EglRouter.debugSnapshot(side)` reads under the same lock and returns `.copy()`. Cost when enabled: ~280 B/frame/side = ~17 KB/s @ 30fps = negligible. When disabled (default): zero overhead.

### §2.4 `multiplyMat4` documentation

```kotlin
/**
 * Column-major 4×4 matrix multiply.
 *
 *     out = lhs × rhs
 *
 * MULTIPLICATION ORDER IS LOAD-BEARING. Reversing operands inverts UV-
 * application semantics and destroys the pipeline. Mirrors
 * `android.opengl.Matrix.multiplyMM(out, 0, lhs, 0, rhs, 0)` for length-16
 * column-major matrices.
 *
 * In-place aliasing NOT supported: `out !== lhs` AND `out !== rhs` required.
 * Caller must ensure `out` is a distinct array. (Use a scratch buffer and
 * copy if aliasing is needed.)
 */
private fun multiplyMat4(out: FloatArray, lhs: FloatArray, rhs: FloatArray)
```

### §2.5 `EglRouter` threading additions

**Constructor:**
```kotlin
internal class EglRouter(
    private val lensFacing: LensFacing,
    private val displayRotation: Int,
    private val sensorOrientation: Int,            // NEW
    private val useFirstPrinciplesRender: Boolean,  // NEW
    private val enableMatrixSnapshots: Boolean,     // NEW
) {
    init {
        require(displayRotation in 0..3) { ... }
        require(sensorOrientation in setOf(0, 90, 180, 270)) {
            "sensorOrientation must be 0/90/180/270 ..."
        }
    }
```

**Field rename: `RenderTarget.cropMatrix` → `RenderTarget.uvTransform`.** The field carries the full uvTransform composition (legacy: `sideAspectCrop × displayRotationCorrection × sideCorrection`; V2: `sideAspectCrop × displayRotationCorrection × textureNormalization`). Naming it `cropMatrix` invites regression.

**`addTarget` branch (single point of variation):**
```kotlin
if (side != null) {
    if (useFirstPrinciplesRender) {
        AspectFitMath.buildUvTransformV2(
            displayRotation, sensorOrientation, side,
            uvTransform, scratchA, scratchB, scratchC, scratchD,
        )
    } else {
        AspectFitMath.buildCropMatrix(displayRotation, side, uvTransform)  // legacy
    }
    // viewport unchanged
}
```

**NEW method:**
```kotlin
fun debugSnapshot(side: VideoSide): DualShotMatrixDebugInfo? {
    synchronized(targets) {
        return debugInfoBySide[side]?.copy()
    }
}
```

### §2.6 `RovaRecordingService` plumbing

Folds new SENSOR_ORIENTATION query into existing CameraCharacteristics call from PR #25:

```kotlin
private fun resolveDualCameraIntrinsics(): DualCameraIntrinsics {  // RENAMED from resolveDualSourceSize
    // ... existing CameraManager + cameraId + characteristics chain ...
    val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: SENSOR_ORIENTATION_FALLBACK
    DualCameraIntrinsics(
        size = DualCameraSizeResolver.resolveDualCameraSize(map),
        sensorOrientation = sanitizeSensorOrientation(sensorOrientation),
    )
    // ... defensive fallback chain returns DualCameraIntrinsics(Size(1920,1440), 90) ...
}

private data class DualCameraIntrinsics(val size: Size, val sensorOrientation: Int)

// SAFE ASSUMPTION for portrait-natural phones (typical rear-camera mount).
// NOT universally correct — some tablets/foldables have 0 or 180. Hit only when
// CameraManager query fails or SENSOR_ORIENTATION is null.
private const val SENSOR_ORIENTATION_FALLBACK = 90

private fun sanitizeSensorOrientation(raw: Int): Int = when (raw) {
    0, 90, 180, 270 -> raw
    else -> {
        RovaLog.w("SENSOR_ORIENTATION out-of-spec ($raw); falling back to $SENSOR_ORIENTATION_FALLBACK", null)
        SENSOR_ORIENTATION_FALLBACK
    }
}

private fun readUseFirstPrinciplesRender(): Boolean {
    if (!BuildConfig.DEBUG) return false  // release-build short-circuit
    return try {
        getSharedPreferences("rova_dev_flags", MODE_PRIVATE)
            .getBoolean("pref.dev.useFirstPrinciplesRender", false)
    } catch (e: ClassCastException) {
        RovaLog.w("useFirstPrinciplesRender prefs type mismatch; defaulting false", e)
        false
    }
}

private fun readEnableMatrixSnapshots(): Boolean {
    // symmetric to readUseFirstPrinciplesRender
    ...
}
```

---

## §3 Data flow + frame sequencing + lifecycle + lock ordering

### §3.1 Session-start sequence (cold path, service thread)

```
RovaRecordingService.setupDualCamera()
    ↓
    resolveDualCameraIntrinsics()
        → CameraManager.getCameraCharacteristics(cameraId)
        → reads SCALER_STREAM_CONFIGURATION_MAP (existing)
        → reads SENSOR_ORIENTATION (NEW)
        → sanitizeSensorOrientation(raw)
        → returns DualCameraIntrinsics(size, sensorOrientation)
        ↓ defensive fallback: Size(1920,1440), sensorOrientation=90
    ↓
    readUseFirstPrinciplesRender(), readEnableMatrixSnapshots()
        → BuildConfig.DEBUG short-circuit → release returns false
        → SharedPreferences read with try/catch for ClassCastException
    ↓
    RovaLog.i("DualShot renderer mode: path=..., snapshots=..., sensorOrientation=..., displayRotation=..., lensFacing=..., sourceSize=...")
    ↓
    DualVideoRecorderConfig(size, sensorOrientation, useFirstPrinciplesRender, enableMatrixSnapshots, displayRotation, ...)
    ↓
    DualVideoRecorder(config)
        ↓ lazy { DualSurfaceProcessor(lensFacing, displayRotation, sensorOrientation, useFirstPrinciplesRender, enableMatrixSnapshots) }
        ↓ DualSurfaceProcessor.<init>:
            EglRouter(lensFacing, displayRotation, sensorOrientation, useFirstPrinciplesRender, enableMatrixSnapshots).also { it.setup() }
            ↓ require(displayRotation in 0..3)
            ↓ require(sensorOrientation in {0,90,180,270})  // tripped in DEBUG only; release pre-sanitized
            ↓ EGL14 init + program build + OES texture
    ↓
    CameraX bind → DualSurfaceProcessor.onInputSurface
        ↓ router.setInputBufferSize(request.resolution.{w,h})
        ↓ router.setOnFrameAvailableListener { router.renderFrame() }
    ↓
    DualVideoRecorder.attachEncoderInput(PORTRAIT, surface, 1080, 1920)
        ↓ processor.attachEncoderInput(...) → router.addTarget(PORTRAIT, ENCODER, ...)
            ↓ flag-branched: buildUvTransformV2 vs legacy buildCropMatrix → writes target.uvTransform
            ↓ computeFitViewport(width, height, 9/16)
            ↓ synchronized(targets) { targets.add(...) }
    ↓
    DualVideoRecorder.attachEncoderInput(LANDSCAPE, ...) // same path
    ↓
    [async] DualPreviewZone TextureView attaches → attachDualPreview(side, surface, w, h) → router.addTarget(side, PREVIEW, ...)
```

### §3.2 Per-frame sequence (hot path, GL/frame-callback thread)

```
SurfaceTexture.onFrameAvailable callback
    ↓
EglRouter.renderFrame()
    ↓
    anchor = targets[0].eglSurface ?: pbufferSurface
    EGL14.eglMakeCurrent(anchor)
    ↓
    tex.updateTexImage()
    tex.getTransformMatrix(texMatrix)  // frame-local; AUTHORITATIVE only for this frame
    ↓
    synchronized(targets) {
        targets.forEach { target ->
            if (target.side == null && target.kind == PREVIEW) return@forEach  // legacy inert

            EGL14.eglMakeCurrent(target.eglSurface)

            // 1. Clear full surface (paints letterbox bars for preview targets).
            glViewport(0, 0, target.width, target.height)
            glClearColor(0,0,0,1); glClear(GL_COLOR_BUFFER_BIT)

            // 2. Restrict viewport + draw.
            glViewport(target.viewport{X,Y,W,H})
            glUseProgram(program)
            glBindTexture(GL_TEXTURE_EXTERNAL_OES, inputTextureId)  // SAME OES every target

            // Composition (uvTransform pinned at addTarget):
            multiplyMM(tmpMatrix,   texMatrix,           mirrorMatrix)
            multiplyMM(finalMatrix, target.uvTransform,  tmpMatrix)
            glUniformMatrix4fv(uTexMatrixLoc, finalMatrix)

            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)

            // Debug snapshot — gated, default-off, zero overhead when disabled.
            if (enableMatrixSnapshots && target.side != null) {
                debugInfoBySide[target.side] = DualShotMatrixDebugInfo(...)
            }

            eglSwapBuffers(target.eglSurface)
        }
    }
```

**`texMatrix` is frame-local.** SurfaceTexture's getTransformMatrix() may return different values frame-to-frame (stabilization, dynamic crop, OEM pipeline mutations). NEVER cache globally — re-read after every `updateTexImage()`. The per-frame copy into `DualShotMatrixDebugInfo.texMatrix` captures the value for THAT specific frame's snapshot; downstream consumers must treat each snapshot as a frame-instant observation.

**Per-frame matrix allocations on live path:** ONLY `tmpMatrix` and `finalMatrix` — both instance fields, allocated once at ctor (existing pattern, untouched).

### §3.3 Teardown sequence

```
RovaRecordingService.onDestroy / mode-switch
    ↓
DualVideoRecorder.release() → DualSurfaceProcessor.release()
    ↓ released.compareAndSet(false, true)
    ↓ router.release()
        ↓ targets.forEach { eglDestroySurface(t.eglSurface) }
        ↓ targets.clear()
        ↓ debugInfoBySide.clear()  // NEW
        ↓ inputSurfaceTexture.release()
        ↓ eglMakeCurrent(NO_SURFACE, NO_CONTEXT)
        ↓ pbufferSurface destroy
        ↓ eglDestroyContext + eglTerminate
```

TextureView detach (independent path):
```
DualPreviewZone.onSurfaceTextureDestroyed
    ↓ RovaRecordingService.detachDualPreview(side)
    ↓ processor.detachPreviewInput(side) → router.removeTarget(side, PREVIEW)
        ↓ synchronized(targets) {
              target = targets.firstOrNull { ... } ?: return  // idempotent
              eglDestroySurface(target.eglSurface)
              targets.remove(target)
              debugInfoBySide.remove(side)  // NEW
          }
```

### §3.4 Lock ordering

**Single lock: `synchronized(targets)` in EglRouter.** Held by `addTarget`, `removeTarget`, `renderFrame` (entire draw loop), `debugSnapshot`. NO second lock introduced.

**Lock-scope rationale:** Rendering executes under the lock to guarantee EGL surface lifetime safety — `removeTarget` destroys an EGL window surface while holding the lock, and an in-flight draw on that surface would segfault.

**Future optimization (NOT this PR):** Snapshot target list lock-free via `val localTargets = synchronized(targets) { targets.toList() }`. Requires EGL-surface refcount or epoch-based reclamation to defer eglDestroySurface until in-flight draws complete. Lock contention is steady-state zero (target mutations are bind-time events, not per-frame), so this is YAGNI today.

**Released-recorder safety (existing, preserved):** `DualSurfaceProcessor.attach*`/`detach*` short-circuit if `released.get()`. `onInputSurface`/`onOutputSurface` log-and-return if `released.get()`.

### §3.5 Flag lifecycle

```
useFirstPrinciplesRender, enableMatrixSnapshots:
  - read ONCE at RovaRecordingService.setupDualCamera
  - pinned through DualVideoRecorderConfig → DualSurfaceProcessor ctor → EglRouter ctor
  - read inside EglRouter.addTarget (useFirstPrinciplesRender) / renderFrame (enableMatrixSnapshots)
  - NOT re-read per frame, NOT re-read per addTarget within a session

Switching mid-session: HAS NO EFFECT until next session.
  Acceptable for debug-only flags.
```

### §3.6 `displayRotation` pinning UX behavior

**Device rotation during active recording does NOT re-orient outputs live.** `displayRotation` is pinned at session-start; rotating the device mid-recording continues to render with the original orientation. Intentional — re-deriving the UV transform mid-session would require re-querying display.rotation on Configuration change, rebuilding all target uvTransforms, synchronizing the rebuild with in-flight encoder frames — all out-of-scope for the DualShot recorder. **Users are expected to set device orientation BEFORE starting recording.** Same pinning applies to `sensorOrientation` (rear/front swap requires session restart anyway via flipCamera()).

---

## §4 Error handling + HW fallback + invalid states + resilience

### §4.1 New failure modes

| # | Failure mode | Trigger | Handling | Severity |
|---|---|---|---|---|
| 1 | `SENSOR_ORIENTATION` returns null | Defensive (Camera2 spec guarantees non-null) | `?: SENSOR_ORIENTATION_FALLBACK (= 90)` + WARN log | LOW |
| 2 | `sensorOrientation` not in {0, 90, 180, 270} | OEM bug | **DEBUG**: fail fast at `EglRouter.<init>` via `require`. **RELEASE**: pre-sanitized via `sanitizeSensorOrientation` in RovaRecordingService → log WARN + normalize to fallback. Camera startup must NOT crash in production. | MEDIUM |
| 3 | `SharedPreferences.getBoolean` ClassCastException | Non-boolean value at boolean key (root muck / DataStore migration error) | Wrap in try/catch in `readUseFirstPrinciplesRender` + `readEnableMatrixSnapshots`, return `false`, log WARN | LOW |
| 4 | `BuildConfig.DEBUG` true in release | Impossible by Android tooling; defensive | n/a — short-circuit is belt-and-suspenders | NONE |
| 5 | `useFirstPrinciplesRender=true` but V2 produces wrong output on a device variant | Bridge test missed a real regression | Visual smoke catches; flag is one ADB prefs flip away from legacy | MEDIUM (debug-only, smoke-gated) |
| 6 | `enableMatrixSnapshots=true` unbounded map growth | Listener never unregistered | Map keyed by `VideoSide` enum — size capped at 2; writes overwrite | LOW (by design) |
| 7 | Scratch buffer aliasing violation | Caller passes `scratchA === scratchB` | `buildUvTransformV2` `require` triple-distinct → throws | MEDIUM (fail fast) |
| 8 | `multiplyMat4` aliasing violation | Reuse bug (out===lhs or out===rhs) | Documented + `require` in private helper | MEDIUM (fail fast) |

### §4.2 HW fallback chain (existing + extended)

`resolveDualCameraIntrinsics` cascade to safe-default `DualCameraIntrinsics(Size(1920, 1440), sensorOrientation = 90)`:

1. `getSystemService(CAMERA_SERVICE)` returns null → fallback
2. `cameraManager.cameraIdList` empty or matching ID not found → fallback
3. `getCameraCharacteristics(id)` throws → fallback
4. `SCALER_STREAM_CONFIGURATION_MAP` null → fallback (existing)
5. `SENSOR_ORIENTATION` null → `?: 90` (NEW)
6. `SENSOR_ORIENTATION` out-of-spec → `sanitizeSensorOrientation` → fallback + WARN (NEW)
7. Generic `Exception` in try → fallback

All logged at WARN level with reason. Fallback is silent to user (recording still starts; if visual is off, on-device smoke surfaces it).

### §4.3 Flag-state matrix

| State | useFirstPrinciplesRender | enableMatrixSnapshots | Behavior |
|---|---|---|---|
| Release build (any prefs) | false | false | Identical to today; legacy math; zero snapshot overhead |
| Debug, both off | false | false | Identical to today; new code inert |
| Debug, FP on | true | false | New math path; no snapshot overhead |
| Debug, snapshots on | false | true | Legacy math; snapshots written (for sub-project 2 consumption before V2 migration) |
| Debug, both on | true | true | New math + snapshots written (validation config) |

All 5 states compile; relevant subsets run cleanly; no combination throws or no-ops silently.

### §4.4 Flag-state logging (QA correlation)

`RovaRecordingService.setupDualCamera` logs at INFO at session-start:
```
DualShot renderer mode: path=v2-first-principles, snapshots=ENABLED, sensorOrientation=90, displayRotation=0, lensFacing=BACK, sourceSize=2560x1920
```

Lands in logcat at each session-start. QA correlates smoke screenshots to this line. **Same line in release** (where both flags forced false) confirms by absence that observed behavior is legacy + stable.

### §4.5 Cross-session safety

**Mid-session rotation**: displayRotation pinned → output continues with original orientation (intentional, documented).

**flipCamera() mid-session**: tears down dual recorder + restarts → re-runs `setupDualCamera` → re-queries SENSOR_ORIENTATION for new lens (correct — front cam has different value). `flipCamera()` body BYTE-IDENTICAL invariant per Phase 6.1c memory.

**Mode-switch (P+L → Portrait standalone)**: DualVideoRecorder.release → router.release. `debugInfoBySide` dropped with the router. Next `setupDualCamera` (if re-entering P+L) builds fresh map.

### §4.6 Recovery from V2 regression

Worst-case impact is **limited to sessions explicitly using the debug-only first-principles renderer flag** (OFF by default, unavailable in release builds). Possible regressions on those sessions: broken preview framing, rotated/mirrored encoder output, clipped crop, or unrecoverable artifacts in the recorded file. Legacy renderer remains immediately recoverable via SharedPreferences reset + app restart — no APK rebuild required. Sessions PRECEDING the flag flip and sessions FOLLOWING the recovery are unaffected.

### §4.7 Out-of-scope error modes

- GL driver crashes mid-frame (EGL_BAD_DISPLAY etc) → existing process-crash → service restart
- Encoder surface ABA → existing ANR
- Multi-camera concurrent access conflict → CameraX bind-failure path
- Sensor orientation mid-session (physically impossible)
- **Dynamic texMatrix semantic shifts across OEM camera pipelines beyond canonical normalization assumptions.** Some OEMs mutate camera transforms unpredictably (post-processing pipelines, stabilization swaps, EIS engagement). The architecture assumes the canonical-UV-frame guarantee holds for SurfaceTexture's `getTransformMatrix()` contract; OEMs that violate this contract are out-of-scope — surfaced separately if sub-project 2's multi-config smoke exposes them.

---

## §5 Testing

### §5.1 Test policy

- **Pure-JVM unit tests only** for `AspectFitMath*` helpers. NO Robolectric, NO fake GL stacks, NO mocked SurfaceTexture pipelines (high-maintenance fake confidence — avoid). Same model as Phase 6.1c.
- **NO `android.opengl.Matrix.*` references in new code.** Silently no-ops under `testOptions.unitTests.isReturnDefaultValues = true` (Phase 6.1c precedent, documented trap).
- **EGL/MediaCodec/AudioRecord/MediaMuxer runtime layer:** on-device smoke only.
- **Bridge tests are migration-phase only.** Retirement plan in §5.5.

### §5.2 New test files

#### `AspectFitMathV2Test.kt`

- `buildTextureNormalization` × 4 legal sensorOrientation values × **canonical UV invariant assertion** each — **4 tests**. NOT pinned-matrix-bytes (would contradict §2.1's softer contract). Instead: apply the output matrix to the 4 UV unit-square corners and assert the resulting positions match the expected canonical orientation (e.g., for `sensorOrientation=90`, applying to UV (0,0) yields canonical (1,0); applying to (1,0) yields (1,1); etc — a +90° CCW pivot around (0.5,0.5)). Future texMatrix-convention refinements can change the matrix internally without breaking these tests as long as the canonical-frame guarantee holds.
- `buildTextureNormalization` negative paths (illegal sensorOrientation, wrong-size out) — **2 tests**
- `buildUvTransformV2` per-side path independence asserts — **2 tests** (PORTRAIT invariant under LANDSCAPE-only input variation; LANDSCAPE invariant under PORTRAIT-only)
- `buildUvTransformV2` component-equivalence (manual `sideAspectCrop × displayRotationCorrection × textureNormalization` matches helper output) — **1 test**
- `buildUvTransformV2` negative paths (out.size wrong + scratch pairwise-aliasing — all C(5,2)=10 pairs of {out, scratchA-D} could theoretically alias, but covering 4 representative pairs is sufficient) — **5 tests**
- `multiplyMat4` aliasing negative paths (out===lhs, out===rhs) — **2 tests**
- **Aspect-ratio preservation invariants** (NEW per review #9 — original bug class): assert composed transform applied to a unit-square UV input preserves intended aspect ratio for the side's canonical crop region; no X/Y non-uniform scaling beyond the intended pivot-scale; no stretch/squash signature — **2 tests** (one per side)

**Subtotal: ~18 tests, 1 class.**

#### `AspectFitMathBridgeTest.kt`

- Sweep legal product space: `2 sides × 4 displayRotations × 4 sensorOrientations × 2 lensFacings = 64 cases`
- `lensFacing` included to **prove mirror isolation** — assert flipping lensFacing produces epsilon-equal uvTransform output (proves mirror handling is properly isolated from uvTransform composition)
- Per-case: `assertArrayEquals(legacy.buildCropMatrix(...), v2.buildUvTransformV2(...), 1e-5f)`
- JUnit `@RunWith(Parameterized::class)`

**Subtotal: 1 class, 1 test method, 64 parameter cases (counting depends on JUnit Parameterized expansion behavior — verify at dry-run).**

**KDoc on this class:**
```
Migration-phase bridge equivalence: asserts V2 first-principles helpers produce
matrix output epsilon-equal to legacy buildCropMatrix across the legal config
product (64 combinations). NOT canonical rendering truth — exists ONLY during
the hybrid coexistence window to gate V2 against legacy regression.

Matrix equality is WEAKER than rendering correctness. Future post-migration
validation may replace bridge matrix equivalence with semantic-space assertions
(aspect preservation, orientation correctness, viewport coverage, canonical-
frame invariants).

RETIREMENT PLAN:
  - After useFirstPrinciplesRender becomes default (migration PR, 1-2 release
    cycles after this PR lands) AND sub-project 2's multi-config smoke validates:
    downgrade to @Ignore'd historical reference, then delete with legacy
    buildCropMatrix deprecation.
  - Failure of this test BEFORE migration = V2 derivation differs from legacy.
    REQUIRES investigation. If divergence is the INTENTIONAL V2 correction of a
    real legacy quirk, retire the failing case from the bridge and document in
    spec §5.5 as "intentional V2 correction."
```

#### `EglRouterDebugSnapshotTest.kt`

JVM-pure via existing 6.1a Size-stub pattern:
- `EglRouter ctor throws on displayRotation = 4` (verify existing) — **1 test**
- `EglRouter ctor throws on sensorOrientation = 45` — **1 test** (NEW)
- `EglRouter ctor throws on sensorOrientation = -90` — **1 test** (NEW)
- `EglRouter ctor accepts all 4 legal sensorOrientation values` parametrized — **1 test**
- `EglRouter.debugSnapshot returns null when enableMatrixSnapshots=false` — **1 test**
- Snapshot map cap: writing PORTRAIT then PORTRAIT yields map.size == 1 — **1 test**
- `removeTarget(PORTRAIT, PREVIEW)` drops debugInfo[PORTRAIT] — **1 test**

**Subtotal: 7 tests, 1 class.**

#### `RovaRecordingServiceSensorOrientationSanitizerTest.kt`

- `sanitizeSensorOrientation(90)` returns 90 — **1 test**
- `sanitizeSensorOrientation(360)` returns 90 (fallback + WARN) — **1 test**
- `sanitizeSensorOrientation(-1)` returns 90 (fallback + WARN) — **1 test**

**Subtotal: 3 tests, 1 class.**

### §5.3 Test count delta (approximate expected)

Current branch-tip baseline (`84c1d16`): **972 / 77 / 0-0-0**

Approximate expected delta:
- `AspectFitMathV2Test`: +~18 tests, +1 class
- `AspectFitMathBridgeTest`: +~1 to +64 tests (parametrized counting varies), +1 class
- `EglRouterDebugSnapshotTest`: +7 tests, +1 class
- `RovaRecordingServiceSensorOrientationSanitizerTest`: +3 tests, +1 class

**Approximate range: ~1001–1064 tests / 81 classes / 0-0-0** (972 baseline + 18 + 7 + 3 fixed; bridge contributes between 1 and 64 depending on JUnit Parameterized expansion). Lock the predicted count in the plan only after dry-run confirms expansion behavior.

### §5.4 Aspect-ratio preservation invariant tests

Per review #9 — first-class coverage for the **original bug class** (1.78× vertical stretch from PR #25's predecessor).

Semantic invariant tested:
- For PORTRAIT side: composed transform applied to canonical UV input produces a 9:16 crop region of the sensor with X-axis pivot-scale `27/64` and Y-axis identity (i.e., no Y stretch/squash signature). Asserted by sampling the transform at characteristic UV points (corners + center) and verifying the resulting sample positions form a 9:16 rectangle in source coordinates.
- For LANDSCAPE side: symmetric — Y-axis pivot-scale `3/4`, X-axis identity. Verifies 16:9 crop region preservation.

This is stronger than the matrix-equality bridge tests because it tests the **rendering semantic** (aspect preservation) rather than the internal factorization (matrix bytes).

### §5.5 Bridge test retirement plan

| Phase | Bridge tests | Trigger |
|---|---|---|
| This PR ships | Required gate (must be GREEN) | Locks hybrid invariant |
| Sub-project 2 lands | Required gate (still locked) | V2 still inert by default |
| Migration PR opens (flip default to V2) | Required gate + multi-config smoke | Last gate before retirement |
| Migration PR ships + 1-2 release cycles clean | Downgrade to `@Ignore`'d historical reference | No new regressions observed |
| Legacy `buildCropMatrix` deletion | Bridge tests deleted alongside legacy | One atomic PR |

### §5.6 On-device smoke gates

**This PR (V2 OFF by default):**
- **Smoke 1**: app launches in release mode → DualShot session → flag log line shows `path=legacy` → visual matches PR #25's `Screenshot_20260518_150348.png` (regression check)
- **Smoke 2**: debug build → ADB-set `useFirstPrinciplesRender=true` → DualShot session → flag log line shows `path=v2-first-principles` → **visually indistinguishable from smoke 1 under normal playback and frame inspection.** No perceptible framing, orientation, or aspect-ratio divergence. (Pixel-identical rendering is NOT promised across GPUs/OEMs/encoders/driver versions — floating-point accumulation, texture sampling, GPU interpolation, vendor rasterization, viewport rounding, encoder chroma alignment all legitimately diverge.)
- **Smoke 3** (optional): flip `enableMatrixSnapshots=true` → confirm `debugInfoBySide` populated via logcat dump (validates plumbing inert-by-default but functional-when-enabled)

**Recorded output validation** (per review #6 — preview correctness ≠ encoder correctness):
- Pull recorded portrait + landscape files off device
- Validate playback orientation metadata (`ffprobe -show_streams` or equivalent)
- Validate recorded aspect ratio (9:16 for PORTRAIT, 16:9 for LANDSCAPE; no stretch/squash)
- Front-camera handedness check (recorded file matches real-world handedness, NOT mirrored)
- Repeat for: back camera, front camera (PORTRAIT + LANDSCAPE each = 4 files per cam = 8 total)

**Sub-project 2 (deferred) will add:** multi-config smoke matrix (back/front × rotated device × fallback HW path), debug overlay capture.

### §5.7 Build + lint gates

- `:app:assembleDebug` BUILD SUCCESSFUL
- `:app:testDebugUnitTest` per §5.3 / 0-0-0
- `:app:lintDebug` **predicted 53 (50W + 3H + 0E) unchanged** from branch-tip baseline. New code uses `CameraCharacteristics.SENSOR_ORIENTATION` (API 21+; minSdk 24), `SharedPreferences.getBoolean` (API 1), `BuildConfig.DEBUG` (build-time). No InlinedApi / NewApi expected.
- Deprecation warnings: baseline 1 + **NEW +1** at the single `EglRouter.addTarget` call site to `@Deprecated` legacy `buildCropMatrix` (only call site after this PR) = predicted final **2 deprecation warnings.**

### §5.8 Diff allowlist

`git diff <base>..HEAD --name-only` expected exactly:
- `app/src/main/java/com/aritr/rova/service/dualrecord/internal/AspectFitMath.kt` (M)
- `app/src/main/java/com/aritr/rova/service/dualrecord/internal/EglRouter.kt` (M)
- `app/src/main/java/com/aritr/rova/service/dualrecord/internal/DualSurfaceProcessor.kt` (M)
- `app/src/main/java/com/aritr/rova/service/dualrecord/DualVideoRecorderConfig.kt` (M)
- `app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt` (M)
- `app/src/test/java/com/aritr/rova/service/dualrecord/internal/AspectFitMathV2Test.kt` (NEW)
- `app/src/test/java/com/aritr/rova/service/dualrecord/internal/AspectFitMathBridgeTest.kt` (NEW)
- `app/src/test/java/com/aritr/rova/service/dualrecord/internal/EglRouterDebugSnapshotTest.kt` (NEW)
- `app/src/test/java/com/aritr/rova/service/RovaRecordingServiceSensorOrientationSanitizerTest.kt` (NEW)
- `docs/superpowers/specs/2026-05-18-render-architecture-audit-design.md` (NEW — this spec)
- `docs/superpowers/plans/2026-05-18-render-architecture-audit.md` (NEW — written via writing-plans)
- `docs/adr/0010-canonical-uv-frame-and-first-principles-render.md` (NEW)

**Total: 5 modified + 4 new src/test + 3 new docs = 12 files.**

---

## §6 Out of scope

- **Live mid-session rotation handling** (sub-project 1.5 if owner dispatches)
- **Dual capture sessions** (rejected per ADR-0009 — breaks single-source GL fan-out, doubles HAL load, may exceed device stream cap)
- **Explicit FBO render targets** (rejected — direct-to-encoder works; FBOs add copy + memory cost; YAGNI for current pipeline)
- **UI re-skin to `01-record-home.html` mockup** (sub-project 3)
- **Debug overlay surface + multi-config smoke harness** (sub-project 2)
- **OEM texMatrix dynamic mutations** (surfaces in sub-project 2 if it appears)
- **Switching default to V2** (deferred to migration PR, 1-2 release cycles after this lands)

---

## §7 References

- **PR #25** (`feat/dualshot-4-3-source-aspect` @ `84c1d16`) — predecessor; ADR-0009 4:3 source aspect; held pending stabilization
- **Phase 6.1c** (`ba252c3`, PR #24 merged) — DualShot render foundation; 3 smoke-fix rounds; introduced the empirical `+270°` sideCorrection this sub-project eliminates
- **Phase 6.1a** (`5224e83`, PR #22 merged) — dual-recording foundation; Size-stub `Pair<Int,Int>` seam precedent for pure-JVM helpers
- **Phase 6.1b** (`1945965`, PR #23 merged) — consumer wire-up; smoke-fix iteration model
- **ADR-0009** (`docs/adr/0009-dualshot-4-3-source-aspect.md`) — 4:3 source aspect decision (PR #25)
- **ADR-0010** (NEW with this PR, `docs/adr/0010-canonical-uv-frame-and-first-principles-render.md`) — canonical UV frame invariant + hybrid migration policy + bridge retirement plan
- **Camera2 docs**: `CameraCharacteristics.SENSOR_ORIENTATION` (always one of 0/90/180/270, multiple-of-90 guaranteed)
- **OES extension**: `GL_OES_EGL_image_external` — texMatrix from SurfaceTexture is frame-local; may vary across `updateTexImage()` calls

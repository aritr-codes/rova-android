# DualShot Preview Crop-to-Fill + Recording-Frame Guide Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fill each DualShot preview zone edge-to-edge (no letterbox bars) and overlay a faint recording-frame guide that marks the true capture bounds — without changing recorded files.

**Architecture:** Preview targets crop the 4:3 source to the preview *zone's* aspect (fills the surface; no `computeFitViewport` letterbox). Encoder targets keep the existing side-fixed 9:16 / 16:9 crop — recorded files byte-identical. A new `AspectFitMath.buildPreviewCropMatrix` parallels legacy `buildCropMatrix`, sharing the same `× displayRotationCorrection × sideCorrection` chain (including the empirical +270° per-side fudge). A new Compose `RecordingFrameGuide` draws the faint outline + scrim in `DualPreviewZone`, unconditionally in P+L mode.

**Tech Stack:** Kotlin · JUnit 4 (pure-JVM math tests) · Jetpack Compose (`Canvas`, `drawRect`) · CameraX (untouched) · GLES20/EGL14 (untouched at the shader/EGL level — only `addTarget` matrix selection changes).

**Spec:** `docs/superpowers/specs/2026-05-22-dualshot-preview-crop-fill-design.md`

---

## Branch & Baseline

- **Branch:** `feat/dualshot-preview-crop-fill` — off master (`a06ed50`). The spec is already committed (`b47421f`, `262779f`).
- **Baseline (= master `a06ed50`):**
  - `:app:testDebugUnitTest` — **1035 tests / 83 classes / 0-0-0**
  - `:app:lintDebug` — **51 issues (50 W + 1 H + 0 E)**
  - `:app:assembleDebug` — BUILD SUCCESSFUL
- **Predicted after:** tests **≈ 1050 / 83 / 0-0-0** (Tasks 2 + 3 add ≈ 15 new methods to the existing `AspectFitMathTest` class — no new test class). Lint **≤ 51**. `assembleDebug` OK.

**Diff allowlist — `git diff master..HEAD --name-only` must equal exactly:**
```
docs/superpowers/specs/2026-05-22-dualshot-preview-crop-fill-design.md
docs/superpowers/plans/2026-05-22-dualshot-preview-crop-fill.md
docs/adr/0010-dualshot-preview-crop-divergence.md
app/src/main/java/com/aritr/rova/service/dualrecord/internal/AspectFitMath.kt
app/src/test/java/com/aritr/rova/service/dualrecord/internal/AspectFitMathTest.kt
app/src/main/java/com/aritr/rova/service/dualrecord/internal/EglRouter.kt
app/src/main/java/com/aritr/rova/ui/theme/RecordChromeTokens.kt
app/src/main/java/com/aritr/rova/ui/screens/DualPreviewZone.kt
docs/UI_DESIGN_TOKENS.md
```

---

## Testing Policy — Read First

This sub-project's only testable seam is `AspectFitMath` — pure-JVM matrix math. **All new tests go into the existing `AspectFitMathTest` class**, matching its style (assert specific column-major matrix elements with `assertEquals(expected, actual, 1e-6f)` tolerance).

`EglRouter`'s GL/EGL runtime layer and the Compose `RecordingFrameGuide` overlay are not unit-tested (project precedent — EGL needs a real GL context; pixel Compose is not unit-tested). Both are verified by `:app:assembleDebug` staying green and on-device smoke (owner follow-up).

**Gradle is subagent-routed** — the implementer subagent runs `.\gradlew.bat` directly; the controller does not.

---

## File Structure

| File | Action | Responsibility |
|---|---|---|
| `docs/adr/0010-dualshot-preview-crop-divergence.md` | Create | Architecture decision — preview crop deliberately diverges from encoder crop. |
| `app/.../AspectFitMath.kt` | Modify | Add `buildAspectCrop` (generalized crop) + `buildPreviewCropMatrix` (composes with `displayRot × sideCorrection`). |
| `app/src/test/.../AspectFitMathTest.kt` | Modify | TDD-author tests for both new functions in the existing test class. |
| `app/.../EglRouter.kt` | Modify | `addTarget` — when `kind == PREVIEW && side != null`, build preview crop, use full-surface viewport. |
| `app/.../ui/theme/RecordChromeTokens.kt` | Modify | Three new tokens — outline colour / stroke width / scrim colour. |
| `app/.../ui/screens/DualPreviewZone.kt` | Modify | New `RecordingFrameGuide` composable; mount unconditionally in each `PreviewZone`. |
| `docs/UI_DESIGN_TOKENS.md` | Modify | Document the three new tokens. |

---

## Task 1: ADR-0010 — preview crop divergence

**Files:**
- Create: `docs/adr/0010-dualshot-preview-crop-divergence.md`

- [ ] **Step 1: Read ADR-0009 to match the project's ADR style**

Read `docs/adr/0009-dualshot-4-3-source-aspect.md` in full. Note section structure (Status / Context / Decision / Consequences / Alternatives Considered) and prose style.

- [ ] **Step 2: Write ADR-0010**

Create `docs/adr/0010-dualshot-preview-crop-divergence.md`. Use the same section structure as ADR-0009. Content:

```markdown
# ADR-0010 — DualShot preview crop diverges from encoder crop

**Status:** Accepted

**Date:** 2026-05-22

## Context

After ADR-0009 (4:3-source-aspect, merged 2026-05-20) the DualShot recording pipeline crops the 4:3 sensor source to fixed per-side aspects — 9:16 for PORTRAIT, 16:9 for LANDSCAPE — and `EglRouter` renders the same per-side crop into both the encoder surface and the preview `TextureView`. Because the preview zone aspects (≈ 0.9 portrait, ≈ 1.4 landscape) do not match the recording aspects, the existing `AspectFitMath.computeFitViewport` letterboxes the preview — wide pillar bars in the portrait zone, thinner top/bottom bars in the landscape zone. The bars are the same near-black as the `cam-split-divider` and the zone background, so they read as one thick dead band; on the Samsung SM-A176B reference device this is the dominant visual complaint about the DualShot record screen.

The HTML mockup (`mockups/new_uiux/01-record-home.html`) shows zero bars — but its viewfinder is a static `<svg>` placeholder stretched to fill 100 %; it does not represent the live 9:16 / 16:9 camera feed.

## Decision

**Preview targets crop the 4:3 source to the preview zone's aspect (fills the surface, no letterbox). Encoder targets keep the existing side-fixed crop — recorded files byte-identical.**

A new `AspectFitMath.buildPreviewCropMatrix` parallels legacy `buildCropMatrix`. It shares the same `× displayRotationCorrection × sideCorrection` chain — including the empirical +270° per-side fudge from the Phase 6.1c smoke-fix series — and substitutes a generalized `buildAspectCrop(targetAspectW, targetAspectH, sensorOrientation)` for the side-fixed `buildSideAspectCrop`. `buildSideAspectCrop` and `buildCropMatrix` are not modified — the encoder path is frozen and its ADR-0009 regression tests stay green.

In `EglRouter.addTarget` the `kind == TargetKind.PREVIEW && side != null` branch calls `buildPreviewCropMatrix(displayRotation, sensorOrientation, side, width, height, crop)` and assigns a full-surface viewport (`[0, 0, width, height]`) — the crop now matches the surface aspect, so there is nothing to letterbox. The `ENCODER` branch is unchanged.

A Compose `RecordingFrameGuide` overlay in `DualPreviewZone` draws the recorded sub-rectangle as a faint 1 dp gray outline plus a low-alpha black scrim over the non-recorded margin, unconditionally in P+L mode (the guide is functional — without it the wider preview is misleading).

## Consequences

**Accepted:**
- The preview UV transform and the encoder UV transform are no longer identical — debugging tools that assume they match must look at the appropriate per-target matrix. The `RenderTarget.uvTransform` field already varies per target; this only widens the variation.
- A small additive surface area in `AspectFitMath` (≈ 80 lines) and a new Compose overlay (≈ 40 lines) — both pure-JVM-testable on the math side and trivial layout on the UI side.
- `RecordingFrameGuide` is always on in P+L mode, independent of the existing decorative "Camera guides" app-setting. It is not theming — it is a capture-bounds indicator.

**Rejected:**
- *Overscan "cover" viewport, shared crop* — keep one crop, oversize the viewport so content overflows and clips. Fills the zone but shows *less* than the recording (a centre-crop) — contradicts the goal of showing context beyond the recording.
- *Per-fragment crop uniform in the GLSL shader* — no matrix change, but invasive to the shader and offers no pure-JVM test seam.
- *Reshape zone proportions to match recording aspect* — would break the `352:225` mockup ratio and push letterbox bars to the outer record-screen layout.

**Hard invariants:**
- No `ENCODER` render path, `DualVideoRecorder`, muxer, or recording-pipeline behaviour changes — recorded files byte-identical.
- `buildCropMatrix` / `buildSideAspectCrop` outputs unchanged — ADR-0009 regression tests stay green.
- `RecordScreen` Start-gate, `WarningId` / `WarningPrecedence`, service binding — untouched.

## Future work

The V2 first-principles path (`useFirstPrinciplesRender = true`) is parallel dead-code at runtime. When that migration completes, a `buildPreviewUvTransformV2` companion will mirror this decision; until then preview always uses the legacy path even if V2 is flipped on for encoders.
```

- [ ] **Step 3: Commit**

```bash
git add docs/adr/0010-dualshot-preview-crop-divergence.md
git commit -m "docs(adr): ADR-0010 DualShot preview crop diverges from encoder crop"
```

---

## Task 2: `AspectFitMath.buildAspectCrop` — generalized aspect-crop math (TDD)

**Files:**
- Modify: `app/src/test/java/com/aritr/rova/service/dualrecord/internal/AspectFitMathTest.kt`
- Modify: `app/src/main/java/com/aritr/rova/service/dualrecord/internal/AspectFitMath.kt`

This task introduces the generalized aspect-crop helper that `buildPreviewCropMatrix` (Task 3) will compose. It mirrors `buildSideAspectCrop`'s axis-swap pattern and pivot-scale shape exactly — for the fixed PORTRAIT / LANDSCAPE inputs, the output matrix is bit-identical to `buildSideAspectCrop`'s.

- [ ] **Step 1: Read the existing test class & helper to match style**

Read `app/src/test/java/com/aritr/rova/service/dualrecord/internal/AspectFitMathTest.kt` in full — note the existing `buildSideAspectCrop` tests (`assertEquals(expected, out[idx], 1e-6f)` pattern, the `FloatArray(16)` boilerplate, `@Test` naming convention `methodName_inputDescription_expectedShape`). Also re-read `AspectFitMath.buildSideAspectCrop` (lines 100–150) for the existing axis-swap logic.

- [ ] **Step 2: Write the failing tests**

Insert these `@Test` methods into the `AspectFitMathTest` class (place them after the existing `buildSideAspectCrop_*` tests; before the `buildDisplayRotationCorrection_*` block):

```kotlin
// ── buildAspectCrop ──────────────────────────────────────────────────────
// Generalised aspect-crop; bit-identical to buildSideAspectCrop for the
// canonical PORTRAIT (9,16) / LANDSCAPE (16,9) targets but accepts any
// positive (w,h) pair. Backs the preview crop (zone aspect).

@Test
fun buildAspectCrop_portraitAspect_sensor0_pivotScaleX_27over64() {
    val out = FloatArray(16)
    AspectFitMath.buildAspectCrop(9, 16, sensorOrientation = 0, out = out)
    // Same output as buildSideAspectCrop(PORTRAIT, 0): pivot-scale (27/64, 1, 1).
    assertEquals(27f / 64f, out[0], 1e-6f)
    assertEquals(1f, out[5], 1e-6f)
    assertEquals(1f, out[10], 1e-6f)
    assertEquals(1f, out[15], 1e-6f)
    assertEquals(0.5f - 0.5f * (27f / 64f), out[12], 1e-6f)  // 37/128
    assertEquals(0f, out[13], 1e-6f)
}

@Test
fun buildAspectCrop_landscapeAspect_sensor0_pivotScaleY_3over4() {
    val out = FloatArray(16)
    AspectFitMath.buildAspectCrop(16, 9, sensorOrientation = 0, out = out)
    // Same output as buildSideAspectCrop(LANDSCAPE, 0): pivot-scale (1, 3/4, 1).
    assertEquals(1f, out[0], 1e-6f)
    assertEquals(3f / 4f, out[5], 1e-6f)
    assertEquals(1f, out[10], 1e-6f)
    assertEquals(1f, out[15], 1e-6f)
    assertEquals(0f, out[12], 1e-6f)
    assertEquals(0.5f - 0.5f * (3f / 4f), out[13], 1e-6f)  // 1/8
}

@Test
fun buildAspectCrop_arbitraryNarrowTarget_sensor0_pivotScaleX() {
    val out = FloatArray(16)
    // Target 9:10 = 0.9, narrower than source 4:3 = 1.333 → pivot-scale X by
    // (9/10) / (4/3) = 27/40.
    AspectFitMath.buildAspectCrop(9, 10, sensorOrientation = 0, out = out)
    val expectedS = (9f / 10f) / (4f / 3f)  // 27/40 = 0.675
    assertEquals(expectedS, out[0], 1e-6f)
    assertEquals(1f, out[5], 1e-6f)
    assertEquals(0.5f - 0.5f * expectedS, out[12], 1e-6f)
    assertEquals(0f, out[13], 1e-6f)
}

@Test
fun buildAspectCrop_arbitraryWideTarget_sensor0_pivotScaleY() {
    val out = FloatArray(16)
    // Target 7:5 = 1.4, wider than source 4:3 = 1.333 → pivot-scale Y by
    // (4/3) / (7/5) = 20/21.
    AspectFitMath.buildAspectCrop(7, 5, sensorOrientation = 0, out = out)
    val expectedS = (4f / 3f) / (7f / 5f)  // 20/21
    assertEquals(1f, out[0], 1e-6f)
    assertEquals(expectedS, out[5], 1e-6f)
    assertEquals(0f, out[12], 1e-6f)
    assertEquals(0.5f - 0.5f * expectedS, out[13], 1e-6f)
}

@Test
fun buildAspectCrop_targetEqualsSourceAspect_identity() {
    val out = FloatArray(16)
    AspectFitMath.buildAspectCrop(4, 3, sensorOrientation = 0, out = out)
    // Identity — no crop.
    assertEquals(1f, out[0], 1e-6f); assertEquals(0f, out[1], 1e-6f)
    assertEquals(0f, out[4], 1e-6f); assertEquals(1f, out[5], 1e-6f)
    assertEquals(1f, out[10], 1e-6f); assertEquals(1f, out[15], 1e-6f)
    assertEquals(0f, out[12], 1e-6f); assertEquals(0f, out[13], 1e-6f)
}

@Test
fun buildAspectCrop_portraitAspect_sensor90_axisSwapsToLandscapeCrop() {
    val out = FloatArray(16)
    AspectFitMath.buildAspectCrop(9, 16, sensorOrientation = 90, out = out)
    // Sensor 90° swaps texMatrix U<->V, so the canonical portrait crop
    // (out[0]) moves to out[5] with the swapped-target scale.
    // Effective target post-swap = (16, 9) → wider than source → pivot-scale Y
    // by (4/3) / (16/9) = 3/4.
    assertEquals(1f, out[0], 1e-6f)
    assertEquals(3f / 4f, out[5], 1e-6f)
    assertEquals(0f, out[12], 1e-6f)
    assertEquals(0.5f - 0.5f * (3f / 4f), out[13], 1e-6f)
}

@Test
fun buildAspectCrop_landscapeAspect_sensor90_axisSwapsToPortraitCrop() {
    val out = FloatArray(16)
    AspectFitMath.buildAspectCrop(16, 9, sensorOrientation = 90, out = out)
    // Effective post-swap target = (9, 16) → narrower → pivot-scale X by 27/64.
    assertEquals(27f / 64f, out[0], 1e-6f)
    assertEquals(1f, out[5], 1e-6f)
    assertEquals(0.5f - 0.5f * (27f / 64f), out[12], 1e-6f)
    assertEquals(0f, out[13], 1e-6f)
}

@Test
fun buildAspectCrop_sensor180_matchesSensor0() {
    val canon = FloatArray(16)
    val flipped = FloatArray(16)
    AspectFitMath.buildAspectCrop(9, 16, sensorOrientation = 0, out = canon)
    AspectFitMath.buildAspectCrop(9, 16, sensorOrientation = 180, out = flipped)
    // sensorOrientation % 180 == 0 → no axis swap; same as canonical.
    for (i in 0..15) assertEquals("idx=$i", canon[i], flipped[i], 1e-6f)
}

@Test
fun buildAspectCrop_sensor270_matchesSensor90() {
    val sens90 = FloatArray(16)
    val sens270 = FloatArray(16)
    AspectFitMath.buildAspectCrop(9, 16, sensorOrientation = 90, out = sens90)
    AspectFitMath.buildAspectCrop(9, 16, sensorOrientation = 270, out = sens270)
    for (i in 0..15) assertEquals("idx=$i", sens90[i], sens270[i], 1e-6f)
}

@Test(expected = IllegalArgumentException::class)
fun buildAspectCrop_invalidOutSize_throws() {
    AspectFitMath.buildAspectCrop(9, 16, 0, FloatArray(15))
}

@Test(expected = IllegalArgumentException::class)
fun buildAspectCrop_invalidSensorOrientation_throws() {
    AspectFitMath.buildAspectCrop(9, 16, 45, FloatArray(16))
}

@Test(expected = IllegalArgumentException::class)
fun buildAspectCrop_zeroTargetWidth_throws() {
    AspectFitMath.buildAspectCrop(0, 16, 0, FloatArray(16))
}

@Test(expected = IllegalArgumentException::class)
fun buildAspectCrop_zeroTargetHeight_throws() {
    AspectFitMath.buildAspectCrop(9, 0, 0, FloatArray(16))
}
```

- [ ] **Step 3: Run tests to verify they FAIL with "unresolved reference"**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.service.dualrecord.internal.AspectFitMathTest"`
Expected: **COMPILE FAIL** — `Unresolved reference 'buildAspectCrop'`.

- [ ] **Step 4: Add `buildAspectCrop` to `AspectFitMath`**

Insert this function into `AspectFitMath` immediately AFTER `buildSideAspectCrop` (i.e. before `buildTextureNormalization`):

```kotlin
    /**
     * Generalisation of [buildSideAspectCrop] — builds a UV center-crop into
     * [out] for an arbitrary target aspect ([targetAspectW] / [targetAspectH]),
     * not just the side-fixed 9:16 / 16:9. For the canonical PORTRAIT (9,16)
     * and LANDSCAPE (16,9) inputs the matrix is bit-identical to
     * [buildSideAspectCrop] — verified in [AspectFitMathTest].
     *
     * Backs [buildPreviewCropMatrix]: the preview path crops the source to
     * the preview zone's aspect (fills the surface, no letterbox) rather
     * than the side-fixed recording aspect.
     *
     * Same axis-swap rationale as [buildSideAspectCrop]: the OES `texMatrix`
     * swaps U<->V for 90° / 270° sensor orientations, so the effective target
     * aspect in post-`texMatrix` UV coords is (H, W) instead of (W, H). The
     * canonical formula is then applied to the effective target.
     *
     * `out` must be length 16; contents are overwritten. [sensorOrientation]
     * must be one of 0 / 90 / 180 / 270. [targetAspectW] and [targetAspectH]
     * must be > 0.
     */
    internal fun buildAspectCrop(
        targetAspectW: Int,
        targetAspectH: Int,
        sensorOrientation: Int,
        out: FloatArray,
    ) {
        require(out.size == 16) { "out must be length 16, was ${out.size}" }
        require(sensorOrientation in setOf(0, 90, 180, 270)) {
            "sensorOrientation must be 0/90/180/270 " +
                "(CameraCharacteristics.SENSOR_ORIENTATION), was $sensorOrientation"
        }
        require(targetAspectW > 0 && targetAspectH > 0) {
            "target aspect dims must be > 0; was ${targetAspectW}x${targetAspectH}"
        }
        // Identity baseline.
        for (i in 0..15) out[i] = 0f
        out[0] = 1f; out[5] = 1f; out[10] = 1f; out[15] = 1f

        // texMatrix U<->V swap on 90°/270° sensors — effective target is (H, W).
        val axisSwapped = sensorOrientation % 180 != 0
        val effW = if (axisSwapped) targetAspectH else targetAspectW
        val effH = if (axisSwapped) targetAspectW else targetAspectH

        val sourceAspect = SOURCE_ASPECT_W / SOURCE_ASPECT_H  // 4/3
        val targetAspect = effW.toFloat() / effH.toFloat()

        if (kotlin.math.abs(targetAspect - sourceAspect) < 1e-6f) return  // identity

        if (targetAspect < sourceAspect) {
            // Target narrower → pivot-scale X (col 0, col 3 row 0).
            val s = targetAspect / sourceAspect
            out[0] = s
            out[12] = 0.5f - 0.5f * s
        } else {
            // Target wider → pivot-scale Y (col 1, col 3 row 1).
            val s = sourceAspect / targetAspect
            out[5] = s
            out[13] = 0.5f - 0.5f * s
        }
    }
```

- [ ] **Step 5: Run tests to verify they PASS**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.service.dualrecord.internal.AspectFitMathTest"`
Expected: BUILD SUCCESSFUL; all `buildAspectCrop_*` tests pass; all pre-existing `buildSideAspectCrop_*` / `buildCropMatrix_*` tests still pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aritr/rova/service/dualrecord/internal/AspectFitMath.kt app/src/test/java/com/aritr/rova/service/dualrecord/internal/AspectFitMathTest.kt
git commit -m "feat(dualrecord): add AspectFitMath.buildAspectCrop generalised crop"
```

---

## Task 3: `AspectFitMath.buildPreviewCropMatrix` — composition (TDD)

**Files:**
- Modify: `app/src/test/java/com/aritr/rova/service/dualrecord/internal/AspectFitMathTest.kt`
- Modify: `app/src/main/java/com/aritr/rova/service/dualrecord/internal/AspectFitMath.kt`

Composes the new aspect-crop through the same `× displayRotationCorrection × sideCorrection` chain as legacy `buildCropMatrix`. The tests prove equivalence with `buildCropMatrix` for the side-fixed targets — that property is what guarantees the preview rotates identically to the encoder, which is what makes the recording-frame guide align with the actual capture region.

- [ ] **Step 1: Write the failing tests**

Add these `@Test` methods to `AspectFitMathTest`, AFTER the `buildAspectCrop_*` block from Task 2 and after the existing `buildCropMatrix_*` tests:

```kotlin
// ── buildPreviewCropMatrix ───────────────────────────────────────────────
// Preview path matrix: same composition as buildCropMatrix but with
// arbitrary-aspect crop. Equivalence with buildCropMatrix for side-fixed
// targets is the load-bearing property — it's what makes the preview
// rotate identically to the encoder.

@Test
fun buildPreviewCropMatrix_portraitSideFixedTarget_matchesBuildCropMatrix_sensor0() {
    val preview = FloatArray(16)
    val legacy = FloatArray(16)
    AspectFitMath.buildPreviewCropMatrix(
        displayRotation = 1, sensorOrientation = 0, side = VideoSide.PORTRAIT,
        targetAspectW = 9, targetAspectH = 16, out = preview,
    )
    @Suppress("DEPRECATION")
    AspectFitMath.buildCropMatrix(
        displayRotation = 1, sensorOrientation = 0, side = VideoSide.PORTRAIT,
        out = legacy,
    )
    for (i in 0..15) assertEquals("idx=$i", legacy[i], preview[i], 1e-6f)
}

@Test
fun buildPreviewCropMatrix_landscapeSideFixedTarget_matchesBuildCropMatrix_sensor0() {
    val preview = FloatArray(16)
    val legacy = FloatArray(16)
    AspectFitMath.buildPreviewCropMatrix(
        displayRotation = 1, sensorOrientation = 0, side = VideoSide.LANDSCAPE,
        targetAspectW = 16, targetAspectH = 9, out = preview,
    )
    @Suppress("DEPRECATION")
    AspectFitMath.buildCropMatrix(
        displayRotation = 1, sensorOrientation = 0, side = VideoSide.LANDSCAPE,
        out = legacy,
    )
    for (i in 0..15) assertEquals("idx=$i", legacy[i], preview[i], 1e-6f)
}

@Test
fun buildPreviewCropMatrix_portraitSideFixedTarget_matchesBuildCropMatrix_sensor90() {
    val preview = FloatArray(16)
    val legacy = FloatArray(16)
    AspectFitMath.buildPreviewCropMatrix(
        displayRotation = 1, sensorOrientation = 90, side = VideoSide.PORTRAIT,
        targetAspectW = 9, targetAspectH = 16, out = preview,
    )
    @Suppress("DEPRECATION")
    AspectFitMath.buildCropMatrix(
        displayRotation = 1, sensorOrientation = 90, side = VideoSide.PORTRAIT,
        out = legacy,
    )
    for (i in 0..15) assertEquals("idx=$i", legacy[i], preview[i], 1e-6f)
}

@Test
fun buildPreviewCropMatrix_zoneAspectTarget_differsFromSideFixed() {
    // A zone-aspect target (9:10 portrait zone) should produce a different
    // matrix than the side-fixed (9:16) target — the crop component changes.
    val zone = FloatArray(16)
    val sideFixed = FloatArray(16)
    AspectFitMath.buildPreviewCropMatrix(
        displayRotation = 1, sensorOrientation = 0, side = VideoSide.PORTRAIT,
        targetAspectW = 9, targetAspectH = 10, out = zone,
    )
    AspectFitMath.buildPreviewCropMatrix(
        displayRotation = 1, sensorOrientation = 0, side = VideoSide.PORTRAIT,
        targetAspectW = 9, targetAspectH = 16, out = sideFixed,
    )
    var differs = false
    for (i in 0..15) if (kotlin.math.abs(zone[i] - sideFixed[i]) > 1e-6f) { differs = true; break }
    assertEquals(true, differs)
}

@Test(expected = IllegalArgumentException::class)
fun buildPreviewCropMatrix_invalidOutSize_throws() {
    AspectFitMath.buildPreviewCropMatrix(
        displayRotation = 1, sensorOrientation = 0, side = VideoSide.PORTRAIT,
        targetAspectW = 9, targetAspectH = 16, out = FloatArray(15),
    )
}
```

The `VideoSide` import is already present in the test file (the existing `buildSideAspectCrop_*` tests reference it).

- [ ] **Step 2: Run tests to verify they FAIL with "unresolved reference"**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.service.dualrecord.internal.AspectFitMathTest"`
Expected: **COMPILE FAIL** — `Unresolved reference 'buildPreviewCropMatrix'`.

- [ ] **Step 3: Add `buildPreviewCropMatrix` to `AspectFitMath`**

Insert this function into `AspectFitMath` immediately AFTER `buildCropMatrix` (i.e. before `buildUvTransformV2`):

```kotlin
    /**
     * Preview-target variant of [buildCropMatrix] — composes the same
     *   `cropMatrix = aspectCrop × displayRotationCorrection × sideOrientationCorrection`
     * chain, including the empirical +270° per-side correction from the
     * Phase 6.1c smoke-fix series, but substitutes [buildAspectCrop] (target
     * = preview zone aspect) for [buildSideAspectCrop] (target = recording
     * aspect). For the canonical (9, 16) / (16, 9) targets the matrix is
     * bit-identical to [buildCropMatrix] — verified in [AspectFitMathTest].
     *
     * Used by `EglRouter.addTarget` when `kind == TargetKind.PREVIEW`. The
     * `side` is still required: the +270° sideCorrection is per-side, and
     * the preview MUST rotate identically to its encoder so the
     * `RecordingFrameGuide` overlay lines up with the actual capture region.
     *
     * `out` must be length 16; [displayRotation] in 0..3;
     * [sensorOrientation] in {0, 90, 180, 270};
     * [targetAspectW] and [targetAspectH] > 0.
     *
     * See `docs/adr/0010-dualshot-preview-crop-divergence.md`.
     */
    fun buildPreviewCropMatrix(
        displayRotation: Int,
        sensorOrientation: Int,
        side: VideoSide,
        targetAspectW: Int,
        targetAspectH: Int,
        out: FloatArray,
    ) {
        require(out.size == 16) { "out must be length 16, was ${out.size}" }
        val rot = FloatArray(16)
        val crop = FloatArray(16)
        buildDisplayRotationCorrection(displayRotation, rot)
        buildAspectCrop(targetAspectW, targetAspectH, sensorOrientation, crop)

        // Per-side +270° UV correction — same as buildCropMatrix. Both sides
        // land on the same correction after the Phase 6.1c round-3 smoke-fix
        // but the per-side `when` is kept to preserve clear intent and to
        // give future smoke-fixes a place to diverge.
        val sideCorrection = FloatArray(16)
        when (side) {
            VideoSide.PORTRAIT -> buildDisplayRotationCorrection(2, sideCorrection)
            VideoSide.LANDSCAPE -> buildDisplayRotationCorrection(2, sideCorrection)
        }
        val cropTimesRot = FloatArray(16)
        multiplyMat4(cropTimesRot, crop, rot)
        // out = (crop × rot) × sideCorrection — applied to UV right-to-left.
        multiplyMat4(out, cropTimesRot, sideCorrection)
    }
```

- [ ] **Step 4: Run tests to verify they PASS**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.service.dualrecord.internal.AspectFitMathTest"`
Expected: BUILD SUCCESSFUL; all `buildPreviewCropMatrix_*` tests pass; all pre-existing tests still pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/service/dualrecord/internal/AspectFitMath.kt app/src/test/java/com/aritr/rova/service/dualrecord/internal/AspectFitMathTest.kt
git commit -m "feat(dualrecord): add AspectFitMath.buildPreviewCropMatrix for preview path"
```

---

## Task 4: `EglRouter.addTarget` — PREVIEW branch

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/service/dualrecord/internal/EglRouter.kt`

**Hard invariant:** do NOT touch the ENCODER target branch (the `if (kind == TargetKind.ENCODER && side != null)` block and everything inside it). Do NOT touch the legacy `side == null` branch. This task only adds the `PREVIEW`-and-side-known branch to the crop/viewport build, before the existing target-list / encoder-thread dispatch.

- [ ] **Step 1: Replace the matrix/viewport build in `addTarget`**

In `EglRouter.kt`, find the `addTarget` function (around line 383). The current body builds `crop` and `viewport` like this:

```kotlin
        val crop = FloatArray(16)
        val viewport: IntArray
        if (side != null) {
            if (useFirstPrinciplesRender) {
                AspectFitMath.buildUvTransformV2(
                    displayRotation, sensorOrientation, side,
                    crop, scratchA, scratchB, scratchC, scratchD,
                )
            } else {
                @Suppress("DEPRECATION")
                AspectFitMath.buildCropMatrix(displayRotation, sensorOrientation, side, crop)
            }
            val contentAspect = when (side) {
                VideoSide.PORTRAIT -> 9f / 16f
                VideoSide.LANDSCAPE -> 16f / 9f
            }
            viewport = AspectFitMath.computeFitViewport(width, height, contentAspect)
        } else {
            Matrix.setIdentityM(crop, 0)
            viewport = intArrayOf(0, 0, width, height)
        }
```

Replace it with:

```kotlin
        val crop = FloatArray(16)
        val viewport: IntArray
        if (side != null) {
            if (kind == TargetKind.PREVIEW) {
                // Preview path — crop to the zone's aspect (fills the surface,
                // no letterbox). Viewport is the full surface because the
                // crop now matches it. See ADR-0010 and
                // docs/superpowers/specs/2026-05-22-dualshot-preview-crop-fill-design.md.
                AspectFitMath.buildPreviewCropMatrix(
                    displayRotation = displayRotation,
                    sensorOrientation = sensorOrientation,
                    side = side,
                    targetAspectW = width,
                    targetAspectH = height,
                    out = crop,
                )
                viewport = intArrayOf(0, 0, width, height)
            } else {
                // Encoder path — unchanged (frozen per ADR-0010).
                if (useFirstPrinciplesRender) {
                    AspectFitMath.buildUvTransformV2(
                        displayRotation, sensorOrientation, side,
                        crop, scratchA, scratchB, scratchC, scratchD,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    AspectFitMath.buildCropMatrix(displayRotation, sensorOrientation, side, crop)
                }
                val contentAspect = when (side) {
                    VideoSide.PORTRAIT -> 9f / 16f
                    VideoSide.LANDSCAPE -> 16f / 9f
                }
                viewport = AspectFitMath.computeFitViewport(width, height, contentAspect)
            }
        } else {
            Matrix.setIdentityM(crop, 0)
            viewport = intArrayOf(0, 0, width, height)
        }
```

Notes for the implementer:
- The `kind == TargetKind.PREVIEW` branch is the new path. The `else` branch is the prior encoder path verbatim — do not re-flow or reformat it.
- `width` and `height` are `addTarget`'s existing `Int` parameters — they are the preview `TextureView` surface dims, which equal the preview zone's aspect numerator/denominator after Compose lays it out.
- For degenerate `width` or `height` (≤ 0 during `TextureView` setup): `buildPreviewCropMatrix` will throw `IllegalArgumentException`. In practice `TextureView.SurfaceTextureListener.onSurfaceTextureAvailable` is only called with positive dims, but if a defensive identity-fallback is wanted, that lives in `buildPreviewCropMatrix` (already handled by the `targetAspect == sourceAspect` identity branch only for the equal case; zero dims correctly fail-fast). Do not add an extra guard here.

- [ ] **Step 2: Update the function's KDoc**

The `addTarget` KDoc currently says:

```kotlin
     * Phase 6.1c — `cropMatrix` is built once here via
     * [AspectFitMath.buildCropMatrix] from the session-pinned
     * `displayRotation` + per-side aspect. The viewport is computed via
     * [AspectFitMath.computeFitViewport]: encoder targets are
     * aspect-matched by design → full surface; preview TextureView
     * targets letterbox the side's content aspect inside their surface
     * dims. Neither is recomputed per frame (the previous smoke-fix #5
     * per-frame `computeCropMatrix` call has been removed).
```

Replace that paragraph with:

```kotlin
     * Phase 6.1c + ADR-0010 — `cropMatrix` is built once here from the
     * session-pinned `displayRotation` + the target's aspect:
     *  - ENCODER target → [AspectFitMath.buildCropMatrix] (or V2), crop to
     *    the side-fixed 9:16 / 16:9 recording aspect; viewport from
     *    [AspectFitMath.computeFitViewport] — encoder surfaces are aspect-
     *    matched by design → full surface.
     *  - PREVIEW target → [AspectFitMath.buildPreviewCropMatrix], crop to
     *    the surface's actual aspect (the preview zone's aspect); viewport
     *    = full surface (the crop now matches it — no letterbox bars). See
     *    `docs/adr/0010-dualshot-preview-crop-divergence.md`.
     *
     * Neither is recomputed per frame (the previous smoke-fix #5 per-frame
     * `computeCropMatrix` call has been removed).
```

- [ ] **Step 3: Compile-gate**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Run the unit-test suite to confirm no regressions**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL. All tests pass; total should be **1035 baseline + Task 2's 13 + Task 3's 5 = 1053 tests / 83 classes / 0-0-0**.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/service/dualrecord/internal/EglRouter.kt
git commit -m "feat(dualrecord): EglRouter.addTarget — PREVIEW crops to zone aspect"
```

---

## Task 5: `RecordChromeTokens` — recording-frame guide tokens

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/theme/RecordChromeTokens.kt`

- [ ] **Step 1: Add the tokens**

Insert the following block immediately BEFORE the closing `}` of `object RecordChromeTokens` (i.e. at the same insertion point pattern as the camera-guide tokens added in the previous sub-project):

```kotlin

    // ── Recording-frame guide (P+L mode — always on, ADR-0010) ─────────
    /**
     * Recording-frame outline colour — faint light-gray, low alpha. Per
     * `docs/superpowers/specs/2026-05-22-dualshot-preview-crop-fill-design.md`
     * §5.3 / §5.4. Deliberately subtle so it does not compete with the live
     * preview; pairs with [recordingFrameStrokeWidth].
     */
    val recordingFrameOutline = Color(0xFFB0B4BC).copy(alpha = 0.38f)
    /** Recording-frame outline stroke width. */
    val recordingFrameStrokeWidth = 1.dp
    /**
     * Recording-frame scrim — faint black over the non-recorded preview
     * margin. Signals "this part isn't captured".
     */
    val recordingFrameScrim = Color.Black.copy(alpha = 0.22f)
```

(`Color` and `dp` are already imported in this file from the camera-guide tokens added previously.)

- [ ] **Step 2: Compile-gate**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/theme/RecordChromeTokens.kt
git commit -m "feat(ui): add recording-frame guide tokens to RecordChromeTokens"
```

---

## Task 6: `DualPreviewZone` — `RecordingFrameGuide` composable + mount

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/DualPreviewZone.kt`

**Hard invariant:** do NOT touch the `TextureView` / `SurfaceTexture` / `registerPreviewSurface` / `unregisterPreviewSurface` lifecycle, the `352f` / `225f` zone weights, the `cam-split-divider` `Box`, or the existing `CameraGuides` mount. Only ADD: a new `RecordingFrameGuide` composable below the file's existing composables, and one new call site inside `PreviewZone`.

- [ ] **Step 1: Add the imports**

In `DualPreviewZone.kt`, ADD these imports (alongside the existing imports — match insertion alphabetical-ish position):

```kotlin
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
```

The existing imports already include `androidx.compose.ui.Modifier`, `androidx.compose.foundation.layout.fillMaxSize`, the `RecordChromeTokens` import (added by the camera-guides sub-project), and `com.aritr.rova.service.dualrecord.VideoSide` — do not duplicate.

- [ ] **Step 2: Add the `RecordingFrameGuide` composable**

Append this composable to the end of `DualPreviewZone.kt` (after the existing `private fun PreviewZone(...)` block):

```kotlin
/**
 * Recording-frame guide — overlay drawn unconditionally in each P+L zone.
 * Marks the recorded sub-rectangle of the wider preview with a faint 1 dp
 * gray outline plus a low-alpha black scrim over the non-recorded margin.
 * Independent of the decorative "Camera guides" app-setting: this is
 * functional (capture-bounds indicator), not decorative.
 *
 * Stateless and non-interactive. Pure layout math from the zone's measured
 * size + the side's recording aspect — no GL, no Compose state, recomposes
 * only when the host zone resizes.
 *
 * See ADR-0010 and
 * `docs/superpowers/specs/2026-05-22-dualshot-preview-crop-fill-design.md` §5.3.
 */
@Composable
private fun RecordingFrameGuide(side: VideoSide, modifier: Modifier = Modifier) {
    val recordingAspect = when (side) {
        VideoSide.PORTRAIT -> 9f / 16f
        VideoSide.LANDSCAPE -> 16f / 9f
    }
    val outlineColor = RecordChromeTokens.recordingFrameOutline
    val scrimColor = RecordChromeTokens.recordingFrameScrim
    val strokeWidthDp = RecordChromeTokens.recordingFrameStrokeWidth

    Canvas(modifier = modifier) {
        val zoneW = size.width
        val zoneH = size.height
        if (zoneW <= 0f || zoneH <= 0f) return@Canvas
        val zoneAspect = zoneW / zoneH

        // Fit the recording-aspect rectangle inside the zone, centred.
        val (recW, recH) = if (recordingAspect < zoneAspect) {
            // Recording narrower than zone → fit by height, side scrims.
            zoneH * recordingAspect to zoneH
        } else {
            // Recording wider than zone → fit by width, top/bottom scrims.
            zoneW to zoneW / recordingAspect
        }
        val recLeft = (zoneW - recW) / 2f
        val recTop = (zoneH - recH) / 2f

        // Scrim over the non-recorded margin.
        if (recW < zoneW) {
            // Side scrims (portrait case).
            drawRect(scrimColor, topLeft = Offset(0f, 0f),               size = Size(recLeft, zoneH))
            drawRect(scrimColor, topLeft = Offset(recLeft + recW, 0f),   size = Size(zoneW - recLeft - recW, zoneH))
        }
        if (recH < zoneH) {
            // Top/bottom scrims (landscape case).
            drawRect(scrimColor, topLeft = Offset(0f, 0f),               size = Size(zoneW, recTop))
            drawRect(scrimColor, topLeft = Offset(0f, recTop + recH),    size = Size(zoneW, zoneH - recTop - recH))
        }

        // Recording-rect outline. `Stroke.width = strokeWidthDp.toPx()` is the
        // device-pixel thickness; for 1.dp on the Samsung SM-A176B (density
        // ~1.7) that lands on ~2 device pixels.
        drawRect(
            color = outlineColor,
            topLeft = Offset(recLeft, recTop),
            size = Size(recW, recH),
            style = Stroke(width = strokeWidthDp.toPx()),
        )
    }
}
```

- [ ] **Step 3: Mount the guide inside `PreviewZone`**

In the `private fun PreviewZone(...)` body, the current `Box` layers are: `AndroidView` (camera) → `CameraGuides` (decorative, toggle-gated) → `Text` (zone tag). Insert the `RecordingFrameGuide` BETWEEN `CameraGuides` and `Text` — the guide is functional and should sit ABOVE the decorative grid + brackets so it stays readable:

Find this section of `PreviewZone`:

```kotlin
        // Decorative guides — grid + focus brackets — above the
        // camera, below the tag. Renders nothing when the toggle is off.
        CameraGuides(visible = guidesEnabled, modifier = Modifier.fillMaxSize())
        // cam-zone-tag — plain uppercase micro-text (mockup .cam-zone-tag:
        // 7.5 sp, weight 500, 1.5 sp tracking, white-32%), bottom-end.
        Text(
```

Replace with:

```kotlin
        // Decorative guides — grid + focus brackets — above the
        // camera, below the tag. Renders nothing when the toggle is off.
        CameraGuides(visible = guidesEnabled, modifier = Modifier.fillMaxSize())
        // Recording-frame guide — always on in P+L mode. Above the decorative
        // grid/brackets so capture bounds stay readable. See ADR-0010.
        RecordingFrameGuide(side = side, modifier = Modifier.fillMaxSize())
        // cam-zone-tag — plain uppercase micro-text (mockup .cam-zone-tag:
        // 7.5 sp, weight 500, 1.5 sp tracking, white-32%), bottom-end.
        Text(
```

- [ ] **Step 4: Compile-gate**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/DualPreviewZone.kt
git commit -m "feat(ui): mount RecordingFrameGuide in DualPreviewZone (P+L always-on)"
```

---

## Task 7: Document the new tokens

**Files:**
- Modify: `docs/UI_DESIGN_TOKENS.md`

- [ ] **Step 1: Read the doc**

Read `docs/UI_DESIGN_TOKENS.md` in full. Locate the `RecordChromeTokens` section (§2.13). The camera-guide tokens added previously sit as a "Camera-guide overlay constants" sub-subsection within §2.13.

- [ ] **Step 2: Add a new sub-subsection**

Inside §2.13's `RecordChromeTokens` section, immediately AFTER the "Camera-guide overlay constants" sub-subsection, add a short paragraph documenting the three new tokens:

- `recordingFrameOutline` — `Color(0xFFB0B4BC).copy(alpha = 0.38f)` — faint light-gray, low alpha. The colour of the recording-frame guide outline (P+L mode).
- `recordingFrameStrokeWidth` — `1.dp` — outline thickness; renders ~2 device pixels on the SM-A176B reference device.
- `recordingFrameScrim` — `Color.Black.copy(alpha = 0.22f)` — faint scrim over the non-recorded preview margin.

Note that the recording-frame guide is **functional**, not decorative — it is always on in P+L mode regardless of the "Camera guides" app-setting. Reference ADR-0010 and the spec.

Keep it short — match the doc's existing "Camera-guide overlay constants" paragraph style. Do not restructure or renumber sections.

- [ ] **Step 3: Commit**

```bash
git add docs/UI_DESIGN_TOKENS.md
git commit -m "docs(ui): document the recording-frame guide tokens"
```

---

## Task 8: Full-suite gate & invariant verification

**Files:** none — verification only.

- [ ] **Step 1: assembleDebug**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Unit-test suite**

Run: `.\gradlew.bat :app:testDebugUnitTest`

Sum the JUnit XMLs under `app/build/test-results/testDebugUnitTest/*.xml`. Expected: **1053 tests / 83 classes / 0 failures / 0 errors / 0 skipped** (= 1035 baseline + 13 from Task 2 + 5 from Task 3). Class count unchanged (all new tests added to the existing `AspectFitMathTest`).

To sum the XMLs in PowerShell:

```powershell
$xmls = Get-ChildItem app\build\test-results\testDebugUnitTest\*.xml
$t=0;$f=0;$e=0;$s=0;$c=0
foreach ($x in $xmls) { [xml]$d = Get-Content $x.FullName; $c++; $t+=[int]$d.testsuite.tests; $f+=[int]$d.testsuite.failures; $e+=[int]$d.testsuite.errors; $s+=[int]$d.testsuite.skipped }
"tests=$t classes=$c failures=$f errors=$e skipped=$s"
```

If the totals drift from 1053 / 83 / 0-0-0, list which suite changed and investigate.

- [ ] **Step 3: Lint**

Run: `.\gradlew.bat :app:lintDebug`
Expected: **≤ 51 issues**. Read `app/build/reports/lint-results-debug.txt` (or `.html`) for the breakdown. Baseline is 51 = 50 W + 1 H + 0 E; this sub-project adds no SDK-gated APIs, no resource references, and no new system-Settings actions, so lint should be unchanged. If a new finding shows up — likely an unused import or `private` visibility nit — fix it and commit `git commit -am "fix(ui): lint finding from camera-frame-guide branch"`.

- [ ] **Step 4: Diff allowlist**

Run: `git diff master..HEAD --name-only`

Expected: **exactly** the 9 paths from the "Diff allowlist" section. Use `rtk proxy git diff master..HEAD --name-only` if the standard tool returns empty output (a Windows `nul`-file quirk; the raw call works). Any extra path — especially under `service/**` outside `dualrecord/internal/{AspectFitMath.kt, EglRouter.kt}`, or any `dualrecord/DualVideoRecorder*.kt` — is a hard-invariant violation; revert it.

- [ ] **Step 5: ADR-0009 regression spot-check**

Confirm the existing `buildSideAspectCrop_*` and `buildCropMatrix_*` tests in `AspectFitMathTest` are unmodified by this branch:

Run: `git diff master -- app/src/test/java/com/aritr/rova/service/dualrecord/internal/AspectFitMathTest.kt`

Expected: every diff hunk is purely additive — `+` lines only, in the regions where Tasks 2 and 3 inserted new `@Test` methods. No `-` lines (no existing-test modifications).

- [ ] **Step 6: Report**

Report the three gate results, exact test totals, lint breakdown, the diff file list, and confirmation that the ADR-0009 regression tests are unmodified. No commit.

---

## Hard Invariants (verify untouched)

- `service/dualrecord/internal/{EglRouter.kt, AspectFitMath.kt}` — modified by Tasks 3 and 4. Every OTHER file in `service/**` and `dualrecord/**` — `DualVideoRecorder`, `DualMuxerStateMachine`, `DualSurfaceProcessor`, `EncoderRenderThread`, `AudioFanOut`, `FboRing`, the configuration and event types — untouched.
- `AspectFitMath.buildCropMatrix`, `AspectFitMath.buildSideAspectCrop`, `AspectFitMath.computeFitViewport`, `AspectFitMath.buildDisplayRotationCorrection`, `AspectFitMath.buildTextureNormalization`, `AspectFitMath.buildUvTransformV2`, `AspectFitMath.multiplyMat4` — untouched. Tasks 2 and 3 only ADD new functions.
- `EglRouter.renderFrame`, EGL setup, FBO ring, encoder-thread fan-out, target lifecycle — untouched. Task 4 only modifies the matrix/viewport build in `addTarget`.
- `DualPreviewZone`'s `TextureView` / `SurfaceTexture` / `registerPreviewSurface` / `unregisterPreviewSurface` lifecycle; the `352f` / `225f` zone weights; the `cam-split-divider` `Box`; the existing `CameraGuides` mount — untouched. Task 6 only ADDS `RecordingFrameGuide`.
- `RecordScreen` Start-gate (`startBlocked` / `onStart` / `WarningId` / `WarningPrecedence`), service binding, `combinedOpen` chrome gate — untouched.
- `RecordViewModel.kt`, `SettingsViewModel.kt`, `RovaSettings.kt`, `SettingsScreen.kt` — untouched (the guide is always on in P+L mode; no new app-setting).
- Single Portrait / Landscape mode preview (CameraX `PreviewView`) — untouched.
- Encoder render path → recorded files byte-identical (load-bearing invariant; protected by ADR-0009 regression tests).

## Owner Follow-Up (not implementer scope)

- **On-device smoke** (Samsung SM-A176B): in DualShot mode both zones fill edge-to-edge with no black bars; the recording-frame guide outline + faint margin scrim mark the capture bounds in each zone; a test recording confirms the saved file's framing is unchanged from before this branch. Single Portrait / Landscape mode preview unchanged.
- The mockup at `mockups/new_uiux/01b-record-home-dualshot-crop-fill.html` is the visual reference (`mockups/` is gitignored — local file only).

## Self-Review

- **Spec coverage:** §1–2 problem/goal → Task 4 (`addTarget` PREVIEW branch) + Task 6 (`RecordingFrameGuide`). §3 cover+guide behaviour → Tasks 4 + 6. §4 architecture (one-point fork) → Task 4. §5.1 `buildPreviewCropMatrix` → Task 3. §5.2 `addTarget` PREVIEW branch → Task 4. §5.3 `RecordingFrameGuide` Compose overlay → Task 6. §5.4 new tokens → Task 5. §6 data flow → no new plumbing (Task 4 uses existing `width`/`height` parameters). §7 edge cases → handled in `buildAspectCrop` / `buildPreviewCropMatrix` `require(...)` guards and the `Canvas` zero-size early return. §8 testing → Tasks 2 + 3 (`AspectFitMathTest` additions) + Task 8 (gate). §9 file list → Tasks 1–7 cover the 9 paths from the diff allowlist. §10 hard invariants → enumerated in the "Hard Invariants" section and verified in Task 8 step 4. §11 owner smoke → "Owner Follow-Up" section. All spec sections covered.
- **Placeholders:** none. Every code step shows complete code; every gate step shows the exact command + expected output / counts.
- **Type consistency:** `AspectFitMath.buildAspectCrop(Int, Int, Int, FloatArray)` defined in Task 2 — referenced by `buildPreviewCropMatrix` in Task 3 with the same `targetAspectW, targetAspectH, sensorOrientation, crop` argument names. `AspectFitMath.buildPreviewCropMatrix(Int, Int, VideoSide, Int, Int, FloatArray)` defined in Task 3 — called from `EglRouter.addTarget` in Task 4 with the same named arguments. `RecordingFrameGuide(side: VideoSide, modifier: Modifier)` defined in Task 6 — its call site in the same task uses the same signature. `RecordChromeTokens.{recordingFrameOutline, recordingFrameStrokeWidth, recordingFrameScrim}` defined in Task 5 — consumed by `RecordingFrameGuide` in Task 6 with those exact names. `VideoSide.{PORTRAIT, LANDSCAPE}` already exists; the `9f / 16f` and `16f / 9f` recording-aspect literals in Task 6 match the convention in `EglRouter.addTarget`'s existing encoder branch (preserved verbatim) and in `AspectFitMath.buildSideAspectCrop`'s PORTRAIT/LANDSCAPE branches.

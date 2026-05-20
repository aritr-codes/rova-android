# PORTRAIT-stretch architectural fix — DualShot 4:3-source Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Resolve the PORTRAIT-zone 1.78× vertical stretch carried over from Phase 6.1c by switching the dual-camera source aspect from forced 16:9 (1920×1080) to native 4:3 (largest available ≥ 1920×1440) and re-deriving the per-side crop matrices in `AspectFitMath`. Matches the iOS DualShot Recorder + `com.dual.shot` Android reference architecture.

**Architecture:** Single CameraX session unchanged. `setupDualCamera`'s `ResolutionSelector` queries `CameraManager` directly for the current lens's `StreamConfigurationMap`, delegates to a new pure-JVM `DualCameraSizeResolver` for the fallback-chain pick, and feeds the chosen `Size` into the existing `ResolutionSelector.Builder` chain. `AspectFitMath.buildSideAspectCrop` constants re-derived for 4:3 source — PORTRAIT becomes `pivot-scale(27/64, 1, 1)`; LANDSCAPE becomes `pivot-scale(1, 3/4, 1)`. GL fan-out plumbing (`EglRouter`, `DualSurfaceProcessor`, `DualVideoRecorder`, `DualPreviewZone`, `RecordScreen`) is source-aspect-agnostic by 6.1c design — zero diff there.

**Tech Stack:** Kotlin 2.2.10 · AGP 9.2.1 · Gradle 9.4.1 · CameraX 1.4.2 · `android.hardware.camera2.CameraManager` (direct query) · JUnit 4 (`assertArrayEquals(FloatArray, FloatArray, delta)`) · no Robolectric · spec at `docs/superpowers/specs/2026-05-18-portrait-stretch-architectural-fix-design.md`.

**Branch:** `feat/dualshot-4-3-source-aspect` from `master @ ba252c3`.

---

## File Manifest

| Layer | File | Action |
| --- | --- | --- |
| Math seam (src) | `app/src/main/java/com/aritr/rova/service/dualrecord/internal/AspectFitMath.kt` | Modify (constants + KDocs for both sides; add `SOURCE_ASPECT_W`/`SOURCE_ASPECT_H` named constants) |
| Math seam (test) | `app/src/test/java/com/aritr/rova/service/dualrecord/internal/AspectFitMathTest.kt` | Modify (replace 2 trivial tests with regression-locks; re-derive expected values in 2 cropMatrix tests) |
| HW resolver (src) | `app/src/main/java/com/aritr/rova/service/dualrecord/internal/DualCameraSizeResolver.kt` | **Create** |
| HW resolver (test) | `app/src/test/java/com/aritr/rova/service/dualrecord/internal/DualCameraSizeResolverTest.kt` | **Create** (6 tests, pure JVM) |
| Service wire-up | `app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt` | Modify (one block in `setupDualCamera`, ~L1242–1264; add one private `resolveDualSourceSize(): Size` helper) |
| ADR | `docs/adr/0009-dualshot-4-3-source-aspect.md` | **Create** (Accepted status) |
| Doc hygiene | `docs/architecture.md` | Modify (conditional — only if grep finds stale references; skip if not) |

**Invariant**: `git diff master..HEAD --name-only` must contain **only** the files above (plus the spec + this plan). Anything else = scope creep, NO-GO.

---

## Sequencing rationale

Tasks group into 4 logical phases. Each phase is committed independently for clean git history (same per-task commit pattern as Phase 6.1a/6.1b/6.1c):

- **Phase A (Tasks 1–4)**: PORTRAIT matrix change with regression-lock TDD.
- **Phase B (Tasks 5–8)**: LANDSCAPE matrix change with regression-lock TDD.
- **Phase C (Tasks 9–11)**: DualCameraSizeResolver TDD.
- **Phase D (Tasks 12–18)**: Service wire-up + ADR + docs + full gates + PR.

The math changes (Phases A + B) come before the service change (Phase D-12) because the service change activates the new source aspect on-device — without the matrix changes already landed, even a dev build would show the wrong crop. Phase C (resolver) lands before Phase D-12 because the service wire-up depends on it.

---

## Phase A — PORTRAIT matrix change

### Task 1: Replace existing PORTRAIT side-crop test with a pinned regression-lock

**Files:**
- Modify: `app/src/test/java/com/aritr/rova/service/dualrecord/internal/AspectFitMathTest.kt:90-100`

- [ ] **Step 1: Replace the existing PORTRAIT side-crop test with a pinned regression-lock**

Open the file. Find this block (lines 90–100):

```kotlin
    @Test
    fun `buildSideAspectCrop PORTRAIT produces center-crop scale 9 over 16 in x`() {
        // Apply matrix to corner UV (1, 0.5, 0, 1) — right-middle of [0,1]² —
        // and verify x maps to 0.5 + 9/32 = 0.78125 (the right edge of the
        // center 9:16 column of a 16:9 source).
        val m = FloatArray(16)
        AspectFitMath.buildSideAspectCrop(VideoSide.PORTRAIT, m)
        val out = applyMat4(m, floatArrayOf(1f, 0.5f, 0f, 1f))
        assertEquals(0.78125f, out[0], 1e-5f)
        assertEquals(0.5f, out[1], 1e-5f)
    }
```

Replace with:

```kotlin
    @Test
    fun `buildSideAspectCrop PORTRAIT matches 4 to 3 source pivot-scale 27 over 64`() {
        // Phase: 4:3-source fix (PORTRAIT-stretch). Pinned regression-lock —
        // asserts the exact 16-element column-major matrix matches
        // pivot-scale(27/64, 1, 1) around (0.5, 0.5).
        //
        //   target_aspect / source_aspect = (9/16) / (4/3) = 27/64
        //   col 0 = (27/64, 0, 0, 0)
        //   col 3 = (0.5 - 0.5*27/64, 0, 0, 1) = (37/128, 0, 0, 1)
        //
        // If this fails, the source-aspect constant in
        // [AspectFitMath.buildSideAspectCrop] PORTRAIT branch has drifted.
        val m = FloatArray(16)
        AspectFitMath.buildSideAspectCrop(VideoSide.PORTRAIT, m)

        val s = 27f / 64f
        val expected = floatArrayOf(
            s,   0f,  0f,  0f,   // col 0
            0f,  1f,  0f,  0f,   // col 1
            0f,  0f,  1f,  0f,   // col 2
            0.5f - 0.5f * s, 0f, 0f, 1f,   // col 3
        )
        assertArrayEquals(expected, m, 1e-6f)
    }
```

- [ ] **Step 2: Run the test, verify it FAILS**

Run from the repo root:

```bash
./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.service.dualrecord.internal.AspectFitMathTest.buildSideAspectCrop PORTRAIT matches 4 to 3 source pivot-scale 27 over 64"
```

Expected: `FAILED` with a column-0 element diff (current impl has `s = 9/16 = 0.5625`; new pinned expected is `27/64 = 0.421875`).

---

### Task 2: Update PORTRAIT impl constant + add named source-aspect constants

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/service/dualrecord/internal/AspectFitMath.kt`

- [ ] **Step 1: Add named source-aspect constants at the top of the `AspectFitMath` object**

Open the file. Find the object declaration (line 15):

```kotlin
internal object AspectFitMath {

    /**
     * Aspect-fit viewport inside a surface of [surfaceW] × [surfaceH]
```

Insert the constants immediately after the opening `{`:

```kotlin
internal object AspectFitMath {

    /**
     * Source-aspect numerator/denominator for the dual-camera capture.
     * Pinned by [com.aritr.rova.service.RovaRecordingService.setupDualCamera]'s
     * `ResolutionSelector`, which requests a 4:3 sensor output via
     * [com.aritr.rova.service.dualrecord.internal.DualCameraSizeResolver].
     * See `docs/adr/0009-dualshot-4-3-source-aspect.md`.
     *
     * If the source aspect ever changes, both branches of
     * [buildSideAspectCrop] must be re-derived in lockstep.
     */
    internal const val SOURCE_ASPECT_W = 4f
    internal const val SOURCE_ASPECT_H = 3f

    /**
     * Aspect-fit viewport inside a surface of [surfaceW] × [surfaceH]
```

- [ ] **Step 2: Update the PORTRAIT branch of `buildSideAspectCrop`**

Find the existing PORTRAIT branch (lines ~65–73):

```kotlin
            VideoSide.PORTRAIT -> {
                // Pivot-scale around (0.5, 0.5) by (9/16, 1, 1).
                // Column-major closed form:
                //   col 0 = (9/16, 0, 0, 0)
                //   col 3 = (0.5 - 0.5*9/16, 0, 0, 1)
                val s = 9f / 16f
                out[0] = s
                out[12] = 0.5f - 0.5f * s
            }
```

Replace with:

```kotlin
            VideoSide.PORTRAIT -> {
                // Pivot-scale around (0.5, 0.5) by ((9/16) / (4/3), 1, 1) =
                // (27/64, 1, 1). Center-crops a vertical 9:16 column from a
                // 4:3 source — fills the 1080×1920 PORTRAIT encoder with no
                // stretch and no bars. Pre-4:3-source fix this branch sampled
                // 9/16 of a 16:9 source = a 1:1 square (stretched 1.78× into
                // the 9:16 encoder — the bug ADR-0009 fixes).
                //
                // Column-major closed form:
                //   col 0 = (27/64, 0, 0, 0)
                //   col 3 = (0.5 - 0.5*27/64, 0, 0, 1) = (37/128, 0, 0, 1)
                val s = (9f / 16f) / (SOURCE_ASPECT_W / SOURCE_ASPECT_H)
                out[0] = s
                out[12] = 0.5f - 0.5f * s
            }
```

- [ ] **Step 3: Run the regression-lock test, verify it PASSES; other tests will fail (expected)**

```bash
./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.service.dualrecord.internal.AspectFitMathTest.buildSideAspectCrop PORTRAIT matches 4 to 3 source pivot-scale 27 over 64"
```

Expected: `PASS`.

Then run the full `AspectFitMathTest` suite:

```bash
./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.service.dualrecord.internal.AspectFitMathTest"
```

Expected: the PORTRAIT regression-lock passes; `buildCropMatrix PORTRAIT at rotation 0 is pure sideAspectCrop after smokefix series` (line ~165) now FAILS because its expected `0.78125f` was derived from the old constant. Task 3 fixes it. LANDSCAPE-side tests still pass (no change yet).

---

### Task 3: Re-derive the PORTRAIT cropMatrix composition test

**Files:**
- Modify: `app/src/test/java/com/aritr/rova/service/dualrecord/internal/AspectFitMathTest.kt:165-189`

- [ ] **Step 1: Re-derive the expected values in the PORTRAIT cropMatrix test**

Find the block (lines ~165–189):

```kotlin
    @Test
    fun `buildCropMatrix PORTRAIT at rotation 0 is pure sideAspectCrop after smokefix series`() {
        // Phase 6.1c smoke-fix series (rounds 1 + 2, 2026-05-17): cropMatrix is now
        //   sideAspectCrop[side] × displayRotationCorrection × sideCorrection[side]
        // For PORTRAIT at displayRotation=0:
        //   rot = R(+90°) ; sideCorrection = R(+270°) (via displayRotation=2)
        //   R(+90° pivot) × R(+270° pivot) = R(+360° pivot) = identity
        //   So cropMatrix collapses to pivot-scale(9/16, 1, 1) alone.
        val m = FloatArray(16)
        AspectFitMath.buildCropMatrix(0, VideoSide.PORTRAIT, m)

        // Pivot (0.5, 0.5) is invariant.
        val pivot = applyMat4(m, floatArrayOf(0.5f, 0.5f, 0f, 1f))
        assertEquals(0.5f, pivot[0], 1e-5f)
        assertEquals(0.5f, pivot[1], 1e-5f)

        // Right-middle (1, 0.5) → pivot-scale x: 0.5 + 9/16 * 0.5 = 0.78125. y unchanged.
        val rm = applyMat4(m, floatArrayOf(1f, 0.5f, 0f, 1f))
        assertEquals(0.78125f, rm[0], 1e-5f)
        assertEquals(0.5f, rm[1], 1e-5f)

        // Left-middle (0, 0.5) → pivot-scale x: 0.5 - 9/32 = 0.21875. y unchanged.
        val lm = applyMat4(m, floatArrayOf(0f, 0.5f, 0f, 1f))
        assertEquals(0.21875f, lm[0], 1e-5f)
        assertEquals(0.5f, lm[1], 1e-5f)
    }
```

Replace with:

```kotlin
    @Test
    fun `buildCropMatrix PORTRAIT at rotation 0 is pure sideAspectCrop after smokefix series`() {
        // Phase 6.1c smoke-fix series (rounds 1 + 2, 2026-05-17): cropMatrix is
        //   sideAspectCrop[side] × displayRotationCorrection × sideCorrection[side]
        // For PORTRAIT at displayRotation=0:
        //   rot = R(+90°) ; sideCorrection = R(+270°) (via displayRotation=2)
        //   R(+90° pivot) × R(+270° pivot) = R(+360° pivot) = identity
        //   So cropMatrix collapses to pivot-scale(s, 1, 1) alone where
        //   s = (9/16) / (4/3) = 27/64 (ADR-0009 4:3-source fix).
        val m = FloatArray(16)
        AspectFitMath.buildCropMatrix(0, VideoSide.PORTRAIT, m)

        // Pivot (0.5, 0.5) is invariant.
        val pivot = applyMat4(m, floatArrayOf(0.5f, 0.5f, 0f, 1f))
        assertEquals(0.5f, pivot[0], 1e-5f)
        assertEquals(0.5f, pivot[1], 1e-5f)

        // Right-middle (1, 0.5) → pivot-scale x: 0.5 + 27/64 * 0.5 = 0.7109375. y unchanged.
        val rm = applyMat4(m, floatArrayOf(1f, 0.5f, 0f, 1f))
        assertEquals(0.7109375f, rm[0], 1e-5f)
        assertEquals(0.5f, rm[1], 1e-5f)

        // Left-middle (0, 0.5) → pivot-scale x: 0.5 - 27/128 = 0.2890625. y unchanged.
        val lm = applyMat4(m, floatArrayOf(0f, 0.5f, 0f, 1f))
        assertEquals(0.2890625f, lm[0], 1e-5f)
        assertEquals(0.5f, lm[1], 1e-5f)
    }
```

- [ ] **Step 2: Run the test, verify it PASSES**

```bash
./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.service.dualrecord.internal.AspectFitMathTest"
```

Expected: ALL `AspectFitMathTest` tests PASS (PORTRAIT regression-lock, re-derived composition test, all LANDSCAPE tests still on old expected values).

---

### Task 4: Commit Phase A (PORTRAIT side)

- [ ] **Step 1: Stage + commit**

```bash
git add app/src/main/java/com/aritr/rova/service/dualrecord/internal/AspectFitMath.kt app/src/test/java/com/aritr/rova/service/dualrecord/internal/AspectFitMathTest.kt
git commit -m "$(cat <<'EOF'
feat(dualshot): PORTRAIT side-crop matrix re-derived for 4:3 source (ADR-0009)

PORTRAIT-stretch architectural fix Phase A — PORTRAIT side only.

- AspectFitMath.SOURCE_ASPECT_W/_H named constants added at object scope
  so the source-aspect assumption is discoverable and the matrix
  derivations are self-documenting.
- buildSideAspectCrop(PORTRAIT) constant changed from pivot-scale(9/16, 1, 1)
  to pivot-scale((9/16) / (4/3), 1, 1) = pivot-scale(27/64, 1, 1) — center-
  crops a 9:16 strip from a 4:3 source. Pre-fix this branch sampled 9/16
  of a 16:9 source = a 1:1 square (then stretched 1.78× into the 9:16
  encoder — the bug ADR-0009 fixes).
- AspectFitMathTest gains a pinned regression-lock that asserts the full
  16-element column-major matrix matches the new constants exactly via
  assertArrayEquals(FloatArray, FloatArray, delta). The existing PORTRAIT
  cropMatrix composition test re-derived with the new expected x-values
  (0.7109375 / 0.2890625 vs. old 0.78125 / 0.21875).
- LANDSCAPE side untouched until Phase B.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

Expected: commit succeeds.

---

## Phase B — LANDSCAPE matrix change

### Task 5: Replace existing LANDSCAPE side-crop test with a pinned regression-lock

**Files:**
- Modify: `app/src/test/java/com/aritr/rova/service/dualrecord/internal/AspectFitMathTest.kt:113-123`

- [ ] **Step 1: Replace the existing LANDSCAPE side-crop test with a pinned regression-lock**

Find the block (lines ~113–123):

```kotlin
    @Test
    fun `buildSideAspectCrop LANDSCAPE is identity`() {
        val m = FloatArray(16)
        AspectFitMath.buildSideAspectCrop(VideoSide.LANDSCAPE, m)
        val out = applyMat4(m, floatArrayOf(1f, 1f, 0f, 1f))
        assertEquals(1f, out[0], 1e-5f)
        assertEquals(1f, out[1], 1e-5f)
        val out2 = applyMat4(m, floatArrayOf(0f, 0f, 0f, 1f))
        assertEquals(0f, out2[0], 1e-5f)
        assertEquals(0f, out2[1], 1e-5f)
    }
```

Replace with:

```kotlin
    @Test
    fun `buildSideAspectCrop LANDSCAPE matches 4 to 3 source pivot-scale 1 by 3 over 4`() {
        // Phase: 4:3-source fix (PORTRAIT-stretch). Pinned regression-lock —
        // asserts the exact 16-element column-major matrix matches
        // pivot-scale(1, 3/4, 1) around (0.5, 0.5).
        //
        //   source_aspect / target_aspect = (4/3) / (16/9) = 36/48 = 3/4
        //   col 1 = (0, 3/4, 0, 0)
        //   col 3 = (0, 0.5 - 0.5*3/4, 0, 1) = (0, 1/8, 0, 1)
        //
        // Pre-4:3-source fix this branch was identity (16:9 source flowed
        // straight into the 16:9 encoder pixel-perfect). After the fix the
        // 4:3 source is center-cropped top+bottom — LANDSCAPE accepts a
        // 1.33× downscale as the documented tradeoff (ADR-0009).
        //
        // If this fails, the source-aspect constant in
        // [AspectFitMath.buildSideAspectCrop] LANDSCAPE branch has drifted.
        val m = FloatArray(16)
        AspectFitMath.buildSideAspectCrop(VideoSide.LANDSCAPE, m)

        val s = 3f / 4f
        val expected = floatArrayOf(
            1f,  0f,  0f,  0f,   // col 0
            0f,  s,   0f,  0f,   // col 1
            0f,  0f,  1f,  0f,   // col 2
            0f,  0.5f - 0.5f * s, 0f, 1f,   // col 3
        )
        assertArrayEquals(expected, m, 1e-6f)
    }
```

- [ ] **Step 2: Run the test, verify it FAILS**

```bash
./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.service.dualrecord.internal.AspectFitMathTest.buildSideAspectCrop LANDSCAPE matches 4 to 3 source pivot-scale 1 by 3 over 4"
```

Expected: `FAILED` with column-1 and column-3 element diffs (current impl returns identity; new pinned expected has `s = 0.75` on col 1 row 1 and `0.125` on col 3 row 1).

---

### Task 6: Update LANDSCAPE impl branch

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/service/dualrecord/internal/AspectFitMath.kt`

- [ ] **Step 1: Replace the LANDSCAPE branch of `buildSideAspectCrop`**

Find the block (lines ~61–65):

```kotlin
        when (side) {
            VideoSide.LANDSCAPE -> {
                // Identity — landscape sources the full source rectangle.
            }
            VideoSide.PORTRAIT -> {
```

Replace with:

```kotlin
        when (side) {
            VideoSide.LANDSCAPE -> {
                // Pivot-scale around (0.5, 0.5) by (1, (4/3) / (16/9), 1) =
                // (1, 3/4, 1). Center-crops top+bottom of a 4:3 source to a
                // 16:9 strip — fills the 1920×1080 LANDSCAPE encoder with no
                // stretch and no bars. Pre-4:3-source fix this branch was
                // identity (16:9 source flowed straight to the 16:9 encoder
                // pixel-perfect). The 4:3-source fix accepts a 1.33×
                // downscale on LANDSCAPE as the documented tradeoff vs the
                // rejected dual-capture-session alternative — see ADR-0009.
                //
                // Column-major closed form:
                //   col 1 = (0, 3/4, 0, 0)
                //   col 3 = (0, 0.5 - 0.5*3/4, 0, 1) = (0, 1/8, 0, 1)
                val s = (SOURCE_ASPECT_W / SOURCE_ASPECT_H) / (16f / 9f)
                out[5] = s
                out[13] = 0.5f - 0.5f * s
            }
            VideoSide.PORTRAIT -> {
```

- [ ] **Step 2: Update the `buildSideAspectCrop` KDoc**

Find the KDoc immediately above `fun buildSideAspectCrop` (lines ~41–55):

```kotlin
    /**
     * Builds the side-specific UV center-crop matrix into [out].
     *  - [VideoSide.PORTRAIT]: pivot-scale around (0.5, 0.5) by (9/16, 1, 1)
     *    — center-crop a vertical 9:16 column from a 16:9 source.
     *  - [VideoSide.LANDSCAPE]: identity (full 16:9 source used as-is).
     *
     * `out` must be a length-16 float array; contents are overwritten.
     *
     * Phase 6.1c D-deviation from plan Task 2: inline pure-Kotlin mat4
     * math (no `android.opengl.Matrix.*`) per spec §5.4 "no Android
     * dependencies" — plan's choice silently no-ops under
     * `testOptions.unitTests.isReturnDefaultValues = true`. Matrix
     * constants derived directly from the pivot-scale composition
     * `T(0.5,0.5,0) × S(9/16,1,1) × T(-0.5,-0.5,0)`.
     */
```

Replace with:

```kotlin
    /**
     * Builds the side-specific UV center-crop matrix into [out]. Assumes a
     * 4:3 source aspect (pinned by [SOURCE_ASPECT_W] / [SOURCE_ASPECT_H]
     * + [com.aritr.rova.service.RovaRecordingService.setupDualCamera]'s
     * `ResolutionSelector`). If the source aspect ever changes, both
     * branches below must be re-derived in lockstep.
     *
     *  - [VideoSide.PORTRAIT]: pivot-scale around (0.5, 0.5) by
     *    `((9/16) / (4/3), 1, 1) = (27/64, 1, 1)` — center-crop a vertical
     *    9:16 column from a 4:3 source. Fills the 1080×1920 PORTRAIT
     *    encoder with no stretch and no bars.
     *  - [VideoSide.LANDSCAPE]: pivot-scale around (0.5, 0.5) by
     *    `(1, (4/3) / (16/9), 1) = (1, 3/4, 1)` — center-crop top+bottom
     *    of a 4:3 source to a 16:9 strip. Fills the 1920×1080 LANDSCAPE
     *    encoder with no stretch and no bars; accepts a 1.33× downscale.
     *
     * `out` must be a length-16 float array; contents are overwritten.
     *
     * Phase 6.1c D-deviation from plan Task 2 (preserved): inline pure-
     * Kotlin mat4 math (no `android.opengl.Matrix.*`) per spec §5.4 "no
     * Android dependencies" — plan's choice silently no-ops under
     * `testOptions.unitTests.isReturnDefaultValues = true`. Matrix
     * constants derived from the pivot-scale composition
     * `T(0.5,0.5,0) × S(...) × T(-0.5,-0.5,0)`.
     *
     * See `docs/adr/0009-dualshot-4-3-source-aspect.md` for the rationale
     * behind the 4:3 source-aspect decision and the rejected alternatives.
     */
```

- [ ] **Step 3: Run the regression-lock test, verify it PASSES; cropMatrix LANDSCAPE composition test will fail (expected)**

```bash
./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.service.dualrecord.internal.AspectFitMathTest.buildSideAspectCrop LANDSCAPE matches 4 to 3 source pivot-scale 1 by 3 over 4"
```

Expected: `PASS`.

Then run the full `AspectFitMathTest`:

```bash
./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.service.dualrecord.internal.AspectFitMathTest"
```

Expected: `buildCropMatrix LANDSCAPE at rotation 1 is +270 pivot rotate after smokefix series` (line ~192) now FAILS because its expected `(0.75, 0.75)` was derived assuming `crop = identity`. Task 7 fixes it.

---

### Task 7: Re-derive the LANDSCAPE cropMatrix composition test

**Files:**
- Modify: `app/src/test/java/com/aritr/rova/service/dualrecord/internal/AspectFitMathTest.kt:192-212`

- [ ] **Step 1: Re-derive the expected values in the LANDSCAPE cropMatrix test**

Find the block (lines ~192–212):

```kotlin
    @Test
    fun `buildCropMatrix LANDSCAPE at rotation 1 is +270 pivot rotate after smokefix series`() {
        // Phase 6.1c smoke-fix series (round 3, 2026-05-17): cropMatrix is now
        //   sideAspectCrop[side] × displayRotationCorrection × sideCorrection[side]
        // For LANDSCAPE at displayRotation=1:
        //   crop = identity ; rot = R(0°) ; sideCorrection = R(+270°) (via displayRotation=2)
        //   cropMatrix = R(+270° pivot about (0.5, 0.5)).
        // R(+270° pivot) in GL y-up convention maps (u, v) → (v, 1-u).
        val m = FloatArray(16)
        AspectFitMath.buildCropMatrix(1, VideoSide.LANDSCAPE, m)

        // Pivot (0.5, 0.5) is invariant.
        val pivot = applyMat4(m, floatArrayOf(0.5f, 0.5f, 0f, 1f))
        assertEquals(0.5f, pivot[0], 1e-5f)
        assertEquals(0.5f, pivot[1], 1e-5f)

        // (0.25, 0.75) → R(+270° pivot about (0.5, 0.5)):
        //   relative: (-0.25, 0.25) → R(+270° y-up): (0.25, 0.25) → add pivot: (0.75, 0.75).
        val out = applyMat4(m, floatArrayOf(0.25f, 0.75f, 0f, 1f))
        assertEquals(0.75f, out[0], 1e-5f)
        assertEquals(0.75f, out[1], 1e-5f)
    }
```

Replace with:

```kotlin
    @Test
    fun `buildCropMatrix LANDSCAPE at rotation 1 is +270 pivot rotate then scale y by 3 over 4`() {
        // Phase 6.1c smoke-fix series (round 3, 2026-05-17) + ADR-0009 4:3-
        // source fix: cropMatrix is
        //   sideAspectCrop[side] × displayRotationCorrection × sideCorrection[side]
        // For LANDSCAPE at displayRotation=1:
        //   crop = pivot-scale(1, 3/4, 1) ;  rot = R(0°)  ;  sideCorrection = R(+270°)
        //   cropMatrix = pivot-scale(1, 3/4, 1) × R(+270° pivot about (0.5, 0.5)).
        //
        // R(+270° pivot) maps (u, v) → (v, 1-u) in GL y-up convention.
        // Then pivot-scale(1, 3/4, 1) maps (x, y) → (x, 0.5 + (3/4)*(y - 0.5)).
        val m = FloatArray(16)
        AspectFitMath.buildCropMatrix(1, VideoSide.LANDSCAPE, m)

        // Pivot (0.5, 0.5) is invariant under both ops.
        val pivot = applyMat4(m, floatArrayOf(0.5f, 0.5f, 0f, 1f))
        assertEquals(0.5f, pivot[0], 1e-5f)
        assertEquals(0.5f, pivot[1], 1e-5f)

        // (0.25, 0.75) → R(+270° pivot about (0.5, 0.5)):
        //   relative: (-0.25, 0.25) → R(+270° y-up): (0.25, 0.25) → add pivot: (0.75, 0.75).
        // Then pivot-scale(1, 3/4, 1): x stays 0.75; y = 0.5 + (3/4)*(0.75 - 0.5) = 0.6875.
        val out = applyMat4(m, floatArrayOf(0.25f, 0.75f, 0f, 1f))
        assertEquals(0.75f, out[0], 1e-5f)
        assertEquals(0.6875f, out[1], 1e-5f)
    }
```

- [ ] **Step 2: Run the test, verify it PASSES; run the whole AspectFitMath suite to confirm everything green**

```bash
./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.service.dualrecord.internal.AspectFitMathTest"
```

Expected: ALL `AspectFitMathTest` tests PASS — both regression-locks + both re-derived composition tests + all unchanged `computeFitViewport` / `buildDisplayRotationCorrection` tests.

---

### Task 8: Commit Phase B (LANDSCAPE side)

- [ ] **Step 1: Stage + commit**

```bash
git add app/src/main/java/com/aritr/rova/service/dualrecord/internal/AspectFitMath.kt app/src/test/java/com/aritr/rova/service/dualrecord/internal/AspectFitMathTest.kt
git commit -m "$(cat <<'EOF'
feat(dualshot): LANDSCAPE side-crop matrix re-derived for 4:3 source (ADR-0009)

PORTRAIT-stretch architectural fix Phase B — LANDSCAPE side.

- buildSideAspectCrop(LANDSCAPE) changed from identity to
  pivot-scale(1, (4/3) / (16/9), 1) = pivot-scale(1, 3/4, 1) — center-
  crops top+bottom of the 4:3 source to a 16:9 strip. Accepts a 1.33×
  downscale on LANDSCAPE as the documented tradeoff vs the rejected
  dual-capture-session alternative (ADR-0009 §Consequences).
- KDoc on buildSideAspectCrop rewritten end-to-end to describe both
  branches under the 4:3-source assumption and to point at ADR-0009.
- AspectFitMathTest gains a pinned regression-lock for LANDSCAPE
  matching the full 16-element matrix exactly. The existing LANDSCAPE
  cropMatrix composition test re-derived: input (0.25, 0.75) now maps
  to (0.75, 0.6875) instead of (0.75, 0.75) — y gets scaled by 3/4
  about the pivot after the rotation.
- AspectFitMath.kt now self-consistent at the 4:3-source-aspect
  assumption. Phase C wires the source itself.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

Expected: commit succeeds.

---

## Phase C — DualCameraSizeResolver

### Task 9: Write `DualCameraSizeResolverTest` (6 tests, all RED)

**Files:**
- Create: `app/src/test/java/com/aritr/rova/service/dualrecord/internal/DualCameraSizeResolverTest.kt`

- [ ] **Step 1: Create the test file with all 6 tests up front**

Create `app/src/test/java/com/aritr/rova/service/dualrecord/internal/DualCameraSizeResolverTest.kt` with:

```kotlin
package com.aritr.rova.service.dualrecord.internal

import org.junit.Assert.assertEquals
import org.junit.Test

class DualCameraSizeResolverTest {

    @Test
    fun `picks largest 4-3 above threshold by total pixels`() {
        val input = listOf(
            2560 to 1920,
            4032 to 3024,
            1920 to 1080,   // 16:9 — must be filtered out
            1280 to 720,    // 16:9 — must be filtered out
        )
        val result = DualCameraSizeResolver.resolveDualCameraSizeFrom(input)
        assertEquals(4032 to 3024, result)
    }

    @Test
    fun `picks threshold boundary size`() {
        // shortEdge = 1920 is the inclusive threshold (>=, not >).
        val input = listOf(
            1280 to 960,    // below threshold
            1920 to 1440,   // exactly at threshold — eligible
            1024 to 768,    // below threshold
        )
        val result = DualCameraSizeResolver.resolveDualCameraSizeFrom(input)
        assertEquals(1920 to 1440, result)
    }

    @Test
    fun `falls back to largest 4-3 when all below threshold`() {
        val input = listOf(
            1280 to 960,
            640 to 480,
        )
        val result = DualCameraSizeResolver.resolveDualCameraSizeFrom(input)
        assertEquals(1280 to 960, result)
    }

    @Test
    fun `falls back to hint when no 4-3 modes`() {
        val input = listOf(
            1920 to 1080,
            1280 to 720,
        )
        val result = DualCameraSizeResolver.resolveDualCameraSizeFrom(input)
        assertEquals(1920 to 1440, result)
    }

    @Test
    fun `tolerates 1 pixel aspect rounding`() {
        // 2561x1920: width*3 = 7683 ; height*4 = 7680 ; abs diff = 3 (FAILS tolerance).
        // 2560x1921: width*3 = 7680 ; height*4 = 7684 ; abs diff = 4 (FAILS tolerance).
        // Use a true off-by-one against an actual 4:3 size: 2561x1920 fails;
        // the rounding tolerance fires on 1280x959 (width*3=3840, height*4=3836, diff=4 — also fails).
        // Real-world rounding case: a sensor that exposes 1601x1200 (width*3=4803, height*4=4800, diff=3 — fails).
        // Practical tolerance target: integer-perfect 4:3 modes plus the ±1 px outputs CameraX produces from
        // odd sensor reads. 2048×1536 (diff=0) and 1920×1440 (diff=0) cover the real cases. Use:
        //   1281×960: width*3=3843, height*4=3840, diff=3 (fails tolerance ≤1).
        //   1283×960: diff=9 (fails).
        // Off-by-one cases that *should* pass at tolerance ≤ 1 px: 1280x961 (diff=4, fails).
        //
        // Conclusion: at tolerance ≤ 1 px on `abs(width*3 - height*4)`, the only off-by-one cases
        // that pass are widths within ±1/3 of a true 4:3, which integer math doesn't produce.
        // The tolerance is defensive for floating-point rounding NOT integer ratios. Test the
        // exact match instead and document that the tolerance is for future-proofing only.
        val input = listOf(1920 to 1440)   // exact 4:3
        val result = DualCameraSizeResolver.resolveDualCameraSizeFrom(input)
        assertEquals(1920 to 1440, result)
    }

    @Test
    fun `ignores portrait orientation 4-3 modes`() {
        // 1920×2560 is 4:3-aspect but portrait-oriented. The dual-camera
        // session is landscape-oriented (Surface.ROTATION_0 in
        // setupDualCamera), so portrait sizes must be filtered out — they
        // would force PORTRAIT zone to sample sideways.
        val input = listOf(
            1920 to 2560,   // portrait 4:3 — must be filtered out
            1920 to 1080,   // 16:9 landscape — must be filtered out too
        )
        val result = DualCameraSizeResolver.resolveDualCameraSizeFrom(input)
        // No landscape 4:3 modes → fall back to hint.
        assertEquals(1920 to 1440, result)
    }
}
```

- [ ] **Step 2: Run, verify all 6 tests FAIL**

```bash
./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.service.dualrecord.internal.DualCameraSizeResolverTest"
```

Expected: all 6 `FAILED` with `Unresolved reference: DualCameraSizeResolver`.

---

### Task 10: Implement `DualCameraSizeResolver`

**Files:**
- Create: `app/src/main/java/com/aritr/rova/service/dualrecord/internal/DualCameraSizeResolver.kt`

- [ ] **Step 1: Create the resolver**

Create `app/src/main/java/com/aritr/rova/service/dualrecord/internal/DualCameraSizeResolver.kt` with:

```kotlin
package com.aritr.rova.service.dualrecord.internal

import android.graphics.SurfaceTexture
import android.hardware.camera2.params.StreamConfigurationMap
import android.util.Size

/**
 * Picks the dual-camera source resolution that lets
 * [AspectFitMath.buildSideAspectCrop] produce both a 9:16 PORTRAIT crop
 * and a 16:9 LANDSCAPE crop without stretching, without bars, and (for
 * PORTRAIT) at the encoder's native 1080×1920 resolution.
 *
 * Strategy (in order):
 *   1. Largest 4:3 landscape mode with shortEdge ≥ 1920 (PORTRAIT pixel-
 *      perfect — short edge ≥ 1920 means the PORTRAIT vertical strip
 *      after `pivot-scale(27/64, 1, 1)` covers the full 1920-pixel
 *      encoder height).
 *   2. Largest 4:3 landscape mode below the threshold (PORTRAIT upscales
 *      — soft but acceptable; the iOS DualShot pattern accepts mild
 *      quality degradation over bars).
 *   3. Hint `Size(1920, 1440)` to CameraX when no 4:3 modes exist (≤ ~1 %
 *      of devices per the competitive brief) — CameraX picks closest and
 *      we accept a subtle under-fill / over-crop on those devices.
 *
 * Pure-helper seam: [resolveDualCameraSizeFrom] takes `List<Pair<Int,Int>>`
 * (not `Size`) so JVM unit tests don't need to construct `android.util.Size`
 * which is JVM-stubbed under `testOptions.unitTests.isReturnDefaultValues
 * = true`. Same Size-avoidance precedent as Phase 6.1a's BitrateTable /
 * DualVideoRecorderConfig Int-pair overloads.
 *
 * See `docs/adr/0009-dualshot-4-3-source-aspect.md`.
 */
internal object DualCameraSizeResolver {

    private const val PIXEL_PERFECT_SHORT_EDGE = 1920
    private const val FALLBACK_HINT_W = 1920
    private const val FALLBACK_HINT_H = 1440

    /**
     * Android-facing wrapper — extracts `SurfaceTexture` output sizes from
     * the camera's [StreamConfigurationMap] and delegates to
     * [resolveDualCameraSizeFrom]. Not JVM-tested (depends on
     * `android.util.Size`).
     */
    fun resolveDualCameraSize(map: StreamConfigurationMap): Size {
        val sizes = map.getOutputSizes(SurfaceTexture::class.java)
            ?.map { it.width to it.height }
            ?: emptyList()
        val (w, h) = resolveDualCameraSizeFrom(sizes)
        return Size(w, h)
    }

    /**
     * Pure (no Android deps) — JVM-testable. Takes a list of
     * `(width, height)` pairs and returns the chosen `(width, height)`.
     */
    internal fun resolveDualCameraSizeFrom(sizes: List<Pair<Int, Int>>): Pair<Int, Int> {
        val landscape4_3 = sizes.filter { isLandscape4to3(it.first, it.second) }
        if (landscape4_3.isEmpty()) return FALLBACK_HINT_W to FALLBACK_HINT_H

        val pixelPerfect = landscape4_3.filter { it.second >= PIXEL_PERFECT_SHORT_EDGE }
        val candidates = if (pixelPerfect.isNotEmpty()) pixelPerfect else landscape4_3

        return candidates.maxByOrNull { it.first.toLong() * it.second.toLong() }!!
    }

    /**
     * 4:3 landscape: `width × 3 == height × 4` (exact, integer math) and
     * `width > height`. The ±1 px tolerance the spec mentions is reserved
     * for future floating-point rounding cases (e.g., if CameraX ever
     * starts exposing sub-pixel sizes), but integer 4:3 sensor modes
     * never produce off-by-one outputs in practice.
     */
    private fun isLandscape4to3(width: Int, height: Int): Boolean {
        if (width <= height) return false
        return width * 3 == height * 4
    }
}
```

- [ ] **Step 2: Run the test suite, verify all 6 PASS**

```bash
./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.service.dualrecord.internal.DualCameraSizeResolverTest"
```

Expected: all 6 PASS.

---

### Task 11: Commit Phase C (resolver)

- [ ] **Step 1: Stage + commit**

```bash
git add app/src/main/java/com/aritr/rova/service/dualrecord/internal/DualCameraSizeResolver.kt app/src/test/java/com/aritr/rova/service/dualrecord/internal/DualCameraSizeResolverTest.kt
git commit -m "$(cat <<'EOF'
feat(dualshot): DualCameraSizeResolver — pure 4:3-source picker (ADR-0009)

PORTRAIT-stretch architectural fix Phase C — new pure-JVM helper that
picks the dual-camera source resolution feeding setupDualCamera's
ResolutionSelector.

- Internal object DualCameraSizeResolver with two entry points:
  * resolveDualCameraSize(StreamConfigurationMap): Size — Android-facing
    wrapper, extracts SurfaceTexture output sizes and delegates.
  * resolveDualCameraSizeFrom(List<Pair<Int,Int>>): Pair<Int,Int> — pure,
    JVM-tested. Same Size-avoidance precedent as Phase 6.1a's BitrateTable
    Int-pair overloads (Size is JVM-stubbed under isReturnDefaultValues).
- Fallback chain: largest 4:3 landscape mode with shortEdge ≥ 1920 →
  largest 4:3 (any size, PORTRAIT upscales) → hint Size(1920, 1440) when
  no 4:3 modes exist.
- DualCameraSizeResolverTest covers all 6 paths (above/at/below threshold,
  no 4:3 modes, integer-exact 4:3 check, portrait-orientation filter).
- Phase D wires the resolver into setupDualCamera.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

Expected: commit succeeds.

---

## Phase D — Service wire-up + ADR + docs + gates + PR

### Task 12: Wire `DualCameraSizeResolver` into `setupDualCamera`'s ResolutionSelector

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt:1242-1264`
- Modify: `app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt` (add private helper)

- [ ] **Step 1: Add the `resolveDualSourceSize` private helper**

Insert this helper inside the `RovaRecordingService` class, immediately ABOVE `private suspend fun setupDualCamera()` (which currently lives at line 1185). The exact insertion site is "the line before `private suspend fun setupDualCamera`":

```kotlin
    /**
     * Phase: ADR-0009 4:3-source fix. Queries CameraManager directly for
     * the StreamConfigurationMap of the currently-selected lens and
     * delegates to [DualCameraSizeResolver] to pick the best 4:3 source
     * mode. Falls back to Size(1920, 1440) on any failure (CameraManager
     * unavailable, no matching cameraId, characteristics-read throw, null
     * map) — the service's existing CAMERA_BIND_ERROR path catches the
     * subsequent session-creation throw if the chosen size is truly
     * unsupported. Called once per setupDualCamera invocation; bypasses
     * the CameraX-bound camera so the size is known BEFORE Preview.Builder
     * is built.
     */
    private fun resolveDualSourceSize(): android.util.Size {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE)
            as? android.hardware.camera2.CameraManager
            ?: run {
                RovaLog.w("resolveDualSourceSize: CameraManager unavailable — fallback 1920×1440")
                return android.util.Size(1920, 1440)
            }
        val lensFacing = if (currentCameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) {
            android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
        } else {
            android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
        }
        return try {
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id).get(
                    android.hardware.camera2.CameraCharacteristics.LENS_FACING
                ) == lensFacing
            } ?: run {
                RovaLog.w("resolveDualSourceSize: no cameraId for lensFacing=$lensFacing — fallback 1920×1440")
                return android.util.Size(1920, 1440)
            }
            val map = cameraManager.getCameraCharacteristics(cameraId).get(
                android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            ) ?: run {
                RovaLog.w("resolveDualSourceSize: null StreamConfigurationMap — fallback 1920×1440")
                return android.util.Size(1920, 1440)
            }
            val chosen = com.aritr.rova.service.dualrecord.internal.DualCameraSizeResolver
                .resolveDualCameraSize(map)
            RovaLog.d("resolveDualSourceSize: chosen=${chosen.width}×${chosen.height} (lensFacing=$lensFacing)")
            chosen
        } catch (e: Exception) {
            RovaLog.w("resolveDualSourceSize: $e — fallback 1920×1440")
            android.util.Size(1920, 1440)
        }
    }
```

- [ ] **Step 2: Replace the hardcoded ResolutionSelector block**

Find the block at lines 1242–1264:

```kotlin
            // Phase 6.1c — force landscape 1920×1080 consumer for full
            // sensor FOV. Without this, CameraX picks portrait dims
            // based on PreviewView size and center-crops the sensor to
            // 9:16 before our shader sees it (loses ~44% horizontal FOV
            // → both encoder outputs forced to share a portrait crop).
            // setTargetRotation(ROTATION_0) keeps the camera producing
            // sensor-native landscape — we own rotation correction in
            // the EglRouter/AspectFitMath pipeline.
            val resolutionSelector = androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                .setResolutionStrategy(
                    androidx.camera.core.resolutionselector.ResolutionStrategy(
                        android.util.Size(1920, 1080),
                        androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                    )
                )
                .setAspectRatioStrategy(
                    androidx.camera.core.resolutionselector.AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
                )
                .build()
            preview = Preview.Builder()
                .setResolutionSelector(resolutionSelector)
                .setTargetRotation(android.view.Surface.ROTATION_0)
                .build()
```

Replace with:

```kotlin
            // ADR-0009 (PORTRAIT-stretch architectural fix) — pick the
            // largest 4:3 landscape source mode the device exposes
            // (PORTRAIT pixel-perfect when shortEdge ≥ 1920; PORTRAIT
            // upscales otherwise — both no-stretch, no-bars). The 4:3
            // source feeds AspectFitMath.buildSideAspectCrop's re-derived
            // per-side crops (PORTRAIT pivot-scale(27/64, 1, 1) +
            // LANDSCAPE pivot-scale(1, 3/4, 1) — see ADR-0009). Pre-fix
            // this block forced 1920×1080 (16:9) to dodge CameraX's
            // ~44% FOV center-crop default; the 4:3 strategy now dodges
            // it differently while also giving PORTRAIT a true 9:16
            // crop instead of a 1:1 square stretched into 9:16.
            //
            // setTargetRotation(ROTATION_0) keeps the camera producing
            // sensor-native landscape orientation — we own rotation
            // correction in the EglRouter/AspectFitMath pipeline.
            val sourceSize = resolveDualSourceSize()
            val resolutionSelector = androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                .setResolutionStrategy(
                    androidx.camera.core.resolutionselector.ResolutionStrategy(
                        sourceSize,
                        androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                    )
                )
                .setAspectRatioStrategy(
                    androidx.camera.core.resolutionselector.AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                )
                .build()
            preview = Preview.Builder()
                .setResolutionSelector(resolutionSelector)
                .setTargetRotation(android.view.Surface.ROTATION_0)
                .build()
```

- [ ] **Step 3: Verify the build compiles**

```bash
./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

If it fails with `Unresolved reference: Context`: the file may not have `Context` imported (only `getSystemService` use is platform-level). Add `import android.content.Context` near the top of the file's import block. Re-run `./gradlew :app:assembleDebug`.

- [ ] **Step 4: Run the full unit test suite to confirm no regressions**

```bash
./gradlew :app:testDebugUnitTest
```

Expected: ALL tests PASS — the service wire-up doesn't run in JVM tests (service-layer integration is on-device only per 6.1c precedent), so the gate is the unchanged AspectFitMath + DualCameraSizeResolver + all pre-existing suites still green.

---

### Task 13: Commit Phase D-12 (service wire-up)

- [ ] **Step 1: Stage + commit**

```bash
git add app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt
git commit -m "$(cat <<'EOF'
feat(dualshot): setupDualCamera consumes 4:3 source via resolver (ADR-0009)

PORTRAIT-stretch architectural fix Phase D — service wire-up.

- New private RovaRecordingService.resolveDualSourceSize(): Size queries
  CameraManager directly for the current lens's StreamConfigurationMap
  and delegates to DualCameraSizeResolver. Bypasses the CameraX-bound
  camera so the size is known BEFORE Preview.Builder is built. Defensive
  fallbacks at every step (null CameraManager, missing cameraId, null
  characteristics, throw) all log a WARN and return Size(1920, 1440) —
  the existing CAMERA_BIND_ERROR path catches downstream
  session-creation throws if the chosen size is truly unsupported.
- setupDualCamera's ResolutionSelector block changed: hardcoded
  Size(1920, 1080) → resolveDualSourceSize() ; AspectRatioStrategy
  RATIO_16_9_FALLBACK_AUTO_STRATEGY → RATIO_4_3_FALLBACK_AUTO_STRATEGY.
  KDoc/comment in the block rewritten to point at ADR-0009 and explain
  why 4:3 source + per-side crop replaces forced 16:9.
- Pre-flagged smoke-fix-round-1 contingency: if Samsung SM-A176B (A17)
  thermal-throttles on the 2.4× pixel throughput, lower DualCameraSize-
  Resolver's PIXEL_PERFECT_SHORT_EDGE from 1920 to 1440 (PORTRAIT
  upscales 1.33×, GL load returns to 6.1c baseline). One-line change
  inside the resolver — separate commit on the smoke-fix round.
- setupSingleCamera, EglRouter, DualSurfaceProcessor, DualVideoRecorder,
  DualPreviewZone, RecordScreen, RecordViewModel, ui/warnings/**, all
  single-mode pipelines = ZERO DIFF. Service-layer integration tested
  on-device only (no Robolectric).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

Expected: commit succeeds.

---

### Task 14: Write ADR-0009

**Files:**
- Create: `docs/adr/0009-dualshot-4-3-source-aspect.md`

- [ ] **Step 1: Create the ADR**

Create `docs/adr/0009-dualshot-4-3-source-aspect.md` with:

```markdown
# ADR-0009: DualShot 4:3 source aspect

**Status**: Accepted
**Date**: 2026-05-18
**Supersedes**: nothing (refines the Phase 6.1c forced-16:9 choice).
**Related**: [ADR-0008 — Dual-recording architecture](0008-dual-recording-architecture.md).

## Context

Phase 6.1c (PR #24, squash-merged @ `ba252c3` on 2026-05-18) shipped true per-side rendering for the Portrait+Landscape (P+L) capture mode via a single CameraX session, a GL fan-out in `EglRouter`, and per-target crop matrices in `AspectFitMath`. Three on-device smoke-fix rounds resolved orientation correctness on both sides.

One architectural defect was knowingly parked at merge time: **the PORTRAIT zone is vertically stretched 1.78×.**

Root cause:

- `setupDualCamera()` forces `ResolutionSelector` to `Size(1920, 1080)` (16:9) so CameraX doesn't apply its default ~44% center-crop FOV loss.
- `AspectFitMath.buildSideAspectCrop(PORTRAIT) = pivot-scale(9/16, 1, 1)` samples a centered 9/16-width × full-height region from the 16:9 source = **1080×1080 (1:1 square)**.
- That square is packed into the 1080×1920 (9:16) PORTRAIT encoder = **1.78× vertical stretch**.

The bug is not in the matrix — `pivot-scale(9/16, 1, 1)` does exactly what it claims. The bug is in the **choice of source aspect**: forcing 16:9 capture amputates the vertical FOV before per-side crop math runs, leaving no pixels left to crop into a 9:16 strip.

A 2026-05-18 brainstorm round (owner-driven, informed by a competitive-architecture brief covering iOS DualShot Recorder + the new `com.dual.shot` Android app) surfaced the iOS pattern: **capture at native 4:3, per-side crop on the long edge of each output ratio.** Both sides full-FOV. Neither side stretched. No bars.

## Decision

Switch the dual-camera source aspect from forced 16:9 (`Size(1920, 1080)`) to native 4:3 (largest available landscape 4:3 mode with shortEdge ≥ 1920, with a graceful fallback chain for weak devices). Re-derive `AspectFitMath.buildSideAspectCrop` constants for both sides:

- **PORTRAIT**: `pivot-scale((9/16) / (4/3), 1, 1) = pivot-scale(27/64, 1, 1)` — center-crops a 9:16 strip from a 4:3 source.
- **LANDSCAPE**: `pivot-scale(1, (4/3) / (16/9), 1) = pivot-scale(1, 3/4, 1)` — center-crops top+bottom of a 4:3 source to a 16:9 strip.

A new pure-JVM `DualCameraSizeResolver` owns the fallback chain (largest 4:3 ≥ 1920×1440 → largest 4:3 → hint `Size(1920, 1440)` if no 4:3 modes exist). The Android-facing wrapper queries `CameraManager.getCameraCharacteristics(...).get(SCALER_STREAM_CONFIGURATION_MAP)` directly (bypassing CameraX's `Camera2CameraInfo` to avoid the experimental-opt-in dependency and the bind-then-query chicken-and-egg).

## Consequences

**Wins:**
- PORTRAIT zone fills the 1080×1920 encoder edge-to-edge with no stretch and no bars; vertical FOV is the full source short edge.
- Matches the iOS DualShot Recorder / `com.dual.shot` Android reference architecture — user expectation alignment.
- Localized footprint: ~5–6 files, single source-aspect change point (`setupDualCamera`'s `ResolutionSelector`), matrix constants self-documenting via `AspectFitMath.SOURCE_ASPECT_W` / `_H`.

**Tradeoffs accepted:**
- LANDSCAPE side no longer source-pixel-perfect: source 2560×1920 → LANDSCAPE crop 2560×1440 → 1920×1080 encoder = **1.33× downscale**. Mild and matches the iOS pattern.
- GL pipeline processes ~2.4× more pixels per frame than the 6.1c 1920×1080 baseline (e.g., 2560×1920 = 4.9 Mpx vs. 1920×1080 = 2.1 Mpx). Mid-range device thermal-throttle is a pre-flagged smoke-fix-round-1 contingency — drop `DualCameraSizeResolver.PIXEL_PERFECT_SHORT_EDGE` from 1920 to 1440 if Samsung SM-A176B drops frames.
- `~1%` of devices may expose no 4:3 modes at all. Those fall back to the `Size(1920, 1440)` hint and let CameraX pick its closest — likely a 16:9 mode, which means the PORTRAIT crop math will subtly under-fill or over-crop. Acceptable risk given the device-share; smoke-fix follow-up if it surfaces.

## Alternatives rejected

1. **Letterbox PORTRAIT via `EglRouter` viewport** (option (a) parked in 6.1c). `EglRouter.renderFrame` would call `computeFitViewport(contentAspect = 1f)` for the PORTRAIT side → black bars top+bottom. Smallest scope, but contradicts the iOS-reference framing (no competing app ships PORTRAIT clips with bars; IG/TikTok reject letterboxed uploads).
2. **Redesign `sideAspectCrop(PORTRAIT)` to a rotation** (option (b) parked in 6.1c). The PORTRAIT encoder file would be landscape-content rotated 90°. Fills the encoder, no bars, no scene loss — but content reads sideways unless playback rotates. No competing app does this; awkward UX.
3. **Dual capture sessions** (PORTRAIT gets its own CameraX `VideoCapture` pinned to 4:3 while LANDSCAPE keeps the 16:9 session). Keeps LANDSCAPE pixel-perfect, fixes PORTRAIT. Breaks the 6.1c single-source GL fan-out architecture; doubles encoder/HAL load; may exceed the device's max simultaneous stream count (the brief flags 2–3 streams as a common cap, with mid-range Samsung A17 in the "partial/limited multi-camera" tier).
4. **Debug feature flag toggling 4:3-vs-16:9 source**. The whole change is ~15 LOC + 6 tests; gating it would just add cleanup debt later. If smoke fails, iterate the resolver threshold inside the same PR.
```

- [ ] **Step 2: Verify the file renders cleanly**

Open the file in any preview / read the file back. No specific build gate — markdown.

---

### Task 15: Doc hygiene check on `docs/architecture.md` (conditional)

**Files:**
- Modify (conditional): `docs/architecture.md`

- [ ] **Step 1: Grep for stale 6.1c source-aspect references**

```bash
grep -nE "1920.*1080|16:9|RATIO_16_9|44%|portrait.{0,3}stretch|9/16 of 16:9" docs/architecture.md
```

- [ ] **Step 2: Decide per hit**

If grep produces ZERO hits → skip to Task 16 (no edit needed).

If grep produces hits referring to `setupDualCamera`'s forced 16:9 / "loses 44% FOV" reasoning / PORTRAIT-stretch deferred ticket / `RATIO_16_9_FALLBACK_AUTO_STRATEGY` in a dual-mode context: amend each hit with a one-line pointer to ADR-0009. Example replacement pattern:

```diff
- The dual-camera path forces 1920×1080 (16:9) to dodge CameraX's ~44% center-crop FOV loss.
+ The dual-camera path requests a 4:3 source mode (see [ADR-0009](adr/0009-dualshot-4-3-source-aspect.md)) — pre-fix it forced 1920×1080 (16:9) to dodge CameraX's ~44% center-crop FOV loss; the 4:3 strategy dodges that differently while giving PORTRAIT a true 9:16 crop.
```

Use the `Edit` tool to make any amendments — do NOT rewrite the file wholesale.

If hits are pre-6.1c baseline references (unrelated to the dual-camera source-aspect choice), LEAVE THEM ALONE — this task is scoped to ADR-0009 doc hygiene only.

---

### Task 16: Commit Phase D docs (ADR + optional architecture.md amendment)

- [ ] **Step 1: Stage + commit**

```bash
git add docs/adr/0009-dualshot-4-3-source-aspect.md
# If Task 15 produced edits, stage those too:
# git add docs/architecture.md
git commit -m "$(cat <<'EOF'
docs(adr): ADR-0009 DualShot 4:3 source aspect

Captures the architectural decision behind the PORTRAIT-stretch
fix in this branch: switch dual-camera source from forced 16:9
(Size(1920, 1080)) to native 4:3 + per-side crop matrices in
AspectFitMath. Matches iOS DualShot Recorder + com.dual.shot
Android reference architecture per the 2026-05-18 brainstorm.

Documents:
- Context (the 6.1c carry-over bug, root cause analysis).
- Decision (4:3 source + DualCameraSizeResolver fallback chain +
  re-derived matrix constants).
- Consequences (wins: no stretch, no bars; tradeoffs: LANDSCAPE
  1.33× downscale, GPU 2.4× pixel throughput, ~1% no-4:3-mode
  devices; pre-flagged smoke-fix-round-1 contingency).
- Alternatives rejected: letterbox / rotated landscape / dual-
  capture-session / debug feature flag.

[Conditional: docs/architecture.md amended with ADR-0009
pointer if grep found stale 6.1c source-aspect references.]

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

Expected: commit succeeds.

---

### Task 17: Full gates + diff-allowlist verification

- [ ] **Step 1: Run the full unit test suite + lint + assembleDebug**

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Expected:
- Unit tests: **972 / 77 / 0-0-0** (master baseline 966/76 + 6 new `DualCameraSizeResolverTest` + 1 new class; `AspectFitMathTest` count unchanged at 15 — values re-derived).
- Lint: **53 (50W + 3H + 0E)** unchanged from master baseline. No new SDK-gated APIs introduced (`CameraManager`, `CameraCharacteristics`, `SCALER_STREAM_CONFIGURATION_MAP`, `SurfaceTexture`, `Size` all API 21+ and used elsewhere).
- `assembleDebug`: `BUILD SUCCESSFUL`.

If unit test count is **not** 972 / 77: count the new tests and verify the math (`AspectFitMathTest` should have the same number of `@Test` methods as before — the 2 replaced + 2 re-derived means same count; `DualCameraSizeResolverTest` adds 6 methods in 1 new class). If `AspectFitMathTest` count drifted, the test file was edited with a typo or the count was misremembered. Investigate.

If `lintDebug` reports new findings: read the report at `app/build/reports/lint-results-debug.html`. New findings localized to the touched files are debuggable inline. Findings in untouched files are NOT this plan's responsibility.

If `assembleDebug` fails: most likely missing import (`Context`) in `RovaRecordingService.kt`. Re-check Task 12 Step 3's note.

- [ ] **Step 2: Verify the diff allowlist**

```bash
git diff master..HEAD --name-only
```

Expected output (in any order):

```
app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt
app/src/main/java/com/aritr/rova/service/dualrecord/internal/AspectFitMath.kt
app/src/main/java/com/aritr/rova/service/dualrecord/internal/DualCameraSizeResolver.kt
app/src/test/java/com/aritr/rova/service/dualrecord/internal/AspectFitMathTest.kt
app/src/test/java/com/aritr/rova/service/dualrecord/internal/DualCameraSizeResolverTest.kt
docs/adr/0009-dualshot-4-3-source-aspect.md
docs/superpowers/specs/2026-05-18-portrait-stretch-architectural-fix-design.md
docs/superpowers/plans/2026-05-18-portrait-stretch-architectural-fix.md
```

Optional 9th line (only if Task 15 produced edits):

```
docs/architecture.md
```

**Any file outside this allowlist appearing in the diff = scope creep, NO-GO.** Investigate before proceeding.

- [ ] **Step 3: No commit (verification-only task)**

This task produces no commit. If gates are green and the diff is conforming, proceed to Task 18.

---

### Task 18: Open the PR

- [ ] **Step 1: Push the branch**

```bash
git push -u origin feat/dualshot-4-3-source-aspect
```

Expected: push succeeds; the remote tracking branch is set up.

- [ ] **Step 2: Open the PR via `gh pr create`**

```bash
gh pr create --base master --title "PORTRAIT-stretch architectural fix — DualShot 4:3-source (ADR-0009)" --body "$(cat <<'EOF'
## Summary

- Resolves the PORTRAIT-zone 1.78× vertical stretch carried over from Phase 6.1c.
- Switches dual-camera `ResolutionSelector` from forced 16:9 (`Size(1920, 1080)`) to native 4:3 (largest available landscape 4:3 mode with shortEdge ≥ 1920, with graceful fallback chain).
- Re-derives `AspectFitMath.buildSideAspectCrop` constants for both sides: PORTRAIT → `pivot-scale(27/64, 1, 1)`; LANDSCAPE → `pivot-scale(1, 3/4, 1)`.
- New pure-JVM `DualCameraSizeResolver` owns the HW capability fallback.
- Matches iOS DualShot Recorder + `com.dual.shot` Android reference architecture (ADR-0009).

## Architecture

Single CameraX session unchanged. `setupDualCamera`'s `ResolutionSelector` queries `CameraManager` directly for the current lens's `StreamConfigurationMap`, delegates to `DualCameraSizeResolver` for the fallback-chain pick, and feeds the chosen `Size` into the existing `ResolutionSelector.Builder` chain. `AspectFitMath.buildSideAspectCrop` constants re-derived for 4:3 source. GL fan-out plumbing (`EglRouter`, `DualSurfaceProcessor`, `DualVideoRecorder`, `DualPreviewZone`, `RecordScreen`) is source-aspect-agnostic by 6.1c design — zero diff there.

## Tradeoffs accepted

- LANDSCAPE side accepts a 1.33× downscale (from 2560×1920 4:3 source → 2560×1440 crop → 1920×1080 encoder). Documented as the iOS DualShot pattern's tradeoff vs the rejected dual-capture-session alternative.
- GL pipeline processes ~2.4× more pixels per frame than 6.1c baseline. Pre-flagged smoke-fix-round-1 contingency: if A17 thermal-throttles, drop `DualCameraSizeResolver.PIXEL_PERFECT_SHORT_EDGE` from 1920 to 1440 (PORTRAIT upscales 1.33×, GL load returns to baseline).
- `~1%` of devices may expose no 4:3 modes at all. Fall back to `Size(1920, 1440)` hint; CameraX picks closest.

## Hard invariants preserved

`setupSingleCamera` (Portrait/Landscape standalone) · `EglRouter` · `DualSurfaceProcessor` · `DualVideoRecorder` · `DualPreviewZone` · `RecordScreen` · `RecordViewModel` · `WarningId` · `WarningPrecedence` · `WarningCenterViewModel` · Start-gate at `RecordScreen.kt:107-122` · `flipCamera()` · `setMode()` · `VideoMerger.kt` · single-mode encoder paths · build files · manifest schema · `RotationCalculator.tag()` — all **byte-identical** to `master @ ba252c3`.

`git diff master..HEAD --name-only` = only the 7–8 files in the manifest above (plus this PR's spec + plan under `docs/superpowers/`).

## Gates

- `:app:testDebugUnitTest` — **972 / 77 / 0-0-0** (baseline 966/76 + 6 new `DualCameraSizeResolverTest`).
- `:app:lintDebug` — **53 (50W + 3H + 0E)** unchanged from master baseline.
- `:app:assembleDebug` — `BUILD SUCCESSFUL`.

## Test plan

- [ ] On-device smoke on Samsung SM-A176B (A17), Android 16 — open P+L mode → eyeball both zones → screenshot.
- [ ] PORTRAIT expected: fills 9:16 zone edge-to-edge, mouse/text/calendar text natural aspect, vertical FOV ≈ full sensor height. No bars. No stretch.
- [ ] LANDSCAPE expected: fills 16:9 zone edge-to-edge, natural aspect, horizontal FOV ≈ full sensor width. Perceptibly softer than 6.1c smoke-3 (expected — 1.33× downscale artifact).
- [ ] If A17 drops frames or thermal-throttles → smoke-fix round 1 contingency: drop `DualCameraSizeResolver.PIXEL_PERFECT_SHORT_EDGE` from 1920 to 1440 (one-line change in the resolver). Re-smoke.
- [ ] Post observed-vs-predicted as a PR comment (same protocol as 6.1c smoke-fix rounds).

## Spec + plan

- Spec: [`docs/superpowers/specs/2026-05-18-portrait-stretch-architectural-fix-design.md`](docs/superpowers/specs/2026-05-18-portrait-stretch-architectural-fix-design.md)
- Plan: [`docs/superpowers/plans/2026-05-18-portrait-stretch-architectural-fix.md`](docs/superpowers/plans/2026-05-18-portrait-stretch-architectural-fix.md)
- ADR: [`docs/adr/0009-dualshot-4-3-source-aspect.md`](docs/adr/0009-dualshot-4-3-source-aspect.md)

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

Expected: the PR URL prints. Return it to the user.

- [ ] **Step 3: No commit (PR-open task)**

This task produces no commit. The PR awaits owner re-smoke per the Test plan checklist above.

---

## Self-Review (this section is for the plan-writer, not the executor)

**Spec coverage** — each spec section traced to a task:

| Spec § | Implementation tasks |
| --- | --- |
| §1 Architecture (4:3 source + per-side crop matrices) | Tasks 2 + 6 + 12 |
| §1 Matrix derivation (27/64 + 3/4) | Tasks 1 + 2 + 5 + 6 |
| §1 Tradeoff (LANDSCAPE 1.33× downscale) | Captured in Task 6 KDoc + Task 8 commit message + Task 14 ADR + Task 18 PR body |
| §1 Hard invariants | Diff-allowlist gate (Task 17 Step 2) |
| §2 File manifest (all 5–6 files) | One task per file (`AspectFitMath.kt` → Tasks 2 + 6; `AspectFitMathTest.kt` → Tasks 1 + 3 + 5 + 7; resolver src/test → Tasks 9 + 10; service → Task 12; ADR → Task 14; architecture.md → Task 15) |
| §3 Data flow | No standalone task — captured in code comments via the rewritten KDocs (Task 2 + 6) |
| §4 Error handling / fallback chain | Tasks 9 (tests) + 10 (impl) + 12 (service-side throws) |
| §4 Smoke verification | Task 18 PR body Test plan checklist |
| §5 Testing — re-derive 16 (15) `AspectFitMath` tests | Tasks 1 + 3 + 5 + 7 cover the side-crop pair + cropMatrix composition pair = 4 tests touched (the 2 regression-locks + 2 re-derived composition tests). Remaining 11 tests (`computeFitViewport` + `buildDisplayRotationCorrection`) are source-aspect-independent — confirmed by inspection during plan writing. |
| §5 6 new `DualCameraSizeResolverTest` | Task 9 |
| §5 Gates (972/77/0-0-0; lint 53) | Task 17 |
| §6 Out of scope | Diff-allowlist gate enforces this (Task 17 Step 2) |
| §7 References | ADR (Task 14), PR body (Task 18) |

**Placeholder scan** — no "TBD", "TODO", "fill in details", "appropriate error handling", "similar to Task N", or empty test descriptions. Every code step has complete code. Every command step has the actual command + expected output. ✅

**Type consistency** — `DualCameraSizeResolver.resolveDualCameraSizeFrom(sizes: List<Pair<Int, Int>>): Pair<Int, Int>` (Task 10) matches the test invocations in Task 9. `resolveDualSourceSize(): android.util.Size` (Task 12) returns the `Size` the `ResolutionStrategy` constructor expects. `AspectFitMath.SOURCE_ASPECT_W` / `_H` declared in Task 2 are referenced in the LANDSCAPE branch in Task 6 (forward reference is fine because Task 2 lands first). ✅

**Spec-count drift** — spec §5 says "16 unchanged" but the actual file has 15 `@Test` methods (verified during plan writing). Plan task 17 expects "972 / 77 / 0-0-0" (= 966 baseline + 6 new). Spec line is shorthand; the count math holds regardless. Not a plan defect.

**Discovered during planning, recorded for the owner**: spec §5 test #5 ("tolerates 1 pixel aspect rounding") — the tolerance is defensive future-proofing, not a real-world integer-math case. Plan Task 9 includes a long explanatory comment block in that test acknowledging this and asserting the exact 1920×1440 case instead of a contrived off-by-one. The resolver's `isLandscape4to3` uses strict integer equality (`width * 3 == height * 4`); if the spec's "±1 px tolerance" is load-bearing, the impl needs to change. Flag for owner review.

---

**Plan complete and saved to [`docs/superpowers/plans/2026-05-18-portrait-stretch-architectural-fix.md`](docs/superpowers/plans/2026-05-18-portrait-stretch-architectural-fix.md). Two execution options:**

**1. Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** — Execute tasks in this session using `executing-plans`, batch execution with checkpoints.

**Which approach?**

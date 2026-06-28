# DualShot Honors the Quality Preset — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make DualShot (P+L) derive its per-side output `Size` + bitrate from the user's selected quality preset (SD/HD/FHD; 4K capped to FHD) instead of hard-coding 1080p @ 8 Mbps.

**Architecture:** A new pure-Kotlin helper `DualShotPresetResolver` maps a canonical preset string → per-side 9:16/16:9 dimensions (primitive `Int`s, JVM-testable under `isReturnDefaultValues = true`). A thin production edge wraps the dims in `android.util.Size`. `setupDualCamera()` calls it and feeds the sizes through the already-built, already-tested `BitrateTable.forSize`. Pure-JVM tests cover the resolver; the wiring is an untested framework seam (house pattern). A device measurement on RZCYA1VBQ2H is the Definition of Done.

**Tech Stack:** Kotlin, JUnit (JVM unit tests, `testOptions.unitTests.isReturnDefaultValues = true`), Gradle `:app:testDebugUnitTest` / `:app:assembleDebug`.

**Driver / spec:** [docs/superpowers/specs/2026-06-28-dualshot-preset-honoring-design.md](../specs/2026-06-28-dualshot-preset-honoring-design.md) + scratchpad `DUALSHOT_PROFILING_REPORT.md` (device-measured, RZCYA1VBQ2H).

## Global Constraints

- Preserve ADR-0009: native-4:3 source + 27/64 matrix side-crops. Crop geometry stays byte-identical; only output resolution changes. **All resolver dims are exact 9:16 / 16:9.**
- Preserve the entire DualShot stability stack (#25–#35). **No threading / render-topology / DualMuxer change.**
- Pause/resume stays DEFERRED — do not touch.
- All **46** static-check gates must pass byte-identically. No `check*` task is added or edited. Verify via `:app:assembleDebug` (fires all gates on `preBuild`).
- No ADR amendment. This realizes the documented v1 TODO.
- Land pure-JVM tests in the same PR. Master test baseline **1241 / 0-0-0**.
- D-deviation pattern: all decidable logic on primitive `Int`s; `android.util.Size` only at the production edge (its `.width`/`.height` return 0 on the android.jar stub under JVM tests).
- Build WARM (no prophylactic cache wipe). codex-review the resolver + wiring diff before merge (non-mechanical perf change). Device-smoke on RZCYA1VBQ2H. Push / PR / merge ONLY on explicit owner GO — never push master.

---

### Task 1: `DualShotPresetResolver` pure helper + tests

**Files:**
- Create: `app/src/main/java/com/aritr/rova/service/dualrecord/internal/DualShotPresetResolver.kt`
- Test: `app/src/test/java/com/aritr/rova/service/dualrecord/internal/DualShotPresetResolverTest.kt`

**Interfaces:**
- Consumes: `com.aritr.rova.data.QualityPresets` (`SD`/`HD`/`FHD`/`UHD` consts, `canonicalizeOrDefault`).
- Produces:
  - `internal data class DualSideSizes(portraitW: Int, portraitH: Int, landscapeW: Int, landscapeH: Int, effectivePreset: String)`
  - `internal data class DualSidePlan(portraitSize: android.util.Size, landscapeSize: android.util.Size, effectivePreset: String)`
  - `DualShotPresetResolver.forDimensions(rawPreset: String?): DualSideSizes` (primitive, JVM-testable)
  - `DualShotPresetResolver.forPreset(rawPreset: String?): DualSidePlan` (production edge — Task 2 consumes this)

- [ ] **Step 1: Write the failing test**

Create `DualShotPresetResolverTest.kt` (drives `forDimensions` only — the primitive entry, no `android.util.Size`):

```kotlin
package com.aritr.rova.service.dualrecord.internal

import com.aritr.rova.data.QualityPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DualShotPresetResolverTest {

    @Test fun sd_resolvesToQhdPerSide() {
        val s = DualShotPresetResolver.forDimensions(QualityPresets.SD)
        assertEquals(540, s.portraitW); assertEquals(960, s.portraitH)
        assertEquals(960, s.landscapeW); assertEquals(540, s.landscapeH)
        assertEquals(QualityPresets.SD, s.effectivePreset)
    }

    @Test fun hd_resolvesTo720pPerSide() {
        val s = DualShotPresetResolver.forDimensions(QualityPresets.HD)
        assertEquals(720, s.portraitW); assertEquals(1280, s.portraitH)
        assertEquals(1280, s.landscapeW); assertEquals(720, s.landscapeH)
        assertEquals(QualityPresets.HD, s.effectivePreset)
    }

    @Test fun fhd_resolvesTo1080pPerSide() {
        val s = DualShotPresetResolver.forDimensions(QualityPresets.FHD)
        assertEquals(1080, s.portraitW); assertEquals(1920, s.portraitH)
        assertEquals(1920, s.landscapeW); assertEquals(1080, s.landscapeH)
        assertEquals(QualityPresets.FHD, s.effectivePreset)
    }

    @Test fun fourK_cappedToFhd() {
        val s = DualShotPresetResolver.forDimensions(QualityPresets.UHD) // "4K"
        assertEquals(1080, s.portraitW); assertEquals(1920, s.portraitH)
        assertEquals(QualityPresets.FHD, s.effectivePreset) // capped, never "4K"
    }

    @Test fun unknownAndNull_fallbackToFhd() {
        for (raw in listOf(null, "", "garbage", "8K")) {
            val s = DualShotPresetResolver.forDimensions(raw)
            assertEquals(1080, s.portraitW); assertEquals(1920, s.portraitH)
            assertEquals(QualityPresets.FHD, s.effectivePreset)
        }
    }

    @Test fun legacyAlias_canonicalizes() {
        val s = DualShotPresetResolver.forDimensions("480p")
        assertEquals(540, s.portraitW)
        assertEquals(QualityPresets.SD, s.effectivePreset)
    }

    @Test fun everyPreset_portraitIsExact9by16_landscapeIsExact16by9() {
        for (raw in listOf(QualityPresets.SD, QualityPresets.HD, QualityPresets.FHD, QualityPresets.UHD)) {
            val s = DualShotPresetResolver.forDimensions(raw)
            // 9:16 portrait, 16:9 landscape — cross-multiply, no float
            assertEquals("portrait 9:16 for $raw", s.portraitW * 16, s.portraitH * 9)
            assertEquals("landscape 16:9 for $raw", s.landscapeW * 9, s.landscapeH * 16)
        }
    }

    @Test fun everyPreset_dimsAreEven_h264HardRequirement() {
        // ONLY %2 is asserted. NOT %8/%16: 540 is %4-only and 1080 (the
        // proven-working current FHD value) is not %16 either (1080/16=67.5).
        // Both rely on the encoder's SPS crop-rectangle padding, as 1080 does today.
        for (raw in listOf(QualityPresets.SD, QualityPresets.HD, QualityPresets.FHD, QualityPresets.UHD)) {
            val s = DualShotPresetResolver.forDimensions(raw)
            for (d in listOf(s.portraitW, s.portraitH, s.landscapeW, s.landscapeH)) {
                assertTrue("$d must be even ($raw)", d % 2 == 0)
            }
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.service.dualrecord.internal.DualShotPresetResolverTest"`
Expected: FAIL — `DualShotPresetResolver` / `DualSideSizes` unresolved reference.

- [ ] **Step 3: Write minimal implementation**

Create `DualShotPresetResolver.kt`:

```kotlin
package com.aritr.rova.service.dualrecord.internal

import com.aritr.rova.data.QualityPresets

/**
 * DualShot per-side output sizing — realizes the deferred 6.1b v1 TODO
 * ("FHD-locked for v1; 6.1c may lookup BitrateTable per resolution").
 *
 * Maps the user's selected quality preset to per-side encoder dimensions,
 * capping 4K → FHD (true per-side 4K = ~1620x2880 x2 streams, unusable on
 * target SoCs — see profiling report). Bitrate is NOT decided here; the
 * caller derives it from these sizes via [BitrateTable.forSize], keeping
 * the encoder rate anchored to the storage gate's per-resolution estimate.
 *
 * All dims are exact 9:16 (portrait) / 16:9 (landscape), preserving the
 * ADR-0009 crop aspect (27/64 of a 4:3 source = exactly 9:16). Output size
 * is free of the crop geometry: the GL fan-out scales the fixed crop into
 * the encoder surface (viewports are aspect-derived, no hard 1080 assumption).
 *
 * D-deviation: [forDimensions] is the primitive-Int entry so pure-JVM tests
 * run under `testOptions.unitTests.isReturnDefaultValues = true` (where
 * `android.util.Size.width/height` return 0 on the android.jar stub).
 * [forPreset] is the production edge and wraps the dims in `android.util.Size`.
 */
internal data class DualSideSizes(
    val portraitW: Int,
    val portraitH: Int,
    val landscapeW: Int,
    val landscapeH: Int,
    val effectivePreset: String, // SD | HD | FHD — never "4K" (capped)
)

internal data class DualSidePlan(
    val portraitSize: android.util.Size,
    val landscapeSize: android.util.Size,
    val effectivePreset: String,
)

internal object DualShotPresetResolver {

    fun forDimensions(rawPreset: String?): DualSideSizes {
        // Unknown/null → FHD (canonicalizeOrDefault default). Then cap 4K → FHD.
        val canonical = QualityPresets.canonicalizeOrDefault(rawPreset)
        val effective = if (canonical == QualityPresets.UHD) QualityPresets.FHD else canonical
        return when (effective) {
            QualityPresets.SD -> DualSideSizes(540, 960, 960, 540, QualityPresets.SD)
            QualityPresets.HD -> DualSideSizes(720, 1280, 1280, 720, QualityPresets.HD)
            else -> DualSideSizes(1080, 1920, 1920, 1080, QualityPresets.FHD)
        }
    }

    fun forPreset(rawPreset: String?): DualSidePlan {
        val d = forDimensions(rawPreset)
        return DualSidePlan(
            portraitSize = android.util.Size(d.portraitW, d.portraitH),
            landscapeSize = android.util.Size(d.landscapeW, d.landscapeH),
            effectivePreset = d.effectivePreset,
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.service.dualrecord.internal.DualShotPresetResolverTest"`
Expected: PASS (8 tests).

- [ ] **Step 5: Add a bitrate-bucket alignment test**

Append to `DualShotPresetResolverTest.kt` — proves the resolver dims land in the bitrate buckets the spec promises (SD 4 / HD 8 / FHD 16 Mbps), and that 4K-capped uses the FHD rate. Uses `forDimensions` to stay primitive (feed ints straight into `BitrateTable.forDimensions`, avoiding the stubbed `Size`):

```kotlin
import com.aritr.rova.service.dualrecord.VideoCodec

    private fun rate(w: Int, h: Int) =
        BitrateTable.forDimensions(w, h, VideoCodec.H264)

    @Test fun resolverDims_landInExpectedBitrateBuckets() {
        val sd = DualShotPresetResolver.forDimensions(QualityPresets.SD)
        assertEquals(4_000_000L, rate(sd.portraitW, sd.portraitH))   // SD 0.5MB/s * 8
        assertEquals(4_000_000L, rate(sd.landscapeW, sd.landscapeH))

        val hd = DualShotPresetResolver.forDimensions(QualityPresets.HD)
        assertEquals(8_000_000L, rate(hd.portraitW, hd.portraitH))   // HD 1MB/s * 8
        assertEquals(8_000_000L, rate(hd.landscapeW, hd.landscapeH))

        val fhd = DualShotPresetResolver.forDimensions(QualityPresets.FHD)
        assertEquals(16_000_000L, rate(fhd.portraitW, fhd.portraitH)) // FHD 2MB/s * 8
        assertEquals(16_000_000L, rate(fhd.landscapeW, fhd.landscapeH))

        val capped = DualShotPresetResolver.forDimensions(QualityPresets.UHD)
        assertEquals(16_000_000L, rate(capped.portraitW, capped.portraitH)) // capped → FHD rate
    }
```

> **Bucket-boundary check (do BEFORE trusting this test):** `BitrateTable.pixelsToPreset` buckets by pixel count: `>= 1920*1080` → FHD, `>= 1280*720` → HD, else SD. Verify the resolver pixel counts land where intended:
> - SD `540*960 = 518,400` → `< 1280*720 (921,600)` → SD bucket ✓
> - HD `720*1280 = 921,600` → `== 1280*720` → HD bucket ✓ (boundary is inclusive `>=`)
> - FHD `1080*1920 = 2,073,600` → `== 1920*1080` → FHD bucket ✓
>
> If any boundary is off, the bug is the resolver dims, not the test — fix the dims (and re-check 9:16).

- [ ] **Step 6: Run the full new test class**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.service.dualrecord.internal.DualShotPresetResolverTest"`
Expected: PASS (9 tests).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/aritr/rova/service/dualrecord/internal/DualShotPresetResolver.kt app/src/test/java/com/aritr/rova/service/dualrecord/internal/DualShotPresetResolverTest.kt
git commit -m "feat(dualshot): add DualShotPresetResolver — preset → per-side 9:16/16:9 size (4K capped to FHD)"
```

---

### Task 2: Wire `setupDualCamera` to honor the preset

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt:2108-2116`

**Interfaces:**
- Consumes: `DualShotPresetResolver.forPreset(rawPreset: String?): DualSidePlan` (Task 1), `BitrateTable.forSize(Size, VideoCodec): Long` (existing), the `resolutionStr` member (in scope in `setupDualCamera`, holds the canonicalized selected preset).
- Produces: nothing new — internal wiring only.

- [ ] **Step 1: Replace the hard-coded size + bitrate constants**

Replace lines 2108–2116 (the comment + `portraitSize`/`landscapeSize` literals and the two `portraitBitrate`/`landscapeBitrate = 8_000_000` config args). Keep `fps = 30`, codec H264, audio 128k/48k unchanged.

Change the block above the `config` construction from:

```kotlin
            // 6.1b consumer config — FHD-locked for v1; 6.1c may lookup BitrateTable per resolution.
            val portraitSize = android.util.Size(1080, 1920)
            val landscapeSize = android.util.Size(1920, 1080)
```

to:

```kotlin
            // DualShot honors the Quality preset (was FHD-locked) — per-side size
            // from DualShotPresetResolver, bitrate from BitrateTable. 4K caps to FHD.
            // ADR-0009 crop geometry unchanged; only output resolution/bitrate vary.
            val sidePlan = com.aritr.rova.service.dualrecord.internal.DualShotPresetResolver
                .forPreset(resolutionStr)
            val portraitSize = sidePlan.portraitSize
            val landscapeSize = sidePlan.landscapeSize
            val portraitBitrate = com.aritr.rova.service.dualrecord.internal.BitrateTable
                .forSize(portraitSize, com.aritr.rova.service.dualrecord.VideoCodec.H264)
            val landscapeBitrate = com.aritr.rova.service.dualrecord.internal.BitrateTable
                .forSize(landscapeSize, com.aritr.rova.service.dualrecord.VideoCodec.H264)
```

Then change the two config args from literals to the new vals:

```kotlin
                portraitBitrate = portraitBitrate,
                landscapeBitrate = landscapeBitrate,
```

(`BitrateTable.forSize` returns `Long`; confirm `DualVideoRecorderConfig.portraitBitrate`/`landscapeBitrate` accept the same numeric type the `8_000_000` literal did — the literal was an untyped `Int`. If the field is `Int`, append `.toInt()`; if `Long`, leave as-is. Check the data class before building.)

- [ ] **Step 2: Confirm the config field type, fix numeric type if needed**

Read `app/src/main/java/com/aritr/rova/service/dualrecord/DualVideoRecorderConfig.kt`, find `portraitBitrate` / `landscapeBitrate` declared type.
- If `Int` → change the two new vals to `...forSize(...).toInt()`.
- If `Long` → no change.

- [ ] **Step 3: Build (compiles + fires all 46 gates)**

Run: `./gradlew :app:assembleDebug 2>&1 | tee gradle_dualshot_preset_wire.log`
Expected: `BUILD SUCCESSFUL`, all 46 `check*` gates pass. If any gate fires, STOP and read its cited ADR clause — do not edit the gate.

> Gate watch: `checkPresetNoOrientation` — the resolver is orientation-orthogonal (returns BOTH sides regardless of orientation-policy), so it must not trip. If it does, the edit touched an orientation-keyed path it shouldn't.

- [ ] **Step 4: Run the full unit suite (no regression)**

Run: `./gradlew :app:testDebugUnitTest 2>&1 | tee gradle_dualshot_preset_tests.log`
Expected: PASS, baseline + new tests (≥ 1241 + 9), 0 failures.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt
git commit -m "feat(dualshot): wire setupDualCamera to honor preset — per-side size+bitrate via resolver/BitrateTable"
```

---

### Task 3: codex review + device verification (Definition of Done)

**Files:** none (review + on-device measurement). No commit unless a fix is needed.

This measurement IS the slice payload — not optional. Device: RZCYA1VBQ2H. Build WARM.

- [ ] **Step 1: codex peer review of the diff**

Invoke `mcp__codex__codex` with the Task 1 + Task 2 diff: "Review this DualShot preset-honoring change for correctness bugs, encoder-dimension hazards, and ADR-0009 crop-geometry safety." Reconcile any flagged issue before device testing.

- [ ] **Step 2: Install + set SD preset**

```powershell
./gradlew :app:assembleDebug
adb -s RZCYA1VBQ2H install -r app/build/outputs/apk/debug/app-debug.apk
```
In-app: set topology = DualShot, Quality = SD. Record a lit ~20 s clip.

- [ ] **Step 3: SD encoder-acceptance + render check**

Pull the portrait side file, confirm:
- HW encoder accepted `540×960` (no `MediaCodec` configure failure in logcat; file plays).
- No PORTRAIT-stretch; crop looks correct (ADR-0009 intact).
- **Fallback:** if `540×960` is rejected or mis-cropped on-device, change the SD row in `DualShotPresetResolver.forDimensions` to `DualSideSizes(504, 896, 896, 504, SD)` (both `%8`, exact 9:16, still SD bucket = 451,584 px < 921,600 → SD ✓), update the SD assertions in Task 1, re-run tests, re-commit, re-measure.

- [ ] **Step 4: SD fps + thermal measurement (the hypothesis discriminator)**

Pull SD DualShot clip; parse `stts` ground-truth fps (MP4 box parse in scratchpad, not logs). Sample AP/SKIN thermal slope across the run (`thermal_sample.ps1`).
- fps climbs toward 30 → encoder-drain was the limiter → **jitter root cause confirmed and fixed.** Slice done.
- fps stays ~18 → limiter is upstream (CameraEffect cadence / per-encoder input wait). **The heat + bandwidth win still lands and the dead-lever bug is fixed**; the one-encoder isolation probe + any fps fix becomes a documented FOLLOW-UP slice (not this PR).

Record both numbers (SD fps, AP plateau delta vs the report's DualShot baseline ~45.8 °C) in the PR description regardless of outcome.

- [ ] **Step 5: FHD regression check**

Set Quality = FHD, record. Confirm FHD still records (now 16 Mbps/side vs the old 8) without a NEW stutter or thermal cliff (heaviest preset). If FHD regresses badly vs the old 8 Mbps, flag to owner before merge — the bitrate doubling at FHD is a real behavioral change.

- [ ] **Step 6: Restore owner prefs + surface results**

Restore prefs (duration=10, loop=2, interval=30, topology=DualShot). Present the fps + thermal numbers and the codex verdict to the owner. **Await explicit GO before PR / merge — never push master.**

---

## Known divergences (carried from spec — deliberately OUT OF SCOPE)

1. **4K-cap storage/manifest divergence.** Picking 4K now records FHD internally, but `StorageEstimator.estimate`, `StorageSignal`, and the manifest `resolution` field still key off raw `resolutionStr` → storage estimate over-conservative (safe), manifest over-labels (History shows 4K on an FHD file). `effectivePreset` is exposed for a future propagation slice. **For SD/HD/FHD there is no divergence.** Not fixed here (touches gate-pinned manifest-write path).
2. **Pre-existing single-sided next-segment gate.** `RovaRecordingService.kt:~2587` `nextSegmentEstimate` uses `bytesPerSecondForResolution(resolutionStr) * nSeconds` with no DualShot `×2`. Pre-existing under-estimate of the look-ahead term only (`accumulatedSessionBytes` already sums both actual files via `max(actual, estimated)`). Separate one-line `×2` fix with its own test — out of scope here.

## Self-Review notes

- **Spec coverage:** S1 resolver → Task 1; S2 wiring → Task 2; S3 tests → Task 1 (steps 1,5); S4 device verify → Task 3. Known divergences carried verbatim. ✓
- **Type consistency:** `DualSideSizes` (Ints) ↔ `DualSidePlan` (`Size`) ↔ `forDimensions`/`forPreset` names match across Task 1 produces and Task 2 consumes. `BitrateTable.forSize(Size, VideoCodec): Long` and `forDimensions(Int, Int, VideoCodec): Long` verified against source. Numeric type of config bitrate fields explicitly checked in Task 2 Step 2. ✓
- **No `%8`/`%16` blanket invariant** — only `%2` asserted (540 is `%4`, 1080 is not `%16`). ✓
```

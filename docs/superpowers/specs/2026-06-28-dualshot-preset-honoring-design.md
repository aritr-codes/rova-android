# DualShot honors the Quality preset — design

**Date:** 2026-06-28
**Branch (profiling origin):** `perf/dualshot-profiling`
**Status:** Approved design (owner + codex `GO-WITH-FIXES` reconciled). Ready for writing-plans.
**Driver:** `DUALSHOT_PROFILING_REPORT.md` (device-measured, RZCYA1VBQ2H).

---

## Problem

`setupDualCamera()` in `RovaRecordingService.kt` (~line 2108) **hard-codes** the DualShot per-side
output size and bitrate, ignoring the user's selected quality preset entirely:

```kotlin
val portraitSize  = Size(1080, 1920)   ; val portraitBitrate  = 8_000_000
val landscapeSize = Size(1920, 1080)   ; val landscapeBitrate = 8_000_000
fps = 30
// source comment: "FHD-locked for v1; 6.1c may lookup BitrateTable per resolution"
```

Device profiling proved the consequences:
- **Jitter:** DualShot encodes 18.56 fps actual vs 30.02 fps for single-encode under the *same* light
  (ground-truth from MP4 `stts`), a ~38% frame drop. The dual pipeline *as configured* cannot sustain
  30 fps on this SoC. (Camera AE and GL math are refuted as causes; render is ~2 ms.)
- **Heat:** DualShot sustains AP (SoC) ~+2.1 °C over single at identical settings.
- **Dead lever:** the user's SD selection — their only way to lighten the load — does nothing in P+L.

This realizes the deferred v1 TODO: derive per-side size + bitrate from the preset via the
already-built, already-tested `BitrateTable`.

## Goal / non-goals

**Goal:** DualShot derives per-side output `Size` + bitrate from the selected preset (SD/HD/FHD; 4K
capped to FHD), preserving ADR-0009 crop geometry, with pure-JVM tests and a device-measured
verification of the fps hypothesis.

**Non-goals (explicit):**
- Segment-boundary stall fix (profiling root cause #4) — separate slice.
- Picker UX change for 4K under DualShot — left as-is.
- FBO-ring / source-texture resizing (possible extra GPU saving) — noted for later.
- Pause/resume — stays DEFERRED.
- Propagating the 4K→FHD *effective* preset into storage/manifest/UI — deferred follow-up (see Known divergences).
- Fixing the pre-existing single-sided next-segment storage gate — separate slice (see Known divergences).

---

## Design

### S1 — `DualShotPresetResolver` (new pure helper)

New file `app/src/main/java/com/aritr/rova/service/dualrecord/internal/DualShotPresetResolver.kt`.
Mirrors `BitrateTable`'s **D-deviation pattern**: all logic on primitive `Int`s so JVM tests work
under `testOptions.unitTests.isReturnDefaultValues = true` (where `android.util.Size.width/height`
return 0 on the android.jar stub). `android.util.Size` appears only at the thin production edge.

Return type carries both the sizes **and** the effective preset (so the 4K→FHD cap is observable to a
future storage/manifest propagation slice — codex requirement):

```kotlin
internal data class DualSideSizes(
    val portraitW: Int, val portraitH: Int,
    val landscapeW: Int, val landscapeH: Int,
    val effectivePreset: String,   // SD | HD | FHD  (never 4K — capped)
)

internal object DualShotPresetResolver {
    // primitive entry — JVM-testable
    fun forDimensions(rawPreset: String?): DualSideSizes

    // production edge — delegates, wraps in android.util.Size at the call site
    fun forPreset(rawPreset: String?): /* sizes as android.util.Size pair + effectivePreset */
}
```

**Preset → per-side dimensions** (canonicalize via `QualityPresets.canonicalizeOrDefault`, then cap 4K→FHD):

| selected preset | effectivePreset | portrait (W×H) | landscape (W×H) | BitrateTable per side |
|-----------------|-----------------|----------------|------------------|------------------------|
| SD              | SD              | 540 × 960      | 960 × 540        | 4 Mbps (SD bucket)     |
| HD              | HD              | 720 × 1280     | 1280 × 720       | 8 Mbps (HD bucket)     |
| FHD             | FHD             | 1080 × 1920    | 1920 × 1080      | 16 Mbps (FHD bucket)   |
| 4K              | FHD (capped)    | 1080 × 1920    | 1920 × 1080      | 16 Mbps (FHD bucket)   |
| unknown / null  | FHD (fallback)  | 1080 × 1920    | 1920 × 1080      | 16 Mbps                |

All sizes are **exact 9:16 / 16:9**, preserving the ADR-0009 crop aspect (27/64 of a 4:3 source =
exactly 9:16, derived). The GL render scales the crop into the output-sized encoder surface, so output
size is free as long as aspect matches (codex confirmed: no hard 1080 assumption in the fan-out;
viewports are aspect-derived; the fixed FBO is a downscale *source*, not a blocker).

**HW-encoder dimension caveat (carried into device verification):** H.264 hard-requires even
dimensions (`%2`, 4:2:0 chroma). HW encoders *prefer* `%16` (macroblock) and pad+crop otherwise — the
current `1080` is not `%16` yet works, so this device tolerates non-`%16` via the SPS crop rectangle.
`720×1280` and `1280×720` are `%16`-clean. `540×960`: `960` is `%16`, `540` is `%4` (not `%8`/`%16`).
Verify `540×960` is accepted on RZCYA1VBQ2H; **fallback ladder** `504×896` (both `%8`, exact 9:16,
still SD bucket = 451,584 px) if the encoder rejects or mis-crops `540×960`.

### S2 — Wiring in `setupDualCamera`

Replace the three hard-coded constants (`RovaRecordingService.kt:~2108`). `resolutionStr` (member,
holds the selected preset) is already in scope:

```kotlin
val sizes = DualShotPresetResolver.forPreset(resolutionStr)
val portraitSize  = sizes.portrait      // e.g. 540×960 at SD
val landscapeSize = sizes.landscape
val portraitBitrate  = BitrateTable.forSize(portraitSize,  VideoCodec.H264)  // SD 4 / HD 8 / FHD 16 Mbps
val landscapeBitrate = BitrateTable.forSize(landscapeSize, VideoCodec.H264)
// fps = 30, codec = H264, audio 128k/48k — UNCHANGED
```

`DualVideoRecorderConfig` validation still holds at every preset: `fps in 15..64`, positive
sizes/bitrates, and the AVC `checkBitrateFloor` (4 Mbps clears the floor for 540×960). No threading,
topology, or render-graph change.

### S3 — Tests (pure JVM, same PR)

`DualShotPresetResolverTest`:
- Exact dims per preset, incl. **4K → FHD cap** and **unknown/null → FHD** fallback.
- `effectivePreset` correctness (4K→"FHD", SD→"SD", …).
- **Aspect invariant** (every preset): `portraitW*16 == portraitH*9` and `landscapeW*9 == landscapeH*16`.
- **Even-dimension invariant**: assert all dims `% 2 == 0` (the H.264 hard requirement). Do **not**
  assert `% 8` or `% 16` as a blanket rule: `540` is `%4`-only and `1080` (the proven-working current
  FHD value) is not `%16` either (1080/16 = 67.5). `720/960/1280/1920` happen to be `%16`; `540/1080`
  are not — both rely on the encoder's SPS crop-rectangle padding, exactly as `1080` does today.
- **Bitrate-bucket alignment**: `BitrateTable.forSize(<resolver portrait for SD>) == SD rate`, …HD, …FHD,
  and 4K-capped → FHD rate.

`setupDualCamera` stays an untested framework seam (house pattern) — all decidable logic is in the pure
resolver.

### S4 — Device verification (Definition of Done, on RZCYA1VBQ2H)

This measurement IS the slice payload, not optional:

1. **SD DualShot:** pull clip, parse `stts` fps + sample AP/SKIN thermal slope.
   - fps climbs toward 30 → encoder-drain was the limiter → jitter root cause **confirmed and fixed**. Done.
   - fps stays ~18 → limiter is upstream (CameraEffect cadence / per-encoder input wait). The
     **heat + bandwidth win still lands**; the one-encoder-same-GL-path isolation probe + any fps fix
     becomes a **follow-up slice**.
2. **SD/HD render correctness:** no PORTRAIT-stretch; HW encoder accepts dims (apply `504×896` fallback
   if `540×960` rejected).
3. **FHD regression check:** FHD still records at the now-16 Mbps/side rate without a new stutter or
   thermal cliff (heaviest preset).

---

## Known divergences (codex-surfaced, deliberately out of scope)

1. **4K-cap storage/manifest divergence.** Storage preflight (`StorageEstimator.estimate…`), the
   start-storage banner (`StorageSignal`), and the manifest's `resolution` field all key off the raw
   `resolutionStr`. When the user picks **4K**, DualShot now records FHD internally but those paths still
   see "4K": the storage estimate stays **over-conservative (safe — never under-budgets)** and the
   manifest **over-labels** (History shows 4K on an FHD file). The resolver exposes `effectivePreset` so
   a future slice can propagate the cap; not done here to keep this change small and off the gate-pinned
   manifest-write path. **For SD/HD/FHD there is no divergence** — encoder rate now matches the storage
   gate's per-side assumption exactly.

2. **Pre-existing single-sided next-segment gate.** `RovaRecordingService.kt:~2587` `nextSegmentEstimate`
   uses `bytesPerSecondForResolution(resolutionStr) * nSeconds` with **no DualShot `×2`**, while DualShot
   writes two sided files per segment. This is a pre-existing under-estimate, independent of this change.
   Recommend a separate one-line `×2`-for-DualShot fix with its own test; **out of scope** here (touches a
   storage-gate path). (Note: `accumulatedSessionBytes` already takes `max(actual, estimated)` and `actual`
   sums all `segment_*.mp4`, so the live accumulated path already sees both files; only the *next-segment*
   look-ahead term is single-sided.)

---

## Gates & ADR

- **No `check*` gate forbids this data change.** Verify the edited lines match no gate regex — notably
  `checkPresetNoOrientation` (the resolver is orientation-orthogonal: it returns *both* sides regardless
  of orientation-policy). Build via `:app:assembleDebug` (fires all 46 gates on `preBuild`).
- **No ADR amendment.** ADR-0009 crop geometry is untouched; this realizes the documented v1 TODO.
- **Optional add-on (owner deferred):** a `checkDualShotHonorsPreset` gate to prevent silent
  re-hardcoding. Not in this slice.

## Risk / rollback

Pure data-derivation change behind the existing DualShot path. Rollback = revert the wiring constants.
Codex-review the resolver + wiring diff before merge (non-mechanical perf change, per policy). The one
empirical unknown — `540×960` HW-encoder acceptance — is gated by the S4 device check with a documented
fallback.

## Test baseline

Master baseline 1241 tests / 0-0-0. This PR adds `DualShotPresetResolverTest` in the same change.

# Library — DualShot Hero Artifact RCA + UX/UI Polish Brief

> **Status:** Analysis deliverable. **No code written.** Owner asked for (1) root-cause of the DualShot
> hero thumbnail artifact, (2) a Library UX/UI audit, (3) a prioritized polish roadmap — before any coding.
> Date 2026-06-15. Branch `feat/library-history-selection` (Slices 1–5 done).

---

# PART A — DualShot hero thumbnail artifact: Root-Cause Analysis

## A.0 DISAMBIGUATION RESULT (2026-06-15, device RZCYA1VBQ2H) — **H2 refuted, H1 confirmed**

Ran A.7 on the hero recording (`Rova_20260615_123526_394_portrait.mp4` + `..._123528_108_landscape.mp4`):

- **`ffprobe` — encoder sizes are EXACT.** Portrait = 1080×1920 (exactly 9:16), Landscape = 1920×1080
  (exactly 16:9), `SAR=1:1`, no rotation tag. → `computeFitViewport` returns the full surface → **no
  letterbox bar is baked**. H2's trigger (encoder ≠ exact side aspect) does **not** fire.
- **Raw frame extraction — both sides are CLEAN.** `ffmpeg` frame@1s from each side shows full-bleed scene
  content, **no grey strip in the pixels**. The strip is NOT in the source file.

**Conclusion: the artifact is introduced at LIBRARY RENDER time (H1), not at capture (H2). H2 is refuted.**
The remaining open detail is *which* render layer — almost certainly the hero autoplay ExoPlayer
(`RESIZE_MODE_ZOOM`) layering over / under the static `VideoFrame` (`ContentScale.Crop`) in the off-16:9
hero box, since the static bitmap is a clean source frame and `Crop` can't synthesize a grey band. (Step 3,
reduce-motion-off, would pin layer-vs-layer; deferred to the P2 fix — low-stakes, and the H1/H2 decision is
already made.) **This makes the fix in-scope for the Library polish pass (Slice P2), not a capture-side task.**

---

## A.1 Verdict (superseded by A.0 — kept for the reasoning trail)

The investigation **narrows to two candidates** but cannot pick between them from source alone — the
deciding evidence (the actual mp4 pixels) must be pulled from the device. **Do not commit to a fix until
A.7's disambiguation runs.** What is firmly established: hero and grid share one bitmap + one URI (A.2),
cache/bitmap-reuse is ruled out (A.5), and the DualShot capture path *can* bake an opaque-black bar (A.3).

- **H1 — Library-side hero autoplay layering (now the front-runner for "why hero, not grid").** The hero
  autoplay stacks a static `VideoFrame` (`ContentScale.Crop`) **under** a transparent-shutter ExoPlayer
  (`RESIZE_MODE_ZOOM`). `Crop` (square-pixel bitmap) and `ZOOM` (uses the container's display-aspect /
  rotation metadata) fill *identically only when the box is ~16:9*. The grid box **is** 16:9 → no
  divergence → clean. The hero box is ~2.27:1 → the two layers diverge → a sliver of the under-layer (or
  background) can show as a band. This explains hero-clean-grid-dirty directly; the capture story does not.
- **H2 — Capture-side baked letterbox bar.** The DualShot encoder *can* bake an opaque-black aspect-fit bar
  into the per-side mp4 (A.3), and the codebase already documents an owner-deferred sibling geometry defect
  in this exact code (A.4). If true, the strip is real recorded pixels and the Library only displays them.

**Correction (codex review):** an earlier draft claimed "the hero's wider crop *reveals* a bottom edge bar
that the grid clips." That is geometrically backwards — `Crop`/`ZOOM` into a *wider* box crops vertically
*more* than a 16:9 box, so it would hide edge bars, not expose them; and symmetric aspect-fit letterboxing
produces **top+bottom** bars, not the bottom-only strip in the screenshot. So a baked edge bar does **not**
cleanly explain hero-dirty/grid-clean — which is why H1 (layer/aspect mismatch that only diverges off-16:9)
is now ranked first.

## A.2 The two thumbnail paths are identical except for the display box

Hero and grid pull `thumbnail` + `previewUri` from the **same `VideoItem`** (`byKey` map) — same bitmap,
same URI. No separate decode, no DualShot-specific compositor in the Library.

- Hero: [LibraryScreen.kt](app/src/main/java/com/aritr/rova/ui/library/LibraryScreen.kt) `renderHero` → `LibraryHeroCard`, media is `fillMaxWidth().height(LibraryDimens.heroHeight = 176dp)`, `ContentScale.Crop` ([VideoFrame.kt:33](app/src/main/java/com/aritr/rova/ui/library/components/VideoFrame.kt#L33)) / `RESIZE_MODE_ZOOM` ([LibraryAutoplayVideo.kt:87](app/src/main/java/com/aritr/rova/ui/library/components/LibraryAutoplayVideo.kt#L87)).
- Grid: `LibraryGridCard`, media locked to `aspectRatio(16f/9f)`, same `ContentScale.Crop` / `RESIZE_MODE_ZOOM`.

**The only difference is box aspect: hero ≈ 2.27:1 (411dp × 176dp), grid = 1.78:1 (16:9).** That box-aspect
difference is the crux of H1: at 16:9 the static `VideoFrame` (`ContentScale.Crop`, square-pixel bitmap) and
the ExoPlayer (`RESIZE_MODE_ZOOM`, which honours the container's display-aspect-ratio / rotation metadata)
fill the box identically; at the hero's 2.27:1 they do **not** necessarily agree, so a sliver of the
under-layer static frame — or the transparent shutter's backdrop — can leak as a horizontal band. (Both
hero and grid actually use the same `LibraryAutoplayVideo` layering; only the hero's off-16:9 box surfaces
the divergence.) → answers Q1 (same bitmap) and reframes Q3: it is likely a **fill/aspect mismatch between
two stacked layers**, not a crop exposing baked content.

## A.3 Where the band is actually introduced — capture time (answers Q2)

A DualShot session has **no library composite**. It fans out to two independent rows, one per side, each
pointing at that side's own muxed `.mp4` ([HistoryViewModel.kt](app/src/main/java/com/aritr/rova/ui/screens/HistoryViewModel.kt) `resolveArtifactsPerSide`). The bar is inside that file:

[EglRouter.kt:409–425](app/src/main/java/com/aritr/rova/service/dualrecord/internal/EglRouter.kt#L409-L425) — encoder target builds a `computeFitViewport` viewport:
```kotlin
// Encoder path
AspectFitMath.buildCropMatrix(displayRotation, sensorOrientation, side, crop)
val contentAspect = when (side) {
    VideoSide.PORTRAIT  -> 9f / 16f
    VideoSide.LANDSCAPE -> 16f / 9f
}
viewport = AspectFitMath.computeFitViewport(width, height, contentAspect)
```

[EglRouter.kt:667–674](app/src/main/java/com/aritr/rova/service/dualrecord/internal/EglRouter.kt#L667-L674) — the render thread clears the **whole encoder surface to opaque black**, then draws only inside that viewport:
```kotlin
// 1. Clear full surface with black — paints letterbox/pillar bars ...
//    no visible effect on encoder targets (full-viewport draw covers it).
GLES20.glViewport(0, 0, target.width, target.height)
GLES20.glClearColor(0f, 0f, 0f, 1f)   // opaque black
GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
GLES20.glViewport(target.viewportX, target.viewportY, target.viewportW, target.viewportH)  // letterboxed region
```

The comment asserts "no visible effect on encoder targets (full-viewport draw covers it)" — **but that is
only true when the encoder surface `width×height` is EXACTLY the content aspect.** `computeFitViewport`
([AspectFitMath.kt:41–45](app/src/main/java/com/aritr/rova/service/dualrecord/internal/AspectFitMath.kt#L41-L45)) returns a sub-viewport (with a `viewportY` offset = top/bottom band) whenever the encoder size resolved by `DualCameraSizeResolver` is not the exact aspect. That cleared-black band is then encoded into every frame of the file. `.toInt()` truncation in `computeFitViewport` guarantees at least a 1-px residual whenever the division isn't exact.

## A.4 The codebase already documents this defect (owner-deferred)

[AspectFitMath.kt:359–366](app/src/main/java/com/aritr/rova/service/dualrecord/internal/AspectFitMath.kt#L359-L366), inside `buildCropMatrix`:
> "Separately surfaced in round 3 but NOT fixed here … PORTRAIT zone shows the center 9/16 column of the
> 16:9 source as a 1:1 square stretched vertically into the 9:16 encoder — **aspect-ratio mismatch baked
> into the original sideAspectCrop design**. Fix likely belongs in EglRouter's call to `computeFitViewport`
> … **Owner-deferred.**"

So there are two sibling baked-in geometry defects in the same code: (a) the `computeFitViewport` letterbox
band (grey/black bar — what the screenshot shows), and (b) the deferred PORTRAIT square-into-9:16 stretch.
Both live in the DualShot encoder render, both ADR-0009/0010 territory.

## A.5 Ruled out (answers Q4)

- **No cache race / bitmap-reuse / frame bleed.** `ThumbnailDiskCache` keys on SHA-1 of `stableKey|size|lastModified|durationMs`; `get` is a single `readBytes()`; no `inBitmap`, no shared mutable buffer. `MediaMetadataRetriever` is created fresh per decode and `release()`d in `finally`. The two DualShot sides have distinct file paths → distinct keys → no collision. The strip is deterministic real content, not stale pixels.
- **Not hero-vs-grid keying divergence.** Both use the same `stableKey` for both thumbnail and previewUri.

## A.6 In static bitmap AND autoplay

The band is in the file, so both the static `VideoFrame` (decoded at 1s via `getFrameAtTime`) and the
`LibraryAutoplayVideo` ExoPlayer (`RESIZE_MODE_ZOOM` on the same file) show it. Fixing only one wouldn't help.

## A.7 Mandatory disambiguation (run BEFORE choosing any fix)

Three cheap checks on the affected DualShot recording decide H1 vs H2 definitively:

1. **`adb pull` the affected per-side `.mp4`** and read its encoded dimensions + `rotation` + sample/display
   aspect (SAR/DAR) — e.g. `ffprobe` / `mediainfo`. If `width:height` isn't exactly the side aspect, or
   SAR≠1, that supports H2 (and would also feed H1, since ExoPlayer ZOOM honours SAR/DAR while the static
   bitmap doesn't).
2. **Extract a RAW frame** from that mp4 (e.g. `ffmpeg -i … frame.png`) and look: **is the grey strip in
   the actual frame pixels?** If yes → H2 (capture-baked). If the raw frame is clean → H1 (Library layering),
   the strip is introduced only at render.
3. **Toggle the hero's autoplay off** (reduce-motion on, which forces the static-`VideoFrame`-only path):
   if the strip **disappears**, it's the ExoPlayer/static **layer mismatch** → H1, in Library scope. If it
   **persists** on the static bitmap alone → the bitmap itself carries it → H2.

`DualShotMatrixDebugInfo` ([AspectFitMath.kt:572](app/src/main/java/com/aritr/rova/service/dualrecord/internal/AspectFitMath.kt#L572)) also carries `viewport` + `encoderSize` — logging `viewportH < encoderHeight` corroborates H2's letterbox path but does **not** by itself prove the strip is what the user sees (do step 2 regardless).

## A.8 Fix branches on the disambiguation outcome (owner decides; no patch yet)

- **If H1 (Library hero layering — most likely):** in scope of the polish pass. Fix the static/ExoPlayer
  fill mismatch — e.g. make the static `VideoFrame` and ExoPlayer use the **same** resize behaviour for the
  hero box, or lock the hero media `aspectRatio` so both layers fill identically, or drop the static
  under-layer once the player's first frame is ready so there's nothing to leak. Cheap, device-verifiable.
- **If H2 (capture-baked bar):** **capture-side, out of Library/reskin scope** (ADR-0009/0010, needs
  on-device re-recording — emulators can't). Options: (1) make the encoder surface exactly the side aspect
  in `DualCameraSizeResolver` so `computeFitViewport` returns the full surface → no bar; (2) crop-fill at the
  encoder like the preview path already does (`buildPreviewCropMatrix` + full-surface viewport, ADR-0010),
  which also folds in the deferred PORTRAIT-stretch defect. This is a pre-existing, independently-deferred
  capture defect — schedule it as its own task, not inside the polish pass.

**Do not present this as proven Library-independent until step 2 (raw-frame inspection) confirms whether the
strip is encoded.** That single check flips the owner between a polish-pass fix and a capture-side task.

---

# PART B — Library UX/UI audit (vs the Record screen quality bar)

Reference = Record screen's token contract: [RecordChromeTokens.kt](app/src/main/java/com/aritr/rova/ui/theme/RecordChromeTokens.kt) (~60 tokens), [RovaTokens.kt](app/src/main/java/com/aritr/rova/ui/theme/RovaTokens.kt) (Inter type scale, glass), [GlassSurface.kt](app/src/main/java/com/aritr/rova/ui/theme/GlassSurface.kt) (3-layer glass). Library tokens = [LibraryDimens.kt](app/src/main/java/com/aritr/rova/ui/library/components/LibraryDimens.kt) (10 tokens only).

The Library is **functionally complete but reads as a prototype** next to Record. Root reasons:

1. **Inconsistent glass language.** Top bar / list rows / batch bar use `GlassSurface`, but **the two most
   prominent surfaces — `LibraryHeroCard` and `LibraryGridCard` — are opaque** (plain `Column` / `Box`),
   so the hero and grid (the things the user actually looks at) miss the design system entirely.
2. **Token-sparse + off-scale.** Library defines 10 dims vs Record's ~60. Values diverge from Record's
   rhythm: `screenPadH = 12dp` (Record edge = 16dp), `cardRadius = 14dp` (Record card/pill family 18–22dp),
   `pillRadius = 8dp` (Record 11–20dp), card vertical padding `4dp` (Record rhythm 6–8dp).
3. **Generic Material typography, not the Inter brand scale.** Hero eyebrow uses `typography.labelMedium`
   instead of `RovaTokens.eyebrow` (Inter Medium 9sp, +2sp tracking, ALL-CAPS). Captions/badges aren't
   styled to a token. No tabular figures on durations/sizes (causes width jitter).
4. **Visual noise / weak hierarchy.** Filter chips + hero + day header + grid all compete; the eyebrow,
   day headers, and badges all shout at similar weight. Thumbnails (the content) aren't dominant enough.
5. **Ad-hoc scrims & borders.** `CaptionBar` gradient is inline (no token); grid selection uses a hard
   `2dp primary` border that clashes with the glass 1dp-white-@0.07 edge language.
6. **States are plain.** Loading = bare `CircularProgressIndicator`; empty = M3 circle; no skeleton/shimmer
   (the rest of the app uses motion-aware polish). Search-empty reuses empty with CTA nulled.

### Concrete gap table (file:line)

| # | Where | Library now | Record reference | Severity |
|---|-------|-------------|------------------|----------|
| 1 | LibraryHeroCard.kt | opaque `Column`, no glass/scrim; eyebrow `labelMedium`; media 176dp/18dp; vpad 4dp | glass + tokenized scrim; `RovaTokens.eyebrow`; 6–8dp rhythm | **HIGH** |
| 2 | LibraryGridCard.kt | opaque `Box`; selection `border(2dp, primary)`; `CaptionBar` inline gradient | glass card; soft 1dp white edge; tokenized scrim (`recordingFrameScrim` family) | **HIGH** |
| 3 | LibraryDimens.kt | screenPadH 12 / cardRadius 14 / pillRadius 8 / no scrim/divider tokens | edge 16 / card-pill 18–22 / pills 11–20 / explicit alphas | **HIGH** |
| 4 | typography (hero/badges/captions) | M3 slots, no tnum | `RovaTokens` Inter scale, tnum on numerics | MEDIUM |
| 5 | LibraryStates.kt | bare spinner; M3 empty; hardcoded `0.4f` icon alpha | skeleton/shimmer; tokenized; motion-aware | MEDIUM |
| 6 | LibraryScreen discovery bar | search field + 3 chips always present, M3 styling, competes with hero | quieter, glass-consistent, collapsible | MEDIUM |
| 7 | LibraryListRow / TopBar / BatchBar | glass via `GlassResolver` (verify alphas == Record's 0.40 fill / 0.07 stroke) | explicit RecordChromeTokens alphas | LOW |
| 8 | LibraryScreen background | `primaryContainer@0.24` ad-hoc gradient | align to Record depth language | LOW |

---

# PART C — Prioritized Library polish roadmap + design brief

Design principles applied (per ui-ux-pro-max + Material/HIG): one primary CTA per screen; hierarchy via
size/spacing/contrast not color; 4/8dp rhythm; tokenized semantic surfaces; thumbnails dominant; quiet
chrome; skeletons over spinners; reduced-motion already gated (ADR-0020). Each slice ships its own a11y +
JVM tests; gates stay green; en+es strings (ADR-0022).

### Slice P1 — Token foundation (no visual risk, unblocks the rest) — **do first**
Extend `LibraryDimens` to a Record-aligned scale and route every component through it. Add: `screenPadH 16`,
`cardRadius 18`, `heroRadius 20`, `pillRadius 11`, `cardPadV 6`, `scrimAlpha`, `dividerAlpha 0.07`,
`selectionEdgeAlpha 0.30`, `emptyIconAlpha`. Pure token change; visual deltas reviewed per component. Mockup
note: 16dp edges + 6dp gutters give the grid room to breathe; 18dp radius matches Record's card family.

### Slice P2 — Hero treatment (highest visual payoff)
Make the hero feel like the app's showcase, not a list item:
- Wrap media in the glass system (or a tokenized scrim) so it has depth and an edge.
- `RovaTokens.eyebrow` for "LATEST RECORDING" (Inter, tracked, caps); title to a real token; meta line
  (`Mon · 12:34 · 12 clips · 1m`) with **tabular figures**.
- Reconsider the 3 round action buttons (Play/Favorite/Share are currently three equal filled circles =
  three primary CTAs). Make **Play** the single primary; Favorite/Share subordinate (tonal/ghost). One CTA rule.
- Stronger bottom scrim under the caption so title is legible over bright footage.
- (Independent of A.8: a hero `aspectRatio` lock would also incidentally clip the DualShot band — but treat
  the capture bug separately per Part A.)

### Slice P3 — Grid/list card polish (thumbnail prominence + glass consistency)
- Grid card → glass-aware surface; thumbnails edge-to-edge with a single tokenized bottom scrim; one caption
  line; badges only when exceptional (already the rule — tighten their weight/placement to reduce noise).
- Selection: replace hard `2dp primary` border with a soft glass-consistent ring + check chip (Photos/Gallery
  convention; already partly there) at `selectionEdgeAlpha`.
- List row spacing/typography to the new scale; thumbnail slightly larger for prominence.

### Slice P4 — Discovery bar + states
- Quiet the discovery bar: glass-consistent chips, collapse search to an icon (already toggled) and keep the
  3 filter chips visually lighter so they don't compete with the hero.
- Loading → skeleton/shimmer cards (reduce-motion → static placeholders). Empty/search-empty → tokenized,
  on-brand, with a single clear CTA. Error/missing-file state messaging.

### Slice P5 — Interaction & motion consistency
- Match Record's press feedback (scale/opacity), motion durations (`elementSpinMs`/Material short), and
  shared-element/continuity feel on tap→player where cheap. Verify `GlassResolver` output alphas equal
  Record's (0.40 fill / 0.07 stroke); align if not.

**Sequencing rationale:** P1 is a safe refactor that every later slice depends on; P2 gives the biggest
perceived-quality jump for least surface area; P3 covers the bulk of the screen; P4/P5 are finish. Each is a
stacked commit on `feat/library-history-selection` (no push/merge until owner says).

### DualShot hero artifact — CONFIRMED H1 (Library render), folds into Slice P2
Device disambiguation (A.0) proved the source frames are clean and encoder sizes exact → the artifact is a
**Library hero render-layer issue**, in scope for **Slice P2 (hero treatment)**: fix the static `VideoFrame`
(`Crop`) vs ExoPlayer (`ZOOM`) layering so both fill the off-16:9 hero box identically (or drop the static
under-layer once the player's first frame is ready, or lock the hero `aspectRatio`). Confirm layer-vs-layer
with the reduce-motion-off check during the fix.
- The separate **owner-deferred capture defect** PORTRAIT square-into-9:16 stretch (`AspectFitMath.kt:359–366`)
  is unrelated to this artifact and stays capture-side (ADR-0009/0010) — not part of the polish pass.

---

## Next step
Owner to: (1) accept/adjust the RCA + decide if/when the capture-side DualShot fix is scheduled (separate
task), and (2) approve the polish roadmap so it can go to a full per-slice plan (writing-plans) before any code.

# Player Navigation Core (PR-6) — Design

> Branch `feat/player-nav-core`. Off master `3798845` (post PR-5 #126).
> Brainstormed 2026-06-20 with web-research evidence + **codex adversarial review**. Codex
> overturned the first-draft interaction model and forced a scope split — both captured below.

## 1. Domain & intent

Rova's player **reviews your own periodic/segmented recordings** — a personal dashcam/CCTV-style
**review** tool, not a streaming/entertainment player. The footage is a single **local merged MP4**
→ seeks are cheap and frame-accurate. The recording has **segments** (built-in chapters), boundaries
known via `segmentDurationsMs: List<Long>`. The value ranking inverts vs Netflix: **find-the-moment
beats watch-fast**, so interactive seek + chapter awareness are the high-payoff, low-cost wins.

Player today (post-#126): read-only viewer — play/pause, ±10s, **display-only** segmented bar,
pause-on-background (no MediaSession), FLAG_SECURE on vault, portrait, dark, Media3 **pinned 1.4.1**,
`useController=false` + custom Compose controls.

## 2. Scope

**PR-6 = interactive seek core.** In:
1. Interactive timeline — tap-to-seek + drag-scrub on a continuous, duration-proportional bar with
   segment ticks.
2. Segment navigation — prev/next segment via tick-snap + a11y custom actions.
3. Resume-from-position — persist `positionMs` in `LibraryMetadataStore`; seek on open.
4. Timeline accessibility — single seekbar node, decorative ticks, throttled announcements.
5. Cleanup — delete the three dead PR-5 strings; ReducedMotion-gated playhead glide (render-layer).

**Explicitly OUT (deferred, with reasons):**
- **Wall-clock playhead → its own data-model PR (PR-6b).** A correct "what real time did this happen"
  clock = `segmentWallStartEpoch + offsetWithinSegment`. `SegmentRecord`
  (`data/SessionManifest.kt:326`) persists **no per-segment wall-start** — only `durationMs`.
  `startedAt + cumulativeFootageOffset` compresses the inter-clip idle gaps (product-wrong);
  `startedAt + segIndex × intervalMinutes` breaks on skips/pauses/thermal/retries. PR-6b adds a
  per-segment wall-start to the manifest (schema bump, recorded going forward; old recordings fall
  back to elapsed time), then the accurate playhead. Owner-confirmed 2026-06-20.
- **Playback speed · double-tap seek · auto-hide controls → PR-7.**
- **Thumbnail filmstrip · true smooth live-scrub (Media3 scrubbing mode) → a Media3 1.8.0+ bump.**
  Thumbnails have no free path on plain MP4 (precompute subsystem). Scrubbing mode lands in 1.8.0,
  not 1.4.1 — do not casually bundle a playback-stack bump into a UI PR.

## 3. Evidence + codex reconciliation (why this shape)

Web research (Media3 docs, YouTube chapters, NVR/dashcam apps, Compose semantics) + a codex
adversarial pass settled five contested points:

| Question | First draft | **Resolved** (codex + evidence) |
|---|---|---|
| Tap behaviour | tap a segment cell = jump to its start | **Tap → seek to x-position** on a continuous bar. Tap-cell-jump is semantically surprising on a seekbar, and equal cells can't stay ≥48dp for 20–60 clips. |
| Geometry | equal-weight cells, interactive | **Duration-proportional** segments so cell geometry matches seek math; ticks at boundaries. |
| Drag on 1.4.1 | live seek, or seek-on-release | **Throttled live preview** (conflated `seekTo`, `CLOSEST_SYNC`, exact on release). Seek-on-release-only is worse without thumbnails; unbounded live seeks thrash the decoder. |
| Chapter nav | "tap any cell" | **Explicit prev/next** = tick-snap + a11y custom actions; don't hide chapter nav behind cell taps. |
| Wall-clock | `startedAt + footageOffset` | **Product-wrong → split to PR-6b** (needs manifest data). |
| Resume cadence | pause + dispose | **`ON_STOP` + pause + dispose + periodic ~5–10s**; reset on `STATE_ENDED`; safer near-end threshold. |

## 4. Components

All framework-touching logic gets a pure-Kotlin sibling (JVM-tested under `isReturnDefaultValues`).

### 4.1 `SegmentedTimelineMath` (pure — extend)
Add to the existing helper (`ui/screens/player/SegmentedTimelineMath.kt`):
- `fun totalDurationMs(durations): Long`
- `fun fractionFromPosition(positionMs, durations): Float` — clamped 0..1.
- `fun positionFromFraction(fraction, durations): Long` — inverse; clamp.
- `fun segmentAtPosition(positionMs, durations): Int` — 0-based index.
- `fun segmentStartMs(index, durations): Long` — cumulative offset of segment start.
- `fun nextSegmentStart(positionMs, durations): Long?` / `prevSegmentStart(positionMs, durations): Long?`
  — for prev/next actions; null at ends. Prev = start of current segment if >~750ms in, else previous
  segment start (standard "previous chapter" feel).
- `fun snapIfNear(positionMs, durations, snapWindowMs): Long` — snap to a boundary within window,
  else identity.
- `fun cellWeights(durations): List<Float>` — duration-proportional weights for layout.
Defensive: empty/zero-duration segments never divide-by-zero (existing contract preserved).

### 4.2 `ResumePolicy` (pure — new)
`ui/screens/player/ResumePolicy.kt`:
- `fun shouldRestartFromStart(positionMs, durationMs): Boolean` — `remaining ≤ min(3000, max(1000, dur·0.02))`.
- `fun resolveOpenPosition(savedMs: Long?, durationMs): Long` — clamp saved vs duration; near-end → 0.
- `fun shouldClearOnEnded(state): Boolean`.
JVM-tested across short (≤20s) and long files, missing/over-duration saved values.

### 4.3 `PlayerViewModel` (seam — extend)
- `fun seekTo(positionMs: Long)` — absolute; clamp `[0, duration]`. (`seekRelative` stays.)
- Scrub session (drag): `beginScrub()` (remember `wasPlaying`, pause), `updateScrub(targetMs)`
  (throttled — see §5), `endScrub(finalMs)` (one EXACT `seekTo`, restore `wasPlaying`). A "scrubbing"
  flag in `progress`/a small UI state so controls pin visible and the polled playhead yields to the
  drag target.
- Resume: on `attachExoPlayer`, read `metadataStore.get(id)?.positionMs` → `ResumePolicy.resolveOpenPosition`
  → seek. Persist on `ON_STOP`/pause/dispose/`STATE_ENDED` + a ~5–10s periodic tick while playing,
  via `metadataStore.update(id){ it.copy(positionMs = ...) }`. Keyed per recording id (and per side for
  P+L so the two angles don't clobber).

### 4.4 `SegmentedTimeline` (composable — rework)
- Render a **continuous duration-proportional** bar with **boundary ticks** (decorative). Filled
  portion = current fraction.
- `Modifier.pointerInput`: `detectTapGestures { seekTo(positionFromFraction(x/width)) }`;
  `detectDragGestures` → `beginScrub`/`updateScrub(snapIfNear(...))`/`endScrub`. Live playhead follows
  the finger immediately; the issued seek is throttled.
- New params: `onSeek: (Long)->Unit`, `onScrubStart/Update/End`, `isScrubbing`, plus existing
  `segmentDurationsMs`/`positionMs`.
- A11y: ONE adjustable node — `progressBarRangeInfo` + `stateDescription` ("Segment 3 of 12, 03:45") +
  `setProgress` action; **prev/next-segment as `customActions`**. Ticks `clearAndSetSemantics{}`
  (decorative). Announcements throttled (segment change / scrub end), not every 250ms.

### 4.5 `LibraryMetadataEntry` + `LibraryMetadataCodec`
Add `positionMs: Long? = null` to the entry; include `isEmpty()`; codec emits the key only when set
(byte-shape preserved). `prune` unaffected.

### 4.6 Strings + motion
- Delete `player_trim_cd`, `player_edit_cd`, `player_editor_coming_soon` from `values/` **and**
  `values-es/`. New a11y strings (en+es): segment range/announcement, prev/next-segment action labels.
- ReducedMotion-gated `animateFloatAsState` glide on the consumed playhead fraction
  (`rememberReduceMotion()` → snap when reduced; `RovaMotion` spring otherwise). Render-layer only —
  no state pushed into pure helpers.

## 5. Drag-seek throttling (codex recipe — the load-bearing detail)
```
onDragStart:   wasPlaying = isPlaying; player.pause(); seekParams = CLOSEST_SYNC
onDrag(dx):    target = clamp(target + dxToMs); uiPlayhead = target (immediate)
               if (now - lastSeekAt ≥ 150..250ms) && |target - lastSeekTarget| ≥ max(500ms, dur*0.01):
                   player.seekTo(target); lastSeekAt = now; lastSeekTarget = target   // conflated, latest-only
onDragEnd:     player.setSeekParameters(EXACT); player.seekTo(finalTarget)
               if (wasPlaying) player.play()
```
Keep only the latest target (no seek queue). This is the safe path on the pinned Media3 1.4.1; true
scrubbing-mode is a 1.8.0+ follow-up.

## 6. Data flow
`SegmentedTimeline` (gesture) → `PlayerViewModel.seekTo/scrub*` → ExoPlayer → `progress` poll (250ms,
yields to scrub target while dragging) → `SegmentedTimelineMath.compute/fraction` → bar render +
a11y. Resume: open → metadata read → `ResumePolicy` → seek; lifecycle/periodic → metadata write.

## 7. Testing (JVM, same PR)
- `SegmentedTimelineMathTest` (extend): fraction↔position round-trip, `segmentAtPosition`,
  `segmentStartMs`, next/prev (incl. the >750ms "restart current" rule), `snapIfNear`, `cellWeights`,
  empty/zero-duration/single-segment edges.
- `ResumePolicyTest`: near-end threshold across short/long durations; resolveOpenPosition clamp;
  clear-on-ended.
- `LibraryMetadataCodecTest` (extend): positionMs round-trip, omitted-when-null byte-shape, tolerant
  parse of legacy entries.
Device smoke (owner; Player FLAG_SECURE → visual): tap-seek lands, drag scrubs without jank,
tick-snap, prev/next via TalkBack, resume on reopen, near-end restart.

## 8. Risks
- Decoder jank on 1.4.1 if throttling too aggressive — tune cadence/threshold on device; fall back to
  seek-on-release if a device shows thrash.
- "Two behaviours on one bar" confusion — mitigated by a single continuous bar (ticks are markers, not
  separate tap zones); tap = position, drag = scrub, snap only near a tick.
- Process-death position loss — periodic + `ON_STOP` writes shrink the window; force-stop is
  unrecoverable (accepted).
- P+L per-side resume clobber — key positionMs per side.

## 9. Follow-on (separate specs)
- **PR-6b (data-model):** per-segment wall-start in the manifest (schema bump) → accurate wall-clock
  playhead. Old recordings → elapsed-time fallback.
- **PR-7:** playback speed · double-tap ±10s · auto-hide controls.
- **Later:** Media3 1.8.0+ (scrubbing mode), thumbnail filmstrip, overflow clip-actions, trim-as-export.

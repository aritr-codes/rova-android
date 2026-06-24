# PR-6b — Wall-Clock Playhead (design spec)

**Status**: Design — approved decisions, codex-reviewed, awaiting owner spec sign-off
**Date**: 2026-06-24
**Track**: Player roadmap PR-6 deferred item (3) — `memory/project_player_roadmap.md`, `docs/BACKLOG.md` "Video / Player / Editing"
**Depends on**: interactive timeline #127 (tap/drag-seek + clip ticks) — wall-clock is a readout layer on top + the schema work.
**ADR**: new **ADR-0032** (per-segment wall-clock start) — see §7.

---

## 1. Problem

Rova reviews your **own** periodic recordings. `config.intervalMinutes` spaces segments over wall-clock **hours**, but the merged MP4 holds only `Σ durationMs` of footage played back-to-back → **footage-time ≠ real-time**. The primary index a reviewer wants is *"what time of day was this moment recorded?"* — a wall-clock playhead.

**Blocker (the reason this is its own PR):** `SessionManifest.SegmentRecord` (`data/SessionManifest.kt:326`) stores per-segment `durationMs` but **no per-segment wall-start**. Deriving capture-time as `startedAt + footageOffset` is product-**wrong** across inter-segment gaps. Fix needs a manifest schema bump to record per-segment wall-start, then render from it.

Confirmed facts (Explore + source, 2026-06-24):
- `SegmentRecord`: `filename, durationMs, sizeBytes, sha1, side: VideoSide?, effectiveTargetRotation: Int?`. **No wall-start.**
- `SCHEMA_VERSION = 11` (`SessionManifest.kt:188`). Emit-when-set precedent: `effectiveTargetRotation` (schema 10, ADR-0029 PR-α).
- Segments appended at **finalize** in `performMerge` (`RovaRecordingService.kt:~3213` single, `~3037` dual) via `SessionStore.appendSegment` → `writeManifestAtomic` (temp+ATOMIC_MOVE rename).
- DualShot writes **two** records/loop (PORTRAIT+LANDSCAPE), same `durationMs`, recorded **simultaneously**.
- Reuse seams: `SegmentedTimelineMath`, `PlayerUiState.Ready` (`startedAt` + `segmentDurationsMs`), `PlayerUriResolver` (per-side filter), `RecordHudFormatters` (pure).
- No `check*` gate asserts segment schema; `checkRecoveryNoDeletion`/`checkRecoverySegmentRegex` concern recovery, not schema.

---

## 2. Decisions (owner-ratified)

| # | Decision | Choice |
|---|---|---|
| D1 | Where to stamp wall-start | **Segment START**, threaded to finalize (most accurate; finalize-time ≈ start+duration+lag is wrong) |
| D2 | Render across inter-segment gaps | **Clock jumps at boundary + gap label** (`"+15 min gap"`) |
| D3 | Readout format | **Time-of-day HH:MM:SS**, locale 12/24h; date prefix (`"Mon 09:14:03"`) only when session spans midnight |
| D4 | Legacy (schema <12) | **Approximate fallback, labelled "approx"** — synthesize missing starts, mark readout approx when current clip inferred |
| D5 | ADR | **New ADR-0032** |

---

## 3. Data model (schema 11 → 12)

Add one optional field to `SegmentRecord`:
```kotlin
val startedAtWallClock: Long? = null   // epoch ms, sampled at segment START
```
- `toJson`: `startedAtWallClock?.let { put("startedAtWallClock", it) }` — emit only when non-null → schema-11 byte-shape preserved.
- `fromJson`: `if (json.has("startedAtWallClock")) json.getLong("startedAtWallClock") else null` — old records read `null`, never fabricated.
- `SCHEMA_VERSION = 12`; add `11->12: SegmentRecord.startedAtWallClock per-segment wall-clock start (ADR-0032). Schema-<12 segments read null.` to the history comment block.
- **DualShot**: PORTRAIT + LANDSCAPE record simultaneously → both records get the **same** start stamp.
- **Recovery-neutral (invariant):** `startedAtWallClock` is informational only — never a deletion/classification input in `RecoveryScanner`. Same status as `effectiveTargetRotation`. (codex non-issue confirmation.)

---

## 4. Stamping — segment START, threaded (D1; codex #3)

- Capture `System.currentTimeMillis()` into a **per-iteration local** **before** `start(...)` for the segment; thread that specific value to the finalize handler that builds the `SegmentRecord` (single `~:3213`, dual `~:3037`).
- **Do NOT** use a mutable service field (e.g. `currentSegmentStartedAt`). Finalize callbacks can arrive late after timeout/recovery paths and a shared field can be overwritten/cleared between iterations (codex #3). The stamp must travel with the segment's own closure/job.
- Plan-stage subagent traces the exact carry seam (per-loop local → the `serviceScope.async` persist closure that calls `appendSegment`). For DualShot, the single captured local feeds **both** the PORTRAIT and LANDSCAPE record builds.
- ⚠️ **File overlap with Track B**: this edits the `RovaRecordingService` segment loop, the same file the DualShot-perf investigation may later touch. Safe **this cycle** because Track B makes zero code edits; any Track-B fix is sequenced **after** this PR.

---

## 5. Rendering — pure helpers (house seam pattern)

### 5.1 Per-side ordering (codex #2)
The DualShot persist jobs (`serviceScope.async(IO)` per side) compute SHA before `appendSegment`, so a later side/loop can overtake in the serial append queue → **manifest list order is not a reliable timeline order**. The resolver MUST order a side's segments by **parsed filename sequence** (the segment index baked into the filename by `SegmentPathBuilder`), not by `manifest.segments` position. This also hardens a pre-existing latent ordering assumption in the current `PlayerUriResolver` per-side duration mapping.
> Plan-stage: confirm `SegmentPathBuilder` filename carries a parseable monotonic index; add a pure `parseSegmentSequence(filename): Int` if not already present.

### 5.2 `WallClockTimeline` (new pure object, sibling to `SegmentedTimelineMath`)
Input: ordered per-clip `wallStartsMs: List<Long>` (resolved, always populated — see §6), `durationsMs: List<Long>`, flat playback `positionMs`.
Output: `WallClockReadout { instantMs: Long, isApprox: Boolean, gapBeforeMs: Long? }`.
- `instantMs = clipWallStart + intraClipOffsetMs` where `intraClipOffsetMs = positionMs − cumulativeDurationBeforeClip`.
- `gapBeforeMs = thisClipWallStart − (prevClipWallStart + prevDuration)`; **belongs to the selected clip**; **clamp**: render only when `> 0` (codex #6 — wall/DST/manual-adjust can make it negative; never render a negative gap; a negative value is suppressed, optionally surfaced as a clock-anomaly flag, not as a "gap").
- `isApprox` = the **selected clip's** stamp was synthesized (§6), not a session-wide flag (codex #1).

**Boundary contract (codex #5):**
- Internal boundary (`positionMs` exactly at a clip end): select the **next** clip with `intraClipOffset = 0`.
- `positionMs ≥ totalDuration`: select the **last** clip at its end.
- Empty list / zero-duration records: defined, total-safe (no div, no negative index).

### 5.3 Formatter (in `RecordHudFormatters`, pure, JVM-tested)
- `formatTimeOfDay(instantMs, zoneOffsetMs, is24h, withDatePrefix): String` → `"09:14:03"` / `"21:14:03"` / `"Mon 09:14:03"`.
  - Compute via **`Math.floorMod`** on `(instantMs + zoneOffsetMs)` — **not `%`** (codex #4; sign-safe for any epoch). No `java.time` → no core-library-desugaring dependency (minSdk 24).
  - The wrapper passes **`TimeZone.getDefault().getOffset(instantMs)` per instant** (codex #4 — historical DST for *that* instant), and computes `withDatePrefix`/day-key from **each instant's own offset**, not one session-wide offset.
- `formatGap(gapMs): UiText` → `"+15 min gap"` (rounded to a sensible unit; only called when `gapMs > 0`).

`spansMidnight` is derived by the wrapper: compare the local-day key of the first vs last clip instant (each via its own per-instant offset). When true, all readouts carry the date prefix.

---

## 6. Resolver + state (migration / legacy — D4, codex #1)

`PlayerUriResolver` resolves, per side, an ordered `segmentWallStartsMs: List<Long>` (always fully populated) + per-clip `wallStartIsApproxMask: List<Boolean>` into `PlayerUiState.Ready`:

- For each ordered clip `i`:
  - If `segment[i].startedAtWallClock != null` → use it, `mask[i] = false` (**exact, preserved**).
  - If `null` (legacy schema <12 OR a recovered orphan in a schema-12 session — codex #1) → **synthesize**: `wallStart[i] = (i == 0) ? startedAt : (wallStart[i-1] + durations[i-1])` (chain from the last resolved start), `mask[i] = true`.
- This **preserves exact clips' correctness** and only collapses the gap *locally* around inferred clips, instead of all-or-nothing compressing the whole session (codex #1).
- `PlayerUiState.Ready` gains: `segmentWallStartsMs: List<Long>`, `wallStartIsApproxMask: List<Boolean>`. The UI "approx" marker shows when the **current** `WallClockReadout.isApprox` is true (driven by the selected clip's mask entry).

Legacy whole-session case (all null) reduces to the original approximate fallback `startedAt + Σ priorDurations`, every clip masked approx — exactly D4.

---

## 7. ADR-0032 (new)

Write `docs/adr/0032-per-segment-wall-clock-start.md`:
- **Context**: player wall-clock playhead; footage-time ≠ real-time; need per-segment capture wall-start.
- **Decision**: add `SegmentRecord.startedAtWallClock: Long?`, sampled at segment START, schema 11→12, emit-when-set, informational-only (never a recovery deletion/classification input), DualShot both sides share the stamp.
- **Consequences**: legacy schema-<12 + recovered orphans fall back to synthesized approximate starts, surfaced as "approx"; no new `check*` gate (no schema invariant gate exists; recovery-neutrality is upheld by existing `checkRecoveryNoDeletion` semantics + KDoc). Per the ADR-amend-first convention this ADR lands **before** the code.

---

## 8. Tests (same PR, JVM only)

1. **Manifest round-trip** (`SessionManifestTest` extension): schema-12 record with `startedAtWallClock` survives toJson→fromJson; schema-11 JSON (no key) loads → `null`; **byte-shape unchanged when null** (no `startedAtWallClock` key emitted).
2. **Migration**: a legacy manifest fixture (no per-segment stamp) loads and resolves to synthesized starts, all masked approx.
3. **Mixed-null** (codex #1): schema-12 session with exact clips 0–1 + null clip 2 (recovered orphan) → clips 0–1 exact (`mask=false`), clip 2 synthesized (`mask=true`); exact clips' instants unchanged.
4. **`WallClockTimeline`**: clip-index selection across positions; instant = start + intra-offset; gap detection; **boundary cases** — exact internal boundary → next clip offset 0; `positionMs ≥ total` → last clip end; empty list; zero-duration records (codex #5); **negative gap clamped/suppressed** (codex #6).
5. **Per-side ordering** (codex #2): DualShot fixture with out-of-order manifest segments resolves by parsed filename sequence, not list order.
6. **Formatter**: `formatTimeOfDay` HH:MM:SS for representative instants; 12h vs 24h; midnight-span date prefix; **`floorMod` correctness for a negative `(epoch+offset)`** edge; per-instant DST offset; `formatGap` rounding + only-when-positive.

---

## 9. Gates / constraints

- **46 static gates + full `:app:testDebugUnitTest` GREEN at every commit.** Verify via `:app:assembleDebug` (gates fire on preBuild), **not** `:app:lintDebug` (pre-existing `VaultAndroidOps:267` NewApi lint failure).
- **No `check*` edits** — none asserts segment schema.
- Pure-helper extraction for all framework-touching logic (formatter, timeline, ordering).
- ADR-0032 lands before code (amend-first convention).
- Subagents EDIT-ONLY; controller runs all gradle/tests/commits/smoke. Build WARM.
- Device smoke on RZCYA1VBQ2H mandatory before owner GO. Push/PR/merge only on explicit owner GO.

---

## 10. Out of scope (YAGNI)

- PR-6b wall-clock playhead **readout** only. No timeline visual redesign (the #127 timeline stays).
- No PR-7 work (speed / double-tap / auto-hide).
- No cross-timezone "recorded-in-zone-X, played-in-zone-Y" reconciliation — readout uses playback-device zone applied to the recorded instant (known limitation; note in KDoc).
- No backfill of wall-starts onto existing recordings — legacy stays approximate by design.

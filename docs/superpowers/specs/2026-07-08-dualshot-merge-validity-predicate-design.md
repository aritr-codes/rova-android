# DualShot merge validity-predicate unification — implementation specification

**Status:** FROZEN → IMPLEMENTED (2026-07-08). Spec review GO-WITH-FIXES (RF1/RF2/RF3 folded in); diff review **GO** (no Required Fixes; R1 applied — sessionId in per-drop log; R2 surfaced to owner). Suite 2285/0-0-0. Device: healthy FHD DualShot CLEAN. **Awaiting owner merge decision** (not committed/merged; no stewardship yet, per owner instruction).
**Date:** 2026-07-08 · **Branch (proposed):** `fix/merge-validity-predicate`
**Owner objective (verbatim):** *"Use one definition of a valid recording throughout the recording pipeline."*
**Consumes:** ADR-0005 §"Media Validity Rules" (existing predicate) + the existing merge/export pipeline. **No new subsystem.**

---

## 0. Why (evidence, already established — do not re-litigate)

Investigation `systematic-debugging` pass, 2026-07-08. Reproduction **established** via preserved on-device artifacts (RZCYA1VBQ2H), three independent 2026-07-01 DualShot sessions, ffprobe-confirmed:

| Session | Portrait clips | Landscape clips | Failing segment | Manifest verdict |
|---|---|---|---|---|
| 150544 | 2 | 3 | L = 322 KB audio-only; P = 850 B header-only (orphan, not in manifest) | `COMPLETED`/`NONE` |
| 151301 | 5 | 6 | L seg6 = 323 KB; P missing | `COMPLETED`/`NONE` |
| 152414 | 2 {1,3} | 3 {1,2,3} | L seg2 = 323 KB; P missing | `COMPLETED`/`NONE` |

Root cause is **not** hardware contention (accepted Limiter-2 truth — out of scope). It is **inconsistent validity semantics inside the merge pipeline**: a video-starved segment whose audio track survived (~323 KB, false `durationMs: 20000`) passes the merge's `length() > 0` size-only filter and is baked into the landscape output as a ~20 s frozen clip, while the portrait counterpart (muxer threw → 850 B header-only) is excluded — producing asymmetric, silently-`COMPLETED`, published-to-gallery output.

The correct predicate **already exists and already catches this case**: `MediaFileValidator.inspectMediaFile` (`utils/MediaFileValidator.kt:49`) selects the video track (`:71`) then requires ≥1 readable video sample (`:73–74`) — the 323 KB audio-only stub returns `INVALID`. **It is simply not called on the merge path.** The merge uses `length() > 0` instead.

---

## 1. Architectural invariant

> **A recording segment is *valid* iff `MediaFileValidator.validateMediaFile` (ADR-0005 §"Media Validity Rules") reports it valid** — file present, non-empty, a video track present, and at least one readable video sample. **This single predicate is the only definition of segment validity admitted at every point a segment can enter merge, publication, or the completion count.** No pipeline stage may admit a segment by a weaker test (specifically: `length() > 0` alone is forbidden as an admission gate for merge).

Corollaries:
- There is **one** predicate symbol, reused — not a second re-implementation. The size-only filters are replaced by (or funnel through) `validateMediaFile`, not supplemented by it.
- The predicate is applied **per side, independently** (see §2 constraint). It never compares the two sides.
- **[RF1] The predicate must never false-drop a healthy segment.** `inspectMediaFile` currently reads the first video sample into a fixed **64 KB buffer** (`MediaFileValidator.kt:72`, `SAMPLE_BUFFER_BYTES:141`). The default preset is **FHD 1920×1080** and 4K is offered (`QualityPresets.kt:26–31`); a 1080p/4K IDR keyframe can exceed 64 KB, and `MediaExtractor.readSampleData` into an undersized buffer is device-dependent (may throw → `INVALID`, or return ≤0 → `INVALID`) → a false-drop of a *healthy* high-res segment, which would corrupt good recordings. **Required:** size the read buffer from the video track's `KEY_MAX_INPUT_SIZE` (fallback to a safe default ≥1 MB, mirroring `VideoMerger.runMux:191–193`), so the predicate is correct-by-construction. This hardens the *shared* callers (recovery classifier, Tier-1 `validatePending`) strictly in the safe direction (a previously-borderline keyframe now reads fully) — those paths must be regression-checked green.

## 2. Merge contract

**Chokepoint:** `VideoMerger.preflight` (`utils/VideoMerger.kt:104–125`) is the universal funnel — every merge caller (single-mode, DualShot per-side, recovery) passes through it. The single predicate lives **there**, so one change realizes the invariant for all callers.

Contract:

1. **Each side (and single-mode, and recovery) independently filters its own segment list through the shared predicate before muxing.** Replace `preflight`'s `segments.filter { it.exists() && it.length() > 0 }` (`:108`) with `segments.filter { isValid(it) }`, where `isValid` defaults to `::validateMediaFile`.
2. **No cross-side symmetry is assumed or enforced.** There is no count comparison, no padding, no truncation-to-min, no alignment between portrait and landscape. If both sides drop the same failing index, symmetric output is a **natural outcome, not a contract term**.
3. **A side whose filtered list is empty does not merge and does not publish** — its export pass raises the existing `"No valid segments found"` (`:109–111`) → that side's `*Ok = false`. Overall session success remains `portraitOk || landscapeOk` (`RovaRecordingService.kt:4358`), unchanged. A both-sides-empty session yields overall failure and publishes nothing (truthful terminal state).
4. **The DualShot assembly pre-filter** (`RovaRecordingService.kt:3962/3966`, currently `exists() && length() > 0`) is aligned to the same predicate so the assembly and the merge agree; the authoritative gate remains `preflight`. Aligning it here (rather than only in `preflight`) is what makes the completion count truthful for free (§3.2). **[RF2] This block runs on `Dispatchers.Main`** — `serviceScope` is `Main` (`:209`) and `stopAndMergeJob` launches without a dispatcher override (`:3818`), so a per-segment `MediaExtractor` open here would be main-thread media inspection = jank/ANR. **Required:** wrap the aligned assembly filter in `withContext(Dispatchers.IO)` (the pattern already used one line up at `:3955`). The segment then gets inspected at assembly *and* again in `preflight` (double `MediaExtractor` open per valid segment) — acceptable for typical N on IO; do **not** try to thread validated lists through to skip it, that would break `preflight`'s role as the universal chokepoint for the other callers.
5. **Injectable predicate + extracted pure helper for testability.** The real predicate is a framework seam (MediaExtractor, untestable under JVM). `preflight` is `private` and also does `MediaMetadataRetriever` rotation work (`VideoMerger.kt:114–119`), so JVM tests cannot reach it directly. **Required (not optional):** extract the per-side filtering + drop/divergence-logging decision into a **pure helper** (e.g. `MergeSegmentFilter`) that takes `isValid: (File) -> Boolean` and returns the kept list + drop reasons; `preflight` and the assembly both call it with the default `::validateMediaFile`. Tests target the pure helper with a fake predicate. Production always uses the default — no behavioral coupling.

**Blast radius (explicit, for review):** changing `preflight` affects **single-mode and recovery merge too**, not only DualShot. This is *intended* (one predicate everywhere) and is the correct direction — a frameless single-mode segment is equally invalid. Review must confirm it does not regress the **healthy** single-mode / recovery path (healthy segments have ≥1 video sample → still admitted).

## 3. Publication contract

1. **Only validated artifacts are published.** MediaStore publication is downstream of the per-side export pass; a side that filtered to empty is skipped by the existing `isNotEmpty()` guard (`RovaRecordingService.kt:4292/4319`) so `ExportPipeline.export` is never called for it → no publication. **[review Observation, corrected mechanism]** If an empty side *did* reach export, `Tier1Exporter` inserts a pending row (`:129–130`) then `cleanupAndMap`→`safeDeleteRow` **deletes** it on the `MuxFailed` from `preflight` (`:168–176,:342–348`) — so there is never a *dangling* row, but the RF2 assembly filter (which empties the side before export) avoids even that insert-then-delete churn. Net: no empty/placeholder publication, no orphan pending row.
2. **[RF3 — corrected] The completion count is truthful once the count is taken over the *validated* lists; keep `maxOf`.** `dualClipCount = maxOf(portraitSegments.size, landscapeSegments.size)` (`RovaRecordingService.kt:4258`) is computed over the lists handed to `performMergeDual` — i.e. the **assembly-filtered** lists (`:3959–3966`). Once §2.4 aligns that pre-filter to `validateMediaFile`, the bogus 323 KB stub is gone *before* `maxOf` runs, so `maxOf` becomes truthful with **no change to `:4258`**. The count feeds only single-number, session-level consumers — HUD merge band (`mergeClipCount`→`RecordHudState.kt:91`), MergeComplete card (`exportedClipCount`→`MergeCompleteCount.kt`), notification total — none of which has a per-side display target, and a forced side-pick could *undercount* a healthy asymmetric session. **Therefore: keep `maxOf`, do not report a per-side number.** (Original draft's `maxOf`-removal was withdrawn on review.)
3. **Manifest is not rewritten.** The recorded `SegmentRecord` (incl. the historically-false `durationMs` on a frameless segment) stays as the muxer emitted it — it is the historical record of what was captured. Enforcement is at *consumption* (the merge filter), not by mutating persisted manifests. (Ponytail: filter at the chokepoint, don't migrate manifests.) The orphan-on-disk cleanup of excluded segments is **out of scope** (separate pre-existing BACKLOG item).

## 4. Logging strategy

Goal: **truthful rather than silent.** Minimal, log-level (no new UI surface — a badge/warning would be a new feature; explicitly deferred, see §6 non-goals).

1. **Per-drop log.** When the predicate rejects a segment during `preflight`, emit `RovaLog.w` with `sessionId` (if available), side/mode, filename, and which gate failed (missing / empty / no-video-track / zero-samples). Closes the current silent structural drops (`MediaFileValidator` today logs only on *exception*, not on rejection).
2. **Per-side divergence log.** After both DualShot sides are filtered, if `portraitValid.size != landscapeValid.size`, emit one `RovaLog.w` naming both counts and the session. Makes degraded output visible in logcat/telemetry without a UI change.
3. **No `RovaLog.i`** (RovaLog has only d/w/e — see `memory/project_player_lifecycle_perf.md`). Drops and divergence are `w`.

## 5. Deterministic test plan (JVM, `isReturnDefaultValues = true`)

The real predicate touches `MediaExtractor` (framework → returns defaults under JVM, untestable directly). All logic tests inject a fake `isValid`. On-device covers the real predicate.

**Pure/seam tests (new, JVM):**
- **T1 asymmetric-fail-same-index:** P={1,2,3}, L={1,2,3}; fake marks P#1 and L#1 invalid → each side independently drops its #1 → P={2,3}, L={2,3}. Assert equal counts as an *outcome*, no divergence log. (Proves natural symmetry without an assumption.)
- **T2 one-side-only fail:** fake marks only L#2 invalid → P={1,2,3}, L={1,3} → counts differ → **divergence log emitted**; both sides still yield their valid sets for publication.
- **T3 all-invalid one side:** fake marks all of L invalid → L filters empty → L export raises "No valid segments found"; P publishes; overall success `true`.
- **T4 all-invalid both sides:** overall success `false`; no publication; truthful terminal state.
- **T5 healthy path unchanged:** fake marks all valid → identical to today's behavior (regression guard for single-mode + recovery blast radius). **NOTE [RF1]:** T5 injects a *fake* predicate, so it exercises the filter plumbing but **cannot** catch a false-drop by the *real* 64 KB-buffer read on a high-res keyframe — that risk is closed by the buffer-sizing fix (§1 corollary) plus the on-device FHD/4K-admit check below, not by T5.
- **T-buffer (JVM, real validator, if reachable):** if the buffer-sizing helper is extractable as pure arithmetic (`chooseBufferSize(maxInputSize)` → ≥ keyframe), unit-test that it never returns < `KEY_MAX_INPUT_SIZE`. The MediaExtractor read itself stays device-verified.
- **T6 single definition (structural):** assert the merge filter and the recovery/Tier-1 probe resolve to the **same** `validateMediaFile` symbol — no second predicate. (Either a unit assertion on the injected default, or a static gate — see below.)
- **T7 per-drop + divergence logging:** inject a capturing logger; assert the drop reason strings and the divergence line fire exactly on T2/T3, not on T1/T5.

**Optional static gate (house `invariant → check*` convention, like ADR-0036 which *deferred* its gate in favor of pure tests):** `checkMergeValidityPredicate` — forbid `length() > 0`-only segment filtering in the merge path (`VideoMerger` + the DualShot assembly), enforcing that admission routes through `validateMediaFile`. **Proposed but not required for v1** — pure tests T1–T7 are the protection; add the gate only on owner sign-off (adds the 49th gate + `RegistryTest` + `RovaGateRules` entry per CLAUDE.md). Decision deferred to review/owner.

**On-device verification (real predicate — mandatory before merge):**
- **Healthy-admit (RF1 close):** record and merge a healthy **FHD** DualShot session **and** a healthy **4K** session; assert **every** segment is admitted (zero false-drops) and output is byte-plausible. This is the load-bearing check the JVM fakes cannot cover.
- **Handling:** reproducing the *failure* is not on-demand (thermal), so device-verify the handling with the preserved corpus / a crafted frameless segment: (a) the audio-only stub is dropped from the merged landscape output, (b) merged output is truthful (no 20 s freeze), (c) the drop + any divergence are logged.
- **Blast-radius regression:** a healthy single-mode session and a recovery-merge path both stay green (shared `preflight`/validator change).

## 6. Non-goals (frozen — do not creep)

- ❌ Solving thermal contention / raising frame rate / preventing starvation (accepted Limiter-2 hardware truth).
- ❌ Cross-side symmetry as a contract (only "same predicate, applied independently").
- ❌ New user-facing UI (badge/warning/dialog) for divergence — that is a **new feature** (owner no-new-features rule); logging is the truthful-minimum for v1. A UI signal, if ever wanted, routes HTML-first as its own cycle.
- ❌ Rewriting/migrating persisted manifests.
- ❌ Orphan-on-disk cleanup of excluded segments (separate pre-existing BACKLOG item).

## 7. Independent review (mandatory, before implementation)

Per CLAUDE.md Independent Review Workflow — dispatch a Review Agent (fresh context, **not** the spec author) to **falsify this specification before any code**. It must validate against repo state (read `MediaFileValidator.kt`, `VideoMerger.kt`, the `RovaRecordingService` merge block), not this summary, and probe at minimum:

- Does `validateMediaFile` truly reject the audio-only/video-starved stub, and truly *admit* every healthy segment (no false-drop of legitimate short/low-motion segments)?
- Does moving the predicate into `preflight` regress single-mode or recovery merge? Any caller that legitimately merges a video-track-less artifact?
- Does the publication contract leave any dangling pending row when a side filters empty? Does `IS_PENDING` handling stay correct?
- Is the completion-count change truthful in every display path (notification, library, recovery), or does removing `maxOf` break a caller expecting one number?
- Extractor-open cost of validating N segments on the merge thread — acceptable, or a stall?
- Threading/lifecycle: predicate runs on the merge coroutine (IO) — confirm no main-thread inspection.

Verdict: **GO / GO WITH FIXES / NO-GO**. Reconcile every Required Fix (or surface the disagreement) **before FREEZE**. Only after FREEZE does implementation begin, itself gated by a second independent review of the code per the standard cycle.

---

## Appendix — exact seams (from 2026-07-08 evidence trace)

| Concern | File:line | Today | After |
|---|---|---|---|
| Predicate (SSOT) | `utils/MediaFileValidator.kt:49,93` | exists, unused on merge | the one definition, injected default |
| **Read-buffer size [RF1]** | `utils/MediaFileValidator.kt:72,141` | fixed 64 KB | sized from `KEY_MAX_INPUT_SIZE` (≥1 MB fallback) — no false-drop of FHD/4K keyframe |
| DualShot assembly filter | `service/RovaRecordingService.kt:3962,3966` | `exists() && length() > 0`, on Main | aligned to predicate, **wrapped `withContext(IO)` [RF2]** |
| Merge chokepoint filter | `utils/VideoMerger.kt:108` | `exists() && length() > 0` | pure helper w/ `isValid` (default `::validateMediaFile`) |
| Count for completion | `service/RovaRecordingService.kt:4258` | `maxOf(p,l)` over pre-filter | **`maxOf` KEPT [RF3]**, now over validated lists = truthful |
| Per-side merge passes | `service/RovaRecordingService.kt:4249–4430` | independent, `p||l` success | unchanged (contract already independent) |
| Empty-side reject | `utils/VideoMerger.kt:109–111` | throws "No valid segments" | unchanged (now reached correctly) |
| Silent drops | `utils/MediaFileValidator.kt:50,54,69,74` | no log on rejection | `w`-log reason via the pure helper |

---

## Appendix B — independent review reconciliation (2026-07-08)

Review verdict **GO WITH FIXES** (independent agent, validated against repo state). Core thesis **VERIFIED**: predicate selects the video track before reading (`MediaFileValidator.kt:56–71,73`); `preflight` is the universal chokepoint (`VideoMerger.kt:58,89` ← every tier `ExportPipeline.kt:201,236,355,389`, `SafRecoverBuilder.kt:85`, recovery `exportRecovered`); no audio-only-by-design caller exists. Three Required Fixes, all **ACCEPTED** and folded in above:

- **RF1** — 64 KB buffer can false-drop a healthy FHD/4K keyframe; T5's fake can't catch it → buffer-size from `KEY_MAX_INPUT_SIZE` + on-device FHD **and** 4K admit check. (§1 corollary, §5.)
- **RF2** — aligned assembly filter runs on `Dispatchers.Main` → ANR → `withContext(Dispatchers.IO)`. (§2.4.)
- **RF3** — `maxOf` removal withdrawn; keep `maxOf` over the now-validated lists (truthful for free, no per-side display target exists). (§3.2.)

No disagreements surfaced to the owner — all three were correct catches. Implementation (code) has **not** begun; it starts only after owner FREEZE and is itself gated by a second independent review of the diff.

# Retrospective — merge validity predicate (PR #177, ADR-0005 §"Merge Admission")

**Date**: 2026-07-08 · **Branch**: `fix/merge-validity-predicate` · **Base**: master `3f286282` (merge)

## What this was
An **investigation-first** branch with an explicit kill criterion, not a feature.
Objective as the owner framed it: *"determine whether Rova can guarantee truthful
output under thermal degradation"* — success measured by **user trust**, not fps.
It resolved into a single architectural invariant: **use one definition of a valid
recording throughout the merge pipeline.**

## The bug
Under dual-HW-encode contention on Exynos, a DualShot segment's video track can
starve while the shared broadcast audio survives. The result is an **audio-only
stub** (~323 KB, false `durationMs`) that the size-only merge filter
`exists() && length() > 0` **admitted** — baking a ~20 s frozen clip into the
landscape output. The portrait muxer meanwhile threw and its header-only orphan
was dropped silently → asymmetric-but-`COMPLETED`, both sides published, no signal.
Root cause was **inconsistent validity semantics**: the merge path used size-only
admission while the existing `MediaFileValidator` decode predicate (ADR-0005) that
*does* reject the frameless stub was never wired into merge.

## What went right
- **The kill criterion did its job.** Reproduction was established from *preserved
  on-device artifacts* (3 corpus sessions + ffprobe + code map), not by inducing
  heat — which is thermal-invariant and non-reproducible on demand. The failure and
  its handling are deterministic at the code level; that's what let the branch pass
  the investigation gate honestly instead of chasing a physical trigger.
- **Spec frozen before code.** The owner required a written implementation spec
  (invariant / merge contract / publication contract / logging / test plan / review)
  and a freeze before any edit. The diff was then a faithful transcription — scope
  never crept toward "fix the contention."
- **"Symmetry is an outcome, never an assumption"** — the owner's sharpest
  constraint. Each side filters independently through the same predicate; no
  reconciliation/padding/truncation. A starved side carries fewer *truthful* clips.
- **Buffer sizing (RF1) came from review, not the original design.** The independent
  spec review flagged that a fixed 64 KB probe buffer could truncate a large FHD/4K
  IDR keyframe and false-drop a **healthy** segment — the exact opposite failure. Fix:
  size from `KEY_MAX_INPUT_SIZE` with a 1 MB floor. Device-verified at genuine 4K.
- **Two device verifications, both clean** (FHD then a real 4K DualShot via the
  Settings path): all segments admitted, zero false drops, zero divergence, both
  sides merged + published.

## What to carry forward
- **Inconsistent-predicate bugs hide behind "it passes a check."** The size filter
  and the decode predicate both looked like "is this a valid segment?" — but only one
  actually opened the file. When two code paths answer the same question with
  different rigor, the looser one is a latent honesty bug. Grep for the *question*,
  not the function name.
- **A universal chokepoint is worth more than N local fixes.** `VideoMerger.preflight`
  already funneled every merge caller; wiring the predicate there (via the pure
  `MergeSegmentFilter`) fixed single-mode, DualShot, recovery, and all three export
  tiers in one place. Upstream `length()` pre-filters were left as-is because they can
  only *remove* candidates the predicate would also remove — never admit one it rejects.
- **Truthful-but-coarse beats granular-but-costly.** R2 (distinguishing no-video-track
  from zero-samples in `DropReason`) was surfaced and the owner chose to keep the
  coarser `INVALID_MEDIA` — the log is truthful and sufficient; a second extractor
  open per drop wasn't worth the granularity.

## Residual (see BACKLOG P2)
The underlying dual-HW-encode contention/frame-starvation under heat is unchanged
(structural Limiter-2, deferred). The now-live question is **UX**: when sides
legitimately diverge, what should the Library surface tell the user? Today it's
honest-but-silent (correct output + warning log). Any disclosure is HTML-first.

## Facts
- 7 files, +474/−8. Suite **2285/0-0-0** (+13: T1–T7 + T-buffer). No new gate
  (protection = pure JVM tests, like ADR-0036/0038). Gate count stays **48**.
- Independent diff review: **GO**, no Required Fixes; R1 (sessionId in per-drop log)
  applied, R2 surfaced + owner-declined.
- Frozen spec: `docs/superpowers/specs/2026-07-08-dualshot-merge-validity-predicate-design.md`.
- Memory: `memory/project_merge_validity_predicate.md`.

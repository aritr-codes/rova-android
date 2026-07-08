# Retrospective — Player → Library transition jitter (investigation, no fix)

**Branch:** `perf/transition-jitter` · **Date:** 2026-07-08 · **Outcome:** investigation closed, **no code merged** — owner decision.

> **Conclusion (owner-ratified):** *"The remaining transition hitch is an architectural consequence of the current navigation model."* No code should be merged solely to partially improve a problem whose root cause is now identified. If navigation is ever redesigned, this investigation is the foundation.

---

## What was investigated

The residual hitch a user sees when popping the Player back to the Library (bento). After ADR-0038 (PR #176) removed the `ExoPlayer.release()` frame from the pop (31→2ms teardown), an owner-visible spike remained. This branch set out to **explain it fully with evidence before proposing any fix**, then — per the owner's explicit reordered sequence — pursue only the lowest-risk work first ("eliminate unnecessary work before optimizing necessary work").

Reordered candidate sequence the owner approved:
1. Re-profile the **release** APK for the true production baseline.
2. Guard the `refresh()`-on-reentry if confirmed unnecessary.
3. Measure.
4. Introduce an app Baseline Profile.
5. Measure.
6. Only then consider architectural change (keep Library composition alive).

## What was confirmed (evidence)

- **Root cause = Nav Compose disposes the covered Library composition.** A `composable()` destination is torn down when the Player is pushed over it (no `popUpTo`); popping back rebuilds it **cold**. That cold rebuild is the hitch.
- **True release baseline** (fresh release build from HEAD `047a983e`, not the stale 2026-07-05 APK which predated ADR-0038): pop ≈ **85ms at N=5**, ~3.3× lighter than the debuggable build (interpreted/JIT inflation).
- **Real corpus size ≈ N=52** (manifest tally). Reconstructed a realistic N=52 / 14-day corpus via the `LegacyScanPolicy` manifest-less-dir mechanism.
- **Pop at N=52 = 117–150ms** (measure pass 65–97ms), confirming an **O(N) on-main derivation** in `LibraryScreen`'s `remember{}` pipeline (collection → groups → `BentoRowPlanner` → `BentoListIndex`).
- **Cost attribution** via the N=5-vs-N=52 natural experiment:
  - **Base ≈ 54ms** — cold tile subcompose inside the measure pass (LazyColumn `SubcomposeLayout`). **Only keeping the composition alive (step 6) removes this.**
  - **N-scaling ≈ 15–40ms** — the derivation pipeline. Hoistable off-main, but recovers only the scaling slice, not the base.
- **FLAG_SECURE has no bearing on the Library path.** `SecureFlagController` is a ref-counted owner of the **Activity window's** FLAG_SECURE; the Player acquires its **own** frame-1 ref (`PlayerScreen.kt:144`), independent of any dispose. Keeping the Library composed is security-neutral. (The vault dispose comment at `MainScreen.kt:262-305` is about the vault→player hop, already solved by the ref-count.)

## What was disproven / undercut

- **"Guard `refresh()`-on-reentry" is not the lever.** `refresh()` already runs fully off-main (`Dispatchers.IO`) and the warm reload conflates to no re-emit (Bitmap identity equals). It is redundant battery work, not a source of the visible hitch. De-scoped to a dirty-flag cleanup at most — and flagged risky (could break Record→Library visibility).
- **VM derivation hoist (reviewer Finding 2) recovers only the N-scaling ≈15–40ms**, not the bulk. Poor complexity-to-payoff ratio → recommended SKIP.
- **Baseline Profile does not target app composables out of the box.** `compileReleaseArtProfile` merges only androidx libraries' own shipped profiles; profiling app composables needs a `:baselineprofile` macrobenchmark module (not present). Speculative for this branch.
- **Premature-stop correction:** an early "stop at 85ms" read was N=5 best-case; the reviewer correctly flagged it. Real-N (52) measurement is what exposed that only **step 6** removes the base cost.

## Why no implementation followed

Every sub-lever inside the current navigation model is either **insufficient** (hoist: ~15–40ms of a 54ms+ base; refresh-guard: not the cause) or **architectural** (step 6: stop disposing the Library). The owner's standing constraint: step 6 is *"an architectural discussion, not a performance optimization"* and is not to be pursued unless production still shows an unacceptable hitch after the lower-risk work — which the evidence shows the lower-risk work cannot deliver. So there is no perf-optimization-shaped fix left to make; the honest move is to stop, not to merge a partial improvement against a now-known root cause.

### Step-6 feasibility (assessed, not built)

If navigation is ever redesigned, the concrete mechanism is a **`dialog()` destination** for the Player — Compose's only native non-disposing overlay in classic navigation-compose — which keeps the Library composed underneath so the pop is a dismiss (~0 rebuild). Assessed **feasible, medium-high risk**, concentrated in three edges, each device-verifiable/sign-off-gated:
1. **Security:** a `Dialog` is a *separate window*; Activity-window FLAG_SECURE does not cover it, so secure vault playback would need FLAG_SECURE set on the dialog's own window (device-verify screenshot-blocked).
2. **Codec:** ExoPlayer surface in a dialog window must be device-verified on the Exynos device (ADR-0038 `setOutputSurface` wedge history).
3. **UX:** `dialog()` loses the `composable()` slide transition → the open/close feel changes → **owner/design sign-off, possibly HTML-first** per the design-freeze rule.

This is recorded so a future navigation redesign starts from the answer, not a re-investigation.

## Cross-references

- BACKLOG: "Player → Library transition jitter" item (now annotated with this conclusion).
- ADR-0038 / `memory/project_player_lifecycle_perf.md` — removed the release() frame (the *other* half of the original owner headache).
- `LegacyScanPolicy.kt` — the corpus-reconstruction mechanism used for realistic N.
- `memory/project_transition_jitter_investigation.md` — durable summary.

## Housekeeping

The device (RZCYA1VBQ2H) held 47 synthetic legacy-clone session dirs (`lg_test_0..5`, `lg_0000..0040`) under `.../files/videos/` used to build the N=52 corpus. Removed on branch close to restore the real corpus state.

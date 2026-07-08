# Retrospective — player-lifecycle perf (PR #176, ADR-0038)

**Date**: 2026-07-08 · **Branch**: `perf/player-lifecycle` · **Base**: master `cc496041`

## What this was
The repo's first branch whose primary goal was a **quality attribute**
(navigation responsiveness) rather than a new capability. Decouple `ExoPlayer`
lifetime from `NavBackStackEntry` scope via an app-scoped `PlayerEngine`
lifecycle holder, with a strict owner-mandated boundary: **holder owns
lifecycle, VM owns playback.**

## What went right
- **Owner set the architectural boundary before code, and it held.** "The holder
  exists to own lifecycle, not playback" became ADR-0038's standing invariant and
  a concrete `ownedPlayer()` lease-guard — not just prose. Scope stayed narrow;
  the TextureView optimization was explicitly kept out.
- **Device verification falsified the core hypothesis, and the architecture
  survived it.** Instance-reuse (the whole point of a "holder") turned out to
  wedge the Exynos codec to black under rapid renav. The fix was a *mechanism*
  swap (fresh player per lease + deferred release) inside the *same* ownership
  architecture — states, tokens, takeover snapshot all unchanged. The boundary
  was robust to a mechanism reversal.
- **Pure state machine paid off.** `PlayerEngineLedger` (zero Android types) made
  the acquire/detach/takeover/A-B-A logic JVM-testable and let the mechanism
  pivot land with the state contract already proven.
- **Honest measurement.** Reported the real numbers (back-nav 31→2 ms) AND that
  the briefed 210/450 ms stalls did not reproduce at that magnitude on 10 s
  clips — the win is teardown leaving the transition frame, stated as such.

## What was hard / lessons
- **A "reuse" optimization can be falsified by hardware.** The paper-obvious win
  (build once, reuse forever) is exactly what the OMX decoder refused. Lesson
  restated from the DualShot arc: on this device, *verify reuse/surface-swap
  claims on hardware before building on them* — `MediaCodec.setOutputSurface` is
  not reliable here.
- **Deferred release needs identity, not tokens.** The delayed `release()` runnable
  captures its specific retired instance by reference (`=== p`); it never consults
  the field or the ownership token. That is what makes "re-acquire inside the
  400 ms window" and "destroy() flush" both safe. Tokens guard *VM→player
  mutation*; instance identity guards *release*. Two different jobs.
- **Compose Nav teardown ordering is a recurring hazard.** Same class as the
  resume-slot races: the outgoing entry's `ON_STOP` fires before `onCleared`, so
  every player-mutating path must be lease-guarded. Re-confirmed here.

## Process
Approved pipeline ran clean: plan → TDD (ledger tests first) → implement →
3 independent Review Agent rounds (all GO WITH FIXES, all reconciled: isOwner
lease-guards, then doc annotations) → device verification (which forced the
pivot) → release stewardship. A 4th independent falsification pass on the
deferred-release path ran at merge time.

## Follow-ups → BACKLOG
- None blocking. The player→library transition frame-spike (BACKLOG P2) is
  *partly* relieved by the 31→2 ms teardown; the remaining spike is the Compose
  shared-element/grid-entry path, still a separate candidate.

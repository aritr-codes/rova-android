# NEXT TASK — DualShot encoder Limiter 2 (fresh-session handoff)

> **⛔ HISTORICAL / CLOSED (2026-06-30, PR #157 → master `b1f7850`).** Limiter 2 was
> diagnosed and **closed with no fix**: the ~45ms encoder service / ~22fps merged
> output is **inherent structural dual-HW-encode contention** on this Exynos (single
> encode = ~37ms/24fps; two concurrent encodes add ~5ms → ~22fps). No cheap lever
> (`OPERATING_RATE`/`PRIORITY` = null; muxer 0.5ms); the ~2fps prize is not worth the
> stability-stack risk. The DEBUG cadence probe has been removed. This document is kept
> for provenance only — do **not** act on the task below. Full verdict:
> `docs/superpowers/specs/2026-06-29-dualshot-fps-cadence-findings.md` ("Limiter-2 RESOLUTION").

**Created:** 2026-06-29, after the AE-floor (Limiter 1) slice shipped to master (`4325808`, PR #155).
**Paste the "Session prompt" block below into a fresh session to continue.**

---

## Where we are

The 2026-06-29 fps-cadence diagnosis (PR #154) proved the DualShot ~18–20fps plateau is **compound**:

- **Limiter 1 — auto-exposure (FIXED, shipped #155).** Dim light stretched exposure → ~16.7fps. Now floored to 24fps via `AeFpsRangePolicy` + `applyAeFpsFloor` (capability-gated `[24,30]`, brightness-preferring `(24,24)` fallback on devices lacking a true span). Device-verified: dim `cameraHW` holds 24fps.
- **Limiter 2 — encoder service time (OPEN, this task).** Once the camera feeds ≥24–30fps, the encoder's per-side GPU service (`take→finish` ≈ **43–45ms**, dominated by `drawFrame` + `glFinish`) caps **merged-file output at ~21–22fps** with mailbox drops. `swap (finish→swap) ≈ 1.3ms`, so it is NOT MediaCodec backpressure — it is the GPU draw + the per-frame `glFinish` CPU stall.

## Hard constraints / landmines

- **The encoder `glFinish` is load-bearing.** Per the fence-sync design §8 ("the encoder's `glFinish` stays"), it guarantees FBO-slot read-safety for the depth-3 ring. Removing it naively → tearing / read-after-write races. Any change here must preserve the #25–#35 stability stack (FBO ring, `glFenceSync`/`glWaitSync`, two-tier lock, latest-wins `FrameMailbox`). This is the central tension of the task.
- **Confirm the cause before fixing (systematic-debugging).** The findings note `service` rose 38ms (dim Run 1) → 45ms (bright Run 2). Determine whether that is the higher frame rate saturating the GPU vs **thermal accumulation across back-to-back sessions** before committing a fix. A micro-diagnosis comes first.
- **The DEBUG fps-cadence probe is still in the tree** (`CadenceProbe`, `CadenceStats`, `EglRouter`/`EglEncoder` taps, `FrameMailbox.overwriteCount`). Use it to measure each step. **Remove the probe before the Limiter-2 PR** (per the diagnosis spec §5).
- Real-device testing on **RZCYA1VBQ2H** is mandatory (emulators fail CameraX video). Controller runs all gradle/git (Windows shared daemon); subagents EDIT-ONLY if using SDD.
- Verify via `:app:assembleDebug` (fires 47 gates on preBuild) + `:app:testDebugUnitTest`, **not** `:app:lintDebug` (pre-existing `VaultAndroidOps:267` NewApi RED is unrelated).
- No PR/merge/push without explicit owner GO; never push master directly.

## Candidate directions (for the brainstorm to weigh — not pre-decided)

1. **Relax/replace the per-frame `glFinish`** with a tighter reliance on the existing fence-sync chain (e.g. a fence the encoder waits on instead of a full CPU stall), IF read-safety can be proven preserved. Highest risk, highest payoff.
2. **Cheaper per-side draw** — reduce `drawFrame` cost (shader/texture/viewport work) so service time drops without touching `glFinish`.
3. **Revisit the single-`CameraEffect(PREVIEW)` fan-out topology** — the diagnosis spec's gated escalation (e.g. parallelize the two per-side encodes, or a different surface/FBO arrangement).

Order matters: a micro-diagnosis to attribute the 45ms (draw vs glFinish vs GPU saturation vs thermal) should precede picking a direction.

## Reference

- Diagnosis spec: `docs/superpowers/specs/2026-06-29-dualshot-fps-cadence-diagnosis-design.md`
- Findings + per-stage data + AE-floor verification: `docs/superpowers/specs/2026-06-29-dualshot-fps-cadence-findings.md`
- AE-floor design + plan: `docs/superpowers/specs/2026-06-29-dualshot-ae-fps-floor-design.md`, `docs/superpowers/plans/2026-06-29-dualshot-ae-fps-floor.md`
- Render-path audit: `memory/project_render_architecture_audit.md`
- Stability stack history: `memory/project_dualshot_stability_stack.md`

---

## Session prompt

> Continue the DualShot fps work. Limiter 1 (auto-exposure) shipped in #155; the open item is **Limiter 2 — the encoder's ~45ms per-side GPU service time (`drawFrame` + `glFinish`) that caps merged-file output at ~22fps once the camera feeds ≥24fps**. Read `docs/superpowers/handoffs/2026-06-29-NEXT-dualshot-encoder-limiter2.md` and `docs/superpowers/specs/2026-06-29-dualshot-fps-cadence-findings.md` first. Start with a **micro-diagnosis** (the DEBUG probe is still in the tree) to attribute the 45ms across draw / `glFinish` / GPU-saturation / thermal — the encoder `glFinish` is load-bearing FBO read-safety (fence-sync §8) and must not be naively removed. Then brainstorm → spec → plan → subagent-driven (Windows EDIT-ONLY override; controller runs gradle/git; device RZCYA1VBQ2H). Remove the DEBUG cadence probe before the Limiter-2 PR. No push/PR without my GO.

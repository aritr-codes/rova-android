# NEXT TASK — fresh-session handoff (after SingleVideoRecorder extraction shipped)

> ⚠️ **HISTORICAL — SUPERSEDED 2026-07-01.** Both candidates in this handoff shipped: δ0 CameraX 1.5.3 (PR #161 → `a0e0ff3`) and the DualShot jitter/thermal cycle (jitter = no fixable defect; thermal "make lighter" = ADR-0035 thermal-adaptive encode decimation, PR #162 → `d4222c2`, device-verified). The current handoff is **`2026-07-01-NEXT-player-library-transition-jitter.md`**. Kept for history.

**Created:** 2026-06-30, after `SingleVideoRecorder` extraction merged (PR #159 → master `11913cc`, device-verified RZCYA1VBQ2H).
**Paste the "Session prompt" block at the bottom into a fresh session to begin.**

> **Re-prioritize at session start — do NOT assume.** The candidates below are ranked by what the memory/backlog trail makes most concrete, but the owner picks. If they want something else, the live alternatives are spelled out.

---

## Where things stand (closed recently)

- **DualShot fps arc — fully closed.** Limiter 1 (dim AE collapse) fixed → 24fps floor (#155). Limiter 2 (encoder ~22fps) diagnosed as inherent dual-HW-encode contention on this Exynos, closed no-fix (#157). **Do NOT re-tread fps/throughput.**
- **`RovaRecordingService.kt` structural work — done.** The audit-named target, `SingleVideoRecorder` extraction, shipped (#159): single-mode CameraX lifecycle now mirrors `service/dualrecord/DualVideoRecorder` in `service/singlerecord/`. The service is at the pure-helper floor; there is **no remaining valuable structural service refactor**.
- **Player functional slices — done.** Resume (#137), wall-clock playhead (#139), speed/double-tap/auto-hide (#143). Further player work is polish/NEEDS-SPEC, not a clear pickup.

## Top candidates (ranked)

### 1. DualShot performance — jitter + sustained-thermal "make app lighter" (P2, owner-reported 2026-06-17)
The strongest remaining capture candidate now that fps and the service split are closed. **Profiling-gated — do not guess a fix.** Two narrowly-scoped live symptoms (the fps/throughput portion is OUT of scope, already resolved):
- **(a) Perceived jitter/smoothness** — DualShot preview/capture is visibly less smooth than the native camera. This is frame *pacing* / even delivery, distinct from throughput (which is the known-inherent ~22fps). Suspect the EGL14/GLES20 fan-out (`service/dualrecord/**`: single `CameraEffect(target=PREVIEW)` → FBO-ring → fence-sync → dual MediaMuxer), or preview-side/display-cadence.
- **(b) Sustained-thermal load** — after ~5+ clips the device heats enough to trip thermal auto-stop (ADR-0016/0019). Owner ask: reduce sustained GPU/encoder load (preview render cost, bitrate, GL pump efficiency).
- **Method:** a fresh device profiling pass FIRST (systrace / GPU profiler / `dumpsys gfxinfo` on RZCYA1VBQ2H) to locate the actual jitter source and the dominant sustained-load contributor, THEN scope a fix. Re-scope given the encoder ceiling is now known-inherent. *(Source: BACKLOG Capture/Mode; `memory/project_dualshot_ae_fps_floor.md`, `project_dualshot_stability_stack.md`; ADR-0009/0034.)*

### 2. δ0 — CameraX 1.4.2 → 1.5.3 bump (P3, merge-later, decoupled)
Low-risk reliability win that may *also* help candidate 1: 1.5.1 fixes a `SurfaceProcessor`-shutdown crash on DualShot's exact path, plus recording-churn + Android-17 DynamicRange crash fixes. The PR #115 probe already proved 1.5.3 compiles clean against current Single + DualShot (zero API breaks). **Could be sequenced FIRST** — it de-risks the candidate-1 profiling baseline and might reduce thermal/jitter for free. Plan archived in PR #115 branch `feat/pr-delta-dualsight`: `docs/superpowers/plans/2026-06-14-delta0-camerax-1.5.3-bump.md`. Execute its regression plan (baseline-vs-bumped `ffprobe` diff + device smoke), then merge as standalone reliability. *(Source: BACKLOG Capture/Mode δ0; 2026-06-14 decision review.)*

### 3. Accessibility source-remediation cycle (P3, ADR-0020 standing)
The 2026-05-29 audit's deferred Moderate/Advisory remainder (touch-target sizing rows, semantics-presence, live-regions) + `TokenContrastTest` hardening. ADR-0020 "WCAG 2.2 AA by default" is a standing requirement. Real user value, fully spec'd backlog rows, but lower owner-urgency than the owner-reported DualShot symptoms. *(Source: BACKLOG Accessibility; `docs/accessibility/remediation-backlog.md`.)*

## Constraints (unchanged, load-bearing)
- Design-first for any non-trivial work: brainstorm → spec (`docs/superpowers/specs/`) → owner-review → plan → subagent-driven → review-gate → device-verify → owner GO. codex peer-review for architecture/perf claims.
- Windows shared Gradle daemon: subagents EDIT-ONLY, controller runs all gradle/git. Verify via `:app:assembleDebug` (fires 47 gates on preBuild) + `:app:testDebugUnitTest`, NOT `:app:lintDebug` (pre-existing `VaultAndroidOps:267` NewApi RED unrelated).
- Real-device RZCYA1VBQ2H mandatory for capture work (emulators fail CameraX video; profiling MUST be on-device). adb gotcha: `MSYS_NO_PATHCONV=1` for `/sdcard/` paths under Git Bash.
- Gate rule: amend ADR → regenerate/extend gate → move code; never edit a gate green. No push/PR/merge without explicit owner GO; never push master directly (classifier-blocked — use a branch + PR even for docs).
- Run `pwsh scripts/preflight.ps1` at task start; untracked `gradle_*.log` are ephemeral noise.

---

## Session prompt

> Re-prioritize first, don't assume. The DualShot fps arc and the `RovaRecordingService.kt` structural work (`SingleVideoRecorder` extraction, PR #159) are both closed. Read `docs/superpowers/handoffs/2026-06-30-NEXT-dualshot-jitter-thermal.md` and the relevant `memory/` entries, then recommend the next task. My current lean is the **DualShot jitter + sustained-thermal "make app lighter" cycle** (P2, owner-reported) — but it is **profiling-gated**: do a fresh on-device profiling pass (systrace / GPU profiler / `dumpsys gfxinfo` on RZCYA1VBQ2H) to locate the real jitter source and dominant sustained-load contributor BEFORE scoping any fix; the fps/throughput portion is already resolved (Limiter 1 #155 / Limiter 2 #157) and is OUT of scope. Consider sequencing the **δ0 CameraX 1.4.2→1.5.3 bump** first (low-risk, may reduce thermal/jitter for free, probe already proved clean compile). Design-first (brainstorm → spec → owner-review → plan → subagent-driven, Windows EDIT-ONLY + controller gradle/git, device RZCYA1VBQ2H). codex-review the diagnosis. No push/PR without my GO.

# NEXT TASK — fresh-session handoff (after δ0 bump + DualShot thermal decimation shipped)

**Created:** 2026-07-01, after the stacked merge train: δ0 CameraX 1.4.2→1.5.3 (PR #161 → master `a0e0ff3`) + DualShot thermal-adaptive encode decimation (ADR-0035, PR #162 → master `d4222c2`), both device-verified on RZCYA1VBQ2H.
**Paste the "Session prompt" block at the bottom into a fresh session to begin.**

> **Re-prioritize at session start — do NOT assume.** Candidates are ranked by what the memory/backlog trail makes most concrete, but the owner picks. If they want something else, the live alternatives are spelled out.

---

## Where things stand (closed recently)

- **DualShot fps arc — fully closed.** Limiter 1 (dim AE collapse) → 24fps floor (#155). Limiter 2 (encoder ~22fps) = inherent dual-HW-encode contention, closed no-fix (#157). **Do NOT re-tread fps/throughput.**
- **DualShot jitter — no fixable defect.** The 2026-06-30 profiling proved the EGL/preview path is dead-steady over 5-min runs; perceived roughness = the inherent ~22fps ceiling. `docs/superpowers/specs/2026-06-30-dualshot-jitter-thermal-findings.md`.
- **DualShot sustained-thermal "make lighter" — SHIPPED.** ADR-0035 thermal-adaptive encode decimation (#162): at SEVERE, encode every 2nd frame → half encoder duty → later CRITICAL autostop. Preview full-rate, merge-safe, reverts on cooldown, factor 1 byte-identical. 48th gate `checkDecimationEncoderOnly`. `memory/project_dualshot_thermal_decimation.md`.
- **δ0 CameraX 1.5.3 — SHIPPED** (#161). Toolchain now CameraX 1.5.3.
- **`RovaRecordingService.kt` structural work — done** (SingleVideoRecorder #159; service at pure-helper floor).
- **Player functional slices — done** (resume #137, wall-clock playhead #139, speed/double-tap/auto-hide #143).

## Top candidates (ranked)

### 1. Player → Library transition jitter (P2 · a11y comfort · owner-experienced 2026-07-01) — RECOMMENDED
Navigating from the video player back to the Library (into the thumbnail grid) is **jittery/shaky enough to be headache-inducing** (owner, during the ADR-0035 device-verify). This is a real WCAG 2.2 / motion-comfort concern (ADR-0020 "AA by default").
- **Already localized:** the profiling's `dumpsys gfxinfo` spike ("3942 missed vsync / p99 109ms" over a 5-min DualShot window) was proven to be this **post-stop Compose nav / HWUI path into the thumbnail grid**, NOT the camera preview (the EGL probe was dead-steady over the same window). See findings §"Jitter" instrument 3.
- **Profiling-gated (but half-done):** do a fresh **during-navigation** `dumpsys gfxinfo com.aritr.rova` (reset stats, perform player→Library nav, dump) to pin the janky frames to specific composables/animations, THEN scope. Likely levers: reduced-motion gate the transition (reuse the `ReducedMotion` seam already used elsewhere — `memory/project_accessibility_wcag_audit.md`), stabilize the shared-element / grid-entry animation, or drop an over-expensive enter animation. Suspect surfaces: `ui/screens/player/` ↔ `ui/screens/HistoryScreen.kt` / `ui/library/` nav transition; check for `AnimatedContent`/`Crossfade`/large-grid first-frame cost.
- **Scope:** bounded UI/a11y task, design-first appropriate. Pure-helper + `ReducedMotion` gate is the house pattern; a `checkA11yAnimationGated`-style invariant may already cover part of it (verify the transition is gated). *(Source: this session; BACKLOG Known Bugs "Player → Library transition"; findings §Jitter.)*

### 2. DualShot P/L divergence + landscape mid-clip freeze under sustained heat (P2 · reliability)
On a genuinely hot device, a DualShot session can end with portrait one clip short of landscape and a ~20s landscape mid-clip freeze. **Root cause: dual-HW-encode contention (Limiter-2) amplified by heat** — under pressure one side's segment gets starved to too-few/zero frames → `MediaFileValidator` drops it → P/L clip-count desync. **Device evidence:** reproduces only *full-rate + hot*; a cool full-rate control run was clean/symmetric. Orthogonal to ADR-0035 (which shares one broadcast to both encoders and *reduces* the contention when hot). **Deeper/riskier** than #1 — touches the load-bearing EGL/FBO/merge stack (Limiter-2 territory the fps close deemed not-worth-the-risk for throughput, but the *reliability* angle — dropped clips — is new). Investigate: per-side frame-drop accounting under heat; whether merge should pad/tolerate a starved side to keep P/L counts aligned; louder logging on the `MediaFileValidator` drop. *(Source: BACKLOG Known Bugs; this session's device-verify.)*

### 3. ThermalStatusSignal fall-dwell stickiness (P3 · signal accuracy)
ADR-0019 hysteresis is edge-triggered (`applyThermalHysteresis` runs only in the PowerManager change callback; 3s fall-dwell steps down one level per event). On a quiet plateau after a drop, `state.value` stays elevated until the next `ON_RESUME refresh()` — so the "device hot" banner AND the ADR-0035 decimation factor can stick elevated. Safe-direction (errs cooler), affects banner+decimation identically. Fix idea: a low-frequency periodic re-eval / dwell-advancing timer so the signal converges without external events. Pure `ThermalHysteresis` is already JVM-testable. *(Source: this session's ADR-0035 device-verify; `ThermalStatusSignal.kt` / `ThermalHysteresis.kt`.)*

### 4. Accessibility source-remediation cycle (P3 · ADR-0020 standing)
The 2026-05-29 audit's deferred Moderate/Advisory remainder + `TokenContrastTest` hardening. Real value, fully spec'd rows, lower owner-urgency. *(Source: `docs/accessibility/remediation-backlog.md`.)*

## Constraints (unchanged, load-bearing)
- Design-first for any non-trivial work: brainstorm → spec (`docs/superpowers/specs/`) → owner-review → plan → subagent-driven → review-gate → device-verify → owner GO. codex peer-review for architecture/perf/a11y claims.
- Windows shared Gradle daemon: subagents EDIT-ONLY, controller runs all gradle/git. Verify via `:app:assembleDebug` (fires **48** gates on preBuild) + `:app:testDebugUnitTest`, NOT `:app:lintDebug` (pre-existing `VaultAndroidOps:267` NewApi RED unrelated). PowerShell cwd can drift to a subdir after a Bash `cd`; `Set-Location` back to repo root before `.\gradlew.bat`.
- Real-device RZCYA1VBQ2H mandatory for capture/UI-motion work (emulators fail CameraX; profiling MUST be on-device). adb gotchas: `MSYS_NO_PATHCONV=1` for `/sdcard/` paths under Git Bash, AND a Windows-form dest path (`C:/Users/...`) for `adb pull`. Thermal states forced via `adb shell cmd thermalservice override-status N` (N = ThermalStatus ordinal: SEVERE=3, CRITICAL=4; `reset` to unlock; an instantaneous override drop can leave the app signal stuck — force `refresh()` via home+relaunch).
- Gate rule: amend ADR → regenerate/extend gate → move code; never edit a gate green. No push/PR/merge without explicit owner GO; never push master directly (classifier-blocked — use a branch + PR even for docs). Stacked-PR train: merge WITHOUT delete → sync master → re-check next PR mergeable → merge → then delete branches (`memory/feedback_stacked_pr_merge_train.md`).
- Run `pwsh scripts/preflight.ps1` at task start; untracked `gradle_*.log` are ephemeral noise.

---

## Session prompt

> Re-prioritize first, don't assume. The DualShot fps arc, jitter (no fixable defect), sustained-thermal "make lighter" (ADR-0035 decimation, #162), δ0 CameraX 1.5.3 (#161), the `RovaRecordingService.kt` structural work, and the player functional slices are ALL closed/shipped. Read `docs/superpowers/handoffs/2026-07-01-NEXT-player-library-transition-jitter.md` and the relevant `memory/` entries, then recommend the next task. My current lean is the **player → Library transition jitter** (P2, a11y comfort — I experienced it as headache-inducing; profiling already localized it to the Compose nav/HWUI path into the thumbnail grid, NOT the camera). It's lightly profiling-gated: do a fresh during-nav `dumpsys gfxinfo com.aritr.rova` on RZCYA1VBQ2H to pin the janky frames before scoping, then likely reduced-motion-gate + stabilize the transition (reuse the `ReducedMotion` seam). Runners-up: DualShot P/L divergence + landscape freeze under sustained heat (P2 reliability, Limiter-2 — deeper/riskier), thermal fall-dwell stickiness (P3). Design-first (brainstorm → spec → owner-review → plan → subagent-driven, Windows EDIT-ONLY + controller gradle/git, device RZCYA1VBQ2H). codex-review the diagnosis. No push/PR without my GO.

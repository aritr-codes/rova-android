# NEXT TASK — `SingleVideoRecorder` extraction (fresh-session handoff)

**Created:** 2026-06-30, after the DualShot fps arc closed (encoder Limiter 2 diagnosed & closed no-fix, PR #157 → master `b1f7850`).
**Paste the "Session prompt" block at the bottom into a fresh session to begin.**

> **Re-prioritize at session start — do NOT assume.** This is the strongest *engineering* candidate on the backlog, but it is a design-first structural project, not a quick win. If the owner wants something lighter/faster instead, the live runner-up is the narrowed **DualShot jitter/thermal** item (BACKLOG → Capture/Mode; the fps sub-track is now closed, so only frame-pacing smoothness + sustained-thermal "make app lighter" remain, both profiling-gated).

---

## Why this task

The 2026-06-28 `RovaRecordingService.kt`-split audit (PR #151, `memory/project_service_decomp_audit.md`) found the service is **already at the pure-helper floor** — every extractable pure collaborator/seam is done, and the rest is irreducible framework orchestration + gate-fenced terminal logic that a cosmetic extension-split would only put at risk for zero coverage gain. The audit named **exactly one genuinely-valuable future structural target**:

> Extract a **`SingleVideoRecorder`** collaborator to mirror the existing `DualVideoRecorder` — single-mode recording lifecycle is **inline in the service** while dual-mode is already a self-contained class.

This is **NOT zero-behavior** and **NOT the pure-helper pattern** — it moves ownership of the single-mode CameraX recording lifecycle out of the service into a class, the way `service/dualrecord/DualVideoRecorder` already owns the dual path. Value: architectural symmetry (single ↔ dual), a real testability/ownership seam, and a smaller, more legible `RovaRecordingService`.

## Scope (what moves)

Single-mode recording lifecycle currently inline in `RovaRecordingService.kt`. The audit enumerated the concerns this owns — spec each boundary before touching code:
- CameraX `VideoCapture`/`Recorder` bind + `Recording` start/stop for single mode (vs `setupDualCamera`/`DualVideoRecorder` for dual).
- Finalize-callback timing (the CameraX `VideoRecordEvent.Finalize` await — cf. dual's `DualRecordEvent.Finalize`).
- Watchdog arm/cancel, segment-persistence submission, rotation freeze (`setTargetRotation` boundary), recovery handoff, stop/cancel interaction.

## Hard constraints / landmines

- **Gate-fenced logic stays correct.** Several gates pin behavior to the `RovaRecordingService` `relPath` and to single-entry/ordering invariants: `checkUserStoppedBeforeMerge`, `checkCompletedWriteOnlyFromPerformMerge`, `checkExportPipelineSingleEntry`, `checkSetTargetRotationBoundaryOnly`, `checkWakeLockBoundedAcquire`/`checkWakeLockHeldRefresh`/`checkWakeLockZeroGapRefresh`, `checkStopNoGetService`. Moving code across the file boundary can RED-fire these. For anything an ADR/gate mentions: **amend the ADR clause first, regenerate/extend the gate, then move code** — and prove the gate still RED-fires on a real violation with a byte-identical message. Do **not** edit a gate to make it green.
- **`COMPLETED` is written only by `performMerge`** (`checkCompletedWriteOnlyFromPerformMerge` + `checkAtomicTerminalWriteForbiddenPair`). Terminal-state writes stay one atomic call. Don't let the extraction split a terminal write into a pair or relocate the `COMPLETED` write.
- **Mirror `DualVideoRecorder`'s shape** (constructor = config; lazy resource alloc; idempotent `release()`; `start()` returns a `Recording` handle; per-segment reuse). Read `service/dualrecord/DualVideoRecorder.kt` + `DualRecording.kt` first — match the seam, callback, and teardown-ordering conventions exactly.
- **JVM-test what becomes pure**, keep framework calls behind a thin seam (house pure-helper pattern). CameraX/MediaMuxer/AlarmManager/FGS are no-ops under `isReturnDefaultValues=true`, so the recorder class itself won't be JVM-runnable — extract any pure decision logic (state machine, finalize-error policy, segment-path build) into tested helpers, like the dual side did (`DualMuxerStateMachine`, `SegmentPathBuilder`, `FinalizeErrorPolicy`, …).
- **Zero behavior change to the recording loop.** This is a structural move, not a feature. The segment cadence, STOP paths (user-stop vs loop-exhaust), drift policy, and recovery handoff must be byte-for-byte equivalent. Prove it.
- Real-device testing on **RZCYA1VBQ2H** is mandatory (emulators fail CameraX video). Single-mode **and** DualShot must both still record end-to-end (don't regress dual while refactoring single). Controller runs all gradle/git (Windows shared daemon); subagents EDIT-ONLY if using SDD.
- Verify via `:app:assembleDebug` (fires 47 gates on preBuild) + `:app:testDebugUnitTest`, **not** `:app:lintDebug` (pre-existing `VaultAndroidOps:267` NewApi RED is unrelated).
- codex MCP peer review for the architecture/boundary decisions (this is contested/novel structural work — exactly the consult case). No PR/merge/push without explicit owner GO; never push master directly.

## Process

Design-first: **brainstorm → design doc (`docs/superpowers/specs/`) → self-review → owner reviews spec → writing-plans skill → subagent-driven implementation → review-gate → device-verify → owner GO**. Do not start editing code before the boundary spec is owner-approved — the value is entirely in getting the ownership boundary right.

## Reference

- Service-split audit (why this is the only valuable target): `memory/project_service_decomp_audit.md`; BACKLOG → Reliability/Service.
- The mirror to copy: `app/src/main/java/com/aritr/rova/service/dualrecord/DualVideoRecorder.kt` + `DualRecording.kt` + the tested helpers under `service/dualrecord/internal/`.
- Service + lifecycle invariants: `CLAUDE.md` (Three control planes; Session lifecycle and `Terminated`); ADR-0005 (recovery), ADR-0006 (lifecycle robustness).
- Gate mechanism + the "change HOW not WHAT" rule: `CLAUDE.md` Static-check-gate section.

---

## Session prompt

> Start the **`SingleVideoRecorder` extraction**. The DualShot fps arc is fully closed (Limiter 1 #155, Limiter 2 closed no-fix #157). Per the 2026-06-28 service-split audit, the one genuinely-valuable structural target is extracting a `SingleVideoRecorder` collaborator that mirrors the existing `service/dualrecord/DualVideoRecorder` — single-mode CameraX recording lifecycle is currently inline in `RovaRecordingService.kt` while dual-mode is already a class. Read `docs/superpowers/handoffs/2026-06-30-NEXT-single-video-recorder-extraction.md` and `memory/project_service_decomp_audit.md` first, then `service/dualrecord/DualVideoRecorder.kt` + `DualRecording.kt` to match the seam. This is a **structural move, zero behavior change** — the segment cadence, both STOP paths, drift policy, terminal-write ordering, and recovery handoff must stay byte-equivalent, and the gate-fenced logic (`checkUserStoppedBeforeMerge`, `checkCompletedWriteOnlyFromPerformMerge`, `checkExportPipelineSingleEntry`, `checkSetTargetRotationBoundaryOnly`, `checkWakeLock*`, `checkStopNoGetService`) must still RED-fire on real violations (amend ADR → regenerate gate → move code; never edit a gate green). Design-first: brainstorm → spec → owner-review → plan → subagent-driven (Windows EDIT-ONLY; controller runs gradle/git; device RZCYA1VBQ2H — verify single AND DualShot both still record). codex-review the boundary. Verify via `:app:assembleDebug` + `:app:testDebugUnitTest`, not `:app:lintDebug`. No push/PR without my GO.

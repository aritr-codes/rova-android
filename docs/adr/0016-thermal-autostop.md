# ADR-0016 — Phase 4 Slice 3: Thermal auto-stop + echo + Tips sheet

**Status:** Accepted
**Date:** 2026-05-24
**Supersedes:** none
**Related ADRs:** ADR-0007 (record warning sheets), ADR-0013 (warning re-skin v3 chrome canon), ADR-0015 (storage-full autostopped echo — direct sibling pattern)
**Spec:** `docs/superpowers/specs/2026-05-24-phase-4-slice3-thermal-autostop-design.md`

## Context

`WarningCenterContract.md` §4.3 C3.5 declared the design intent for thermal escalation: state-machine banners on the active HUD plus an auto-stop with reason `THERMAL` at `SEVERE` and above. As of master `b9d4773` (Slice 2 merge), the read path existed (`ThermalStatusSignal`) but only refreshed on `ON_RESUME`; the auto-stop did not exist; the new `THERMAL` `StopReason` did not exist; no post-stop user feedback existed.

## Decision

Three coordinated changes that land in a single PR (Slice 3):

### 1. Threshold choice

Auto-stop fires at **CRITICAL** or above. **SEVERE** stays a banner-only state with the existing "Stop now" CTA already wired to `onStopRecording`.

Rationale:
- The contract's "auto-stop at SEVERE" intent was revised during Slice 3 brainstorming: instant SEVERE stop is too aggressive (false-stop risk on quick thermal spikes) and SEVERE is still in the user-visible "uncomfortable to hold" band where a banner nudge is more respectful than an immediate stop.
- CRITICAL is where the OS begins aggressive throttling. Past CRITICAL the encoder output starts degrading; auto-stopping there protects footage integrity.
- The active-HUD banner at SEVERE already exposes the user's stop affordance — the auto-stop is a safety net for users who didn't react.

### 2. Trigger cadence: between-segment

Layer 4 of `RovaRecordingService.checkSegmentGates` reads `ThermalStatusSignal.state.value` between segments and returns `Terminate(StopReason.THERMAL)` when `>= CRITICAL`. Mirrors the LOW_STORAGE (Layer 3) pattern.

Worst-case delay: one segment duration. Acceptable because:
- The SEVERE banner already nudges manual stop one level earlier.
- A mid-segment interrupt would risk encoder/muxer corruption on the in-progress segment. Out of scope for v1 (spec §3 non-goal).

The pure threshold helper `SegmentGateThermal.shouldTerminate(status)` keeps Layer 4 JVM-testable (matches the existing `StorageEstimator.accumulatedSessionBytes` extraction pattern; preserves ADR-0007 test policy).

### 3. Push listener lifecycle

`ThermalStatusSignal` gains `start()` / `stop()` backed by `PowerManager.addThermalStatusListener` (API 29+) via two new constructor seams (`addListener`, `removeListener`). Default no-ops preserve every existing test fixture.

`RovaApp.onCreate` calls `start()`. The OS releases the registration on process death — no explicit `stop()` from app teardown (`Application.onTerminate` is not reliably invoked on production devices).

Listener fires on the main thread via `Handler.post` so `_state.value` writes are safe without an explicit dispatcher.

Vendor-quirk safety: `addListener` / `removeListener` wrap their `pm.addThermalStatusListener(...)` calls in `runCatching` and log on failure. The signal degrades to `ON_RESUME` polling — banner still works (just lagged), gate still works (it reads `state.value`, no listener required).

### 4. Echo banner — reuses Slice 2 plumbing

`SessionAutoStopEchoSignal` is already reason-agnostic (per its KDoc, written in Slice 2). The Slice-2 `WarningPrecedence` echo branch is rewritten from a single-id if to a `when (stopReason)`: `LOW_STORAGE → STORAGE_FULL_AUTOSTOPPED`; `THERMAL → THERMAL_AUTOSTOPPED`. New `WarningId.THERMAL_AUTOSTOPPED` (ADVISORY tier, slot #13).

`WarningCenter`'s Idle TopBanner special-case generalizes from one-arm to a two-arm `when` over `(STORAGE_FULL_AUTOSTOPPED, THERMAL_AUTOSTOPPED)`. The overflow handler ("Don't show again", "Review session") is factored into a shared `handleEchoOverflow` used by both arms.

Per-session-id persistent dismissal reuses `RovaSettings.dismissedAutoStopEchoIds` (Slice 2 — backup-excluded).

### 5. Tips sheet — static content, no nav route

`THERMAL_AUTOSTOPPED` banner CTA is "Tips to cool down" → `ThermalTipsSheet`, a `ModalBottomSheet` with 5 static bullets + "Got it". Hosted from `RecordScreen` via `rememberSaveable` visibility. No new nav destination — a sheet is sufficient.

## Consequences

- `StopReason.THERMAL` is now part of the manifest contract. Recovery code paths that switch on `StopReason` already default-handle unknown reasons (or do not enumerate it). T8's grep sweep confirms only the service notification copy needed a new arm.
- Worst-case auto-stop latency at CRITICAL is one segment duration. Owner accepts this in exchange for muxer-safety.
- Push listener registered at `Application.onCreate`. Cold-start receiver paths that bypass UI initialization now pay a tiny additional cost (one main-thread listener registration). Negligible.
- Pre-API-29 devices receive no banner, no gate, no echo — slice is invisible. `ThermalStatusSignal.start()` returns early on the SDK gate.
- `WarningCenter`'s Idle TopBanner branch is no longer single-arm. Future echo additions (e.g. a permission-revoked echo) plug into the same `when`.

## Alternatives considered

- **Mid-segment thermal interrupt.** Rejected — risks encoder corruption on the in-progress segment. Re-evaluate if owner reports CRITICAL→OS-kill races in the field.
- **15-second grace-period countdown UI** between SEVERE and auto-stop. Rejected — adds a banner state machine and a new countdown chrome; owner picked "persistent SEVERE banner + auto-stop at CRITICAL" as the simpler product line.
- **Telemetry of thermal-time per session.** Out of scope; defer to a future "session diagnostics" surface.
- **A `/help/thermal` nav route** instead of a sheet. Rejected — a single-topic help surface is over-engineered for one bullet list; a sheet integrates with the existing chrome.
- **A separate `SessionThermalAutoStopEchoSignal`** mirroring `SessionAutoStopEchoSignal`. Rejected — the latter is already reason-agnostic; generalization is one when-arm and a parallel signal would duplicate the dismiss-persistence wiring.
- **Bumping `StopReason.SEVERE` to the auto-stop threshold** (contract's original wording). Revised during brainstorming for the reasons above; this ADR is the canonical record of the revision.

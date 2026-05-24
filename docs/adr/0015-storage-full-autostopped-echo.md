# ADR-0015 — `STORAGE_FULL_AUTOSTOPPED` echo banner (Phase 4 Slice 2)

**Status:** Accepted (2026-05-24)
**Phase:** 4 (post-4.1c)
**Supersedes:** none (additive — fills `WarningCenterContract.md` §4.2 row C2.3 "read-only echo on record" surface that was specified but unwired).
**Related ADRs:** ADR-0006 (terminal manifest reasons), ADR-0007 (warning sheet model), ADR-0013 (warning re-skin v3 chrome canon), ADR-0014 (snooze persistence).

## Context

The service auto-stops mid-recording when `SegmentGate.compute()` detects insufficient storage for the next segment (ADR-0006 row 9). It writes `Terminated.KILLED_BY_SYSTEM` + `StopReason.LOW_STORAGE` to the session manifest. A recovery card appears on Library next launch.

But the user is left on Record-Idle without explanation. The HUD reverts from Recording to Idle silently. `WarningCenterContract.md` §4.2 row C2.3 calls for a "read-only echo on `record`" — that echo was not yet wired.

## Decision

Add a new `WarningId.STORAGE_FULL_AUTOSTOPPED` (ADVISORY tier, ordinal 12 — between `STORAGE_LOW_MID_REC` and `THERMAL_SEVERE`). New leaf signal `SessionAutoStopEchoSignal` exposes the most-recent terminal session as `TerminalEcho?`, filtered against a persistent dismissed-id set on `RovaSettings.dismissedAutoStopEchoIds`. `WarningPrecedence.resolve` adds a branch that returns the new id when `stopReason == LOW_STORAGE`. `WarningCenter` Idle-branch special-cases the id to render `WarningTopBannerV3` (the same chrome used by the active-HUD top banner) with the "Free up space" primary CTA and an overflow menu offering "Don't show again" (per-session-id persistent dismiss) + "Review session" (host-wired navigation to History).

## Survives / does NOT survive

Echo SURVIVES:
- cold start
- system reclaim (`onTrimMemory`)
- force-stop via Settings → Apps
- device reboot
- in-place APK update

Echo does NOT survive:
- explicit "Don't show again" tap (persistent per-session-id dismissal in `rova_runtime_prefs.dismissed_autostop_echo_ids`)
- uninstall + reinstall (backup-excluded; same policy as `mode` + `snoozedWarningIds`)
- a more-recent terminal session that is NOT `LOW_STORAGE` (the signal surfaces only the most-recent terminal; precedence filters to LOW_STORAGE)

## Implementation

- **Storage:** `RovaSettings.dismissedAutoStopEchoIds: Set<String>` on `rova_runtime_prefs` under key `dismissed_autostop_echo_ids`. Values are session ids.
- **Signal:** `SessionAutoStopEchoSignal(terminalEchoSource: () -> TerminalEcho?, initialDismissedIds: Set<String>)` exposes `state: StateFlow<TerminalEcho?>`. `markDismissed(id)` flips the flow when the current echo matches. Reason-agnostic — `WarningPrecedence` filters to `LOW_STORAGE`.
- **Source reader:** `SessionStore.latestTerminalSession(): TerminalEcho?` (extension function, sync) walks manifests, returns most-recent terminal.
- **Precedence:** `WarningPrecedence.resolve` gains 11th param `autoStopEcho: TerminalEcho?` with trailing-default `= null`. Branch at slot 12 returns the new id when `stopReason == LOW_STORAGE`.
- **VM:** `WarningCenterViewModel` ctor gains 11th source param `autoStopEcho` + trailing optional `onAutoStopDismissed: ((String) -> Unit)?`. New `dismissAutoStopEcho(sessionId)` mutator routes to the callback. `aggregate()` combinator restructured: 4 non-Bool flows (`thermal`, `power`, `camera`, `autoStopEcho`) packed into `NonBools4`; outer combine stays at 3 typed args.
- **Surface:** `WarningTopBannerV3` extended with optional `onOverflow: ((ActionTarget) -> Unit)?` slot and `content.overflow: List<WarningAction>` field. `WarningCenter` Idle-branch special-cases the new id to render this composable with `RovaWarnings.advisory` severity color (NOT `escalating` — echo is informational).
- **Refresh triggers:** `RecordScreen` `ON_RESUME` lifecycle event calls `app.autoStopEchoSignal.refresh()`. Future slices can add a service terminal-transition observer for same-process auto-stops.
- **Factory:** `buildWarningCenterViewModel(app)` threads `autoStopEcho = app.autoStopEchoSignal.state` + `onAutoStopDismissed = { id -> settings.dismissedAutoStopEchoIds += id; app.autoStopEchoSignal.markDismissed(id) }`.

## Consequences

- **Behavioural:** Users now see why the session stopped, on the same screen they're looking at. Removes a real source of confusion (silent revert to Idle).
- **No new schema:** persisted format is `Set<String>` keyed by session id. No new manifest fields.
- **`WarningId` ordinal shift:** rows 12-17 shift +1. `WarningPrecedence.resolve` uses no ordinal arithmetic. `WarningId.gatesStart` is by-name. `snoozedWarningIds` (ADR-0014) is by-name. No breakage.
- **Set growth:** `dismissedAutoStopEchoIds` grows by 1 per auto-stop event. Acceptable for v1.0 (rare event). Garbage collection deferred.

## Rejected alternatives

- **Bundle with SD_CARD_EJECTED** — rejected; `WarningCenterContract.md` §4.6 already demoted SD_CARD as out-of-scope for v1.0. Honor the contract.
- **At-stop flash on active HUD** — rejected; HUD reverts to Idle quickly (~1s) and a flash would barely register. The Idle echo gives the user time to read and act.
- **Auto-clear on next successful recording** — rejected for v1.0; explicit dismiss is clearer UX. A future slice can add auto-clear if telemetry shows users ignoring the banner.
- **Extending `RecordRecoveryChip`** — rejected; the chip is generic "N interrupted sessions" + Review CTA. Specializing it for LOW_STORAGE would bypass `WarningCenter` and split the routing model.
- **Per-id confirmation dialog on dismiss** — rejected; low-stakes single-tap, no destructive consequence.

## References

- Spec: `docs/superpowers/specs/2026-05-24-phase-4-slice2-storage-full-autostopped-design.md`
- Plan: `docs/superpowers/plans/2026-05-24-phase-4-slice2-storage-full-autostopped.md`
- ADR-0006 (terminal manifest reasons)
- ADR-0007 (warning sheet model)
- ADR-0013 (warning re-skin v3 chrome canon)
- ADR-0014 (snooze persistence)
- `docs/WarningCenterContract.md` §4.2 row C2.3

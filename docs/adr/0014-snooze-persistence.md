# ADR-0014 — Snooze persistence (Phase 4.1c)

**Status:** Accepted (2026-05-24)
**Phase:** 4.1c
**Supersedes:** WarningCenterContract.md §5 "Snooze contract" paragraph (the
"not persisted across process restarts; the user gets the banner back on cold
start, by design" clause).
**Related ADRs:** ADR-0007 (warning sheet model), ADR-0013 (warning re-skin
v3 chrome canon).

## Context

`WarningCenterViewModel._snoozedForever: MutableStateFlow<Set<WarningId>>`
shipped in Phase 4 v3 as in-memory only. WarningCenterContract.md §5 was
explicit about this being the design intent at the time — silencing a
battery banner that survives a phone reboot would be wrong. After ship, the
"Don't show again" affordance proved confusing for advisory-tier warnings
that the user genuinely wanted to silence durably (NOTIFICATIONS_DENIED,
BATTERY_OPTIMIZATION_ON): every cold start re-presented the sheet, so the
button read as a no-op.

## Decision

Persist `snoozedForever` for the lifetime of an install. Snoozes survive:

- normal cold start
- system reclaim (`onTrimMemory`)
- force-stop via Settings → Apps
- device reboot
- in-place APK update

Snoozes do NOT survive:

- uninstall + reinstall (the `rova_runtime_prefs.xml` file is
  `<exclude>`d from both `cloud-backup` and `device-transfer` in
  `data_extraction_rules.xml` + `backup_rules.xml`)
- explicit reset via the new Settings → "Reset snoozed warnings" row

## Implementation

- Storage: `RovaSettings.snoozedWarningIds: Set<String>` on the existing
  backup-excluded `rova_runtime_prefs` file under key `snoozed_warning_ids`.
  Values are `WarningId.name` strings.
- VM seam: `WarningCenterViewModel` ctor gains `initialSnoozedIds: Set<WarningId>`
  + `onSnoozeChanged: ((Set<WarningId>) -> Unit)?` with defaults that
  preserve in-memory-only behaviour for legacy callers + tests.
- Factory: `buildWarningCenterViewModel(app)` in `WarningCenter.kt` reads
  the persisted set + supplies the round-trip callback. Stale `WarningId.name`
  strings from renamed/removed ids self-heal via `runCatching { valueOf(it) }.getOrNull()`
  on the next write.
- Reset UX: `SettingsSheet` gains a "Reset snoozed warnings" row, gated
  on a non-empty set. Subtitle shows the count.

## Consequences

- **Behavioural:** "Don't show again" now means "until reset or reinstall".
  The Contract amendment in §5 reflects this.
- **No new schema:** the persisted format is a `Set<String>` keyed by
  `WarningId.name`. Future enum renames must keep the old `name` alive
  (or accept a one-time silent self-heal on the next mutator call).
- **No TTL / auto-expire:** considered + rejected (spec §10). Adding
  per-id timestamps would require a `Map<WarningId, Long>` and a clock
  dependency for tests. The explicit reset row is judged sufficient.

## Rejected alternatives

- **Backup-included variant** — would let snoozes carry through
  device-to-device transfer. Rejected: matches the `mode` policy already
  set in ADR-0009 (runtime state stays local; user-config keys back up).
- **Per-id snooze granularity in Settings** — would be a sub-screen with
  one toggle per snoozed id. Out of scope; the reset row clears all in
  one tap, which is the recovery-path UX. Granular unsnooze comes back
  as a future slice if there is demand.
- **Per-id confirmation dialog on reset** — low-stakes single-tap action
  with no destructive consequence (worst case: the user sees a sheet they
  already dismissed once). Consistent with other Settings rows.

## References

- Spec: `docs/superpowers/specs/2026-05-24-phase-4-1c-snooze-persistence-design.md`
- Plan: `docs/superpowers/plans/2026-05-24-phase-4-1c-snooze-persistence.md`
- ADR-0007 (warning sheet model)
- ADR-0013 (warning re-skin v3 chrome canon)
- `app/src/main/res/xml/backup_rules.xml`, `app/src/main/res/xml/data_extraction_rules.xml`

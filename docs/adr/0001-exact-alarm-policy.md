# ADR 0001 — Exact-Alarm Policy

- **Status:** Accepted
- **Date:** 2026-04-25
- **Phase:** 0 (pre-implementation prerequisite)
- **Supersedes:** —
- **Superseded by:** —
- **Related:** ROADMAP_v6.md §3 (Scheduling Model), §1.2 (Scheduler), §1.3 (Stop Path)

---

## Context

Rova schedules inter-segment ticks via `AlarmManager` while the foreground service holds the camera. The platform offers three exact-alarm options, each with non-trivial cost:

| Option | Permission | User-revocable | Play policy | Behavior on revocation |
|---|---|---|---|---|
| Exact, opt-in | `SCHEDULE_EXACT_ALARM` (API 31+) | Yes — Settings → Alarms & Reminders, and OS may revoke | Standard review | Falls back to inexact silently unless app handles `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` |
| Exact, allow-listed | `USE_EXACT_ALARM` (API 33+) | No (granted at install if approved) | Restricted policy — calendar/alarm-clock/timer use cases only | N/A (cannot be revoked) |
| Inexact-only | none beyond standard alarm APIs | N/A | None | Always available |

Two facts dominate the decision:

1. A periodic background video recorder is **not** in the `USE_EXACT_ALARM` policy whitelist (calendars, alarm clocks, timers). Submitting with that permission is a Play-review risk and likely rejection.
2. `SCHEDULE_EXACT_ALARM` can be revoked at any time — by the user in Settings or by the OS during routine app standby buckets. Code that assumes exact alarms always fire on time will silently miss windows in production.

## Decision

Rova ships with **`SCHEDULE_EXACT_ALARM`** (opt-in exact path) **plus an always-supported inexact fallback**. Tick scheduling chooses per call:

- If `AlarmManager.canScheduleExactAlarms()` returns `true` (and `SDK_INT >= 31`):
  - Use `setExactAndAllowWhileIdle(RTC_WAKEUP, triggerAtMillis, pendingIntent)`.
- Otherwise (permission denied, OS-revoked, or `SDK_INT < 31` is moot — `SCHEDULE_EXACT_ALARM` is API 31+, so the gate is `SDK_INT >= 31 && canScheduleExactAlarms()`):
  - Use `setAndAllowWhileIdle(RTC_WAKEUP, triggerAtMillis, pendingIntent)`.

The session continues to completion in either branch. The UI surfaces the active mode via a banner ("Inexact mode — drift up to N minutes" — copy in Phase 2 §2.x). Drift bounds are recorded in ADR 0004 (drift policy).

`USE_EXACT_ALARM` is **not** declared in the manifest. Inexact-only mode (no `SCHEDULE_EXACT_ALARM` request at all) is rejected because it would always fall to the inexact band on Doze-active devices, which violates the §3.4 drift target for short and medium sessions.

## Consequences

### Positive

- Standard Play review path. No restricted-permission justification required.
- Architecture forces every alarm-scheduling call site through the runtime capability check, so the inexact code path is always live in production and tested by every install where the permission is unset or revoked.
- The inexact branch makes long-session drift behavior an explicit product decision (ADR 0004), not a silent failure mode.

### Negative

- A user who denies `SCHEDULE_EXACT_ALARM` will see drift up to the inexact-band ceiling on Doze-active devices. The banner mitigates surprise; the drift policy bounds it.
- The runtime registers `BroadcastReceiver` for `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` so a mid-session revocation is observed and the next tick is scheduled inexactly. Without that receiver, an in-flight session would continue using a stale `setExactAndAllowWhileIdle` call that the OS now treats as inexact anyway, but the UI banner would lag — which we forbid.

### Neutral

- The lint rule (CI Rules Summary, ROADMAP_v6.md §"Lint / CI Rules Summary") that forbids `PendingIntent.getService` in alarm-scheduling code (v3) is unchanged.

## Manifest Implications

`AndroidManifest.xml` declares:

```xml
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM"
                 android:minSdkVersion="31" />
```

It does **not** declare `USE_EXACT_ALARM`. Any future change to add it requires a new ADR superseding this one and a Play policy review.

## Acceptance Criteria

- ADR file present at `docs/adr/0001-exact-alarm-policy.md`.
- `AndroidManifest.xml` declares `SCHEDULE_EXACT_ALARM` (and not `USE_EXACT_ALARM`).
- The scheduler implementation in §1.2 selects between `setExactAndAllowWhileIdle` and `setAndAllowWhileIdle` per the runtime capability check above.
- A UI banner copy draft exists for the inexact-mode case (delivered in Phase 2).

## References

- Android docs: `AlarmManager` exact-alarm guidance.
- Play policy: `USE_EXACT_ALARM` restricted-permission criteria.
- ROADMAP_v6.md §3 (Scheduling Model — unchanged from v5).
- ROADMAP_v6.md §1.2 (Scheduler — unchanged from v5).
- ADR 0004 (drift policy) — defines the drift bands referenced by the inexact-mode banner copy.

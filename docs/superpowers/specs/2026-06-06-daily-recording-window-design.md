# Daily Recording Window — Design Spec

**Date:** 2026-06-06
**Feature:** Time-based scheduling — auto-start/auto-stop a periodic recording session on a daily time window.
**Status:** Approved design → implementation plan next.

---

## 1. Goal

Let a user say "record every weekday between 09:00 and 17:00" and have Rova handle the timing. Off by default. Single toggle, progressive disclosure. One daily window only — **not** cron, **not** multi-window.

## 2. Platform reality (the constraint that shapes everything)

Rova's recorder is a **`camera`-type foreground service**. On Android 14+ (target/compileSdk 37), starting a `camera`/`microphone`/`location` ("while-in-use") FGS **from the background is forbidden**, and the `SCHEDULE_EXACT_ALARM` / `BOOT_COMPLETED` exemptions that rescue ordinary FGS types are **explicitly excluded** for these types. Android 15+ blocks them even when the app otherwise qualifies for a background-start exemption. The trend is monotonically stricter.

**Consequence:** silent, unattended cold-start of the camera is impossible for a Play consumer app. The realistic feature is:

- **Auto-START = one-tap-confirmed.** An exact alarm fires at the window open and posts a high-priority notification; one tap brings the Activity foreground, which legally starts the camera FGS.
- **Auto-STOP = fully silent.** Stopping a running FGS from the background is permitted. An exact alarm at window close stops the session with no user interaction.

This is the platform ceiling, documented here so the design is not mistaken for a shortcut. References: Android FGS background-start restrictions, FGS service types (Android 14), FGS changes (Android 15/16), exact-alarm permission (Android 14), Doze/App-Standby, Play exact-alarm + full-screen-intent policies.

## 3. Architecture

House conventions: **pure-helper extraction** (framework-touching code gets a pure-Kotlin sibling testable under `isReturnDefaultValues = true`), **thin seam** for system-service access, **exact-alarms-only** scheduling discipline, **ADR → `check*` task → preBuild** for new invariants.

### 3.1 Components

| Unit | Type | Responsibility |
|------|------|----------------|
| `ScheduleSettings` (in `RovaSettings`) | persisted data | `enabled: Boolean` (default false), `startMinuteOfDay: Int`, `stopMinuteOfDay: Int`, `weekdayMask: Int` (7-bit, bit per weekday; 0 ⇒ every day). |
| **`ScheduleArmer`** | **pure Kotlin** | Given `nowMillis`, `zoneId`, and `ScheduleSettings`, compute the next `startAtMillis` and next `stopAtMillis`. Handles overnight windows (stop ≤ start ⇒ stop is next day), weekday skips, DST transitions, and "currently inside a window" (arm the *next* occurrence; do not retroactively fire). Returns a small immutable result type. The unit-testable heart of the feature. |
| `ScheduleAlarmScheduler` | thin Android seam | Arms the start and stop alarms. Reuses the existing `AlarmScheduler` discipline: `canScheduleExactAlarms()` gate on API 31+, `setExactAndAllowWhileIdle(RTC_WAKEUP, …)`, `try/catch SecurityException` TOCTOU downgrade to inexact `setWindow`. `getService`-free (broadcast targets only). Cancel/re-arm is deterministic across process death (same PendingIntent shape, identity in `Intent.data`). |
| `ScheduleStartReceiver` | `BroadcastReceiver` | On the start alarm: (a) posts a high-importance notification deep-linking `MainActivity` with an `auto-arm` extra; (b) immediately re-arms the next eligible day's start alarm. **Never starts the FGS.** |
| `MainActivity` auto-arm path | Activity | When launched from the schedule notification AND the window is still open, start the camera FGS through the **existing** Start flow (reusing the existing Start gates `CAMERA_PERMISSION_DENIED` / `STORAGE_INSUFFICIENT`). This is the single legal camera-start site. If the window already closed by the time of the tap, do nothing (or surface a brief "window already ended" message). |
| `ScheduleStopReceiver` | `BroadcastReceiver` | On the stop alarm: send STOP to the running service through the **existing** `RovaStopReceiver`/controller path, tagged with a **new, separate stop reason** (`SCHEDULE_WINDOW`) distinct from user-stop and loop-exhaust. Silent. Re-arms the next day's stop alarm. |
| `ScheduleBootReceiver` | `BroadcastReceiver` | `BOOT_COMPLETED` + `LOCKED_BOOT_COMPLETED` + `MY_PACKAGE_REPLACED` → **only re-arms** the start/stop alarms via `ScheduleAlarmScheduler`. **Never starts the FGS** (forbidden on 15+; alarm re-arm from boot is allowed). |
| segment-loop self-heal | existing service loop | At each segment boundary, if `now ≥ windowEnd` for a schedule-started session, stop. Backstops a missed/Doze-throttled stop alarm. Must be **first-terminal-writer-safe** — it races user-stop, storage-stop, thermal-stop, and merge-failure, and must not double-write a terminal state. |

### 3.2 Data flow

```
Settings toggle ON
  → request SCHEDULE_EXACT_ALARM (if API 31+ and not granted)
  → ScheduleArmer.next(now, settings) → (startAt, stopAt)
  → ScheduleAlarmScheduler.arm(startAt START) + arm(stopAt STOP)

[window open] start alarm → ScheduleStartReceiver
  → post "tap to record" notification + re-arm next start
  → user taps → MainActivity(auto-arm) → existing Start flow → camera FGS runs segment loop
      (manifest persists windowStart/windowEnd + startedBySchedule)

[window close] stop alarm → ScheduleStopReceiver
  → STOP (reason=SCHEDULE_WINDOW) via RovaStopReceiver/controller → silent stop + merge
  → re-arm next stop
  (self-heal: if stop alarm missed, segment loop stops at now ≥ windowEnd)

[reboot] BOOT_COMPLETED → ScheduleBootReceiver → re-arm start+stop (no FGS start)
```

## 4. Permissions & error handling

- **`SCHEDULE_EXACT_ALARM`** (NOT `USE_EXACT_ALARM` — Play restricts that to alarm-clock/calendar apps; a recorder would be rejected). Request via `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` on enable; gate every exact call on `canScheduleExactAlarms()` (API 31+); pre-31 needs no runtime request. If denied: degrade to inexact `setWindow` and tell the user timing may drift in Doze. Keep the feature usable, not silently broken.
- **`POST_NOTIFICATIONS` (API 33+) is load-bearing.** If notifications are denied while scheduling is on, the one-tap start prompt is invisible → the feature silently fails. Surface this through WarningCenter as a **new `WarningId`** (flat, non-gating banner unless we decide otherwise) that appears only when `scheduleEnabled && notificationsDenied`. Onboarding/settings copy states the one-tap-at-window-open requirement honestly.
- **Full-screen intent** is Play-policy-restricted and not auto-granted on 14+. Design around a **high-importance heads-up notification + content intent**; treat true full-screen-intent as best-effort only.

## 5. Recovery awareness

- Persist into `SessionManifest` (schema bump): `windowStartMillis`, `windowEndMillis`, `startedBySchedule: Boolean`, and a `windowExpired` latch.
- The cold-launch `RecoveryScanner.classifyAll` classifies a schedule-started session **from manifest + on-disk evidence** — it must **not** blindly write a `SCHEDULE_WINDOW_ENDED` terminal state, because that would mask a crash/kill. A session whose window expired but lacks a clean terminal write is still classified by the normal killed-vs-completed logic.
- No new deletion path in the scan (`checkRecoveryNoDeletion`). Terminal-write atomicity invariants preserved (`checkCompletedWriteOnlyFromPerformMerge`, `checkAtomicTerminalWriteForbiddenPair`). `COMPLETED` is still only written by `performMerge`.

## 6. New static gate

`checkScheduleReceiverNoFgsStart` — the schedule start receiver and boot receiver must never contain `PendingIntent.getService` / `startForegroundService` / `startService`. Enforces the "alarms re-arm + notify only; never start the camera FGS from background" invariant. ADR clause → `check*` task → wired into `preBuild`, following the same convention as the existing 30 gates. (Consistent with existing `checkSchedulerNoGetService` / `checkStopNoGetService`.)

## 7. ADR

**ADR-0027 — Time-based daily recording window.** Records: the platform constraint (camera-FGS background-start blocked on 14+, getting stricter), the resulting one-tap-confirmed-start / silent-stop invariant, the exact-alarm-only + `SCHEDULE_EXACT_ALARM` decision (and why not `USE_EXACT_ALARM`), the boot-re-arm-without-FGS-start rule, the separate `SCHEDULE_WINDOW` stop reason, and recovery's "classify from evidence, don't blindly write terminal" rule.

## 8. Testing (JVM-only policy)

- `ScheduleArmerTest`: same-day window; overnight window (stop next day); weekday mask skips to next eligible day; no-eligible-day-this-week wraps to next week; "now inside window" arms next occurrence; DST spring-forward / fall-back boundary; midnight edge; every-day (mask 0).
- `SessionManifest` round-trip test for the new schedule fields (real `org.json` on `testImplementation`).
- WarningCenter precedence test for the new schedule-notifications `WarningId`.
- en + es strings added (`checkNoHardcodedUiStrings`, ADR-0022).

## 9. Settings surface (minimal now, polish deferred)

A new collapsible "Scheduling" section in `SettingsScreen`: master toggle + start/stop time pickers + weekday chips. Functional and accessible (WCAG 2.2 AA per ADR-0020), but visual polish is deferred to the planned UI sweep. If scheduling is enabled while the device is *already inside* a window, arm the **next** occurrence (do not prompt immediately) — least surprising.

## 10. Out of scope (YAGNI)

- Multiple windows per day, cron expressions, per-day distinct times.
- Zero-touch silent start (platform-impossible).
- Sunrise/sunset or location-based triggers.
- Scheduling for P+L / DualShot specifics beyond what the normal Start flow already supports.

## 11. Non-overlap with parallel work

This feature touches `service/scheduler/`, new receivers under `service/`, `data/` (RovaSettings + SessionManifest), `ui/screens/SettingsScreen`, `ui/warnings/`, `MainActivity`, strings, ADR + a new `check*` task. The parallel **Phase-0 optimization slice** touches `app/build.gradle.kts`, `gradle.properties`, `utils/RovaLog.kt`, `service/dualrecord/internal/EglRouter.kt` — disjoint except for `build.gradle.kts` (the new `check*` task registration there is additive and unlikely to conflict; sequence the gradle edits to avoid a race).

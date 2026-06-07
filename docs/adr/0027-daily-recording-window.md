# ADR-0027: Daily recording window — one-tap-confirmed start, silent stop

Status: Accepted
Date: 2026-06-07

## Context

Users want Rova to record automatically on a daily time window (e.g. weekdays 09:00–17:00). Rova's recorder is a `camera`-type foreground service. On Android 14+ a camera/microphone/location ("while-in-use") FGS cannot be started from the background, and the `SCHEDULE_EXACT_ALARM` / `BOOT_COMPLETED` exemptions that rescue ordinary FGS types are explicitly excluded for these types; Android 15+ blocks the background start even when an exemption is met. The restriction tightens each release. Silent, unattended cold-start of the camera is therefore impossible for a Play consumer app.

## Decision

1. **Auto-start is one-tap-confirmed.** An exact alarm at window-open posts a high-importance notification; the user's tap brings `MainActivity` to the foreground, and the Activity starts the camera FGS via the normal Start flow (`MainActivity.ACTION_SCHEDULE_AUTO_ARM` → `RovaApp.startScheduledRecording`). This is the single legal camera-start site.
2. **Auto-stop is silent.** An exact alarm at window-close (`ScheduleStopReceiver`) forwards a cooperative stop to the live in-process controller (`RovaController.requestStop(StopReason.SCHEDULE_WINDOW)`), reusing the existing stop path. No `getService`, no new stop pathway. If the process is dead there is nothing recording to stop; cold-launch recovery classifies from evidence.
3. **Exact alarms via `SCHEDULE_EXACT_ALARM`**, gated on `canScheduleExactAlarms()` (API 31+), degrading to inexact `setWindow` if denied. NOT `USE_EXACT_ALARM` — Play restricts that to alarm-clock/calendar apps.
4. **Reboot re-arms only.** `ScheduleBootReceiver` (`BOOT_COMPLETED` / `LOCKED_BOOT_COMPLETED` / `MY_PACKAGE_REPLACED`) re-arms the alarms and never starts the FGS. Requires `RECEIVE_BOOT_COMPLETED`.
5. **Defensive self-heal.** The segment loop stops when `now >= scheduleWindowEndMillis`, first-terminal-writer-safe (`currentStopReason` is first-writer-wins), backstopping a Doze-throttled stop alarm. Like every user-stop, the captured segments are still merged; the session is classified `USER_STOPPED` + `SCHEDULE_WINDOW`.
6. **Recovery classifies from evidence.** The manifest persists `startedBySchedule`, `scheduleWindowStartMillis`, and `scheduleWindowEndMillis` (schema 9) as diagnostic provenance. The cold-launch scanner classifies a killed scheduled session by the normal killed-vs-completed logic; it does NOT read these fields or blindly write a terminal state for an expired window (that would mask a crash). `scheduleWindowExpired` is a reserved latch field (default false) for future use; the load-bearing signal that a window-driven stop occurred is `StopReason.SCHEDULE_WINDOW` on the terminal record.
7. **One daily window only** — no cron, no multiple windows (YAGNI).
8. **No new `WarningId`** — the existing `EXACT_ALARM_DENIED` and `NOTIFICATIONS_DENIED` cover both risks; the Settings toggle requests the permissions and warns inline when denied.

## Enforcement

- `checkScheduleReceiverNoFgsStart` (new preBuild gate, 31st `check*`): forbids `getService` / `startForegroundService` / `startService` anywhere under `service/schedule/`.
- Existing gates preserved: `checkSchedulerNoGetService`, `checkStopNoGetService`, `checkUserStoppedBeforeMerge`, `checkCompletedWriteOnlyFromPerformMerge`, `checkAtomicTerminalWriteForbiddenPair`, `checkRecoveryNoDeletion`.
- `ScheduleArmer` is pure-Kotlin (JVM-tested: overnight, weekday-mask, DST, inside-window, exact-instant, current-window-end). The Android edges (`System.currentTimeMillis`, `TimeZone.getDefault`, `AlarmManager`) live in `ScheduleController` / `ScheduleAlarmScheduler`.

## Consequences

- The product is "tap once at the scheduled moment, walk away" — not zero-touch. This is the platform ceiling, set honestly in settings copy, not a design shortcut.
- Future Android tightening only touches the notification seam; the trigger (alarm) and start (Activity→FGS) layers stay decoupled.
- If the user denies notifications, the one-tap prompt is invisible — surfaced via the global `NOTIFICATIONS_DENIED` warning plus the Settings inline note + an on-enable permission request.

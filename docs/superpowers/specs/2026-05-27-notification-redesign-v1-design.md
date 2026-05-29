# M5 — Foreground Notification Redesign (v1.0 polish)

> **Date:** 2026-05-27
> **Mockup:** `mockups/new_uiux/09-notification-export.html` — Foreground Notification · States (phones 1–4)
> **Roadmap:** `NEW_UI_BACKEND_REPLAN.md` §5 Phase 3.1, §6 row "Notification — clip active / gap / merging / complete"
> **Replaces:** none — extends Phase 3.1 typed-copy work already in `service/notification/NotificationCopy.kt`
> **References:** Android Accessibility Developer Guide (developer.android.com/guide/topics/ui/accessibility), Material Design 3 Accessibility (m3.material.io/foundations/designing/accessibility), WCAG 2.1 / 2.2 AA

---

## 1. Context

`NotificationCopy.kt` (PR #51, Milestone 2 reliability bundle) shipped a typed `NotificationState` sealed interface and a pure `(title, body)` mapper for four states: `ClipRecording`, `GapWaiting`, `Merging`, `MergeComplete`. The service consumes the mapper through `updateNotification(state: NotificationState)`, which calls the existing `createNotification(title, contentText, currentSessionId)` builder.

What ships today against what the mockup specifies:

| Surface | Shipped | Mockup target |
|---|---|---|
| Channel topology | Single `CHANNEL_ID` (`IMPORTANCE_LOW`), all four states reuse it; MergeComplete inherits `setOngoing(true)` | Two channels — recording (low, ongoing, silent) + complete (default, dismissible, optional sound) |
| Small icon | `android.R.drawable.ic_menu_camera` (system stock) | Per-state branded vectors (recording dot, pause/wait, merge, complete check) |
| Accent | none (`setColor` not called) | `#5b7fff` recording/merging; `#34d399` complete |
| Body copy | static strings — `"Recording in progress"`, `"Processing — please wait"`, etc.; documented in `NotificationCopy.kt` KDoc as deferred | dynamic — `"0:18 remaining in this clip"`, `"Next clip starts in 4:42"`, `"About 15 seconds remaining"`, `"6 clips · 5:00 total · saved to Library"` |
| Progress bar | only on the separate `NOTIFICATION_ID_RECOVERY_MERGE` notif | determinate bar on Merging (done/total); determinate countdown on GapWaiting |
| Actions | single `STOP` action wired regardless of state | per-state — Clip: Stop+Open · Gap: StopEarly+Open · Merging: **no actions** · Complete: ViewInLibrary+Share |
| Dismissibility | always `setOngoing(true)` | Complete is `setOngoing(false) + setAutoCancel(true)` |

Goal: bring the FGS notification to v1.0 polish — accurate to the mockup, accessible per WCAG 2.2 AA and the Android Accessibility guide, and stable against the existing service contract.

## 2. Scope

### In scope (M5)

- Per-state notification skin: small icon, accent color, body copy with live numbers, progress bar, per-state action set, per-state dismissibility.
- Channel split: `recording_session` (LOW, ongoing) + `recording_complete` (DEFAULT, dismissible).
- New pure helpers: `NotificationChannelConfig.kt`, `NotificationActionSpec.kt`, `NotificationIconRes.kt`.
- Extended `NotificationState` data classes with optional numeric fields (`etaSecondsRemaining`, `nextStartsInSeconds`, `mergeProgressPercent`, `totalDurationSeconds`).
- Wire ETA / elapsed / duration from existing service state.
- Four new vector drawables (record / wait / merge / complete) following the existing app vector style.
- Deep link for "View in Library" → MainActivity with `EXTRA_SESSION_ID`, History tab selected (scroll-to-row deferred).
- "Share" action on MergeComplete → standard `ACTION_SEND` chooser for the merged MP4 URI via existing `safeShareUri` helper.
- A11y: `Action.Builder` with `setContentDescription`, contentDescription on small-icon equivalents where used in expanded layout, WCAG contrast verification.
- Unit-test extension for the four pure helpers.

### Out of scope (separate milestones)

- Export options sheet (mockup phones 5–7) — Phase 5 of replan, deferred per replan recommendation.
- `RovaSettings.autoExportEnabled` / `exportFolderName` consumers.
- Localization / RTL of notification copy — per memory `project_current_state.md`, full i18n parked.
- History "scroll to row" on deep-link arrival — defer; v1.0 ships tab selection only.
- Recovery-merge notification (`NOTIFICATION_ID_RECOVERY_MERGE`) — has its own builder, ships unchanged; redesign tracked separately.
- Rich `BigPictureStyle` / `BigTextStyle` expansion — collapsed style only per mockup.
- Notification dot / shortcut badge.

### Explicit NO-GO

- 5th notification state (replan §11.5 forbids adding a 5th state; sealed interface stays at 4 + free-form legacy `updateNotification(String)`).
- Robolectric tests for notification building (CLAUDE.md test policy: JVM unit only).
- Manifest `SessionManifest` schema bump (replan §7 NO-GO #8).
- Renaming `NotificationState` or any of its four variants.
- Migrating away from `NotificationCompat.Builder` (e.g., to `Notification.Builder` directly) — `NotificationCompat` is the AndroidX guarantee surface.

## 3. Architecture deltas

The service stays the only owner of `NotificationCompat.Builder` and the `NotificationManager.notify` call. All pre-builder decisions (channel id, color, icon, action specs, ongoing flag) become pure data computed by helpers in `service/notification/`. The service consumes one wide `NotificationSpec` per state, then translates it to the platform builder.

```
NotificationState (extended)
    │
    ├── toCopy()                  ──→ NotificationCopy(title, body)
    ├── toChannelId()             ──→ String                          [new — NotificationChannelConfig.kt]
    ├── toIconRes()               ──→ @DrawableRes Int                [new — NotificationIconRes.kt]
    ├── toAccent()                ──→ @ColorInt Int                   [new — same file as channel config]
    ├── toActionSpecs(...)        ──→ List<NotificationActionSpec>    [new — NotificationActionSpec.kt]
    ├── toProgress()              ──→ Progress? (max, current, indet) [new — same file as channel config]
    └── isDismissible()           ──→ Boolean                         [new — same file as channel config]
                                   │
                                   ▼
                        RovaRecordingService.createNotification(state, sessionId)
                                   │
                                   ▼
                        NotificationCompat.Builder ─→ notify(NOTIFICATION_ID, …)
```

Free-form `updateNotification(String)` legacy stays for INIT / error transient copy. No change there.

## 4. Channel topology

Two channels, both gated `Build.VERSION_CODES.O` (minSdk=24 path: pre-O drops to the legacy `NotificationManager.notify` without channel binding, which is the existing behavior — no change).

| Channel id | Importance | Sound | Vibration | States | Notes |
|---|---|---|---|---|---|
| `rova_recording_session` | `IMPORTANCE_LOW` | none | none | ClipRecording, GapWaiting, Merging | Ongoing, silent FGS spine. Matches today's `IMPORTANCE_LOW` behavior. |
| `rova_recording_complete` | `IMPORTANCE_DEFAULT` | system default (user-overridable in Settings → Apps → Rova → Notifications) | system default | MergeComplete | User can opt sound off per-channel without silencing the recording channel. |

Channel display names + descriptions go to `res/values/strings.xml`:

- `notification_channel_session_name` = "Recording session"
- `notification_channel_session_desc` = "Ongoing notification while a recording session is active or merging."
- `notification_channel_complete_name` = "Recording complete"
- `notification_channel_complete_desc` = "One-shot notification when a recorded session is ready in the Library."

Migration: the old `CHANNEL_ID = "rova_background_recording"` becomes unused. Per Android NotificationChannel semantics, deleting a channel is one-way and resets user customizations. We **do not delete** the old channel — it stays registered (and unused) so existing user importance/sound overrides are not nuked silently. A follow-on cleanup slice can delete it after a few weeks of production.

Old `CHANNEL_ID` constant stays in source as `@Deprecated("Replaced by SESSION_CHANNEL_ID and COMPLETE_CHANNEL_ID")` for one release.

## 5. State → notification specification

### 5.1 `ClipRecording(current: Int, total: Int?, etaSecondsRemaining: Int?)`

| Field | Value |
|---|---|
| Channel | `rova_recording_session` |
| Small icon | `ic_notif_recording` — solid red filled circle (16dp) |
| Accent (`setColor`) | `#5b7fff` |
| Colorized | `false` (LOW importance can't colorize on most OEMs) |
| Title | `"Recording · Clip $current of $total"` (or `"Recording · Clip $current"` if total null) |
| Body | `"$etaLabel remaining in this clip"` where `etaLabel = mm:ss` of `etaSecondsRemaining`; falls back to `"Recording in progress"` if null |
| Progress | none |
| Actions | `Stop` (existing `STOP` PendingIntent), `Open` (existing `openPendingIntent`) |
| Ongoing | `true` |
| AutoCancel | `false` |
| Sub-text | `"$current/$total"` for at-a-glance read on collapsed |

### 5.2 `GapWaiting(nextNumber: Int, nextInLabel: String, total: Int?, nextStartsInSeconds: Int?, gapTotalSeconds: Int?)`

| Field | Value |
|---|---|
| Channel | `rova_recording_session` |
| Small icon | `ic_notif_waiting` — pause/clock vector (16dp) |
| Accent | `#5b7fff` |
| Title | `"Waiting · Clip $nextNumber of $total next"` (or `"Waiting · Clip $nextNumber next"` if total null) |
| Body | `"Next clip starts in $nextInLabel"` (mockup-exact) |
| Progress | determinate countdown: `max = gapTotalSeconds`, `current = gapTotalSeconds - nextStartsInSeconds`. If either is null → no bar (don't fake indeterminate). |
| Actions | `Stop Early` (`STOP` intent, relabeled), `Open` |
| Ongoing | `true` |
| AutoCancel | `false` |

### 5.3 `Merging(done: Int, total: Int, mergeProgressPercent: Int?)`

| Field | Value |
|---|---|
| Channel | `rova_recording_session` |
| Small icon | `ic_notif_merging` — file-with-arrows vector (16dp) |
| Accent | `#5b7fff` |
| Title | `"Merging clips · $done of $total"` |
| Body | static `"Processing — please wait"` (ETA-from-merger deferred; mockup shows `"About 15 seconds remaining"` but the ExportPipeline does not expose an ETA today — wiring is a follow-on). |
| Progress | determinate: `max = 100`, `current = mergeProgressPercent ?? 0`. If null, indeterminate. |
| Actions | **none** — merging is not cancellable. Don't render the disabled "Cannot cancel" pill from the mockup; native notifications can't render disabled buttons, and an empty action row is the correct platform mapping. |
| Ongoing | `true` |
| AutoCancel | `false` |

### 5.4 `MergeComplete(clipCount: Int, totalDurationSeconds: Int?, sessionId: String?)`

| Field | Value |
|---|---|
| Channel | `rova_recording_complete` |
| Small icon | `ic_notif_complete` — check vector (16dp), tinted to channel accent |
| Accent | `#34d399` |
| Colorized | `true` (DEFAULT importance allows it; system honors on most OEMs) |
| Title | `"Merge complete"` |
| Body | with duration: `"$clipCount clips · $durLabel total · saved to Library"`; without duration: `"$clipCount clips saved to Library"` (current copy). Singular handled for `clipCount == 1`. |
| Progress | none |
| Actions | `View in Library` → MainActivity intent with `EXTRA_SESSION_ID = sessionId` + `EXTRA_TARGET_TAB = "history"`; `Share` → `ACTION_SEND` chooser for the merged MP4 URI via `safeShareUri` |
| Ongoing | `false` |
| AutoCancel | `true` |
| Show when | `setShowWhen(true)` — completion timestamp |

When `sessionId == null` (degenerate path), the `View in Library` action falls back to MainActivity without extras (just opens History tab).

## 6. Data flow — new numeric fields

Each new field maps to existing service state. No new manifest persistence.

| Field | Source | Notes |
|---|---|---|
| `ClipRecording.etaSecondsRemaining` | `(segmentStartMillis + segmentDurationMillis) - now` clamped ≥ 0 | Already computed for `RecordHudFormatters`. Plumb into the existing notification update call site at the segment-tick boundary. |
| `GapWaiting.nextStartsInSeconds` | `nextTickFireTimeMillis - now` divided by 1000 | `AlarmScheduler` already stores this; expose via a read-only signal. |
| `GapWaiting.gapTotalSeconds` | `intervalMinutes * 60 - segmentDurationSeconds` | Pure from `SessionConfig`. |
| `Merging.mergeProgressPercent` | `(segmentsMerged * 100) / totalSegments` from existing `MergeProgressSignal` | Already surfaced for `SessionStatusCard`; route to notification updater. |
| `MergeComplete.totalDurationSeconds` | Sum of `segments[i].durationMillis` from `SessionManifest` post-merge | `performMerge` already iterates segments; capture sum and pass to the final notification. |

Notification update cadence (rate-limit): the service already gates `lastMergeNotifyMillis` for merge progress. Extend the same pattern to ClipRecording (≥ 1 Hz) and GapWaiting (≥ 1 Hz) updates to avoid notification spam. Merging stays at the existing throttle.

## 7. Drawables

Four new vector drawables in `res/drawable/`:

| File | Visual | Notes |
|---|---|---|
| `ic_notif_recording.xml` | Solid filled circle (Material Symbols "fiber_manual_record") | 24dp viewBox, single-color path tinted by `setColor(#5b7fff)` |
| `ic_notif_waiting.xml` | Pause / "hourglass_top" | 24dp viewBox, single path |
| `ic_notif_merging.xml` | "merge" arrows / "auto_awesome_motion" | 24dp viewBox |
| `ic_notif_complete.xml` | Check ("check_circle") | 24dp viewBox |

All four are single-path Material Symbols-style outlines, monochrome white (system tints via accent). No PNGs.

The status-bar small icon on Android must be a single-color silhouette — these conform. The notification panel large area picks up the accent via `setColor`.

## 8. Accessibility

WCAG 2.2 AA + Android Accessibility guide audit:

- **Contrast** — accent on platform notification background:
  - `#5b7fff` (RGB 91/127/255) on dark surface `~rgba(22,25,38)` → contrast ratio 5.2:1 ✓ (AA non-text 3:1; AA text 4.5:1)
  - `#34d399` (RGB 52/211/153) on same → 7.8:1 ✓ AAA
  - System forces text contrast for `setContentTitle` / `setContentText`; we don't override.
- **Action button labels** — `Action.Builder(icon, "Stop", intent).build()` — the string is read by TalkBack. No tooltip needed.
- **Action button `contentDescription`** — `Action.Builder.setContentDescription("Stop recording the current session")` for ambiguous labels. Add for: `Stop` ("Stop the recording session"), `Stop Early` ("Stop before the next clip"), `Open` ("Open Rova"), `View in Library` ("View this recording in the Library"), `Share` ("Share this recording").
- **Touch targets** — notification action buttons are system-sized (≥ 48dp). No override.
- **Progress bar a11y** — `setProgress(max, current, indeterminate)` is automatically announced by TalkBack as "X percent".
- **Live-region announce** — MergeComplete uses `setOnlyAlertOnce(false)` so the channel default sound plays; recording-session updates use `setOnlyAlertOnce(true)` to avoid TalkBack spam every second.
- **Lockscreen visibility** — `setVisibility(NotificationCompat.VISIBILITY_PUBLIC)` on recording-session channel (matches today); MergeComplete uses `VISIBILITY_PRIVATE` so the body ("saved to Library") doesn't leak filenames on the lockscreen.
- **Color independence** — every state distinguishable by icon + title text, not color alone (WCAG 1.4.1).
- **Motion** — Android handles notification expand/collapse animation; no custom animations.

## 9. Responsive behavior

Notifications render in a system-managed bar — width/density handled by the platform. We must verify:

- Collapsed (1-line) vs expanded (2-line + actions): set `setStyle(NotificationCompat.BigTextStyle().bigText(body))` only when body exceeds ~40 chars; otherwise default. Most M5 body strings fit collapsed.
- Action overflow: max 3 actions before system collapses. We never exceed 2.
- Tablet / large-screen: same layout; system handles.
- Android Auto / Wear: not in scope (no `setCategory(CATEGORY_TRANSPORT)`).
- Light mode: design tokens light variant not built (`Theme.kt:87` pins `darkTheme=true`). Notification panel ignores app theme — system colors handle.

## 10. Testing

Pure JVM tests only (CLAUDE.md policy `isReturnDefaultValues = true`):

### Extended (existing file)

`NotificationCopyTest.kt`:
- `ClipRecording with eta produces "X:XX remaining" body`
- `ClipRecording without eta falls back to static body`
- `GapWaiting body uses nextInLabel verbatim`
- `Merging body remains static (eta deferred)`
- `MergeComplete with duration produces "N clips · X:XX total · saved to Library"`
- `MergeComplete without duration uses singular for clipCount=1`
- `MergeComplete without duration uses plural for clipCount>1`

### New files

`NotificationChannelConfigTest.kt`:
- `ClipRecording routes to session channel`
- `GapWaiting routes to session channel`
- `Merging routes to session channel`
- `MergeComplete routes to complete channel`
- `session channel has IMPORTANCE_LOW`
- `complete channel has IMPORTANCE_DEFAULT`
- `accent for recording states is brand blue`
- `accent for complete state is brand green`
- `dismissibility — only MergeComplete is dismissible`

`NotificationActionSpecTest.kt`:
- `ClipRecording produces Stop + Open specs in order`
- `GapWaiting produces Stop Early + Open specs in order`
- `Merging produces empty action list`
- `MergeComplete produces View in Library + Share specs`
- `MergeComplete View in Library carries sessionId extra`
- `MergeComplete without sessionId produces View in Library without extras`
- `every action spec has a non-blank contentDescription`

`NotificationIconResTest.kt`:
- four straight `assertEquals(R.drawable.ic_notif_…, state.toIconRes())`

`NotificationProgressTest.kt`:
- `ClipRecording returns null progress`
- `GapWaiting with both counts returns determinate progress`
- `GapWaiting missing nextStartsInSeconds returns null`
- `Merging with percent returns determinate`
- `Merging without percent returns indeterminate`
- `MergeComplete returns null progress`

Test count delta: roughly +25 tests. Baseline after M5 ≈ 1270.

No instrumented tests added. Smoke verification per CLAUDE.md is real-device only (emulators fail CameraX video recording).

## 11. Real-device smoke checklist

For owner verification before merge:

- [ ] Start a session, watch ClipRecording notification → ETA decrements per second, accent blue, Stop+Open actions visible
- [ ] Wait through to gap → notification flips to GapWaiting, body shows countdown, progress bar advances
- [ ] Loop completes → notification flips to Merging, progress bar fills as segments mux
- [ ] Merge completes → new notification on `recording_complete` channel, green check, View+Share actions, swipe-dismissible
- [ ] Tap "View in Library" → MainActivity opens, History tab selected
- [ ] Tap "Share" on MergeComplete → system share chooser opens with the merged MP4
- [ ] Open Settings → Apps → Rova → Notifications → both channels visible with distinct names
- [ ] Disable "Recording complete" channel only → recording-session notifications still post
- [ ] TalkBack on → each state's title + body + actions read aloud correctly
- [ ] Lockscreen → recording-session shows title; MergeComplete body hidden (`VISIBILITY_PRIVATE`)
- [ ] Force-stop mid-merge → no orphaned MergeComplete notification

## 12. Build / static-check impact

- No new `check*` tasks needed (notification topology not under ADR invariant).
- No ADR amendment needed.
- No `SessionManifest` schema change.
- One existing static check, `checkAudioModeFgsTypeMatch`, is unaffected (it inspects the `startForeground` FGS-type argument, not the notification builder).
- Lint: `NotificationPermission` (API 33+) — already handled today by the FGS permission request; no new POST_NOTIFICATIONS handling here.

## 13. Files touched

| File | Change |
|---|---|
| `app/src/main/java/com/aritr/rova/service/notification/NotificationCopy.kt` | extend `NotificationState` variants with optional numeric fields; extend `toCopy()` to format with them |
| `app/src/main/java/com/aritr/rova/service/notification/NotificationChannelConfig.kt` | **new** — channel ids, importance, accent, dismissibility, progress; pure |
| `app/src/main/java/com/aritr/rova/service/notification/NotificationActionSpec.kt` | **new** — typed action specs (label, contentDescription, intent action string, extras map); pure |
| `app/src/main/java/com/aritr/rova/service/notification/NotificationIconRes.kt` | **new** — `@DrawableRes` lookup per state; pure |
| `app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt` | split `createNotification` into per-state builder, register both channels, wire `setColor` / `setColorized` / `setProgress` / per-state actions / dismissibility; rate-limit existing `updateNotification(state)` per state |
| `app/src/main/res/drawable/ic_notif_recording.xml` | **new** — vector |
| `app/src/main/res/drawable/ic_notif_waiting.xml` | **new** — vector |
| `app/src/main/res/drawable/ic_notif_merging.xml` | **new** — vector |
| `app/src/main/res/drawable/ic_notif_complete.xml` | **new** — vector |
| `app/src/main/res/values/strings.xml` | **new entries** — channel names + descriptions + action labels + contentDescriptions |
| `app/src/test/java/com/aritr/rova/service/notification/NotificationCopyTest.kt` | extend |
| `app/src/test/java/com/aritr/rova/service/notification/NotificationChannelConfigTest.kt` | **new** |
| `app/src/test/java/com/aritr/rova/service/notification/NotificationActionSpecTest.kt` | **new** |
| `app/src/test/java/com/aritr/rova/service/notification/NotificationIconResTest.kt` | **new** |
| `app/src/test/java/com/aritr/rova/service/notification/NotificationProgressTest.kt` | **new** |

## 14. Risks

| Risk | Mitigation |
|---|---|
| OEM honoring `setColor` / `setColorized` is inconsistent (Samsung One UI in particular) | Accent is decorative — body title + icon convey state without color |
| Channel split surprises existing users (importance reset) | Old `CHANNEL_ID` stays registered (unused) so user overrides aren't reset; new channels declared with sensible defaults |
| ETA jitter from clock drift | Service already uses `SystemClock.elapsedRealtime` for the segment boundary; reuse |
| Notification update cadence too high → TalkBack spam | `setOnlyAlertOnce(true)` on recording-session updates; rate-limit at 1 Hz via existing throttle pattern |
| `View in Library` deep link doesn't scroll to row | Out of scope — tab selection only is the v1.0 promise |
| MergeComplete on the new channel fails to play sound on first launch (user must accept POST_NOTIFICATIONS on API 33+) | Already handled by RecordScreen JIT permission prompt (M4) |

## 15. Acceptance criteria

1. All four states render with mockup-faithful skin (icon + accent + copy + actions + progress + dismissibility) on a real device running Android 14+.
2. `./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug` green.
3. Test baseline grows from 1245 to ≥ 1270 with 0-0-0.
4. Real-device smoke checklist (§11) passes.
5. TalkBack reads each state correctly.
6. No new lint warnings on the notification builder code path.
7. No `check*` task regressions.
8. Owner sign-off on the diff per the project's review-gate cycle.

## 16. Follow-on (post-M5)

- Wire merge ETA into `Merging` body once `ExportPipeline` exposes one.
- Scroll-to-row on `View in Library` deep link.
- Delete the old `rova_background_recording` channel after one release on master.
- Export options sheet (Phase 5 of replan).

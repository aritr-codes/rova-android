# Product Vision & Feature Analysis

## 1. Product Overview

**Rova** is an Android app for **automated periodic video recording**. Unlike standard camera apps that require manual start/stop, Rova runs recording loops autonomously: record for N seconds, wait M minutes, repeat K times, then merge all segments into a single video.

The app runs as a foreground service, continuing to record even when the screen is off or the app is in the background. This makes it ideal for hands-free, unattended recording scenarios.

**Core value proposition:** Set up your phone, configure a recording pattern, walk away, and come back to a single merged video.

---

## 2. Target Users & Use Cases

### Primary: Athletes & Coaches
- Film basketball free throws, golf swings, tennis serves, weightlifting form
- Record 10-second clips with 30-second rest between reps
- Review form across multiple attempts in a single merged video
- Hands-free operation while training solo

### Secondary: Content Creators
- Time-lapse style periodic capture for cooking, art, DIY projects
- Vlog-style periodic recording throughout a day
- Behind-the-scenes footage of creative processes

### Tertiary: Monitoring & Documentation
- Property/garden monitoring at intervals
- Construction progress documentation
- Pet/baby monitoring when away
- Event documentation (conferences, workshops)

---

## 3. Core Functionality

### 3.1 Recording Engine
| Feature | Description | Status |
|---------|-------------|--------|
| Periodic loop recording | Record N seconds, wait M minutes, repeat K times | Implemented |
| Background recording | Continue recording with screen off via foreground service | Implemented |
| Auto-merge on completion | Stitch all segments into a single MP4 using MediaMuxer | Implemented |
| Resolution selection | SD / HD / FHD / 4K | Implemented |
| Front/back camera | Switch between cameras before recording | Implemented |
| Flash modes | Off / On / Auto (torch mode) | Implemented |
| Audio recording | Record with device microphone, graceful fallback if permission denied | Implemented |
| DualShot (P+L mode) | Simultaneous Portrait + Landscape recording in a single session via CameraEffect fan-out | Implemented (ADRs 0008/0009/0010, PRs #22–#35) |
| Pre-recording countdown | 3-2-1 countdown with audio/visual cues before first segment | Not implemented |
| Frame rate selection | 24 / 30 / 60 fps | Not implemented |
| Single continuous mode | Standard recording without looping | Not implemented |

### 3.2 Session Management
| Feature | Description | Status |
|---------|-------------|--------|
| Quick presets | Tap-to-apply configurations (Drill, Vlog) | Implemented |
| Custom presets | User-created and saved configurations | Implemented |
| Settings persistence | All parameters saved to SharedPreferences | Implemented |
| Named sessions | Give recordings meaningful names instead of timestamps | Not implemented |
| Session resume | Recover from crash and continue where left off | Partial (orphan cleanup exists) |

### 3.3 Library & Playback
| Feature | Description | Status |
|---------|-------------|--------|
| Video list | Browse recorded videos with metadata | Implemented (basic) |
| Batch delete | Select and delete multiple videos | Implemented |
| Play/share via system | Open videos in external player or share | Implemented |
| Video thumbnails | Frame preview for each video in the list | Implemented |
| Sort/filter | Sort by date, size; search by name | Not implemented (stubs exist) |
| In-app video player | Watch recordings without leaving the app | Implemented (PR #1 `db25405`) |
| Resolution/duration badges | Show actual video metadata on cards | Not implemented |

### 3.4 Safety & Reliability
| Feature | Description | Status |
|---------|-------------|--------|
| Storage space check | Estimate required space before recording, abort if insufficient | Implemented |
| Orphaned segment cleanup | Delete leftover segments from crashed sessions on next launch | Implemented |
| Battery optimization prompt | Encourage user to exempt app from Doze mode | Shipped (WarningCenter `BATTERY_OPTIMIZATION_ON`, ADR-0007) |
| Crash recovery dialog | Offer to merge orphaned segments instead of silently deleting | Not implemented |
| Low-battery graceful shutdown | Finish current segment, merge, save, stop | Not implemented |

---

## 4. Advanced / Future Features

### 4.1 Coach Mode (High Impact)
Transform the app from a timer-based recorder into an autonomous training assistant:
- Voice announcements: "Rep 1 - GO", "REST", "Rep 2 - GO"
- Configurable voice prompts between segments
- Auto-label segments as reps in the merged output
- Session summary: "10 reps completed, total recording time: 2m 30s"

This is the strongest differentiator. No mainstream app offers voice-guided periodic recording for athlete training.

### 4.2 Smart Recording Triggers
- **Scheduled recording** — "Start at 6:00 AM every weekday" using WorkManager + AlarmManager
- **Motion-triggered** — Start recording when the camera detects movement (MediaPipe or frame-diff)
- **Sound-triggered** — Start when ambient noise exceeds a threshold
- **Bluetooth trigger** — Start when a specific device connects (smartwatch, car)

### 4.3 Advanced Output
- **Segment transition effects** — Crossfade or quick-cut between merged segments
- **Timelapse mode** — Capture 1 frame per N seconds, compile into timelapse video
- **Slow-motion playback** — 0.25x / 0.5x for form review
- **Side-by-side comparison** — Compare two recordings from different sessions
- **Auto-trim** — Detect the "action window" in each segment and trim dead time

### 4.4 Connected Features
- **Wearable control** — Start/stop from Wear OS watch with haptic countdown
- **Multi-angle sync** — Two phones on same Wi-Fi sync their recording loops
- **Cloud upload** — Auto-upload to Google Drive / Dropbox after merge
- **Remote start/stop** — Control via companion web app or second device

### 4.5 Loop Overwrite Mode (Dashcam-Style)
Keep only the last N minutes of footage, continuously overwriting the oldest segments. Tap a "save" button to permanently keep the last 30 seconds. Completely different from periodic mode but serves a distinct use case with the same tech stack.

### 4.6 Progress Journal
Over time, build a visual timeline of all sessions. An athlete can scroll through weeks of practice footage. The app could auto-generate a monthly highlight reel from bookmarked segments.

---

## 5. UI / UX Design Principles

### 5.1 Navigation Structure

```
Record screen (home) — owns its own bottom nav:

  Library         [ START/STOP ]        Settings
  (drill-down)    (center FAB)          (drill-down)
```

Shipped R1 model (PR #17): `record` is the home screen and owns a floating bottom nav (Library / center FAB / Settings). `history` and `settings` are drill-down routes pushed on the back stack, not tab peers. There is no Schedule or Feedback tab. On first launch, an `onboarding` flow (3 immersive screens, PR #53) precedes `record`.

### 5.2 Record Screen
- Camera preview dominates the screen
- Bottom sheet: quick presets at peek, detailed controls on expand
- Live session dashboard during recording: large countdown timer, current loop, elapsed time, estimated remaining, storage consumed
- FAB visually transforms between states: idle (camera icon) → recording (pulsing red) → waiting (blue with countdown) → merging (spinner)

### 5.3 Library Screen
- Thumbnail grid as default view (not a text list)
- Each card: first-frame thumbnail, duration, date, file size, resolution badge
- Swipe actions: share (right), delete (left)
- Tap opens in-app player with scrub bar and playback speed controls
- Batch operations: select multiple, delete, share, merge together

### 5.4 Settings Screen
- Grouped sections: Recording Defaults, Countdown & Alerts, Storage, Behavior, Output
- Each setting shows its current value inline
- "Test Recording" button: runs a 3-second test with current settings

### 5.5 Visual Feedback During Recording
- Pulsing REC indicator with overlay timer (exists)
- Haptic feedback (vibration) on recording start/stop
- Audio beep on start/stop using custom sound (implemented)
- Semi-transparent overlay showing loop progress
- Notification with live info: current loop, time remaining

### 5.6 Onboarding

Shipped M4 (PR #53 `12c12a9`): 3 immersive screens shown automatically on first launch. The onboarding is fullscreen, swipe-navigated, and covers permission grants (Camera, Mic, Notifications, Exact Alarm). There is no persona picker in the current implementation — the original "What will you use this for?" flow was not shipped in M4 and remains a future consideration.

---

## 6. Ideal Recording Workflow

### Setup (30 seconds)
1. Open app → camera preview is immediately live
2. Bottom sheet shows last-used preset selected by default
3. User taps a preset or adjusts settings
4. User positions phone on tripod, shelf, or propped surface

### Recording (hands-free)
5. Tap START → **3-second countdown** with large on-screen numbers and beeps
6. Screen shows "Recording 1/10" with progress ring
7. Segment ends → beep → "Next in 45s" countdown displayed
8. User reviews form, repositions if needed
9. Next beep → recording starts again
10. Screen dims/locks between loops if `keepScreenOn` is off

### Completion
11. All loops finish → merge begins automatically with progress overlay
12. Notification: "Video saved: Session_20260306.mp4"
13. App navigates to Library with the new video highlighted
14. User taps to review, optionally shares

### Interruption Handling
- **Phone call** → current segment finishes, session pauses, resumes after call
- **Manual STOP** → merge whatever segments exist so far
- **App crash** → on next launch, detect orphaned segments, offer to merge or discard
- **Low battery (<10%)** → finish current segment, merge, save, stop gracefully
- **Storage full** → stop recording, merge existing segments, notify user

---

## 7. Settings & Customization

### Recording Defaults
| Setting | Range | Default |
|---------|-------|---------|
| Duration | 1s - 300s | 10s |
| Interval | 0 - 1440 min | 1 min |
| Loop count | 1 - 999 or Infinite | 10 |
| Resolution | SD / HD / FHD / 4K | FHD |
| Frame rate | 24 / 30 / 60 fps | 30 |
| Default camera | Front / Back | Back |
| Audio | On / Mute | On |

### Countdown & Alerts
| Setting | Options | Default |
|---------|---------|---------|
| Pre-recording countdown | 0 / 3 / 5 / 10 seconds | 3s |
| Beep sound | On / Off / Custom sound | On |
| Vibrate on start/stop | On / Off | On |
| On-screen countdown | On / Off | On |

### Storage
| Setting | Options | Default |
|---------|---------|---------|
| Save location | Internal / SD Card | Internal |
| Auto-merge after session | On / Off | On |
| Keep individual segments | On / Off | Off |
| Auto-cleanup older than | Never / 7d / 30d / 90d | Never |
| Min free space warning | 500MB / 1GB / 2GB | 1GB |

### Behavior
| Setting | Options | Default |
|---------|---------|---------|
| Keep screen on | On / Off | Off |
| Background recording | On / Off | On |
| Lock exposure between loops | On / Off | Off |
| Battery saver (reduce quality below 20%) | On / Off | On |

### Output
| Setting | Options | Default |
|---------|---------|---------|
| Filename pattern | Auto / Custom prefix | Auto |
| Video codec | H.264 / H.265 | H.264 |
| Merge transition | Hard cut / Crossfade | Hard cut |

---

## 8. Reliability & Safety

### Battery
- Prompt user to exempt app from battery optimization (Doze mode)
- Graceful shutdown at low battery: finish segment → merge → save → stop
- Battery consumption estimate shown before starting session
- Reduced quality mode below 20% battery

### Storage
- Pre-session storage check with space estimate (implemented)
- Mid-session monitoring: stop if free space drops below 200MB
- Clear warning: "This session will use approximately 1.2 GB"
- Storage usage dashboard in Settings

### Crash Recovery
- Orphaned segment cleanup on next launch (implemented)
- Upgrade to user-facing dialog: "Found 5 segments from a previous session. Merge or discard?"
- Write session metadata to `.session.json` at start for recovery context
- Periodic checkpoint after every N segments

### Service Robustness
- Foreground service with proper notification (implemented)
- `START_STICKY` with settings-based state restoration (implemented)
- Wake lock for CPU during interval waits
- Handle permission revocation mid-session gracefully

---

## 9. Feature Priority for v1.0

| Priority | Feature | Rationale |
|----------|---------|-----------|
| P0 | Pre-recording countdown (3-2-1) | First segment always shows user walking away without this |
| P0 | Video thumbnails in Library (**Shipped**) | A list of filenames is unusable |
| P0 | In-app video player (**Shipped** — PR #1 `db25405`) | Users shouldn't leave the app to watch recordings |
| P0 | Battery optimization banner (**Shipped** — WarningCenter ADR-0007) | Recording fails silently on many OEMs without this |
| P1 | Haptic feedback on start/stop | Athletes can't always hear the beep |
| P1 | Session naming | Timestamps are meaningless a week later |
| P1 | Frame rate selection (30/60fps) | Athletes reviewing form need 60fps |
| P1 | Sort/search in Library | Basic organization for frequent users |
| P2 | Coach Mode (voice announcements) | The core differentiator |
| P2 | Scheduled recording | "Record my garden every morning at 7am" |
| P2 | Smart trim | Removes tedium of editing raw periodic footage |
| P3 | Multi-angle sync | Ambitious but unique |
| P3 | Wearable control | Convenience for solo athletes |
| P3 | Cloud upload | Expected for any modern recording app |

---

## 10. Long-Term Product Direction

### Phase 1: Solid Recorder (Current)
Ship a reliable periodic recording app that handles the basics flawlessly — record, merge, review. No crashes, no silent failures, no lost footage.

### Phase 2: Training Assistant
Add Coach Mode, voice prompts, rep counting, and form comparison. Position the app as the go-to solo training companion for athletes.

### Phase 3: Smart Capture Platform
Add triggers (motion, sound, schedule, Bluetooth), timelapse mode, and connected features. The app becomes a versatile automated capture system.

### Phase 4: Connected Ecosystem
Multi-device sync, cloud upload, wearable control, progress journal. The app becomes a platform, not just a tool.

The key principle: **each phase should ship a complete, polished experience** — not a half-built version of the next phase. A great recorder beats a mediocre training assistant.

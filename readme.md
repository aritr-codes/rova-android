# Rova — Periodic Background Video Recorder

An Android app for **automated, hands-free periodic video recording**.

Set a duration, interval, and loop count — Rova records in the background, then merges all segments into a single video when done. Designed for athletes, creators, and anyone who needs unattended recording.

| | |
|---|---|
| Package | `com.aritr.rova` |
| Version | `0.5.0` |
| `minSdk` | 24 (Android 7.0) |
| `targetSdk` | 36 |
| UI | Jetpack Compose (Material 3) |
| Capture | CameraX |
| Status | Active development — see [`ROADMAP_v6.md`](ROADMAP_v6.md) |

---

## Quick Start

**Prerequisites:** Android Studio Ladybug+, physical Android device (emulators often fail with CameraX video recording).

```bash
# Clone, open in Android Studio, then:
./gradlew installDebug
```

1. Grant Camera and Microphone permissions on first launch.
2. Select a preset or configure Duration / Interval / Loops in the bottom sheet.
3. Tap **START RECORDING** and walk away.
4. Tap **STOP** (in-app or in the notification) when done.
5. The merged video appears in the **History** tab.

Videos are saved to `Android/data/com.aritr.rova/files/videos/`.

---

## Features

- **Periodic loop recording** — record N seconds, wait M minutes, repeat K times (or continuous).
- **Background recording** — continues with screen off via a foreground service typed for camera + microphone.
- **Auto-merge** — segments stitched into a single MP4 when the loop ends.
- **Video library** — real thumbnails, resolution badges, multi-select share/delete.
- **In-app player** (Phase 2.5) — manifest-driven Media3 surface routed from the Library list via `player/{sessionId}`. Tier 1 plays the `MediaStore` content URI; Tier 2/3 play a `file://` URI. Segmented timeline shows clip boundaries; play/pause + ±10s seek + auto-pause on background. Trim/Edit are placeholders (editor scope deferred per `NEW_UI_BACKEND_REPLAN.md` §6.2). Mockup: [`mockups/new_uiux/04-video-player.html`](mockups/new_uiux/04-video-player.html).
- **Quick presets** — one-tap configs (Drill, Vlog) plus custom user-saved presets.
- **Resolution selection** — SD / HD / FHD / 4K with `QualitySelector` fallback.
- **Tiered public export** — finalized merges land in the public Movies directory using the right API for the device (Tier 1 API 29+ MediaStore, Tier 2 API 26–28, Tier 3 API 24–25).
- **Crash- and kill-resilient recovery** — sessions terminated by force-stop, OOM, or vendor battery management surface as recovery cards in History on the next cold launch.
- **Vendor guidance** — `KILLED_BY_SYSTEM` recovery cards open the device's auto-start / battery-optimization screen (MIUI, Samsung, OnePlus, Vivo, Oppo) with a graceful fallback to App Settings.
- **Battery optimization prompt** — detects Doze mode and guides the user to exempt the app.
- **Storage safety** — estimates required space before recording; aborts if insufficient.

---

## Architecture

```mermaid
graph TB
    subgraph UI["UI · Jetpack Compose"]
        MS[MainScreen<br/>NavHost]
        RS[RecordScreen]
        HS[HistoryScreen]
        SS[SettingsScreen]
        RC[RecoveryCard]
        PL[PlayerScreen<br/>player/{sessionId}]
    end

    subgraph VM["ViewModels"]
        RVM[RecordViewModel]
        HVM[HistoryViewModel]
        SVM[SettingsViewModel]
        RecVM[RecoveryViewModel]
        PVM[PlayerViewModel<br/>ExoPlayer + manifest]
    end

    subgraph APP["Process singleton"]
        APPK[RovaApp<br/>recoveryReport: StateFlow]
    end

    subgraph SVC["Foreground service"]
        REC[RovaRecordingService<br/>CameraX bind · segment loop]
        TICK[RovaTickReceiver<br/>segment boundary]
        STOP[RovaStopReceiver<br/>loop-count exhaust]
    end

    subgraph DATA["Data layer"]
        STORE[SessionStore<br/>atomic manifest write]
        MERGE[VideoMerger]
    end

    subgraph RECOVER["Recovery + Export"]
        SCAN[RecoveryScanner<br/>classifyAll]
        EXPRUN[ExportRecoveryRunner]
        T1[Tier1Exporter · API 29+]
        T2[Tier2Exporter · 26-28]
        T3[Tier3Exporter · 24-25]
    end

    MS --> RS & HS & SS & PL
    HS --> RC
    HS --> PL
    RS --> RVM
    HS --> HVM
    SS --> SVM
    HS --> RecVM
    PL --> PVM
    PVM --> STORE
    RVM --> REC
    RecVM --> APPK
    APPK -- triggerRecoveryScanIfNeeded --> SCAN
    APPK --> EXPRUN
    SCAN --> STORE
    EXPRUN --> T1 & T2 & T3
    REC --> STORE
    REC --> MERGE
    REC -. AlarmManager .-> TICK
    REC -. AlarmManager .-> STOP
    TICK --> REC
    STOP --> REC
    HS --> STORE
```

The recovery scan is triggered **only** from `MainActivity.onCreate` via
`RovaApp.triggerRecoveryScanIfNeeded()` (ADR 0005 §"Scan Trigger Boundary").
A static `checkScanTriggerSingleSite` task in `app/build.gradle.kts`
enforces the single-site contract.

---

## Session lifecycle

```mermaid
stateDiagram-v2
    [*] --> Idle
    Idle --> Recording: user starts loop
    Recording --> Recording: segment finalized<br/>next TICK alarm

    Recording --> Merging: user stops · loop count exhausted
    Merging --> Completed: terminated = COMPLETED
    Completed --> [*]

    Recording --> UserStopped: user stops before merge
    Recording --> KilledBySystem: vendor battery reaper /<br/>OOM-kill
    Recording --> KilledForceStop: Settings · Force Stop

    UserStopped --> RecoveryCard
    KilledBySystem --> RecoveryCard
    KilledForceStop --> RecoveryCard

    RecoveryCard --> [*]: discard (deferred slice)
    RecoveryCard --> Merging: merge what was recorded<br/>(deferred slice)

    note right of RecoveryCard
        Surfaced in History on
        next cold launch.
        KILLED_BY_SYSTEM cards
        also expose the vendor
        auto-start screen.
    end note
```

`Terminated` is the persisted manifest field that drives recovery; the
classifier in `RecoveryScanner` cross-references on-disk segments against
the manifest to decide between `OFFER_DISCARD`, `AUTO_DISCARD_ELIGIBLE`,
and `BLOCKED`.

---

## Permissions

| Permission | Purpose |
|---|---|
| `CAMERA` | Mandatory — required to start. |
| `RECORD_AUDIO` | Optional — absence locks the session into VIDEO_ONLY (ADR 0006 B18). |
| `FOREGROUND_SERVICE` | Service host. |
| `FOREGROUND_SERVICE_CAMERA` | API 30+ FGS type for the camera pipeline. |
| `FOREGROUND_SERVICE_MICROPHONE` | API 30+ FGS type when audio is on. |
| `POST_NOTIFICATIONS` | Mandatory at session start on API 33+. |
| `WAKE_LOCK` | Bounded acquire/release across segment boundaries (ADR 0006). |
| `SCHEDULE_EXACT_ALARM` | Segment boundary TICK + loop-count STOP alarms (ADR 0001). |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | User-driven prompt; soft-fail tolerated. |
| `WRITE_EXTERNAL_STORAGE` | Tier 3 only (`maxSdkVersion=28`). |

---

## Tech Stack

| | |
|--|--|
| Language | Kotlin |
| UI | Jetpack Compose (Material 3) |
| Camera | AndroidX CameraX |
| Concurrency | Kotlin Coroutines + StateFlow |
| State | `ViewModel` + `MutableStateFlow` |
| Video Merge | `MediaMuxer` + `MediaExtractor` |
| Public Export | `MediaStore` (Tier 1) / scoped temp + `MediaScannerConnection` (Tier 2) / direct path (Tier 3) |
| Playback | AndroidX Media3 (ExoPlayer + PlayerView) — pinned to 1.4.1 |
| Min SDK | 24 (Android 7.0) |
| Target SDK | 36 |

---

## Build

```bash
./gradlew :app:lintDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug

# install
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The `lintDebug` task runs both Android Lint and the project's static-check
suite — every `check*` task in `app/build.gradle.kts` is wired into the
debug verification chain. Each check cites the ADR clause it enforces.

---

## Project layout

```
app/src/main/java/com/aritr/rova/
├── RovaApp.kt                       # Application + recoveryReport StateFlow + scan trigger
├── MainActivity.kt                  # Single Activity; triggers recovery scan
├── data/
│   ├── SessionStore.kt              # Atomic manifest write + session dirs
│   ├── SessionManifest.kt
│   └── ...
├── service/
│   ├── RovaRecordingService.kt      # FGS · CameraX bind · segment loop
│   ├── RovaTickReceiver.kt          # Segment boundary
│   ├── RovaStopReceiver.kt          # Loop-count exhaustion
│   ├── recovery/
│   │   └── RecoveryScanner.kt       # Phase 1.5 classifier
│   ├── export/
│   │   ├── Tier1Exporter.kt         # API 29+ MediaStore
│   │   ├── Tier2Exporter.kt         # API 26-28
│   │   ├── Tier3Exporter.kt         # API 24-25
│   │   ├── ExportRecoveryRunner.kt  # Phase 1.7 cold-boot reconciliation
│   │   └── ExportCleanupPredicate.kt
│   └── wakelock/
│       └── WakeLockPolicy.kt        # Bounded acquire (ADR 0006)
├── ui/
│   ├── MainScreen.kt                # Bottom nav scaffold
│   ├── screens/
│   │   ├── RecordScreen.kt
│   │   ├── HistoryScreen.kt         # Hosts recovery cards
│   │   └── SettingsScreen.kt
│   ├── recovery/
│   │   ├── RecoveryCard.kt          # Display surface
│   │   ├── RecoveryUiState.kt       # Pure mapper
│   │   ├── RecoveryViewSource.kt    # Adapter
│   │   ├── RecoveryViewModel.kt
│   │   └── VendorGuidanceIntents.kt # OEM auto-start screen resolver
│   ├── screens/player/              # Phase 2.5 in-app player
│   │   ├── PlayerScreen.kt          # Compose surface + segmented timeline
│   │   ├── PlayerViewModel.kt       # ExoPlayer + 250ms position poll
│   │   ├── PlayerUriResolver.kt     # Pure manifest → URI dispatch
│   │   ├── PlayerUiState.kt         # Loading | Ready | Unavailable
│   │   ├── SegmentedTimeline.kt     # Strip composable
│   │   └── SegmentedTimelineMath.kt # Pure boundary math
│   └── components/                  # Shared cards + controls (refreshed palette)
└── utils/
    ├── VideoMerger.kt               # MediaMuxer concat
    └── ...

app/src/test/java/com/aritr/rova/    # JVM unit tests (JUnit + json only — no Robolectric)
docs/adr/                            # Architecture Decision Records
```

---

## Documentation

| Document | Description |
|----------|-------------|
| [docs/product_vision.md](docs/product_vision.md) | Product overview, target users, feature roadmap, UX principles |
| [docs/architecture.md](docs/architecture.md) | Code structure, data flow, key technical decisions |
| [docs/development_log.md](docs/development_log.md) | Chronological record of implementation phases and fixes |
| [docs/naming.md](docs/naming.md) | App name candidates and branding analysis |

### Architecture Decision Records

ADRs are the source of truth for behavioral invariants and live under [`docs/adr/`](docs/adr/):

- **[0001](docs/adr/0001-exact-alarm-policy.md)** — Exact alarm policy.
- **[0002](docs/adr/0002-headless-surface.md)** — Headless surface (dummy preview when UI absent).
- **[0003](docs/adr/0003-storage-export-tiered.md)** — Tiered storage / public export (FD Mode amendment 2026-04-30).
- **[0005](docs/adr/0005-recovery-scan.md)** — Recovery scan (amended by ADR 0006 §"Cross-Phase Ordering Invariant").
- **[0006](docs/adr/0006-recording-lifecycle-robustness.md)** — Recording lifecycle robustness (Phase 1.4 / 1.5 / 1.7 contracts).

Each `check*` task in `app/build.gradle.kts` cites the ADR clause it enforces.

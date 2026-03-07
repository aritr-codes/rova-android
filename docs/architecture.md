# Architecture & Technical Overview

## 1. Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Kotlin |
| Min SDK | 24 (Android 7.0) |
| Target SDK | 36 |
| UI | Jetpack Compose (Material3) |
| Camera | AndroidX CameraX (Core, Video, View, Lifecycle) |
| Concurrency | Kotlin Coroutines & StateFlow |
| Permissions | Accompanist Permissions |
| Navigation | Navigation Compose |
| State | AndroidViewModel + MutableStateFlow |
| Persistence | SharedPreferences (via LoomSettings) |
| Video Merge | Android MediaMuxer + MediaExtractor |

---

## 2. Project Structure

```
app/src/main/java/com/aritr/loom/
├── MainActivity.kt                  # Entry point, renders MainScreen
├── data/
│   └── LoomSettings.kt             # SharedPreferences wrapper + LoomPreset data class
├── service/
│   └── LoomRecordingService.kt     # Foreground service: CameraX, recording loops, merge
├── ui/
│   ├── MainScreen.kt               # Navigation shell (bottom tabs)
│   ├── components/
│   │   ├── LoomAnimations.kt       # Pulsing opacity, slide animations
│   │   ├── LoomCardComponents.kt   # VideoCard, SwitchRow, StepperControl, etc.
│   │   ├── LoomDialogs.kt          # MergeProgressSheet, CustomDurationDialog, etc.
│   │   └── BackgroundRecordingBanner.kt
│   ├── screens/
│   │   ├── RecordScreen.kt         # Camera preview + recording controls
│   │   ├── RecordViewModel.kt      # ViewModel for RecordScreen state + service binding
│   │   ├── HistoryScreen.kt        # Video library with batch operations
│   │   ├── ScheduleScreen.kt       # Placeholder
│   │   └── SettingsScreen.kt       # App preferences
│   └── theme/
│       ├── Color.kt
│       ├── Theme.kt
│       └── Type.kt
└── utils/
    └── VideoMerger.kt              # MediaMuxer-based segment concatenation

app/src/main/res/
├── raw/
│   └── loom_beep.mp3               # Custom beep sound for recording start/stop
└── ...
```

---

## 3. Core Architecture

### 3.1 Recording Service (`LoomRecordingService`)

The service is the heart of the app. It manages the entire recording lifecycle independently of the UI.

```
┌─────────────────────────────────────────────────────┐
│                LoomRecordingService                  │
│  (Foreground Service + LifecycleOwner)               │
│                                                      │
│  ┌──────────┐  ┌──────────┐  ┌───────────────────┐  │
│  │ CameraX  │  │ Recording│  │ State Management  │  │
│  │ Provider │  │   Loop   │  │ (MutableStateFlow) │  │
│  │          │  │          │  │                    │  │
│  │ Preview  │  │ record() │  │ isRecording        │  │
│  │ Video    │  │ wait()   │  │ isPeriodicActive   │  │
│  │ Capture  │  │ repeat() │  │ isMerging          │  │
│  │          │  │ merge()  │  │ mergeProgress      │  │
│  └──────────┘  └──────────┘  │ currentLoop        │  │
│                               │ countdown          │  │
│  ┌──────────────────────┐    └───────────────────┘  │
│  │     LocalBinder       │                           │
│  │  getService()         │◄── RecordViewModel binds  │
│  │  getStateFlow()       │    via ServiceConnection  │
│  └──────────────────────┘                            │
└─────────────────────────────────────────────────────┘
```

**Key design decisions:**
- **Instance-scoped state** — `_serviceState` is an instance field, not a companion object. This prevents state leakage between service restarts.
- **CompletableDeferred for synchronization** — `surfaceProviderReady` signals when the UI has provided a preview surface. `recordingFinalized` signals when CameraX has finished writing a segment file.
- **Mutex-protected camera setup** — `setupMutex` prevents concurrent `setupCamera()` calls from racing.
- **MediaPlayer for beeps** — Plays `res/raw/loom_beep.mp3` on recording start/stop, respecting the `enableBeeps` user setting.

**Recording loop flow:**
1. `onStartCommand()` receives parameters (duration, interval, loops, resolution)
2. `startPeriodicRecording()` waits up to 3s for surface provider, then sets up camera
3. Loop: `recordSegment()` → wait interval → repeat until loop limit
4. On stop (manual or loop limit): `stopPeriodicRecordingAndMerge()`
5. `performMerge()` calls `VideoMerger.mergeSegments()` → saves final video → deletes segments

### 3.2 ViewModel (`RecordViewModel`)

Bridges the UI and service. Survives configuration changes (rotation, tab switches).

```
┌──────────────────────────────────┐
│         RecordViewModel          │
│     (AndroidViewModel)           │
│                                  │
│  ServiceConnection lifecycle:    │
│    init {} → bindService()       │
│    onCleared() → unbindService() │
│                                  │
│  State flows:                    │
│    duration: MutableStateFlow    │
│    interval: MutableStateFlow    │
│    loopCount: MutableStateFlow   │
│    resolution: MutableStateFlow  │
│    flashMode: MutableStateFlow   │
│    keepScreenOn: MutableStateFlow│
│    backgroundMode: MutableStateFlow │
│    enableBeeps: MutableStateFlow │
│    customPresets: StateFlow      │
│    serviceState: StateFlow       │◄── collected from service binder
│                                  │
│  Actions:                        │
│    setSurfaceProvider()          │
│    flipCamera()                  │
│    setFlashMode()                │
│    savePreset() / deletePreset() │
│                                  │
│  Persistence:                    │
│    Each flow auto-persists to    │
│    LoomSettings via collect {}   │
└──────────────────────────────────┘
```

### 3.3 UI Layer (`RecordScreen`)

Pure Compose UI. Collects all state from ViewModel via `collectAsStateWithLifecycle()`. No business logic.

```
RecordScreen
├── ModalNavigationDrawer (settings toggles)
│   └── BottomSheetScaffold
│       ├── Sheet Content
│       │   ├── Preset chips (LazyRow)
│       │   ├── Duration stepper
│       │   ├── Interval stepper
│       │   └── Loop count stepper
│       └── Main Content
│           ├── Camera Preview (AndroidView + PreviewView)
│           ├── Loading overlay (when camera initializing)
│           ├── Top bar (menu, REC indicator, flash/flip/info)
│           ├── Bottom overlay (loop progress during recording)
│           ├── FAB (start/stop)
│           └── Tutorial overlay
├── Merge progress dialog
├── Save preset dialog
└── Camera disconnected alert
```

### 3.4 Video Merger (`VideoMerger`)

Concatenates MP4 segments using Android's `MediaMuxer` and `MediaExtractor`.

**Key properties:**
- **MIME-type based track mapping** — Matches audio/video tracks by MIME type, not index. Safe even if segments have different track ordering.
- **Byte-weighted progress** — Progress callback uses `bytesProcessed / totalBytes` instead of `segmentIndex / segmentCount`, giving accurate UX for uneven segment sizes.
- **Rotation preservation** — Extracts rotation metadata from the first segment and applies it to the output via `setOrientationHint()`.
- **Coroutine-aware** — Checks `coroutineContext.isActive` for cancellation support.

### 3.5 Data Layer (`LoomSettings`)

Thin SharedPreferences wrapper. All properties use `apply()` (async write) via Kotlin's `prefs.edit { }` extension.

Persisted settings: `durationSeconds`, `intervalMinutes`, `loopCount`, `resolution`, `backgroundMode`, `keepScreenOn`, `enableBeeps`, `vibrateAlerts`, `preBeepDelay`, `postBeepDelay`, `customPresetsJson`.

Custom presets are stored as a JSON string (`customPresetsJson`) and parsed to `List<LoomPreset>` by the ViewModel.

---

## 4. Data Flow

```
User taps START
    │
    ▼
RecordScreen → LoomRecordingService.start(context, duration, interval, loops, resolution)
    │
    ▼
Service.onStartCommand() → startForeground() → startPeriodicRecording()
    │
    ▼
Recording loop:  setupCamera() → recordSegment() → delay(interval) → repeat
    │                                                                    │
    │  _serviceState.update { ... }                                      │
    │         │                                                          │
    │         ▼                                                          │
    │  LocalBinder.getStateFlow()                                        │
    │         │                                                          │
    │         ▼                                                          │
    │  RecordViewModel._serviceState                                     │
    │         │                                                          │
    │         ▼                                                          │
    │  RecordScreen UI recomposes                                        │
    │                                                                    │
    ▼                                                                    │
Loop limit reached or user taps STOP                                     │
    │                                                                    │
    ▼                                                                    │
stopPeriodicRecordingAndMerge() → VideoMerger.mergeSegments()            │
    │                                                                    │
    ▼                                                                    │
Delete segments → stopSelf() → RecordScreen navigates to Library
```

---

## 5. Key Technical Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Video merging | MediaMuxer (not ffmpeg-kit) | Zero dependencies, small APK, fast, sufficient for concatenation |
| State management | StateFlow (not LiveData) | Better coroutine integration, null-safe, thread-safe |
| Camera framework | CameraX (not Camera2) | Higher-level API, handles lifecycle automatically |
| UI framework | Compose (not Views) | Modern, declarative, less boilerplate |
| Persistence | SharedPreferences (not Room/DataStore) | Simple key-value settings, no relational data yet |
| Service type | Foreground (not WorkManager) | Continuous real-time camera access required |
| ViewModel scope | AndroidViewModel | Needs Application context for service binding |

---

## 6. Known Limitations

- **No database** — Video metadata (duration, resolution, thumbnails) is extracted from files at runtime, not cached. Will need Room or DataStore if the library grows large.
- **No unit tests** — `VideoMerger` and `LoomSettings` have no test coverage. Core logic changes risk silent regressions.
- **SharedPreferences on main thread** — Reads are synchronous. Not a problem at current scale but would need DataStore migration for complex settings.
- **No ProGuard/R8** — `isMinifyEnabled = false` in release build. Larger APK, no obfuscation.
- **Single recorder instance** — The service assumes one active recording session. No support for multiple concurrent sessions.

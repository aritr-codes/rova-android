# Architecture & Technical Overview

## 1. Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Kotlin |
| Min SDK | 24 (Android 7.0) |
| Target SDK | 37 |
| UI | Jetpack Compose (Material3) |
| Camera | AndroidX CameraX (Core, Video, View, Lifecycle) |
| Concurrency | Kotlin Coroutines & StateFlow |
| Permissions | Accompanist Permissions |
| Navigation | Navigation Compose |
| State | AndroidViewModel + MutableStateFlow |
| Persistence | SharedPreferences (via RovaSettings) |
| Video Merge | Android MediaMuxer + MediaExtractor |

---

## 2. Project Structure

```
app/src/main/java/com/aritr/rova/
├── MainActivity.kt                  # Entry point, renders MainScreen
├── RovaApp.kt                       # Application class; hosts leaf signals as lazy props
├── data/
│   └── RovaSettings.kt             # SharedPreferences wrapper + RovaPreset data class
├── service/
│   ├── RovaRecordingService.kt     # Foreground service: CameraX, recording loops, merge
│   ├── RovaTickReceiver.kt         # Segment-boundary AlarmManager fire
│   ├── RovaStopReceiver.kt         # Loop-count-exhausted STOP fire
│   ├── audio/                      # BeepPolicy
│   ├── dualrecord/                 # P+L dual-encode — CameraEffect + EGL14 fan-out to dual MediaMuxer
│   │   └── internal/               # EGL14 / GLES20 implementation details
│   ├── export/                     # Tier1/2/3 exporters, ExportRecoveryRunner,
│   │                               # ExportCleanupPredicate, MediaScanWaiter
│   ├── notification/               # NotificationCopy
│   ├── recovery/                   # RecoveryScanner.classifyAll, RecoveryReport
│   ├── scheduler/                  # AlarmScheduler — exact alarms only (ADR-0001)
│   ├── surface/                    # Headless preview surface variants (ADR-0002)
│   └── wakelock/                   # WakeLockPolicy — bounded acquire (ADR-0006)
├── ui/
│   ├── MainScreen.kt               # Navigation shell (History + Settings drill-down; Record owns nav)
│   ├── components/                 # Shared chrome (re-skin tokens)
│   ├── permissions/                # Permission-request composables
│   ├── share/                      # Share-sheet helpers
│   ├── screens/
│   │   ├── RecordScreen.kt         # Camera preview + recording controls
│   │   ├── RecordChrome.kt         # R1/R2: chrome metrics, active HUD (RecordActiveHud,
│   │   │                           #   LoopPill, StatusPill), SessionSettingsSheet
│   │   ├── RecordViewModel.kt      # ViewModel: service binding, recording settings, presets
│   │   ├── HistoryScreen.kt        # Video library with thumbnails and batch operations
│   │   ├── HistoryViewModel.kt     # Off-thread metadata loading for HistoryScreen
│   │   ├── SettingsScreen.kt       # App preferences
│   │   ├── SettingsViewModel.kt    # Activity-scoped: single source of truth for app settings
│   │   ├── VideoMetadataUtils.kt   # Thumbnail + resolution extraction helpers
│   │   ├── onboarding/             # 3-screen immersive onboarding (M4, PR #53)
│   │   └── player/                 # In-app player (PR #1, db25405)
│   │       ├── PlayerScreen.kt     # Compose surface + segmented timeline (Media3 ExoPlayer)
│   │       ├── PlayerViewModel.kt  # ExoPlayer + 250 ms position poll
│   │       ├── PlayerUriResolver.kt # Pure manifest → URI dispatch
│   │       └── PlayerUiState.kt    # Loading | Ready | Unavailable
│   ├── recovery/                   # RecoveryCard, RecoveryViewModel, VendorGuidanceIntents
│   ├── signals/
│   │   ├── BatteryOptimizationSignal.kt
│   │   ├── CameraPermissionSignal.kt
│   │   ├── CameraStateSignal.kt
│   │   ├── ExactAlarmSignal.kt
│   │   ├── MicrophonePermissionSignal.kt
│   │   ├── NotificationPermissionSignal.kt
│   │   ├── PowerSignal.kt
│   │   ├── StorageLowMidRecSignal.kt   # R2: mid-rec storage poll; top-banner surface
│   │   ├── StorageSignal.kt
│   │   └── ThermalStatusSignal.kt      # Asymmetric hysteresis (ADR-0019)
│   ├── warnings/
│   │   ├── WarningCenter.kt            # WarningSheet/WarningChip + WarningTopBanner
│   │   ├── WarningCenterViewModel.kt
│   │   ├── WarningId.kt               # 17-entry enum (precedence order; see WarningCenterContract.md)
│   │   └── WarningPrecedence.kt        # Pure resolver → WarningId? (highest-priority)
│   └── theme/
│       ├── Color.kt
│       ├── Font.kt
│       ├── RecordChromeTokens.kt   # Record-screen pixel-faithful constants (ADR-0013)
│       ├── RovaTokens.kt           # Shared type/shape/spacing tokens
│       ├── RovaTokensPreview.kt
│       ├── RovaWarningsV3.kt       # Warning severity + notification re-skin tokens (M5)
│       ├── SettingsSheetTokens.kt
│       ├── Theme.kt
│       └── Type.kt
└── utils/
    └── VideoMerger.kt              # MediaMuxer-based segment concatenation

app/src/main/res/
├── raw/
│   └── rova_beep.mp3               # Custom beep sound for recording start/stop
└── ...
```

---

## 3. System Overview

The app follows an MVVM architecture with a foreground service that owns the camera lifecycle independently of the UI.

```mermaid
graph TD
    subgraph Android System
        MA[MainActivity]
    end

    subgraph UI Layer
        MS[MainScreen<br/>Navigation Host]
        RS[RecordScreen<br/>Camera Preview + Controls]
        HS[HistoryScreen<br/>Video Library]
        SS[SettingsScreen<br/>App Preferences]
        PA[PreviewActivity<br/>Video Player]
    end

    subgraph ViewModel Layer
        RVM[RecordViewModel<br/>Service Binding + Settings]
        HVM[HistoryViewModel<br/>Metadata Loading + Cache]
        SVM[SettingsViewModel<br/>Activity-Scoped Shared Settings]
    end

    subgraph Service Layer
        RRS[RovaRecordingService<br/>Foreground Service + LifecycleOwner]
        VM_COMP[VideoMerger<br/>MediaMuxer Pipeline]
    end

    subgraph Data Layer
        SETTINGS[RovaSettings<br/>SharedPreferences Wrapper]
        FS[File System<br/>Segment + Merged Videos]
    end

    MA --> MS
    MS --> RS
    MS --> HS
    MS --> SS

    RS --> RVM
    RS --> SVM
    HS --> HVM
    SS --> SVM

    RVM -->|ServiceConnection<br/>bind/unbind| RRS
    RVM -->|auto-persist via collect| SETTINGS
    SVM -->|auto-persist via collect| SETTINGS
    HVM -->|scan video dir| FS

    RRS -->|StateFlow| RVM
    RRS -->|recordSegment| FS
    RRS -->|on stop| VM_COMP
    VM_COMP -->|merge segments| FS

    HS -->|launch intent| PA

    style RRS fill:#e1f5fe
    style RVM fill:#fff3e0
    style SETTINGS fill:#f3e5f5
```

---

## 4. Recording Service Internals

The service is the heart of the app. It manages CameraX, the recording loop, segment storage, and merging — all independently of the UI lifecycle.

```mermaid
graph TD
    subgraph RovaRecordingService
        subgraph CameraX Pipeline
            CP[ProcessCameraProvider]
            PRV[Preview Use Case]
            VC[VideoCapture Use Case]
            CAM[Camera Instance]
            CP --> PRV
            CP --> VC
            CP --> CAM
        end

        subgraph Recording Loop
            RL_START[startPeriodicRecording]
            RL_SETUP[setupCamera + stabilize]
            RL_REC[recordSegment]
            RL_WAIT[delay interval]
            RL_RETRY[retry on failure<br/>up to 3 attempts]
            RL_MERGE[stopPeriodicRecordingAndMerge]
            RL_START --> RL_SETUP
            RL_SETUP --> RL_REC
            RL_REC -->|success| RL_WAIT
            RL_REC -->|ERROR_NO_VALID_DATA| RL_RETRY
            RL_RETRY -->|reconfigure camera| RL_REC
            RL_WAIT -->|loop limit not reached| RL_REC
            RL_WAIT -->|loop limit reached| RL_MERGE
        end

        subgraph Synchronization
            SPR[surfaceProviderReady<br/>CompletableDeferred]
            RF[recordingFinalized<br/>CompletableDeferred]
            MX[setupMutex<br/>Camera Setup Lock]
        end

        subgraph State
            SS_STATE[_serviceState<br/>MutableStateFlow&lt;RovaServiceState&gt;]
        end

        subgraph Binder
            LB[LocalBinder<br/>getService / getStateFlow]
        end
    end

    EXT_VM[RecordViewModel] -->|bind| LB
    LB -->|exposes| SS_STATE
    EXT_UI[RecordScreen] -->|setSurfaceProvider| PRV

    style RovaRecordingService fill:#e8f5e9
```

### RovaServiceState Fields

| Field | Type | Purpose |
|-------|------|---------|
| `isRecording` | Boolean | Currently capturing a segment |
| `isPeriodicActive` | Boolean | Recording loop is running |
| `isCameraActive` | Boolean | CameraX pipeline is bound |
| `isMerging` | Boolean | VideoMerger is running |
| `mergeProgress` | Float | 0.0–1.0 merge completion |
| `currentLoop` | Int | Current segment number |
| `totalLoops` | Int | Configured loop limit (-1 = infinite) |
| `nextRecordingCountdown` | Long | Seconds until next segment |
| `recordingError` | String? | Last segment error description |
| `mergeError` | String? | Merge failure description |

### Key Design Decisions

- **Instance-scoped state** — `_serviceState` is an instance field, not a companion object. Prevents state leakage between service restarts.
- **CompletableDeferred for sync** — `surfaceProviderReady` signals when the UI has provided a preview surface. `recordingFinalized` signals when CameraX has finished writing a segment file.
- **Mutex-protected camera setup** — `setupMutex` prevents concurrent `setupCamera()` calls from racing.
- **Lifecycle-aware camera release** — Camera is unbound when app backgrounds (if not recording) to prevent CPU drain.
- **Segment retry** — Failed segments are retried up to 3 times with camera reconfigure between attempts.

---

## 5. Recording Lifecycle State Machine

```mermaid
stateDiagram-v2
    [*] --> Idle: Service created

    Idle --> CameraActive: startCameraPreview()
    CameraActive --> Idle: stopCameraPreview()<br/>(app backgrounded)

    CameraActive --> Stabilizing: onStartCommand()<br/>startPeriodicRecording()
    Stabilizing --> Recording: delay(2500ms)<br/>camera ready

    Recording --> SegmentComplete: recordSegment() succeeds
    Recording --> RetryingSegment: recordSegment() fails<br/>(ERROR_NO_VALID_DATA)

    RetryingSegment --> Recording: forceReconfigureCamera()<br/>+ retry (up to 3x)
    RetryingSegment --> SegmentComplete: all retries exhausted<br/>(skip segment)

    SegmentComplete --> WaitingInterval: interval > 0
    SegmentComplete --> Recording: interval = 0<br/>+ loops remaining
    SegmentComplete --> Merging: loop limit reached

    WaitingInterval --> Recording: countdown finished

    Recording --> Merging: user taps STOP
    WaitingInterval --> Merging: user taps STOP

    Merging --> MergeComplete: VideoMerger succeeds
    Merging --> MergeError: VideoMerger fails

    MergeComplete --> Idle: stopSelf()<br/>navigate to History
    MergeError --> Idle: stopSelf()

    CameraActive --> Idle: releaseResources()

    note right of Stabilizing
        Samsung devices need 2500ms
        for MediaCodec initialization
    end note
```

---

## 6. Recording User Journey

End-to-end sequence from user tap to merged video appearing in the library.

```mermaid
sequenceDiagram
    actor User
    participant RS as RecordScreen
    participant RVM as RecordViewModel
    participant SVC as RovaRecordingService
    participant CX as CameraX
    participant FS as File System
    participant VM as VideoMerger

    Note over RS,RVM: App Launch
    RVM->>SVC: bindService(BIND_AUTO_CREATE)
    SVC-->>RVM: onServiceConnected(LocalBinder)
    RVM->>SVC: startCameraPreview()
    SVC->>CX: setupCamera(Preview + VideoCapture)
    CX-->>SVC: camera active
    SVC-->>RVM: stateFlow → isCameraActive = true
    RVM-->>RS: collectAsStateWithLifecycle()
    RS->>RS: Show camera preview

    Note over User,VM: User Taps START
    User->>RS: Tap FAB
    RS->>SVC: RovaRecordingService.start(duration, interval, loops, resolution)
    SVC->>SVC: onStartCommand → startPeriodicRecording()
    SVC->>SVC: delay(2500ms) stabilize

    loop For each segment (up to loop limit)
        SVC->>SVC: beep()
        SVC->>CX: startRecording()
        CX->>FS: Write segment_bg_XXX.mp4
        SVC-->>RVM: isRecording = true
        RVM-->>RS: UI shows REC indicator
        SVC->>SVC: delay(duration)
        SVC->>CX: stopRecording()
        CX-->>SVC: VideoRecordEvent.Finalize
        SVC->>SVC: beep()
        SVC-->>RVM: isRecording = false, segmentCount++

        alt Interval > 0
            SVC-->>RVM: nextRecordingCountdown = N
            SVC->>SVC: countdown delay
        end
    end

    Note over SVC,VM: Recording Complete
    SVC->>SVC: stopPeriodicRecordingAndMerge()
    SVC-->>RVM: isMerging = true
    SVC->>VM: mergeSegments(segments, outputFile)
    VM->>FS: Read segments via MediaExtractor
    VM->>FS: Write merged Rova_TIMESTAMP.mp4
    VM-->>SVC: onProgress callbacks
    SVC-->>RVM: mergeProgress updates
    RVM-->>RS: Show merge progress overlay

    VM-->>SVC: Merge complete
    SVC->>FS: Delete segment files
    SVC->>FS: Copy to public Movies dir
    SVC-->>RVM: isMerging = false
    SVC->>SVC: stopSelf()
    RS->>RS: Navigate to History tab
```

---

## 7. ViewModel Architecture

```mermaid
graph LR
    subgraph RecordViewModel
        direction TB
        SC[ServiceConnection<br/>init: bind / onCleared: unbind]
        SF_D[duration: StateFlow]
        SF_I[interval: StateFlow]
        SF_L[loopCount: StateFlow]
        SF_R[resolution: StateFlow]
        SF_F[flashMode: StateFlow]
        SF_P[customPresets: StateFlow]
        SF_SS[serviceState: StateFlow]
        A_SP[setSurfaceProvider]
        A_FC[flipCamera]
        A_FM[setFlashMode]
        A_STOP[stopCameraPreview]
        A_START[startCameraPreview]
    end

    subgraph SettingsViewModel
        direction TB
        SF_B[enableBeeps: StateFlow]
        SF_V[vibrateAlerts: StateFlow]
        SF_K[keepScreenOn: StateFlow]
    end

    subgraph RovaSettings
        direction TB
        SP[SharedPreferences<br/>rova_settings]
    end

    subgraph Service
        SVC_STATE[RovaServiceState<br/>via LocalBinder]
    end

    SC -->|getStateFlow| SVC_STATE
    SVC_STATE -->|collect| SF_SS

    SF_D -->|collect + persist| SP
    SF_I -->|collect + persist| SP
    SF_L -->|collect + persist| SP
    SF_R -->|collect + persist| SP
    SF_B -->|collect + persist| SP
    SF_V -->|collect + persist| SP
    SF_K -->|collect + persist| SP

    SP -->|init read| SF_D
    SP -->|init read| SF_I
    SP -->|init read| SF_L
    SP -->|init read| SF_R
    SP -->|init read| SF_B
    SP -->|init read| SF_V
    SP -->|init read| SF_K

    style RecordViewModel fill:#fff3e0
    style SettingsViewModel fill:#fff3e0
    style RovaSettings fill:#f3e5f5
```

**RecordViewModel** is NavGraph-scoped (survives tab switches, dies on Activity destroy). It owns the `ServiceConnection` and all recording-related settings.

**SettingsViewModel** is Activity-scoped (instantiated in `MainScreen`, outside the `NavHost`). Shared between `RecordScreen` and `SettingsScreen` so that toggling a setting in one screen is immediately reflected in the other.

**Auto-persistence pattern:** Each `MutableStateFlow` is initialized from `RovaSettings` and a `viewModelScope.launch { flow.collect { settings.prop = it } }` collector writes changes back automatically.

---

## 8. UI Composition & Navigation

**R1 redesign model (shipped PR #17, 2026-05-12):** there is no longer an app-wide `NavigationBar`. The `record` screen is the home and carries its own bottom nav (Library / center Start-Stop FAB / Settings). `history` and `settings` are drill-down routes pushed on the back stack. There is no `ModalNavigationDrawer` — settings are a drill-down `SettingsScreen` route.

```mermaid
graph TD
    subgraph MainActivity
        MA_SC[setContent]
    end

    subgraph MainScreen
        NAV[NavHost<br/>startDestination = record]
    end

    subgraph RecordScreen["RecordScreen (top-level route)"]
        REC_IDLE[Idle dock<br/>plan summary + presets + START]
        REC_HUD[Active HUD<br/>REC / WAIT / Merge states]
        REC_NAV[Record bottom nav<br/>Library · FAB · Settings]
        EDITSHEETS[SessionSettingsSheet<br/>ModalBottomSheet overlay]
        WARN[WarningSheet / WarningChip<br/>overlay — ADR-0007]
    end

    subgraph HistoryScreen["history (drill-down)"]
        HS_LIST[Library grid + recovery cards]
        HS_PLAYER[→ player/{sessionId}]
    end

    subgraph PlayerScreen["player/{sessionId} (drill-down — PR #1)"]
        PL_SURF[PlayerScreen<br/>Media3 ExoPlayer + segmented timeline]
    end

    subgraph OnboardingScreen["onboarding (first-launch — M4 PR #53)"]
        OB[3 immersive screens<br/>permission grants]
    end

    subgraph SettingsScreen
        SS_SC[5 sections<br/>Reliability · Recording · Alerts · Storage · About]
    end

    MA_SC --> MainScreen
    NAV --> RecordScreen
    NAV --> HistoryScreen
    NAV --> SettingsScreen
    NAV --> PlayerScreen
    NAV --> OnboardingScreen

    REC_IDLE -->|START| REC_HUD
    REC_IDLE -.->|cell tap| EDITSHEETS
    REC_IDLE -.->|condition| WARN
    REC_HUD -.->|condition| WARN
    REC_NAV -->|Library| HistoryScreen
    REC_NAV -->|Settings| SettingsScreen
    HS_LIST -->|tap row| HS_PLAYER
    HS_PLAYER --> PL_SURF

    style RecordScreen fill:#e3f2fd
    style HistoryScreen fill:#e8f5e9
    style SettingsScreen fill:#fce4ec
    style PlayerScreen fill:#f3e5f5
    style OnboardingScreen fill:#1f2f3a
```

---

## 9. Video Merge Pipeline

```mermaid
flowchart LR
    subgraph Input
        S1[segment_bg_001.mp4]
        S2[segment_bg_002.mp4]
        S3[segment_bg_003.mp4]
    end

    subgraph Step 1: Setup
        ROT[Extract rotation<br/>from first segment]
        MUX[Create MediaMuxer<br/>+ setOrientationHint]
        EXT[Create MediaExtractor<br/>per segment]
    end

    subgraph Step 2: Track Mapping
        FIRST[First segment tracks]
        MIME[Map by MIME type<br/>video/avc → track 0<br/>audio/mp4a → track 1]
        ADD[muxer.addTrack per MIME]
    end

    subgraph Step 3: Muxing
        READ[readSampleData<br/>from extractor]
        OFFSET[Apply timestamp offset<br/>presentationTimeUs + offset]
        WRITE[muxer.writeSampleData]
        ADV[extractor.advance]
        PROG[onProgress callback<br/>bytesProcessed / totalBytes]
    end

    subgraph Output
        OUT[Rova_TIMESTAMP.mp4]
        PUB[Copy to Movies dir<br/>via MediaStore]
        DEL[Delete segment files]
    end

    S1 --> EXT
    S2 --> EXT
    S3 --> EXT
    S1 --> ROT
    ROT --> MUX
    EXT --> FIRST
    FIRST --> MIME
    MIME --> ADD
    ADD --> MUX

    MUX --> READ
    READ --> OFFSET
    OFFSET --> WRITE
    WRITE --> ADV
    ADV -->|more samples| READ
    ADV -->|segment done| PROG
    PROG -->|more segments| READ

    WRITE --> OUT
    OUT --> PUB
    OUT --> DEL

    style Output fill:#e8f5e9
```

### Merge Safety

- **Muxer state tracking** — A `muxerStarted` flag prevents calling `muxer.stop()` on an un-started muxer in error paths.
- **Single extractor release** — Extractors are released only in the `finally` block, preventing double-release.
- **Cancellation support** — Checks `coroutineContext.isActive` between segments.
- **Byte-weighted progress** — Large segments report proportionally more progress than small ones.

---

## 10. Settings Data Flow

```mermaid
flowchart TD
    subgraph App Startup
        PREFS[(SharedPreferences<br/>rova_settings)] -->|read defaults| RVM_INIT[RecordViewModel.init<br/>MutableStateFlow = settings.prop]
        PREFS -->|read defaults| SVM_INIT[SettingsViewModel.init<br/>MutableStateFlow = settings.prop]
    end

    subgraph User Changes Setting
        UI_CHANGE[User adjusts stepper<br/>or toggle] -->|onValueChange| FLOW_UPDATE[viewModel.flow.value = newVal]
        FLOW_UPDATE -->|collect in viewModelScope| PERSIST[settings.prop = newVal<br/>SharedPreferences.apply]
    end

    subgraph Recording Starts
        START_REC[RecordScreen] -->|read current values| CALL_START[RovaRecordingService.start<br/>duration, interval, loops, resolution]
        CALL_START -->|Intent extras| SVC_CMD[onStartCommand<br/>reads extras into fields]
    end

    subgraph Service Restart / START_STICKY
        SVC_RESTART[Service restarted by OS] -->|read from| PREFS
        PREFS -->|fallback values| SVC_CMD
    end

    RVM_INIT --> UI_CHANGE
    SVM_INIT --> UI_CHANGE

    style PREFS fill:#f3e5f5
```

**Preset Flow:** Custom presets are serialized as JSON in `customPresetsJson`. RecordViewModel parses them on init and persists changes via `JSONArray` serialization.

---

## 11. Key Technical Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Video merging | MediaMuxer (not ffmpeg-kit) | Zero dependencies, small APK, fast, sufficient for concatenation |
| State management | StateFlow (not LiveData) | Better coroutine integration, null-safe, thread-safe |
| Camera framework | CameraX (not Camera2) | Higher-level API, handles lifecycle automatically |
| UI framework | Compose (not Views) | Modern, declarative, less boilerplate |
| Persistence | SharedPreferences (not Room/DataStore) | Simple key-value settings, no relational data yet |
| Service type | Foreground (not WorkManager) | Continuous real-time camera access required |
| ViewModel scope | AndroidViewModel | Needs Application context for service binding |
| Camera lifecycle | Release on background | Prevents CPU drain when app not visible and not recording |
| Segment retry | 3 attempts + camera reconfigure | Samsung devices have transient encoder failures |

---

## 12. Known Limitations & Key Design Constraints

- **No database** — Video metadata (duration, resolution, thumbnails) is cached in-memory by HistoryViewModel but not persisted. Will need Room or DataStore if the library grows large.
- **SharedPreferences on main thread** — Reads are synchronous. Not a problem at current scale but would need DataStore migration for complex settings.
- **No ProGuard/R8** — `isMinifyEnabled = false` in release build. Larger APK, no obfuscation.
- **Single recorder instance** — The service assumes one active recording session. No support for multiple concurrent sessions.
- **JVM unit tests only** — No Robolectric, no instrumented tests. `testOptions.unitTests.isReturnDefaultValues = true`; framework calls no-op under JVM. Pattern: extract pure-helper objects (`SegmentGateThermal`, `ThermalHysteresis`, `AspectFitMath`, etc.) for logic that must be unit-tested; the framework-touching wrapper stays a thin seam. Baseline: 1322 tests / 0-0-0 on master.

### DualShot (P+L mode)

`service/dualrecord/` implements simultaneous Portrait + Landscape recording (ADRs 0008, 0009, 0010) via a single `CameraEffect(target=PREVIEW)` wired into an EGL14/GLES20 fan-out that drives two `MediaMuxer` instances and a single broadcast `AudioRecord`. DualShot uses a native 4:3 source with 27/64 side-crop matrices; pause/resume is deferred.

### Static-check gate (load-bearing)

`app/build.gradle.kts` registers **25 custom `check*` tasks** wired into `preBuild`. Each task is a regex/AST scan enforcing an invariant from a specific ADR clause. Check tasks cite the ADR clause they enforce; do not remove or weaken them — fix the source code to satisfy the check, or amend the ADR with explicit owner sign-off and update the check accordingly.

# Development Log

Chronological record of major implementation milestones and fixes.

---

## Phase 0: Initial Build

### Core Architecture
- Built `LoomRecordingService` as a foreground service managing CameraX lifecycle independently of the Activity
- Implemented `LoomServiceState` with Kotlin StateFlow for reactive UI updates
- Created persistent notification channel with STOP action
- Implemented `RecordScreen` in Jetpack Compose with Material3:
  - Live camera preview via `AndroidView` + CameraX `PreviewView`
  - Bottom sheet with duration/interval/loop steppers
  - Quick presets (Drill, Vlog) and custom user presets
  - Flash toggle (Off/On/Auto), camera flip, settings drawer
  - Pulsing REC indicator and countdown overlay
- Built `LoomSettings` SharedPreferences wrapper for persistence
- JSON-based custom preset storage
- Permissions handling via Accompanist (Camera, Microphone)
- Navigation shell with bottom tabs (Record, History, Schedule, Settings)

### Video Merging Integration
- Extracted `mergeVideos()` from legacy monolithic UI into standalone `VideoMerger.kt`
- Implemented `suspend fun mergeSegments()` with progress callback using MediaMuxer + MediaExtractor
- Added auto-merge trigger in service on recording completion
- Merge progress displayed in RecordScreen overlay
- Auto-cleanup of segment files after successful merge
- Rotation metadata preserved from first segment via `MediaMetadataRetriever`

### Camera Stability Fixes
- Fixed `PreviewView` lifecycle (retain instance to prevent black screen on recomposition)
- Added mutex-protected gate in `setupCamera()` to prevent race conditions
- Service waits for `SurfaceProvider` before binding CameraX use cases
- Camera loading state shown to user during initialization
- UI controls locked during recording and merging

### History Screen
- Video card grid with file metadata (name, size, date)
- Batch select and delete
- Play/share via system intents
- Filters out temporary segment files, shows only final merged videos

---

## Phase 1: Quick Wins (Technical Audit Fixes)

| ID | Fix | File |
|----|-----|------|
| Q2 | `formatInterval(0)` now returns "No wait" instead of misleading "30s" | `RecordScreen.kt` |
| Q1 | Removed duplicate `// Config` comment blocks | `LoomRecordingService.kt` |
| C3 | Wrapped `ProcessCameraProvider.getInstance().get()` in `withContext(Dispatchers.IO)` to prevent ANR | `LoomRecordingService.kt` |
| R5 | Added `POST_NOTIFICATIONS` permission request for Android 13+ | `RecordScreen.kt` |
| C4 | `onStartCommand` with null intent now restores params from `LoomSettings` instead of using wrong defaults | `LoomRecordingService.kt` |
| N4 | Replaced deprecated `Divider()` with `HorizontalDivider()` | `RecordScreen.kt` |

---

## Phase 2: Reliability

| ID | Fix | File |
|----|-----|------|
| C2 | Added `surfaceProviderReady: CompletableDeferred` — recording loop waits up to 3s for UI surface before proceeding headlessly | `LoomRecordingService.kt` |
| R2 | Added `recordingFinalized: CompletableDeferred` — both `recordSegment()` and `stopPeriodicRecordingAndMerge()` await Finalize callback with 3s timeout, replacing unreliable `delay(500)` | `LoomRecordingService.kt` |
| C5 | Added `cleanupOrphanedSegments()` in `onCreate()` — deletes leftover `segment_bg_*.mp4` files from crashed sessions | `LoomRecordingService.kt` |
| R3 | Changed VideoMerger track mapping from index-based to MIME-type-based — safe regardless of per-segment track ordering | `VideoMerger.kt` |
| R1 | `flipCamera()` returns early with log if `isRecording` is true — prevents silent inconsistency | `LoomRecordingService.kt` |
| R4 | Added `hasEnoughStorage()` + `estimateSessionBytes()` — aborts session with notification if insufficient free space | `LoomRecordingService.kt` |

---

## Phase 3: Architecture Refactor

| ID | Change | File |
|----|--------|------|
| C1 | Moved `_serviceState` from companion object to instance field. `LocalBinder.getStateFlow()` exposes it. Prevents state leakage across service restarts | `LoomRecordingService.kt` |
| A1+A2 | Created `RecordViewModel` (AndroidViewModel) — owns service binding lifecycle (`init`/`onCleared`), all settings as `MutableStateFlow`, preset management, camera action delegation | `RecordViewModel.kt` (new) |
| — | Added `lifecycle-viewmodel-compose:2.8.7` dependency | `build.gradle.kts` |
| A1+A2 | Rewrote `RecordScreen` — removed inline `ServiceConnection`, local state, `LaunchedEffect` persistence. All state from ViewModel via `collectAsStateWithLifecycle()` | `RecordScreen.kt` |
| Q3 | Wired `keepScreenOn` via `DisposableEffect` setting `view.keepScreenOn` | `RecordScreen.kt` |
| Q3 | Added `beep()` method using `ToneGenerator` for recording start/stop sounds | `LoomRecordingService.kt` |

---

## Phase 4: Cleanup & Polish

| ID | Change | File |
|----|--------|------|
| A4 | Deleted `LoomAppLegacy.kt` (945 lines of dead code). Removed `USE_NEW_UI` flag and `if/else` branch from `MainActivity` | `MainActivity.kt`, `LoomAppLegacy.kt` (deleted) |
| P3 | Changed merge progress from segment-count-based to byte-weighted. `bytesProcessed / totalBytes` gives accurate progress for uneven segment sizes | `VideoMerger.kt` |
| — | Replaced `ToneGenerator` beep with `MediaPlayer` playing custom `res/raw/loom_beep.mp3` | `LoomRecordingService.kt` |

---

## Verification Checklist

### Portrait Recording
1. Hold device in portrait
2. Start recording, let it loop at least once
3. Stop recording
4. Verify: merge notification appears, final video is upright in History

### Premature Stop
1. Start recording
2. Immediately tap STOP
3. Verify: app cleans up safely, no crash

### Background Recording
1. Start recording
2. Press Home to minimize
3. Verify: notification shows recording status, segments continue being recorded
4. Return to app, stop recording, verify merge completes

### Settings Persistence
1. Change duration/interval/loops in bottom sheet
2. Switch to History tab and back
3. Verify: settings retained (ViewModel survives tab switch)
4. Kill and restart app
5. Verify: settings restored from SharedPreferences

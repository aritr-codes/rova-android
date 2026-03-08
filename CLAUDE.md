# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
./gradlew installDebug          # Build and install debug APK on connected device
./gradlew assembleDebug         # Build debug APK without installing
./gradlew assembleRelease       # Build release APK
./gradlew test                  # Run unit tests
./gradlew connectedAndroidTest  # Run instrumented tests on device
./gradlew clean                 # Clean build outputs
```

The project uses Gradle 8.13.2 with Android Gradle Plugin 8.13.2. Kotlin 2.0.21 with the Compose compiler plugin.

## Architecture

Rova is a looping video recorder Android app. It records short video segments in a loop using CameraX, then merges them into a single file using Android's MediaMuxer.

### MVVM + Foreground Service

The core architecture splits into three layers:

1. **RovaRecordingService** (`service/`) — Foreground service that owns the CameraX lifecycle independently of the UI. Manages the recording loop: `recordSegment() → delay(interval) → repeat`. Exposes state via `MutableStateFlow<RovaServiceState>` through a `LocalBinder`. Uses `CompletableDeferred` for surface provider readiness and `Mutex` for camera setup synchronization.

2. **ViewModels** (`ui/screens/`) — `RecordViewModel` (AndroidViewModel) binds to the service via `ServiceConnection`, collects service state, and auto-persists settings to SharedPreferences. `SettingsViewModel` is activity-scoped and shared between RecordScreen and SettingsScreen. `HistoryViewModel` handles video library with off-thread metadata loading.

3. **Compose UI** (`ui/screens/`, `ui/components/`) — Pure Compose screens with zero business logic. State collected via `collectAsStateWithLifecycle()`. Camera preview rendered through `AndroidView` wrapping `PreviewView`.

### Data Flow

```
RecordScreen → RecordViewModel → RovaRecordingService.start()
                                        ↓
                        Service records loop segments (CameraX)
                                        ↓
                        _serviceState.update { ... }
                                        ↓
                        LocalBinder.getStateFlow() → ViewModel collects → UI recomposes
```

### Key Files

- `service/RovaRecordingService.kt` — Core recording logic (~766 lines), foreground service with camera lifecycle
- `ui/screens/RecordViewModel.kt` — Service binding, settings persistence, preset management
- `ui/screens/RecordScreen.kt` — Camera preview UI, controls, settings drawer
- `data/RovaSettings.kt` — SharedPreferences wrapper + `RovaPreset` data model
- `utils/VideoMerger.kt` — MediaMuxer-based segment merge with byte-weighted progress callbacks and rotation metadata preservation

### Key Technical Details

- **SDK targets**: minSdk 24 (Android 7.0), compileSdk/targetSdk 36
- **Namespace/AppId**: `com.aritr.rova`
- **No external heavy dependencies**: Uses native MediaMuxer (no ffmpeg), SharedPreferences (no Room/DataStore), no DI framework
- **Foreground service type**: `camera|microphone` — required for background recording
- **FileProvider** authority: `${applicationId}.provider`
- **Compose experimental opt-in**: `ExperimentalMaterial3Api` enabled globally via compiler args
- **Java/Kotlin target**: JVM 11

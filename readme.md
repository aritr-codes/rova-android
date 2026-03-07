# Loom — Periodic Background Video Recorder

An Android app for **automated, hands-free periodic video recording**.

Set a duration, interval, and loop count — Loom records in the background, then merges all segments into a single video when done. Designed for athletes, creators, and anyone who needs unattended recording.

---

## Quick Start

**Prerequisites:** Android Studio Ladybug+, physical Android device (emulators often fail with CameraX video recording)

```bash
# Clone and open in Android Studio, then:
./gradlew installDebug
```

1. Grant Camera and Microphone permissions on first launch
2. Select a preset or configure Duration / Interval / Loops in the bottom sheet
3. Tap **START RECORDING** and walk away
4. Tap **STOP** (in-app or in the notification) when done
5. The merged video appears in the Library tab

Videos are saved to `Android/data/com.aritr.loom/files/videos/`.

---

## Tech Stack

| | |
|--|--|
| Language | Kotlin |
| UI | Jetpack Compose (Material3) |
| Camera | AndroidX CameraX |
| Concurrency | Kotlin Coroutines + StateFlow |
| Video Merge | Android MediaMuxer |
| Min SDK | 24 (Android 7.0) |

---

## Documentation

| Document | Description |
|----------|-------------|
| [docs/product_vision.md](docs/product_vision.md) | Product overview, target users, feature roadmap, UX principles |
| [docs/architecture.md](docs/architecture.md) | Code structure, data flow, key technical decisions |
| [docs/development_log.md](docs/development_log.md) | Chronological record of implementation phases and fixes |
| [docs/naming.md](docs/naming.md) | App name candidates and branding analysis |

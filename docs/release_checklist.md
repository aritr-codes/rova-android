# Rova — release checklist

Living checklist for cutting an internal-beta release of `com.aritr.rova`.
Update with the actual values per release; the structure is fixed, the
contents change.

## Current cut

| Field | Value |
|---|---|
| Commit | `e05f5fc chore(release): prepare beta distribution metadata` |
| `versionCode` | `2` |
| `versionName` | `0.3.0` |
| Signed APK | `app/build/outputs/apk/release/app-release.apk` |
| Keystore | `rova-beta-release.jks` (gitignored) |
| `keystore.properties` | local-only (gitignored) |
| Signing fingerprint (SHA-256) | `cc61f156a4b373e922aab9dd9ca4b072f2aeef3a6cd649da4e8241f9d258de34` |

## Required verification gates

Run before every distribution:

```
rtk ./gradlew :app:lintDebug
rtk ./gradlew :app:testDebugUnitTest --rerun-tasks
rtk ./gradlew :app:assembleDebug
rtk ./gradlew :app:assembleRelease
```

All four must report `BUILD SUCCESSFUL`. R8 minify on release should
produce an APK ~2.7M. Compile warnings about `Theme.kt` deprecated
status/navigation bar setters are pre-existing and not blocking.

## On-device smoke checklist (signed release APK)

Walk the user-facing pipeline end-to-end on a clean install of the
signed release APK. Use a test device, not a daily driver.

### Camera + record
- Cold launch → tap START → preview shows live camera, no unexplained
  black gap (warm-up overlay covers the CameraX bind window).
- Idle flip front/back works; flip during recording is ignored.
- 10 s duration + 1 m interval: end-to-start scheduling — full 60 s
  pause after each recording finishes.
- `interval == 0` (continuous): segments record back-to-back with no
  added beep gap.

### Audio cues
- VIDEO_AUDIO recording with beeps ON, interval > 0 → audible start
  cue, final clip silent of `rova_beep`.
- VIDEO_AUDIO recording with `interval == 0` → no beep, no added
  delay (continuous capture not interrupted).
- VIDEO_ONLY with beeps ON → start + stop cues audible.
- Vibration setting unchanged from preference.

### Quality selector
- Default chip = `FHD`.
- Tap `HD` → chip flips, summary chip in peek shows `HD`.
- Start recording with `HD` selected → output file is HD.
- Quality chips disabled while recording / merging.
- Custom preset save preserves selected quality.

### History — list, share, preview
- Finalized recordings appear in History with thumbnails.
- Single-select share opens system share sheet.
- Multi-select share opens system share sheet.
- Preview screen → tap Share → share sheet opens.
- Shared MP4 opens in at least one receiver app.

### History — delete (gallery + metadata)
- Single-select delete → entry gone from History AND phone gallery
  (Photos / Files app) AND `Android/data/com.aritr.rova/files/videos/<sid>/`
  is gone.
- Multi-select delete → all three layers cleared for every selected
  entry.
- Failure path: "Could not delete N recording(s)" snackbar appears
  if any artifact delete fails.

### Recovery cards
- Force-stop mid-session → cold launch → exactly one red recovery
  card on top of History.
- Older interrupted sessions render as "+N older interrupted
  sessions" footer, not stacked red cards.
- Tap Discard on the visible card → card disappears immediately,
  next-newest takes its place if any.
- KILLED_BY_SYSTEM card surfaces "Open device settings" vendor
  guidance button.

## Keystore backup reminder

Before distributing the APK:

- [ ] `rova-beta-release.jks` backed up to two safe locations
  (loss = permanent Play Store signature lockout).
- [ ] `keystore.properties` backed up to the same two locations.
- [ ] Signing fingerprint recorded for future Play Console upload-key
  registration:
  - SHA-256: `cc61f156a4b373e922aab9dd9ca4b072f2aeef3a6cd649da4e8241f9d258de34`
- [ ] Confirm keystore + properties files are NOT staged in git
  (`*.jks`, `*.keystore`, `keystore.properties` are gitignored).

Never commit, never paste passwords into chat, never publish the
fingerprint until the upload key is registered.

## Push / no-push convention

- Code changes default to local commits only. Push only after explicit
  Push GO from the user.
- After push, confirm sync:
  ```
  rtk git status --short --branch
  rtk git rev-list --left-right --count origin/master...master
  ```
  Expect `0 0`.
- Distribution of the signed APK is independent of the git push and
  follows its own GO step.

## `versionCode` reminder

- Play / internal testing tracks reject any upload whose `versionCode`
  is less than or equal to a previously uploaded version. `versionCode`
  is monotonic, never reused.
- Bump `versionCode` in [app/build.gradle.kts](../app/build.gradle.kts)
  every time a new APK is uploaded to a tester-facing track.
- Keep `versionName` (currently `0.3.0`) tracking the human-meaningful
  release; bump it on user-facing milestones, not on every internal
  iteration.

## Status legend

When updating this file for the next cut:

- Replace `Current cut` table values.
- Re-run `Required verification gates` and confirm all four green.
- Walk the `On-device smoke checklist` against the new APK.
- Re-tick `Keystore backup reminder` if anything moved.

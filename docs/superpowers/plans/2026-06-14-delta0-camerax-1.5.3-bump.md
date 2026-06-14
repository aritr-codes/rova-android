# δ0 — CameraX 1.4.2 → 1.5.3 Bump Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land a mergeable, regression-clean bump of CameraX from 1.4.2 to 1.5.3 on master — the dependency prerequisite for the (deferred) DualSight feature, decoupled from it.

**Architecture:** A single dependency-version change in `gradle/libs.versions.toml` (the `camerax` ref drives all five `androidx.camera:*` libraries). No source changes are expected — the δ-probe branch already proved the bump compiles clean against the existing Single + DualShot capture code with zero API breaks. The risk is *runtime* behavior drift across five minor versions (1.4.2→1.5.0→1.5.1→1.5.2→1.5.3), so the load-bearing work is **regression verification**: the full static-gate suite, all JVM unit tests, and a real-device smoke of every existing capture path (emulators fail CameraX recording).

**Tech Stack:** Kotlin, CameraX 1.5.3 (camera-core/camera2/lifecycle/view/video), AGP 9.2.1, Gradle 9.4.1, Windows + PowerShell (`gradlew.bat`), adb via PowerShell direct (MCP wrapper broken on Windows).

---

## Context the engineer needs

- **Why now, why standalone:** DualSight (PR-δ) needs CameraX ≥1.5.0 for `CompositionSettings`. The probe (`docs/superpowers/specs/2026-06-14-dualsight-probe-results.md`) confirmed DualSight cannot run on the available device and is deferred — but the bump itself is independently useful and low-risk, so it ships on its own. This keeps the bump's regression surface separate from the (later, hardware-gated) feature.
- **Why 1.5.3 specifically:** latest stable. 1.5.0 added `CompositionSettings`; 1.5.1 made concurrent `VideoCapture` robust; 1.5.2 fixed an Android-17 dynamic-range crash (relevant at `targetSdk 37`); 1.5.3 is the current patch. The δ-probe used 1.5.3 and built clean.
- **What "clean compile" was already shown:** on branch `probe/dualsight-concurrent-camera`, `:app:assembleDebug` succeeded with no API-break errors and packaged a new native lib (`libsurface_util_jni.so`). That branch is throwaway; this plan re-does the bump properly off master.
- **Media3 stays pinned at 1.4.1** — unrelated to CameraX, do not touch (CLAUDE.md: 1.5.x deliberately not adopted).
- **The static-gate suite is load-bearing** — 41 `check*` tasks wired into `preBuild`. `lintDebug` runs all of them. None of them touch CameraX APIs, so none should newly fail; if one does, it is a real regression, not a gate to edit.
- **Device:** RZCYA1VBQ2H (Samsung Galaxy A-class, Android 16). It runs all *existing* Rova capture modes (Single portrait/landscape, DualShot P+L) — only DualSight is unsupported. So it is a valid regression-smoke device for δ0.
- **Build is WARM** — `gradlew.bat ...` directly, no `--stop`/cache wipe (the recovery dance is obsolete; `kotlin-postedit.ps1` hook disabled 2026-06-09).

---

## File Structure

- **Modify (the only source change):** `gradle/libs.versions.toml` — `camerax` version line `1.4.2` → `1.5.3`.
- **Possibly modify (only if the release-notes scan or build surfaces a real break):** capture-path Kotlin under `service/` (`RovaRecordingService.kt`, `service/dualrecord/**`) and/or `ui/screens/player/**`. None expected. Any change here must be the minimal adaptation to a documented API change, recorded in the commit and the PR body as δ0's regression surface.

---

## Task 1: Branch + bump the version

**Files:**
- Modify: `gradle/libs.versions.toml` (the `camerax` version line)

- [ ] **Step 1: Branch off master**

```bash
cd "g:/Books/Python/ACTUAL CODES/PROJECTS/rova-android"
git checkout master
git pull --ff-only
git checkout -b chore/camerax-1.5.3-bump
```

- [ ] **Step 2: Confirm the current pin**

Run: `grep -n "camerax" gradle/libs.versions.toml`
Expected: `camerax = "1.4.2"` plus the five `androidx-camera-*` lines using `version.ref = "camerax"`.

- [ ] **Step 3: Bump to 1.5.3**

Edit `gradle/libs.versions.toml`: change `camerax = "1.4.2"` to `camerax = "1.5.3"`. Change nothing else (do NOT touch `media3`).

- [ ] **Step 4: Commit the bump alone (so any later source fixes are a separate, reviewable commit)**

```bash
git add gradle/libs.versions.toml
git commit -m "chore(deps): bump CameraX 1.4.2 -> 1.5.3"
```

---

## Task 2: Release-notes breaking-change scan

A doc review *before* trusting the build, so we know what runtime behavior to look for in Task 4. Output is a written list, not code.

**Files:** none (research → notes appended to the PR body later).

- [ ] **Step 1: Read the CameraX release notes for 1.5.0, 1.5.1, 1.5.2, 1.5.3**

Source: https://developer.android.com/jetpack/androidx/releases/camera — read the "Version 1.5.x" sections. For each version, note any entry under **"Behavior changes"**, **"Breaking changes"**, or bug-fixes that touch: `VideoCapture` / `Recorder`, `Preview` / `SurfaceProvider`, `PreviewView`, target rotation, `CameraEffect` (DualShot uses `CameraEffect(target=PREVIEW)`), `UseCaseGroup`, mirror mode, or `ProcessCameraProvider` binding.

  **Known 1.4.2→1.5.3 changes to confirm (from cross-model review — verify each against the notes):**
  - **Floors:** 1.5.0-rc01 raised default `minSdk` 21→23 and CameraX needs `compileSdk` ≥35. Rova is `minSdk 24` / `compileSdk 37` → satisfied; confirm and move on (not a blocker, but record it).
  - **⚠ Resolution selection (the dangerous silent-output one):** 1.5.1 fixed cases where, under internal `StreamSharing`, `Preview` missed 16:9 and `VideoCapture` missed `QUALITY_1080P`. This can **silently change the recorded resolution** with no compile error. → mandates the baseline-vs-bumped file-metadata diff in Task 4.
  - **Target rotation on recreate:** 1.5.1 fixed recreated `ImageCapture`/`VideoCapture` losing target rotation. Rova **recreates the VideoCapture at every segment boundary** → explicitly smoke rotation *across a segment rollover*, not just at start.
  - **Mirror:** `VideoCapture` default stays `MIRROR_MODE_OFF`; 1.5.1 changed mirror behavior *only under concurrent composition* (irrelevant to Rova's non-concurrent paths) — but DualShot uses a `CameraEffect`, so verify DualShot output mirror/orientation is unchanged.
  - **CameraEffect / SurfaceProcessor:** multiple 1.5.x fixes (crash after `SurfaceProcessor` shutdown, OpenGL red tint on UHD, leaks with PreviewView+effects+4 use cases). DualShot's EGL fan-out depends on this path → smoke repeated start/stop + background/foreground + check for tint/black/stretched secondary output.
  - **Recorder:** 1.5.x adds an insufficient-storage finalize error + initial-muted API (additive; note but no action).

- [ ] **Step 2: Cross-check against Rova's CameraX touchpoints**

Run: `grep -rn "androidx.camera" app/src/main/java | grep import | sort -u`
For each import that appears in a behavior-change note from Step 1, mark the file for explicit attention in the Task 4 device smoke. Write the list (e.g. "1.5.x changed VideoCapture default mirror → verify front-camera Single recording not flipped"). If nothing relevant changed, write "no behavior-change entries intersect Rova's CameraX usage" — that is a valid, useful result.

---

## Task 3: Build + static gates + unit tests (the automated regression wall)

**Files:** none (verification; only edit source if a real break appears).

- [ ] **Step 1: Assemble debug**

Run: `gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL. If a *compile* error appears (none expected — the probe proved clean), it is a documented API change: apply the minimal fix in the affected capture file, then `git add` + commit as `"fix(camerax): adapt <file> to 1.5.x <api> change"`.

- [ ] **Step 2: Run the full JVM unit-test suite**

Run: `gradlew.bat :app:testDebugUnitTest`
Expected: all tests pass (baseline 1241 / 0-0-0 on master; the bump must not regress any). CameraX is not exercised under JVM tests (`isReturnDefaultValues=true`), so this should be unaffected — a failure here means something other than CameraX moved and must be investigated.

- [ ] **Step 3: Run lint + every custom gate**

Run: `gradlew.bat :app:lintDebug`
Expected: BUILD SUCCESSFUL with all 41 `check*` gates green. None reference CameraX APIs, so none should newly fail. A new failure is a real regression — fix the source, never the gate.
> Known pre-existing caveat (from project memory): if `lintDebug` reports a `VaultAndroidOps` NewApi finding unrelated to this change, confirm it is pre-existing on master (`git stash` + `lintDebug` on master) before treating it as δ0's problem.

---

## Task 4: On-device regression smoke (the load-bearing manual gate)

Emulators fail CameraX recording, so this is mandatory and human-run on RZCYA1VBQ2H. The bump is not mergeable until this passes.

**Files:** none (device run + a smoke note).

- [ ] **Step 0: Capture the 1.4.2 BASELINE first (before installing the bumped build)**

The silent-resolution/bitrate/rotation risks can only be caught by comparison. From `master` (1.4.2), build + install, record one Single-portrait and one DualShot clip, pull them, and capture metadata:

```bash
git stash; git checkout master
gradlew.bat :app:assembleDebug
adb -s RZCYA1VBQ2H install -r app/build/outputs/apk/debug/app-debug.apk
# record a Single-portrait clip + a DualShot clip in-app, then pull the merged outputs:
adb -s RZCYA1VBQ2H pull /sdcard/Movies/<single_clip>.mp4 baseline_single_142.mp4
adb -s RZCYA1VBQ2H pull /sdcard/Movies/<dualshot_clip>.mp4 baseline_dualshot_142.mp4
ffprobe -v error -show_streams baseline_single_142.mp4   # note: width,height,r_frame_rate,bit_rate,rotation/displaymatrix,duration
ffprobe -v error -show_streams baseline_dualshot_142.mp4
git checkout chore/camerax-1.5.3-bump
```
Record the baseline numbers (resolution, fps, bitrate, rotation matrix, duration, codec) in the smoke note. (If `ffprobe` is unavailable, use `MediaMetadataRetriever` values surfaced in-app or `adb shell` `mediainfo`.)

- [ ] **Step 1: Install the bumped build**

```bash
gradlew.bat :app:assembleDebug
adb -s RZCYA1VBQ2H install -r app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 2: Run the regression checklist** (each must behave exactly as on 1.4.2). Watch logcat for CameraX warnings: `adb -s RZCYA1VBQ2H logcat -s CameraX:W Camera2CameraImpl:W *:E`

  - [ ] **Single — Portrait:** record a multi-segment session; verify preview is upright, segments roll at the boundary, the merged clip plays back correct orientation/aspect, audio present.
  - [ ] **Single — Auto/landscape + segment rollover:** rotate the device while idle, start recording in landscape; verify the recorded file orientation matches the preview (ADR-0029 PR-α `effectiveTargetRotation` path). **Let it cross at least one segment boundary** and confirm rotation is still correct in the merged output — 1.5.1's "recreated VideoCapture loses target rotation" fix means the per-segment rebind is the highest-risk rotation site.
  - [ ] **Recorded-file metadata diff vs baseline:** `ffprobe` the bumped Single + DualShot clips and compare width/height/fps/bitrate/rotation/duration against the Step-0 baseline. **Any change in resolution or rotation is a regression** (the StreamSharing `QUALITY_1080P` selection fix can silently alter recorded resolution) — investigate before merging, even though playback "looks fine".
  - [ ] **Per-segment metadata (not only the merged file):** the MediaMuxer concat can normalize/mask a per-segment rotation or resolution defect. `ffprobe` at least the raw pre-merge segments of one multi-segment session and confirm each matches the baseline — a merged file that looks right can hide a bad segment.
  - [ ] **A/V sync across rollover:** confirm audio stays in sync and continuous across at least two segment boundaries in the merged output (the audio-state-`IDLING` fix is in this path; verify no drift/gap introduced).
  - [ ] **Long-run DualShot memory smoke:** run a DualShot session through several segments + background/foreground cycles; watch for growth (`adb shell dumpsys meminfo com.aritr.rova`). One of the cited 1.5.x fixes is a CameraEffect leak — confirm the bump doesn't regress long-run memory for the effect path.
  - [ ] **(Optional, if a second unit is available) one low/mid OEM device** in addition to RZCYA1VBQ2H — CameraX behavior changes often surface device-specifically.
  - [ ] **Single — Front camera:** flip to front, record; verify the front clip is mirrored/un-mirrored exactly as before (1.5.x is the version family that touched mirror defaults — check explicitly).
  - [ ] **DualShot (P+L) — stress the effect path:** record a P+L session; verify both portrait and landscape outputs, the 4:3-source side-crops, and that the `CameraEffect(target=PREVIEW)` EGL fan-out produces two valid muxed files. **Repeat start/stop several times + background/foreground the app mid-session** and check for tint (1.5.x UHD red-tint fix), black/stretched secondary output, or crash-after-`SurfaceProcessor`-shutdown — the 1.5.x effect/SurfaceProcessor fixes are exactly this path.
  - [ ] **Segment boundary + STOP:** let a session hit loop-count exhaustion (STOP path) and also user-stop mid-segment; verify the merged output and manifest `COMPLETED`/`USER_STOPPED` are correct.
  - [ ] **Export:** export a completed session to the gallery (Tier 1 on this API level); verify the file appears and plays.
  - [ ] **Recovery:** force-stop the app mid-recording, relaunch; verify the recovery scan classifies + the recovery card behaves as before.

- [ ] **Step 3: Record the result** in `docs/superpowers/specs/2026-06-14-delta0-bump-smoke.md` — one line per checklist item (PASS/FAIL + any logcat warning text). Any FAIL blocks the PR and becomes a fix commit (then re-smoke).

- [ ] **Step 4: Commit the smoke note**

```bash
git add docs/superpowers/specs/2026-06-14-delta0-bump-smoke.md
git commit -m "docs(camerax): δ0 1.5.3 bump device regression smoke on RZCYA1VBQ2H"
```

---

## Task 5: Open the PR

**Files:** none.

- [ ] **Step 1: Push + open PR**

```bash
git push -u origin chore/camerax-1.5.3-bump
gh pr create --base master --title "chore(deps): bump CameraX 1.4.2 -> 1.5.3" --body "<body below>"
```

- [ ] **Step 2: PR body must include:**
  - One-line summary: dependency bump only, prerequisite for the deferred DualSight feature.
  - The Task 2 release-notes scan result (what behavior changed / "nothing intersects Rova").
  - The Task 4 smoke checklist results (device, each item PASS).
  - Any source-adaptation commits and why (expected: none).
  - Link to `docs/superpowers/specs/2026-06-14-dualsight-probe-results.md` for the why-now context.

- [ ] **Step 3: Await owner review + merge.** Do not self-merge (house convention: commit/push/PR only when asked; owner drives merge).

---

## Self-Review

- **Spec coverage:** Implements spec §3 "Phase δ0" (the mergeable bump + full regression smoke). The bump target (1.5.3) and the regression surface (Single portrait/landscape/front, DualShot, segment/STOP, export, recovery) match the spec's δ0 sketch and the probe's clean-compile finding.
- **Placeholder scan:** No "TBD/add error handling/similar-to". The one open-ended item — the release-notes scan (Task 2) and the "fix only if a real break appears" (Task 3 Step 1) — is bounded by an explicit "none expected; the probe proved clean" and a precise commit-message contract, which is correct for a dependency bump (you cannot pre-write a fix for a break that the evidence says does not exist).
- **Type consistency:** No new types. The only change is a version string; all CameraX symbol usage is unchanged from master.

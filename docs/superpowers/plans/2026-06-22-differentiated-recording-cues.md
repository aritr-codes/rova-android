# Differentiated Recording Cues Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the recording cues meaning: a full multi-pulse **start cue** on the first segment, a short **reminder** on subsequent segment starts, and **no end cue**.

**Architecture:** Restore the pre-#121 long cue asset as a second raw resource (`rova_cue_start.mp3`); `beepStart` branches the asset on `isFirstSegment`; the existing short `rova_beep.mp3` (~1.07s) becomes the reminder; `beepEnd` is deleted entirely. The bleed-safe await (`beepPlaybackCeilingMs` from `mp.duration`) already auto-adapts to the longer asset, so no timing math changes.

**Tech Stack:** Kotlin, Android `MediaPlayer`, coroutines (`withContext(Main.immediate)` + `withTimeoutOrNull`), JVM unit tests (`BeepTimingTest`). Spec source: `docs/BACKLOG.md` → **## Audio cues → "Differentiated recording cues"** (owner-clarified + asset/code-verified 2026-06-20).

## Global Constraints

- Branch `feat/audio-differentiated-cues` off **latest master** (a service/audio-only stream, disjoint from the icon/player work). Create via a git worktree.
- **46 static gates + full `:app:testDebugUnitTest` green at EVERY commit.** Never edit a `check*` to pass — fix the source. Relevant gate: `checkAudioModeFgsTypeMatch` (unaffected — no FGS-type change here).
- **No new user-facing strings** (cues are audio assets, not UI text). `record_flash_cd`-style resources are untouched.
- **EDIT-only subagents**; the controller runs ALL gradle/tests/commits/smoke. Build **WARM**. Copy the gitignored `local.properties` into the worktree before the first build.
- **codex** peer review for the `beepStart`/`beepEnd` changes (service behavior).
- Device smoke on **RZCYA1VBQ2H** (Android 14) is MANDATORY — emulators fail CameraX video; the cue-bleed regression is only observable on a real device. Smoke BOTH single and DualShot. **Push/PR/merge only on explicit owner GO.**
- Verified facts (do not re-derive):
  - Trim commit = `d4ef5b5` (#121). The long pre-#121 cue lives at `d4ef5b5^`.
  - That asset's git blob = sha **`14efedcd3c61d92dab46e42d019914b404ac7995`**, content size **112849 bytes** (confirmed via `git cat-file -s` and `git cat-file blob … | wc -c`). It is a ~3.5s, 4-pulse cue.
  - Current `app/src/main/res/raw/rova_beep.mp3` = 34316 bytes (~1.07s) — this stays as the reminder.
  - In the segment loop, `beepStart` runs inside the `retry@ for (attempt in 1..3)` block; `segmentCount` is pre-increment there (incremented at ~line 1530 after a Success), so **`isFirstSegment = segmentCount == 0`** (true for the first segment across all its retry attempts — acceptable; retries are rare).

---

### Task 1: Recover the long start-cue asset (`rova_cue_start.mp3`)

**Files:**
- Create: `app/src/main/res/raw/rova_cue_start.mp3` (recovered binary, exactly 112849 bytes)

**Interfaces:**
- Produces: `R.raw.rova_cue_start` (consumed by Task 2).

- [ ] **Step 1: Recover the blob BINARY-SAFE**

The blob must be written byte-exact. **Do NOT use PowerShell `>` redirection or any text-mode redirect — it re-encodes binary (observed: doubles the file to ~224 KB).** Use a binary-safe extraction:

```bash
git cat-file blob 14efedcd3c61d92dab46e42d019914b404ac7995 > app/src/main/res/raw/rova_cue_start.mp3
```
(run in git-bash, or `git show 'd4ef5b5^:app/src/main/res/raw/rova_beep.mp3'` piped binary-safe.)

- [ ] **Step 2: Verify byte-exactness**

Run: `git cat-file blob 14efedcd3c61d92dab46e42d019914b404ac7995 | wc -c` and `wc -c < app/src/main/res/raw/rova_cue_start.mp3`
Expected: BOTH print `112849`. If the new file is not exactly 112849 bytes, the redirection corrupted it — redo Step 1 with a binary-safe method.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/res/raw/rova_cue_start.mp3
git commit -m "feat(audio): restore pre-#121 long multi-pulse cue as rova_cue_start"
```

---

### Task 2: `beepStart` branches the asset on first segment

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt` (`beepStart` ~4362–4399; call site ~1473; Q3 comment block ~4333–4357)
- Modify: `app/src/main/java/com/aritr/rova/service/audio/BeepTiming.kt` (stale KDoc)

**Interfaces:**
- Consumes: `R.raw.rova_cue_start` (Task 1), `R.raw.rova_beep` (existing), `segmentCount` (service field).
- Produces: `beepStart(intervalMinutes: Int, isFirstSegment: Boolean)`.

- [ ] **Step 1: Add `isFirstSegment` + branch the asset in `beepStart`**

Change the signature and the `MediaPlayer.create` asset. Replace:

```kotlin
    private suspend fun beepStart(intervalMinutes: Int) {
        if (!com.aritr.rova.service.audio.shouldPlayBeep(
                enableBeeps = RovaSettings(this).enableBeeps,
                audioMode = currentAudioMode,
                intervalMinutes = intervalMinutes
            )
        ) return
        withContext(Dispatchers.Main.immediate) {
            val mp = try {
                MediaPlayer.create(this@RovaRecordingService, R.raw.rova_beep) ?: return@withContext
```

with:

```kotlin
    private suspend fun beepStart(intervalMinutes: Int, isFirstSegment: Boolean) {
        if (!com.aritr.rova.service.audio.shouldPlayBeep(
                enableBeeps = RovaSettings(this).enableBeeps,
                audioMode = currentAudioMode,
                intervalMinutes = intervalMinutes
            )
        ) return
        // Differentiated cues: the FIRST segment plays the full multi-pulse start cue
        // (rova_cue_start, ~3.5s — once per recording, acceptable pre-roll); every later
        // segment start plays the short reminder (rova_beep, ~1s). The bleed-safe await
        // below derives its ceiling from the actual MediaPlayer.duration, so it adapts to
        // whichever asset plays — no per-asset timing constant.
        val cueAsset = if (isFirstSegment) R.raw.rova_cue_start else R.raw.rova_beep
        withContext(Dispatchers.Main.immediate) {
            val mp = try {
                MediaPlayer.create(this@RovaRecordingService, cueAsset) ?: return@withContext
```

(The `RovaLog.w` clamp message at ~4383 references `R.raw.rova_beep`; update that literal to `the start cue asset` so it isn't misleading for the long asset.)

- [ ] **Step 2: Update the call site to pass `isFirstSegment`**

At ~line 1473, change:

```kotlin
                        beepStart(mMinutes.toInt()) // Q3: pre-roll cue, awaited to completion
```

to:

```kotlin
                        beepStart(mMinutes.toInt(), isFirstSegment = segmentCount == 0) // pre-roll cue, awaited to completion
```

- [ ] **Step 3: Refresh the Q3 comment block + BeepTiming KDoc**

The service comment at ~4333–4357 and `BeepTiming.kt`'s KDoc (~3–18) both describe the asset as a single "~3527 ms, 4-pulse" `rova_beep`. That is now stale on two counts: (a) `rova_beep` is the SHORT (~1.07s) reminder, and (b) the long multi-pulse cue is `rova_cue_start`, played only on the first segment. Rewrite both to describe the two-asset scheme accurately: the ceiling is still derived from `mp.duration` so it adapts to whichever asset plays; the long start cue is a ~3.7s first-segment-only pre-roll. Keep the cue-bleed history (it is the reason the `mp.duration`-derived ceiling exists), but correct the asset facts.

- [ ] **Step 4: Build + gates + JVM (controller)**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; 46 gates pass; `BeepTimingTest` green (its ceiling math is asset-agnostic, so it still passes with the longer asset).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt app/src/main/java/com/aritr/rova/service/audio/BeepTiming.kt
git commit -m "feat(audio): first-segment start cue vs short reminder via beepStart(isFirstSegment)"
```

---

### Task 3: Remove the end cue (`beepEnd`)

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt` (delete the `beepEnd` call ~1476 and the `beepEnd` fn ~4401–4417)

**Interfaces:**
- Consumes: nothing new.
- Produces: nothing.

- [ ] **Step 1: Delete the `beepEnd` call**

At ~line 1476, remove the whole line:

```kotlin
                        beepEnd(mMinutes.toInt()) // Q3: beep on recording stop
```

(Leave the surrounding `recordSegment()` / `isRecording = false` lines intact.)

- [ ] **Step 2: Delete the `beepEnd` function**

Remove the entire `private fun beepEnd(intervalMinutes: Int) { … }` (~4401–4417). After removal, confirm `beepEnd` has zero references anywhere in the module (grep `beepEnd` → no hits).

- [ ] **Step 3: Build + gates + JVM (controller)**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; no unresolved-reference to `beepEnd`; gates + JVM green.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt
git commit -m "feat(audio): remove redundant per-segment end cue (beepEnd)"
```

---

### Task 4: Device smoke (controller + owner)

**No code.** Install the debug APK on RZCYA1VBQ2H and verify on a real recording (both single mode and DualShot, with audio enabled and a non-zero interval so cues play):

- [ ] First segment plays the **long multi-pulse** start cue.
- [ ] Subsequent segment starts play the **short reminder**.
- [ ] **No end cue** fires when a segment finishes.
- [ ] **No cue bleed** into any recorded segment's audio track (single + DualShot) — the long first cue must fully finish before the mic opens (the `mp.duration`-derived ceiling handles this; confirm no `beepStart: cue duration … exceeds … ceiling` warning in logcat, which would mean the asset exceeds `BEEP_CEILING_MAX_MS = 10_000` — it will not at ~3.5s).
- [ ] Owner confirms the cues sound right and distinct.

---

## Out of scope / future

- **Distinct reminder *sound*** — the backlog notes the reminder should ideally be a different *tone* (not just shorter) for recognizability. The current `rova_beep` (short, single) is already distinguishable from the long multi-pulse start cue; authoring a bespoke reminder tone is a separate audio-asset task, not code, and is deferred.
- **`shouldPlayBeep` / continuous mode** unchanged: `intervalMinutes == 0` + `VIDEO_AUDIO` still suppresses cues (no gap to hide a synchronous pre-roll). `BeepPolicy.kt` is untouched.

## Execution notes

- Tasks 2 and 3 both edit `RovaRecordingService.kt` (disjoint regions) — sequence them. Task 1 (asset) is independent and can go first.
- Service/audio behavior is runtime — the JVM suite covers only the ceiling math (`BeepTimingTest`); the cue identity + bleed are device-verified (Task 4). This is intentional and matches the cue-bleed fix (#121), which was also device-verified.

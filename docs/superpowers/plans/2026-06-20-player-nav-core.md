# Player Navigation Core (PR-6) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the player's segmented timeline interactive (tap-to-seek + throttled drag-scrub on a continuous duration-proportional bar with segment ticks), add segment prev/next + resume-from-position, with full a11y — no wall-clock (split to PR-6b).

**Architecture:** Pure-Kotlin helpers (`SegmentedTimelineMath` extensions, `ResumePolicy`) carry all seek/segment/resume math and are JVM-unit-tested. The `PlayerViewModel` seam gains absolute `seekTo` + a throttled scrub session + resume load/save against `LibraryMetadataStore`. The `SegmentedTimeline` composable becomes interactive (`pointerInput` tap/drag) with one adjustable seekbar a11y node. Compose surfaces are verified by device smoke (JVM-only test policy); all logic lives in the tested pure helpers.

**Tech Stack:** Kotlin, Jetpack Compose, Media3 ExoPlayer **pinned 1.4.1**, JUnit (JVM, `isReturnDefaultValues = true`), `org.json` on `testImplementation`.

## Global Constraints

- **Media3 pinned 1.4.1** — no scrubbing mode, no MediaSession, no version bump.
- **JVM unit tests only** (`isReturnDefaultValues = true`); framework-touching code gets a pure-Kotlin sibling. A feature lands its tests in the same PR.
- **All 46 `check*` gates + JVM tests green at every commit.** Never edit a `check*` to pass.
- **New user-facing strings in `values/strings.xml` AND `values-es/strings.xml`** (ADR-0022, `checkNoHardcodedUiStrings`).
- **WCAG 2.2 AA** (ADR-0020): timeline = one adjustable seekbar node; reduced-motion gated animation (`checkA11yAnimationGated`); ≥24dp touch (`checkA11yTargetSizeToken`).
- **Subagents edit-only; the controller runs all gradle/tests/commits/smoke.** Build WARM.
- **Player is FLAG_SECURE** → `adb screencap` is black; owner verifies UI visually on RZCYA1VBQ2H.
- **No wall-clock playhead** (manifest lacks per-segment wall-start — PR-6b). **No** speed/double-tap/auto-hide (PR-7). **No** thumbnails/live-scrub-mode (Media3 1.8.0+).

---

### Task 1: `SegmentedTimelineMath` seek/segment extensions (pure)

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/player/SegmentedTimelineMath.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/screens/player/SegmentedTimelineMathTest.kt`

**Interfaces:**
- Consumes: existing `compute(segmentDurationsMs: List<Long>, positionMs: Long): TimelineState`.
- Produces (all pure, in `object SegmentedTimelineMath`):
  - `fun totalDurationMs(durations: List<Long>): Long`
  - `fun fractionFromPosition(positionMs: Long, durations: List<Long>): Float`  // 0f..1f
  - `fun positionFromFraction(fraction: Float, durations: List<Long>): Long`    // clamped
  - `fun segmentAtPosition(positionMs: Long, durations: List<Long>): Int`       // 0-based, clamped
  - `fun segmentStartMs(index: Int, durations: List<Long>): Long`               // cumulative
  - `fun nextSegmentStart(positionMs: Long, durations: List<Long>): Long?`      // null at end
  - `fun prevSegmentStart(positionMs: Long, durations: List<Long>, restartCurrentAfterMs: Long = 750L): Long?`
  - `fun snapIfNear(positionMs: Long, durations: List<Long>, snapWindowMs: Long): Long`
  - `fun cellWeights(durations: List<Long>): List<Float>`                       // duration-proportional, sums ~1f

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.aritr.rova.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SegmentedTimelineMathTest {
    private val segs = listOf(1000L, 2000L, 1000L) // total 4000, starts 0/1000/3000

    @Test fun totalDuration_sums() {
        assertEquals(4000L, SegmentedTimelineMath.totalDurationMs(segs))
        assertEquals(0L, SegmentedTimelineMath.totalDurationMs(emptyList()))
    }

    @Test fun fraction_position_roundTrip() {
        assertEquals(0.5f, SegmentedTimelineMath.fractionFromPosition(2000L, segs), 0.0001f)
        assertEquals(2000L, SegmentedTimelineMath.positionFromFraction(0.5f, segs))
        // clamp out of range
        assertEquals(0f, SegmentedTimelineMath.fractionFromPosition(-50L, segs), 0f)
        assertEquals(4000L, SegmentedTimelineMath.positionFromFraction(2f, segs))
        assertEquals(0L, SegmentedTimelineMath.positionFromFraction(-1f, segs))
    }

    @Test fun fraction_emptyOrZero_isSafe() {
        assertEquals(0f, SegmentedTimelineMath.fractionFromPosition(100L, emptyList()), 0f)
        assertEquals(0L, SegmentedTimelineMath.positionFromFraction(0.5f, emptyList()))
        assertEquals(0f, SegmentedTimelineMath.fractionFromPosition(0L, listOf(0L, 0L)), 0f)
    }

    @Test fun segmentAtPosition_andStart() {
        assertEquals(0, SegmentedTimelineMath.segmentAtPosition(0L, segs))
        assertEquals(0, SegmentedTimelineMath.segmentAtPosition(999L, segs))
        assertEquals(1, SegmentedTimelineMath.segmentAtPosition(1000L, segs))
        assertEquals(2, SegmentedTimelineMath.segmentAtPosition(3500L, segs))
        assertEquals(2, SegmentedTimelineMath.segmentAtPosition(99999L, segs)) // clamp to last
        assertEquals(0L, SegmentedTimelineMath.segmentStartMs(0, segs))
        assertEquals(1000L, SegmentedTimelineMath.segmentStartMs(1, segs))
        assertEquals(3000L, SegmentedTimelineMath.segmentStartMs(2, segs))
    }

    @Test fun nextSegmentStart_advancesThenNull() {
        assertEquals(1000L, SegmentedTimelineMath.nextSegmentStart(500L, segs))
        assertEquals(3000L, SegmentedTimelineMath.nextSegmentStart(1500L, segs))
        assertEquals(null, SegmentedTimelineMath.nextSegmentStart(3500L, segs)) // already in last
    }

    @Test fun prevSegmentStart_restartsCurrentThenJumps() {
        // >750ms into segment 1 (starts 1000) -> restart segment 1 start
        assertEquals(1000L, SegmentedTimelineMath.prevSegmentStart(1900L, segs))
        // <=750ms into segment 1 -> jump to segment 0 start
        assertEquals(0L, SegmentedTimelineMath.prevSegmentStart(1200L, segs))
        // at the very start -> null
        assertEquals(null, SegmentedTimelineMath.prevSegmentStart(0L, segs))
    }

    @Test fun snapIfNear_snapsWithinWindowOnly() {
        assertEquals(1000L, SegmentedTimelineMath.snapIfNear(1080L, segs, 200L)) // near boundary 1000
        assertEquals(1500L, SegmentedTimelineMath.snapIfNear(1500L, segs, 200L)) // far -> identity
    }

    @Test fun cellWeights_areProportional() {
        val w = SegmentedTimelineMath.cellWeights(segs)
        assertEquals(0.25f, w[0], 0.0001f)
        assertEquals(0.50f, w[1], 0.0001f)
        assertEquals(0.25f, w[2], 0.0001f)
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.player.SegmentedTimelineMathTest"`
Expected: FAIL (unresolved references to the new functions).

- [ ] **Step 3: Implement the extensions**

Append to `object SegmentedTimelineMath`:

```kotlin
fun totalDurationMs(durations: List<Long>): Long = durations.sumOf { it.coerceAtLeast(0L) }

fun fractionFromPosition(positionMs: Long, durations: List<Long>): Float {
    val total = totalDurationMs(durations)
    if (total <= 0L) return 0f
    return (positionMs.toFloat() / total.toFloat()).coerceIn(0f, 1f)
}

fun positionFromFraction(fraction: Float, durations: List<Long>): Long {
    val total = totalDurationMs(durations)
    if (total <= 0L) return 0L
    return (fraction.coerceIn(0f, 1f) * total).toLong().coerceIn(0L, total)
}

fun segmentStartMs(index: Int, durations: List<Long>): Long {
    var acc = 0L
    for (i in 0 until index.coerceIn(0, durations.size)) acc += durations[i].coerceAtLeast(0L)
    return acc
}

fun segmentAtPosition(positionMs: Long, durations: List<Long>): Int {
    if (durations.isEmpty()) return 0
    val clamped = positionMs.coerceIn(0L, totalDurationMs(durations))
    var acc = 0L
    for ((i, d) in durations.withIndex()) {
        acc += d.coerceAtLeast(0L)
        if (clamped < acc) return i
    }
    return durations.lastIndex
}

fun nextSegmentStart(positionMs: Long, durations: List<Long>): Long? {
    val cur = segmentAtPosition(positionMs, durations)
    if (cur >= durations.lastIndex) return null
    return segmentStartMs(cur + 1, durations)
}

fun prevSegmentStart(positionMs: Long, durations: List<Long>, restartCurrentAfterMs: Long = 750L): Long? {
    if (durations.isEmpty() || positionMs <= 0L) return null
    val cur = segmentAtPosition(positionMs, durations)
    val curStart = segmentStartMs(cur, durations)
    return if (positionMs - curStart > restartCurrentAfterMs) curStart
    else if (cur == 0) null
    else segmentStartMs(cur - 1, durations)
}

fun snapIfNear(positionMs: Long, durations: List<Long>, snapWindowMs: Long): Long {
    var acc = 0L
    val boundaries = ArrayList<Long>(durations.size + 1).apply { add(0L) }
    for (d in durations) { acc += d.coerceAtLeast(0L); boundaries.add(acc) }
    val nearest = boundaries.minByOrNull { kotlin.math.abs(it - positionMs) } ?: return positionMs
    return if (kotlin.math.abs(nearest - positionMs) <= snapWindowMs) nearest else positionMs
}

fun cellWeights(durations: List<Long>): List<Float> {
    val total = totalDurationMs(durations)
    if (total <= 0L) return durations.map { 1f / durations.size.coerceAtLeast(1) }
    return durations.map { it.coerceAtLeast(0L).toFloat() / total.toFloat() }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.player.SegmentedTimelineMathTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/player/SegmentedTimelineMath.kt app/src/test/java/com/aritr/rova/ui/screens/player/SegmentedTimelineMathTest.kt
git commit -m "feat(player): segment/seek math for interactive timeline"
```

---

### Task 2: `ResumePolicy` (pure)

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/screens/player/ResumePolicy.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/screens/player/ResumePolicyTest.kt`

**Interfaces:**
- Produces (`object ResumePolicy`):
  - `fun nearEndThresholdMs(durationMs: Long): Long`  // min(3000, max(1000, dur*0.02))
  - `fun shouldRestartFromStart(positionMs: Long, durationMs: Long): Boolean`
  - `fun resolveOpenPosition(savedMs: Long?, durationMs: Long): Long`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.aritr.rova.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumePolicyTest {
    @Test fun threshold_scalesAndClamps() {
        assertEquals(1000L, ResumePolicy.nearEndThresholdMs(10_000L))   // 2% = 200 -> floor 1000
        assertEquals(3000L, ResumePolicy.nearEndThresholdMs(10_000_000L)) // 2% huge -> ceil 3000
        assertEquals(2000L, ResumePolicy.nearEndThresholdMs(100_000L))  // 2% = 2000
    }

    @Test fun restart_whenWithinThresholdOfEnd() {
        assertTrue(ResumePolicy.shouldRestartFromStart(99_500L, 100_000L))  // remaining 500 <= 2000
        assertFalse(ResumePolicy.shouldRestartFromStart(50_000L, 100_000L))
        assertFalse(ResumePolicy.shouldRestartFromStart(0L, 0L))            // unknown duration -> no restart
    }

    @Test fun resolveOpen_clampsAndRestarts() {
        assertEquals(0L, ResumePolicy.resolveOpenPosition(null, 100_000L))      // no saved -> 0
        assertEquals(50_000L, ResumePolicy.resolveOpenPosition(50_000L, 100_000L))
        assertEquals(100_000L, ResumePolicy.resolveOpenPosition(999_999L, 100_000L)) // clamp to duration
        assertEquals(0L, ResumePolicy.resolveOpenPosition(99_900L, 100_000L))   // near-end -> restart 0
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.player.ResumePolicyTest"`
Expected: FAIL (class not found).

- [ ] **Step 3: Implement**

```kotlin
package com.aritr.rova.ui.screens.player

import kotlin.math.max
import kotlin.math.min

/** Pure resume-position rules (PR-6). Near-end positions restart from 0 instead of parking at the end. */
object ResumePolicy {
    fun nearEndThresholdMs(durationMs: Long): Long {
        if (durationMs <= 0L) return 0L
        return min(3000L, max(1000L, (durationMs * 0.02).toLong()))
    }

    fun shouldRestartFromStart(positionMs: Long, durationMs: Long): Boolean {
        if (durationMs <= 0L) return false
        return (durationMs - positionMs) <= nearEndThresholdMs(durationMs)
    }

    fun resolveOpenPosition(savedMs: Long?, durationMs: Long): Long {
        val saved = savedMs ?: return 0L
        if (durationMs <= 0L) return saved.coerceAtLeast(0L)
        val clamped = saved.coerceIn(0L, durationMs)
        return if (shouldRestartFromStart(clamped, durationMs)) 0L else clamped
    }
}
```

- [ ] **Step 4: Run to verify pass** — `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.player.ResumePolicyTest"` → PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/player/ResumePolicy.kt app/src/test/java/com/aritr/rova/ui/screens/player/ResumePolicyTest.kt
git commit -m "feat(player): ResumePolicy near-end + open-position rules"
```

---

### Task 3: Persist `positionMs` in the library sidecar

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/library/LibraryMetadataEntry.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/library/LibraryMetadataCodec.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/library/LibraryMetadataCodecTest.kt` (extend; create if absent)

**Interfaces:**
- Consumes: existing `LibraryMetadataEntry(favorite, customTitle, lastPlayedAt)`, `LibraryMetadataCodec.toJson/fromJson`.
- Produces: `LibraryMetadataEntry.positionMs: Long?` (default null); codec round-trips it; `isEmpty()` accounts for it.

- [ ] **Step 1: Write the failing test** (add to the codec test)

```kotlin
@Test fun positionMs_roundTripsAndOmitsWhenNull() {
    val withPos = mapOf("rec1" to LibraryMetadataEntry(positionMs = 12_345L))
    val json = LibraryMetadataCodec.toJson(withPos)
    assertEquals(12_345L, LibraryMetadataCodec.fromJson(json)["rec1"]?.positionMs)
    // null position must not be emitted (byte-shape preserved)
    val noPos = LibraryMetadataCodec.toJson(mapOf("rec2" to LibraryMetadataEntry(favorite = true)))
    assertFalse(noPos.contains("positionMs"))
}
```

- [ ] **Step 2: Run to verify failure** — `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.LibraryMetadataCodecTest"` → FAIL.

- [ ] **Step 3: Implement**

`LibraryMetadataEntry.kt` — add the field + isEmpty term:

```kotlin
data class LibraryMetadataEntry(
    val favorite: Boolean = false,
    val customTitle: String? = null,
    val lastPlayedAt: Long? = null,
    val positionMs: Long? = null,
) {
    fun isEmpty(): Boolean = !favorite && customTitle == null && lastPlayedAt == null && positionMs == null
}
```

`LibraryMetadataCodec.kt` — in `toJson` emit only when non-null; in `fromJson` parse tolerantly:

```kotlin
// toJson, inside the per-entry object build:
entry.positionMs?.let { if (it > 0L) obj.put("positionMs", it) }
// fromJson, inside the per-entry parse:
positionMs = if (o.has("positionMs")) o.optLong("positionMs").takeIf { it > 0L } else null,
```

- [ ] **Step 4: Run to verify pass** — same command → PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/LibraryMetadataEntry.kt app/src/main/java/com/aritr/rova/ui/library/LibraryMetadataCodec.kt app/src/test/java/com/aritr/rova/ui/library/LibraryMetadataCodecTest.kt
git commit -m "feat(library): persist player positionMs in metadata sidecar"
```

---

### Task 4: `PlayerViewModel` — absolute seek, throttled scrub session, resume load/save

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/player/PlayerViewModel.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/player/PlayerUiState.kt` (add `isScrubbing` to `PlaybackProgress`)

**Interfaces:**
- Consumes: `seekRelative`, the 250ms poll, `exoPlayer`, `progress: StateFlow<PlaybackProgress>`, injected `LibraryMetadataStore` + the recording id/side, Tasks 1–2 helpers.
- Produces (public on the VM):
  - `fun seekTo(positionMs: Long)`
  - `fun beginScrub()` / `fun updateScrub(targetMs: Long)` / `fun endScrub(finalMs: Long)`
  - `fun jumpPrevSegment()` / `fun jumpNextSegment()`
  - `PlaybackProgress` gains `val isScrubbing: Boolean = false`.

> Compose/Android-touching — verified by build + device smoke (no JVM test; the decisions it calls, `positionFromFraction`/`prev|nextSegmentStart`/`ResolveOpenPosition`, are already unit-tested in Tasks 1–2).

- [ ] **Step 1: Add `isScrubbing` to `PlaybackProgress`**

```kotlin
data class PlaybackProgress(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isPlaying: Boolean = false,
    val isScrubbing: Boolean = false,
)
```

- [ ] **Step 2: Add absolute seek + segment jumps**

```kotlin
fun seekTo(positionMs: Long) {
    val p = exoPlayer ?: return
    val dur = p.duration.takeIf { it > 0L } ?: return
    p.seekTo(positionMs.coerceIn(0L, dur))
    pushProgress()
}

fun jumpNextSegment() {
    SegmentedTimelineMath.nextSegmentStart(progress.value.positionMs, segmentDurationsMs)?.let { seekTo(it) }
}

fun jumpPrevSegment() {
    SegmentedTimelineMath.prevSegmentStart(progress.value.positionMs, segmentDurationsMs)?.let { seekTo(it) }
}
```

(`segmentDurationsMs` is the list already resolved into `PlayerUiState.Ready`; hold a copy on the VM when the manifest loads.)

- [ ] **Step 3: Throttled scrub session** (codex recipe — §5 of the spec)

```kotlin
private var scrubWasPlaying = false
private var lastScrubSeekAt = 0L
private var lastScrubTarget = -1L

fun beginScrub() {
    val p = exoPlayer ?: return
    scrubWasPlaying = p.isPlaying
    p.pause()
    p.setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC)
    _progress.update { it.copy(isScrubbing = true) }
}

fun updateScrub(targetMs: Long) {
    val p = exoPlayer ?: return
    val dur = p.duration.takeIf { it > 0L } ?: return
    val target = targetMs.coerceIn(0L, dur)
    _progress.update { it.copy(positionMs = target) }            // UI playhead follows finger immediately
    val now = android.os.SystemClock.elapsedRealtime()
    val moved = lastScrubTarget < 0 || kotlin.math.abs(target - lastScrubTarget) >= maxOf(500L, dur / 100)
    if (now - lastScrubSeekAt >= 180L && moved) {                 // conflated, latest-only
        p.seekTo(target); lastScrubSeekAt = now; lastScrubTarget = target
    }
}

fun endScrub(finalMs: Long) {
    val p = exoPlayer ?: return
    val dur = p.duration.takeIf { it > 0L } ?: return
    p.setSeekParameters(androidx.media3.exoplayer.SeekParameters.EXACT)
    p.seekTo(finalMs.coerceIn(0L, dur))
    if (scrubWasPlaying) p.play()
    lastScrubTarget = -1L
    _progress.update { it.copy(isScrubbing = false) }
    pushProgress()
}
```

(Use the VM's existing `MutableStateFlow` backing `progress` — rename the read of it consistently; `_progress` here is whatever the file already calls its backing field.)

- [ ] **Step 4: Resume load/save**

- On `attachExoPlayer` (after `prepare`, once duration is known via the player listener `onPlaybackStateChanged == STATE_READY`): `val saved = metadataStore.get(recordingId)?.positionMs; seekTo(ResumePolicy.resolveOpenPosition(saved, exoPlayer.duration))`.
- Save `positionMs` on: lifecycle `ON_STOP`, pause, `onCleared`/dispose, and a ~5–10s periodic tick inside the existing poll loop (write only every Nth poll, e.g. every 24 ticks ≈ 6s). On `STATE_ENDED` write `positionMs = null` (reset). Key per recording id (and per side for P+L).

```kotlin
private fun persistPosition(clear: Boolean = false) {
    val id = recordingId ?: return
    val pos = if (clear) null else progress.value.positionMs.takeIf { it > 0L }
    viewModelScope.launch { metadataStore.update(id) { it.copy(positionMs = pos) } }
}
```

- [ ] **Step 5: Build + commit**

Run: `./gradlew :app:assembleDebug` → confirm `:app:packageDebug` EXECUTED.

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/player/PlayerViewModel.kt app/src/main/java/com/aritr/rova/ui/screens/player/PlayerUiState.kt
git commit -m "feat(player): absolute seek, throttled scrub session, resume load/save"
```

---

### Task 5: `SegmentedTimeline` — interactive continuous bar + ticks + a11y

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/player/SegmentedTimeline.kt`
- Modify: `app/src/main/res/values/strings.xml` + `app/src/main/res/values-es/strings.xml`

**Interfaces:**
- Consumes: Task 1 (`fractionFromPosition`, `positionFromFraction`, `snapIfNear`, `cellWeights`), Task 4 (`onSeek`, scrub callbacks), `progress.isScrubbing`.
- Produces: interactive `SegmentedTimeline(segmentDurationsMs, positionMs, isScrubbing, onSeek, onScrubStart, onScrubUpdate, onScrubEnd, onPrevSegment, onNextSegment, modifier)`.

> Compose — verified by build + device smoke.

- [ ] **Step 1: Add strings** (en then es)

`values/strings.xml`:
```xml
<string name="player_timeline_cd">Playback position</string>
<string name="player_timeline_state">Segment %1$d of %2$d, %3$s</string>
<string name="player_prev_segment_cd">Previous segment</string>
<string name="player_next_segment_cd">Next segment</string>
```
`values-es/strings.xml`:
```xml
<string name="player_timeline_cd">Posición de reproducción</string>
<string name="player_timeline_state">Segmento %1$d de %2$d, %3$s</string>
<string name="player_prev_segment_cd">Segmento anterior</string>
<string name="player_next_segment_cd">Segmento siguiente</string>
```

- [ ] **Step 2: Rework the composable**

- Replace equal-weight cells with a single `Box` (the bar) whose children are duration-proportional via `cellWeights`; draw boundary **ticks** (thin dividers) and a filled portion to `fractionFromPosition(positionMs)`.
- Gestures on the bar `Box`:
  ```kotlin
  Modifier
    .pointerInput(segmentDurationsMs) {
      detectTapGestures { off -> onSeek(SegmentedTimelineMath.positionFromFraction(off.x / size.width, segmentDurationsMs)) }
    }
    .pointerInput(segmentDurationsMs) {
      detectDragGestures(
        onDragStart = { onScrubStart() },
        onDragEnd = { onScrubEnd(SegmentedTimelineMath.positionFromFraction(dragFrac, segmentDurationsMs)) },
        onDragCancel = { onScrubEnd(SegmentedTimelineMath.positionFromFraction(dragFrac, segmentDurationsMs)) },
      ) { change, _ ->
        dragFrac = (change.position.x / size.width).coerceIn(0f, 1f)
        val raw = SegmentedTimelineMath.positionFromFraction(dragFrac, segmentDurationsMs)
        onScrubUpdate(SegmentedTimelineMath.snapIfNear(raw, segmentDurationsMs, snapWindowMs = (totalMs / 50).coerceIn(150L, 1500L)))
      }
    }
  ```
  (`dragFrac` = a `remember { mutableFloatStateOf(0f) }`.)
- A11y: put `progressBarRangeInfo` + `stateDescription` (formatted from `player_timeline_state` using the current segment via `SegmentedTimelineMath.segmentAtPosition`) + `setProgress` action + two `customActions` (prev/next → `onPrevSegment`/`onNextSegment`) on ONE wrapper node; ticks `clearAndSetSemantics {}`.
- Keep the existing live-region but throttle to segment-change only (compare last announced segment index).

- [ ] **Step 3: Build** — `./gradlew :app:assembleDebug` → packageDebug EXECUTED.

- [ ] **Step 4: Static-gate check** — `./gradlew :app:lintDebug` is RED on a pre-existing B5 NewApi; instead run the a11y gates: `./gradlew :app:checkA11yAnimationGated :app:checkA11yClickableHasRole :app:checkA11yTargetSizeToken :app:checkNoHardcodedUiStrings` → all PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/player/SegmentedTimeline.kt app/src/main/res/values/strings.xml app/src/main/res/values-es/strings.xml
git commit -m "feat(player): interactive timeline (tap/drag seek, ticks, seekbar a11y)"
```

---

### Task 6: Wire `PlayerScreen`, prune dead strings, motion glide

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/player/PlayerScreen.kt`
- Modify: `app/src/main/res/values/strings.xml` + `values-es/strings.xml` (delete 3 dead keys)

- [ ] **Step 1: Wire the timeline + scrub state**

Pass the new callbacks from `PlayerScreen` into `SegmentedTimeline` (`onSeek = vm::seekTo`, scrub → `vm::beginScrub/updateScrub/endScrub`, prev/next → `vm::jumpPrevSegment/jumpNextSegment`), forward `progress.isScrubbing`. While `isScrubbing`, keep the control row visible (suppress any auto behaviour — there is none yet, just ensure the bar stays mounted).

- [ ] **Step 2: Playhead glide (reduced-motion gated)**

```kotlin
val reduce = rememberReduceMotion()
val shownPos by animateFloatAsState(
    targetValue = SegmentedTimelineMath.fractionFromPosition(progress.positionMs, segs),
    animationSpec = if (reduce || progress.isScrubbing) snap() else RovaMotion.containerSpring(),
    label = "playheadGlide",
)
```
Feed `shownPos` to the bar's fill (render-layer only; pure helpers untouched). During scrub use `snap()` so the playhead tracks the finger exactly.

- [ ] **Step 3: Delete dead strings**

Remove `player_trim_cd`, `player_edit_cd`, `player_editor_coming_soon` from BOTH `values/strings.xml` (lines ~361/364/365) and `values-es/strings.xml` (lines ~359/362/363). Grep to confirm zero remaining references:

Run: `git grep -n "player_trim_cd\|player_edit_cd\|player_editor_coming_soon" -- app/src` → expect no matches.

- [ ] **Step 4: Build + gate sweep**

Run: `./gradlew :app:assembleDebug` → packageDebug EXECUTED. Then run the full custom-check set the spec lists (or `./gradlew :app:preBuild` if it aggregates them) → all PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/player/PlayerScreen.kt app/src/main/res/values/strings.xml app/src/main/res/values-es/strings.xml
git commit -m "feat(player): wire interactive timeline + glide; drop dead editor strings"
```

---

### Task 7: Full verification + device smoke

- [ ] **Step 1: Full JVM + build**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: all tests green (incl. the 3 new test classes); `:app:packageDebug` EXECUTED; fresh APK mtime.

- [ ] **Step 2: All 46 gates green**

Run the custom `check*` tasks (they wire into `preBuild`). Expected: all PASS. If any fails, FIX THE SOURCE — never the check.

- [ ] **Step 3: Install + owner device smoke (RZCYA1VBQ2H)**

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Owner verifies visually (Player FLAG_SECURE → screencap black):
- tap timeline → playhead jumps to that point and plays from there;
- drag → smooth scrub, no jank, snaps near clip boundaries, controls stay visible;
- TalkBack: timeline reads as a seekbar with "Segment N of M"; prev/next-segment custom actions work;
- close mid-playback, reopen → resumes near the saved position; finish a clip → reopen starts from 0.

- [ ] **Step 4: Do NOT push/PR** until owner GO. On GO: open the PR for `feat/player-nav-core`.

---

## Self-Review

**Spec coverage:** interactive tap/drag (Tasks 1,4,5) ✓ · segment prev/next (Tasks 1,4,5 a11y actions) ✓ · resume (Tasks 2,3,4) ✓ · a11y seekbar node (Task 5) ✓ · dead-string cleanup + glide (Task 6) ✓ · wall-clock explicitly deferred ✓ · throttled drag recipe (Task 4 §3) ✓.
**Placeholders:** none — every code step carries real code; the Compose tasks state device-smoke as their verification per JVM-only policy (not a faked Robolectric test).
**Type consistency:** `segmentDurationsMs`/`positionFromFraction`/`fractionFromPosition`/`snapIfNear`/`prev|nextSegmentStart`/`ResumePolicy.resolveOpenPosition`/`positionMs`/`isScrubbing` used identically across Tasks 1–6.
**Note for executor:** confirm the VM's existing backing-field name for `progress` (the plan calls it `_progress`) and the injected `LibraryMetadataStore` + recording-id wiring (PlayerViewModel is an `AndroidViewModel`; the store is constructed in `RovaApp`); adapt the two field names to the real ones at Task 4.

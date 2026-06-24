# PR-7 Player Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add three small comfort/skim features to the in-app review player — playback speed (cycling chip), double-tap edge-seek, and auto-hide chrome — without touching navigation (the shipped headline).

**Architecture:** Each feature gets a pure JVM-testable policy object (`PlaybackSpeedPolicy`, `EdgeSeekZones`, `AutoHideChromePolicy`) under `…/ui/screens/player/`; the Compose/ExoPlayer wrappers stay thin seams (house pattern). Speed is session-only transient state carried on `PlaybackProgress` (same preservation discipline as `isScrubbing`). Double-tap reuses existing `seekRelative`/`togglePlayPause` primitives — no new VM seek method. Auto-hide visibility is transient `PlayerReady` composable state (NOT in VM/`PlaybackProgress`).

**Tech Stack:** Kotlin, Jetpack Compose, Media3/ExoPlayer 1.4.1, JUnit4 (JVM unit tests under `isReturnDefaultValues = true`).

## Global Constraints

- Toolchain: AGP 9.2.1 · Kotlin 2.2.10 · Gradle 9.4.1 · compileSdk/targetSdk 37 · minSdk 24 · Java 11 · Media3 pinned 1.4.1.
- **46 static gates + `:app:testDebugUnitTest` GREEN at EVERY commit.** Verify builds via `./gradlew :app:assembleDebug` (fires the 46 `check*` on preBuild), NOT `lintDebug` (pre-existing `VaultAndroidOps:267` NewApi RED is unrelated).
- **NEVER edit a `check*` task to pass.** Fix the source. PR-7 adds NO new invariant / no new `check*`.
- Pure-helper extraction + JVM tests land in the SAME commit. No Robolectric / instrumented.
- New user-visible strings land in BOTH `app/src/main/res/values/strings.xml` (en) AND `app/src/main/res/values-es/strings.xml` (es).
- `checkNoHardcodedUiStrings` — no `Text("…")` / `contentDescription = "…"` literals; use `stringResource`. (Computed `String` vars passed to `Text(...)` are fine — only literals are flagged.)
- `checkUserCopyVocabulary` — banned in en+es string VALUES: `loop(s)`, `repeat(s)`, `segment(s)`, `ciclos`, `segmentos`, `repeticion`, `bucles`. Use **"clip"** / **"session"**.
- `checkA11yAnimationGated` — any animation in a `.kt` must read `com.aritr.rova.ui.components.rememberReduceMotion()` in the same file. The auto-hide fade gates on it.
- `checkA11yClickableHasRole` — clickable/gesture regions declare a semantics `Role`.
- `checkA11yTargetSizeToken` — speed chip hit target ≥48dp.
- codex MCP peer-review for code >5 lines / architecture / algorithmic logic (controller runs it; subagents are edit-only).
- Subagent-driven: subagents EDIT-ONLY; controller runs ALL gradle/tests/commits/smoke, serializes the one Gradle daemon (one build at a time), builds WARM (no prophylactic cache wipes).

### Owner decisions locked (spec §8)
- Q1 = **(A) cycling chip** (no popup).
- Q2 = speeds **`0.5× · 1× · 1.5× · 2×`**; 4× DEFERRED (not shipped).
- Q3 = **KEEP** the explicit Replay10/Forward10 buttons (TalkBack-accessible seek path).
- Q4 = **no double-tap flash** v1 (playhead jump is the feedback).
- Q5 = single-tap = **always-show** chrome (never hides on tap).
- Q6 = ~300 ms single-tap arbitration latency ACCEPTED.
- Q7 = auto-hide timeout **3 s**.
- Q8 = speed **session-only** (resets to 1× on reopen; NO persisted setting).

---

## File Structure

**Create:**
- `app/src/main/java/com/aritr/rova/ui/screens/player/PlaybackSpeedPolicy.kt` — pure speed cycle/clamp/label.
- `app/src/main/java/com/aritr/rova/ui/screens/player/EdgeSeekZones.kt` — pure tap-x → zone.
- `app/src/main/java/com/aritr/rova/ui/screens/player/AutoHideChromePolicy.kt` — pure timer-gating + visibility.
- `app/src/test/java/com/aritr/rova/ui/screens/player/PlaybackSpeedPolicyTest.kt`
- `app/src/test/java/com/aritr/rova/ui/screens/player/EdgeSeekZonesTest.kt`
- `app/src/test/java/com/aritr/rova/ui/screens/player/AutoHideChromePolicyTest.kt`

**Modify:**
- `app/src/main/java/com/aritr/rova/ui/screens/player/PlayerUiState.kt` — add `speed: Float = 1f` to `PlaybackProgress`.
- `app/src/main/java/com/aritr/rova/ui/screens/player/PlayerViewModel.kt` — add `setPlaybackSpeed`; preserve `speed` in `pushProgress`.
- `app/src/main/java/com/aritr/rova/ui/screens/player/PlayerScreen.kt` — speed chip, `detectTapGestures` on video Box, `AnimatedVisibility` chrome wrap.
- `app/src/main/res/values/strings.xml` — `player_speed_cd`, `player_show_controls_cd`.
- `app/src/main/res/values-es/strings.xml` — same keys, es.

---

## Task 1: Playback speed control (cycling chip)

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/screens/player/PlaybackSpeedPolicy.kt`
- Create: `app/src/test/java/com/aritr/rova/ui/screens/player/PlaybackSpeedPolicyTest.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/player/PlayerUiState.kt` (add `speed`)
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/player/PlayerViewModel.kt` (`setPlaybackSpeed`, preserve in `pushProgress`)
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/player/PlayerScreen.kt` (`ControlsRow` + chip + wiring)
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-es/strings.xml`

**Interfaces:**
- Produces:
  - `object PlaybackSpeedPolicy { val SPEEDS: List<Float>; val DEFAULT: Float; fun next(current: Float): Float; fun isValid(speed: Float): Boolean; fun clampToSupported(speed: Float): Float; fun label(speed: Float, locale: java.util.Locale): String }`
  - `PlaybackProgress.speed: Float` (default `1f`).
  - `PlayerViewModel.setPlaybackSpeed(speed: Float)`.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/aritr/rova/ui/screens/player/PlaybackSpeedPolicyTest.kt`:

```kotlin
package com.aritr.rova.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * PR-7 — pins the playback-speed cycle/clamp/label contract for the
 * player speed chip. JVM-only, no Compose / ExoPlayer dependency.
 * Speed set is 0.5×/1×/1.5×/2× (4× deferred per spec §2.1 / owner Q2).
 */
class PlaybackSpeedPolicyTest {

    @Test fun `speed set is exactly the four review speeds`() {
        assertEquals(listOf(0.5f, 1f, 1.5f, 2f), PlaybackSpeedPolicy.SPEEDS)
        assertEquals(1f, PlaybackSpeedPolicy.DEFAULT)
    }

    @Test fun `next cycles 1 to 1_5 to 2 and wraps 2 to 0_5 to 1`() {
        assertEquals(1.5f, PlaybackSpeedPolicy.next(1f))
        assertEquals(2f, PlaybackSpeedPolicy.next(1.5f))
        assertEquals(0.5f, PlaybackSpeedPolicy.next(2f))   // wrap
        assertEquals(1f, PlaybackSpeedPolicy.next(0.5f))
    }

    @Test fun `next of off-list value snaps to nearest then advances`() {
        // 1.25 nearest supported is 1f (tie broken to lower index) -> advance 1.5
        assertEquals(1.5f, PlaybackSpeedPolicy.next(1.25f))
        // 3f nearest supported is 2f -> advance wraps to 0.5
        assertEquals(0.5f, PlaybackSpeedPolicy.next(3f))
    }

    @Test fun `clampToSupported coerces into range and snaps`() {
        assertEquals(2f, PlaybackSpeedPolicy.clampToSupported(4f))    // above max
        assertEquals(0.5f, PlaybackSpeedPolicy.clampToSupported(0.1f)) // below min
        assertEquals(1f, PlaybackSpeedPolicy.clampToSupported(1f))     // exact
        assertEquals(1f, PlaybackSpeedPolicy.clampToSupported(Float.NaN))            // non-finite -> default
        assertEquals(1f, PlaybackSpeedPolicy.clampToSupported(Float.POSITIVE_INFINITY))
    }

    @Test fun `isValid only for listed speeds`() {
        assertTrue(PlaybackSpeedPolicy.isValid(0.5f))
        assertTrue(PlaybackSpeedPolicy.isValid(2f))
        assertFalse(PlaybackSpeedPolicy.isValid(4f))
        assertFalse(PlaybackSpeedPolicy.isValid(1.25f))
    }

    @Test fun `label is locale-aware decimal with multiplier`() {
        assertEquals("1×", PlaybackSpeedPolicy.label(1f, Locale.US))
        assertEquals("2×", PlaybackSpeedPolicy.label(2f, Locale.US))
        assertEquals("1.5×", PlaybackSpeedPolicy.label(1.5f, Locale.US))
        assertEquals("0.5×", PlaybackSpeedPolicy.label(0.5f, Locale.US))
        assertEquals("1,5×", PlaybackSpeedPolicy.label(1.5f, Locale("es")))
        assertEquals("0,5×", PlaybackSpeedPolicy.label(0.5f, Locale("es")))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.player.PlaybackSpeedPolicyTest"`
Expected: FAIL — `Unresolved reference: PlaybackSpeedPolicy`.

- [ ] **Step 3: Write the pure helper**

`app/src/main/java/com/aritr/rova/ui/screens/player/PlaybackSpeedPolicy.kt`:

```kotlin
package com.aritr.rova.ui.screens.player

import java.util.Locale
import kotlin.math.abs

/**
 * PR-7 — pure playback-speed policy for the player speed chip.
 *
 * Compose/ExoPlayer-free so it is JVM-unit-testable under
 * `isReturnDefaultValues = true`, mirroring the house pure-helper pattern
 * ([PlayerIconSpec], [SegmentedTimelineMath]).
 *
 * Speed set is the review affordance 0.5×/1×/1.5×/2× — 0.5× is slow-mo to
 * confirm a detail, 2× is the skim ceiling. 4× is deliberately DEFERRED
 * (spec §2.1, owner Q2): a review tool, not skim-for-hours.
 *
 * Speed is session-only (owner Q8): nothing here persists; the chip resets
 * to [DEFAULT] on the next player open.
 */
object PlaybackSpeedPolicy {

    /** Cycle order is the list order; [next] of the last wraps to the first. */
    val SPEEDS: List<Float> = listOf(0.5f, 1f, 1.5f, 2f)
    val DEFAULT: Float = 1f

    private const val EPSILON = 1e-4f

    /** True only for an exact listed speed. */
    fun isValid(speed: Float): Boolean = SPEEDS.any { abs(it - speed) < EPSILON }

    /** Index of the nearest supported speed (ties broken to the lower index). */
    private fun nearestIndex(speed: Float): Int =
        SPEEDS.indices.minByOrNull { abs(SPEEDS[it] - speed) } ?: 0

    /**
     * Next speed in cycle order, wrapping. Tolerant of an off-list current
     * value: snaps to the nearest supported speed, then advances one step.
     */
    fun next(current: Float): Float {
        val idx = if (isValid(current)) {
            SPEEDS.indexOfFirst { abs(it - current) < EPSILON }
        } else {
            nearestIndex(current)
        }
        return SPEEDS[(idx + 1) % SPEEDS.size]
    }

    /**
     * Defensive: coerce arbitrary input into range, then snap onto [SPEEDS].
     * Non-finite input (NaN/±Inf) resets to [DEFAULT] so a bad value can never
     * reach `ExoPlayer.setPlaybackSpeed` (Media3 requires speed > 0 / finite).
     */
    fun clampToSupported(speed: Float): Float {
        if (!speed.isFinite()) return DEFAULT
        val coerced = speed.coerceIn(SPEEDS.first(), SPEEDS.last())
        return SPEEDS[nearestIndex(coerced)]
    }

    /**
     * Locale-aware chip label, e.g. "1×" / "1.5×" (en) / "1,5×" (es).
     * Whole speeds render with no decimal. The "×" multiplier is part of the
     * formatted value, not user copy — pinned by [PlaybackSpeedPolicyTest].
     */
    fun label(speed: Float, locale: Locale): String {
        val isWhole = speed == speed.toLong().toFloat()
        val num = if (isWhole) {
            String.format(locale, "%d", speed.toLong())
        } else {
            String.format(locale, "%.1f", speed)
        }
        return "$num×"
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.player.PlaybackSpeedPolicyTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Add `speed` to `PlaybackProgress`**

In `PlayerUiState.kt`, extend the `PlaybackProgress` data class (after `isScrubbing`):

```kotlin
data class PlaybackProgress(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isPlaying: Boolean = false,
    val isScrubbing: Boolean = false,
    /**
     * PR-7 — current playback speed multiplier (session-only; resets to
     * [PlaybackSpeedPolicy.DEFAULT] on reopen). Owned by
     * [PlayerViewModel.setPlaybackSpeed]; preserved across
     * [PlayerViewModel.pushProgress] rebuilds the same way [isScrubbing] is,
     * so a poll tick can never silently reset it to 1×.
     */
    val speed: Float = 1f
)
```

- [ ] **Step 6: Add `setPlaybackSpeed` + preserve speed in `pushProgress`**

In `PlayerViewModel.kt`, add the import `import androidx.media3.common.PlaybackParameters` (with the other media3 imports), then add the VM seam (place after `seekTo`):

```kotlin
    /**
     * PR-7 — set the playback speed. Coerces arbitrary input onto the
     * supported set ([PlaybackSpeedPolicy.clampToSupported]) before applying
     * to ExoPlayer (Media3 1.4.1 `Player.setPlaybackSpeed` — no MediaSession
     * needed; no STATE_READY requirement). Gated on
     * COMMAND_SET_SPEED_AND_PITCH per the Media3 contract — a no-op (and no
     * UI write) if the command is unavailable. The optimistic
     * [PlaybackProgress.speed] write is reconciled by
     * [onPlaybackParametersChanged] (the player reports the active params, so
     * the chip never drifts if the player adjusts/rejects). Session-only:
     * nothing is persisted.
     */
    fun setPlaybackSpeed(speed: Float) {
        val p = exoPlayer ?: return
        if (!p.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) return
        val applied = PlaybackSpeedPolicy.clampToSupported(speed)
        p.setPlaybackSpeed(applied)
        _progress.update { it.copy(speed = applied) }
    }
```

Add the reconciling callback to the existing `playerListener` object (alongside `onIsPlayingChanged` etc.):

```kotlin
        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            // PR-7 — the active speed reported by the player is authoritative;
            // reconcile the optimistic setPlaybackSpeed write so the chip
            // reflects what is actually applied.
            _progress.update { it.copy(speed = playbackParameters.speed) }
        }
```

In the same file, in `pushProgress`, add speed preservation to the rebuilt snapshot (mirror the `isScrubbing` line):

```kotlin
        _progress.value = PlaybackProgress(
            positionMs = if (_progress.value.isScrubbing) _progress.value.positionMs
                         else p.currentPosition.coerceAtLeast(0L),
            durationMs = dur,
            isPlaying = isPlaying ?: p.isPlaying,
            isScrubbing = _progress.value.isScrubbing,
            // PR-7 — speed is owned by setPlaybackSpeed; pushProgress rebuilds
            // the whole snapshot, so it must carry the current speed forward or
            // a poll tick would reset the chip to 1×.
            speed = _progress.value.speed
        )
```

- [ ] **Step 7: Add EN + ES strings**

In `app/src/main/res/values/strings.xml`, after `player_forward_cd` (line ~359):

```xml
    <!-- Player speed chip (PR-7): %1$s = current speed label e.g. "1.5×" -->
    <string name="player_speed_cd">Playback speed %1$s, tap to change</string>
```

In `app/src/main/res/values-es/strings.xml`, at the matching location:

```xml
    <!-- Player speed chip (PR-7): %1$s = current speed label e.g. "1,5×" -->
    <string name="player_speed_cd">Velocidad de reproducción %1$s, toca para cambiar</string>
```

- [ ] **Step 8: Wire the speed chip into `ControlsRow`**

In `PlayerScreen.kt`:

(a) Add imports (alphabetical with existing):
```kotlin
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
```
(Note: `Arrangement`, `Box`, `Surface`, `CircleShape`, `semantics`, `contentDescription`, `size` already imported.)

(b) Thread the speed seam through `PlayerReady`. Add a parameter:
```kotlin
    onSetSpeed: (Float) -> Unit,
```
and pass it from `PlayerScreen`'s `PlayerReady(...)` call:
```kotlin
                    onSetSpeed = viewModel::setPlaybackSpeed,
```

(c) Update the `ControlsRow(...)` invocation inside `PlayerReady` to pass speed + cycle:
```kotlin
            ControlsRow(
                isPlaying = progress.isPlaying,
                speed = progress.speed,
                onTogglePlay = onTogglePlay,
                onSeekBack = { onSeekRelative(-SEEK_DELTA_MS) },
                onSeekForward = { onSeekRelative(SEEK_DELTA_MS) },
                onCycleSpeed = { onSetSpeed(PlaybackSpeedPolicy.next(progress.speed)) }
            )
```

(d) Replace the `ControlsRow` composable body. The three transport controls stay centered; the speed chip sits at the right edge (≥48dp), keeping play/pause visually primary:

```kotlin
@Composable
private fun ControlsRow(
    isPlaying: Boolean,
    speed: Float,
    onTogglePlay: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onCycleSpeed: () -> Unit
) {
    val playPauseCd = if (isPlaying) {
        stringResource(R.string.player_pause_cd)
    } else {
        stringResource(R.string.player_play_cd)
    }
    val locale = LocalConfiguration.current.locales[0]
    val speedLabel = PlaybackSpeedPolicy.label(speed, locale)
    val speedCd = stringResource(R.string.player_speed_cd, speedLabel)

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onSeekBack) {
                SemanticIcon(
                    imageVector = Icons.Default.Replay10,
                    contentDescription = stringResource(R.string.player_rewind_cd),
                    role = IconRole.Default
                )
            }
            Surface(
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.10f),
                onClick = onTogglePlay,
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = playPauseCd }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    SemanticIcon(
                        imageVector = PlayerIconSpec.transportGlyph(isPlaying),
                        contentDescription = null,
                        role = IconRole.Default
                    )
                }
            }
            IconButton(onClick = onSeekForward) {
                SemanticIcon(
                    imageVector = Icons.Default.Forward10,
                    contentDescription = stringResource(R.string.player_forward_cd),
                    role = IconRole.Default
                )
            }
        }
        // PR-7 speed chip — cycling 1×→1.5×→2×→0.5×→1× (owner Q1=A). Mirrors
        // the play/pause Surface(onClick) a11y pattern: ≥48dp target,
        // contentDescription announces current speed + that tapping changes it.
        // Surface(onClick) supplies Role.Button (checkA11yClickableHasRole).
        Surface(
            shape = CircleShape,
            color = Color.White.copy(alpha = 0.10f),
            onClick = onCycleSpeed,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .size(48.dp)
                .semantics { contentDescription = speedCd }
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = speedLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.88f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
```

(Drop the unused `BoxWithConstraints` / `wrapContentWidth` imports from step (a) if the compiler flags them — they were listed defensively; only `LocalConfiguration` and `TextAlign` are required.)

- [ ] **Step 9: Build (fires 46 gates) + run player unit tests**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL (all 46 `check*` green — `checkNoHardcodedUiStrings`, `checkUserCopyVocabulary`, `checkA11yClickableHasRole`, `checkA11yTargetSizeToken` in particular).
Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.player.*"`
Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/player/PlaybackSpeedPolicy.kt \
        app/src/test/java/com/aritr/rova/ui/screens/player/PlaybackSpeedPolicyTest.kt \
        app/src/main/java/com/aritr/rova/ui/screens/player/PlayerUiState.kt \
        app/src/main/java/com/aritr/rova/ui/screens/player/PlayerViewModel.kt \
        app/src/main/java/com/aritr/rova/ui/screens/player/PlayerScreen.kt \
        app/src/main/res/values/strings.xml app/src/main/res/values-es/strings.xml
git commit -m "feat(player): PR-7 playback speed cycling chip (0.5×–2×, session-only)"
```

---

## Task 2: Double-tap edge-seek

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/screens/player/EdgeSeekZones.kt`
- Create: `app/src/test/java/com/aritr/rova/ui/screens/player/EdgeSeekZonesTest.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/player/PlayerScreen.kt` (gesture on video Box)
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-es/strings.xml` (`player_show_controls_cd`)

**Interfaces:**
- Consumes: `PlayerViewModel.seekRelative(Long)`, `togglePlayPause()` (existing); `SEEK_DELTA_MS` (existing const = 10_000L).
- Produces: `object EdgeSeekZones { enum class Zone { SEEK_BACK, TOGGLE, SEEK_FORWARD }; fun zoneFor(tapX: Float, width: Float, leftFraction: Float = 1f/3f, rightFraction: Float = 1f/3f): Zone }`. Also introduces a LOCAL `onSingleTap: () -> Unit` lambda inside `PlayerReady` (placeholder no-op here; Task 3 replaces its body with chrome-show logic). No change to `PlayerReady`'s signature.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/aritr/rova/ui/screens/player/EdgeSeekZonesTest.kt`:

```kotlin
package com.aritr.rova.ui.screens.player

import com.aritr.rova.ui.screens.player.EdgeSeekZones.Zone
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PR-7 — pins the double-tap edge-seek zone split. Pure, Compose-free.
 * Bands: [0, leftEdge) = SEEK_BACK, [leftEdge, rightEdge) = TOGGLE,
 * [rightEdge, width] = SEEK_FORWARD. Degenerate width / negative x -> TOGGLE.
 */
class EdgeSeekZonesTest {

    @Test fun `left third seeks back`() {
        assertEquals(Zone.SEEK_BACK, EdgeSeekZones.zoneFor(10f, 300f))
    }

    @Test fun `center third toggles`() {
        assertEquals(Zone.TOGGLE, EdgeSeekZones.zoneFor(150f, 300f))
    }

    @Test fun `right third seeks forward`() {
        assertEquals(Zone.SEEK_FORWARD, EdgeSeekZones.zoneFor(290f, 300f))
    }

    @Test fun `left boundary is exclusive (left band open at leftEdge)`() {
        // x == leftEdge (100) -> center, not back
        assertEquals(Zone.TOGGLE, EdgeSeekZones.zoneFor(100f, 300f))
    }

    @Test fun `right boundary is inclusive (forward band closed at rightEdge)`() {
        // x == rightEdge (200) -> forward
        assertEquals(Zone.SEEK_FORWARD, EdgeSeekZones.zoneFor(200f, 300f))
    }

    @Test fun `degenerate width returns toggle (no divide-by-zero)`() {
        assertEquals(Zone.TOGGLE, EdgeSeekZones.zoneFor(0f, 0f))
        assertEquals(Zone.TOGGLE, EdgeSeekZones.zoneFor(50f, -10f))
    }

    @Test fun `negative tap x returns toggle (safe default)`() {
        assertEquals(Zone.TOGGLE, EdgeSeekZones.zoneFor(-10f, 300f))
    }

    @Test fun `custom fractions honored`() {
        // leftFraction 0.25 -> leftEdge 75; 60 < 75 = back
        assertEquals(Zone.SEEK_BACK, EdgeSeekZones.zoneFor(60f, 300f, 0.25f, 0.25f))
        // rightFraction 0.25 -> rightEdge 225; 80 in [75,225) = center
        assertEquals(Zone.TOGGLE, EdgeSeekZones.zoneFor(80f, 300f, 0.25f, 0.25f))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.player.EdgeSeekZonesTest"`
Expected: FAIL — `Unresolved reference: EdgeSeekZones`.

- [ ] **Step 3: Write the pure helper**

`app/src/main/java/com/aritr/rova/ui/screens/player/EdgeSeekZones.kt`:

```kotlin
package com.aritr.rova.ui.screens.player

/**
 * PR-7 — pure tap-x → seek zone for double-tap edge-seek on the player
 * video surface. Compose-free, JVM-unit-testable.
 *
 * Double-tap the left third → seek back 10 s; right third → forward 10 s;
 * center → toggle play/pause (so the center isn't a dead zone). Single-tap
 * (show chrome) is handled separately by `detectTapGestures(onTap = …)`.
 *
 * Bands (half-open so every x maps to exactly one zone):
 *   [0, leftEdge)        → SEEK_BACK
 *   [leftEdge, rightEdge) → TOGGLE
 *   [rightEdge, width]   → SEEK_FORWARD
 * Degenerate width (≤0) or negative tapX → TOGGLE (safe, no divide-by-zero).
 */
object EdgeSeekZones {

    enum class Zone { SEEK_BACK, TOGGLE, SEEK_FORWARD }

    fun zoneFor(
        tapX: Float,
        width: Float,
        leftFraction: Float = 1f / 3f,
        rightFraction: Float = 1f / 3f
    ): Zone {
        if (width <= 0f || tapX < 0f) return Zone.TOGGLE
        val leftEdge = width * leftFraction
        val rightEdge = width * (1f - rightFraction)
        return when {
            tapX < leftEdge -> Zone.SEEK_BACK
            tapX >= rightEdge -> Zone.SEEK_FORWARD
            else -> Zone.TOGGLE
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.player.EdgeSeekZonesTest"`
Expected: PASS (8 tests).

- [ ] **Step 5: Add EN + ES strings for the single-tap region CD**

In `app/src/main/res/values/strings.xml`, after `player_speed_cd`:

```xml
    <!-- Player video surface single-tap region (PR-7) -->
    <string name="player_show_controls_cd">Video, tap to show controls</string>
```

In `app/src/main/res/values-es/strings.xml`:

```xml
    <!-- Player video surface single-tap region (PR-7) -->
    <string name="player_show_controls_cd">Video, toca para mostrar los controles</string>
```

- [ ] **Step 6: Add the gesture to the video Box in `PlayerScreen.kt`**

(a) Add imports:
```kotlin
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
```

(b) Do NOT change `PlayerReady`'s signature. Inside `PlayerReady`, above its `Box`, add the surface-width state, the CD string, and a LOCAL `onSingleTap` placeholder (Task 3 replaces its body):

```kotlin
    var surfaceWidthPx by remember { mutableFloatStateOf(0f) }
    val showControlsCd = stringResource(R.string.player_show_controls_cd)
    // PR-7 — placeholder; Task 3 replaces the body with chrome-show logic
    // (chromeVisible = true; interactionTick++). Kept as a single lambda so
    // both the pointer gesture and the semantics onClick action route through
    // one path.
    val onSingleTap: () -> Unit = {}
```

(c) Change the `AndroidView` modifier to add size-tracking, the tap gesture, and a working a11y click action. NOTE the `semantics.onClick` action: `pointerInput` does NOT register an accessibility click, so without this a TalkBack user could not activate the surface (codex). The `onClick` action routes to the same `onSingleTap`. Double-tap branches also call `onSingleTap()` so the chrome reveals — with no flash (Q4), the playhead jump is invisible unless chrome is shown:

```kotlin
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { surfaceWidthPx = it.width.toFloat() }
                // PR-7 — single-tap shows chrome (onTap fires only after the
                // ~300 ms double-tap window elapses; owner Q5=always-show,
                // Q6=latency accepted). Double-tap maps the x onto an
                // EdgeSeekZones band: left=−10s, right=+10s, center=play/pause,
                // and reveals chrome so the playhead jump is visible (Q4=no flash).
                // The gesture sits on the full-bleed video Box (z-below the
                // chrome) so taps on real control buttons are consumed by
                // those buttons (spec C5). The semantics Role+CD+onClick keeps
                // the surface an activatable labeled control for TalkBack
                // (checkA11yClickableHasRole); the ±10s accessible path stays
                // the explicit Replay10/Forward10 buttons (double-tap is
                // TalkBack-invisible, §3.4).
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onSingleTap() },
                        onDoubleTap = { offset ->
                            when (EdgeSeekZones.zoneFor(offset.x, surfaceWidthPx)) {
                                EdgeSeekZones.Zone.SEEK_BACK -> onSeekRelative(-SEEK_DELTA_MS)
                                EdgeSeekZones.Zone.SEEK_FORWARD -> onSeekRelative(SEEK_DELTA_MS)
                                EdgeSeekZones.Zone.TOGGLE -> onTogglePlay()
                            }
                            onSingleTap() // reveal chrome + restart hide timer
                        }
                    )
                }
                .semantics {
                    contentDescription = showControlsCd
                    role = Role.Button
                    onClick { onSingleTap(); true }
                },
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                }
            },
            update = { view ->
                bindPlayerView(view)
                view.keepScreenOn = progress.isPlaying
            }
        )
```

- [ ] **Step 7: Build (fires 46 gates) + run player unit tests**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL (`checkA11yClickableHasRole`, `checkNoHardcodedUiStrings`, `checkUserCopyVocabulary` green).
Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.player.*"`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/player/EdgeSeekZones.kt \
        app/src/test/java/com/aritr/rova/ui/screens/player/EdgeSeekZonesTest.kt \
        app/src/main/java/com/aritr/rova/ui/screens/player/PlayerScreen.kt \
        app/src/main/res/values/strings.xml app/src/main/res/values-es/strings.xml
git commit -m "feat(player): PR-7 double-tap edge-seek (left/right ±10s, center play/pause)"
```

---

## Task 3: Auto-hide chrome

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/screens/player/AutoHideChromePolicy.kt`
- Create: `app/src/test/java/com/aritr/rova/ui/screens/player/AutoHideChromePolicyTest.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/player/PlayerScreen.kt` (AnimatedVisibility + state wiring)

**Interfaces:**
- Consumes: `EdgeSeekZones` gesture's `onSingleTap` hook (Task 2); `PlaybackProgress.isPlaying` / `isScrubbing` (existing); `rememberReduceMotion()` from `com.aritr.rova.ui.components`.
- Produces: `object AutoHideChromePolicy { const val DEFAULT_TIMEOUT_MS: Long; fun shouldRunHideTimer(isPlaying: Boolean, isScrubbing: Boolean, chromeVisible: Boolean, speedMenuOpen: Boolean = false): Boolean; fun onUserTap(currentlyVisible: Boolean): Boolean; fun onPlaybackPaused(): Boolean }`.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/aritr/rova/ui/screens/player/AutoHideChromePolicyTest.kt`:

```kotlin
package com.aritr.rova.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-7 — pins the auto-hide timer-gating + visibility rules. Pure,
 * Compose-free; the delay() itself stays in the LaunchedEffect (the helper
 * only decides whether the timer may run and the next visibility).
 */
class AutoHideChromePolicyTest {

    @Test fun `timeout is three seconds`() {
        assertEquals(3_000L, AutoHideChromePolicy.DEFAULT_TIMEOUT_MS)
    }

    @Test fun `timer runs only while playing, visible, not scrubbing, no menu`() {
        assertTrue(
            AutoHideChromePolicy.shouldRunHideTimer(
                isPlaying = true, isScrubbing = false, chromeVisible = true
            )
        )
    }

    @Test fun `timer suppressed when paused`() {
        assertFalse(
            AutoHideChromePolicy.shouldRunHideTimer(
                isPlaying = false, isScrubbing = false, chromeVisible = true
            )
        )
    }

    @Test fun `timer suppressed while scrubbing`() {
        assertFalse(
            AutoHideChromePolicy.shouldRunHideTimer(
                isPlaying = true, isScrubbing = true, chromeVisible = true
            )
        )
    }

    @Test fun `timer suppressed when already hidden`() {
        assertFalse(
            AutoHideChromePolicy.shouldRunHideTimer(
                isPlaying = true, isScrubbing = false, chromeVisible = false
            )
        )
    }

    @Test fun `timer suppressed when speed menu open`() {
        assertFalse(
            AutoHideChromePolicy.shouldRunHideTimer(
                isPlaying = true, isScrubbing = false, chromeVisible = true, speedMenuOpen = true
            )
        )
    }

    @Test fun `tap always shows chrome`() {
        assertTrue(AutoHideChromePolicy.onUserTap(currentlyVisible = false))
        assertTrue(AutoHideChromePolicy.onUserTap(currentlyVisible = true))
    }

    @Test fun `pause forces visible`() {
        assertTrue(AutoHideChromePolicy.onPlaybackPaused())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.player.AutoHideChromePolicyTest"`
Expected: FAIL — `Unresolved reference: AutoHideChromePolicy`.

- [ ] **Step 3: Write the pure helper**

`app/src/main/java/com/aritr/rova/ui/screens/player/AutoHideChromePolicy.kt`:

```kotlin
package com.aritr.rova.ui.screens.player

/**
 * PR-7 — pure auto-hide chrome policy. The `delay()` itself stays in the
 * screen's LaunchedEffect (Compose/coroutine side); this helper only decides
 * *whether* the hide timer may run and what the next visibility is, keeping
 * the wrapper a thin seam (house pattern).
 *
 * Chrome (top bar + bottom panel) fades out after [DEFAULT_TIMEOUT_MS] of
 * inactivity WHILE PLAYING. It is pinned visible when paused (a reviewer
 * studying a frame), while scrubbing, or (variant B only) while a speed menu
 * is open. Owner: Q5 tap = always-show, Q7 timeout = 3 s.
 */
object AutoHideChromePolicy {

    const val DEFAULT_TIMEOUT_MS = 3_000L

    /** The hide countdown may run only when ALL of these hold. */
    fun shouldRunHideTimer(
        isPlaying: Boolean,
        isScrubbing: Boolean,
        chromeVisible: Boolean,
        speedMenuOpen: Boolean = false
    ): Boolean = isPlaying && !isScrubbing && chromeVisible && !speedMenuOpen

    /** Owner Q5 — a tap always shows chrome (never hides on tap). */
    fun onUserTap(currentlyVisible: Boolean): Boolean = true

    /** Pausing always reveals controls. */
    fun onPlaybackPaused(): Boolean = true
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.player.AutoHideChromePolicyTest"`
Expected: PASS (8 tests).

- [ ] **Step 5: Wire AnimatedVisibility + state into `PlayerReady`**

In `PlayerScreen.kt`:

(a) Add imports:
```kotlin
import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.aritr.rova.ui.components.rememberReduceMotion
```
(`getValue`/`setValue`/`LocalContext` may already be imported — keep one copy.)

(b) At the top of `PlayerReady` (with the surface-width state from Task 2), add the chrome state, the touch-exploration read, and the two effects:
```kotlin
    var chromeVisible by remember { mutableStateOf(true) }
    var interactionTick by remember { mutableIntStateOf(0) }
    val reduceMotion = rememberReduceMotion()

    // PR-7 — when TalkBack touch-exploration is active, auto-hide is
    // suppressed entirely: removing a focused control from composition
    // (AnimatedVisibility) would trap/lose the screen-reader's focus (codex).
    // Read once at composition — a mid-session TalkBack toggle is a rare edge
    // that re-enters the screen anyway.
    val context = LocalContext.current
    val touchExplorationActive = remember {
        (context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager)
            .isTouchExplorationEnabled
    }

    // PR-7 — auto-hide countdown. Runs only while playing + visible + not
    // scrubbing (AutoHideChromePolicy) AND not under touch-exploration.
    // Pausing / scrubbing changes a key and cancels-and-restarts this effect;
    // interactionTick re-arms the timer on every chrome interaction even when
    // chromeVisible was already true (a tap/seek/speed-cycle that doesn't flip
    // a key would otherwise let an in-flight timer hide chrome mid-interaction).
    LaunchedEffect(progress.isPlaying, progress.isScrubbing, chromeVisible, interactionTick) {
        if (!touchExplorationActive &&
            AutoHideChromePolicy.shouldRunHideTimer(
                isPlaying = progress.isPlaying,
                isScrubbing = progress.isScrubbing,
                chromeVisible = chromeVisible
            )
        ) {
            kotlinx.coroutines.delay(AutoHideChromePolicy.DEFAULT_TIMEOUT_MS)
            chromeVisible = false
        }
    }

    // PR-7 — pausing always reveals controls (spec C4). Reactive on isPlaying.
    LaunchedEffect(progress.isPlaying) {
        if (!progress.isPlaying) chromeVisible = AutoHideChromePolicy.onPlaybackPaused()
    }
```

(c) Replace the Task-2 `onSingleTap` placeholder body (still a local lambda in `PlayerReady`, declared AFTER the `chromeVisible`/`interactionTick` state above) with the real show-chrome logic:
```kotlin
    val onSingleTap: () -> Unit = {
        chromeVisible = AutoHideChromePolicy.onUserTap(chromeVisible)
        interactionTick++
    }
```
Then wrap the chrome-control callbacks so each chrome interaction re-arms the hide timer (these don't flip a `LaunchedEffect` key on their own). Define wrapped lambdas and pass them to `SegmentedTimeline` / `ControlsRow` instead of the raw VM callbacks:
```kotlin
    // PR-7 — every chrome interaction bumps interactionTick so the hide timer
    // restarts (codex). togglePlay flips isPlaying and scrub flips isScrubbing,
    // which are already keys — but seek/seekTo/speed/segment-jumps are not.
    val onSeekRelativeBump: (Long) -> Unit = { onSeekRelative(it); interactionTick++ }
    val onSeekBump: (Long) -> Unit = { onSeek(it); interactionTick++ }
    val onPrevSegmentBump: () -> Unit = { onPrevSegment(); interactionTick++ }
    val onNextSegmentBump: () -> Unit = { onNextSegment(); interactionTick++ }
    val onCycleSpeedBump: () -> Unit = { onSetSpeed(PlaybackSpeedPolicy.next(progress.speed)); interactionTick++ }
```
Update the `SegmentedTimeline(...)` call to use `onSeek = onSeekBump`, `onPrevSegment = onPrevSegmentBump`, `onNextSegment = onNextSegmentBump`; update the `ControlsRow(...)` call (from Task 1) to use `onSeekBack = { onSeekRelativeBump(-SEEK_DELTA_MS) }`, `onSeekForward = { onSeekRelativeBump(SEEK_DELTA_MS) }`, `onCycleSpeed = onCycleSpeedBump`.

(d) Wrap the top gradient `Box` and the bottom panel `Column` in `AnimatedVisibility`. The fade gates on reduced motion (`checkA11yAnimationGated` / ADR-0020) — instant show/hide when reduced. For the top bar, wrap it:
```kotlin
        AnimatedVisibility(
            visible = chromeVisible,
            enter = if (reduceMotion) EnterTransition.None else fadeIn(),
            exit = if (reduceMotion) ExitTransition.None else fadeOut()
        ) {
            // existing top gradient Box (back + title/clip-count) unchanged
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    /* …existing gradient + padding + content… */
            ) { /* … */ }
        }
```
For the bottom panel, wrap the existing `Column` the same way, but preserve its `.align(Alignment.BottomCenter)` by moving the alignment onto the `AnimatedVisibility` (which is the direct child of the outer `Box`):
```kotlin
        AnimatedVisibility(
            modifier = Modifier.align(Alignment.BottomCenter),
            visible = chromeVisible,
            enter = if (reduceMotion) EnterTransition.None else fadeIn(),
            exit = if (reduceMotion) ExitTransition.None else fadeOut()
        ) {
            // existing bottom Column (InfoRow → SegmentedTimeline → ControlsRow)
            // with its OWN .align(...) REMOVED (now on AnimatedVisibility).
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    /* …existing gradient + padding…, NO .align here… */
            ) { /* InfoRow / SegmentedTimeline / ControlsRow unchanged */ }
        }
```

- [ ] **Step 6: Build (fires 46 gates) + run full player unit tests**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. `checkA11yAnimationGated` green (fade reads `rememberReduceMotion()` in this file).
Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.player.*"`
Expected: PASS.

- [ ] **Step 7: Full test suite (regression guard) + commit**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS — baseline 1241 + 22 new (6 speed + 8 zones + 8 autohide) = 1263, 0 failures.

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/player/AutoHideChromePolicy.kt \
        app/src/test/java/com/aritr/rova/ui/screens/player/AutoHideChromePolicyTest.kt \
        app/src/main/java/com/aritr/rova/ui/screens/player/PlayerScreen.kt
git commit -m "feat(player): PR-7 auto-hide chrome (3s, pinned while paused/scrubbing, reduced-motion gated)"
```

---

## Device smoke (controller, on RZCYA1VBQ2H — before owner GO)

Build + install: `./gradlew :app:assembleDebug` then `adb -s RZCYA1VBQ2H install -r app/build/outputs/apk/debug/app-debug.apk`. Open a merged session in the player and verify:
1. Speed chip cycles 1×→1.5×→2×→0.5×→1×; playback audibly/visibly changes; reopen resets to 1×.
2. Double-tap left = −10s, right = +10s, center = play/pause; single tap shows chrome; explicit ±10s buttons still work.
3. Chrome fades after ~3 s while playing; pause pins it; scrub pins it; any tap brings it back.
4. TalkBack reaches play/pause + Replay10/Forward10 + speed chip; speed chip announces current speed.
5. Reduced-motion (system setting ON) → chrome shows/hides instantly, no fade.

---

## Self-Review

- **Spec coverage:** Feature 1 (§2) → Task 1. Feature 2 (§3) → Task 2. Feature 3 (§4) → Task 3. Interaction conflicts §5: C1 (single vs double tap) handled by `detectTapGestures` arbitration (Task 2 step 6); C2 (timer vs scrub) by `shouldRunHideTimer` keying on `isScrubbing` (Task 3); C3 (speed popup) N/A — variant A has no popup; C4 (pause pins) by the second `LaunchedEffect` (Task 3 step 5b); C5 (gesture on video Box below chrome) by placing `pointerInput` on the `AndroidView` (Task 2 step 6c). All 8 open questions resolved in "Owner decisions locked". No new `check*` / no manifest schema change / no ADR amend (PR-7 adds no behavioral invariant).
- **Placeholder scan:** none — every code step has full content.
- **Type consistency:** `PlaybackSpeedPolicy.{SPEEDS,DEFAULT,next,isValid,clampToSupported,label}`, `EdgeSeekZones.{Zone,zoneFor}`, `AutoHideChromePolicy.{DEFAULT_TIMEOUT_MS,shouldRunHideTimer,onUserTap,onPlaybackPaused}`, `PlaybackProgress.speed`, `PlayerViewModel.setPlaybackSpeed` — names identical across tasks and call sites.

- **codex peer-review reconciliation (folded in):** (1) `setPlaybackSpeed` gates on `Player.COMMAND_SET_SPEED_AND_PITCH` + reconciles via `onPlaybackParametersChanged` (no optimistic drift; no STATE_READY requirement). (2) `clampToSupported` rejects non-finite input → `DEFAULT` (no NaN to ExoPlayer). (3) double-tap branches call `onSingleTap()` so the seek is visible with Q4 no-flash. (4) `interactionTick` bumps on every chrome control (seek/seekTo/speed/segment-jump) — not just surface taps. (5) `semantics.onClick` added (pointerInput alone has no a11y click action). (6) auto-hide suppressed under TalkBack touch-exploration (avoids removing a focused node from composition). Rejected: speed-CD-needs-value (already interpolated via `player_speed_cd %1$s`); `speedMenuOpen` keying (N/A — variant A, no popup).
```

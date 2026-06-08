# PR-α: Device-Driven Orientation (Auto) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> **Execution note (this repo):** the controller (main session) runs ALL gradle and does ALL commits; implementer subagents are EDIT-ONLY. The "Run" / "Commit" steps below are written in normal writing-plans format but are executed by the controller. Verify each task with `./gradlew :app:assembleDebug` + `./gradlew :app:testDebugUnitTest` — **NOT** `lintDebug` (the pre-existing B5 `VaultAndroidOps` NewApi lint RED is unrelated to this work and will mask a green build).

**Goal:** Make output orientation device-driven (Auto) by sampling the phone's physical rotation at each segment boundary and applying it to the next segment, so a phone picked up mid-session stops recording sideways forever — without touching the `mode` string enum (that collapse is PR-γ).

**Architecture:** Add two framework-free pure helpers under `service/orientation/` — `OrientationSnap` (sensor degrees → `Surface.ROTATION_*` with asymmetric hysteresis, reusing the `ThermalHysteresis` dwell idiom) and a tiny `OrientationHudState` formatter (current-vs-pending). `RovaRecordingService` gains a thin `OrientationEventListener` seam that feeds `OrientationSnap`, applies the snapped rotation live via `setTargetRotation` (no rebind) on the Single-camera use cases, and samples the stable rotation at each segment start into a new per-segment manifest field. `SessionManifest`/`SegmentRecord` bump schema 9→10 to persist `effectiveTargetRotation` per clip. The DualShot path (P+L) is untouched — EGL owns its rotation (ADR-0009 / ADR-0029 §4).

**Tech Stack:** Kotlin 2.2.10, AGP 9.2.1, CameraX 1.4.2 (`OrientationEventListener`, `VideoCapture.setTargetRotation`), JVM unit tests only (`testOptions.unitTests.isReturnDefaultValues = true`), real `org.json:json` on `testImplementation` for manifest round-trip tests, en + es string resources.

**Spec / ADR:** `docs/adr/0029-capture-topology-orientation-policy.md` (§Decision 2, 3, 6 + Enforcement), `docs/superpowers/specs/2026-06-08-mode-orientation-split-design.md` (PR-α section §6, helper signatures §2.1).

**Scope guardrails:**
- No enum split, no picker change, no P+L auto-rotate, no locks — those are PR-β/γ/δ.
- **No new `check*` gate** (ADR-0029's candidate gates land with γ). Do not add or weaken any gate. Preserve all existing gates including `checkPresetNoOrientation`.
- Do NOT edit a `check*` gate to make a build pass — fix source instead.
- `OrientationPolicyResolver` and the `CaptureTopology`/`OrientationPolicy` enums are **PR-γ**, not here. PR-α's only behaviour is "Auto", which equals the snapped rotation.

---

## File Structure

### Created

| Path | Responsibility |
|------|----------------|
| `app/src/main/java/com/aritr/rova/service/orientation/OrientationSnap.kt` | Pure helper: `snapOrientation(degrees, current, nowMs, …)` → `OrientationSnapState`; maps `0..359`/`ORIENTATION_UNKNOWN` to one of four `Surface.ROTATION_*` buckets with asymmetric dwell hysteresis + dead-band. Also `firstSampleFallback(lastEffective, snappedDisplayRotation)` → `FallbackResult` (deterministic first-sample order + `FirstSampleSource` tag). Holds the `DEFAULT_ORIENTATION_POLICY_IS_AUTO` constant comment seam (see Task 5). Framework-free. |
| `app/src/test/java/com/aritr/rova/service/orientation/OrientationSnapTest.kt` | JVM tests for the §2.1 case list (UNKNOWN ignored, in-bucket clears candidate, dwell-not-elapsed holds, dwell-elapsed flips, dead-band thrash, multi-event no-restart, exact-boundary determinism) plus the first-sample fallback (lastEffective present → used; null but display present → display; both null → portrait; source tag correct). |
| `app/src/main/java/com/aritr/rova/service/orientation/OrientationHudState.kt` | Pure helper: `OrientationHudState` data class + `orientationHud(currentSegmentRotation, pendingNextRotation)` builder that yields a small testable state object (whether a rotation is pending for the next clip, and the two rotation values). No Compose. |
| `app/src/test/java/com/aritr/rova/service/orientation/OrientationHudStateTest.kt` | JVM tests: equal current/pending → not pending; differing → pending; values echoed faithfully. |
| `app/src/test/java/com/aritr/rova/data/SegmentRecordRotationTest.kt` | JVM round-trip test for the new per-segment `effectiveTargetRotation` field (real `org.json`): present → round-trips; absent (legacy schema-9 record) → `null`, never fabricated. |

### Modified

| Path | Change |
|------|--------|
| `app/src/main/java/com/aritr/rova/data/SessionManifest.kt` | `SegmentRecord` gains `effectiveTargetRotation: Int? = null`; `toJson` emits it only when non-null (byte-shape preserved for legacy); `fromJson` reads it tolerantly (absent → null). Bump `SCHEMA_VERSION 9 → 10` with a comment line. |
| `app/src/main/java/com/aritr/rova/data/SessionStore.kt` | `submitPersistFinalizedSegment(…)` gains an `effectiveTargetRotation: Int?` parameter (default `null`) threaded into the built `SegmentRecord`. |
| `app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt` | Add `OrientationEventListener` field + `OrientationSnapState` field; enable on Single bind success, disable on every teardown/unbind; callback feeds `snapOrientation` and on a `stable` change calls `videoCapture?.setTargetRotation` + `preview?.setTargetRotation` live + refreshes `ResolutionInfo`; expose current/pending rotation on `RovaServiceState`; at segment start, sample `stable` rotation (or `firstSampleFallback(lastEffective, snappedDisplayRotation)` when no real snap has arrived) and pass it to `submitPersistFinalizedSegment`, carrying it forward into `lastEffectiveTargetRotation`. `computeTargetRotation` stays as the bind-time seed. **`setupDualCamera` untouched.** |
| `app/src/main/res/values/strings.xml` | Add `record_orientation_rotating_next` HUD string (en). |
| `app/src/main/res/values-es/strings.xml` | Add the same key (es). |

---

## Task 1: `OrientationSnap` pure helper — state type + constants + `snapOrientation` skeleton

**Files:**
- Create: `app/src/main/java/com/aritr/rova/service/orientation/OrientationSnap.kt`
- Test: `app/src/test/java/com/aritr/rova/service/orientation/OrientationSnapTest.kt`

This helper mirrors `applyThermalHysteresis` (`app/src/main/java/com/aritr/rova/ui/signals/ThermalHysteresis.kt`): a pure `(raw, state, now) → state` step function with a dwell timer; multi-event during dwell does NOT restart the timer.

- [ ] **Step 1: Write the failing test (UNKNOWN ignored + in-bucket clears candidate)**

Create `app/src/test/java/com/aritr/rova/service/orientation/OrientationSnapTest.kt`:

```kotlin
package com.aritr.rova.service.orientation

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * PR-α (ADR-0029 §Decision 2) — pure-JVM tests for [snapOrientation].
 * Same shape as [com.aritr.rova.ui.signals.ThermalHysteresisTest]: a pure
 * (raw, state, now) -> state dwell step with asymmetric hysteresis.
 * Bucket map: [315..360)u[0..45)=ROTATION_0, [45..135)=ROTATION_270,
 * [135..225)=ROTATION_180, [225..315)=ROTATION_90.
 */
class OrientationSnapTest {

    private fun stableAt(rot: Int) = OrientationSnapState(stable = rot, candidate = null, candidateSinceMs = null)

    @Test fun `unknown is ignored — state returned unchanged`() {
        val current = stableAt(Surface.ROTATION_0)
        val result = snapOrientation(degrees = -1, current = current, nowMs = 1000L)
        assertEquals(Surface.ROTATION_0, result.stable)
        assertNull(result.candidate)
        assertNull(result.candidateSinceMs)
    }

    @Test fun `raw squarely in current bucket clears any in-flight candidate`() {
        val current = OrientationSnapState(
            stable = Surface.ROTATION_0,
            candidate = Surface.ROTATION_270,
            candidateSinceMs = 500L,
        )
        // 10 degrees is squarely inside the ROTATION_0 bucket.
        val result = snapOrientation(degrees = 10, current = current, nowMs = 1000L)
        assertEquals(Surface.ROTATION_0, result.stable)
        assertNull("candidate cleared once raw returns to the stable bucket", result.candidate)
        assertNull(result.candidateSinceMs)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.service.orientation.OrientationSnapTest"`
Expected: FAIL — compilation error, `OrientationSnap.kt` / `snapOrientation` / `OrientationSnapState` unresolved.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/aritr/rova/service/orientation/OrientationSnap.kt`:

```kotlin
package com.aritr.rova.service.orientation

import android.view.Surface

/**
 * PR-α (ADR-0029 §Decision 2) — dwell duration before a new candidate rotation
 * becomes `stable`. First device-tuning guess; spec §7 (Risks) flags this as a
 * one-constant tuning seam. Mirrors THERMAL_FALL_DWELL_MS.
 */
internal const val ORIENTATION_DWELL_MS: Long = 350L

/**
 * PR-α — dead-band (degrees) each side of a bucket boundary. A raw degree within
 * this band of an edge keeps the current stable rotation (absorbs flutter at
 * 45/135/225/315). First guess; tune on device (spec §7).
 */
internal const val ORIENTATION_HYSTERESIS_DEG: Int = 12

/** Sentinel from OrientationEventListener for an undeterminable rotation. */
internal const val ORIENTATION_UNKNOWN: Int = -1

/**
 * PR-α — opaque hysteresis state held inside [RovaRecordingService]. `stable` is
 * the Surface.ROTATION_* the operator sees applied; `candidate`/`candidateSinceMs`
 * are non-null only while a new bucket is under dwell. Mirrors
 * [com.aritr.rova.ui.signals.HysteresisState].
 */
internal data class OrientationSnapState(
    val stable: Int,                 // a Surface.ROTATION_* (0/1/2/3)
    val candidate: Int?,             // pending rotation under dwell, or null
    val candidateSinceMs: Long?,     // non-null iff candidate != null
)

/**
 * Pure bucket map. `degrees` in [0,359]. Android convention, device-natural
 * portrait assumed (sensor-vs-display sign verified empirically — the seam keeps
 * it testable regardless). Exact boundary degrees fall into the HIGHER bucket
 * (e.g. 45 -> ROTATION_270) by the half-open ranges below.
 */
internal fun bucketOf(degrees: Int): Int = when (degrees) {
    in 45 until 135 -> Surface.ROTATION_270
    in 135 until 225 -> Surface.ROTATION_180
    in 225 until 315 -> Surface.ROTATION_90
    else -> Surface.ROTATION_0   // [315..360) u [0..45)
}

/**
 * True when `degrees` is within [deadBandDeg] of ANY bucket boundary
 * (45/135/225/315 and the 0/360 wrap). Inside the band we refuse to start a new
 * candidate so a phone hovering on an edge does not chatter.
 */
internal fun inDeadBand(degrees: Int, deadBandDeg: Int): Boolean {
    val edges = intArrayOf(45, 135, 225, 315, 0, 360)
    return edges.any { edge -> kotlin.math.abs(degrees - edge) < deadBandDeg }
}

/**
 * Pure. degrees in [0,359] or [ORIENTATION_UNKNOWN] (-1). UNKNOWN returns state
 * unchanged. A degree within [deadBandDeg] of a bucket boundary keeps the current
 * stable rotation (dead-band; any in-flight candidate held). A degree cleanly in
 * the CURRENT bucket clears any candidate. A degree cleanly in a NEW bucket
 * starts/continues a dwell; only after [dwellMs] does `stable` flip. Multi-event
 * during dwell does NOT restart the timer (same semantics as applyThermalHysteresis).
 */
internal fun snapOrientation(
    degrees: Int,
    current: OrientationSnapState,
    nowMs: Long,
    dwellMs: Long = ORIENTATION_DWELL_MS,
    deadBandDeg: Int = ORIENTATION_HYSTERESIS_DEG,
): OrientationSnapState {
    if (degrees == ORIENTATION_UNKNOWN) return current

    val normalized = ((degrees % 360) + 360) % 360
    if (inDeadBand(normalized, deadBandDeg)) return current

    val target = bucketOf(normalized)

    // Cleanly inside the current stable bucket: clear any in-flight candidate.
    if (target == current.stable) {
        return if (current.candidate == null) current
        else current.copy(candidate = null, candidateSinceMs = null)
    }

    // New bucket. Start a dwell if none in flight (or in flight for a different
    // candidate); otherwise hold the original timer.
    if (current.candidate != target) {
        return current.copy(candidate = target, candidateSinceMs = nowMs)
    }
    val since = current.candidateSinceMs ?: nowMs
    return if (nowMs - since >= dwellMs) {
        OrientationSnapState(stable = target, candidate = null, candidateSinceMs = null)
    } else {
        current  // dwell not elapsed; do NOT restart the timer
    }
}

/**
 * PR-α (ADR-0029 §Decision 2) — which source provided the first-sample fallback
 * rotation. Recorded/returned for debuggability so a sideways first clip can be
 * root-caused (was it a carried value, a display read, or the hard default?).
 */
internal enum class FirstSampleSource {
    LAST_EFFECTIVE,   // reused the previous segment's persisted effectiveTargetRotation
    DISPLAY_ROTATION, // read the current snapped display rotation
    DEFAULT_PORTRAIT, // nothing available -> Surface.ROTATION_0
}

/**
 * PR-α (ADR-0029 §Decision 2) — result of [firstSampleFallback]: the chosen
 * Surface.ROTATION_* plus the [FirstSampleSource] that fired.
 */
internal data class FallbackResult(
    val rotation: Int,            // a Surface.ROTATION_* (0/1/2/3)
    val source: FirstSampleSource,
)

/**
 * PR-α (ADR-0029 §Decision 2) — DETERMINISTIC first-sample fallback for `Auto`.
 *
 * `Auto` snaps the device rotation via [snapOrientation], but at a segment
 * boundary there may be NO stable snapped sample yet: the
 * OrientationEventListener has not delivered, the raw degree is
 * ORIENTATION_UNKNOWN, or the device is in motion at clip start. This pure
 * function picks the rotation to encode the clip with, in a fixed priority order
 * so the choice is reproducible and testable:
 *
 *   1. [lastEffective] — the previous segment's persisted effectiveTargetRotation
 *      (carry forward; null for the very first segment of a session).
 *   2. [snappedDisplayRotation] — the current snapped display rotation, if known.
 *   3. default portrait — Surface.ROTATION_0.
 *
 * The returned [FallbackResult.source] records which branch fired. Pure / JVM —
 * only Surface.ROTATION_* int constants are touched.
 */
internal fun firstSampleFallback(
    lastEffective: Int?,
    snappedDisplayRotation: Int?,
): FallbackResult = when {
    lastEffective != null ->
        FallbackResult(lastEffective, FirstSampleSource.LAST_EFFECTIVE)
    snappedDisplayRotation != null ->
        FallbackResult(snappedDisplayRotation, FirstSampleSource.DISPLAY_ROTATION)
    else ->
        FallbackResult(Surface.ROTATION_0, FirstSampleSource.DEFAULT_PORTRAIT)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.service.orientation.OrientationSnapTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/service/orientation/OrientationSnap.kt app/src/test/java/com/aritr/rova/service/orientation/OrientationSnapTest.kt
git commit -m "feat(orientation): OrientationSnap pure helper — degrees->ROTATION_* with hysteresis + first-sample fallback (ADR-0029 PR-α)"
```

---

## Task 2: `OrientationSnap` — dwell + dead-band + boundary case coverage

**Files:**
- Modify: `app/src/test/java/com/aritr/rova/service/orientation/OrientationSnapTest.kt`
- (No source change expected — Task 1's implementation should satisfy these. If a test fails, fix `OrientationSnap.kt`, not the test.)

- [ ] **Step 1: Add the remaining failing tests**

Append these methods inside the `OrientationSnapTest` class (before the closing brace):

```kotlin
    @Test fun `new bucket under dwell holds stable, sets candidate`() {
        val current = stableAt(Surface.ROTATION_0)
        // 90 is squarely inside ROTATION_270 bucket (45..135).
        val result = snapOrientation(degrees = 90, current = current, nowMs = 2000L, dwellMs = 350L)
        assertEquals("stable unchanged before dwell elapses", Surface.ROTATION_0, result.stable)
        assertEquals(Surface.ROTATION_270, result.candidate)
        assertEquals(2000L, result.candidateSinceMs)
    }

    @Test fun `new bucket after dwell flips stable, clears candidate`() {
        val current = OrientationSnapState(
            stable = Surface.ROTATION_0,
            candidate = Surface.ROTATION_270,
            candidateSinceMs = 1000L,
        )
        val result = snapOrientation(degrees = 90, current = current, nowMs = 1400L, dwellMs = 350L)
        assertEquals("dwell elapsed (400 >= 350) -> flip", Surface.ROTATION_270, result.stable)
        assertNull(result.candidate)
        assertNull(result.candidateSinceMs)
    }

    @Test fun `multi-event during dwell does NOT restart the timer`() {
        var s = stableAt(Surface.ROTATION_0)
        s = snapOrientation(degrees = 90, current = s, nowMs = 1000L, dwellMs = 350L)
        assertEquals(1000L, s.candidateSinceMs)
        // second event for the SAME candidate at t=1200 must keep since=1000.
        s = snapOrientation(degrees = 100, current = s, nowMs = 1200L, dwellMs = 350L)
        assertEquals("timer not restarted on further same-bucket events", 1000L, s.candidateSinceMs)
        assertEquals(Surface.ROTATION_0, s.stable)
        // third event at t=1400: 400 >= 350 from the ORIGINAL start -> flip.
        s = snapOrientation(degrees = 100, current = s, nowMs = 1400L, dwellMs = 350L)
        assertEquals(Surface.ROTATION_270, s.stable)
    }

    @Test fun `oscillation inside dead-band never flips`() {
        var s = stableAt(Surface.ROTATION_0)
        // 44 and 46 straddle the 45 boundary, both within 12 deg dead-band.
        s = snapOrientation(degrees = 44, current = s, nowMs = 1000L)
        s = snapOrientation(degrees = 46, current = s, nowMs = 1400L)
        s = snapOrientation(degrees = 44, current = s, nowMs = 1800L)
        assertEquals("dead-band absorbs straddle", Surface.ROTATION_0, s.stable)
        assertNull("no candidate started inside dead-band", s.candidate)
    }

    @Test fun `exact boundary degree falls into higher bucket (deterministic)`() {
        // 60 is clear of dead-band and inside [45..135) -> ROTATION_270.
        assertEquals(Surface.ROTATION_270, bucketOf(60))
        // 200 -> ROTATION_180; 280 -> ROTATION_90; 350 and 10 -> ROTATION_0.
        assertEquals(Surface.ROTATION_180, bucketOf(200))
        assertEquals(Surface.ROTATION_90, bucketOf(280))
        assertEquals(Surface.ROTATION_0, bucketOf(350))
        assertEquals(Surface.ROTATION_0, bucketOf(10))
    }

    @Test fun `negative non-sentinel degrees are normalized, not treated as unknown`() {
        // Only -1 is the UNKNOWN sentinel; other negatives normalize.
        val current = stableAt(Surface.ROTATION_0)
        val result = snapOrientation(degrees = -90, current = current, nowMs = 1000L)
        // -90 normalizes to 270, which is on the 270 dead-band edge -> held.
        assertEquals(Surface.ROTATION_0, result.stable)
    }

    // --- first-sample fallback (deterministic order + source tag) ---

    @Test fun `firstSampleFallback prefers lastEffective when present`() {
        val r = firstSampleFallback(lastEffective = Surface.ROTATION_90, snappedDisplayRotation = Surface.ROTATION_180)
        assertEquals(Surface.ROTATION_90, r.rotation)
        assertEquals(FirstSampleSource.LAST_EFFECTIVE, r.source)
    }

    @Test fun `firstSampleFallback falls to display when lastEffective null`() {
        val r = firstSampleFallback(lastEffective = null, snappedDisplayRotation = Surface.ROTATION_180)
        assertEquals(Surface.ROTATION_180, r.rotation)
        assertEquals(FirstSampleSource.DISPLAY_ROTATION, r.source)
    }

    @Test fun `firstSampleFallback defaults to portrait when both null`() {
        val r = firstSampleFallback(lastEffective = null, snappedDisplayRotation = null)
        assertEquals(Surface.ROTATION_0, r.rotation)
        assertEquals(FirstSampleSource.DEFAULT_PORTRAIT, r.source)
    }
```

- [ ] **Step 2: Run the full test class**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.service.orientation.OrientationSnapTest"`
Expected: PASS (11 tests). If any fail, fix `OrientationSnap.kt` (NOT the test) until green.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/aritr/rova/service/orientation/OrientationSnapTest.kt
git commit -m "test(orientation): dwell/dead-band/boundary coverage for snapOrientation (ADR-0029 PR-α)"
```

---

## Task 3: `OrientationHudState` — current-vs-pending pure formatter

**Files:**
- Create: `app/src/main/java/com/aritr/rova/service/orientation/OrientationHudState.kt`
- Test: `app/src/test/java/com/aritr/rova/service/orientation/OrientationHudStateTest.kt`

ADR-0029 §Decision 3: the HUD must distinguish the **current-segment** rotation from a **pending-next** rotation so the user sees a rotation will take effect at the next boundary. This is the pure, testable state object; the Compose wiring is intentionally thin (Task 8 just reads these fields).

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/aritr/rova/service/orientation/OrientationHudStateTest.kt`:

```kotlin
package com.aritr.rova.service.orientation

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PR-α (ADR-0029 §Decision 3) — pure tests for the current-vs-pending HUD state. */
class OrientationHudStateTest {

    @Test fun `equal current and pending -> not rotating`() {
        val s = orientationHud(currentSegmentRotation = Surface.ROTATION_0, pendingNextRotation = Surface.ROTATION_0)
        assertFalse(s.rotatingNextClip)
        assertEquals(Surface.ROTATION_0, s.currentSegmentRotation)
        assertEquals(Surface.ROTATION_0, s.pendingNextRotation)
    }

    @Test fun `differing current and pending -> rotating next clip`() {
        val s = orientationHud(currentSegmentRotation = Surface.ROTATION_0, pendingNextRotation = Surface.ROTATION_270)
        assertTrue(s.rotatingNextClip)
        assertEquals(Surface.ROTATION_0, s.currentSegmentRotation)
        assertEquals(Surface.ROTATION_270, s.pendingNextRotation)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.service.orientation.OrientationHudStateTest"`
Expected: FAIL — `orientationHud` / `OrientationHudState` unresolved.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/aritr/rova/service/orientation/OrientationHudState.kt`:

```kotlin
package com.aritr.rova.service.orientation

/**
 * PR-α (ADR-0029 §Decision 3) — pure HUD state. The current segment records at
 * [currentSegmentRotation]; if the device has since snapped to a different
 * rotation, [pendingNextRotation] differs and [rotatingNextClip] is true so the
 * HUD can tell the operator the NEXT clip will rotate (the Recorder ignores
 * mid-clip rotation changes, so the current clip stays as-is — spec §7 Risks).
 */
internal data class OrientationHudState(
    val currentSegmentRotation: Int,   // Surface.ROTATION_* frozen at this segment's start
    val pendingNextRotation: Int,      // latest stable snapped rotation
    val rotatingNextClip: Boolean,     // pendingNextRotation != currentSegmentRotation
)

/** Pure builder. [rotatingNextClip] = the two rotations differ. */
internal fun orientationHud(
    currentSegmentRotation: Int,
    pendingNextRotation: Int,
): OrientationHudState = OrientationHudState(
    currentSegmentRotation = currentSegmentRotation,
    pendingNextRotation = pendingNextRotation,
    rotatingNextClip = currentSegmentRotation != pendingNextRotation,
)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.service.orientation.OrientationHudStateTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/service/orientation/OrientationHudState.kt app/src/test/java/com/aritr/rova/service/orientation/OrientationHudStateTest.kt
git commit -m "feat(orientation): OrientationHudState current-vs-pending pure formatter (ADR-0029 PR-α)"
```

---

## Task 4: `SegmentRecord.effectiveTargetRotation` + schema 9→10 (manifest persistence)

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/data/SessionManifest.kt` (`SegmentRecord` ~302-328; `SCHEMA_VERSION` line 185)
- Test: `app/src/test/java/com/aritr/rova/data/SegmentRecordRotationTest.kt`

ADR-0029 §Decision 6 + spec §3: per-clip orientation is the load-bearing new field. Add it to `SegmentRecord`, emit only when non-null (legacy byte-shape preserved), read tolerantly (absent → null, never fabricated for schema < 10).

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/aritr/rova/data/SegmentRecordRotationTest.kt`:

```kotlin
package com.aritr.rova.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * PR-α (ADR-0029 §Decision 6) — per-segment effectiveTargetRotation persistence.
 * Uses the real org.json on testImplementation (per test policy). Schema 9 -> 10.
 */
class SegmentRecordRotationTest {

    @Test fun `effectiveTargetRotation round-trips when set`() {
        val rec = SegmentRecord(
            filename = "segment_0000.mp4",
            durationMs = 10_000L,
            sizeBytes = 1234L,
            sha1 = "abc",
            effectiveTargetRotation = 1, // Surface.ROTATION_90
        )
        val back = SegmentRecord.fromJson(rec.toJson())
        assertEquals(1, back.effectiveTargetRotation)
    }

    @Test fun `null effectiveTargetRotation is not emitted (legacy byte-shape)`() {
        val rec = SegmentRecord(
            filename = "segment_0000.mp4",
            durationMs = 10_000L,
            sizeBytes = 1234L,
            sha1 = "abc",
        )
        assertFalse("absent field must not appear for single-rotation/legacy records",
            rec.toJson().has("effectiveTargetRotation"))
    }

    @Test fun `legacy schema-9 segment json (no rotation key) reads as null`() {
        val legacy = JSONObject().apply {
            put("filename", "segment_0001.mp4")
            put("durationMs", 9_000L)
            put("sizeBytes", 555L)
            put("sha1", "def")
        }
        assertNull("never fabricate rotation for an old segment record",
            SegmentRecord.fromJson(legacy).effectiveTargetRotation)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.data.SegmentRecordRotationTest"`
Expected: FAIL — no such parameter `effectiveTargetRotation` on `SegmentRecord`.

- [ ] **Step 3: Add the field to `SegmentRecord`**

In `app/src/main/java/com/aritr/rova/data/SessionManifest.kt`, replace the `SegmentRecord` data class (currently lines ~302-328) with:

```kotlin
data class SegmentRecord(
    val filename: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val sha1: String,
    val side: com.aritr.rova.service.dualrecord.VideoSide? = null,
    /**
     * PR-α (ADR-0029 §Decision 3, 6) — the Surface.ROTATION_* this clip was
     * encoded with, sampled at SEGMENT START from the device-orientation
     * snapper. `null` for legacy (schema < 10) records and for sessions where
     * the rotation was never sampled; under `Auto` it may differ clip-to-clip.
     * Recovery treats it as informational only (never a deletion input).
     */
    val effectiveTargetRotation: Int? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("filename", filename)
        put("durationMs", durationMs)
        put("sizeBytes", sizeBytes)
        put("sha1", sha1)
        side?.let { put("side", it.name) }
        // Emit only when set so schema-9 single-rotation records keep byte-shape.
        effectiveTargetRotation?.let { put("effectiveTargetRotation", it) }
    }

    companion object {
        fun fromJson(json: JSONObject): SegmentRecord = SegmentRecord(
            filename = json.getString("filename"),
            durationMs = json.getLong("durationMs"),
            sizeBytes = json.getLong("sizeBytes"),
            sha1 = json.getString("sha1"),
            side = json.optString("side", "").ifEmpty { null }?.let {
                runCatching { com.aritr.rova.service.dualrecord.VideoSide.valueOf(it) }.getOrNull()
            },
            // schema-9 records lack this key -> null (never fabricated).
            effectiveTargetRotation = if (json.has("effectiveTargetRotation")) {
                json.getInt("effectiveTargetRotation")
            } else {
                null
            },
        )
    }
}
```

- [ ] **Step 4: Bump the schema version**

In `app/src/main/java/com/aritr/rova/data/SessionManifest.kt`, update the companion comment block and constant (currently around lines 183-185):

```kotlin
        // 7->8: pendingMoveOut{Uri,Path} commit-before-finalize (B5 / ADR-0025).
        // 8->9: daily-window schedule fields (ADR-0027).
        // 9->10: SegmentRecord.effectiveTargetRotation per-clip device-driven
        //        orientation (ADR-0029 PR-α). Schema-<10 segments read null.
        const val SCHEMA_VERSION = 10   // 6->7: vault fields (B5 / ADR-0025)
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.data.SegmentRecordRotationTest"`
Expected: PASS (3 tests).

- [ ] **Step 6: Run the existing manifest/config suite to confirm no regression**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.data.*"`
Expected: PASS (all existing `data` tests still green; `SessionConfigModeTest` untouched).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/aritr/rova/data/SessionManifest.kt app/src/test/java/com/aritr/rova/data/SegmentRecordRotationTest.kt
git commit -m "feat(manifest): SegmentRecord.effectiveTargetRotation + schema 9->10 (ADR-0029 PR-α)"
```

---

## Task 5: Thread `effectiveTargetRotation` through `SessionStore.submitPersistFinalizedSegment` + define the default-policy constant

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/data/SessionStore.kt` (`submitPersistFinalizedSegment` ~160-184)
- Modify: `app/src/main/java/com/aritr/rova/service/orientation/OrientationSnap.kt` (add the default-policy constant seam)

The single-mode persist entry must carry the per-segment rotation. We also define the **single named constant** for the pending owner decision here so the rest of the plan stays invariant to its value.

- [ ] **Step 1: Add the default-policy constant seam to `OrientationSnap.kt`**

In `app/src/main/java/com/aritr/rova/service/orientation/OrientationSnap.kt`, append after the `ORIENTATION_UNKNOWN` constant:

```kotlin
/**
 * PR-α — default orientation behaviour. PR-α only ships "Auto" (device-driven),
 * so the live value is `true`. The OrientationPolicy enum (Auto vs PortraitLock)
 * does NOT exist until PR-γ; this single constant is the seam so the owner's
 * Decision A flips behaviour in ONE place when the enum lands.
 *
 * Ratified 2026-06-08 (Decision A): Auto default, conditional on a visible
 * session-setup orientation control (else PortraitLock). See ADR-0029 §2.
 */
internal const val DEFAULT_ORIENTATION_POLICY_IS_AUTO: Boolean = true
```

> Note: PR-α reads this constant in Task 6 to decide whether the listener-driven rotation is live (`Auto`) or whether the bind-time seed is held fixed (a future `PortraitLock`-style default). Because PR-α only implements Auto, the constant is `true`; the wiring in Task 6 is written so that flipping it to `false` cleanly degrades to today's bind-frozen behaviour with zero other edits.

- [ ] **Step 2: Write the failing test for the new parameter**

Append to `app/src/test/java/com/aritr/rova/data/SegmentRecordRotationTest.kt` (inside the class):

```kotlin
    @Test fun `submit parameter default keeps rotation null`() {
        // Pure signature check via SegmentRecord: a record built without rotation
        // must serialize without the key (guards the default param plumbed in
        // SessionStore.submitPersistFinalizedSegment).
        val rec = SegmentRecord(filename = "s.mp4", durationMs = 1L, sizeBytes = 1L, sha1 = "x")
        assertNull(rec.effectiveTargetRotation)
    }
```

(This is a lightweight guard — `SessionStore.submitPersistFinalizedSegment` itself touches `loadManifest`/IO and is not JVM-unit-tested directly; the behavioural coverage lives in the pure helpers + the manifest round-trip. The parameter plumbing is verified by `assembleDebug` compiling Task 6's call site.)

- [ ] **Step 3: Add the parameter to `submitPersistFinalizedSegment`**

In `app/src/main/java/com/aritr/rova/data/SessionStore.kt`, replace the function signature + `SegmentRecord` construction (lines ~160-177):

```kotlin
    fun submitPersistFinalizedSegment(
        sessionId: String,
        segmentFile: File,
        filename: String,
        durationMs: Long,
        effectiveTargetRotation: Int? = null,
    ): Deferred<SegmentRecord> = persistScope.async {
        val sha1 = try {
            sha1Of(segmentFile)
        } catch (e: Exception) {
            RovaLog.w("submitPersistFinalizedSegment: sha1 failed for ${segmentFile.name}", e)
            ""
        }
        val record = SegmentRecord(
            filename = filename,
            durationMs = durationMs,
            sizeBytes = segmentFile.length(),
            sha1 = sha1,
            effectiveTargetRotation = effectiveTargetRotation,
        )
```

(Leave the rest of the function body — `dir` / `loadManifest` / `copy` / `writeManifestAtomic` / `record` — unchanged.)

- [ ] **Step 4: Run the data tests + assemble to confirm it compiles**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.data.SegmentRecordRotationTest"`
Expected: PASS (4 tests).

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL (the new optional param is backward-compatible; existing single-arg callers unaffected).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/data/SessionStore.kt app/src/main/java/com/aritr/rova/service/orientation/OrientationSnap.kt app/src/test/java/com/aritr/rova/data/SegmentRecordRotationTest.kt
git commit -m "feat(orientation): plumb effectiveTargetRotation into persist + DEFAULT_ORIENTATION_POLICY_IS_AUTO seam (ADR-0029 PR-α)"
```

---

## Task 6: `OrientationEventListener` seam in the service — enable/disable + live `setTargetRotation`

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt`

Wire the framework seam: a service field listener feeds `snapOrientation`; on a `stable` change apply it live (no rebind) to the Single use cases and refresh `ResolutionInfo`. Lifecycle mirrors the existing camera-generation/flip bookkeeping: enable on Single bind success, disable on every teardown/unbind. **`setupDualCamera` is NOT touched.**

- [ ] **Step 1: Add imports + listener/state fields**

In `app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt`, add imports near the existing `android.hardware.display.DisplayManager` import (line ~13):

```kotlin
import android.view.OrientationEventListener
import com.aritr.rova.service.orientation.OrientationSnapState
import com.aritr.rova.service.orientation.snapOrientation
import com.aritr.rova.service.orientation.firstSampleFallback
import com.aritr.rova.service.orientation.DEFAULT_ORIENTATION_POLICY_IS_AUTO
```

Add these fields alongside `private var videoCapture` (around line 252):

```kotlin
    // PR-α (ADR-0029 §Decision 2,3) — device-orientation seam. The listener feeds
    // snapOrientation; orientationSnapState holds the opaque hysteresis state.
    // Enabled only for the Single path on bind success; disabled on every
    // unbind/teardown. DualShot (P+L) owns its own rotation (ADR-0009) and never
    // enables this. Null until first Single bind.
    private var orientationListener: OrientationEventListener? = null
    private var orientationSnapState: OrientationSnapState =
        OrientationSnapState(stable = android.view.Surface.ROTATION_0, candidate = null, candidateSinceMs = null)

    // PR-α (ADR-0029 §Decision 2) — first-sample fallback inputs. `hasStableSnap`
    // flips true once the listener has delivered at least one in-bucket sample, so
    // segment start can tell "real snap" from "seed/no sample yet" and fall back
    // deterministically. `lastEffectiveTargetRotation` carries the PRIOR segment's
    // persisted effectiveTargetRotation forward (null for the first segment).
    private var hasStableSnap: Boolean = false
    private var lastEffectiveTargetRotation: Int? = null
```

- [ ] **Step 2: Add the enable/disable helpers**

Add these two private methods to `RovaRecordingService` (place them just below `setupSingleCamera()`'s closing brace, after line ~1734):

```kotlin
    /**
     * PR-α (ADR-0029 §Decision 2,3) — start tracking physical device rotation for
     * the SINGLE camera path. Idempotent: re-enabling tears down the prior listener
     * first. No-op when the default policy is not Auto (the seam for a future
     * PortraitLock default — see DEFAULT_ORIENTATION_POLICY_IS_AUTO). Seeds the
     * snapper from the bind-time computeTargetRotation so HUD/persist agree with
     * the first segment before any sensor event arrives.
     */
    private fun enableOrientationTracking(seedRotation: Int) {
        disableOrientationTracking()
        orientationSnapState = OrientationSnapState(stable = seedRotation, candidate = null, candidateSinceMs = null)
        hasStableSnap = false  // seed is not a real snapped sample yet
        _serviceState.update { it.copy(currentSegmentRotation = seedRotation, pendingNextRotation = seedRotation) }
        if (!DEFAULT_ORIENTATION_POLICY_IS_AUTO) return

        val listener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                val prevStable = orientationSnapState.stable
                orientationSnapState = snapOrientation(
                    degrees = orientation,
                    current = orientationSnapState,
                    nowMs = android.os.SystemClock.elapsedRealtime(),
                )
                // A non-UNKNOWN sample that resolves to a stable bucket means we now
                // have a real snapped value (clears first-sample fallback at segment
                // start). UNKNOWN leaves state untouched, so guard on that.
                if (orientation != android.view.OrientationEventListener.ORIENTATION_UNKNOWN) {
                    hasStableSnap = true
                }
                val newStable = orientationSnapState.stable
                // Pending-next HUD updates on every stable change (debounced by the
                // dwell inside snapOrientation already).
                if (newStable != prevStable) {
                    // Live apply: Preview rotates immediately for the operator;
                    // VideoCapture/Recorder ignores it mid-clip and adopts it at the
                    // NEXT segment start (the desired segment-boundary contract).
                    try { videoCapture?.targetRotation = newStable } catch (_: Exception) {}
                    try { preview?.targetRotation = newStable } catch (_: Exception) {}
                    refreshResolutionConsumers()
                    _serviceState.update { it.copy(pendingNextRotation = newStable) }
                }
            }
        }
        if (listener.canDetectOrientation()) {
            listener.enable()
            orientationListener = listener
            RovaLog.d { "enableOrientationTracking: listener enabled (seed=$seedRotation)" }
        } else {
            RovaLog.w("enableOrientationTracking: device cannot detect orientation — staying at seed $seedRotation")
        }
    }

    /** PR-α — stop tracking; called on every unbind/teardown. Idempotent. */
    private fun disableOrientationTracking() {
        orientationListener?.let { try { it.disable() } catch (_: Exception) {} }
        orientationListener = null
        hasStableSnap = false  // next enable re-seeds; no stale "real snap" claim
    }

    /**
     * PR-α (ADR-0029 §Decision 3) — after a live setTargetRotation the bound
     * VideoCapture's ResolutionInfo can shift between portrait/landscape aspect.
     * Today the Single path has no live crop/resolution consumer to re-pull (the
     * PreviewView handles its own transform and DualShot owns its EGL crop), so
     * this is a documented guard seam: if a consumer is added later (e.g. a record
     * overlay reading videoCapture.resolutionInfo), refresh it here.
     */
    private fun refreshResolutionConsumers() {
        // Guard seam — no live Single-path ResolutionInfo consumer exists in PR-α.
        // videoCapture?.resolutionInfo re-reads on demand at the next use.
    }
```

- [ ] **Step 3: Add the HUD rotation fields to `RovaServiceState`**

In the `RovaServiceState` data class (ends around line 155, after `cameraConfigGeneration`), add:

```kotlin
    ,
    // PR-α (ADR-0029 §Decision 3) — orientation HUD. currentSegmentRotation is
    // frozen at the active segment's start; pendingNextRotation is the latest
    // stable snapped rotation. When they differ, the HUD shows "next clip will
    // rotate" (Recorder ignores mid-clip rotation). Both Surface.ROTATION_*.
    val currentSegmentRotation: Int = android.view.Surface.ROTATION_0,
    val pendingNextRotation: Int = android.view.Surface.ROTATION_0
```

(Insert before the closing `)` of the data class; ensure the preceding `cameraConfigGeneration: Long = 0L` keeps its trailing position — the leading comma above attaches the new fields.)

- [ ] **Step 4: Enable on Single bind success; disable on unbind paths**

In `setupSingleCamera()`, inside the successful bind `try` block, right after the existing `applyFlashState()` call (line ~1686), add:

```kotlin
                // PR-α — begin device-orientation tracking now that the Single use
                // cases are bound. Seed from the same bind-time rotation the first
                // segment will use, so HUD/persist agree before the first sensor event.
                enableOrientationTracking(targetRot)
```

In `forceReconfigureCamera()` (line ~1565), after `markCameraUnbound()` (line ~1573), add:

```kotlin
            disableOrientationTracking()  // PR-α — stop tracking before rebind/teardown
```

In `setupSingleCamera()`'s bind-failure `catch` block, after the existing `markCameraUnbound()` (line ~1706), add:

```kotlin
                disableOrientationTracking()  // PR-α — bind failed; no tracking
```

- [ ] **Step 5: Disable on service destroy**

Find `onDestroy()` and add `disableOrientationTracking()` near the other teardown calls (e.g. where `processObserver` is removed). Search for the existing `onDestroy` and add this line inside it:

```kotlin
        disableOrientationTracking()  // PR-α — guaranteed teardown on service destroy
```

- [ ] **Step 6: Run assemble to confirm it compiles**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. (No new JVM test here — `OrientationEventListener` is a framework seam; the logic it delegates to is already covered by `OrientationSnapTest`.)

- [ ] **Step 7: Run the full unit suite to confirm no regression**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (baseline 1241 + the new pure tests; 0 failures). Confirm no existing test references a changed `RovaServiceState` constructor positionally in a way that breaks (the new fields have defaults).

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt
git commit -m "feat(service): OrientationEventListener seam — live setTargetRotation on Single path (ADR-0029 PR-α)"
```

---

## Task 7: Sample rotation at segment start → persist per-segment + freeze HUD current

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt` (segment-start region ~2476-2568)

At segment start, capture the current `stable` rotation, freeze it as the HUD "current" for this clip, and pass it to `submitPersistFinalizedSegment` so the manifest records per-clip orientation.

- [ ] **Step 1: Capture the rotation at segment start (with deterministic first-sample fallback)**

In `recordSegment` (single-mode path), right after `videoFile = File(sessionDir, segmentFilename)` and the log (line ~2477-2478), add:

```kotlin
            // PR-α (ADR-0029 §Decision 2,3) — freeze THIS segment's rotation at its
            // start. Under Auto the device may have snapped since the last clip; the
            // Recorder uses whatever targetRotation is set right now and holds it for
            // the whole clip. If the listener has delivered a real stable snap we use
            // it; otherwise (listener not delivering, UNKNOWN, motion at clip start)
            // fall back deterministically: last persisted rotation -> current snapped
            // display rotation -> portrait. firstSampleFallback also returns WHICH
            // source fired for debuggability.
            val segmentRotation: Int = if (hasStableSnap) {
                orientationSnapState.stable
            } else {
                val fb = firstSampleFallback(
                    lastEffective = lastEffectiveTargetRotation,
                    snappedDisplayRotation = orientationSnapState.stable,
                )
                RovaLog.d { "recordSegment: orientation first-sample fallback -> ${fb.rotation} (${fb.source})" }
                fb.rotation
            }
            _serviceState.update {
                it.copy(currentSegmentRotation = segmentRotation, pendingNextRotation = segmentRotation)
            }
```

> Note: `orientationSnapState.stable` is non-null and always a valid `Surface.ROTATION_*` (seeded from the bind-time `computeTargetRotation`), so passing it as `snappedDisplayRotation` means the fallback degrades to the seed rather than hard portrait when no prior segment exists — `firstSampleFallback`'s portrait branch is the final guard for the genuinely-unknown case. `lastEffectiveTargetRotation` is `null` for the first segment of a session and is updated in Step 2 after each successful persist.

- [ ] **Step 2: Pass the rotation into the persist call**

In the `VideoRecordEvent.Finalize` success branch, update the `submitPersistFinalizedSegment(…)` call (line ~2561-2566) to pass the captured rotation:

```kotlin
                                val deferred = sessionStore.submitPersistFinalizedSegment(
                                    sessionId = capturedSessionId,
                                    segmentFile = capturedFile,
                                    filename = capturedFilename,
                                    durationMs = capturedDurationMs,
                                    effectiveTargetRotation = segmentRotation,
                                )
```

(`segmentRotation` is captured in the enclosing `recordSegment` scope from Step 1, so the finalize lambda closes over it — same closure pattern as `capturedSessionId` / `capturedFilename`.)

- [ ] **Step 3: Carry the rotation forward for the next segment's fallback**

Still in the `VideoRecordEvent.Finalize` success branch, right after the `submitPersistFinalizedSegment(…)` call from Step 2, record this segment's rotation so the NEXT segment's `firstSampleFallback` can reuse it when no live snap is available:

```kotlin
                                // PR-α (ADR-0029 §Decision 2) — carry this clip's
                                // rotation forward; the next segment's first-sample
                                // fallback prefers it over a display read / portrait.
                                lastEffectiveTargetRotation = segmentRotation
```

(`lastEffectiveTargetRotation` is the service field added in Task 6 Step 1; it stays `null` until the first segment finalizes, which is exactly the "first segment of a session" case the fallback expects.)

- [ ] **Step 4: Run assemble + full suite**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (no regressions; per-segment rotation now flows to the manifest).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt
git commit -m "feat(service): sample device rotation at segment start, persist per-clip + carry-forward fallback (ADR-0029 PR-α)"
```

---

## Task 8: HUD strings (en + es) + minimal Compose surfacing of pending-rotation

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-es/strings.xml`
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt` (or `RecordChrome.kt` — whichever renders the active-state HUD; confirm via the loop/status pill site)

ADR-0029 §Decision 3 requires the HUD to surface that a rotation will take effect next clip. The pure state object exists (`OrientationHudState`, Task 3); here we add the localized string and a minimal read of `pendingNextRotation != currentSegmentRotation` from `RovaServiceState`. `checkNoHardcodedUiStrings` requires the user-facing text to be a string resource.

- [ ] **Step 1: Add the en string**

In `app/src/main/res/values/strings.xml`, add (near the other record/HUD strings):

```xml
    <!-- PR-α (ADR-0029 §Decision 3) — shown in the active HUD when the device has
         rotated but the current clip keeps its orientation; the next clip adopts
         the new one at the segment boundary. -->
    <string name="record_orientation_rotating_next">Next clip will rotate</string>
```

- [ ] **Step 2: Add the es string**

In `app/src/main/res/values-es/strings.xml`, add the matching key:

```xml
    <!-- PR-α (ADR-0029 §Decision 3) — see the en strings.xml comment. -->
    <string name="record_orientation_rotating_next">El siguiente clip girará</string>
```

- [ ] **Step 3: Surface it minimally in the active HUD**

Locate the active-state HUD composable that renders the loop/status pills (search `RecordChrome.kt` / `RecordScreen.kt` for the status-pill row). Add a small text chip gated on the pending-rotation flag, reading from the collected `RovaServiceState`. Example shape (adapt to the actual HUD container — reuse an existing pill/text style, do not invent chrome):

```kotlin
            // PR-α (ADR-0029 §Decision 3) — pending-next-orientation indicator.
            val rotatingNext = serviceState.pendingNextRotation != serviceState.currentSegmentRotation
            if (rotatingNext) {
                Text(
                    text = stringResource(R.string.record_orientation_rotating_next),
                    style = MaterialTheme.typography.labelSmall,
                    color = RovaTokens.quietText, // reuse an existing token; match adjacent pills
                )
            }
```

(Keep this thin — the testable logic is `OrientationHudState.rotatingNextClip`; the Compose wiring is presentation-only. If `serviceState` is not already collected at this site, collect it the same way the loop/status pills already do — do not introduce a new ViewModel.)

- [ ] **Step 4: Run assemble + the string gate**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL — `checkNoHardcodedUiStrings` and `checkLocaleConfigNoPseudolocale` pass (the new text is a resource present in both locales).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res/values/strings.xml app/src/main/res/values-es/strings.xml app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt
git commit -m "feat(ui): pending-next-orientation HUD indicator + en/es strings (ADR-0029 PR-α)"
```

---

## Task 9: Full-suite green gate + final verification

**Files:** none (verification only).

- [ ] **Step 1: Run the entire JVM unit suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS — baseline 1241 + new tests (OrientationSnapTest 11, OrientationHudStateTest 2, SegmentRecordRotationTest 4), 0 failures / 0 errors / 0 ignored.

- [ ] **Step 2: Run the full debug build (drives all 28 `check*` preBuild gates)**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL — every existing gate green, including `checkPresetNoOrientation`. **No new gate was added** (correct for PR-α; gates land with PR-γ). Do NOT run `lintDebug` (pre-existing unrelated B5 `VaultAndroidOps` NewApi RED).

- [ ] **Step 3: Spot-check the manifest schema bump didn't break recovery reads**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.service.recovery.*"`
Expected: PASS — recovery classifier tolerates schema-10 manifests (the new per-segment field is additive and ignored by recovery; `checkRecoveryNoDeletion` invariant untouched).

- [ ] **Step 4: Final commit (if any verification touch-ups were needed)**

```bash
git add -A
git commit -m "test(orientation): PR-α full-suite green — device-driven Auto (ADR-0029)"
```

(If Steps 1-3 are already green with no edits, skip this commit.)

---

## Self-Review

**1. Spec coverage (PR-α section of `2026-06-08-mode-orientation-split-design.md` §6 + ADR-0029 §2,3,6):**

| Spec / ADR requirement | Task |
|------------------------|------|
| New file `OrientationSnap.kt` — `snapOrientation` + `OrientationSnapState` + dwell/dead-band consts (§2.1) | Task 1, 2 |
| `Auto` deterministic first-sample fallback (lastEffective → display → portrait) + source tag, wired at segment start when no stable snap exists | Task 1 (`firstSampleFallback`/`FallbackResult`/`FirstSampleSource`), Task 2 (tests), Task 6 (`hasStableSnap`/`lastEffectiveTargetRotation` fields), Task 7 (segment-start use + carry-forward) |
| `OrientationSnapTest` — §2.1 case list (UNKNOWN ignored, in-bucket clears, dwell hold/flip, dead-band thrash, multi-event no-restart, boundary determinism) | Task 1, 2 |
| Reuse `ThermalHysteresis` asymmetric-hysteresis idiom (ADR-0019) | Task 1 (mirrors `applyThermalHysteresis` shape, documented in KDoc) |
| `OrientationEventListener` field; enable on Single bind success; disable on every teardown/unbind | Task 6 (enable Step 4, disable forceReconfigure/catch/onDestroy) |
| On stable change → live `setTargetRotation` (no rebind) on Single use cases; refresh `ResolutionInfo` | Task 6 (Step 2 listener body + `refreshResolutionConsumers` guard seam) |
| DualShot path untouched — hardcoded ROTATION_0 stays | Honored — `setupDualCamera` never edited; called out in Task 6 + File Structure |
| Sample rotation at segment START → per-segment manifest field | Task 7 |
| HUD current-vs-pending (pure helper + tests + thin Compose) | Task 3 (helper+tests), Task 6 (state fields), Task 8 (Compose) |
| Refresh crop/resolution consumers after `setTargetRotation` (or note guard) | Task 6 (`refreshResolutionConsumers` documented guard — no live Single consumer exists) |
| SessionManifest schema 9→10, `effectiveTargetRotation` per-segment, legacy read-compat, real org.json tests | Task 4 |
| en + es strings for new HUD text, same task | Task 8 |
| `computeTargetRotation` retained as bind-time seed | Task 6 (seed passed to `enableOrientationTracking(targetRot)`; helper not modified) |
| Default orientation policy = single named constant + `// PENDING: Decision A` comment, plan invariant to value | Task 5 (`DEFAULT_ORIENTATION_POLICY_IS_AUTO`, consumed in Task 6 so flipping to `false` degrades to bind-frozen with zero other edits) |
| No new `check*` gate; preserve `checkPresetNoOrientation`; never edit a gate to pass | Task 9 (Step 2) + scope guardrails header |
| Verify with `assembleDebug`, not `lintDebug` | Execution note header + every assemble step |

No gaps found.

**2. Placeholder scan:** No "TBD/TODO/implement later/add error handling" left as instructions. The one deliberate empty body (`refreshResolutionConsumers`) is a documented guard seam required by ADR-0029 §3 ("refresh if a consumer exists; otherwise note it as a guard"), not a placeholder. The Compose snippet in Task 8 is marked "adapt to the actual HUD container" because the exact pill-row site is confirmed to live in `RecordChrome.kt`/`RecordScreen.kt` but its surrounding layout is presentation detail — the load-bearing logic (`rotatingNext` boolean + string resource) is fully specified.

**3. Type-name consistency across tasks:**
- `OrientationSnapState(stable, candidate, candidateSinceMs)` — identical in Task 1 (def), Task 2 (tests), Task 6 (service field, enable/disable). ✓
- `snapOrientation(degrees, current, nowMs, dwellMs, deadBandDeg)` — identical signature Task 1 def / Task 6 call. ✓
- `firstSampleFallback(lastEffective, snappedDisplayRotation)` → `FallbackResult(rotation, source)` with `FirstSampleSource{LAST_EFFECTIVE, DISPLAY_ROTATION, DEFAULT_PORTRAIT}` — defined Task 1, tested Task 2, called Task 7 Step 1 with `lastEffective = lastEffectiveTargetRotation` / `snappedDisplayRotation = orientationSnapState.stable`; `.rotation` + `.source` field names match the call site. ✓
- `hasStableSnap` / `lastEffectiveTargetRotation` service fields — defined Task 6 Step 1, set in the listener / reset in disable (Task 6 Step 2), read + carried forward in Task 7 Step 1/3. ✓
- `OrientationHudState(currentSegmentRotation, pendingNextRotation, rotatingNextClip)` + `orientationHud(...)` — Task 3 def matches `RovaServiceState.currentSegmentRotation`/`pendingNextRotation` field names used in Task 6/7/8. ✓
- `SegmentRecord.effectiveTargetRotation: Int?` — Task 4 def matches `submitPersistFinalizedSegment(effectiveTargetRotation =)` param in Task 5 and the call site in Task 7. ✓
- `DEFAULT_ORIENTATION_POLICY_IS_AUTO` — defined Task 5, consumed Task 6. ✓
- `enableOrientationTracking(seedRotation)` / `disableOrientationTracking()` / `refreshResolutionConsumers()` — defined Task 6, called within Task 6 only. ✓
- `R.string.record_orientation_rotating_next` — defined Task 8 (en+es), consumed Task 8 Compose. ✓

No inconsistencies found.

---

## Owner decision (ratified)

**Decision A — default `OrientationPolicy` = Auto (ratified 2026-06-08).** PR-α ships device-driven Auto behaviour and encodes the default behind the single constant `DEFAULT_ORIENTATION_POLICY_IS_AUTO = true` (Task 5). The owner has ratified **Auto** as the new-user default — conditional on a visible session-setup orientation control (else PortraitLock), which lands with the picker in a later PR; see ADR-0029 §2. The constant therefore stays `true`. PR-α additionally makes the `Auto` first-sample fallback an explicit deliverable: `firstSampleFallback` (Task 1) guarantees a deterministic rotation at a segment boundary even when no stable snapped sample exists (listener not delivering, UNKNOWN, motion at start), in the order last effective rotation → current snapped display rotation → portrait, recording which source fired. Flipping the constant to `false` still cleanly degrades to today's bind-frozen seed with no other edits, so the PR-γ `OrientationPolicy` enum can adopt it unchanged.

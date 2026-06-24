# PR-6b Wall-Clock Playhead Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show "what time of day was this moment recorded" on the in-app video player, sourced from a new per-segment wall-clock start (schema 11→12).

**Architecture:** Per-segment `startedAtWallClock` sampled at segment START and threaded to the finalize handler. Pure helpers resolve per-side ordered wall-starts (filename-sequence ordered) + an approx mask, compute the playhead instant + inter-clip gap, and format a locale/DST-correct time-of-day readout. The player screen renders the readout as a layer over the existing #127 interactive timeline.

**Tech Stack:** Kotlin, Compose, `org.json` (manifest), `java.text.SimpleDateFormat`/`java.util.TimeZone` (pure-JVM time formatting), JUnit JVM tests.

## Global Constraints

- AGP 9.2.1 / Kotlin 2.2.10 / Gradle 9.4.1 / compileSdk 37 / minSdk 24 / Java 11.
- **46 static gates + full `:app:testDebugUnitTest` GREEN at EVERY commit.** Verify via `:app:assembleDebug` (gates fire on preBuild), **NOT** `:app:lintDebug` (pre-existing `VaultAndroidOps:267` NewApi lint failure).
- **Never edit a `check*` task to pass.** No `check*` asserts segment schema; none should need changing.
- JVM unit tests only; `testOptions.unitTests.isReturnDefaultValues = true`. `JSONObject`/`JSONArray` use the real `org.json:json:20231013` on `testImplementation`. Anything touching `android.text.format.*` is a no-op under JVM — use `java.text.*` / `java.util.*` for testable time formatting.
- Pure-helper extraction for all framework-touching logic.
- All user-facing strings in resources (`checkNoHardcodedUiStrings`), en + es (`values/` + `values-es/`). No raw UI string literals.
- **Subagents EDIT-ONLY; the controller runs ALL gradle/tests/commits.** Build WARM (no cache wipe). PowerShell for git.
- Branch: `feat/player-wall-clock-playhead` (already created, spec committed). master tip `fe5b92d`. Push/PR/merge ONLY on explicit owner GO.
- Spec: `docs/superpowers/specs/2026-06-24-player-wall-clock-playhead-design.md`.
- **Spec deviation (approved-improvement):** spec §5.3 proposed hand-rolled `floorMod` modular arithmetic; this plan uses pure `java.text.SimpleDateFormat` + `java.util.TimeZone` instead — same constraints (pure, JVM-testable, DST + locale correct, no desugaring), strictly better, removes manual AM/PM i18n risk. Spec §5.3 to be amended in Task 0.

---

### Task 0: ADR-0032 + spec amendment (docs)

**Files:**
- Create: `docs/adr/0032-per-segment-wall-clock-start.md`
- Modify: `docs/superpowers/specs/2026-06-24-player-wall-clock-playhead-design.md` (§5.3 note the SimpleDateFormat mechanism)

- [ ] **Step 1: Write ADR-0032**

Create `docs/adr/0032-per-segment-wall-clock-start.md`:

```markdown
# ADR-0032 — Per-segment wall-clock start

**Status**: Accepted (2026-06-24)

## Context
The in-app player needs a wall-clock playhead ("what time of day was this moment
recorded"). `config.intervalMinutes` spaces clips over wall-clock hours, but the
merged MP4 plays them back-to-back, so footage-time ≠ real-time. Deriving
capture-time as `startedAt + footageOffset` is wrong across inter-segment gaps.
`SegmentRecord` stored `durationMs` but no per-segment wall-start.

## Decision
Add `SegmentRecord.startedAtWallClock: Long?` (epoch ms), sampled at SEGMENT
START and threaded to the finalize handler that builds the record. Schema
11→12, emit-when-set (legacy/`null` records keep byte-shape). DualShot's
PORTRAIT and LANDSCAPE records of the same loop share the single start stamp
(they record simultaneously). The field is **informational only** — never a
recovery deletion/classification input, mirroring `effectiveTargetRotation`
(ADR-0029 PR-α).

## Consequences
- Legacy (schema <12) sessions and recovered orphan segments (appended by
  `RecoveryScanner` with no start source) have `null` stamps; the player
  synthesizes an approximate start per clip and surfaces it as "approx".
- No new `check*` gate: no schema-invariant gate exists, and recovery
  neutrality is upheld by existing `checkRecoveryNoDeletion` semantics + KDoc.
- Per the amend-first convention this ADR lands before the code.
```

- [ ] **Step 2: Amend spec §5.3**

In `docs/superpowers/specs/2026-06-24-player-wall-clock-playhead-design.md`, append to the §5.3 bullet (the formatter): `> Implementation note (plan Task 0): the formatter uses pure java.text.SimpleDateFormat + java.util.TimeZone (java.*, JVM-testable, DST+locale correct, no desugaring) rather than hand-rolled floorMod — same guarantees, no manual AM/PM.`

- [ ] **Step 3: Commit**

```bash
git add docs/adr/0032-per-segment-wall-clock-start.md docs/superpowers/specs/2026-06-24-player-wall-clock-playhead-design.md
git commit -m "docs(adr): ADR-0032 per-segment wall-clock start (amend-first)"
```

---

### Task 1: Schema field + migration + round-trip tests

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/data/SessionManifest.kt:326-368` (SegmentRecord) + `:188` (SCHEMA_VERSION) + `:177-187` (history comment)
- Test: `app/src/test/java/com/aritr/rova/data/SessionManifestSegmentWallClockTest.kt`

**Interfaces:**
- Produces: `SegmentRecord.startedAtWallClock: Long?` (default `null`); JSON key `"startedAtWallClock"`.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/aritr/rova/data/SessionManifestSegmentWallClockTest.kt`:

```kotlin
package com.aritr.rova.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SessionManifestSegmentWallClockTest {

    @Test fun `round-trips startedAtWallClock when set`() {
        val rec = SegmentRecord("segment_0001.mp4", 30000L, 1234L, "abc", startedAtWallClock = 1_700_000_000_000L)
        val back = SegmentRecord.fromJson(rec.toJson())
        assertEquals(1_700_000_000_000L, back.startedAtWallClock)
    }

    @Test fun `legacy record without key reads null`() {
        val legacy = JSONObject()
            .put("filename", "segment_0001.mp4").put("durationMs", 30000L)
            .put("sizeBytes", 1234L).put("sha1", "abc")
        val rec = SegmentRecord.fromJson(legacy)
        assertNull(rec.startedAtWallClock)
    }

    @Test fun `byte-shape unchanged when null — no key emitted`() {
        val rec = SegmentRecord("segment_0001.mp4", 30000L, 1234L, "abc")
        assertFalse(rec.toJson().has("startedAtWallClock"))
    }

    @Test fun `schema version is 12`() {
        assertEquals(12, SessionManifest.SCHEMA_VERSION)
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.data.SessionManifestSegmentWallClockTest"`
Expected: FAIL — `startedAtWallClock` unresolved / SCHEMA_VERSION is 11.

- [ ] **Step 3: Add the field + JSON + bump**

In `SessionManifest.kt`, add to `SegmentRecord` after `effectiveTargetRotation` (`:339`):

```kotlin
    /**
     * PR-6b (ADR-0032) — epoch-ms wall-clock when this clip STARTED recording,
     * sampled at segment start and threaded to finalize. `null` for legacy
     * (schema < 12) records and recovered orphans with no start source.
     * Informational only — never a recovery deletion/classification input
     * (same status as [effectiveTargetRotation]). DualShot PORTRAIT/LANDSCAPE
     * of the same loop carry the SAME value.
     */
    val startedAtWallClock: Long? = null,
```

In `toJson()` after the `effectiveTargetRotation` line (`:348`):

```kotlin
        // PR-6b — emit only when set so schema-<12 records keep byte-shape.
        startedAtWallClock?.let { put("startedAtWallClock", it) }
```

In `fromJson()` after the `effectiveTargetRotation` block (`:365`):

```kotlin
            // schema-<12 records lack this key -> null (never fabricated).
            startedAtWallClock = if (json.has("startedAtWallClock")) {
                json.getLong("startedAtWallClock")
            } else {
                null
            },
```

Bump `:188`:

```kotlin
        const val SCHEMA_VERSION = 12   // 6->7: vault fields (B5 / ADR-0025)
```

Add to the history comment block before `:188`:

```kotlin
        // 11->12: SegmentRecord.startedAtWallClock per-segment wall-clock start
        //         (ADR-0032). Schema-<12 segments read null.
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.data.SessionManifestSegmentWallClockTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/data/SessionManifest.kt app/src/test/java/com/aritr/rova/data/SessionManifestSegmentWallClockTest.kt
git commit -m "feat(data): SegmentRecord.startedAtWallClock, schema 11->12 (ADR-0032)"
```

---

### Task 2: parseSegmentSequence helper + tests

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/screens/player/SegmentOrdering.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/screens/player/SegmentOrderingTest.kt`

**Interfaces:**
- Produces: `SegmentOrdering.parseSequence(filename: String): Int?` (4-digit group or null); `SegmentOrdering.orderedIndices(filenames: List<String>): List<Int>` — original indices reordered by `(sequence ?: Int.MAX_VALUE)` then stable by original index.

- [ ] **Step 1: Write the failing tests**

Create `SegmentOrderingTest.kt`:

```kotlin
package com.aritr.rova.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SegmentOrderingTest {
    @Test fun `parses dual sequence`() {
        assertEquals(7, SegmentOrdering.parseSequence("segment_0007_P.mp4"))
        assertEquals(7, SegmentOrdering.parseSequence("segment_0007_L.mp4"))
    }
    @Test fun `parses single sequence`() {
        assertEquals(3, SegmentOrdering.parseSequence("segment_0003.mp4"))
    }
    @Test fun `unparseable returns null`() {
        assertNull(SegmentOrdering.parseSequence("weird.mp4"))
    }
    @Test fun `orders out-of-order filenames by sequence`() {
        val names = listOf("segment_0002_P.mp4", "segment_0001_P.mp4", "segment_0003_P.mp4")
        assertEquals(listOf(1, 0, 2), SegmentOrdering.orderedIndices(names))
    }
    @Test fun `stable for unparseable — preserves original order`() {
        val names = listOf("a.mp4", "b.mp4")
        assertEquals(listOf(0, 1), SegmentOrdering.orderedIndices(names))
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.player.SegmentOrderingTest"`
Expected: FAIL — `SegmentOrdering` unresolved.

- [ ] **Step 3: Implement**

Create `SegmentOrdering.kt`:

```kotlin
package com.aritr.rova.ui.screens.player

/**
 * PR-6b — canonical per-side segment ordering for the wall-clock playhead.
 * Mirrors RecoveryScanner.SEGMENT_REGEX's 4-digit sequence
 * (`segment_NNNN[_P|_L].mp4`). The merged MP4 concatenates clips in
 * sequence order; DualShot's two async persist coroutines can append the two
 * sides out of manifest-list order (codex review #2), so the player must
 * order by parsed sequence, not list position, to keep wall-starts aligned
 * with footage.
 */
internal object SegmentOrdering {
    private val SEQUENCE = Regex("""segment_(\d{4})(?:_[PL])?\.mp4$""")

    fun parseSequence(filename: String): Int? =
        SEQUENCE.find(filename)?.groupValues?.get(1)?.toIntOrNull()

    /** Original indices reordered by (sequence ?: MAX) then stably by index. */
    fun orderedIndices(filenames: List<String>): List<Int> =
        filenames.indices.sortedWith(
            compareBy({ parseSequence(filenames[it]) ?: Int.MAX_VALUE }, { it })
        )
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.player.SegmentOrderingTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/player/SegmentOrdering.kt app/src/test/java/com/aritr/rova/ui/screens/player/SegmentOrderingTest.kt
git commit -m "feat(player): SegmentOrdering — sequence-ordered per-side segments"
```

---

### Task 3: WallClockTimeline pure helper + tests

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/screens/player/WallClockTimeline.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/screens/player/WallClockTimelineTest.kt`

**Interfaces:**
- Consumes: ordered `wallStartsMs: List<Long>`, `durationsMs: List<Long>`, `approxMask: List<Boolean>` (all same length, same order — from Task 4 resolver), `positionMs: Long`.
- Produces: `WallClockTimeline.Readout(instantMs: Long, isApprox: Boolean, gapBeforeMs: Long?)`; `WallClockTimeline.readoutAt(...)`; `WallClockTimeline.spansMidnight(firstMs, lastMs, zone): Boolean`.

- [ ] **Step 1: Write the failing tests**

Create `WallClockTimelineTest.kt`:

```kotlin
package com.aritr.rova.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class WallClockTimelineTest {
    // clip0: starts 09:00:00 (t0), 30s; clip1: starts 09:15:00 (t0+900s), 30s
    private val t0 = 1_700_000_000_000L
    private val starts = listOf(t0, t0 + 900_000L)
    private val durs = listOf(30_000L, 30_000L)
    private val noApprox = listOf(false, false)

    @Test fun `instant inside clip0`() {
        val r = WallClockTimeline.readoutAt(starts, durs, noApprox, positionMs = 10_000L)
        assertEquals(t0 + 10_000L, r.instantMs)
        assertFalse(r.isApprox)
        assertNull(r.gapBeforeMs)
    }
    @Test fun `boundary selects next clip at offset 0 with gap`() {
        val r = WallClockTimeline.readoutAt(starts, durs, noApprox, positionMs = 30_000L)
        assertEquals(t0 + 900_000L, r.instantMs)          // clip1 start
        assertEquals(870_000L, r.gapBeforeMs)              // 900s - 30s
    }
    @Test fun `instant inside clip1`() {
        val r = WallClockTimeline.readoutAt(starts, durs, noApprox, positionMs = 45_000L)
        assertEquals(t0 + 900_000L + 15_000L, r.instantMs)
    }
    @Test fun `position at or beyond total selects last clip end`() {
        val r = WallClockTimeline.readoutAt(starts, durs, noApprox, positionMs = 999_000L)
        assertEquals(t0 + 900_000L + 30_000L, r.instantMs)
    }
    @Test fun `approx propagates from selected clip mask`() {
        val r = WallClockTimeline.readoutAt(starts, durs, listOf(false, true), positionMs = 45_000L)
        assertTrue(r.isApprox)
    }
    @Test fun `negative gap suppressed`() {
        // clip1 start earlier than clip0 end (clock went backwards)
        val bad = listOf(t0, t0 + 10_000L)
        val r = WallClockTimeline.readoutAt(bad, durs, noApprox, positionMs = 30_000L)
        assertNull(r.gapBeforeMs)   // -20_000 clamped/suppressed
    }
    @Test fun `empty list is total-safe`() {
        val r = WallClockTimeline.readoutAt(emptyList(), emptyList(), emptyList(), positionMs = 0L)
        assertEquals(0L, r.instantMs)
        assertNull(r.gapBeforeMs)
    }
    @Test fun `zero-duration clip does not divide by zero`() {
        val r = WallClockTimeline.readoutAt(listOf(t0, t0 + 5_000L), listOf(0L, 10_000L), listOf(false, false), positionMs = 0L)
        assertEquals(t0 + 5_000L, r.instantMs)  // 0-dur clip0 ends at pos 0 -> select clip1 start
    }
    @Test fun `spansMidnight true across day boundary`() {
        val utc = TimeZone.getTimeZone("UTC")
        // 23:59:50 UTC -> +20s crosses midnight
        val late = 1_700_006_390_000L
        assertTrue(WallClockTimeline.spansMidnight(late, late + 20_000L, utc))
    }
    @Test fun `spansMidnight false within day`() {
        val utc = TimeZone.getTimeZone("UTC")
        assertFalse(WallClockTimeline.spansMidnight(t0, t0 + 60_000L, utc))
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.player.WallClockTimelineTest"`
Expected: FAIL — `WallClockTimeline` unresolved.

- [ ] **Step 3: Implement**

Create `WallClockTimeline.kt`:

```kotlin
package com.aritr.rova.ui.screens.player

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * PR-6b (ADR-0032) — pure math for the wall-clock playhead. Given per-clip
 * resolved wall-starts (sequence-ordered, same order as footage), clip
 * durations, an approx mask, and a flat playback position, computes the
 * recorded instant + whether an inter-clip gap precedes the current clip.
 *
 * Boundary contract (matches SegmentedTimelineMath): at an internal clip
 * boundary, select the NEXT clip with intra-offset 0; at/after total
 * duration, select the LAST clip at its end. gapBeforeMs belongs to the
 * SELECTED clip; a negative value (clock/DST/manual adjust) is suppressed —
 * never rendered as a "gap".
 */
internal object WallClockTimeline {

    data class Readout(val instantMs: Long, val isApprox: Boolean, val gapBeforeMs: Long?)

    fun readoutAt(
        wallStartsMs: List<Long>,
        durationsMs: List<Long>,
        approxMask: List<Boolean>,
        positionMs: Long,
    ): Readout {
        if (wallStartsMs.isEmpty() || durationsMs.isEmpty()) {
            return Readout(0L, isApprox = true, gapBeforeMs = null)
        }
        val n = minOf(wallStartsMs.size, durationsMs.size)
        val total = (0 until n).sumOf { durationsMs[it].coerceAtLeast(0L) }
        val clamped = positionMs.coerceIn(0L, total)

        // Select clip: first clip whose cumulative END is strictly > clamped;
        // at exact boundary clamped == end advances to next clip (offset 0);
        // at/after total, last clip.
        var consumed = 0L
        var idx = n - 1
        var clipStart = 0L
        for (i in 0 until n) {
            val dur = durationsMs[i].coerceAtLeast(0L)
            val end = consumed + dur
            if (clamped < end || (i == n - 1)) {
                idx = i
                clipStart = consumed
                break
            }
            consumed = end
        }
        val intraOffset = (clamped - clipStart).coerceAtLeast(0L)
        val instant = wallStartsMs[idx] + intraOffset

        val gap: Long? = if (idx > 0) {
            val prevEnd = wallStartsMs[idx - 1] + durationsMs[idx - 1].coerceAtLeast(0L)
            val raw = wallStartsMs[idx] - prevEnd
            if (raw > 0L) raw else null
        } else null

        val isApprox = approxMask.getOrElse(idx) { true }
        return Readout(instant, isApprox, gap)
    }

    /** True when first/last instants fall on different local calendar days. */
    fun spansMidnight(firstInstantMs: Long, lastInstantMs: Long, zone: TimeZone): Boolean {
        val dayFmt = SimpleDateFormat("yyyyDDD", Locale.US).apply { timeZone = zone }
        return dayFmt.format(Date(firstInstantMs)) != dayFmt.format(Date(lastInstantMs))
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.player.WallClockTimelineTest"`
Expected: PASS (10 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/player/WallClockTimeline.kt app/src/test/java/com/aritr/rova/ui/screens/player/WallClockTimelineTest.kt
git commit -m "feat(player): WallClockTimeline pure playhead math (ADR-0032)"
```

---

### Task 4: Resolver — ordered wall-starts + approx mask into PlayerUiState.Ready

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/player/PlayerUiState.kt:28-45` (add fields to Ready)
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/player/PlayerUriResolver.kt:180-204` (build ordered durations+wall-starts+mask)
- Test: `app/src/test/java/com/aritr/rova/ui/screens/player/PlayerUriResolverWallClockTest.kt`

**Interfaces:**
- Consumes: `SegmentOrdering.orderedIndices`, `SegmentRecord.startedAtWallClock`, `manifest.startedAt`.
- Produces: `PlayerUiState.Ready.segmentWallStartsMs: List<Long>`, `PlayerUiState.Ready.wallStartIsApproxMask: List<Boolean>` (parallel to `segmentDurationsMs`, sequence-ordered).

- [ ] **Step 1: Write the failing tests**

Create `PlayerUriResolverWallClockTest.kt`:

```kotlin
package com.aritr.rova.ui.screens.player

import com.aritr.rova.data.ExportState
import com.aritr.rova.data.ExportTier
import com.aritr.rova.data.SegmentRecord
import com.aritr.rova.data.SessionConfig
import com.aritr.rova.data.SessionManifest
import com.aritr.rova.service.dualrecord.VideoSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerUriResolverWallClockTest {

    private fun cfg(topology: String = "Single") = SessionConfig(
        durationSeconds = 30, intervalMinutes = 15, resolution = "FHD", loopCount = 2,
        captureTopology = topology,
    )

    private fun manifest(segments: List<SegmentRecord>, topology: String = "Single") = SessionManifest(
        sessionId = "s1", startedAt = 1_700_000_000_000L, config = cfg(topology),
        segments = segments, exportTier = ExportTier.TIER2_API26_28,
    ).copy(exportState = ExportState.FINALIZED, publicTargetPath = "/movies/Rova/s1.mp4")

    @Test fun `exact stamps preserved, mask all false`() {
        val t0 = 1_700_000_000_000L
        val m = manifest(listOf(
            SegmentRecord("segment_0001.mp4", 30000L, 1L, "a", startedAtWallClock = t0),
            SegmentRecord("segment_0002.mp4", 30000L, 1L, "b", startedAtWallClock = t0 + 900_000L),
        ))
        val r = PlayerUriResolver.resolve(m) as PlayerUiState.Ready
        assertEquals(listOf(t0, t0 + 900_000L), r.segmentWallStartsMs)
        assertEquals(listOf(false, false), r.wallStartIsApproxMask)
    }

    @Test fun `legacy null stamps synthesize from startedAt, mask all true`() {
        val t0 = 1_700_000_000_000L
        val m = manifest(listOf(
            SegmentRecord("segment_0001.mp4", 30000L, 1L, "a"),
            SegmentRecord("segment_0002.mp4", 30000L, 1L, "b"),
        ))
        val r = PlayerUriResolver.resolve(m) as PlayerUiState.Ready
        assertEquals(listOf(t0, t0 + 30_000L), r.segmentWallStartsMs)   // chain from startedAt
        assertEquals(listOf(true, true), r.wallStartIsApproxMask)
    }

    @Test fun `mixed null — exact preserved, only orphan synthesized (codex #1)`() {
        val t0 = 1_700_000_000_000L
        val m = manifest(listOf(
            SegmentRecord("segment_0001.mp4", 30000L, 1L, "a", startedAtWallClock = t0),
            SegmentRecord("segment_0002.mp4", 30000L, 1L, "b"),  // recovered orphan
        ))
        val r = PlayerUriResolver.resolve(m) as PlayerUiState.Ready
        assertEquals(t0, r.segmentWallStartsMs[0])                       // exact preserved
        assertEquals(t0 + 30_000L, r.segmentWallStartsMs[1])            // synth = prevStart+prevDur
        assertEquals(listOf(false, true), r.wallStartIsApproxMask)
    }

    @Test fun `DualShot orders per-side by sequence, not list order (codex #2)`() {
        val t0 = 1_700_000_000_000L
        // manifest interleaves + out of order: P2, L1, P1, L2
        val m = manifest(listOf(
            SegmentRecord("segment_0002_P.mp4", 30000L, 1L, "p2", VideoSide.PORTRAIT, startedAtWallClock = t0 + 900_000L),
            SegmentRecord("segment_0001_L.mp4", 30000L, 1L, "l1", VideoSide.LANDSCAPE, startedAtWallClock = t0),
            SegmentRecord("segment_0001_P.mp4", 30000L, 1L, "p1", VideoSide.PORTRAIT, startedAtWallClock = t0),
            SegmentRecord("segment_0002_L.mp4", 30000L, 1L, "l2", VideoSide.LANDSCAPE, startedAtWallClock = t0 + 900_000L),
        ), topology = "DualShot").copy(
            portraitPublicTargetPath = "/movies/Rova/s1_P.mp4",
            landscapePublicTargetPath = "/movies/Rova/s1_L.mp4",
        )
        val r = PlayerUriResolver.resolve(m, VideoSide.PORTRAIT) as PlayerUiState.Ready
        assertEquals(listOf(t0, t0 + 900_000L), r.segmentWallStartsMs)  // P1 then P2
        assertEquals(2, r.segmentDurationsMs.size)
    }

    @Test fun `single-mode order unchanged for in-order list`() {
        val t0 = 1_700_000_000_000L
        val m = manifest(listOf(
            SegmentRecord("segment_0001.mp4", 10000L, 1L, "a", startedAtWallClock = t0),
            SegmentRecord("segment_0002.mp4", 20000L, 1L, "b", startedAtWallClock = t0 + 900_000L),
        ))
        val r = PlayerUriResolver.resolve(m) as PlayerUiState.Ready
        assertEquals(listOf(10000L, 20000L), r.segmentDurationsMs)
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.player.PlayerUriResolverWallClockTest"`
Expected: FAIL — `segmentWallStartsMs` unresolved on Ready.

- [ ] **Step 3: Add fields to Ready**

In `PlayerUiState.kt`, add to `Ready` after `totalDurationFromSegmentsMs` (`:44`):

```kotlin
        ,
        /**
         * PR-6b (ADR-0032) — per-clip wall-clock starts, sequence-ordered and
         * parallel to [segmentDurationsMs]. Always fully populated: exact where
         * the segment carried [com.aritr.rova.data.SegmentRecord.startedAtWallClock],
         * else synthesized (see [wallStartIsApproxMask]).
         */
        val segmentWallStartsMs: List<Long>,
        /**
         * PR-6b — parallel to [segmentWallStartsMs]; true where the wall-start
         * was synthesized (legacy schema <12 or recovered orphan). The player
         * shows an "approx" marker when the current clip's entry is true.
         */
        val wallStartIsApproxMask: List<Boolean>
```

- [ ] **Step 4: Build ordered wall-starts in the resolver**

In `PlayerUriResolver.kt`, replace the `segmentDurations` block (`:180-184`) and the `Ready(...)` return (`:196-204`) with:

```kotlin
        val sideSegments = if (isPlusL && side != null) {
            manifest.segments.filter { it.side == side }
        } else {
            manifest.segments
        }
        if (sideSegments.isEmpty()) {
            return PlayerUiState.Unavailable(UiText.Str(R.string.player_unavailable_incomplete))
        }
        // PR-6b (ADR-0032 / codex #2) — order by parsed filename sequence so
        // wall-starts align with footage concatenation, not async append order.
        val order = SegmentOrdering.orderedIndices(sideSegments.map { it.filename })
        val ordered = order.map { sideSegments[it] }
        val segmentDurations = ordered.map { it.durationMs }
        // PR-6b — resolve per-clip wall-starts: exact where present, else
        // synthesize chaining from the last resolved start (codex #1: preserves
        // exact clips; only synthesizes the missing ones — never all-or-nothing).
        val wallStarts = ArrayList<Long>(ordered.size)
        val approxMask = ArrayList<Boolean>(ordered.size)
        for ((i, seg) in ordered.withIndex()) {
            val stamp = seg.startedAtWallClock
            if (stamp != null) {
                wallStarts.add(stamp); approxMask.add(false)
            } else {
                val synth = if (i == 0) manifest.startedAt
                            else wallStarts[i - 1] + segmentDurations[i - 1]
                wallStarts.add(synth); approxMask.add(true)
            }
        }
        return PlayerUiState.Ready(
            mediaUri = uri,
            sessionId = manifest.sessionId,
            startedAt = manifest.startedAt,
            segmentDurationsMs = segmentDurations,
            perClipDurationMs = manifest.config.durationSeconds * 1000L,
            totalClips = segmentDurations.size,
            totalDurationFromSegmentsMs = segmentDurations.sum(),
            segmentWallStartsMs = wallStarts,
            wallStartIsApproxMask = approxMask
        )
```

> Note: the old `segmentDurations.isEmpty()` guard (`:193-195`) is now folded into the `sideSegments.isEmpty()` guard above; delete the old guard block to avoid a duplicate.

- [ ] **Step 5: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.player.PlayerUriResolverWallClockTest"`
Expected: PASS (5 tests). Also run existing resolver tests to confirm no regression:
`./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.player.*"`

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/player/PlayerUiState.kt app/src/main/java/com/aritr/rova/ui/screens/player/PlayerUriResolver.kt app/src/test/java/com/aritr/rova/ui/screens/player/PlayerUriResolverWallClockTest.kt
git commit -m "feat(player): resolve sequence-ordered wall-starts + approx mask (ADR-0032)"
```

---

### Task 5: Time-of-day + gap formatters + tests

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/components/RecordHudFormatters.kt` (add two pure functions)
- Modify: `app/src/main/res/values/strings.xml` + `app/src/main/res/values-es/strings.xml` (gap + approx strings)
- Test: `app/src/test/java/com/aritr/rova/ui/components/WallClockFormatterTest.kt`

**Interfaces:**
- Produces: `RecordHudFormatters.formatTimeOfDay(instantMs, zone, locale, is24h, withDate): String`; `RecordHudFormatters.formatWallClockGap(gapMs): UiText`.

- [ ] **Step 1: Add string resources**

In `app/src/main/res/values/strings.xml` (with the other player strings):

```xml
    <string name="player_wallclock_gap_minutes">+%1$d min gap</string>
    <string name="player_wallclock_gap_seconds">+%1$d s gap</string>
    <string name="player_wallclock_approx_prefix">~%1$s</string>
    <string name="player_wallclock_cd">Recorded at %1$s</string>
```

In `app/src/main/res/values-es/strings.xml`:

```xml
    <string name="player_wallclock_gap_minutes">+%1$d min de intervalo</string>
    <string name="player_wallclock_gap_seconds">+%1$d s de intervalo</string>
    <string name="player_wallclock_approx_prefix">~%1$s</string>
    <string name="player_wallclock_cd">Grabado a las %1$s</string>
```

- [ ] **Step 2: Write the failing tests**

Create `WallClockFormatterTest.kt`:

```kotlin
package com.aritr.rova.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class WallClockFormatterTest {
    private val utc = TimeZone.getTimeZone("UTC")
    // 2023-11-14T22:13:20Z = 1_700_000_000_000
    private val t = 1_700_000_000_000L

    @Test fun `24h time of day`() {
        assertEquals("22:13:20", RecordHudFormatters.formatTimeOfDay(t, utc, Locale.US, is24h = true, withDate = false))
    }
    @Test fun `12h time of day`() {
        assertEquals("10:13:20 PM", RecordHudFormatters.formatTimeOfDay(t, utc, Locale.US, is24h = false, withDate = false))
    }
    @Test fun `date prefix when withDate`() {
        assertEquals("Tue 22:13:20", RecordHudFormatters.formatTimeOfDay(t, utc, Locale.US, is24h = true, withDate = true))
    }
    @Test fun `timezone offset applied`() {
        val ny = TimeZone.getTimeZone("America/New_York") // EST -5 in Nov
        assertEquals("17:13:20", RecordHudFormatters.formatTimeOfDay(t, ny, Locale.US, is24h = true, withDate = false))
    }
    @Test fun `gap minutes`() {
        val ui = RecordHudFormatters.formatWallClockGap(900_000L)
        assertEquals(com.aritr.rova.R.string.player_wallclock_gap_minutes, (ui as com.aritr.rova.ui.text.UiText.StrArgs).resId)
        assertEquals(listOf(15), ui.args)
    }
    @Test fun `gap seconds under a minute`() {
        val ui = RecordHudFormatters.formatWallClockGap(45_000L)
        assertEquals(com.aritr.rova.R.string.player_wallclock_gap_seconds, (ui as com.aritr.rova.ui.text.UiText.StrArgs).resId)
        assertEquals(listOf(45), ui.args)
    }
}
```

> If `UiText.StrArgs` field names differ from `resId`/`args`, adjust the asserts to the actual property names (check `ui/text/UiText.kt`).

- [ ] **Step 3: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.components.WallClockFormatterTest"`
Expected: FAIL — `formatTimeOfDay` unresolved.

- [ ] **Step 4: Implement the formatters**

In `RecordHudFormatters.kt`, add inside the object (before the closing brace):

```kotlin
    /**
     * PR-6b (ADR-0032) — time-of-day readout for the wall-clock playhead.
     * Pure java.text/java.util (JVM-testable, DST + locale correct via [zone] +
     * [locale]; the instant's own offset is applied since [zone] resolves DST
     * per-instant). [withDate] prepends a short weekday when the session spans
     * midnight (see [com.aritr.rova.ui.screens.player.WallClockTimeline.spansMidnight]).
     */
    fun formatTimeOfDay(
        instantMs: Long,
        zone: java.util.TimeZone,
        locale: java.util.Locale,
        is24h: Boolean,
        withDate: Boolean,
    ): String {
        val pattern = when {
            withDate && is24h -> "EEE HH:mm:ss"
            withDate -> "EEE h:mm:ss a"
            is24h -> "HH:mm:ss"
            else -> "h:mm:ss a"
        }
        val fmt = java.text.SimpleDateFormat(pattern, locale)
        fmt.timeZone = zone
        return fmt.format(java.util.Date(instantMs))
    }

    /**
     * PR-6b — inter-clip gap label ("+15 min gap"). Caller passes only a
     * positive gap (WallClockTimeline suppresses non-positive); rounds to
     * whole minutes at/over 60 s, else whole seconds.
     */
    fun formatWallClockGap(gapMs: Long): UiText {
        val g = gapMs.coerceAtLeast(0L)
        return if (g >= 60_000L) {
            UiText.StrArgs(R.string.player_wallclock_gap_minutes, listOf((g / 60_000L).toInt()))
        } else {
            UiText.StrArgs(R.string.player_wallclock_gap_seconds, listOf((g / 1_000L).toInt()))
        }
    }
```

- [ ] **Step 5: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.components.WallClockFormatterTest"`
Expected: PASS (6 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/components/RecordHudFormatters.kt app/src/main/res/values/strings.xml app/src/main/res/values-es/strings.xml app/src/test/java/com/aritr/rova/ui/components/WallClockFormatterTest.kt
git commit -m "feat(player): time-of-day + gap formatters (ADR-0032)"
```

---

### Task 6: Service stamping — capture wall-start at segment START, thread to finalize

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt` — single-mode segment loop + finalize (SegmentRecord build ~`:3213`-region for single) and DualShot finalize (PORTRAIT/LANDSCAPE record builds, the two `serviceScope.async(Dispatchers.IO)` closures).

**Interfaces:**
- Consumes: `SegmentRecord.startedAtWallClock` (Task 1).
- Produces: real per-segment stamps in persisted manifests (verified by device smoke; no JVM test — the service is framework-bound and not unit-tested, per house policy).

> **Implementer note:** this is the one non-TDD task (service code is not JVM-testable under `isReturnDefaultValues`). Read ±40 lines around each edit site before editing. The capture MUST be a per-iteration local (codex #3) — never a mutable service field — because finalize callbacks can arrive late after timeout/recovery and a shared field would be clobbered between loop iterations.

- [ ] **Step 1: Capture the start stamp per segment (single + dual loops)**

In the periodic segment loop, immediately BEFORE the `recorder.start(...)` (single) / dual `recorder.start(...)` (`~:3068`) call for the segment, capture a local:

```kotlin
val segmentStartWallClock = System.currentTimeMillis()
```

Thread `segmentStartWallClock` into the finalize handler closure. The finalize is event-driven; carry the value by capturing it in the lambda/closure that builds the `SegmentRecord` (it is already a closure over per-iteration locals like `durationMs`). If the single-mode finalize is dispatched via a handler that does not close over the loop local, store it alongside the other per-segment finalize inputs (e.g. the same structure that carries `durationMs`/`nSeconds` to the build site) — do NOT introduce a service-level `var`.

- [ ] **Step 2: Set the field on every SegmentRecord build**

Single-mode build site and BOTH DualShot build sites (PORTRAIT and LANDSCAPE `serviceScope.async(Dispatchers.IO)` closures, `~:3213`+) — add the argument:

```kotlin
val rec = com.aritr.rova.data.SegmentRecord(
    filename = pFile.name,
    durationMs = durationMs,
    sizeBytes = pFile.length(),
    sha1 = com.aritr.rova.data.SessionStore.sha1Of(pFile),
    side = com.aritr.rova.service.dualrecord.VideoSide.PORTRAIT,
    startedAtWallClock = segmentStartWallClock,   // PR-6b (ADR-0032) — same value for both sides
)
```

The LANDSCAPE record uses the **same** `segmentStartWallClock` (both sides record simultaneously). The single-mode record adds `startedAtWallClock = segmentStartWallClock` (no `side`).

- [ ] **Step 3: Build to verify compile + gates**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL; all 46 `check*` gates pass (they run on preBuild). If `checkAtomicTerminalWriteForbiddenPair` or any recovery gate trips, STOP — the field must not be referenced in any terminal-write or recovery-deletion path; it is set only at segment finalize. Do not edit the gate.

- [ ] **Step 4: Full test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS, baseline + new tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt
git commit -m "feat(service): stamp SegmentRecord.startedAtWallClock at segment start (ADR-0032)"
```

---

### Task 7: UI — render the wall-clock readout on the player

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/player/PlayerScreen.kt` (render readout near the timeline/playhead)
- Read for context: `app/src/main/java/com/aritr/rova/ui/screens/player/SegmentedTimeline.kt`, `PlayerViewModel.kt` (how `PlaybackProgress.positionMs` + `PlayerUiState.Ready` reach the composable)
- Test: covered by the pure helpers (Tasks 3, 5); UI wiring verified by device smoke.

**Interfaces:**
- Consumes: `PlayerUiState.Ready.{segmentWallStartsMs, wallStartIsApproxMask, segmentDurationsMs}`, `PlaybackProgress.positionMs`, `WallClockTimeline.readoutAt`, `WallClockTimeline.spansMidnight`, `RecordHudFormatters.formatTimeOfDay`, `RecordHudFormatters.formatWallClockGap`.

- [ ] **Step 1: Compute the readout at the Compose edge**

In `PlayerScreen.kt`, where `Ready` + the current `positionMs` are in scope (same place the timeline reads `positionMs`), add:

```kotlin
val context = LocalContext.current
val zone = remember { java.util.TimeZone.getDefault() }
val locale = remember(context) { context.resources.configuration.locales[0] }
val is24h = remember(context) { android.text.format.DateFormat.is24HourFormat(context) }
val spansMidnight = remember(ready.segmentWallStartsMs, ready.segmentDurationsMs) {
    val starts = ready.segmentWallStartsMs
    if (starts.isEmpty()) false
    else WallClockTimeline.spansMidnight(
        starts.first(),
        starts.last() + (ready.segmentDurationsMs.lastOrNull() ?: 0L),
        zone,
    )
}
val readout = WallClockTimeline.readoutAt(
    ready.segmentWallStartsMs, ready.segmentDurationsMs, ready.wallStartIsApproxMask, positionMs,
)
val timeText = RecordHudFormatters.formatTimeOfDay(readout.instantMs, zone, locale, is24h, spansMidnight)
val displayText = if (readout.isApprox) {
    stringResource(R.string.player_wallclock_approx_prefix, timeText)
} else timeText
```

- [ ] **Step 2: Render the readout + gap chip**

Place a `Text(displayText, ...)` adjacent to the timeline/playhead (follow the existing HUD text style + `RovaTokens`; reuse the timeline's typography). Set `contentDescription` via `stringResource(R.string.player_wallclock_cd, timeText)` in `semantics`. When `readout.gapBeforeMs != null`, render a small gap chip from `RecordHudFormatters.formatWallClockGap(readout.gapBeforeMs!!).asString()` next to the boundary; gate any gap-appearance animation behind the reduced-motion flag already used by the timeline (per `checkA11yAnimationGated`). Do NOT add `Modifier.blur` (NO-GO #3). Use `SemanticIcon` if an icon is added (per `checkSemanticIconNoRawAlpha`).

- [ ] **Step 3: Build + gates + tests**

Run: `./gradlew :app:assembleDebug` then `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 46 gates pass (esp. `checkNoHardcodedUiStrings`, `checkA11yAnimationGated`, `checkA11yClickableHasRole`), tests PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/player/PlayerScreen.kt
git commit -m "feat(player): render wall-clock time-of-day readout + gap chip (ADR-0032)"
```

---

### Task 8: Full verification + device smoke

**Files:** none (verification only).

- [ ] **Step 1: Full build + gates + suite**

Run: `./gradlew :app:assembleDebug` and `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; all 46 gates pass; full suite 0 failures (baseline + all new tests).

- [ ] **Step 2: Install + device smoke (RZCYA1VBQ2H)**

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Smoke checklist (owner-driven):
1. Record a NEW periodic session (≥2 clips, interval ≥1 min so a real gap exists). Open it in the player → scrub: time-of-day readout updates, jumps forward at the clip boundary, gap chip shows the interval. No "approx" marker (new recording = exact stamps).
2. Open an OLD recording (pre-this-build, schema 11) → readout shows with the "~"/approx marker; no crash.
3. DualShot session (≥2 loops) → open each side; readout coherent, ordered, gap shows.
4. Confirm `:app:lintDebug` is NOT used as the gate (pre-existing VaultAndroidOps NewApi).

- [ ] **Step 3: Report results to owner; await GO before push/PR.**

---

## Self-Review

**Spec coverage:** D1 (start-stamp threaded) → Task 6. D2 (gap label) → Tasks 3+5+7. D3 (HH:MM:SS locale, midnight date) → Task 5. D4 (per-clip approx) → Task 4. D5/§7 (ADR-0032) → Task 0. Schema bump+migration → Task 1. Ordering (codex #2) → Tasks 2+4. Per-clip mask not all-or-nothing (codex #1) → Task 4. floorMod→SimpleDateFormat note (codex #4 superseded) → Task 0/5. Boundary contract + negative-gap clamp (codex #5/#6) → Task 3. Recovery-neutral invariant → Task 0 ADR + Task 6 gate check. Tests (§8) → Tasks 1-5. All covered.

**Placeholder scan:** Task 6 intentionally has no JVM test (service is framework-bound, house policy) — verified by build+smoke; this is explicit, not a placeholder. Task 5 notes a UiText property-name fallback. No "TBD"/"handle edge cases" left.

**Type consistency:** `startedAtWallClock: Long?` (Tasks 1,4,6 consistent). `readoutAt(wallStartsMs, durationsMs, approxMask, positionMs)` (Tasks 3 def, 7 call consistent). `Ready.segmentWallStartsMs`/`wallStartIsApproxMask` (Tasks 4 def, 7 use consistent). `formatTimeOfDay(instantMs, zone, locale, is24h, withDate)` (Tasks 5 def, 7 call consistent). `orderedIndices` (Tasks 2 def, 4 use consistent).

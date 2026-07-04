# Library Bento Compose Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Transcribe the frozen `docs/design/library-bento.html` v3.2.1 into the Library screen — day-sectioned adaptive bento grid, tap-plays tiles, ground-wash sticky headers, orientation glyph grammar, selection-mode details — per ADR-0030 amendment 2026-07-04.

**Architecture:** Pure contracts first (row planner, wash policy, list-index bookkeeping), then tile/header composables, then chrome reflow, then the bento body swap, then selection/sheet/rail rework, then retirement of superseded chrome. The shipped substrate (aggregation, identity, sidecar, day epochs, policies) is reused, never rewritten.

**Tech Stack:** Jetpack Compose (BOM 2025.01.01), Kotlin 2.2.10, single `:app` module, JVM-only unit tests.

**codex-reconciled 2026-07-04:** filtered `collection` drives grouping (not raw rows); day-header labels re-source to `LibraryDateLabels` (grouping's `formatGroupHeader` label is legacy-English); wash policy is header-offset-based (a pinned sticky header IS still the first visible item in Compose); bento row keys carry a membership digest; dual-tile predicate is `sides.size == 2` with a single-tile fallback; day age reuses the DST-safe `LibraryDateLabels` rounding; the old monolithic screen-swap task is split into contracts → chrome → assembly.

## Global Constraints

- Sources of truth: `docs/design/library-bento.html` (v3.2.1 FROZEN) → ADR-0030 amendment 2026-07-04 → `docs/superpowers/specs/2026-07-04-library-bento-compose-spec.md` (frozen values inlined there and repeated per-task here; do NOT invent values).
- **No independent design decisions.** Ambiguity → STOP, report BLOCKED; it routes back to the HTML.
- Verify with `./gradlew :app:assembleDebug` (48 `check*` gates on preBuild) and `./gradlew :app:testDebugUnitTest`. **NEVER `:app:lintDebug`** (pre-existing NewApi RED). Suite baseline **2201 / 0-0-0**; new pure code lands with tests in the same task.
- JVM unit tests only; no Robolectric; composables get no unit tests (house policy) — verification is compile + gates + device pass at the end.
- `checkLibraryNoManifestWrite`: nothing under `ui/library/` (or History/Library files in `ui/screens/`) may call manifest-mutating `SessionStore` APIs. UI metadata via `LibraryMetadataStore` only.
- `checkNoHardcodedUiStrings`: every user-facing literal via resources; new keys land in `values/strings.xml` AND `values-es/strings.xml` in the same task. Copy says "recordings", never "videos".
- `checkA11yClickableHasRole`: every custom `.clickable`/`.combinedClickable` carries literal `role = Role.…`. Targets ≥48dp via `heightIn(min = 48.dp)`/`sizeIn` at each interactive site (accepted `checkA11yTargetSizeToken` pattern; the gate's token scan is curated — do not add new size tokens to `ui/theme` unless reusable).
- `checkSemanticIconNoRawAlpha`: glyph tints via `SemanticIcon(status=…)`; over-media paint via `Canvas`/`drawBehind` with `LibraryColors` locked overlay constants (never `tint = Color.…`).
- `checkA11yAnimationGated`: every new animation respects the reduced-motion seam; wash snaps, stagger and placement animations are skipped under reduced motion.
- NO media autoplay anywhere. Tap = play via the existing `player/{sessionId}?side={side}` route; Player untouched.
- Commit per task; **NO PUSH** (owner GO required).
- Working branch: `feat/library-bento` (spec + ADR + plan commits present).

---

### Task 1: BentoRowPlanner (pure)

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/library/BentoRowPlanner.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/library/BentoRowPlannerTest.kt`

**Interfaces:**
- Consumes: nothing (pure). Day age arrives precomputed (Task 2 provides it).
- Produces: `BentoRowPlanner.RowPattern(spans: List<Int>, heightDp: Int)`, `BentoRowPlanner.plan(isDual: List<Boolean>, dayAge: Int): List<RowPattern>`, `BentoRowPlanner.tierFor(dayAge: Int)`. Tasks 6/8 consume.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.aritr.rova.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BentoRowPlannerTest {

    private fun singles(n: Int) = List(n) { false }

    @Test fun `featured day with 3+ sessions leads with the hero`() {
        val plan = BentoRowPlanner.plan(singles(4), dayAge = 0)
        assertEquals(listOf(6), plan.first().spans)
        assertEquals(208, plan.first().heightDp)
    }

    @Test fun `featured day with 2 sessions renders a pair not a hero`() {
        val plan = BentoRowPlanner.plan(singles(2), dayAge = 0)
        assertEquals(1, plan.size)
        assertEquals(2, plan.first().spans.size) // [3,3] pair — frozen owner ruling
    }

    @Test fun `every plan covers exactly the session count`() {
        for (age in listOf(0, 1, 2, 6, 7, 30)) for (n in 1..9) {
            val plan = BentoRowPlanner.plan(singles(n), age)
            assertEquals("age=$age n=$n", n, plan.sumOf { it.spans.size })
        }
    }

    @Test fun `single leftover takes the tier fill row`() {
        val plan = BentoRowPlanner.plan(singles(1), dayAge = 7)
        assertEquals(listOf(listOf(6)), plan.map { it.spans })
        assertEquals(108, plan.first().heightDp) // archive fill height
    }

    @Test fun `every DualShot session lands on a span of at least 3`() {
        for (age in listOf(0, 2, 7)) for (n in 2..8) for (dualAt in 0 until n) {
            val flags = List(n) { it == dualAt }
            val plan = BentoRowPlanner.plan(flags, age)
            var i = 0
            for (row in plan) for (span in row.spans) {
                if (i < n && flags[i]) assertTrue("age=$age n=$n dualAt=$dualAt", span >= 3)
                i++
            }
        }
    }

    @Test fun `same age same content is deterministic`() {
        assertEquals(BentoRowPlanner.plan(singles(6), 3), BentoRowPlanner.plan(singles(6), 3))
    }

    @Test fun `tier heights match the frozen tables`() {
        assertTrue(BentoRowPlanner.plan(singles(3), 0).drop(1).all { it.heightDp in setOf(152, 164, 192) })
        assertTrue(BentoRowPlanner.plan(singles(6), 3).all { it.heightDp in setOf(148, 128, 104) })
        assertTrue(BentoRowPlanner.plan(singles(6), 9).all { it.heightDp in setOf(92, 108) })
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.BentoRowPlannerTest"`
Expected: FAIL — `Unresolved reference: BentoRowPlanner`.

- [ ] **Step 3: Implement (verbatim port of the frozen `TIERS` + `buildRows`)**

```kotlin
package com.aritr.rova.ui.library

/**
 * Pure port of the frozen bento row planner (docs/design/library-bento.html
 * v3.2.1, `TIERS` + `buildRows`). Values are FROZEN — do not re-derive.
 * Rotation keys on day AGE (stable across filter/search), every DualShot
 * session is guaranteed a span >= 3 slot, a single leftover takes the fill
 * row, and only a featured day with >= 3 sessions leads with the hero.
 * Day age is computed by the caller (LibraryDateLabels — DST-safe).
 */
object BentoRowPlanner {

    data class RowPattern(val spans: List<Int>, val heightDp: Int)

    data class Tier(val hero: RowPattern?, val rows: List<RowPattern>, val fill1: RowPattern)

    private val FEATURED = Tier(
        hero = RowPattern(listOf(6), 208),
        rows = listOf(
            RowPattern(listOf(3, 3), 152),
            RowPattern(listOf(4, 2), 164),
            RowPattern(listOf(2, 4), 164),
        ),
        fill1 = RowPattern(listOf(6), 192),
    )
    private val STANDARD = Tier(
        hero = null,
        rows = listOf(
            RowPattern(listOf(4, 2), 148),
            RowPattern(listOf(3, 3), 128),
            RowPattern(listOf(2, 2, 2), 104),
            RowPattern(listOf(2, 4), 148),
        ),
        fill1 = RowPattern(listOf(6), 148),
    )
    private val ARCHIVE = Tier(
        hero = null,
        rows = listOf(
            RowPattern(listOf(2, 2, 2), 92),
            RowPattern(listOf(3, 3), 92),
        ),
        fill1 = RowPattern(listOf(6), 108),
    )

    fun tierFor(dayAge: Int): Tier = when {
        dayAge <= 1 -> FEATURED
        dayAge <= 6 -> STANDARD
        else -> ARCHIVE
    }

    fun plan(isDual: List<Boolean>, dayAge: Int): List<RowPattern> {
        val tier = tierFor(dayAge)
        val out = ArrayList<RowPattern>()
        var i = 0
        var rot = dayAge
        while (i < isDual.size) {
            val left = isDual.size - i
            if (left == 1) { out.add(tier.fill1); i++; continue }
            if (tier.hero != null && i == 0 && isDual.size >= 3) { out.add(tier.hero); i++; continue }
            val cands = tier.rows.filter { it.spans.size <= left }
            val off = rot % cands.size
            val ordered = cands.subList(off, cands.size) + cands.subList(0, off)
            val pick = ordered.firstOrNull { r ->
                r.spans.withIndex().all { (j, sp) -> sp >= 3 || isDual.getOrNull(i + j) != true }
            } ?: ordered.first()
            out.add(pick)
            i += pick.spans.size
            rot++
        }
        return out
    }
}
```

- [ ] **Step 4: Run to verify pass** — same command, PASS (7 tests).
- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/BentoRowPlanner.kt app/src/test/java/com/aritr/rova/ui/library/BentoRowPlannerTest.kt
git commit -m "feat(library): BentoRowPlanner — frozen tier tables + row planning port (bento Task 1)"
```

---

### Task 2: BentoWashPolicy (offset-based) + DST-safe day age + day-label strings

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/library/BentoWashPolicy.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/library/LibraryDateLabels.kt` (add `dayAge`)
- Modify: `app/src/main/res/values/strings.xml` + `app/src/main/res/values-es/strings.xml`
- Test: `app/src/test/java/com/aritr/rova/ui/library/BentoWashPolicyTest.kt`, extend `LibraryDateLabelsTest.kt`

**Interfaces:**
- Produces: `BentoWashPolicy.pinnedDayEpoch(visibleHeaders: List<Pair<Long, Int>>): Long?` (pairs = dayEpochMillis to offsetPx of each HEADER item currently in `visibleItemsInfo`, in list order); `LibraryDateLabels.dayAge(dayEpochMillis: Long, nowMillis: Long, timeZone: TimeZone): Int` (reuses the file's existing DST-safe rounded day-diff logic at `LibraryDateLabels.kt:50` — read it and mirror the rounding, do NOT do raw 86_400_000 truncation); strings `library_day_today`, `library_day_yesterday`, plural `library_day_count_duration`. Tasks 5/6/8 consume.

**Why offset-based (codex-reconciled):** in Compose, a pinned sticky header is still composed and reported in `visibleItemsInfo` — index-range tests against `firstVisibleItemIndex` are wrong. The sticky mechanism clamps the active header to offset 0 and pushes the outgoing one negative; headers resting below the top have offset > 0. So: **pinned day = the LAST visible header with offset ≤ 0.** This is robust to leading chrome items, month dividers, and viewport-tall rows, and it matches the frozen semantics (wash on from the exact pin, kept while being pushed off, handed to the incoming header when it reaches the top).

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.aritr.rova.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BentoWashPolicyTest {

    @Test fun `no visible headers - nothing pinned`() {
        assertNull(BentoWashPolicy.pinnedDayEpoch(emptyList()))
    }

    @Test fun `header resting below the top is not pinned`() {
        assertNull(BentoWashPolicy.pinnedDayEpoch(listOf(100L to 240)))
    }

    @Test fun `header clamped at the top is pinned`() {
        assertEquals(100L, BentoWashPolicy.pinnedDayEpoch(listOf(100L to 0, 200L to 480)))
    }

    @Test fun `push-off keeps the outgoing wash until the incoming header reaches the top`() {
        // outgoing pushed to -12, incoming still 25px below the top → outgoing owns the wash
        assertEquals(100L, BentoWashPolicy.pinnedDayEpoch(listOf(100L to -12, 200L to 25)))
        // incoming reaches the top → it takes over
        assertEquals(200L, BentoWashPolicy.pinnedDayEpoch(listOf(100L to -37, 200L to 0)))
    }
}
```

And in `LibraryDateLabelsTest.kt` (follow the file's existing fixture style for timezone construction):

```kotlin
    @Test fun `dayAge counts calendar days DST-safely`() {
        // same day → 0; yesterday → 1; a 23h DST-spring day still counts as 1
        // Build epochs with the SAME helper the class uses (dayEpoch) so the
        // test exercises the rounding, not raw division.
        val tz = java.util.TimeZone.getTimeZone("Europe/Berlin")
        val today = LibraryDateLabels.dayEpoch(1_711_843_200_000L, tz) // 2024-03-31 (DST spring)
        val yesterday = LibraryDateLabels.dayEpoch(1_711_756_800_000L, tz) // 2024-03-30
        assertEquals(0, LibraryDateLabels.dayAge(today, 1_711_843_200_000L, tz))
        assertEquals(1, LibraryDateLabels.dayAge(yesterday, 1_711_843_200_000L, tz))
    }
```

- [ ] **Step 2: Run to verify failure** — `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.BentoWashPolicyTest" --tests "com.aritr.rova.ui.library.LibraryDateLabelsTest"` → FAIL (unresolved).

- [ ] **Step 3: Implement**

```kotlin
package com.aritr.rova.ui.library

/**
 * Pinned-state policy for the ground-wash day headers (ADR-0030 amendment
 * 2026-07-04 §4). Input: (dayEpochMillis, offsetPx) of every HEADER item in
 * visibleItemsInfo, in list order. Compose's sticky mechanism clamps the
 * active header to offset 0 and pushes the outgoing one negative; a header
 * resting below the viewport top has offset > 0 and never washes. Derived
 * synchronously from layout info (frozen v3.2 codex High: the wash must
 * never lag a render).
 */
object BentoWashPolicy {
    fun pinnedDayEpoch(visibleHeaders: List<Pair<Long, Int>>): Long? =
        visibleHeaders.lastOrNull { it.second <= 0 }?.first
}
```

`LibraryDateLabels.dayAge`: mirror the existing rounded calendar-day-diff at `:50` (`((todayEpoch - dayEpoch + HALF_DAY) / DAY).toInt()`-style — read the actual constant names in the file) and clamp at 0.

- [ ] **Step 4: Add strings (both locales)**

`values/strings.xml`:

```xml
    <string name="library_day_today">Today</string>
    <string name="library_day_yesterday">Yesterday</string>
    <!-- %1$d = recording count, %2$s = total duration e.g. "18m" -->
    <plurals name="library_day_count_duration">
        <item quantity="one">%1$d recording · %2$s</item>
        <item quantity="other">%1$d recordings · %2$s</item>
    </plurals>
```

`values-es/strings.xml`:

```xml
    <string name="library_day_today">Hoy</string>
    <string name="library_day_yesterday">Ayer</string>
    <plurals name="library_day_count_duration">
        <item quantity="one">%1$d grabación · %2$s</item>
        <item quantity="other">%1$d grabaciones · %2$s</item>
    </plurals>
```

- [ ] **Step 5: Run to verify pass**, then commit

```bash
git add app/src/main/java/com/aritr/rova/ui/library/BentoWashPolicy.kt app/src/main/java/com/aritr/rova/ui/library/LibraryDateLabels.kt app/src/test/java/com/aritr/rova/ui/library/BentoWashPolicyTest.kt app/src/test/java/com/aritr/rova/ui/library/LibraryDateLabelsTest.kt app/src/main/res/values/strings.xml app/src/main/res/values-es/strings.xml
git commit -m "feat(library): offset-based BentoWashPolicy + DST-safe dayAge + day-label strings (bento Task 2)"
```

---

### Task 3: LibraryColors v2 — AA accent pair + state layers (additive)

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/library/LibraryColors.kt`
- Test: extend the existing pure colors test (locate `LibraryColorSpecTest.kt` / `LibraryLatestColorsTest.kt` — follow its palette fixture pattern)

**Interfaces:**
- Consumes: `DialogActionColors.resolve` (`ui/theme/DialogActionColors.kt`) — READ ITS REAL SIGNATURE FIRST; the invariant is "Library accent pair == dialog resolver output for (accent, accent)".
- Produces (new `LibraryColors`/`LibraryColorSpec` fields): `accentFill: Color`, `accentInk: Color`, `fill1: Color`, `fill2: Color`, `press: Color`, `hairline: Color` (textHigh at alphas .05/.08/.12/.06 — frozen). Existing latest-row fields STAY until Task 11 (LibraryListRow still consumes them).

- [ ] **Step 1: Failing test** — accentFill/accentInk equal `DialogActionColors.resolve(accent, accent)`'s fill/label (adapt property names to the real API); the four state layers are `textHigh.copy(alpha = .05f/.08f/.12f/.06f)`.
- [ ] **Step 2: Verify failure** — `./gradlew :app:testDebugUnitTest --tests "*LibraryColor*"`.
- [ ] **Step 3: Implement** additively in `LibraryColorSpec` + `LibraryColors` + `rememberLibraryColors()`.
- [ ] **Step 4: Verify pass** + `./gradlew :app:assembleDebug` (gates: `checkStatusColorLocked`, `checkSingleColorSchemeSource` stay green — derivations only).
- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/LibraryColors.kt app/src/test/java/com/aritr/rova/ui/library
git commit -m "feat(library): LibraryColors v2 — DialogActionColors accent pair + frozen state layers (bento Task 3)"
```

---

### Task 4: BentoTile + orientation glyph + tile semantics

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/library/components/BentoTile.kt`
- Create: `app/src/main/java/com/aritr/rova/ui/library/components/OrientationGlyph.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/library/TileSemantics.kt`
- Modify: strings en+es
- Test: extend `app/src/test/java/com/aritr/rova/ui/library/TileSemanticsTest.kt`

**Interfaces:**
- Consumes: `LibraryRow` (`stableKey,title,dateMillis,durationMs,badge,badgeStopReason,favorite,orientation,sides`), `LibraryOrientation`, `LibraryColors` (Task 3 fields + locked overlay constants), `VideoFrame`, `LibraryIconSpec.badgeGlyph`, `SemanticIcon`, `PressFeedback`, existing strings (`library_orientation_portrait/landscape`, `library_badge_*` — REUSE before adding).
- Produces: `@Composable fun BentoTile(row: LibraryRow, heightDp: Int, span: Int, isLatest: Boolean, selecting: Boolean, selected: Boolean, onPlay: (sideKey: String?) -> Unit, onToggleSelect: () -> Unit, onEnterSelection: () -> Unit, modifier: Modifier = Modifier)`; `TileSemantics.bentoLabel(selecting: Boolean, orientationWord: String?, dayAndTime: String, duration: String, favorite: Boolean, latest: Boolean): String` and `TileSemantics.bentoPaneLabel(selecting: Boolean, side: String, dayAndTime: String, duration: String): String`. Task 8 consumes.

**Dual predicate (codex-reconciled):** the tile renders the diptych **iff `row.sides.size == 2`** (aggregator populates `sides` portrait-first only for two distinct sides). A DualShot session with one surviving side renders as a SINGLE tile (fallback — the frozen spec has no one-pane diptych) with orientation from `row.side`/`row.orientation`. Never index `sides[1]` without the size check.

Frozen anatomy (transcribe, do not restyle): container radius 16dp, 1dp border White@0.08, press scale .97 (120ms, interruptible); bottom scrim per pane: vertical gradient of `Color(0xFF060308)` at alpha .16 (0f) → 0 (.26f) → 0 (.52f) → .58 (1f); diptych = two equal `weight(1f)` panes with a 2dp `Color(0xFF060308)` seam, **Portrait ALWAYS the left pane**, each pane its own clickable `Role.Button` (tap side plays that side; while selecting both toggle); placeholder (single tile AND each pane) = 140° gradient surfaceHi→surface. Overlays at TILE level (whole across the seam): meta pill bottom-left 8dp — 11sp/600 tabular White@0.95 on `Color(0x9E060308)`, 1dp White@0.10 border, pill shape, padding 3×8dp, leading orientation glyph(s) 11dp (gap 5dp to text; duo shows the ▯▭ pair gap 2.5dp), `· duration` (White@0.68) only when span ≥ 3; LATEST chip top-left 8dp (10sp/700, letterSpacing 0.1em, accentFill bg / accentInk text, padding 4×9dp) — when `row.badge != null` the STATUS BADGE (StatusBadgePill pattern + `LibraryIconSpec.badgeGlyph`) takes that slot instead; favorite = gold star in a 24dp `Color(0x9E060308)` dot top-right, indicator-only, hidden while selecting; selection check bottom-right 22dp (off: White@0.16 fill + 1.5dp White@0.7 border; on: accentFill + accentInk check); selected tile: scale .94 + 2dp accentFill ring + `Color(0x59060308)` dim overlay. Long-press anywhere = `onEnterSelection()` + toggle. All interactive targets ≥48dp (`sizeIn`); single tile = ONE merged semantics node; diptych = two pane nodes inside a group described via `library_a11y_dualshot_group`.

`OrientationGlyph(orientation: LibraryOrientation, modifier)` — 11dp `Canvas`: rounded-rect OUTLINE, stroke 1.4×(11/12)dp, 12×12 viewport, PORTRAIT rect (3.4,1,5.2×10), LANDSCAPE transposed (1,3.4,10×5.2), corner radius 1.6, color White@0.95 via Canvas draw (never `tint =` — gate-clean). Null orientation → the caller renders no glyph.

- [ ] **Step 1: Failing semantics tests**

```kotlin
    @Test fun `bento single label leads with orientation and verb play`() {
        val label = TileSemantics.bentoLabel(
            selecting = false, orientationWord = "portrait recording", dayAndTime = "Today 11:42 am",
            duration = "2m", favorite = true, latest = true,
        )
        assertEquals("Play portrait recording, Today 11:42 am, 2m, favorite, latest recording", label)
    }

    @Test fun `bento selection mode swaps the verb`() {
        val label = TileSemantics.bentoLabel(
            selecting = true, orientationWord = null, dayAndTime = "Today 11:42 am",
            duration = "2m", favorite = false, latest = false,
        )
        assertEquals("Select Today 11:42 am, 2m", label)
    }

    @Test fun `bento pane label names the side`() {
        val label = TileSemantics.bentoPaneLabel(selecting = false, side = "Portrait", dayAndTime = "Today 11:42 am", duration = "2m")
        assertEquals("Play Portrait side, Today 11:42 am, 2m", label)
    }
```

(The composable feeds localized words from resources; the pure builder joins. Follow `TileSemantics.describe`'s joining style. Verb words come in AS PARAMETERS if the existing pattern passes resource strings — read `describe` first and match its convention; if `describe` embeds English literals for tests, mirror that.)

- [ ] **Step 2: Verify failure** — `--tests "com.aritr.rova.ui.library.TileSemanticsTest"`.
- [ ] **Step 3: Implement** builders + `OrientationGlyph.kt` + `BentoTile.kt`. New strings only if no existing key fits (`library_a11y_dualshot_group` = "DualShot, %1$s" en / "DualShot, %1$s" es).
- [ ] **Step 4: Verify** — semantics tests PASS; `./gradlew :app:assembleDebug` BUILD SUCCESSFUL (BentoTile unwired yet — compiles standalone, gates green).
- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/components/BentoTile.kt app/src/main/java/com/aritr/rova/ui/library/components/OrientationGlyph.kt app/src/main/java/com/aritr/rova/ui/library/TileSemantics.kt app/src/test/java/com/aritr/rova/ui/library/TileSemanticsTest.kt app/src/main/res/values/strings.xml app/src/main/res/values-es/strings.xml
git commit -m "feat(library): BentoTile + orientation glyph + bento tile semantics (bento Task 4)"
```

---

### Task 5: BentoDayHeader with ground wash

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/library/components/BentoDayHeader.kt`
- Modify: strings en+es (`library_day_select_all_cd` = "Select all in %1$s" / "Seleccionar todo en %1$s")

**Interfaces:**
- Consumes: `LibraryDateLabels.headerLabel` (`DayHeaderKind` → `library_day_today`/`library_day_yesterday`/weekday/absolute — the header label is resolved HERE from `dayEpochMillis`, NOT taken from `LibraryDayGroup.label` which is legacy hardcoded English), `library_day_count_duration` plural, `ReducedMotion` seam, palette top background color, `SemanticIcon`.
- Produces: `@Composable fun BentoDayHeader(dayEpochMillis: Long, nowMillis: Long, recordingCount: Int, totalDurationLabel: String, pinned: Boolean, selecting: Boolean, allSelected: Boolean, onSelectDay: () -> Unit, modifier: Modifier = Modifier)`. Task 8 consumes.

Frozen anatomy: padding 8dp vertical / 16dp horizontal; title 14sp/700/lineHeight 21sp; meta 11.5sp/lh 21sp/textDim; box height locked 37dp — identical pinned/unpinned; heading semantics on the title; selection mode shows a 24dp select-all circle in a ≥48dp target (accentFill when all selected, check via `SemanticIcon`), CD `library_day_select_all_cd`. **Wash**: `drawBehind` vertical gradient of the palette's TOP background color — solid 0→31dp, linear to transparent at 63dp (draws 26dp past the box, over the tiles beneath; sticky headers draw above subsequent items); alpha `animateFloatAsState(if (pinned) 1f else 0f, tween(120 in / 200 out, easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)))`; reduced motion → `snap()`. NO blur, NO border, NO surface fill (ADR amendment §4 — ground, not glass).

- [ ] **Step 1: Implement** exactly as specified.
- [ ] **Step 2: Verify** — `./gradlew :app:assembleDebug` GREEN (`checkA11yAnimationGated` sees the reduced-motion gate; strings en+es in this commit).
- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/components/BentoDayHeader.kt app/src/main/res/values/strings.xml app/src/main/res/values-es/strings.xml
git commit -m "feat(library): BentoDayHeader — ground-wash pinned state, resource labels (bento Task 5)"
```

---

### Task 6: BentoListIndex — the list-shape contract (pure)

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/library/BentoListIndex.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/library/BentoListIndexTest.kt`

**Interfaces:**
- Consumes: `LibraryDayGroup` (existing), `BentoRowPlanner.RowPattern` (Task 1).
- Produces — the SINGLE owner of LazyColumn item bookkeeping, consumed by Task 8 (assembly), the rail, and focus restore:

```kotlin
package com.aritr.rova.ui.library

/**
 * Pure bookkeeping for the bento LazyColumn shape. ONE place computes item
 * order, keys, scrubber segments and focus lookups so assembly, the rail
 * and focus restore can never disagree (codex plan round: the old flattened
 * assumptions in ScrubberIndex/FocusRestorePolicy call sites break with
 * month dividers + 4 leading chrome items).
 *
 * Item order contract (frozen):
 *   [0] recovery/warnings   [1] stats   [2] chips   [3] vault door
 *   then per day (newest first): [monthDivider?] [header] [bentoRow…]
 *   then [endcap].
 */
object BentoListIndex {

    const val LEADING_ITEM_COUNT = 4

    sealed interface Entry {
        data class MonthDivider(val label: String) : Entry
        data class Header(val dayEpochMillis: Long) : Entry
        data class BentoRow(
            val dayEpochMillis: Long,
            val pattern: BentoRowPlanner.RowPattern,
            val memberKeys: List<String>, // stableKeys, in span order
        ) : Entry
    }

    data class Built(
        val entries: List<Entry>,               // day-section entries ONLY (offset by LEADING_ITEM_COUNT in the real list)
        val keyForEntry: List<String>,          // stable LazyColumn key per entry (same size as entries)
        val scrubberSegments: List<ScrubberSegment>, // label + absolute item indices (leading count already applied)
        val itemIndexByStableKey: Map<String, Int>,  // tile stableKey -> absolute LazyColumn item index of its bento row
        val visibleStableKeys: Set<String>,     // every tile key in the built list (selection prune input)
    )

    fun build(
        groups: List<LibraryDayGroup>,
        plans: List<List<BentoRowPlanner.RowPattern>>, // parallel to groups
        monthDividerLabels: List<String?>,             // parallel to groups; non-null = divider BEFORE that day
        scrubberLabels: List<String>,                  // parallel to groups (short day labels)
    ): Built
}
```

Key rules (frozen + codex-reconciled): header key = `"hdr-${dayEpochMillis}"` (existing convention); bento row key = `"row-${dayEpochMillis}-" + memberKeys.joinToString("|").hashCode().toUInt().toString(16)` — **membership digest**, so a row whose composition changes under filtering gets a NEW identity (no false `animateItem` reuse); month divider key = `"month-${label}"`; endcap is the assembly's own trailing item (not in `entries`). `scrubberSegments` = one segment per day, `startItemIndex` = the day's HEADER absolute index (divider attaches to the day BEFORE the header — a rail jump lands on the header), `itemCount` = header + its bento rows (+ its leading divider). `itemIndexByStableKey` maps every member key to its row's ABSOLUTE index (for focus restore: restore scrolls to the row containing the returned key).

- [ ] **Step 1: Write failing tests** covering: absolute indexing with `LEADING_ITEM_COUNT`; divider shifts subsequent indices; segment starts point at headers; `itemIndexByStableKey` finds a mid-day tile's row; membership digest changes the key when a member is removed but the lead tile stays; `visibleStableKeys` = union of members; empty groups → empty Built.

```kotlin
package com.aritr.rova.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BentoListIndexTest {
    private fun row(vararg keys: String) = keys.toList()
    private val p33 = BentoRowPlanner.RowPattern(listOf(3, 3), 152)
    private val p6 = BentoRowPlanner.RowPattern(listOf(6), 192)

    private fun groupOf(epoch: Long, n: Int): LibraryDayGroup {
        // build via the existing LibraryDayGroup constructor — read its shape;
        // rows' stableKeys must be "k$epoch-$i"
        TODO_IN_IMPLEMENTATION() // implementer: use the real constructor, tests must compile against it
    }
    // ... assertions per the rules above (write them against the REAL LibraryDayGroup shape)

    @Test fun `membership digest changes key when a member leaves`() {
        val a = BentoListIndex.build(/* one day, one [3,3] row of k1,k2 */)
        val b = BentoListIndex.build(/* same day, one [6] fill row of k1 only */)
        assertNotEquals(a.keyForEntry.last(), b.keyForEntry.last())
    }
}
```

(NOTE to implementer: the test file sketch above marks ONE intentionally-open construction detail — `LibraryDayGroup`'s real constructor. Everything else (indices, keys, segments, prune set) must be asserted concretely; write ~8 tests. `TODO_IN_IMPLEMENTATION` must NOT survive into the committed test — replace with the real fixture.)

- [ ] **Step 2: Verify failure**, **Step 3: implement `build`**, **Step 4: verify pass** (`--tests "com.aritr.rova.ui.library.BentoListIndexTest"`), plus `./gradlew :app:testDebugUnitTest` (full suite still green — nothing existing consumed yet).
- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/BentoListIndex.kt app/src/test/java/com/aritr/rova/ui/library/BentoListIndexTest.kt
git commit -m "feat(library): BentoListIndex — item order, keys, segments, focus lookup contract (bento Task 6)"
```

---

### Task 7: Chrome reflow — top bar slots, vault door, stats copy, sort pin

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/library/components/LibraryTopBar.kt` (slots: back · title · search · select; REMOVE vault/density/sort icons)
- Create: `app/src/main/java/com/aritr/rova/ui/library/components/VaultDoorRow.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/HistoryViewModel.kt` (pin `LibrarySort.NEWEST` — keep `setSort` internal/test-only)
- Modify: `app/src/main/java/com/aritr/rova/ui/library/LibraryScreen.kt` (top-bar call site + selection entry button wiring; vault door NOT yet placed — Task 8 moves it into the list)
- Modify: strings en+es (`library_stats_filtered` = "%1$d of %2$d recordings" / "%1$d de %2$d grabaciones"; `library_select_open_cd` = "Select recordings" / "Seleccionar grabaciones"; reuse `vault_*` count keys where they exist)
- Test: none new (assembly-only; existing suite must stay green)

**Interfaces:**
- Produces: `@Composable fun VaultDoorRow(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier)` — full-width min-48dp row, 16dp outer gutters, `LibraryColors.fill1` bg + `hairline` 1dp border, radius 14dp, lock glyph via `SemanticIcon`, "Private Vault" 13.5sp/600 textDim, count 11.5sp textDim, chevron, `Role.Button`. New `LibraryTopBar` slots. `HistoryViewModel` sort pinned NEWEST.
- The density toggle DISAPPEARS from the top bar in this task but `libraryDensity` plumbing survives until Task 11 (dead but compiling — single writer removed with its button).

- [ ] **Step 1: Implement** the four modifications. Top-bar select button opens selection mode (existing `SelectionReducer` entry action used by long-press — reuse the same action).
- [ ] **Step 2: Verify** — `./gradlew :app:testDebugUnitTest` full suite GREEN + `./gradlew :app:assembleDebug` GREEN.
- [ ] **Step 3: Commit**

```bash
git add -A app/src/main/java/com/aritr/rova app/src/main/res
git commit -m "feat(library): chrome reflow — top-bar slots, VaultDoorRow, NEWEST pin (bento Task 7)"
```

---

### Task 8: Bento body swap — the assembly

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/library/LibraryScreen.kt` (the `when` body :499-625 region + play wiring + selection prune + focus restore + scrubber feed)

**Interfaces:**
- Consumes: EVERYTHING above — `BentoRowPlanner.plan` + `LibraryDateLabels.dayAge` (Task 1/2), `BentoWashPolicy.pinnedDayEpoch` (Task 2), `BentoTile` (Task 4), `BentoDayHeader` (Task 5), `BentoListIndex.build` (Task 6), `VaultDoorRow` (Task 7), existing `LibraryDayGrouping.groupForSort`, `LatestRowPolicy.latestKey`, `SelectionReducer`, `FocusRestorePolicy`, `LibraryScrubber`, `LibraryFilterChips`, `LibraryUsageLine`, `LibrarySearchField`, `play(rowKey, sideKey?)`.

Assembly requirements (each frozen — no improvisation):
1. **Source = `collection`** (the FILTERED+searched row set the screen already computes — codex Critical#1). Groups: `groupForSort(collection, LibrarySort.NEWEST, nowMillis, …)` with the existing midnight re-stamp pattern. Latest chip: `LatestRowPolicy.latestKey(collection, NEWEST)` AND ONLY when no filter and no search are active. Selection prune: on every filter/search change prune `SelectionReducer` state to `BentoListIndex.Built.visibleStableKeys` (ADR §3).
2. Per group: `plan = BentoRowPlanner.plan(group.rows.map { it.sides.size == 2 }, LibraryDateLabels.dayAge(group.dayEpochMillis, nowMillis, tz))`. Month divider labels: month change between consecutive groups → locale-aware `"MMMM yyyy"` uppercased (reuse `HistoryRowFormatters` formatter conventions). Short rail labels from `dayEpochMillis` via `LibraryDateLabels` (kind → Today/Yesterday/`"MMM d"`).
3. `val built = BentoListIndex.build(groups, plans, dividerLabels, railLabels)` — computed in the same recomposition pass as the items (synchronous wash invariant). LazyColumn: items 0-3 = recovery/warnings (existing composable), stats, chips, `VaultDoorRow`; then `built.entries` via `keyForEntry` (`stickyHeader` for `Entry.Header` → `BentoDayHeader(pinned = entry.dayEpochMillis == pinnedEpoch, …)`; plain `item` for dividers; `item` per `Entry.BentoRow` → `Row(spacedBy(10.dp), Modifier.padding(horizontal = 16.dp).height(pattern.heightDp.dp))` of `BentoTile(Modifier.weight(span.toFloat()))`); trailing endcap item (`"— %1$d RECORDINGS —"` string `library_endcap`, en+es, caps 10.5sp textFaint, padding 26/44dp).
4. **Wash**: `val pinnedEpoch by remember { derivedStateOf { BentoWashPolicy.pinnedDayEpoch(listState.layoutInfo.visibleItemsInfo.mapNotNull { info -> (info.key as? String)?.takeIf { it.startsWith("hdr-") }?.removePrefix("hdr-")?.toLongOrNull()?.let { it to info.offset } }) } }`.
5. **Play wiring**: single tile / single-side-dual fallback → `onPlay(null)` → existing `play(row.stableKey, null)`; diptych panes → `onPlay(row.sides[0/1].stableKey)` (guarded by `sides.size == 2`; index 0 = Portrait = LEFT).
6. **Focus restore (#164)**: target item index = `built.itemIndexByStableKey[returnedKey]`; preserve `FocusRestorePolicy.shouldScroll` (only scroll when not visible). Read the existing call site and keep its contract; adapt only the index source.
7. **Scrubber feed**: `LibraryScrubber(segments = built.scrubberSegments, …)` — the old `ScrubberIndex.segments(labels, rowSizes, leadingItemCount = 1)` call site is REPLACED by `built.scrubberSegments`; `ScrubberIndex`'s pure fns and test stay untouched (Task 6 built segments from the same `ScrubberSegment` type).
8. **Entrance**: boot-only stagger (30ms/item, translateY 14dp + scale .97, 500ms, `CubicBezierEasing(0.2f,0.8f,0.2f,1f)`) on first composition only (`remember` boot flag); later changes use `Modifier.animateItem()` placement. BOTH skipped under `ReducedMotion`.
9. Empty states: keep `LibraryStates`/`FilteredEmptyPolicy` wiring; reuse existing `library_*_empty_*` keys (no new anatomy).
10. Delete-in-place survivors: deferred delete + UNDO (`PendingDelete`), recovery cards, warning strip — untouched behavior, new positions only.

- [ ] **Step 1: Implement the swap** per requirements 1-10.
- [ ] **Step 2: Verify** — `./gradlew :app:assembleDebug` (48 gates) + full `./gradlew :app:testDebugUnitTest` GREEN (existing grouping/policy/scrubber-math tests unaffected; `FocusRestorePolicyTest`/`ScrubberIndexTest` still pass because their pure APIs were not changed — only call sites moved to `BentoListIndex`).
- [ ] **Step 3: Commit**

```bash
git add -A app/src/main/java/com/aritr/rova app/src/main/res
git commit -m "feat(library): bento timeline swap — planner-driven tiles, ground-wash headers (bento Task 8)"
```

---

### Task 9: Selection bar (frozen anatomy) + details sheet rework

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/library/components/LibrarySelectionTopBar.kt` (close · count(live) · All · info(enabled iff exactly 1) · favorite · vault · delete — absorbs the batch actions)
- Modify: `app/src/main/java/com/aritr/rova/ui/library/components/LibraryItemSheet.kt` (frozen details sheet)
- Modify: `app/src/main/java/com/aritr/rova/ui/library/LibraryScreen.kt` (remove `LibraryBatchBar` from `bottomBar`; sheet entry = selection info; vault/delete drop-from-selection-then-close)
- Modify: strings en+es (`library_action_info_cd` = "Details" / "Detalles"; facts templates `library_facts_line1` = "%1$s · %2$s – %3$s", `library_facts_line2` = "%1$s · %2$s · %3$s · %4$s"; reuse `library_action_share` if present else add "Share"/"Compartir")
- Test: none new (SelectionReducer covers state; sheet is composable)

**Interfaces:**
- Consumes: `SelectionReducer`, `shareItems` (existing `LibraryScreen.kt:237`), `toggleFavorite`/`moveToVault`/deferred delete (existing VM paths), `DialogActionColors.resolve(accent, accent2)` for the hero play-disc gradient, `LibraryColors.accentFill/Ink` for flat fills, clip-count plural (reuse `library_hero_clip_count`/`library_usage_clips` — read which fits).

Frozen sheet: modal bottom sheet radius 26dp top, surfaceHi→surface vertical gradient, grab handle 40×4.5dp, explicit ≥48dp close (32dp `Color(0x9E060308)` dot + X, top-right). Hero 16:9 — single: static frame + centered 56dp play disc (cta gradient + resolved ink, plays via existing route); DualShot (`sides.size == 2`): two panes, per-pane play + "Portrait"/"Landscape" label pills (media scrim, reuse `library_orientation_*`), Portrait left; duration pill bottom-right on the single hero. Title 18sp/700 + pencil → INLINE rename (TextField in place; IME-done/Enter commits; back/Escape cancels rename only) — replaces the `LibraryRenameDialog` call site (file deleted in Task 11). Facts prose 12.5sp/lh 1.7 textDim, two lines: `{Month d, yyyy} · {start} – {end}` / `{duration} · {n} clips · {size} · {Portrait|Landscape|DualShot}`. Action rows ≥50dp: favorite toggle (gold when on), Move to Private Vault (DualShot-disabled — reuse `library_action_vault_unavailable_dualshot`, ADR-0025), **Share** (v3.2.1 — `shareItems` for this session's items via `LibrarySessionKeys.expand`). Danger zone: separated container (danger@7% bg, danger@25% border, radius 18dp), Delete row in danger-text → existing deferred-delete + UNDO. Vault/delete actions drop the key from selection BEFORE closing (frozen v3 codex Critical).

- [ ] **Step 1: Implement** the selection bar rework (targets ≥48dp; count node `liveRegion = LiveRegionMode.Polite`).
- [ ] **Step 2: Implement** the sheet rework.
- [ ] **Step 3: Wire** entry + remove the bottom batch bar (`bottomBar` becomes nothing while selecting).
- [ ] **Step 4: Verify** — `./gradlew :app:assembleDebug` + full suite GREEN.
- [ ] **Step 5: Commit**

```bash
git add -A app/src/main/java/com/aritr/rova app/src/main/res
git commit -m "feat(library): frozen selection bar + details sheet — inline rename, Share, danger zone (bento Task 9)"
```

---

### Task 10: Date rail re-skin

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/library/components/LibraryScrubber.kt`

**Interfaces:**
- Consumes: `built.scrubberSegments` (already fed by Task 8), `ScrubberIndex` pure fns (UNCHANGED), `LibraryColors` (chrome fills + accent pair).
- Produces: frozen rail anatomy on the byte-identical semantics contract.

Frozen anatomy: thumb = 44×48dp chrome tab docked right (rounded LEFT corners 14dp, surfaceHi@94% fill, edge border, calendar-ish glyph textDim via `SemanticIcon`; while dragging accentFill bg + accentInk); bubble = date pill left of the thumb (12.5sp/700, surfaceHi@94%, border, elevation), drag-only, rides the thumb via existing `bubbleTopPx`; idle-hide 1400ms (update `SCRUBBER_IDLE_MS = 1400L`), fade 260ms reduced-motion-snapped (existing pattern); hidden under 2 day segments (existing gate). KEEP byte-identical: slider-on-rail semantics + `setProgress`, separate polite live-region node, drag math, `ScrubberIndex` (PR-C invariant).

- [ ] **Step 1: Implement** (visual params only).
- [ ] **Step 2: Verify** — `--tests "com.aritr.rova.ui.library.ScrubberIndexTest"` PASS + `:app:assembleDebug` GREEN.
- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/components/LibraryScrubber.kt
git commit -m "feat(library): date rail re-skin — chrome tab thumb + drag bubble (bento Task 10)"
```

---

### Task 11: Retire superseded chrome + full verification

**Files:**
- Delete: `app/src/main/java/com/aritr/rova/ui/library/components/LibraryListRow.kt`, `LibrarySortSheet.kt`, `LibraryRenameDialog.kt`, `app/src/main/java/com/aritr/rova/ui/library/LibraryDensityDimens.kt`
- Delete (verify-first): `app/src/main/java/com/aritr/rova/ui/screens/LibraryRow.kt` (legacy VideoItem composable — grep for call sites; if referenced outside itself, STOP and report instead)
- Delete tests: `LibraryDensityDimensTest.kt`
- Modify: `app/src/main/java/com/aritr/rova/data/RovaSettings.kt` (remove the `libraryDensity` accessor at :347 ONLY — leave the orphan pref key, PR-C precedent "harmless"; do NOT invent migration plumbing — codex #6), `HistoryViewModel.kt` (remove `_density/readDensity/toggleDensity/refreshDensity` + `LibraryUiState.density`), `LibraryUiState.kt`, `LibraryColors.kt` (retire `latestContainer/latestEdge/latestEyebrow` + their spec/test lines now that no consumer remains), strings en+es (remove `library_density_*`, `library_sort_*`, `library_eyebrow_latest`, `library_latest_resume` — grep each for zero refs first)

- [ ] **Step 1: Grep every deletion target** (`rg "LibraryListRow|LibrarySortSheet|LibraryRenameDialog|LibraryDensityDimens|libraryDensity|library_density|library_sort_|library_eyebrow_latest|library_latest_resume" app/src`) — every hit must be inside the deletion set; anything else = STOP, report.
- [ ] **Step 2: Delete + trim.** `LibraryDayGrouping.groupForSort` + `LibrarySort` STAY (pure, tested, API-stable; the grouping's legacy `label` field also stays — `BentoDayHeader` just ignores it).
- [ ] **Step 3: Full verification** — `./gradlew :app:testDebugUnitTest` (report exact counts vs baseline 2201 + Tasks 1/2/3/4/6 additions − deleted density tests) then `./gradlew :app:assembleDebug` (48 gates GREEN).
- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor(library): retire density/sort/row-anatomy chrome superseded by bento (bento Task 11)"
```

---

## Post-plan gates (controller, not implementer)

- Final whole-branch review (most capable model) + codex last-pass on the branch diff.
- Device-verify on RZCYA1VBQ2H against a written checklist (tiers render per age, dual panes play correct sides, single-side-dual fallback, wash pin/push-off/unpin, orientation glyphs incl. null-orientation legacy rows, selection + sheet flows incl. inline rename, rail, TalkBack labels, reduced-motion, ≥2 themes incl. Daylight) — then **pixel-match review vs the frozen HTML**.
- Deltas to surface at owner review: batch Share dropped (sheet-only Share), `LibrarySessionConfigDialog` no longer reachable from the sheet, bottom batch bar replaced by the frozen top selection bar.
- NO PUSH without owner GO.

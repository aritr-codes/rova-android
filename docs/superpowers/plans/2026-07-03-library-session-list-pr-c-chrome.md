# Library Session-List PR-C (Chrome) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the mechanical chrome slice of the session-list redesign (handoff `docs/superpowers/handoffs/2026-07-03-NEXT-library-pr-c-chrome.md`, authoritative scope): sticky day headers on day-epoch keys, midnight ON_RESUME label refresh, the LibraryScrubber bubble fix + 16dp thumb, and a top-bar density toggle.

**Architecture:** Four independent mechanical changes to the PR-B single-list Library. Day identity moves from label strings to a DST-safe day-epoch (pure `LibraryDateLabels.dayEpoch` → new `LibraryDayGroup.dayEpochMillis`) so sticky-header keys survive midnight; the stale-label root cause (`nowMillis = remember(ui.rows) {...}` at `LibraryScreen.kt:169`) becomes a state written by the EXISTING ON_RESUME observer; scrubber bubble geometry gets a pure clamp seam in `ScrubberIndex`; the density toggle is a null-gated `LibraryTopBar` slot writing the already-live `libraryDensity` pref through a new single-writer `HistoryViewModel.toggleDensity()`.

**Tech Stack:** Kotlin, Compose (BOM 2025.01.01, foundation `stickyHeader` behind `@OptIn(ExperimentalFoundationApi::class)`), JUnit4 (`org.junit.Assert`), Gradle 9.4.1 (`gradlew.bat` on Windows), no new dependencies.

## Global Constraints

- **Scope is the handoff's list, NOT the spec's chrome section.** NOT in PR-C (all moved to PR-D): side-action glyphs, day-header content changes (weekday/count — header text stays `HistoryRowFormatters.formatGroupHeader` output verbatim), any row-anatomy change, full TalkBack pass.
- JVM unit tests only; no Robolectric/instrumented tests (project CLAUDE.md).
- Verify via `./gradlew :app:assembleDebug` (fires the 48 `check*` gates on preBuild). Do NOT use `:app:lintDebug` (pre-existing `VaultAndroidOps:267` NewApi RED).
- Full JVM suite green: `./gradlew :app:testDebugUnitTest` (baseline 2193; net grows this PR).
- `checkLibraryNoManifestWrite` (ADR-0030 §2): zero `SessionManifest`-mutating calls — everything here is pref/pure/compose only.
- `checkNoHardcodedUiStrings`: every new user-facing string is a resource in BOTH `values/strings.xml` and `values-es/strings.xml`. (The English "Today"/"Yesterday" literals inside `HistoryRowFormatters.formatGroupHeader` are pre-existing and out of scope — do not touch.)
- ADR-0020 WCAG 2.2 AA: toggle target ≥48dp (`IconButton` default), `stateDescription` on the toggle, no new infinite animations (`checkA11yAnimationGated` unaffected). Scrubber slider semantics + polite live-region stay byte-identical.
- NO autoplay anywhere (ADR-0030 amendment trust rule) — nothing here touches media.
- Branch: `feat/library-session-list-pr-c` off master. Commit per task; do NOT push (owner-gated).
- Line numbers are anchors as of master `6526533a` — verify with a read before each edit; content matching wins.

**Documented simplifications (owner visibility):**
1. **Push-up transition = native `stickyHeader` behavior** (the incoming header pushes the stuck one out of the viewport). No custom animation code — this is the Compose-foundation default and exactly the "push-up" the handoff asks for.
2. **`LibraryDayHeader` gains an opaque `surface` background.** A stuck header with the current transparent background would have rows scrolling visibly through its text. Solid `colorScheme.surface` guarantees the existing `onSurfaceVariant`/`outline` text pairs keep their M3 AA contrast (no new token pair → no `TokenContrastTest` change). It reads as a quiet full-width band over the theme-brush background; PR-D restyles header presentation anyway.
3. **Midnight refresh is unconditional**: every ON_RESUME re-stamps `nowMillis` → regroup runs each resume. Grouping recompute was measured ~12ms in the PR #164 cycle and keys are stable (no scroll jump); a day-flip guard would be extra state for no observable win.
4. Toggle glyph = existing `RovaIcons.GridLayout` (no new glyph authoring in PR-C — glyph work is PR-D's). Owner may swap the concept at PR-D.

---

### Task 0: Branch

**Files:** none.

- [ ] **Step 1: Branch off master**

```powershell
git switch -c feat/library-session-list-pr-c
```

(The untracked `.serena/` tool cache flagged by preflight is handled OUTSIDE this PR — codex plan-review 2026-07-03 correctly called a `.gitignore` chore commit scope noise for PR-C.)

---

### Task 1: Day-epoch foundation (pure layer)

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/library/LibraryDateLabels.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/library/LibraryDayGrouping.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/library/LibraryDateLabelsTest.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/library/LibraryDayGroupingTest.kt`

**Interfaces:**
- Consumes: `LibraryDateLabels.startOfDay` (existing private Calendar-based day-floor — already DST-safe).
- Produces: `LibraryDateLabels.dayEpoch(millis: Long, tz: TimeZone): Long` (public); `LibraryDayGroup.dayEpochMillis: Long` (new field, default `0L`; `0L` on the header-less flat bucket). Task 2 keys sticky headers on `dayEpochMillis`.

- [ ] **Step 1: Write the failing tests**

Append to `LibraryDateLabelsTest.kt` (match the file's existing helpers/imports; add `import java.util.TimeZone` etc. only if missing):

```kotlin
    // --- PR-C: dayEpoch (stable day identity for sticky-header keys) ---

    @Test fun `dayEpoch same local day maps to one epoch`() {
        val tz = TimeZone.getTimeZone("UTC")
        val morning = 1_751_500_800_000L + 8 * 3600_000L   // 2025-07-03 08:00 UTC
        val evening = 1_751_500_800_000L + 22 * 3600_000L  // 2025-07-03 22:00 UTC
        assertEquals(LibraryDateLabels.dayEpoch(morning, tz), LibraryDateLabels.dayEpoch(evening, tz))
    }

    @Test fun `dayEpoch flips exactly at local midnight`() {
        val tz = TimeZone.getTimeZone("UTC")
        val beforeMidnight = 1_751_500_800_000L + 24 * 3600_000L - 1_000L // 23:59:59
        val afterMidnight = 1_751_500_800_000L + 24 * 3600_000L + 1_000L  // 00:00:01 next day
        assertNotEquals(LibraryDateLabels.dayEpoch(beforeMidnight, tz), LibraryDateLabels.dayEpoch(afterMidnight, tz))
    }

    @Test fun `dayEpoch is the local day floor across a DST transition`() {
        // America/New_York springs forward 2026-03-08 (23h day). Noon that day must floor to that
        // day's local midnight, distinct from both neighbours.
        val tz = TimeZone.getTimeZone("America/New_York")
        val cal = java.util.Calendar.getInstance(tz).apply {
            clear(); set(2026, java.util.Calendar.MARCH, 8, 12, 0, 0)
        }
        val noonDst = cal.timeInMillis
        val epoch = LibraryDateLabels.dayEpoch(noonDst, tz)
        val midnight = java.util.Calendar.getInstance(tz).apply {
            clear(); set(2026, java.util.Calendar.MARCH, 8, 0, 0, 0)
        }.timeInMillis
        assertEquals(midnight, epoch)
        assertNotEquals(epoch, LibraryDateLabels.dayEpoch(noonDst - 24 * 3600_000L, tz))
        assertNotEquals(epoch, LibraryDateLabels.dayEpoch(noonDst + 24 * 3600_000L, tz))
    }

    @Test fun `dayEpoch is the local day floor across a fall-back DST transition`() {
        // America/New_York falls back 2026-11-01 (25h day) — both DST directions pinned
        // (codex plan-review 2026-07-03). 23:30 that local day must still floor to the SAME
        // local midnight as 01:30, despite 25 wall-clock-spanning hours.
        val tz = TimeZone.getTimeZone("America/New_York")
        fun at(h: Int, min: Int): Long = java.util.Calendar.getInstance(tz).apply {
            clear(); set(2026, java.util.Calendar.NOVEMBER, 1, h, min, 0)
        }.timeInMillis
        val midnight = at(0, 0)
        assertEquals(midnight, LibraryDateLabels.dayEpoch(at(1, 30), tz))
        assertEquals(midnight, LibraryDateLabels.dayEpoch(at(23, 30), tz))
        assertNotEquals(midnight, LibraryDateLabels.dayEpoch(at(23, 30) + 3600_000L, tz)) // 00:30 next day
    }
```

(If the file lacks them, add `import org.junit.Assert.assertNotEquals` alongside the existing Assert imports.)

Append to `LibraryDayGroupingTest.kt` (reuses the file's existing `row`/`millis` helpers and `tz`/`locale` fields):

```kotlin
    // --- PR-C: dayEpochMillis (sticky-header keys stable across midnight) ---

    @Test fun `group stamps each bucket with its local day epoch`() {
        val now = millis(2026, Calendar.JUNE, 14)
        val rows = listOf(
            row("a", millis(2026, Calendar.JUNE, 14), 1024),
            row("b", millis(2026, Calendar.JUNE, 13), 2048),
        )
        val groups = LibraryDayGrouping.group(rows, now, locale, tz)
        assertEquals(2, groups.size)
        assertEquals(LibraryDateLabels.dayEpoch(millis(2026, Calendar.JUNE, 14), tz), groups[0].dayEpochMillis)
        assertEquals(LibraryDateLabels.dayEpoch(millis(2026, Calendar.JUNE, 13), tz), groups[1].dayEpochMillis)
        // Distinct per day — the LazyList duplicate-key invariant for the new header keys.
        assertEquals(groups.size, groups.map { it.dayEpochMillis }.distinct().size)
    }

    @Test fun `flat bucket carries the zero epoch (header suppressed, key unused)`() {
        val now = millis(2026, Calendar.JUNE, 14)
        val groups = LibraryDayGrouping.groupForSort(interleavedBySize(now), LibrarySort.LARGEST, now, locale, tz)
        assertEquals(0L, groups[0].dayEpochMillis)
    }
```

- [ ] **Step 2: Run to verify they fail**

```powershell
./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.LibraryDateLabelsTest" --tests "com.aritr.rova.ui.library.LibraryDayGroupingTest"
```

Expected: compile FAILURE (`dayEpoch` / `dayEpochMillis` unresolved).

- [ ] **Step 3: Implement**

`LibraryDateLabels.kt` — add a public entry above the private `startOfDay`:

```kotlin
    /**
     * Local-midnight epoch of [millis]'s calendar day in [tz]. DST-safe (Calendar floor, not
     * modular arithmetic). PR-C: stable day identity for sticky-header LazyList keys — the key
     * survives midnight while the header LABEL ("Today" → "Yesterday") changes with nowMillis.
     */
    fun dayEpoch(millis: Long, tz: TimeZone): Long = startOfDay(millis, tz)
```

`LibraryDayGrouping.kt` — extend the group model and stamp buckets:

```kotlin
/** ADR-0030 / spec §4 — one day bucket: header label, per-day size total, rows. */
data class LibraryDayGroup(
    val label: String,
    val sizeTotalLabel: String,
    val rows: List<LibraryRow>,
    /**
     * Local-midnight epoch of this bucket's day ([LibraryDateLabels.dayEpoch]) — the sticky-header
     * key (PR-C), stable across midnight unlike [label]. 0L on the header-less flat bucket
     * (label == "", header suppressed, key never used).
     */
    val dayEpochMillis: Long = 0L,
)
```

In `group()`, stamp the bucket at the label boundary (bucketing itself stays label-driven — byte-identical grouping):

```kotlin
        val out = ArrayList<LibraryDayGroup>()
        var bucketLabel: String? = null
        var bucketEpoch = 0L
        var bucket = ArrayList<LibraryRow>()
        fun flush() {
            val label = bucketLabel ?: return
            out += LibraryDayGroup(
                label = label,
                sizeTotalLabel = StorageFormat.dayTotal(bucket.map { it.sizeBytes }, locale),
                rows = bucket.toList(),
                dayEpochMillis = bucketEpoch,
            )
        }
        for (r in rows) {
            val label = HistoryRowFormatters.formatGroupHeader(r.dateMillis, nowMillis, locale, tz)
            if (label != bucketLabel) {
                flush()
                bucketLabel = label
                bucketEpoch = LibraryDateLabels.dayEpoch(r.dateMillis, tz)
                bucket = ArrayList()
            }
            bucket += r
        }
        flush()
        return out
```

(`groupForSort`'s flat-bucket construction is untouched — `dayEpochMillis` defaults to `0L`.)

- [ ] **Step 4: Run to verify they pass**

```powershell
./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.LibraryDateLabelsTest" --tests "com.aritr.rova.ui.library.LibraryDayGroupingTest"
```

Expected: PASS (all pre-existing cases too).

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/aritr/rova/ui/library/LibraryDateLabels.kt app/src/main/java/com/aritr/rova/ui/library/LibraryDayGrouping.kt app/src/test/java/com/aritr/rova/ui/library/LibraryDateLabelsTest.kt app/src/test/java/com/aritr/rova/ui/library/LibraryDayGroupingTest.kt
git commit -m "feat(library): day-epoch identity on day groups (PR-C foundation)"
```

---

### Task 2: Sticky day headers

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/library/LibraryScreen.kt` (~110, ~550-554, imports)
- Modify: `app/src/main/java/com/aritr/rova/ui/library/components/LibraryDayHeader.kt`

**Interfaces:**
- Consumes: `LibraryDayGroup.dayEpochMillis` (Task 1).
- Produces: nothing downstream. **`FocusRestorePolicy` and `ScrubberIndex` are intentionally untouched** — `stickyHeader` occupies exactly one lazy item slot like the `item` it replaces, so all index math holds; only the KEY string changes (`hdr-<label>` → `hdr-<epoch>`), and nothing reads header keys (row keys drive focus restore; scrubber scrolls by index).

- [ ] **Step 1: Swap the header items to `stickyHeader` on epoch keys**

In `LibraryScreen.kt`, the list block (currently ~lines 550-554):

```kotlin
                                item(key = "hdr-recovery-warn") { RecoveryAndWarnings() }
                                groups.forEach { group ->
                                    if (group.label.isNotEmpty()) {
                                        stickyHeader(key = "hdr-${group.dayEpochMillis}") {
                                            LibraryDayHeader(group.label, group.sizeTotalLabel)
                                        }
                                    }
                                    items(group.rows, key = { it.stableKey }) { row ->
```

(`hdr-recovery-warn` stays a plain `item` — it must scroll away, not stick.)

Annotate the screen composable (line ~110):

```kotlin
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
```

Add import: `androidx.compose.foundation.ExperimentalFoundationApi`. (`stickyHeader` is a `LazyListScope` member in foundation ≥1.7 — no extra import.)

- [ ] **Step 2: Opaque header backing (stuck readability)**

`LibraryDayHeader.kt` — rows must not scroll visibly through the stuck header. Insert a background between `fillMaxWidth` and the padding (text tokens `onSurfaceVariant`/`outline` on `surface` are the standard M3 pairs — no new contrast pair):

```kotlin
    Row(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = LibraryDimens.screenPadH, vertical = LibraryDimens.sectionPadV)
            .semantics { heading() },
```

Add import: `androidx.compose.foundation.background`. Update the KDoc's "Transparent background" sentence to state the surface backing exists for sticky legibility (PR-C).

- [ ] **Step 3: Build + full library test sweep**

```powershell
./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.*"
./gradlew :app:assembleDebug
```

Expected: tests PASS (FocusRestorePolicyTest, ScrubberIndexTest, LibraryDayGroupingTest all hold — no index-math change); assembleDebug + 48 gates GREEN.

- [ ] **Step 4: Commit**

```powershell
git add app/src/main/java/com/aritr/rova/ui/library/LibraryScreen.kt app/src/main/java/com/aritr/rova/ui/library/components/LibraryDayHeader.kt
git commit -m "feat(library): sticky day headers on day-epoch keys (PR-C)"
```

---

### Task 3: Midnight ON_RESUME label refresh

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/library/LibraryScreen.kt` (~169, ~336-339, imports)

**Interfaces:**
- Consumes: the existing ON_RESUME `LifecycleEventObserver` (`LibraryScreen.kt` ~334-363) and the `groups` remember keyed on `nowMillis` (~319) — both already in place; this task only re-stamps the input.
- Produces: nothing downstream.

- [ ] **Step 1: Make `nowMillis` observer-writable**

Replace line ~169 (`val nowMillis = remember(ui.rows) { System.currentTimeMillis() }`):

```kotlin
    // PR-C midnight fix: relative day labels ("Today"/"Yesterday") go stale when the day flips
    // while the app is backgrounded — the old remember(ui.rows) stamp only refreshed on row
    // changes. Single UN-keyed state instance so the ON_RESUME observer's closure write (below)
    // always hits the live instance (a rows-keyed remember would recreate the state and strand
    // the observer's capture — the LaunchedEffect keeps the rows-refresh behavior instead).
    val nowMillisState = remember { mutableLongStateOf(System.currentTimeMillis()) }
    val nowMillis = nowMillisState.longValue
    LaunchedEffect(ui.rows) { nowMillisState.longValue = System.currentTimeMillis() }
```

Add import: `androidx.compose.runtime.mutableLongStateOf` (and `androidx.compose.runtime.LaunchedEffect` if not present).

- [ ] **Step 2: Re-stamp on resume**

In the existing ON_RESUME observer (~line 336), before `viewModel.refreshDensity()`:

```kotlin
            if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            // PR-C midnight fix — re-stamp so day groups/labels recompute if the day flipped
            // while backgrounded (regroup ≈12ms, keys stable → no scroll jump; PR #164 pattern).
            nowMillisState.longValue = System.currentTimeMillis()
            // Density reseed — pick up a Settings/PR-C density toggle when returning to the
```

- [ ] **Step 3: Build**

```powershell
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL, 48 gates GREEN. (Wiring-only change into an already-tested pure pipeline — no new JVM surface; behavior is device-verified via the clock-flip checklist item.)

- [ ] **Step 4: Commit**

```powershell
git add app/src/main/java/com/aritr/rova/ui/library/LibraryScreen.kt
git commit -m "fix(library): re-stamp day labels on ON_RESUME so midnight flips refresh (PR-C)"
```

---

### Task 4: Scrubber bubble fix + 16dp thumb

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/library/ScrubberIndex.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/library/components/LibraryScrubber.kt` (~105-125, ~163-173, imports)
- Test: `app/src/test/java/com/aritr/rova/ui/library/ScrubberIndexTest.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces: `ScrubberIndex.bubbleTopPx(thumbTopPx: Float, thumbSizePx: Float, bubbleHeightPx: Float, railHeightPx: Float): Float` — pure clamp used only by `LibraryScrubber`.

Defects being fixed (current `LibraryScrubber.kt:114-125`): the bubble sits inside the 48dp-wide rail Box with `padding(end = 28.dp)` → 20dp max width minus 24dp of horizontal padding = **negative content width** → one-character wrap; and it has no vertical offset → pinned to the top instead of riding the thumb. Thumb visual shrinks 24dp → 16dp (owner request); the 24dp-wide full-height gesture rail and ALL semantics (slider node, separate polite live-region) stay byte-identical.

- [ ] **Step 1: Write the failing test**

Append to `ScrubberIndexTest.kt`:

```kotlin
    // --- PR-C: bubble geometry (label rides the thumb, clamped inside the rail) ---

    @Test
    fun bubbleTopPx_centersOnThumb_andClamps() {
        // Centered: thumb 16px tall at top=100 in a 400px rail, bubble 32px → 100+8-16 = 92.
        assertEquals(92f, ScrubberIndex.bubbleTopPx(100f, 16f, 32f, 400f))
        // Clamp top: thumb at 0 → centered would be negative → 0.
        assertEquals(0f, ScrubberIndex.bubbleTopPx(0f, 16f, 32f, 400f))
        // Clamp bottom: thumb at rail end (384) → centered 376 > 400-32=368 → 368.
        assertEquals(368f, ScrubberIndex.bubbleTopPx(384f, 16f, 32f, 400f))
        // Degenerate: bubble taller than rail → pinned to 0, never negative.
        assertEquals(0f, ScrubberIndex.bubbleTopPx(10f, 16f, 500f, 400f))
    }
```

- [ ] **Step 2: Run to verify it fails**

```powershell
./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.ScrubberIndexTest"
```

Expected: compile FAILURE (`bubbleTopPx` unresolved).

- [ ] **Step 3: Implement the pure seam**

Add to the `ScrubberIndex` object:

```kotlin
    /**
     * Top Y of the drag bubble so its center rides the thumb's center, clamped fully inside the
     * rail. Pure math seam for the PR-C scrubber fix (#3): the composable feeds measured px and
     * applies the result as a layout offset.
     */
    fun bubbleTopPx(thumbTopPx: Float, thumbSizePx: Float, bubbleHeightPx: Float, railHeightPx: Float): Float {
        val centered = thumbTopPx + thumbSizePx / 2f - bubbleHeightPx / 2f
        return centered.coerceIn(0f, (railHeightPx - bubbleHeightPx).coerceAtLeast(0f))
    }
```

- [ ] **Step 4: Run to verify it passes**

```powershell
./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.ScrubberIndexTest"
```

Expected: PASS.

- [ ] **Step 5: Rework the composable geometry**

In `LibraryScrubber.kt`:

(a) New constant next to the existing two:

```kotlin
/** Thumb dot visual size — 16dp per owner request (PR-C); the 24dp-wide gesture rail is unchanged. */
private val SCRUBBER_THUMB = 16.dp
```

(b) Hoist thumb geometry above the bubble (inside the composable, after `val accent = ...`):

```kotlin
    // Thumb + bubble geometry hoisted so the bubble (rendered before the rail) can track the
    // thumb. Pure clamp in ScrubberIndex.bubbleTopPx (JVM-tested).
    val thumbSizePx = with(density) { SCRUBBER_THUMB.toPx() }
    val thumbTopPx = (thumbFraction * (railHeightPx - thumbSizePx)).coerceAtLeast(0f)
    var bubbleHeightPx by remember { mutableFloatStateOf(0f) }
```

(c) Replace the bubble block (current lines ~114-125). `offset {}` is layout-phase (no recomposition per drag frame); `wrapContentWidth(unbounded = true)` lets the label escape the 48dp rail at intrinsic width (right edge stays 28dp from the box's right = 4dp gap to the 24dp rail); `onSizeChanged` feeds the clamp (first frame uses 0 → corrected next frame, invisible mid-drag):

```kotlin
        // Bubble (drag only) to the LEFT of the rail, riding the thumb (PR-C fix #3: intrinsic
        // width via unbounded wrapContentWidth — the old in-rail measure went negative and
        // wrapped one char per line; vertical offset tracks the thumb, clamped inside the rail).
        if (dragging) {
            Box(
                Modifier
                    .offset {
                        IntOffset(
                            0,
                            ScrubberIndex.bubbleTopPx(thumbTopPx, thumbSizePx, bubbleHeightPx, railHeightPx)
                                .roundToInt(),
                        )
                    }
                    .padding(end = 28.dp)
                    .wrapContentWidth(align = Alignment.End, unbounded = true)
                    .onSizeChanged { bubbleHeightPx = it.height.toFloat() }
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.inverseSurface)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(label, color = MaterialTheme.colorScheme.inverseOnSurface, style = MaterialTheme.typography.labelLarge)
            }
        }
```

(d) Rail Box: add `contentAlignment = Alignment.TopCenter` so the 16dp thumb centers horizontally in the 24dp rail — the rail `Box(...)` opening (~line 127) becomes:

```kotlin
        Box(
            Modifier
                .fillMaxHeight()
                .width(24.dp)
                ...
            contentAlignment = Alignment.TopCenter,
        ) {
```

(the `...` = every existing modifier — `onSizeChanged`/`pointerInput`/`semantics` — byte-identical).

(e) Replace the thumb block (current ~163-172) using the hoisted geometry:

```kotlin
            val thumbY = with(density) { thumbTopPx.toDp() }
            Box(
                Modifier
                    .padding(top = thumbY)
                    .size(SCRUBBER_THUMB)
                    .alpha(thumbAlpha)
                    .clip(CircleShape)
                    .background(accent),
            )
```

(f) Imports to add: `androidx.compose.foundation.layout.offset`, `androidx.compose.foundation.layout.wrapContentWidth`, `androidx.compose.ui.unit.IntOffset`. (`mutableFloatStateOf`, `onSizeChanged`, `roundToInt` are already imported.)

- [ ] **Step 6: Build + scrubber tests**

```powershell
./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.ScrubberIndexTest"
./gradlew :app:assembleDebug
```

Expected: PASS; BUILD SUCCESSFUL, 48 gates GREEN (semantics untouched → a11y gates unaffected).

- [ ] **Step 7: Commit**

```powershell
git add app/src/main/java/com/aritr/rova/ui/library/ScrubberIndex.kt app/src/main/java/com/aritr/rova/ui/library/components/LibraryScrubber.kt app/src/test/java/com/aritr/rova/ui/library/ScrubberIndexTest.kt
git commit -m "fix(library): scrubber bubble intrinsic width + thumb tracking, 16dp thumb (PR-C)"
```

---

### Task 5: Density toggle in the top bar

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/library/LibraryDensityDimens.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/HistoryViewModel.kt` (~263-265 area)
- Modify: `app/src/main/java/com/aritr/rova/ui/library/components/LibraryTopBar.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/library/LibraryScreen.kt` (~441-451)
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-es/strings.xml`
- Test: `app/src/test/java/com/aritr/rova/ui/library/LibraryDensityDimensTest.kt`

**Interfaces:**
- Consumes: `RovaSettings.libraryDensity` setter (`RovaSettings.kt:347-349`, live since PR-A), `HistoryViewModel._density` / `readDensity()` (PR-B), `LibraryTopBar` null-gated slot pattern, `SemanticIcon`/`IconRole.Secondary`/`RovaIcons.GridLayout`.
- Produces: `LibraryDensity.next(): LibraryDensity` (extension, same file as the enum); `HistoryViewModel.toggleDensity()`; `LibraryTopBar` params `onToggleDensity: (() -> Unit)?`, `densityLabel: String`, `densityState: String`.

- [ ] **Step 1: Write the failing test**

Append to `LibraryDensityDimensTest.kt`:

```kotlin
    // --- PR-C: toggle cycle ---

    @Test fun `next cycles the two densities`() {
        assertEquals(LibraryDensity.COMPACT, LibraryDensity.COMFORTABLE.next())
        assertEquals(LibraryDensity.COMFORTABLE, LibraryDensity.COMPACT.next())
    }
```

- [ ] **Step 2: Run to verify it fails**

```powershell
./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.LibraryDensityDimensTest"
```

Expected: compile FAILURE (`next` unresolved).

- [ ] **Step 3: Implement the cycle + VM writer**

`LibraryDensityDimens.kt` — under the enum:

```kotlin
/** PR-C top-bar toggle cycle. Two values today; exhaustive `when` keeps a third honest. */
fun LibraryDensity.next(): LibraryDensity = when (this) {
    LibraryDensity.COMFORTABLE -> LibraryDensity.COMPACT
    LibraryDensity.COMPACT -> LibraryDensity.COMFORTABLE
}
```

`HistoryViewModel.kt` — below `refreshDensity()` (~line 265):

```kotlin
    /**
     * PR-C top-bar density toggle — the single production writer of [RovaSettings.libraryDensity].
     * Writes the pref first, then the state, so a process death between the two resurrects the
     * NEW value on next launch (state is re-seeded from the pref).
     */
    fun toggleDensity() {
        val next = _density.value.next()
        settings.libraryDensity = next.name
        _density.value = next
    }
```

(Import `com.aritr.rova.ui.library.next` if the IDE doesn't resolve it via the existing `com.aritr.rova.ui.library.LibraryDensity` import.)

- [ ] **Step 4: Run to verify it passes**

```powershell
./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.LibraryDensityDimensTest"
```

Expected: PASS. (Explicit coverage boundary — codex plan-review 2026-07-03: the JVM test covers the `next()` cycle ONLY. `toggleDensity`'s single-writer contract and pref-first/state-second write order are **device-only validation** — the "persists across cold start" checklist item is what proves them — matching how `refreshDensity` shipped untested-at-JVM in PR-B. The seam stays untested rather than growing a settings fake for a two-line writer.)

- [ ] **Step 5: Top-bar slot + screen wiring + strings**

`LibraryTopBar.kt` — new params (after `vaultLabel`, keeping the null-gated convention):

```kotlin
    onToggleDensity: (() -> Unit)? = null,
    densityLabel: String = "",
    densityState: String = "",
```

New button between the vault and search slots (48dp `IconButton` target — ADR-0020; `stateDescription` announces the CURRENT mode):

```kotlin
            if (onToggleDensity != null) {
                IconButton(
                    onClick = onToggleDensity,
                    modifier = Modifier.semantics { stateDescription = densityState },
                ) {
                    SemanticIcon(
                        glyph = RovaIcons.GridLayout,
                        contentDescription = densityLabel,
                        role = IconRole.Secondary,
                        modifier = Modifier.size(LibraryDimens.navIcon),
                    )
                }
            }
```

Imports to add: `androidx.compose.ui.semantics.semantics`, `androidx.compose.ui.semantics.stateDescription`.

`LibraryScreen.kt` — extend the `LibraryTopBar(...)` call (~441-451):

```kotlin
                        onToggleDensity = { viewModel.toggleDensity() },
                        densityLabel = stringResource(R.string.library_density_toggle_cd),
                        densityState = stringResource(
                            if (ui.density == LibraryDensity.COMPACT) R.string.library_density_state_compact
                            else R.string.library_density_state_comfortable
                        ),
```

(`LibraryDensity` is already imported — `ui.density` drives `dims` at ~line 325.)

`values/strings.xml` (next to the other `library_*` entries, ~line 900):

```xml
    <string name="library_density_toggle_cd">Toggle list density</string>
    <string name="library_density_state_comfortable">Comfortable</string>
    <string name="library_density_state_compact">Compact</string>
```

`values-es/strings.xml` (same block):

```xml
    <string name="library_density_toggle_cd">Cambiar densidad de la lista</string>
    <string name="library_density_state_comfortable">Cómoda</string>
    <string name="library_density_state_compact">Compacta</string>
```

- [ ] **Step 6: Build (fires `checkNoHardcodedUiStrings` + a11y gates)**

```powershell
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL, 48 gates GREEN.

- [ ] **Step 7: Commit**

```powershell
git add app/src/main/java/com/aritr/rova/ui/library/LibraryDensityDimens.kt app/src/main/java/com/aritr/rova/ui/screens/HistoryViewModel.kt app/src/main/java/com/aritr/rova/ui/library/components/LibraryTopBar.kt app/src/main/java/com/aritr/rova/ui/library/LibraryScreen.kt app/src/main/res/values/strings.xml app/src/main/res/values-es/strings.xml app/src/test/java/com/aritr/rova/ui/library/LibraryDensityDimensTest.kt
git commit -m "feat(library): top-bar density toggle (PR-C)"
```

---

### Task 6: Final verification

**Files:** none (verification only).

- [ ] **Step 1: Full JVM suite**

```powershell
./gradlew :app:testDebugUnitTest
```

Expected: 0 failures, 0 errors, 0 skipped; total > 2193 (baseline + 4 dayEpoch + 2 grouping + 1 bubble + 1 cycle = +8 minimum).

- [ ] **Step 2: Full build + gates**

```powershell
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL, all 48 `check*` gates GREEN.

- [ ] **Step 3: Hand off to review-gate**

Stop here. Next per kickoff: code-review + codex review of the diff, then device-verify on RZCYA1VBQ2H (checklist below), then PR — no push without owner GO.

---

## Device-verify checklist (RZCYA1VBQ2H — from the handoff, verbatim)

- Headers stick under scroll + push-up transition clean in BOTH densities.
- Scrubber bubble single-line and rides the thumb.
- Density toggle flips row dims live + persists across cold start.
- Date-label refresh on resume after a day flip (device clock via Settings, restore after).
- TalkBack sanity on the toggle (`stateDescription` announced) + headers-not-focus-trapped.
- Latest accent + side actions + PR #164 no-jump unregressed.

## Self-review notes (handoff scope coverage)

- Sticky day headers on day-epoch keys + push-up → Tasks 1+2 (push-up = native `stickyHeader`; keys stable across midnight because they're epoch-, not label-, derived).
- Midnight ON_RESUME refresh → Task 3 (root cause `LibraryScreen.kt:169` `remember(ui.rows)` stamp; reuses the PR-B observer, mirrors the `ThermalStatusSignal` resume-refresh pattern).
- Scrubber `114-125` bubble wrap + top-pin + 16dp thumb → Task 4 (pure clamp seam; semantics byte-identical).
- Density toggle UI + `stateDescription` + en/es strings → Task 5 (pref was live since PR-B; toggle is just a writer).
- Explicit non-goals honored: no header content change (label text stays `formatGroupHeader` output), no glyph authoring, no row-anatomy change, no TalkBack pass beyond the toggle sanity item.

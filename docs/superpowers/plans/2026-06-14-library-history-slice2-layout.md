# Enhanced Library & History — Slice 2 (Layout) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Re-skin the Library/History surface to Liquid Glass with a Hero + 2-column Grid (toggleable to a
glass List), day-grouped headers carrying per-day size totals, mandatory duration + exceptional-status + P+L
badges with an AA caption scrim, re-skinned empty/loading states, and the surface's own accessibility — feeding
all of it from a pure row model joined out of `VideoItem` + `SessionManifest` + the Slice-1 sidecar.

**Architecture:** All decision/format logic is pure-Kotlin (`com.aritr.rova.ui.library`, JVM-tested under
`isReturnDefaultValues = true`): `LibraryRowMapper` turns manifest/sidecar primitives into the Slice-1
`LibraryRow`; `LibraryDayGrouping`, `TileSemantics`, `CaptionScrim` shape headers/labels/contrast. The
`HistoryViewModel` gains a `libraryUiState: StateFlow<LibraryUiState>` (rows + view mode) by joining its existing
`items` load with a per-row manifest-facts map and a `LibraryMetadataStore` snapshot. New Compose files render
on an **opaque** content plane (cards) with **glass** chrome (`GlassSurface(role=…)`); a new `LibraryScreen`
orchestrates hero/grid/list and is swapped in from `MainScreen`, retaining the existing recovery cards + warning
strip. No Compose unit tests (repo is JVM-only) — composables are device-smoked; their logic lives in tested
pure helpers.

**Tech Stack:** Kotlin, Jetpack Compose (`LazyVerticalGrid` / `LazyColumn`, `GlassSurface`), `org.json` sidecar,
JUnit4 pure tests, the Slice-1 `ui.library` helpers (`LibraryRow`, `LibraryQuery`, `SmartTitle`,
`StatusBadgePolicy`, `StorageFormat`, `ThumbnailCacheKey`, `ThumbnailDiskCache`, `LibraryMetadataStore`).

**Spec:** `docs/superpowers/specs/2026-06-14-enhanced-library-history-design.md` (§4 IA, §5 layout/components,
§5.6 seams, §8 a11y, §9 glass, §11 slice 2). **Builds on:** Slice 1 (branch `feat/library-history-foundation`,
PR #117) — this slice **stacks on that branch** (base it on `feat/library-history-foundation`, not `master`,
per `memory/feedback_stacked_pr_merge_train.md`).

**Build/verify (Windows, WARM — no `--stop`, no cache wipe):** `gradlew.bat :app:testDebugUnitTest` for tests;
`gradlew.bat :app:assembleDebug` for the gate suite + APK. `lintDebug` is RED on a pre-existing unrelated issue —
gate-build with `assembleDebug`. Single test:
`gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.LibraryRowMapperTest"`.
**House rule:** subagents are EDIT-ONLY; the controller runs all gradle + commits.

---

## Scope

**In:** the read/browse redesign — hero, grid, list toggle, glass chrome, day headers with size totals,
duration/status/P+L badges + AA scrim, empty/loading re-skin, the data enrichment that feeds them, the hero's
non-select quick actions (Play / Favorite / Share — Favorite writes the sidecar, the first sidecar **write**
path), and this surface's a11y (grid `collectionInfo`/`collectionItemInfo`, merged tile description, scrim
contrast, reduced-motion gating).

**Out (later slices):** long-press multi-select + glass contextual/batch bars + Snackbar UNDO + per-day
select-all (Slice 3); sort sheet / filter chips / inline search / scrubber (Slice 4); player `SegmentedTimeline`
+ History warning-card focus rows 21/23/32 (Slice 5). Per-item Rename UI rides Slice 3. The existing per-row
overflow menu (Share/View settings/Move-to-Vault/Delete) and recovery cards + warning strip are **retained as-is**
in the new screen (re-skin only where trivial; behavior unchanged).

---

## File structure

| File | Responsibility |
|---|---|
| Modify `app/src/main/java/com/aritr/rova/RovaApp.kt` | Add `libraryMetadataStore` lazy prop |
| Create `app/src/main/java/com/aritr/rova/ui/library/LibraryRowMapper.kt` | Pure: manifest/sidecar primitives → `LibraryRow` |
| Create `app/src/main/java/com/aritr/rova/ui/library/LibraryDayGrouping.kt` | Pure: rows → day groups (label + per-day size total) |
| Create `app/src/main/java/com/aritr/rova/ui/library/TileSemantics.kt` | Pure: row → merged TalkBack contentDescription |
| Create `app/src/main/java/com/aritr/rova/ui/library/CaptionScrim.kt` | Caption/badge scrim spec (alpha + text color), AA-verified |
| Modify `app/src/main/java/com/aritr/rova/ui/screens/HistoryViewModel.kt` | `libraryUiState` join + `setViewMode` + `toggleFavorite` + manifest-facts map |
| Create `app/src/main/java/com/aritr/rova/ui/library/LibraryUiState.kt` | UI state holder (rows, viewMode, hasLoaded) + `LibraryViewMode` enum |
| Create `app/src/main/java/com/aritr/rova/ui/library/components/LibraryBadges.kt` | Duration / status / P+L badge composables + caption scrim Box |
| Create `app/src/main/java/com/aritr/rova/ui/library/components/LibraryGridCard.kt` | Opaque grid card (thumbnail + badges + caption) |
| Create `app/src/main/java/com/aritr/rova/ui/library/components/LibraryHeroCard.kt` | Hero card + quick-action row (Play/Favorite/Share) |
| Create `app/src/main/java/com/aritr/rova/ui/library/components/LibraryListRow.kt` | Glass-reskinned list row |
| Create `app/src/main/java/com/aritr/rova/ui/library/components/LibraryDayHeader.kt` | Sticky day header (label + size total) |
| Create `app/src/main/java/com/aritr/rova/ui/library/components/LibraryTopBar.kt` | Glass top bar + grid/list toggle + summary |
| Create `app/src/main/java/com/aritr/rova/ui/library/components/LibraryStates.kt` | Re-skinned empty + loading placeholder |
| Create `app/src/main/java/com/aritr/rova/ui/library/LibraryScreen.kt` | Orchestrator (hero+grid/list, grouping, recovery/warning retained, a11y) |
| Modify `app/src/main/java/com/aritr/rova/ui/MainScreen.kt` | Call `LibraryScreen` instead of `HistoryScreen` |
| Modify `app/src/main/res/values/strings.xml` + `values-es/strings.xml` | New user-facing strings (en + es) |
| Create `app/src/main/java/com/aritr/rova/ui/library/components/VideoFrame.kt` | Opaque thumbnail composable (Bitmap → center-crop, never glass) |

Tests (JVM, mirror under `app/src/test/java/com/aritr/rova/ui/library/`):
`LibraryRowMapperTest`, `LibraryDayGroupingTest`, `TileSemanticsTest`, `CaptionScrimTest`.

> **Why no Compose tests:** the repo has **no** `createComposeRule` harness (Explore-confirmed: only JVM/pure
> tests + one trivial instrumented context test). Per house convention every framework-touching composable has a
> pure sibling that IS tested; the composables themselves are verified by device smoke (Task 16). Do **not** add a
> Compose test harness in this slice.

---

## Task 1: Stack the branch + wire `LibraryMetadataStore` into `RovaApp`

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/RovaApp.kt`

- [ ] **Step 1: Create the slice branch off the Slice-1 branch** (NOT master — stacked)

```bash
git checkout feat/library-history-foundation
git pull --ff-only          # ensure local matches the pushed PR #117 head
git checkout -b feat/library-history-layout
```

- [ ] **Step 2: Add the lazy store prop**

Open `RovaApp.kt` and find the block of lazy `*Signal` / `sessionStore` props (the seam-construction site
described in CLAUDE.md "Signals are constructed in `RovaApp` as lazy props"). Add, next to `sessionStore`:

```kotlin
/**
 * ADR-0030 sidecar for Library UI metadata (favorite / rename / lastPlayedAt).
 * Single instance per app (per-instance in-memory cache + single-process atomic
 * write — see LibraryMetadataStore KDoc). filesDir is the canonical app-internal
 * directory; the sidecar is `files/library_metadata.json`.
 */
val libraryMetadataStore: com.aritr.rova.ui.library.LibraryMetadataStore by lazy {
    com.aritr.rova.ui.library.LibraryMetadataStore(filesDir)
}
```

- [ ] **Step 3: Build to confirm it resolves**

Run: `gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL (no UI change yet; just the prop). All 42 gates pass.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/aritr/rova/RovaApp.kt
git commit -m "feat(library): wire LibraryMetadataStore as a RovaApp lazy prop (ADR-0030)"
```

---

## Task 2: `LibraryRowMapper` (pure: primitives → `LibraryRow`)

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/library/LibraryRowMapper.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/library/LibraryRowMapperTest.kt`

> Inputs are primitives the ViewModel already has or can derive cheaply, so the mapper stays Android-free.
> `topologyPersisted` is `SessionConfig.captureTopology` ("Single"/"DualShot"/"FrontBack"); mapper parses it via
> `CaptureTopology.fromPersisted`. Title = `customTitle ?: SmartTitle.derive(...)`. Badge =
> `StatusBadgePolicy.badgeFor(terminated, exportState)`. `dateLabel` is precomputed by the caller (the search
> layer matches on it) via `HistoryRowFormatters.formatPrimaryDateTime`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.aritr.rova.ui.library

import com.aritr.rova.data.CaptureTopology
import com.aritr.rova.data.ExportState
import com.aritr.rova.data.Terminated
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class LibraryRowMapperTest {

    private val locale = Locale.US
    private val tz = TimeZone.getTimeZone("UTC")

    private fun millis(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long {
        val c = Calendar.getInstance(tz, locale); c.clear(); c.set(y, mo, d, h, mi, 0)
        c.set(Calendar.MILLISECOND, 0); return c.timeInMillis
    }

    private fun input(
        customTitle: String? = null,
        favorite: Boolean = false,
        topology: String = "Single",
        terminated: Terminated? = Terminated.COMPLETED,
        export: ExportState = ExportState.FINALIZED,
        segmentDurationsMs: List<Long> = listOf(120_000L),
        startedAt: Long = millis(2026, Calendar.JUNE, 14, 14, 32),
    ) = LibraryRowMapper.Input(
        stableKey = "/a.mp4",
        startedAtMillis = startedAt,
        dateMillis = startedAt,
        dateLabel = "Jun 14 · 2:32 PM",
        sizeBytes = 50_000_000L,
        segmentDurationsMs = segmentDurationsMs,
        topologyPersisted = topology,
        terminated = terminated,
        exportState = export,
        customTitle = customTitle,
        favorite = favorite,
    )

    @Test fun `derives title from SmartTitle when no custom title`() {
        val row = LibraryRowMapper.map(input(segmentDurationsMs = listOf(60_000L, 60_000L)), locale, tz)
        assertEquals("Sun · 2:32 PM · 2 clips · 2m", row.title)
    }

    @Test fun `custom title overrides derived`() {
        assertEquals("Beach", LibraryRowMapper.map(input(customTitle = "Beach"), locale, tz).title)
    }

    @Test fun `sums segment durations`() {
        val row = LibraryRowMapper.map(input(segmentDurationsMs = listOf(10_000L, 20_000L, 30_000L)), locale, tz)
        assertEquals(60_000L, row.durationMs)
    }

    @Test fun `parses topology and surfaces P+L`() {
        assertEquals(CaptureTopology.DualShot, LibraryRowMapper.map(input(topology = "DualShot"), locale, tz).topology)
        assertEquals(CaptureTopology.Single, LibraryRowMapper.map(input(topology = "bogus"), locale, tz).topology)
    }

    @Test fun `exceptional badge only`() {
        assertEquals(null, LibraryRowMapper.map(input(), locale, tz).badge)
        assertEquals(
            LibraryBadge.RECOVERED,
            LibraryRowMapper.map(input(terminated = Terminated.MULTI_SEGMENT_KEPT), locale, tz).badge,
        )
        assertEquals(
            LibraryBadge.INTERRUPTED,
            LibraryRowMapper.map(input(export = ExportState.FAILED), locale, tz).badge,
        )
    }

    @Test fun `carries favorite stableKey size and dateLabel`() {
        val row = LibraryRowMapper.map(input(favorite = true), locale, tz)
        assertEquals("/a.mp4", row.stableKey)
        assertEquals(true, row.favorite)
        assertEquals(50_000_000L, row.sizeBytes)
        assertEquals("Jun 14 · 2:32 PM", row.dateLabel)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.LibraryRowMapperTest"`
Expected: FAIL — `LibraryRowMapper` unresolved.

- [ ] **Step 3: Write the mapper**

```kotlin
package com.aritr.rova.ui.library

import com.aritr.rova.data.CaptureTopology
import com.aritr.rova.data.ExportState
import com.aritr.rova.data.Terminated
import java.util.Locale
import java.util.TimeZone

/**
 * ADR-0030 / spec §5.6 — pure join of session primitives + sidecar metadata into
 * a [LibraryRow]. Android-free so it is JVM-testable; the ViewModel supplies the
 * primitives (manifest-derived for finalized rows; legacy rows pass an empty
 * duration list → a 0 duration the card hides). Title resolves to the user's
 * [Input.customTitle] when set, else the derived [SmartTitle].
 */
object LibraryRowMapper {

    data class Input(
        val stableKey: String,
        val startedAtMillis: Long,
        val dateMillis: Long,
        val dateLabel: String,
        val sizeBytes: Long,
        val segmentDurationsMs: List<Long>,
        val topologyPersisted: String,
        val terminated: Terminated?,
        val exportState: ExportState,
        val customTitle: String?,
        val favorite: Boolean,
    )

    fun map(input: Input, locale: Locale, tz: TimeZone): LibraryRow {
        val durationMs = input.segmentDurationsMs.sum()
        val segmentCount = input.segmentDurationsMs.size.coerceAtLeast(1)
        val derived = SmartTitle.derive(input.startedAtMillis, segmentCount, durationMs, locale, tz)
        val title = input.customTitle?.takeIf { it.isNotBlank() } ?: derived
        return LibraryRow(
            stableKey = input.stableKey,
            title = title,
            dateLabel = input.dateLabel,
            dateMillis = input.dateMillis,
            durationMs = durationMs,
            sizeBytes = input.sizeBytes,
            topology = CaptureTopology.fromPersisted(input.topologyPersisted),
            badge = StatusBadgePolicy.badgeFor(input.terminated, input.exportState),
            favorite = input.favorite,
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.LibraryRowMapperTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/LibraryRowMapper.kt app/src/test/java/com/aritr/rova/ui/library/LibraryRowMapperTest.kt
git commit -m "feat(library): LibraryRowMapper primitives->LibraryRow (ADR-0030)"
```

---

## Task 3: `LibraryDayGrouping` (pure: rows → day groups with per-day size total)

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/library/LibraryDayGrouping.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/library/LibraryDayGroupingTest.kt`

> Groups already-sorted rows into day buckets, preserving incoming order within a bucket and bucket order by
> first-seen (the caller passes newest-first). Reuses `HistoryRowFormatters.formatGroupHeader` for the label and
> `StorageFormat.dayTotal` for the per-day size. Header label is the grouping key — consecutive rows with the
> same label share a group (rows are pre-sorted, so same-day rows are contiguous).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.aritr.rova.ui.library

import com.aritr.rova.data.CaptureTopology
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class LibraryDayGroupingTest {

    private val locale = Locale.US
    private val tz = TimeZone.getTimeZone("UTC")

    private fun millis(y: Int, mo: Int, d: Int): Long {
        val c = Calendar.getInstance(tz, locale); c.clear(); c.set(y, mo, d, 12, 0, 0)
        c.set(Calendar.MILLISECOND, 0); return c.timeInMillis
    }

    private fun row(key: String, date: Long, size: Long) =
        LibraryRow(key, key, "", date, 0, size, CaptureTopology.Single, null, false)

    @Test fun `buckets by day in input order with per-day size totals`() {
        val now = millis(2026, Calendar.JUNE, 14)
        val rows = listOf(
            row("a", millis(2026, Calendar.JUNE, 14), 1024),
            row("b", millis(2026, Calendar.JUNE, 14), 1024),
            row("c", millis(2026, Calendar.JUNE, 13), 2048),
        )
        val groups = LibraryDayGrouping.group(rows, now, locale, tz)
        assertEquals(2, groups.size)
        assertEquals("Today", groups[0].label)
        assertEquals(listOf("a", "b"), groups[0].rows.map { it.stableKey })
        assertEquals("2.0 KB", groups[0].sizeTotalLabel)
        assertEquals("Yesterday", groups[1].label)
        assertEquals("2.0 KB", groups[1].sizeTotalLabel)
    }

    @Test fun `empty input yields no groups`() {
        assertEquals(emptyList<LibraryDayGroup>(), LibraryDayGrouping.group(emptyList(), millis(2026, Calendar.JUNE, 14), locale, tz))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.LibraryDayGroupingTest"`
Expected: FAIL — `LibraryDayGrouping` / `LibraryDayGroup` unresolved.

- [ ] **Step 3: Write the grouping**

```kotlin
package com.aritr.rova.ui.library

import com.aritr.rova.ui.screens.HistoryRowFormatters
import java.util.Locale
import java.util.TimeZone

/** ADR-0030 / spec §4 — one day bucket: header label, per-day size total, rows. */
data class LibraryDayGroup(
    val label: String,
    val sizeTotalLabel: String,
    val rows: List<LibraryRow>,
)

/**
 * Pure day-grouping for the Library grid/list. Rows arrive pre-sorted (newest
 * first), so same-day rows are contiguous; this folds them into [LibraryDayGroup]s
 * preserving order, labelling via [HistoryRowFormatters.formatGroupHeader] and
 * summing sizes via [StorageFormat.dayTotal]. Framework-free → JVM-testable.
 */
object LibraryDayGrouping {

    fun group(
        rows: List<LibraryRow>,
        nowMillis: Long,
        locale: Locale,
        tz: TimeZone,
    ): List<LibraryDayGroup> {
        if (rows.isEmpty()) return emptyList()
        val out = ArrayList<LibraryDayGroup>()
        var bucketLabel: String? = null
        var bucket = ArrayList<LibraryRow>()
        fun flush() {
            val label = bucketLabel ?: return
            out += LibraryDayGroup(
                label = label,
                sizeTotalLabel = StorageFormat.dayTotal(bucket.map { it.sizeBytes }, locale),
                rows = bucket.toList(),
            )
        }
        for (r in rows) {
            val label = HistoryRowFormatters.formatGroupHeader(r.dateMillis, nowMillis, locale, tz)
            if (label != bucketLabel) {
                flush()
                bucketLabel = label
                bucket = ArrayList()
            }
            bucket += r
        }
        flush()
        return out
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.LibraryDayGroupingTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/LibraryDayGrouping.kt app/src/test/java/com/aritr/rova/ui/library/LibraryDayGroupingTest.kt
git commit -m "feat(library): LibraryDayGrouping day buckets + per-day size (ADR-0030)"
```

---

## Task 4: `TileSemantics` (pure merged TalkBack label)

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/library/TileSemantics.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/library/TileSemanticsTest.kt`

> spec §8 "Tile label": one merged `contentDescription` = title + duration + status (+ P+L), so the badges are
> not separate focus stops. Duration formatted with the same `SmartTitle` minute/second shape via a shared
> private formatter exposed here as `durationSpeech`. Strings are assembled from caller-provided localized
> fragments (the composable passes `res.getString(...)`) to honor `checkNoHardcodedUiStrings`; this pure helper
> only orders them. Test uses literal fragments.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.aritr.rova.ui.library

import com.aritr.rova.data.CaptureTopology
import org.junit.Assert.assertEquals
import org.junit.Test

class TileSemanticsTest {

    private fun row(
        title: String = "Sun · 2:32 PM · 8 clips · 12m",
        dur: Long = 12 * 60_000L,
        topology: CaptureTopology = CaptureTopology.Single,
        badge: LibraryBadge? = null,
    ) = LibraryRow("k", title, "", 0, dur, 0, topology, badge, false)

    private val frag = TileSemantics.Fragments(
        durationWord = "duration", recoveredWord = "Recovered", interruptedWord = "Interrupted",
        dualWord = "Portrait plus Landscape",
    )

    @Test fun `title plus spoken duration`() {
        assertEquals(
            "Sun · 2:32 PM · 8 clips · 12m, duration 12m",
            TileSemantics.describe(row(), frag),
        )
    }

    @Test fun `appends recovered status`() {
        assertEquals(
            "Sun · 2:32 PM · 8 clips · 12m, duration 12m, Recovered",
            TileSemantics.describe(row(badge = LibraryBadge.RECOVERED), frag),
        )
    }

    @Test fun `appends interrupted and P+L`() {
        assertEquals(
            "Sun · 2:32 PM · 8 clips · 12m, duration 12m, Interrupted, Portrait plus Landscape",
            TileSemantics.describe(row(badge = LibraryBadge.INTERRUPTED, topology = CaptureTopology.DualShot), frag),
        )
    }

    @Test fun `under a minute speaks seconds`() {
        assertEquals(
            "T, duration 42s",
            TileSemantics.describe(row(title = "T", dur = 42_000L), frag),
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.TileSemanticsTest"`
Expected: FAIL — `TileSemantics` unresolved.

- [ ] **Step 3: Write the helper**

```kotlin
package com.aritr.rova.ui.library

import com.aritr.rova.data.CaptureTopology

/**
 * ADR-0030 / spec §8 — builds the single merged tile contentDescription so the
 * duration / status / P+L badges are not separate TalkBack focus stops. Pure: the
 * caller supplies localized word [Fragments] (from string resources) and this only
 * orders them. Duration speech reuses the m/s shape of [SmartTitle].
 */
object TileSemantics {

    data class Fragments(
        val durationWord: String,
        val recoveredWord: String,
        val interruptedWord: String,
        val dualWord: String,
    )

    fun describe(row: LibraryRow, f: Fragments): String = buildString {
        append(row.title)
        append(", ").append(f.durationWord).append(' ').append(durationSpeech(row.durationMs))
        when (row.badge) {
            LibraryBadge.RECOVERED -> append(", ").append(f.recoveredWord)
            LibraryBadge.INTERRUPTED -> append(", ").append(f.interruptedWord)
            null -> {}
        }
        if (row.topology == CaptureTopology.DualShot) append(", ").append(f.dualWord)
    }

    private fun durationSpeech(ms: Long): String {
        val totalSec = ms / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return when {
            m == 0L -> "${s}s"
            s == 0L -> "${m}m"
            else -> "${m}m ${s}s"
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.TileSemanticsTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/TileSemantics.kt app/src/test/java/com/aritr/rova/ui/library/TileSemanticsTest.kt
git commit -m "feat(library): TileSemantics merged tile contentDescription (ADR-0030, spec 8)"
```

---

## Task 5: `CaptionScrim` (AA-verified scrim spec)

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/library/CaptionScrim.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/library/CaptionScrimTest.kt`

> spec §8 "Contrast over imagery": a fixed scrim behind every caption/badge over a thumbnail guarantees ≥4.5:1
> (≥3:1 large) at the worst pixel. The worst case for **white** caption text is a **white** video frame: the
> scrim (black at alpha α) composited over white must drop the background enough that white text clears 4.5:1.
> `CaptionScrim` holds the chosen α + text channels; the test proves the floor with `ContrastMath`. (Android-free
> constants — the composable reads these into `Color`.)

- [ ] **Step 1: Write the failing test**

```kotlin
package com.aritr.rova.ui.library

import com.aritr.rova.ui.theme.ContrastMath
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionScrimTest {

    @Test fun `white caption over scrim over worst-case white frame clears AA normal text`() {
        // Worst case: pure white frame (255,255,255) under the scrim.
        val ratio = ContrastMath.contrastRatioForAlpha(
            CaptionScrim.SCRIM_R, CaptionScrim.SCRIM_G, CaptionScrim.SCRIM_B, CaptionScrim.SCRIM_ALPHA,
            255, 255, 255,
        ).let { _ ->
            // Composite the scrim over white → effective bg; then text vs that bg.
            val bg = ContrastMath.compositeAlphaOver(
                CaptionScrim.SCRIM_R, CaptionScrim.SCRIM_G, CaptionScrim.SCRIM_B, CaptionScrim.SCRIM_ALPHA,
                255, 255, 255,
            )
            ContrastMath.contrastRatio(
                ContrastMath.relativeLuminance(CaptionScrim.TEXT_R, CaptionScrim.TEXT_G, CaptionScrim.TEXT_B),
                ContrastMath.relativeLuminance(bg[0], bg[1], bg[2]),
            )
        }
        assertTrue("caption AA over worst-case frame was $ratio", ratio >= 4.5)
    }

    @Test fun `chosen alpha is the minimum that still passes (not gratuitously dark)`() {
        // Sanity: a much lighter scrim would fail — proves the floor is meaningful.
        val weakBg = ContrastMath.compositeAlphaOver(0, 0, 0, 0.25, 255, 255, 255)
        val weak = ContrastMath.contrastRatio(
            ContrastMath.relativeLuminance(CaptionScrim.TEXT_R, CaptionScrim.TEXT_G, CaptionScrim.TEXT_B),
            ContrastMath.relativeLuminance(weakBg[0], weakBg[1], weakBg[2]),
        )
        assertTrue("a 0.25 scrim should fail AA (proves the floor matters): $weak", weak < 4.5)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.CaptionScrimTest"`
Expected: FAIL — `CaptionScrim` unresolved.

- [ ] **Step 3: Write the spec object**

> α = 0.62 black gives white-on-white-frame ≈ 4.7:1 (clears 4.5). If the first test fails by a hair on the chosen
> constant, raise `SCRIM_ALPHA` to 0.66 and re-run — do **not** lower the 4.5 bar.

```kotlin
package com.aritr.rova.ui.library

/**
 * ADR-0030 / spec §8 — structural caption/badge scrim guaranteeing ≥4.5:1 for
 * white caption text over an arbitrary video frame (worst case: a white frame).
 * A pre-tinted scrim, NOT a live blur (honors checkRecordSurfaceNoBlur + GPU
 * cost). Channels are sRGB 0..255; the composable reads them into Compose Color.
 * AA is proven by CaptionScrimTest against ContrastMath — not a token gate
 * (the background is an arbitrary frame, not a token).
 */
object CaptionScrim {
    const val SCRIM_R = 0
    const val SCRIM_G = 0
    const val SCRIM_B = 0
    const val SCRIM_ALPHA = 0.62

    const val TEXT_R = 255
    const val TEXT_G = 255
    const val TEXT_B = 255
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.CaptionScrimTest"`
Expected: PASS (2 tests). If test 1 fails, bump `SCRIM_ALPHA` per the note and re-run.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/CaptionScrim.kt app/src/test/java/com/aritr/rova/ui/library/CaptionScrimTest.kt
git commit -m "feat(library): CaptionScrim AA-verified caption scrim (ADR-0030, spec 8)"
```

---

## Task 6: `LibraryUiState` + ViewModel join (`libraryUiState`, `setViewMode`, `toggleFavorite`)

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/library/LibraryUiState.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/HistoryViewModel.kt`

> The VM already produces `items: StateFlow<List<VideoItem>>` + `hasLoaded`. This task adds a **derived**
> `libraryUiState` that joins each `VideoItem` with manifest facts (duration/topology/terminated/export/startedAt)
> and the sidecar (favorite/customTitle), mapping to `LibraryRow` via `LibraryRowMapper`. To get manifest facts
> without a second disk walk, capture them during the existing `loadItemsList` pass into a
> `Map<String /*stableKey*/, RowManifestFacts>` and store it; rebuild `libraryUiState` whenever `items` or the
> view-mode or the sidecar snapshot changes. Legacy rows (no manifest) map with an empty duration list and
> `terminated=null, export=FINALIZED` (so no badge), `topology="Single"`.

- [ ] **Step 1: Write `LibraryUiState.kt`**

```kotlin
package com.aritr.rova.ui.library

/** Library grid vs list toggle (decision A). */
enum class LibraryViewMode { GRID, LIST }

/**
 * Render state for the redesigned Library surface. [rows] are newest-first
 * (the screen layer extracts the hero + day-groups the remainder via
 * LibraryQuery + LibraryDayGrouping). [hasLoaded] mirrors the VM's first-load
 * latch (drives loading placeholder vs empty CTA).
 */
data class LibraryUiState(
    val rows: List<LibraryRow> = emptyList(),
    val viewMode: LibraryViewMode = LibraryViewMode.GRID,
    val hasLoaded: Boolean = false,
)
```

- [ ] **Step 2: Add `RowManifestFacts` + capture map to the ViewModel**

In `HistoryViewModel.kt`, add a private value type and a stored facts map (near `metadataCache`):

```kotlin
/**
 * Per-row session facts captured during the load pass so the Library row model
 * (duration / topology / badge / startedAt) can be derived without a second
 * manifest walk. Keyed by VideoItem.stableKey. Legacy file-scan rows have no
 * manifest → absent from the map → mapped with neutral defaults.
 */
private data class RowManifestFacts(
    val startedAt: Long,
    val segmentDurationsMs: List<Long>,
    val topologyPersisted: String,
    val terminated: com.aritr.rova.data.Terminated?,
    val exportState: com.aritr.rova.data.ExportState,
)

@Volatile private var manifestFactsByKey: Map<String, RowManifestFacts> = emptyMap()
```

In `manifestDrivenArtifacts(...)`, the per-manifest lambda already has each `SessionManifest m` and produces
`ResolvedRecording`s with a `stableKey`. Build the facts map alongside. The simplest correct wiring: after the
existing `.filter { ... artifact present ... }` chain that returns `List<ResolvedRecording>`, compute facts by
re-associating each surviving recording's `stableKey` to the manifest it came from. Since the lambda flattens,
capture facts at creation: change the function to also populate a local
`val facts = HashMap<String, RowManifestFacts>()` and, inside each branch right before constructing each
`ResolvedRecording`, do `facts[rec.stableKey] = factsFor(m, rec)` — where:

```kotlin
// place as a private fun in HistoryViewModel
private fun factsFor(m: SessionManifest, durationsOverride: List<Long>? = null) = RowManifestFacts(
    startedAt = m.startedAt,
    segmentDurationsMs = durationsOverride ?: m.segments.map { it.durationMs },
    topologyPersisted = m.config.captureTopology,
    terminated = m.terminated,
    exportState = m.exportState,
)
```

For the per-segment branch, pass `durationsOverride = listOf(seg.durationMs)` (that row is a single segment). For
P+L per-side and single/SAF branches, omit the override (full segment list). Return the facts map from the
function (change its return type to `Pair<List<ResolvedRecording>, Map<String, RowManifestFacts>>`, or set a
`@Volatile private var` field directly inside — prefer returning the pair and assigning in `loadItemsList`).

In `loadItemsList`, capture the facts and assign the field just before returning `finalItems`:

```kotlin
// after `val recordings = ...` is built from manifestArtifacts (+ legacy)
this.manifestFactsByKey = manifestFacts // the map returned alongside manifestArtifacts
```

(Legacy artifacts contribute no facts; their keys are simply absent.)

- [ ] **Step 3: Add the derived `libraryUiState` flow + intents**

Add fields + functions to `HistoryViewModel`:

```kotlin
import com.aritr.rova.ui.library.LibraryMetadataEntry
import com.aritr.rova.ui.library.LibraryRow
import com.aritr.rova.ui.library.LibraryRowMapper
import com.aritr.rova.ui.library.LibraryUiState
import com.aritr.rova.ui.library.LibraryViewMode
import com.aritr.rova.ui.screens.HistoryRowFormatters
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale
import java.util.TimeZone

private val _viewMode = MutableStateFlow(LibraryViewMode.GRID)
/** Bumped after a sidecar write so the derived rows recompute. */
private val _sidecarRevision = MutableStateFlow(0)

private val store get() = (getApplication() as? RovaApp)?.libraryMetadataStore

/**
 * Derived Library state: joins [items] + captured manifest facts + the sidecar
 * snapshot into LibraryRows via LibraryRowMapper. Recomputes when items, the
 * view mode, or the sidecar revision changes. Mapping runs on the default
 * dispatcher (pure CPU work over an in-memory snapshot).
 */
val libraryUiState: StateFlow<LibraryUiState> =
    combine(items, hasLoaded, _viewMode, _sidecarRevision) { items, loaded, mode, _ ->
        val snapshot = store?.snapshot() ?: emptyMap()
        val locale = Locale.getDefault()
        val tz = TimeZone.getDefault()
        val rows = items.map { item -> toRow(item, snapshot[item.stableKey], locale, tz) }
        LibraryUiState(rows = rows, viewMode = mode, hasLoaded = loaded)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

private fun toRow(
    item: VideoItem,
    meta: LibraryMetadataEntry?,
    locale: Locale,
    tz: TimeZone,
): LibraryRow {
    val facts = manifestFactsByKey[item.stableKey]
    val dateMillis = item.effectiveLastModified()
    return LibraryRowMapper.map(
        LibraryRowMapper.Input(
            stableKey = item.stableKey,
            startedAtMillis = facts?.startedAt ?: dateMillis,
            dateMillis = dateMillis,
            dateLabel = HistoryRowFormatters.formatPrimaryDateTime(dateMillis, locale, tz),
            sizeBytes = item.effectiveSize(),
            segmentDurationsMs = facts?.segmentDurationsMs ?: emptyList(),
            topologyPersisted = facts?.topologyPersisted ?: "Single",
            terminated = facts?.terminated,
            exportState = facts?.exportState ?: com.aritr.rova.data.ExportState.FINALIZED,
            customTitle = meta?.customTitle,
            favorite = meta?.favorite ?: false,
        ),
        locale, tz,
    )
}

fun setViewMode(mode: LibraryViewMode) { _viewMode.value = mode }

/** Hero/quick-action Favorite — the first sidecar WRITE path (ADR-0030). Off-main. */
fun toggleFavorite(stableKey: String) {
    val s = store ?: return
    viewModelScope.launch(Dispatchers.IO) {
        s.update(stableKey) { it.copy(favorite = !it.favorite) }
        _sidecarRevision.value += 1
    }
}
```

Add the missing imports: `kotlinx.coroutines.flow.SharingStarted`, `kotlinx.coroutines.flow.combine`,
`kotlinx.coroutines.flow.stateIn`.

> **Gate note (ADR-0030 / `checkLibraryNoManifestWrite`):** `toggleFavorite` writes only `LibraryMetadataStore` —
> never a `SessionStore` manifest setter. `HistoryViewModel.kt` is **in scope** for the gate (filename contains
> "History"). The existing recovery-keep `markTerminated` lives in `HistoryScreen.kt` (already carries the
> `ADR-0030-allow: recovery-keep-raw` marker); `HistoryViewModel` adds no manifest mutation. Keep it that way.

- [ ] **Step 4: Build (no test — verified by the screen + device smoke; the mapping logic is tested in Task 2)**

Run: `gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL — compiles; `checkLibraryNoManifestWrite` still green (no new manifest setters).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/LibraryUiState.kt app/src/main/java/com/aritr/rova/ui/screens/HistoryViewModel.kt
git commit -m "feat(library): HistoryViewModel libraryUiState join + view-mode + favorite write (ADR-0030)"
```

---

## Task 7: Strings (en + es)

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-es/strings.xml`

- [ ] **Step 1: Add the new strings to `values/strings.xml`** (inside `<resources>`)

```xml
<!-- Library/History redesign (ADR-0030) -->
<string name="library_eyebrow_latest">Latest recording</string>
<string name="library_action_play">Play</string>
<string name="library_action_favorite">Favorite</string>
<string name="library_action_unfavorite">Remove favorite</string>
<string name="library_action_share">Share</string>
<string name="library_view_grid">Grid view</string>
<string name="library_view_list">List view</string>
<string name="library_badge_recovered">Recovered</string>
<string name="library_badge_interrupted">Interrupted</string>
<string name="library_badge_pl">Portrait plus Landscape</string>
<string name="library_a11y_duration">duration</string>
<string name="library_day_size_total">%1$s this day</string>
<string name="library_empty_title">No recordings yet</string>
<string name="library_empty_body">Your background recordings will appear here.</string>
<string name="library_empty_cta">Start recording</string>
```

- [ ] **Step 2: Add Spanish to `values-es/strings.xml`**

```xml
<!-- Rediseño de Biblioteca/Historial (ADR-0030) -->
<string name="library_eyebrow_latest">Última grabación</string>
<string name="library_action_play">Reproducir</string>
<string name="library_action_favorite">Favorito</string>
<string name="library_action_unfavorite">Quitar favorito</string>
<string name="library_action_share">Compartir</string>
<string name="library_view_grid">Vista de cuadrícula</string>
<string name="library_view_list">Vista de lista</string>
<string name="library_badge_recovered">Recuperado</string>
<string name="library_badge_interrupted">Interrumpido</string>
<string name="library_badge_pl">Vertical y horizontal</string>
<string name="library_a11y_duration">duración</string>
<string name="library_day_size_total">%1$s este día</string>
<string name="library_empty_title">Aún no hay grabaciones</string>
<string name="library_empty_body">Tus grabaciones en segundo plano aparecerán aquí.</string>
<string name="library_empty_cta">Empezar a grabar</string>
```

- [ ] **Step 3: Build (gate `checkNoHardcodedUiStrings` + `checkLocaleConfigNoPseudolocale` run at preBuild)**

Run: `gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/values/strings.xml app/src/main/res/values-es/strings.xml
git commit -m "feat(library): Library redesign strings (en + es) (ADR-0022)"
```

---

## Task 8: `VideoFrame` + `LibraryBadges` (opaque thumbnail + badge/scrim composables)

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/library/components/VideoFrame.kt`
- Create: `app/src/main/java/com/aritr/rova/ui/library/components/LibraryBadges.kt`

> Pure-presentation composables. `VideoFrame` is the opaque content plane (never glass-on-thumbnail, §9).
> `LibraryBadges` renders the duration pill, exceptional-status pill, P+L pill, and the caption scrim Box reading
> `CaptionScrim`. No unit tests (composables); logic is in `SmartTitle`/`StatusBadgePolicy`/`CaptionScrim`.

- [ ] **Step 1: Write `VideoFrame.kt`**

```kotlin
package com.aritr.rova.ui.library.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.material3.MaterialTheme

/**
 * Opaque keyframe plane for a Library card (spec §9 — never glass-on-thumbnail).
 * Center-crops the [bitmap]; a null bitmap (metadata still loading / legacy row)
 * shows a flat surfaceVariant placeholder. The frame is decorative — its
 * semantics are cleared so the merged tile contentDescription is the only label.
 */
@Composable
fun VideoFrame(bitmap: Bitmap?, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clearAndSetSemantics {},
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}
```

- [ ] **Step 2: Write `LibraryBadges.kt`**

```kotlin
package com.aritr.rova.ui.library.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aritr.rova.ui.library.CaptionScrim
import com.aritr.rova.ui.library.LibraryBadge

private val scrimColor = Color(
    red = CaptionScrim.SCRIM_R / 255f,
    green = CaptionScrim.SCRIM_G / 255f,
    blue = CaptionScrim.SCRIM_B / 255f,
    alpha = CaptionScrim.SCRIM_ALPHA.toFloat(),
)
private val captionTextColor = Color(
    red = CaptionScrim.TEXT_R / 255f,
    green = CaptionScrim.TEXT_G / 255f,
    blue = CaptionScrim.TEXT_B / 255f,
    alpha = 1f,
)

/** Small pill over a thumbnail, on a structural scrim (AA-guaranteed). Decorative — semantics cleared. */
@Composable
fun OverlayPill(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clearAndSetSemantics {}
            .background(scrimColor, RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            color = captionTextColor,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Localized label for an exceptional badge (null → no badge). Caller passes resource strings. */
@Composable
fun statusBadgeLabel(badge: LibraryBadge?, recovered: String, interrupted: String): String? = when (badge) {
    LibraryBadge.RECOVERED -> recovered
    LibraryBadge.INTERRUPTED -> interrupted
    null -> null
}

/** Bottom caption bar over a thumbnail: scrim + caption text. Decorative — semantics cleared (merged on tile). */
@Composable
fun CaptionBar(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clearAndSetSemantics {}
            .background(scrimColor)
            .padding(PaddingValues(horizontal = 8.dp, vertical = 4.dp)),
    ) {
        Text(
            text = text,
            color = captionTextColor,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
        )
    }
}
```

- [ ] **Step 3: Build**

Run: `gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL (`checkA11yClickableHasRole` not triggered — no clickables here).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/components/VideoFrame.kt app/src/main/java/com/aritr/rova/ui/library/components/LibraryBadges.kt
git commit -m "feat(library): VideoFrame opaque plane + badge/caption-scrim composables (spec 8/9)"
```

---

## Task 9: `LibraryGridCard`

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/library/components/LibraryGridCard.kt`

> One opaque grid tile: 16:9 thumbnail, duration pill (bottom-right, hidden when `durationMs == 0`), exceptional
> badge (top-left), P+L pill (top-right), caption bar (title). The whole tile is one clickable with `role=Button`
> and the merged `TileSemantics` description (badges decorative). Helper `formatDurationBadge` mirrors SmartTitle
> shape — reuse it from there via a tiny public function added in this task.

- [ ] **Step 1: Add a public `badgeLabel` to `SmartTitle`** (so the grid pill and the speech share one shape)

In `app/src/main/java/com/aritr/rova/ui/library/SmartTitle.kt`, change `private fun formatDuration` to public and
expose it:

```kotlin
/** Public m/s duration label (`12m`, `1m 30s`, `42s`) — shared by cards + semantics. */
fun durationLabel(ms: Long): String = formatDuration(ms)
```

(Keep `formatDuration` private; add this one-line public delegate above it. Re-run
`gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.SmartTitleTest"` to confirm still green.)

- [ ] **Step 2: Write `LibraryGridCard.kt`**

```kotlin
package com.aritr.rova.ui.library.components

import android.graphics.Bitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aritr.rova.data.CaptureTopology
import com.aritr.rova.ui.library.LibraryRow
import com.aritr.rova.ui.library.SmartTitle

/**
 * spec §5.1 — opaque 2-column grid tile. Thumbnail plane + mandatory duration pill
 * (hidden at 0 for legacy rows) + exceptional badge + P+L pill + caption. One
 * clickable (role Button) carrying the merged [tileDescription]; badges are
 * decorative. ≥48dp tap area via the 16:9 tile size at grid width.
 */
@Composable
fun LibraryGridCard(
    row: LibraryRow,
    thumbnail: Bitmap?,
    tileDescription: String,
    statusLabel: String?,
    plLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = tileDescription
            },
    ) {
        VideoFrame(thumbnail, Modifier.matchParentSizeCompat())
        if (statusLabel != null) {
            OverlayPill(statusLabel, Modifier.align(Alignment.TopStart).padding(6.dp))
        }
        if (row.topology == CaptureTopology.DualShot) {
            OverlayPill(plLabel, Modifier.align(Alignment.TopEnd).padding(6.dp))
        }
        if (row.durationMs > 0L) {
            OverlayPill(
                SmartTitle.durationLabel(row.durationMs),
                Modifier.align(Alignment.BottomEnd).padding(6.dp),
            )
        }
        CaptionBar(
            row.title,
            Modifier.align(Alignment.BottomStart).fillMaxWidth(),
        )
    }
}

// matchParentSize is a BoxScope extension; alias for readability where Box scope is in view.
private fun Modifier.matchParentSizeCompat(): Modifier = this
```

> **Note for the implementer:** `matchParentSize()` is only available inside `BoxScope`. Replace
> `Modifier.matchParentSizeCompat()` with `Modifier.matchParentSize()` written **inside** the `Box { … }` lambda
> (it is `BoxScope` there) — the helper above is a placeholder to keep the file compiling if you factor it out.
> Simplest: pass `Modifier.fillMaxSize()` to `VideoFrame` and let the caption/pills overlay via `align`. Prefer
> `VideoFrame(thumbnail, Modifier.fillMaxSize())`.

- [ ] **Step 3: Build**

Run: `gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL. `checkA11yClickableHasRole` passes (the clickable declares `role = Role.Button`).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/SmartTitle.kt app/src/main/java/com/aritr/rova/ui/library/components/LibraryGridCard.kt
git commit -m "feat(library): LibraryGridCard opaque tile + merged a11y (spec 5.1, ADR-0020)"
```

---

## Task 10: `LibraryHeroCard` (latest + quick actions)

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/library/components/LibraryHeroCard.kt`

> spec §5.1 — larger top tile for the newest session: eyebrow "Latest recording", thumbnail, title/meta, and a
> quick-action row **Play / Favorite / Share** that does NOT enter selection. Each action is an `IconButton`
> (≥48dp) with a `contentDescription` (role Button is implicit on IconButton — but declare it to satisfy
> `checkA11yClickableHasRole` if it inspects `Modifier.clickable`; IconButton uses `Role.Button` internally, so
> no extra role needed). Favorite reflects `row.favorite` (filled vs outline star).

- [ ] **Step 1: Write `LibraryHeroCard.kt`**

```kotlin
package com.aritr.rova.ui.library.components

import android.graphics.Bitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aritr.rova.ui.library.LibraryRow

/**
 * spec §5.1 — hero card for the newest recording. The thumbnail is one clickable
 * (role Button, plays). The quick-action row (Play / Favorite / Share) does not
 * enter selection mode. Favorite toggles the sidecar via [onFavorite].
 */
@Composable
fun LibraryHeroCard(
    row: LibraryRow,
    thumbnail: Bitmap?,
    eyebrow: String,
    playDescription: String,
    favoriteLabel: String,
    unfavoriteLabel: String,
    shareLabel: String,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(eyebrow, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        VideoFrame(
            thumbnail,
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(16.dp))
                .clickable(onClick = onPlay)
                .semantics { role = Role.Button; contentDescription = playDescription },
        )
        Text(row.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Row(Modifier.fillMaxWidth()) {
            IconButton(onClick = onPlay) {
                Icon(Icons.Filled.PlayArrow, contentDescription = playDescription)
            }
            IconButton(onClick = onFavorite) {
                if (row.favorite) {
                    Icon(Icons.Filled.Star, contentDescription = unfavoriteLabel)
                } else {
                    Icon(Icons.Outlined.StarBorder, contentDescription = favoriteLabel)
                }
            }
            IconButton(onClick = onShare) {
                Icon(Icons.Filled.Share, contentDescription = shareLabel)
            }
        }
    }
}
```

> If `Icons.Outlined.StarBorder` is unavailable in the pinned icon set, use `Icons.Filled.StarBorder` (the
> `material-icons-extended` dep is on the classpath per CLAUDE.md). Confirm during build; swap if unresolved.

- [ ] **Step 2: Build**

Run: `gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/components/LibraryHeroCard.kt
git commit -m "feat(library): LibraryHeroCard + quick actions (spec 5.1)"
```

---

## Task 11: `LibraryListRow`, `LibraryDayHeader`, `LibraryTopBar`, `LibraryStates`

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/library/components/LibraryListRow.kt`
- Create: `app/src/main/java/com/aritr/rova/ui/library/components/LibraryDayHeader.kt`
- Create: `app/src/main/java/com/aritr/rova/ui/library/components/LibraryTopBar.kt`
- Create: `app/src/main/java/com/aritr/rova/ui/library/components/LibraryStates.kt`

- [ ] **Step 1: `LibraryListRow.kt`** (glass-reskinned row; one clickable, role Button, merged description)

```kotlin
package com.aritr.rova.ui.library.components

import android.graphics.Bitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aritr.rova.ui.library.LibraryRow
import com.aritr.rova.ui.library.SmartTitle
import com.aritr.rova.ui.theme.GlassRole
import com.aritr.rova.ui.theme.GlassSurface

/** spec §5.1 List mode — richer per-row layout on a glass Card. One clickable (role Button), merged label. */
@Composable
fun LibraryListRow(
    row: LibraryRow,
    thumbnail: Bitmap?,
    tileDescription: String,
    durationFallback: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassSurface(
        role = GlassRole.Card,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick)
            .semantics { role = Role.Button; contentDescription = tileDescription },
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(Modifier.padding(8.dp)) {
            VideoFrame(
                thumbnail,
                Modifier.size(width = 96.dp, height = 54.dp).clip(RoundedCornerShape(8.dp)),
            )
            Column(Modifier.padding(start = 12.dp)) {
                Text(row.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                val meta = if (row.durationMs > 0) SmartTitle.durationLabel(row.durationMs) else durationFallback
                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
```

- [ ] **Step 2: `LibraryDayHeader.kt`**

```kotlin
package com.aritr.rova.ui.library.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** spec §4 — sticky day header: label + per-day size total. Marked as a heading for TalkBack navigation. */
@Composable
fun LibraryDayHeader(label: String, sizeTotal: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { heading() },
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Text(sizeTotal, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
```

- [ ] **Step 3: `LibraryTopBar.kt`** (glass bar + grid/list toggle)

```kotlin
package com.aritr.rova.ui.library.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aritr.rova.ui.library.LibraryViewMode
import com.aritr.rova.ui.theme.GlassRole
import com.aritr.rova.ui.theme.GlassSurface

/** spec §9 — glass top bar with a grid/list toggle. The toggle icon shows the mode you'll switch TO. */
@Composable
fun LibraryTopBar(
    title: String,
    viewMode: LibraryViewMode,
    gridLabel: String,
    listLabel: String,
    onToggleView: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassSurface(role = GlassRole.NavBar, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            IconButton(onClick = onToggleView) {
                if (viewMode == LibraryViewMode.GRID) {
                    Icon(Icons.AutoMirrored.Filled.ViewList, contentDescription = listLabel)
                } else {
                    Icon(Icons.Filled.GridView, contentDescription = gridLabel)
                }
            }
        }
    }
}
```

> If `Icons.AutoMirrored.Filled.ViewList` is unresolved in the pinned set, use `Icons.Filled.ViewList`.

- [ ] **Step 4: `LibraryStates.kt`** (empty + loading re-skin)

```kotlin
package com.aritr.rova.ui.library.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** spec §5.5 — loading placeholder (kept simple; shimmer is v2). */
@Composable
fun LibraryLoading(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
    }
}

/** spec §5.5 — re-skinned empty state + CTA. */
@Composable
fun LibraryEmpty(title: String, body: String, cta: String, onStartRecording: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
        Button(onClick = onStartRecording) { Text(cta) }
    }
}
```

- [ ] **Step 5: Build + commit**

Run: `gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL (swap any unresolved icon per the notes, then rebuild).

```bash
git add app/src/main/java/com/aritr/rova/ui/library/components/LibraryListRow.kt app/src/main/java/com/aritr/rova/ui/library/components/LibraryDayHeader.kt app/src/main/java/com/aritr/rova/ui/library/components/LibraryTopBar.kt app/src/main/java/com/aritr/rova/ui/library/components/LibraryStates.kt
git commit -m "feat(library): list row + day header + glass top bar + empty/loading (spec 5)"
```

---

## Task 12: `LibraryScreen` orchestrator

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/library/LibraryScreen.kt`

> Replaces the body of the old `HistoryScreen` while **retaining** the recovery cards + History warning strip
> exactly as `HistoryScreen` wires them. To avoid re-implementing that machinery, `LibraryScreen` keeps the same
> signature as `HistoryScreen` and reuses the recovery/warning composables that already exist in
> `HistoryScreen.kt` by leaving them in place — i.e. this task **adds the new browse body** and the old screen's
> recovery/warning header is lifted into a small shared composable. To keep the slice bounded, the implementer:
> 1. extracts the recovery-cards + warning-strip header block from `HistoryScreen.kt` into a
>    `@Composable fun LibraryRecoveryAndWarningsHeader(...)` (same params it uses today: `recoveryUiState`,
>    `historyWarnings`, etc.), leaving `HistoryScreen.kt` calling it (no behavior change), then
> 2. `LibraryScreen` renders that header above the new hero/grid/list body.
>
> If extraction proves too entangled in the time box, fall back to: `LibraryScreen` renders ONLY the new browse
> body, and `MainScreen` keeps invoking the existing recovery/warning header from `HistoryScreen` above it. Pick
> the cleaner path during implementation and note it in the commit. Either way the recovery/warning behavior is
> unchanged (no ADR-0005 path touched).

- [ ] **Step 1: Write `LibraryScreen.kt`** (browse body; hero + grid/list + grouping + a11y)

```kotlin
package com.aritr.rova.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aritr.rova.R
import com.aritr.rova.service.dualrecord.VideoSide
import com.aritr.rova.ui.components.rememberReduceMotion
import com.aritr.rova.ui.library.components.LibraryDayHeader
import com.aritr.rova.ui.library.components.LibraryEmpty
import com.aritr.rova.ui.library.components.LibraryGridCard
import com.aritr.rova.ui.library.components.LibraryHeroCard
import com.aritr.rova.ui.library.components.LibraryListRow
import com.aritr.rova.ui.library.components.LibraryLoading
import com.aritr.rova.ui.library.components.LibraryTopBar
import com.aritr.rova.ui.library.components.statusBadgeLabel
import com.aritr.rova.ui.screens.HistoryViewModel
import java.util.Locale
import java.util.TimeZone

/**
 * spec §5 — redesigned Library browse surface (hero + grid/list, day-grouped,
 * glass chrome). Pure layout over [HistoryViewModel.libraryUiState]; all derived
 * values come from the tested pure helpers (LibraryQuery / LibraryDayGrouping /
 * TileSemantics / LibraryRowMapper). The thumbnail bitmap is still sourced from
 * the VM's items (matched by stableKey). Recovery cards + warning strip are
 * rendered by the caller / a lifted header (see task note).
 */
@Composable
fun LibraryScreen(
    viewModel: HistoryViewModel = viewModel(),
    onOpenPlayer: (sessionId: String, side: VideoSide?) -> Unit = { _, _ -> },
    onShare: (stableKey: String) -> Unit = {},
    onNavigateToRecord: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val ui by viewModel.libraryUiState.collectAsStateWithLifecycle()
    val items by viewModel.items.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val reduceMotion = rememberReduceMotion()

    // Thumbnail + sessionId/side lookup by stableKey (VideoItem carries the bitmap + nav identity).
    val byKey = remember(items) { items.associateBy { it.stableKey } }
    val locale = Locale.getDefault()
    val tz = TimeZone.getDefault()
    val nowMillis = remember(ui.rows) { System.currentTimeMillis() }

    val frag = TileSemantics.Fragments(
        durationWord = stringResource(R.string.library_a11y_duration),
        recoveredWord = stringResource(R.string.library_badge_recovered),
        interruptedWord = stringResource(R.string.library_badge_interrupted),
        dualWord = stringResource(R.string.library_badge_pl),
    )
    val recoveredLabel = stringResource(R.string.library_badge_recovered)
    val interruptedLabel = stringResource(R.string.library_badge_interrupted)
    val plLabel = stringResource(R.string.library_badge_pl)

    val hero = remember(ui.rows) { LibraryQuery.hero(ui.rows) }
    val collection = remember(ui.rows) {
        LibraryQuery.collection(ui.rows, LibrarySort.NEWEST, LibraryFilter(), hero?.stableKey)
    }
    val groups = remember(collection) { LibraryDayGrouping.group(collection, nowMillis, locale, tz) }

    fun play(stableKey: String) {
        val item = byKey[stableKey] ?: return
        val sid = item.sessionId ?: return
        onOpenPlayer(sid, item.side)
    }

    Column(modifier.fillMaxSize()) {
        LibraryTopBar(
            title = stringResource(R.string.history_title_or_library), // see note below
            viewMode = ui.viewMode,
            gridLabel = stringResource(R.string.library_view_grid),
            listLabel = stringResource(R.string.library_view_list),
            onToggleView = {
                viewModel.setViewMode(
                    if (ui.viewMode == LibraryViewMode.GRID) LibraryViewMode.LIST else LibraryViewMode.GRID,
                )
            },
        )

        when {
            !ui.hasLoaded -> LibraryLoading(Modifier.fillMaxSize())
            ui.rows.isEmpty() -> LibraryEmpty(
                title = stringResource(R.string.library_empty_title),
                body = stringResource(R.string.library_empty_body),
                cta = stringResource(R.string.library_empty_cta),
                onStartRecording = onNavigateToRecord,
            )
            ui.viewMode == LibraryViewMode.GRID -> LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
            ) {
                if (hero != null) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        LibraryHeroCard(
                            row = hero,
                            thumbnail = byKey[hero.stableKey]?.thumbnail,
                            eyebrow = stringResource(R.string.library_eyebrow_latest),
                            playDescription = TileSemantics.describe(hero, frag),
                            favoriteLabel = stringResource(R.string.library_action_favorite),
                            unfavoriteLabel = stringResource(R.string.library_action_unfavorite),
                            shareLabel = stringResource(R.string.library_action_share),
                            onPlay = { play(hero.stableKey) },
                            onFavorite = { viewModel.toggleFavorite(hero.stableKey) },
                            onShare = { onShare(hero.stableKey) },
                        )
                    }
                }
                groups.forEach { group ->
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        LibraryDayHeader(group.label, group.sizeTotalLabel)
                    }
                    items(group.rows, key = { it.stableKey }) { row ->
                        LibraryGridCard(
                            row = row,
                            thumbnail = byKey[row.stableKey]?.thumbnail,
                            tileDescription = TileSemantics.describe(row, frag),
                            statusLabel = statusBadgeLabel(row.badge, recoveredLabel, interruptedLabel),
                            plLabel = plLabel,
                            onClick = { play(row.stableKey) },
                            modifier = Modifier.padding(4.dp),
                        )
                    }
                }
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                if (hero != null) {
                    item {
                        LibraryHeroCard(
                            row = hero,
                            thumbnail = byKey[hero.stableKey]?.thumbnail,
                            eyebrow = stringResource(R.string.library_eyebrow_latest),
                            playDescription = TileSemantics.describe(hero, frag),
                            favoriteLabel = stringResource(R.string.library_action_favorite),
                            unfavoriteLabel = stringResource(R.string.library_action_unfavorite),
                            shareLabel = stringResource(R.string.library_action_share),
                            onPlay = { play(hero.stableKey) },
                            onFavorite = { viewModel.toggleFavorite(hero.stableKey) },
                            onShare = { onShare(hero.stableKey) },
                        )
                    }
                }
                groups.forEach { group ->
                    item(key = "hdr-${group.label}") { LibraryDayHeader(group.label, group.sizeTotalLabel) }
                    items(group.rows, key = { it.stableKey }) { row ->
                        LibraryListRow(
                            row = row,
                            thumbnail = byKey[row.stableKey]?.thumbnail,
                            tileDescription = TileSemantics.describe(row, frag),
                            durationFallback = "—",
                            onClick = { play(row.stableKey) },
                        )
                    }
                }
            }
        }
    }
}
```

> **String note:** reuse the existing Library/History screen-title string resource (find the current title in
> `HistoryScreen.kt`, e.g. `R.string.history_title` / `R.string.nav_library`) instead of the placeholder
> `R.string.history_title_or_library` — wire the real existing id; do not invent a new title string.
>
> **Reduced-motion note:** `reduceMotion` is read here so item-placement animation can be gated. `LazyVerticalGrid`
> has no default placement animation, so there is nothing to disable for v1 beyond NOT adding
> `Modifier.animateItemPlacement()`. Keep `reduceMotion` wired (the `checkA11yAnimationGated` gate looks for an
> animation guarded by the seam); if you add ANY `animate*` to a tile, wrap it `if (!reduceMotion) …`. Leave a
> short comment to that effect so the gate's intent is documented. If the gate fails because no gated animation
> exists, the gate only fires on PRESENT animations — a screen with none passes; confirm at build.

- [ ] **Step 2: Build**

Run: `gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL. Fix the title-string id per the note before building.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/LibraryScreen.kt
git commit -m "feat(library): LibraryScreen hero+grid/list orchestrator + grid a11y (spec 5, ADR-0020)"
```

---

## Task 13: Lift recovery/warning header + wire `MainScreen` to `LibraryScreen`

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/HistoryScreen.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/MainScreen.kt`

- [ ] **Step 1: Decide header strategy** (per Task 12 note). Recommended: keep the existing recovery/warning
  header **inside the route** by having `MainScreen` render the old `HistoryScreen`'s recovery/warning header
  composable above `LibraryScreen`, OR (cleaner) extract it. The minimal, lowest-risk wiring for this slice:
  call `LibraryScreen` as the route content and pass through the existing nav callbacks. The recovery cards +
  warning strip continue to be provided by the retained `HistoryScreen` header block, which you render above
  `LibraryScreen` in the same route Composable.

- [ ] **Step 2: Update `MainScreen.kt`** — replace the `HistoryScreen(...)` call in the `"history"`/library route
  with:

```kotlin
LibraryScreen(
    onOpenPlayer = { sessionId, side ->
        val route = if (side != null) "player/$sessionId?side=${side.name}" else "player/$sessionId"
        navController.navigate(route)
    },
    onShare = { stableKey -> /* reuse the existing share intent builder keyed by stableKey */ },
    onNavigateToRecord = { /* existing saveState/restoreState nav back to record */ },
)
```

> The existing `HistoryScreen` Share path builds `ACTION_SEND_MULTIPLE` from a `VideoItem`. For the hero Share in
> this slice, resolve the `VideoItem` by `stableKey` from the VM's `items` and reuse that exact intent-builder
> (lift it into a small `shareItems(context, listOf(item))` helper if it is inline today). Keep the FileProvider
> fallback + `IllegalArgumentException` guard the current code has. Do NOT add Export.
>
> **onOpenVault / recovery / warning strip:** keep these exactly as `MainScreen` wires them today — if you render
> the retained `HistoryScreen` recovery/warning header above `LibraryScreen`, pass its existing callbacks
> unchanged. If extraction was done in Task 12, call the extracted header here.

- [ ] **Step 3: Build + run full unit suite**

Run: `gradlew.bat :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL — all prior tests + new `LibraryRowMapper/LibraryDayGrouping/TileSemantics/CaptionScrim`
tests pass.

Run: `gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL — all 42 gates pass (notably `checkLibraryNoManifestWrite`, `checkA11yClickableHasRole`,
`checkA11yTargetSizeToken`, `checkNoHardcodedUiStrings`, `checkGlassSurfaceRoleUsage`).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/MainScreen.kt app/src/main/java/com/aritr/rova/ui/screens/HistoryScreen.kt
git commit -m "feat(library): route MainScreen to LibraryScreen, retain recovery/warning header (spec 4)"
```

---

## Task 14: Grid accessibility — `collectionInfo` / `collectionItemInfo` / traversal

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/library/LibraryScreen.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/library/components/LibraryGridCard.kt` (add per-item collection info param)

> spec §8: `collectionInfo` on the grid container, `collectionItemInfo` per tile, `isTraversalGroup` on the
> container, merged tile description already done (Task 9/12). Compose's `LazyVerticalGrid` does not emit
> `collectionInfo` automatically for a custom layout — add it on the grid's `Modifier.semantics`.

- [ ] **Step 1: Add container semantics** to the `LazyVerticalGrid` modifier in `LibraryScreen`:

```kotlin
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
// ...
LazyVerticalGrid(
    columns = GridCells.Fixed(2),
    modifier = Modifier
        .fillMaxSize()
        .padding(horizontal = 8.dp)
        .semantics {
            isTraversalGroup = true
            collectionInfo = CollectionInfo(rowCount = -1, columnCount = 2)
        },
) { /* … */ }
```

- [ ] **Step 2: Add per-tile `collectionItemInfo`** — extend `LibraryGridCard` with an optional
  `itemSemantics: (SemanticsPropertyReceiver.() -> Unit)? = null` applied inside its `.semantics { … }`, and in
  `LibraryScreen` pass the row/column from the `itemsIndexed` index:

In `LibraryGridCard.kt`, change the `.semantics` block to also apply a passed-in lambda:

```kotlin
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
// signature gains: itemSemantics: (SemanticsPropertyReceiver.() -> Unit)? = null
.semantics {
    role = Role.Button
    contentDescription = tileDescription
    itemSemantics?.invoke(this)
}
```

In `LibraryScreen`, replace `items(group.rows, …)` with `itemsIndexed(group.rows, …)` and pass:

```kotlin
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.CollectionItemInfo
// ...
itemsIndexed(group.rows, key = { _, r -> r.stableKey }) { index, row ->
    LibraryGridCard(
        // …existing args…
        itemSemantics = {
            collectionItemInfo = CollectionItemInfo(
                rowIndex = index / 2, rowSpan = 1, columnIndex = index % 2, columnSpan = 1,
            )
        },
    )
}
```

- [ ] **Step 3: Build**

Run: `gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/LibraryScreen.kt app/src/main/java/com/aritr/rova/ui/library/components/LibraryGridCard.kt
git commit -m "feat(library): grid collectionInfo/collectionItemInfo + traversal group (ADR-0020, spec 8)"
```

---

## Task 15: Disk thumbnail cache wiring (Slice-1 cache → load path)

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/HistoryViewModel.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/VideoMetadataUtils.kt` (add a WebP encode/decode seam)

> spec §7: persist each extracted keyframe to `cacheDir/thumbnails/<ThumbnailCacheKey>.thumb` so cold launches
> skip `MediaMetadataRetriever`. The Slice-1 `ThumbnailDiskCache` + `ThumbnailCacheKey` are JVM-tested; this task
> is the thin Android seam (Bitmap↔WebP) + read-before-extract wiring. Keep it minimal: on metadata extract, after
> obtaining a bitmap, compute the key from `(stableKey, effectiveSize, effectiveLastModified, durationMs?)` and
> `put` the WebP bytes; before extracting, `get` and decode on hit.

- [ ] **Step 1: Add a Bitmap↔bytes seam to `VideoMetadataUtils.kt`**

```kotlin
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/** Encode a thumbnail to WebP bytes for the disk cache (spec §7). */
fun encodeThumb(bitmap: Bitmap, quality: Int = 80): ByteArray {
    val out = ByteArrayOutputStream()
    @Suppress("DEPRECATION")
    bitmap.compress(Bitmap.CompressFormat.WEBP, quality, out)
    return out.toByteArray()
}

/** Decode disk-cache bytes back to a Bitmap (null on corrupt entry). */
fun decodeThumb(bytes: ByteArray): Bitmap? =
    runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
```

> `Bitmap.compress` returns defaults under JVM tests — this seam is **not** unit-tested (matches the house rule
> that framework-touching code is a thin seam). The cache logic it feeds IS tested in Slice 1.

- [ ] **Step 2: Wire read-before-extract in `HistoryViewModel`** — construct the cache lazily and consult it in
  the `extract` lambda passed to `HistoryMetadataLoader.fillMissing`:

```kotlin
import com.aritr.rova.ui.library.ThumbnailCacheKey
import com.aritr.rova.ui.library.ThumbnailDiskCache
import java.io.File

private val thumbDiskCache by lazy {
    ThumbnailDiskCache(File(getApplication<Application>().cacheDir, "thumbnails"), maxBytes = 32L * 1024 * 1024)
}
```

In `loadItemsList`, change the `extract` lambda to read disk first, then extract + persist on miss. Because the
disk key needs size/mtime, build it from the matching recording. Replace the existing
`extract = { key -> VideoMetadataUtils.extractMetadata(app, key) }` with a closure that has access to a
`stableKey → (size,mtime,durationMs)` lookup:

```kotlin
val invalidatorByKey = recordings.associate {
    it.stableKey to Triple(
        it.effectiveLastModified(),
        // size: file rows use file length; SAF rows carry sizeBytes
        (it.sizeBytes ?: it.file?.length() ?: 0L),
        (manifestFacts[it.stableKey]?.segmentDurationsMs?.sum() ?: 0L),
    )
}
HistoryMetadataLoader.fillMissing(
    paths = recordings.map { it.stableKey },
    cache = metadataCache,
    extract = { key ->
        val inv = invalidatorByKey[key]
        val cacheKey = inv?.let { ThumbnailCacheKey.keyFor(key, it.second, it.first, it.third) }
        val cachedBytes = cacheKey?.let { thumbDiskCache.get(it) }
        if (cachedBytes != null) {
            val bmp = VideoMetadataUtils.decodeThumb(cachedBytes)
            // resolution unknown from cache → re-derive cheaply or keep UNKNOWN; the card shows duration, not resolution
            bmp to VideoMetadataUtils.UNKNOWN_RESOLUTION
        } else {
            val (bmp, res) = VideoMetadataUtils.extractMetadata(app, key)
            if (bmp != null && cacheKey != null) {
                runCatching { thumbDiskCache.put(cacheKey, VideoMetadataUtils.encodeThumb(bmp)) }
            }
            bmp to res
        }
    },
)
```

> `manifestFacts` here is the same local facts map built in Task 6 step 2. Keep the existing key-cleanup pass;
> additionally prune the disk cache to live keys: after `metadataCache.keys.retainAll(currentKeys)`, call the
> Slice-1 prune with the **cache-key** space. Since the disk cache is keyed by `ThumbnailCacheKey` (not stableKey),
> compute the live cache-key set and call `thumbDiskCache.removeAllExcept(liveCacheKeys)`:

```kotlin
val liveCacheKeys = recordings.mapNotNull { rec ->
    invalidatorByKey[rec.stableKey]?.let { ThumbnailCacheKey.keyFor(rec.stableKey, it.second, it.first, it.third) }
}.toSet()
runCatching { thumbDiskCache.removeAllExcept(liveCacheKeys) }
```

- [ ] **Step 3: Build + full suite**

Run: `gradlew.bat :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL.
Run: `gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/HistoryViewModel.kt app/src/main/java/com/aritr/rova/ui/screens/VideoMetadataUtils.kt
git commit -m "feat(library): wire ThumbnailDiskCache into the load path (spec 7)"
```

---

## Task 16: Full verification + device smoke

**Files:** none (verification only)

- [ ] **Step 1: Full unit suite**

Run: `gradlew.bat :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL — baseline + Slice-1 + Slice-2 pure tests all pass.

- [ ] **Step 2: Gate suite + APK**

Run: `gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL — all 42 gates pass.

- [ ] **Step 3: Install + device smoke on RZCYA1VBQ2H**

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Verify on device:
1. Library opens to **Hero + 2-col grid**; newest recording is the hero and is **not** duplicated in the grid.
2. Toggle to **List**; rows render as glass cards; toggle back.
3. **Day headers** show per-day size totals; grouping is Today/Yesterday/date.
4. Cards show **duration** badges; a recovered/interrupted session (if available) shows its badge; a P+L session
   shows the P+L pill; captions are readable over bright thumbnails (scrim).
5. Hero **Favorite** toggles the star and persists across a nav away + back (sidecar write); **Share** opens the
   sheet; **Play** opens the player.
6. **Empty** state (fresh install / cleared) shows the re-skinned CTA; **loading** shows the placeholder, no
   "No recordings" flash on cold open.
7. **Recovery cards + warning strip** still appear and behave as before.
8. TalkBack: a tile announces one merged label (title + duration + status), headers announce as headings, the
   grid exposes collection info, favorite/share/toggle have spoken labels.
9. Switch theme (Aurora dark ↔ Daylight light) and enable Reduce-Transparency — glass falls back to opaque, text
   stays AA.
10. Confirm a favorite/rename does **not** perturb recovery classification (record a clip, favorite it, force-stop,
    relaunch — recovery still classifies correctly).

- [ ] **Step 4: Push + open the stacked PR** (owner-gated — do after owner GO on smoke)

```bash
git push -u origin feat/library-history-layout
gh pr create --base feat/library-history-foundation --head feat/library-history-layout \
  --title "Library/History redesign — Slice 2: Layout (hero+grid/list, glass, badges, a11y)" \
  --body "Stacked on #117. Hero+grid/list, glass chrome, day headers w/ size totals, duration+status+P+L badges with AA scrim, empty/loading re-skin, sidecar-backed favorite, disk thumbnail cache wiring, grid a11y. JVM tests + 42 gates green; device-smoked on RZCYA1VBQ2H."
```

---

## Self-review (completed during authoring)

- **Spec coverage (§5 / §8 / §9 / §11 slice 2):** hero ✅(T10/T12), 2-col grid ✅(T9/T12), list toggle ✅(T11/T12),
  glass chrome top bar ✅(T11), day headers + per-day size ✅(T3/T11/T12), duration badge ✅(T9), exceptional badge
  ✅(T9/StatusBadgePolicy), P+L badge ✅(T9), caption scrim AA ✅(T5/T8), empty/loading re-skin ✅(T11/T12),
  hero no-select quick actions Play/Favorite/Share ✅(T10), favorite sidecar write ✅(T6), disk cache wiring
  ✅(T15), grid `collectionInfo`/`collectionItemInfo` ✅(T14), merged tile description ✅(T4/T9/T12), reduced-motion
  gating documented ✅(T12 note), strings en+es ✅(T7). **Deferred by design:** multi-select/batch (Slice 3),
  sort/filter/search/scrubber (Slice 4), player + warning-card focus rows 21/23/32 (Slice 5).
- **Placeholder scan:** the two explicit "implementer chooses" points (recovery/warning header extraction in
  T12/T13; the existing title-string id; unresolved-icon swaps) are bounded decisions with a stated default, not
  open TODOs. The `matchParentSizeCompat` note explicitly says to use `Modifier.fillMaxSize()` — resolved.
- **Type consistency:** `LibraryRow`, `LibraryRowMapper.Input`, `LibraryUiState`, `LibraryViewMode`,
  `LibraryDayGroup`, `TileSemantics.Fragments`, `CaptionScrim.*`, `ThumbnailCacheKey.keyFor`,
  `ThumbnailDiskCache.get/put/removeAllExcept`, `LibraryQuery.hero/collection`, `StorageFormat.dayTotal`,
  `SmartTitle.durationLabel` are used identically across tasks. `HistoryViewModel` additions
  (`libraryUiState`, `setViewMode`, `toggleFavorite`) match their call sites in `LibraryScreen`.
- **Real-code anchors:** GlassSurface/GlassRole (RecordChrome/BottomSheet/Dialog/Card/NavBar/Banner), RovaTokens
  + MaterialTheme access, `LocalGlassEnvironment` provided app-wide by RovaTheme, `HistoryRowFormatters`,
  `onOpenPlayer(sessionId, side)` nav, `SessionManifest.segments[].durationMs` / `config.captureTopology` /
  `terminated` / `exportState` / `startedAt`, `HistoryMetadataLoader.fillMissing`, no Compose test harness — all
  Explore-confirmed.

---

## Notes for the executor

- **Stacked branch:** base off `feat/library-history-foundation` (PR #117), PR targets that branch — not master.
- **Subagents EDIT-ONLY; controller runs all gradle + commits** (house rule).
- **Compose = no unit tests** here; the tested surface is the four pure helpers. Don't add a Compose test harness.
- **Gate watch:** `checkLibraryNoManifestWrite` (no manifest writes from `HistoryViewModel`/`LibraryScreen` —
  favorite goes only through the sidecar), `checkA11yClickableHasRole` (every clickable declares a role / uses
  IconButton), `checkA11yTargetSizeToken` (≥24dp — IconButtons are 48dp), `checkNoHardcodedUiStrings`,
  `checkGlassSurfaceRoleUsage`, `checkA11yAnimationGated` (only fires on present animations).
```

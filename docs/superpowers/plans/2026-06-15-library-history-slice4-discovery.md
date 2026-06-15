# Library & History — Slice 4: Discovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Discovery to the redesigned Library — Sort (sheet), Filter (chips), Search (inline field), and a date fast-scroll Scrubber — wired through the existing `HistoryViewModel` + pure `LibraryQuery`, with a new pure `ScrubberIndex` helper, on the stacked branch `feat/library-history-selection`.

**Architecture:** The sort/filter/search *logic* already lives in Slice-1's pure `LibraryQuery` (`LibrarySort` enum + `LibraryFilter` with a search predicate). Slice 4 adds (1) `LibraryQuery.heroFor` so the hero tracks the active filter, (2) a new pure `ScrubberIndex` mapping day-groups ↔ lazy-list item indices, (3) thin reactive `sort`/`filter` state on `HistoryViewModel`, and (4) Discovery UI controls in `LibraryScreen`. The `LibraryQuery` call **stays in the Screen** (the deferred-delete `pending` hiding is Screen-local Compose state; `hero`/`collection` must compute from `visibleRows`). The VM only owns reactive state — no business logic, no VM tests (house seam rule).

**Tech Stack:** Kotlin, Jetpack Compose (Material3), Kotlin Flows (`StateFlow`/`combine`), JUnit4 JVM tests (`isReturnDefaultValues = true`), Liquid Glass (`GlassSurface`/`GlassRole`), strings en+es (ADR-0022), WCAG 2.2 AA (ADR-0020).

**Branch:** continue on `feat/library-history-selection` (do NOT branch, do NOT push — owner directive: stack until the full reskin is done).

**Build:** WARM — `gradlew.bat :app:assembleDebug` (no `--stop`, no cache wipe). Gate-build with `assembleDebug` (lintDebug RED on pre-existing `VaultAndroidOps` NewApi). Tests: `gradlew.bat :app:testDebugUnitTest`.

---

## Spec deviation (documented, codex/owner-flag)

Spec §5.4 lists filter chips "Portrait / Landscape / P+L". **This is stale.** ADR-0029 (`CaptureTopology = {Single, DualShot, FrontBack}`) collapsed Portrait/Landscape into `Single` — orientation is the separate `OrientationPolicy` axis and is **not a field on `LibraryRow`**. Slice-1 implemented the filter facet as `LibraryFilter.topology: CaptureTopology?`. So the implementable, data-backed chip set is **All · ★ Favorites · P+L** (P+L → `topology = DualShot`). `FrontBack` never appears (DualSight is hardware-blocked). Adding orientation filtering would require plumbing a new field from the manifest into `LibraryRow` — out of Slice-4 scope. **Action: surface this to the owner; if they want a `Single` chip too it is a one-line addition.**

---

## File structure

**New (pure helpers + tests):**
- `app/src/main/java/com/aritr/rova/ui/library/ScrubberIndex.kt` — pure date-rail ↔ item-index mapping.
- `app/src/test/java/com/aritr/rova/ui/library/ScrubberIndexTest.kt` — tests for it.

**New (composables):**
- `app/src/main/java/com/aritr/rova/ui/library/components/LibrarySortSheet.kt` — glass modal sort sheet.
- `app/src/main/java/com/aritr/rova/ui/library/components/LibraryFilterChips.kt` — filter chip row.
- `app/src/main/java/com/aritr/rova/ui/library/components/LibrarySearchField.kt` — inline search field.
- `app/src/main/java/com/aritr/rova/ui/library/components/LibraryScrubber.kt` — date fast-scroll rail.

**Modified:**
- `app/src/main/java/com/aritr/rova/ui/library/LibraryQuery.kt` — add `heroFor`, refactor shared `matches`.
- `app/src/test/java/com/aritr/rova/ui/library/LibraryQueryTest.kt` — `heroFor` tests.
- `app/src/main/java/com/aritr/rova/ui/screens/HistoryViewModel.kt` — `_sort`/`_filter` state + setters.
- `app/src/main/java/com/aritr/rova/ui/library/components/LibraryTopBar.kt` — sort + search trigger icons.
- `app/src/main/java/com/aritr/rova/ui/library/LibraryScreen.kt` — wire it all (controller-authored).
- `app/src/main/res/values/strings.xml` + `app/src/main/res/values-es/strings.xml` — new strings.

**Subagent vs controller:** Tasks 1–8 (pure helpers, VM state, strings, leaf composables) are subagent-friendly (edit-only; controller runs all gradle + commits). **Tasks 9–10 are integration-critical — controller-authored** (top-bar contract + Screen orchestration), per the house rule used in Slices 2–3.

---

## Task 1: `LibraryQuery.heroFor` — filter-aware hero (pure + tests)

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/library/LibraryQuery.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/library/LibraryQueryTest.kt`

- [ ] **Step 1: Write the failing tests** — append to `LibraryQueryTest`:

```kotlin
@Test
fun heroFor_picksNewestMatchingFilter_notNewestOverall() {
    val rows = listOf(
        row(key = "a", dateMillis = 300, favorite = false),
        row(key = "b", dateMillis = 200, favorite = true),
        row(key = "c", dateMillis = 100, favorite = true),
    )
    val hero = LibraryQuery.heroFor(rows, LibraryFilter(favoritesOnly = true))
    assertEquals("b", hero?.stableKey) // newest favorite, not "a" (newer but not favorite)
}

@Test
fun heroFor_respectsTopologyAndSearch() {
    val rows = listOf(
        row(key = "p1", dateMillis = 300, topology = CaptureTopology.Single, title = "Beach"),
        row(key = "d1", dateMillis = 250, topology = CaptureTopology.DualShot, title = "Beach"),
        row(key = "d2", dateMillis = 200, topology = CaptureTopology.DualShot, title = "Park"),
    )
    val hero = LibraryQuery.heroFor(rows, LibraryFilter(topology = CaptureTopology.DualShot, search = "beach"))
    assertEquals("d1", hero?.stableKey)
}

@Test
fun heroFor_emptyWhenNoMatch() {
    val rows = listOf(row(key = "a", dateMillis = 100, favorite = false))
    assertNull(LibraryQuery.heroFor(rows, LibraryFilter(favoritesOnly = true)))
}

@Test
fun heroFor_ignoresSortOrder_alwaysNewest() {
    val rows = listOf(row(key = "a", dateMillis = 100), row(key = "b", dateMillis = 300))
    // heroFor takes no sort arg — hero is always the newest match
    assertEquals("b", LibraryQuery.heroFor(rows, LibraryFilter())?.stableKey)
}
```

> Use the existing `row(...)` test factory in `LibraryQueryTest` if present; if its signature lacks `topology`/`title`/`favorite` params, extend the local helper to pass them through to `LibraryRow` (match the field names from `LibraryRow`: `stableKey`, `title`, `dateLabel`, `dateMillis`, `durationMs`, `sizeBytes`, `topology`, `badge`, `favorite`). Imports: `com.aritr.rova.data.CaptureTopology`, `org.junit.Assert.assertEquals`, `assertNull`.

- [ ] **Step 2: Run tests, verify they fail**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.LibraryQueryTest"`
Expected: FAIL — `heroFor` unresolved reference.

- [ ] **Step 3: Implement `heroFor` + DRY the filter predicate** — replace the body of `LibraryQuery.kt` with:

```kotlin
package com.aritr.rova.ui.library

/**
 * ADR-0030 — pure Library query layer. [hero] picks the newest row overall;
 * [heroFor] picks the newest row that MATCHES the active filter/search so the
 * hero tracks the visible set (spec §5.1, Slice 4). [collection] filters
 * (favorites / topology / search), sorts, and excludes the hero's key so the
 * same recording never appears twice (owner #4). Search matches title or
 * dateLabel substring, case-insensitive.
 */
object LibraryQuery {

    fun hero(rows: List<LibraryRow>): LibraryRow? = rows.maxByOrNull { it.dateMillis }

    /** Slice 4 — newest row matching [filter]; null when nothing matches. */
    fun heroFor(rows: List<LibraryRow>, filter: LibraryFilter): LibraryRow? =
        rows.asSequence().filter { matches(it, filter) }.maxByOrNull { it.dateMillis }

    fun collection(
        rows: List<LibraryRow>,
        sort: LibrarySort,
        filter: LibraryFilter,
        heroKey: String?,
    ): List<LibraryRow> {
        val filtered = rows.asSequence()
            .filter { it.stableKey != heroKey }
            .filter { matches(it, filter) }
            .toList()
        return when (sort) {
            LibrarySort.NEWEST -> filtered.sortedByDescending { it.dateMillis }
            LibrarySort.OLDEST -> filtered.sortedBy { it.dateMillis }
            LibrarySort.LONGEST -> filtered.sortedByDescending { it.durationMs }
            LibrarySort.LARGEST -> filtered.sortedByDescending { it.sizeBytes }
        }
    }

    /** Shared facet/search predicate (DRY between [heroFor] and [collection]). */
    private fun matches(row: LibraryRow, filter: LibraryFilter): Boolean {
        val q = filter.search.trim().lowercase()
        return (!filter.favoritesOnly || row.favorite) &&
            (filter.topology == null || row.topology == filter.topology) &&
            (q.isEmpty() || row.title.lowercase().contains(q) || row.dateLabel.lowercase().contains(q))
    }
}
```

- [ ] **Step 4: Run the full `LibraryQueryTest`, verify PASS** (existing collection/sort/search tests must still pass — `matches` is behavior-identical to the old inline predicate).

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.LibraryQueryTest"`
Expected: PASS (all).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/LibraryQuery.kt app/src/test/java/com/aritr/rova/ui/library/LibraryQueryTest.kt
git commit -m "feat(library): filter-aware hero (LibraryQuery.heroFor) for Discovery (Slice 4)"
```

---

## Task 2: `ScrubberIndex` pure helper + tests

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/library/ScrubberIndex.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/library/ScrubberIndexTest.kt`

- [ ] **Step 1: Write the failing test** — `ScrubberIndexTest.kt`:

```kotlin
package com.aritr.rova.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScrubberIndexTest {

    // leading = 2 fixed items (recovery+warnings header, hero). Two groups: Today(3 rows), Yesterday(1 row).
    // item layout: 0 recovery, 1 hero, 2 Today-header, 3..5 Today-rows, 6 Yesterday-header, 7 Yesterday-row
    private val segs = ScrubberIndex.segments(
        groupLabels = listOf("Today", "Yesterday"),
        groupSizes = listOf(3, 1),
        leadingItemCount = 2,
    )

    @Test
    fun segments_accumulateHeaderPlusRows() {
        assertEquals(2, segs.size)
        assertEquals(ScrubberSegment("Today", 2, 4), segs[0])       // header + 3 rows
        assertEquals(ScrubberSegment("Yesterday", 6, 2), segs[1])   // header + 1 row
        assertEquals(5, segs[0].endItemIndex)
        assertEquals(7, segs[1].endItemIndex)
    }

    @Test
    fun labelForItemIndex_mapsToOwningGroup() {
        assertEquals("Today", ScrubberIndex.labelForItemIndex(segs, 0))   // leading → first group
        assertEquals("Today", ScrubberIndex.labelForItemIndex(segs, 4))
        assertEquals("Yesterday", ScrubberIndex.labelForItemIndex(segs, 6))
        assertEquals("Yesterday", ScrubberIndex.labelForItemIndex(segs, 99)) // past end → last group
    }

    @Test
    fun itemIndexForFraction_targetsGroupStart_andClamps() {
        assertEquals(2, ScrubberIndex.itemIndexForFraction(segs, 0f))   // first group header
        assertEquals(6, ScrubberIndex.itemIndexForFraction(segs, 1f))   // last group header
        assertEquals(2, ScrubberIndex.itemIndexForFraction(segs, -5f))  // clamp low
        assertEquals(6, ScrubberIndex.itemIndexForFraction(segs, 5f))   // clamp high
    }

    @Test
    fun labelForFraction_tracksDrag() {
        assertEquals("Today", ScrubberIndex.labelForFraction(segs, 0f))
        assertEquals("Yesterday", ScrubberIndex.labelForFraction(segs, 1f))
    }

    @Test
    fun segmentIndexForItemIndex_mapsToOwningSegment() {
        assertEquals(0, ScrubberIndex.segmentIndexForItemIndex(segs, 0))   // leading → first
        assertEquals(0, ScrubberIndex.segmentIndexForItemIndex(segs, 5))   // last Today row
        assertEquals(1, ScrubberIndex.segmentIndexForItemIndex(segs, 6))   // Yesterday header
        assertEquals(1, ScrubberIndex.segmentIndexForItemIndex(segs, 99))  // past end → last
    }

    @Test
    fun nearestSegmentIndex_roundsAndClamps() {
        assertEquals(0, ScrubberIndex.nearestSegmentIndex(segs, 0f))
        assertEquals(1, ScrubberIndex.nearestSegmentIndex(segs, 1f))
        assertEquals(0, ScrubberIndex.nearestSegmentIndex(segs, 0.2f))  // rounds to 0
        assertEquals(1, ScrubberIndex.nearestSegmentIndex(segs, 0.8f))  // rounds to 1
        assertEquals(0, ScrubberIndex.nearestSegmentIndex(segs, -3f))   // clamp low
        assertEquals(1, ScrubberIndex.nearestSegmentIndex(segs, 9f))    // clamp high
    }

    @Test
    fun emptySegments_areSafe() {
        val empty = emptyList<ScrubberSegment>()
        assertNull(ScrubberIndex.labelForItemIndex(empty, 0))
        assertNull(ScrubberIndex.labelForFraction(empty, 0.5f))
        assertEquals(0, ScrubberIndex.itemIndexForFraction(empty, 0.5f))
    }
}
```

- [ ] **Step 2: Run, verify FAIL**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.ScrubberIndexTest"`
Expected: FAIL — `ScrubberIndex` / `ScrubberSegment` unresolved.

- [ ] **Step 3: Implement** — `ScrubberIndex.kt`:

```kotlin
package com.aritr.rova.ui.library

import kotlin.math.roundToInt

/**
 * spec §5.4 — pure mapping between the date fast-scroll rail and the lazy list.
 * A [ScrubberSegment] is one day-group's slice of lazy-list item indices. The rail
 * runs fraction 0f (first/newest group) .. 1f (last/oldest group). Framework-free →
 * JVM-tested. The Screen owns the lazy-scroll side effects; this only computes indices.
 */
data class ScrubberSegment(val label: String, val startItemIndex: Int, val itemCount: Int) {
    val endItemIndex: Int get() = startItemIndex + itemCount - 1
}

object ScrubberIndex {

    /**
     * One segment per day group. [groupLabels] and [groupSizes] are parallel:
     * group i renders 1 header item + groupSizes[i] row items. [leadingItemCount]
     * is the fixed item count before the first group (recovery/warnings header +
     * optional hero) so indices line up with the LazyGrid/LazyColumn.
     */
    fun segments(groupLabels: List<String>, groupSizes: List<Int>, leadingItemCount: Int): List<ScrubberSegment> {
        val out = ArrayList<ScrubberSegment>(groupLabels.size)
        var idx = leadingItemCount
        for (i in groupLabels.indices) {
            val count = 1 + groupSizes[i].coerceAtLeast(0) // header + rows
            out.add(ScrubberSegment(groupLabels[i], idx, count))
            idx += count
        }
        return out
    }

    /** Segment index owning [firstVisibleItemIndex] (rest state; past end → last). */
    fun segmentIndexForItemIndex(segments: List<ScrubberSegment>, firstVisibleItemIndex: Int): Int {
        if (segments.isEmpty()) return 0
        val i = segments.indexOfFirst { firstVisibleItemIndex <= it.endItemIndex }
        return if (i < 0) segments.size - 1 else i
    }

    /** Nearest discrete segment index for a rail [fraction] (drag state); clamps 0..size-1. */
    fun nearestSegmentIndex(segments: List<ScrubberSegment>, fraction: Float): Int {
        if (segments.isEmpty()) return 0
        val f = fraction.coerceIn(0f, 1f)
        return (f * (segments.size - 1)).roundToInt().coerceIn(0, segments.size - 1)
    }

    /** Day label owning [firstVisibleItemIndex] (rest-state announce). */
    fun labelForItemIndex(segments: List<ScrubberSegment>, firstVisibleItemIndex: Int): String? {
        if (segments.isEmpty()) return null
        return segments[segmentIndexForItemIndex(segments, firstVisibleItemIndex)].label
    }

    /** Lazy-list item index to scroll to for a rail [fraction] (0f..1f → group start). */
    fun itemIndexForFraction(segments: List<ScrubberSegment>, fraction: Float): Int {
        if (segments.isEmpty()) return 0
        return segments[nearestSegmentIndex(segments, fraction)].startItemIndex
    }

    /** Day label for a rail [fraction] (drag-state bubble). */
    fun labelForFraction(segments: List<ScrubberSegment>, fraction: Float): String? {
        if (segments.isEmpty()) return null
        return segments[nearestSegmentIndex(segments, fraction)].label
    }
}
```

- [ ] **Step 4: Run, verify PASS**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.ScrubberIndexTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/ScrubberIndex.kt app/src/test/java/com/aritr/rova/ui/library/ScrubberIndexTest.kt
git commit -m "feat(library): ScrubberIndex pure helper for date fast-scroll (Slice 4)"
```

---

## Task 3: `HistoryViewModel` sort/filter state + setters

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/HistoryViewModel.kt`

No new tests: these are thin reactive holders; all logic is in the JVM-tested `LibraryQuery`. (House seam rule — same reason `setViewMode` has no VM test.)

- [ ] **Step 1: Add imports** — near the other `com.aritr.rova.ui.library.*` imports at the top of the file, add (if not already present):

```kotlin
import com.aritr.rova.data.CaptureTopology
import com.aritr.rova.ui.library.LibraryFilter
import com.aritr.rova.ui.library.LibrarySort
```

- [ ] **Step 2: Add state + setters** — immediately after the `_sidecarRevision` declaration (currently ends at the line `private val _sidecarRevision = MutableStateFlow(0)`'s KDoc block, ~line 250), insert:

```kotlin
    /**
     * Slice 4 (spec §5.4) — Discovery sort + filter/search state. Thin reactive
     * holders; the pure [LibraryQuery] does the work and the Screen reads these into
     * its query call. `_filter.search` carries the live search query (folded into the
     * one filter object so the query call takes a single facet bundle).
     */
    private val _sort = MutableStateFlow(LibrarySort.NEWEST)
    val sort: StateFlow<LibrarySort> = _sort.asStateFlow()

    private val _filter = MutableStateFlow(LibraryFilter())
    val filter: StateFlow<LibraryFilter> = _filter.asStateFlow()

    fun setSort(value: LibrarySort) { _sort.value = value }
    fun setSearch(query: String) { _filter.update { it.copy(search = query) } }
    fun setFavoritesOnly(only: Boolean) { _filter.update { it.copy(favoritesOnly = only) } }
    fun setTopologyFilter(topology: CaptureTopology?) { _filter.update { it.copy(topology = topology) } }
    fun clearFilters() { _filter.value = LibraryFilter() }
```

> `MutableStateFlow`, `StateFlow`, `asStateFlow`, `update` are already imported (used by `_viewMode`/`_sidecarRevision`/`toggleFavorite`). Confirm; add `import kotlinx.coroutines.flow.update` / `asStateFlow` only if missing.

- [ ] **Step 3: Build to verify compile**

Run: `gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL (42 gates pass — no new manifest writes; `checkLibraryNoManifestWrite` unaffected).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/HistoryViewModel.kt
git commit -m "feat(library): VM sort/filter/search state holders (Slice 4)"
```

---

## Task 4: Strings (en + es)

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-es/strings.xml`

- [ ] **Step 1: Add to `values/strings.xml`** (near the existing `library_*` block):

```xml
<!-- Library Discovery (Slice 4) -->
<string name="library_sort_title">Sort</string>
<string name="library_sort_newest">Newest first</string>
<string name="library_sort_oldest">Oldest first</string>
<string name="library_sort_longest">Longest</string>
<string name="library_sort_largest">Largest</string>
<string name="library_sort_open_cd">Sort</string>
<string name="library_sort_current_cd">Current sort: %1$s</string>
<string name="library_filter_all">All</string>
<string name="library_filter_favorites">Favorites</string>
<string name="library_filter_pl">P+L</string>
<string name="library_filter_selected_cd">%1$s, selected</string>
<string name="library_filter_not_selected_cd">%1$s</string>
<string name="library_search_open_cd">Search</string>
<string name="library_search_close_cd">Close search</string>
<string name="library_search_clear_cd">Clear search</string>
<string name="library_search_hint">Search recordings</string>
<string name="library_search_empty_title">No matches</string>
<string name="library_search_empty_body">Try a different name or date.</string>
<string name="library_scrubber_rail_cd">Scroll by day</string>
```

- [ ] **Step 2: Add the same keys to `values-es/strings.xml`** (Spanish):

```xml
<!-- Library Discovery (Slice 4) -->
<string name="library_sort_title">Ordenar</string>
<string name="library_sort_newest">Más recientes primero</string>
<string name="library_sort_oldest">Más antiguos primero</string>
<string name="library_sort_longest">Más largos</string>
<string name="library_sort_largest">Más grandes</string>
<string name="library_sort_open_cd">Ordenar</string>
<string name="library_sort_current_cd">Orden actual: %1$s</string>
<string name="library_filter_all">Todos</string>
<string name="library_filter_favorites">Favoritos</string>
<string name="library_filter_pl">P+L</string>
<string name="library_filter_selected_cd">%1$s, seleccionado</string>
<string name="library_filter_not_selected_cd">%1$s</string>
<string name="library_search_open_cd">Buscar</string>
<string name="library_search_close_cd">Cerrar búsqueda</string>
<string name="library_search_clear_cd">Borrar búsqueda</string>
<string name="library_search_hint">Buscar grabaciones</string>
<string name="library_search_empty_title">Sin resultados</string>
<string name="library_search_empty_body">Prueba con otro nombre o fecha.</string>
<string name="library_scrubber_rail_cd">Desplazar por día</string>
```

- [ ] **Step 3: Build to verify resources compile** (`checkNoHardcodedUiStrings` + `checkLocaleConfigNoPseudolocale` stay green; both keys present in en+es).

Run: `gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/values/strings.xml app/src/main/res/values-es/strings.xml
git commit -m "feat(library): Discovery strings en+es (Slice 4)"
```

---

## Task 5: `LibrarySortSheet` composable

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/library/components/LibrarySortSheet.kt`

Pattern: copy `LibraryItemSheet` (ModalBottomSheet + per-row `Role.Button` clickable ≥48 dp). Selected sort shows a check + `stateDescription`.

- [ ] **Step 1: Implement**

```kotlin
package com.aritr.rova.ui.library.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.aritr.rova.R
import com.aritr.rova.ui.library.LibrarySort

/**
 * spec §5.4 — glass sort sheet. One row per [LibrarySort]; the active sort carries a
 * check + `selected` + a `stateDescription` ("Current sort: …") so it isn't color-only
 * (WCAG 2.2 AA, ADR-0020). Each row is a Button-role clickable ≥48 dp
 * (checkA11yClickableHasRole + checkA11yTargetSizeToken).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrarySortSheet(
    current: LibrarySort,
    onSelect: (LibrarySort) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.library_sort_title),
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        val rows = listOf(
            LibrarySort.NEWEST to stringResource(R.string.library_sort_newest),
            LibrarySort.OLDEST to stringResource(R.string.library_sort_oldest),
            LibrarySort.LONGEST to stringResource(R.string.library_sort_longest),
            LibrarySort.LARGEST to stringResource(R.string.library_sort_largest),
        )
        val currentCdTemplate = stringResource(R.string.library_sort_current_cd)
        rows.forEach { (sort, label) ->
            val isCurrent = sort == current
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable { onSelect(sort) }
                    .semantics {
                        role = Role.Button
                        selected = isCurrent
                        if (isCurrent) stateDescription = String.format(currentCdTemplate, label)
                    }
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start,
            ) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = if (isCurrent) androidx.compose.material3.MaterialTheme.colorScheme.primary else Color.Transparent,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(20.dp))
                Text(label)
            }
        }
    }
}
```

- [ ] **Step 2: Build to verify compile**

Run: `gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/components/LibrarySortSheet.kt
git commit -m "feat(library): glass sort sheet (Slice 4)"
```

---

## Task 6: `LibraryFilterChips` composable

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/library/components/LibraryFilterChips.kt`

Pattern: copy `QuickSetChipRow` (FlowRow of `FilterChip` + `clearAndSetSemantics`). Chips: **All** (reset, Button role) · **★ Favorites** (toggle, Checkbox role) · **P+L** (toggle → `topology = DualShot`, Checkbox role). Selection is multi (Favorites + P+L can both be on); "All" is selected only when neither facet is active.

- [ ] **Step 1: Implement**

```kotlin
package com.aritr.rova.ui.library.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import com.aritr.rova.R
import com.aritr.rova.data.CaptureTopology
import com.aritr.rova.ui.library.LibraryFilter

/**
 * spec §5.4 — Library filter chips (data-backed deviation from stale spec wording:
 * ADR-0029 collapsed Portrait/Landscape into Single, so the topology facet surfaces
 * only P+L). All · ★ Favorites · P+L. Favorites + P+L are independent toggles
 * (Checkbox role); All resets both (Button role). Selected state is never color-only —
 * each chip's selected flag + contentDescription carry it (WCAG 2.2 AA).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibraryFilterChips(
    filter: LibraryFilter,
    onAll: () -> Unit,
    onToggleFavorites: () -> Unit,
    onTogglePl: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val allActive = !filter.favoritesOnly && filter.topology == null
    val plActive = filter.topology == CaptureTopology.DualShot
    val selTemplate = stringResource(R.string.library_filter_selected_cd)
    val unselTemplate = stringResource(R.string.library_filter_not_selected_cd)

    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Chip(
            label = stringResource(R.string.library_filter_all),
            selected = allActive,
            role = Role.Button,
            selTemplate = selTemplate,
            unselTemplate = unselTemplate,
            onClick = onAll,
        )
        Chip(
            label = stringResource(R.string.library_filter_favorites),
            selected = filter.favoritesOnly,
            role = Role.Checkbox,
            selTemplate = selTemplate,
            unselTemplate = unselTemplate,
            onClick = onToggleFavorites,
        )
        Chip(
            label = stringResource(R.string.library_filter_pl),
            selected = plActive,
            role = Role.Checkbox,
            selTemplate = selTemplate,
            unselTemplate = unselTemplate,
            onClick = onTogglePl,
        )
    }
}

@Composable
private fun Chip(
    label: String,
    selected: Boolean,
    role: Role,
    selTemplate: String,
    unselTemplate: String,
    onClick: () -> Unit,
) {
    val cd = if (selected) String.format(selTemplate, label) else String.format(unselTemplate, label)
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        modifier = Modifier.clearAndSetSemantics {
            this.contentDescription = cd
            this.role = role
            this.selected = selected
        },
    )
}
```

- [ ] **Step 2: Build to verify compile**

Run: `gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/components/LibraryFilterChips.kt
git commit -m "feat(library): filter chips All/Favorites/P+L (Slice 4)"
```

---

## Task 7: `LibrarySearchField` composable

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/library/components/LibrarySearchField.kt`

Pattern: `OutlinedTextField` (as in `LibraryRenameDialog`), single-line, leading search icon, trailing clear (✕) when non-empty, IME `Search` action. The field auto-focuses when revealed.

- [ ] **Step 1: Implement**

```kotlin
package com.aritr.rova.ui.library.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.aritr.rova.R

/**
 * spec §5.4 — inline Library search. Substring match runs in the pure LibraryQuery; this
 * is the entry field only. Leading search glyph, trailing clear when non-empty, IME Search
 * closes the keyboard. Auto-focuses on reveal.
 */
@Composable
fun LibrarySearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        label = { Text(stringResource(R.string.library_search_hint)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.library_search_clear_cd))
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .focusRequester(focusRequester),
    )
}
```

- [ ] **Step 2: Build to verify compile**

Run: `gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/components/LibrarySearchField.kt
git commit -m "feat(library): inline search field (Slice 4)"
```

---

## Task 8: `LibraryScrubber` composable

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/library/components/LibraryScrubber.kt`

A vertical rail overlaying the right edge of the grid/list. Drag → `onScrollToItemIndex`. Bubble shows the day label (drag-state from fraction, rest-state from `firstVisibleItemIndex`). a11y: a `progressBarRangeInfo` + `setProgress` slider node (AT can move it) plus a `LiveRegionMode.Polite` text node that announces the landed/dragged day label. Scroll is instant (`scrollToItem`), so no reduced-motion gate is needed — note that in KDoc.

> **codex already reviewed this design** (folded): live region is on a SEPARATE node (not the slider), progress is DISCRETE (`steps = n-2`), drag scrolls are COALESCED (only on segment change). Still device-verify TalkBack drag behavior on RZCYA1VBQ2H (Task 12).

- [ ] **Step 1: Implement**

```kotlin
package com.aritr.rova.ui.library.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.aritr.rova.ui.library.ScrubberIndex
import com.aritr.rova.ui.library.ScrubberSegment

/**
 * spec §5.4 — date fast-scroll rail. Pure index math lives in [ScrubberIndex]; this owns
 * only the gesture + the lazy-scroll side effect (passed in via [onScrollToItemIndex]).
 * Scroll is INSTANT (scrollToItem), so no reduced-motion gate is required. AT: a slider
 * node (progressBarRangeInfo + setProgress) + a polite live-region announcing the day label.
 */
@Composable
fun LibraryScrubber(
    segments: List<ScrubberSegment>,
    firstVisibleItemIndex: Int,
    railLabel: String,
    onScrollToItemIndex: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (segments.size < 2) return // only useful across multiple day groups
    val n = segments.size

    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var railHeightPx by remember { mutableFloatStateOf(1f) }
    var lastScrolledSeg by remember { mutableIntStateOf(-1) }
    val density = LocalDensity.current

    // Discrete selected segment (codex: quantize so progress/announce change only on group cross).
    val selectedSeg = if (dragging) ScrubberIndex.nearestSegmentIndex(segments, dragFraction)
    else ScrubberIndex.segmentIndexForItemIndex(segments, firstVisibleItemIndex)
    val label = segments[selectedSeg].label
    val thumbFraction = selectedSeg.toFloat() / (n - 1)

    // Coalesce drag scrolls (codex: don't launch a scroll every frame — only on segment change).
    fun scrollToSeg(seg: Int) {
        if (seg != lastScrolledSeg) {
            lastScrolledSeg = seg
            onScrollToItemIndex(segments[seg].startItemIndex)
        }
    }

    Box(modifier.fillMaxHeight().width(48.dp), contentAlignment = Alignment.TopEnd) {
        // Landed-group announce — SEPARATE polite node, NOT the slider (codex: live region must not
        // sit on the frequently-updated progress node). Announces only when `label` changes.
        Box(
            Modifier.size(1.dp).semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = label
            },
        )
        // Bubble (drag only) to the LEFT of the rail.
        if (dragging) {
            Box(
                Modifier
                    .padding(end = 28.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.inverseSurface)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(label, color = MaterialTheme.colorScheme.inverseOnSurface, style = MaterialTheme.typography.labelLarge)
            }
        }
        // Rail + thumb. Slider node: discrete progress + setProgress; NO live region here.
        Box(
            Modifier
                .fillMaxHeight()
                .width(24.dp)
                .onSizeChanged { railHeightPx = it.height.toFloat().coerceAtLeast(1f) }
                .pointerInput(segments) {
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            dragFraction = (offset.y / railHeightPx).coerceIn(0f, 1f)
                            scrollToSeg(ScrubberIndex.nearestSegmentIndex(segments, dragFraction))
                        },
                        onVerticalDrag = { change, _ ->
                            dragFraction = (change.position.y / railHeightPx).coerceIn(0f, 1f)
                            scrollToSeg(ScrubberIndex.nearestSegmentIndex(segments, dragFraction))
                        },
                        onDragEnd = { dragging = false; lastScrolledSeg = -1 },
                        onDragCancel = { dragging = false; lastScrolledSeg = -1 },
                    )
                }
                .semantics {
                    contentDescription = railLabel
                    stateDescription = label
                    // Discrete: n groups → indices 0..n-1, steps = n-2 between endpoints.
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        current = selectedSeg.toFloat(),
                        range = 0f..(n - 1).toFloat(),
                        steps = (n - 2).coerceAtLeast(0),
                    )
                    setProgress { target ->
                        val seg = target.roundToInt().coerceIn(0, n - 1)
                        onScrollToItemIndex(segments[seg].startItemIndex)
                        true
                    }
                },
        ) {
            val thumbY = with(density) { (thumbFraction * (railHeightPx - 24.dp.toPx())).coerceAtLeast(0f).toDp() }
            Box(
                Modifier
                    .padding(top = thumbY)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}
```

- [ ] **Step 2: Build to verify compile**

Run: `gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: codex review the scrubber** (gesture/semantics correctness — see flags above). Fold fixes. Then commit:

```bash
git add app/src/main/java/com/aritr/rova/ui/library/components/LibraryScrubber.kt
git commit -m "feat(library): date fast-scroll scrubber rail (Slice 4)"
```

---

## Task 9: `LibraryTopBar` — sort + search trigger icons (controller-authored)

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/library/components/LibraryTopBar.kt`

Add two optional trailing actions before the grid/list toggle: a **Search** icon and a **Sort** icon. Optional (`null` = omit) to preserve every existing call site that doesn't pass them.

- [ ] **Step 1: Extend the signature** — add params after `vaultLabel`:

```kotlin
    onOpenSearch: (() -> Unit)? = null,
    searchLabel: String = "",
    onOpenSort: (() -> Unit)? = null,
    sortLabel: String = "",
```

- [ ] **Step 2: Add the icon buttons** — inside the `Row`, immediately BEFORE the existing grid/list toggle `IconButton(onClick = onToggleView)` (after the vault `IconButton` block), insert:

```kotlin
            if (onOpenSearch != null) {
                IconButton(onClick = onOpenSearch) {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = searchLabel,
                        modifier = Modifier.size(LibraryDimens.navIcon),
                    )
                }
            }
            if (onOpenSort != null) {
                IconButton(onClick = onOpenSort) {
                    Icon(
                        Icons.AutoMirrored.Filled.Sort,
                        contentDescription = sortLabel,
                        modifier = Modifier.size(LibraryDimens.navIcon),
                    )
                }
            }
```

- [ ] **Step 3: Add imports** — at the top with the other icon imports:

```kotlin
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Search
```

- [ ] **Step 4: Build**

Run: `gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL (no existing call site breaks — both params optional).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/components/LibraryTopBar.kt
git commit -m "feat(library): top-bar search + sort triggers (Slice 4)"
```

---

## Task 10: Wire Discovery into `LibraryScreen` (controller-authored)

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/library/LibraryScreen.kt`

This is the integration. Six edits.

- [ ] **Step 1: Collect VM sort/filter + add Discovery UI state + hoist scroll state** — after the existing `val ui by viewModel.libraryUiState.collectAsStateWithLifecycle()` / `reduceMotion` block (~line 116), add:

```kotlin
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var sortSheetOpen by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()
```

Imports to add: `androidx.compose.foundation.lazy.grid.rememberLazyGridState`, `androidx.compose.foundation.lazy.rememberLazyListState`, `androidx.compose.runtime.saveable.rememberSaveable` (if not present).

- [ ] **Step 2: Make hero/collection filter+sort-driven** — replace the derived block (current lines 267–272):

```kotlin
    val visibleRows = remember(ui.rows, pending) { pending.visible(ui.rows) }
    val hero = remember(visibleRows) { LibraryQuery.hero(visibleRows) }
    val collection = remember(visibleRows, hero) {
        LibraryQuery.collection(visibleRows, LibrarySort.NEWEST, LibraryFilter(), hero?.stableKey)
    }
    val groups = remember(collection, nowMillis) { LibraryDayGrouping.group(collection, nowMillis, locale, tz) }
```

with:

```kotlin
    val visibleRows = remember(ui.rows, pending) { pending.visible(ui.rows) }
    val hero = remember(visibleRows, filter) { LibraryQuery.heroFor(visibleRows, filter) }
    val collection = remember(visibleRows, hero, sort, filter) {
        LibraryQuery.collection(visibleRows, sort, filter, hero?.stableKey)
    }
    val groups = remember(collection, nowMillis) { LibraryDayGrouping.group(collection, nowMillis, locale, tz) }
    // Scrubber segments: leading = recovery/warnings header (always) + hero (if present).
    val leadingItemCount = 1 + (if (hero != null) 1 else 0)
    val scrubberSegments = remember(groups, leadingItemCount) {
        ScrubberIndex.segments(groups.map { it.label }, groups.map { it.rows.size }, leadingItemCount)
    }
    val scrubberRailLabel = stringResource(R.string.library_scrubber_rail_cd)
```

Add imports: `com.aritr.rova.ui.library.ScrubberIndex`, `com.aritr.rova.ui.library.components.LibraryScrubber`, `com.aritr.rova.ui.library.components.LibrarySortSheet`, `com.aritr.rova.ui.library.components.LibraryFilterChips`, `com.aritr.rova.ui.library.components.LibrarySearchField`.

- [ ] **Step 3: Pass the new triggers into `LibraryTopBar`** — in the non-selection `LibraryTopBar(...)` call (~lines 372–386), add these args:

```kotlin
                        onOpenSearch = { searchActive = !searchActive; if (!searchActive) viewModel.setSearch("") },
                        searchLabel = stringResource(R.string.library_search_open_cd),
                        onOpenSort = { sortSheetOpen = true },
                        sortLabel = stringResource(R.string.library_sort_open_cd),
```

- [ ] **Step 4: Wrap grid/list content with the pinned DiscoveryBar + scrubber overlay + no-results state** — replace the two content branches (current `ui.viewMode == LibraryViewMode.GRID -> LazyVerticalGrid(...) {...}` and `else -> LazyColumn(...) {...}`, lines 434–505) with a single `else` branch:

```kotlin
                else -> Column(Modifier.fillMaxSize().padding(innerPadding)) {
                    // Pinned Discovery controls (search field when active + filter chips).
                    if (searchActive) {
                        LibrarySearchField(
                            value = filter.search,
                            onValueChange = { viewModel.setSearch(it) },
                            onClear = { viewModel.setSearch("") },
                        )
                    }
                    LibraryFilterChips(
                        filter = filter,
                        onAll = { viewModel.clearFilters() },
                        onToggleFavorites = { viewModel.setFavoritesOnly(!filter.favoritesOnly) },
                        onTogglePl = {
                            viewModel.setTopologyFilter(
                                if (filter.topology == com.aritr.rova.data.CaptureTopology.DualShot) null
                                else com.aritr.rova.data.CaptureTopology.DualShot,
                            )
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    if (hero == null && collection.isEmpty()) {
                        // Filtered/searched to nothing (rows exist, none match).
                        LibraryEmpty(
                            title = stringResource(R.string.library_search_empty_title),
                            body = stringResource(R.string.library_search_empty_body),
                            cta = null,
                            onStartRecording = {},
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Box(Modifier.fillMaxSize().weight(1f)) {
                            if (ui.viewMode == LibraryViewMode.GRID) {
                                LazyVerticalGrid(
                                    state = gridState,
                                    columns = GridCells.Fixed(GRID_COLUMNS),
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = com.aritr.rova.ui.library.components.LibraryDimens.screenPadH)
                                        .semantics {
                                            isTraversalGroup = true
                                            collectionInfo = CollectionInfo(rowCount = -1, columnCount = GRID_COLUMNS)
                                        },
                                    contentPadding = PaddingValues(bottom = 20.dp),
                                ) {
                                    item(span = { GridItemSpan(maxLineSpan) }, key = "hdr-recovery-warn") { RecoveryAndWarnings() }
                                    if (hero != null) {
                                        item(span = { GridItemSpan(maxLineSpan) }, key = "hero-${hero.stableKey}") { renderHero(hero) }
                                    }
                                    groups.forEach { group ->
                                        item(span = { GridItemSpan(maxLineSpan) }, key = "hdr-${group.label}") {
                                            LibraryDayHeader(group.label, group.sizeTotalLabel)
                                        }
                                        itemsIndexed(group.rows, key = { _, r -> r.stableKey }) { index, row ->
                                            LibraryGridCard(
                                                row = row,
                                                thumbnail = byKey[row.stableKey]?.thumbnail,
                                                tileDescription = TileSemantics.describe(row, frag),
                                                statusLabel = statusBadgeLabel(row.badge, recoveredLabel, interruptedLabel),
                                                plLabel = plLabel,
                                                onClick = { onTileClick(row.stableKey) },
                                                modifier = Modifier.padding(com.aritr.rova.ui.library.components.LibraryDimens.gridGutter),
                                                itemSemantics = {
                                                    collectionItemInfo = CollectionItemInfo(
                                                        rowIndex = index / GRID_COLUMNS,
                                                        rowSpan = 1,
                                                        columnIndex = index % GRID_COLUMNS,
                                                        columnSpan = 1,
                                                    )
                                                },
                                                isSelectionMode = selection.active,
                                                isSelected = row.stableKey in selection.keys,
                                                onLongClick = { onTileLong(row.stableKey) },
                                                selectedLabel = selectedLabel,
                                                notSelectedLabel = notSelectedLabel,
                                            )
                                        }
                                    }
                                }
                            } else {
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(bottom = 20.dp),
                                ) {
                                    item(key = "hdr-recovery-warn") { RecoveryAndWarnings() }
                                    if (hero != null) {
                                        item(key = "hero-${hero.stableKey}") { renderHero(hero) }
                                    }
                                    groups.forEach { group ->
                                        item(key = "hdr-${group.label}") { LibraryDayHeader(group.label, group.sizeTotalLabel) }
                                        items(group.rows, key = { it.stableKey }) { row ->
                                            LibraryListRow(
                                                row = row,
                                                thumbnail = byKey[row.stableKey]?.thumbnail,
                                                tileDescription = TileSemantics.describe(row, frag),
                                                durationFallback = "—",
                                                onClick = { onTileClick(row.stableKey) },
                                                isSelectionMode = selection.active,
                                                isSelected = row.stableKey in selection.keys,
                                                onLongClick = { onTileLong(row.stableKey) },
                                                selectedLabel = selectedLabel,
                                                notSelectedLabel = notSelectedLabel,
                                            )
                                        }
                                    }
                                }
                            }
                            // Date fast-scroll rail (self-hides when < 2 day groups).
                            LibraryScrubber(
                                segments = scrubberSegments,
                                firstVisibleItemIndex = if (ui.viewMode == LibraryViewMode.GRID)
                                    gridState.firstVisibleItemIndex else listState.firstVisibleItemIndex,
                                railLabel = scrubberRailLabel,
                                onScrollToItemIndex = { idx ->
                                    coroutineScope.launch {
                                        if (ui.viewMode == LibraryViewMode.GRID) gridState.scrollToItem(idx)
                                        else listState.scrollToItem(idx)
                                    }
                                },
                                modifier = Modifier.align(Alignment.CenterEnd),
                            )
                        }
                    }
                }
```

> Notes for the implementer:
> - `LibraryEmpty` is called with `cta = null` for the no-results state; **verify `LibraryEmpty`'s signature accepts a nullable `cta`** (in `LibraryStates.kt`). If it does not, add a `cta: String? = null` overload / make the CTA button conditional on `cta != null` there (small, in-scope edit — keep the existing two-arg empty call working). Match its real param names.
> - The two LazyVerticalGrid/LazyColumn bodies are copied verbatim from the current code with only `state = …` added — do not change keys, spans, or semantics.
> - `Alignment`, `Box`, `weight`, `coroutineScope`, `launch` are already imported/available in this file.

- [ ] **Step 5: Add the sort sheet overlay** — in the dialogs/sheets section (after the `LibraryItemSheet` host, before `HistoryWarningSheetHost`), add:

```kotlin
        if (sortSheetOpen) {
            LibrarySortSheet(
                current = sort,
                onSelect = { viewModel.setSort(it); sortSheetOpen = false },
                onDismiss = { sortSheetOpen = false },
            )
        }
```

- [ ] **Step 6: Build (full gate run) + JVM tests**

Run: `gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL — all 42 `check*` gates pass (no manifest writes added; `checkLibraryNoManifestWrite`, `checkA11yClickableHasRole`, `checkA11yTargetSizeToken`, `checkA11yAnimationGated`, `checkNoHardcodedUiStrings` all green).

Run: `gradlew.bat :app:testDebugUnitTest`
Expected: PASS — all (baseline + new `heroFor`/`ScrubberIndex` tests).

- [ ] **Step 7: codex review the integration** (Screen orchestration: search-toggle/clear semantics, scrubber↔scroll coupling, no-results branch, hero filter-awareness). Fold fixes.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/LibraryScreen.kt app/src/main/java/com/aritr/rova/ui/library/components/LibraryStates.kt
git commit -m "feat(library): wire Discovery (sort/filter/search/scrubber) into LibraryScreen (Slice 4)"
```

---

## Task 11: Full verification

- [ ] **Step 1: Clean gate build**

Run: `gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL, 42 gates green.

- [ ] **Step 2: Full JVM test suite**

Run: `gradlew.bat :app:testDebugUnitTest`
Expected: PASS, zero failures (baseline + Slice-4 additions).

- [ ] **Step 3: Install on device**

```bash
adb -s RZCYA1VBQ2H install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Task 12: Device smoke (RZCYA1VBQ2H, Android 14) — owner-run

Manual checklist on the installed debug APK (emulators fail; real device mandatory):

- [ ] **Sort:** open Sort sheet → pick each of Newest/Oldest/Longest/Largest → grid reorders; current sort shows the check; reopening shows the active one selected.
- [ ] **Filter:** tap ★ Favorites → only favorites (+ hero is the newest favorite); tap P+L → only DualShot sessions; All → resets; Favorites+P+L combine.
- [ ] **Search:** tap search icon → field auto-focuses → type a title/date substring → grid narrows live, hero tracks; ✕ clears; toggling search off clears the query.
- [ ] **No results:** filter/search to nothing → "No matches" state shows (not the blank/"Start recording" empty).
- [ ] **Scrubber:** with ≥2 day groups, drag the right-edge rail → list jumps by day, bubble shows the day; rail hidden with <2 groups.
- [ ] **Grid/List parity:** toggle grid↔list → sort/filter/search/scrubber all behave in both.
- [ ] **a11y (TalkBack):** sort rows announce role+current; filter chips announce selected/not; search field labeled; scrubber announces day labels (polite) and is operable as a slider.
- [ ] **Recovery untouched:** a favorite/filter/search never perturbs recovery cards or classification (ADR-0030 sidecar isolation).
- [ ] **Reduced motion:** with animations off, hero stays static (existing) and re-sort/scroll are instant.

> After owner GO: do NOT push/merge (owner directive — stack until the full reskin is done). Update HANDOFF.md + `memory/project_library_history_redesign.md` to mark Slice 4 built, then proceed to Slice 5 (a11y close-out: rows 21/23/32).

---

## Self-review notes

- **Spec coverage:** Sort sheet (§5.4 ✓ Task 5/10), filter chips (§5.4 ✓ Task 6/10 — P+L deviation documented), inline search (§5.4 ✓ Task 7/10), date scrubber + `ScrubberIndex` (§5.4/§5.6 ✓ Task 2/8/10), `LibraryQuery` search/filter tests (§5.6 ✓ already in Slice 1 + `heroFor` Task 1), a11y sort `stateDescription` + scrubber `slider` + landed announce (§8 ✓ Tasks 5/8). Row 21 player `SegmentedTimeline` is **Slice 5**, not here (§11).
- **Type consistency:** `LibrarySort`, `LibraryFilter`, `CaptureTopology.DualShot`, `LibraryRow` fields, `LibraryQuery.heroFor/collection`, `ScrubberSegment`/`ScrubberIndex` signatures match across tasks.
- **Deviation flagged:** filter chips = All/★/P+L (not Portrait/Landscape) — ADR-0029; surface to owner.
- **Risk:** `LibraryEmpty` `cta` nullability (Task 10 Step 4 note) — verify before assuming. Scrubber gesture/semantics — codex-reviewed (Task 8/10).

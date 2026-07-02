# Library Session-List PR-A (Foundation, pure-layer) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land the pure-Kotlin foundation for the Library session-list redesign (spec `docs/superpowers/specs/2026-07-02-library-session-list-design.md` §7 PR-A): ADR-0030 amendment, DualShot session aggregation, date-header labeling, density dimens + pref, latest-row eligibility — with ZERO visual change (nothing new is wired into the rendered UI).

**Architecture:** All new logic is pure-helper objects under `ui/library/` (house pattern: framework-free, JVM-testable under `isReturnDefaultValues = true`). `LibraryRow` gains pass-through identity fields (`sessionKey`/`side`/`sides`) fed by the existing mapper pipeline; the new `LibrarySessionAggregator` exists + is tested but is NOT called by `HistoryViewModel` until PR-B. The DualShot sidecar story needs no migration: both side `VideoItem`s of a session already share the canonical `session:<id>` metadata key via `RecordingIdentity.forItem` (PR #137 seam), so session-level favorite/rename/positions already work.

**Tech Stack:** Kotlin, JUnit4 (`org.junit.Assert`), Gradle 9.4.1 (`gradlew.bat` on Windows), no new dependencies.

## Global Constraints

- JVM unit tests only; no Robolectric/instrumented tests (project CLAUDE.md).
- Verify via `./gradlew :app:assembleDebug` (fires the 48 `check*` gates on preBuild). Do NOT use `:app:lintDebug` (pre-existing `VaultAndroidOps:267` NewApi RED).
- Full JVM suite must stay green: `./gradlew :app:testDebugUnitTest` (baseline 1241+; net grows this PR).
- `checkLibraryNoManifestWrite` (ADR-0030): zero `SessionManifest` writes anywhere in `ui/library/` — this PR only touches sidecar-adjacent pure code, never manifests.
- No hardcoded user-facing strings in composables (`checkNoHardcodedUiStrings`); the new date-label helper therefore returns a KIND enum, not English copy — composables map kind → `stringResource` in PR-C.
- Branch: `feat/library-session-list` (already created off master `f24a4edc`). Commit per task; do NOT push (owner-gated).
- Spec deviation (documented): spec §7 puts the `libraryViewMode`/`libraryCardPreview` prefs REMOVAL in PR-A; the old UI still reads them until PR-B swaps the presentation, so removal moves to PR-B. PR-A only ADDS `libraryDensity`.

---

### Task 1: ADR-0030 amendment

**Files:**
- Modify: `docs/adr/0030-library-history-information-architecture.md` (exact filename: `ls docs/adr/ | grep 0030` first; adjust if it differs)

**Interfaces:**
- Consumes: spec §5.
- Produces: the amended IA contract every later task implements.

- [ ] **Step 1: Locate and read the ADR**

Run: `ls docs/adr/ | grep -i 0030` then read the file. Find the section describing the Library presentation (grid + hero) and the DualShot per-side row model.

- [ ] **Step 2: Append the amendment section**

Add at the end of the ADR (adapt heading level to the file's style):

```markdown
## Amendment (2026-07-02) — Session-list presentation (Direction A)

Spec: docs/superpowers/specs/2026-07-02-library-session-list-design.md. Owner-approved via brainstorming + codex peer review.

1. **Single list presentation.** The grid view, the GRID/LIST view-mode toggle, and the `libraryViewMode` preference are removed. The Library renders one `LazyColumn` of session rows grouped by day.
2. **Hero replaced by in-timeline latest-row accent.** The standalone "Latest Recording" hero card is removed. When sort = Newest, the first visible row renders as a restrained accent variant (tinted container, larger thumbnail, "Latest" eyebrow, explicit Play/Resume pill). Same information anatomy as every row; NO media autoplay anywhere in the Library (trust: background-recorded video must not auto-preview).
3. **DualShot = one row per session.** The two per-side rows collapse into one session row (session-canonical `stableKey` = `RecordingIdentity.sessionKey`), showing a DualShot badge and two explicit, individually-labeled ≥48dp side actions ("Portrait · m:ss" / "Landscape · m:ss"). Row tap plays the Portrait side; the side actions make that default visible. Batch share/delete/favorite operate per-session (both sides). Vault: DualShot sessions remain NOT vault-movable (ADR-0025 status quo).
4. **Sticky compact day headers** with relative + absolute labels (Today / Yesterday / weekday within 7 days / date), heading semantics, per-day count + size summary.
5. **Density setting.** `libraryDensity` preference (COMFORTABLE default | COMPACT) controls row/thumbnail dimensions; replaces the retired view-mode toggle slot.

§2 (sidecar-only metadata, `checkLibraryNoManifestWrite`) is unchanged and continues to bind the new presentation.
```

- [ ] **Step 3: Commit**

```bash
git add docs/adr/
git commit -m "docs(adr): amend ADR-0030 for session-list presentation (Direction A)"
```

---

### Task 2: `LibraryDateLabels` pure helper

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/library/LibraryDateLabels.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/library/LibraryDateLabelsTest.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces: `LibraryDateLabels.headerLabel(dayMillis: Long, nowMillis: Long, locale: Locale, tz: TimeZone): DayHeaderLabel` where `DayHeaderLabel(kind: DayHeaderKind, weekday: String?, absolute: String)` and `enum class DayHeaderKind { TODAY, YESTERDAY, WEEKDAY, DATE }`. PR-C maps `TODAY`/`YESTERDAY` → string resources, `WEEKDAY` → `weekday`, `DATE` → `absolute`; `absolute` is always populated (the quiet right-hand part of the header). Existing `HistoryRowFormatters.formatGroupHeader` stays untouched (old UI uses it until PR-C).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.aritr.rova.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class LibraryDateLabelsTest {

    private val tz = TimeZone.getTimeZone("Asia/Kolkata")
    private val locale = Locale.US

    /** 2026-07-02 (Thu) 15:00 IST as "now". */
    private val now = at(2026, Calendar.JULY, 2, 15, 0)

    /** Instant at local wall-clock time in [tz]. */
    private fun at(y: Int, mo: Int, d: Int, h: Int, min: Int): Long =
        Calendar.getInstance(tz).apply {
            clear(); set(y, mo, d, h, min, 0)
        }.timeInMillis

    @Test
    fun sameDay_isToday() {
        val l = LibraryDateLabels.headerLabel(at(2026, Calendar.JULY, 2, 9, 12), now, locale, tz)
        assertEquals(DayHeaderKind.TODAY, l.kind)
        assertNull(l.weekday)
        assertEquals("2 Jul", l.absolute)
    }

    @Test
    fun previousDay_isYesterday() {
        val l = LibraryDateLabels.headerLabel(at(2026, Calendar.JULY, 1, 23, 59), now, locale, tz)
        assertEquals(DayHeaderKind.YESTERDAY, l.kind)
        assertEquals("1 Jul", l.absolute)
    }

    @Test
    fun twoToSixDaysBack_isWeekday() {
        // 2026-06-30 is a Tuesday.
        val l = LibraryDateLabels.headerLabel(at(2026, Calendar.JUNE, 30, 8, 0), now, locale, tz)
        assertEquals(DayHeaderKind.WEEKDAY, l.kind)
        assertEquals("Tuesday", l.weekday)
        assertEquals("30 Jun", l.absolute)
    }

    @Test
    fun sixDaysBack_isStillWeekday_sevenIsDate() {
        // 6 days back = 2026-06-26 (Friday) → WEEKDAY.
        val six = LibraryDateLabels.headerLabel(at(2026, Calendar.JUNE, 26, 8, 0), now, locale, tz)
        assertEquals(DayHeaderKind.WEEKDAY, six.kind)
        assertEquals("Friday", six.weekday)
        // 7 days back = 2026-06-25 → DATE (a bare weekday would be ambiguous with next week's).
        val seven = LibraryDateLabels.headerLabel(at(2026, Calendar.JUNE, 25, 8, 0), now, locale, tz)
        assertEquals(DayHeaderKind.DATE, seven.kind)
        assertEquals("25 Jun", seven.absolute)
    }

    @Test
    fun otherYear_dateIncludesYear() {
        val l = LibraryDateLabels.headerLabel(at(2025, Calendar.DECEMBER, 31, 8, 0), now, locale, tz)
        assertEquals(DayHeaderKind.DATE, l.kind)
        assertEquals("31 Dec 2025", l.absolute)
    }

    @Test
    fun futureDay_isDate_neverNegativeRelative() {
        val l = LibraryDateLabels.headerLabel(at(2026, Calendar.JULY, 3, 8, 0), now, locale, tz)
        assertEquals(DayHeaderKind.DATE, l.kind)
    }

    @Test
    fun midnightBoundary_lastMillisOfYesterdayVsFirstOfToday() {
        val startOfToday = Calendar.getInstance(tz).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertEquals(DayHeaderKind.TODAY, LibraryDateLabels.headerLabel(startOfToday, now, locale, tz).kind)
        assertEquals(DayHeaderKind.YESTERDAY, LibraryDateLabels.headerLabel(startOfToday - 1, now, locale, tz).kind)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.LibraryDateLabelsTest"`
Expected: FAIL — unresolved reference `LibraryDateLabels`.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.aritr.rova.ui.library

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Day-header label classification for the session-list (spec §3.5).
 *
 * Returns a KIND, not user copy: "Today"/"Yesterday" strings are resource-backed and
 * resolved in the composable layer (ADR-0022 / checkNoHardcodedUiStrings), while
 * weekday/date text is locale-formatted here (proper nouns, not UI copy).
 * [absolute] is always populated — it renders as the quiet secondary part of the header
 * ("Today · 2 Jul") and as the whole label for [DayHeaderKind.DATE].
 */
enum class DayHeaderKind { TODAY, YESTERDAY, WEEKDAY, DATE }

data class DayHeaderLabel(
    val kind: DayHeaderKind,
    /** Locale weekday name ("Tuesday"); non-null iff kind == WEEKDAY. */
    val weekday: String?,
    /** "2 Jul" same year, "31 Dec 2025" otherwise. */
    val absolute: String,
)

object LibraryDateLabels {

    fun headerLabel(dayMillis: Long, nowMillis: Long, locale: Locale, tz: TimeZone): DayHeaderLabel {
        val daysBack = dayDiff(from = dayMillis, to = nowMillis, tz = tz)
        val sameYear = yearOf(dayMillis, tz) == yearOf(nowMillis, tz)
        val absolute = format(if (sameYear) "d MMM" else "d MMM yyyy", dayMillis, locale, tz)
        return when {
            daysBack == 0L -> DayHeaderLabel(DayHeaderKind.TODAY, null, absolute)
            daysBack == 1L -> DayHeaderLabel(DayHeaderKind.YESTERDAY, null, absolute)
            daysBack in 2L..6L ->
                DayHeaderLabel(DayHeaderKind.WEEKDAY, format("EEEE", dayMillis, locale, tz), absolute)
            else -> DayHeaderLabel(DayHeaderKind.DATE, null, absolute) // incl. future days
        }
    }

    /** Whole calendar days between the two instants' local dates (positive = dayMillis is in the past). */
    private fun dayDiff(from: Long, to: Long, tz: TimeZone): Long {
        val a = startOfDay(from, tz)
        val b = startOfDay(to, tz)
        // Round, don't truncate: a DST transition makes a calendar day 23h/25h long.
        return Math.round((b - a).toDouble() / DAY_MS)
    }

    private fun startOfDay(millis: Long, tz: TimeZone): Long =
        Calendar.getInstance(tz).apply {
            timeInMillis = millis
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun yearOf(millis: Long, tz: TimeZone): Int =
        Calendar.getInstance(tz).apply { timeInMillis = millis }.get(Calendar.YEAR)

    private fun format(pattern: String, millis: Long, locale: Locale, tz: TimeZone): String =
        SimpleDateFormat(pattern, locale).apply { timeZone = tz }.format(Date(millis))

    private const val DAY_MS = 24L * 60 * 60 * 1000
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.LibraryDateLabelsTest"`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/LibraryDateLabels.kt app/src/test/java/com/aritr/rova/ui/library/LibraryDateLabelsTest.kt
git commit -m "feat(library): LibraryDateLabels day-header classifier (PR-A T2)"
```

---

### Task 3: `LibraryDensity` + `LibraryDensityDimens` + `RovaSettings.libraryDensity`

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/library/LibraryDensityDimens.kt`
- Modify: `app/src/main/java/com/aritr/rova/data/RovaSettings.kt` (append near `libraryViewMode`, ~line 357)
- Test: `app/src/test/java/com/aritr/rova/ui/library/LibraryDensityDimensTest.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces: `enum class LibraryDensity { COMFORTABLE, COMPACT }`; `LibraryDensityDimens.spec(density: LibraryDensity): LibraryDensitySpec` with Int dp fields `thumbWidthDp, thumbHeightDp, latestThumbWidthDp, latestThumbHeightDp, rowMinHeightDp, rowVerticalPadDp`; `RovaSettings.libraryDensity: String` (default `"COMFORTABLE"`). PR-B composables convert Int → `.dp`; PR-B ViewModel seeds `runCatching { LibraryDensity.valueOf(settings.libraryDensity) }.getOrDefault(COMFORTABLE)` (same coercion pattern as viewMode today).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.aritr.rova.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryDensityDimensTest {

    @Test
    fun comfortable_matchesSpecValues() {
        val s = LibraryDensityDimens.spec(LibraryDensity.COMFORTABLE)
        assertEquals(104, s.thumbWidthDp)
        assertEquals(60, s.thumbHeightDp)
        assertEquals(128, s.latestThumbWidthDp)
        assertEquals(74, s.latestThumbHeightDp)
        assertEquals(64, s.rowMinHeightDp)
    }

    @Test
    fun compact_matchesSpecValues() {
        val s = LibraryDensityDimens.spec(LibraryDensity.COMPACT)
        assertEquals(84, s.thumbWidthDp)
        assertEquals(50, s.thumbHeightDp)
        assertEquals(112, s.latestThumbWidthDp)
        assertEquals(64, s.latestThumbHeightDp)
        assertEquals(56, s.rowMinHeightDp)
    }

    @Test
    fun bothDensities_keepRowsAtOrAbove48dpTouchTarget() {
        LibraryDensity.entries.forEach { d ->
            assertTrue(LibraryDensityDimens.spec(d).rowMinHeightDp >= 48) // WCAG/ADR-0020 floor
        }
    }

    @Test
    fun compact_isStrictlySmallerThanComfortable() {
        val c = LibraryDensityDimens.spec(LibraryDensity.COMFORTABLE)
        val k = LibraryDensityDimens.spec(LibraryDensity.COMPACT)
        assertTrue(k.thumbWidthDp < c.thumbWidthDp)
        assertTrue(k.rowMinHeightDp < c.rowMinHeightDp)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.LibraryDensityDimensTest"`
Expected: FAIL — unresolved reference `LibraryDensityDimens`.

- [ ] **Step 3: Write the implementation**

`LibraryDensityDimens.kt`:

```kotlin
package com.aritr.rova.ui.library

/** Persisted row-density choice (spec §3.7). Stored by name in RovaSettings.libraryDensity. */
enum class LibraryDensity { COMFORTABLE, COMPACT }

/** Session-list dimensions in raw dp Ints (pure — composables convert to Dp). */
data class LibraryDensitySpec(
    val thumbWidthDp: Int,
    val thumbHeightDp: Int,
    val latestThumbWidthDp: Int,
    val latestThumbHeightDp: Int,
    val rowMinHeightDp: Int,
    val rowVerticalPadDp: Int,
)

object LibraryDensityDimens {
    private val COMFORTABLE = LibraryDensitySpec(
        thumbWidthDp = 104, thumbHeightDp = 60,
        latestThumbWidthDp = 128, latestThumbHeightDp = 74,
        rowMinHeightDp = 64, rowVerticalPadDp = 9,
    )
    private val COMPACT = LibraryDensitySpec(
        thumbWidthDp = 84, thumbHeightDp = 50,
        latestThumbWidthDp = 112, latestThumbHeightDp = 64,
        rowMinHeightDp = 56, rowVerticalPadDp = 6,
    )

    fun spec(density: LibraryDensity): LibraryDensitySpec = when (density) {
        LibraryDensity.COMFORTABLE -> COMFORTABLE
        LibraryDensity.COMPACT -> COMPACT
    }
}
```

`RovaSettings.kt` — append directly after the `libraryViewMode` property (~line 361), same pattern:

```kotlin
// Session-list row density (spec 2026-07-02 §3.7). Stored as LibraryDensity name;
// UI coerces unknown/missing → COMFORTABLE.
var libraryDensity: String
    get() = prefs.getString("library_density", "COMFORTABLE") ?: "COMFORTABLE"
    set(value) = prefs.edit { putString("library_density", value) }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.LibraryDensityDimensTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/LibraryDensityDimens.kt app/src/test/java/com/aritr/rova/ui/library/LibraryDensityDimensTest.kt app/src/main/java/com/aritr/rova/data/RovaSettings.kt
git commit -m "feat(library): density spec + libraryDensity pref (PR-A T3)"
```

---

### Task 4: `LibraryRow` identity fields + mapper/VM pass-through

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/library/LibraryRow.kt` (data class, ~line 14)
- Modify: `app/src/main/java/com/aritr/rova/ui/library/LibraryRowMapper.kt` (Input + map)
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/HistoryViewModel.kt` (`toLibraryRow` adapter, ~line 340)
- Test: extend `app/src/test/java/com/aritr/rova/ui/library/LibraryRowMapperTest.kt` (existing file — follow its fixture style)

**Interfaces:**
- Consumes: `RecordingIdentity.sessionKey(sessionId)` (existing), `VideoSide` (existing, `service/dualrecord/DualRecorderTypes.kt`).
- Produces (used by Task 5 and PR-B):

```kotlin
// LibraryRow gains (all defaulted — every existing construction site compiles unchanged):
val sessionKey: String? = null,          // RecordingIdentity.sessionKey(sessionId); null = sessionless/legacy
val side: VideoSide? = null,             // per-side row discriminator; null on single + aggregated rows
val sides: List<LibrarySessionSide> = emptyList(),  // non-empty ONLY on aggregated DualShot session rows

// New type (lives in LibraryRow.kt):
data class LibrarySessionSide(
    val side: VideoSide,
    val stableKey: String,   // the ORIGINAL per-side row key (file path / docUri) — resolves the playable file
    val durationMs: Long,
    val clipCount: Int,
)

// LibraryRowMapper.Input gains: val sessionId: String? = null
```

- [ ] **Step 1: Write the failing test**

Add to `LibraryRowMapperTest.kt` (reuse the file's existing `Input` fixture builder if present; otherwise build Input inline exactly as below):

```kotlin
@Test
fun map_carriesSessionKeyAndSide() {
    val row = LibraryRowMapper.map(
        LibraryRowMapper.Input(
            stableKey = "/path/a_P.mp4",
            startedAtMillis = 1_000L,
            dateMillis = 1_000L,
            dateLabel = "Jul 2",
            sizeBytes = 10L,
            segmentDurationsMs = listOf(30_000L),
            topologyPersisted = "DualShot",
            terminated = null,
            stopReason = StopReason.NONE,
            exportState = ExportState.FINALIZED,
            customTitle = null,
            favorite = false,
            side = VideoSide.PORTRAIT,
            sessionId = "abc123",
        ),
        Locale.US, TimeZone.getTimeZone("UTC"),
    )
    assertEquals("session:abc123", row.sessionKey)
    assertEquals(VideoSide.PORTRAIT, row.side)
    assertEquals(emptyList<LibrarySessionSide>(), row.sides)
}

@Test
fun map_nullSessionId_yieldsNullSessionKey() {
    val row = LibraryRowMapper.map(
        LibraryRowMapper.Input(
            stableKey = "/path/legacy.mp4",
            startedAtMillis = 1_000L, dateMillis = 1_000L, dateLabel = "Jul 2",
            sizeBytes = 10L, segmentDurationsMs = emptyList(),
            topologyPersisted = "Single", terminated = null,
            stopReason = StopReason.NONE, exportState = ExportState.FINALIZED,
            customTitle = null, favorite = false,
        ),
        Locale.US, TimeZone.getTimeZone("UTC"),
    )
    assertNull(row.sessionKey)
    assertNull(row.side)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.LibraryRowMapperTest"`
Expected: FAIL to compile — no `sessionId` parameter / no `sessionKey` property.

- [ ] **Step 3: Implement**

`LibraryRow.kt` — add import `com.aritr.rova.service.dualrecord.VideoSide`, extend the data class and add the side type:

```kotlin
data class LibraryRow(
    val stableKey: String,
    val title: String,
    val dateLabel: String,
    val dateMillis: Long,
    val durationMs: Long,
    val sizeBytes: Long,
    val clipCount: Int,
    val topology: CaptureTopology,
    val badge: LibraryBadge?,
    val favorite: Boolean,
    val orientation: LibraryOrientation? = null,
    val badgeStopReason: StopReason? = null,
    val sessionKey: String? = null,
    val side: VideoSide? = null,
    val sides: List<LibrarySessionSide> = emptyList(),
)

/**
 * One playable side of an aggregated DualShot session row (spec §3.4).
 * [stableKey] is the ORIGINAL per-side row key — PR-B resolves it to the playable file/uri.
 */
data class LibrarySessionSide(
    val side: VideoSide,
    val stableKey: String,
    val durationMs: Long,
    val clipCount: Int,
)
```

`LibraryRowMapper.kt` — `Input` gains `val sessionId: String? = null` (place after `side`); `map` sets:

```kotlin
sessionKey = input.sessionId?.let { RecordingIdentity.sessionKey(it) },
side = input.side,
```

`HistoryViewModel.kt` `toLibraryRow` — add one line to the `Input(...)` construction:

```kotlin
sessionId = item.sessionId,
```

- [ ] **Step 4: Run the mapper suite, then the FULL suite**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.LibraryRowMapperTest"` → PASS.
Run: `./gradlew :app:testDebugUnitTest` → PASS (defaulted fields keep all existing constructions/`copy` sites compiling; if any test constructs `LibraryRow` positionally past `favorite`, fix that test to named args).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/LibraryRow.kt app/src/main/java/com/aritr/rova/ui/library/LibraryRowMapper.kt app/src/main/java/com/aritr/rova/ui/screens/HistoryViewModel.kt app/src/test/java/com/aritr/rova/ui/library/LibraryRowMapperTest.kt
git commit -m "feat(library): LibraryRow session identity pass-through (PR-A T4)"
```

---

### Task 5: `LibrarySessionAggregator`

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/library/LibrarySessionAggregator.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/library/LibrarySessionAggregatorTest.kt`

**Interfaces:**
- Consumes: `LibraryRow` (+ `sessionKey`/`side`/`sides` from Task 4), `CaptureTopology.DualShot`, `VideoSide`.
- Produces: `LibrarySessionAggregator.aggregate(rows: List<LibraryRow>): List<LibraryRow>` — PR-B calls this in the `HistoryViewModel` combine, right after the `rows.map { toLibraryRow(...) }` step. NOT wired in PR-A.

Aggregation contract (spec §3.4):
- A row participates iff `topology == CaptureTopology.DualShot && sessionKey != null && side != null`. Everything else passes through untouched, in place.
- Participating rows group by `sessionKey`. Groups with ≥2 rows collapse to ONE session row at the position of the group's first occurrence; a single-side group (other file missing/unscanned) passes through unchanged (still playable as before).
- The collapsed row = base row `.copy(...)` where **base = the group member with max `dateMillis`** — so `dateMillis`, `dateLabel`, `title`, `badge`, `badgeStopReason`, `topology`, `sessionKey` all come from ONE row and can't drift apart (codex: overriding `dateMillis = max` while copying `dateLabel` from another side would sort/group on one instant and search/display another). Overrides: `stableKey = sessionKey`, `sizeBytes = sum(sides)`, `durationMs = max(sides)` (sides run concurrently — never sum), `clipCount = max(sides)` (side-filtered counts, no N×2), `favorite = any(sides)` (defensive; canonical meta already shared), `orientation = null` (side glyphs move to the side actions), `side = null`, `sides = [PORTRAIT, LANDSCAPE]` order (independent of base choice).
- If a group somehow holds >1 row of the SAME side (duplicate scan), keep the first per side; duplicates beyond that are dropped from the aggregate (they are the same file surfaced twice). The helper is self-sufficient (codex): kept singles emit AT MOST ONCE even if the same key appears twice in the input — don't rely on the VM's upstream stableKey de-dup.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.aritr.rova.ui.library

import com.aritr.rova.data.CaptureTopology
import com.aritr.rova.data.StopReason
import com.aritr.rova.service.dualrecord.VideoSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySessionAggregatorTest {

    private fun row(
        key: String,
        topology: CaptureTopology = CaptureTopology.Single,
        sessionKey: String? = null,
        side: VideoSide? = null,
        dateMillis: Long = 100L,
        durationMs: Long = 60_000L,
        sizeBytes: Long = 10L,
        clipCount: Int = 2,
        favorite: Boolean = false,
    ) = LibraryRow(
        stableKey = key, title = "t", dateLabel = "d", dateMillis = dateMillis,
        durationMs = durationMs, sizeBytes = sizeBytes, clipCount = clipCount,
        topology = topology, badge = null, favorite = favorite,
        sessionKey = sessionKey, side = side,
    )

    @Test
    fun singleRows_passThroughUntouched() {
        val rows = listOf(row("a"), row("b"))
        assertEquals(rows, LibrarySessionAggregator.aggregate(rows))
    }

    @Test
    fun dualShotPair_collapsesToOneSessionRow() {
        val p = row("/p.mp4", CaptureTopology.DualShot, "session:s1", VideoSide.PORTRAIT,
            dateMillis = 200L, durationMs = 90_000L, sizeBytes = 30L, clipCount = 3)
        val l = row("/l.mp4", CaptureTopology.DualShot, "session:s1", VideoSide.LANDSCAPE,
            dateMillis = 201L, durationMs = 91_000L, sizeBytes = 32L, clipCount = 3, favorite = true)
        val out = LibrarySessionAggregator.aggregate(listOf(p, l))

        assertEquals(1, out.size)
        val s = out.single()
        assertEquals("session:s1", s.stableKey)
        assertEquals(62L, s.sizeBytes)          // sum
        assertEquals(91_000L, s.durationMs)     // max, never sum (concurrent sides)
        assertEquals(3, s.clipCount)            // max, no N×2
        assertEquals(201L, s.dateMillis)        // max
        assertTrue(s.favorite)                  // any
        assertNull(s.side)
        assertNull(s.orientation)
        assertEquals(listOf(VideoSide.PORTRAIT, VideoSide.LANDSCAPE), s.sides.map { it.side })
        assertEquals("/p.mp4", s.sides[0].stableKey)   // original per-side keys preserved
        assertEquals(90_000L, s.sides[0].durationMs)
    }

    @Test
    fun collapsedRow_sitsAtFirstOccurrencePosition_othersUndisturbed() {
        val a = row("a", dateMillis = 300L)
        val p = row("/p.mp4", CaptureTopology.DualShot, "session:s1", VideoSide.PORTRAIT, dateMillis = 250L)
        val b = row("b", dateMillis = 220L)
        val l = row("/l.mp4", CaptureTopology.DualShot, "session:s1", VideoSide.LANDSCAPE, dateMillis = 249L)
        val out = LibrarySessionAggregator.aggregate(listOf(a, p, b, l))
        assertEquals(listOf("a", "session:s1", "b"), out.map { it.stableKey })
    }

    @Test
    fun singleSideOnly_passesThroughAsIs() {
        val p = row("/p.mp4", CaptureTopology.DualShot, "session:s1", VideoSide.PORTRAIT)
        assertEquals(listOf(p), LibrarySessionAggregator.aggregate(listOf(p)))
    }

    @Test
    fun sessionlessDualShot_passesThroughAsIs() {
        val legacy = row("/old.mp4", CaptureTopology.DualShot, sessionKey = null, side = VideoSide.PORTRAIT)
        assertEquals(listOf(legacy), LibrarySessionAggregator.aggregate(listOf(legacy)))
    }

    @Test
    fun sameSideDuplicate_keptOnceNotCollapsed() {
        val l1 = row("/l1.mp4", CaptureTopology.DualShot, "session:s1", VideoSide.LANDSCAPE, durationMs = 10L)
        val l2 = row("/l2.mp4", CaptureTopology.DualShot, "session:s1", VideoSide.LANDSCAPE, durationMs = 20L)
        val out = LibrarySessionAggregator.aggregate(listOf(l1, l2))
        // Same-side duplicates: first kept, duplicate dropped → one pass-through row (not a collapse).
        assertEquals(listOf(l1), out)
    }

    @Test
    fun keptSingle_sameKeyTwiceInInput_emitsOnce() {
        // Helper is self-sufficient (codex): don't rely on upstream stableKey de-dup.
        val p = row("/p.mp4", CaptureTopology.DualShot, "session:s1", VideoSide.PORTRAIT)
        val out = LibrarySessionAggregator.aggregate(listOf(p, p.copy()))
        assertEquals(listOf(p), out)
    }

    @Test
    fun collapse_baseIsLatestDatedRow_noLabelDrift() {
        // dateMillis/dateLabel/title must come from ONE row (codex: sort-vs-display drift).
        val p = row("/p.mp4", CaptureTopology.DualShot, "session:s1", VideoSide.PORTRAIT, dateMillis = 100L)
            .copy(dateLabel = "old", title = "old-title")
        val l = row("/l.mp4", CaptureTopology.DualShot, "session:s1", VideoSide.LANDSCAPE, dateMillis = 200L)
            .copy(dateLabel = "new", title = "new-title")
        val s = LibrarySessionAggregator.aggregate(listOf(p, l)).single()
        assertEquals(200L, s.dateMillis)
        assertEquals("new", s.dateLabel)
        assertEquals("new-title", s.title)
        // Sides order stays PORTRAIT-first regardless of base choice.
        assertEquals(listOf(VideoSide.PORTRAIT, VideoSide.LANDSCAPE), s.sides.map { it.side })
    }

    @Test
    fun twoDifferentSessions_dontMerge() {
        val p1 = row("/p1.mp4", CaptureTopology.DualShot, "session:s1", VideoSide.PORTRAIT)
        val l1 = row("/l1.mp4", CaptureTopology.DualShot, "session:s1", VideoSide.LANDSCAPE)
        val p2 = row("/p2.mp4", CaptureTopology.DualShot, "session:s2", VideoSide.PORTRAIT)
        val l2 = row("/l2.mp4", CaptureTopology.DualShot, "session:s2", VideoSide.LANDSCAPE)
        val out = LibrarySessionAggregator.aggregate(listOf(p1, l1, p2, l2))
        assertEquals(listOf("session:s1", "session:s2"), out.map { it.stableKey })
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.LibrarySessionAggregatorTest"`
Expected: FAIL — unresolved reference `LibrarySessionAggregator`.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.aritr.rova.ui.library

import com.aritr.rova.data.CaptureTopology
import com.aritr.rova.service.dualrecord.VideoSide

/**
 * Collapses per-side DualShot rows into one session row (spec §3.4, ADR-0030 amendment §3).
 *
 * Pure post-map step: PR-B calls this on the mapped row list in HistoryViewModel's combine.
 * duration/clipCount use MAX across sides (the sides record CONCURRENTLY — summing would
 * double the session; same invariant as SessionDurations.forRow's side filter). Size sums
 * (two real files). The collapsed row keys on the canonical sessionKey; the original
 * per-side keys survive inside [LibraryRow.sides] for playback/share/delete resolution.
 */
object LibrarySessionAggregator {

    fun aggregate(rows: List<LibraryRow>): List<LibraryRow> {
        // Same-side duplicates (same file surfaced twice) are dropped per side BEFORE the
        // size test, so a same-side "pair" is a kept single, not a collapse.
        val participatingGroups = rows.filter { it.participates() }
            .groupBy { it.sessionKey!! }
            .mapValues { (_, g) -> g.distinctBy { it.side } }
        val bySession = participatingGroups.filterValues { it.size >= 2 }
        val keptSingles = participatingGroups.filterValues { it.size < 2 }
            .values.flatten().mapTo(HashSet()) { it.stableKey }

        if (participatingGroups.isEmpty()) return rows

        val emitted = HashSet<String>()          // collapsed sessions already output
        val emittedSingles = HashSet<String>()   // kept single-side keys already output
        val result = ArrayList<LibraryRow>(rows.size)
        for (r in rows) {
            when {
                !r.participates() -> result.add(r)
                r.sessionKey!! in bySession ->
                    if (emitted.add(r.sessionKey!!)) result.add(collapse(bySession.getValue(r.sessionKey!!)))
                    // else: later member of an already-collapsed group → skip
                r.stableKey in keptSingles ->
                    // Self-sufficient duplicate drop (codex): a kept single emits at most once
                    // even if the same key appears twice in the input.
                    if (emittedSingles.add(r.stableKey)) result.add(r)
                // else: same-side duplicate of a kept single → skip
            }
        }
        return result
    }

    private fun LibraryRow.participates(): Boolean =
        topology == CaptureTopology.DualShot && sessionKey != null && side != null

    private fun collapse(group: List<LibraryRow>): LibraryRow {
        val ordered = group.sortedBy { if (it.side == VideoSide.PORTRAIT) 0 else 1 }
        // Base = latest-dated member: dateMillis, dateLabel, title, badge all come from ONE
        // row so sort/group and search/display can't drift apart (codex). Sides order stays
        // PORTRAIT-first regardless of which side is the base.
        val base = group.maxByOrNull { it.dateMillis }!!
        return base.copy(
            stableKey = base.sessionKey!!,
            sizeBytes = group.sumOf { it.sizeBytes },
            durationMs = group.maxOf { it.durationMs },
            clipCount = group.maxOf { it.clipCount },
            favorite = group.any { it.favorite },
            orientation = null,
            side = null,
            sides = ordered.map { LibrarySessionSide(it.side!!, it.stableKey, it.durationMs, it.clipCount) },
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.LibrarySessionAggregatorTest"`
Expected: PASS (9 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/LibrarySessionAggregator.kt app/src/test/java/com/aritr/rova/ui/library/LibrarySessionAggregatorTest.kt
git commit -m "feat(library): DualShot session aggregation, unwired (PR-A T5)"
```

---

### Task 6: `LatestRowPolicy`

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/library/LatestRowPolicy.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/library/LatestRowPolicyTest.kt`

**Interfaces:**
- Consumes: `LibraryRow`, `LibrarySort`.
- Produces: `LatestRowPolicy.latestKey(visibleRows: List<LibraryRow>, sort: LibrarySort): String?` — PR-B calls it with the ALREADY filtered+sorted collection (`LibraryQuery.collection` output) and renders the accent variant for the matching key. Null = no accent.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.aritr.rova.ui.library

import com.aritr.rova.data.CaptureTopology
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LatestRowPolicyTest {

    private fun row(key: String) = LibraryRow(
        stableKey = key, title = "t", dateLabel = "d", dateMillis = 1L,
        durationMs = 1L, sizeBytes = 1L, clipCount = 1,
        topology = CaptureTopology.Single, badge = null, favorite = false,
    )

    private val rows = listOf(row("newest"), row("older"))

    @Test
    fun newestSort_firstVisibleRowIsLatest() {
        assertEquals("newest", LatestRowPolicy.latestKey(rows, LibrarySort.NEWEST))
    }

    @Test
    fun nonNewestSorts_noAccent() {
        assertNull(LatestRowPolicy.latestKey(rows, LibrarySort.OLDEST))
        assertNull(LatestRowPolicy.latestKey(rows, LibrarySort.LONGEST))
        assertNull(LatestRowPolicy.latestKey(rows, LibrarySort.LARGEST))
    }

    @Test
    fun emptyList_noAccent() {
        assertNull(LatestRowPolicy.latestKey(emptyList(), LibrarySort.NEWEST))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.LatestRowPolicyTest"`
Expected: FAIL — unresolved reference `LatestRowPolicy`.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.aritr.rova.ui.library

/**
 * Latest-row accent eligibility (spec §3.3). The anchor applies ONLY under NEWEST sort —
 * under OLDEST the newest session is at the bottom, and under size/duration sorts a
 * "latest" accent would contradict the sort the user chose. Filter-awareness comes free:
 * callers pass the already-filtered visible collection (matches old heroFor semantics
 * without a second surface).
 */
object LatestRowPolicy {
    fun latestKey(visibleRows: List<LibraryRow>, sort: LibrarySort): String? =
        if (sort == LibrarySort.NEWEST) visibleRows.firstOrNull()?.stableKey else null
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.LatestRowPolicyTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/LatestRowPolicy.kt app/src/test/java/com/aritr/rova/ui/library/LatestRowPolicyTest.kt
git commit -m "feat(library): latest-row eligibility policy (PR-A T6)"
```

---

### Task 7: Full verification

**Files:** none new.

- [ ] **Step 1: Full JVM suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS, 0 failures, 0 errors, 0 skipped; count ≥ baseline (net +~23 tests).

- [ ] **Step 2: Build with gates**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL — all 48 `check*` gates green (this PR adds no gate and must trip none; `checkLibraryNoManifestWrite` in particular).

- [ ] **Step 3: Confirm zero visual change**

`git diff master --stat` — the only `app/src/main` changes are: `LibraryRow.kt`, `LibraryRowMapper.kt`, `RovaSettings.kt`, `HistoryViewModel.kt` (one adapter line), plus 4 new pure-helper files. No composable touched. (No device smoke needed — Foundation precedent from Slice 1.)

- [ ] **Step 4: Commit any stragglers, report**

Working tree clean except intentional untracked noise. Report PR-A ready for review-gate (code-review + codex), then owner push/PR GO.

---

## PR-B / PR-C (planned after PR-A merges — scope pointers only)

- **PR-B — presentation:** single-list `LibraryScreen` (delete grid branch + `LibraryViewMode` + toggle), session row + latest accent row + DualShot side actions, wire `LibrarySessionAggregator` + `LatestRowPolicy` + density into `HistoryViewModel` combine (5-arity: `_cardPreview`→`_density`; `LatestRowPolicy` gets the FULL filtered+sorted collection — call `LibraryQuery.collection` with `heroKey = null` once the hero dies, per codex), delete hero/`heroFor`/`LibraryAutoplayVideo`/`AutoplayPolicy`/`libraryCardPreview` (+ its Settings row: `SettingsViewModel.kt:37,122`, `SettingsScreen.kt:132,365-366`) + retire `libraryViewMode`, focus-restore hero-key simplification, batch-op side resolution via `LibraryRow.sides`. Device-verify.
- **PR-C — chrome:** sticky day headers on `LibraryDateLabels` (group identity switches from label string to day-epoch key), midnight ON_RESUME refresh, scrubber bubble fix + 16dp thumb, density toggle in top bar, en+es strings, TalkBack pass. Device-verify.

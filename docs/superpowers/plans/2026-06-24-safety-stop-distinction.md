# Safety-stop distinction — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make thermal / low-storage / scheduled auto-stops read differently from a manual user stop in the Library status badge and the recovery card — a presentation-only change over data that already exists.

**Architecture:** One shared pure helper (`StopCategoryClassifier.categorize(terminated, stopReason)`) is the taxonomy SSOT. Library maps the category → `LibraryBadge?` (layering export-`FAILED` on top, a Library-local rule) and Recovery maps it → an expanded `RecoveryCardKind` driving per-reason copy and accent. Terminal classification, the recovery scanner, and the manifest schema are untouched.

**Tech Stack:** Kotlin, Jetpack Compose, JUnit JVM unit tests (`isReturnDefaultValues = true`), Gradle static-check gates.

## Global Constraints

- Verify builds with `./gradlew :app:assembleDebug` (fires the 46 `check*` gates on `preBuild`). Do **not** use `:app:lintDebug` to gauge success — a pre-existing `VaultAndroidOps:267` NewApi lint error is unrelated RED.
- Full `./gradlew :app:testDebugUnitTest` must be GREEN at every commit.
- NEVER edit a `check*` task to make it pass. Fix the source.
- New user-visible strings: declare in BOTH `app/src/main/res/values/strings.xml` (en) and `app/src/main/res/values-es/strings.xml` (es). Forbidden words in string VALUES (`checkUserCopyVocabulary`, en+es): `loop(s)`, `repeat(s)`, `segment(s)`, `ciclos`, `segmentos`, `repeticion(es)`, `bucles`. Use `clip` / `session`.
- Do NOT dilute a locked `RovaSemantics.*` status color with `.copy(...)` (`checkStatusColorLocked`). `IconStatus.Interrupted` is reused as-is.
- Recovery scanner (`service/recovery/RecoveryScanner.kt`) and `SessionManifest` schema must NOT change (`checkUserStoppedBeforeMerge`, `checkRecoveryNoDeletion`).
- Pure helpers + their JVM tests land in the same commit as the code they cover.
- Build WARM (no cache wipe). Branch: `feat/safety-stop-distinction` (already created off master `29be01d`).

---

### Task 1: ADR presentation clauses (amend-first)

**Files:**
- Modify: `docs/adr/0016-*.md` (thermal autostop), `docs/adr/0027-*.md` (daily window), `docs/adr/0030-*.md` (Library badges)

No code. The house rule is amend-the-ADR-first. Add a short "Presentation" clause to each, cross-referencing the others and the design doc.

- [ ] **Step 1: Locate the three ADR files**

Run: `ls docs/adr | grep -E "0016|0027|0030"`
Expected: three filenames.

- [ ] **Step 2: Add presentation clauses**

In ADR-0016 (thermal), append a clause:
> **Presentation (2026-06-24).** A `StopReason.THERMAL` stop is surfaced to the user as an "auto/safety stop", distinct from a manual user stop: a `LibraryBadge.AUTO_STOPPED` badge in the Library and a `RecoveryCardKind.SAFETY_STOPPED` recovery card with "cool down" copy. Terminal classification stays `Terminated.USER_STOPPED`; this is display-only. See ADR-0027, ADR-0030, and `docs/superpowers/specs/2026-06-24-safety-stop-distinction-design.md`.

In ADR-0027 (daily window), append:
> **Presentation (2026-06-24).** A `StopReason.SCHEDULE_WINDOW` stop reads as a planned success ("Ended on schedule"), NOT an alarm: no exceptional Library badge, and a neutral-accent `RecoveryCardKind.SCHEDULED_END` recovery card. Display-only; terminal stays `USER_STOPPED`. See ADR-0016, ADR-0030, and the design doc above.

In ADR-0030 (Library badges), append:
> **Amendment (2026-06-24).** A third exceptional badge `LibraryBadge.AUTO_STOPPED` (safety stops: `THERMAL` / `LOW_STORAGE`) joins `RECOVERED` / `INTERRUPTED`. It reuses the locked `IconStatus.Interrupted` color (no new locked color) with a reason-aware glyph. Scheduled-window stops remain badge-less (a planned end is not "exceptional"). See the design doc above.

- [ ] **Step 3: Commit**

```bash
git add docs/adr/0016-*.md docs/adr/0027-*.md docs/adr/0030-*.md
git commit -m "docs(adr): presentation clauses for safety-stop distinction (0016/0027/0030)"
```

---

### Task 2: Shared pure helper — `StopCategory` + `StopCategoryClassifier`

**Files:**
- Create: `app/src/main/java/com/aritr/rova/data/StopCategory.kt`
- Test: `app/src/test/java/com/aritr/rova/data/StopCategoryClassifierTest.kt`

**Interfaces:**
- Produces: `enum class StopCategory { COMPLETED, USER_STOPPED, SAFETY_STOPPED, SCHEDULED_END, ERROR_STOPPED, INTERRUPTED, RECOVERED }` and `object StopCategoryClassifier { fun categorize(terminated: Terminated?, stopReason: StopReason): StopCategory }`. `INTERRUPTED` = killed only (no exportState input).

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/aritr/rova/data/StopCategoryClassifierTest.kt`:

```kotlin
package com.aritr.rova.data

import org.junit.Assert.assertEquals
import org.junit.Test

class StopCategoryClassifierTest {
    private fun cat(t: Terminated?, r: StopReason) = StopCategoryClassifier.categorize(t, r)

    @Test fun `multi-segment kept is Recovered`() =
        assertEquals(StopCategory.RECOVERED, cat(Terminated.MULTI_SEGMENT_KEPT, StopReason.NONE))

    @Test fun `killed by system is Interrupted`() =
        assertEquals(StopCategory.INTERRUPTED, cat(Terminated.KILLED_BY_SYSTEM, StopReason.NONE))

    @Test fun `killed force stop is Interrupted`() =
        assertEquals(StopCategory.INTERRUPTED, cat(Terminated.KILLED_FORCE_STOP, StopReason.NONE))

    @Test fun `user stop thermal is SafetyStopped`() =
        assertEquals(StopCategory.SAFETY_STOPPED, cat(Terminated.USER_STOPPED, StopReason.THERMAL))

    @Test fun `user stop low storage is SafetyStopped`() =
        assertEquals(StopCategory.SAFETY_STOPPED, cat(Terminated.USER_STOPPED, StopReason.LOW_STORAGE))

    @Test fun `user stop schedule window is ScheduledEnd`() =
        assertEquals(StopCategory.SCHEDULED_END, cat(Terminated.USER_STOPPED, StopReason.SCHEDULE_WINDOW))

    @Test fun `user stop permission revoked is ErrorStopped`() =
        assertEquals(StopCategory.ERROR_STOPPED, cat(Terminated.USER_STOPPED, StopReason.PERMISSION_REVOKED))

    @Test fun `user stop init failed is ErrorStopped`() =
        assertEquals(StopCategory.ERROR_STOPPED, cat(Terminated.USER_STOPPED, StopReason.INIT_FAILED))

    @Test fun `user stop manual is UserStopped`() {
        assertEquals(StopCategory.USER_STOPPED, cat(Terminated.USER_STOPPED, StopReason.USER))
        assertEquals(StopCategory.USER_STOPPED, cat(Terminated.USER_STOPPED, StopReason.NONE))
    }

    @Test fun `completed is Completed`() =
        assertEquals(StopCategory.COMPLETED, cat(Terminated.COMPLETED, StopReason.NONE))

    @Test fun `null terminated is Completed`() =
        assertEquals(StopCategory.COMPLETED, cat(null, StopReason.NONE))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.data.StopCategoryClassifierTest"`
Expected: FAIL — `StopCategory` / `StopCategoryClassifier` unresolved.

- [ ] **Step 3: Write the helper**

Create `app/src/main/java/com/aritr/rova/data/StopCategory.kt`:

```kotlin
package com.aritr.rova.data

/**
 * Presentation taxonomy for how a recording ended (ADR-0016/0027/0030 presentation clauses,
 * design doc 2026-06-24). Pure / Android-free so it is JVM-unit-testable under
 * `isReturnDefaultValues = true`. The single reason→category seam shared by the Library badge
 * ([com.aritr.rova.ui.library.StatusBadgePolicy]) and the recovery card
 * ([com.aritr.rova.ui.recovery.RecoveryUiStateMapper]).
 *
 * Differentiates at presentation ONLY — terminal classification ([Terminated]) is unchanged.
 * `INTERRUPTED` here means a system/force KILL; export-`FAILED` is layered on by the Library
 * consumer, not folded in here (a clean user-stop whose export failed must stay `USER_STOPPED`,
 * not be mislabeled a kill — see design §3).
 */
enum class StopCategory {
    COMPLETED,
    USER_STOPPED,
    SAFETY_STOPPED,
    SCHEDULED_END,
    ERROR_STOPPED,
    INTERRUPTED,
    RECOVERED,
}

object StopCategoryClassifier {
    /** Pure stop taxonomy. `exportState` is intentionally NOT an input (design §3). */
    fun categorize(terminated: Terminated?, stopReason: StopReason): StopCategory = when {
        terminated == Terminated.MULTI_SEGMENT_KEPT -> StopCategory.RECOVERED
        terminated == Terminated.KILLED_BY_SYSTEM ||
            terminated == Terminated.KILLED_FORCE_STOP -> StopCategory.INTERRUPTED
        terminated == Terminated.USER_STOPPED &&
            (stopReason == StopReason.THERMAL ||
             stopReason == StopReason.LOW_STORAGE) -> StopCategory.SAFETY_STOPPED
        terminated == Terminated.USER_STOPPED &&
            stopReason == StopReason.SCHEDULE_WINDOW -> StopCategory.SCHEDULED_END
        terminated == Terminated.USER_STOPPED &&
            (stopReason == StopReason.PERMISSION_REVOKED ||
             stopReason == StopReason.INIT_FAILED) -> StopCategory.ERROR_STOPPED
        terminated == Terminated.COMPLETED -> StopCategory.COMPLETED
        terminated == Terminated.USER_STOPPED -> StopCategory.USER_STOPPED // USER / NONE
        else -> StopCategory.COMPLETED // terminated == null
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.data.StopCategoryClassifierTest"`
Expected: PASS (12 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/data/StopCategory.kt app/src/test/java/com/aritr/rova/data/StopCategoryClassifierTest.kt
git commit -m "feat(data): StopCategory taxonomy + classifier (safety-stop distinction SSOT)"
```

---

### Task 3: Library badge core — `AUTO_STOPPED` value + `badgeFor` + plumbing

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/library/LibraryBadge.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/library/StatusBadgePolicy.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/library/components/LibraryIconSpec.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/library/components/LibraryBadges.kt` (`statusBadgeLabel`)
- Modify: `app/src/main/java/com/aritr/rova/ui/library/TileSemantics.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/library/LibraryRowMapper.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/library/LibraryScreen.kt:635`
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/HistoryViewModel.kt` (`RowManifestFacts`, `toLibraryRow`, facts builder ~line 808)
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-es/strings.xml`
- Test: `app/src/test/java/com/aritr/rova/ui/library/StatusBadgePolicyTest.kt`, `app/src/test/java/com/aritr/rova/ui/library/LibraryRowMapperTest.kt`

**Interfaces:**
- Consumes: `StopCategoryClassifier.categorize` (Task 2).
- Produces: `enum class LibraryBadge { RECOVERED, INTERRUPTED, AUTO_STOPPED }`; `StatusBadgePolicy.badgeFor(terminated: Terminated?, stopReason: StopReason, exportState: ExportState): LibraryBadge?`; `LibraryRowMapper.Input` gains `stopReason: StopReason`; `statusBadgeLabel(badge, recovered, interrupted, autoStopped)`. Glyph for `AUTO_STOPPED` is a FIXED thermal icon here; Task 4 makes it reason-aware.

- [ ] **Step 1: Write the failing test (badge policy)**

Edit `StatusBadgePolicyTest.kt` — update existing calls to the new signature (add `StopReason`) and add new cases:

```kotlin
package com.aritr.rova.ui.library

import com.aritr.rova.data.ExportState
import com.aritr.rova.data.StopReason
import com.aritr.rova.data.Terminated
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatusBadgePolicyTest {

    @Test fun `completed clean session has no badge`() {
        assertNull(StatusBadgePolicy.badgeFor(Terminated.COMPLETED, StopReason.NONE, ExportState.FINALIZED))
    }

    @Test fun `clean user stop has no badge`() {
        assertNull(StatusBadgePolicy.badgeFor(Terminated.USER_STOPPED, StopReason.USER, ExportState.FINALIZED))
    }

    @Test fun `multi-segment kept is Recovered`() {
        assertEquals(LibraryBadge.RECOVERED, StatusBadgePolicy.badgeFor(Terminated.MULTI_SEGMENT_KEPT, StopReason.NONE, ExportState.FINALIZED))
    }

    @Test fun `system kill is Interrupted`() {
        assertEquals(LibraryBadge.INTERRUPTED, StatusBadgePolicy.badgeFor(Terminated.KILLED_BY_SYSTEM, StopReason.NONE, ExportState.FINALIZED))
        assertEquals(LibraryBadge.INTERRUPTED, StatusBadgePolicy.badgeFor(Terminated.KILLED_FORCE_STOP, StopReason.NONE, ExportState.FINALIZED))
    }

    @Test fun `failed export is Interrupted even if terminal is clean`() {
        assertEquals(LibraryBadge.INTERRUPTED, StatusBadgePolicy.badgeFor(Terminated.COMPLETED, StopReason.NONE, ExportState.FAILED))
    }

    @Test fun `null terminated with finalized export has no badge`() {
        assertNull(StatusBadgePolicy.badgeFor(null, StopReason.NONE, ExportState.FINALIZED))
    }

    @Test fun `thermal auto-stop is AutoStopped`() {
        assertEquals(LibraryBadge.AUTO_STOPPED, StatusBadgePolicy.badgeFor(Terminated.USER_STOPPED, StopReason.THERMAL, ExportState.FINALIZED))
    }

    @Test fun `low storage auto-stop is AutoStopped`() {
        assertEquals(LibraryBadge.AUTO_STOPPED, StatusBadgePolicy.badgeFor(Terminated.USER_STOPPED, StopReason.LOW_STORAGE, ExportState.FINALIZED))
    }

    @Test fun `scheduled end has no badge`() {
        assertNull(StatusBadgePolicy.badgeFor(Terminated.USER_STOPPED, StopReason.SCHEDULE_WINDOW, ExportState.FINALIZED))
    }

    @Test fun `error stop is Interrupted`() {
        assertEquals(LibraryBadge.INTERRUPTED, StatusBadgePolicy.badgeFor(Terminated.USER_STOPPED, StopReason.PERMISSION_REVOKED, ExportState.FINALIZED))
        assertEquals(LibraryBadge.INTERRUPTED, StatusBadgePolicy.badgeFor(Terminated.USER_STOPPED, StopReason.INIT_FAILED, ExportState.FINALIZED))
    }

    @Test fun `safety stop keeps AutoStopped even if export failed`() {
        assertEquals(LibraryBadge.AUTO_STOPPED, StatusBadgePolicy.badgeFor(Terminated.USER_STOPPED, StopReason.THERMAL, ExportState.FAILED))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.StatusBadgePolicyTest"`
Expected: FAIL — `AUTO_STOPPED` unresolved / `badgeFor` arity mismatch.

- [ ] **Step 3: Add the enum value**

Edit `LibraryBadge.kt`:

```kotlin
package com.aritr.rova.ui.library

/** ADR-0030 — exceptional-only card badges. No "Complete" badge exists by design. */
enum class LibraryBadge { RECOVERED, INTERRUPTED, AUTO_STOPPED }
```

- [ ] **Step 4: Rewrite `StatusBadgePolicy.badgeFor`**

Edit `StatusBadgePolicy.kt`:

```kotlin
package com.aritr.rova.ui.library

import com.aritr.rova.data.ExportState
import com.aritr.rova.data.StopCategory
import com.aritr.rova.data.StopCategoryClassifier
import com.aritr.rova.data.StopReason
import com.aritr.rova.data.Terminated

/**
 * ADR-0030 (+ 2026-06-24 amendment) — maps a session's stop taxonomy to an exceptional badge,
 * or null for the clean common case. Delegates the taxonomy to [StopCategoryClassifier] and
 * layers export-`FAILED` on top (Library-local: a failed export on an otherwise badge-less row
 * is Interrupted, but a specific safety/scheduled cause keeps its own badge). Pure; enum-only.
 */
object StatusBadgePolicy {
    fun badgeFor(terminated: Terminated?, stopReason: StopReason, exportState: ExportState): LibraryBadge? =
        when (StopCategoryClassifier.categorize(terminated, stopReason)) {
            StopCategory.RECOVERED -> LibraryBadge.RECOVERED
            StopCategory.SAFETY_STOPPED -> LibraryBadge.AUTO_STOPPED
            StopCategory.INTERRUPTED, StopCategory.ERROR_STOPPED -> LibraryBadge.INTERRUPTED
            StopCategory.SCHEDULED_END, StopCategory.USER_STOPPED, StopCategory.COMPLETED ->
                if (exportState == ExportState.FAILED) LibraryBadge.INTERRUPTED else null
        }
}
```

- [ ] **Step 5: Fix the compile-forced exhaustive `when` sites**

Edit `LibraryIconSpec.kt` `badgeGlyph` — add the AUTO_STOPPED case (FIXED thermal glyph for now; Task 4 makes it reason-aware):

```kotlin
    fun badgeGlyph(badge: LibraryBadge): RovaIcon = when (badge) {
        LibraryBadge.RECOVERED -> RovaIcons.Recovered
        LibraryBadge.INTERRUPTED -> RovaIcons.Interrupted
        // Safety auto-stop: thermal glyph with the locked Interrupted color. Task 4 refines
        // the glyph to be reason-aware (thermometer vs storage). No `.copy` — checkStatusColorLocked clean.
        LibraryBadge.AUTO_STOPPED -> RovaIcon(RovaGlyphs.Thermal.outline, status = IconStatus.Interrupted)
    }
```

Add imports to `LibraryIconSpec.kt`: `import com.aritr.rova.ui.theme.RovaGlyphs`.

Edit `LibraryBadges.kt` `statusBadgeLabel` — add the `autoStopped` param + case:

```kotlin
@Composable
fun statusBadgeLabel(badge: LibraryBadge?, recovered: String, interrupted: String, autoStopped: String): String? = when (badge) {
    LibraryBadge.RECOVERED -> recovered
    LibraryBadge.INTERRUPTED -> interrupted
    LibraryBadge.AUTO_STOPPED -> autoStopped
    null -> null
}
```

Edit `TileSemantics.kt:25` `when (row.badge)` — add the AUTO_STOPPED branch. (Open the file; mirror the existing RECOVERED/INTERRUPTED branches, mapping AUTO_STOPPED to the new `R.string.library_badge_auto_stopped` in the same style the existing branches resolve their label.)

- [ ] **Step 6: Add the badge string (en + es)**

In `app/src/main/res/values/strings.xml`, after `library_badge_interrupted` (~line 821):
```xml
    <string name="library_badge_auto_stopped">Auto-stopped</string>
```
In `app/src/main/res/values-es/strings.xml`, after `library_badge_interrupted` (~line 819):
```xml
    <string name="library_badge_auto_stopped">Detención automática</string>
```

- [ ] **Step 7: Thread `stopReason` through the row mapper + ViewModel**

Edit `LibraryRowMapper.kt`: add `val stopReason: StopReason` to `Input` (import `com.aritr.rova.data.StopReason`), and change the `badge =` line:

```kotlin
            badge = StatusBadgePolicy.badgeFor(input.terminated, input.stopReason, input.exportState),
```

Edit `HistoryViewModel.kt` `RowManifestFacts` (line 225) — add field:
```kotlin
        val stopReason: com.aritr.rova.data.StopReason,
```
In the facts builder (~line 808 `return RowManifestFacts(`), add:
```kotlin
            stopReason = m.stopReason,
```
In `toLibraryRow` `LibraryRowMapper.Input(...)` (~line 348), add:
```kotlin
                stopReason = facts?.stopReason ?: com.aritr.rova.data.StopReason.NONE,
```

- [ ] **Step 8: Update the `statusBadgeLabel` call site**

Edit `LibraryScreen.kt:635`. There are `recoveredLabel` / `interruptedLabel` locals resolved via `stringResource`; add an `autoStoppedLabel` local next to them and pass it:
```kotlin
                                                statusLabel = statusBadgeLabel(row.badge, recoveredLabel, interruptedLabel, autoStoppedLabel),
```
Find where `interruptedLabel` is declared (search `interruptedLabel =`) and add directly below:
```kotlin
        val autoStoppedLabel = stringResource(R.string.library_badge_auto_stopped)
```

- [ ] **Step 9: Update `LibraryRowMapperTest` input() builder**

Edit `app/src/test/java/com/aritr/rova/ui/library/LibraryRowMapperTest.kt` — the `input(...)` helper (line 30) builds `LibraryRowMapper.Input`. Add a `stopReason: StopReason = StopReason.NONE` parameter and pass it through; add `import com.aritr.rova.data.StopReason`. Add a case:
```kotlin
    @Test fun `thermal auto-stop row is AutoStopped`() {
        assertEquals(
            LibraryBadge.AUTO_STOPPED,
            LibraryRowMapper.map(input(terminated = Terminated.USER_STOPPED, stopReason = StopReason.THERMAL), locale, tz).badge,
        )
    }
```

- [ ] **Step 10: Run the full test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (all green, incl. updated `StatusBadgePolicyTest`, `LibraryRowMapperTest`).

- [ ] **Step 11: Build to fire gates**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL (46 gates pass; `checkStatusColorLocked`, `checkUserCopyVocabulary`, `checkNoHardcodedUiStrings` clean).

- [ ] **Step 12: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library app/src/main/java/com/aritr/rova/ui/screens/HistoryViewModel.kt app/src/main/res/values/strings.xml app/src/main/res/values-es/strings.xml app/src/test/java/com/aritr/rova/ui/library
git commit -m "feat(library): AUTO_STOPPED badge for safety stops (thermal/low-storage)"
```

---

### Task 4: Reason-aware Library badge glyph (thermometer vs storage)

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/library/LibraryRow.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/library/LibraryRowMapper.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/library/components/LibraryIconSpec.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/library/components/LibraryBadges.kt` (`StatusBadgePill`)
- Modify: `app/src/main/java/com/aritr/rova/ui/library/components/LibraryGridCard.kt:137`
- Modify: `app/src/main/java/com/aritr/rova/ui/library/components/LibraryListRow.kt:133`
- Test: `app/src/test/java/com/aritr/rova/ui/library/LibraryIconSpecTest.kt`

**Interfaces:**
- Consumes: `LibraryBadge.AUTO_STOPPED` (Task 3).
- Produces: `LibraryRow` gains `badgeStopReason: StopReason? = null`; `LibraryIconSpec.badgeGlyph(badge: LibraryBadge, stopReason: StopReason? = null): RovaIcon`; `StatusBadgePill(badge, text, stopReason, modifier)`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/aritr/rova/ui/library/LibraryIconSpecTest.kt`:

```kotlin
package com.aritr.rova.ui.library

import com.aritr.rova.data.StopReason
import com.aritr.rova.ui.library.components.LibraryIconSpec
import com.aritr.rova.ui.theme.IconStatus
import com.aritr.rova.ui.theme.RovaGlyphs
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryIconSpecTest {
    @Test fun `auto-stopped thermal uses thermal glyph with interrupted lock`() {
        val icon = LibraryIconSpec.badgeGlyph(LibraryBadge.AUTO_STOPPED, StopReason.THERMAL)
        assertEquals(RovaGlyphs.Thermal.outline, icon.glyph)
        assertEquals(IconStatus.Interrupted, icon.status)
    }

    @Test fun `auto-stopped low storage uses storage glyph`() {
        val icon = LibraryIconSpec.badgeGlyph(LibraryBadge.AUTO_STOPPED, StopReason.LOW_STORAGE)
        assertEquals(RovaGlyphs.Storage.outline, icon.glyph)
        assertEquals(IconStatus.Interrupted, icon.status)
    }

    @Test fun `auto-stopped unknown reason falls back to thermal`() {
        val icon = LibraryIconSpec.badgeGlyph(LibraryBadge.AUTO_STOPPED, null)
        assertEquals(RovaGlyphs.Thermal.outline, icon.glyph)
    }

    @Test fun `recovered and interrupted ignore reason`() {
        assertEquals(IconStatus.Recovered, LibraryIconSpec.badgeGlyph(LibraryBadge.RECOVERED, null).status)
        assertEquals(IconStatus.Interrupted, LibraryIconSpec.badgeGlyph(LibraryBadge.INTERRUPTED, null).status)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.LibraryIconSpecTest"`
Expected: FAIL — `badgeGlyph` arity mismatch.

- [ ] **Step 3: Make `badgeGlyph` reason-aware**

Edit `LibraryIconSpec.kt`:

```kotlin
    fun badgeGlyph(badge: LibraryBadge, stopReason: StopReason? = null): RovaIcon = when (badge) {
        LibraryBadge.RECOVERED -> RovaIcons.Recovered
        LibraryBadge.INTERRUPTED -> RovaIcons.Interrupted
        // Reason-aware safety glyph: thermometer for THERMAL, storage for LOW_STORAGE, both with
        // the locked Interrupted color. No `.copy` — checkStatusColorLocked clean.
        LibraryBadge.AUTO_STOPPED -> when (stopReason) {
            StopReason.LOW_STORAGE -> RovaIcon(RovaGlyphs.Storage.outline, status = IconStatus.Interrupted)
            else -> RovaIcon(RovaGlyphs.Thermal.outline, status = IconStatus.Interrupted)
        }
    }
```

Add import: `import com.aritr.rova.data.StopReason`.

- [ ] **Step 4: Thread `badgeStopReason` onto `LibraryRow` + mapper**

Edit `LibraryRow.kt` — add to the `LibraryRow` data class (import `com.aritr.rova.data.StopReason`):
```kotlin
    /** When [badge] == AUTO_STOPPED, the gate reason (THERMAL/LOW_STORAGE) that picks the badge glyph. Else null. */
    val badgeStopReason: StopReason? = null,
```

Edit `LibraryRowMapper.kt` `map(...)` — set it after computing `badge`:
```kotlin
        val badge = StatusBadgePolicy.badgeFor(input.terminated, input.stopReason, input.exportState)
        return LibraryRow(
            ...
            badge = badge,
            badgeStopReason = if (badge == LibraryBadge.AUTO_STOPPED) input.stopReason else null,
            ...
        )
```
(Refactor the existing inline `badge = StatusBadgePolicy.badgeFor(...)` to the `val badge` form so it can be reused for `badgeStopReason`.)

- [ ] **Step 5: Pass the reason into `StatusBadgePill`**

Edit `LibraryBadges.kt` `StatusBadgePill`:
```kotlin
@Composable
fun StatusBadgePill(badge: LibraryBadge, text: String, stopReason: com.aritr.rova.data.StopReason? = null, modifier: Modifier = Modifier) {
    val icon = LibraryIconSpec.badgeGlyph(badge, stopReason)
    ...
}
```

Edit `LibraryGridCard.kt:137`:
```kotlin
                if (statusLabel != null && badge != null) StatusBadgePill(badge, statusLabel, row.badgeStopReason)
```

Edit `LibraryListRow.kt:133` — the `row.badge?.let { badge -> ... StatusBadgePill(...) }` block: pass `row.badgeStopReason` as the third arg to its `StatusBadgePill(...)` call.

- [ ] **Step 6: Run tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.*"`
Expected: PASS (incl. `LibraryIconSpecTest`).

- [ ] **Step 7: Build to fire gates**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library app/src/test/java/com/aritr/rova/ui/library/LibraryIconSpecTest.kt
git commit -m "feat(library): reason-aware AUTO_STOPPED badge glyph (thermal vs storage)"
```

---

### Task 5: Recovery — expand `RecoveryCardKind` + render whens

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/recovery/RecoveryUiState.kt` (`RecoveryCardKind`)
- Modify: `app/src/main/java/com/aritr/rova/ui/recovery/RecoveryCard.kt` (`severityColorFor`, `tagLabelResFor`)
- Modify: `app/src/main/java/com/aritr/rova/ui/recovery/RecoveryIconSpec.kt` (`statusGlyphFor`)
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-es/strings.xml`
- Test: `app/src/test/java/com/aritr/rova/ui/recovery/RecoveryIconSpecTest.kt` (if it exists — else add the cases to the nearest recovery test)

**Interfaces:**
- Produces: `enum class RecoveryCardKind { USER_STOPPED, SAFETY_STOPPED, SCHEDULED_END, ERROR_STOPPED, KILLED_BY_SYSTEM, KILLED_FORCE_STOP }`. `severityColorFor`, `tagLabelResFor`, `statusGlyphFor` handle all six. Mapper still emits only the old kinds until Task 6 (so this task compiles and existing tests stay green).

- [ ] **Step 1: Add the recovery tag/accent strings (en + es)**

`values/strings.xml`, after `recovery_tag_user_stopped` (~line 717):
```xml
    <string name="recovery_tag_auto_stopped">Auto-stopped</string>
    <string name="recovery_tag_scheduled">Scheduled</string>
    <string name="recovery_tag_interrupted">Interrupted</string>
```
`values-es/strings.xml`, after `recovery_tag_user_stopped` (~line 715):
```xml
    <string name="recovery_tag_auto_stopped">Detención automática</string>
    <string name="recovery_tag_scheduled">Programada</string>
    <string name="recovery_tag_interrupted">Interrumpida</string>
```

- [ ] **Step 2: Expand the enum**

Edit `RecoveryUiState.kt` `RecoveryCardKind`:
```kotlin
enum class RecoveryCardKind {
    USER_STOPPED,
    SAFETY_STOPPED,
    SCHEDULED_END,
    ERROR_STOPPED,
    KILLED_BY_SYSTEM,
    KILLED_FORCE_STOP,
}
```

- [ ] **Step 3: Update `severityColorFor` + `tagLabelResFor` in `RecoveryCard.kt`**

```kotlin
private fun severityColorFor(kind: RecoveryCardKind): Color = when (kind) {
    RecoveryCardKind.KILLED_BY_SYSTEM -> RovaWarnings.hard
    RecoveryCardKind.SCHEDULED_END -> RovaWarnings.advisory   // neutral/informational, NOT an alarm
    RecoveryCardKind.KILLED_FORCE_STOP,
    RecoveryCardKind.SAFETY_STOPPED,
    RecoveryCardKind.ERROR_STOPPED,
    RecoveryCardKind.USER_STOPPED -> RovaWarnings.soft
}

@StringRes
private fun tagLabelResFor(kind: RecoveryCardKind): Int = when (kind) {
    RecoveryCardKind.KILLED_BY_SYSTEM -> R.string.recovery_tag_killed_by_system
    RecoveryCardKind.KILLED_FORCE_STOP -> R.string.recovery_tag_force_stopped
    RecoveryCardKind.USER_STOPPED -> R.string.recovery_tag_user_stopped
    RecoveryCardKind.SAFETY_STOPPED -> R.string.recovery_tag_auto_stopped
    RecoveryCardKind.SCHEDULED_END -> R.string.recovery_tag_scheduled
    RecoveryCardKind.ERROR_STOPPED -> R.string.recovery_tag_interrupted
}
```
`isHardSeverity` (`state.kind == RecoveryCardKind.KILLED_BY_SYSTEM`) is unchanged — only the system-kill pulses.

- [ ] **Step 4: Update `RecoveryIconSpec.statusGlyphFor`**

Edit `RecoveryIconSpec.kt` — the new non-killed kinds keep the generic emblem (return null), matching the current USER_STOPPED behavior; the copy + tag + accent carry the distinction:
```kotlin
    fun statusGlyphFor(kind: RecoveryCardKind): RovaIcon? = when (kind) {
        RecoveryCardKind.KILLED_BY_SYSTEM -> RovaIcons.Interrupted
        RecoveryCardKind.KILLED_FORCE_STOP -> RovaIcons.Interrupted
        RecoveryCardKind.USER_STOPPED -> null
        RecoveryCardKind.SAFETY_STOPPED -> null
        RecoveryCardKind.SCHEDULED_END -> null
        RecoveryCardKind.ERROR_STOPPED -> null
    }
```
Update its KDoc to cover the new kinds (one line each).

- [ ] **Step 5: Build to confirm it compiles (mapper still emits old kinds only)**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL — all exhaustive `when`s satisfied; mapper unchanged so no behavior change yet.

- [ ] **Step 6: Run tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.recovery.*"`
Expected: PASS (existing recovery tests green; new kinds present but not yet produced).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/recovery/RecoveryUiState.kt app/src/main/java/com/aritr/rova/ui/recovery/RecoveryCard.kt app/src/main/java/com/aritr/rova/ui/recovery/RecoveryIconSpec.kt app/src/main/res/values/strings.xml app/src/main/res/values-es/strings.xml
git commit -m "feat(recovery): expand RecoveryCardKind with safety/scheduled/error kinds + render whens"
```

---

### Task 6: Recovery — mapper derivation + per-reason copy

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/recovery/RecoveryUiState.kt` (`RecoveryUiStateMapper.toCard`, `titleResFor`/`bodyResFor`)
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-es/strings.xml`
- Test: `app/src/test/java/com/aritr/rova/ui/recovery/RecoveryUiStateMapperTest.kt`

**Interfaces:**
- Consumes: `StopCategoryClassifier.categorize` (Task 2), expanded `RecoveryCardKind` (Task 5), `manifest.stopReason`.
- Produces: `RecoveryUiStateMapper` derives kind via `categorize` and selects per-reason title/body.

- [ ] **Step 1: Add the per-reason title/body strings (en + es)**

`values/strings.xml`, after `recovery_body_force_stopped` (~line 695):
```xml
    <string name="recovery_title_safety_thermal">Paused to cool down</string>
    <string name="recovery_body_safety_thermal">Your device got too warm, so recording stopped before the clips could merge. The recovered clips stay on your device until you choose Discard recording. This action is permanent.</string>
    <string name="recovery_title_safety_storage">Stopped — storage was full</string>
    <string name="recovery_body_safety_storage">Your device ran out of storage, so recording stopped before the clips could merge. The recovered clips stay on your device until you choose Discard recording. This action is permanent.</string>
    <string name="recovery_title_scheduled">Ended on schedule</string>
    <string name="recovery_body_scheduled">This recording finished at the end of its scheduled window. The clips stay on your device until you choose Discard recording. This action is permanent.</string>
    <string name="recovery_title_error">Recording was interrupted</string>
    <string name="recovery_body_error">Recording stopped unexpectedly before the clips could merge. The recovered clips stay on your device until you choose Discard recording. This action is permanent.</string>
```
`values-es/strings.xml`, after `recovery_body_force_stopped` (~line 693):
```xml
    <string name="recovery_title_safety_thermal">Pausada para enfriar</string>
    <string name="recovery_body_safety_thermal">Tu dispositivo se calentó demasiado, así que la grabación se detuvo antes de que los clips pudieran combinarse. Los clips recuperados permanecen en tu dispositivo hasta que elijas Descartar grabación. Esta acción es permanente.</string>
    <string name="recovery_title_safety_storage">Detenida: almacenamiento lleno</string>
    <string name="recovery_body_safety_storage">Tu dispositivo se quedó sin almacenamiento, así que la grabación se detuvo antes de que los clips pudieran combinarse. Los clips recuperados permanecen en tu dispositivo hasta que elijas Descartar grabación. Esta acción es permanente.</string>
    <string name="recovery_title_scheduled">Finalizó según lo programado</string>
    <string name="recovery_body_scheduled">Esta grabación terminó al final de su ventana programada. Los clips permanecen en tu dispositivo hasta que elijas Descartar grabación. Esta acción es permanente.</string>
    <string name="recovery_title_error">La grabación se interrumpió</string>
    <string name="recovery_body_error">La grabación se detuvo inesperadamente antes de que los clips pudieran combinarse. Los clips recuperados permanecen en tu dispositivo hasta que elijas Descartar grabación. Esta acción es permanente.</string>
```

- [ ] **Step 2: Write the failing mapper test**

Edit `app/src/test/java/com/aritr/rova/ui/recovery/RecoveryUiStateMapperTest.kt`. It already builds `RecoverySessionView` (manifest + classification) — find the existing builder/helper it uses for an OFFER_DISCARD `USER_STOPPED` view, and add reason-driven cases. Representative (adapt names to the file's existing helpers):

```kotlin
    @Test fun `thermal user-stop maps to SafetyStopped with cool-down copy`() {
        val view = offerDiscardView(Terminated.USER_STOPPED, StopReason.THERMAL)
        val card = RecoveryUiStateMapper.map(listOf(view)).cards.single()
        assertEquals(RecoveryCardKind.SAFETY_STOPPED, card.kind)
        assertEquals(R.string.recovery_title_safety_thermal, card.titleRes)
        assertEquals(R.string.recovery_body_safety_thermal, card.bodyRes)
    }

    @Test fun `low-storage user-stop uses storage copy`() {
        val card = RecoveryUiStateMapper.map(listOf(offerDiscardView(Terminated.USER_STOPPED, StopReason.LOW_STORAGE))).cards.single()
        assertEquals(RecoveryCardKind.SAFETY_STOPPED, card.kind)
        assertEquals(R.string.recovery_title_safety_storage, card.titleRes)
    }

    @Test fun `scheduled-window user-stop maps to ScheduledEnd`() {
        val card = RecoveryUiStateMapper.map(listOf(offerDiscardView(Terminated.USER_STOPPED, StopReason.SCHEDULE_WINDOW))).cards.single()
        assertEquals(RecoveryCardKind.SCHEDULED_END, card.kind)
        assertEquals(R.string.recovery_title_scheduled, card.titleRes)
    }

    @Test fun `permission-revoked user-stop maps to ErrorStopped`() {
        val card = RecoveryUiStateMapper.map(listOf(offerDiscardView(Terminated.USER_STOPPED, StopReason.PERMISSION_REVOKED))).cards.single()
        assertEquals(RecoveryCardKind.ERROR_STOPPED, card.kind)
        assertEquals(R.string.recovery_title_error, card.titleRes)
    }

    @Test fun `manual user-stop keeps existing copy`() {
        val card = RecoveryUiStateMapper.map(listOf(offerDiscardView(Terminated.USER_STOPPED, StopReason.USER))).cards.single()
        assertEquals(RecoveryCardKind.USER_STOPPED, card.kind)
        assertEquals(R.string.recovery_title_user_stopped, card.titleRes)
    }

    @Test fun `system kill unchanged`() {
        val card = RecoveryUiStateMapper.map(listOf(offerDiscardView(Terminated.KILLED_BY_SYSTEM, StopReason.NONE))).cards.single()
        assertEquals(RecoveryCardKind.KILLED_BY_SYSTEM, card.kind)
        assertTrue(card.showVendorHelpSlot)
    }
```
If the test file has no `offerDiscardView(terminated, stopReason)` helper, add one that constructs a `SessionManifest` with the given `terminated` + `stopReason` and a `SessionClassification` with `eligibility = DiscardEligibility.OFFER_DISCARD` and a non-empty surviving set — mirroring the file's existing fixture for the current USER_STOPPED test.

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.recovery.RecoveryUiStateMapperTest"`
Expected: FAIL — mapper still emits USER_STOPPED for every gate reason.

- [ ] **Step 4: Rewrite `toCard` kind derivation + copy selection**

Edit `RecoveryUiState.kt`. Add imports: `import com.aritr.rova.data.StopCategory`, `import com.aritr.rova.data.StopCategoryClassifier`, `import com.aritr.rova.data.StopReason`.

In `toCard`, replace the `val kind = when (terminated) { ... }` block with:

```kotlin
        val stopReason = view.manifest.stopReason
        val kind = when (StopCategoryClassifier.categorize(terminated, stopReason)) {
            StopCategory.SAFETY_STOPPED -> RecoveryCardKind.SAFETY_STOPPED
            StopCategory.SCHEDULED_END -> RecoveryCardKind.SCHEDULED_END
            StopCategory.ERROR_STOPPED -> RecoveryCardKind.ERROR_STOPPED
            StopCategory.USER_STOPPED -> RecoveryCardKind.USER_STOPPED
            StopCategory.INTERRUPTED ->
                if (terminated == Terminated.KILLED_BY_SYSTEM) RecoveryCardKind.KILLED_BY_SYSTEM
                else RecoveryCardKind.KILLED_FORCE_STOP
            StopCategory.RECOVERED, StopCategory.COMPLETED -> return null // isEligible already filters
        }
```

Replace `titleRes = titleResFor(kind)` / `bodyRes = bodyResFor(kind)` with reason-aware selectors:

```kotlin
            titleRes = titleResFor(kind, stopReason),
            bodyRes = bodyResFor(kind, stopReason),
```

Replace `titleResFor` / `bodyResFor` with the expanded, reason-aware forms:

```kotlin
    @StringRes
    private fun titleResFor(kind: RecoveryCardKind, stopReason: StopReason): Int = when (kind) {
        RecoveryCardKind.USER_STOPPED -> R.string.recovery_title_user_stopped
        RecoveryCardKind.KILLED_BY_SYSTEM -> R.string.recovery_title_killed_by_system
        RecoveryCardKind.KILLED_FORCE_STOP -> R.string.recovery_title_force_stopped
        RecoveryCardKind.SAFETY_STOPPED ->
            if (stopReason == StopReason.LOW_STORAGE) R.string.recovery_title_safety_storage
            else R.string.recovery_title_safety_thermal
        RecoveryCardKind.SCHEDULED_END -> R.string.recovery_title_scheduled
        RecoveryCardKind.ERROR_STOPPED -> R.string.recovery_title_error
    }

    @StringRes
    private fun bodyResFor(kind: RecoveryCardKind, stopReason: StopReason): Int = when (kind) {
        RecoveryCardKind.USER_STOPPED -> R.string.recovery_body_user_stopped
        RecoveryCardKind.KILLED_BY_SYSTEM -> R.string.recovery_body_killed_by_system
        RecoveryCardKind.KILLED_FORCE_STOP -> R.string.recovery_body_force_stopped
        RecoveryCardKind.SAFETY_STOPPED ->
            if (stopReason == StopReason.LOW_STORAGE) R.string.recovery_body_safety_storage
            else R.string.recovery_body_safety_thermal
        RecoveryCardKind.SCHEDULED_END -> R.string.recovery_body_scheduled
        RecoveryCardKind.ERROR_STOPPED -> R.string.recovery_body_error
    }
```

`showVendorHelpSlot = (kind == RecoveryCardKind.KILLED_BY_SYSTEM)` is unchanged. The `when (terminated)` defensive early-returns for `COMPLETED`/`MULTI_SEGMENT_KEPT` already happen above in `toCard` (and in `isEligible`); leave them.

- [ ] **Step 5: Run tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.recovery.*"`
Expected: PASS.

- [ ] **Step 6: Full build + test**

Run: `./gradlew :app:assembleDebug` then `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; all tests green.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/recovery/RecoveryUiState.kt app/src/main/res/values/strings.xml app/src/main/res/values-es/strings.xml app/src/test/java/com/aritr/rova/ui/recovery/RecoveryUiStateMapperTest.kt
git commit -m "feat(recovery): per-reason copy + accent for safety/scheduled/error stops"
```

---

### Task 7: Full verification + device smoke

**Files:** none (verification only).

- [ ] **Step 1: Clean-of-cache-free full gate run**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL — all 46 `check*` gates green. Confirm in output that `checkStatusColorLocked`, `checkUserCopyVocabulary`, `checkNoHardcodedUiStrings`, `checkUserStoppedBeforeMerge`, `checkRecoveryNoDeletion` ran and passed.

- [ ] **Step 2: Full unit suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS, no regressions (baseline was 1241/0-0-0; this adds tests).

- [ ] **Step 3: Install + device smoke (RZCYA1VBQ2H)**

```bash
adb -s RZCYA1VBQ2H install -r app/build/outputs/apk/debug/app-debug.apk
```
Manual checks (owner-driven):
- A **manual** stop → Library tile shows NO badge; recovery card (if unmerged) reads "Session stopped".
- A **thermal/low-storage** auto-stop → Library tile shows the orange "Auto-stopped" badge with the thermometer (thermal) / storage glyph; recovery card reads "Paused to cool down" / "Stopped — storage was full" with amber accent.
- A **scheduled-window** end → Library tile shows NO badge; recovery card (if unmerged) reads "Ended on schedule" with the neutral (blue) accent, no alarm.
- Confirm the badge glyph choice (single thermometer would be wrong for storage; verify the reason-aware glyph picks storage correctly).

- [ ] **Step 4: Report results to owner**

Summarize gate + test output and smoke observations. Await explicit GO before push/PR/merge.

---

## Self-Review

**Spec coverage:** §3 helper → Task 2. §4 Library badge (value, color, glyph, label, plumbing) → Tasks 3+4. §5 Recovery (enum, render, mapper, copy) → Tasks 5+6. §6 strings → Tasks 3/5/6. §7 tests → each task's test step. §8 gates → Task 7. ADR amend-first → Task 1. All covered.

**Placeholder scan:** No TBD/TODO. Every code step shows the code. The two "open the file and mirror the existing branch" steps (TileSemantics:25, LibraryListRow:133) name the exact file:line and the pattern to mirror — both are tiny exhaustive-`when`/call-arg additions the compiler will force, not vague hand-waves.

**Type consistency:** `categorize(terminated, stopReason)` — same 2-arg signature in Task 2 (def), Task 3 (StatusBadgePolicy), Task 6 (mapper). `badgeFor(terminated, stopReason, exportState)` consistent Task 3 def + LibraryRowMapper caller. `badgeGlyph(badge, stopReason?)` consistent Task 4 def + StatusBadgePill caller. `RecoveryCardKind` six values defined Task 5, consumed Task 6. `StopReason.NONE` default used for legacy/missing facts. Consistent.

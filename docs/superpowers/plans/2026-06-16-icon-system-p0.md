# Icon System P0 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **Subagents EDIT-ONLY; the controller runs all gradle + commits.**

**Goal:** Land the pre-theme-engine icon slice (ADR-0031 §8 / spec §11 P0): a single `SemanticIcon` tint seam that ALL glyph color flows through, status colors locked to `RovaSemantics`, the concept→glyph collision decision encoded in one `RovaIcons` alias map, and two new `check*` gates — so the Liquid Glass theme engine inherits a stable icon color contract.

**Architecture:** Mirror the established **identity-vs-locked** seam pattern (`GlassResolver` / `LibraryColorSpec` / `RovaSemantics`). A pure-Kotlin resolver (`SemanticIconSpec`) maps an `IconRole` (identity — retints per palette) or an `IconStatus` (locked — `RovaSemantics`) to a `Color`. A thin `@Composable SemanticIcon(...)` wrapper reads `LocalGlassEnvironment.current.palette` and renders `Icon(...)`. Every raw `tint = Color.White.copy(alpha=…)` / `tint = Color.White` Icon call-site migrates to it. Bespoke glyph **authoring** (new vectors) is explicitly **out of P0** (→ P1 with the engine).

**Tech Stack:** Kotlin 2.2.10, Jetpack Compose (BOM 2025.01.01), JUnit4 JVM unit tests (`isReturnDefaultValues = true`), Gradle 9.4.1 custom `check*` tasks wired to `preBuild`.

---

## Scope reconciliation (read before starting)

The kickoff/spec named "20 raw `Color.White.copy(alpha=…)` sites (RecordChrome ×7, PlayerScreen ×8, WarningSheetV3 ×3, SettingsScreen ×2, + LibraryRow/Onboarding/Vault/Warnings)". An authoritative grep (2026-06-16, `tint\s*=.*\bColor` + `Color.White.copy(alpha`) **corrected** this:

- **No** icon-tint offenders in SettingsScreen, Onboarding, or Vault (those files' `Color.White.copy(alpha)` hits are tokens/scrims/illustrations, not Icon tints).
- **Newly found** (missed by spec): `PreviewActivity.kt` ×4 (`tint = Color.White`, full-opacity raw literal) and `RovaCardComponents.kt` ×1 (`tint = Color.White`).
- The real offender set = **every `tint =` argument bound to a raw `Color` literal** (covers `.copy(alpha=…)`, full `Color.White`, and conditional `tint = if (x) token else Color.White.copy(…)`).

**Authoritative P0 migration set — 8 files, ~22 sites** (the `checkSemanticIconNoRawAlpha` gate is the final arbiter of completeness; migrate until it is green):

| File | Lines | Notes |
|---|---|---|
| `ui/screens/RecordChrome.kt` | 251, 252, 762, 821, 822, 862 | 251/252 are `val tint = if(enabled) …` computed tints feeding Icon; 762 conditional with `RecordChromeTokens.navIcon` active branch |
| `ui/screens/player/PlayerScreen.kt` | 278, 338, 441, 448, 463, 471, 478 | over-media transport controls |
| `ui/warnings/WarningSheetV3.kt` | 434 | overflow MoreHoriz |
| `ui/warnings/WarningSnoozeChip.kt` | 99 | chip leading icon |
| `ui/warnings/WarningTopBannerV3.kt` | 154 | overflow MoreVert |
| `ui/screens/LibraryRow.kt` | 395 | over-media PlayCircle |
| `ui/PreviewActivity.kt` | 198, 328, 333, 338 | `tint = Color.White` over-media |
| `ui/components/RovaCardComponents.kt` | 177 | `tint = Color.White` |

**Collision resolution — what P0 can actually do without authoring bespoke vectors** (ADR-0031 §8 defers bespoke `RovaGlyphs` authoring to P1): P0 delivers the **`RovaIcons` alias map** (one canonical reference per concept — structurally enforces "one concept → one glyph") + **safe stock re-points** + a **decision record**. The bespoke *redraws* named in the spec §7 (8-spoke gear, stacked-frames Library, ring+core record-nav, bespoke amber triangle) require new `ImageVector` authoring and are **P1**. P0 keeps the existing bespoke vectors (`RecordChromeIcons.settings`, `.library`, `.fabPlay`) as the canonical references behind the alias map, and re-points only stock-glyph concepts that need no new vector. This boundary is owner-flagged in the final summary.

---

## File structure

**New files:**
- `app/src/main/java/com/aritr/rova/ui/theme/SemanticIconSpec.kt` — pure-Kotlin resolver + `IconRole`/`IconStatus` enums + `SemanticIcons` immutable data class + `rememberSemanticIcons()` accessor. (theme/ — sibling of `GlassResolver.kt`, `LibraryColorSpec` lives in library/ but this seam is app-wide so theme/ is correct.)
- `app/src/main/java/com/aritr/rova/ui/components/SemanticIcon.kt` — the `@Composable SemanticIcon(...)` wrapper (the single allowlisted call-site for raw `Icon(tint=Color…)`).
- `app/src/main/java/com/aritr/rova/ui/theme/RovaIcons.kt` — the concept→glyph alias map object.
- `app/src/test/java/com/aritr/rova/ui/theme/SemanticIconSpecTest.kt` — JVM tests for the resolver.
- `app/src/test/java/com/aritr/rova/ui/theme/RovaIconsTest.kt` — JVM tests for the alias map (one canonical glyph per concept; status concepts pair with `IconStatus`).

**Modified files:**
- 8 migration files (table above).
- `ui/warnings/WarningSheetContent.kt` — Notifications concept re-point (if adopted; see Task 12).
- `app/build.gradle.kts` — register `checkSemanticIconNoRawAlpha` + `checkStatusColorLocked`, wire both into `preBuild`.
- `app/src/main/res/values/strings.xml` + `values-es/strings.xml` — any new `contentDescription` strings (ADR-0022).
- `docs/adr/0031-icon-glyph-system.md` — Proposed→Accepted; number the clauses the gates cite.
- `CLAUDE.md` — gate count 42→44; add the two gates to the gate list.

---

## Seam design (reference for all tasks)

```kotlin
// SemanticIconSpec.kt — pure Kotlin, no Compose imports beyond Color. JVM-testable.

/** Identity tint roles — retint with the active palette (the channel the theme engine drives). */
enum class IconRole { Default, Secondary, Disabled, Accent }

/** Locked status roles — bound to RovaSemantics, identical across all palettes, never alpha-diluted. */
enum class IconStatus { Recovered, Interrupted, Processing, Success, Warning, Rec }

object SemanticIconSpec {
    // Disabled glyphs sit well below body text; matches the dominant pre-migration value (~0.30 over media).
    private const val DISABLED_ALPHA = 0.30f

    /** Identity: derives from the palette so a theme swap retints every glyph through one place. */
    fun tint(palette: RovaPalette, role: IconRole): Color = when (role) {
        IconRole.Default   -> palette.textHigh                       // primary glyph (neutral-dark ≈ white@0.93)
        IconRole.Secondary -> palette.textDim                        // secondary / inactive (≈ white@0.65)
        IconRole.Disabled  -> palette.textHigh.copy(alpha = DISABLED_ALPHA)
        IconRole.Accent    -> palette.accent                         // active / primary action
    }

    /** Locked: status color is meaning, never theme-retinted, never per-call alpha (ADR-0031 §3). */
    fun statusTint(status: IconStatus): Color = when (status) {
        IconStatus.Recovered   -> RovaSemantics.success
        IconStatus.Interrupted -> RovaSemantics.warning
        IconStatus.Processing  -> RovaSemantics.escalating
        IconStatus.Success     -> RovaSemantics.success
        IconStatus.Warning     -> RovaSemantics.warning
        IconStatus.Rec         -> RovaSemantics.rec
    }
}
```

```kotlin
// SemanticIcon.kt — the ONLY allowlisted site for a raw Color Icon tint.
@Composable
fun SemanticIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    role: IconRole = IconRole.Default,
    status: IconStatus? = null,   // when non-null, locks tint to RovaSemantics and wins over role
) {
    val palette = LocalGlassEnvironment.current.palette
    val tint = status?.let { SemanticIconSpec.statusTint(it) } ?: SemanticIconSpec.tint(palette, role)
    Icon(imageVector = imageVector, contentDescription = contentDescription, modifier = modifier, tint = tint)
}
```

**Canonical role mapping for migration** (before-alpha → role; verify on device — this is a deliberate, owner-reviewable opacity normalization):

| Pre-migration tint | Role | Resolves to (pinned neutral-dark) |
|---|---|---|
| `Color.White` (1.0) / `…copy(0.9)` / `…copy(0.85)` / `…copy(0.78)` | `Default` | white @ 0.93 |
| `…copy(0.75)` / `…copy(0.7)` / `…copy(0.6)` / `…copy(0.55)` | `Secondary` | white @ 0.65 |
| `…copy(0.3)` / `…copy(0.25)` / `…copy(0.14)` | `Disabled` | white @ 0.30 |
| active branch using `RecordChromeTokens.navIcon` | `Accent` | palette accent |

---

## Task 0: Branch + baseline (controller only)

- [ ] **Step 1: Create the local branch off master**

```bash
git switch -c feat/icon-system-p0
```

- [ ] **Step 2: Confirm baseline green**

Run: `./gradlew.bat :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, existing suite passes (JVM only).

---

## Task 1: `SemanticIconSpec` resolver + enums (pure Kotlin, TDD)

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/theme/SemanticIconSpec.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/theme/SemanticIconSpecTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.aritr.rova.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SemanticIconSpecTest {

    private val aurora = rovaPalettes.getValue(ThemeSelection.AURORA)
    private val daylight = rovaPalettes.getValue(ThemeSelection.DAYLIGHT)

    @Test fun default_role_is_palette_textHigh() {
        assertEquals(aurora.textHigh, SemanticIconSpec.tint(aurora, IconRole.Default))
    }

    @Test fun secondary_role_is_palette_textDim() {
        assertEquals(aurora.textDim, SemanticIconSpec.tint(aurora, IconRole.Secondary))
    }

    @Test fun accent_role_is_palette_accent() {
        assertEquals(aurora.accent, SemanticIconSpec.tint(aurora, IconRole.Accent))
    }

    @Test fun disabled_role_dims_textHigh_to_30pct() {
        assertEquals(0.30f, SemanticIconSpec.tint(aurora, IconRole.Disabled).alpha, 0.001f)
    }

    @Test fun identity_roles_retint_per_palette() {
        // The whole point of "identity": a theme swap changes the tint.
        assertNotEquals(
            SemanticIconSpec.tint(aurora, IconRole.Accent),
            SemanticIconSpec.tint(daylight, IconRole.Accent),
        )
    }

    @Test fun status_tints_are_locked_to_RovaSemantics() {
        assertEquals(RovaSemantics.success, SemanticIconSpec.statusTint(IconStatus.Recovered))
        assertEquals(RovaSemantics.warning, SemanticIconSpec.statusTint(IconStatus.Interrupted))
        assertEquals(RovaSemantics.escalating, SemanticIconSpec.statusTint(IconStatus.Processing))
        assertEquals(RovaSemantics.success, SemanticIconSpec.statusTint(IconStatus.Success))
        assertEquals(RovaSemantics.warning, SemanticIconSpec.statusTint(IconStatus.Warning))
        assertEquals(RovaSemantics.rec, SemanticIconSpec.statusTint(IconStatus.Rec))
    }

    @Test fun status_tints_carry_no_per_call_alpha_dilution() {
        // Locked colors are used at their full RovaSemantics opacity.
        IconStatus.values().forEach { s ->
            assertEquals(1.0f, SemanticIconSpec.statusTint(s).alpha, 0.001f)
        }
    }

    @Test fun status_is_identical_across_all_palettes() {
        // Locked = does NOT retint. (statusTint takes no palette, but assert intent explicitly.)
        val rec = SemanticIconSpec.statusTint(IconStatus.Rec)
        assertEquals(RovaSemantics.rec, rec)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.theme.SemanticIconSpecTest"`
Expected: FAIL — `SemanticIconSpec` / `IconRole` / `IconStatus` unresolved.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.aritr.rova.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Identity tint roles — derive from the active [RovaPalette] and so retint on a theme swap.
 * This is the channel the Liquid Glass theme engine will drive (ADR-0031 §3/§4).
 */
enum class IconRole { Default, Secondary, Disabled, Accent }

/**
 * Locked status roles — bound to [RovaSemantics], identical across all 12 palettes, never
 * theme-retinted and never per-call alpha-diluted (ADR-0031 §3, WCAG 1.4.1: always paired with shape).
 */
enum class IconStatus { Recovered, Interrupted, Processing, Success, Warning, Rec }

/**
 * The single content-color contract for every in-app glyph (ADR-0031 §4). Pure-Kotlin so it is
 * JVM-unit-testable under `isReturnDefaultValues = true`; the framework-touching part is the thin
 * [com.aritr.rova.ui.components.SemanticIcon] composable. Mirrors the GlassResolver / LibraryColorSpec
 * identity-vs-locked split exactly.
 */
@Immutable
object SemanticIconSpec {
    /** Disabled glyphs sit far below body text; matches the dominant pre-migration over-media value. */
    private const val DISABLED_ALPHA = 0.30f

    fun tint(palette: RovaPalette, role: IconRole): Color = when (role) {
        IconRole.Default -> palette.textHigh
        IconRole.Secondary -> palette.textDim
        IconRole.Disabled -> palette.textHigh.copy(alpha = DISABLED_ALPHA)
        IconRole.Accent -> palette.accent
    }

    fun statusTint(status: IconStatus): Color = when (status) {
        IconStatus.Recovered -> RovaSemantics.success
        IconStatus.Interrupted -> RovaSemantics.warning
        IconStatus.Processing -> RovaSemantics.escalating
        IconStatus.Success -> RovaSemantics.success
        IconStatus.Warning -> RovaSemantics.warning
        IconStatus.Rec -> RovaSemantics.rec
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.theme.SemanticIconSpecTest"`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/theme/SemanticIconSpec.kt app/src/test/java/com/aritr/rova/ui/theme/SemanticIconSpecTest.kt
git commit -m "feat(icons): SemanticIconSpec resolver — identity roles + locked status (ADR-0031 P0)"
```

---

## Task 2: `SemanticIcon` composable wrapper

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/components/SemanticIcon.kt`

(No JVM test — composable; verified by compile + the migration tasks + on-device smoke. The pure logic it calls is covered by Task 1.)

- [ ] **Step 1: Write the wrapper**

```kotlin
package com.aritr.rova.ui.components

import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.aritr.rova.ui.theme.IconRole
import com.aritr.rova.ui.theme.IconStatus
import com.aritr.rova.ui.theme.LocalGlassEnvironment
import com.aritr.rova.ui.theme.SemanticIconSpec

/**
 * The single entry-point for glyph color (ADR-0031 §4). All in-app `Icon(...)` tints flow through
 * here so the theme engine can drive icon color from one seam. Reads the active palette from
 * [LocalGlassEnvironment]; `status` (locked RovaSemantics) wins over `role` (identity) when present.
 *
 * This is the ONLY file allowlisted by `checkSemanticIconNoRawAlpha` to apply a raw Color tint.
 */
@Composable
fun SemanticIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    role: IconRole = IconRole.Default,
    status: IconStatus? = null,
) {
    val palette = LocalGlassEnvironment.current.palette
    val tint = if (status != null) {
        SemanticIconSpec.statusTint(status)
    } else {
        SemanticIconSpec.tint(palette, role)
    }
    Icon(imageVector = imageVector, contentDescription = contentDescription, modifier = modifier, tint = tint)
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL (warm). If a real kotlinc/MD5 fault appears: `./gradlew.bat --stop` then delete `app/build/kotlin` + `app/build/intermediates/built_in_kotlinc`, retry. Do NOT wipe caches prophylactically.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/components/SemanticIcon.kt
git commit -m "feat(icons): SemanticIcon composable — single tint entry-point (ADR-0031 P0)"
```

---

## Task 3: `RovaIcons` alias map (collision decision record)

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/theme/RovaIcons.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/theme/RovaIconsTest.kt`

Encodes "one concept → one glyph" (ADR-0031 §2). Each concept resolves to exactly one canonical glyph — existing bespoke where it exists, stock Material otherwise. **No new vectors authored** (that is P1). The map IS the collision resolution: a single edit re-points a concept everywhere.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.aritr.rova.ui.theme

import androidx.compose.material.icons.Icons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RovaIconsTest {

    @Test fun warning_status_concept_carries_a_locked_status_role() {
        // Warning is a STATUS, never a setting — it must declare an IconStatus, not just a glyph.
        assertEquals(IconStatus.Warning, RovaIcons.WarningStatus.status)
    }

    @Test fun notifications_setting_is_not_a_status() {
        // Notifications is a SETTING (bell), distinct from the Warning status (ADR-0031 §7 / spec §7.3).
        assertEquals(null, RovaIcons.NotificationsSetting.status)
    }

    @Test fun library_and_play_are_distinct_glyphs() {
        // Un-collide Library vs Play (spec §8). Reference identity differs.
        assertNotEquals(RovaIcons.Library.glyph, RovaIcons.Play.glyph)
    }

    @Test fun settings_and_view_are_distinct_glyphs() {
        assertNotEquals(RovaIcons.Settings.glyph, RovaIcons.View.glyph)
    }

    @Test fun sort_resolves_to_a_state_free_material_glyph() {
        assertEquals(Icons.AutoMirrored.Filled.Sort, RovaIcons.Sort.glyph)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.theme.RovaIconsTest"`
Expected: FAIL — `RovaIcons` unresolved.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.aritr.rova.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.ui.graphics.vector.ImageVector
import com.aritr.rova.ui.screens.RecordChromeIcons

/**
 * Canonical concept→glyph map (ADR-0031 §2/§5). One concept → exactly one glyph, everywhere — a single
 * edit re-points a concept. Bespoke vectors stay in [RecordChromeIcons] until P1 folds them into a
 * `RovaGlyphs` home; stock Material glyphs are aliased here. **No vector is authored in P0.**
 *
 * `status` is non-null only for STATUS concepts — those tint through [IconStatus] (locked RovaSemantics),
 * never an identity role. Keeps Warning-the-status distinct from Notifications-the-setting.
 */
data class RovaIcon(val glyph: ImageVector, val status: IconStatus? = null)

object RovaIcons {
    // ── Navigation / brand (existing bespoke — kept as canonical references; P1 redraws to RovaGlyphs) ──
    val Library = RovaIcon(RecordChromeIcons.library)
    val Settings = RovaIcon(RecordChromeIcons.settings)
    val Play = RovaIcon(RecordChromeIcons.fabPlay)

    // ── Stock actions (state-free) ──
    val Sort = RovaIcon(Icons.AutoMirrored.Filled.Sort)
    val View = RovaIcon(Icons.Default.GridView)            // distinct from Settings (gear) and Sort (bars)

    // ── Status vs setting split (spec §7.3) ──
    val WarningStatus = RovaIcon(Icons.Default.WarningAmber, status = IconStatus.Warning)  // locked amber
    val NotificationsSetting = RovaIcon(Icons.Default.Notifications)                        // bell, a setting
}
```

> If `Icons.Default.GridView` is not resolvable in the pinned material-icons set, substitute `Icons.Default.ViewModule`; the test only asserts distinctness, not the exact glyph. The subagent confirms the import compiles.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.theme.RovaIconsTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Compile (catches missing Material imports)**

Run: `./gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/theme/RovaIcons.kt app/src/test/java/com/aritr/rova/ui/theme/RovaIconsTest.kt
git commit -m "feat(icons): RovaIcons alias map — one concept→one glyph, status/setting split (ADR-0031 P0)"
```

---

## Tasks 4–11: Migrate each file to `SemanticIcon`

**Uniform transform for every offender:**

```
Icon(<vector>, contentDescription = <cd>, tint = <rawColor>, modifier = <m>)
        ↓
SemanticIcon(<vector>, contentDescription = <cd>, role = <mapped role>, modifier = <m>)
```

- Drop the `tint =` argument; choose `role` from the canonical role-mapping table (Seam design section).
- Preserve the existing `contentDescription` verbatim (no new strings unless the icon had none AND is non-decorative — none of the listed sites need new strings).
- For a conditional tint (`tint = if (enabled) A else B`), pass the role conditionally: `role = if (enabled) IconRole.Secondary else IconRole.Disabled`. For the `RecordChrome.kt:762` active branch that used `RecordChromeTokens.navIcon`, use `role = if (enabled) IconRole.Accent else IconRole.Disabled` (verify the active nav glyph still reads correctly on device; if the token was a neutral white rather than accent, use `IconRole.Default`).
- For `val tint = …` computed-tint sites (`RecordChrome.kt:251/252`), delete the `val tint`/`val flipTint` and inline the role at the `SemanticIcon` call.
- **Faint-site audit (codex):** the very-low-alpha sites (`0.14` nav-disabled @762, `0.25` fab-disabled @822) brighten to `0.30` under `Disabled`. Before mapping, confirm each is a genuinely *disabled icon state* (it is — nav/FAB disabled) and not *intentionally-faint decorative chrome*. If any turns out decorative, keep it out of `SemanticIcon` (it isn't a semantic glyph) rather than forcing it to `0.30`. Device smoke (Task 17) is the final check that disabled still reads as disabled.
- Each task ends by **rebuilding** (`assembleDebug`) and confirming the file's offenders are gone (the gate in Task 12 is the global check).

Each task is one file. **Read the full file before editing** (the snippets below are anchors, not the whole call). Add `import com.aritr.rova.ui.components.SemanticIcon`, `com.aritr.rova.ui.theme.IconRole` (and `IconStatus` where used); remove now-unused `Icon` / `Color` imports only if no other usage remains in the file.

### Task 4: `ui/screens/RecordChrome.kt` (251, 252, 762, 821, 822, 862)

- [ ] **Step 1:** Read the file. Migrate all 6 sites:
  - 251/252: delete `val tint`/`val flipTint`; at the cam-control `Icon` calls use `role = if (enabled) IconRole.Secondary else IconRole.Disabled` (and `flipEnabled` for the flip control).
  - 762: nav glyph — `role = if (enabled) IconRole.Accent else IconRole.Disabled` (device-verify active nav).
  - 821 (`RecordFabState.Start`, `fabPlay`, white 0.78): `role = IconRole.Default`.
  - 822 (`RecordFabState.Disabled`, `fabPlay`, white 0.25): `role = IconRole.Disabled`.
  - 862 (`HistoryIcon`, white 0.7): `role = IconRole.Secondary`.
- [ ] **Step 2:** Run `./gradlew.bat :app:assembleDebug` → BUILD SUCCESSFUL.
- [ ] **Step 3:** Commit: `git commit -am "refactor(icons): RecordChrome → SemanticIcon (ADR-0031 P0)"`

### Task 5: `ui/screens/player/PlayerScreen.kt` (278, 338, 441, 448, 463, 471, 478)

- [ ] **Step 1:** Read the file. Migrate (over-media transport; preserve `stringResource(...)` CDs):
  - 278 (`ArrowBack`, 0.85): `role = IconRole.Default`.
  - 338 (`PlayArrow`, 0.9): `role = IconRole.Default`.
  - 441 (`ContentCut`, 0.6): `role = IconRole.Secondary`.
  - 448 (`Replay10`, 0.85): `role = IconRole.Default`.
  - 463 (`Pause`/`PlayArrow`, 0.9): `role = IconRole.Default`.
  - 471 (`Forward10`, 0.85): `role = IconRole.Default`.
  - 478 (`Edit`, 0.6): `role = IconRole.Secondary`.
- [ ] **Step 2:** Run `./gradlew.bat :app:assembleDebug` → BUILD SUCCESSFUL.
- [ ] **Step 3:** Commit: `git commit -am "refactor(icons): PlayerScreen → SemanticIcon (ADR-0031 P0)"`

### Task 6: `ui/warnings/WarningSheetV3.kt` (434)

- [ ] **Step 1:** Read the file. 434 (`MoreHoriz`, 0.30): `role = IconRole.Disabled` (preserve `warning_more_actions_cd`).
- [ ] **Step 2:** `./gradlew.bat :app:assembleDebug` → SUCCESSFUL.
- [ ] **Step 3:** Commit: `git commit -am "refactor(icons): WarningSheetV3 → SemanticIcon (ADR-0031 P0)"`

### Task 7: `ui/warnings/WarningSnoozeChip.kt` (99)

- [ ] **Step 1:** Read the file. 99 (`content.icon`, 0.78): `role = IconRole.Default`. (This chip surfaces a warning — if `content` already carries a semantic state, prefer `status = IconStatus.Warning`; otherwise `role = IconRole.Default`. Verify against the `content` model; do NOT invent a status the model lacks.)
- [ ] **Step 2:** `./gradlew.bat :app:assembleDebug` → SUCCESSFUL.
- [ ] **Step 3:** Commit: `git commit -am "refactor(icons): WarningSnoozeChip → SemanticIcon (ADR-0031 P0)"`

### Task 8: `ui/warnings/WarningTopBannerV3.kt` (154)

- [ ] **Step 1:** Read the file. 154 (`MoreVert`, 0.55): `role = IconRole.Secondary` (preserve `warning_more_options_cd`).
- [ ] **Step 2:** `./gradlew.bat :app:assembleDebug` → SUCCESSFUL.
- [ ] **Step 3:** Commit: `git commit -am "refactor(icons): WarningTopBannerV3 → SemanticIcon (ADR-0031 P0)"`

### Task 9: `ui/screens/LibraryRow.kt` (395)

- [ ] **Step 1:** Read the file. 395 (`PlayCircle`, 0.78): `role = IconRole.Default`.
- [ ] **Step 2:** `./gradlew.bat :app:assembleDebug` → SUCCESSFUL.
- [ ] **Step 3:** Commit: `git commit -am "refactor(icons): LibraryRow → SemanticIcon (ADR-0031 P0)"`

### Task 10: `ui/PreviewActivity.kt` (198, 328, 333, 338)

- [ ] **Step 1:** Read the file. Confirm the composables are inside `RovaTheme { }` (so `LocalGlassEnvironment` is provided; if not, `SemanticIcon` falls back to the static default = Aurora, white@0.93 ≈ the current `Color.White` — acceptable). Migrate all four `tint = Color.White` (over-media back/replay/save/share): `role = IconRole.Default`.
- [ ] **Step 2:** `./gradlew.bat :app:assembleDebug` → SUCCESSFUL.
- [ ] **Step 3:** Commit: `git commit -am "refactor(icons): PreviewActivity → SemanticIcon (ADR-0031 P0)"`

### Task 11: `ui/components/RovaCardComponents.kt` (177)

- [ ] **Step 1:** Read the file. 177 (`tint = Color.White`): `role = IconRole.Default` (verify the icon's role in context — if it's a secondary/dim affordance, use `Secondary`).
- [ ] **Step 2:** `./gradlew.bat :app:assembleDebug` → SUCCESSFUL.
- [ ] **Step 3:** Commit: `git commit -am "refactor(icons): RovaCardComponents → SemanticIcon (ADR-0031 P0)"`

---

## Task 12: Concept re-points (optional, low-risk stock swaps)

**Files:**
- Modify: `ui/warnings/WarningSheetContent.kt` (NOTIFICATIONS_DENIED icon)

P0 stock re-points that need no new vector. **Only adopt if it does not change intended meaning** — the notifications-denied warning legitimately shows *why* (the off-bell). Decision per spec §7.3: the **status vs setting split is what matters**, and `RovaIcons` already encodes it. The call-site `NotificationsOff` (cause icon) may stay. **Default: make NO call-site glyph swap in P0** — the split is recorded in `RovaIcons`; call-sites adopt `RovaIcons.*` in P1 with the engine. Skip this task unless the owner asks for the swap now.

- [ ] **Step 1 (only if owner opts in):** In `WarningSheetContent.kt`, leave domain cause-icons as-is. No change. (Documented decision: domain cause-icons are intentional; the canonical Notifications-setting/Warning-status glyphs live in `RovaIcons` for adoption in P1.)

---

## Task 13: Gate `checkSemanticIconNoRawAlpha`

**Files:**
- Modify: `app/build.gradle.kts` (register task + add to `preBuild` `dependsOn` block)

Enforces ADR-0031 §4: no raw `Color` literal as an `Icon` tint outside the `SemanticIcon` seam. Keys on the **`tint =` argument** (not on `Icon(` proximity — offenders are multi-line) so it catches `.copy(alpha=…)`, full `Color.White`, and conditional tints. Allowlists the seam file by canonical path. Follows the exact idiom of `checkRecordSurfaceNoBlur` / `checkNoHardcodedUiStrings` (file-walk + comment-strip + regex + grouped GradleException citing the ADR clause).

- [ ] **Step 1: Add the task registration** (place near the other `check*` registrations)

```kotlin
val checkSemanticIconNoRawAlpha = tasks.register("checkSemanticIconNoRawAlpha") {
    group = "verification"
    description = "Forbid a raw Color literal as an Icon tint outside the SemanticIcon seam — all glyph " +
        "color must flow through SemanticIcon/SemanticIconSpec (ADR-0031 §4)."
    val srcDir = file("src/main/java/com/aritr/rova")
    val seamFile = file("src/main/java/com/aritr/rova/ui/components/SemanticIcon.kt").canonicalFile
    inputs.dir(srcDir).withPropertyName("rovaSources")
    doLast {
        if (!srcDir.exists()) {
            throw GradleException("checkSemanticIconNoRawAlpha: Rova source dir missing: $srcDir")
        }
        // A `tint =` argument bound to a raw Color literal/expression: `Color.White`, `Color(0x…)`,
        // `Color.White.copy(alpha=…)`, or a conditional `if (x) token else Color.White.copy(…)`.
        // `\bColor\s*[.(]` matches a standalone `Color` token (word boundary) followed by `.` or `(`,
        // so theme-derived tints (`tint = palette.textHigh`, `tint = LocalContentColor.current`,
        // `tint = RecordChromeTokens.navIcon`, `tint = someColorVar`) are NOT flagged. Non-greedy `.*?`
        // stops at the first Color token. A 3-line forward window catches the offender shape where
        // `tint =` ends a line and the `Color` value wraps onto the next (per codex review).
        val rawTintPattern = Regex("""tint\s*=.*?\bColor\s*[.(]""")
        val offenders = srcDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.canonicalFile != seamFile }
            .mapNotNull { f ->
                val lines = f.readLines()
                val hits = lines.indices.mapNotNull { i ->
                    val line = lines[i]
                    if (line.contains("semanticicon-opt-out")) return@mapNotNull null
                    val trimmed = line.trimStart()
                    if (trimmed.startsWith("//") || trimmed.startsWith("*")) return@mapNotNull null
                    if (!line.contains("tint")) return@mapNotNull null
                    // Join this line + up to 2 following so a wrapped `Color` value is still seen.
                    val window = (i until minOf(i + 3, lines.size)).joinToString(" ") { lines[it] }
                    if (rawTintPattern.containsMatchIn(window)) i to line else null
                }
                if (hits.isEmpty()) null else f to hits
            }
            .toList()
        if (offenders.isNotEmpty()) {
            val report = offenders.joinToString("\n") { (f, hits) ->
                hits.joinToString("\n") { (i, line) -> "  ${f.relativeTo(rootDir)}:${i + 1}: ${line.trim()}" }
            }
            throw GradleException(
                "ADR-0031 §4 violation: raw Color literal used as an Icon tint outside the SemanticIcon " +
                    "seam. Route glyph color through `SemanticIcon(role = …, status = …)` / " +
                    "`SemanticIconSpec` so the theme engine drives icon color from one place. " +
                    "For a genuinely non-themed glyph, add `// semanticicon-opt-out: <reason>` on the line.\n" +
                    "Offenders:\n$report"
            )
        }
    }
}
```

- [ ] **Step 2: Wire into `preBuild`** — add inside the `tasks.matching { it.name == "preBuild" }.configureEach { … }` block:

```kotlin
        dependsOn(checkSemanticIconNoRawAlpha)
```

- [ ] **Step 3: Run the gate — expect GREEN** (all 8 files migrated in Tasks 4–11)

Run: `./gradlew.bat :app:checkSemanticIconNoRawAlpha`
Expected: BUILD SUCCESSFUL. If RED, the report lists remaining offenders — migrate them (a missed site or a file the audit didn't list), then re-run. **Never edit the gate to pass.**

- [ ] **Step 4: Prove the gate bites** (controller; transient)

Temporarily add to any UI file a line `Icon(Icons.Default.Star, null, tint = Color.White.copy(alpha = 0.5f))`, run `./gradlew.bat :app:checkSemanticIconNoRawAlpha`, confirm it FAILS naming that line, then revert the line. (Confirms the regex catches real offenders.)

- [ ] **Step 5: Commit**

```bash
git commit -am "build(icons): checkSemanticIconNoRawAlpha gate — tint via SemanticIcon only (ADR-0031 §4)"
```

---

## Task 14: Gate `checkStatusColorLocked`

**Files:**
- Modify: `app/build.gradle.kts`

Enforces ADR-0031 §3: status colors are locked — a `RovaSemantics.*` color is never per-call alpha-diluted (`.copy(alpha=…)`), anywhere. This is the precise, low-false-positive encoding of "status colors pull only from RovaSemantics, never a per-call color/alpha." (The status→`RovaSemantics` *mapping* itself is enforced by `SemanticIconSpecTest`; this gate enforces the no-dilution rule across all call-sites.)

- [ ] **Step 1: Add the task registration**

```kotlin
val checkStatusColorLocked = tasks.register("checkStatusColorLocked") {
    group = "verification"
    description = "Forbid per-call mutation (.copy of alpha or any channel) of a locked RovaSemantics " +
        "status color — status colors are exact, used at full locked opacity, always paired with shape " +
        "(ADR-0031 §3, WCAG 1.4.1)."
    val srcDir = file("src/main/java/com/aritr/rova")
    inputs.dir(srcDir).withPropertyName("rovaSources")
    doLast {
        if (!srcDir.exists()) {
            throw GradleException("checkStatusColorLocked: Rova source dir missing: $srcDir")
        }
        // Forbid ANY `.copy(...)` on a RovaSemantics color (per codex review): `.copy(alpha=…)` dilutes
        // opacity, `.copy(red=…)` mutates the locked hue — both break "status color is exact + locked".
        // The `.copy(` opener sits on the `RovaSemantics.<member>` line even when args wrap, so this also
        // catches the multiline / positional-arg (`copy(0.6f)`) cases the alpha-only regex missed.
        val dilutePattern = Regex("""RovaSemantics\s*\.\s*\w+\s*\.copy\s*\(""")
        val offenders = srcDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .mapNotNull { f ->
                val hits = f.readLines().withIndex().filter { (_, line) ->
                    val trimmed = line.trimStart()
                    if (trimmed.startsWith("//") || trimmed.startsWith("*")) false
                    else dilutePattern.containsMatchIn(line)
                }
                if (hits.isEmpty()) null else f to hits
            }
            .toList()
        if (offenders.isNotEmpty()) {
            val report = offenders.joinToString("\n") { (f, hits) ->
                hits.joinToString("\n") { (i, line) -> "  ${f.relativeTo(rootDir)}:${i + 1}: ${line.trim()}" }
            }
            throw GradleException(
                "ADR-0031 §3 violation: a locked RovaSemantics status color is mutated (.copy) at the " +
                    "call-site. Status colors render exact, at full locked opacity (and are paired with " +
                    "shape, WCAG 1.4.1). Use the color directly, or vary emphasis with shape/size.\n" +
                    "Offenders:\n$report"
            )
        }
    }
}
```

- [ ] **Step 2: Wire into `preBuild`** — add:

```kotlin
        dependsOn(checkStatusColorLocked)
```

- [ ] **Step 3: Run the gate — expect GREEN**

Run: `./gradlew.bat :app:checkStatusColorLocked`
Expected: BUILD SUCCESSFUL. (No current code dilutes a `RovaSemantics` color; if RED, fix the source — e.g. replace `RovaSemantics.warning.copy(alpha=…)` with the direct color.)

- [ ] **Step 4: Prove the gate bites** (controller; transient)

Temporarily add `val x = RovaSemantics.warning.copy(alpha = 0.5f)` to a UI file, run `./gradlew.bat :app:checkStatusColorLocked`, confirm FAIL naming the line, revert.

- [ ] **Step 5: Commit**

```bash
git commit -am "build(icons): checkStatusColorLocked gate — no per-call alpha on RovaSemantics (ADR-0031 §3)"
```

---

## Task 15: Full gate sweep + test suite

- [ ] **Step 1: Run the full debug build (runs every preBuild gate, all 44)**

Run: `./gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL — both new gates execute via `preBuild` and pass.

- [ ] **Step 2: Run the full unit suite**

Run: `./gradlew.bat :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL — baseline + the new `SemanticIconSpecTest` (8) + `RovaIconsTest` (5) all green, no regressions.

- [ ] **Step 3: Confirm zero remaining offenders (belt-and-suspenders)**

Run: `./gradlew.bat :app:checkSemanticIconNoRawAlpha :app:checkStatusColorLocked`
Expected: BUILD SUCCESSFUL.

---

## Task 16: Docs — ADR-0031 Accepted, clause numbering, CLAUDE.md, spec annotation

**Files:**
- Modify: `docs/adr/0031-icon-glyph-system.md`
- Modify: `CLAUDE.md`
- Modify: `docs/superpowers/specs/2026-06-16-icon-glyph-system-design.md`

- [ ] **Step 1:** ADR-0031: change Status `Proposed` → `Accepted (P0 landed <date>)`. Ensure the Enforcement section's gate bullets cite the exact clauses the gate messages reference (`§4` for `checkSemanticIconNoRawAlpha`, `§3` for `checkStatusColorLocked`). Add a one-line note that `RovaIcons` (alias map) is the P0 collision-resolution artifact and bespoke redraws remain P1.

- [ ] **Step 2:** `CLAUDE.md`: update the static-check gate count and list — **42 → 44**, add `checkSemanticIconNoRawAlpha`, `checkStatusColorLocked`. (Also correct the pre-existing drift the HANDOFF flagged: the doc still says "41 gates / 29 ADRs"; bring it to **44 gates / 31 ADRs** while here.)

- [ ] **Step 3:** Spec §11: annotate the P0 row as **landed**, noting the corrected offender set (8 files, ~22 sites; PreviewActivity/RovaCardComponents added, SettingsScreen/Onboarding/Vault removed) and that collision resolution shipped as the `RovaIcons` alias map + status/setting split, with bespoke redraws deferred to P1.

- [ ] **Step 4: Commit**

```bash
git add docs/adr/0031-icon-glyph-system.md CLAUDE.md docs/superpowers/specs/2026-06-16-icon-glyph-system-design.md
git commit -m "docs(icons): ADR-0031 Accepted (P0); gate clauses §3/§4; 44 gates"
```

---

## Task 17: Device smoke (owner) — opacity normalization check

P0 is presentation-only but the role mapping **normalizes** ad-hoc per-site alphas onto 4 canonical roles (a deliberate, owner-reviewable change). Install and eyeball:

- [ ] **Step 1 (controller):** `adb install -r app/build/outputs/apk/debug/app-debug.apk` (drive adb via PowerShell directly — the adb MCP wrapper is broken on Windows).
- [ ] **Step 2 (owner):** Smoke on RZCYA1VBQ2H (Android 14) — Record chrome (cam controls enabled/disabled, nav, FAB start/disabled), Player transport over media, Warning banner/chip/sheet overflow, Library row play glyph, Preview controls. Confirm disabled glyphs still read as disabled (0.30) and active states read correctly. Report any glyph that looks too bright/dim → adjust the role mapping (not the gate).

---

## Self-review notes (author)

- **Spec coverage:** §11 P0 → Tasks 1–2 (seam), Task 14 + Task 1 tests (status lock), Task 3 + Task 12 (collisions, within the no-new-vectors boundary), Tasks 13–14 (gates), Tasks 4–11 (the 20-→22-site migration). §10 sizing-scale registry is **not** in P0 scope (the kickoff scoped P0 to seam + status lock + collisions + 2 gates; size-token consolidation is unlisted — leave to a follow-up unless owner adds it).
- **Type consistency:** `IconRole {Default,Secondary,Disabled,Accent}`, `IconStatus {Recovered,Interrupted,Processing,Success,Warning,Rec}`, `SemanticIconSpec.tint(palette, role)` / `.statusTint(status)`, `SemanticIcon(imageVector, contentDescription, modifier, role, status)`, `RovaIcon(glyph, status)` / `RovaIcons.*` — used consistently across all tasks.
- **Gate completeness risk:** the migration site list is from a point-in-time grep; Task 13 Step 3 makes the gate itself the arbiter — any missed site fails the build and is migrated then. This is the safety net for the count discrepancy.
- **Boundary flagged for owner:** P0 does NOT author new bespoke vectors (gear redraw, stacked-frames, record-nav ring+core, bespoke amber triangle) — those are P1 per ADR-0031 §8. P0's collision deliverable is the `RovaIcons` decision map + status/setting split + safe stock references.

# Liquid Glass Foundation (PR1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the liquid-glass design-system foundation — `ThemeSelection` (flat-12 + Follow-System) with migration, `RovaPalette`×12 + locked `RovaSemantics`, role-aware glass (`GlassEnvironment`/`GlassResolver`/`GlassRole`/`GlassSurface`), `RovaMotion`, an additive type scale, two new static gates, ADR-0028, and per-theme contrast tests — proven on the App Settings surface and the Wave-1 theme picker, with **zero visual regression anywhere else**.

**Architecture:** Themes are a flat selection (`FOLLOW_SYSTEM` + 12 named palettes); each palette supplies background + glass tint + edges + accent gradient + text alphas, while semantic colors are locked identical across all themes. Glass is role-aware: a `LocalGlassEnvironment {palette, apiLevel, reduceTransparency}` is seeded once high in `RovaTheme`; call sites resolve a concrete `GlassMaterial` via the pure `GlassResolver.resolve(env, role)` and render through `GlassSurface(role=…)`. Degradation (API < 31, Reduce-Transparency, `RecordChrome`) is first-class in the resolver: heavier solid tint, no blur. PR1 wires the selection→palette→dark path and seeds the env, but only the App Settings proof surface renders through `GlassSurface`; all other surfaces keep their current `DarkColorScheme`/`LightColorScheme` rendering untouched.

**Tech Stack:** Kotlin 2.2.10, Jetpack Compose (BOM 2025.01.01, Material3), AGP 9.2.1 / Gradle 9.4.1, minSdk 24 / compile+target 37, JVM unit tests only (`isReturnDefaultValues = true`), pure-helper extraction pattern, regex static gates wired into `preBuild`.

**Source of truth:** `docs/superpowers/specs/2026-06-07-liquid-glass-ui-sweep-design.md`. Palette hex values are extracted from the gitignored mockup `rova_design_system_round2.html` and inlined below (mockups are not committed; values that matter live here and in source).

---

## File Structure

**New source files** (all under `app/src/main/java/com/aritr/rova/`):
- `ui/theme/ThemeSelection.kt` — the flat selection enum + Wave-1 list + follow-system resolution.
- `ui/theme/ThemeMigration.kt` — pure migration `ThemeMode`/raw-string → `ThemeSelection`.
- `ui/theme/RovaPalette.kt` — `@Immutable RovaPalette` data class, `RovaSemantics` object, the 12 palette vals, `rovaPalettes` registry, `resolvePalette(...)`.
- `ui/theme/GlassEnvironment.kt` — `GlassEnvironment`, `GlassRole`, `GlassMaterial`, `LocalGlassEnvironment`.
- `ui/theme/GlassResolver.kt` — pure `GlassResolver.resolve(env, role)`.
- `ui/theme/GlassSurface.kt` — the `GlassSurface(role=…)` composable wrapper (sole sanctioned blur site).
- `ui/theme/RovaMotion.kt` — centralized motion tokens.
- `ui/theme/RovaType.kt` — additive named type-scale `TextStyle`s (does NOT replace global `Typography`).

**New test files** (under `app/src/test/java/com/aritr/rova/ui/theme/`):
- `ThemeSelectionTest.kt`, `ThemeMigrationTest.kt`, `PaletteRegistryTest.kt`, `GlassResolverTest.kt`, `RovaMotionTest.kt`, `ThemeContrastTest.kt`.

**Modified files:**
- `app/build.gradle.kts` — register `checkRecordSurfaceNoBlur` + `checkGlassSurfaceRoleUsage`, wire both into `preBuild`.
- `app/src/main/java/com/aritr/rova/data/RovaSettings.kt` — add `themeSelection` getter/setter (sync prefs, migration-on-read).
- `app/src/main/java/com/aritr/rova/ui/screens/SettingsViewModel.kt` — add `themeSelection` StateFlow + persistence.
- `app/src/main/java/com/aritr/rova/ui/theme/Theme.kt` — `RovaTheme` seeds `LocalGlassEnvironment`.
- `app/src/main/java/com/aritr/rova/MainActivity.kt` — resolve palette from `themeSelection`, pass into `RovaTheme`.
- `app/src/main/java/com/aritr/rova/ui/screens/SettingsScreen.kt` — appearance row + Wave-1 `ThemeSelection` sheet; wrap one Settings card in `GlassSurface` (proof).
- `app/src/main/res/values/strings.xml` + `app/src/main/res/values-es/strings.xml` — new theme-selection strings (en + es).
- `docs/adr/0028-liquid-glass-design-system.md` — new ADR.

**Build/verify discipline (carry-over):** the CONTROLLER runs ALL gradle. `gradlew.bat --stop` first; kill stray java; the post-edit hook's gradle noise (detekt-not-a-task / daemon-stopped / cache IOExceptions) is EXPECTED. **Verify with `:app:assembleDebug` and `:app:testDebugUnitTest`, NOT `lintDebug`** (RED on pre-existing B5 `VaultAndroidOps` NewApi, unrelated). **Never edit a `check*` gate to make it green.** Commit only when asked.

---

## Task 0: Preflight — inventory existing `Modifier.blur` + confirm real record-chrome filenames

**Files:** none (read-only investigation). This task de-risks the two new gates (Task 11): a gate that lands while existing code already violates it fails the build immediately, and a record-chrome gate keyed on guessed filenames is vacuous.

- [ ] **Step 1: Inventory every existing `Modifier.blur` / `.blur(` under `ui/`**

Run (controller, read-only):
```bash
grep -rnE "\.blur\s*\(|Modifier\s*\.\s*blur" app/src/main/java/com/aritr/rova/ui
```
Expected today: at least `DualPreviewZone.kt` (the documented carve-out). **Record the full list.** For each hit decide: (a) it's `DualPreviewZone.kt` → already allowlisted; (b) it's a record-chrome file → must move to `GlassRole.RecordChrome` (out of scope for PR1 → **add that file to the `checkGlassSurfaceRoleUsage` allowlist with a `// TODO(PRn): migrate to GlassSurface` and note it**, do not silently break it); (c) anything else unexpected → surface to the owner before writing the gate. **If the only hit is `DualPreviewZone.kt`, both gates are safe to land as written in Task 11.**

- [ ] **Step 2: Confirm the actual record-chrome filenames**

Run:
```bash
git ls-files "app/src/main/java/com/aritr/rova/ui/**" | grep -iE "record|chrome|hud"
```
Compare the result against the `recordChromeNames` set hardcoded in Task 11 Step 1 (`RecordScreen.kt, RecordChrome.kt, RecordHud.kt, RecordControls.kt`). **If the real filenames differ, update that set in Task 11 to the real list before registering the gate** — otherwise `checkRecordSurfaceNoBlur` scans nothing and silently passes.

- [ ] **Step 3: Record findings inline**

**FINDINGS (resolved 2026-06-07, baked into Task 11 below):**
- Existing `Modifier.blur` under `ui/`: `ui/warnings/WarningSheetV3.kt:260` (`.blur(RovaWarningsV3.sheetIconGlowBlur)`) and `ui/recovery/RecoveryCard.kt:124` (`.blur(RovaWarningsV3.recoveryCardGlowBlur)`). Both are **pre-existing decorative icon-glow bloom** (not glass, not record chrome) → **added to the `checkGlassSurfaceRoleUsage` allowlist** with a migration TODO (revisited when PR6 warnings / PR9 recovery migrate).
- `ui/screens/DualPreviewZone.kt` uses `RenderEffect.createBlurEffect` (not `Modifier.blur`) on the non-recorded framing margins — the ADR carve-out; in `ui/` so explicitly allowlisted.
- Real record-chrome rendering files: **`RecordScreen.kt`, `RecordChrome.kt`, `RecordChromeIcons.kt`** (NOT `RecordHud.kt`/`RecordControls.kt` — those don't exist). These three currently contain **no** `Modifier.blur`/`RenderEffect`, so `checkRecordSurfaceNoBlur` lands green. DualPreviewZone is deliberately NOT in the record-chrome scan set (it's the preview, the carve-out — not chrome).

No commit (read-only task).

---

## Task 1: `ThemeSelection` enum + Wave-1 list + follow-system resolution

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/theme/ThemeSelection.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/theme/ThemeSelectionTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// app/src/test/java/com/aritr/rova/ui/theme/ThemeSelectionTest.kt
package com.aritr.rova.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeSelectionTest {

    @Test
    fun `enum has follow-system plus twelve palettes in declared order`() {
        assertEquals(13, ThemeSelection.entries.size)
        assertEquals(ThemeSelection.FOLLOW_SYSTEM, ThemeSelection.entries.first())
        assertEquals(ThemeSelection.AURORA, ThemeSelection.DEFAULT)
    }

    @Test
    fun `wave1 picker list is follow-system plus the signature six in order`() {
        assertEquals(
            listOf(
                ThemeSelection.FOLLOW_SYSTEM,
                ThemeSelection.AURORA,
                ThemeSelection.TIDE,
                ThemeSelection.JADE,
                ThemeSelection.DUSK,
                ThemeSelection.ECLIPSE,
                ThemeSelection.DAYLIGHT,
            ),
            ThemeSelection.wave1Picker,
        )
    }

    @Test
    fun `follow-system resolves to aurora in dark and daylight in light`() {
        assertEquals(ThemeSelection.AURORA, ThemeSelection.FOLLOW_SYSTEM.resolveConcrete(systemDark = true))
        assertEquals(ThemeSelection.DAYLIGHT, ThemeSelection.FOLLOW_SYSTEM.resolveConcrete(systemDark = false))
    }

    @Test
    fun `a concrete selection resolves to itself regardless of system`() {
        assertEquals(ThemeSelection.TIDE, ThemeSelection.TIDE.resolveConcrete(systemDark = true))
        assertEquals(ThemeSelection.TIDE, ThemeSelection.TIDE.resolveConcrete(systemDark = false))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.theme.ThemeSelectionTest"`
Expected: FAIL — `Unresolved reference: ThemeSelection`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
// app/src/main/java/com/aritr/rova/ui/theme/ThemeSelection.kt
package com.aritr.rova.ui.theme

/**
 * Flat theme selection: Follow-System plus 12 named palettes (ADR-0028 §1).
 * Declaration order is load-bearing — Wave-1 (the signature six) are the first
 * six palettes after [FOLLOW_SYSTEM]; Wave-2 (extended six) are defined here but
 * not yet exposed in the picker.
 */
enum class ThemeSelection {
    FOLLOW_SYSTEM,
    AURORA, TIDE, JADE, DUSK, ECLIPSE, DAYLIGHT,        // Wave 1 (Signature 6)
    BLOSSOM, CORAL, MEADOW, COBALT, ORCHID, GRAPHITE;   // Wave 2 (Extended 6)

    /**
     * Resolve [FOLLOW_SYSTEM] to a concrete palette using the OS dark flag
     * (dark → Aurora, light → Daylight). A concrete selection returns itself.
     */
    fun resolveConcrete(systemDark: Boolean): ThemeSelection = when (this) {
        FOLLOW_SYSTEM -> if (systemDark) AURORA else DAYLIGHT
        else -> this
    }

    companion object {
        /** Default when no preference is stored. */
        val DEFAULT: ThemeSelection = AURORA

        /** Options surfaced in the picker in PR1 (Follow-System + Wave-1). */
        val wave1Picker: List<ThemeSelection> = listOf(
            FOLLOW_SYSTEM, AURORA, TIDE, JADE, DUSK, ECLIPSE, DAYLIGHT,
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.theme.ThemeSelectionTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/theme/ThemeSelection.kt app/src/test/java/com/aritr/rova/ui/theme/ThemeSelectionTest.kt
git commit -m "feat(theme): ThemeSelection flat-12 + Follow-System enum (ADR-0028)"
```

---

## Task 2: `ThemeMigration` — pure `ThemeMode`/raw-string → `ThemeSelection`

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/theme/ThemeMigration.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/theme/ThemeMigrationTest.kt`

Existing fact: `ThemeMode { SYSTEM, DARK, LIGHT }` in `ui/theme/ThemeMode.kt`; the shipped key is `theme_mode` (stored as `ThemeMode.name`).

- [ ] **Step 1: Write the failing test**

```kotlin
// app/src/test/java/com/aritr/rova/ui/theme/ThemeMigrationTest.kt
package com.aritr.rova.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeMigrationTest {

    @Test
    fun `system migrates to follow-system`() {
        assertEquals(ThemeSelection.FOLLOW_SYSTEM, ThemeMigration.migrate("SYSTEM"))
    }

    @Test
    fun `dark migrates to aurora`() {
        assertEquals(ThemeSelection.AURORA, ThemeMigration.migrate("DARK"))
    }

    @Test
    fun `light migrates to daylight`() {
        assertEquals(ThemeSelection.DAYLIGHT, ThemeMigration.migrate("LIGHT"))
    }

    @Test
    fun `missing or unknown migrates to follow-system`() {
        assertEquals(ThemeSelection.FOLLOW_SYSTEM, ThemeMigration.migrate(null))
        assertEquals(ThemeSelection.FOLLOW_SYSTEM, ThemeMigration.migrate(""))
        assertEquals(ThemeSelection.FOLLOW_SYSTEM, ThemeMigration.migrate("PURPLE"))
    }

    @Test
    fun `resolve prefers a stored new-key selection over migrating the old key`() {
        // New key present and valid -> use it verbatim.
        assertEquals(
            ThemeSelection.TIDE,
            ThemeMigration.resolve(newRaw = "TIDE", oldRaw = "DARK"),
        )
        // New key absent -> migrate the old key.
        assertEquals(
            ThemeSelection.AURORA,
            ThemeMigration.resolve(newRaw = null, oldRaw = "DARK"),
        )
        // Neither present -> default.
        assertEquals(
            ThemeSelection.FOLLOW_SYSTEM,
            ThemeMigration.resolve(newRaw = null, oldRaw = null),
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.theme.ThemeMigrationTest"`
Expected: FAIL — `Unresolved reference: ThemeMigration`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
// app/src/main/java/com/aritr/rova/ui/theme/ThemeMigration.kt
package com.aritr.rova.ui.theme

/**
 * Pure, deterministic migration from the shipped `theme_mode` (#80,
 * ThemeMode.name) to the new `theme_selection` (ThemeSelection.name). Runs in
 * the synchronous SharedPreferences read path so the first frame never flashes a
 * default (ADR-0028 §1.2). The old key is left untouched for one release.
 */
object ThemeMigration {

    /** Old `theme_mode` raw string -> new selection. */
    fun migrate(oldRaw: String?): ThemeSelection = when (oldRaw) {
        "SYSTEM" -> ThemeSelection.FOLLOW_SYSTEM
        "DARK" -> ThemeSelection.AURORA
        "LIGHT" -> ThemeSelection.DAYLIGHT
        else -> ThemeSelection.FOLLOW_SYSTEM
    }

    /** Parse a stored new-key value, tolerant of null/unknown. */
    private fun parseNew(newRaw: String?): ThemeSelection? =
        ThemeSelection.entries.firstOrNull { it.name == newRaw }

    /**
     * Resolve the effective selection: prefer a valid new-key value; otherwise
     * migrate the old key; otherwise [ThemeSelection.DEFAULT]'s follow-system
     * fallback ([migrate] of null).
     */
    fun resolve(newRaw: String?, oldRaw: String?): ThemeSelection =
        parseNew(newRaw) ?: migrate(oldRaw)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.theme.ThemeMigrationTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/theme/ThemeMigration.kt app/src/test/java/com/aritr/rova/ui/theme/ThemeMigrationTest.kt
git commit -m "feat(theme): pure ThemeMigration theme_mode->theme_selection (ADR-0028)"
```

---

## Task 3: `RovaSettings.themeSelection` persistence (sync prefs seam)

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/data/RovaSettings.kt`

`RovaSettings` is the framework-touching seam (`Context.getSharedPreferences`) — its logic is delegated to the pure `ThemeMigration` (already tested in Task 2), so it gets no JVM test of its own (house pattern: thin seam over a tested pure helper). Mirror the existing `themeMode` property exactly.

Existing code in `RovaSettings.kt` (for reference — do not duplicate):
```kotlin
private val prefs = context.getSharedPreferences("rova_settings", Context.MODE_PRIVATE)
// ...
var themeMode: ThemeMode
    get() = themeModeFromStorage(prefs.getString("theme_mode", null))
    set(value) = prefs.edit { putString("theme_mode", value.name) }
```

- [ ] **Step 1: Add the import and property**

Add the import near the other `ui.theme` imports at the top of `RovaSettings.kt`:

```kotlin
import com.aritr.rova.ui.theme.ThemeMigration
import com.aritr.rova.ui.theme.ThemeSelection
```

Add this property immediately after the existing `themeMode` property:

```kotlin
/**
 * Liquid-glass theme selection (ADR-0028 §1.2). Read path is migration-aware:
 * a stored `theme_selection` wins; absent that, the legacy `theme_mode` is
 * migrated (the old key is left intact for one-release rollback safety).
 * Synchronous SharedPreferences — resolved before the theme StateFlow inits so
 * the first frame never flashes the default.
 */
var themeSelection: ThemeSelection
    get() = ThemeMigration.resolve(
        newRaw = prefs.getString("theme_selection", null),
        oldRaw = prefs.getString("theme_mode", null),
    )
    set(value) = prefs.edit { putString("theme_selection", value.name) }
```

- [ ] **Step 2: Verify it compiles (controller runs gradle)**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. (No new test — the pure logic is covered by `ThemeMigrationTest`.)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/aritr/rova/data/RovaSettings.kt
git commit -m "feat(theme): RovaSettings.themeSelection sync-prefs seam w/ migration-on-read (ADR-0028)"
```

---

## Task 4: `RovaSemantics` + `RovaPalette` type + 12 palettes + registry

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/theme/RovaPalette.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/theme/PaletteRegistryTest.kt`

Palette values (from `rova_design_system_round2.html`): shared dark-base tokens `txt=white .94 / dim .62 / faint .40`, `glass=rgba(18,22,32,.58)`, `edge=white .10`, `edgeTop=white .18`; locked semantics `success #34d399, warning #fbbf24, error #ef4444, escalating #f97316, rec #ff4d4d`. Per-palette accent pair + base tint listed inline below. Eclipse (OLED) and Daylight (light) carry overrides.

- [ ] **Step 1: Write the failing test**

```kotlin
// app/src/test/java/com/aritr/rova/ui/theme/PaletteRegistryTest.kt
package com.aritr.rova.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteRegistryTest {

    @Test
    fun `every concrete selection has a palette`() {
        ThemeSelection.entries
            .filter { it != ThemeSelection.FOLLOW_SYSTEM }
            .forEach { sel -> assertNotNull("missing palette for $sel", rovaPalettes[sel]) }
        assertEquals(12, rovaPalettes.size)
    }

    @Test
    fun `resolvePalette maps follow-system by the OS dark flag`() {
        assertEquals(rovaPalettes.getValue(ThemeSelection.AURORA), resolvePalette(ThemeSelection.FOLLOW_SYSTEM, systemDark = true))
        assertEquals(rovaPalettes.getValue(ThemeSelection.DAYLIGHT), resolvePalette(ThemeSelection.FOLLOW_SYSTEM, systemDark = false))
        assertEquals(rovaPalettes.getValue(ThemeSelection.TIDE), resolvePalette(ThemeSelection.TIDE, systemDark = true))
    }

    @Test
    fun `semantics are locked and identical for all palettes`() {
        assertEquals(Color(0xFF34D399), RovaSemantics.success)
        assertEquals(Color(0xFFFBBF24), RovaSemantics.warning)
        assertEquals(Color(0xFFEF4444), RovaSemantics.error)
        assertEquals(Color(0xFFF97316), RovaSemantics.escalating)
        assertEquals(Color(0xFFFF4D4D), RovaSemantics.rec)
    }

    @Test
    fun `only daylight is light`() {
        rovaPalettes.forEach { (sel, p) ->
            assertEquals("isLight wrong for $sel", sel == ThemeSelection.DAYLIGHT, p.isLight)
        }
    }

    @Test
    fun `aurora is the default palette id`() {
        assertEquals(ThemeSelection.AURORA, rovaPalettes.getValue(ThemeSelection.AURORA).id)
        assertTrue(rovaPalettes.containsKey(ThemeSelection.DEFAULT))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.theme.PaletteRegistryTest"`
Expected: FAIL — `Unresolved reference: RovaSemantics` / `rovaPalettes`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
// app/src/main/java/com/aritr/rova/ui/theme/RovaPalette.kt
package com.aritr.rova.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Locked semantic colors (ADR-0028 §1.3) — identical across all 12 palettes.
 * Sourced from the warnings v2/v3 token set.
 */
@Immutable
object RovaSemantics {
    val success: Color = Color(0xFF34D399)
    val warning: Color = Color(0xFFFBBF24)
    val error: Color = Color(0xFFEF4444)
    val escalating: Color = Color(0xFFF97316)
    val rec: Color = Color(0xFFFF4D4D)
}

/**
 * A single named palette (ADR-0028 §1.3). Supplies only background + glass tint
 * + edges + accent gradient + text alphas + the pinned-dark accent companions.
 * Semantic colors live outside the palette in [RovaSemantics] (locked).
 */
@Immutable
data class RovaPalette(
    val id: ThemeSelection,
    val background: Brush,            // page/scene fill
    val glassTint: Color,            // base surface tint (before role/fallback adjustment)
    val edge: Color,
    val edgeTop: Color,
    val accent: Color,
    val accent2: Color,              // gradient companion stop
    val textHigh: Color,
    val textDim: Color,
    val textFaint: Color,
    val accentOnDark: Color,         // contrast-checked accent for pinned-dark routes (§2.4)
    val accentContainerOnDark: Color,
    val isLight: Boolean,            // drives status-bar icon polarity
)

// --- shared dark-base tokens (mockup `.t` defaults) ---
private val DarkTextHigh = Color(0xF0FFFFFF)   // white .94
private val DarkTextDim = Color(0x9EFFFFFF)    // white .62
private val DarkTextFaint = Color(0x66FFFFFF)  // white .40
private val DarkGlass = Color(0x94121620)      // rgba(18,22,32,.58)
private val DarkEdge = Color(0x1AFFFFFF)       // white .10
private val DarkEdgeTop = Color(0x2EFFFFFF)    // white .18

/** Build a standard dark palette; accent doubles as accentOnDark (legible on neutral-dark). */
private fun darkPalette(
    id: ThemeSelection,
    bgTop: Long,
    bgBottom: Long,
    accent: Color,
    accent2: Color,
): RovaPalette = RovaPalette(
    id = id,
    background = Brush.verticalGradient(listOf(Color(bgTop), Color(bgBottom))),
    glassTint = DarkGlass,
    edge = DarkEdge,
    edgeTop = DarkEdgeTop,
    accent = accent,
    accent2 = accent2,
    textHigh = DarkTextHigh,
    textDim = DarkTextDim,
    textFaint = DarkTextFaint,
    accentOnDark = accent,
    accentContainerOnDark = accent.copy(alpha = 0.22f),
    isLight = false,
)

// --- Wave 1 (Signature 6) ---
private val Aurora = darkPalette(ThemeSelection.AURORA, 0xFF2C3658, 0xFF141622, Color(0xFF5B9DFF), Color(0xFF7C5BFF))
private val Tide = darkPalette(ThemeSelection.TIDE, 0xFF16323A, 0xFF0E1A1F, Color(0xFF34D3C0), Color(0xFF2AA3FF))
private val Jade = darkPalette(ThemeSelection.JADE, 0xFF13322A, 0xFF0C1C18, Color(0xFF34D399), Color(0xFF059E6B))
private val Dusk = darkPalette(ThemeSelection.DUSK, 0xFF3A241C, 0xFF1F1310, Color(0xFFFF8A4C), Color(0xFFFF5FA2))

/** Eclipse — pure-black OLED. #000 hides elevation, so its edges carry the depth (§1.3). */
private val Eclipse = RovaPalette(
    id = ThemeSelection.ECLIPSE,
    background = Brush.verticalGradient(listOf(Color(0xFF050608), Color(0xFF000000))),
    glassTint = Color(0xD608090C),       // rgba(8,9,12,.84)
    edge = Color(0x14FFFFFF),            // white .08
    edgeTop = Color(0x1FFFFFFF),         // white .12
    accent = Color(0xFF5B9DFF),
    accent2 = Color(0xFF6F8CFF),
    textHigh = DarkTextHigh,
    textDim = DarkTextDim,
    textFaint = DarkTextFaint,
    accentOnDark = Color(0xFF5B9DFF),
    accentContainerOnDark = Color(0xFF5B9DFF).copy(alpha = 0.22f),
    isLight = false,
)

/** Daylight — the only light palette; ink-on-light text + bright glass. */
private val Daylight = RovaPalette(
    id = ThemeSelection.DAYLIGHT,
    background = Brush.verticalGradient(listOf(Color(0xFFE3E9F5), Color(0xFFF4F1EA))),
    glassTint = Color(0xA8FFFFFF),       // rgba(255,255,255,.66)
    edge = Color(0x12141A28),            // rgba(20,26,40,.07)
    edgeTop = Color(0xD9FFFFFF),         // white .85
    accent = Color(0xFF5B7FFF),
    accent2 = Color(0xFF7C5BFF),
    textHigh = Color(0xF2141A28),        // rgba(20,26,40,.95)
    textDim = Color(0x99141A28),         // .60
    textFaint = Color(0x6B141A28),       // .42
    accentOnDark = Color(0xFF5B7FFF),    // legible on the shared neutral-dark base
    accentContainerOnDark = Color(0xFF5B7FFF).copy(alpha = 0.22f),
    isLight = true,
)

// --- Wave 2 (Extended 6) — defined now, exposed in a later PR ---
private val Blossom = darkPalette(ThemeSelection.BLOSSOM, 0xFF3A2238, 0xFF1E1220, Color(0xFFFF8FC7), Color(0xFFFF6FAE))
private val Coral = darkPalette(ThemeSelection.CORAL, 0xFF3A2820, 0xFF1F1510, Color(0xFFFFB24D), Color(0xFFFF6A8B))
private val Meadow = darkPalette(ThemeSelection.MEADOW, 0xFF22301A, 0xFF12190E, Color(0xFF9AE65C), Color(0xFF34D3C0))
private val Cobalt = darkPalette(ThemeSelection.COBALT, 0xFF1C2350, 0xFF0E1230, Color(0xFF6F8CFF), Color(0xFF3A45C4))
private val Orchid = darkPalette(ThemeSelection.ORCHID, 0xFF3A2030, 0xFF1E1019, Color(0xFFFB7185), Color(0xFFA855F7))
private val Graphite = darkPalette(ThemeSelection.GRAPHITE, 0xFF1A1C20, 0xFF0E0F12, Color(0xFFD2D6DE), Color(0xFF8A8F99))

/** The full registry — every concrete [ThemeSelection] maps to exactly one palette. */
val rovaPalettes: Map<ThemeSelection, RovaPalette> = mapOf(
    ThemeSelection.AURORA to Aurora,
    ThemeSelection.TIDE to Tide,
    ThemeSelection.JADE to Jade,
    ThemeSelection.DUSK to Dusk,
    ThemeSelection.ECLIPSE to Eclipse,
    ThemeSelection.DAYLIGHT to Daylight,
    ThemeSelection.BLOSSOM to Blossom,
    ThemeSelection.CORAL to Coral,
    ThemeSelection.MEADOW to Meadow,
    ThemeSelection.COBALT to Cobalt,
    ThemeSelection.ORCHID to Orchid,
    ThemeSelection.GRAPHITE to Graphite,
)

/** Resolve a selection (Follow-System included) to a concrete palette. */
fun resolvePalette(selection: ThemeSelection, systemDark: Boolean): RovaPalette =
    rovaPalettes.getValue(selection.resolveConcrete(systemDark))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.theme.PaletteRegistryTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/theme/RovaPalette.kt app/src/test/java/com/aritr/rova/ui/theme/PaletteRegistryTest.kt
git commit -m "feat(theme): RovaPalette x12 + locked RovaSemantics + registry (ADR-0028)"
```

---

## Task 5: `GlassEnvironment` + `GlassRole` + `GlassMaterial` + `LocalGlassEnvironment`

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/theme/GlassEnvironment.kt`

No standalone test (these are plain data holders + a CompositionLocal; behavior is tested via `GlassResolver` in Task 6). Verify by compile.

- [ ] **Step 1: Write the implementation**

```kotlin
// app/src/main/java/com/aritr/rova/ui/theme/GlassEnvironment.kt
package com.aritr.rova.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The ambient glass environment (ADR-0028 §2.1), seeded once high in
 * [RovaTheme]. Stable: recomposes only on theme / reduce-transparency / config
 * change — all rare. Call sites read this via [LocalGlassEnvironment] and
 * resolve a concrete [GlassMaterial] through [GlassResolver].
 */
@Immutable
data class GlassEnvironment(
    val palette: RovaPalette,
    val apiLevel: Int,
    val reduceTransparency: Boolean,
)

/** The kind of surface a glass element backs (ADR-0028 §2.1). */
enum class GlassRole { RecordChrome, BottomSheet, Dialog, Card, NavBar, Banner }

/** The concrete material a role resolves to in a given environment. */
@Immutable
data class GlassMaterial(
    val fill: Color,
    val blurRadius: Dp,
    val edge: Color,
    val edgeTop: Color,
    val scrim: Brush?,
)

/**
 * Default environment for previews / un-provided trees: Aurora, a modern API
 * level, transparency allowed. Real trees override this in [RovaTheme].
 */
val LocalGlassEnvironment = staticCompositionLocalOf {
    GlassEnvironment(
        palette = rovaPalettes.getValue(ThemeSelection.AURORA),
        apiLevel = 31,
        reduceTransparency = false,
    )
}

/** Shared zero-blur constant for the no-blur paths. */
val NoBlur: Dp = 0.dp
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/theme/GlassEnvironment.kt
git commit -m "feat(theme): GlassEnvironment/GlassRole/GlassMaterial + LocalGlassEnvironment (ADR-0028)"
```

---

## Task 6: `GlassResolver` — pure degradation logic

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/theme/GlassResolver.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/theme/GlassResolverTest.kt`

Rules (ADR-0028 §2.2): `blurRadius > 0` only when `apiLevel >= 31 && !reduceTransparency && role != RecordChrome`. Otherwise blur = 0 and `fill` is bumped to a heavier tint (≥ ~0.82 alpha; fully solid when `reduceTransparency`). `RecordChrome` gets a gradient scrim; non-record roles never get a scrim.

- [ ] **Step 1: Write the failing test**

```kotlin
// app/src/test/java/com/aritr/rova/ui/theme/GlassResolverTest.kt
package com.aritr.rova.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassResolverTest {

    private val aurora = rovaPalettes.getValue(ThemeSelection.AURORA)
    private fun env(api: Int, reduce: Boolean) = GlassEnvironment(aurora, api, reduce)

    @Test
    fun `api31 non-record non-reduced gets real blur and no scrim`() {
        val m = GlassResolver.resolve(env(31, false), GlassRole.BottomSheet)
        assertTrue("expected blur > 0", m.blurRadius.value > 0f)
        assertNull(m.scrim)
    }

    @Test
    fun `api30 forces zero blur and a heavier fill`() {
        val m = GlassResolver.resolve(env(30, false), GlassRole.BottomSheet)
        assertEquals(0f, m.blurRadius.value, 0f)
        assertTrue("fallback fill must be >= 0.82 alpha", m.fill.alpha >= 0.82f)
    }

    @Test
    fun `record chrome never blurs and always gets a scrim at any api`() {
        val hi = GlassResolver.resolve(env(34, false), GlassRole.RecordChrome)
        val lo = GlassResolver.resolve(env(24, false), GlassRole.RecordChrome)
        assertEquals(0f, hi.blurRadius.value, 0f)
        assertEquals(0f, lo.blurRadius.value, 0f)
        assertNotNull(hi.scrim)
        assertNotNull(lo.scrim)
        assertTrue("record fill must be heavy", hi.fill.alpha >= 0.82f)
    }

    @Test
    fun `reduce transparency forces a fully solid fill and no blur`() {
        val m = GlassResolver.resolve(env(34, true), GlassRole.Card)
        assertEquals(0f, m.blurRadius.value, 0f)
        assertEquals(1f, m.fill.alpha, 0f)
        assertNull(m.scrim)
    }

    @Test
    fun `reduce transparency beats record-chrome — solid fill, no scrim`() {
        // Accessibility opt-out must win even on the record surface (resolver
        // checks reduceTransparency before the RecordChrome branch).
        val m = GlassResolver.resolve(env(34, true), GlassRole.RecordChrome)
        assertEquals(0f, m.blurRadius.value, 0f)
        assertEquals(1f, m.fill.alpha, 0f)
        assertNull(m.scrim)
    }

    @Test
    fun `edges always come from the palette`() {
        val m = GlassResolver.resolve(env(31, false), GlassRole.NavBar)
        assertEquals(aurora.edge, m.edge)
        assertEquals(aurora.edgeTop, m.edgeTop)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.theme.GlassResolverTest"`
Expected: FAIL — `Unresolved reference: GlassResolver`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
// app/src/main/java/com/aritr/rova/ui/theme/GlassResolver.kt
package com.aritr.rova.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Pure resolution of a [GlassRole] to a concrete [GlassMaterial] in a given
 * [GlassEnvironment] (ADR-0028 §2.2). Framework-free → JVM-testable. The single
 * place the blur/fallback/scrim rules live; [GlassSurface] only renders the
 * output.
 */
object GlassResolver {

    private val DefaultBlur = 24.dp
    private const val FALLBACK_ALPHA = 0.86f   // heavier opaque-enough tint for no-blur paths
    private const val RECORD_ALPHA = 0.88f

    fun resolve(env: GlassEnvironment, role: GlassRole): GlassMaterial {
        val p = env.palette

        // Reduce-Transparency wins over EVERY role (incl. RecordChrome): fully
        // solid high-contrast surface, no blur, no scrim shimmer. Checked FIRST so
        // an accessibility opt-out is never overridden by a role-specific branch.
        if (env.reduceTransparency) {
            return GlassMaterial(
                fill = p.glassTint.copy(alpha = 1f),
                blurRadius = 0.dp,
                edge = p.edge,
                edgeTop = p.edgeTop,
                scrim = null,
            )
        }

        // RecordChrome (transparency allowed): heavy fill + gradient scrim + edge,
        // never blur (§2.2/§2.3) — depth via scrim, not backdrop blur.
        if (role == GlassRole.RecordChrome) {
            return GlassMaterial(
                fill = p.glassTint.atLeastAlpha(RECORD_ALPHA),
                blurRadius = 0.dp,
                edge = p.edge,
                edgeTop = p.edgeTop,
                scrim = recordScrim(),
            )
        }

        val blurAllowed = env.apiLevel >= 31   // reduceTransparency + RecordChrome already handled above

        return if (blurAllowed) {
            GlassMaterial(
                fill = p.glassTint,
                blurRadius = DefaultBlur,
                edge = p.edge,
                edgeTop = p.edgeTop,
                scrim = null,
            )
        } else {
            // API < 31: heavier translucent fill, no blur, edge highlight.
            GlassMaterial(
                fill = p.glassTint.atLeastAlpha(FALLBACK_ALPHA),
                blurRadius = 0.dp,
                edge = p.edge,
                edgeTop = p.edgeTop,
                scrim = null,
            )
        }
    }

    /** Bottom-anchored darkening scrim used behind record chrome. */
    private fun recordScrim(): Brush = Brush.verticalGradient(
        colorStops = arrayOf(
            0.0f to Color.Transparent,
            0.55f to Color.Black.copy(alpha = 0.22f),
            1.0f to Color.Black.copy(alpha = 0.55f),
        ),
    )

    /** Raise this color's alpha to at least [min], leaving heavier tints untouched. */
    private fun Color.atLeastAlpha(min: Float): Color =
        if (alpha >= min) this else copy(alpha = min)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.theme.GlassResolverTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/theme/GlassResolver.kt app/src/test/java/com/aritr/rova/ui/theme/GlassResolverTest.kt
git commit -m "feat(theme): pure GlassResolver degradation/scrim rules (ADR-0028)"
```

---

## Task 7: `GlassSurface` composable wrapper (sole sanctioned blur site)

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/theme/GlassSurface.kt`

This is the ONLY file (besides `DualPreviewZone.kt`) permitted to call `Modifier.blur` — enforced by `checkGlassSurfaceRoleUsage` (Task 11). It reads `LocalGlassEnvironment`, resolves the material, and renders fill + scrim + edge.

**Critical:** `Modifier.blur` blurs the composable's OWN children — so blur MUST be applied to a separate background layer, never to the `Box` that wraps `content` (else the text/icons inside blur too and become unreadable). The structure is three stacked full-size layers: a (possibly-blurred) fill layer at the back, a scrim+edge layer, then the sharp content layer on top. This is Compose's `Modifier.blur` (own-content blur), NOT a CSS `backdrop-filter` — it does not sample what's painted behind the surface; the resolver's fill+scrim sell the depth. No standalone JVM test (Compose UI rendering is out of JVM scope); blur/fallback behavior is covered by `GlassResolverTest`. Verify by compile.

- [ ] **Step 1: Write the implementation**

```kotlin
// app/src/main/java/com/aritr/rova/ui/theme/GlassSurface.kt
package com.aritr.rova.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Role-aware glass surface (ADR-0028 §2.1). Reads [LocalGlassEnvironment],
 * resolves a [GlassMaterial] via [GlassResolver], and renders, back-to-front:
 *   1. a fill layer (blurred ONLY when the resolver returned a non-zero radius),
 *   2. an optional scrim + edge layer,
 *   3. the sharp [content] layer (never blurred).
 *
 * This is the single sanctioned `Modifier.blur` site in the app (enforced by
 * `checkGlassSurfaceRoleUsage`). NOTE: Compose's `Modifier.blur` blurs a
 * composable's own pixels — it is NOT a CSS `backdrop-filter`; it cannot sample
 * what is painted behind the surface. Depth comes from fill + scrim + edge. The
 * blur is therefore confined to the back fill layer and MUST never touch the
 * content subtree.
 */
@Composable
fun GlassSurface(
    role: GlassRole,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    content: @Composable () -> Unit,
) {
    val env = LocalGlassEnvironment.current
    val material = GlassResolver.resolve(env, role)

    Box(modifier = modifier.clip(shape)) {
        // Layer 1 — fill (blurred only on the blur path; never wraps content).
        Box(
            Modifier
                .matchParentSize()
                .then(
                    if (material.blurRadius.value > 0f) Modifier.blur(material.blurRadius)
                    else Modifier,
                )
                .background(material.fill, shape),
        )
        // Layer 2 — scrim (if any) + edge highlight.
        Box(
            Modifier
                .matchParentSize()
                .then(material.scrim?.let { Modifier.background(it, shape) } ?: Modifier)
                .border(1.dp, material.edge, shape),
        )
        // Layer 3 — sharp content, on top, unblurred.
        content()
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/theme/GlassSurface.kt
git commit -m "feat(theme): GlassSurface role-aware wrapper, single blur site (ADR-0028)"
```

---

## Task 8: `RovaMotion` motion tokens

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/theme/RovaMotion.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/theme/RovaMotionTest.kt`

Centralized motion tokens (ADR-0028 §3.2): spring container `stiffness≈380, damping≈30`; chip/toggle 120ms; dock shrink 200ms; record pulse 1.8–2.2s. PR1 only DEFINES the tokens (no animated surface is added, so `checkA11yAnimationGated` is not triggered). A tiny test pins the numeric contract so later surface PRs can't silently drift them.

- [ ] **Step 1: Write the failing test**

```kotlin
// app/src/test/java/com/aritr/rova/ui/theme/RovaMotionTest.kt
package com.aritr.rova.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RovaMotionTest {

    @Test
    fun `container spring constants match the spec`() {
        assertEquals(380f, RovaMotion.containerStiffness, 0f)
        assertEquals(30f, RovaMotion.containerDamping, 0f)
    }

    @Test
    fun `standard durations match the spec`() {
        assertEquals(120, RovaMotion.chipToggleMs)
        assertEquals(200, RovaMotion.dockShrinkMs)
    }

    @Test
    fun `record pulse is in the 1800-2200ms band`() {
        assertTrue(RovaMotion.recordPulseMs in 1800..2200)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.theme.RovaMotionTest"`
Expected: FAIL — `Unresolved reference: RovaMotion`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
// app/src/main/java/com/aritr/rova/ui/theme/RovaMotion.kt
package com.aritr.rova.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring

/**
 * Centralized motion tokens (ADR-0028 §3.2). All animated surfaces consume
 * these so motion is consistent and can be reasoned about in one place. Every
 * looping/auto-playing use site MUST additionally gate on the reduced-motion
 * seam (`rememberReduceMotion()` / `ReducedMotion.isReduced`) per ADR-0020 —
 * enforced by `checkA11yAnimationGated`. PR1 only defines the tokens.
 */
object RovaMotion {
    const val containerStiffness: Float = 380f
    const val containerDamping: Float = 30f
    const val chipToggleMs: Int = 120
    const val dockShrinkMs: Int = 200
    const val recordPulseMs: Int = 2000   // within the 1.8–2.2s band

    /** Standard container transition spring. */
    fun <T> containerSpring(): SpringSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = containerStiffness,
    )
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.theme.RovaMotionTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/theme/RovaMotion.kt app/src/test/java/com/aritr/rova/ui/theme/RovaMotionTest.kt
git commit -m "feat(theme): RovaMotion centralized motion tokens (ADR-0028)"
```

---

## Task 9: `RovaType` additive type scale

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/theme/RovaType.kt`

Additive named `TextStyle`s mapped from the Inter ramp (ADR-0028 §3.1). **Does NOT replace the global `Typography`** in `Theme.kt` — replacing it would regress every surface, which PR1 forbids. Surfaces opt in to these styles as they migrate. Reuses the existing `Inter` font family (already declared in `ui/theme/`, used by `RovaTokens`). No standalone test (static `TextStyle` constants); verify by compile.

- [ ] **Step 1: Write the implementation**

```kotlin
// app/src/main/java/com/aritr/rova/ui/theme/RovaType.kt
package com.aritr.rova.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFeature
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextScaleConfig
import androidx.compose.ui.unit.sp

/**
 * Liquid-glass type scale (ADR-0028 §3.1): the Inter ramp as named roles.
 * Hierarchy by weight + size; de-emphasis is by ALPHA (text color), never a
 * second hue. All sizes are `sp` (dynamic type); no hard line heights.
 * Counters/timers use the `tnum` font feature. This is ADDITIVE — the global
 * `Typography` in Theme.kt is untouched; surfaces adopt these per migration.
 */
object RovaType {
    val display = TextStyle(fontFamily = Inter, fontWeight = FontWeight.W800, fontSize = 34.sp, letterSpacing = (-0.5).sp)
    val headline = TextStyle(fontFamily = Inter, fontWeight = FontWeight.W700, fontSize = 26.sp)
    val title = TextStyle(fontFamily = Inter, fontWeight = FontWeight.W600, fontSize = 20.sp)
    val subtitle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.W600, fontSize = 17.sp)
    val bodyLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.W400, fontSize = 15.sp)
    val body = TextStyle(fontFamily = Inter, fontWeight = FontWeight.W400, fontSize = 13.sp)
    val label = TextStyle(fontFamily = Inter, fontWeight = FontWeight.W600, fontSize = 12.sp)
    val caption = TextStyle(fontFamily = Inter, fontWeight = FontWeight.W600, fontSize = 10.sp, letterSpacing = 1.4.sp)

    /** Tabular-figures variant for counters/timers (no width jitter as digits change). */
    val counter = body.copy(fontFeatureSettings = "tnum")
}
```

> **Note:** if the `Inter` symbol's exact package differs, match the import already used in `RovaTokens.kt` (`val eyebrow = TextStyle(fontFamily = Inter, …)` — same `Inter`). Remove the unused `FontFeature`/`TextScaleConfig` imports if the IDE flags them; they are listed for completeness and the body only needs `Inter`, `TextStyle`, `FontWeight`, `sp`.

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. (If unused-import warnings appear, trim to `Inter, TextStyle, FontWeight, sp` only.)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/theme/RovaType.kt
git commit -m "feat(theme): RovaType additive Inter type scale (ADR-0028)"
```

---

## Task 10: Per-theme contrast tests

**Files:**
- Create: `app/src/test/java/com/aritr/rova/ui/theme/ThemeContrastTest.kt`

Asserts, for each of the 12 palettes × text/selected-control roles × fallback modes (NOT every screen × theme), that body text ≥ 4.5:1 and selected-chip/border ≥ 3:1 against that palette's own resolved surface — including the API<31 solid fallback and the Eclipse OLED surface (ADR-0028 §4). Uses the existing pure `ContrastMath`.

`ContrastMath` API (existing, `ui/theme/ContrastMath.kt`):
```kotlin
fun relativeLuminance(r: Int, g: Int, b: Int): Double
fun contrastRatio(lum1: Double, lum2: Double): Double
fun compositeAlphaOver(fr: Int, fg: Int, fb: Int, alpha: Double, br: Int, bg: Int, bb: Int): IntArray
fun contrastRatioForAlpha(fr: Int, fg: Int, fb: Int, alpha: Double, br: Int, bg: Int, bb: Int): Double
```

**Correctness note (codex):** the contrast MUST be computed against the *real composited* colors — both the glass fill (alpha over the scene) AND the text/accent (alpha over the resolved surface). Treating a translucent foreground as opaque OVERESTIMATES contrast on dark surfaces (white@0.94 composited is slightly darker than pure white → lower luminance → lower real contrast), which makes the test pass when the rendered UI would fail. So: surface = `compositeAlphaOver(fill, sceneBottom)`; ratio = `contrastRatioForAlpha(foreground, foregroundAlpha, surface)`.

- [ ] **Step 1: Write the test**

```kotlin
// app/src/test/java/com/aritr/rova/ui/theme/ThemeContrastTest.kt
package com.aritr.rova.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * Per-theme WCAG 2.2 AA contrast (ADR-0028 §4). For every palette we composite
 * the role's glass fill over the palette's darkest background stop to get the
 * REAL resolved surface, then composite each foreground (text/accent) over that
 * surface and assert text >= 4.5:1 and selected-control/border >= 3:1 — in both
 * the blur path and the API<31 solid-fallback path. All compositing uses the
 * existing ContrastMath so the test reflects rendered pixels, not opaque
 * approximations.
 */
class ThemeContrastTest {

    private fun rgb(c: Color) = Triple(
        (c.red * 255).roundToInt(),
        (c.green * 255).roundToInt(),
        (c.blue * 255).roundToInt(),
    )

    /** Darkest stop of a palette's vertical-gradient background (worst case for light text). */
    private fun sceneBottom(sel: ThemeSelection): Color = when (sel) {
        ThemeSelection.AURORA -> Color(0xFF141622)
        ThemeSelection.TIDE -> Color(0xFF0E1A1F)
        ThemeSelection.JADE -> Color(0xFF0C1C18)
        ThemeSelection.DUSK -> Color(0xFF1F1310)
        ThemeSelection.ECLIPSE -> Color(0xFF000000)
        ThemeSelection.DAYLIGHT -> Color(0xFFF4F1EA)
        ThemeSelection.BLOSSOM -> Color(0xFF1E1220)
        ThemeSelection.CORAL -> Color(0xFF1F1510)
        ThemeSelection.MEADOW -> Color(0xFF12190E)
        ThemeSelection.COBALT -> Color(0xFF0E1230)
        ThemeSelection.ORCHID -> Color(0xFF1E1019)
        ThemeSelection.GRAPHITE -> Color(0xFF0E0F12)
        ThemeSelection.FOLLOW_SYSTEM -> error("not a concrete palette")
    }

    /** Resolved surface = role fill (with its alpha) composited over the opaque scene bottom. */
    private fun resolvedSurface(fill: Color, sel: ThemeSelection): IntArray {
        val (fr, fg, fb) = rgb(fill)
        val (br, bg, bb) = rgb(sceneBottom(sel))
        return ContrastMath.compositeAlphaOver(fr, fg, fb, fill.alpha.toDouble(), br, bg, bb)
    }

    /** Contrast of a foreground (carrying its own alpha) over a resolved surface. */
    private fun ratioOver(fg: Color, surface: IntArray): Double {
        val (r, g, b) = rgb(fg)
        return ContrastMath.contrastRatioForAlpha(
            r, g, b, fg.alpha.toDouble(), surface[0], surface[1], surface[2],
        )
    }

    private val concrete = ThemeSelection.entries.filter { it != ThemeSelection.FOLLOW_SYSTEM }

    @Test
    fun `body text meets 4_5 to 1 over every palette surface (blur path)`() {
        concrete.forEach { sel ->
            val p = rovaPalettes.getValue(sel)
            val mat = GlassResolver.resolve(GlassEnvironment(p, 31, false), GlassRole.Card)
            val ratio = ratioOver(p.textHigh, resolvedSurface(mat.fill, sel))
            assertTrue("$sel body text ${"%.2f".format(ratio)}:1 < 4.5", ratio >= 4.5)
        }
    }

    @Test
    fun `body text meets 4_5 to 1 over the api30 solid-fallback surface`() {
        concrete.forEach { sel ->
            val p = rovaPalettes.getValue(sel)
            val mat = GlassResolver.resolve(GlassEnvironment(p, 30, false), GlassRole.Card)
            val ratio = ratioOver(p.textHigh, resolvedSurface(mat.fill, sel))
            assertTrue("$sel fallback body text ${"%.2f".format(ratio)}:1 < 4.5", ratio >= 4.5)
        }
    }

    @Test
    fun `selected-control accent meets 3 to 1 over every palette surface`() {
        concrete.forEach { sel ->
            val p = rovaPalettes.getValue(sel)
            val mat = GlassResolver.resolve(GlassEnvironment(p, 31, false), GlassRole.Card)
            // Accent is painted opaque on selected controls/borders.
            val ratio = ratioOver(p.accent.copy(alpha = 1f), resolvedSurface(mat.fill, sel))
            assertTrue("$sel accent ${"%.2f".format(ratio)}:1 < 3.0", ratio >= 3.0)
        }
    }
}
```

> **Implementation note for the executor:** scope is `textHigh` (body/high-emphasis) + selected-control `accent`; `textDim`/`textFaint` are de-emphasis tiers handled in their surface PRs. If a palette FAILS, the fix is to **adjust that palette's tokens in `RovaPalette.kt`** (raise `textHigh` alpha, darken the scene bottom stop, or pick a stronger `accent`) — NEVER weaken the assertion threshold. If `ContrastMath.compositeAlphaOver`'s exact return shape differs from `IntArray[r,g,b]`, adapt `resolvedSurface` to its real signature (confirmed in Task 10 prep by reading `ContrastMath.kt`) — the math (composite fill over scene, then foreground over that) stays identical.

- [ ] **Step 2: Run the test**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.theme.ThemeContrastTest"`
Expected: PASS (3 tests). If a palette fails, adjust that palette's tokens in `RovaPalette.kt` (Task 4) and re-run — do not edit the assertion thresholds.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/aritr/rova/ui/theme/ThemeContrastTest.kt
git commit -m "test(theme): per-theme WCAG AA contrast across palettes + fallback (ADR-0028)"
```

---

## Task 11: New static gates — `checkRecordSurfaceNoBlur` + `checkGlassSurfaceRoleUsage`

**Files:**
- Modify: `app/build.gradle.kts`

Follow the existing `checkA11yAnimationGated` pattern verbatim (regex scan, comment-line skip, `a11y-opt-out`-style allowlist, `GradleException` citing the ADR). Wire both into `preBuild`. **Use the blur inventory + real record-chrome filenames confirmed in Task 0** — if Task 0 found `Modifier.blur` in any non-allowlisted `ui/` file, that file must be added to the `checkGlassSurfaceRoleUsage` allowlist (with a migration TODO) BEFORE this gate lands, or the build breaks immediately; and the `recordChromeNames` set below must match the real filenames from Task 0 Step 2.

- **`checkRecordSurfaceNoBlur`** — scans the record-chrome files for `Modifier.blur` / `.blur(`; allowlists `DualPreviewZone.kt`. Invariant: record glass uses `GlassRole.RecordChrome`, whose resolver returns `blurRadius = 0` (ADR-0028 §2.3).
- **`checkGlassSurfaceRoleUsage`** — scans all of `ui/` for `.blur(`; permits it ONLY in the allowlist `{GlassSurface.kt, DualPreviewZone.kt}`, ensuring every blur goes through the resolver-driven `GlassSurface` (ADR-0028 §2.1/§5).

- [ ] **Step 1: Register `checkRecordSurfaceNoBlur`**

Add near the other `val checkXxx = tasks.register(...)` blocks in `app/build.gradle.kts`:

```kotlin
val checkRecordSurfaceNoBlur = tasks.register("checkRecordSurfaceNoBlur") {
    group = "verification"
    description = "Record-chrome files must not apply Modifier.blur — record glass uses GlassRole.RecordChrome (blurRadius=0). Allowlists DualPreviewZone (ADR-0028 §2.3)."
    val srcDir = file("src/main/java/com/aritr/rova")
    inputs.dir(srcDir).withPropertyName("rovaSources")
    doLast {
        if (!srcDir.exists()) {
            throw GradleException("checkRecordSurfaceNoBlur: Rova source dir missing: $srcDir")
        }
        // Record-chrome rendering files (confirmed real in Task 0). DualPreviewZone
        // is deliberately EXCLUDED — it's the camera preview/carve-out, not chrome.
        val recordChromeNames = setOf(
            "RecordScreen.kt", "RecordChrome.kt", "RecordChromeIcons.kt",
        )
        // Catch both Compose own-content blur AND RenderEffect/backdrop blur.
        val blurPattern = Regex("""\.blur\s*\(|Modifier\s*\.\s*blur\b|RenderEffect|createBlurEffect""")
        val offenders = srcDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name in recordChromeNames }
            .mapNotNull { f ->
                val hits = f.readLines().withIndex().filter { (_, line) ->
                    val t = line.trimStart()
                    if (t.startsWith("//") || t.startsWith("*")) false
                    else blurPattern.containsMatchIn(line)
                }
                if (hits.isEmpty()) null else f to hits
            }
            .toList()
        if (offenders.isNotEmpty()) {
            val report = offenders.joinToString("\n") { (f, hits) ->
                hits.joinToString("\n") { (i, line) -> "  ${f.relativeTo(rootDir)}:${i + 1}: ${line.trim()}" }
            }
            throw GradleException(
                "ADR-0028 §2.3 violation: record-chrome files must not blur. Record glass " +
                    "renders through GlassRole.RecordChrome, whose resolver returns " +
                    "blurRadius=0 (fill + scrim + edge + opaque text capsule instead). " +
                    "DualPreviewZone's RenderEffect on non-recorded margins is the only " +
                    "documented carve-out and is not record chrome.\nOffenders:\n$report",
            )
        }
    }
}
```

- [ ] **Step 2: Register `checkGlassSurfaceRoleUsage`**

```kotlin
val checkGlassSurfaceRoleUsage = tasks.register("checkGlassSurfaceRoleUsage") {
    group = "verification"
    description = "Modifier.blur is permitted only in GlassSurface.kt (the resolver-driven glass wrapper) and DualPreviewZone.kt (ADR-0028 carve-out). All other glass goes through GlassSurface(role=…)."
    val srcDir = file("src/main/java/com/aritr/rova/ui")
    inputs.dir(srcDir).withPropertyName("rovaUiSources")
    doLast {
        if (!srcDir.exists()) {
            throw GradleException("checkGlassSurfaceRoleUsage: Rova ui source dir missing: $srcDir")
        }
        // GlassSurface = sanctioned glass blur. DualPreviewZone = ADR carve-out
        // (RenderEffect on non-recorded margins). WarningSheetV3 + RecoveryCard =
        // pre-existing decorative icon-glow bloom (Task 0); TODO(PR6/PR9): revisit
        // when those surfaces migrate to GlassSurface.
        val allowlist = setOf(
            "GlassSurface.kt", "DualPreviewZone.kt", "WarningSheetV3.kt", "RecoveryCard.kt",
        )
        val blurPattern = Regex("""\.blur\s*\(|Modifier\s*\.\s*blur\b""")
        val offenders = srcDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name !in allowlist }
            .mapNotNull { f ->
                val hits = f.readLines().withIndex().filter { (_, line) ->
                    val t = line.trimStart()
                    if (t.startsWith("//") || t.startsWith("*")) false
                    else blurPattern.containsMatchIn(line)
                }
                if (hits.isEmpty()) null else f to hits
            }
            .toList()
        if (offenders.isNotEmpty()) {
            val report = offenders.joinToString("\n") { (f, hits) ->
                hits.joinToString("\n") { (i, line) -> "  ${f.relativeTo(rootDir)}:${i + 1}: ${line.trim()}" }
            }
            throw GradleException(
                "ADR-0028 §2.1/§5 violation: Modifier.blur outside the sanctioned glass " +
                    "wrapper. All translucent glass must render through " +
                    "GlassSurface(role=…) so the GlassResolver owns the blur/fallback " +
                    "decision. Permitted only in GlassSurface.kt and the documented " +
                    "DualPreviewZone.kt carve-out.\nOffenders:\n$report",
            )
        }
    }
}
```

- [ ] **Step 3: Wire both into `preBuild`**

In the existing `afterEvaluate { tasks.matching { it.name == "preBuild" }.configureEach { … } }` block, add two lines after the last existing `dependsOn(...)`:

```kotlin
        dependsOn(checkRecordSurfaceNoBlur)
        dependsOn(checkGlassSurfaceRoleUsage)
```

- [ ] **Step 4: Verify the gates run and pass (controller runs gradle)**

Run: `./gradlew :app:checkRecordSurfaceNoBlur :app:checkGlassSurfaceRoleUsage`
Expected: both BUILD SUCCESSFUL (no offenders — `GlassSurface.kt` is allowlisted; no record-chrome file blurs).

Then confirm preBuild wiring: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL (both gates execute as preBuild dependencies).

- [ ] **Step 5: Negative-fixture check (prove the record gate is not vacuous)**

Temporarily add a single line `val x = Modifier.blur(4.dp)` near the top of a real record-chrome file (the first name confirmed in Task 0 Step 2, e.g. `RecordScreen.kt`), then run:

Run: `./gradlew :app:checkRecordSurfaceNoBlur`
Expected: **FAIL** with the ADR-0028 §2.3 message citing that file:line. This proves the gate's `recordChromeNames` set actually matches a real file (a vacuous gate would pass here). **Revert the temporary line immediately** (`git checkout -- <file>`) and re-run to confirm GREEN before committing.

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle.kts
git commit -m "build(gate): checkRecordSurfaceNoBlur + checkGlassSurfaceRoleUsage preBuild gates (ADR-0028)"
```

---

## Task 12: ADR-0028 — Liquid-glass design system

**Files:**
- Create: `docs/adr/0028-liquid-glass-design-system.md`

Follow the ADR-0027 template (Status / Date / Context / Decision (numbered) / Enforcement / Consequences).

- [ ] **Step 1: Write the ADR**

```markdown
# ADR-0028: Liquid-glass design system — flat-12 themes, role-aware glass, locked semantics

Status: Accepted
Date: 2026-06-07

## Context

Rova is moving its entire UI to a cohesive 2027–28 liquid-glass language with a
12-theme personality system, a defined motion + type scale, and WCAG 2.2 AA held
in every theme. This is a presentation-only program (no reliability/behavior
change) delivered foundation-first: PR1 builds the design system; later PRs
migrate one surface each. Two hard platform facts shape the design: Compose has
no CSS `backdrop-filter`, so a translucent panel cannot blur the live camera
behind it; and `RenderEffect` blur requires API 31+ (minSdk is 24).

## Decision

1. **Themes are a flat selection.** `ThemeSelection { FOLLOW_SYSTEM, AURORA
   (default), TIDE, JADE, DUSK, ECLIPSE, DAYLIGHT, BLOSSOM, CORAL, MEADOW,
   COBALT, ORCHID, GRAPHITE }`. Wave-1 (the first six palettes) ships in the
   picker; all 12 are defined from PR1. Follow-System maps OS light→Daylight,
   OS dark→Aurora. Two-axis palette×mode theming is rejected (combinatorial QA).
2. **Migration from the shipped `ThemeMode` (#80) is pure + deterministic**,
   run in the synchronous SharedPreferences read path (no async DataStore) so
   the first frame never flashes a default: SYSTEM→FOLLOW_SYSTEM, DARK→AURORA,
   LIGHT→DAYLIGHT, missing/unknown→FOLLOW_SYSTEM. The new key `theme_selection`
   is written; the old `theme_mode` is left intact for one release (rollback).
3. **Palettes supply only** background + glass tint + edges + accent gradient +
   text alphas (+ pinned-dark accent companions). **Semantic colors are locked**
   in `RovaSemantics`, identical across all 12: success #34d399, warning #fbbf24,
   error #ef4444, escalating #f97316, rec #ff4d4d. Eclipse (OLED) carries depth
   in its edges because #000 hides elevation. Dynamic color / Material You is OFF.
4. **Glass is role-aware.** `LocalGlassEnvironment {palette, apiLevel,
   reduceTransparency}` is seeded once high in `RovaTheme`; call sites use
   `GlassSurface(role = GlassRole.X)` which resolves a concrete `GlassMaterial`
   via the pure `GlassResolver.resolve(env, role)`. NOT a tree-wide
   `isRecordSurface` flag — sheets/dialogs mount inside `RecordScreen` and must
   keep their own role.
5. **Degradation is first-class in the resolver.** `blurRadius > 0` only when
   `apiLevel >= 31 && !reduceTransparency && role != RecordChrome`; otherwise
   blur = 0 and the fill is bumped to a heavier opaque-enough tint (fully solid
   under Reduce-Transparency).
6. **No backdrop blur over the camera.** Record glass sells depth with fill +
   gradient scrim + edge + an opaque capsule behind critical text — never a
   backdrop blur (impossible in Compose). The existing
   `RenderEffect.createBlurEffect` in `DualPreviewZone.kt` blurs the
   *non-recorded framing margins*, not chrome, and is an explicit carve-out.
7. **Camera/media routes stay pinned dark in every theme** (Record, Player,
   Onboarding): one shared cinematic neutral-dark base; personality shows via a
   contrast-checked `accentOnDark` companion on meaningful controls only. App
   surfaces (Library/Settings/History/warnings/sheets/dialogs) carry the full
   active palette. The route-aware status-bar writer (`isPinnedDarkRoute`) flips
   icon polarity per transition.
8. **Motion + type are tokenized.** `RovaMotion` centralizes spring/duration
   tokens; every looping/auto-playing use site additionally gates on the
   reduced-motion seam (ADR-0020 / `checkA11yAnimationGated`). `RovaType` adds a
   named Inter ramp; de-emphasis is by alpha, never a second hue. The type scale
   is additive in PR1 — the global `Typography` is migrated per surface, not
   wholesale.
9. **Icons (PR11) and launcher/splash (PR12) are separate later PRs** — high
   blast radius. The themed launcher icon is a single static monochrome asset
   (cannot follow the in-app theme).

## Enforcement

- `checkRecordSurfaceNoBlur` (new preBuild gate): record-chrome files apply no
  `Modifier.blur`; allowlists `DualPreviewZone.kt`.
- `checkGlassSurfaceRoleUsage` (new preBuild gate): `Modifier.blur` is permitted
  only in `GlassSurface.kt` (sanctioned glass), the `DualPreviewZone.kt` carve-out,
  and the two pre-existing decorative icon-glow-bloom sites `WarningSheetV3.kt` /
  `RecoveryCard.kt` (allowlisted with a TODO to migrate when PR6/PR9 land) — all
  new glass goes through `GlassSurface(role=…)`/`GlassResolver`.
- Per-theme contrast tests (`ThemeContrastTest`, JVM): 12 palettes × text /
  selected-control roles × fallback modes, asserting body ≥ 4.5:1 and
  selected-control/border ≥ 3:1 against each palette's own resolved surface,
  including the API<31 solid fallback and the Eclipse OLED surface. Extends
  `ContrastMath` (ADR-0020 reuse).
- `GlassResolver`, `ThemeMigration`, `RovaMotion`, the palette registry, and
  `ThemeSelection` are pure-Kotlin and JVM-tested.
- Existing gates preserved unchanged.

## Consequences

- PR1 introduces no visual regression outside the App Settings proof surface:
  the selection→palette→dark path is wired and `LocalGlassEnvironment` is
  seeded, but only App Settings renders through `GlassSurface`; every other
  surface keeps its current `DarkColorScheme`/`LightColorScheme` rendering until
  its own migration PR.
- Wave-2 themes are a one-line picker change later (no enum work).
- The honest limit "no backdrop blur over the camera" is a permanent platform
  constraint encoded in the resolver and gates, not a temporary shortcut.
```

- [ ] **Step 2: Commit**

```bash
git add docs/adr/0028-liquid-glass-design-system.md
git commit -m "docs(adr): ADR-0028 liquid-glass design system"
```

---

## Task 13: Integration — seed `LocalGlassEnvironment` + resolve palette in `MainActivity` + `SettingsViewModel.themeSelection` + strings

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/theme/Theme.kt`
- Modify: `app/src/main/java/com/aritr/rova/MainActivity.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/SettingsViewModel.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-es/strings.xml`

This wires selection → palette → dark + seeds the env, **without changing any rendered colors** (Aurora→dark scheme, Daylight→light scheme — exactly the prior dark/light mapping). No JVM test (Compose wiring); verify by compile + the existing test suite staying green.

- [ ] **Step 1: `RovaTheme` seeds `LocalGlassEnvironment`**

In `ui/theme/Theme.kt`, add imports:

```kotlin
import android.os.Build
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import com.aritr.rova.ui.components.ReducedMotion
```

Change the `RovaTheme` signature to accept the resolved palette (default Aurora keeps existing call sites valid) and provide the env around `MaterialTheme`:

```kotlin
@Composable
fun RovaTheme(
    darkTheme: Boolean = true,
    lightStatusBarIcons: Boolean = !darkTheme,
    dynamicColor: Boolean = false,
    palette: RovaPalette = rovaPalettes.getValue(ThemeSelection.AURORA),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = lightStatusBarIcons
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = lightStatusBarIcons
        }
    }

    val context = LocalContext.current
    val glassEnv = GlassEnvironment(
        palette = palette,
        apiLevel = Build.VERSION.SDK_INT,
        reduceTransparency = ReducedMotion.isReduced(context),
    )

    CompositionLocalProvider(LocalGlassEnvironment provides glassEnv) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content,
        )
    }
}
```

> Note: `ReducedMotion.isReduced(context)` is the available pure seam (it reads the OS animation-scale toggle). It is reused here as the transparency-degradation proxy; a dedicated "reduce transparency" system read can replace it in a later PR without changing the resolver. This keeps PR1 dependency-free.

- [ ] **Step 2: `SettingsViewModel` gains `themeSelection`**

In `ui/screens/SettingsViewModel.kt`, add import and StateFlow mirroring the existing `themeMode` pattern:

```kotlin
import com.aritr.rova.ui.theme.ThemeSelection
```

Inside the class, add after the existing `themeMode` block:

```kotlin
val themeSelection = MutableStateFlow(settings.themeSelection)

init {
    viewModelScope.launch { themeSelection.collect { settings.themeSelection = it } }
}
```

> If the class already has an `init { … }` collecting `themeMode`, add the `themeSelection.collect { … }` line inside that same `init` block rather than declaring a second `init`.

- [ ] **Step 3: `MainActivity` resolves the palette and passes it in**

In `MainActivity.kt`, add imports:

```kotlin
import com.aritr.rova.ui.theme.resolvePalette
```

In `setContent`, replace the theme resolution to drive off `themeSelection`:

```kotlin
val settingsViewModel: SettingsViewModel = viewModel()
val themeSelection by settingsViewModel.themeSelection.collectAsStateWithLifecycle()
val systemDark = isSystemInDarkTheme()
val palette = resolvePalette(themeSelection, systemDark)
val dark = !palette.isLight
val navController = rememberNavController()
val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
val lightBars = if (isPinnedDarkRoute(currentRoute)) false else !dark
val secureController = remember { /* unchanged */ }
RovaTheme(darkTheme = dark, lightStatusBarIcons = lightBars, palette = palette) {
    // unchanged body
}
```

> `dark = !palette.isLight` reproduces the prior behavior exactly: Aurora (and every dark palette) → dark scheme; Daylight → light scheme; Follow-System → Aurora/Daylight by `systemDark`. No rendered color changes.

- [ ] **Step 4: Add strings (en)**

In `app/src/main/res/values/strings.xml`, add:

```xml
<string name="settings_theme_selection_follow_system">Follow system</string>
<string name="settings_theme_selection_follow_system_sub">Daylight / Aurora</string>
<string name="settings_theme_selection_aurora">Aurora</string>
<string name="settings_theme_selection_tide">Tide</string>
<string name="settings_theme_selection_jade">Jade</string>
<string name="settings_theme_selection_dusk">Dusk</string>
<string name="settings_theme_selection_eclipse">Eclipse</string>
<string name="settings_theme_selection_daylight">Daylight</string>
```

- [ ] **Step 5: Add strings (es)**

In `app/src/main/res/values-es/strings.xml`, add:

```xml
<string name="settings_theme_selection_follow_system">Seguir el sistema</string>
<string name="settings_theme_selection_follow_system_sub">Daylight / Aurora</string>
<string name="settings_theme_selection_aurora">Aurora</string>
<string name="settings_theme_selection_tide">Tide</string>
<string name="settings_theme_selection_jade">Jade</string>
<string name="settings_theme_selection_dusk">Dusk</string>
<string name="settings_theme_selection_eclipse">Eclipse</string>
<string name="settings_theme_selection_daylight">Daylight</string>
```

> Palette names are proper nouns — kept identical in es; only the Follow-system row is translated. This satisfies `checkNoHardcodedUiStrings` (Task 14 references these).

- [ ] **Step 6: Verify compile + full suite green**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; all tests pass (no regression in the ~1500 baseline + the new theme tests).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/theme/Theme.kt app/src/main/java/com/aritr/rova/MainActivity.kt app/src/main/java/com/aritr/rova/ui/screens/SettingsViewModel.kt app/src/main/res/values/strings.xml app/src/main/res/values-es/strings.xml
git commit -m "feat(theme): wire ThemeSelection->palette->RovaTheme + seed LocalGlassEnvironment (ADR-0028)"
```

---

## Task 14: Proof surface — App Settings theme picker (Wave-1) + one `GlassSurface` card

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/SettingsScreen.kt`

Swap the existing appearance theme row + sheet from `ThemeMode` to `ThemeSelection` (Wave-1 options), and wrap the Appearance section's content in a `GlassSurface(role = GlassRole.Card)` so the proof surface visibly reflects the active palette's glass tint. The rest of the screen is untouched.

Existing code (for reference): the appearance row uses `value = themeModeLabel(themeMode)` and `onClick = { openThemeSheet = true }`; the sheet is a `SettingsOptionSheet(options = ThemeMode.entries, selected = themeMode, optionLabel = {…}, onPick = { settingsViewModel.themeMode.value = it })`; and `themeModeLabel` maps the three modes to strings.

- [ ] **Step 1: Collect `themeSelection` and add a label helper**

Near the existing `val themeMode by settingsViewModel.themeMode.collectAsStateWithLifecycle()`, add:

```kotlin
val themeSelection by settingsViewModel.themeSelection.collectAsStateWithLifecycle()
```

Add a label helper next to the existing `themeModeLabel`:

```kotlin
@Composable
private fun themeSelectionLabel(sel: ThemeSelection): String = when (sel) {
    ThemeSelection.FOLLOW_SYSTEM -> stringResource(R.string.settings_theme_selection_follow_system)
    ThemeSelection.AURORA -> stringResource(R.string.settings_theme_selection_aurora)
    ThemeSelection.TIDE -> stringResource(R.string.settings_theme_selection_tide)
    ThemeSelection.JADE -> stringResource(R.string.settings_theme_selection_jade)
    ThemeSelection.DUSK -> stringResource(R.string.settings_theme_selection_dusk)
    ThemeSelection.ECLIPSE -> stringResource(R.string.settings_theme_selection_eclipse)
    ThemeSelection.DAYLIGHT -> stringResource(R.string.settings_theme_selection_daylight)
    // Wave-2 not yet surfaced; fall back to the enum name (never shown in PR1).
    else -> sel.name
}
```

Add the imports at the top of `SettingsScreen.kt`:

```kotlin
import com.aritr.rova.ui.theme.GlassRole
import com.aritr.rova.ui.theme.GlassSurface
import com.aritr.rova.ui.theme.ThemeSelection
```

- [ ] **Step 2: Point the appearance row at `themeSelection` and wrap the section in `GlassSurface`**

Replace the existing Appearance `SettingsSection { … }` block with a `GlassSurface`-wrapped version, and change the theme row's `value`:

```kotlin
GlassSurface(
    role = GlassRole.Card,
    modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 8.dp),
) {
    SettingsSection(label = stringResource(R.string.settings_section_appearance)) {
        SettingsRow(
            icon = Icons.Default.DarkMode,
            label = stringResource(R.string.settings_theme_label),
            supporting = stringResource(R.string.settings_theme_supporting),
            value = themeSelectionLabel(themeSelection),
            onClick = { openThemeSheet = true },
            trailing = { ChevronTrailing() },
        )
        if (showLanguageRow) {
            SettingsDivider()
            SettingsRow(
                icon = Icons.Default.Language,
                label = stringResource(R.string.settings_language_label),
                supporting = stringResource(R.string.settings_language_supporting),
                value = languageOptionLabel(localeTag),
                onClick = { openLanguageSheet = true },
                trailing = { ChevronTrailing() },
            )
        }
    }
}
```

> `Modifier`, `fillMaxWidth`, `padding`, `dp` are already imported in `SettingsScreen.kt`.

- [ ] **Step 3: Swap the theme sheet to Wave-1 `ThemeSelection`**

Replace the `if (openThemeSheet) { … }` block with:

```kotlin
if (openThemeSheet) {
    SettingsOptionSheet(
        title = stringResource(R.string.settings_theme_label),
        options = ThemeSelection.wave1Picker,
        selected = themeSelection,
        optionLabel = { sel -> themeSelectionLabel(sel) },
        onPick = { settingsViewModel.themeSelection.value = it },
        onDismiss = { openThemeSheet = false },
    )
}
```

> `SettingsOptionSheet` is generic over the option type (already used with `ThemeMode.entries` and the language `List`), so `List<ThemeSelection>` works unchanged. The old `themeMode`/`themeModeLabel` symbols may now be unused — leave `themeModeLabel` if other code references it; otherwise the executor may delete it in the same commit (verify with the compiler).

- [ ] **Step 4: Verify compile + gates + suite**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL (both new gates execute under preBuild and pass — `SettingsScreen.kt` contains no `Modifier.blur`, only the allowlisted `GlassSurface.kt` does); all tests green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/SettingsScreen.kt
git commit -m "feat(settings): App Settings proof surface — GlassSurface + Wave-1 ThemeSelection picker (ADR-0028)"
```

---

## Task 15: Final verification — full build, full suite, all gates

**Files:** none (verification only).

- [ ] **Step 1: Clean-ish full verify (controller runs gradle)**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL. Test count = prior baseline + the new theme tests (ThemeSelection 4, ThemeMigration 5, PaletteRegistry 5, GlassResolver 6, RovaMotion 3, ThemeContrast 3 = 26 new), 0 failures.

- [ ] **Step 2: Confirm both new gates are wired and green**

Run: `./gradlew :app:checkRecordSurfaceNoBlur :app:checkGlassSurfaceRoleUsage`
Expected: both BUILD SUCCESSFUL.

- [ ] **Step 3: Confirm no visual regression scope**

Manually confirm the diff touched rendering only in `SettingsScreen.kt` (Appearance section) + the additive `RovaTheme` env provider. Every other surface still renders via the unchanged `DarkColorScheme`/`LightColorScheme`. (`lintDebug` is expected RED on the pre-existing B5 `VaultAndroidOps` NewApi — do NOT treat that as a regression.)

- [ ] **Step 4: Device smoke (owner, before merge)**

Install the debug APK on a real device (emulators fail CameraX, but this PR is non-camera): open Settings → Appearance → theme sheet shows Follow-system + Aurora/Tide/Jade/Dusk/Eclipse/Daylight; picking each persists across relaunch; the Appearance card reflects the palette glass tint; Record/Player/Onboarding remain pinned dark; status-bar icon polarity flips correctly entering/leaving pinned-dark routes.

---

## Self-Review

**Spec coverage:**
- §1.1 selection model → Task 1. §1.2 migration → Tasks 2–3, 13. §1.3 palette tokens + locked semantics + Eclipse edges + Material-You-off → Task 4 (+ADR Task 12). §2.1 role-aware resolution → Tasks 5–7. §2.2 effective-surface rules → Task 6. §2.3 blur honesty + DualPreviewZone carve-out → Tasks 7, 11, 12. §2.4 pinned-dark contract → Task 13 (MainActivity `isPinnedDarkRoute`/`dark` mapping) + ADR; `accentOnDark` tokens defined in Task 4. §3.1 typography → Task 9. §3.2 motion → Task 8. §3.3/§3.4 icons + launcher → explicitly deferred (PR11/PR12) per ADR Task 12. §4 per-theme contrast → Task 10. §5 gates + ADR → Tasks 11–12. §6 PR sequence / proof surface → Tasks 13–14.
- **Gap noted + closed:** `accentOnDark`/`accentContainerOnDark` are defined and carried in `RovaPalette` (Task 4) so PR2/PR5/PR7 can apply them; PR1 doesn't render pinned-route accents (those surfaces aren't migrated this PR) — consistent with §6's "Record route untouched this PR."

**Placeholder scan:** No TBD/"handle edge cases"/"similar to Task N". All code blocks are complete. The one prose note in Task 10 (`textRatio`) explains a conservative-but-correct simplification and gives the exact remediation (adjust palette tokens, never the threshold).

**Type consistency:** `ThemeSelection` (enum, `DEFAULT`, `wave1Picker`, `resolveConcrete`) consistent across Tasks 1/2/4/13/14. `RovaPalette` field set (incl. `accentOnDark`, `accentContainerOnDark`, `isLight`) consistent Tasks 4/6/10/13. `GlassEnvironment(palette, apiLevel, reduceTransparency)`, `GlassRole`, `GlassMaterial(fill, blurRadius, edge, edgeTop, scrim)`, `GlassResolver.resolve`, `LocalGlassEnvironment` consistent Tasks 5/6/7/10/13. `RovaSemantics` field names consistent Task 4/12. `settings.themeSelection` / `settingsViewModel.themeSelection` consistent Tasks 3/13/14. Gate names `checkRecordSurfaceNoBlur` / `checkGlassSurfaceRoleUsage` consistent Tasks 11/12/15.

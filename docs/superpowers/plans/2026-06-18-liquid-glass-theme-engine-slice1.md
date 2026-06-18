# Liquid Glass Theme Engine — Slice 1 (palette propagation) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the active `RovaPalette` drive `MaterialTheme.colorScheme` on every surface, so a theme swap visibly restyles the whole app (today only ~3 migrated surfaces change).

**Architecture:** A pure `PaletteColorScheme.from(palette): ColorScheme` builds the Material scheme from the `darkColorScheme()/lightColorScheme()` factory base + `.copy()` overriding the identity slots; locked slots (`error`, `scrim`) come from `RovaSemantics`/black, never the palette. `RovaTheme` and `RovaDarkSurface` use it. A 12-swatch picker exposes all palettes. A new `checkSingleColorSchemeSource` preBuild gate keeps the scheme constructed in one place.

**Tech Stack:** Kotlin, Jetpack Compose (Material3, Compose BOM 2025.01.01), JUnit4 JVM unit tests (`isReturnDefaultValues = true`), Gradle Kotlin DSL static-check gates.

## Global Constraints

- **JVM unit tests only** — no Robolectric/instrumented. Pure helpers must run under `testOptions.unitTests.isReturnDefaultValues = true`. `ColorScheme`/`Color` construct fine on JVM (value-like classes; codex-confirmed).
- **WCAG 2.2 AA (ADR-0020)** — every `onX` foreground must clear ≥4.5:1 over its `X` fill; controls/outlines ≥3:1. Verified by `ThemeContrastTest` across all 12 palettes BEFORE any device build.
- **Identity-vs-locked seam (ADR-0028 §1.3)** — `error`/`onError`/`errorContainer`/`onErrorContainer` from `RovaSemantics`; `scrim` = `Color.Black`. The palette NEVER feeds these.
- **Strings in resources, en + es (ADR-0022)** — no hardcoded UI strings (`checkNoHardcodedUiStrings`). Palette names are proper nouns: es values mirror the English proper noun (as Wave-1 already does).
- **Static-check gate convention (CLAUDE.md)** — new invariant ⇒ ADR clause ⇒ `check*` task ⇒ wire into `preBuild`. Never edit a check to go green; fix source.
- **Build WARM** — `gradlew.bat :app:assembleDebug` incremental (~1–3 min); no cache wipe. Before any device install, confirm `:app:packageDebug` shows **EXECUTED** (not UP-TO-DATE) + fresh APK mtime (stale-APK gotcha).
- **Subagents EDIT-ONLY** — the controller runs all gradle + git.
- Baseline to preserve: full JVM suite GREEN, 45 gates GREEN → this slice adds the 46th.

---

### Task 1: Add `surfaceBase` field to `RovaPalette`

`MaterialTheme.background`/`surface` need a flat `Color`, but `palette.background` is a `Brush`. Add one opaque solid-base field; every palette already encodes its darkest stop.

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/theme/RovaPalette.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/theme/PaletteRegistryTest.kt`

**Interfaces:**
- Produces: `RovaPalette.surfaceBase: Color` (opaque). The `darkPalette(...)` factory sets it from `bgBottom`; Eclipse/Daylight/NeutralDark set it inline.

- [ ] **Step 1: Write the failing test** — append to `PaletteRegistryTest`:

```kotlin
@Test
fun `every palette has an opaque surfaceBase matching its darkest background stop`() {
    val expected = mapOf(
        ThemeSelection.AURORA to Color(0xFF141622),
        ThemeSelection.TIDE to Color(0xFF0E1A1F),
        ThemeSelection.JADE to Color(0xFF0C1C18),
        ThemeSelection.DUSK to Color(0xFF1F1310),
        ThemeSelection.ECLIPSE to Color(0xFF000000),
        ThemeSelection.DAYLIGHT to Color(0xFFF4F1EA),
        ThemeSelection.BLOSSOM to Color(0xFF1E1220),
        ThemeSelection.CORAL to Color(0xFF1F1510),
        ThemeSelection.MEADOW to Color(0xFF12190E),
        ThemeSelection.COBALT to Color(0xFF0E1230),
        ThemeSelection.ORCHID to Color(0xFF1E1019),
        ThemeSelection.GRAPHITE to Color(0xFF0E0F12),
    )
    expected.forEach { (sel, base) ->
        val p = rovaPalettes.getValue(sel)
        assertEquals("$sel surfaceBase", base, p.surfaceBase)
        assertEquals("$sel surfaceBase must be opaque", 1f, p.surfaceBase.alpha)
    }
}
```

- [ ] **Step 2: Run test, verify it FAILS** — `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.theme.PaletteRegistryTest"` → FAIL (`surfaceBase` unresolved / compile error). Controller runs this.

- [ ] **Step 3: Add the field + values** in `RovaPalette.kt`:
  - Add to the data class (after `accentContainerOnDark` / before `isLight`):
    ```kotlin
        val surfaceBase: Color,
    ```
  - In `darkPalette(...)` body add `surfaceBase = Color(bgBottom),` (the factory already receives `bgBottom`).
  - In the `Eclipse` literal add `surfaceBase = Color(0xFF000000),`.
  - In the `Daylight` literal add `surfaceBase = Color(0xFFF4F1EA),`.
  - In `NeutralDarkRecordPalette` add `surfaceBase = Color(0xFF05070B),`.

- [ ] **Step 4: Run test + full theme test package, verify PASS** — `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.theme.*"` → PASS (no existing test broke; `surfaceBase` is additive).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/theme/RovaPalette.kt app/src/test/java/com/aritr/rova/ui/theme/PaletteRegistryTest.kt
git commit -m "feat(theme): add opaque surfaceBase to RovaPalette (engine flat-base source)"
```

---

### Task 2: `PaletteColorScheme` mapper + pure helpers

The spine. Pure object building a `ColorScheme` from a palette.

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/theme/PaletteColorScheme.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/theme/PaletteColorSchemeTest.kt`

**Interfaces:**
- Consumes: `RovaPalette` (incl. `surfaceBase` from Task 1), `RovaSemantics.error`, `DialogActionColors.resolve(start: IntArray, end: IntArray): DialogActionColors.Cta` (fields `start`/`end: IntArray`, `contentWhite: Boolean`), `androidx.compose.ui.graphics.compositeOver`.
- Produces: `PaletteColorScheme.from(p: RovaPalette): androidx.compose.material3.ColorScheme`.

- [ ] **Step 1: Write the failing test** — `PaletteColorSchemeTest.kt`:

```kotlin
package com.aritr.rova.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteColorSchemeTest {

    private val concrete = ThemeSelection.entries.filter { it != ThemeSelection.FOLLOW_SYSTEM }

    @Test fun `from is deterministic`() {
        concrete.forEach { sel ->
            val p = rovaPalettes.getValue(sel)
            val a = PaletteColorScheme.from(p)
            val b = PaletteColorScheme.from(p)
            assertEquals("$sel primary", a.primary, b.primary)
            assertEquals("$sel surface", a.surface, b.surface)
            assertEquals("$sel error", a.error, b.error)
        }
    }

    @Test fun `error and scrim are locked, never palette-derived`() {
        concrete.forEach { sel ->
            val s = PaletteColorScheme.from(rovaPalettes.getValue(sel))
            assertEquals("$sel error", RovaSemantics.error, s.error)
            assertEquals("$sel onError", Color.White, s.onError)
            assertEquals("$sel scrim", Color.Black, s.scrim)
        }
    }

    @Test fun `surface and container slots are fully opaque`() {
        concrete.forEach { sel ->
            val s = PaletteColorScheme.from(rovaPalettes.getValue(sel))
            listOf(
                "background" to s.background, "surface" to s.surface,
                "surfaceVariant" to s.surfaceVariant, "surfaceDim" to s.surfaceDim,
                "surfaceBright" to s.surfaceBright,
                "surfaceContainerLowest" to s.surfaceContainerLowest,
                "surfaceContainerLow" to s.surfaceContainerLow,
                "surfaceContainer" to s.surfaceContainer,
                "surfaceContainerHigh" to s.surfaceContainerHigh,
                "surfaceContainerHighest" to s.surfaceContainerHighest,
                "outline" to s.outline, "outlineVariant" to s.outlineVariant,
                "primaryContainer" to s.primaryContainer, "errorContainer" to s.errorContainer,
            ).forEach { (name, c) -> assertTrue("$sel $name opaque", c.alpha == 1f) }
        }
    }

    @Test fun `surface container ladder is monotonic in luminance off surfaceBase`() {
        // Dark palettes raise toward white (increasing luminance); Daylight lowers.
        concrete.forEach { sel ->
            val s = PaletteColorScheme.from(rovaPalettes.getValue(sel))
            val rungs = listOf(
                s.surfaceContainerLowest, s.surfaceContainerLow, s.surfaceContainer,
                s.surfaceContainerHigh, s.surfaceContainerHighest,
            ).map { lum(it) }
            val ascending = rungs.zipWithNext().all { (a, b) -> b >= a - 1e-6 }
            val descending = rungs.zipWithNext().all { (a, b) -> b <= a + 1e-6 }
            assertTrue("$sel ladder must be monotonic", ascending || descending)
        }
    }

    @Test fun `onPrimary clears AA over the assigned primary fill`() {
        concrete.forEach { sel ->
            val s = PaletteColorScheme.from(rovaPalettes.getValue(sel))
            assertTrue("$sel onPrimary/primary ${ratio(s.onPrimary, s.primary)}",
                ratio(s.onPrimary, s.primary) >= 4.5)
            assertTrue("$sel onSecondary/secondary", ratio(s.onSecondary, s.secondary) >= 4.5)
        }
    }

    private fun lum(c: Color) =
        ContrastMath.relativeLuminance((c.red*255).toInt(), (c.green*255).toInt(), (c.blue*255).toInt())
    private fun ratio(fg: Color, bg: Color) = ContrastMath.contrastRatio(lum(fg), lum(bg))
}
```

- [ ] **Step 2: Run test, verify it FAILS** — `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.theme.PaletteColorSchemeTest"` → FAIL (unresolved `PaletteColorScheme`).

- [ ] **Step 3: Write `PaletteColorScheme.kt`:**

```kotlin
package com.aritr.rova.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import kotlin.math.roundToInt

/**
 * The propagation engine (ADR-0028 amendment 2026-06-18). Pure mapper: the active
 * [RovaPalette] -> a Material3 [ColorScheme] so EVERY `MaterialTheme.colorScheme.*`
 * reader restyles per palette. Built from the pure `darkColorScheme()/lightColorScheme()`
 * factory base + `.copy()` so newer M3 slots keep valid defaults (codex 2026-06-18).
 *
 * Identity-vs-locked (ADR-0028 §1.3, same contract as [LibraryColorSpec]): `error*`
 * come from [RovaSemantics], `scrim` is black — the palette NEVER feeds them. The
 * surfaceContainer family is a NEUTRAL tonal ladder off [RovaPalette.surfaceBase], not
 * accent-tinted. Framework-free -> JVM-tested ([PaletteColorSchemeTest]).
 */
object PaletteColorScheme {

    private val NearBlack = Color(0xFF0B0B0F)

    fun from(p: RovaPalette): ColorScheme {
        val base = if (p.isLight) lightColorScheme() else darkColorScheme()
        val sb = p.surfaceBase

        val (primary, onPrimary) = resolveFill(p.accent)
        val (secondary, onSecondary) = resolveFill(p.accent2)   // tertiary mirrors secondary (no 3rd hue, §8)

        val ladder = surfaceLadder(sb, p.isLight)

        return base.copy(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = p.accentContainerOnDark.compositeOver(sb),
            onPrimaryContainer = p.textHigh,
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = p.accent2.copy(alpha = 0.22f).compositeOver(sb),
            onSecondaryContainer = p.textHigh,
            tertiary = secondary,
            onTertiary = onSecondary,
            tertiaryContainer = p.accent2.copy(alpha = 0.22f).compositeOver(sb),
            onTertiaryContainer = p.textHigh,
            background = sb,
            onBackground = p.textHigh,
            surface = sb,
            onSurface = p.textHigh,
            surfaceVariant = ladder.variant,
            onSurfaceVariant = p.textDim,
            surfaceDim = ladder.dim,
            surfaceBright = ladder.bright,
            surfaceContainerLowest = ladder.lowest,
            surfaceContainerLow = ladder.low,
            surfaceContainer = ladder.mid,
            surfaceContainerHigh = ladder.high,
            surfaceContainerHighest = ladder.highest,
            outline = promoteOpaque(p.edge, sb, 0.55f),
            outlineVariant = promoteOpaque(p.edge, sb, 0.30f),
            surfaceTint = primary,
            inverseSurface = if (p.isLight) Color(0xFF1A1C20) else Color(0xFFE7E9EE),
            inverseOnSurface = if (p.isLight) Color(0xFFF2F3F6) else Color(0xFF15171C),
            inversePrimary = p.accent,
            error = RovaSemantics.error,
            onError = Color.White,
            errorContainer = RovaSemantics.error.copy(alpha = 0.22f).compositeOver(sb),
            onErrorContainer = p.textHigh,
            scrim = Color.Black,
        )
    }

    /** Resolve a SOLID accent slot to (deepened fill, AA label) via the shared CTA helper. */
    private fun resolveFill(accent: Color): Pair<Color, Color> {
        val rgb = accent.toRgb255()
        val cta = DialogActionColors.resolve(rgb, rgb)
        val fill = Color(red = cta.start[0], green = cta.start[1], blue = cta.start[2])
        return fill to if (cta.contentWhite) Color.White else NearBlack
    }

    /** Composite a low-alpha edge at a boosted alpha over the opaque base -> visible opaque outline. */
    private fun promoteOpaque(edge: Color, base: Color, boost: Float): Color =
        edge.copy(alpha = (edge.alpha + boost).coerceAtMost(1f)).compositeOver(base)

    private data class Ladder(
        val dim: Color, val variant: Color, val bright: Color,
        val lowest: Color, val low: Color, val mid: Color, val high: Color, val highest: Color,
    )

    /**
     * Neutral opaque surface ladder off [base]. M3 elevation direction (codex): `surfaceBright`
     * always blends toward WHITE; the surfaceContainer ladder + variant use a direction-correct
     * elevation tint (toward white on dark palettes = more elevated/lighter; toward a neutral
     * darker tone on the light palette). `surfaceDim` is the dimmest. The 5 surfaceContainer rungs
     * share one tint with monotonically increasing alpha (monotonic luminance — see test).
     */
    private fun surfaceLadder(base: Color, isLight: Boolean): Ladder {
        fun toward(tint: Color, a: Float) = tint.copy(alpha = a).compositeOver(base)
        val elevate = if (isLight) Color.Black else Color.White   // container elevation direction
        return Ladder(
            dim = if (isLight) toward(Color.Black, 0.04f) else base,
            variant = toward(elevate, 0.07f),
            bright = toward(Color.White, 0.14f),                  // bright is lighter in BOTH modes
            lowest = toward(elevate, 0.02f),
            low = toward(elevate, 0.04f),
            mid = toward(elevate, 0.06f),
            high = toward(elevate, 0.09f),
            highest = toward(elevate, 0.12f),
        )
    }

    private fun Color.toRgb255(): IntArray = intArrayOf(
        (red * 255).roundToInt().coerceIn(0, 255),
        (green * 255).roundToInt().coerceIn(0, 255),
        (blue * 255).roundToInt().coerceIn(0, 255),
    )
}
```

- [ ] **Step 4: Run test, verify PASS** — `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.theme.PaletteColorSchemeTest"` → PASS (all 5). If `onPrimary` AA fails for any palette, the issue is `resolveFill` — confirm `DialogActionColors.resolve` is called with the accent as BOTH endpoints (solid).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/theme/PaletteColorScheme.kt app/src/test/java/com/aritr/rova/ui/theme/PaletteColorSchemeTest.kt
git commit -m "feat(theme): PaletteColorScheme.from(palette) engine mapper + pure helpers"
```

---

### Task 3: Extend `ThemeContrastTest` to the derived ColorScheme slots

Prove AA across all 12 palettes on the actual scheme slots, BEFORE any device build.

**Files:**
- Modify: `app/src/test/java/com/aritr/rova/ui/theme/ThemeContrastTest.kt`

**Interfaces:**
- Consumes: `PaletteColorScheme.from` (Task 2), `ContrastMath`.

- [ ] **Step 1: Write the failing tests** — append three methods (they reuse the file's `rgb`/existing helpers; add a local `lumColor`/`ratioColor` since the existing helpers expect a resolved surface):

```kotlin
private fun lumColor(c: Color) =
    ContrastMath.relativeLuminance((c.red*255).roundToInt(), (c.green*255).roundToInt(), (c.blue*255).roundToInt())
private fun ratioColor(fg: Color, bg: Color) = ContrastMath.contrastRatio(lumColor(fg), lumColor(bg))

@Test
fun `derived scheme on-colors clear 4_5 over their fills (all 12 palettes)`() {
    concrete.forEach { sel ->
        val s = PaletteColorScheme.from(rovaPalettes.getValue(sel))
        val pairs = listOf(
            "onBackground" to (s.onBackground to s.background),
            "onSurface" to (s.onSurface to s.surface),
            "onSurfaceVariant" to (s.onSurfaceVariant to s.surfaceVariant),
            "onPrimary" to (s.onPrimary to s.primary),
            "onSecondary" to (s.onSecondary to s.secondary),
            "onTertiary" to (s.onTertiary to s.tertiary),
            "onPrimaryContainer" to (s.onPrimaryContainer to s.primaryContainer),
            "onSecondaryContainer" to (s.onSecondaryContainer to s.secondaryContainer),
            "onTertiaryContainer" to (s.onTertiaryContainer to s.tertiaryContainer),
            "onErrorContainer" to (s.onErrorContainer to s.errorContainer),
            "onError" to (s.onError to s.error),
        )
        pairs.forEach { (name, fb) ->
            val r = ratioColor(fb.first, fb.second)
            assertTrue("$sel $name ${"%.2f".format(r)}:1 < 4.5", r >= 4.5)
        }
    }
}

@Test
fun `body text clears 4_5 over every surfaceContainer rung (all 12 palettes)`() {
    concrete.forEach { sel ->
        val s = PaletteColorScheme.from(rovaPalettes.getValue(sel))
        listOf(
            "Dim" to s.surfaceDim, "Bright" to s.surfaceBright,
            "Lowest" to s.surfaceContainerLowest, "Low" to s.surfaceContainerLow,
            "Container" to s.surfaceContainer, "High" to s.surfaceContainerHigh,
            "Highest" to s.surfaceContainerHighest,
        ).forEach { (name, rung) ->
            val r = ratioColor(s.onSurface, rung)
            assertTrue("$sel onSurface over $name ${"%.2f".format(r)}:1 < 4.5", r >= 4.5)
        }
    }
}

@Test
fun `primary and outline clear 3 to 1 over surface (all 12 palettes)`() {
    concrete.forEach { sel ->
        val s = PaletteColorScheme.from(rovaPalettes.getValue(sel))
        assertTrue("$sel primary/surface", ratioColor(s.primary, s.surface) >= 3.0)
        assertTrue("$sel outline/surface", ratioColor(s.outline, s.surface) >= 3.0)
    }
}
```

  (Add `import com.aritr.rova.ui.theme.PaletteColorScheme`? Same package — no import needed. Ensure `Color` + `roundToInt` already imported — they are.)

- [ ] **Step 2: Run, verify it runs and shows any AA gaps** — `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.theme.ThemeContrastTest"`. If a slot fails AA, this is a real engine bug to FIX in `PaletteColorScheme` (e.g. bump `promoteOpaque` boost for `outline`, or adjust ladder alphas, or `onSurfaceVariant` over a too-light rung — switch that rung's `onSurfaceVariant` source to `textHigh` if `textDim` can't clear it over the brightest rung). Tune `PaletteColorScheme.kt`, re-run, until GREEN. Do NOT relax the assertions.

- [ ] **Step 3: Run full theme package, verify PASS** — `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.theme.*"` → PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/test/java/com/aritr/rova/ui/theme/ThemeContrastTest.kt app/src/main/java/com/aritr/rova/ui/theme/PaletteColorScheme.kt
git commit -m "test(theme): per-palette AA over derived ColorScheme slots + ladder rungs"
```

---

### Task 4: Wire `RovaTheme` + `RovaDarkSurface` to the engine

Replace the static scheme selection. **Composition-order correctness (load-bearing):** in `MainScreen` the pinned-env swap (`forPinnedRoute`) is provided INSIDE `RovaDarkSurface`'s content lambda, so `RovaDarkSurface` reads the still-active APP palette. It must apply `forPinnedRoute` ITSELF when building its scheme, or a light app theme (Daylight) leaks a light scheme onto the camera/player.

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/theme/Theme.kt` (RovaTheme ~110, RovaDarkSurface ~152; delete `DarkColorScheme`/`LightColorScheme` vals ~25-86)

**Interfaces:**
- Consumes: `PaletteColorScheme.from` (Task 2), `PinnedGlassEnvironment.forPinnedRoute`, `LocalGlassEnvironment`.

- [ ] **Step 1: Rewire `RovaTheme`'s scheme** — in `Theme.kt`, replace:

```kotlin
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
```
with:
```kotlin
    val colorScheme = PaletteColorScheme.from(palette)
```
(`darkTheme` stays — it still drives `lightStatusBarIcons`. `palette` is already a param defaulting to Aurora.)

- [ ] **Step 2: Rewire `RovaDarkSurface`** — replace its body with the pinned-aware version:

```kotlin
@Composable
fun RovaDarkSurface(content: @Composable () -> Unit) {
    // Pinned camera/media routes stay cinematic neutral-dark (ADR-0028 §2.4). The
    // pinned-env swap is provided INSIDE this composable's content by the caller, so
    // we apply forPinnedRoute here too to build the scheme from the neutral-dark base
    // carrying ONLY the active accent — never the (possibly light) app surface.
    val pinned = PinnedGlassEnvironment.forPinnedRoute(LocalGlassEnvironment.current)
    MaterialTheme(
        colorScheme = PaletteColorScheme.from(pinned.palette),
        typography = Typography,
        content = content,
    )
}
```
  Add imports if missing: `androidx.compose.runtime.Composable` (present), `com.aritr.rova.ui.theme.*` are same-package. Ensure `LocalGlassEnvironment` + `PinnedGlassEnvironment` resolve (same package — no import).

- [ ] **Step 3: Delete the now-unused static schemes** — remove the `private val DarkColorScheme = darkColorScheme(...)` (~25-53) and `private val LightColorScheme = lightColorScheme(...)` (~55-86) blocks from `Theme.kt`. (They have no remaining readers. This also keeps `darkColorScheme(`/`lightColorScheme(` out of `Theme.kt` except via the engine — though `Theme.kt` is gate-allowlisted regardless.) Leave the Color token vals in `Color.kt` untouched.

- [ ] **Step 4: Build, verify GREEN** — controller: `gradlew.bat :app:assembleDebug`. Expected: BUILD SUCCESSFUL, no unresolved refs. (If `DarkColorScheme`/`LightColorScheme` are referenced anywhere else, the compile fails listing them — grep + fix; per audit only Theme.kt used them.)

- [ ] **Step 5: Run full JVM suite, verify GREEN** — `gradlew.bat :app:testDebugUnitTest` → all pass (existing + new).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/theme/Theme.kt
git commit -m "feat(theme): RovaTheme + RovaDarkSurface derive ColorScheme from active palette"
```

---

### Task 5: `checkSingleColorSchemeSource` preBuild gate (46th)

Lock the Material scheme to one construction site so no surface escapes the engine.

**Files:**
- Modify: `app/build.gradle.kts` (register task near the other `check*` tasks ~2434; wire `dependsOn` in the preBuild block ~2622)

**Interfaces:**
- Produces: gradle task `checkSingleColorSchemeSource`, wired into `preBuild`.

- [ ] **Step 1: Register the task** — insert after `checkRovaGlyphHome` (after line ~2434):

```kotlin
val checkSingleColorSchemeSource = tasks.register("checkSingleColorSchemeSource") {
    group = "verification"
    description = "The Material ColorScheme is constructed in exactly one place — the engine " +
        "(Theme.kt builds it from PaletteColorScheme; PaletteColorScheme.kt holds the factory base). " +
        "No other file may call darkColorScheme(/lightColorScheme( or pass MaterialTheme(colorScheme=…) " +
        "(ADR-0028 amendment 2026-06-18). // colorscheme-source-opt-out to waive a line."
    val srcDir = file("src/main/java/com/aritr/rova")
    val allow = setOf(
        file("src/main/java/com/aritr/rova/ui/theme/Theme.kt").canonicalFile,
        file("src/main/java/com/aritr/rova/ui/theme/PaletteColorScheme.kt").canonicalFile,
    )
    inputs.dir(srcDir).withPropertyName("rovaSources")
    doLast {
        if (!srcDir.exists()) {
            throw GradleException("checkSingleColorSchemeSource: Rova source dir missing: $srcDir")
        }
        // Strip block + line comments AND string literals (preserve newlines for line numbers) so a
        // `colorScheme =` inside a comment/string never matches.
        fun strip(src: String): String {
            val noBlock = Regex("""/\*[\s\S]*?\*/""").replace(src) { m -> m.value.replace(Regex("[^\n]"), " ") }
            val noLine = noBlock.lines().joinToString("\n") { line ->
                val i = line.indexOf("//"); if (i >= 0) line.substring(0, i) else line
            }
            // blank out double-quoted string contents (keep quotes + length)
            val noStr = Regex(""""(\\.|[^"\\])*"""").replace(noLine) { m -> "\"" + " ".repeat((m.value.length - 2).coerceAtLeast(0)) + "\"" }
            // blank char literals too — a Kotlin '(' or ')' must not corrupt the balanced-paren scan (codex).
            // Preserve length (escaped literals like '\n' are 4 chars) so raw-offset waiver stays 1:1.
            return Regex("""'(\\.|[^'\\])'""").replace(noStr) { m -> "'" + " ".repeat(m.value.length - 2) + "'" }
        }
        fun lineAt(text: String, idx: Int) = text.substring(0, idx).count { it == '\n' } + 1
        val factory = Regex("""\b(dark|light)ColorScheme\s*\(""")
        val mt = Regex("""MaterialTheme\s*\(""")
        val offenders = srcDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.canonicalFile !in allow }
            .mapNotNull { f ->
                val text = strip(f.readText())
                val hits = mutableListOf<Int>()
                factory.findAll(text).forEach { m ->
                    if (!waived(text, m.range.first)) hits += lineAt(text, m.range.first)
                }
                // Balanced-paren scan from each MaterialTheme( — inspect only THAT call's args for colorScheme=
                mt.findAll(text).forEach { m ->
                    val open = text.indexOf('(', m.range.first)
                    if (open < 0) return@forEach
                    var depth = 0; var i = open; var close = -1
                    while (i < text.length) {
                        when (text[i]) { '(' -> depth++; ')' -> { depth--; if (depth == 0) { close = i; break } } }
                        i++
                    }
                    if (close < 0) return@forEach
                    val args = text.substring(open + 1, close)
                    if (Regex("""\bcolorScheme\s*=""").containsMatchIn(args) && !waived(text, m.range.first)) {
                        hits += lineAt(text, m.range.first)
                    }
                }
                if (hits.isEmpty()) null else f to hits.sorted()
            }.toList()
        if (offenders.isNotEmpty()) {
            val report = offenders.joinToString("\n") { (f, hits) ->
                hits.joinToString("\n") { "  ${f.relativeTo(rootDir)}:$it" }
            }
            throw GradleException(
                "ADR-0028 amendment: the Material ColorScheme must be built only by the theme engine " +
                    "(Theme.kt / PaletteColorScheme.kt). A surface constructs or overrides its own scheme " +
                    "— route it through the active palette instead.\nOffenders:\n$report"
            )
        }
    }
}

// shared: a `// colorscheme-source-opt-out` on the same or previous line waives a hit
fun waived(text: String, idx: Int): Boolean {
    val lineStart = text.lastIndexOf('\n', idx).let { if (it < 0) 0 else it }
    val lineEnd = text.indexOf('\n', idx).let { if (it < 0) text.length else it }
    val prevStart = text.lastIndexOf('\n', (lineStart - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it }
    return text.substring(prevStart, lineEnd).contains("colorscheme-source-opt-out")
}
```
  (Note: the `waived` helper reads the ORIGINAL hatch comment — but `strip` removed comments. Simplest fix: skip the hatch for slice 1 and drop `&& !waived(...)` + the helper, OR run `waived` against the UNSTRIPPED text. Use the unstripped text: capture `val raw = f.readText()` and call `waived(raw, rawIdx)`. Since stripping preserves offsets/newlines 1:1, `idx` maps directly — pass `raw` to `waived`. Adjust: `val raw = f.readText(); val text = strip(raw)` then `waived(raw, m.range.first)`.)

- [ ] **Step 2: Apply the hatch-offset fix** — in the task body set `val raw = f.readText()` then `val text = strip(raw)`, and call `waived(raw, m.range.first)` in both branches. (Offsets are identical because `strip` preserves length + newlines.)

- [ ] **Step 3: Wire into preBuild** — in the preBuild `configureEach` block (after `dependsOn(checkRovaGlyphHome)` ~2622) add:

```kotlin
        dependsOn(checkSingleColorSchemeSource)
```

- [ ] **Step 4: Run the gate, verify GREEN on clean tree** — `gradlew.bat :app:checkSingleColorSchemeSource` → no offenders (Theme.kt + PaletteColorScheme.kt allowlisted; no other file constructs a scheme).

- [ ] **Step 5: Prove-it-bites #1 (factory)** — temporarily add to a non-engine file (e.g. top of `app/src/main/java/com/aritr/rova/ui/MainScreen.kt`): `private val sneaky = androidx.compose.material3.darkColorScheme()`. Run `gradlew.bat :app:checkSingleColorSchemeSource` → **FAIL** citing MainScreen.kt:line. Revert the line.

- [ ] **Step 6: Prove-it-bites #2 (nested-paren MaterialTheme)** — temporarily add in a non-engine composable a call `MaterialTheme(typography = Typography.copy(), colorScheme = androidx.compose.material3.darkColorScheme()) {}` (nested parens BEFORE the arg). Run the gate → **FAIL** (proves the balanced-paren scanner, not a brittle regex, is in force). Revert.

- [ ] **Step 7: Commit**

```bash
git add app/build.gradle.kts
git commit -m "build(theme): checkSingleColorSchemeSource gate (46th) — one ColorScheme source"
```

---

### Task 6: 12-swatch theme picker + Wave-2 strings

Replace the text radio sheet with a swatch grid exposing all 12 palettes.

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/screens/ThemeSwatchSheet.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/theme/ThemeSelection.kt` (add `allPicker`)
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/SettingsScreen.kt` (~740-771 swap sheet; ~1088-1096 extend label)
- Modify: `app/src/main/res/values/strings.xml` (~412 add 6)
- Modify: `app/src/main/res/values-es/strings.xml` (mirror 6)

**Interfaces:**
- Consumes: `rovaPalettes`, `RovaPalette` (background Brush, accent/accent2, textHigh, surfaceBase), `ThemeSelection`.
- Produces: `ThemeSelection.allPicker: List<ThemeSelection>`; composable `ThemeSwatchSheet(title, options, selected, optionLabel, onPick, onDismiss)`.

- [ ] **Step 1: Add `allPicker`** to `ThemeSelection.kt` companion (keep `wave1Picker` for its existing test):

```kotlin
        /** Full picker — Follow-System + all 12 palettes (engine slice 1, 2026-06-18). */
        val allPicker: List<ThemeSelection> = listOf(FOLLOW_SYSTEM) + entries.filter { it != FOLLOW_SYSTEM }
```

- [ ] **Step 2: Add the 6 Wave-2 strings** — in `values/strings.xml` after line 412 (`..._daylight`):

```xml
    <string name="settings_theme_selection_blossom">Blossom</string>
    <string name="settings_theme_selection_coral">Coral</string>
    <string name="settings_theme_selection_meadow">Meadow</string>
    <string name="settings_theme_selection_cobalt">Cobalt</string>
    <string name="settings_theme_selection_orchid">Orchid</string>
    <string name="settings_theme_selection_graphite">Graphite</string>
```
  And the SAME six (proper nouns, identical) in `values-es/strings.xml` next to its `..._daylight` entry.

- [ ] **Step 3: Create `ThemeSwatchSheet.kt`** (Glass-branded modal grid; each tile = palette bg gradient + accent gradient chip + name; selected = accent ring):

```kotlin
package com.aritr.rova.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aritr.rova.ui.theme.ThemeSelection
import com.aritr.rova.ui.theme.rovaPalettes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSwatchSheet(
    title: String,
    options: List<ThemeSelection>,
    selected: ThemeSelection,
    optionLabel: (ThemeSelection) -> String,
    onPick: (ThemeSelection) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            title,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            fontWeight = FontWeight.SemiBold,
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(options) { sel ->
                ThemeSwatchTile(sel, sel == selected, optionLabel(sel)) { onPick(sel); onDismiss() }
            }
        }
    }
}

@Composable
private fun ThemeSwatchTile(
    sel: ThemeSelection,
    isSelected: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    // Follow-System has no own palette — preview the concrete dark default (Aurora).
    val p = rovaPalettes.getValue(sel.resolveConcrete(systemDark = true))
    val ring = if (isSelected) p.accent else p.edge
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(p.background)
            .border(if (isSelected) 2.dp else 1.dp, ring, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(12.dp)
            .heightIn(min = 72.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 24.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Brush.horizontalGradient(listOf(p.accent, p.accent2))),
        )
        Text(label, color = p.textHigh, fontWeight = FontWeight.Medium)
    }
}
```

- [ ] **Step 4: Swap the picker call in `SettingsScreen.kt`** — replace the `SettingsOptionSheet(...)` block (lines ~752-770) with the swatch sheet using all 12; resolve every label (extend the `when` to all 12, exhaustive):

```kotlin
        val blossomLabel = stringResource(R.string.settings_theme_selection_blossom)
        val coralLabel = stringResource(R.string.settings_theme_selection_coral)
        val meadowLabel = stringResource(R.string.settings_theme_selection_meadow)
        val cobaltLabel = stringResource(R.string.settings_theme_selection_cobalt)
        val orchidLabel = stringResource(R.string.settings_theme_selection_orchid)
        val graphiteLabel = stringResource(R.string.settings_theme_selection_graphite)
        ThemeSwatchSheet(
            title = stringResource(R.string.settings_theme_label),
            options = ThemeSelection.allPicker,
            selected = themeSelection,
            optionLabel = { sel ->
                when (sel) {
                    ThemeSelection.FOLLOW_SYSTEM -> followLabel
                    ThemeSelection.AURORA -> auroraLabel
                    ThemeSelection.TIDE -> tideLabel
                    ThemeSelection.JADE -> jadeLabel
                    ThemeSelection.DUSK -> duskLabel
                    ThemeSelection.ECLIPSE -> eclipseLabel
                    ThemeSelection.DAYLIGHT -> daylightLabel
                    ThemeSelection.BLOSSOM -> blossomLabel
                    ThemeSelection.CORAL -> coralLabel
                    ThemeSelection.MEADOW -> meadowLabel
                    ThemeSelection.COBALT -> cobaltLabel
                    ThemeSelection.ORCHID -> orchidLabel
                    ThemeSelection.GRAPHITE -> graphiteLabel
                }
            },
            onPick = { settingsViewModel.themeSelection.value = it },
            onDismiss = { openThemeSheet = false },
        )
```
  (Keep the existing `followLabel..daylightLabel` declarations above; add the six new ones. The `when` is now exhaustive — drop the `else`.)

- [ ] **Step 5: Extend the row-value `themeSelectionLabel`** (~1088) to all 12 (exhaustive `when`, add the 6 cases mirroring Step 4, drop any `else`).

- [ ] **Step 6: Build, verify GREEN** — controller: `gradlew.bat :app:assembleDebug` → SUCCESS. Then `gradlew.bat :app:lintDebug` is NOT required, but run the strings gate: `gradlew.bat :app:checkNoHardcodedUiStrings` → GREEN (all labels via resources).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/ThemeSwatchSheet.kt app/src/main/java/com/aritr/rova/ui/theme/ThemeSelection.kt app/src/main/java/com/aritr/rova/ui/screens/SettingsScreen.kt app/src/main/res/values/strings.xml app/src/main/res/values-es/strings.xml
git commit -m "feat(theme): 12-swatch theme picker + expose Wave-2 palettes (en/es)"
```

---

### Task 7: ADR-0028 amendment + full verification + device smoke

**Files:**
- Modify: `docs/adr/0028-liquid-glass-design-system.md` (append amendment)
- Modify: `CLAUDE.md` (gate count 45→46; add `checkSingleColorSchemeSource` to the list)

- [ ] **Step 1: Append the ADR-0028 amendment** documenting the propagation flip:

```markdown
## Amendment (2026-06-18) — theme engine slice 1: palette propagation

§Consequences (PR1) froze most surfaces on the static `DarkColorScheme`/`LightColorScheme`
until per-surface migration. This is superseded: the active palette now derives
`MaterialTheme.colorScheme` app-wide via the pure `PaletteColorScheme.from(palette)`
(factory base + `.copy`), so all surfaces restyle from one layer. Identity-vs-locked
(§1.3) is unchanged — `error*` come from `RovaSemantics`, `scrim` is black, sourced
inside the mapper; the surfaceContainer family is a neutral tonal ladder off the new
`RovaPalette.surfaceBase`, never accent-tinted. Pinned camera/media routes (§2.4) stay
neutral-dark: `RovaDarkSurface` applies `PinnedGlassEnvironment.forPinnedRoute` itself
before building its scheme (the caller provides the pinned env inside its content), so a
light app theme never leaks a light scheme onto the camera. New preBuild gate
`checkSingleColorSchemeSource` enforces the single construction site (Theme.kt +
PaletteColorScheme.kt). AA across all 12 palettes × derived slots is proven by
`ThemeContrastTest`; purity/locked/opacity/pairing by `PaletteColorSchemeTest`.
Deferred: icon glass-chip active states + animated states (ADR-0031 P2); deep
per-surface seams only where the mapping proves too coarse in device smoke.
```

- [ ] **Step 2: Update `CLAUDE.md`** — bump "45 custom check* tasks" → 46 and add `checkSingleColorSchemeSource` to the gate list line.

- [ ] **Step 3: Full clean verification (controller):**
  - `gradlew.bat :app:testDebugUnitTest` → full JVM suite GREEN (baseline + new).
  - `gradlew.bat :app:assembleDebug` → BUILD SUCCESSFUL; confirm **`:app:checkSingleColorSchemeSource`** and all preBuild gates ran. **Confirm `:app:packageDebug` shows EXECUTED + fresh APK mtime** (stale-APK gotcha).

- [ ] **Step 4: Commit docs**

```bash
git add docs/adr/0028-liquid-glass-design-system.md CLAUDE.md
git commit -m "docs(theme): ADR-0028 propagation amendment + 46th gate in CLAUDE.md"
```

- [ ] **Step 5: Device smoke (owner, RZCYA1VBQ2H)** — install the fresh APK; walk EVERY surface and swap palettes:
  - Settings → theme picker shows 12 swatch tiles; tapping live-restyles.
  - Swap Aurora → Jade → Dusk → Daylight (light!) → Eclipse and verify on each: **History/Library** (cards, sheets, chips), **Settings** rows, **warnings/dialogs** (RovaAlertDialog), **onboarding** — text stays readable (AA), accents shift per theme, no black-on-black or white-on-white.
  - **Pinned routes** (Record viewfinder, Player, Onboarding) stay cinematic neutral-dark in EVERY theme incl. Daylight; only accent controls pick up the theme. Record/Stop FAB + rec dot stay locked red.
  - Error/destructive surfaces (vault delete confirm) stay the locked red regardless of theme.
  - Note any surface that reads wrong → file as a targeted per-surface seam follow-up (NOT a slice-1 blocker unless AA-breaking).

---

## Self-Review

**Spec coverage:**
- PaletteColorScheme.from (spine) → Task 2. ✓
- factory base + .copy → Task 2. ✓
- identity slot mapping + locked error/scrim → Task 2. ✓
- primary/onPrimary AA pairing via DialogActionColors → Task 2 (`resolveFill`) + tested Task 2/3. ✓
- surfaceBase field → Task 1. ✓
- surfaceLadder neutral ladder → Task 2. ✓
- RovaTheme + RovaDarkSurface wiring (+ pinned composition-order fix) → Task 4. ✓
- 12-swatch picker + Wave-2 strings en/es → Task 6. ✓
- checkSingleColorSchemeSource gate (balanced-paren + string strip + 2 prove-it-bites) → Task 5. ✓
- extended ThemeContrastTest (all on/X + ladder rungs + 3:1) → Task 3. ✓
- PaletteColorSchemeTest (purity/locked/opacity/ladder/pairing) → Task 2. ✓
- ADR-0028 amendment → Task 7. ✓
- device smoke all surfaces → Task 7. ✓

**Placeholder scan:** none — every code/test step has real content.

**Type consistency:** `PaletteColorScheme.from(p: RovaPalette): ColorScheme` used identically in Tasks 2/3/4. `DialogActionColors.resolve(IntArray, IntArray): Cta` with `.start`/`.contentWhite` matches the real source. `RovaPalette.surfaceBase: Color` defined Task 1, consumed Task 2. `ThemeSelection.allPicker` defined + consumed Task 6. `ThemeSwatchSheet(...)` signature defined + called Task 6.

**Note for executor:** Task 3 Step 2 may surface a real AA gap on a specific slot/palette — that is expected engine tuning (adjust `promoteOpaque` boost, ladder alphas, or an `onSurfaceVariant` source), NOT a reason to relax the assertion. Tune the mapper until GREEN.

## Codex review (folded)

Codex reviewed the plan code. Two real bugs fixed inline:
1. **Gate scanner** — Kotlin char literals `'('`/`')'` corrupted the balanced-paren
   depth count (strip only blanked strings/comments). `strip()` now blanks char-literal
   contents too, length-preserving (escaped `'\n'` is 4 chars) so the raw-offset waiver
   stays 1:1.
2. **surfaceLadder** — for the light palette, `tint=Black` made `surfaceBright` darker
   than `surface` (inverts M3 elevation). `surfaceBright` now blends toward white in both
   modes; the container ladder uses a direction-correct elevation tint.
Confirmed OK: `compositeOver` opaque-over-opaque, pure `light/darkColorScheme()` on JVM,
surfaceContainer* present in the BOM Material3, `RovaDarkSurface` `forPinnedRoute`
double-apply is idempotent, top-level `fun waived` scope fine.

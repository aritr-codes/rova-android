# Liquid Glass — Record Home (PR2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the active theme finally show on the pinned-dark Record home — `accentOnDark` on selected/active markers only — by routing record chrome through `GlassSurface(role=RecordChrome)` over a shared neutral-dark base env, with zero regression to today's airy no-blur look, across portrait / landscape / P+L.

**Architecture:** Pinned-dark routes (Record/Player/Onboarding) get a `LocalGlassEnvironment` re-seeded with a dedicated `NeutralDarkRecordPalette` that carries ONLY the active palette's dark-safe accents (`accentOnDark`, `accentContainerOnDark`). The `GlassRole.RecordChrome` resolver branch is tuned from 0.88→~0.40 alpha (airy, matching the shipped/mockup-preserved chrome). Theme personality reaches the Record home exclusively through the selected mode segment + active-recording progress accents; the record/stop affordance stays the locked `rec #ff4d4d` semantic in every theme.

**Tech Stack:** Kotlin, Jetpack Compose, Material3, CameraX. JVM unit tests only (`isReturnDefaultValues = true`); pure-helper extraction for anything framework-touching. Static gates `checkRecordSurfaceNoBlur` + `checkGlassSurfaceRoleUsage` must stay green. ADR-0028 amendment for the RecordChrome resolver tuning + neutral-dark base.

**Decisions (owner-deferred → mockups + codex, 2026-06-08):**
- D1 accent reach = restrained (selected/active markers only; idle Start FAB neutral; rec-red locked).
- D2 panels = keep airy (resolver RecordChrome 0.88→~0.40); neutral-dark base approach (i).
- Scope: theme + glass-migrate the EXISTING portrait/landscape/P+L layouts. The mockup's edge-hugging landscape *re-layout* is OUT OF SCOPE for PR2 (candidate PR2b) — PR2 themes the current layout and verifies it on device in each orientation.

---

## File Structure

**Create:**
- `app/src/main/java/com/aritr/rova/ui/theme/PinnedGlassEnvironment.kt` — pure builder: active `GlassEnvironment` → pinned-route env (neutral-dark base + carried dark-safe accents).
- `app/src/test/java/com/aritr/rova/ui/theme/PinnedGlassEnvironmentTest.kt` — JVM tests for the builder.
- `app/src/test/java/com/aritr/rova/ui/theme/RecordAccentContrastTest.kt` — per-palette white-on-`accentOnDark` ≥ 3:1 (selected-fill legibility) + `accentOnDark`-on-neutral-dark ≥ 3:1 (stroke/marker legibility).

**Modify:**
- `app/src/main/java/com/aritr/rova/ui/theme/RovaPalette.kt` — add the `NeutralDarkRecordPalette` constant (encodes today's record-chrome panel/edge values as a `RovaPalette`).
- `app/src/main/java/com/aritr/rova/ui/theme/GlassResolver.kt` — tune `RecordChrome` fill alpha 0.88→`RECORD_ALPHA` (~0.40); set RecordChrome `scrim = null` (dock owns the gradient — no double-stack).
- `app/src/test/java/com/aritr/rova/ui/theme/GlassResolverTest.kt` — update RecordChrome assertions to the new contract.
- `app/src/main/java/com/aritr/rova/ui/MainScreen.kt` — (NOTE: path is `ui/MainScreen.kt`, NOT `ui/screens/`) for `isPinnedDarkRoute` routes (record/onboarding/player), each already wrapped in `RovaDarkSurface { … }`; wrap the inner content in `CompositionLocalProvider(LocalGlassEnvironment provides pinnedEnv)` built from the active env.
- `app/src/main/java/com/aritr/rova/ui/theme/Theme.kt` — expose the active `GlassEnvironment` so `MainScreen` can derive the pinned env (e.g. read `LocalGlassEnvironment.current` inside the pinned wrapper and transform it).
- `app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt` — migrate panel backings (status pill, settings card, camera-control buttons, mode chip) to `GlassSurface(role = GlassRole.RecordChrome)`; recolor the **selected** mode segment to `accentOnDark`; recolor active-recording progress/loop-pulse accent to `accentOnDark`. Dock keeps `bottomNavBrush`. Stop/recording stays `rec`.
- `app/src/main/java/com/aritr/rova/ui/screens/DualPreviewZone.kt` — verify the P+L selected-mode accent + neutral-dark env compose correctly; no blur change (carve-out preserved).
- `docs/adr/0028-liquid-glass-design-system.md` — amend §2.2/§2.4: RecordChrome fill is airy (~0.40, no-blur over live camera), scrim provided by the dock brush (not the resolver), shared `NeutralDarkRecordPalette` base for pinned routes carrying only dark-safe accents.

**No new gate** (no new invariant requiring one; existing two must stay green). **No new user-facing strings expected** (reskin); if any added → en + es.

---

## Task 1: Neutral-dark record base palette

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/theme/RovaPalette.kt`

- [ ] **Step 1: Add `NeutralDarkRecordPalette` constant**

Encode today's record-chrome panel values so `GlassSurface(role=RecordChrome)` over this base reproduces the shipped look. Glass tint = black @ ~0.40 (matches `RecordChromeTokens` panel fills); edges match `glassStroke`/`edgeTop`. Accent fields are placeholders — they are ALWAYS overwritten per-route with the active palette's dark-safe accents (Task 2).

```kotlin
/**
 * Shared cinematic neutral-dark base for pinned camera/media routes
 * (Record/Player/Onboarding). ADR-0028 §2.4: pinned routes never adopt the
 * active palette's surface colors — only its dark-safe accents (see
 * [PinnedGlassEnvironment]). Glass tint/edges encode the shipped record-chrome
 * panel values so GlassSurface(role=RecordChrome) reproduces today's airy look.
 */
internal val NeutralDarkRecordPalette = RovaPalette(
    id = ThemeSelection.AURORA, // identity slot only; never theme-derived on pinned routes
    background = Brush.verticalGradient(listOf(Color(0xFF0B0E14), Color(0xFF05070B))),
    glassTint = Color(0x66000000),          // black @ 0.40 — matches RecordChromeTokens panels
    edge = Color.White.copy(alpha = 0.09f), // matches glassStroke / settingsCardStroke
    edgeTop = Color.White.copy(alpha = 0.12f),
    accent = Color(0xFF5B9DFF),             // overwritten per-route
    accent2 = Color(0xFF7C5BFF),            // overwritten per-route
    textHigh = Color.White.copy(alpha = 0.93f),
    textDim = Color.White.copy(alpha = 0.65f),
    textFaint = Color.White.copy(alpha = 0.50f),
    accentOnDark = Color(0xFF5B9DFF),       // overwritten per-route
    accentContainerOnDark = Color(0xFF5B9DFF).copy(alpha = 0.22f), // overwritten per-route
    isLight = false,
)
```

- [ ] **Step 2: Build to verify it compiles**

Run: `./gradlew :app:assembleDebug` (controller runs all gradle).
Expected: BUILD SUCCESSFUL, all gates green.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/theme/RovaPalette.kt
git commit -m "feat(theme): NeutralDarkRecordPalette shared pinned-route base (ADR-0028 §2.4)"
```

---

## Task 2: Pure pinned-env builder + tests (TDD)

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/theme/PinnedGlassEnvironment.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/theme/PinnedGlassEnvironmentTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.aritr.rova.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PinnedGlassEnvironmentTest {

    private fun envFor(selection: ThemeSelection): GlassEnvironment =
        GlassEnvironment(
            palette = rovaPalettes.getValue(selection),
            apiLevel = 34,
            reduceTransparency = false,
        )

    @Test
    fun `pinned env uses neutral-dark surface, not the active palette surface`() {
        val pinned = PinnedGlassEnvironment.forPinnedRoute(envFor(ThemeSelection.DAYLIGHT))
        // A LIGHT active theme must NOT leak a light glass tint into the record route.
        assertEquals(NeutralDarkRecordPalette.glassTint, pinned.palette.glassTint)
        assertEquals(NeutralDarkRecordPalette.edge, pinned.palette.edge)
        assertFalse(pinned.palette.isLight)
    }

    @Test
    fun `pinned env carries the active palette dark-safe accents`() {
        val active = envFor(ThemeSelection.JADE)
        val pinned = PinnedGlassEnvironment.forPinnedRoute(active)
        assertEquals(active.palette.accentOnDark, pinned.palette.accentOnDark)
        assertEquals(active.palette.accentContainerOnDark, pinned.palette.accentContainerOnDark)
    }

    @Test
    fun `pinned env preserves apiLevel and reduceTransparency`() {
        val active = GlassEnvironment(rovaPalettes.getValue(ThemeSelection.TIDE), apiLevel = 26, reduceTransparency = true)
        val pinned = PinnedGlassEnvironment.forPinnedRoute(active)
        assertEquals(26, pinned.apiLevel)
        assertEquals(true, pinned.reduceTransparency)
    }
}
```

- [ ] **Step 2: Run test, verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.theme.PinnedGlassEnvironmentTest"`
Expected: FAIL — `PinnedGlassEnvironment` unresolved.

- [ ] **Step 3: Implement the pure builder**

```kotlin
package com.aritr.rova.ui.theme

/**
 * Pure builder for the pinned camera/media route glass environment
 * (Record/Player/Onboarding). ADR-0028 §2.4.
 *
 * Invariant (PR2, codex-reviewed 2026-06-08): pinned routes render on the
 * shared [NeutralDarkRecordPalette] surface and take ONLY the active palette's
 * dark-safe accents. A light active theme (e.g. Daylight) must never leak a
 * light glassTint/edge/isLight into a route painted over a live camera.
 */
object PinnedGlassEnvironment {
    fun forPinnedRoute(active: GlassEnvironment): GlassEnvironment =
        active.copy(
            palette = NeutralDarkRecordPalette.copy(
                accentOnDark = active.palette.accentOnDark,
                accentContainerOnDark = active.palette.accentContainerOnDark,
            ),
        )
}
```

- [ ] **Step 4: Run test, verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.theme.PinnedGlassEnvironmentTest"`
Expected: PASS (3/3).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/theme/PinnedGlassEnvironment.kt app/src/test/java/com/aritr/rova/ui/theme/PinnedGlassEnvironmentTest.kt
git commit -m "feat(theme): pure PinnedGlassEnvironment builder + tests (ADR-0028 §2.4)"
```

---

## Task 3: Tune the RecordChrome resolver branch (airy, no double-scrim)

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/theme/GlassResolver.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/theme/GlassResolverTest.kt`

- [ ] **Step 1: Update the failing test first**

Change the RecordChrome assertions to the new contract: airy fill alpha (~0.40, quant-tolerant) and `scrim == null` (dock owns the gradient).

```kotlin
@Test
fun `recordChrome fill is airy and never blurs`() {
    val env = GlassEnvironment(rovaPalettes.getValue(ThemeSelection.AURORA), apiLevel = 34, reduceTransparency = false)
    val m = GlassResolver.resolve(env, GlassRole.RecordChrome)
    assertEquals(0.dp, m.blurRadius)
    // Airy (no-blur over live camera): ~0.40, NOT the 0.88 slab. 8-bit alpha tolerance.
    assertTrue("fill alpha ${m.fill.alpha} should be ~0.40", m.fill.alpha in 0.36f..0.45f)
    assertNull("RecordChrome scrim is owned by the dock brush, not the resolver", m.scrim)
}

@Test
fun `recordChrome under reduce-transparency is opaque and unblurred`() {
    val env = GlassEnvironment(rovaPalettes.getValue(ThemeSelection.AURORA), apiLevel = 34, reduceTransparency = true)
    val m = GlassResolver.resolve(env, GlassRole.RecordChrome)
    assertEquals(0.dp, m.blurRadius)
    assertTrue(m.fill.alpha >= 0.99f)
    assertNull(m.scrim)
}
```

- [ ] **Step 2: Run test, verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.theme.GlassResolverTest"`
Expected: FAIL on the RecordChrome cases (old code returns 0.88 + a scrim).

- [ ] **Step 3: Tune the resolver**

In `GlassResolver`, introduce `private const val RECORD_ALPHA = 0.40f` (replacing the 0.88 constant for the RecordChrome branch) and set the RecordChrome `GlassMaterial.scrim = null`. Keep the reduce-transparency branch opaque + `scrim = null`. Leave BottomSheet/Dialog/Card/NavBar/Banner branches unchanged. Add an invariant KDoc tagging the PR2 design contract.

- [ ] **Step 4: Run test, verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.theme.GlassResolverTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/theme/GlassResolver.kt app/src/test/java/com/aritr/rova/ui/theme/GlassResolverTest.kt
git commit -m "feat(theme): tune RecordChrome resolver to airy no-blur fill, dock owns scrim (ADR-0028 §2.2)"
```

---

## Task 4: Per-palette accent legibility test (TDD, guards D1)

**Files:**
- Create: `app/src/test/java/com/aritr/rova/ui/theme/RecordAccentContrastTest.kt`

- [ ] **Step 1: Write the test**

Assert, for all 12 palettes, the THREE contrast relationships the selected-segment treatment actually uses (codex-reconciled 2026-06-08; verified passing for all 12 with margin). **`ContrastMath` has NO `ratio(Color, Color)`** — mirror the `ThemeContrastTest` idiom: `rgb(Color)` → `ContrastMath.relativeLuminance(r,g,b)` → `ContrastMath.contrastRatio(lumA, lumB)`; for the tinted fill use `ContrastMath.compositeAlphaOver(accent@0.22 over #0B0E14)`.

**Why three bars, not "white on solid accent":** a solid `accentOnDark` fill behind white text FAILS 3:1 for 10/12 themes (the accents are bright: white/acc ranges 1.46–3.54). So the selected segment is NOT a solid accent fill. It is `accentContainerOnDark` (accent@22%) tint over neutral-dark + an `accentOnDark` indicator + a WHITE label. The three real relationships all pass (white/comp ≥11.40, acc/surf ≥5.45, acc/comp ≥4.13):

```kotlin
package com.aritr.rova.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * Per-palette legibility of the RESTRAINED record selected-state accent
 * (ADR-0028 §2.4, codex-reconciled 2026-06-08). The selected mode segment is a
 * dark `accentContainerOnDark` (accent@22%) tint, NOT a solid bright fill — a
 * solid accentOnDark fill behind white text fails 3:1 for 10/12 themes. Three
 * bars match the three real relationships; if any palette fails, fix that
 * palette's accent in RovaPalette.kt, never the threshold.
 */
class RecordAccentContrastTest {

    // Neutral-dark record surface = NeutralDarkRecordPalette glassTint over the black camera surround.
    private val recordSurface = Color(0xFF0B0E14)

    private fun rgb(c: Color) = Triple(
        (c.red * 255).roundToInt(),
        (c.green * 255).roundToInt(),
        (c.blue * 255).roundToInt(),
    )

    private fun lum(c: Color): Double { val (r, g, b) = rgb(c); return ContrastMath.relativeLuminance(r, g, b) }
    private fun ratio(a: Color, b: Color): Double = ContrastMath.contrastRatio(lum(a), lum(b))

    /** accent@22% composited over the neutral-dark record surface → the selected-segment fill. */
    private fun selectedFill(accent: Color): IntArray {
        val (ar, ag, ab) = rgb(accent)
        val (br, bg, bb) = rgb(recordSurface)
        return ContrastMath.compositeAlphaOver(ar, ag, ab, 0.22, br, bg, bb)
    }

    private fun ratioToFill(c: Color, fill: IntArray): Double {
        val (r, g, b) = rgb(c)
        return ContrastMath.contrastRatio(
            ContrastMath.relativeLuminance(r, g, b),
            ContrastMath.relativeLuminance(fill[0], fill[1], fill[2]),
        )
    }

    @Test
    fun `selected mode segment accent is legible in every theme`() {
        ThemeSelection.entries
            .filter { it != ThemeSelection.FOLLOW_SYSTEM }
            .forEach { sel ->
                val p = rovaPalettes.getValue(sel)
                val fill = selectedFill(p.accentOnDark)

                // 1) accentOnDark indicator/marker on the neutral-dark surface (SC 1.4.11, ≥3:1).
                val accOnSurface = ratio(p.accentOnDark, recordSurface)
                assertTrue("$sel: accentOnDark-on-surface = $accOnSurface (< 3.0)", accOnSurface >= 3.0)

                // 2) white label on the tinted selected fill (normal text, ≥4.5:1).
                val whiteOnFill = ratioToFill(Color.White, fill)
                assertTrue("$sel: white-on-selectedFill = $whiteOnFill (< 4.5)", whiteOnFill >= 4.5)

                // 3) accentOnDark indicator drawn ON the tinted selected fill (SC 1.4.11, ≥3:1).
                val accOnFill = ratioToFill(p.accentOnDark, fill)
                assertTrue("$sel: accentOnDark-on-selectedFill = $accOnFill (< 3.0)", accOnFill >= 3.0)
            }
    }
}
```

- [ ] **Step 2: Run it**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.theme.RecordAccentContrastTest"`
Expected: PASS. **If any palette fails**, do NOT weaken the threshold — fix that palette's `accentOnDark` in `RovaPalette.kt` to a darker/saturated companion (spec §2.4) and re-run. Note any palette adjusted in the commit body.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/aritr/rova/ui/theme/RecordAccentContrastTest.kt app/src/main/java/com/aritr/rova/ui/theme/RovaPalette.kt
git commit -m "test(theme): per-palette record selected-accent legibility on neutral-dark (ADR-0028 §2.4)"
```

---

## Task 5: Seed the pinned env on pinned-dark routes

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/MainScreen.kt` (NOTE: `ui/`, not `ui/screens/`)
- Modify (if needed): `app/src/main/java/com/aritr/rova/ui/theme/Theme.kt`

- [ ] **Step 1: Provide the pinned env inside the pinned-dark wrapper**

In `MainScreen`, for routes where `isPinnedDarkRoute(route)` is true (record/player/onboarding), transform the current env and provide it down. Read the active env from the CompositionLocal seeded by `RovaTheme` (above `MainScreen`), build the pinned env, and provide it INSIDE `RovaDarkSurface`:

```kotlin
// inside the composable("record") { ... } pinned wrapper
RovaDarkSurface {
    val activeEnv = LocalGlassEnvironment.current
    CompositionLocalProvider(
        LocalGlassEnvironment provides PinnedGlassEnvironment.forPinnedRoute(activeEnv),
    ) {
        RecordScreen(/* … */)
    }
}
```

If `RovaDarkSurface` already nests an env provider, fold the transform into one provider. Apply the same wrapper pattern to the `player` and `onboarding` composable routes for consistency (their surfaces aren't migrated this PR, but the env must be correct so a future PR inherits it — and so the status-bar polarity writer stays correct).

- [ ] **Step 2: Confirm status-bar polarity unaffected**

The route-aware system-bar writer (`isPinnedDarkRoute`) already forces light icons on pinned routes — verify the env change does not alter that path (it should not; polarity is route-driven, not env-driven). No code change expected; note in commit if a guard was needed.

- [ ] **Step 3: Build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL, gates green.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/MainScreen.kt app/src/main/java/com/aritr/rova/ui/theme/Theme.kt
git commit -m "feat(record): seed neutral-dark pinned GlassEnvironment on camera/media routes (ADR-0028 §2.4)"
```

---

## Task 6: Migrate record panels to GlassSurface(role=RecordChrome)

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt`

- [ ] **Step 1: Replace panel backings with GlassSurface**

For each panel currently drawn as `.background(RecordChromeTokens.<fill>).border(…, RecordChromeTokens.<stroke>)`, wrap its content in `GlassSurface(role = GlassRole.RecordChrome, shape = <existing shape>)` and drop the manual fill/border. Targets (these three are black-glass panels @ ~0.40, so a single RecordChrome fill reproduces them):
- `RecordTopOverlay` status pill (was `glassFill` = Black@0.40 / `glassStroke`).
- `RecordSettingsCard` backing (was `settingsCardFill` = Black@0.40 / `settingsCardStroke`).
- `GlassCircleButton` camera-control backing (was `camControlFill` = Black@0.38 / `camControlStroke` — within quant tolerance of 0.40).

**EXCLUDE `ModeCycleChip` from this migration.** Its `modeChipFill` is **White@0.07** — a light inset control chip, NOT a black glass panel. Wrapping it in `GlassSurface(RecordChrome)` would flip it to a Black@0.40 fill → visible regression. The ModeCycleChip backing is owned by Task 7 (accent tint + indicator). Leave its `modeChipFill`/`modeChipStroke` as-is in this task.

(Optional, only if trivially clean: the R2 active-HUD `StatusPill`/`LoopPill` use `Surface(color = glassFill = Black@0.40)` — they already match the RecordChrome fill, so migrating them is allowed but NOT required; if it adds risk, leave them. Note the choice in the commit.)

Because the pinned env's `NeutralDarkRecordPalette` encodes the same values (glassTint = Black@0.40, edge = White@0.09), the rendered result matches today. The dock (`RecordBottomNav`) keeps `RecordChromeTokens.bottomNavBrush` — do NOT wrap it in GlassSurface (it IS the record scrim; avoids double-stack per codex). Leave the `rec`-family Stop/recording colors and all text-alpha tokens untouched.

- [ ] **Step 2: Verify no blur was introduced**

Run: `./gradlew :app:checkRecordSurfaceNoBlur :app:checkGlassSurfaceRoleUsage`
Expected: both PASS (GlassSurface owns no blur for RecordChrome; no `.blur` added).

- [ ] **Step 3: Build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt
git commit -m "feat(record): route record panels through GlassSurface(role=RecordChrome) (ADR-0028 §2.1)"
```

---

## Task 7: Accent the selected mode segment + active-recording markers

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt`

- [ ] **Step 1: ModeCycleChip selected/active-mode → restrained accent (tint + indicator, NOT solid fill)**

The record home's mode affordance is `ModeCycleChip` (one chip showing the current mode; the 3-segment Portrait/Landscape/P+L picker lives in the settings sheet, out of scope). Give the chip the theme's restrained accent — read `val acc = LocalGlassEnvironment.current.palette.accentOnDark`:
- Replace the chip's `modeChipFill` (White@0.07) backing with `acc.copy(alpha = 0.22f)` (= `accentContainerOnDark`-equivalent composited over the neutral-dark surface — the AA-verified tinted fill from Task 4). Keep the existing white-ish label (`cellValueText`) — Task 4 proves white-on-tinted-fill ≥ 4.5:1 for all 12.
- Replace the chip's `modeChipStroke` border with a `1.5.dp` `acc` (accentOnDark) border as the selection **indicator** (marker-on-fill ≥ 3:1 for all 12, Task 4 bar 3).
- This is **flat tint + accent border**, NOT a solid bright accent fill (a solid fill behind white text fails 3:1 for 10/12 — see Task 4). The mockup's saturated `.lpill span.on` gradient is the settings-sheet picker; the record-home chip is the restrained single token.

Do NOT accent unselected/neutral cells, the swipe hint, separators, or any passive text — D1 restraint.

- [ ] **Step 2: Active-recording progress accent — survey, do not invent**

Survey `RecordActiveHud` / `LoopPill` / `StatusPill` for a NON-semantic "advancement/progress" element that today uses a neutral or hardcoded accent. The current active HUD is: red recording **dot** (`dotRecording`, locked), slate WAITING dot, blue `MergingDotColor`, and white-on-glass pills — there is **no themed progress bar**. So:
- Do NOT add a new accent surface to the active HUD (avoids regression + keeps recording chrome neutral so the red dot is the sole active signal).
- Leave `MergingDotColor`, `dotBreak`, and the `LoopPill` numeral as-is.
- Note in the commit that no non-semantic progress accent existed, so the active-HUD accent is intentionally a no-op (personality reaches Record via the ModeCycleChip only).

- [ ] **Step 3: Confirm rec-red is untouched**

Grep the diff: no change to `dotRecording`, `fabStop*`, `stopSquare`, `fabStopRing`. The Stop FAB and recording dot remain `rec`-red in every theme.

- [ ] **Step 4: Build + full test**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; all tests green (baseline + new).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt
git commit -m "feat(record): accentOnDark on selected mode + active progress only; rec-red locked (ADR-0028 §2.4)"
```

---

## Task 8: P+L / landscape compose-correctness pass

**Files:**
- Modify (verify, minimal): `app/src/main/java/com/aritr/rova/ui/screens/DualPreviewZone.kt`
- Modify (verify): `app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt`

- [ ] **Step 1: Verify P+L composes with the pinned env + migrated chrome**

Confirm `DualPreviewZone` still renders its `RenderEffect` carve-out (non-recorded margins) unchanged — it is the allowlisted blur site, NOT chrome. Confirm the shared dock + migrated panels read the pinned env correctly when `mode == "PortraitLandscape"`. Confirm the selected-mode accent does not visually collide with the split divider (`splitDivider`) — if it does, keep the divider neutral.

- [ ] **Step 2: Verify landscape orientation themes the existing layout**

Confirm the existing landscape record layout inherits the pinned env + accent on its selected mode indicator. (No re-layout to the mockup's edge-hugging design this PR — that is the deferred PR2b.)

- [ ] **Step 3: Gates + build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL, `checkRecordSurfaceNoBlur` + `checkGlassSurfaceRoleUsage` green.

- [ ] **Step 4: Commit (only if changes were needed)**

```bash
git add -A
git commit -m "fix(record): P+L/landscape compose correctly under pinned glass env (ADR-0028)"
```

---

## Task 9: ADR-0028 amendment + final verification

**Files:**
- Modify: `docs/adr/0028-liquid-glass-design-system.md`

- [ ] **Step 1: Amend ADR-0028**

Add a PR2 amendment note under §2.2/§2.4: (a) `GlassRole.RecordChrome` fill is airy (~0.40) — appropriate for no-blur chrome over a live camera; the 0.88 slab was wrong for record and is scoped to record-only; (b) the RecordChrome resolver scrim is `null` — the record scrim is owned by the dock brush to avoid double-stacking; (c) pinned camera/media routes render on the shared `NeutralDarkRecordPalette` carrying only the active palette's `accentOnDark`/`accentContainerOnDark` (built by `PinnedGlassEnvironment`); (d) record personality = accent on selected mode + active progress only; record/stop stays locked `rec`.

- [ ] **Step 2: Full build + full test suite**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; 0 failures; all 28 `check*` gates + 2 glass gates green.

- [ ] **Step 3: Commit**

```bash
git add docs/adr/0028-liquid-glass-design-system.md
git commit -m "docs(adr): ADR-0028 PR2 amendment — record airy fill, pinned neutral-dark base, dock-owned scrim"
```

---

## Device smoke (before PR — owner-run, controller-assisted)

Install the branch APK on RZCYA1VBQ2H and verify (screenshots in each):
1. **Portrait Record home** under Aurora — looks identical to today (airy panels), selected mode shows blue accent.
2. Switch theme to **Jade** (green) then **Daylight** (light) in Settings → return to Record: panels stay neutral-dark (no light leak), selected-mode accent recolors green / blue-violet; **Start FAB stays neutral**; recording dot/Stop stays red.
3. **Start a recording** — progress/loop accent = theme accent; recording dot + Stop FAB = red; status pill legible.
4. **Landscape** record mode — themed, legible, no-blur.
5. **P+L** mode — dual preview intact, carve-out blur preserved, dock + accent correct.
6. Status-bar icons stay light on Record under every theme.
7. (If feasible) OS **Reduce Transparency** ON → record panels become opaque neutral-dark, no scrim surprise.

---

## Self-Review (run before execution)

- **Spec coverage:** §2.4 pinned-dark base ✓ (T1/T2/T5); §2.2 no-blur airy fill ✓ (T3/T6); accent-on-selected ✓ (T7); landscape+P+L ✓ (T8); per-theme AA ✓ (T4); ADR ✓ (T9).
- **Type consistency:** `PinnedGlassEnvironment.forPinnedRoute(GlassEnvironment): GlassEnvironment`; `NeutralDarkRecordPalette: RovaPalette`; `GlassRole.RecordChrome`; `ContrastMath.ratio(Color, Color)` (confirm exact name during T4 — fall back to the helper used by `ThemeContrastTest`).
- **Placeholder scan:** none — every code step shows code.
- **Gate safety:** no gate edited; T6/T8 explicitly re-run the two glass gates.
- **Open risk:** `RovaDarkSurface`'s current relationship to `LocalGlassEnvironment` (T5) — the implementer must check whether it already provides an env; the provider must wrap so the transform applies. Verified-during-implementation, not assumed.

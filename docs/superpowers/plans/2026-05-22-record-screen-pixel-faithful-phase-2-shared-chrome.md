# Record Screen Pixel-Faithful Re-skin — Phase 2 (Shared Chrome) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Re-skin the record screen's shared chrome (`RecordChrome.kt`) to pixel-match `mockups/new_uiux/01-record-home.html` by consuming the Phase-1 tokens, plus 6 custom vector icons, the recording-dot blink+glow, and the dimmed-during-recording settings card.

**Architecture:** `RecordChrome.kt` composables are token-wired in place — every hardcoded colour/dimension/`TextStyle` is replaced with a `RecordChromeTokens` / `RovaTokens` reference (both already on `master` from Phase 1). 6 custom `ImageVector`s land in a new `RecordChromeIcons.kt`. `RecordScreen.kt` composes the settings card during active sessions, dimmed. No new behaviour beyond the dimmed card; no test touched.

**Tech Stack:** Kotlin · Jetpack Compose (BOM `2025.01.01`) · `androidx.compose.ui.graphics.vector` (ImageVector DSL) · `androidx.compose.animation` (InfiniteTransition).

**Spec:** `docs/superpowers/specs/2026-05-22-record-screen-pixel-faithful-phase-2-shared-chrome-design.md`

---

## Testing Policy — Read First

Phase 2 changes are Compose composables — layout, colour, typography, one self-contained animation. The project has **no Robolectric / Compose-UI-test layer** for chrome; the JVM test layer cannot exercise composition. This matches the Phase 1, R1, and R2 precedent of no unit tests for pixel-level UI.

**Therefore: no new unit tests in Phase 2.** Verification is build + lint + the existing suite staying byte-identical green:
- `:app:assembleDebug` — every edited composable + the new icons file compiles.
- `:app:testDebugUnitTest` — the existing suite stays **byte-identical** (1026/82/0-0-0). Phase 2 adds, removes, changes **zero** tests.
- `:app:lintDebug` — issue count must not rise vs the 53 baseline.

**Gradle is subagent-routed** — the implementer subagent runs `.\gradlew.bat` directly; the controller does not.

---

## Branch & Baseline

- **Branch:** `feat/record-skin-phase-2-shared-chrome`, cut from `master` @ `29e78ff` (the Phase 2 spec commit).
- **Baseline @ `29e78ff`:**
  - `:app:testDebugUnitTest` — **1026 tests / 82 classes / 0 failures / 0 errors / 0 skipped**
  - `:app:lintDebug` — **53 issues (50 W + 3 H + 0 E)**
  - `:app:assembleDebug` — BUILD SUCCESSFUL
- **Predicted after Phase 2:** test count **unchanged** (1026/82/0-0-0); lint **≤ 53**; `assembleDebug` OK.

**Diff allowlist — `git diff master..HEAD --name-only` must equal exactly these 4 paths:**
```
app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt
app/src/main/java/com/aritr/rova/ui/screens/RecordChromeIcons.kt
app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt
docs/UI_DESIGN_TOKENS.md
```

---

## File Structure

| File | Action | Responsibility |
|---|---|---|
| `app/src/main/java/com/aritr/rova/ui/screens/RecordChromeIcons.kt` | Create | `object RecordChromeIcons` — 6 custom `ImageVector`s. |
| `app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt` | Modify | Token-wire every chrome composable; sizing fixes; FAB-stop ring; recording-dot blink+glow; settings-card `dimmed` param. |
| `app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt` | Modify | Compose the settings card during active sessions (dimmed). |
| `docs/UI_DESIGN_TOKENS.md` | Modify | One short note: the record chrome now consumes the Phase-1 tokens. |

**Note on the icon set.** Spec §6 itemised flash as three variants; the mockup actually provides a *single* flash-bolt glyph and a settings-card chevron the table omitted. This plan builds the accurate set of **6**: `flashBolt`, `flipCamera`, `library`, `settings`, `fabPlay`, `chevronUp`. The FAB-stop indicator stays a plain rounded square (no vector). This refines spec §6's itemisation; it is not a design change — the mockup is the source of truth.

---

## Task 1: Create RecordChromeIcons.kt

The 6 custom chrome glyphs, path data transcribed verbatim from the inline `<svg>` in `mockups/new_uiux/01-record-home.html`. Each is a lazily-built `ImageVector` (`by lazy` — built once, not per recomposition).

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/screens/RecordChromeIcons.kt`

**Mockup source SVGs (verbatim from `01-record-home.html`):**
- Flash bolt — `viewBox 0 0 13 15`, fill: `path d="M7.5 1L1.5 8.5H6L5 14L11.5 6.5H7L7.5 1Z"`
- Flip camera — `viewBox 0 0 16 14`, 4 stroked paths, `stroke-width 1.4`, round caps/joins:
  `M1.5 5A6 6 0 0 1 13 5` · `M14.5 9A6 6 0 0 1 3 9` · `M12 3l2 2-2 2` · `M4 7L2 9l2 2`
- Library — `viewBox 0 0 24 24`, stroked, `stroke-width 1.6`, round caps/joins: `rect x=2 y=2 w=20 h=20 rx=2.5` + lines `7,2→7,22` · `17,2→17,22` · `2,12→22,12` · `2,7→7,7` · `17,7→22,7` · `2,17→7,17` · `17,17→22,17`
- Settings (gear) — `viewBox 0 0 24 24`, stroked, `stroke-width 1.6`, round caps/joins: `circle cx=12 cy=12 r=3` + `path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-2.83-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"`
- Chevron up (settings-card arrow) — `viewBox 0 0 24 24`, stroked, `stroke-width 2.2`, round caps/joins: `polyline 18,15 12,9 6,15`
- FAB play — the mockup's `.start-tri` is a CSS-border triangle (no SVG). Built as a filled right-pointing triangle on a `24×24` viewport: `path d="M9 7L17 12L9 17Z"`.

Vector glyphs are tinted at the call site (`Icon(..., tint = …)`), so each `ImageVector` is authored with a neutral fill/stroke (`SolidColor(Color.White)` / `tint = Color.Black` default) and recoloured by the consuming `Icon`. Strokes use `StrokeCap.Round` / `StrokeJoin.Round` where the mockup specifies round.

- [ ] **Step 1: Create the file**

Create `app/src/main/java/com/aritr/rova/ui/screens/RecordChromeIcons.kt`:

```kotlin
package com.aritr.rova.ui.screens

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Phase 2 — custom vector icons for the record-screen chrome, reproducing the
 * inline `<svg>` glyphs in `mockups/new_uiux/01-record-home.html` exactly
 * (Material icons are visually off vs the mockup). Path data is verbatim from
 * the mockup; viewport matches each glyph's `viewBox`.
 *
 * Glyphs are authored neutral (white fill / white stroke); the consuming
 * `Icon(..., tint = …)` recolours them, so one `ImageVector` serves every
 * tint state. Each vector is built once via `by lazy`.
 */
object RecordChromeIcons {

    /** `.cam-ctrl-btn` flash glyph — the mockup's single lightning bolt. */
    val flashBolt: ImageVector by lazy {
        ImageVector.Builder(
            name = "RecordChromeFlashBolt",
            defaultWidth = 13.dp, defaultHeight = 15.dp,
            viewportWidth = 13f, viewportHeight = 15f,
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(7.5f, 1f)
                lineTo(1.5f, 8.5f)
                horizontalLineTo(6f)
                lineTo(5f, 14f)
                lineTo(11.5f, 6.5f)
                horizontalLineTo(7f)
                lineTo(7.5f, 1f)
                close()
            }
        }.build()
    }

    /** `.cam-ctrl-btn` flip-camera glyph — two arcs + two arrowheads. */
    val flipCamera: ImageVector by lazy {
        ImageVector.Builder(
            name = "RecordChromeFlipCamera",
            defaultWidth = 16.dp, defaultHeight = 14.dp,
            viewportWidth = 16f, viewportHeight = 14f,
        ).apply {
            val stroke = SolidColor(Color.White)
            path(stroke = stroke, strokeLineWidth = 1.4f, strokeLineCap = StrokeCap.Round) {
                moveTo(1.5f, 5f)
                arcTo(6f, 6f, 0f, false, true, 13f, 5f)
            }
            path(stroke = stroke, strokeLineWidth = 1.4f, strokeLineCap = StrokeCap.Round) {
                moveTo(14.5f, 9f)
                arcTo(6f, 6f, 0f, false, true, 3f, 9f)
            }
            path(stroke = stroke, strokeLineWidth = 1.4f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 3f)
                lineToRelative(2f, 2f)
                lineToRelative(-2f, 2f)
            }
            path(stroke = stroke, strokeLineWidth = 1.4f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(4f, 7f)
                lineTo(2f, 9f)
                lineToRelative(2f, 2f)
            }
        }.build()
    }

    /** `.nav-ico` Library glyph — bordered grid. */
    val library: ImageVector by lazy {
        ImageVector.Builder(
            name = "RecordChromeLibrary",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            val stroke = SolidColor(Color.White)
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
                path(stroke = stroke, strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                    moveTo(x1, y1); lineTo(x2, y2)
                }
            // rounded outer rect (rx 2.5)
            path(stroke = stroke, strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(4.5f, 2f)
                horizontalLineTo(19.5f)
                arcTo(2.5f, 2.5f, 0f, false, true, 22f, 4.5f)
                verticalLineTo(19.5f)
                arcTo(2.5f, 2.5f, 0f, false, true, 19.5f, 22f)
                horizontalLineTo(4.5f)
                arcTo(2.5f, 2.5f, 0f, false, true, 2f, 19.5f)
                verticalLineTo(4.5f)
                arcTo(2.5f, 2.5f, 0f, false, true, 4.5f, 2f)
                close()
            }
            line(7f, 2f, 7f, 22f)
            line(17f, 2f, 17f, 22f)
            line(2f, 12f, 22f, 12f)
            line(2f, 7f, 7f, 7f)
            line(17f, 7f, 22f, 7f)
            line(2f, 17f, 7f, 17f)
            line(17f, 17f, 22f, 17f)
        }.build()
    }

    /** `.nav-ico` Settings glyph — circle + gear. */
    val settings: ImageVector by lazy {
        ImageVector.Builder(
            name = "RecordChromeSettings",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            val stroke = SolidColor(Color.White)
            // inner circle r=3
            path(stroke = stroke, strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(15f, 12f)
                arcTo(3f, 3f, 0f, true, true, 9f, 12f)
                arcTo(3f, 3f, 0f, true, true, 15f, 12f)
                close()
            }
            // gear body — verbatim mockup path
            path(stroke = stroke, strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(19.4f, 15f)
                arcToRelative(1.65f, 1.65f, 0f, false, false, 0.33f, 1.82f)
                lineToRelative(0.06f, 0.06f)
                arcToRelative(2f, 2f, 0f, true, true, -2.83f, 2.83f)
                lineToRelative(-0.06f, -0.06f)
                arcToRelative(1.65f, 1.65f, 0f, false, false, -2.83f, -0.33f)
                arcToRelative(1.65f, 1.65f, 0f, false, false, -1f, 1.51f)
                verticalLineTo(21f)
                arcToRelative(2f, 2f, 0f, false, true, -4f, 0f)
                verticalLineToRelative(-0.09f)
                arcToRelative(1.65f, 1.65f, 0f, false, false, -1.08f, -1.51f)
                arcToRelative(1.65f, 1.65f, 0f, false, false, -1.82f, 0.33f)
                lineToRelative(-0.06f, 0.06f)
                arcToRelative(2f, 2f, 0f, true, true, -2.83f, -2.83f)
                lineToRelative(0.06f, -0.06f)
                arcToRelative(1.65f, 1.65f, 0f, false, false, 0.33f, -1.82f)
                arcToRelative(1.65f, 1.65f, 0f, false, false, -1.51f, -1f)
                horizontalLineTo(3f)
                arcToRelative(2f, 2f, 0f, false, true, 0f, -4f)
                horizontalLineToRelative(0.09f)
                arcTo(1.65f, 1.65f, 0f, false, false, 4.6f, 9f)
                arcToRelative(1.65f, 1.65f, 0f, false, false, -0.33f, -1.82f)
                lineToRelative(-0.06f, -0.06f)
                arcToRelative(2f, 2f, 0f, true, true, 2.83f, -2.83f)
                lineToRelative(0.06f, 0.06f)
                arcTo(1.65f, 1.65f, 0f, false, false, 9f, 4.68f)
                arcTo(1.65f, 1.65f, 0f, false, false, 10f, 3.17f)
                verticalLineTo(3f)
                arcToRelative(2f, 2f, 0f, false, true, 4f, 0f)
                verticalLineToRelative(0.09f)
                arcToRelative(1.65f, 1.65f, 0f, false, false, 1f, 1.51f)
                arcToRelative(1.65f, 1.65f, 0f, false, false, 1.82f, -0.33f)
                lineToRelative(0.06f, -0.06f)
                arcToRelative(2f, 2f, 0f, true, true, 2.83f, 2.83f)
                lineToRelative(-0.06f, 0.06f)
                arcTo(1.65f, 1.65f, 0f, false, false, 19.4f, 9f)
                arcToRelative(1.65f, 1.65f, 0f, false, false, 1.51f, 1f)
                horizontalLineTo(21f)
                arcToRelative(2f, 2f, 0f, false, true, 0f, 4f)
                horizontalLineToRelative(-0.09f)
                arcToRelative(1.65f, 1.65f, 0f, false, false, -1.51f, 1f)
                close()
            }
        }.build()
    }

    /** `.settings-arrow` chevron — points up (expand sheet). */
    val chevronUp: ImageVector by lazy {
        ImageVector.Builder(
            name = "RecordChromeChevronUp",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(stroke = SolidColor(Color.White), strokeLineWidth = 2.2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(18f, 15f)
                lineTo(12f, 9f)
                lineTo(6f, 15f)
            }
        }.build()
    }

    /** `.center-btn .start-tri` — the FAB Start triangle (mockup uses a CSS triangle). */
    val fabPlay: ImageVector by lazy {
        ImageVector.Builder(
            name = "RecordChromeFabPlay",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(9f, 7f)
                lineTo(17f, 12f)
                lineTo(9f, 17f)
                close()
            }
        }.build()
    }
}
```

> The mockup provides one flash glyph. The app's flash control is tri-state
> (auto / on / off) — Task 4 keeps the existing tint distinction (ON = yellow)
> and renders the OFF state as `flashBolt` plus a drawn slash. AUTO and ON both
> use the bare `flashBolt`. No auto/off glyph is invented — the mockup has none.

- [ ] **Step 2: Compile-gate**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. (Proves all 6 `ImageVector.Builder` chains compile — arc/line path DSL calls are the likely failure point.)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/RecordChromeIcons.kt
git commit -m "feat(ui): add RecordChromeIcons — 6 custom chrome vector glyphs"
```

---

## Task 2: Token-wire colours, shapes & sizing in RecordChrome.kt

Replace the private style constants and inline literals with `RecordChromeTokens` references. `RecordChromeTokens` is on `master` (Phase 1) in package `com.aritr.rova.ui.theme`.

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt`

- [ ] **Step 1: Add the import**

At the top of `RecordChrome.kt`, in the import block, add (keep imports sorted — it goes after `com.aritr.rova.service.RovaRecordingService`):

```kotlin
import com.aritr.rova.ui.theme.RecordChromeTokens
```

- [ ] **Step 2: Delete the three private style-constant blocks**

Delete the constant block at lines ~53-63:

```kotlin
// Screen-local style constants (see mockups/new_uiux/01-record-home.html .status-pill / .loop-pill;
// docs/UI_DESIGN_TOKENS.md decides any of these the tokens doc promotes to MaterialTheme.*).
private val GlassFill = Color.Black.copy(alpha = 0.40f)
private val GlassStroke = Color.White.copy(alpha = 0.07f)
private val RecordingDotColor = Color(0xFFEF4444)   // red
private val WaitingDotColor   = Color(0xFFFBBF24)   // amber (matches WarningCenter's AmberWarning + SoftSheet accent)
private val MergingDotColor   = Color(0xFF60A5FA)   // blue
private val StatusPillShape = RoundedCornerShape(20.dp)
private val PillShape = RoundedCornerShape(11.dp)
private val ControlBtnSize = 30.dp          // visible glass-circle diameter
private val ControlBtnTouchSize = 48.dp     // a11y touch target (the glass circle is centered inside)
```

Replace it with:

```kotlin
// Phase 2 — record chrome consumes the mockup token set (RecordChromeTokens,
// docs/UI_DESIGN_TOKENS.md §2.13). Only values with no token stay local:
// the merging-dot colour (the mockup defines no merging dot) and the 48 dp
// a11y touch box (an interaction metric, not a mockup pixel).
private val MergingDotColor = Color(0xFF60A5FA)   // blue — no mockup token (mockup has idle/recording/break only)
private val ControlBtnTouchSize = 48.dp           // a11y touch target; the glass circle is centered inside
private val StatusPillShape = RoundedCornerShape(RecordChromeTokens.statusPillRadius)
private val PillShape = RoundedCornerShape(RecordChromeTokens.loopPillRadius)
private val SettingsCardShape = RoundedCornerShape(RecordChromeTokens.settingsCardRadius)
```

Then delete the now-duplicated `SettingsCardShape` block at lines ~194-198:

```kotlin
// ── RecordSettingsCard style constants ──
private val SettingsCardShape = RoundedCornerShape(14.dp)
private val SettingsCardFill = Color.White.copy(alpha = 0.065f)
private val SettingsCardStroke = Color.White.copy(alpha = 0.09f)
private val CellDivider = Color.White.copy(alpha = 0.07f)
```

(`SettingsCardShape` moved up alongside the other shapes; `SettingsCardFill` / `SettingsCardStroke` / `CellDivider` are replaced by tokens at their call sites — Steps 4-6.)

Then delete the `RecordBottomNav` constant block at lines ~264-267:

```kotlin
// ── RecordBottomNav style constants ──
private val BottomNavFill = Color.Black.copy(alpha = 0.50f)
private val BottomNavStroke = Color.White.copy(alpha = 0.055f)
private val FabSize = 56.dp
```

- [ ] **Step 3: Re-point `RecordTopOverlay`'s status pill**

In `RecordTopOverlay`, the status-pill `Row` modifier currently reads:

```kotlin
            modifier = Modifier
                .clip(StatusPillShape)
                .background(GlassFill)
                .border(1.dp, GlassStroke, StatusPillShape)
                .padding(horizontal = 11.dp, vertical = 6.dp),
```

Change to:

```kotlin
            modifier = Modifier
                .clip(StatusPillShape)
                .background(RecordChromeTokens.glassFill)
                .border(1.dp, RecordChromeTokens.glassStroke, StatusPillShape)
                .padding(
                    horizontal = RecordChromeTokens.statusPillPaddingH,
                    vertical = RecordChromeTokens.statusPillPaddingV,
                ),
```

And the `horizontalArrangement = Arrangement.spacedBy(7.dp)` on that same `Row` → `Arrangement.spacedBy(RecordChromeTokens.pillContentGap)`. The `verticalArrangement = Arrangement.spacedBy(8.dp)` on the outer `Column` → `Arrangement.spacedBy(RecordChromeTokens.topOverlayGap)`.

- [ ] **Step 4: Re-point the idle `StatusDot`**

The idle `StatusDot(hudState:)` at lines ~103-106:

```kotlin
@Composable
private fun StatusDot(hudState: RecordHudState) {
    Box(Modifier.size(6.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.25f)))
}
```

Change to:

```kotlin
@Composable
private fun StatusDot(hudState: RecordHudState) {
    Box(
        Modifier
            .size(RecordChromeTokens.dotSize)
            .clip(CircleShape)
            .background(RecordChromeTokens.dotIdle),
    )
}
```

- [ ] **Step 5: Re-point `GlassCircleButton`**

In `GlassCircleButton`, the inner `Box` modifier:

```kotlin
            modifier = Modifier
                .size(ControlBtnSize)
                .clip(CircleShape)
                .background(GlassFill)
                .border(1.dp, GlassStroke, CircleShape),
```

Change to (the camera buttons get their own `camControl*` tokens — distinct alpha from the pills):

```kotlin
            modifier = Modifier
                .size(RecordChromeTokens.camControlSize)
                .clip(CircleShape)
                .background(RecordChromeTokens.camControlFill)
                .border(1.dp, RecordChromeTokens.camControlStroke, CircleShape),
```

And in `RecordCameraControls`, `verticalArrangement = Arrangement.spacedBy(7.dp)` → `Arrangement.spacedBy(RecordChromeTokens.camControlGap)`.

- [ ] **Step 6: Re-point `RecordSettingsCard` colours & paddings**

In `RecordSettingsCard`, the swipe-bar `Box`:

```kotlin
            Box(Modifier.width(30.dp).height(2.dp).clip(RoundedCornerShape(1.dp)).background(Color.White.copy(alpha = 0.22f)))
```

Change to:

```kotlin
            Box(
                Modifier
                    .width(RecordChromeTokens.swipeBarWidth)
                    .height(RecordChromeTokens.swipeBarHeight)
                    .clip(RoundedCornerShape(1.dp))
                    .background(RecordChromeTokens.swipeHint),
            )
```

The settings-card `Row` modifier currently:

```kotlin
                .clip(SettingsCardShape)
                .background(SettingsCardFill)
                .border(1.dp, SettingsCardStroke, SettingsCardShape)
                .clickable { onOpenSheet() }
                .pointerInput(Unit) {
                    detectVerticalDragGestures { _, dragAmount -> if (dragAmount < -8f) onOpenSheet() }
                }
                .padding(horizontal = 12.dp, vertical = 8.dp),
```

Change to:

```kotlin
                .clip(SettingsCardShape)
                .background(RecordChromeTokens.settingsCardFill)
                .border(1.dp, RecordChromeTokens.settingsCardStroke, SettingsCardShape)
                .clickable { onOpenSheet() }
                .pointerInput(Unit) {
                    detectVerticalDragGestures { _, dragAmount -> if (dragAmount < -8f) onOpenSheet() }
                }
                .padding(
                    horizontal = RecordChromeTokens.settingsCardPaddingH,
                    vertical = RecordChromeTokens.settingsCardPaddingV,
                ),
```

The outer `Column`'s `verticalArrangement = Arrangement.spacedBy(7.dp)` → `Arrangement.spacedBy(RecordChromeTokens.settingsWrapGap)`.

In `CellSep`, `.background(CellDivider)` → `.background(RecordChromeTokens.cellDivider)`.

In `SettingsCell`, `modifier.padding(horizontal = 3.dp)` → `modifier.padding(horizontal = RecordChromeTokens.settingsCellPaddingH)`.

- [ ] **Step 7: Re-point `RecordBottomNav`**

In `RecordBottomNav`, the `Row` modifier:

```kotlin
        modifier = modifier
            .fillMaxWidth()
            .background(BottomNavFill)
            .border(width = 1.dp, color = BottomNavStroke, shape = RoundedCornerShape(0.dp))
            .windowInsetsPadding(WindowInsets.navigationBars)   // clear the gesture-nav bar
            .padding(horizontal = 28.dp, vertical = 14.dp),
```

Change to (note: bottom padding 14 → `bottomNavPaddingBottom` 18; top stays 14 — the mockup only specifies a bottom pad, top is the visual breathing room above the FAB):

```kotlin
        modifier = modifier
            .fillMaxWidth()
            .background(RecordChromeTokens.bottomNavFill)
            .border(width = 1.dp, color = RecordChromeTokens.bottomNavTopStroke, shape = RoundedCornerShape(0.dp))
            .windowInsetsPadding(WindowInsets.navigationBars)   // clear the gesture-nav bar
            .padding(
                start = RecordChromeTokens.bottomNavPaddingH,
                end = RecordChromeTokens.bottomNavPaddingH,
                top = 14.dp,
                bottom = RecordChromeTokens.bottomNavPaddingBottom,
            ),
```

- [ ] **Step 8: Re-point `NavItem` sizing**

In `NavItem`, `verticalArrangement = Arrangement.spacedBy(3.dp)` → `Arrangement.spacedBy(RecordChromeTokens.navItemGap)`. Leave the `Icon` `.size(34.dp)` and colours for now — Task 4 reworks `NavItem` into the 42 dp icon-box with the custom glyph.

- [ ] **Step 9: Re-point `RecordFab`**

In `RecordFab`, the state `Triple`:

```kotlin
        RecordFabState.Start -> Triple(Color.White.copy(alpha = 0.07f), Color.White.copy(alpha = 0.15f), "Start recording")
        RecordFabState.Stop -> Triple(Color(0xFFEF4444).copy(alpha = 0.13f), Color(0xFFEF4444).copy(alpha = 0.30f), "Stop recording")
        RecordFabState.Disabled -> Triple(Color.White.copy(alpha = 0.04f), Color.White.copy(alpha = 0.08f), "Start recording (unavailable)")
```

Change to:

```kotlin
        RecordFabState.Start -> Triple(RecordChromeTokens.fabStartFill, RecordChromeTokens.fabStartStroke, "Start recording")
        RecordFabState.Stop -> Triple(RecordChromeTokens.fabStopFill, RecordChromeTokens.fabStopStroke, "Stop recording")
        RecordFabState.Disabled -> Triple(Color.White.copy(alpha = 0.04f), Color.White.copy(alpha = 0.08f), "Start recording (unavailable)")
```

(`Disabled` keeps inline literals — the mockup has no disabled FAB; that state is app-only.)

The FAB `Box` `.size(FabSize)` → `.size(RecordChromeTokens.fabSize)`.

The `Stop` square: `Box(Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFFEF4444)))` →

```kotlin
            RecordFabState.Stop -> Box(
                Modifier
                    .size(RecordChromeTokens.stopSquareSize)
                    .clip(RoundedCornerShape(RecordChromeTokens.stopSquareRadius))
                    .background(RecordChromeTokens.stopSquare),
            )
```

- [ ] **Step 10: Re-point `LoopPill` / `StatusPill` glass + shapes**

In the private `LoopPill`, the `Surface`:

```kotlin
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = GlassFill,
        contentColor = Color.White,
        border = BorderStroke(1.dp, GlassStroke),
    ) {
```

Change to (mockup loop-pill radius = 11):

```kotlin
    Surface(
        modifier = modifier,
        shape = PillShape,
        color = RecordChromeTokens.glassFill,
        contentColor = Color.White,
        border = BorderStroke(1.dp, RecordChromeTokens.glassStroke),
    ) {
```

In the private `StatusPill`, the `Surface`:

```kotlin
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        color = GlassFill,
        contentColor = Color.White,
        border = BorderStroke(1.dp, GlassStroke),
    ) {
```

Change to (mockup status-pill radius = 20):

```kotlin
    Surface(
        modifier = modifier,
        shape = StatusPillShape,
        color = RecordChromeTokens.glassFill,
        contentColor = Color.White,
        border = BorderStroke(1.dp, RecordChromeTokens.glassStroke),
    ) {
```

The active `StatusDot(dot: StatusDotColor)` colour `when`:

```kotlin
    val color = when (dot) {
        StatusDotColor.RECORDING -> RecordingDotColor
        StatusDotColor.WAITING   -> WaitingDotColor
        StatusDotColor.MERGING   -> MergingDotColor
    }
```

Change to (the **amber → slate** waiting-dot correction):

```kotlin
    val color = when (dot) {
        StatusDotColor.RECORDING -> RecordChromeTokens.dotRecording
        StatusDotColor.WAITING   -> RecordChromeTokens.dotBreak   // slate #94A3B8 — mockup .dot-break
        StatusDotColor.MERGING   -> MergingDotColor
    }
```

- [ ] **Step 11: Compile-gate**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. If a `GlassFill` / `GlassStroke` / `BottomNavFill` / `SettingsCardFill` / `CellDivider` / `FabSize` reference is unresolved, a call site was missed — search the file for the old name and re-point it.

- [ ] **Step 12: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt
git commit -m "feat(ui): token-wire RecordChrome colours, shapes & sizing to RecordChromeTokens"
```

---

## Task 3: Token-wire typography & text colours in RecordChrome.kt

Swap `MaterialTheme.typography.*` chrome text for the `RovaTokens` styles and the `Color.White.copy(alpha = …)` text fills for `RecordChromeTokens.*Text`. This is where the mockup letter-spacing / weights land.

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt`

- [ ] **Step 1: Add the import**

Add to the import block, after the `RecordChromeTokens` import from Task 2:

```kotlin
import com.aritr.rova.ui.theme.RovaTokens
```

- [ ] **Step 2: `RecordTopOverlay` status text**

The two `Text`s in `RecordTopOverlay`:

```kotlin
            StatusDot(hudState)
            Text(statusText, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.65f))
            if (statusDetail != null) {
                Text("· $statusDetail", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.32f))
            }
```

Change to:

```kotlin
            StatusDot(hudState)
            Text(statusText, style = RovaTokens.statusMain, color = RecordChromeTokens.statusMainText)
            if (statusDetail != null) {
                Text("· $statusDetail", style = RovaTokens.statusTime, color = RecordChromeTokens.statusTimeText)
            }
```

- [ ] **Step 3: `RecordSettingsCard` swipe label**

```kotlin
            Text("SWIPE TO EDIT", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.22f))
```

Change to:

```kotlin
            Text("SWIPE TO EDIT", style = RovaTokens.swipeLabel, color = RecordChromeTokens.swipeHint)
```

- [ ] **Step 4: `SettingsCell` text**

```kotlin
        Text(value, style = MaterialTheme.typography.labelLarge, color = if (readOnly) Color.White.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.88f), textAlign = TextAlign.Center, maxLines = 1)
        Text(key.uppercase(), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.28f), textAlign = TextAlign.Center, maxLines = 1)
```

Change to:

```kotlin
        Text(
            value,
            style = RovaTokens.cellValue,
            color = if (readOnly) RecordChromeTokens.cellValueReadOnlyText else RecordChromeTokens.cellValueText,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        Text(
            key.uppercase(),
            style = RovaTokens.cellKey,
            color = RecordChromeTokens.cellKeyText,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
```

- [ ] **Step 5: `NavItem` label text**

```kotlin
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = if (enabled) 0.32f else 0.12f))
```

Change to (enabled colour from token; disabled stays an inline literal — the mockup has no disabled nav state):

```kotlin
        Text(
            label,
            style = RovaTokens.navTxt,
            color = if (enabled) RecordChromeTokens.navText else Color.White.copy(alpha = 0.12f),
        )
```

- [ ] **Step 6: `LoopPill` — split numeral + caption**

The mockup loop pill is a big numeral (`.loop-count`, `RovaTokens.loopCount`) plus a small caption (`.loop-unit`, `RovaTokens.loopUnit`). The current single-`Text` body must become a baseline-aligned `Row`.

Replace the `LoopPill` body (the `Surface { Text(...) }` block):

```kotlin
@Composable
private fun LoopPill(loopIndex: Int, loopTotal: Int, modifier: Modifier = Modifier) {
    val text = loopPillContent(loopIndex, loopTotal) ?: return       // hide pill on single-clip / zero-clip
    Surface(
        modifier = modifier,
        shape = PillShape,
        color = RecordChromeTokens.glassFill,
        contentColor = Color.White,
        border = BorderStroke(1.dp, RecordChromeTokens.glassStroke),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}
```

with:

```kotlin
@Composable
private fun LoopPill(loopIndex: Int, loopTotal: Int, modifier: Modifier = Modifier) {
    // loopPillContent is the tested (untouched) hide-gate: null ⇒ single-clip /
    // zero-clip ⇒ no pill. The mockup splits the body into a numeral + a caption,
    // so the numeral is re-derived here with the same clamp loopPillContent uses.
    loopPillContent(loopIndex, loopTotal) ?: return
    val numeral = if (loopTotal < 0) {
        "${loopIndex.coerceAtLeast(0)}"
    } else {
        "${loopIndex.coerceIn(0, loopTotal)}/$loopTotal"
    }
    Surface(
        modifier = modifier,
        shape = PillShape,
        color = RecordChromeTokens.glassFill,
        contentColor = Color.White,
        border = BorderStroke(1.dp, RecordChromeTokens.glassStroke),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = RecordChromeTokens.loopPillPaddingH,
                vertical = RecordChromeTokens.loopPillPaddingV,
            ),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(RecordChromeTokens.loopPillContentGap),
        ) {
            Text(numeral, style = RovaTokens.loopCount, color = RecordChromeTokens.loopCountText)
            Text("LOOPS DONE", style = RovaTokens.loopUnit, color = RecordChromeTokens.loopUnitText)
        }
    }
}
```

> `loopPillContent` (and its tests) stay byte-identical — it is still called as
> the hide-gate. The numeral re-derivation is a 4-line formatting duplication of
> the clamp, deliberate: changing `loopPillContent`'s return shape would break
> its unit tests and the byte-identical-suite invariant.

- [ ] **Step 7: `StatusPill` (active HUD) text**

The two `Text`s in the private `StatusPill`:

```kotlin
            StatusDot(content.dot)
            Text(
                content.main,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
            )
            Text(
                content.time,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.75f),
            )
```

Change to:

```kotlin
            StatusDot(content.dot)
            Text(
                content.main,
                style = RovaTokens.statusMain,
                color = RecordChromeTokens.statusMainText,
            )
            Text(
                content.time,
                style = RovaTokens.statusTime,
                color = RecordChromeTokens.statusTimeText,
            )
```

Also re-point `StatusPill`'s `Row` padding `horizontal = 12.dp, vertical = 8.dp` → `horizontal = RecordChromeTokens.statusPillPaddingH, vertical = RecordChromeTokens.statusPillPaddingV` and `horizontalArrangement = Arrangement.spacedBy(8.dp)` → `Arrangement.spacedBy(RecordChromeTokens.pillContentGap)`.

- [ ] **Step 8: Compile-gate**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt
git commit -m "feat(ui): token-wire RecordChrome typography & text colours to RovaTokens"
```

---

## Task 4: Wire custom icons + the 42 dp nav-icon box + FAB-stop ring

Replace Material icons with `RecordChromeIcons`, rebuild `NavItem` as the mockup's 42 dp rounded icon-box, and add the FAB-stop outer ring.

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt`

- [ ] **Step 1: Drop the now-unused Material icon imports**

These imports become unused after this task — delete them from the import block:

```kotlin
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlipCameraIos
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
```

KEEP `import androidx.compose.material.icons.Icons` and `import androidx.compose.material.icons.filled.History as HistoryIcon` and `import androidx.compose.material.icons.filled.Settings as SettingsIcon` — `RecordRecoveryChip` still uses `Icons.Default.HistoryIcon`. `SettingsIcon` becomes unused (Task replaces the nav Settings icon) — remove `import androidx.compose.material.icons.filled.Settings as SettingsIcon` too.
After this step, of the `material.icons` imports only `Icons` and `History as HistoryIcon` remain. Verify by searching the file for `Icons.Default.` — the sole survivor must be `Icons.Default.HistoryIcon` in `RecordRecoveryChip`.

- [ ] **Step 2: Flash button — `RecordCameraControls`**

The flash `GlassCircleButton` body:

```kotlin
        GlassCircleButton(onClick = onCycleFlash, enabled = enabled) {
            val (icon, contentTint) = when (flashMode) {
                RovaRecordingService.FLASH_MODE_ON -> Icons.Default.FlashOn to (if (enabled) Color.Yellow else tint)
                RovaRecordingService.FLASH_MODE_AUTO -> Icons.Default.FlashAuto to tint
                else -> Icons.Default.FlashOff to tint
            }
            Icon(icon, contentDescription = "Flash", tint = contentTint)
        }
```

Change to (the mockup has one bolt; ON tints it yellow, OFF overlays a slash):

```kotlin
        GlassCircleButton(onClick = onCycleFlash, enabled = enabled) {
            val isOff = flashMode != RovaRecordingService.FLASH_MODE_ON &&
                flashMode != RovaRecordingService.FLASH_MODE_AUTO
            val contentTint = when {
                flashMode == RovaRecordingService.FLASH_MODE_ON && enabled -> Color.Yellow
                else -> tint
            }
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    RecordChromeIcons.flashBolt,
                    contentDescription = "Flash",
                    tint = contentTint,
                    modifier = Modifier.size(15.dp),
                )
                if (isOff) {
                    // OFF state — diagonal slash across the bolt (mockup shows
                    // only the bolt; the slash is the app's tri-state affordance).
                    Box(
                        Modifier
                            .size(width = 20.dp, height = 1.5.dp)
                            .rotate(-45f)
                            .background(contentTint),
                    )
                }
            }
        }
```

Add the imports this needs to the import block:

```kotlin
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.rotate
```

(`androidx.compose.foundation.layout.size` is already imported — verify; add only `rotate`. `Box`, `Alignment`, `background` are already imported.)

- [ ] **Step 3: Flip button — `RecordCameraControls`**

```kotlin
        GlassCircleButton(onClick = onFlip, enabled = flipEnabled) {
            Icon(Icons.Default.FlipCameraIos, contentDescription = "Flip camera", tint = flipTint)
        }
```

Change to:

```kotlin
        GlassCircleButton(onClick = onFlip, enabled = flipEnabled) {
            Icon(
                RecordChromeIcons.flipCamera,
                contentDescription = "Flip camera",
                tint = flipTint,
                modifier = Modifier.size(16.dp),
            )
        }
```

- [ ] **Step 4: Settings-card chevron — `RecordSettingsCard`**

```kotlin
            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Edit session settings", tint = Color.White.copy(alpha = 0.18f), modifier = Modifier.padding(start = 6.dp))
```

Change to:

```kotlin
            Icon(
                RecordChromeIcons.chevronUp,
                contentDescription = "Edit session settings",
                tint = RecordChromeTokens.settingsArrow,
                modifier = Modifier.padding(start = 8.dp).size(13.dp),
            )
```

- [ ] **Step 5: Rebuild `NavItem` as the 42 dp icon-box**

The mockup's `.nav-ico` is a 42×42 rounded box (radius 12) holding a 20 dp glyph. Replace the whole `NavItem`:

```kotlin
@Composable
private fun NavItem(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(RecordChromeTokens.navItemGap),
        modifier = if (enabled) Modifier.clickable { onClick() } else Modifier,
    ) {
        Icon(icon, contentDescription = label, tint = Color.White.copy(alpha = if (enabled) 0.4f else 0.14f), modifier = Modifier.size(34.dp))
        Text(
            label,
            style = RovaTokens.navTxt,
            color = if (enabled) RecordChromeTokens.navText else Color.White.copy(alpha = 0.12f),
        )
    }
}
```

with:

```kotlin
@Composable
private fun NavItem(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(RecordChromeTokens.navItemGap),
        modifier = if (enabled) Modifier.clickable { onClick() } else Modifier,
    ) {
        Box(
            modifier = Modifier
                .size(RecordChromeTokens.navIconBoxSize)
                .clip(RoundedCornerShape(RecordChromeTokens.navIconCornerRadius)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (enabled) RecordChromeTokens.navIcon else Color.White.copy(alpha = 0.14f),
                modifier = Modifier.size(RecordChromeTokens.navIconGlyphSize),
            )
        }
        Text(
            label,
            style = RovaTokens.navTxt,
            color = if (enabled) RecordChromeTokens.navText else Color.White.copy(alpha = 0.12f),
        )
    }
}
```

- [ ] **Step 6: Point `RecordBottomNav` at the custom nav glyphs**

In `RecordBottomNav`:

```kotlin
        NavItem(icon = Icons.Default.PhotoLibrary, label = "Library", enabled = !navItemsLocked, onClick = onLibrary)
        RecordFab(state = fabState, onClick = onFabClick)
        NavItem(icon = Icons.Default.SettingsIcon, label = "Settings", enabled = !navItemsLocked, onClick = onSettings)
```

Change to:

```kotlin
        NavItem(icon = RecordChromeIcons.library, label = "Library", enabled = !navItemsLocked, onClick = onLibrary)
        RecordFab(state = fabState, onClick = onFabClick)
        NavItem(icon = RecordChromeIcons.settings, label = "Settings", enabled = !navItemsLocked, onClick = onSettings)
```

- [ ] **Step 7: FAB play glyph + Stop outer ring — `RecordFab`**

The `RecordFab` content `when`:

```kotlin
        when (state) {
            RecordFabState.Stop -> Box(
                Modifier
                    .size(RecordChromeTokens.stopSquareSize)
                    .clip(RoundedCornerShape(RecordChromeTokens.stopSquareRadius))
                    .background(RecordChromeTokens.stopSquare),
            )
            RecordFabState.Start -> Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White.copy(alpha = 0.78f), modifier = Modifier.size(26.dp))
            RecordFabState.Disabled -> Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White.copy(alpha = 0.25f), modifier = Modifier.size(26.dp))
        }
```

Change to:

```kotlin
        when (state) {
            RecordFabState.Stop -> Box(
                Modifier
                    .size(RecordChromeTokens.stopSquareSize)
                    .clip(RoundedCornerShape(RecordChromeTokens.stopSquareRadius))
                    .background(RecordChromeTokens.stopSquare),
            )
            RecordFabState.Start -> Icon(RecordChromeIcons.fabPlay, contentDescription = null, tint = Color.White.copy(alpha = 0.78f), modifier = Modifier.size(22.dp))
            RecordFabState.Disabled -> Icon(RecordChromeIcons.fabPlay, contentDescription = null, tint = Color.White.copy(alpha = 0.25f), modifier = Modifier.size(22.dp))
        }
```

Add the FAB-stop outer ring. The current `RecordFab` body `Box`:

```kotlin
    val enabled = state != RecordFabState.Disabled
    Box(
        modifier = Modifier
            .size(RecordChromeTokens.fabSize)
            .clip(CircleShape)
            .background(fill)
            .border(1.5.dp, stroke, CircleShape)
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .semantics { contentDescription = semanticsLabel },
        contentAlignment = Alignment.Center,
    ) {
```

Change to (wrap the FAB in an outer `Box` that draws the ring only in the Stop state):

```kotlin
    val enabled = state != RecordFabState.Disabled
    Box(contentAlignment = Alignment.Center) {
        if (state == RecordFabState.Stop) {
            // .btn-stop::after — a ring inset -5 dp (extends outward).
            Box(
                Modifier
                    .size(RecordChromeTokens.fabSize + RecordChromeTokens.fabStopRingInset * 2)
                    .clip(CircleShape)
                    .border(1.dp, RecordChromeTokens.fabStopRing, CircleShape),
            )
        }
        Box(
            modifier = Modifier
                .size(RecordChromeTokens.fabSize)
                .clip(CircleShape)
                .background(fill)
                .border(1.5.dp, stroke, CircleShape)
                .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
                .semantics { contentDescription = semanticsLabel },
            contentAlignment = Alignment.Center,
        ) {
```

— and add the matching extra closing brace for the new outer `Box` at the end of `RecordFab` (the function currently ends `... } }` after the content `when`; it now ends with one more `}`).

- [ ] **Step 8: Compile-gate**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. An unbalanced-brace error in `RecordFab` is the likely failure — confirm the new outer `Box` opens and closes exactly once.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt
git commit -m "feat(ui): wire custom chrome icons, 42dp nav-icon box & FAB-stop ring"
```

---

## Task 5: Recording-dot blink + glow

The mockup's `.dot-recording` blinks (`blink 1.8s ease-in-out infinite`) with a red glow (`box-shadow: 0 0 8px`). Add this to the **Recording** branch of the active `StatusDot(dot:)` — idle / break / merging dots stay static.

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt`

- [ ] **Step 1: Add the animation + draw imports**

Add to the import block:

```kotlin
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
```

- [ ] **Step 2: Rework the active `StatusDot(dot:)`**

Replace the whole private `StatusDot(dot: StatusDotColor, ...)`:

```kotlin
@Composable
private fun StatusDot(dot: StatusDotColor, modifier: Modifier = Modifier) {
    val color = when (dot) {
        StatusDotColor.RECORDING -> RecordChromeTokens.dotRecording
        StatusDotColor.WAITING   -> RecordChromeTokens.dotBreak   // slate #94A3B8 — mockup .dot-break
        StatusDotColor.MERGING   -> MergingDotColor
    }
    Box(
        modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color),
    )
}
```

with:

```kotlin
@Composable
private fun StatusDot(dot: StatusDotColor, modifier: Modifier = Modifier) {
    val color = when (dot) {
        StatusDotColor.RECORDING -> RecordChromeTokens.dotRecording
        StatusDotColor.WAITING   -> RecordChromeTokens.dotBreak   // slate #94A3B8 — mockup .dot-break
        StatusDotColor.MERGING   -> MergingDotColor
    }
    if (dot == StatusDotColor.RECORDING) {
        // mockup `.dot-recording` — blink 1.8s ease-in-out + a red glow.
        // Compose has no box-shadow; the glow is a radial-gradient halo drawn
        // behind the dot, pulsing on the same transition as the dot alpha.
        val transition = rememberInfiniteTransition(label = "recordingDot")
        val pulse by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1800),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "recordingDotPulse",
        )
        Box(
            modifier
                .size(20.dp)                       // 8 dp dot + room for the ~8 dp halo
                .drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                color.copy(alpha = 0.55f * pulse),
                                color.copy(alpha = 0f),
                            ),
                            radius = size.minDimension / 2f,
                        ),
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = pulse)),
            )
        }
    } else {
        Box(
            modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
    }
}
```

> The `InfiniteTransition` runs only while this composable is in composition —
> i.e. only during an active recording session (the active HUD is unmounted at
> Idle). No idle-screen battery cost.

- [ ] **Step 3: Compile-gate**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt
git commit -m "feat(ui): recording status-dot blink + radial-halo glow"
```

---

## Task 6: RecordSettingsCard — `dimmed` parameter

Add a `dimmed` flag so the card can render present-but-non-interactive during an active session (mockup behaviour).

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt`

- [ ] **Step 1: Add the `alpha` import**

Add to the import block:

```kotlin
import androidx.compose.ui.draw.alpha
```

- [ ] **Step 2: Add the parameter + apply it**

The `RecordSettingsCard` signature:

```kotlin
@Composable
fun RecordSettingsCard(
    durationSeconds: Int,
    loopCount: Int,
    intervalMinutes: Int,
    quality: String,
    mode: String,
    onOpenSheet: () -> Unit,
    modifier: Modifier = Modifier,
) {
```

Change to (add `dimmed`, defaulted `false` so existing call sites are unaffected until Task 7):

```kotlin
@Composable
fun RecordSettingsCard(
    durationSeconds: Int,
    loopCount: Int,
    intervalMinutes: Int,
    quality: String,
    mode: String,
    onOpenSheet: () -> Unit,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
) {
```

The outer `Column`:

```kotlin
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(RecordChromeTokens.settingsWrapGap)) {
```

Change to (75% opacity when dimmed):

```kotlin
    Column(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (dimmed) 0.75f else 1f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(RecordChromeTokens.settingsWrapGap),
    ) {
```

The settings-card `Row` modifier currently chains `.clickable { onOpenSheet() }` and the `detectVerticalDragGestures` `pointerInput`. Make both no-ops when dimmed — the card is read-only during recording. Change:

```kotlin
                .clickable { onOpenSheet() }
                .pointerInput(Unit) {
                    detectVerticalDragGestures { _, dragAmount -> if (dragAmount < -8f) onOpenSheet() }
                }
```

to:

```kotlin
                .then(
                    if (dimmed) {
                        Modifier
                    } else {
                        Modifier
                            .clickable { onOpenSheet() }
                            .pointerInput(Unit) {
                                detectVerticalDragGestures { _, dragAmount ->
                                    if (dragAmount < -8f) onOpenSheet()
                                }
                            }
                    },
                )
```

- [ ] **Step 3: Compile-gate**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt
git commit -m "feat(ui): RecordSettingsCard gains dimmed (read-only) mode"
```

---

## Task 7: RecordScreen — settings card during active sessions

Render `RecordSettingsCard` in every HUD state, dimmed when not Idle. This reverses the R2 idle-only decision per the mockup.

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt`

**Hard invariant:** do NOT touch the Start-gate region (the `startBlocked` derivation near line ~108-123, `WarningId`, `WarningPrecedence`, `WarningCenter`, `onStart`). This task edits only the bottom-area `when` and adds one composable call.

- [ ] **Step 1: Remove the settings card from the Idle-only branch**

In the bottom-area `when (hudState)`, the `RecordHudState.Idle` branch currently contains the whole `if (!showCompleteCard) { RecordSettingsCard(...) }` block (lines ~550-581). Delete the `RecordSettingsCard(...)` call **and** the `if (!showCompleteCard)` wrapper from inside the `Idle` branch, leaving the `Idle ->` arm with only its explanatory comment and an empty body:

```kotlin
                when (hudState) {
                    RecordHudState.Idle -> {
                        // Idle: camera-first body. The settings card is rendered
                        // once for all states below the `when` (dimmed when active).
                    }
                    RecordHudState.Recording,
                    RecordHudState.Waiting,
                    is RecordHudState.Merging -> {
```

(The `Recording / Waiting / Merging` branch with the `RecordActiveHud` Column is **unchanged**.)

- [ ] **Step 2: Render the settings card once, below the `when`**

Immediately AFTER the closing brace of the `when (hudState)` block (after the active-HUD branch, before the `// ── Task 13/14 — new chrome` comment / the `if (hudState is RecordHudState.Idle) { RecordTopOverlay(...) }` block at ~line 619), insert:

```kotlin
                // Phase 2 — the settings card is shared chrome: shown in every
                // HUD state, dimmed + read-only while a session is active
                // (mockups/new_uiux/01-record-home.html — the recording/break
                // states show the card at 75% opacity). Suppressed only during
                // the brief post-merge MergeCompleteCard grace window.
                if (!showCompleteCard) {
                    RecordSettingsCard(
                        durationSeconds = duration,
                        loopCount = loopCount,
                        intervalMinutes = interval,
                        quality = resolution,
                        mode = if (mode == "PortraitLandscape") "P + L" else mode,
                        onOpenSheet = { viewModel.openSettingsSheet() },
                        dimmed = hudState != RecordHudState.Idle,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .padding(
                                bottom = RecordChromeMetrics.bottomNavClearance,
                                start = 16.dp,
                                end = 16.dp,
                            ),
                    )
                }
```

This reuses the exact arguments the old idle-only call used (`duration`, `loopCount`, `interval`, `resolution`, the `mode` "P + L" display map, `viewModel.openSettingsSheet()`, the `BottomCenter` + `navigationBars` + `bottomNavClearance` modifier). The only additions are `dimmed = hudState != RecordHudState.Idle` and that it is no longer inside the `Idle` arm.

> `RecordChromeMetrics.bottomNavClearance` (90 dp) is kept as the card's bottom
> inset — unchanged from the idle layout, so the active-state card lands exactly
> where the idle card did. Re-tuning that metric is out of Phase 2 scope.

- [ ] **Step 3: Compile-gate**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. A `when` non-exhaustive error means the `Idle ->` arm was deleted rather than emptied — it must stay as `RecordHudState.Idle -> { }`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt
git commit -m "feat(ui): show the settings card dimmed during active recording"
```

---

## Task 8: Update UI_DESIGN_TOKENS.md

**Files:**
- Modify: `docs/UI_DESIGN_TOKENS.md`

- [ ] **Step 1: Read the doc**

Read `docs/UI_DESIGN_TOKENS.md` in full. Locate §2.13 (the "Record-chrome constants / `RecordChromeTokens`" section added in Phase 1) and §2.2 (typography).

- [ ] **Step 2: Mark the tokens as consumed**

§2.13 (and the §2.2 `RovaTokens` type-scale note) currently describe `RecordChromeTokens` / the 7 new type styles as "declared by Phase 1, consumed by Phase 2/3". Update that wording: as of Phase 2 the **record-screen shared chrome (`RecordChrome.kt`) consumes these tokens** — they are no longer unused. Note that the 6 custom chrome glyphs live in `ui/screens/RecordChromeIcons.kt`. Keep it to a short paragraph; do not restructure the doc.

- [ ] **Step 3: Commit**

```bash
git add docs/UI_DESIGN_TOKENS.md
git commit -m "docs(ui): UI_DESIGN_TOKENS — record chrome now consumes the Phase-1 tokens"
```

---

## Task 9: Full-suite gate & invariant verification

**Files:** none — verification only.

- [ ] **Step 1: assembleDebug**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Unit-test suite**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: **1026 tests / 82 classes / 0 failures / 0 errors / 0 skipped** — byte-identical to the `29e78ff` baseline. Phase 2 adds, removes, and changes **zero** tests. Any drift means a non-chrome file (a tested helper — likely `loopPillContent`) was changed — investigate before proceeding.

- [ ] **Step 3: Lint**

Run: `.\gradlew.bat :app:lintDebug`
Expected: **≤ 53 issues (50 W + 3 H + 0 E)** — unchanged vs baseline. The likely new finding is an unused import in `RecordChrome.kt` (a Material-icon import missed in Task 4, Step 1) — if the count rose, read the finding and resolve it.

- [ ] **Step 4: Diff allowlist**

Run: `git diff master..HEAD --name-only`
Expected: **exactly** the 4 paths from the "Diff allowlist" section — `RecordChrome.kt`, `RecordChromeIcons.kt`, `RecordScreen.kt`, `docs/UI_DESIGN_TOKENS.md`. Any extra path means a hard-invariant file (`WarningId`, `WarningPrecedence`, `service/**`) or an out-of-scope composable was touched — revert it.

- [ ] **Step 5: Report**

Report the three gate results, the diff file list, and any deviations. No commit (verification task).

---

## Hard Invariants (verify untouched)

- `WarningId` / `WarningPrecedence` / `WarningCenterViewModel` / the VM-factory wiring.
- `RecordScreen.kt` Start-gate region (`startBlocked` derivation, ~L108-123).
- `ui/warnings/**` and their tests.
- All `service/**`, `dualrecord/**`, recording-pipeline code.
- `loopPillContent` / `hudStatusPillContent` / `recordFabState` / `RecordFabState` / `StatusDotColor` / `StatusPillContent` — public/internal signatures and behaviour unchanged (their tests stay green).
- The unit-test suite: **1026 / 82 / 0-0-0**, byte-identical.

---

## Owner Follow-Up (not implementer scope)

- **On-device smoke** — install on the Samsung SM-A176B; compare the re-skinned chrome to `mockups/new_uiux/01-record-home.html` across idle / recording / break states; confirm the recording-dot blink+glow; confirm the dimmed (75%, non-tappable) settings card during recording; confirm the custom flash / flip / Library / Settings / FAB glyphs render and the OFF-flash slash reads correctly.
- **Phase 3** — mode-specific framing (dual zone-tags, split divider, landscape letterbox) + decorative overlays (rule-of-thirds grid, edge vignette, focus reticle). Own spec → plan → PR.
- After Phase 2 is merge-ready: push `master` (carries Phase 1) + the Phase 2 branch, open the PR (base `master`).

---

## Self-Review

- **Spec coverage:** custom icons (Task 1) · colour/shape/sizing tokens incl. waiting-dot fix + FAB-stop ring tokens (Task 2) · typography + text colours + loop-pill split (Task 3) · custom-icon wiring + 42 dp nav box + FAB-stop ring render (Task 4) · recording-dot blink+glow (Task 5) · settings-card `dimmed` (Task 6) · `RecordScreen` active-state card (Task 7) · docs (Task 8) · verification + allowlist (Task 9). All spec §5-§9 items covered.
- **Placeholders:** none. Every code step shows the full before/after. Icon path data is verbatim from the mockup (§Task 1). The flash tri-state / single-bolt gap is resolved explicitly (Task 4 Step 2 — slash overlay for OFF), not deferred.
- **Type consistency:** `RecordChromeTokens` / `RovaTokens` member names match the Phase-1 definitions exactly (`glassFill`, `statusPillRadius`, `loopPillContentGap`, `dotBreak`, `statusMainText`, `loopCount`, `navTxt`, …). `RecordChromeIcons.{flashBolt, flipCamera, library, settings, chevronUp, fabPlay}` defined in Task 1 are exactly the symbols Task 4 consumes. `RecordSettingsCard`'s new `dimmed` param (Task 6) is the one Task 7 passes. `loopPillContent` is referenced (Task 3) but never redefined — its signature and tests are untouched.

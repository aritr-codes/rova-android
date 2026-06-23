# Icon System P1a — Slice 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax. **Subagents EDIT-ONLY; the controller runs all gradle + commits.**

**Goal:** Prove the two-layer duotone glyph pipeline end-to-end by authoring 4 System-D glyphs (Library, Settings, Sort, Record) into a `RovaGlyphs` home, rendering them through a new `SemanticIcon(glyph: RovaGlyph, …)` overload, and wiring them at their existing chrome call-sites.

**Architecture:** A pure-data `RovaGlyph(outline, accent?)` type; bespoke `ImageVector`s authored in a `RovaGlyphs` object from `board-3-semantic.html` path data (24-grid, 1.9px stroke, round caps/joins); a `SemanticIcon` composable overload that stacks the two layers (outline tinted by role/status via the P0 `SemanticIconSpec`, accent tinted by `palette.accent`). `RovaIcons` repoints the 4 concepts at `RovaGlyph`s.

**Tech Stack:** Kotlin 2.2.10, Jetpack Compose, `ImageVector.Builder`/`PathBuilder` DSL, JUnit4 JVM tests (`isReturnDefaultValues = true`), the P0 seam (`SemanticIcon`/`SemanticIconSpec`/`RovaIcons` on `master d8c02c6`).

---

## Source path data (from `board-3-semantic.html`, 24×24 grid)

| Glyph | Outline layer | Accent (`.ac2`) layer |
|---|---|---|
| **lib_stack** (Library) | `rect x=3.5 y=8 w=13 h=11 rx=2` (stroke) | `rect x=6 y=4.2 w=13 h=9.4 rx=2` (stroke) |
| **set_gear** (Settings) | `circle 12,12 r=4.3` + 8 spokes (stroke) | `circle 12,12 r=1.8` (fill) |
| **sort_bars** (Sort) | `M5 7 h13`, `M5 12 h9` (stroke) | `M5 17 h5` (stroke) |
| **rec_disc** (Record) | `circle 12,12 r=6.8` (fill) | — (mono) |

8 gear spokes (stroke segments): `12,3.4→12,5.7` · `12,18.3→12,20.6` · `3.4,12→5.7,12` · `18.3,12→20.6,12` · `5.9,5.9→7.5,7.5` · `16.5,16.5→18.1,18.1` · `18.1,5.9→16.5,7.5` · `7.5,16.5→5.9,18.1`.

---

## File structure

**New:**
- `app/src/main/java/com/aritr/rova/ui/theme/RovaGlyph.kt` — the `RovaGlyph` data type.
- `app/src/main/java/com/aritr/rova/ui/theme/RovaGlyphs.kt` — the bespoke `ImageVector` home (4 glyphs + private path-builder helpers).
- `app/src/test/java/com/aritr/rova/ui/theme/RovaGlyphsTest.kt` — structural JVM tests.

**Modified:**
- `app/src/main/java/com/aritr/rova/ui/components/SemanticIcon.kt` — add the `RovaGlyph` overload.
- `app/src/main/java/com/aritr/rova/ui/theme/RovaIcons.kt` — repoint Library/Settings/Sort, add Record.
- `app/src/test/java/com/aritr/rova/ui/theme/RovaIconsTest.kt` — update for the type change.
- `app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt` — nav (Library/Settings) + FAB (Record) call-sites.
- `app/src/main/java/com/aritr/rova/ui/library/components/LibraryTopBar.kt` — Sort call-site.

**Not touched / deferred:** `RecordChromeIcons.kt` (full fold-in + `checkRovaGlyphHome` gate = later slice); the theme engine; glass-chip/animation (P2).

---

## Task 0: Branch + baseline (controller)

- [ ] **Step 1:** `git switch -c feat/icon-p1a-slice1`
- [ ] **Step 2:** `./gradlew.bat :app:testDebugUnitTest` → BUILD SUCCESSFUL (baseline incl. P0 tests).

---

## Task 1: `RovaGlyph` type + `RovaGlyphs` home (4 glyphs)

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/theme/RovaGlyph.kt`
- Create: `app/src/main/java/com/aritr/rova/ui/theme/RovaGlyphs.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/theme/RovaGlyphsTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.aritr.rova.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RovaGlyphsTest {

    @Test fun library_is_duotone_stacked_frames() {
        assertNotNull(RovaGlyphs.Library.outline)
        assertNotNull("Library accent (top frame) drives the duotone channel", RovaGlyphs.Library.accent)
    }

    @Test fun settings_is_duotone_gear() {
        assertNotNull(RovaGlyphs.Settings.outline)
        assertNotNull(RovaGlyphs.Settings.accent)
    }

    @Test fun sort_is_duotone_bars() {
        assertNotNull(RovaGlyphs.Sort.outline)
        assertNotNull(RovaGlyphs.Sort.accent)
    }

    @Test fun record_disc_is_mono() {
        assertNotNull(RovaGlyphs.Record.outline)
        assertNull("Record action disc is a single mono mark", RovaGlyphs.Record.accent)
    }

    @Test fun all_glyphs_use_the_24_grid() {
        listOf(RovaGlyphs.Library, RovaGlyphs.Settings, RovaGlyphs.Sort, RovaGlyphs.Record).forEach { g ->
            assertEquals(24f, g.outline.viewportWidth, 0f)
            assertEquals(24f, g.outline.viewportHeight, 0f)
            g.accent?.let {
                assertEquals(24f, it.viewportWidth, 0f)
                assertEquals(24f, it.viewportHeight, 0f)
            }
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.theme.RovaGlyphsTest"`
Expected: FAIL — `RovaGlyph` / `RovaGlyphs` unresolved.

- [ ] **Step 3: Write `RovaGlyph.kt`**

```kotlin
package com.aritr.rova.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * A System-D bespoke glyph (ADR-0031 §1/§5). Two tintable layers: a mono-safe [outline] that carries
 * the meaning on its own, and an optional duotone [accent] channel (the board's `.ac2` group) tinted
 * with the palette accent. Rendered by [com.aritr.rova.ui.components.SemanticIcon].
 */
@Immutable
data class RovaGlyph(val outline: ImageVector, val accent: ImageVector? = null)
```

- [ ] **Step 4: Write `RovaGlyphs.kt`**

```kotlin
package com.aritr.rova.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.unit.dp

/**
 * Bespoke System-D glyphs (ADR-0031 §5), authored from `board-3-semantic.html` on the 24-grid,
 * 1.9px monoline, round caps/joins. Placeholder colors are overridden by `Icon(tint = …)` in
 * [com.aritr.rova.ui.components.SemanticIcon]. This is the start of the bespoke `RovaGlyphs` home;
 * `RecordChromeIcons` folds in here in a later slice.
 */
object RovaGlyphs {
    // Library — stacked frames (back = outline, top = accent). Un-collides Play (spec §8).
    val Library = RovaGlyph(
        outline = glyph { strokePath { roundRect(3.5f, 8f, 13f, 11f, 2f) } },
        accent = glyph { strokePath { roundRect(6f, 4.2f, 13f, 9.4f, 2f) } },
    )

    // Settings — soft 8-spoke gear (ring + spokes = outline, center dot = accent). spec §7.1.
    val Settings = RovaGlyph(
        outline = glyph {
            strokePath {
                circle(12f, 12f, 4.3f)
                seg(12f, 3.4f, 12f, 5.7f)
                seg(12f, 18.3f, 12f, 20.6f)
                seg(3.4f, 12f, 5.7f, 12f)
                seg(18.3f, 12f, 20.6f, 12f)
                seg(5.9f, 5.9f, 7.5f, 7.5f)
                seg(16.5f, 16.5f, 18.1f, 18.1f)
                seg(18.1f, 5.9f, 16.5f, 7.5f)
                seg(7.5f, 16.5f, 5.9f, 18.1f)
            }
        },
        accent = glyph { fillPath { circle(12f, 12f, 1.8f) } },
    )

    // Sort — state-free decreasing bars (top two = outline, short bottom = accent). spec §7.2.
    val Sort = RovaGlyph(
        outline = glyph {
            strokePath {
                seg(5f, 7f, 18f, 7f)
                seg(5f, 12f, 14f, 12f)
            }
        },
        accent = glyph { strokePath { seg(5f, 17f, 10f, 17f) } },
    )

    // Record — solid accent disc, the FAB capture action (mono). spec §8 / canonical map.
    val Record = RovaGlyph(
        outline = glyph { fillPath { circle(12f, 12f, 6.8f) } },
    )

    // ── authoring helpers ───────────────────────────────────────────────────
    private const val SW = 1.9f
    private val PLACEHOLDER = SolidColor(Color.Black) // overridden by Icon tint

    private fun glyph(build: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply(build).build()

    private fun ImageVector.Builder.strokePath(path: PathBuilder.() -> Unit) {
        path(
            stroke = PLACEHOLDER, strokeLineWidth = SW,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            pathBuilder = path,
        )
    }

    private fun ImageVector.Builder.fillPath(path: PathBuilder.() -> Unit) {
        path(fill = PLACEHOLDER, pathBuilder = path)
    }

    /** A straight segment (SVG `M x1 y1 L x2 y2`). */
    private fun PathBuilder.seg(x1: Float, y1: Float, x2: Float, y2: Float) {
        moveTo(x1, y1); lineTo(x2, y2)
    }

    /**
     * A full circle as two semicircle arcs (SVG `<circle cx cy r>`). Each arc is exactly 180° →
     * `isMoreThanHalf = false`. (large-arc-flag `true` requests an arc >180° and is renderer-dependent
     * at exactly 180° — codex-flagged; matters for the filled disc + gear ring.)
     */
    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx, cy - r)
        arcToRelative(r, r, 0f, false, true, 0f, 2 * r)
        arcToRelative(r, r, 0f, false, true, 0f, -2 * r)
        close()
    }

    /** A rounded rect (SVG `<rect x y width height rx>`). */
    private fun PathBuilder.roundRect(x: Float, y: Float, w: Float, h: Float, rx: Float) {
        moveTo(x + rx, y)
        lineTo(x + w - rx, y); arcToRelative(rx, rx, 0f, false, true, rx, rx)
        lineTo(x + w, y + h - rx); arcToRelative(rx, rx, 0f, false, true, -rx, rx)
        lineTo(x + rx, y + h); arcToRelative(rx, rx, 0f, false, true, -rx, -rx)
        lineTo(x, y + rx); arcToRelative(rx, rx, 0f, false, true, rx, -rx)
        close()
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.theme.RovaGlyphsTest"`
Expected: PASS (5 tests). If `path(...)` named-arg `pathBuilder` mismatches the Compose version, the alternative is the trailing-lambda form `path(stroke = …, strokeLineWidth = …, strokeLineCap = …, strokeLineJoin = …) { … }` — the subagent adapts to the resolved `ImageVector.Builder.path` signature and re-runs.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/theme/RovaGlyph.kt app/src/main/java/com/aritr/rova/ui/theme/RovaGlyphs.kt app/src/test/java/com/aritr/rova/ui/theme/RovaGlyphsTest.kt
git commit -m "feat(icons): RovaGlyph two-layer type + 4 System-D glyphs (ADR-0031 P1a slice 1)"
```

---

## Task 2: `SemanticIcon(glyph: RovaGlyph, …)` overload

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/components/SemanticIcon.kt`

- [ ] **Step 1: Add the overload** (below the existing `ImageVector` overload; keep that one)

```kotlin
/**
 * Two-layer overload for bespoke [RovaGlyph]s (ADR-0031 §1). Stacks the outline (tinted by role or
 * status via [SemanticIconSpec]) and the optional duotone accent layer (tinted by the palette accent —
 * the retintable channel). `status` locks the whole mark and suppresses the separate accent tint.
 * Callers MUST pass a size in [modifier] (both layers use `matchParentSize`).
 */
@Composable
fun SemanticIcon(
    glyph: RovaGlyph,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    role: IconRole = IconRole.Default,
    status: IconStatus? = null,
) {
    val palette = LocalGlassEnvironment.current.palette
    val baseTint = if (status != null) SemanticIconSpec.statusTint(status) else SemanticIconSpec.tint(palette, role)
    Box(modifier) {
        Icon(glyph.outline, contentDescription, Modifier.matchParentSize(), tint = baseTint)
        glyph.accent?.let { acc ->
            val accentTint = if (status != null) baseTint else palette.accent
            Icon(acc, contentDescription = null, Modifier.matchParentSize(), tint = accentTint)
        }
    }
}
```

Add imports: `androidx.compose.foundation.layout.Box`, `androidx.compose.foundation.layout.matchParentSize` is a `BoxScope` member (no import needed), `com.aritr.rova.ui.theme.RovaGlyph`. (`IconRole`, `IconStatus`, `SemanticIconSpec`, `LocalGlassEnvironment`, `Icon`, `Modifier` already imported.)

- [ ] **Step 2: Compile**

Run: `./gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL. (`Box` is the only new import likely needed; the subagent adds whatever the compiler reports missing.)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/components/SemanticIcon.kt
git commit -m "feat(icons): SemanticIcon RovaGlyph overload — two-layer duotone render (ADR-0031 P1a slice 1)"
```

---

## Task 3: Repoint `RovaIcons` + update its test

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/theme/RovaIcons.kt`
- Modify: `app/src/test/java/com/aritr/rova/ui/theme/RovaIconsTest.kt`

The current `RovaIcons` holds `RovaIcon(glyph: ImageVector, status)` vals. Convert the four now-bespoke concepts to `RovaGlyph`-typed vals; keep `RovaIcon` for the still-stock concepts (View, WarningStatus, NotificationsSetting, Play).

- [ ] **Step 1: Update `RovaIcons.kt`** to:

```kotlin
package com.aritr.rova.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.ui.graphics.vector.ImageVector
import com.aritr.rova.ui.screens.RecordChromeIcons

/**
 * Canonical concept→glyph map (ADR-0031 §2/§5). One concept → exactly one glyph. Bespoke System-D
 * concepts resolve to a [RovaGlyph] (two-layer); concepts still on stock Material stay [RovaIcon]
 * until authored in a later slice.
 */
data class RovaIcon(val glyph: ImageVector, val status: IconStatus? = null)

object RovaIcons {
    // ── Bespoke System-D (RovaGlyph) ──
    val Library: RovaGlyph = RovaGlyphs.Library
    val Settings: RovaGlyph = RovaGlyphs.Settings
    val Sort: RovaGlyph = RovaGlyphs.Sort
    val Record: RovaGlyph = RovaGlyphs.Record

    // ── Still stock Material (RovaIcon), authored in a later slice ──
    val View = RovaIcon(Icons.Default.GridView)
    val Play = RovaIcon(RecordChromeIcons.fabPlay)
    val WarningStatus = RovaIcon(Icons.Default.WarningAmber, status = IconStatus.Warning)
    val NotificationsSetting = RovaIcon(Icons.Default.Notifications)
}
```

- [ ] **Step 2: Update `RovaIconsTest.kt`** to:

```kotlin
package com.aritr.rova.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RovaIconsTest {

    @Test fun warning_status_concept_carries_a_locked_status_role() {
        assertEquals(IconStatus.Warning, RovaIcons.WarningStatus.status)
    }

    @Test fun notifications_setting_is_not_a_status() {
        assertNull(RovaIcons.NotificationsSetting.status)
    }

    @Test fun library_and_play_are_distinct_glyphs() {
        assertNotEquals(RovaIcons.Library.outline, RovaIcons.Play.glyph)
    }

    @Test fun settings_and_view_are_distinct_glyphs() {
        assertNotEquals(RovaIcons.Settings.outline, RovaIcons.View.glyph)
    }

    @Test fun bespoke_concepts_resolve_to_RovaGlyphs() {
        assertEquals(RovaGlyphs.Library, RovaIcons.Library)
        assertEquals(RovaGlyphs.Settings, RovaIcons.Settings)
        assertEquals(RovaGlyphs.Sort, RovaIcons.Sort)
        assertEquals(RovaGlyphs.Record, RovaIcons.Record)
    }
}
```

- [ ] **Step 3: Run tests**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.theme.RovaIconsTest"`
Expected: PASS (5 tests).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/theme/RovaIcons.kt app/src/test/java/com/aritr/rova/ui/theme/RovaIconsTest.kt
git commit -m "refactor(icons): RovaIcons repoints Library/Settings/Sort/Record to RovaGlyphs (ADR-0031 P1a slice 1)"
```

---

## Task 4: Wire RecordChrome nav (Library/Settings) + FAB (Record)

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt`

- [ ] **Step 1: Read the file.** Locate (a) the bottom-nav `NavItem` rendering for Library and Settings — currently rendering `RecordChromeIcons.library` / `RecordChromeIcons.settings` (after P0, the nav glyph at the former line ~762 is a plain `Icon(... navIcon/SemanticIconSpec ...)`), and (b) the `RecordFab` Start/Disabled states — after P0 these are `SemanticIcon(RecordChromeIcons.fabPlay, role = IconRole.Default/Disabled, …)`.

- [ ] **Step 2: Migrate the nav glyphs.** Replace the Library and Settings nav `Icon(...)`/`SemanticIcon(imageVector=…)` calls with the `RovaGlyph` overload, preserving the enabled/disabled role logic and the existing size modifier (`RecordChromeTokens.navIconGlyphSize`):

```kotlin
// Library nav item:
SemanticIcon(
    glyph = RovaIcons.Library,
    contentDescription = <existing cd>,
    role = if (enabled) IconRole.Accent else IconRole.Disabled,
    modifier = Modifier.size(RecordChromeTokens.navIconGlyphSize),
)
// Settings nav item: same shape with RovaIcons.Settings + its existing cd.
```

(If the two nav items share a single `NavItem(glyph: ImageVector, …)` helper, change that helper to take a `RovaGlyph` and call the new overload; pass `RovaIcons.Library` / `RovaIcons.Settings` from the two call-sites. Preserve the existing role/enabled logic.)

- [ ] **Step 3: Migrate the FAB Record glyph.** Replace the Start and Disabled `SemanticIcon(RecordChromeIcons.fabPlay, …)` with the Record disc glyph (keep the `Modifier.size(22.dp)` and the role per state):

```kotlin
RecordFabState.Start    -> SemanticIcon(glyph = RovaIcons.Record, contentDescription = null, role = IconRole.Default, modifier = Modifier.size(22.dp))
RecordFabState.Disabled -> SemanticIcon(glyph = RovaIcons.Record, contentDescription = null, role = IconRole.Disabled, modifier = Modifier.size(22.dp))
```

Leave the `RecordFabState.Stop` red-square `Box` exactly as-is (out of scope; `rec_morph` is a later slice). Do not remove `RecordChromeIcons.library/settings/fabPlay` from `RecordChromeIcons.kt` — other code/states may still reference them and the fold-in is a later slice.

- [ ] **Step 4: Compile**

Run: `./gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt
git commit -m "feat(icons): RecordChrome nav + FAB use RovaGlyphs (Library/Settings/Record) (ADR-0031 P1a slice 1)"
```

---

## Task 5: Wire LibraryTopBar Sort

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/library/components/LibraryTopBar.kt`

- [ ] **Step 1: Read the file.** Locate the Sort `Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = <cd>, …)` (around line 92).

- [ ] **Step 2: Migrate** to the glyph overload, preserving the contentDescription and the existing size modifier (`LibraryDimens.navIcon` = 22.dp, or whatever it currently uses):

```kotlin
SemanticIcon(
    glyph = RovaIcons.Sort,
    contentDescription = <existing cd>,
    role = IconRole.Secondary,
    modifier = <existing size modifier>,
)
```

Add imports: `com.aritr.rova.ui.components.SemanticIcon`, `com.aritr.rova.ui.theme.IconRole`, `com.aritr.rova.ui.theme.RovaIcons`. Remove the `Icons.AutoMirrored.Filled.Sort` import and the `androidx.compose.material3.Icon` import only if no longer used in the file.

- [ ] **Step 3: Compile**

Run: `./gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/components/LibraryTopBar.kt
git commit -m "feat(icons): LibraryTopBar sort uses RovaGlyphs.Sort (ADR-0031 P1a slice 1)"
```

---

## Task 6: Full build + gates + suite

- [ ] **Step 1:** `./gradlew.bat :app:assembleDebug` → BUILD SUCCESSFUL (all 44 gates via preBuild; the new `SemanticIcon` accent uses `palette.accent` not a raw `Color`, and `RovaGlyphs` uses `SolidColor(Color.Black)` on `stroke=`/`fill=` not `tint=`, so `checkSemanticIconNoRawAlpha` stays green; no `RovaSemantics.copy`, so `checkStatusColorLocked` stays green).
- [ ] **Step 2:** `./gradlew.bat :app:testDebugUnitTest` → BUILD SUCCESSFUL (P0 + `RovaGlyphsTest` (5) + updated `RovaIconsTest` (5)).

---

## Task 7: Device smoke (owner) + finish

- [ ] **Step 1 (controller):** `adb install -r app/build/outputs/apk/debug/app-debug.apk` (PowerShell-direct).
- [ ] **Step 2 (owner):** On RZCYA1VBQ2H — confirm: the bottom-nav **Library** glyph is now stacked-frames, **Settings** is the 8-spoke gear, the **FAB Start** is the record disc (was a play triangle), library **Sort** is the decreasing bars; the duotone accent layers (Library top frame, gear center dot, sort short bar) pick up the theme accent and **retint on a theme switch**; glyphs read cleanly at 20–22 dp over the camera surface; disabled FAB still reads disabled.
- [ ] **Step 3:** On GO → `superpowers:finishing-a-development-branch` (merge to master locally).

---

## Self-review

- **Spec coverage:** §2 in → Task 1 (type + 4 glyphs), Task 2 (overload), Task 3 (RovaIcons), Tasks 4–5 (call-sites), Task 6 (build/test), Task 7 (smoke). §2 out (other glyphs, RecordChromeIcons fold-in + `checkRovaGlyphHome`, theme engine, P2) correctly excluded.
- **Placeholder scan:** glyph code is concrete (real path data); the only adapt-on-resolve note is the Compose `path(...)` signature in Task 1 Step 5 / imports in Task 2 Step 2 — these are compiler-driven, not placeholders.
- **Type consistency:** `RovaGlyph(outline, accent?)`, `RovaGlyphs.{Library,Settings,Sort,Record}`, `RovaIcons.{Library,Settings,Sort,Record}: RovaGlyph` + `{View,Play,WarningStatus,NotificationsSetting}: RovaIcon`, `SemanticIcon(glyph: RovaGlyph, contentDescription, modifier, role, status)` — consistent across Tasks 1–5. Test references (`.outline`, `.accent`, `.glyph`, `.status`) match the types.
- **Gate safety:** verified in Task 6 Step 1 — no raw `tint = Color`, no `RovaSemantics.copy`.

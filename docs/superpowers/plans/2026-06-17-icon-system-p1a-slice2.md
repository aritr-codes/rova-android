# Icon System P1a Slice 2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Author the 6 bespoke brand glyphs + 2 phone-outline orientation glyphs into `RovaGlyphs`, fold the legacy `RecordChromeIcons` vectors into the same home (dropping the 3 superseded ones), and add the `checkRovaGlyphHome` gate so all hand-authored `ImageVector`s live in exactly one file.

**Architecture:** Extend the Slice-1 `RovaGlyphs` object. Brand glyphs are authored from verbatim `board-3-semantic.html` SVG path data via two new helpers (`svgStroke`/`svgFill`, backed by Compose `addPathNodes`) plus the existing `seg`/`circle`/`roundRect`/`strokePath`/`fillPath` helpers. The legacy `RecordChromeIcons.kt` vectors that are still consumed (FlashBolt, CameraFront, CameraRear, ChevronUp, Play) are relocated **verbatim** (own viewports/strokes — this is a structural move, not a re-skin); the 3 superseded ones (library, settings, flipCamera) are dropped. A new `preBuild`-wired static gate `checkRovaGlyphHome` forbids `ImageVector.Builder(` outside `RovaGlyphs.kt`.

**Tech Stack:** Kotlin 2.2.10, Jetpack Compose (`androidx.compose.ui.graphics.vector` — `ImageVector.Builder`, `addPathNodes`, `addPath`, `path`), JUnit4 JVM unit tests (`isReturnDefaultValues = true`), Gradle Kotlin DSL custom `check*` tasks.

---

## Scope

**In scope:**
- 6 brand glyphs: `DualShot`, `Vault`, `Recovery`, `DualSight`, `BackgroundRecord`, `Merge` (verbatim board path data).
- 2 orientation glyphs: `OrientationPortrait`, `OrientationLandscape` (phone-outline, owner-chosen form — board has no orientation glyph so these are authored here).
- `RovaIcons` map entries for all 8 new glyphs.
- Fold-in: relocate `FlashBolt`/`CameraFront`/`CameraRear`/`ChevronUp`/`Play` into `RovaGlyphs`; drop `library`/`settings`/`flipCamera`; migrate call-sites; delete `RecordChromeIcons.kt`; drop it from `checkRecordSurfaceNoBlur`'s file set.
- New gate `checkRovaGlyphHome` + `preBuild` wiring.

**Out of scope (Slice 3 / theme engine):**
- Wiring the new brand glyphs into live UI surfaces (mode picker, vault, recovery card, notifications). This slice authors + maps them; rendering them is a follow-up. Device behaviour is therefore **unchanged** this slice (like P0 — a structural/plumbing slice).
- Glass-chip active containers + animated states (tied to the Liquid Glass theme engine, P2).
- Re-authoring the relocated cam-control glyphs to the System-D monoline (a later visual-polish slice; relocating verbatim avoids device regression here).

---

## Glyph source data (verbatim from `board-3-semantic.html`)

Spec Part B locked these option keys (memory `project-icon-glyph-system`):

| Glyph | board key | SVG elements |
|-------|-----------|--------------|
| DualShot | `ds_twin` | `<rect x=3.4 y=5.6 w=8.6 h=12.8 rx=2>` · `<rect x=11.6 y=9 w=9 h=9 rx=2>` · `<circle ac2 solid cx=11.9 cy=12 r=2>` |
| Vault | `vault_stack` | `<rect ac2 x=6 y=4 w=12.5 h=9 rx=2>` · `<rect x=3.5 y=8 w=12.5 h=9 rx=2>` · `<rect ac2 solid x=14 y=14.2 w=6.5 h=5.3 rx=1.2>` · `<path ac2 d="M15.3 14.2v-1.3a2 2 0 0 1 4 0v1.3">` |
| Recovery | `recov_rejoin` | `<path d="M10.3 5H6a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h4.3">` · `<path d="M13.7 5H18a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2h-4.3">` · `<path ac2 d="M12 4.3v3M12 10.5v3M12 16.7v3">` |
| DualSight | `dualsight` | `<rect x=3.5 y=5.5 w=17 h=13 rx=2.3>` · `<rect ac2 solid x=12.4 y=11.6 w=6.2 h=5.2 rx=1.4>` · `<circle cx=8.2 cy=10.5 r=2.4>` |
| BackgroundRecord | `bg_record` | `<path d="M8.5 4.5H18a1.8 1.8 0 0 1 1.8 1.8V15">` · `<rect x=4.2 y=8 w=11.6 h=11.5 rx=2.2>` · `<circle ac2 solid cx=10 cy=13.7 r=2.6>` |
| Merge | `merge_stitch` | `<path d="M4 7h4">` · `<path d="M4 17h4">` · `<path d="M8 7c4 0 4 5 8 5">` · `<path d="M8 17c4 0 4-5 8-5">` · `<path d="M16 12h4">` · `<circle ac2 solid cx=16.5 cy=12 r=1.9>` |

**Channel split:** elements WITHOUT the `ac2` class → the `outline` `ImageVector` (tinted by role/status). Elements WITH `ac2` → the `accent` `ImageVector` (tinted `palette.accent`). Within either channel, `solid` → fill, otherwise → stroke.

**Orientation glyphs (authored, not from board):** phone outline = rounded rect; accent = the speaker bar (mono-safe: the outline alone still reads as an upright vs rotated phone).
- `OrientationPortrait`: outline `roundRect(8, 3, 8, 18, 2.2)`; accent stroke `seg(10.6, 5.4, 13.4, 5.4)`.
- `OrientationLandscape`: outline `roundRect(3, 8, 18, 8, 2.2)`; accent stroke `seg(5.4, 10.6, 5.4, 13.4)`.

---

## Task 1: SVG-path authoring helpers (`svgStroke` / `svgFill`)

The brand glyphs include arbitrary cubic/arc `<path d=…>` data (shields, brackets, stitch curves). Hand-translating each curve into `PathBuilder` calls is error-prone; instead parse the verbatim `d` string with Compose `addPathNodes` and feed `addPath`. The existing `seg`/`circle`/`roundRect` helpers still serve `<rect>`/`<circle>`/`<line>`.

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/theme/RovaGlyphs.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/theme/RovaGlyphsTest.kt`

- [ ] **Step 1: Write the failing test** — add to `RovaGlyphsTest.kt` (a glyph that uses `svgStroke`/`svgFill` will be Recovery in Task 2; for now assert the helpers exist by exercising a tiny local glyph is not possible since they're private. Instead, defer the helper's own coverage to Task 2's brand-glyph tests + the existing `every_glyph_path_has_a_brush` guard, which already recurses every path's brush). No new test in this task — the helpers are private plumbing proven by Task 2. Skip to Step 2.

- [ ] **Step 2: Add the imports** to `RovaGlyphs.kt` (after the existing `vector.path` import):

```kotlin
import androidx.compose.ui.graphics.vector.addPathNodes
```

- [ ] **Step 3: Add the two helpers** in `RovaGlyphs.kt`, in the "authoring helpers" section, immediately after `fillPath`:

```kotlin
    /** Verbatim SVG `<path d=…>` stroke (round caps/joins, System-D weight). */
    private fun ImageVector.Builder.svgStroke(d: String) {
        addPath(
            pathData = addPathNodes(d),
            stroke = PLACEHOLDER, strokeLineWidth = SW,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
    }

    /** Verbatim SVG `<path d=…>` fill. */
    private fun ImageVector.Builder.svgFill(d: String) {
        addPath(pathData = addPathNodes(d), fill = PLACEHOLDER)
    }
```

- [ ] **Step 4: Compile-check** (helpers are unused until Task 2, so just confirm the file compiles by running the existing glyph tests):

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.theme.RovaGlyphsTest"`
Expected: PASS (existing Slice-1 tests still green; new helpers compile).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/theme/RovaGlyphs.kt
git commit -m "feat(icons): RovaGlyphs svgStroke/svgFill helpers (addPathNodes) for verbatim board paths (ADR-0031 P1a slice 2)"
```

---

## Task 2: Author the 6 brand glyphs

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/theme/RovaGlyphs.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/theme/RovaGlyphsTest.kt`

- [ ] **Step 1: Write the failing tests** — add to `RovaGlyphsTest.kt`:

```kotlin
    @Test fun dualshot_is_duotone_twin_frames() {
        assertNotNull(RovaGlyphs.DualShot.outline)
        assertNotNull("DualShot accent = solid core dot", RovaGlyphs.DualShot.accent)
    }

    @Test fun vault_is_duotone_stack_with_lock() {
        assertNotNull(RovaGlyphs.Vault.outline)
        assertNotNull("Vault accent = back frame + padlock", RovaGlyphs.Vault.accent)
    }

    @Test fun recovery_is_duotone_rejoined_frame() {
        assertNotNull(RovaGlyphs.Recovery.outline)
        assertNotNull("Recovery accent = seam", RovaGlyphs.Recovery.accent)
    }

    @Test fun dualsight_is_duotone_frame_with_pip() {
        assertNotNull(RovaGlyphs.DualSight.outline)
        assertNotNull("DualSight accent = PiP inset", RovaGlyphs.DualSight.accent)
    }

    @Test fun background_record_is_duotone_card_with_dot() {
        assertNotNull(RovaGlyphs.BackgroundRecord.outline)
        assertNotNull("BackgroundRecord accent = rec dot", RovaGlyphs.BackgroundRecord.accent)
    }

    @Test fun merge_is_duotone_streams_to_node() {
        assertNotNull(RovaGlyphs.Merge.outline)
        assertNotNull("Merge accent = join node", RovaGlyphs.Merge.accent)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.theme.RovaGlyphsTest"`
Expected: FAIL — `Unresolved reference: DualShot` (etc.).

- [ ] **Step 3: Author the glyphs** — add to `RovaGlyphs.kt`, after the `Record` glyph (before the helpers section):

```kotlin
    // ── Brand glyphs (spec Part B, board-3-semantic.html) ───────────────────

    // DualShot — P+L twin frames (outline) + accent capture core. board `ds_twin`.
    val DualShot = RovaGlyph(
        outline = glyph {
            strokePath { roundRect(3.4f, 5.6f, 8.6f, 12.8f, 2f) }
            strokePath { roundRect(11.6f, 9f, 9f, 9f, 2f) }
        },
        accent = glyph { fillPath { circle(11.9f, 12f, 2f) } },
    )

    // Vault — front frame (outline) + back frame & padlock (accent). board `vault_stack`.
    val Vault = RovaGlyph(
        outline = glyph { strokePath { roundRect(3.5f, 8f, 12.5f, 9f, 2f) } },
        accent = glyph {
            strokePath { roundRect(6f, 4f, 12.5f, 9f, 2f) }
            fillPath { roundRect(14f, 14.2f, 6.5f, 5.3f, 1.2f) }
            svgStroke("M15.3 14.2v-1.3a2 2 0 0 1 4 0v1.3")
        },
    )

    // Recovery — two rejoined bracket halves (outline) + dashed seam (accent). board `recov_rejoin`.
    val Recovery = RovaGlyph(
        outline = glyph {
            svgStroke("M10.3 5H6a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h4.3")
            svgStroke("M13.7 5H18a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2h-4.3")
        },
        accent = glyph { svgStroke("M12 4.3v3M12 10.5v3M12 16.7v3") },
    )

    // DualSight — frame + 2nd lens (outline) + PiP inset (accent). board `dualsight`.
    val DualSight = RovaGlyph(
        outline = glyph {
            strokePath { roundRect(3.5f, 5.5f, 17f, 13f, 2.3f) }
            strokePath { circle(8.2f, 10.5f, 2.4f) }
        },
        accent = glyph { fillPath { roundRect(12.4f, 11.6f, 6.2f, 5.2f, 1.4f) } },
    )

    // Background-record — backgrounded-app card (outline) + rec dot (accent). board `bg_record`.
    val BackgroundRecord = RovaGlyph(
        outline = glyph {
            svgStroke("M8.5 4.5H18a1.8 1.8 0 0 1 1.8 1.8V15")
            strokePath { roundRect(4.2f, 8f, 11.6f, 11.5f, 2.2f) }
        },
        accent = glyph { fillPath { circle(10f, 13.7f, 2.6f) } },
    )

    // Merge — two streams converge (outline) + join node (accent). board `merge_stitch`.
    val Merge = RovaGlyph(
        outline = glyph {
            svgStroke("M4 7h4M4 17h4M8 7c4 0 4 5 8 5M8 17c4 0 4-5 8-5M16 12h4")
        },
        accent = glyph { fillPath { circle(16.5f, 12f, 1.9f) } },
    )
```

- [ ] **Step 4: Extend the brush-regression guard** — in `RovaGlyphsTest.kt`, add the 6 new glyphs to the `every_glyph_path_has_a_brush` list:

```kotlin
            "DualShot.outline" to RovaGlyphs.DualShot.outline,
            "DualShot.accent" to RovaGlyphs.DualShot.accent!!,
            "Vault.outline" to RovaGlyphs.Vault.outline,
            "Vault.accent" to RovaGlyphs.Vault.accent!!,
            "Recovery.outline" to RovaGlyphs.Recovery.outline,
            "Recovery.accent" to RovaGlyphs.Recovery.accent!!,
            "DualSight.outline" to RovaGlyphs.DualSight.outline,
            "DualSight.accent" to RovaGlyphs.DualSight.accent!!,
            "BackgroundRecord.outline" to RovaGlyphs.BackgroundRecord.outline,
            "BackgroundRecord.accent" to RovaGlyphs.BackgroundRecord.accent!!,
            "Merge.outline" to RovaGlyphs.Merge.outline,
            "Merge.accent" to RovaGlyphs.Merge.accent!!,
```

- [ ] **Step 5: Extend the 24-grid test** — in `RovaGlyphsTest.kt`, add the 6 new glyphs to the `all_glyphs_use_the_24_grid` `listOf(...)`:

```kotlin
        listOf(
            RovaGlyphs.Library, RovaGlyphs.Settings, RovaGlyphs.Sort, RovaGlyphs.Record,
            RovaGlyphs.DualShot, RovaGlyphs.Vault, RovaGlyphs.Recovery, RovaGlyphs.DualSight,
            RovaGlyphs.BackgroundRecord, RovaGlyphs.Merge,
        ).forEach { g ->
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.theme.RovaGlyphsTest"`
Expected: PASS (all brand glyphs present, duotone, on the 24-grid, every path has a brush).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/theme/RovaGlyphs.kt app/src/test/java/com/aritr/rova/ui/theme/RovaGlyphsTest.kt
git commit -m "feat(icons): 6 bespoke brand glyphs (DualShot/Vault/Recovery/DualSight/BackgroundRecord/Merge) (ADR-0031 P1a slice 2)"
```

---

## Task 3: Author the 2 orientation phone-outline glyphs

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/theme/RovaGlyphs.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/theme/RovaGlyphsTest.kt`

- [ ] **Step 1: Write the failing tests** — add to `RovaGlyphsTest.kt`:

```kotlin
    @Test fun orientation_portrait_is_duotone_phone() {
        assertNotNull(RovaGlyphs.OrientationPortrait.outline)
        assertNotNull("Portrait accent = speaker bar", RovaGlyphs.OrientationPortrait.accent)
    }

    @Test fun orientation_landscape_is_duotone_phone() {
        assertNotNull(RovaGlyphs.OrientationLandscape.outline)
        assertNotNull("Landscape accent = speaker bar", RovaGlyphs.OrientationLandscape.accent)
    }

    @Test fun orientation_glyphs_differ() {
        // Portrait is taller-than-wide, landscape wider-than-tall: their outline
        // path data must not be identical (guards against a copy-paste author bug).
        assertNotEquals(
            RovaGlyphs.OrientationPortrait.outline.root.toString(),
            RovaGlyphs.OrientationLandscape.outline.root.toString(),
        )
    }
```

Add the import if missing: `import org.junit.Assert.assertNotEquals`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.theme.RovaGlyphsTest"`
Expected: FAIL — `Unresolved reference: OrientationPortrait`.

- [ ] **Step 3: Author the glyphs** — add to `RovaGlyphs.kt`, after `Merge` (still before the helpers section):

```kotlin
    // ── Orientation (phone outline; owner-chosen over a person glyph) ───────
    // Mono-safe: the outline alone reads as an upright vs rotated phone; the
    // accent speaker bar is the duotone channel. (No board source — authored here.)

    // Portrait — upright phone + top speaker bar.
    val OrientationPortrait = RovaGlyph(
        outline = glyph { strokePath { roundRect(8f, 3f, 8f, 18f, 2.2f) } },
        accent = glyph { strokePath { seg(10.6f, 5.4f, 13.4f, 5.4f) } },
    )

    // Landscape — rotated phone + speaker bar on the left short edge.
    val OrientationLandscape = RovaGlyph(
        outline = glyph { strokePath { roundRect(3f, 8f, 18f, 8f, 2.2f) } },
        accent = glyph { strokePath { seg(5.4f, 10.6f, 5.4f, 13.4f) } },
    )
```

- [ ] **Step 4: Extend the brush guard + 24-grid test** — in `RovaGlyphsTest.kt`, add both orientation glyphs to `every_glyph_path_has_a_brush`:

```kotlin
            "OrientationPortrait.outline" to RovaGlyphs.OrientationPortrait.outline,
            "OrientationPortrait.accent" to RovaGlyphs.OrientationPortrait.accent!!,
            "OrientationLandscape.outline" to RovaGlyphs.OrientationLandscape.outline,
            "OrientationLandscape.accent" to RovaGlyphs.OrientationLandscape.accent!!,
```

and to the `all_glyphs_use_the_24_grid` list:

```kotlin
            RovaGlyphs.OrientationPortrait, RovaGlyphs.OrientationLandscape,
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.theme.RovaGlyphsTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/theme/RovaGlyphs.kt app/src/test/java/com/aritr/rova/ui/theme/RovaGlyphsTest.kt
git commit -m "feat(icons): portrait/landscape phone-outline orientation glyphs (ADR-0031 P1a slice 2)"
```

---

## Task 4: Expose the 8 new glyphs via `RovaIcons`

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/theme/RovaIcons.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/theme/RovaIconsTest.kt`

- [ ] **Step 1: Write the failing test** — add to `RovaIconsTest.kt`:

```kotlin
    @Test fun brand_and_orientation_concepts_resolve_to_bespoke_glyphs() {
        // Each maps to its RovaGlyphs source (one concept → one glyph, ADR-0031 §2).
        assertSame(RovaGlyphs.DualShot, RovaIcons.DualShot)
        assertSame(RovaGlyphs.Vault, RovaIcons.Vault)
        assertSame(RovaGlyphs.Recovery, RovaIcons.Recovery)
        assertSame(RovaGlyphs.DualSight, RovaIcons.DualSight)
        assertSame(RovaGlyphs.BackgroundRecord, RovaIcons.BackgroundRecord)
        assertSame(RovaGlyphs.Merge, RovaIcons.Merge)
        assertSame(RovaGlyphs.OrientationPortrait, RovaIcons.OrientationPortrait)
        assertSame(RovaGlyphs.OrientationLandscape, RovaIcons.OrientationLandscape)
    }
```

Add the import if missing: `import org.junit.Assert.assertSame`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.theme.RovaIconsTest"`
Expected: FAIL — `Unresolved reference: DualShot`.

- [ ] **Step 3: Add the map entries** — in `RovaIcons.kt`, in the "Bespoke System-D (RovaGlyph)" block (after `Record`):

```kotlin
    val DualShot: RovaGlyph = RovaGlyphs.DualShot
    val Vault: RovaGlyph = RovaGlyphs.Vault
    val Recovery: RovaGlyph = RovaGlyphs.Recovery
    val DualSight: RovaGlyph = RovaGlyphs.DualSight
    val BackgroundRecord: RovaGlyph = RovaGlyphs.BackgroundRecord
    val Merge: RovaGlyph = RovaGlyphs.Merge
    val OrientationPortrait: RovaGlyph = RovaGlyphs.OrientationPortrait
    val OrientationLandscape: RovaGlyph = RovaGlyphs.OrientationLandscape
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.theme.RovaIconsTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/theme/RovaIcons.kt app/src/test/java/com/aritr/rova/ui/theme/RovaIconsTest.kt
git commit -m "feat(icons): RovaIcons maps 8 new bespoke concepts (brand + orientation) (ADR-0031 P1a slice 2)"
```

---

## Task 5: Fold `RecordChromeIcons` into `RovaGlyphs`, delete the legacy file

Relocate the 5 still-consumed vectors **verbatim** (own viewports/strokes preserved — structural move, not a re-skin), drop the 3 superseded (`library`, `settings` → replaced by the Slice-1 duotone glyphs; `flipCamera` → unused since B6 switched to cameraFront/cameraRear), migrate the 4 call-sites, delete the file, and remove it from the blur-gate's file set.

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/theme/RovaGlyphs.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt` (3 sites + imports)
- Modify: `app/src/main/java/com/aritr/rova/ui/theme/RovaIcons.kt` (Play + import)
- Delete: `app/src/main/java/com/aritr/rova/ui/screens/RecordChromeIcons.kt`
- Modify: `app/build.gradle.kts` (`checkRecordSurfaceNoBlur` file set)

- [ ] **Step 1: Relocate the 5 vectors into `RovaGlyphs.kt`** — add a new section after the orientation glyphs (still before the helpers). These are plain `ImageVector` (single-layer, neutral-authored, tinted by the consuming `Icon`/`SemanticIcon`), copied verbatim from `RecordChromeIcons.kt` with PascalCase names. Add the import `import androidx.compose.ui.graphics.vector.path` is already present; `SolidColor`/`Color` already imported.

```kotlin
    // ── Folded-in record-chrome vectors (ex-RecordChromeIcons, verbatim) ────
    // Single-layer, neutral white, tinted by the consuming Icon/SemanticIcon.
    // Kept at their original viewports/strokes — this was a structural move, not
    // a re-skin (System-D re-authoring is a later visual slice). Superseded
    // library/settings/flipCamera were dropped (duotone RovaGlyphs replace nav;
    // flip switched to CameraFront/CameraRear in B6).

    /** `.cam-ctrl-btn` flash glyph — single lightning bolt. */
    val FlashBolt: ImageVector =
        ImageVector.Builder(
            name = "RovaGlyphFlashBolt",
            defaultWidth = 13.dp, defaultHeight = 15.dp,
            viewportWidth = 13f, viewportHeight = 15f,
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(7.5f, 1f); lineTo(1.5f, 8.5f); horizontalLineTo(6f)
                lineTo(5f, 14f); lineTo(11.5f, 6.5f); horizontalLineTo(7f)
                lineTo(7.5f, 1f); close()
            }
        }.build()

    /** "switch to FRONT camera" — body + selfie head/shoulders. */
    val CameraFront: ImageVector =
        ImageVector.Builder(
            name = "RovaGlyphCameraFront",
            defaultWidth = 16.dp, defaultHeight = 16.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            val stroke = SolidColor(Color.White)
            path(stroke = stroke, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(4f, 7f); horizontalLineTo(20f); verticalLineTo(19f); horizontalLineTo(4f); close()
            }
            path(stroke = stroke, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(9f, 7f); lineTo(10.5f, 4.5f); horizontalLineTo(13.5f); lineTo(15f, 7f)
            }
            path(stroke = stroke, strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(13.5f, 12f)
                arcTo(1.5f, 1.5f, 0f, true, true, 10.5f, 12f)
                arcTo(1.5f, 1.5f, 0f, true, true, 13.5f, 12f)
                close()
            }
            path(stroke = stroke, strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(9.5f, 17f)
                arcTo(2.5f, 2.5f, 0f, false, true, 14.5f, 17f)
            }
        }.build()

    /** "switch to REAR camera" — body + centred lens ring. */
    val CameraRear: ImageVector =
        ImageVector.Builder(
            name = "RovaGlyphCameraRear",
            defaultWidth = 16.dp, defaultHeight = 16.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            val stroke = SolidColor(Color.White)
            path(stroke = stroke, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(4f, 7f); horizontalLineTo(20f); verticalLineTo(19f); horizontalLineTo(4f); close()
            }
            path(stroke = stroke, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(9f, 7f); lineTo(10.5f, 4.5f); horizontalLineTo(13.5f); lineTo(15f, 7f)
            }
            path(stroke = stroke, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(15f, 13f)
                arcTo(3f, 3f, 0f, true, true, 9f, 13f)
                arcTo(3f, 3f, 0f, true, true, 15f, 13f)
                close()
            }
        }.build()

    /** `.settings-arrow` chevron — points up (expand sheet). */
    val ChevronUp: ImageVector =
        ImageVector.Builder(
            name = "RovaGlyphChevronUp",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(stroke = SolidColor(Color.White), strokeLineWidth = 2.2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(18f, 15f); lineTo(12f, 9f); lineTo(6f, 15f)
            }
        }.build()

    /** Play triangle (ex-fabPlay) — used as RovaIcons.Play. */
    val Play: ImageVector =
        ImageVector.Builder(
            name = "RovaGlyphPlay",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(9f, 7f); lineTo(17f, 12f); lineTo(9f, 17f); close()
            }
        }.build()
```

- [ ] **Step 2: Migrate `RecordChrome.kt` call-sites.** Replace the 3 references:

`app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt:286`:
```kotlin
                    RovaGlyphs.FlashBolt,
```
`app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt:318`:
```kotlin
        val flipIcon = if (isFrontCamera) RovaGlyphs.CameraRear else RovaGlyphs.CameraFront
```
`app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt:454`:
```kotlin
                            RovaGlyphs.ChevronUp,
```

Then fix imports in `RecordChrome.kt`: remove `import com.aritr.rova.ui.screens.RecordChromeIcons` if present (same package — it may be unqualified; if there is no explicit import, nothing to remove). Add `import com.aritr.rova.ui.theme.RovaGlyphs` if not already imported.

- [ ] **Step 3: Migrate `RovaIcons.kt` Play** — change line 26:
```kotlin
    val Play = RovaIcon(RovaGlyphs.Play)
```
and remove the now-unused `import com.aritr.rova.ui.screens.RecordChromeIcons`.

- [ ] **Step 4: Delete the legacy file**

```bash
git rm app/src/main/java/com/aritr/rova/ui/screens/RecordChromeIcons.kt
```

- [ ] **Step 5: Drop it from the blur-gate file set** — in `app/build.gradle.kts`, the `checkRecordSurfaceNoBlur` `recordChromeNames` set (~line 2400):
```kotlin
        val recordChromeNames = setOf(
            "RecordScreen.kt", "RecordChrome.kt",
        )
```
(Removing `"RecordChromeIcons.kt"` — the file no longer exists; the blur invariant for its content is moot and the home-gate now governs it.)

- [ ] **Step 6: Verify no dangling references**

Run: `git grep -n "RecordChromeIcons" -- "app/src/**/*.kt"`
Expected: no matches in `app/src` (docs/plans may still mention it — that's fine).

- [ ] **Step 7: Build + run the full glyph/icon tests**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL (compiles; call-sites resolve; file deleted cleanly).

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.theme.*"`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "refactor(icons): fold RecordChromeIcons into RovaGlyphs, drop superseded nav/flip vectors (ADR-0031 P1a slice 2)"
```

---

## Task 6: Add the `checkRovaGlyphHome` static gate

Forbid `ImageVector.Builder(` anywhere except `RovaGlyphs.kt` — one home for bespoke glyphs (ADR-0031 §5). This can only go green AFTER Task 5's fold-in (so it lands in this slice, with the fold-in).

**Files:**
- Modify: `app/build.gradle.kts` (register task + wire into `preBuild`)
- Modify: `docs/adr/0031-icon-glyph-system.md` (mark the gate built)

- [ ] **Step 1: Register the gate** — in `app/build.gradle.kts`, after `checkStatusColorLocked` (~line 1927+, near the other ADR-0031 gates):

```kotlin
// ADR-0031 §5 (P1) — bespoke ImageVectors have one home. `ImageVector.Builder(` may appear only in
// RovaGlyphs.kt; folding RecordChromeIcons in here removed the second declaration site. This subsumes
// the old RecordChromeIcons.kt allowance in checkRecordSurfaceNoBlur.
val checkRovaGlyphHome = tasks.register("checkRovaGlyphHome") {
    group = "verification"
    description = "Bespoke ImageVectors must be declared only in RovaGlyphs.kt — one home for " +
        "hand-authored glyphs so the icon family + theme engine have a single source (ADR-0031 §5)."
    val srcDir = file("src/main/java/com/aritr/rova")
    val homeFile = file("src/main/java/com/aritr/rova/ui/theme/RovaGlyphs.kt").canonicalFile
    inputs.dir(srcDir).withPropertyName("rovaSources")
    doLast {
        if (!srcDir.exists()) {
            throw GradleException("checkRovaGlyphHome: Rova source dir missing: $srcDir")
        }
        // Whole-file scan (NOT per-line): `\s` then matches a newline, so a builder split across
        // lines (`ImageVector.\n    Builder(`) is still caught (codex-flagged false-negative). Strip
        // block + line comments first (preserving line counts) so KDoc/examples never false-fire.
        val builderPattern = Regex("""\bImageVector\s*\.\s*Builder\s*\(""")
        fun stripComments(src: String): String {
            val noBlock = Regex("""/\*[\s\S]*?\*/""").replace(src) { m -> m.value.replace(Regex("[^\n]"), " ") }
            return noBlock.lines().joinToString("\n") { line ->
                val i = line.indexOf("//"); if (i >= 0) line.substring(0, i) else line
            }
        }
        val offenders = srcDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.canonicalFile != homeFile }
            .mapNotNull { f ->
                val text = stripComments(f.readText())
                val hits = builderPattern.findAll(text)
                    .map { m -> text.substring(0, m.range.first).count { it == '\n' } + 1 }
                    .toList()
                if (hits.isEmpty()) null else f to hits
            }
            .toList()
        if (offenders.isNotEmpty()) {
            val report = offenders.joinToString("\n") { (f, hits) ->
                hits.joinToString("\n") { line -> "  ${f.relativeTo(rootDir)}:$line" }
            }
            throw GradleException(
                "ADR-0031 §5 violation: a bespoke ImageVector is declared outside RovaGlyphs.kt. " +
                    "All hand-authored glyphs live in ui/theme/RovaGlyphs.kt so the icon family and the " +
                    "theme engine resolve glyphs from one place. Move the vector there.\nOffenders:\n$report"
            )
        }
    }
}
```

- [ ] **Step 2: Wire into `preBuild`** — in the `tasks.matching { it.name == "preBuild" }` block, after `dependsOn(checkStatusColorLocked)` (~line 2574):

```kotlin
        dependsOn(checkRovaGlyphHome)
```

- [ ] **Step 3: Run the gate — expect GREEN** (fold-in already moved the only other site)

Run: `./gradlew :app:checkRovaGlyphHome`
Expected: BUILD SUCCESSFUL (only `RovaGlyphs.kt` declares `ImageVector.Builder`).

- [ ] **Step 4: Prove-it-bites (transient).** Temporarily add a dummy builder in another file to confirm the gate fires:

In `app/src/main/java/com/aritr/rova/ui/theme/RovaIcons.kt`, add a throwaway line inside the object:
```kotlin
    private val _probe = androidx.compose.ui.graphics.vector.ImageVector.Builder(defaultWidth = 1f.dp, defaultHeight = 1f.dp, viewportWidth = 1f, viewportHeight = 1f).build()
```

Run: `./gradlew :app:checkRovaGlyphHome`
Expected: FAIL — `ADR-0031 §5 violation: ... RovaIcons.kt`.

Then **revert** the probe line (do not commit it):
```bash
git checkout -- app/src/main/java/com/aritr/rova/ui/theme/RovaIcons.kt
```

Run: `./gradlew :app:checkRovaGlyphHome`
Expected: BUILD SUCCESSFUL again.

- [ ] **Step 5: Mark the gate built in ADR-0031** — in `docs/adr/0031-icon-glyph-system.md`, update the `checkRovaGlyphHome (P1)` line to note it is now built/wired (P1a slice 2), and that it subsumes the `RecordChromeIcons.kt` allowance in `checkRecordSurfaceNoBlur`.

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle.kts docs/adr/0031-icon-glyph-system.md
git commit -m "feat(icons): checkRovaGlyphHome gate — bespoke ImageVectors only in RovaGlyphs (ADR-0031 §5, P1a slice 2)"
```

---

## Task 7: Full verification + docs + memory

**Files:**
- Modify: `docs/superpowers/specs/2026-06-16-icon-glyph-system-design.md` (mark Slice 2 done)
- Modify: `memory/project_icon_glyph_system.md` + `memory/MEMORY.md` (record Slice 2)

- [ ] **Step 1: Full static-gate + test + build sweep** (controller runs all gradle):

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS — baseline + all new glyph/icon tests; 0 failures.

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL — all 45 `check*` gates (44 prior + `checkRovaGlyphHome`) green via `preBuild`.

- [ ] **Step 2: Update the spec** — in `docs/superpowers/specs/2026-06-16-icon-glyph-system-design.md`, mark the 6 brand glyphs + orientation glyphs + `RecordChromeIcons` fold-in + `checkRovaGlyphHome` as landed in P1a Slice 2; note brand-glyph UI wiring is Slice 3.

- [ ] **Step 3: Update memory** — append a P1a Slice 2 entry to `memory/project_icon_glyph_system.md` (glyphs authored, fold-in done, gate built, device unchanged this slice, Slice 3 = wire brand glyphs) and refresh its one-line `MEMORY.md` pointer.

- [ ] **Step 4: Commit**

```bash
git add docs/superpowers/specs/2026-06-16-icon-glyph-system-design.md memory/project_icon_glyph_system.md memory/MEMORY.md
git commit -m "docs(icons): record P1a slice 2 (brand+orientation glyphs, fold-in, home gate) (ADR-0031)"
```

- [ ] **Step 5: Final code review** — dispatch a final reviewer over the whole slice diff (`git diff master...HEAD`), then hand off via `superpowers:finishing-a-development-branch` (owner gates merge/push).

---

## Self-Review

**Spec coverage:** All 6 Part-B brand glyphs (Task 2) + orientation glyphs (Task 3, owner-chosen phone form) + `RecordChromeIcons` fold-in (Task 5) + `checkRovaGlyphHome` (Task 6) are covered. Glass-chip + animated states are explicitly deferred (theme engine / P2), per spec. Brand-glyph UI wiring deferred to Slice 3 (stated in Scope).

**Placeholder scan:** No TBD/TODO/"add error handling"/"similar to". Every code step shows complete code; every gate/test step shows the exact command + expected output. Brand glyph path data is verbatim from the board (no invented geometry); orientation glyph geometry is fully specified.

**Type consistency:** `RovaGlyph(outline, accent?)`, `glyph { }`, `strokePath`/`fillPath`/`seg`/`circle`/`roundRect` match Slice-1 signatures; new `svgStroke`/`svgFill(d: String)` defined in Task 1 and used in Task 2. Relocated vectors are plain `ImageVector` (PascalCase: `FlashBolt`/`CameraFront`/`CameraRear`/`ChevronUp`/`Play`); `RovaIcons.Play = RovaIcon(RovaGlyphs.Play)`. Gate name `checkRovaGlyphHome` consistent across register + `preBuild` wiring + ADR. `assertNotEquals`/`assertSame`/`assertNotNull` imports flagged where added.

**Ordering invariant:** the home-gate (Task 6) only goes green after the fold-in (Task 5) removes the second `ImageVector.Builder` site — both land in this slice, in order.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-06-17-icon-system-p1a-slice2.md`. Two execution options:

1. **Subagent-Driven (recommended)** — fresh subagent per task, two-stage review between tasks (EDIT-ONLY subagents; controller runs all gradle + commits, per house rules).
2. **Inline Execution** — execute tasks in this session with checkpoints.

Which approach?

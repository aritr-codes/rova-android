# Icon System P1a Slice 3 — Wire Brand Glyphs Into Live UI

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **Subagents are EDIT-ONLY; the controller runs ALL gradle commands and does ALL commits.**

**Goal:** Make the bespoke System-D glyphs actually render — author the 2 missing picker glyphs (Single, FollowDevice), then wire all 6 brand glyphs + 2 orientation glyphs into the live capture-topology/orientation pickers, the Vault row, the Merge-complete card, and the Recovery card.

**Architecture:** Two new `RovaGlyph`s join `RovaGlyphs`; both pickers gain a glyph above each tab label via the `SemanticIcon(glyph=…)` two-layer seam (one concept → one glyph, mapped by pure `captureGlyphFor`/`orientationGlyphFor` helpers). Three existing surfaces swap/add glyphs through the same seam. No raw-color tints (preserves `checkSemanticIconNoRawAlpha`); no new gates; no strings; no schema/behavior change.

**Tech Stack:** Kotlin, Jetpack Compose, `ImageVector.Builder` DSL + `addPathNodes`, JVM unit tests (`isReturnDefaultValues = true`).

**Deferred (out of scope, documented):** `BackgroundRecord` — the foreground-service notification uses `setSmallIcon(plan.iconRes)`, a **drawable-resource int**, not an `ImageVector`. Wiring a `RovaGlyph` there needs a vector-drawable XML export pipeline = a separate slice. `BackgroundRecord` stays authored-but-unwired this slice.

**Glyph rendering note (verify on device):** `SemanticIcon` reads the palette from `LocalGlassEnvironment`. `MergeCompleteCard` / `RecoveryCard` are dark overlays; if not under a glass-env provider the seam falls back to the default palette (white-ish outline + default accent) — still visible on the dark fills. This is a known P0 fallback (PreviewActivity), non-fatal; confirm visually in Task 9 device smoke.

---

## File Structure

- `app/src/main/java/com/aritr/rova/ui/theme/RovaGlyphs.kt` — **modify**: add `Single` + `FollowDevice` glyph vals.
- `app/src/test/java/com/aritr/rova/ui/theme/RovaGlyphsTest.kt` — **modify**: 2 new tests + extend the two list-based guards.
- `app/src/main/java/com/aritr/rova/ui/theme/RovaIcons.kt` — **modify**: expose `Single` + `FollowDevice`.
- `app/src/test/java/com/aritr/rova/ui/theme/RovaIconsTest.kt` — **modify**: assert the 2 new concepts resolve to their glyphs.
- `app/src/main/java/com/aritr/rova/ui/screens/PickerGlyphs.kt` — **create**: pure `captureGlyphFor` / `orientationGlyphFor` mappers.
- `app/src/test/java/com/aritr/rova/ui/screens/PickerGlyphsTest.kt` — **create**: map coverage.
- `app/src/main/java/com/aritr/rova/ui/screens/SettingsSheet.kt` — **modify**: wire `ModeTabs` + `OrientationRow`.
- `app/src/main/java/com/aritr/rova/ui/library/components/LibraryItemSheet.kt` — **modify**: `RovaGlyph` `SheetRow` overload + Vault row swap.
- `app/src/main/java/com/aritr/rova/ui/components/MergeCompleteCard.kt` — **modify**: swap CheckCircle → `RovaIcons.Merge`.
- `app/src/main/java/com/aritr/rova/ui/recovery/RecoveryCard.kt` — **modify**: add leading `RovaIcons.Recovery` glyph.
- `docs/adr/0031-icon-glyph-system.md`, `docs/superpowers/specs/2026-06-16-icon-glyph-system-design.md` — **modify**: Slice 3 landed note.

---

## Task 1: Author Single + FollowDevice glyphs

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/theme/RovaGlyphs.kt` (insert after `Merge`, before the `// ── Orientation` block at line ~124)
- Test: `app/src/test/java/com/aritr/rova/ui/theme/RovaGlyphsTest.kt`

- [ ] **Step 1: Write the failing tests**

In `RovaGlyphsTest.kt`, add two tests after `merge_is_duotone_streams_to_node` (line 63):

```kotlin
    @Test fun single_is_duotone_frame_with_core() {
        assertNotNull(RovaGlyphs.Single.outline)
        assertNotNull("Single accent = capture core dot", RovaGlyphs.Single.accent)
    }

    @Test fun follow_device_is_duotone_phone_with_rotation() {
        assertNotNull(RovaGlyphs.FollowDevice.outline)
        // Rotation arrows live in the MONO outline (mono-safe differentiator vs Portrait);
        // the accent is the speaker-bar duotone flavour, consistent with Portrait/Landscape.
        assertNotNull("FollowDevice accent = speaker bar", RovaGlyphs.FollowDevice.accent)
    }
```

Also extend the two list-based guards so the new glyphs are covered:

In `all_glyphs_use_the_24_grid` (the `listOf(...)` at line 85-90), append to the list:
```kotlin
            RovaGlyphs.Single, RovaGlyphs.FollowDevice,
```

In `every_glyph_path_has_a_brush` (the `listOf(...)` at line 107-131), append before the closing `)`:
```kotlin
            "Single.outline" to RovaGlyphs.Single.outline,
            "Single.accent" to RovaGlyphs.Single.accent!!,
            "FollowDevice.outline" to RovaGlyphs.FollowDevice.outline,
            "FollowDevice.accent" to RovaGlyphs.FollowDevice.accent!!,
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.theme.RovaGlyphsTest"`
Expected: FAIL — `RovaGlyphs.Single` / `RovaGlyphs.FollowDevice` unresolved.

- [ ] **Step 3: Author the two glyphs**

In `RovaGlyphs.kt`, insert after the `Merge` val (line 122) and before the `// ── Orientation` comment (line 124):

```kotlin

    // Single (Auto) — one capture frame (outline) + accent capture core. Mono-safe:
    // one frame reads as single-camera capture; the core dot is the duotone channel.
    // (No board source — authored here as the Auto sibling of DualShot's twin frames.)
    val Single = RovaGlyph(
        outline = glyph { strokePath { roundRect(5.5f, 6.5f, 13f, 11f, 2.4f) } },
        accent = glyph { fillPath { circle(12f, 12f, 2f) } },
    )

    // FollowDevice — phone + two auto-rotate arrows (outline) + speaker bar (accent).
    // The rotation arrows are its ONLY differentiator from Portrait, so they live in the
    // MONO outline layer — they must never wash out (ADR-0031 §1 mono-safe). The accent is
    // the speaker bar, matching Portrait/Landscape's duotone channel. (No board source.)
    val FollowDevice = RovaGlyph(
        outline = glyph {
            strokePath { roundRect(9f, 6f, 6f, 12f, 1.8f) }
            svgStroke("M14.5 4c3 0.6 5 2.6 5.6 5.6M20.1 9.6l0.1-2.6M20.1 9.6l-2.6 0.2")
            svgStroke("M9.5 20c-3-0.6-5-2.6-5.6-5.6M3.9 14.4l-0.1 2.6M3.9 14.4l2.6-0.2")
        },
        accent = glyph { strokePath { seg(10.5f, 8.4f, 13.5f, 8.4f) } },
    )
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.theme.RovaGlyphsTest"`
Expected: PASS (all RovaGlyphsTest tests, including the brush + 24-grid guards over the 2 new glyphs).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/theme/RovaGlyphs.kt app/src/test/java/com/aritr/rova/ui/theme/RovaGlyphsTest.kt
git commit -m "feat(icons): Single + FollowDevice glyphs for picker tabs (ADR-0031 P1a slice 3)"
```

---

## Task 2: Expose Single + FollowDevice in RovaIcons

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/theme/RovaIcons.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/theme/RovaIconsTest.kt`

- [ ] **Step 1: Write the failing test**

In `RovaIconsTest.kt`, add to `brand_and_orientation_concepts_resolve_to_bespoke_glyphs()` (after line 41, before the closing `}`):
```kotlin
        assertEquals(RovaGlyphs.Single, RovaIcons.Single)
        assertEquals(RovaGlyphs.FollowDevice, RovaIcons.FollowDevice)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.theme.RovaIconsTest"`
Expected: FAIL — `RovaIcons.Single` / `RovaIcons.FollowDevice` unresolved.

- [ ] **Step 3: Add the map entries**

In `RovaIcons.kt`, in the bespoke block (after line 29 `val OrientationLandscape = …`):
```kotlin
    val Single: RovaGlyph = RovaGlyphs.Single
    val FollowDevice: RovaGlyph = RovaGlyphs.FollowDevice
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.theme.RovaIconsTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/theme/RovaIcons.kt app/src/test/java/com/aritr/rova/ui/theme/RovaIconsTest.kt
git commit -m "feat(icons): RovaIcons exposes Single + FollowDevice (ADR-0031 P1a slice 3)"
```

---

## Task 3: Pure picker-glyph mappers

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/screens/PickerGlyphs.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/screens/PickerGlyphsTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/aritr/rova/ui/screens/PickerGlyphsTest.kt`:
```kotlin
package com.aritr.rova.ui.screens

import com.aritr.rova.ui.theme.RovaIcons
import org.junit.Assert.assertEquals
import org.junit.Test

class PickerGlyphsTest {

    @Test fun capture_modes_map_to_their_glyphs() {
        assertEquals(RovaIcons.Single, captureGlyphFor(CaptureMode.Auto))
        assertEquals(RovaIcons.DualShot, captureGlyphFor(CaptureMode.DualShot))
        assertEquals(RovaIcons.DualSight, captureGlyphFor(CaptureMode.DualSight))
    }

    @Test fun orientation_options_map_to_their_glyphs() {
        assertEquals(RovaIcons.FollowDevice, orientationGlyphFor("FollowDevice", -1))
        assertEquals(RovaIcons.OrientationPortrait, orientationGlyphFor("Lock", 0))
        assertEquals(RovaIcons.OrientationLandscape, orientationGlyphFor("Lock", 1))
        assertEquals(RovaIcons.OrientationLandscape, orientationGlyphFor("Lock", 3))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.PickerGlyphsTest"`
Expected: FAIL — `captureGlyphFor` / `orientationGlyphFor` unresolved.

- [ ] **Step 3: Create the mappers**

Create `app/src/main/java/com/aritr/rova/ui/screens/PickerGlyphs.kt`:
```kotlin
package com.aritr.rova.ui.screens

import com.aritr.rova.ui.theme.RovaGlyph
import com.aritr.rova.ui.theme.RovaIcons

/**
 * Canonical capture-topology → bespoke glyph (ADR-0031 §2 — one concept → one glyph).
 * Auto (Single capture) uses the lone-frame [RovaIcons.Single]; the dual topologies use
 * their twin/PiP marks. Pure so the picker wiring stays JVM-testable.
 */
internal fun captureGlyphFor(mode: CaptureMode): RovaGlyph = when (mode) {
    CaptureMode.Auto -> RovaIcons.Single
    CaptureMode.DualShot -> RovaIcons.DualShot
    CaptureMode.DualSight -> RovaIcons.DualSight
}

/**
 * Orientation-policy option → bespoke glyph. The picker rows are built from
 * (policy, lockRotation) pairs (see [OrientationRow]): FollowDevice, Lock@0 = portrait,
 * Lock@{1,3} = landscape.
 */
internal fun orientationGlyphFor(optPolicy: String, optLock: Int): RovaGlyph = when {
    optPolicy == "FollowDevice" -> RovaIcons.FollowDevice
    optLock == 0 -> RovaIcons.OrientationPortrait
    else -> RovaIcons.OrientationLandscape
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.PickerGlyphsTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/PickerGlyphs.kt app/src/test/java/com/aritr/rova/ui/screens/PickerGlyphsTest.kt
git commit -m "feat(icons): pure captureGlyphFor/orientationGlyphFor mappers (ADR-0031 P1a slice 3)"
```

---

## Task 4: Wire ModeTabs (capture-topology picker)

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/SettingsSheet.kt` (`ModeTabs`, lines 820-891)

UI-only task — no unit test (Compose composable); verified by build (Task 9) + device smoke.

- [ ] **Step 1: Add imports**

In `SettingsSheet.kt` imports, ensure present (add any missing):
```kotlin
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.aritr.rova.ui.components.SemanticIcon
import com.aritr.rova.ui.theme.IconRole
```

- [ ] **Step 2: Replace the tab content Box (lines 876-878)**

Replace:
```kotlin
                Box(modifier = tabModifier, contentAlignment = Alignment.Center) {
                    Text(label, style = RovaTokens.sheetModeTab, color = textColor)
                }
```
with:
```kotlin
                // ADR-0031 P1a slice 3: glyph above the label. Icon role tracks the
                // text tier (active = textHigh, idle = textDim, disabled). The duotone
                // accent may wash out over the active accent gradient — acceptable, the
                // mark is mono-safe (meaning survives without the accent, ADR-0031 §1).
                val iconRole = when {
                    isActive -> IconRole.Default
                    !enabled -> IconRole.Disabled
                    else -> IconRole.Secondary
                }
                Box(modifier = tabModifier, contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        SemanticIcon(
                            glyph = captureGlyphFor(mode),
                            contentDescription = null, // the tab semantics{} already carries the label
                            modifier = Modifier.size(18.dp),
                            role = iconRole,
                        )
                        // maxLines=1 + ellipsis keeps the now-taller (icon+label) tab a
                        // predictable height at large font / 320dp (verify in device smoke).
                        Text(
                            label,
                            style = RovaTokens.sheetModeTab,
                            color = textColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
```

- [ ] **Step 3: Commit** (build verified in Task 9)

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/SettingsSheet.kt
git commit -m "feat(icons): ModeTabs renders capture-topology glyphs (ADR-0031 P1a slice 3)"
```

---

## Task 5: Wire OrientationRow (orientation picker)

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/SettingsSheet.kt` (`OrientationRow`, lines 899-973)

UI-only task — verified by build + device smoke.

- [ ] **Step 1: Replace the tab content Box (lines 968-970)**

Replace:
```kotlin
            Box(modifier = tabModifier, contentAlignment = Alignment.Center) {
                Text(label, style = RovaTokens.sheetModeTab, color = textColor)
            }
```
with:
```kotlin
            // ADR-0031 P1a slice 3: orientation glyph above the label. The whole row
            // already alphas to 0.4 when disabled, so per-icon Disabled role is not
            // needed — active = Default, idle = Secondary.
            val iconRole = if (isActive) IconRole.Default else IconRole.Secondary
            Box(modifier = tabModifier, contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    SemanticIcon(
                        glyph = orientationGlyphFor(optPolicy, optLock),
                        contentDescription = null, // the tab semantics{} already carries the label
                        modifier = Modifier.size(18.dp),
                        role = iconRole,
                    )
                    Text(
                        label,
                        style = RovaTokens.sheetModeTab,
                        color = textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/SettingsSheet.kt
git commit -m "feat(icons): OrientationRow renders orientation glyphs (ADR-0031 P1a slice 3)"
```

---

## Task 6: Vault row → RovaIcons.Vault

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/library/components/LibraryItemSheet.kt`

UI-only task — verified by build + device smoke.

- [ ] **Step 1: Imports**

Add:
```kotlin
import com.aritr.rova.ui.components.SemanticIcon
import com.aritr.rova.ui.theme.RovaGlyph
import com.aritr.rova.ui.theme.RovaIcons
```
Remove (now unused):
```kotlin
import androidx.compose.material.icons.filled.Lock
```

- [ ] **Step 2: Add a RovaGlyph SheetRow overload**

After the existing `SheetRow(icon: ImageVector, …)` (ends line 199), add:
```kotlin
/** Bespoke-glyph row (ADR-0031): renders a two-layer [RovaGlyph] through the SemanticIcon seam. */
@Composable
private fun SheetRow(glyph: RovaGlyph, label: String, onClick: () -> Unit) {
    val contentColor = MaterialTheme.colorScheme.onSurface
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        SemanticIcon(glyph = glyph, contentDescription = null, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(20.dp))
        Text(label, color = contentColor)
    }
}
```

- [ ] **Step 3: Swap the Vault call-site (line 137)**

Replace:
```kotlin
                if (movable) SheetRow(Icons.Filled.Lock, vaultLabel) { onMoveToVault() }
```
with:
```kotlin
                if (movable) SheetRow(RovaIcons.Vault, vaultLabel) { onMoveToVault() }
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/components/LibraryItemSheet.kt
git commit -m "feat(icons): Vault row uses RovaGlyphs.Vault (ADR-0031 P1a slice 3)"
```

---

## Task 7: Merge-complete card → RovaIcons.Merge

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/components/MergeCompleteCard.kt`

UI-only task — verified by build + device smoke.

- [ ] **Step 1: Imports**

Add:
```kotlin
import com.aritr.rova.ui.components.SemanticIcon
import com.aritr.rova.ui.theme.RovaIcons
```
(`SemanticIcon` is in this same package `com.aritr.rova.ui.components`, so the import is optional but harmless; keep `RovaIcons`.)
Remove (now unused — the only `Icon`/CheckCircle use is being replaced):
```kotlin
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
```

- [ ] **Step 2: Swap the icon (lines 73-78)**

Replace:
```kotlin
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp)
            )
```
with:
```kotlin
            // ADR-0031 P1a slice 3: the merge mark through the SemanticIcon seam.
            // The card's Surface already carries the live-region a11y label.
            SemanticIcon(
                glyph = RovaIcons.Merge,
                contentDescription = null,
                modifier = Modifier.size(36.dp)
            )
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/components/MergeCompleteCard.kt
git commit -m "feat(icons): MergeCompleteCard uses RovaGlyphs.Merge (ADR-0031 P1a slice 3)"
```

---

## Task 8: Recovery card leading glyph

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/recovery/RecoveryCard.kt`

UI-only task — verified by build + device smoke.

- [ ] **Step 1: Imports**

Add:
```kotlin
import com.aritr.rova.ui.components.SemanticIcon
import com.aritr.rova.ui.theme.IconRole
import com.aritr.rova.ui.theme.RovaIcons
```
(`Row`, `Arrangement`, `Alignment`, `Modifier.size` are already imported — used by `SeverityTag`.)

- [ ] **Step 2: Wrap SeverityTag with a leading glyph (lines 135-139)**

Replace:
```kotlin
            SeverityTag(
                label = stringResource(tagLabelResFor(state.kind)),
                accent = severityColor,
                pulsing = isHardSeverity,
            )
```
with:
```kotlin
            // ADR-0031 P1a slice 3: a Recovery emblem leads the severity tag. Role =
            // Secondary (textDim) so the quiet emblem does not visually outrank the
            // severity tag. The severity colour stays on the tag dot + card glow, which
            // use RovaWarnings (not the locked RovaSemantics status palette), so mixing
            // the two colour systems on one mark is deliberately avoided.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SemanticIcon(
                    glyph = RovaIcons.Recovery,
                    contentDescription = null, // card title/body carry the meaning
                    modifier = Modifier.size(22.dp),
                    role = IconRole.Secondary,
                )
                SeverityTag(
                    label = stringResource(tagLabelResFor(state.kind)),
                    accent = severityColor,
                    pulsing = isHardSeverity,
                )
            }
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/recovery/RecoveryCard.kt
git commit -m "feat(icons): RecoveryCard leads with RovaGlyphs.Recovery (ADR-0031 P1a slice 3)"
```

---

## Task 9: Full build + gates + JVM suite + docs + device smoke

**Files:**
- Modify: `docs/adr/0031-icon-glyph-system.md`, `docs/superpowers/specs/2026-06-16-icon-glyph-system-design.md`

- [ ] **Step 1: Full gated build** (controller runs)

Run: `./gradlew :app:assembleDebug` (runs all 45 `check*` gates via preBuild) and `./gradlew :app:testDebugUnitTest`.
Expected: BUILD SUCCESSFUL; 45 gates green; full JVM suite green (baseline + the new RovaGlyphs/RovaIcons/PickerGlyphs tests). Use `assembleDebug` (NOT `lintDebug` — pre-existing B5 `VaultAndroidOps` NewApi).

Gate sanity (no edits needed, just confirm green): `checkRovaGlyphHome` (new glyphs live in RovaGlyphs.kt), `checkSemanticIconNoRawAlpha` (all new tints flow through `SemanticIcon`), `checkA11yClickableHasRole` / `checkA11yTargetSizeToken` (no target/role changes), `checkNoHardcodedUiStrings` (no new strings).

- [ ] **Step 2: Docs — mark Slice 3 landed**

In `docs/adr/0031-icon-glyph-system.md` Status line, append: `; P1a slice 3 landed 2026-06-17 (brand + orientation glyphs wired into pickers/Vault/Merge/Recovery; Single + FollowDevice authored; BackgroundRecord notification icon deferred — drawable-resource pipeline)`.

In the design spec, add a short "Slice 3 landed" note mirroring the Slice 2 note (glyphs wired; BackgroundRecord deferred with reason).

- [ ] **Step 3: Commit docs**

```bash
git add docs/adr/0031-icon-glyph-system.md docs/superpowers/specs/2026-06-16-icon-glyph-system-design.md
git commit -m "docs(icons): ADR-0031 + spec — P1a slice 3 landed (ADR-0031 P1a slice 3)"
```

- [ ] **Step 4: Device smoke** (controller runs adb via PowerShell directly — adb MCP wrapper is broken on Windows)

```powershell
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.aritr.rova/.MainActivity
```
Verify visually: capture-topology tabs show frame glyphs (Single/DualShot); orientation tabs show phone glyphs (FollowDevice/Portrait/Landscape); library item sheet Vault row shows the vault-stack+padlock; recovery card leads with the recovery emblem; (merge card needs a real merge to view — best-effort). Confirm no crash, no invisible-glyph regression. Owner GO required before merge.

---

## Self-Review

- **Spec coverage:** 6 brand glyphs + 2 orientation glyphs — DualShot/DualSight (Task 4), Portrait/Landscape (Task 5), Vault (Task 6), Merge (Task 7), Recovery (Task 8); Single/FollowDevice authored (Task 1) to complete the pickers. BackgroundRecord explicitly deferred with reason.
- **Type consistency:** `captureGlyphFor(CaptureMode): RovaGlyph` / `orientationGlyphFor(String, Int): RovaGlyph`; `SemanticIcon(glyph: RovaGlyph, …)` overload exists; `RovaIcons.{Single,FollowDevice,Vault,Merge,Recovery,DualShot,DualSight,OrientationPortrait,OrientationLandscape}` all `RovaGlyph`. `SheetRow(glyph: RovaGlyph, …)` overload added alongside the `ImageVector` one.
- **No raw tints:** every new icon goes through `SemanticIcon` — preserves `checkSemanticIconNoRawAlpha`. No new gate; no new strings; no schema/behavior change.

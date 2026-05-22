# Camera-Guides Framing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the mockup's decorative camera framing — `#060d18` zone background, faint composition grid, edge vignette, centred focus brackets, plain-text P+L zone tags — to all three record-screen modes, gated by a new "Camera guides" app-settings toggle (on by default).

**Architecture:** A new stateless `CameraGuides.kt` holds three token-driven overlay layers (grid / vignette / focus brackets). A `cameraGuidesEnabled` boolean threads `RovaSettings` → the Activity-scoped shared `SettingsViewModel` → both the settings screen (write) and the record screen (read). `RecordScreen` reads the flag exactly as it already reads `keepScreenOn`; no `RecordViewModel` change.

**Tech Stack:** Kotlin · Jetpack Compose (`Canvas`, `Brush.radialGradient`, `drawLine`) · SharedPreferences (`RovaSettings`).

**Spec:** `docs/superpowers/specs/2026-05-22-camera-guides-framing-design.md`

---

## Branch & Baseline

- **Branch:** `feat/camera-guides-framing` — **stacked on `feat/settings-sheet-reskin`** (PR #37 tip `4e8c497`), because PR #37 also rewrites `RecordScreen.kt` and is not yet merged. The spec commit (`7174166`) is already on this branch.
- **Baseline (= `feat/settings-sheet-reskin` @ `4e8c497`):**
  - `:app:testDebugUnitTest` — **1035 tests / 83 classes / 0-0-0**
  - `:app:lintDebug` — **51 issues (50 W + 1 H + 0 E)**
  - `:app:assembleDebug` — BUILD SUCCESSFUL
- **Predicted after:** tests **1035 / 83** unchanged (no testable seam — pure UI); lint **≤ 51**; `assembleDebug` OK.
- **When PR #37 squash-merges to master:** rebase with
  `git rebase --onto master feat/settings-sheet-reskin feat/camera-guides-framing`
  to drop the settings-sheet commits, then open this PR `--base master`.

**Diff allowlist — `git diff feat/settings-sheet-reskin..HEAD --name-only` must equal exactly:**
```
docs/superpowers/specs/2026-05-22-camera-guides-framing-design.md
docs/superpowers/plans/2026-05-22-camera-guides-framing.md
app/src/main/java/com/aritr/rova/ui/theme/RecordChromeTokens.kt
app/src/main/java/com/aritr/rova/ui/screens/CameraGuides.kt
app/src/main/java/com/aritr/rova/ui/screens/DualPreviewZone.kt
app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt
app/src/main/java/com/aritr/rova/data/RovaSettings.kt
app/src/main/java/com/aritr/rova/ui/screens/SettingsViewModel.kt
app/src/main/java/com/aritr/rova/ui/screens/SettingsScreen.kt
docs/UI_DESIGN_TOKENS.md
```

---

## Testing Policy — Read First

This is pure Compose UI — colour, geometry, gradient, `Canvas` drawing. The project has **no Robolectric / Compose-UI-test layer**; pixel UI is not unit-tested (Phase 1/2/R1/R2 + the settings-sheet precedent). `RovaSettings.cameraGuidesEnabled` is a branch-free SharedPreferences get/set — no testable seam. **No tasks add tests.** Every task is verified by `:app:assembleDebug` + the existing suite staying green.

**Gradle is subagent-routed** — the implementer subagent runs `.\gradlew.bat` directly; the controller does not.

---

## File Structure

| File | Action | Responsibility |
|---|---|---|
| `ui/theme/RecordChromeTokens.kt` | Modify | Add 7 camera-guide tokens (grid cell/line, vignette stops, focus-bracket arm/stroke). |
| `ui/screens/CameraGuides.kt` | Create | `CameraGuides(visible, modifier)` + private `CameraGrid` / `CameraVignette` / `FocusFrame`. |
| `ui/screens/DualPreviewZone.kt` | Modify | Zone bg → `camZoneBackground`; tag chip → plain uppercase micro-text; per-zone `CameraGuides`; new defaulted `guidesEnabled` param. |
| `data/RovaSettings.kt` | Modify | `cameraGuidesEnabled: Boolean` (default `true`). |
| `ui/screens/SettingsViewModel.kt` | Modify | `cameraGuidesEnabled` StateFlow + write-through. |
| `ui/screens/SettingsScreen.kt` | Modify | "Camera guides" `SettingsRow` + `Switch`. |
| `ui/screens/RecordScreen.kt` | Modify | Read the flag; overlay `CameraGuides` on single-mode preview; pass `guidesEnabled` to `DualPreviewZone`. |
| `docs/UI_DESIGN_TOKENS.md` | Modify | Document the new tokens. |

---

## Task 1: `RecordChromeTokens` — camera-guide tokens

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/theme/RecordChromeTokens.kt`

- [ ] **Step 1: Add the tokens**

`RecordChromeTokens.kt` ends with a `focusFrameSize = 60.dp` declaration (a `// ── Camera-zone framing` group is mid-file; the object's closing `}` is the last line). Insert the following block immediately **before** the closing `}` of `object RecordChromeTokens`:

```kotlin

    // ── Camera guides (decorative overlays — mockup 01 cam-zone) ─────────
    /** `.camera-grid` cell width — CSS `background-size` X (`105.3px`). */
    val cameraGridCellWidth = 105.3.dp
    /** `.camera-grid` cell height — CSS `background-size` Y (`228.3px`). */
    val cameraGridCellHeight = 228.3.dp
    /** `.camera-grid` line thickness — the linear-gradient's `1px` band. */
    val cameraGridLineWidth = 1.dp
    /** `.camera-vignette` outer stop — `rgba(0,0,0,0.6)`. */
    val cameraVignetteEdge = Color.Black.copy(alpha = 0.6f)
    /** `.camera-vignette` inner transparent stop — CSS `transparent 35%`. */
    val cameraVignetteInnerStop = 0.35f
    /** `.focus-frame` corner-bracket arm length — CSS corner `14px`. */
    val focusFrameCornerArm = 14.dp
    /** `.focus-frame` corner-bracket stroke width — CSS `border-width: 1.5px`. */
    val focusFrameStrokeWidth = 1.5.dp
```

(`Color` and `dp` are already imported in the file. Do NOT modify any existing token.)

- [ ] **Step 2: Compile-gate**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/theme/RecordChromeTokens.kt
git commit -m "feat(ui): add camera-guide tokens to RecordChromeTokens"
```

---

## Task 2: `CameraGuides.kt` — the decorative overlay composables

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/screens/CameraGuides.kt`

- [ ] **Step 1: Create the file**

```kotlin
package com.aritr.rova.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.aritr.rova.ui.theme.RecordChromeTokens

/**
 * Decorative camera-guide overlays — re-skin of the `.camera-grid`,
 * `.camera-vignette` and `.focus-frame` layers in
 * `mockups/new_uiux/01-record-home.html`.
 *
 * Stateless and **non-interactive** (no `clickable` / `pointerInput`) — it
 * cannot intercept camera-area touches. The caller sizes the host (a P+L
 * preview zone, or the single-mode preview area) and this fills it. When
 * [visible] is false nothing is emitted — the "Camera guides" app-setting is
 * off. The focus brackets are static decoration, NOT a tap-to-focus target.
 */
@Composable
fun CameraGuides(visible: Boolean, modifier: Modifier = Modifier) {
    if (!visible) return
    Box(modifier) {
        CameraGrid(Modifier.fillMaxSize())
        CameraVignette(Modifier.fillMaxSize())
        // CSS `translate(-50%,-60%)` puts the frame centre 10 % of its own
        // height above the parent centre — centre-align then offset up.
        FocusFrame(
            Modifier
                .align(Alignment.Center)
                .offset(y = -(RecordChromeTokens.focusFrameSize * 0.1f)),
        )
    }
}

/** `.camera-grid` — faint composition grid, one line per cell from the origin. */
@Composable
private fun CameraGrid(modifier: Modifier = Modifier) {
    val color = RecordChromeTokens.cameraGridLine
    Canvas(modifier) {
        val cellW = RecordChromeTokens.cameraGridCellWidth.toPx()
        val cellH = RecordChromeTokens.cameraGridCellHeight.toPx()
        val lineW = RecordChromeTokens.cameraGridLineWidth.toPx()
        var x = cellW
        while (x < size.width) {
            drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = lineW)
            x += cellW
        }
        var y = cellH
        while (y < size.height) {
            drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = lineW)
            y += cellH
        }
    }
}

/**
 * `.camera-vignette` — radial edge-darkening. Compose `radialGradient` is
 * circular; the mockup's `ellipse 90% 80%` is approximated by a circular
 * gradient sized to the host (default radius reaches the farthest corner).
 */
@Composable
private fun CameraVignette(modifier: Modifier = Modifier) {
    Box(
        modifier.background(
            Brush.radialGradient(
                colorStops = arrayOf(
                    RecordChromeTokens.cameraVignetteInnerStop to Color.Transparent,
                    1f to RecordChromeTokens.cameraVignetteEdge,
                ),
            ),
        ),
    )
}

/**
 * `.focus-frame` — four L-shaped corner brackets in a 60 dp square. Each
 * coordinate is inset by half the stroke width so the 1.5 dp strokes sit
 * fully inside the square instead of half-clipping on the edge.
 */
@Composable
private fun FocusFrame(modifier: Modifier = Modifier) {
    val stroke = RecordChromeTokens.focusFrameStroke
    Canvas(modifier.size(RecordChromeTokens.focusFrameSize)) {
        val arm = RecordChromeTokens.focusFrameCornerArm.toPx()
        val w = RecordChromeTokens.focusFrameStrokeWidth.toPx()
        val i = w / 2f
        val s = size.width
        // top-left
        drawLine(stroke, Offset(i, i), Offset(i + arm, i), strokeWidth = w)
        drawLine(stroke, Offset(i, i), Offset(i, i + arm), strokeWidth = w)
        // top-right
        drawLine(stroke, Offset(s - i - arm, i), Offset(s - i, i), strokeWidth = w)
        drawLine(stroke, Offset(s - i, i), Offset(s - i, i + arm), strokeWidth = w)
        // bottom-right
        drawLine(stroke, Offset(s - i - arm, s - i), Offset(s - i, s - i), strokeWidth = w)
        drawLine(stroke, Offset(s - i, s - i - arm), Offset(s - i, s - i), strokeWidth = w)
        // bottom-left
        drawLine(stroke, Offset(i, s - i), Offset(i + arm, s - i), strokeWidth = w)
        drawLine(stroke, Offset(i, s - i - arm), Offset(i, s - i), strokeWidth = w)
    }
}
```

> `RecordChromeTokens.focusFrameStroke` (white-0.20) is the **pre-multiplied**
> effective alpha (mockup `rgba(255,255,255,0.8)` × `opacity:0.25`) — no extra
> opacity layer is needed.

- [ ] **Step 2: Compile-gate**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. (`CameraGuides` is not yet referenced — this proves it compiles standalone.)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/CameraGuides.kt
git commit -m "feat(ui): add CameraGuides — grid + vignette + focus-bracket overlays"
```

---

## Task 3: `DualPreviewZone` — zone bg, tag re-skin, per-zone guides

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/DualPreviewZone.kt`

**Hard invariant:** do NOT touch the `TextureView` / `SurfaceTexture` / `registerPreviewSurface` / `unregisterPreviewSurface` lifecycle, nor the `352f` / `225f` zone weights, nor the `cam-split-divider` `Box`. This task changes only the zone background colour, the zone-tag rendering, and adds the `CameraGuides` overlay + a `guidesEnabled` param.

- [ ] **Step 1: Fix the imports**

In `DualPreviewZone.kt`, **remove** these three now-unused imports:
```kotlin
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface as M3Surface
```
**Add** these two:
```kotlin
import com.aritr.rova.ui.theme.RecordChromeTokens
import com.aritr.rova.ui.theme.RovaTokens
```
Keep `import androidx.compose.material3.Text` (still used). Keep all other imports.

- [ ] **Step 2: Add `guidesEnabled` to the `DualPreviewZone` signature and forward it**

Change the `DualPreviewZone` composable's parameter list from:
```kotlin
fun DualPreviewZone(
    registerPreviewSurface: (side: VideoSide, surface: Surface, width: Int, height: Int) -> Unit,
    unregisterPreviewSurface: (side: VideoSide) -> Unit,
    modifier: Modifier = Modifier,
) {
```
to:
```kotlin
fun DualPreviewZone(
    registerPreviewSurface: (side: VideoSide, surface: Surface, width: Int, height: Int) -> Unit,
    unregisterPreviewSurface: (side: VideoSide) -> Unit,
    modifier: Modifier = Modifier,
    guidesEnabled: Boolean = true,
) {
```
Then in the body, add `guidesEnabled = guidesEnabled,` to BOTH `PreviewZone(...)` calls (the `VideoSide.PORTRAIT` one and the `VideoSide.LANDSCAPE` one) — e.g. the portrait call becomes:
```kotlin
        PreviewZone(
            side = VideoSide.PORTRAIT,
            label = "Portrait · 9:16",
            registerPreviewSurface = registerPreviewSurface,
            unregisterPreviewSurface = unregisterPreviewSurface,
            guidesEnabled = guidesEnabled,
            modifier = Modifier
                .fillMaxWidth()
                .weight(352f),
        )
```
Do the same for the `VideoSide.LANDSCAPE` call (keep its `label`, `weight(225f)`).

> The `= true` default keeps `RecordScreen`'s existing `DualPreviewZone(...)`
> call compiling between this task and Task 6; Task 6 passes the real value.

- [ ] **Step 3: Add `guidesEnabled` to the `PreviewZone` signature**

Change the private `PreviewZone` composable's parameter list from:
```kotlin
private fun PreviewZone(
    side: VideoSide,
    label: String,
    registerPreviewSurface: (side: VideoSide, surface: Surface, width: Int, height: Int) -> Unit,
    unregisterPreviewSurface: (side: VideoSide) -> Unit,
    modifier: Modifier = Modifier,
) {
```
to:
```kotlin
private fun PreviewZone(
    side: VideoSide,
    label: String,
    registerPreviewSurface: (side: VideoSide, surface: Surface, width: Int, height: Int) -> Unit,
    unregisterPreviewSurface: (side: VideoSide) -> Unit,
    guidesEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
```

- [ ] **Step 4: Re-skin the `PreviewZone` body**

Replace the entire `PreviewZone` body — the `Box(modifier = modifier.background(Color.Black)) { ... }` block — with:
```kotlin
    Box(modifier = modifier.background(RecordChromeTokens.camZoneBackground)) {
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                            registerPreviewSurface(side, Surface(st), w, h)
                        }
                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                            // Re-register; service-side map keyed by side, replaces prior entry.
                            registerPreviewSurface(side, Surface(st), w, h)
                        }
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            unregisterPreviewSurface(side)
                            return true  // framework releases the SurfaceTexture
                        }
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        // Decorative guides — grid + vignette + focus brackets — above the
        // camera, below the tag. Renders nothing when the toggle is off.
        CameraGuides(visible = guidesEnabled, modifier = Modifier.fillMaxSize())
        // cam-zone-tag — plain uppercase micro-text (mockup .cam-zone-tag:
        // 7.5 sp, weight 500, 1.5 sp tracking, white-32%), bottom-end.
        Text(
            text = label.uppercase(),
            style = RovaTokens.zoneTag,
            color = RecordChromeTokens.zoneTagText,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(
                    end = RecordChromeTokens.zoneTagPaddingEnd,
                    bottom = RecordChromeTokens.zoneTagPaddingBottom,
                ),
        )
    }
```

> `SurfaceTextureListener` body is byte-identical to the original — the
> recording-preview plumbing is untouched. Only the `Box` background, the
> added `CameraGuides`, and the tag (chip `M3Surface` → bare `Text`) change.
> `RovaTokens.zoneTag` is already an exact type match (Inter Medium 7.5 sp,
> 1.5 sp tracking); `camZoneBackground` / `zoneTagText` / `zoneTagPaddingEnd`
> / `zoneTagPaddingBottom` are pre-existing `RecordChromeTokens`.

- [ ] **Step 5: Update the file's KDoc (optional but tidy)**

The file header KDoc mentions "label chip in the bottom-right corner per mockup .cam-zone-tag styling". If it still says "chip", change "label chip" to "label" so the doc matches the bare-`Text` reality. Leave the rest.

- [ ] **Step 6: Compile-gate**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. (`RecordScreen`'s `DualPreviewZone(...)` call still compiles via the `guidesEnabled = true` default.)

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/DualPreviewZone.kt
git commit -m "feat(ui): re-skin DualPreviewZone — #060d18 bg, plain tags, camera guides"
```

---

## Task 4: `RovaSettings` + `SettingsViewModel` — the `cameraGuidesEnabled` flag

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/data/RovaSettings.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/SettingsViewModel.kt`

- [ ] **Step 1: Add the persisted property**

In `RovaSettings.kt`, immediately after the `keepScreenOn` property (the `var keepScreenOn: Boolean { get … set … }` block), insert:
```kotlin

    // Decorative camera guides (grid + focus brackets + vignette) over the
    // viewfinder. Default ON — the record screen ships matching the mockup.
    var cameraGuidesEnabled: Boolean
        get() = prefs.getBoolean("camera_guides_enabled", true)
        set(value) = prefs.edit { putBoolean("camera_guides_enabled", value) }
```

- [ ] **Step 2: Add the StateFlow + write-through to `SettingsViewModel`**

In `SettingsViewModel.kt`, after the `val keepScreenOn = MutableStateFlow(settings.keepScreenOn)` line, add:
```kotlin
    val cameraGuidesEnabled = MutableStateFlow(settings.cameraGuidesEnabled)
```
Then inside the `init { … }` block, after the
`viewModelScope.launch { keepScreenOn.collect { settings.keepScreenOn = it } }`
line, add:
```kotlin
        viewModelScope.launch { cameraGuidesEnabled.collect { settings.cameraGuidesEnabled = it } }
```

- [ ] **Step 3: Compile-gate**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/aritr/rova/data/RovaSettings.kt app/src/main/java/com/aritr/rova/ui/screens/SettingsViewModel.kt
git commit -m "feat(data): add cameraGuidesEnabled setting (default on)"
```

---

## Task 5: `SettingsScreen` — the "Camera guides" toggle row

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/SettingsScreen.kt`

- [ ] **Step 1: Add the import**

In `SettingsScreen.kt`, add (alongside the other `androidx.compose.material.icons.filled.*` imports — e.g. near `Smartphone` / `Vibration`):
```kotlin
import androidx.compose.material.icons.filled.GridOn
```
If `GridOn` fails to resolve at compile time, the project's material-icons-extended set should still provide `CenterFocusWeak` — use `androidx.compose.material.icons.filled.CenterFocusWeak` and `Icons.Default.CenterFocusWeak` instead. (`Vibration` / `DeleteSweep` are already imported from the extended set, so `GridOn` should resolve.)

- [ ] **Step 2: Collect the flag**

After the `val keepScreenOn by settingsViewModel.keepScreenOn.collectAsStateWithLifecycle()` line (~L98), add:
```kotlin
    val cameraGuidesEnabled by settingsViewModel.cameraGuidesEnabled.collectAsStateWithLifecycle()
```

- [ ] **Step 3: Add the toggle row**

The "Recording behavior" `SettingsSection` currently contains a single "Keep screen on" `SettingsRow`. Replace that section block:
```kotlin
            SettingsSection(label = "Recording behavior") {
                SettingsRow(
                    icon = Icons.Default.Smartphone,
                    label = "Keep screen on",
                    supporting = "Stops the screen from dimming while you frame a shot.",
                    onClick = {
                        settingsViewModel.keepScreenOn.value = !keepScreenOn
                    },
                    trailing = {
                        Switch(
                            checked = keepScreenOn,
                            onCheckedChange = { settingsViewModel.keepScreenOn.value = it }
                        )
                    }
                )
            }
```
with:
```kotlin
            SettingsSection(label = "Recording behavior") {
                SettingsRow(
                    icon = Icons.Default.Smartphone,
                    label = "Keep screen on",
                    supporting = "Stops the screen from dimming while you frame a shot.",
                    onClick = {
                        settingsViewModel.keepScreenOn.value = !keepScreenOn
                    },
                    trailing = {
                        Switch(
                            checked = keepScreenOn,
                            onCheckedChange = { settingsViewModel.keepScreenOn.value = it }
                        )
                    }
                )
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Default.GridOn,
                    label = "Camera guides",
                    supporting = "Show the framing grid, focus brackets and edge vignette over the viewfinder.",
                    onClick = {
                        settingsViewModel.cameraGuidesEnabled.value = !cameraGuidesEnabled
                    },
                    trailing = {
                        Switch(
                            checked = cameraGuidesEnabled,
                            onCheckedChange = { settingsViewModel.cameraGuidesEnabled.value = it }
                        )
                    }
                )
            }
```
(`SettingsDivider` is a private composable already in this file — used between rows in the "Alerts" section. If you fell back to `CenterFocusWeak` in Step 1, use `Icons.Default.CenterFocusWeak` here.)

- [ ] **Step 4: Compile-gate**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/SettingsScreen.kt
git commit -m "feat(ui): add the Camera guides toggle to Settings"
```

---

## Task 6: `RecordScreen` — wire the flag to both preview paths

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt`

**Hard invariant:** do NOT touch the Start-gate region (`startBlocked`, `onStart`, `WarningId`, `WarningPrecedence`), the service binding, or the `combinedOpen` chrome gate. This task edits only the camera-preview `Box` body and adds one `collectAsStateWithLifecycle`.

- [ ] **Step 1: Collect the flag**

`RecordScreen` already reads `keepScreenOn` — find
`val keepScreenOn by settingsViewModel.keepScreenOn.collectAsStateWithLifecycle()`
(~L125). Immediately after it, add:
```kotlin
    val cameraGuidesEnabled by settingsViewModel.cameraGuidesEnabled.collectAsStateWithLifecycle()
```

- [ ] **Step 2: Pass `guidesEnabled` to `DualPreviewZone`**

Find the `DualPreviewZone(...)` call inside the `if (mode == "PortraitLandscape")` branch of the camera-preview `Box`:
```kotlin
                            DualPreviewZone(
                                registerPreviewSurface = registerPreview,
                                unregisterPreviewSurface = unregisterPreview,
                                modifier = Modifier.fillMaxSize(),
                            )
```
Add the `guidesEnabled` argument:
```kotlin
                            DualPreviewZone(
                                registerPreviewSurface = registerPreview,
                                unregisterPreviewSurface = unregisterPreview,
                                modifier = Modifier.fillMaxSize(),
                                guidesEnabled = cameraGuidesEnabled,
                            )
```

- [ ] **Step 3: Overlay `CameraGuides` on the single-mode preview**

In the same camera-preview `Box`, the `else` branch renders the single-mode `AndroidView`:
```kotlin
                        } else {
                            // Single Portrait / Landscape modes — existing
                            // PreviewView path, unchanged.
                            AndroidView(
                                factory = { _ -> previewView },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
```
Replace that `else` branch with:
```kotlin
                        } else {
                            // Single Portrait / Landscape modes — existing
                            // PreviewView path, with the decorative guides
                            // overlaid above it.
                            AndroidView(
                                factory = { _ -> previewView },
                                modifier = Modifier.fillMaxSize()
                            )
                            CameraGuides(
                                visible = cameraGuidesEnabled,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
```
(`CameraGuides` is same-package — no import. The `if`/`else` branches sit inside `Box(modifier = Modifier.fillMaxSize())`, so the `CameraGuides` overlay stacks above the `AndroidView` within that `Box`.)

- [ ] **Step 4: Compile-gate**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt
git commit -m "feat(ui): overlay camera guides on the record-screen preview"
```

---

## Task 7: Document the new tokens

**Files:**
- Modify: `docs/UI_DESIGN_TOKENS.md`

- [ ] **Step 1: Read the doc**

Read `docs/UI_DESIGN_TOKENS.md` in full. Locate the `RecordChromeTokens` section (the settings-sheet sub-project added a `SettingsSheetTokens` block as a §2.14 — match the doc's numbering/heading style).

- [ ] **Step 2: Document the tokens**

In the `RecordChromeTokens` section, add a short paragraph noting the 7 new camera-guide tokens — `cameraGridCellWidth`, `cameraGridCellHeight`, `cameraGridLineWidth`, `cameraVignetteEdge`, `cameraVignetteInnerStop`, `focusFrameCornerArm`, `focusFrameStrokeWidth` — mockup-exact constants for the decorative viewfinder overlays (`01-record-home.html` `.camera-grid` / `.camera-vignette` / `.focus-frame`), consumed by `CameraGuides.kt`. Mention the guides are gated by the "Camera guides" app-setting (default on). Keep it to a short paragraph; do not restructure the doc.

- [ ] **Step 3: Commit**

```bash
git add docs/UI_DESIGN_TOKENS.md
git commit -m "docs(ui): document the camera-guide tokens"
```

---

## Task 8: Full-suite gate & invariant verification

**Files:** none — verification only.

- [ ] **Step 1: assembleDebug**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Unit-test suite**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL. Sum the JUnit XMLs under `app/build/test-results/testDebugUnitTest/*.xml`: expect **1035 tests / 83 classes / 0 failures / 0 errors / 0 skipped** — unchanged from baseline (this sub-project adds no tests). Any drift means a tested helper changed — investigate.

- [ ] **Step 3: Lint**

Run: `.\gradlew.bat :app:lintDebug`
Expected: **≤ 51 issues**. If it rose, read the new findings (likely an unused import in `DualPreviewZone.kt` — Task 3 removes three; confirm none lingered) and resolve them. If a fix is needed, commit it `git commit -am "fix(ui): drop unused import flagged by lint"`.

- [ ] **Step 4: Diff allowlist**

Run: `git diff feat/settings-sheet-reskin..HEAD --name-only`
Expected: **exactly** the 10 paths from the "Diff allowlist" section. Any extra path — especially `service/**`, `dualrecord/**`, `RecordChrome.kt`, `RovaRecordingService`, or a warning/Start-gate file — is a hard-invariant violation; revert it.

- [ ] **Step 5: Report**

Report the three gate results, the exact test totals, the diff file list, and any deviations. No commit.

---

## Hard Invariants (verify untouched)

- `DualPreviewZone`'s `TextureView` / `SurfaceTexture` / `registerPreviewSurface` / `unregisterPreviewSurface` lifecycle; the `352f` / `225f` zone weights; the `cam-split-divider` `Box`.
- `service/**`, `dualrecord/**`, the recording pipeline.
- `WarningId` / `WarningPrecedence` / `WarningCenter` / the `RecordScreen` Start-gate region (`startBlocked`, `onStart`).
- `RecordChrome.kt` and existing `RecordChromeTokens` token values (Task 1 only **adds**).
- `RecordViewModel.kt` — not modified (the flag rides the shared `SettingsViewModel`).
- The settings-sheet work from PR #37 — this branch is stacked on it; do not revert any of it.

## Owner Follow-Up (not implementer scope)

- **On-device smoke** (Samsung SM-A176B): in each of the three modes the grid + vignette + focus brackets show when "Camera guides" is on; toggling it off in Settings clears them on return to Record; P+L zone tags read as plain uppercase micro-text bottom-right on `#060d18`; compare to `01-record-home.html`.
- After PR #37 merges: `git rebase --onto master feat/settings-sheet-reskin feat/camera-guides-framing`, then push and open the PR `--base master`.
- **Sub-project 3** — the `06-app-settings.html` full-screen re-skin (own brainstorm → spec → plan).

## Self-Review

- **Spec coverage:** `#060d18` zone bg (Task 3) · grid/vignette/focus-frame overlays (Task 2, `CameraGuides`) · zone-tag re-skin (Task 3) · all-three-modes (Task 3 P+L + Task 6 single-mode) · `cameraGuidesEnabled` setting default on (Task 4) · settings toggle (Task 5) · shared-`SettingsViewModel` read, no `RecordViewModel` change (Task 6) · new tokens (Task 1) · docs (Task 7) · gate + allowlist (Task 8). All spec sections covered.
- **Placeholders:** none — every code step shows complete code. The one conditional (`GridOn` → `CenterFocusWeak` fallback) is fully specified.
- **Type consistency:** `RecordChromeTokens.{cameraGridCellWidth,cameraGridCellHeight,cameraGridLineWidth,cameraVignetteEdge,cameraVignetteInnerStop,focusFrameCornerArm,focusFrameStrokeWidth}` defined in Task 1 are exactly the symbols `CameraGuides.kt` consumes in Task 2. `CameraGuides(visible, modifier)` signature (Task 2) matches the call sites in Task 3 and Task 6. `DualPreviewZone`'s new `guidesEnabled: Boolean = true` param (Task 3) matches the `guidesEnabled = cameraGuidesEnabled` argument in Task 6. `SettingsViewModel.cameraGuidesEnabled` (Task 4) matches the reads in Task 5 and Task 6. `RovaSettings.cameraGuidesEnabled` (Task 4) matches the `SettingsViewModel` init.

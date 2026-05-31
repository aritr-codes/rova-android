# B2 Theme Switcher Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Light / Dark / System theme switcher to App Settings, applied live with no Activity recreate, defaulting to System, with the camera/player/onboarding surfaces pinned dark and in-scope light chrome meeting WCAG 2.2 AA.

**Architecture:** A persisted `ThemeMode` pref drives a pure `resolveDarkTheme()` seam read in `MainActivity.setContent` above `RovaTheme`; mutating the `SettingsViewModel.themeMode` flow recomposes the theme root. Dark-only surfaces (record/player/onboarding) are wrapped in a `RovaDarkSurface` that swaps to the dark `colorScheme` without touching window bars. Secondary/label text uses a theme-aware color helper because the dark scheme's alpha-dimming pattern cannot meet AA over a light background.

**Tech Stack:** Kotlin, Jetpack Compose, Material3, SharedPreferences, JUnit (JVM unit tests, `isReturnDefaultValues = true`).

Spec: `docs/superpowers/specs/2026-05-31-b2-theme-switcher-design.md`.

---

## File Structure

- **Create** `app/src/main/java/com/aritr/rova/ui/theme/ThemeMode.kt` — `ThemeMode` enum + pure `resolveDarkTheme` + pure `themeModeFromStorage`.
- **Create** `app/src/test/java/com/aritr/rova/ui/theme/ThemeModeTest.kt` — tests for both pure fns.
- **Create** `app/src/main/java/com/aritr/rova/ui/theme/QuietText.kt` — pure `quietTextColor` + `@Composable rovaQuietText`.
- **Create** `app/src/test/java/com/aritr/rova/ui/theme/QuietTextTest.kt` — tests for `quietTextColor`.
- **Modify** `app/src/main/java/com/aritr/rova/data/RovaSettings.kt` — add `themeMode` property.
- **Modify** `app/src/main/java/com/aritr/rova/ui/theme/Theme.kt` — add `RovaDarkSurface`; expose `DarkColorScheme` to it (same file, stays `private`).
- **Modify** `app/src/main/java/com/aritr/rova/ui/screens/SettingsViewModel.kt` — add `themeMode` flow + write-back collector.
- **Modify** `app/src/main/java/com/aritr/rova/MainActivity.kt` — theme root reads `themeMode`, computes `darkTheme`.
- **Modify** `app/src/main/java/com/aritr/rova/ui/MainScreen.kt` — accept `settingsViewModel` param; scaffold `containerColor`; wrap dark islands.
- **Modify** `app/src/main/java/com/aritr/rova/ui/screens/SettingsScreen.kt` — route quiet text through helper; add Appearance section + theme picker.
- **Modify** `app/src/main/java/com/aritr/rova/ui/screens/HistoryScreen.kt` — route dimmed secondary text through helper (light AA sweep).
- **Modify** `app/src/test/java/com/aritr/rova/ui/theme/TokenContrastTest.kt` — light-scheme AA assertions.
- **Modify** `CHANGELOG.md` — Unreleased entry.

---

### Task 1: ThemeMode enum + pure resolver/parser

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/theme/ThemeMode.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/theme/ThemeModeTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.aritr.rova.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeModeTest {

    @Test
    fun `resolveDarkTheme SYSTEM follows the system flag`() {
        assertEquals(true, resolveDarkTheme(ThemeMode.SYSTEM, systemDark = true))
        assertEquals(false, resolveDarkTheme(ThemeMode.SYSTEM, systemDark = false))
    }

    @Test
    fun `resolveDarkTheme DARK is always dark`() {
        assertEquals(true, resolveDarkTheme(ThemeMode.DARK, systemDark = false))
    }

    @Test
    fun `resolveDarkTheme LIGHT is always light`() {
        assertEquals(false, resolveDarkTheme(ThemeMode.LIGHT, systemDark = true))
    }

    @Test
    fun `themeModeFromStorage maps each valid name`() {
        assertEquals(ThemeMode.SYSTEM, themeModeFromStorage("SYSTEM"))
        assertEquals(ThemeMode.DARK, themeModeFromStorage("DARK"))
        assertEquals(ThemeMode.LIGHT, themeModeFromStorage("LIGHT"))
    }

    @Test
    fun `themeModeFromStorage coerces null and unknown to SYSTEM`() {
        assertEquals(ThemeMode.SYSTEM, themeModeFromStorage(null))
        assertEquals(ThemeMode.SYSTEM, themeModeFromStorage(""))
        assertEquals(ThemeMode.SYSTEM, themeModeFromStorage("PURPLE"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.theme.ThemeModeTest"`
Expected: FAIL — unresolved reference `ThemeMode` / `resolveDarkTheme` / `themeModeFromStorage`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.aritr.rova.ui.theme

/** User-selectable app theme. SYSTEM follows the OS light/dark setting. */
enum class ThemeMode { SYSTEM, DARK, LIGHT }

/** Pure seam: resolve the effective dark/light decision for the current frame. */
fun resolveDarkTheme(mode: ThemeMode, systemDark: Boolean): Boolean = when (mode) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
}

/**
 * Pure coercion for the persisted [ThemeMode] name. Unknown / null / missing
 * values fall back to [ThemeMode.SYSTEM] — defends against stale or
 * version-mismatched reads, mirroring the `RovaSettings.mode` coercion intent.
 */
fun themeModeFromStorage(raw: String?): ThemeMode =
    ThemeMode.entries.firstOrNull { it.name == raw } ?: ThemeMode.SYSTEM
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.theme.ThemeModeTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/theme/ThemeMode.kt app/src/test/java/com/aritr/rova/ui/theme/ThemeModeTest.kt
git commit -m "feat(theme): ThemeMode enum + pure resolveDarkTheme/themeModeFromStorage (B2 task 1)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: RovaSettings.themeMode

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/data/RovaSettings.kt`

No JVM test (touches SharedPreferences; the coercion is already covered by `themeModeFromStorage` in Task 1).

- [ ] **Step 1: Add the property**

In `RovaSettings.kt`, add the import at the top (after the existing imports):

```kotlin
import com.aritr.rova.ui.theme.ThemeMode
import com.aritr.rova.ui.theme.themeModeFromStorage
```

Add this property next to the other `prefs`-backed properties (e.g. just after `loopCount`). It uses the **backed-up** `prefs` file (a genuine user preference — correct to survive reinstall, unlike `mode`):

```kotlin
    // B2 — app theme. Backed up (a genuine user preference, unlike `mode`).
    // Stored as the ThemeMode name; reads coerce unknown/missing to SYSTEM
    // via the pure themeModeFromStorage helper (see ThemeMode.kt).
    var themeMode: ThemeMode
        get() = themeModeFromStorage(prefs.getString("theme_mode", null))
        set(value) = prefs.edit { putString("theme_mode", value.name) }
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/aritr/rova/data/RovaSettings.kt
git commit -m "feat(theme): persist RovaSettings.themeMode in backed-up prefs (B2 task 2)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: RovaDarkSurface island wrapper

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/theme/Theme.kt`

A composable that forces the dark `colorScheme` for a subtree without touching the window bars — for surfaces that stay dark regardless of app theme (camera/player/onboarding). It reuses the existing private `DarkColorScheme` and `Typography` (same file, so no visibility change needed).

- [ ] **Step 1: Add the composable**

Append to `Theme.kt` (after `RovaTheme`):

```kotlin
/**
 * B2 — forces the dark [MaterialTheme] color scheme for a subtree, WITHOUT
 * the window-bar SideEffect that [RovaTheme] runs. Used to pin surfaces that
 * have only a dark design source (camera viewfinder, video player, onboarding)
 * to dark even when the app theme is Light, so their descendants that read
 * `colorScheme.*` get dark values consistent with their hardcoded dark
 * backgrounds (avoids light-on-dark mismatch). Status-bar polarity is owned by
 * the outer [RovaTheme] and is intentionally left untouched here.
 */
@Composable
fun RovaDarkSurface(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content,
    )
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/theme/Theme.kt
git commit -m "feat(theme): RovaDarkSurface — dark-scheme island without window side-effect (B2 task 3)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: SettingsViewModel.themeMode flow

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/SettingsViewModel.kt`

Follows the established B1 write-back collector pattern. Single writer — only `SettingsViewModel` writes the theme, so no resume-reseed is needed.

- [ ] **Step 1: Add the flow + collector**

Add the import:

```kotlin
import com.aritr.rova.ui.theme.ThemeMode
```

Add the flow next to the other `MutableStateFlow`s (after `loopCount`):

```kotlin
    // B2 — app theme mode. Single owner (only this VM writes it); the write-back
    // collector mirrors changes to RovaSettings. The theme root in MainActivity
    // collects this flow above RovaTheme so a change re-themes the whole tree live.
    val themeMode = MutableStateFlow(settings.themeMode)
```

Add the collector inside `init { ... }` (after the existing `loopCount` collector):

```kotlin
        viewModelScope.launch { themeMode.collect { settings.themeMode = it } }
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/SettingsViewModel.kt
git commit -m "feat(theme): SettingsViewModel.themeMode flow + write-back (B2 task 4)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 5: Live theme root + dark islands

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/MainActivity.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/MainScreen.kt`

Unpins the M4 hard-Dark and wires live switching. `SettingsViewModel` is hoisted to `MainActivity` (explicit owner, per codex review) and passed into `MainScreen`. Dark-only NavHost destinations are wrapped in `RovaDarkSurface`. The scaffold background becomes theme-aware; the record screen still paints its own dark surface on top.

- [ ] **Step 1: Update MainActivity.onCreate setContent**

In `MainActivity.kt`, replace the `setContent { ... }` block (lines ~20-24) with:

```kotlin
        setContent {
            // B2 — theme root. The activity-scoped SettingsViewModel is the
            // single owner of themeMode; collecting it ABOVE RovaTheme means a
            // picker change recomposes here and re-themes the whole tree with
            // no Activity recreate. The same instance is passed into MainScreen.
            val settingsViewModel: SettingsViewModel = viewModel()
            val themeMode by settingsViewModel.themeMode.collectAsStateWithLifecycle()
            val dark = resolveDarkTheme(themeMode, isSystemInDarkTheme())
            RovaTheme(darkTheme = dark) {
                MainScreen(initialTab = initialTab, settingsViewModel = settingsViewModel)
            }
        }
```

Add the imports:

```kotlin
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aritr.rova.ui.screens.SettingsViewModel
import com.aritr.rova.ui.theme.resolveDarkTheme
```

- [ ] **Step 2: Update MainScreen signature + ownership**

In `MainScreen.kt`, change the function signature to accept the hoisted VM, and delete the local creation. Replace:

```kotlin
fun MainScreen(initialTab: InitialTab = InitialTab.DEFAULT) {
```
with:
```kotlin
fun MainScreen(
    initialTab: InitialTab = InitialTab.DEFAULT,
    settingsViewModel: SettingsViewModel = viewModel(),
) {
```

Delete the now-redundant local creation line (around line 53):

```kotlin
    val settingsViewModel: SettingsViewModel = viewModel()
```

(The `import androidx.lifecycle.viewmodel.compose.viewModel` stays — it backs the default param.)

- [ ] **Step 3: Theme-aware scaffold background + dark-island wraps**

In `MainScreen.kt`, change the Scaffold container color:

```kotlin
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { _ ->
```

Add imports:

```kotlin
import androidx.compose.material3.MaterialTheme
import com.aritr.rova.ui.theme.RovaDarkSurface
```

Remove the now-unused `import androidx.compose.ui.graphics.Color` only if no other usage remains (verify; if `Color` is used elsewhere in the file, keep it).

Wrap the three dark-only destinations. The `onboarding` composable body becomes:

```kotlin
            composable("onboarding") {
                RovaDarkSurface {
                    OnboardingScreen(
                        onCompleted = {
                            navController.navigate("record") {
                                popUpTo("onboarding") { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    )
                }
            }
```

The `record` composable body wraps the `RecordScreen(...)` call:

```kotlin
            composable("record") {
                val toHistory: () -> Unit = {
                    navController.navigate("history") {
                        popUpTo(navController.graph.startDestinationId)
                        launchSingleTop = true
                    }
                }
                RovaDarkSurface {
                    RecordScreen(
                        onMergeFinished = toHistory,
                        onNavigateToHistory = toHistory,
                        onNavigateToSettings = {
                            navController.navigate("settings") { launchSingleTop = true }
                        },
                        settingsViewModel = settingsViewModel
                    )
                }
            }
```

The `player/...` composable wraps the `PlayerScreen(...)` call:

```kotlin
                RovaDarkSurface {
                    PlayerScreen(
                        sessionId = sessionId,
                        side = side,
                        onBack = { navController.popBackStack() }
                    )
                }
```

(The `history` and `settings` destinations are NOT wrapped — they flip to light.)

- [ ] **Step 4: Verify it compiles**

Run: `./gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/MainActivity.kt app/src/main/java/com/aritr/rova/ui/MainScreen.kt
git commit -m "feat(theme): live theme root in MainActivity + dark islands for record/player/onboarding (B2 task 5)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 6: Theme-aware quiet-text helper + contrast guard + apply in Settings

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/theme/QuietText.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/theme/QuietTextTest.kt`
- Modify: `app/src/test/java/com/aritr/rova/ui/theme/TokenContrastTest.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/SettingsScreen.kt`

**Why:** the dark scheme dims secondary text via `onSurfaceVariant.copy(alpha = 0.55f/0.45f)`. Over a light background that is mathematically below AA (measured 2.31:1 / 1.94:1 — at 0.45α even pure black is only 3:1). On light we use **solid** `onSurfaceVariant` (Ink80 over Sand30 = 5.66:1, PASS); on dark we keep the dimmed look unchanged.

- [ ] **Step 1: Write the failing pure-helper test**

```kotlin
package com.aritr.rova.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class QuietTextTest {

    private val onSurfaceVariant = Color(0xFF4C6175) // Ink80

    @Test
    fun `dark keeps the dimmed alpha`() {
        val c = quietTextColor(isDark = true, onSurfaceVariant = onSurfaceVariant, dimAlpha = 0.55f)
        assertEquals(0.55f, c.alpha, 0.001f)
    }

    @Test
    fun `light returns the solid color`() {
        val c = quietTextColor(isDark = false, onSurfaceVariant = onSurfaceVariant, dimAlpha = 0.55f)
        assertEquals(1.0f, c.alpha, 0.001f)
        assertEquals(onSurfaceVariant, c)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.theme.QuietTextTest"`
Expected: FAIL — unresolved reference `quietTextColor`.

- [ ] **Step 3: Implement the helper**

Create `QuietText.kt`:

```kotlin
package com.aritr.rova.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * B2 — pure decision for "quiet" (secondary / label) text color.
 *
 * On a DARK scheme the app dims `onSurfaceVariant` with a low alpha for the
 * intentionally-quiet aesthetic. That alpha-dimming is mathematically below
 * WCAG 2.2 AA (SC 1.4.3) over a LIGHT background (at 0.45α even pure black is
 * only 3:1), so on light we return the solid color, which passes AA. See
 * docs/superpowers/specs/2026-05-31-b2-theme-switcher-design.md §5 + ADR-0020.
 */
fun quietTextColor(isDark: Boolean, onSurfaceVariant: Color, dimAlpha: Float): Color =
    if (isDark) onSurfaceVariant.copy(alpha = dimAlpha) else onSurfaceVariant

/**
 * Composable wrapper: reads the active scheme and routes [quietTextColor].
 * "Dark" is detected from the surface luminance so it also resolves correctly
 * inside a [RovaDarkSurface] island, not only under the top-level [RovaTheme].
 */
@Composable
fun rovaQuietText(dimAlpha: Float): Color {
    val cs = MaterialTheme.colorScheme
    return quietTextColor(
        isDark = cs.surface.luminance() < 0.5f,
        onSurfaceVariant = cs.onSurfaceVariant,
        dimAlpha = dimAlpha,
    )
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.theme.QuietTextTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Add the light-scheme contrast guard**

Append to `TokenContrastTest.kt` (inside the class). It uses the existing `ContrastMath` API (`relativeLuminance`, `contrastRatio`) and the public palette constants from `Color.kt`:

```kotlin
    @Test
    fun `light quiet text (solid onSurfaceVariant) meets AA over the light background`() {
        // B2: on light, rovaQuietText returns SOLID onSurfaceVariant (Ink80)
        // over the light background (Sand30). Regression guard for the AA fix.
        val fg = ContrastMath.relativeLuminance(0x4C, 0x61, 0x75) // Ink80
        val bg = ContrastMath.relativeLuminance(0xF6, 0xF0, 0xE7) // Sand30
        val r = ContrastMath.contrastRatio(fg, bg)
        assertTrue("light quiet text must meet 4.5:1 (was ${"%.2f".format(r)}:1)", r >= 4.5)
    }

    @Test
    fun `light primary value text meets AA over the light background`() {
        // SettingsRow value text = primary (Harbor40) at 0.85α over Sand30.
        val r = ContrastMath.contrastRatioForAlpha(0x29, 0x51, 0x6E, 0.85, 0xF6, 0xF0, 0xE7)
        assertTrue("light value text must meet 4.5:1 (was ${"%.2f".format(r)}:1)", r >= 4.5)
    }
```

If `ContrastMath.contrastRatioForAlpha` takes foreground rgb as the white-only `(255,255,255,...)` shape seen in the existing tests, instead compute the value-text ratio with the composite helper that accepts an arbitrary foreground. Verify the signature in `ContrastMath.kt` first; the existing test calls `contrastRatioForAlpha(255, 255, 255, alpha, bg…)`. The function signature is `contrastRatioForAlpha(fr, fg, fb, alpha, br, bg, bb)`, so passing `0x29, 0x51, 0x6E` as the foreground is correct.

- [ ] **Step 6: Run the contrast test**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.theme.TokenContrastTest"`
Expected: PASS (all, including the 2 new light assertions).

- [ ] **Step 7: Apply the helper in SettingsScreen**

In `SettingsScreen.kt`, add the import:

```kotlin
import com.aritr.rova.ui.theme.rovaQuietText
```

In `SettingsSection`, replace the section-label color:

```kotlin
            color = rovaQuietText(dimAlpha = 0.45f),
```
(was `MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)`)

In `SettingsRow`, replace the supporting-text color:

```kotlin
                    color = rovaQuietText(dimAlpha = 0.55f)
```
(was `MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)` on the `supporting` Text)

Leave the icon tint (`onSurfaceVariant.copy(alpha = 0.7f)`), chevron (`0.35f`), and divider (`onSurface.copy(alpha = settingsRowDividerAlpha)`) UNCHANGED — those are decorative (icons/dividers), exempt from SC 1.4.3, and dimming them on light is acceptable. The value text (`primary.copy(alpha = 0.85f)`) and labels already pass on light (Step 5) — leave them.

- [ ] **Step 8: Verify compile + targeted tests**

Run: `./gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest --tests "com.aritr.rova.ui.theme.*"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/theme/QuietText.kt app/src/test/java/com/aritr/rova/ui/theme/QuietTextTest.kt app/src/test/java/com/aritr/rova/ui/theme/TokenContrastTest.kt app/src/main/java/com/aritr/rova/ui/screens/SettingsScreen.kt
git commit -m "feat(theme): theme-aware quiet-text helper for light AA + apply in Settings (B2 task 6)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 7: HistoryScreen light AA sweep

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/HistoryScreen.kt`

HistoryScreen flips to light and is the other major in-scope chrome surface. Route its **dimmed secondary/label text** through `rovaQuietText` so it passes AA on light, identical to Settings.

- [ ] **Step 1: Find the dimmed text sites**

Run: `git grep -n "onSurfaceVariant.copy(alpha" app/src/main/java/com/aritr/rova/ui/screens/HistoryScreen.kt`
Also: `git grep -n "onSurface.copy(alpha" app/src/main/java/com/aritr/rova/ui/screens/HistoryScreen.kt`

For EACH match, classify:
- **Text** (a `Text(...)` `color = ...`) used as secondary/caption/label → route through `rovaQuietText(dimAlpha = <the existing alpha>)`.
- **Non-text** (icon `tint`, `HorizontalDivider` color, `Surface`/`Box` `.background`, scrim over a media thumbnail) → leave UNCHANGED (decorative / over-media, exempt).

- [ ] **Step 2: Add the import + apply**

Add:
```kotlin
import com.aritr.rova.ui.theme.rovaQuietText
```
Replace each qualifying **text** color `MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = X)` with `rovaQuietText(dimAlpha = X)`. (If a secondary text uses `onSurface.copy(alpha = X)` rather than `onSurfaceVariant`, still route it through `rovaQuietText(dimAlpha = X)` — the helper's light branch returns a solid AA-passing color either way; the dark branch keeps the same visual.)

The 2 known media-overlay sites (`Color.Black.copy(alpha = 0.7f)` ~L658 and `Color.White` ~L667) sit over video thumbnails — leave UNCHANGED.

- [ ] **Step 3: Verify compile**

Run: `./gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/HistoryScreen.kt
git commit -m "fix(theme): route HistoryScreen secondary text through rovaQuietText for light AA (B2 task 7)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 8: Appearance section + theme picker

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/SettingsScreen.kt`

A new "Appearance" `SettingsSection` with one row that opens a `SettingsOptionSheet<ThemeMode>` (reusing the B1 radio sheet). Picking a mode sets `settingsViewModel.themeMode.value`, applying live.

- [ ] **Step 1: Add imports**

```kotlin
import androidx.compose.material.icons.filled.DarkMode
import com.aritr.rova.ui.theme.ThemeMode
```

- [ ] **Step 2: Collect the flow + add picker state**

Near the other `collectAsStateWithLifecycle()` calls (after `loopCount`):

```kotlin
    val themeMode by settingsViewModel.themeMode.collectAsStateWithLifecycle()
```

Near `var openSheet by remember { ... }` (after it):

```kotlin
    var openThemeSheet by remember { mutableStateOf(false) }
```

- [ ] **Step 3: Add a label mapper (file-private)**

Add near the bottom of the file, next to the `RecordingDefaultSheet` enum:

```kotlin
private fun themeModeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> "Follow system"
    ThemeMode.DARK -> "Dark"
    ThemeMode.LIGHT -> "Light"
}
```

- [ ] **Step 4: Add the Appearance section**

Insert this `SettingsSection` as the FIRST section inside the scrolling `Column`, immediately after `SettingsPermissionsSection(...)` and before `SettingsSection(label = "Recording defaults")`:

```kotlin
            SettingsSection(label = "Appearance") {
                SettingsRow(
                    icon = Icons.Default.DarkMode,
                    label = "Theme",
                    supporting = "Light, dark, or match your system setting.",
                    value = themeModeLabel(themeMode),
                    onClick = { openThemeSheet = true },
                    trailing = { ChevronTrailing() },
                )
            }
```

- [ ] **Step 5: Add the sheet host**

After the `when (openSheet) { ... }` block (near the end of the composable body), add:

```kotlin
    if (openThemeSheet) {
        SettingsOptionSheet(
            title = "Theme",
            options = ThemeMode.entries,
            selected = themeMode,
            optionLabel = { themeModeLabel(it) },
            onPick = { settingsViewModel.themeMode.value = it },
            onDismiss = { openThemeSheet = false },
        )
    }
```

Note: `ThemeMode.entries` returns the enum order SYSTEM, DARK, LIGHT — the intended display order.

- [ ] **Step 6: Verify compile**

Run: `./gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/SettingsScreen.kt
git commit -m "feat(theme): Appearance section + Light/Dark/System picker (B2 task 8)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 9: CHANGELOG + full gate

**Files:**
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Add the Unreleased entry**

Under `## [Unreleased]` → `### Added` in `CHANGELOG.md`, add:

```markdown
- **Theme switcher (Light / Dark / System).** App Settings → Appearance lets you
  pick the theme; it applies live with no restart and defaults to following the
  system setting. The camera viewfinder, video player, and onboarding stay dark
  by design. Secondary text uses a theme-aware color so the light theme meets
  WCAG 2.2 AA (ADR-0020). Spec: docs/superpowers/specs/2026-05-31-b2-theme-switcher-design.md.
```

- [ ] **Step 2: Run the full gate**

Run: `./gradlew.bat :app:testDebugUnitTest :app:lintDebug --no-daemon`
Expected: BUILD SUCCESSFUL — all JVM unit tests pass and all 25 custom `check*` tasks pass. (B2 touches no ADR-guarded invariant; the checks should stay green.)

- [ ] **Step 3: Commit**

```bash
git add CHANGELOG.md
git commit -m "docs(theme): CHANGELOG for B2 theme switcher (B2 task 9)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

- [ ] **Step 4: Manual device verification (mandatory per CLAUDE.md)**

On a real device (or emulator — the theme paths exercise no CameraX recording):
1. Fresh state → app follows system theme.
2. Settings → Appearance → Theme → pick **Light**: Settings + History flip to light immediately; status-bar icons turn dark; **record home, player, and onboarding stay dark**.
3. Pick **Dark**: everything dark, unchanged from today's look.
4. Pick **Follow system**, toggle the OS theme: app follows.
5. Kill + relaunch: the chosen mode persists.
6. In Light, confirm Settings section labels + supporting text are legible (AA).

---

## Self-Review

**Spec coverage:**
- §4.1 ThemeMode pref → Tasks 1, 2. ✓
- §4.2 resolveDarkTheme seam → Task 1. ✓
- §4.3 SettingsViewModel flow → Task 4. ✓
- §4.4 live theme root, hoisted VM, MainScreen param → Task 5. ✓
- §4.5 RovaDarkSurface islands (record/player/onboarding) + scaffold color → Tasks 3, 5. ✓
- §4.6 picker reuse SettingsOptionSheet → Task 8. ✓
- §5 WCAG light AA (theme-aware quiet text) → Tasks 6, 7. ✓
- §8 testing (pure tests, contrast, full gate, device) → Tasks 1, 6, 9. ✓

**Placeholder scan:** No TBD/TODO. Task 7 uses a grep-then-classify rule (concrete rule, not a placeholder) because History's exact dimmed-text sites must be read in-file; the classification rule and replacement are fully specified.

**Type consistency:** `ThemeMode` (SYSTEM/DARK/LIGHT), `resolveDarkTheme(mode, systemDark)`, `themeModeFromStorage(raw)`, `quietTextColor(isDark, onSurfaceVariant, dimAlpha)`, `rovaQuietText(dimAlpha)`, `RovaDarkSurface(content)`, `themeModeLabel(mode)`, `settingsViewModel.themeMode` — names consistent across all tasks. `SettingsOptionSheet<T>(title, options, selected, optionLabel, onPick, onDismiss)` matches the B1 signature. `MainScreen(initialTab, settingsViewModel)` matches the Task 5 call in MainActivity.

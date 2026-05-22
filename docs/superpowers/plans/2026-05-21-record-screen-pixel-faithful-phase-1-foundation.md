# Record Screen Pixel-Faithful Re-skin — Phase 1 (Foundation) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Establish the Inter typeface and a mockup-exact token set for the record-screen re-skin, with zero composable edits.

**Architecture:** Inter ships as a downloadable `FontFamily` via the AndroidX Google Fonts provider. `Typography` (`Type.kt`) is rewired to Inter. `RovaTokens` gains the mockup type scale; a new `RecordChromeTokens` object holds the mockup's colour/alpha/dimension constants. Nothing consumes the new tokens yet — Phase 2 does. Phase 1 changes no behaviour.

**Tech Stack:** Kotlin · Jetpack Compose (BOM `2025.01.01`) · `androidx.compose.ui:ui-text-google-fonts` · Gradle version catalog.

**Spec:** `docs/superpowers/specs/2026-05-21-record-screen-pixel-faithful-phase-1-foundation-design.md`

---

## Testing Policy — Read First

Phase 1 is **declarative theme data** — font wiring, `TextStyle` constants, `Color`/`Dp` constants. There is no logic, branching, or state. There is nothing a unit test could meaningfully assert that is not already enforced by the Kotlin compiler (a `TextStyle` literal either compiles or it does not).

**Therefore: no new unit tests in Phase 1.** This is deliberate, matches the spec §3, and follows the precedent of the dualrecord GL layer (declarative/runtime code that the JVM test layer cannot exercise).

Verification is **build + lint + the existing suite staying green**:
- `:app:assembleDebug` — proves the new dependency resolves and every new/edited file compiles.
- `:app:testDebugUnitTest` — the existing suite must stay **byte-identical green** (no test added, removed, or changed).
- `:app:lintDebug` — issue count must not rise vs baseline.

**Gradle is subagent-routed** — the implementer subagent runs `.\gradlew.bat` directly; the controller does not.

---

## Branch & Baseline

- **Branch:** `feat/record-skin-phase-1-foundation`, cut from `master` @ `674d1a6` (the Phase 1 spec commit).
- **Baseline @ `674d1a6`** (post-B3 master):
  - `:app:testDebugUnitTest` — **1026 tests / 82 classes / 0 failures / 0 errors / 0 skipped**
  - `:app:lintDebug` — **53 issues (50 W + 3 H + 0 E)**
  - `:app:assembleDebug` — BUILD SUCCESSFUL
- **Predicted after Phase 1:** test/lint counts **unchanged** (no behaviour, no new lint-eligible code); `assembleDebug` OK.

**Diff allowlist — `git diff master..HEAD --name-only` must equal exactly these 8 paths:**
```
app/build.gradle
app/src/main/java/com/aritr/rova/ui/theme/Font.kt
app/src/main/java/com/aritr/rova/ui/theme/RecordChromeTokens.kt
app/src/main/java/com/aritr/rova/ui/theme/RovaTokens.kt
app/src/main/java/com/aritr/rova/ui/theme/Type.kt
app/src/main/res/values/font_certs.xml
docs/UI_DESIGN_TOKENS.md
gradle/libs.versions.toml
```

---

## File Structure

| File | Action | Responsibility |
|---|---|---|
| `gradle/libs.versions.toml` | Modify | Catalog entry for `ui-text-google-fonts`. |
| `app/build.gradle` | Modify | `implementation` of the google-fonts library. |
| `app/src/main/res/values/font_certs.xml` | Create | Play-services font-provider certificate arrays. |
| `app/src/main/java/com/aritr/rova/ui/theme/Font.kt` | Create | `Inter` downloadable `FontFamily`. |
| `app/src/main/java/com/aritr/rova/ui/theme/Type.kt` | Modify | `Typography` slots → Inter; drop resolved TODOs. |
| `app/src/main/java/com/aritr/rova/ui/theme/RovaTokens.kt` | Modify | +7 mockup type styles; all type styles → Inter. |
| `app/src/main/java/com/aritr/rova/ui/theme/RecordChromeTokens.kt` | Create | Mockup colour/alpha/dimension constants. |
| `docs/UI_DESIGN_TOKENS.md` | Modify | Type table + record-chrome section + Inter decision. |

---

## Task 1: Inter font infrastructure

The catalog entry, the Gradle dependency, `font_certs.xml`, and `Font.kt` are **one atomic task** — `Font.kt` will not compile without the dependency, and its `R.array.com_google_android_gms_fonts_certs` reference will not resolve without `font_certs.xml`. They land together or `assembleDebug` fails.

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle`
- Create: `app/src/main/res/values/font_certs.xml`
- Create: `app/src/main/java/com/aritr/rova/ui/theme/Font.kt`

- [ ] **Step 1: Add the version-catalog entry**

In `gradle/libs.versions.toml`, under `[libraries]`, add (alphabetical, after the `androidx-compose-material3` line):

```toml
androidx-compose-ui-text-google-fonts = { group = "androidx.compose.ui", name = "ui-text-google-fonts" }
```

No `[versions]` entry — the version is governed by the existing `androidx-compose-bom`.

- [ ] **Step 2: Add the Gradle dependency**

In `app/build.gradle`, immediately after the line
`implementation("androidx.compose.material:material-icons-extended")`, add:

```groovy
    implementation(libs.androidx.compose.ui.text.google.fonts)
```

- [ ] **Step 3: Create `font_certs.xml`**

Create `app/src/main/res/values/font_certs.xml`. Its content is the **standard, published Play-services font-provider certificate set** — a fixed constant, identical across every Android downloadable-fonts integration. **Do NOT hand-transcribe the certificate blobs.** Copy the file verbatim from the official source:

> Android Developers — *"Downloadable fonts"* / *"Add fonts as XML resources"*:
> https://developer.android.com/develop/ui/views/text-and-emoji/downloadable-fonts#adding-certificates
> (identical to the `font_certs.xml` in the AndroidX `compose-samples` and the
> Downloadable Fonts sample.)

The file declares one `array` (`com_google_android_gms_fonts_certs`) referencing two `string-array`s (`..._dev`, `..._prod`). Structure:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <array name="com_google_android_gms_fonts_certs">
        <item>@array/com_google_android_gms_fonts_certs_dev</item>
        <item>@array/com_google_android_gms_fonts_certs_prod</item>
    </array>
    <string-array name="com_google_android_gms_fonts_certs_dev">
        <item><!-- dev cert base64 — verbatim from the official source --></item>
    </string-array>
    <string-array name="com_google_android_gms_fonts_certs_prod">
        <item><!-- prod cert base64 — verbatim from the official source --></item>
    </string-array>
</resources>
```

The base64 blobs must be byte-exact — a wrong cert makes the provider silently reject the font and Inter never loads (the `SansSerif` fallback would mask it). If web access is unavailable, escalate (`NEEDS_CONTEXT`) rather than guessing the blobs.

- [ ] **Step 4: Create `Font.kt`**

Create `app/src/main/java/com/aritr/rova/ui/theme/Font.kt`:

```kotlin
package com.aritr.rova.ui.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import com.aritr.rova.R

/**
 * Inter — the v1.0.0 record-screen typeface, per the
 * `mockups/new_uiux/*.html` reference pages. Delivered as a downloadable
 * Google Fonts family (no bundled .ttf, no APK cost). Weights 300/400/500/600
 * are every weight the mockup CSS uses; FontFamily.SansSerif (Roboto) is the
 * platform fallback while the first cold-launch fetch resolves.
 */
private val provider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

private val interGoogleFont = GoogleFont("Inter")

val Inter: FontFamily = FontFamily(
    Font(googleFont = interGoogleFont, fontProvider = provider, weight = FontWeight.Light),    // 300
    Font(googleFont = interGoogleFont, fontProvider = provider, weight = FontWeight.Normal),   // 400
    Font(googleFont = interGoogleFont, fontProvider = provider, weight = FontWeight.Medium),   // 500
    Font(googleFont = interGoogleFont, fontProvider = provider, weight = FontWeight.SemiBold), // 600
)
```

- [ ] **Step 5: Compile-gate**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. (Proves the dependency resolves, `font_certs.xml` is a valid resource, and `Font.kt` compiles with the `R.array` reference.)

- [ ] **Step 6: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle app/src/main/res/values/font_certs.xml app/src/main/java/com/aritr/rova/ui/theme/Font.kt
git commit -m "feat(ui): add Inter downloadable font (Google Fonts provider)"
```

---

## Task 2: Rewire Typography to Inter

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/theme/Type.kt`

- [ ] **Step 1: Swap the typeface in every `Typography` slot**

In `Type.kt`, the `Typography(...)` block has 11 slots — `displayMedium`, `headlineSmall`, `titleLarge`, `titleMedium`, `titleSmall`, `bodyLarge`, `bodyMedium`, `bodySmall`, `labelLarge`, `labelMedium`, `labelSmall`. Each currently has `fontFamily = FontFamily.SansSerif`. Change every one to:

```kotlin
        fontFamily = Inter,
```

`Inter` is in the same package (`com.aritr.rova.ui.theme`) — no import needed. Do **not** touch any `fontSize` / `fontWeight` / `lineHeight` / `letterSpacing` — only the family moves.

Leave `NumericMonoLarge` and `NumericMonoMedium` **unchanged** — they keep `FontFamily.Monospace` (deliberate tabular timer faces; the mockup's timers are monospace too).

- [ ] **Step 2: Delete the resolved TODO comments**

Remove the 4 comment lines that read `// TODO: bundle Inter font asset (docs/UI_DESIGN_TOKENS.md §2.2...)` — they sit inside `displayMedium`, `headlineSmall`, `titleLarge`, and in the `RovaTokens` KDoc (the `RovaTokens` one is handled in Task 3). In `Type.kt` there are 3: inside `displayMedium` (a 3-line comment), `headlineSmall` (1 line), `titleLarge` (1 line). Delete the comment text only; keep the slot bodies.

- [ ] **Step 3: Compile-gate**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/theme/Type.kt
git commit -m "feat(ui): rewire Typography slots to Inter"
```

---

## Task 3: Expand RovaTokens with the mockup type scale

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/theme/RovaTokens.kt`

- [ ] **Step 1: Point existing type styles at Inter**

In `RovaTokens.kt`, the 4 existing `TextStyle`s — `eyebrow`, `statusPillLabel`, `cellValue`, `cellKey` — each set `fontFamily = FontFamily.SansSerif`. Change each to `fontFamily = Inter`.

Remove the `import androidx.compose.ui.text.font.FontFamily` line **only if** no `FontFamily.*` reference remains after the swap (the 7 new styles below also use `Inter`, not `FontFamily.*`) — verify and drop the now-unused import.

- [ ] **Step 2: Update the `RovaTokens` KDoc**

Delete the `// TODO: bundle Inter font asset (docs/UI_DESIGN_TOKENS.md §2.2 + §5.5).` line in the `RovaTokens` KDoc/comment block (the TODO is resolved by Task 1).

- [ ] **Step 3: Add the 7 mockup type styles**

Inside `object RovaTokens`, after the existing `cellKey` style, add the 7 styles below. Values are mapped 1 px → 1 sp from `mockups/new_uiux/01-record-home.html`. ALL-CAPS styles (`loopUnit`, `swipeLabel`, `zoneTag`) are upper-cased at the call site (Phase 2) — the style carries font/weight/spacing only.

```kotlin
    // Phase 1 — mockup-exact record-chrome type scale (mockups/new_uiux/01-record-home.html).
    // 1 px → 1 sp. ALL-CAPS labels are .uppercase()'d at the call site.

    /** `.loop-count` — the big "4/10" numeral in the loop pill. */
    val loopCount: TextStyle = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        letterSpacing = (-0.6).sp,
        fontFeatureSettings = "tnum"
    )

    /** `.loop-unit` — the "LOOPS DONE" caption beside [loopCount]. */
    val loopUnit: TextStyle = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Normal,
        fontSize = 10.sp,
        letterSpacing = 1.sp
    )

    /** `.status-main` — the status-pill primary label ("Recording" / "On break"). */
    val statusMain: TextStyle = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        letterSpacing = 0.1.sp
    )

    /** `.status-time` — the status-pill trailing time ("· 0:18 left"). */
    val statusTime: TextStyle = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Light,
        fontSize = 11.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = "tnum"
    )

    /** `.swipe-label` — the "SWIPE TO EDIT" hint above the settings card. */
    val swipeLabel: TextStyle = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Normal,
        fontSize = 8.sp,
        letterSpacing = 1.4.sp
    )

    /** `.nav-txt` — the Library / Settings / Start-Stop labels in the bottom nav. */
    val navTxt: TextStyle = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Normal,
        fontSize = 9.sp,
        letterSpacing = 0.6.sp
    )

    /** `.cam-zone-tag` — the per-zone "PORTRAIT · 9:16" tag in dual mode. */
    val zoneTag: TextStyle = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Medium,
        fontSize = 7.5.sp,
        letterSpacing = 1.5.sp
    )
```

- [ ] **Step 4: Compile-gate**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/theme/RovaTokens.kt
git commit -m "feat(ui): add mockup-exact record-chrome type scale to RovaTokens"
```

---

## Task 4: Create RecordChromeTokens

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/theme/RecordChromeTokens.kt`

- [ ] **Step 1: Create the file**

Create `app/src/main/java/com/aritr/rova/ui/theme/RecordChromeTokens.kt` with the full content below. Every value is traced to a `mockups/new_uiux/01-record-home.html` CSS rule. The object is unused until Phase 2 — public `object` members are not flagged by the compiler or lint.

```kotlin
package com.aritr.rova.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Phase 1 — mockup-exact pixel constants for the record-screen re-skin
 * (`mockups/new_uiux/01-record-home.html`). Record-screen-scoped on purpose:
 * a record-only object cannot over-apply to unrelated UI (same rationale as
 * [RovaTokens]' KDoc). Phase 2/3 composables consume these; Phase 1 only
 * declares them.
 *
 * Colour tokens named `*Text` are white at the mockup's `rgba` alpha — they
 * are text-fill colours, applied directly as `color = ...`. Geometry tokens
 * are `Dp`. CSS `backdrop-filter` blur is deliberately NOT tokenised — Compose
 * has no backdrop-blur API; the semi-transparent fills are the approximation.
 */
object RecordChromeTokens {

    // ── Surface fills & strokes ──────────────────────────────────────────
    /** `.status-pill` / `.loop-pill` background — `rgba(0,0,0,0.40)`. */
    val glassFill = Color.Black.copy(alpha = 0.40f)
    /** `.status-pill` / `.loop-pill` border — `rgba(255,255,255,0.07)`. */
    val glassStroke = Color.White.copy(alpha = 0.07f)
    /** `.cam-ctrl-btn` background — `rgba(0,0,0,0.38)`. */
    val camControlFill = Color.Black.copy(alpha = 0.38f)
    /** `.cam-ctrl-btn` border — `rgba(255,255,255,0.09)`. */
    val camControlStroke = Color.White.copy(alpha = 0.09f)
    /** `.settings-card` background — `rgba(255,255,255,0.065)`. */
    val settingsCardFill = Color.White.copy(alpha = 0.065f)
    /** `.settings-card` border — `rgba(255,255,255,0.09)`. */
    val settingsCardStroke = Color.White.copy(alpha = 0.09f)
    /** `.s-cell + .s-cell` divider — `rgba(255,255,255,0.07)`. */
    val cellDivider = Color.White.copy(alpha = 0.07f)
    /** `.bottom-nav` background — `rgba(0,0,0,0.50)`. */
    val bottomNavFill = Color.Black.copy(alpha = 0.50f)
    /** `.bottom-nav` top border — `rgba(255,255,255,0.055)`. */
    val bottomNavTopStroke = Color.White.copy(alpha = 0.055f)

    // ── Status dots ──────────────────────────────────────────────────────
    /** `.dot-idle` — `rgba(255,255,255,0.25)`. */
    val dotIdle = Color.White.copy(alpha = 0.25f)
    /** `.dot-recording` — `#ef4444`. */
    val dotRecording = Color(0xFFEF4444)
    /** `.dot-break` — `#94a3b8` (slate; corrects today's amber). */
    val dotBreak = Color(0xFF94A3B8)

    // ── FAB (`.center-btn`) ──────────────────────────────────────────────
    /** `.btn-start` background — `rgba(255,255,255,0.07)`. */
    val fabStartFill = Color.White.copy(alpha = 0.07f)
    /** `.btn-start` border — `rgba(255,255,255,0.15)`. */
    val fabStartStroke = Color.White.copy(alpha = 0.15f)
    /** `.btn-stop` background — `rgba(239,68,68,0.12)`. */
    val fabStopFill = Color(0xFFEF4444).copy(alpha = 0.12f)
    /** `.btn-stop` border — `rgba(239,68,68,0.30)`. */
    val fabStopStroke = Color(0xFFEF4444).copy(alpha = 0.30f)
    /** `.btn-stop::after` outer ring — `rgba(239,68,68,0.10)`. */
    val fabStopRing = Color(0xFFEF4444).copy(alpha = 0.10f)
    /** `.stop-sq` — `#ef4444`. */
    val stopSquare = Color(0xFFEF4444)

    // ── Camera-zone framing (dual mode) ──────────────────────────────────
    /** `.cam-split-divider` — `rgba(255,255,255,0.14)`. */
    val splitDivider = Color.White.copy(alpha = 0.14f)
    /** `.cam-zone` background — `#060d18`. */
    val camZoneBackground = Color(0xFF060D18)
    /** `.camera-grid` line — `rgba(255,255,255,0.018)`. */
    val cameraGridLine = Color.White.copy(alpha = 0.018f)
    /** `.focus-frame` bracket — `rgba(255,255,255,0.8)` × `opacity:0.25` = 0.20. */
    val focusFrameStroke = Color.White.copy(alpha = 0.20f)

    // ── Text-fill colours (white at the mockup alpha) ────────────────────
    /** `.loop-count` — `rgba(255,255,255,0.93)`. */
    val loopCountText = Color.White.copy(alpha = 0.93f)
    /** `.loop-unit` — `rgba(255,255,255,0.32)`. */
    val loopUnitText = Color.White.copy(alpha = 0.32f)
    /** `.status-main` — `rgba(255,255,255,0.65)`. */
    val statusMainText = Color.White.copy(alpha = 0.65f)
    /** `.status-time` — `rgba(255,255,255,0.32)`. */
    val statusTimeText = Color.White.copy(alpha = 0.32f)
    /** `.s-val` — `rgba(255,255,255,0.88)`. */
    val cellValueText = Color.White.copy(alpha = 0.88f)
    /** `.s-key` — `rgba(255,255,255,0.28)`. */
    val cellKeyText = Color.White.copy(alpha = 0.28f)
    /** Read-only Mode cell value — existing 0.50 alpha, kept. */
    val cellValueReadOnlyText = Color.White.copy(alpha = 0.50f)
    /** `.settings-arrow` — `rgba(255,255,255,0.18)`. */
    val settingsArrow = Color.White.copy(alpha = 0.18f)
    /** `.swipe-hint` container opacity — `0.22`. */
    val swipeHint = Color.White.copy(alpha = 0.22f)
    /** `.nav-ico` glyph — `rgba(255,255,255,0.35)`. */
    val navIcon = Color.White.copy(alpha = 0.35f)
    /** `.nav-txt` — `rgba(255,255,255,0.30)`. */
    val navText = Color.White.copy(alpha = 0.30f)
    /** `.cam-zone-tag` — `rgba(255,255,255,0.32)`. */
    val zoneTagText = Color.White.copy(alpha = 0.32f)

    // ── Pills ────────────────────────────────────────────────────────────
    /** `.status-pill` corner radius. */
    val statusPillRadius = 20.dp
    /** `.loop-pill` corner radius. */
    val loopPillRadius = 11.dp
    /** `.status-pill` padding — `6px 11px`. */
    val statusPillPaddingH = 11.dp
    val statusPillPaddingV = 6.dp
    /** `.loop-pill` padding — `8px 13px`. */
    val loopPillPaddingH = 13.dp
    val loopPillPaddingV = 8.dp
    /** `.status-pill` inner gap. */
    val pillContentGap = 7.dp
    /** `.loop-pill` inner gap. */
    val loopPillContentGap = 6.dp
    /** `.top-overlay` vertical gap between loop pill and status pill. */
    val topOverlayGap = 8.dp
    /** `.dot` diameter. */
    val dotSize = 6.dp

    // ── Camera controls ──────────────────────────────────────────────────
    /** `.cam-ctrl-btn` diameter. */
    val camControlSize = 30.dp
    /** `.cam-controls` vertical gap. */
    val camControlGap = 7.dp

    // ── Settings card ────────────────────────────────────────────────────
    /** `.settings-card` corner radius. */
    val settingsCardRadius = 14.dp
    /** `.settings-card` padding — `7px 12px`. */
    val settingsCardPaddingH = 12.dp
    val settingsCardPaddingV = 7.dp
    /** `.s-cell` horizontal padding. */
    val settingsCellPaddingH = 3.dp
    /** `.settings-wrap` vertical gap. */
    val settingsWrapGap = 7.dp
    /** `.settings-wrap` bottom offset. */
    val settingsCardBottomInset = 110.dp
    /** `.swipe-bar` dimensions. */
    val swipeBarWidth = 30.dp
    val swipeBarHeight = 2.dp

    // ── Bottom nav ───────────────────────────────────────────────────────
    /** `.bottom-nav` height. */
    val bottomNavHeight = 106.dp
    /** `.bottom-nav` horizontal padding. */
    val bottomNavPaddingH = 28.dp
    /** `.bottom-nav` bottom padding. */
    val bottomNavPaddingBottom = 18.dp
    /** `.nav-item` / `.center-btn-wrap` inner gap. */
    val navItemGap = 5.dp
    /** `.nav-ico` rounded container size. */
    val navIconBoxSize = 42.dp
    /** Inner `<svg>` glyph size within `.nav-ico`. */
    val navIconGlyphSize = 20.dp
    /** `.nav-ico` corner radius. */
    val navIconCornerRadius = 12.dp
    /** `.center-btn` diameter. */
    val fabSize = 56.dp
    /** `.btn-stop::after` ring inset (negative in CSS — extends outward). */
    val fabStopRingInset = 5.dp
    /** `.stop-sq` dimensions. */
    val stopSquareSize = 18.dp
    val stopSquareRadius = 4.dp

    // ── Shared geometry ──────────────────────────────────────────────────
    /** `.top-overlay` / `.cam-controls` / `.settings-wrap` edge margin. */
    val screenEdgeMargin = 16.dp
    /** `.cam-split-divider` height. */
    val splitDividerHeight = 2.dp
    /** `.cam-zone-tag` offsets. */
    val zoneTagPaddingEnd = 13.dp
    val zoneTagPaddingBottom = 9.dp
    /** `.focus-frame` size. */
    val focusFrameSize = 60.dp
}
```

> `sp` is imported because Phase 2 may add `sp`-typed tokens here; if the
> implementer's IDE flags the `sp` import as unused after this file (no `.sp`
> literal above — all geometry is `.dp`), **drop the `import ...sp` line**.
> Keep the file warning-clean.

- [ ] **Step 2: Compile-gate**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/theme/RecordChromeTokens.kt
git commit -m "feat(ui): add RecordChromeTokens — mockup-exact pixel constants"
```

---

## Task 5: Update UI_DESIGN_TOKENS.md

**Files:**
- Modify: `docs/UI_DESIGN_TOKENS.md`

- [ ] **Step 1: Read the doc**

Read `docs/UI_DESIGN_TOKENS.md` in full. Locate §2.2 (typography) and §5.5 if referenced by the deleted TODOs.

- [ ] **Step 2: Update §2.2 — typography**

- State that the M3 `Typography` slots and the `RovaTokens` type styles now use **Inter** (downloadable, Google Fonts provider) — the "bundle Inter" TODO is **resolved**.
- Replace/extend the type table with the mockup-exact `RovaTokens` scale: `eyebrow`, `statusPillLabel`, `cellValue`, `cellKey` (existing) + `loopCount` 21/600/−0.6/tnum, `loopUnit` 10/400/+1.0, `statusMain` 11/400/+0.1, `statusTime` 11/300/0/tnum, `swipeLabel` 8/400/+1.4, `navTxt` 9/400/+0.6, `zoneTag` 7.5/500/+1.5.
- Remove any "TODO: bundle Inter font asset" wording.

- [ ] **Step 3: Add a "Record-chrome constants" section**

Add a new section documenting `RecordChromeTokens` — the colour/alpha and dimension tables from the spec §7.2. Note it is record-screen-scoped and consumed by Phase 2/3.

- [ ] **Step 4: Record the Inter decision**

Add a short decision note: **Inter via the AndroidX Google Fonts downloadable provider** — rationale (no asset/licence scope, no APK cost); trade-off (Play-services dependency + ~1-frame `SansSerif` fallback on first cold launch).

- [ ] **Step 5: Commit**

```bash
git add docs/UI_DESIGN_TOKENS.md
git commit -m "docs(ui): UI_DESIGN_TOKENS — Inter + mockup type scale + RecordChromeTokens"
```

---

## Task 6: Full-suite gate & invariant verification

**Files:** none — verification only.

- [ ] **Step 1: assembleDebug**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Unit-test suite**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: **1026 tests / 82 classes / 0 failures / 0 errors / 0 skipped** — byte-identical to the `674d1a6` baseline. Phase 1 adds, removes, and changes **zero** tests; any drift means something other than theme data was touched — investigate before proceeding.

- [ ] **Step 3: Lint**

Run: `.\gradlew.bat :app:lintDebug`
Expected: **53 issues (50 W + 3 H + 0 E)** — unchanged vs baseline. A downloadable-font setup and public token constants add no lint findings. If the count rose, read the new finding and resolve it (likely an unused import — see the Task 3/4 import notes).

- [ ] **Step 4: Diff allowlist**

Run: `git diff master..HEAD --name-only`
Expected: **exactly** the 8 paths in the "Diff allowlist" section above — no more, no fewer. Any extra path means a composable or unrelated file was touched (Phase 1 is theme-only) — revert it.

- [ ] **Step 5: Report**

Report the three gate results, the diff file list, and any deviations. No commit (verification task).

---

## Owner Follow-Up (not implementer scope)

- **On-device launch smoke** — install on the Samsung SM-A176B, launch, confirm text renders in Inter (compare a heading to the mockup), confirm no blank-text flash beyond the first cold frame. Owner's call (no emulator in the build env).
- **Phase 2** — shared-chrome pixel re-skin (`RecordChrome.kt`), consuming `RovaTokens` + `RecordChromeTokens`. Own spec → plan → PR.

---

## Self-Review

- **Spec coverage:** Inter font (Task 1) · Typography rewire (Task 2) · `RovaTokens` type scale (Task 3) · `RecordChromeTokens` (Task 4) · docs (Task 5) · verification + allowlist (Task 6). All spec §4 files covered; all spec §9 gates present.
- **Placeholders:** none. The only deferred content is `font_certs.xml`'s certificate blobs — deliberately sourced verbatim from the official AndroidX docs (a fixed published constant, not a value to invent), with an escalation path if web access is unavailable.
- **Type consistency:** token names match the spec §7 tables exactly (`loopCount`, `loopUnit`, `statusMain`, `statusTime`, `swipeLabel`, `navTxt`, `zoneTag`; `RecordChromeTokens.*`). `Inter` is the single `FontFamily` symbol referenced by Task 2/3. No symbol is used before the task that defines it (Font.kt → Type.kt → RovaTokens.kt all in dependency order).
